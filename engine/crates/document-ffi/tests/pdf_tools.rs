//! The exported PDF tool calls, end to end on real files.
//!
//! Set `EZFILES_PDF_SAMPLE_DIR` to keep every input and output, so they can be
//! opened in other PDF readers. Protected samples use the password `sample-pw`.

use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};

use document_ffi::{
    NativePdfPageEdit, NativePdfSource, NativePdfToolListener, NativePdfToolOp, ReadError, pdf_tool_inspect,
    pdf_tool_run,
};

struct TempDir(PathBuf);

impl TempDir {
    fn new(name: &str) -> Self {
        let dir = std::env::temp_dir().join(format!("ezfiles-pdf-tool-{name}-{}", std::process::id()));
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

#[derive(Clone, Default)]
struct Listener {
    last_done: Arc<AtomicU32>,
    cancelled: Arc<AtomicBool>,
}

impl NativePdfToolListener for Listener {
    fn on_progress(&self, done: u32, _total: u32) {
        self.last_done.store(done, Ordering::SeqCst);
    }

    fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::SeqCst)
    }
}

/// Real text, a link between pages, a rotated page and an annotation.
fn text_pdf(title: &str, pages: usize) -> Vec<u8> {
    let kids: Vec<String> = (0..pages).map(|i| format!("{} 0 R", 7 + i * 2)).collect();
    let mut bodies = vec![
        "<</Type /Catalog /Pages 2 0 R>>".to_owned(),
        format!(
            "<</Type /Pages /Kids [{}] /Count {pages} /MediaBox [0 0 612 792] /Resources <</Font <</F1 3 0 R>>>>>>",
            kids.join(" ")
        ),
        "<</Type /Font /Subtype /Type1 /BaseFont /Helvetica>>".to_owned(),
        format!("<</Title ({title})>>"),
        format!(
            "<</Type /Annot /Subtype /Link /Rect [72 690 300 720] /Border [0 0 1] /Dest [{} /Fit]>>",
            kids[pages - 1]
        ),
        "<</Type /Annot /Subtype /Square /Rect [72 400 300 500] /C [1 0 0] /Contents (A note)>>".to_owned(),
    ];
    for i in 0..pages {
        let rotate = if i == 1 { "/Rotate 90" } else { "" };
        let annots = if i == 0 { "/Annots [5 0 R 6 0 R]" } else { "" };
        bodies.push(format!("<</Type /Page /Parent 2 0 R {rotate} {annots} /Contents {} 0 R>>", 8 + i * 2));
        let text = format!("BT /F1 24 Tf 72 700 Td ({title} page {}) Tj ET 0.8 0.1 0.2 rg 72 72 200 100 re f", i + 1);
        bodies.push(format!("<</Length {}>>\nstream\n{text}\nendstream", text.len()));
    }
    let mut out = b"%PDF-1.7\n%\xE2\xE3\xCF\xD3\n".to_vec();
    let mut offsets = Vec::new();
    for (index, body) in bodies.iter().enumerate() {
        offsets.push(out.len());
        out.extend_from_slice(format!("{} 0 obj\n{body}\nendobj\n", index + 1).as_bytes());
    }
    let at = out.len();
    out.extend_from_slice(format!("xref\n0 {}\n0000000000 65535 f \n", bodies.len() + 1).as_bytes());
    for offset in offsets {
        out.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    out.extend_from_slice(
        format!("trailer\n<</Size {} /Root 1 0 R /Info 4 0 R>>\nstartxref\n{at}\n%%EOF\n", bodies.len() + 1).as_bytes(),
    );
    out
}

fn by_path(path: &str, password: Option<&str>) -> NativePdfSource {
    NativePdfSource { fd: -1, path: Some(path.to_owned()), password: password.map(str::to_owned) }
}

const PASSWORD: &str = "sample-pw";

#[test]
fn every_operation_runs_and_leaves_a_readable_file() {
    let temp = TempDir::new("all");
    let keep = std::env::var("EZFILES_PDF_SAMPLE_DIR").ok();
    let dir = keep.clone().unwrap_or_else(|| temp.path(""));
    std::fs::create_dir_all(&dir).unwrap();
    let path = |name: &str| PathBuf::from(&dir).join(name).to_str().unwrap().to_owned();

    std::fs::write(path("input-a.pdf"), text_pdf("Alpha", 3)).unwrap();
    std::fs::write(path("input-b.pdf"), text_pdf("Bravo", 2)).unwrap();
    let (a, b) = (path("input-a.pdf"), path("input-b.pdf"));

    let run = |sources: Vec<NativePdfSource>, op, name: &str| {
        pdf_tool_run(sources, op, path(name), Box::new(Listener::default())).unwrap()
    };

    let merged = run(vec![by_path(&a, None), by_path(&b, None)], NativePdfToolOp::Merge, "merged.pdf");
    assert_eq!(merged.page_count, 5);
    let pages = vec![NativePdfPageEdit { page: 2, rotation: 90 }, NativePdfPageEdit { page: 0, rotation: 0 }];
    let rebuilt = run(vec![by_path(&a, None)], NativePdfToolOp::Rebuild { pages }, "rebuilt.pdf");
    assert_eq!(rebuilt.page_count, 2);
    let inserted =
        run(vec![by_path(&a, None), by_path(&b, None)], NativePdfToolOp::InsertPages { at_page: 1 }, "inserted.pdf");
    assert_eq!(inserted.page_count, 5);
    run(vec![by_path(&a, None)], NativePdfToolOp::Resize { width: 595.0, height: 842.0 }, "resized-a4.pdf");
    run(vec![by_path(&a, None)], NativePdfToolOp::AddPassword { password: PASSWORD.to_owned() }, "protected.pdf");
    let locked = path("protected.pdf");

    assert!(matches!(pdf_tool_inspect(by_path(&locked, None)), Err(ReadError::PasswordRequired { .. })));
    let restricted = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../document-parsers/testdata/pdf_crypt/r6_empty_user_no_print.pdf");
    let info = pdf_tool_inspect(by_path(restricted.to_str().unwrap(), None)).unwrap();
    assert!(info.encrypted);
    assert_eq!(info.page_count, 2);
    assert_eq!(pdf_tool_inspect(by_path(&locked, Some(PASSWORD))).unwrap().page_count, 3);
    assert!(matches!(pdf_tool_inspect(by_path(&locked, Some("nope"))), Err(ReadError::WrongPassword { .. })));

    run(vec![by_path(&locked, Some(PASSWORD))], NativePdfToolOp::RemovePassword, "password-removed.pdf");
    let plain = pdf_tool_inspect(by_path(&path("password-removed.pdf"), None)).unwrap();
    assert!(!plain.encrypted);
    assert_eq!(plain.page_count, 3);
}

/// qpdf wrote these; each unlocks with `userpw` and comes out plain.
#[test]
fn outside_encrypted_files_come_out_plain() {
    let temp = TempDir::new("outside");
    let keep = std::env::var("EZFILES_PDF_SAMPLE_DIR").ok();
    let dir = keep.unwrap_or_else(|| temp.path(""));
    let fixtures = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../document-parsers/testdata/pdf_crypt");
    for name in ["r2_rc4_40", "r3_rc4_128", "r4_rc4_128", "r4_aes_128", "r4_aes_clear_metadata", "r6_aes_256"] {
        let source = fixtures.join(format!("{name}.pdf"));
        let target = PathBuf::from(&dir).join(format!("unlocked-{name}.pdf"));
        let report = pdf_tool_run(
            vec![by_path(source.to_str().unwrap(), Some("userpw"))],
            NativePdfToolOp::RemovePassword,
            target.to_str().unwrap().to_owned(),
            Box::new(Listener::default()),
        )
        .unwrap();
        assert_eq!(report.page_count, 2, "{name}");
    }
}

#[test]
fn failures_are_typed_and_leave_nothing_behind() {
    let temp = TempDir::new("failures");
    std::fs::write(temp.path("a.pdf"), text_pdf("Alpha", 1)).unwrap();
    let a = temp.path("a.pdf");
    let target = temp.path("out.pdf");

    let lock = NativePdfToolOp::AddPassword { password: PASSWORD.to_owned() };
    pdf_tool_run(vec![by_path(&a, None)], lock, temp.path("locked.pdf"), Box::new(Listener::default())).unwrap();
    let locked = temp.path("locked.pdf");

    let result = pdf_tool_run(
        vec![by_path(&locked, None)],
        NativePdfToolOp::RemovePassword,
        target.clone(),
        Box::new(Listener::default()),
    );
    assert!(matches!(result, Err(ReadError::PasswordRequired { .. })));
    assert!(!std::path::Path::new(&target).exists());

    let result = pdf_tool_run(
        vec![by_path(&locked, Some("nope"))],
        NativePdfToolOp::RemovePassword,
        target.clone(),
        Box::new(Listener::default()),
    );
    assert!(matches!(result, Err(ReadError::WrongPassword { .. })));
    assert!(!std::path::Path::new(&target).exists());

    let listener = Listener::default();
    listener.cancelled.store(true, Ordering::SeqCst);
    let result = pdf_tool_run(vec![by_path(&a, None)], NativePdfToolOp::Merge, target.clone(), Box::new(listener));
    assert!(matches!(result, Err(ReadError::Cancelled { .. })));
    assert!(!std::path::Path::new(&target).exists());

    let result = pdf_tool_run(
        vec![by_path(&temp.path("missing.pdf"), None)],
        NativePdfToolOp::Merge,
        target.clone(),
        Box::new(Listener::default()),
    );
    assert!(matches!(result, Err(ReadError::Io { .. })));
}

#[test]
fn progress_reaches_the_listener() {
    let temp = TempDir::new("progress");
    std::fs::write(temp.path("a.pdf"), text_pdf("Alpha", 3)).unwrap();
    let listener = Listener::default();
    let done = Arc::clone(&listener.last_done);
    pdf_tool_run(
        vec![by_path(&temp.path("a.pdf"), None)],
        NativePdfToolOp::Merge,
        temp.path("o.pdf"),
        Box::new(listener),
    )
    .unwrap();
    assert_eq!(done.load(Ordering::SeqCst), 3);
}
