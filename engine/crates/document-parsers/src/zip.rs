//! Minimal, defensive ZIP reader shared by every container format.
//!
//! Written in-house rather than taken from a general-purpose crate so that
//! every limit is enforced here: entry count, central-directory size, per-entry
//! and per-session decompressed bytes, compression ratio, and CRC. Supports
//! the two methods office and e-book formats use (stored, deflate) and refuses
//! ZIP64, multi-disk and encrypted archives.
//!
//! The reader is owned: [`ZipArchive::stream`] moves it into an
//! [`EntryReader`], and [`ZipArchive::finish`] takes it back, so one entry is
//! decompressed at a time without self-referential borrows.

use std::collections::HashMap;
use std::io::{self, BufReader, Read, Seek, SeekFrom, Take};

use document_core::{CancelToken, EngineError, ErrorKind, Limits, ReadSeek, Result};
use flate2::read::DeflateDecoder;

const EOCD_SIGNATURE: u32 = 0x0605_4b50;
const CENTRAL_SIGNATURE: u32 = 0x0201_4b50;
const LOCAL_SIGNATURE: u32 = 0x0403_4b50;
const EOCD_MIN_LEN: usize = 22;
/// The end record may be followed by a comment of up to 65535 bytes.
const EOCD_SEARCH_LEN: u64 = EOCD_MIN_LEN as u64 + 65_535;
/// Ratio checks start once an entry has produced this much output, so small
/// highly compressible parts (empty XML skeletons) are not flagged.
const RATIO_GRACE_BYTES: u64 = 1024 * 1024;

#[derive(Debug, Clone)]
pub(crate) struct ZipEntry {
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
    pub(crate) compressed_size: u64,
    /// The real compression method; for WinZip AES entries, the method inside the AES extra field.
    pub(crate) method: u16,
    pub(crate) encrypted: bool,
    pub(crate) crc32: u32,
    pub(crate) flags: u16,
    /// WinZip AES strength (1 = 128-bit, 2 = 192-bit, 3 = 256-bit); 0 for other schemes.
    pub(crate) aes_strength: u8,
    pub(crate) header_offset: u64,
    /// MS-DOS last-modified time (low 16 bits) and date (high 16 bits), as stored.
    pub(crate) modified_dos: u32,
}

pub(crate) struct ZipArchive {
    reader: Option<Box<dyn ReadSeek>>,
    entries: Vec<ZipEntry>,
    index: HashMap<String, usize>,
    central_offset: u64,
    limits: Limits,
    cancel: CancelToken,
    decompressed: u64,
}

impl ZipArchive {
    pub fn open(mut reader: Box<dyn ReadSeek>, byte_size: u64, limits: Limits, cancel: CancelToken) -> Result<Self> {
        let tail_len = byte_size.min(EOCD_SEARCH_LEN);
        reader.seek(SeekFrom::Start(byte_size - tail_len))?;
        let mut tail = vec![0; tail_len as usize];
        reader.read_exact(&mut tail)?;
        let eocd = (0..=tail.len().saturating_sub(EOCD_MIN_LEN))
            .rev()
            .find(|&i| le32(&tail[i..]) == EOCD_SIGNATURE)
            .ok_or_else(|| malformed("the ZIP end-of-directory record is missing"))?;
        let eocd_offset = byte_size - tail_len + eocd as u64;
        let record = &tail[eocd..];

        let (disk, cd_disk) = (le16(&record[4..]), le16(&record[6..]));
        let entry_count = le16(&record[10..]);
        let (cd_size, cd_offset) = (le32(&record[12..]), le32(&record[16..]));
        if disk != 0 || cd_disk != 0 {
            return Err(EngineError::unsupported("multi-part ZIP archives are not supported"));
        }
        if entry_count == 0xFFFF || cd_size == u32::MAX || cd_offset == u32::MAX {
            return Err(EngineError::unsupported("ZIP64 archives are not supported yet"));
        }
        let (cd_size, cd_offset) = (u64::from(cd_size), u64::from(cd_offset));
        if cd_offset + cd_size > eocd_offset {
            return Err(malformed("the ZIP central directory lies outside the file"));
        }
        if usize::from(entry_count) > limits.max_archive_entries {
            return Err(EngineError::limit(format!(
                "the archive has {entry_count} entries; the limit is {}",
                limits.max_archive_entries
            )));
        }

        reader.seek(SeekFrom::Start(cd_offset))?;
        let mut directory = vec![0; cd_size as usize];
        reader.read_exact(&mut directory)?;
        let entries = parse_central_directory(&directory, entry_count.into(), cd_offset)?;
        let index = entries.iter().enumerate().map(|(i, e)| (e.name.clone(), i)).collect();
        Ok(Self { reader: Some(reader), entries, index, central_offset: cd_offset, limits, cancel, decompressed: 0 })
    }

