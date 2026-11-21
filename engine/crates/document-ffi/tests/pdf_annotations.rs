//! The exported PDF annotation calls, end to end on real files.
//!
//! Set `EZFILES_PDF_SAMPLE_DIR` to keep the input and output, so they can be
//! opened in other PDF readers.

use std::path::PathBuf;

use document_ffi::{
    NativePdfAnnotation, NativePdfBox, NativePdfInkStyle, NativePdfStroke, ReadError, pdf_append_path, pdf_inspect_path,
};

struct TempDir(PathBuf);

impl TempDir {
    fn new(name: &str) -> Self {
        let dir = std::env::temp_dir().join(format!("ezfiles-pdf-{name}-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        Self(dir)
    }

    fn path(&self, name: &str) -> String {
        self.0.join(name).to_str().unwrap().to_owned()
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        std::fs::remove_dir_all(&self.0).ok();
    }
}

/// Two text pages; the second is rotated, to show annotations stay upright.
fn text_pdf() -> Vec<u8> {
    let content = "BT /F1 18 Tf 72 700 Td (The quick brown fox jumps over the lazy dog.) Tj ET";
    let bodies = [
        "<</Type /Catalog /Pages 2 0 R>>".to_owned(),
        "<</Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 612 792]>>".to_owned(),
        "<</Type /Page /Parent 2 0 R /Contents 5 0 R /Resources <</Font <</F1 6 0 R>>>>>>".to_owned(),
        "<</Type /Page /Parent 2 0 R /Rotate 90 /Contents 5 0 R /Resources <</Font <</F1 6 0 R>>>>>>".to_owned(),
        format!("<</Length {}>>\nstream\n{content}\nendstream", content.len()),
        "<</Type /Font /Subtype /Type1 /BaseFont /Helvetica>>".to_owned(),
    ];
    let mut out = b"%PDF-1.7\n".to_vec();
    let mut offsets = Vec::new();
    for (index, body) in bodies.iter().enumerate() {
        offsets.push(out.len());
        out.extend_from_slice(format!("{} 0 obj\n{body}\nendobj\n", index + 1).as_bytes());
    }
    let xref_at = out.len();
    out.extend_from_slice(format!("xref\n0 {}\n0000000000 65535 f \n", bodies.len() + 1).as_bytes());
    for offset in offsets {
        out.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    out.extend_from_slice(
        format!("trailer\n<</Size {} /Root 1 0 R>>\nstartxref\n{xref_at}\n%%EOF\n", bodies.len() + 1).as_bytes(),
    );
    out
}

fn annotations() -> Vec<NativePdfAnnotation> {
    let mut all = Vec::new();
    for page in 0..2 {
        all.push(NativePdfAnnotation::Highlight {
            page,
            color: 0x66FF_EB3B,
            boxes: vec![NativePdfBox { left: 0.1, top: 0.1, right: 0.6, bottom: 0.13 }],
        });
        all.push(NativePdfAnnotation::Ink {
            page,
            color: 0xFFE6_0023,
            width: 0.006,
            strokes: vec![NativePdfStroke { points: vec![0.2, 0.3, 0.5, 0.45, 0.8, 0.3] }],
            style: NativePdfInkStyle::Pen,
        });
        all.push(NativePdfAnnotation::Ink {
            page,
            color: 0x6600_C853,
            width: 0.02,
            strokes: vec![NativePdfStroke { points: vec![0.1, 0.6, 0.9, 0.6] }],
            style: NativePdfInkStyle::Marker,
        });
        all.push(NativePdfAnnotation::Ink {
            page,
            color: 0xFF10_2040,
            width: 0.003,
            strokes: vec![NativePdfStroke { points: vec![0.5, 0.85, 0.6, 0.8, 0.7, 0.87, 0.85, 0.82] }],
            style: NativePdfInkStyle::Signature,
        });
        all.push(NativePdfAnnotation::Note {
            page,
            color: 0xFFFF_D54F,
            x: 0.85,
            y: 0.1,
            text: "A note — with non-ASCII".into(),
        });
    }
    all
}

#[test]
fn saves_a_copy_that_keeps_the_original_and_reads_back() {
    let dir = TempDir::new("save");
    let (source, target) = (dir.path("in.pdf"), dir.path("out.pdf"));
    let original = text_pdf();
    std::fs::write(&source, &original).unwrap();

    let before = pdf_inspect_path(source.clone()).unwrap();
    assert_eq!(before.page_count, 2);
    assert!(!before.encrypted && !before.is_signed && !before.archival);
    assert!(before.annotations.is_empty());

    let wanted = annotations();
    let report = pdf_append_path(source.clone(), target.clone(), wanted.clone()).unwrap();
    let saved = std::fs::read(&target).unwrap();
    assert_eq!(report.original_len, original.len() as u64);
    assert_eq!(report.total_len, saved.len() as u64);
    assert!(saved.starts_with(&original));
    assert_eq!(std::fs::read(&source).unwrap(), original, "the source is only read");

    let after = pdf_inspect_path(target.clone()).unwrap();
    assert_eq!(after.annotations.len(), wanted.len());

    if let Ok(keep) = std::env::var("EZFILES_PDF_SAMPLE_DIR") {
        std::fs::create_dir_all(&keep).unwrap();
        std::fs::write(format!("{keep}/plain.pdf"), &original).unwrap();
        std::fs::write(format!("{keep}/annotated.pdf"), &saved).unwrap();
    }
}

/// For checking files from other writers: `EZFILES_PDF_INPUT` lists paths
/// (`;`-separated), each saved next to itself as `<path>.annotated.pdf`.
#[test]
fn annotates_files_named_in_the_environment() {
    let Ok(inputs) = std::env::var("EZFILES_PDF_INPUT") else { return };
    for input in inputs.split(';').filter(|p| !p.is_empty()) {
        let target = format!("{input}.annotated.pdf");
        let report = pdf_append_path(input.to_owned(), target.clone(), annotations()).unwrap();
        let (original, saved) = (std::fs::read(input).unwrap(), std::fs::read(&target).unwrap());
        assert_eq!(report.original_len, original.len() as u64);
        assert!(saved.starts_with(&original), "{input}");
        assert!(pdf_inspect_path(target).unwrap().annotations.len() >= annotations().len(), "{input}");
    }
}

#[test]
fn a_failed_save_leaves_no_file_behind() {
    let dir = TempDir::new("fail");
    let (source, target) = (dir.path("in.pdf"), dir.path("out.pdf"));
    std::fs::write(&source, b"%PDF-1.7\nnothing here").unwrap();
    let err = pdf_append_path(source.clone(), target.clone(), annotations()).unwrap_err();
    assert!(matches!(err, ReadError::Malformed { .. }));
    assert!(!std::path::Path::new(&target).exists());

    let err = pdf_inspect_path(source).unwrap_err();
    assert!(matches!(err, ReadError::Malformed { .. }));
}

#[test]
fn encrypted_files_report_it_and_refuse_to_save() {
    let dir = TempDir::new("locked");
    let (source, target) = (dir.path("in.pdf"), dir.path("out.pdf"));
    let mut bytes = text_pdf();
    let at = bytes.windows(5).rposition(|w| w == b"/Root").unwrap();
    bytes.splice(at..at, b"/Encrypt 9 0 R ".iter().copied());
    std::fs::write(&source, &bytes).unwrap();

    assert!(pdf_inspect_path(source.clone()).unwrap().encrypted);
    let err = pdf_append_path(source, target.clone(), annotations()).unwrap_err();
    assert!(matches!(err, ReadError::UnsupportedFormat { .. }), "{err:?}");
    assert!(!std::path::Path::new(&target).exists());
}
