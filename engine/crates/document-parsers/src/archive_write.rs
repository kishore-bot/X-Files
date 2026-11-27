//! Writer for the archive tools. Callers provide owned local inputs and a private output path.
use std::collections::HashSet;
use std::fs::{self, File};
use std::io::{self, BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};

use ::zip::{AesMode, CompressionMethod, ZipWriter, write::FileOptions};
use document_core::{CancelToken, EngineError, Result};
use flate2::{Compression, write::GzEncoder};
use sevenz_rust2::{ArchiveEntry, ArchiveWriter};

use crate::archive_index::{ExtractProgress, MAX_BATCH_BYTES, MAX_BATCH_ENTRIES, MAX_ENTRY_BYTES, safe_relative};

#[derive(Clone, Copy)]
pub enum ArchiveFormat {
    Zip,
    Tar,
    TarGzip,
    SevenZip,
}

struct Input {
    local: PathBuf,
    name: String,
    size: u64,
    directory: bool,
}

pub fn create(
    format: ArchiveFormat,
    sources: &[PathBuf],
    output: &Path,
    password: Option<&str>,
    cancel: &CancelToken,
    progress: &ExtractProgress,
) -> Result<usize> {
    if password.is_some() && (!matches!(format, ArchiveFormat::Zip) || password == Some("")) {
        return Err(EngineError::malformed("Enter a password for ZIP encryption"));
    }
    let mut entries = Vec::new();
    let mut names = HashSet::new();
    for source in sources {
        let name = source
            .file_name()
            .and_then(|it| it.to_str())
            .ok_or_else(|| EngineError::malformed("Invalid source name"))?;
        let name = if names.contains(name) {
            let stem = source.file_stem().and_then(|it| it.to_str()).unwrap_or(name);
            let extension =
                source.extension().and_then(|it| it.to_str()).map(|it| format!(".{it}")).unwrap_or_default();
            (2..)
                .map(|index| format!("{stem} ({index}){extension}"))
                .find(|candidate| !names.contains(candidate))
                .expect("archive names are bounded")
        } else {
            name.to_owned()
        };
        collect(source, &name, 0, &mut names, &mut entries, cancel)?;
    }
    if entries.is_empty() {
        return Err(EngineError::malformed("Pick files or folders to archive"));
    }
    let total = entries.iter().filter(|entry| !entry.directory).try_fold(0u64, |total, entry| {
        let next = total.saturating_add(entry.size);
        if entry.size > MAX_ENTRY_BYTES || next > MAX_BATCH_BYTES {
            return Err(EngineError::limit("An input exceeds the archive size limit"));
        }
        Ok(next)
    })?;
    progress.start(total, entries.len() as u32);
    let file = BufWriter::new(File::options().write(true).create_new(true).open(output)?);
    let result = write(format, file, &entries, password, cancel, progress).and_then(|()| cancel.check());
    if result.is_err() {
        let _ = fs::remove_file(output);
    }
    result.map(|()| entries.len())
}

fn collect(
    path: &Path,
    name: &str,
    depth: u32,
    names: &mut HashSet<String>,
    entries: &mut Vec<Input>,
    cancel: &CancelToken,
) -> Result<()> {
    cancel.check()?;
    if depth > 64 || entries.len() >= MAX_BATCH_ENTRIES {
        return Err(EngineError::limit("The input folder is too large or deeply nested"));
    }
    let relative = safe_relative(name).ok_or_else(|| EngineError::malformed("A source has an invalid archive name"))?;
    if relative.to_str() != Some(name) || !names.insert(name.to_owned()) {
        return Err(EngineError::malformed("Source names must be unique and relative"));
    }
    let meta = fs::symlink_metadata(path)?;
    if !meta.is_file() && !meta.is_dir() {
        return Err(EngineError::unsupported("Only regular files and folders can be archived"));
    }
    entries.push(Input { local: path.into(), name: name.into(), size: meta.len(), directory: meta.is_dir() });
    if meta.is_dir() {
        for child in fs::read_dir(path)? {
            let child = child?;
            let component = child.file_name();
            let component = component.to_str().ok_or_else(|| EngineError::malformed("Invalid source name"))?;
            collect(&child.path(), &format!("{name}/{component}"), depth + 1, names, entries, cancel)?;
        }
    }
    Ok(())
}