    pub fn limits(&self) -> Limits {
        self.limits
    }

    pub fn entries(&self) -> &[ZipEntry] {
        &self.entries
    }

    pub fn find(&self, name: &str) -> Option<&ZipEntry> {
        self.index.get(name.trim_start_matches('/')).map(|&i| &self.entries[i])
    }

    /// Decompresses a whole entry, which must not exceed `max_part_bytes`.
    pub fn read_to_vec(&mut self, name: &str) -> Result<Vec<u8>> {
        let declared = self.find(name).map(|e| e.size).ok_or_else(|| missing(name))?;
        if declared > self.limits.max_part_bytes {
            return Err(EngineError::limit(format!(
                "{name} is {declared} bytes uncompressed; the limit for this part is {} bytes",
                self.limits.max_part_bytes
            )));
        }
        let mut entry = self.stream(name)?;
        let mut bytes = Vec::with_capacity(declared as usize);
        let result = entry.read_to_end(&mut bytes).map_err(|e| from_io(&e));
        self.finish(entry);
        result.map(|_| bytes)
    }

    /// Like [`ZipArchive::read_to_vec`], but works while `active` is
    /// mid-stream: the file handle is borrowed from it and put back where it
    /// was, so the stream continues as if nothing happened.
    ///
    /// The read counts against the session budget but is not charged to it.
    /// Pictures are capped, read on request and freed after decoding, and
    /// charging them would let scrolling back and forth through an illustrated
    /// book use up the budget its text needs.
    pub fn read_aside(&mut self, active: Option<&mut EntryReader>, name: &str) -> Result<Vec<u8>> {
        self.aside(active, |archive| archive.read_to_vec(name))
    }

    /// Runs `read` on the archive while `active` is mid-stream, as
    /// [`ZipArchive::read_aside`] does for one entry: for a renderer that reads
    /// several parts (and their relationships) between two batches. Every
    /// entry `read` streams must be handed back, as [`ZipArchive::read_to_vec`] does.
    pub fn aside<T>(
        &mut self,
        active: Option<&mut EntryReader>,
        read: impl FnOnce(&mut Self) -> Result<T>,
    ) -> Result<T> {
        let charged = self.decompressed;
        let result = match active {
            None => read(self),
            Some(active) => {
                self.decompressed = active.session_decompressed;
                let raw = active.source.raw_mut();
                let position = raw.stream_position()?;
                self.reader = Some(std::mem::replace(raw, Box::new(io::Cursor::new(Vec::new()))));
                let result = read(self);
                let mut reader = self.reader.take().expect("reads hand the reader back");
                let restored = reader.seek(SeekFrom::Start(position));
                *active.source.raw_mut() = reader;
                restored?;
                result
            }
        };
        self.decompressed = charged;
        result
    }

    /// Hands out the raw archive reader positioned at an entry's data, for the
    /// decryption layer. It must come back through [`ZipArchive::restore_reader`].
    pub(crate) fn take_raw(&mut self, name: &str) -> Result<(ZipEntry, Box<dyn ReadSeek>)> {
        let entry = self.find(name).cloned().ok_or_else(|| missing(name))?;
        let mut reader = self.reader.take().expect("an entry is already being read");
        let start = match local_data_start(&mut reader, &entry) {
            Ok(start) => start,
            Err(e) => {
                self.reader = Some(reader);
                return Err(e);
            }
        };
        if start + entry.compressed_size > self.central_offset {
            self.reader = Some(reader);
            return Err(malformed(format!("entry {} overlaps the central directory", entry.name)));
        }
        if let Err(e) = reader.seek(SeekFrom::Start(start)) {
            self.reader = Some(reader);
            return Err(e.into());
        }
        Ok((entry, reader))
    }

