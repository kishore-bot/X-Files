//! Read-only archive facade for ZIP, TAR, TAR.GZ and 7z.
//!
//! Two operations leave the engine: the entry index (central directory only,
//! nothing decompressed) and bounded extraction of selected entries to a
//! staging directory. Every guard for the second lives here: all names, sizes
//! and passwords are validated before the first byte is written, and the
//! decompression ratio and CRC are enforced on every path.
//!
//! Extraction is sequential and holds the archive for its duration; a second
//! call on the same [`ArchiveIndex`] waits for the first.

use std::fs::{self, File};
use std::io::{BufWriter, Read, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};

use document_core::{CancelToken, EngineError, ErrorKind, Limits, ReadSeek, Result};

use crate::archive_crypto::PlainEntry;
use crate::zip::{ZipArchive, ZipEntry, from_io};

/// Largest single entry that may be extracted.
pub const MAX_ENTRY_BYTES: u64 = 512 * 1024 * 1024;
/// Largest total declared size one extraction call may write.
pub const MAX_BATCH_BYTES: u64 = 2 * 1024 * 1024 * 1024;
/// Largest number of entries one extraction call may name.
pub const MAX_BATCH_ENTRIES: usize = 20_000;
/// Entries below this declared size skip the ratio pre-check, as the reader does.
const RATIO_FLOOR: u64 = 1024 * 1024;
const COPY_BUFFER: usize = 64 * 1024;

/// One entry as the browser sees it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArchiveEntryInfo {
    pub path: String,
    pub size: u64,
    pub compressed_size: u64,
    pub is_dir: bool,
    pub encrypted: bool,
    /// MS-DOS time (low 16 bits) and date (high 16 bits).
    pub modified_dos: u32,
}

/// Live counters for one extraction call. Read without locking the archive.
#[derive(Debug, Default)]
pub struct ExtractProgress {
    bytes_done: AtomicU64,
    bytes_total: AtomicU64,
    entries_done: AtomicU32,
    entries_total: AtomicU32,
}

/// A point-in-time copy of [`ExtractProgress`].
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ExtractSnapshot {
    pub bytes_done: u64,
    pub bytes_total: u64,
    pub entries_done: u32,
    pub entries_total: u32,
}

impl ExtractProgress {
    pub fn snapshot(&self) -> ExtractSnapshot {
        ExtractSnapshot {
            bytes_done: self.bytes_done.load(Ordering::Relaxed),
            bytes_total: self.bytes_total.load(Ordering::Relaxed),
            entries_done: self.entries_done.load(Ordering::Relaxed),
            entries_total: self.entries_total.load(Ordering::Relaxed),
        }
    }

    pub(crate) fn advance(&self, bytes: u64) {
        self.bytes_done.fetch_add(bytes, Ordering::Relaxed);
    }

    pub(crate) fn finish_entry(&self) {
        self.entries_done.fetch_add(1, Ordering::Relaxed);
    }

    pub(crate) fn start(&self, bytes_total: u64, entries_total: u32) {
        self.bytes_done.store(0, Ordering::Relaxed);
        self.entries_done.store(0, Ordering::Relaxed);
        self.bytes_total.store(bytes_total, Ordering::Relaxed);
        self.entries_total.store(entries_total, Ordering::Relaxed);
    }
}

/// Format dispatch for the existing archive browser, UniFFI session, and Swift bridge.
pub struct ArchiveIndex {
    backend: Backend,
}

enum Backend {
    Zip(ZipIndex),
    Stream(crate::archive_stream::StreamIndex),
}