fn write(
    format: ArchiveFormat,
    file: BufWriter<File>,
    entries: &[Input],
    password: Option<&str>,
    cancel: &CancelToken,
    progress: &ExtractProgress,
) -> Result<()> {
    match format {
        ArchiveFormat::Zip => {
            let mut zip = ZipWriter::new(file);
            for item in entries {
                cancel.check()?;
                let options = FileOptions::<()>::default();
                if item.directory {
                    zip.add_directory(&item.name, options).map_err(invalid)?;
                    progress.finish_entry();
                    continue;
                }
                let options = options.compression_method(CompressionMethod::Deflated);
                let options = match password {
                    Some(secret) => options.with_aes_encryption(AesMode::Aes256, secret),
                    None => options,
                };
                zip.start_file(&item.name, options).map_err(invalid)?;
                io::copy(&mut source(item, cancel, progress)?, &mut zip).map_err(|err| input_error(err, cancel))?;
                progress.finish_entry();
            }
            zip.finish().map_err(invalid)?.flush()?;
        }
        ArchiveFormat::Tar => write_tar(file, entries, cancel, progress)?.flush()?,
        ArchiveFormat::TarGzip => {
            write_tar(GzEncoder::new(file, Compression::default()), entries, cancel, progress)?.finish()?.flush()?;
        }
        ArchiveFormat::SevenZip => {
            let mut archive = ArchiveWriter::new(file).map_err(invalid)?;
            for item in entries {
                cancel.check()?;
                if item.directory {
                    archive
                        .push_archive_entry(ArchiveEntry::new_directory(&item.name), None::<CheckedInput<'_>>)
                        .map_err(invalid)?;
                } else {
                    let input = source(item, cancel, progress)?;
                    archive
                        .push_archive_entry(ArchiveEntry::new_file(&item.name), Some(input))
                        .map_err(|err| cancel.check().err().unwrap_or_else(|| invalid(err)))?;
                }
                progress.finish_entry();
            }
            archive.finish()?.flush()?;
        }
    }
    Ok(())
}

fn write_tar<W: Write>(file: W, entries: &[Input], cancel: &CancelToken, progress: &ExtractProgress) -> Result<W> {
    let mut tar = tar::Builder::new(file);
    for item in entries {
        cancel.check()?;
        let mut header = tar::Header::new_gnu();
        header.set_uid(0);
        header.set_gid(0);
        header.set_mtime(0);
        if item.directory {
            header.set_mode(0o755);
            header.set_size(0);
            header.set_entry_type(tar::EntryType::Directory);
            tar.append_data(&mut header, &item.name, io::empty())?;
        } else {
            header.set_mode(0o644);
            header.set_size(item.size);
            header.set_entry_type(tar::EntryType::Regular);
            tar.append_data(&mut header, &item.name, source(item, cancel, progress)?)
                .map_err(|err| input_error(err, cancel))?;
        }
        progress.finish_entry();
    }
    Ok(tar.into_inner()?)
}

struct CheckedInput<'a> {
    file: BufReader<File>,
    cancel: &'a CancelToken,
    progress: &'a ExtractProgress,
    remaining: u64,
}

impl Read for CheckedInput<'_> {
    fn read(&mut self, bytes: &mut [u8]) -> io::Result<usize> {
        // Interrupted is retried automatically by io::copy.
        self.cancel.check().map_err(io::Error::other)?;
        let limit = bytes.len().min(usize::try_from(self.remaining).unwrap_or(usize::MAX));
        if limit == 0 {
            return Ok(0);
        }
        let read = self.file.read(&mut bytes[..limit])?;
        if read == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "An archive input changed while reading"));
        }
        self.remaining -= read as u64;
        self.progress.advance(read as u64);
        Ok(read)
    }
}

fn source<'a>(item: &Input, cancel: &'a CancelToken, progress: &'a ExtractProgress) -> Result<CheckedInput<'a>> {
    Ok(CheckedInput { file: BufReader::new(File::open(&item.local)?), cancel, progress, remaining: item.size })
}

fn input_error(error: io::Error, cancel: &CancelToken) -> EngineError {
    cancel.check().err().unwrap_or_else(|| error.into())
}

fn invalid(error: impl std::fmt::Display) -> EngineError {
    EngineError::malformed(error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::archive_index::ArchiveIndex;
    use document_core::ErrorKind;

    #[test]
    fn writes_archives_that_the_existing_reader_can_extract() {
        let temp = tempfile::tempdir().unwrap();
        let source = temp.path().join("Documents");
        fs::create_dir_all(source.join("Empty")).unwrap();
        fs::write(source.join("évidence.txt"), b"Saved contents").unwrap();
        let cancel = CancelToken::default();

        for (index, (format, password)) in [
            (ArchiveFormat::Zip, Some("a private password")),
            (ArchiveFormat::Tar, None),
            (ArchiveFormat::TarGzip, None),
            (ArchiveFormat::SevenZip, None),
        ]
        .into_iter()
        .enumerate()
        {
            let archive = temp.path().join(format!("output-{index}"));
            let progress = ExtractProgress::default();
            create(format, std::slice::from_ref(&source), &archive, password, &cancel, &progress).unwrap();
            let file = File::open(archive).unwrap();
            let size = file.metadata().unwrap().len();
            let mut reader = ArchiveIndex::open(Box::new(file), size, cancel.clone()).unwrap();
            let paths = reader.entries().into_iter().map(|entry| entry.path).collect::<Vec<_>>();
            let output = temp.path().join(format!("extract-{index}"));

            if password.is_some() {
                assert!(reader.entries().iter().any(|entry| entry.encrypted));
                let error = reader.extract_to_dir(&paths, &output, &progress, Some(b"wrong")).unwrap_err();
                assert_eq!(error.kind, ErrorKind::WrongPassword);
            }
            reader.extract_to_dir(&paths, &output, &progress, password.map(str::as_bytes)).unwrap();
            assert_eq!(fs::read(output.join("Documents/évidence.txt")).unwrap(), b"Saved contents");
            assert!(output.join("Documents/Empty").is_dir());
        }
    }
}