    pub(crate) fn restore_reader(&mut self, reader: Box<dyn ReadSeek>) {
        self.reader = Some(reader);
    }

    /// Starts decompressing an entry. Until it is handed back with
    /// [`ZipArchive::finish`], no other entry can be read.
    pub fn stream(&mut self, name: &str) -> Result<EntryReader> {
        let entry = self.find(name).cloned().ok_or_else(|| missing(name))?;
        if entry.encrypted {
            return Err(EngineError::unsupported("password-protected files are not supported"));
        }
        let mut reader = self.reader.take().expect("an entry is already being read");
        let data_start = match local_data_start(&mut reader, &entry) {
            Ok(start) => start,
            Err(e) => {
                self.reader = Some(reader);
                return Err(e);
            }
        };
        if data_start + entry.compressed_size > self.central_offset {
            self.reader = Some(reader);
            return Err(malformed(format!("entry {} overlaps the central directory", entry.name)));
        }
        if let Err(e) = reader.seek(SeekFrom::Start(data_start)) {
            self.reader = Some(reader);
            return Err(e.into());
        }
        let raw = reader.take(entry.compressed_size);
        let source = match entry.method {
            0 if entry.compressed_size == entry.size => Source::Stored(raw),
            0 => {
                self.reader = Some(raw.into_inner());
                return Err(malformed(format!("stored entry {} has inconsistent sizes", entry.name)));
            }
            8 => Source::Deflate(Box::new(DeflateDecoder::new(BufReader::new(raw)))),
            method => {
                self.reader = Some(raw.into_inner());
                return Err(EngineError::unsupported(format!("ZIP compression method {method} is not supported")));
            }
        };
        Ok(EntryReader {
            source,
            entry,
            produced: 0,
            crc: crc32fast::Hasher::new(),
            session_decompressed: self.decompressed,
            limits: self.limits,
            cancel: self.cancel.clone(),
        })
    }

    pub fn finish(&mut self, entry: EntryReader) {
        self.decompressed = entry.session_decompressed;
        self.reader = Some(entry.source.into_inner());
    }
}

type Raw = Take<Box<dyn ReadSeek>>;

enum Source {
    Stored(Raw),
    Deflate(Box<DeflateDecoder<BufReader<Raw>>>),
}

impl Source {
    fn into_inner(self) -> Box<dyn ReadSeek> {
        match self {
            Source::Stored(take) => take.into_inner(),
            Source::Deflate(decoder) => decoder.into_inner().into_inner().into_inner(),
        }
    }

    fn raw_mut(&mut self) -> &mut Box<dyn ReadSeek> {
        match self {
            Source::Stored(take) => take.get_mut(),
            Source::Deflate(decoder) => decoder.get_mut().get_mut().get_mut(),
        }
    }
}

/// Decompressed bytes of one entry, checked against its declared size, CRC,
/// the compression-ratio limit and the session decompression budget.
pub(crate) struct EntryReader {
    source: Source,
    entry: ZipEntry,
    produced: u64,
    crc: crc32fast::Hasher,
    session_decompressed: u64,
    limits: Limits,
    cancel: CancelToken,
}

impl Read for EntryReader {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if self.cancel.is_cancelled() {
            return Err(engine_io(EngineError::cancelled()));
        }
        let n = match &mut self.source {
            Source::Stored(r) => r.read(buf)?,
            Source::Deflate(r) => r.read(buf)?,
        };
        if n == 0 {
            if !buf.is_empty() && (self.produced != self.entry.size || self.crc.clone().finalize() != self.entry.crc32)
            {
                return Err(engine_io(malformed(format!(
                    "{} is corrupt (size or checksum mismatch)",
                    self.entry.name
                ))));
            }
            return Ok(0);
        }
        self.produced += n as u64;
        self.session_decompressed += n as u64;
        self.crc.update(&buf[..n]);
        if self.produced > self.entry.size {
            return Err(engine_io(malformed(format!("{} is larger than its header declares", self.entry.name))));
        }
        if self.session_decompressed > self.limits.max_decompressed_bytes {
            return Err(engine_io(EngineError::limit("the document expands to more data than allowed")));
        }
        let ratio_limit = self.entry.compressed_size.max(1).saturating_mul(self.limits.max_compression_ratio);
        if self.produced > RATIO_GRACE_BYTES && self.produced > ratio_limit {
            return Err(engine_io(EngineError::limit(format!(
                "{} is compressed suspiciously well; refusing to expand it",
                self.entry.name
            ))));
        }
        Ok(n)
    }
}