impl ArchiveIndex {
    pub fn open(mut reader: Box<dyn ReadSeek>, byte_size: u64, cancel: CancelToken) -> Result<Self> {
        use crate::archive_stream::{StreamFormat, StreamIndex};
        let mut signature = [0u8; 6];
        let size = (byte_size.min(signature.len() as u64)) as usize;
        reader.read_exact(&mut signature[..size])?;
        reader.seek(SeekFrom::Start(0))?;
        let stream = if size >= 6 && signature == *b"7z\xBC\xAF\x27\x1C" {
            Some(StreamFormat::SevenZip)
        } else if size >= 2 && signature.starts_with(&[0x1f, 0x8b]) {
            Some(StreamFormat::TarGzip)
        } else if !matches!(&signature[..4], b"PK\x03\x04" | b"PK\x05\x06" | b"PK\x07\x08") && byte_size >= 512 {
            let mut header = [0; 512];
            reader.read_exact(&mut header)?;
            reader.seek(SeekFrom::Start(0))?;
            crate::archive_stream::is_tar_header(&header).then_some(StreamFormat::Tar)
        } else {
            None
        };
        let backend = match stream {
            Some(format) => Backend::Stream(StreamIndex::open(reader, byte_size, format, cancel)?),
            None => Backend::Zip(ZipIndex::open(reader, byte_size, cancel)?),
        };
        Ok(Self { backend })
    }

    pub fn entries(&self) -> Vec<ArchiveEntryInfo> {
        match &self.backend {
            Backend::Zip(index) => index.entries(),
            Backend::Stream(index) => index.entries().to_vec(),
        }
    }

    pub fn extract_to_dir(&mut self, names: &[String], dest: &Path, progress: &ExtractProgress, password: Option<&[u8]>) -> Result<ExtractSnapshot> {
        match &mut self.backend {
            Backend::Zip(index) => index.extract_to_dir(names, dest, progress, password),
            Backend::Stream(index) => index.extract(names, dest, progress),
        }
    }
}

struct ZipIndex {
    archive: ZipArchive,
    cancel: CancelToken,
}

impl ZipIndex {
    /// Reads the ZIP central directory. The session's decompression budget is the
    /// extraction batch cap, so a single call can never write more than that.
    pub fn open(reader: Box<dyn ReadSeek>, byte_size: u64, cancel: CancelToken) -> Result<Self> {
        let limits = Limits { max_decompressed_bytes: MAX_BATCH_BYTES, ..Limits::default() };
        let archive = ZipArchive::open(reader, byte_size, limits, cancel.clone())?;
        Ok(Self { archive, cancel })
    }

    pub fn entries(&self) -> Vec<ArchiveEntryInfo> {
        self.archive.entries().iter().map(info_of).collect()
    }

    /// Writes the named entries under `dest`, keeping their relative paths.
    ///
    /// Fails before writing anything if a name is unsafe or missing, if an
    /// encrypted entry is named without a password, or if a declared size
    /// exceeds a cap. A wrong password fails on the first encrypted entry
    /// before its file is created. Returns the final counters.
    pub fn extract_to_dir(
        &mut self,
        names: &[String],
        dest: &Path,
        progress: &ExtractProgress,
        password: Option<&[u8]>,
    ) -> Result<ExtractSnapshot> {
        if names.len() > MAX_BATCH_ENTRIES {
            return Err(EngineError::limit(format!(
                "{} entries were requested; one extraction takes at most {MAX_BATCH_ENTRIES}",
                names.len()
            )));
        }
        let plan = self.plan(names, password.is_some())?;
        let total: u64 = plan.iter().filter(|(_, info)| !info.is_dir).map(|(_, info)| info.size).sum();
        progress.start(total, plan.len() as u32);

        fs::create_dir_all(dest)?;
        for (relative, info) in &plan {
            self.cancel.check()?;
            let target = staging_target(dest, relative)?;
            if info.is_dir {
                fs::create_dir_all(&target)?;
            } else {
                if let Some(parent) = target.parent() {
                    fs::create_dir_all(parent)?;
                }
                let written = if info.encrypted {
                    // `plan` guarantees a password is present for encrypted entries.
                    let password = password.unwrap_or_default();
                    self.write_encrypted(&info.path, &target, progress, password)
                } else {
                    self.write_plain(&info.path, &target, progress)
                };
                written?;
            }
            progress.entries_done.fetch_add(1, Ordering::Relaxed);
        }
        Ok(progress.snapshot())
    }

