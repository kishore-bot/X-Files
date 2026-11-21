//! Minimal, defensive reader for OLE2 compound files (MS-CFB), the container
//! of legacy Office documents and of password-protected OOXML.
//!
//! Written in-house for the same reason as [`crate::zip`]: every limit is
//! enforced here, and the reads needed are small (root-level streams only).
//! Nothing in the file is trusted. Tables are capped by the real file size,
//! sector chains and the directory tree are checked for loops, and a declared
//! size is checked against what the file could hold before anything is
//! allocated for it. Storages below the root are never walked, because no
//! format read here keeps its main streams there.

use std::io::{Read, Seek, SeekFrom};

use document_core::{CancelToken, EngineError, ErrorKind, Limits, ReadSeek, Result};

const SIGNATURE: &[u8] = &[0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1];
const HEADER_LEN: usize = 512;
const DIRECTORY_ENTRY_LEN: usize = 128;
const MINI_SECTOR_LEN: u64 = 64;
const END_OF_CHAIN: u32 = 0xFFFF_FFFE;
const FREE_SECTOR: u32 = 0xFFFF_FFFF;
const NO_STREAM: u32 = 0xFFFF_FFFF;
const ROOT_ENTRY: u8 = 5;
const STREAM_ENTRY: u8 = 2;

/// Upper bound on a stream read whole into memory: a legacy document's main
/// stream, or an encrypted package and so its decrypted form.
pub(crate) const MAX_STREAM_BYTES: u64 = 128 * 1024 * 1024;

pub(crate) fn le16(data: &[u8], at: usize) -> Option<u16> {
    let bytes = data.get(at..at.checked_add(2)?)?;
    Some(u16::from_le_bytes([bytes[0], bytes[1]]))
}

