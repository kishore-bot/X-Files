//! End-to-end tests of the exported API: the same functions the generated
//! Kotlin bindings call.

use std::path::PathBuf;

use document_ffi::{NativeBlock, NativeDocumentKind, NativeFormat, NativeLimits, ReadError, open_fd, open_path};

struct TempDir(PathBuf);

impl TempDir {
    fn new(name: &str) -> Self {
        let dir = std::env::temp_dir().join(format!("ezfiles-{name}-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        Self(dir)
    }

    fn file(&self, name: &str, contents: &[u8]) -> String {
        let path = self.0.join(name);
        std::fs::write(&path, contents).unwrap();
        path.to_str().unwrap().to_owned()
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        std::fs::remove_dir_all(&self.0).ok();
    }
}

fn texts(blocks: &[NativeBlock]) -> Vec<&str> {
    blocks
        .iter()
        .map(|block| match block {
            NativeBlock::Paragraph { text, .. } => text.as_str(),
            other => panic!("plain text produced {other:?}"),
        })
        .collect()
}

#[test]
fn reads_a_text_file_to_the_end() {
    let dir = TempDir::new("read");
    let session = open_path(dir.file("hello.txt", b"Hello\r\nworld\n"), None, NativeLimits::default(), None).unwrap();

    let info = session.info();
    assert_eq!((info.format, info.kind, info.byte_size), (NativeFormat::PlainText, NativeDocumentKind::Text, 13));
    assert_eq!(info.encoding.as_deref(), Some("utf-8"));

    let batch = session.next_batch().unwrap().unwrap();
    assert_eq!(texts(&batch.blocks), ["Hello", "world"]);
    assert!(batch.is_last && !batch.truncated);
    assert!(session.next_batch().unwrap().is_none());
}

#[test]
fn errors_are_typed() {
    let dir = TempDir::new("errors");
    let ole = b"\xD0\xCF\x11\xE0\xA1\xB1\x1A\xE1 legacy office";
    let legacy = open_path(dir.file("report.txt", ole), None, NativeLimits::default(), None).err().unwrap();
    assert!(matches!(legacy, ReadError::Malformed { ref detail } if detail.contains("compound file")), "{legacy:?}");

    // Detection goes by content, not by the .txt name.
    let pdf = open_path(dir.file("scan.txt", b"%PDF-1.7\n%\xE2\xE3\n"), None, NativeLimits::default(), None).unwrap();
    assert_eq!((pdf.info().format, pdf.info().kind), (NativeFormat::Pdf, NativeDocumentKind::Pdf));
    assert!(pdf.next_batch().unwrap().unwrap().blocks.is_empty());

    let missing = open_path(dir.0.join("missing.txt").to_str().unwrap().into(), None, NativeLimits::default(), None);
    assert!(matches!(missing.err().unwrap(), ReadError::Io { .. }));

    let limits = NativeLimits { max_input_bytes: 10, ..Default::default() };
    let big = open_path(dir.file("big.txt", &[b'a'; 100]), None, limits, None);
    assert!(matches!(big.err().unwrap(), ReadError::LimitExceeded { .. }));

    assert!(matches!(open_fd(-1, None, NativeLimits::default(), None).err().unwrap(), ReadError::Io { .. }));
}

#[cfg(unix)]
#[test]
fn takes_ownership_of_file_descriptors() {
    use std::os::fd::IntoRawFd;
    let dir = TempDir::new("fd");
    let fd = std::fs::File::open(dir.file("a.txt", b"fd works")).unwrap().into_raw_fd();
    let session = open_fd(fd, None, NativeLimits::default(), None).unwrap();
    assert_eq!(texts(&session.next_batch().unwrap().unwrap().blocks), ["fd works"]);
}

#[test]
fn cancellation_fails_the_next_batch() {
    let dir = TempDir::new("cancel");
    let limits = NativeLimits { max_batch_bytes: 100, ..Default::default() };
    let session = open_path(dir.file("long.txt", "line\n".repeat(10_000).as_bytes()), None, limits, None).unwrap();

    assert!(!session.next_batch().unwrap().unwrap().blocks.is_empty());
    session.cancel();
    assert!(matches!(session.next_batch().err().unwrap(), ReadError::Cancelled { .. }));
}

#[test]
fn sessions_are_independent_across_threads() {
    let dir = TempDir::new("threads");
    let threads: Vec<_> = (0..4)
        .map(|i| {
            let path = dir.file(&format!("{i}.txt"), format!("doc {i}\n").repeat(500).as_bytes());
            std::thread::spawn(move || {
                let limits = NativeLimits { max_batch_bytes: 256, ..Default::default() };
                let session = open_path(path, None, limits, None).unwrap();
                let mut lines = 0;
                while let Some(batch) = session.next_batch().unwrap() {
                    for text in texts(&batch.blocks) {
                        assert_eq!(text, format!("doc {i}"));
                        lines += 1;
                    }
                }
                lines
            })
        })
        .collect();

    for thread in threads {
        assert_eq!(thread.join().unwrap(), 500);
    }
}

#[test]
fn name_hint_selects_the_text_presentation() {
    let dir = TempDir::new("hint");
    let path = dir.file(
        "notes.bin",
        b"# Title

**bold** text
",
    );
    let plain = open_path(path.clone(), None, NativeLimits::default(), None).unwrap();
    assert_eq!(plain.info().format, NativeFormat::PlainText);

    let markdown = open_path(path, Some("README.md".into()), NativeLimits::default(), None).unwrap();
    assert_eq!(markdown.info().format, NativeFormat::Markdown);
    let blocks = markdown.next_batch().unwrap().unwrap().blocks;
    assert!(matches!(&blocks[0], NativeBlock::Heading { level: 1, text, .. } if text == "Title"));
    let NativeBlock::Paragraph { spans, .. } = &blocks[1] else { panic!("{blocks:?}") };
    assert_eq!(spans, &[document_ffi::NativeSpan { start: 0, end: 4, style: 1, link: None, color: None }]);
}