    fn write_plain(&mut self, name: &str, target: &Path, progress: &ExtractProgress) -> Result<()> {
        let mut reader = self.archive.stream(name)?;
        let written = copy_entry(&mut reader, target, progress, &self.cancel);
        self.archive.finish(reader);
        written
    }

    fn write_encrypted(&mut self, name: &str, target: &Path, progress: &ExtractProgress, password: &[u8]) -> Result<()> {
        let (entry, raw) = self.archive.take_raw(name)?;
        match PlainEntry::open(raw, &entry, password, self.cancel.clone()) {
            Err((error, raw)) => {
                self.archive.restore_reader(raw);
                Err(error)
            }
            Ok(mut plain) => {
                let written = copy_entry(&mut plain, target, progress, &self.cancel);
                self.archive.restore_reader(plain.into_reader());
                written
            }
        }
    }

    fn plan(&self, names: &[String], has_password: bool) -> Result<Vec<(PathBuf, ArchiveEntryInfo)>> {
        let ratio = Limits::default().max_compression_ratio;
        let mut plan = Vec::with_capacity(names.len());
        let mut total = 0u64;
        for name in names {
            let entry = self
                .archive
                .find(name)
                .ok_or_else(|| EngineError::new(ErrorKind::Malformed, format!("{name} is not in the archive")))?;
            let info = info_of(entry);
            let relative = safe_relative(&info.path).ok_or_else(|| {
                EngineError::malformed(format!("{} has an unsafe path and was not extracted", info.path))
            })?;
            if info.encrypted && !has_password {
                return Err(EngineError::new(
                    ErrorKind::PasswordRequired,
                    format!("{} is password-protected", info.path),
                ));
            }
            if !info.is_dir {
                if info.size > MAX_ENTRY_BYTES {
                    return Err(EngineError::limit(format!(
                        "{} is {} bytes; the extraction limit per file is {MAX_ENTRY_BYTES}",
                        info.path, info.size
                    )));
                }
                if info.size > RATIO_FLOOR && info.size > info.compressed_size.max(1).saturating_mul(ratio) {
                    return Err(EngineError::limit(format!(
                        "{} is compressed suspiciously well; refusing to expand it",
                        info.path
                    )));
                }
                total = total.saturating_add(info.size);
                if total > MAX_BATCH_BYTES {
                    return Err(EngineError::limit("the selection is larger than one extraction may write"));
                }
            }
            plan.push((relative, info));
        }
        Ok(plan)
    }
}

fn info_of(entry: &ZipEntry) -> ArchiveEntryInfo {
    ArchiveEntryInfo {
        path: entry.name.clone(),
        size: entry.size,
        compressed_size: entry.compressed_size,
        is_dir: entry.is_dir,
        encrypted: entry.encrypted,
        modified_dos: entry.modified_dos,
    }
}

pub(crate) fn staging_target(root: &Path, relative: &Path) -> Result<PathBuf> {
    let mut current = root.to_owned();
    let is_directory = |path: &Path| -> Result<()> {
        match fs::symlink_metadata(path) {
            Ok(metadata) if metadata.is_dir() => Ok(()),
            Ok(_) => Err(EngineError::malformed("Archive destination contains an unsafe path")),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                fs::create_dir(path)?;
                Ok(())
            }
            Err(error) => Err(error.into()),
        }
    };
    is_directory(root)?;
    if let Some(parent) = relative.parent() {
        for component in parent.components() {
            current.push(component);
            is_directory(&current)?;
        }
    }
    let target = root.join(relative);
    if fs::symlink_metadata(&target).is_ok_and(|metadata| metadata.is_symlink()) {
        return Err(EngineError::malformed("Archive destination contains a symbolic link"));
    }
    Ok(target)
}