pub(crate) fn le32(data: &[u8], at: usize) -> Option<u32> {
    let bytes = data.get(at..at.checked_add(4)?)?;
    Some(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
}

fn malformed(detail: &str) -> EngineError {
    EngineError::malformed(format!("the compound file is damaged: {detail}"))
}

#[derive(Debug, Clone)]
struct RootStream {
    name: String,
    start: u32,
    size: u64,
}

struct DirectoryEntry {
    name: String,
    kind: u8,
    left: u32,
    right: u32,
    child: u32,
    start: u32,
    size: u64,
}

pub(crate) struct CfbFile {
    reader: Box<dyn ReadSeek>,
    byte_size: u64,
    sector_size: u64,
    /// Sectors that fit in the file; the FAT is never kept longer than this.
    sector_count: usize,
    mini_cutoff: u64,
    fat: Vec<u32>,
    mini_fat: Vec<u32>,
    /// The sectors holding the mini stream, in order.
    mini_sectors: Vec<u32>,
    mini_stream_size: u64,
    streams: Vec<RootStream>,
    /// Names of root-level storages, which hold no readable stream.
    storages: Vec<String>,
    cancel: CancelToken,
}

impl CfbFile {
    pub fn open(mut reader: Box<dyn ReadSeek>, byte_size: u64, limits: Limits, cancel: CancelToken) -> Result<Self> {
        if byte_size < HEADER_LEN as u64 {
            return Err(malformed("the file is shorter than its header"));
        }
        let mut header = [0u8; HEADER_LEN];
        reader.seek(SeekFrom::Start(0))?;
        reader.read_exact(&mut header).map_err(EngineError::from)?;
        let field16 = |at| u16::from_le_bytes([header[at], header[at + 1]]);
        let field32 = |at| u32::from_le_bytes([header[at], header[at + 1], header[at + 2], header[at + 3]]);

        if &header[..8] != SIGNATURE {
            return Err(malformed("the signature is wrong"));
        }
        if field16(28) != 0xFFFE {
            return Err(malformed("the byte order mark is wrong"));
        }
        let major = field16(26);
        let sector_shift = field16(30);
        if !matches!((major, sector_shift), (3, 9) | (4, 12)) {
            return Err(malformed("the version and sector size are not valid"));
        }
        if field16(32) != 6 {
            return Err(malformed("the mini sector size is not valid"));
        }
        let sector_size = 1u64 << sector_shift;
        let sector_count = usize::try_from(byte_size.saturating_sub(sector_size).div_ceil(sector_size))
            .map_err(|_| EngineError::limit("the compound file is too large"))?;

        let mut file = Self {
            reader,
            byte_size,
            sector_size,
            sector_count,
            mini_cutoff: u64::from(field32(56)),
            fat: Vec::new(),
            mini_fat: Vec::new(),
            mini_sectors: Vec::new(),
            mini_stream_size: 0,
            streams: Vec::new(),
            storages: Vec::new(),
            cancel,
        };
        file.load_fat(&header, field32(68))?;
        let root = file.load_directory(field32(48), major, limits.max_archive_entries)?;
        file.load_mini_stream(field32(60), &root)?;
        Ok(file)
    }

    /// Whether a root-level stream or storage has this name.
    pub fn contains(&self, name: &str) -> bool {
        self.stream(name).is_some() || self.storages.iter().any(|s| s.eq_ignore_ascii_case(name))
    }

    fn stream(&self, name: &str) -> Option<&RootStream> {
        self.streams.iter().find(|s| s.name.eq_ignore_ascii_case(name))
    }

    /// Reads a whole root-level stream, which must not exceed `max_bytes`.
    pub fn read_stream(&mut self, name: &str, max_bytes: u64) -> Result<Vec<u8>> {
        let entry = self
            .stream(name)
            .cloned()
            .ok_or_else(|| EngineError::malformed(format!("the {name} stream is missing")))?;
        if entry.size > max_bytes {
            return Err(EngineError::limit(format!(
                "the {name} stream is {} bytes; the limit is {max_bytes} bytes",
                entry.size
            )));
        }
        if entry.size == 0 {
            return Ok(Vec::new());
        }
        if entry.size < self.mini_cutoff { self.read_mini(&entry) } else { self.read_regular(&entry) }
    }

    /// Reads `len` bytes at `offset` of a root-level stream, without reading
    /// the rest of a large one: a presentation's pictures, say.
    pub fn read_stream_range(&mut self, name: &str, offset: u64, len: usize) -> Result<Vec<u8>> {
        let entry = self
            .stream(name)
            .cloned()
            .ok_or_else(|| EngineError::malformed(format!("the {name} stream is missing")))?;
        let end = offset.checked_add(len as u64).filter(|&end| end <= entry.size);
        let end = end.ok_or_else(|| malformed("a read runs past the end of its stream"))?;
        if entry.size < self.mini_cutoff {
            let whole = self.read_mini(&entry)?;
            return Ok(whole[offset as usize..end as usize].to_vec());
        }
        if entry.size > self.sector_count as u64 * self.sector_size {
            return Err(malformed("a stream claims to be larger than the file"));
        }
        let needed = end.div_ceil(self.sector_size) as usize;
        let sectors = chain(&self.fat, entry.start, needed)?;
        if sectors.len() < needed {
            return Err(malformed("a stream ends before its declared size"));
        }
        let mut bytes = vec![0; len];
        let mut filled = 0;
        while filled < len {
            let at = offset + filled as u64;
            let within = at % self.sector_size;
            let wanted = ((self.sector_size - within) as usize).min(len - filled);
            let sector = sectors[(at / self.sector_size) as usize];
            self.read_at(self.sector_offset(sector) + within, &mut bytes[filled..filled + wanted])?;
            filled += wanted;
        }
        Ok(bytes)
    }

    fn read_regular(&mut self, entry: &RootStream) -> Result<Vec<u8>> {
        let capacity = self.sector_count as u64 * self.sector_size;
        if entry.size > capacity {
            return Err(malformed("a stream claims to be larger than the file"));
        }
        let needed = entry.size.div_ceil(self.sector_size) as usize;
        let sectors = chain(&self.fat, entry.start, needed)?;
        if sectors.len() < needed {
            return Err(malformed("a stream ends before its declared size"));
        }
        let mut bytes = vec![0; entry.size as usize];
        let mut filled = 0;
        let mut index = 0;
        // Consecutive sectors are read in one go: large streams are usually contiguous.
        while index < sectors.len() {
            self.cancel.check()?;
            let mut run = 1;
            while sectors
                .get(index + run)
                .is_some_and(|&next| u64::from(next) == u64::from(sectors[index]) + run as u64)
            {
                run += 1;
            }
            let wanted = (run as u64 * self.sector_size).min(entry.size - filled as u64) as usize;
            let offset = self.sector_offset(sectors[index]);
            self.read_at(offset, &mut bytes[filled..filled + wanted])?;
            filled += wanted;
            index += run;
        }
        Ok(bytes)
    }

    fn read_mini(&mut self, entry: &RootStream) -> Result<Vec<u8>> {
        if entry.size > self.mini_stream_size {
            return Err(malformed("a stream claims to be larger than the mini stream"));
        }
        let needed = entry.size.div_ceil(MINI_SECTOR_LEN) as usize;
        let sectors = chain(&self.mini_fat, entry.start, needed)?;
        if sectors.len() < needed {
            return Err(malformed("a stream ends before its declared size"));
        }
        let mut bytes = vec![0; entry.size as usize];
        for (chunk, &mini) in bytes.chunks_mut(MINI_SECTOR_LEN as usize).zip(&sectors) {
            let position = u64::from(mini) * MINI_SECTOR_LEN;
            if position + chunk.len() as u64 > self.mini_stream_size {
                return Err(malformed("a stream lies outside the mini stream"));
            }
            let container = self
                .mini_sectors
                .get((position / self.sector_size) as usize)
                .copied()
                .ok_or_else(|| malformed("a stream lies outside the mini stream"))?;
            let offset = self.sector_offset(container) + position % self.sector_size;
            self.read_at(offset, chunk)?;
        }
        Ok(bytes)
    }

    fn load_fat(&mut self, header: &[u8; HEADER_LEN], first_difat: u32) -> Result<()> {
        let mut fat_sectors: Vec<u32> = header[76..]
            .chunks_exact(4)
            .map(|b| u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
            .take_while(|&s| s != FREE_SECTOR)
            .collect();

        let mut seen = vec![false; self.sector_count];
        let mut next = first_difat;
        while next != END_OF_CHAIN && next != FREE_SECTOR {
            let slot = next as usize;
            if slot >= seen.len() {
                return Err(malformed("the sector index lies outside the file"));
            }
            if std::mem::replace(&mut seen[slot], true) {
                return Err(malformed("the sector index loops"));
            }
            let sector = self.read_sector(next)?;
            let (entries, link) = sector.split_at(sector.len() - 4);
            fat_sectors.extend(
                entries
                    .chunks_exact(4)
                    .map(|b| u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
                    .filter(|&s| s != FREE_SECTOR),
            );
            if fat_sectors.len() > self.sector_count + 109 {
                return Err(malformed("the sector table is larger than the file"));
            }
            next = le32(link, 0).unwrap_or(END_OF_CHAIN);
        }

        // Entries for sectors past the end of the file could only ever fail a
        // read, so the table stops there however much the file claims to have.
        for sector in fat_sectors {
            if self.fat.len() >= self.sector_count {
                break;
            }
            let bytes = self.read_sector(sector)?;
            self.fat.extend(bytes.chunks_exact(4).map(|b| u32::from_le_bytes([b[0], b[1], b[2], b[3]])));
        }
        self.fat.truncate(self.sector_count);
        if self.fat.is_empty() {
            return Err(malformed("the sector table is empty"));
        }
        Ok(())
    }

    /// Reads the directory, keeps the root's children and returns the root.
    fn load_directory(&mut self, first: u32, major: u16, max_entries: usize) -> Result<DirectoryEntry> {
        let sectors = chain(&self.fat, first, usize::MAX)?;
        let per_sector = self.sector_size as usize / DIRECTORY_ENTRY_LEN;
        if sectors.len().saturating_mul(per_sector) > max_entries {
            return Err(EngineError::limit(format!("the compound file has more than {max_entries} directory entries")));
        }
        let mut entries = Vec::with_capacity(sectors.len() * per_sector);
        for sector in sectors {
            let bytes = self.read_sector(sector)?;
            entries.extend(bytes.chunks_exact(DIRECTORY_ENTRY_LEN).map(|raw| parse_entry(raw, major)));
        }
        let mut entries = entries.into_iter();
        let root =
            entries.next().filter(|e| e.kind == ROOT_ENTRY).ok_or_else(|| malformed("the root entry is missing"))?;
        let rest: Vec<DirectoryEntry> = entries.collect();

        // Entry numbers count the root, so index i of `rest` is entry i + 1.
        let mut seen = vec![false; rest.len()];
        let mut pending = vec![root.child];
        while let Some(id) = pending.pop() {
            if id == NO_STREAM {
                continue;
            }
            let slot = (id as usize).checked_sub(1).ok_or_else(|| malformed("the directory tree loops"))?;
            let entry = rest.get(slot).ok_or_else(|| malformed("the directory points outside itself"))?;
            if std::mem::replace(&mut seen[slot], true) {
                return Err(malformed("the directory tree loops"));
            }
            pending.push(entry.left);
            pending.push(entry.right);
            match entry.kind {
                STREAM_ENTRY => {
                    self.streams.push(RootStream { name: entry.name.clone(), start: entry.start, size: entry.size })
                }
                _ => self.storages.push(entry.name.clone()),
            }
        }
        Ok(root)
    }

    fn load_mini_stream(&mut self, first_mini_fat: u32, root: &DirectoryEntry) -> Result<()> {
        // Some writers mark an absent mini FAT as free rather than as end of chain.
        let first_mini_fat = if first_mini_fat == FREE_SECTOR { END_OF_CHAIN } else { first_mini_fat };
        for sector in chain(&self.fat, first_mini_fat, usize::MAX)? {
            let bytes = self.read_sector(sector)?;
            self.mini_fat.extend(bytes.chunks_exact(4).map(|b| u32::from_le_bytes([b[0], b[1], b[2], b[3]])));
        }
        if root.size > self.sector_count as u64 * self.sector_size {
            return Err(malformed("the mini stream claims to be larger than the file"));
        }
        let needed = root.size.div_ceil(self.sector_size) as usize;
        self.mini_sectors = chain(&self.fat, root.start, needed)?;
        if self.mini_sectors.len() < needed {
            return Err(malformed("the mini stream ends before its declared size"));
        }
        self.mini_stream_size = root.size;
        Ok(())
    }

    fn sector_offset(&self, sector: u32) -> u64 {
        (u64::from(sector) + 1) * self.sector_size
    }

    fn read_sector(&mut self, sector: u32) -> Result<Vec<u8>> {
        if sector as usize >= self.sector_count {
            return Err(malformed("a sector lies outside the file"));
        }
        let mut bytes = vec![0; self.sector_size as usize];
        self.read_at(self.sector_offset(sector), &mut bytes)?;
        Ok(bytes)
    }

    fn read_at(&mut self, offset: u64, into: &mut [u8]) -> Result<()> {
        if offset.saturating_add(into.len() as u64) > self.byte_size {
            return Err(malformed("a sector lies outside the file"));
        }
        self.reader.seek(SeekFrom::Start(offset))?;
        self.reader.read_exact(into).map_err(|e| EngineError::new(ErrorKind::Io, e.to_string()))
    }
}

/// Follows a sector chain for at most `max_len` sectors. A loop, an index
/// outside the table, or a special marker in the middle of a chain is an error.
/// `FREE_SECTOR` and the other markers are above any valid index, which the
/// table lookup rejects, so only the end-of-chain marker needs naming.
fn chain(table: &[u32], start: u32, max_len: usize) -> Result<Vec<u32>> {
    let mut seen = vec![false; table.len()];
    let mut sectors = Vec::new();
    let mut at = start;
    while at != END_OF_CHAIN && sectors.len() < max_len {
        let slot = at as usize;
        let next = *table.get(slot).ok_or_else(|| malformed("a sector chain leaves the sector table"))?;
        if std::mem::replace(&mut seen[slot], true) {
            return Err(malformed("a sector chain loops"));
        }
        sectors.push(at);
        at = next;
    }
    Ok(sectors)
}

fn parse_entry(raw: &[u8], major: u16) -> DirectoryEntry {
    let field32 = |at| le32(raw, at).unwrap_or(NO_STREAM);
    let name_bytes = usize::from(le16(raw, 64).unwrap_or(0)).clamp(2, 64) - 2;
    let units: Vec<u16> = raw[..name_bytes].chunks_exact(2).map(|b| u16::from_le_bytes([b[0], b[1]])).collect();
    let low = u64::from(field32(120));
    // Version 3 files may hold garbage in the upper half of the size.
    let size = if major == 3 { low } else { low | u64::from(field32(124)) << 32 };
    DirectoryEntry {
        name: String::from_utf16_lossy(&units),
        kind: raw[66],
        left: field32(68),
        right: field32(72),
        child: field32(76),
        start: field32(116),
        size,
    }
}

#[cfg(test)]
pub(crate) mod testing {
    //! Writes well-formed compound files, for fixtures. A hostile file is a
    //! well-formed one with bytes patched afterwards.
    use super::*;

    fn put32(out: &mut [u8], at: usize, value: u32) {
        out[at..at + 4].copy_from_slice(&value.to_le_bytes());
    }

    /// A version 3 file with these root-level streams, smaller ones in the
    /// mini stream, as Office lays them out.
    pub fn cfb(streams: &[(&str, &[u8])]) -> Vec<u8> {
        const SECTOR: usize = 512;
        let mut sectors: Vec<u8> = Vec::new();
        let mut fat: Vec<u32> = Vec::new();
        let append = |bytes: &[u8], sectors: &mut Vec<u8>, fat: &mut Vec<u32>| -> u32 {
            if bytes.is_empty() {
                return END_OF_CHAIN;
            }
            let first = fat.len() as u32;
            let count = bytes.len().div_ceil(SECTOR);
            sectors.extend_from_slice(bytes);
            sectors.resize(sectors.len().next_multiple_of(SECTOR), 0);
            for i in 0..count {
                fat.push(if i + 1 == count { END_OF_CHAIN } else { first + i as u32 + 1 });
            }
            first
        };

        let (mut mini, mut mini_fat) = (Vec::new(), Vec::new());
        let mut placed = Vec::new();
        for (name, bytes) in streams {
            let start = if bytes.len() < 4096 && !bytes.is_empty() {
                let first = (mini.len() / 64) as u32;
                let count = bytes.len().div_ceil(64);
                mini.extend_from_slice(bytes);
                mini.resize(mini.len().next_multiple_of(64), 0);
                for i in 0..count {
                    mini_fat.push(if i + 1 == count { END_OF_CHAIN } else { first + i as u32 + 1 });
                }
                first
            } else {
                append(bytes, &mut sectors, &mut fat)
            };
            placed.push((*name, start, bytes.len()));
        }
        let mini_start = append(&mini, &mut sectors, &mut fat);
        let mini_fat_bytes: Vec<u8> = {
            let mut table: Vec<u32> = mini_fat;
            if !table.is_empty() {
                table.resize(table.len().next_multiple_of(128), FREE_SECTOR);
            }
            table.iter().flat_map(|v| v.to_le_bytes()).collect()
        };
        let first_mini_fat = append(&mini_fat_bytes, &mut sectors, &mut fat);

        let mut directory = vec![0u8; (placed.len() + 1).next_multiple_of(4) * DIRECTORY_ENTRY_LEN];
        let mut write_entry = |index: usize, name: &str, kind: u8, right: u32, child: u32, start: u32, size: usize| {
            let raw = &mut directory[index * DIRECTORY_ENTRY_LEN..(index + 1) * DIRECTORY_ENTRY_LEN];
            let units: Vec<u16> = name.encode_utf16().collect();
            for (i, unit) in units.iter().enumerate() {
                raw[i * 2..i * 2 + 2].copy_from_slice(&unit.to_le_bytes());
            }
            raw[64..66].copy_from_slice(&((units.len() as u16 + 1) * 2).to_le_bytes());
            raw[66] = kind;
            raw[67] = 1;
            put32(raw, 68, NO_STREAM);
            put32(raw, 72, right);
            put32(raw, 76, child);
            put32(raw, 116, start);
            put32(raw, 120, size as u32);
        };
        let child = if placed.is_empty() { NO_STREAM } else { 1 };
        write_entry(0, "Root Entry", ROOT_ENTRY, NO_STREAM, child, mini_start, mini.len());
        for (i, (name, start, size)) in placed.iter().enumerate() {
            let right = if i + 1 < placed.len() { i as u32 + 2 } else { NO_STREAM };
            write_entry(i + 1, name, STREAM_ENTRY, right, NO_STREAM, *start, *size);
        }
        for raw in directory.chunks_exact_mut(DIRECTORY_ENTRY_LEN) {
            if raw[66] == 0 {
                raw[68..80].fill(0xFF);
            }
        }
        let first_directory = append(&directory, &mut sectors, &mut fat);

        // The table must also cover the sectors that hold it and its index.
        const PER_SECTOR: usize = SECTOR / 4;
        const PER_DIFAT: usize = PER_SECTOR - 1;
        let data_sectors = fat.len();
        let (mut fat_count, mut difat_count) = (0, 0);
        loop {
            let needed_fat = (data_sectors + fat_count + difat_count).div_ceil(PER_SECTOR);
            let needed_difat = needed_fat.saturating_sub(109).div_ceil(PER_DIFAT);
            if needed_fat == fat_count && needed_difat == difat_count {
                break;
            }
            fat_count = needed_fat;
            difat_count = needed_difat;
        }
        let first_fat_sector = data_sectors as u32;
        let first_difat_sector = first_fat_sector + fat_count as u32;
        fat.extend(std::iter::repeat_n(0xFFFF_FFFD, fat_count));
        fat.extend(std::iter::repeat_n(0xFFFF_FFFC, difat_count));
        fat.resize(fat_count * PER_SECTOR, FREE_SECTOR);
        for value in &fat {
            sectors.extend_from_slice(&value.to_le_bytes());
        }

        let mut header = vec![0u8; HEADER_LEN];
        header[..8].copy_from_slice(SIGNATURE);
        header[24..26].copy_from_slice(&0x3Eu16.to_le_bytes());
        header[26..28].copy_from_slice(&3u16.to_le_bytes());
        header[28..30].copy_from_slice(&0xFFFEu16.to_le_bytes());
        header[30..32].copy_from_slice(&9u16.to_le_bytes());
        header[32..34].copy_from_slice(&6u16.to_le_bytes());
        put32(&mut header, 44, fat_count as u32);
        put32(&mut header, 48, first_directory);
        put32(&mut header, 56, 4096);
        put32(&mut header, 60, first_mini_fat);
        put32(&mut header, 64, (mini_fat_bytes.len() / SECTOR) as u32);
        put32(&mut header, 68, if difat_count > 0 { first_difat_sector } else { END_OF_CHAIN });
        put32(&mut header, 72, difat_count as u32);
        for i in 0..109 {
            let value = if i < fat_count { first_fat_sector + i as u32 } else { FREE_SECTOR };
            put32(&mut header, 76 + i * 4, value);
        }
        let mut remaining = (109..fat_count).map(|i| first_fat_sector + i as u32);
        for d in 0..difat_count {
            let start = sectors.len();
            sectors.resize(start + SECTOR, 0xFF);
            for slot in 0..PER_DIFAT {
                if let Some(value) = remaining.next() {
                    put32(&mut sectors, start + slot * 4, value);
                }
            }
            let next = if d + 1 < difat_count { first_difat_sector + d as u32 + 1 } else { END_OF_CHAIN };
            put32(&mut sectors, start + PER_DIFAT * 4, next);
        }

        header.extend_from_slice(&sectors);
        header
    }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::testing::cfb;
    use super::*;

    fn open(bytes: Vec<u8>) -> Result<CfbFile> {
        open_with(bytes, Limits::default())
    }

    fn open_with(bytes: Vec<u8>, limits: Limits) -> Result<CfbFile> {
        let size = bytes.len() as u64;
        CfbFile::open(Box::new(Cursor::new(bytes)), size, limits, CancelToken::default())
    }

    fn pattern(len: usize) -> Vec<u8> {
        (0..len).map(|i| (i % 251) as u8).collect()
    }

    fn put32(bytes: &mut [u8], at: usize, value: u32) {
        bytes[at..at + 4].copy_from_slice(&value.to_le_bytes());
    }

    fn sector_at(sector: u32) -> usize {
        (sector as usize + 1) * 512
    }

    fn entry_at(bytes: &[u8], index: usize) -> usize {
        sector_at(le32(bytes, 48).unwrap()) + index * DIRECTORY_ENTRY_LEN
    }

    /// Byte offset of the FAT entry for `sector`.
    fn fat_entry(bytes: &[u8], sector: u32) -> usize {
        sector_at(le32(bytes, 76).unwrap()) + sector as usize * 4
    }

    fn assert_malformed(result: Result<Vec<u8>>, needle: &str) {
        let err = result.expect_err("should fail");
        assert_eq!(err.kind, ErrorKind::Malformed, "{err}");
        assert!(err.message.contains(needle), "{err}");
    }

    #[test]
    fn reads_small_and_large_streams() {
        let (small, large, empty) = (pattern(100), pattern(10_000), Vec::new());
        let bytes = cfb(&[("Small", &small), ("Large", &large), ("Empty", &empty)]);
        let mut file = open(bytes).unwrap();
        assert_eq!(file.read_stream("small", 1 << 20).unwrap(), small);
        assert_eq!(file.read_stream("LARGE", 1 << 20).unwrap(), large);
        assert_eq!(file.read_stream("Empty", 1 << 20).unwrap(), empty);
        assert!(file.contains("Large") && !file.contains("Other"));
        assert_eq!(file.read_stream("Other", 1).err().unwrap().kind, ErrorKind::Malformed);
    }

    #[test]
    fn reads_a_file_that_needs_a_difat_chain() {
        // More than 109 FAT sectors, so the sector index overflows the header.
        let large = pattern(8 * 1024 * 1024);
        let bytes = cfb(&[("Large", &large)]);
        assert_ne!(le32(&bytes, 68).unwrap(), END_OF_CHAIN);
        assert_eq!(open(bytes).unwrap().read_stream("Large", u64::MAX).unwrap(), large);
    }

    #[test]
    fn enforces_the_caller_limit() {
        let large = pattern(10_000);
        let mut file = open(cfb(&[("Large", &large)])).unwrap();
        assert_eq!(file.read_stream("Large", 9_999).err().unwrap().kind, ErrorKind::LimitExceeded);
    }

    #[test]
    fn rejects_bad_headers() {
        let good = cfb(&[("A", b"a")]);
        assert_eq!(open(good[..100].to_vec()).err().unwrap().kind, ErrorKind::Malformed);
        for (at, value, needle) in [
            (0usize, 0u16, "signature"),
            (28, 0xFEFF, "byte order"),
            (26, 2, "version"),
            (30, 12, "version"),
            (32, 7, "mini sector"),
        ] {
            let mut bytes = good.clone();
            bytes[at..at + 2].copy_from_slice(&value.to_le_bytes());
            let err = open(bytes).map(|_| ()).expect_err("should fail");
            assert_eq!(err.kind, ErrorKind::Malformed);
            assert!(err.message.contains(needle), "{err}");
        }
    }

    #[test]
    fn stream_chain_loop_is_an_error() {
        let large = pattern(5000);
        let mut bytes = cfb(&[("Large", &large)]);
        let at = fat_entry(&bytes, 3);
        put32(&mut bytes, at, 1);
        assert_malformed(open(bytes).unwrap().read_stream("Large", u64::MAX), "loops");
    }

    #[test]
    fn self_referencing_sector_is_an_error() {
        let large = pattern(5000);
        let mut bytes = cfb(&[("Large", &large)]);
        let at = fat_entry(&bytes, 0);
        put32(&mut bytes, at, 0);
        assert_malformed(open(bytes).unwrap().read_stream("Large", u64::MAX), "loops");
    }

    #[test]
    fn directory_chain_loop_is_an_error() {
        let mut bytes = cfb(&[("A", b"a")]);
        let first = le32(&bytes, 48).unwrap();
        let at = fat_entry(&bytes, first);
        put32(&mut bytes, at, first);
        assert!(open(bytes).err().unwrap().message.contains("loops"));
    }

    #[test]
    fn difat_loop_is_an_error() {
        let large = pattern(8 * 1024 * 1024);
        let mut bytes = cfb(&[("Large", &large)]);
        let first = le32(&bytes, 68).unwrap();
        // The last slot of a DIFAT sector links to the next one.
        put32(&mut bytes, sector_at(first) + 508, first);
        assert!(open(bytes).err().unwrap().message.contains("loops"));
    }

    #[test]
    fn directory_tree_cycle_is_an_error() {
        let mut bytes = cfb(&[("A", b"a"), ("B", b"b")]);
        let at = entry_at(&bytes, 2) + 72;
        put32(&mut bytes, at, 1); // B's right sibling is A, whose right sibling is B.
        assert!(open(bytes).err().unwrap().message.contains("loops"));

        // Pointing back at the root is a cycle too.
        let mut bytes = cfb(&[("A", b"a")]);
        let at = entry_at(&bytes, 1) + 68;
        put32(&mut bytes, at, 0);
        assert!(open(bytes).err().unwrap().message.contains("loops"));
    }

    #[test]
    fn directory_pointing_outside_itself_is_an_error() {
        let mut bytes = cfb(&[("A", b"a")]);
        let at = entry_at(&bytes, 1) + 72;
        put32(&mut bytes, at, 1000);
        assert_eq!(open(bytes).err().unwrap().kind, ErrorKind::Malformed);
    }

    #[test]
    fn huge_declared_sizes_allocate_nothing() {
        let large = pattern(5000);
        let mut bytes = cfb(&[("Large", &large)]);
        let at = entry_at(&bytes, 1) + 120;
        put32(&mut bytes, at, u32::MAX);
        assert_malformed(open(bytes).unwrap().read_stream("Large", u64::MAX), "larger than the file");
    }

    #[test]
    fn stream_shorter_than_declared_is_an_error() {
        let large = pattern(5000);
        let mut bytes = cfb(&[("Large", &large)]);
        let at = fat_entry(&bytes, 2);
        put32(&mut bytes, at, END_OF_CHAIN);
        assert_malformed(open(bytes).unwrap().read_stream("Large", u64::MAX), "ends before");
    }

    #[test]
    fn start_sector_outside_the_table_is_an_error() {
        for start in [1_000_000, FREE_SECTOR, 0xFFFF_FFFD] {
            let large = pattern(5000);
            let mut bytes = cfb(&[("Large", &large)]);
            let at = entry_at(&bytes, 1) + 116;
            put32(&mut bytes, at, start);
            assert_malformed(open(bytes).unwrap().read_stream("Large", u64::MAX), "leaves the sector table");
        }
    }

    #[test]
    fn mini_stream_problems_are_errors() {
        // Mini chain loop.
        let small = pattern(300);
        let mut bytes = cfb(&[("Small", &small)]);
        let first_mini_fat = le32(&bytes, 60).unwrap();
        put32(&mut bytes, sector_at(first_mini_fat) + 4, 0);
        assert_malformed(open(bytes).unwrap().read_stream("Small", u64::MAX), "loops");

        // A mini sector beyond the mini stream.
        let mut bytes = cfb(&[("Small", &small)]);
        let first_mini_fat = le32(&bytes, 60).unwrap();
        put32(&mut bytes, sector_at(first_mini_fat) + 4, 100);
        put32(&mut bytes, sector_at(first_mini_fat) + 400, END_OF_CHAIN);
        assert_malformed(open(bytes).unwrap().read_stream("Small", u64::MAX), "");

        // A root entry claiming an impossible mini stream.
        let mut bytes = cfb(&[("Small", &small)]);
        let at = entry_at(&bytes, 0) + 120;
        put32(&mut bytes, at, u32::MAX);
        assert!(open(bytes).err().unwrap().message.contains("larger than the file"));
    }

    #[test]
    fn fat_sector_outside_the_file_is_an_error() {
        let mut bytes = cfb(&[("A", b"a")]);
        put32(&mut bytes, 76, 9_000_000);
        assert_eq!(open(bytes).err().unwrap().kind, ErrorKind::Malformed);
    }

    #[test]
    fn too_many_directory_entries_hit_the_limit() {
        let bytes = cfb(&[("A", b"a")]);
        let limits = Limits { max_archive_entries: 2, ..Limits::default() };
        assert_eq!(open_with(bytes, limits).err().unwrap().kind, ErrorKind::LimitExceeded);
    }

    #[test]
    fn garbage_names_do_not_panic() {
        for length in [0u16, 1, 63, 65, 0xFFFF] {
            let mut bytes = cfb(&[("A", b"a")]);
            let at = entry_at(&bytes, 1) + 64;
            bytes[at..at + 2].copy_from_slice(&length.to_le_bytes());
            let _ = open(bytes);
        }
    }

    #[test]
    fn version_4_files_use_large_sectors() {
        // One directory sector, one FAT sector, in a hand-laid 4096-byte file.
        let mut bytes = vec![0u8; 4096 * 4];
        bytes[..8].copy_from_slice(SIGNATURE);
        bytes[26..28].copy_from_slice(&4u16.to_le_bytes());
        bytes[28..30].copy_from_slice(&0xFFFEu16.to_le_bytes());
        bytes[30..32].copy_from_slice(&12u16.to_le_bytes());
        bytes[32..34].copy_from_slice(&6u16.to_le_bytes());
        put32(&mut bytes, 48, 1); // directory in sector 1
        put32(&mut bytes, 56, 4096);
        put32(&mut bytes, 60, END_OF_CHAIN);
        put32(&mut bytes, 68, END_OF_CHAIN);
        bytes[76..512].fill(0xFF);
        put32(&mut bytes, 76, 0); // FAT in sector 0
        let fat = sector_offset_v4(0);
        for (sector, value) in [(0, 0xFFFF_FFFD), (1, END_OF_CHAIN), (2, END_OF_CHAIN)] {
            put32(&mut bytes, fat + sector * 4, value);
        }
        let directory = sector_offset_v4(1);
        for i in 0..32 {
            bytes[directory + i * 128 + 68..directory + i * 128 + 80].fill(0xFF);
        }
        let name: Vec<u8> = "Root".encode_utf16().flat_map(|u| u.to_le_bytes()).collect();
        bytes[directory..directory + 8].copy_from_slice(&name);
        bytes[directory + 64] = 10;
        bytes[directory + 66] = ROOT_ENTRY;
        put32(&mut bytes, directory + 76, 1);
        put32(&mut bytes, directory + 116, END_OF_CHAIN);
        let entry = directory + 128;
        let name: Vec<u8> = "Data".encode_utf16().flat_map(|u| u.to_le_bytes()).collect();
        bytes[entry..entry + 8].copy_from_slice(&name);
        bytes[entry + 64] = 10;
        bytes[entry + 66] = STREAM_ENTRY;
        put32(&mut bytes, entry + 116, 2);
        put32(&mut bytes, entry + 120, 4096);
        let data = sector_offset_v4(2);
        bytes[data..data + 4096].fill(0x5A);

        let mut file = open(bytes).unwrap();
        assert_eq!(file.read_stream("Data", u64::MAX).unwrap(), vec![0x5A; 4096]);
    }

    fn sector_offset_v4(sector: usize) -> usize {
        (sector + 1) * 4096
    }
}
