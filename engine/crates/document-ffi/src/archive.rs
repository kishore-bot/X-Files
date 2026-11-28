//! Archive browser surface: index an archive once, then extract selected entries to
//! a staging folder that the app copies or opens.
//!
//! One [`ArchiveSession`] serves one operation. Opening takes a descriptor the
//! caller owns (a fresh `openFileDescriptor` per session, so sessions never
//! share a file offset). Index reads are cheap and extraction runs on the
//! calling thread, which the app dispatches on its IO lane. Progress and
//! cancellation use atomics, so polling and cancelling never wait for the
//! running extraction.

use std::fs::File;
use std::path::Path;
use std::sync::{Arc, Mutex};

use document_core::CancelToken;
use document_parsers::archive_index::{ArchiveEntryInfo, ArchiveIndex, ExtractProgress, ExtractSnapshot};
use zeroize::Zeroizing;

use crate::ReadError;

/// One archive entry. Directories have `size` 0.
#[derive(Debug, Clone, uniffi::Record)]
pub struct NativeArchiveEntry {
    pub path: String,
    pub size: u64,
    pub compressed_size: u64,
    pub is_dir: bool,
    pub encrypted: bool,
    /// MS-DOS time (low 16 bits) and date (high 16 bits).
    pub modified_dos: u32,
}

/// Counters for the current or last extraction.
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct NativeArchiveProgress {
    pub bytes_done: u64,
    pub bytes_total: u64,
    pub entries_done: u32,
    pub entries_total: u32,
}

impl From<ExtractSnapshot> for NativeArchiveProgress {
    fn from(s: ExtractSnapshot) -> Self {
        Self {
            bytes_done: s.bytes_done,
            bytes_total: s.bytes_total,
            entries_done: s.entries_done,
            entries_total: s.entries_total,
        }
    }
}

impl From<ArchiveEntryInfo> for NativeArchiveEntry {
    fn from(e: ArchiveEntryInfo) -> Self {
        Self {
            path: e.path,
            size: e.size,
            compressed_size: e.compressed_size,
            is_dir: e.is_dir,
            encrypted: e.encrypted,
            modified_dos: e.modified_dos,
        }
    }
}

/// An open archive. Calls on one session are serialized by its lock.
#[derive(uniffi::Object)]
pub struct ArchiveSession {
    index: Mutex<ArchiveIndex>,
    cancel: CancelToken,
    progress: ExtractProgress,
}

#[uniffi::export]
impl ArchiveSession {
    /// Every entry in central-directory order. The Kotlin side builds the
    /// folder tree from this list once, off the main thread.
    pub fn entries(&self) -> Result<Vec<NativeArchiveEntry>, ReadError> {
        let index = self.index.lock().map_err(|_| poisoned())?;
        Ok(index.entries().into_iter().map(NativeArchiveEntry::from).collect())
    }

    /// Writes the named entries under `staging_dir`, keeping relative paths.
    /// Blocks until done, failed or cancelled. Validation and size caps run
    /// before the first byte is written. `password` is used only for this
    /// call, is wiped afterwards, and is never stored.
    pub fn extract(
        &self,
        paths: Vec<String>,
        staging_dir: String,
        password: Option<String>,
    ) -> Result<NativeArchiveProgress, ReadError> {
        let password = password.map(Zeroizing::new);
        let mut index = self.index.lock().map_err(|_| poisoned())?;
        let snapshot = index.extract_to_dir(
            &paths,
            Path::new(&staging_dir),
            &self.progress,
            password.as_ref().map(|p| p.as_bytes()),
        )?;
        Ok(snapshot.into())
    }

    /// Lock-free: safe to call while `extract` runs.
    pub fn progress(&self) -> NativeArchiveProgress {
        self.progress.snapshot().into()
    }

    /// Lock-free. The running extraction stops at its next check point.
    pub fn cancel(&self) {
        self.cancel.cancel();
    }
}

/// Opens an archive over a descriptor the caller transfers. The descriptor is
/// closed on failure and when the session is released.
#[uniffi::export]
pub fn open_archive_fd(fd: i32) -> Result<Arc<ArchiveSession>, ReadError> {
    #[cfg(unix)]
    {
        use std::os::fd::FromRawFd;
        if fd < 0 {
            return Err(ReadError::Io { detail: "invalid file descriptor".into() });
        }
        // SAFETY: the caller transfers sole ownership of an open descriptor.
        let file = unsafe { File::from_raw_fd(fd) };
        open_archive_file(file, CancelToken::default())
    }
    #[cfg(not(unix))]
    {
        let _ = fd;
        Err(ReadError::Io { detail: "archive descriptors are only supported on Unix targets".into() })
    }
}

#[cfg(unix)]
pub(crate) fn open_archive_file(file: File, cancel: CancelToken) -> Result<Arc<ArchiveSession>, ReadError> {
    let byte_size = file.metadata().map_err(|e| ReadError::Io { detail: e.to_string() })?.len();
    let index = ArchiveIndex::open(Box::new(file), byte_size, cancel.clone())?;
    Ok(Arc::new(ArchiveSession { index: Mutex::new(index), cancel, progress: ExtractProgress::default() }))
}

fn poisoned() -> ReadError {
    ReadError::Internal { detail: "this archive hit an internal error earlier and cannot be used further".into() }
}

#[derive(Clone, Copy, uniffi::Enum)]
pub enum NativeArchiveFormat {
    Zip,
    Tar,
    TarGzip,
    SevenZip,
}

#[derive(uniffi::Object)]
pub struct ArchiveCreateJob {
    cancel: CancelToken,
    progress: ExtractProgress,
}

#[uniffi::export]
impl ArchiveCreateJob {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self { cancel: CancelToken::default(), progress: ExtractProgress::default() })
    }

    pub fn progress(&self) -> NativeArchiveProgress {
        self.progress.snapshot().into()
    }

    pub fn write(&self, sources: Vec<String>, output: String, format: NativeArchiveFormat, password: Option<String>) -> Result<u32, ReadError> {
        create_archive(sources, &output, format, password, &self.cancel, &self.progress)
    }

    pub fn cancel(&self) {
        self.cancel.cancel();
    }
}

pub(crate) fn create_archive(sources: Vec<String>, output: &str, format: NativeArchiveFormat, password: Option<String>, cancel: &CancelToken, progress: &ExtractProgress) -> Result<u32, ReadError> {
    use document_parsers::archive_write::{self, ArchiveFormat};
    let format = match format {
        NativeArchiveFormat::Zip => ArchiveFormat::Zip,
        NativeArchiveFormat::Tar => ArchiveFormat::Tar,
        NativeArchiveFormat::TarGzip => ArchiveFormat::TarGzip,
        NativeArchiveFormat::SevenZip => ArchiveFormat::SevenZip,
    };
    let sources = sources.into_iter().map(std::path::PathBuf::from).collect::<Vec<_>>();
    let password = password.map(Zeroizing::new);
    Ok(archive_write::create(format, &sources, Path::new(output), password.as_ref().map(|secret| secret.as_str()), cancel, progress)? as u32)
}
