//! Bounded TAR, gzip TAR and 7z adapters for the archive browser.
use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::{self, Read, SeekFrom};
use std::path::Path;

use document_core::{CancelToken, EngineError, ErrorKind, ReadSeek, Result};
use flate2::read::MultiGzDecoder;
use sevenz_rust2::{ArchiveReader, Password};

use crate::archive_index::{
    ArchiveEntryInfo, ExtractProgress, ExtractSnapshot, MAX_BATCH_BYTES, MAX_BATCH_ENTRIES, MAX_ENTRY_BYTES,
    copy_entry, safe_relative, staging_target,
};

const MAX_SCAN_BYTES: u64 = MAX_BATCH_BYTES + 64 * 1024 * 1024;
const SEVEN_Z_MEMORY_KIB: usize = 64 * 1024;

#[derive(Clone, Copy)]
pub(crate) enum StreamFormat {
    Tar,
    TarGzip,
    SevenZip,
}

enum Reader {
    Tar(Box<dyn ReadSeek>, StreamFormat),
    Seven(ArchiveReader<Box<dyn ReadSeek>>),
}

pub(crate) struct StreamIndex {
    reader: Reader,
    entries: Vec<ArchiveEntryInfo>,
    cancel: CancelToken,
}

impl StreamIndex {
    pub(crate) fn open(
        mut file: Box<dyn ReadSeek>,
        file_size: u64,
        format: StreamFormat,
        cancel: CancelToken,
    ) -> Result<Self> {
        if file_size > MAX_SCAN_BYTES {
            return Err(EngineError::limit("Archive exceeds the scan limit"));
        }
        let mut entries = Vec::new();
        let mut names = HashSet::new();
        let mut add = |entry: ArchiveEntryInfo| {
            cancel.check()?;
            if entries.len() >= MAX_BATCH_ENTRIES {
                return Err(EngineError::limit("Archive contains too many entries"));
            }
            let relative = safe_relative(&entry.path);
            if let Some(relative) = relative {
                if !names.insert(relative) {
                    return Err(EngineError::malformed("Archive contains duplicate entry paths"));
                }
                entries.push(entry);
            }
            Ok(())
        };
        let reader = match format {
            StreamFormat::SevenZip => {
                let mut reader = ArchiveReader::new_with_memory_limit(file, Password::empty(), SEVEN_Z_MEMORY_KIB)
                    .map_err(seven_error)?;
                reader.set_thread_count(1);
                let mut expanded = 0u64;
                if reader.archive().files.len() > MAX_BATCH_ENTRIES {
                    return Err(EngineError::limit("Archive contains too many entries"));
                }
                for entry in &reader.archive().files {
                    expanded = expanded.saturating_add(entry.size);
                    if expanded > MAX_BATCH_BYTES {
                        return Err(EngineError::limit("7z archive expands beyond the scan limit"));
                    }
                    if let Some(info) = seven_entry(entry) {
                        add(info)?;
                    }
                }
                Reader::Seven(reader)
            }
            _ => {
                walk_tar(&mut file, format, MAX_SCAN_BYTES, &cancel, |entry, _| add(entry))?;
                Reader::Tar(file, format)
            }
        };
        Ok(Self { reader, entries, cancel })
    }

    pub(crate) fn entries(&self) -> &[ArchiveEntryInfo] {
        &self.entries
    }

