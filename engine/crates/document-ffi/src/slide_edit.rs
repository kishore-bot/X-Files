//! UniFFI surface for PPTX slide tools beyond reorder: delete and duplicate.
//! Each writes a copy to `target_path`, re-opens it through the engine, and
//! returns the new slide count. The original file is never modified.

use std::fs::File;

use document_core::CancelToken;
use document_parsers::{office_edit, slide_edit};

use crate::ReadError;

/// Removes the slide at `index` (0-based) into a copy. Returns the new count.
#[uniffi::export]
pub fn pptx_delete_slide(path: String, target_path: String, index: u32) -> Result<u32, ReadError> {
    let data = read_input(&path)?;
    let output = slide_edit::delete_pptx_slide(&data, index)?;
    write_verified(&target_path, &output)
}

/// Inserts a copy of the slide at `index` directly after it. Returns the new count.
#[uniffi::export]
pub fn pptx_duplicate_slide(path: String, target_path: String, index: u32) -> Result<u32, ReadError> {
    let data = read_input(&path)?;
    let output = slide_edit::duplicate_pptx_slide(&data, index)?;
    write_verified(&target_path, &output)
}

fn read_input(path: &str) -> Result<Vec<u8>, ReadError> {
    std::fs::read(path).map_err(|e| ReadError::Io { detail: format!("cannot read file: {e}") })
}

fn write_verified(target_path: &str, output: &[u8]) -> Result<u32, ReadError> {
    let result = (|| -> Result<u32, ReadError> {
        std::fs::write(target_path, output).map_err(|e| ReadError::Io { detail: format!("cannot write file: {e}") })?;
        let written = File::open(target_path).map_err(|e| ReadError::Io { detail: format!("cannot read file: {e}") })?;
        office_edit::verify_reopens(written, &CancelToken::default())?;
        Ok(slide_edit::pptx_slide_count(output)?.slide_count)
    })();
    if result.is_err() {
        std::fs::remove_file(target_path).ok();
    }
    result
}
