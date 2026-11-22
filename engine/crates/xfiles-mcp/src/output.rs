//! Outputs are written beside their final name and renamed into place only after the whole
//! operation succeeded, so a failed or cancelled run never leaves a partial file behind.

use std::fs::{self, File, OpenOptions};
use std::path::{Path, PathBuf};
use std::process;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::error::{Result, ToolError};

static NONCE: AtomicU64 = AtomicU64::new(0);

fn unique_suffix() -> String {
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
    format!("{}-{nanos}-{}", process::id(), NONCE.fetch_add(1, Ordering::Relaxed))
}

/// A file being written. Dropping it without [`PendingOutput::commit`] deletes the partial file.
pub struct PendingOutput {
    target: PathBuf,
    temp: PathBuf,
    overwrite: bool,
    file: Option<File>,
}

impl PendingOutput {
    pub fn create(target: PathBuf, overwrite: bool) -> Result<Self> {
        let name = target
            .file_name()
            .map(|name| name.to_string_lossy().into_owned())
            .unwrap_or_else(|| "output".to_owned());
        let temp = target.with_file_name(format!(".{name}.{}.partial", unique_suffix()));
        let file = OpenOptions::new().write(true).create_new(true).open(&temp)?;
        Ok(Self { target, temp, overwrite, file: Some(file) })
    }

    /// The open temporary file. Fails only if the file was already closed by a commit.
    pub fn file(&mut self) -> Result<&mut File> {
        self.file
            .as_mut()
            .ok_or_else(|| ToolError::Internal("the output was already closed".to_owned()))
    }

    /// The temporary path, for checks that reopen the written data before commit.
    pub fn temp_path(&self) -> &Path {
        &self.temp
    }

    /// Moves the finished file to its final name.
    pub fn commit(mut self) -> Result<PathBuf> {
        drop(self.file.take());
        if !self.overwrite && self.target.exists() {
            return Err(ToolError::Exists(format!(
                "{} already exists; set overwrite to true to replace it",
                crate::sandbox::display(&self.target)
            )));
        }
        fs::rename(&self.temp, &self.target)?;
        Ok(self.target.clone())
    }
}

impl Drop for PendingOutput {
    fn drop(&mut self) {
        drop(self.file.take());
        let _ = fs::remove_file(&self.temp);
    }
}

/// A private folder for extraction, removed when dropped.
pub struct ScratchDir(PathBuf);

impl ScratchDir {
    pub fn create(parent: &Path) -> Result<Self> {
        let path = parent.join(format!(".xfiles-scratch-{}", unique_suffix()));
        fs::create_dir(&path)?;
        Ok(Self(path))
    }

    pub fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for ScratchDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn commit_moves_the_file_into_place() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("out.pdf");
        let mut pending = PendingOutput::create(target.clone(), false).unwrap();
        std::io::Write::write_all(pending.file().unwrap(), b"%PDF").unwrap();
        let temp = pending.temp_path().to_path_buf();
        assert_eq!(pending.commit().unwrap(), target);
        assert_eq!(fs::read(&target).unwrap(), b"%PDF");
        assert!(!temp.exists());
    }

    #[test]
    fn dropping_without_commit_leaves_nothing() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("out.pdf");
        let temp;
        {
            let pending = PendingOutput::create(target.clone(), false).unwrap();
            temp = pending.temp_path().to_path_buf();
            assert!(temp.exists());
        }
        assert!(!temp.exists());
        assert!(!target.exists());
    }

    #[test]
    fn commit_refuses_to_clobber_when_overwrite_is_off() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("out.pdf");
        fs::write(&target, b"keep").unwrap();
        let pending = PendingOutput::create(target.clone(), false).unwrap();
        assert!(matches!(pending.commit(), Err(ToolError::Exists(_))));
        assert_eq!(fs::read(&target).unwrap(), b"keep");
    }

    #[test]
    fn scratch_dir_is_removed_on_drop() {
        let dir = tempfile::tempdir().unwrap();
        let path;
        {
            let scratch = ScratchDir::create(dir.path()).unwrap();
            path = scratch.path().to_path_buf();
            assert!(path.is_dir());
        }
        assert!(!path.exists());
    }
}