fn parse_central_directory(dir: &[u8], expected: usize, cd_offset: u64) -> Result<Vec<ZipEntry>> {
    let mut entries = Vec::with_capacity(expected);
    let mut pos = 0;
    for _ in 0..expected {
        let header = dir.get(pos..pos + 46).ok_or_else(|| malformed("the ZIP central directory is truncated"))?;
        if le32(header) != CENTRAL_SIGNATURE {
            return Err(malformed("the ZIP central directory is corrupt"));
        }
        let flags = le16(&header[8..]);
        let method = le16(&header[10..]);
        let crc32 = le32(&header[16..]);
        let (compressed, size) = (le32(&header[20..]), le32(&header[24..]));
        let (name_len, extra_len, comment_len) =
            (le16(&header[28..]) as usize, le16(&header[30..]) as usize, le16(&header[32..]) as usize);
        let header_offset = le32(&header[42..]);
        if compressed == u32::MAX || size == u32::MAX || header_offset == u32::MAX {
            return Err(EngineError::unsupported("ZIP64 archives are not supported yet"));
        }
        let name_bytes = dir
            .get(pos + 46..pos + 46 + name_len)
            .ok_or_else(|| malformed("the ZIP central directory is truncated"))?;
        let name = String::from_utf8_lossy(name_bytes).replace('\\', "/");
        if u64::from(header_offset) >= cd_offset {
            return Err(malformed(format!("entry {name} points outside the archive")));
        }
        let extra = dir
            .get(pos + 46 + name_len..pos + 46 + name_len + extra_len)
            .ok_or_else(|| malformed("the ZIP central directory is truncated"))?;
        let (method, aes_strength) = match aes_extra(extra) {
            Some((strength, actual)) => (actual, strength),
            None => (method, 0),
        };
        entries.push(ZipEntry {
            is_dir: name.ends_with('/'),
            name: name.trim_start_matches('/').to_owned(),
            size: size.into(),
            compressed_size: compressed.into(),
            method,
            encrypted: flags & 1 != 0,
            crc32,
            flags,
            aes_strength,
            header_offset: header_offset.into(),
            modified_dos: (u32::from(le16(&header[14..])) << 16) | u32::from(le16(&header[12..])),
        });
        pos += 46 + name_len + extra_len + comment_len;
    }
    Ok(entries)
}

/// WinZip AES extra field (id 0x9901): returns the strength and the real compression method.
fn aes_extra(mut extra: &[u8]) -> Option<(u8, u16)> {
    while extra.len() >= 4 {
        let id = le16(extra);
        let size = le16(&extra[2..]) as usize;
        let body = extra.get(4..4 + size)?;
        if id == 0x9901 && size >= 7 && &body[2..4] == b"AE" {
            return Some((body[4], le16(&body[5..])));
        }
        extra = &extra[4 + size..];
    }
    None
}

fn local_data_start(reader: &mut Box<dyn ReadSeek>, entry: &ZipEntry) -> Result<u64> {
    reader.seek(SeekFrom::Start(entry.header_offset))?;
    let mut header = [0u8; 30];
    reader.read_exact(&mut header)?;
    if le32(&header) != LOCAL_SIGNATURE {
        return Err(malformed(format!("entry {} has a corrupt local header", entry.name)));
    }
    let (name_len, extra_len) = (u64::from(le16(&header[26..])), u64::from(le16(&header[28..])));
    Ok(entry.header_offset + 30 + name_len + extra_len)
}

fn le16(b: &[u8]) -> u16 {
    u16::from_le_bytes([b[0], b[1]])
}