    pub(crate) fn extract(
        &mut self,
        paths: &[String],
        dest: &Path,
        progress: &ExtractProgress,
    ) -> Result<ExtractSnapshot> {
        if paths.len() > MAX_BATCH_ENTRIES {
            return Err(EngineError::limit("Too many entries selected"));
        }
        let entries: HashMap<_, _> = self.entries.iter().map(|entry| (entry.path.as_str(), entry)).collect();
        let mut wanted = HashSet::new();
        let mut total = 0u64;
        for path in paths {
            let entry = entries
                .get(path.as_str())
                .ok_or_else(|| EngineError::malformed("Entry is missing or has an unsafe path"))?;
            if wanted.insert(path.as_str()) {
                total = total.saturating_add(entry.size);
            }
            if entry.size > MAX_ENTRY_BYTES || total > MAX_BATCH_BYTES {
                return Err(EngineError::limit("Archive selection exceeds the extraction limit"));
            }
        }
        progress.start(total, wanted.len() as u32);
        fs::create_dir_all(dest)?;
        let cancel = &self.cancel;
        let mut extract = |entry: ArchiveEntryInfo, reader: &mut dyn Read| {
            cancel.check()?;
            if !wanted.remove(entry.path.as_str()) {
                return Ok(());
            }
            let path = safe_relative(&entry.path).ok_or_else(|| EngineError::malformed("Unsafe archive path"))?;
            let target = staging_target(dest, &path)?;
            if entry.is_dir {
                fs::create_dir_all(target)?;
            } else {
                let size_before = progress.snapshot().bytes_done;
                copy_entry(reader, &target, progress, cancel)?;
                if progress.snapshot().bytes_done - size_before != entry.size {
                    let _ = fs::remove_file(target);
                    return Err(EngineError::malformed("Archive entry length does not match its header"));
                }
            }
            progress.finish_entry();
            Ok(())
        };
        match &mut self.reader {
            Reader::Tar(file, format) => {
                walk_tar(file, *format, MAX_SCAN_BYTES, cancel, &mut extract)?;
            }
            Reader::Seven(reader) => {
                let mut error = None;
                let result = reader.for_each_entries(|entry, source| {
                    let step: Result<()> = (|| {
                        cancel.check()?;
                        if let Some(entry) = seven_entry(entry) {
                            extract(entry, source)?;
                        }
                        // The reader drains unselected data before the next member in a solid block.
                        // Do that here so cancellation can interrupt the drain.
                        let mut buffer = [0; 16 * 1024];
                        loop {
                            cancel.check()?;
                            if source.read(&mut buffer)? == 0 {
                                break;
                            }
                        }
                        Ok(())
                    })();
                    if let Err(failure) = step {
                        error = Some(failure);
                        return Err(io::Error::other("Archive extraction stopped").into());
                    }
                    Ok(true)
                });
                if let Some(error) = error {
                    return Err(error);
                }
                result.map_err(seven_error)?;
            }
        }
        if !wanted.is_empty() {
            return Err(EngineError::malformed("Some selected entries are missing"));
        }
        Ok(progress.snapshot())
    }
}

fn seven_entry(entry: &sevenz_rust2::ArchiveEntry) -> Option<ArchiveEntryInfo> {
    // Reject anti-items, reparse points and Unix symlinks.
    let attributes = entry.windows_attributes;
    if entry.is_anti_item || attributes & 0x400 != 0 || (attributes >> 16) & 0xf000 == 0xa000 {
        return None;
    }
    Some(entry_info(entry.name.clone(), entry.size, entry.compressed_size, entry.is_directory))
}

fn seven_error(error: sevenz_rust2::Error) -> EngineError {
    match error {
        sevenz_rust2::Error::PasswordRequired => {
            EngineError::new(ErrorKind::UnsupportedEncryption, "Encrypted 7z archives are unsupported")
        }
        sevenz_rust2::Error::MaxMemLimited { .. } => EngineError::limit(error.to_string()),
        _ => EngineError::malformed(error.to_string()),
    }
}

