//! FFI surface for the searchable text layer (see `document_parsers::pdf_text_layer`).
//! The Kotlin side renders and OCRs each page, then hands the boxed lines here in
//! one call; the Rust side writes one incremental update and re-reads it to check.

use std::fs::File;

use document_core::EngineError;
use document_parsers::{TextLine, add_text_layer, inspect_pdf, verify_text_layer};

use crate::{NativePdfAppendReport, ReadError};

/// One recognised line, boxed in fractions of its displayed page (origin top-left).
/// `page` is zero-based.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NativeTextLine {
    pub page: u32,
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
    pub text: String,
}

impl From<NativeTextLine> for TextLine {
    fn from(line: NativeTextLine) -> Self {
        Self { page: line.page, left: line.left, top: line.top, right: line.right, bottom: line.bottom, text: line.text }
    }
}

/// Writes the PDF at `path` plus an invisible text layer built from `lines` to
/// `target_path`, a private file. The source is only read. The target is removed
/// on any failure.
#[uniffi::export]
pub fn pdf_add_text_layer_path(
    path: String,
    target_path: String,
    lines: Vec<NativeTextLine>,
) -> Result<NativePdfAppendReport, ReadError> {
    let lines: Vec<TextLine> = lines.into_iter().map(Into::into).collect();
    let source = crate::open_for_read(&path)?;
    let result = write_text_layer(source, &target_path, &lines);
    if result.is_err() {
        std::fs::remove_file(&target_path).ok();
    }
    result
}

fn write_text_layer(source: File, target_path: &str, lines: &[TextLine]) -> Result<NativePdfAppendReport, ReadError> {
    let page_count = inspect_pdf(source.try_clone().map_err(EngineError::from)?)?.page_count;
    let mut target = std::io::BufWriter::new(File::create(target_path).map_err(EngineError::from)?);
    let report = add_text_layer(source, &mut target, lines)?;
    let target = target.into_inner().map_err(|e| EngineError::from(e.into_error()))?;
    target.sync_all().map_err(EngineError::from)?;
    drop(target);
    verify_text_layer(crate::open_for_read(target_path)?, page_count)?;
    Ok(NativePdfAppendReport { original_len: report.original_len, total_len: report.total_len })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_missing_source_is_an_io_error_and_leaves_no_target() {
        let target = std::env::temp_dir().join("xfiles-text-layer-missing.pdf");
        let err = pdf_add_text_layer_path(
            "definitely/not/here.pdf".into(),
            target.to_string_lossy().into_owned(),
            vec![NativeTextLine { page: 0, left: 0.1, top: 0.1, right: 0.5, bottom: 0.2, text: "x".into() }],
        )
        .unwrap_err();
        assert!(matches!(err, ReadError::Io { .. }));
        assert!(!target.exists());
    }

    #[test]
    fn lines_convert_without_loss() {
        let line = TextLine::from(NativeTextLine { page: 2, left: 0.1, top: 0.2, right: 0.3, bottom: 0.4, text: "hi".into() });
        assert_eq!((line.page, line.left, line.top, line.right, line.bottom), (2, 0.1, 0.2, 0.3, 0.4));
        assert_eq!(line.text, "hi");
    }
}