fn le32(b: &[u8]) -> u32 {
    u32::from_le_bytes([b[0], b[1], b[2], b[3]])
}

fn malformed(message: impl Into<String>) -> EngineError {
    EngineError::malformed(message)
}

fn missing(name: &str) -> EngineError {
    malformed(format!("the file is missing its {name} part"))
}

/// Carries a typed engine error through `std::io::Read`.
pub(crate) fn engine_io(err: EngineError) -> io::Error {
    io::Error::other(err)
}

/// Recovers the typed error carried by [`engine_io`]; other I/O errors map to `Io`.
pub(crate) fn from_io(err: &io::Error) -> EngineError {
    match err.get_ref().and_then(|inner| inner.downcast_ref::<EngineError>()) {
        Some(engine) => engine.clone(),
        None if matches!(err.kind(), io::ErrorKind::InvalidData | io::ErrorKind::InvalidInput) => {
            EngineError::new(ErrorKind::Malformed, format!("compressed data is corrupt: {err}"))
        }
        None => EngineError::new(ErrorKind::Io, err.to_string()),
    }
}

#[cfg(test)]
pub(crate) mod testing {
    //! Builds ZIP archives in memory for parser tests.
    use std::io::{Cursor, Write};

    use flate2::Compression;
    use flate2::write::DeflateEncoder;

    pub fn zip(files: &[(&str, &[u8])]) -> Vec<u8> {
        zip_with(files, true)
    }

    pub fn zip_with(files: &[(&str, &[u8])], deflate: bool) -> Vec<u8> {
        let mut out = Cursor::new(Vec::new());
        let mut central = Vec::new();
        for (name, data) in files {
            let offset = out.position() as u32;
            let crc = crc32fast::hash(data);
            let (method, body) = if deflate {
                let mut enc = DeflateEncoder::new(Vec::new(), Compression::default());
                enc.write_all(data).unwrap();
                (8u16, enc.finish().unwrap())
            } else {
                (0u16, data.to_vec())
            };
            let mut local = Vec::new();
            local.extend(0x0403_4b50u32.to_le_bytes());
            local.extend([20, 0, 0, 0]);
            local.extend(method.to_le_bytes());
            local.extend([0; 4]);
            local.extend(crc.to_le_bytes());
            local.extend((body.len() as u32).to_le_bytes());
            local.extend((data.len() as u32).to_le_bytes());
            local.extend((name.len() as u16).to_le_bytes());
            local.extend(0u16.to_le_bytes());
            local.extend(name.as_bytes());
            out.write_all(&local).unwrap();
            out.write_all(&body).unwrap();

            central.extend(0x0201_4b50u32.to_le_bytes());
            central.extend([20, 0, 20, 0, 0, 0]);
            central.extend(method.to_le_bytes());
            central.extend([0; 4]);
            central.extend(crc.to_le_bytes());
            central.extend((body.len() as u32).to_le_bytes());
            central.extend((data.len() as u32).to_le_bytes());
            central.extend((name.len() as u16).to_le_bytes());
            central.extend([0; 12]);
            central.extend(offset.to_le_bytes());
            central.extend(name.as_bytes());
        }
        let cd_offset = out.position() as u32;
        out.write_all(&central).unwrap();
        let mut end = Vec::new();
        end.extend(0x0605_4b50u32.to_le_bytes());
        end.extend([0; 4]);
        end.extend((files.len() as u16).to_le_bytes());
        end.extend((files.len() as u16).to_le_bytes());
        end.extend((central.len() as u32).to_le_bytes());
        end.extend(cd_offset.to_le_bytes());
        end.extend(0u16.to_le_bytes());
        out.write_all(&end).unwrap();
        out.into_inner()
    }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;

    fn open(bytes: Vec<u8>, limits: Limits) -> Result<ZipArchive> {
        let len = bytes.len() as u64;
        ZipArchive::open(Box::new(Cursor::new(bytes)), len, limits, CancelToken::default())
    }