pub(crate) fn copy_entry<R: Read + ?Sized>(reader: &mut R, target: &Path, progress: &ExtractProgress, cancel: &CancelToken) -> Result<()> {
    let file = File::options().write(true).create_new(true).open(target)?;
    let result: Result<()> = (|| {
        let mut out = BufWriter::with_capacity(COPY_BUFFER, file);
        let mut buffer = vec![0u8; COPY_BUFFER];
        loop {
            cancel.check()?;
            let read = reader.read(&mut buffer).map_err(|e| from_io(&e))?;
            if read == 0 {
                break;
            }
            out.write_all(&buffer[..read])?;
            progress.advance(read as u64);
        }
        out.flush()?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(target);
    }
    result
}

/// Turns an archive name into a relative path that cannot escape its folder.
///
/// Rejects NUL, backslashes, drive or stream markers (`:`), and any `..`
/// component. Empty and `.` components are dropped, so `a//./b` becomes `a/b`.
pub fn safe_relative(name: &str) -> Option<PathBuf> {
    if name.is_empty() || name.starts_with('/') || name.len() > 4096 || name.contains(['\0', '\\', ':']) {
        return None;
    }
    let mut out = PathBuf::new();
    for part in name.split('/') {
        match part {
            "" | "." => {}
            ".." => return None,
            part => out.push(part),
        }
    }
    if out.as_os_str().is_empty() { None } else { Some(out) }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;
    use crate::archive_crypto::test_support::{winzip_aes_seal, zipcrypto_seal};

    #[test]
    fn rejects_traversal_and_markers() {
        for bad in ["../x", "a/../../x", "C:x", "a\\..\\b", "", "./", "x\0y"] {
            assert!(safe_relative(bad).is_none(), "{bad:?} should be rejected");
        }
    }

    #[test]
    fn keeps_plain_relative_paths() {
        assert_eq!(safe_relative("a//./b.txt").unwrap(), PathBuf::from("a").join("b.txt"));
    }

    /// One entry for the in-test zip writer.
    struct Fixture<'a> {
        name: &'a str,
        method: u16,
        flags: u16,
        crc: u32,
        size: u32,
        data: Vec<u8>,
        /// Central-directory extra field; a non-empty value also sets method 99 (WinZip AES).
        extra: Vec<u8>,
    }

    /// Writes a minimal single-disk zip: local headers, central directory, end record.
    fn build_zip(entries: &[Fixture]) -> Vec<u8> {
        let mut out = Vec::new();
        let mut central = Vec::new();
        for entry in entries {
            let offset = out.len() as u32;
            let name = entry.name.as_bytes();
            out.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
            out.extend_from_slice(&20u16.to_le_bytes());
            out.extend_from_slice(&entry.flags.to_le_bytes());
            out.extend_from_slice(&entry.method.to_le_bytes());
            out.extend_from_slice(&[0; 4]); // time, date
            out.extend_from_slice(&entry.crc.to_le_bytes());
            out.extend_from_slice(&(entry.data.len() as u32).to_le_bytes());
            out.extend_from_slice(&entry.size.to_le_bytes());
            out.extend_from_slice(&(name.len() as u16).to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(name);
            out.extend_from_slice(&entry.data);

            let method = if entry.extra.is_empty() { entry.method } else { 99 };
            central.extend_from_slice(&0x0201_4b50u32.to_le_bytes());
            central.extend_from_slice(&20u16.to_le_bytes());
            central.extend_from_slice(&20u16.to_le_bytes());
            central.extend_from_slice(&entry.flags.to_le_bytes());
            central.extend_from_slice(&method.to_le_bytes());
            central.extend_from_slice(&[0; 4]);
            central.extend_from_slice(&entry.crc.to_le_bytes());
            central.extend_from_slice(&(entry.data.len() as u32).to_le_bytes());
            central.extend_from_slice(&entry.size.to_le_bytes());
            central.extend_from_slice(&(name.len() as u16).to_le_bytes());
            central.extend_from_slice(&(entry.extra.len() as u16).to_le_bytes());
            central.extend_from_slice(&[0; 10]); // comment, disk, attributes
            central.extend_from_slice(&offset.to_le_bytes());
            central.extend_from_slice(name);
            central.extend_from_slice(&entry.extra);
        }
        let cd_offset = out.len() as u32;
        let cd_size = central.len() as u32;
        out.extend_from_slice(&central);
        out.extend_from_slice(&0x0605_4b50u32.to_le_bytes());
        out.extend_from_slice(&[0; 4]);
        out.extend_from_slice(&(entries.len() as u16).to_le_bytes());
        out.extend_from_slice(&(entries.len() as u16).to_le_bytes());
        out.extend_from_slice(&cd_size.to_le_bytes());
        out.extend_from_slice(&cd_offset.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out
    }

    /// The WinZip AES 0x9901 extra field: vendor AE, strength, real method.
    fn aes_extra_block(strength: u8, method: u16) -> Vec<u8> {
        let mut block = Vec::new();
        block.extend_from_slice(&0x9901u16.to_le_bytes());
        block.extend_from_slice(&7u16.to_le_bytes());
        block.extend_from_slice(&2u16.to_le_bytes());
        block.extend_from_slice(b"AE");
        block.push(strength);
        block.extend_from_slice(&method.to_le_bytes());
        block
    }

    fn crc_of(bytes: &[u8]) -> u32 {
        let mut hasher = crc32fast::Hasher::new();
        hasher.update(bytes);
        hasher.finalize()
    }

    fn scratch(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("archive-index-{}-{tag}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    fn open(bytes: Vec<u8>) -> ArchiveIndex {
        let len = bytes.len() as u64;
        ArchiveIndex::open(Box::new(Cursor::new(bytes)), len, CancelToken::default()).unwrap()
    }

    #[test]
    fn extracts_a_zipcrypto_entry_and_rejects_a_wrong_password() {
        let plain = b"zipcrypto payload".to_vec();
        let crc = crc_of(&plain);
        let encrypted = zipcrypto_seal(b"pw", (crc >> 24) as u8, &plain);
        let mut index = open(build_zip(&[Fixture {
            name: "secret.txt",
            method: 0,
            flags: 1,
            crc,
            size: plain.len() as u32,
            data: encrypted,
            extra: Vec::new(),
        }]));
        let dir = scratch("zipcrypto");
        let progress = ExtractProgress::default();
        let names = vec!["secret.txt".to_string()];

        assert!(index.extract_to_dir(&names, &dir, &progress, None).is_err());
        let wrong = index.extract_to_dir(&names, &dir, &progress, Some(b"nope"));
        assert!(matches!(wrong.unwrap_err().kind, ErrorKind::WrongPassword));
        assert!(!dir.join("secret.txt").exists());

        index.extract_to_dir(&names, &dir, &progress, Some(b"pw")).unwrap();
        assert_eq!(fs::read(dir.join("secret.txt")).unwrap(), plain);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn extracts_a_winzip_aes_entry_and_removes_a_tampered_one() {
        let plain = b"aes payload for the archive browser".to_vec();
        let names = vec!["a.bin".to_string()];
        let progress = ExtractProgress::default();

        let sealed = winzip_aes_seal(b"pw", 3, &plain);
        let mut index = open(build_zip(&[Fixture {
            name: "a.bin",
            method: 0,
            flags: 1,
            crc: 0, // AE-2 stores no CRC; the HMAC covers integrity.
            size: plain.len() as u32,
            data: sealed.clone(),
            extra: aes_extra_block(3, 0),
        }]));
        let dir = scratch("aes");
        index.extract_to_dir(&names, &dir, &progress, Some(b"pw")).unwrap();
        assert_eq!(fs::read(dir.join("a.bin")).unwrap(), plain);
        let _ = fs::remove_dir_all(&dir);

        let mut tampered = sealed;
        let last = tampered.len() - 1;
        tampered[last] ^= 1;
        let mut index = open(build_zip(&[Fixture {
            name: "a.bin",
            method: 0,
            flags: 1,
            crc: 0,
            size: plain.len() as u32,
            data: tampered,
            extra: aes_extra_block(3, 0),
        }]));
        let dir = scratch("aes-tampered");
        assert!(index.extract_to_dir(&names, &dir, &progress, Some(b"pw")).is_err());
        assert!(!dir.join("a.bin").exists(), "a failed entry must not leave a file behind");
        let _ = fs::remove_dir_all(&dir);
    }
}