fn walk_tar(
    file: &mut dyn ReadSeek,
    format: StreamFormat,
    limit: u64,
    cancel: &CancelToken,
    mut visit: impl FnMut(ArchiveEntryInfo, &mut dyn Read) -> Result<()>,
) -> Result<()> {
    file.seek(SeekFrom::Start(0))?;
    let mut input: Box<dyn Read + '_> = match format {
        StreamFormat::TarGzip => Box::new(MultiGzDecoder::new(file)),
        _ => Box::new(file),
    };
    let mut archive = tar::Archive::new(ScanReader { input: &mut input, left: limit, cancel });
    let result: Result<()> = (|| {
        let mut name = None;
        let mut pax_size = None;
        for (index, entry) in archive.entries()?.raw(true).enumerate() {
            cancel.check()?;
            if index >= MAX_BATCH_ENTRIES * 3 {
                return Err(EngineError::limit("Archive contains too many records"));
            }
            let mut entry = entry?;
            let kind = entry.header().entry_type();
            if kind.is_gnu_longname()
                || kind.is_gnu_longlink()
                || kind.is_pax_local_extensions()
                || kind.is_pax_global_extensions()
            {
                if entry.size() > 64 * 1024 {
                    return Err(EngineError::limit("TAR extended metadata exceeds 64 KiB"));
                }
                if kind.is_gnu_longname() {
                    let mut path = Vec::new();
                    entry.read_to_end(&mut path)?;
                    while path.last() == Some(&0) {
                        path.pop();
                    }
                    name = Some(utf8_path(&path)?);
                } else if kind.is_pax_local_extensions() || kind.is_pax_global_extensions() {
                    if let Some(extensions) = entry.pax_extensions()? {
                        for field in extensions {
                            let field = field?;
                            match field.key_bytes() {
                                b"path" if kind.is_pax_local_extensions() => {
                                    name = Some(utf8_path(field.value_bytes())?)
                                }
                                b"size" if kind.is_pax_local_extensions() => {
                                    pax_size = Some(
                                        field
                                            .value()
                                            .ok()
                                            .and_then(|value| value.parse::<u64>().ok())
                                            .ok_or_else(|| EngineError::malformed("Invalid PAX size"))?,
                                    );
                                }
                                b"path" | b"size" => {
                                    return Err(EngineError::unsupported("Global PAX path and size are unsupported"));
                                }
                                _ => {}
                            }
                        }
                    }
                }
                continue;
            }
            if pax_size.take().is_some_and(|size| size != entry.size()) {
                return Err(EngineError::unsupported("PAX size overrides are unsupported"));
            }
            let extended_name = name.take();
            if kind.is_file() || kind.is_dir() {
                let path = match extended_name {
                    Some(path) => path,
                    None => utf8_path(&entry.path_bytes())?,
                };
                visit(entry_info(path, entry.size(), 0, kind.is_dir()), &mut entry)?;
            }
        }
        Ok(())
    })();
    cancel.check()?;
    result?;
    // TAR can reach its end marker before gzip has validated its checksum.
    if matches!(format, StreamFormat::TarGzip) {
        let mut remaining = archive.into_inner();
        io::copy(&mut remaining, &mut io::sink()).map_err(|err| cancel.check().err().unwrap_or_else(|| err.into()))?;
    }
    Ok(())
}

fn utf8_path(bytes: &[u8]) -> Result<String> {
    std::str::from_utf8(bytes)
        .map(str::to_owned)
        .map_err(|_| EngineError::unsupported("TAR entries must use UTF-8 names"))
}

fn entry_info(path: String, size: u64, compressed_size: u64, is_dir: bool) -> ArchiveEntryInfo {
    ArchiveEntryInfo {
        path,
        size: if is_dir { 0 } else { size },
        compressed_size,
        is_dir,
        encrypted: false,
        modified_dos: 0,
    }
}

struct ScanReader<'a> {
    input: &'a mut dyn Read,
    left: u64,
    cancel: &'a CancelToken,
}

impl Read for ScanReader<'_> {
    fn read(&mut self, bytes: &mut [u8]) -> io::Result<usize> {
        self.cancel.check().map_err(io::Error::other)?;
        if bytes.is_empty() {
            return Ok(0);
        }
        if self.left == 0 {
            return Err(io::Error::other("Archive exceeds the decompression scan limit"));
        }
        let capacity = bytes.len().min(self.left as usize);
        let count = self.input.read(&mut bytes[..capacity])?;
        self.left -= count as u64;
        Ok(count)
    }
}

/// Recognizes POSIX and pre-ustar archives by their header checksum.
pub(crate) fn is_tar_header(header: &[u8; 512]) -> bool {
    if header.iter().all(|&byte| byte == 0) {
        return true;
    }
    let Ok(value) = std::str::from_utf8(&header[148..156]) else { return false };
    let Ok(expected) = u32::from_str_radix(value.trim_matches([' ', '\0']), 8) else { return false };
    let actual = header[..148].iter().chain(&header[156..]).map(|&byte| u32::from(byte)).sum::<u32>() + 8 * 32;
    expected == actual
}