    #[test]
    fn reads_stored_and_deflated_entries() {
        for deflate in [false, true] {
            let bytes = testing::zip_with(&[("a.txt", b"alpha"), ("dir/b.xml", b"<b/>")], deflate);
            let mut zip = open(bytes, Limits::default()).unwrap();
            assert_eq!(zip.entries().len(), 2);
            assert_eq!(zip.read_to_vec("dir/b.xml").unwrap(), b"<b/>");
            assert_eq!(zip.read_to_vec("a.txt").unwrap(), b"alpha");
        }
    }

    #[test]
    fn reads_aside_without_disturbing_a_stream_or_its_budget() {
        // Larger than any read buffer, and hard to compress.
        let long: Vec<u8> = (0..400_000u32).flat_map(|i| i.wrapping_mul(2_654_435_761).to_le_bytes()).collect();
        for deflate in [false, true] {
            let bytes = testing::zip_with(&[("long.bin", &long), ("pic.png", b"picture")], deflate);
            let limits = Limits { max_decompressed_bytes: long.len() as u64 + 100, ..Limits::default() };
            let mut zip = open(bytes, limits).unwrap();
            let mut entry = zip.stream("long.bin").unwrap();
            let mut read = Vec::new();
            let mut chunk = [0u8; 4096];
            while read.len() < long.len() / 2 {
                let n = entry.read(&mut chunk).unwrap();
                read.extend_from_slice(&chunk[..n]);
                // Well past the budget in total, because reads aside are not charged.
                assert_eq!(zip.read_aside(Some(&mut entry), "pic.png").unwrap(), b"picture");
            }
            entry.read_to_end(&mut read).unwrap();
            zip.finish(entry);
            assert!(read == long, "the stream must continue unchanged");
            assert_eq!(zip.read_aside(None, "pic.png").unwrap(), b"picture");
        }
    }

    #[test]
    fn a_failed_read_aside_leaves_the_stream_usable() {
        let bytes = testing::zip_with(&[("a.txt", b"alpha"), ("b.png", b"beta")], false);
        let mut zip = open(bytes, Limits::default()).unwrap();
        let mut entry = zip.stream("a.txt").unwrap();
        assert!(zip.read_aside(Some(&mut entry), "missing.png").is_err());
        let mut read = Vec::new();
        entry.read_to_end(&mut read).unwrap();
        zip.finish(entry);
        assert_eq!(read, b"alpha");
    }

    #[test]
    fn detects_corruption() {
        let mut bytes = testing::zip_with(&[("a.txt", b"alpha")], false);
        bytes[30 + 5] = b'X'; // flip a data byte: CRC mismatch
        let mut zip = open(bytes, Limits::default()).unwrap();
        assert_eq!(zip.read_to_vec("a.txt").unwrap_err().kind, ErrorKind::Malformed);
        assert!(open(b"PK\x03\x04garbage".to_vec(), Limits::default()).is_err());
    }

    #[test]
    fn refuses_decompression_bombs() {
        let bomb = vec![0u8; 8 * 1024 * 1024];
        let bytes = testing::zip(&[("bomb.xml", &bomb)]);
        let limits = Limits { max_part_bytes: u64::MAX, ..Limits::default() };
        let err = open(bytes.clone(), limits).unwrap().read_to_vec("bomb.xml").unwrap_err();
        assert_eq!(err.kind, ErrorKind::LimitExceeded, "{err}");

        let limits = Limits { max_decompressed_bytes: 1024, max_compression_ratio: u64::MAX, ..limits };
        let err = open(bytes, limits).unwrap().read_to_vec("bomb.xml").unwrap_err();
        assert_eq!(err.kind, ErrorKind::LimitExceeded, "{err}");
    }

    #[test]
    fn enforces_entry_and_part_limits() {
        let bytes = testing::zip(&[("a", b"1"), ("b", b"2"), ("c", b"3")]);
        let err = open(bytes.clone(), Limits { max_archive_entries: 2, ..Limits::default() }).err().unwrap();
        assert_eq!(err.kind, ErrorKind::LimitExceeded);
        let mut zip = open(bytes, Limits { max_part_bytes: 0, ..Limits::default() }).unwrap();
        assert_eq!(zip.read_to_vec("a").unwrap_err().kind, ErrorKind::LimitExceeded);
    }
}
