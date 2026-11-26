//! Search exports: typo-tolerant file-name matching, the full-text index
//! with snippets, and plain text pulled straight from a document for it.
//! The algorithms live in `document-search`; this is only the boundary.

use std::fs::File;
use std::sync::{Arc, Mutex};

use document_core::{Block, CancelToken, FormatId, Limits, Session};
use document_search::{
    Added, Entry, NameIndex, PassageCache, PassageConfig, PassageIndex, PlainText, SearchIndex, Selection, html_to_text,
};

use crate::{NativeDocumentKind, NativeLimits, ReadError, duplicate_document_fd, file_from_fd, open_for_read};

/// File names prepared once; each keystroke is then one call.
#[derive(uniffi::Object)]
pub struct NativeNameIndex(NameIndex);

#[uniffi::export]
impl NativeNameIndex {
    #[uniffi::constructor]
    pub fn new(names: Vec<String>) -> Arc<Self> {
        Arc::new(Self(NameIndex::new(&names)))
    }

    /// Positions of the names matching `query`, best first. A blank query
    /// returns every name in order.
    pub fn search(&self, query: String, limit: u32) -> Vec<u32> {
        self.0.search(&query, limit as usize).into_iter().map(|m| m.index).collect()
    }
}

/// Reads the plain text of one document, for indexing. A PDF (`kind` is
/// `Pdf`) yields nothing here: the platform reads its pages.
#[derive(uniffi::Object)]
pub struct NativeTextReader {
    kind: NativeDocumentKind,
    format: FormatId,
    cancel: CancelToken,
    session: Mutex<Session>,
}

#[uniffi::export]
impl NativeTextReader {
    pub fn kind(&self) -> NativeDocumentKind {
        self.kind
    }

    /// Up to `max_chars` UTF-16 units of text: blocks on their own lines,
    /// spaces collapsed. Stops reading as soon as the text is full.
    pub fn read(&self, max_chars: u32) -> Result<String, ReadError> {
        let mut session = self.session.lock().map_err(|_| ReadError::Internal {
            detail: "this document hit an internal error earlier and cannot be read further".into(),
        })?;
        let mut text = PlainText::new(max_chars as usize);
        if self.format == FormatId::Html {
            // Web pages have no blocks; their bytes are the one resource.
            self.cancel.check()?;
            let bytes = session.resource("html")?;
            html_to_text(&String::from_utf8_lossy(&bytes), &mut text);
            return Ok(text.finish());
        }
        while !text.is_full() {
            let Some(batch) = session.next_batch()? else { break };
            for block in batch.blocks {
                push_block(&mut text, block);
            }
        }
        Ok(text.finish())
    }

    /// Stops a running `read` at its next batch, which then fails with `Cancelled`.
    pub fn cancel(&self) {
        self.cancel.cancel();
    }
}

fn push_block(text: &mut PlainText, block: Block) {
    match block {
        Block::Heading { text: t, .. }
        | Block::Paragraph { text: t }
        | Block::ListItem { text: t, .. }
        | Block::Quote { text: t } => text.push_block(&t.text),
        Block::Code { text: t, .. } => text.push_block(&t),
        Block::TableRow { cells, .. } => text.push_parts(cells.iter().map(String::as_str)),
        Block::Section { title } => text.push_block(&title),
        Block::Entry { path, .. } => text.push_block(&path),
        Block::Shape(shape) => {
            for paragraph in &shape.paragraphs {
                text.push_block(&paragraph.text.text);
            }
            for row in &shape.table {
                text.push_parts(row.iter().map(String::as_str));
            }
        }
        _ => {}
    }
}

/// Opens a text reader over a descriptor, taking ownership of it in every
/// case. Encrypted documents fail with `PasswordRequired`: search never asks
/// for, or reads, protected files.
#[uniffi::export]
pub fn text_reader_open_fd(
    fd: i32,
    name_hint: Option<String>,
    limits: NativeLimits,
) -> Result<Arc<NativeTextReader>, ReadError> {
    open_reader(file_from_fd(fd)?, name_hint, limits.resolve())
}

/// Opens a reader by duplicating a descriptor still owned by the caller.
/// The caller keeps the original open for the duration of the call.
#[uniffi::export]
pub fn text_reader_open_borrowed_fd(
    fd: i32,
    name_hint: Option<String>,
    limits: NativeLimits,
) -> Result<Arc<NativeTextReader>, ReadError> {
    open_reader(duplicate_document_fd(fd)?, name_hint, limits.resolve())
}

/// As [`text_reader_open_fd`], by path. Used by host tests.
#[uniffi::export]
pub fn text_reader_open_path(
    path: String,
    name_hint: Option<String>,
    limits: NativeLimits,
) -> Result<Arc<NativeTextReader>, ReadError> {
    open_reader(open_for_read(&path)?, name_hint, limits.resolve())
}

fn open_reader(file: File, name_hint: Option<String>, limits: Limits) -> Result<Arc<NativeTextReader>, ReadError> {
    let session = document_parsers::engine(limits).open_protected(file, name_hint, None)?;
    let info = session.info();
    Ok(Arc::new(NativeTextReader {
        kind: info.kind.into(),
        format: info.format,
        cancel: session.cancel_token(),
        session: Mutex::new(session),
    }))
}

/// A document as the indexer last saw it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NativeIndexEntry {
    pub key: String,
    pub size: u64,
    pub mtime: i64,
}

/// `[start, end)` in UTF-16 units.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct NativeTextRange {
    pub start: u32,
    pub end: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NativeSearchHit {
    pub key: String,
    pub score: f32,
    pub snippet: String,
    /// Matched words in `snippet`.
    pub ranges: Vec<NativeTextRange>,
    /// The PDF page the snippet is on, from 0.
    pub page: Option<u32>,
}

/// The full-text index in a private directory. Safe to call from several
/// threads: queries keep answering while documents are added.
#[derive(uniffi::Object)]
pub struct NativeSearchIndex(SearchIndex);

#[uniffi::export]
impl NativeSearchIndex {
    /// Opens or creates the index in `dir`. A damaged index starts empty.
    #[uniffi::constructor]
    pub fn new(dir: String) -> Arc<Self> {
        Arc::new(Self(SearchIndex::open(dir, Default::default())))
    }

    /// Loads a committed query snapshot without touching files owned by an active writer.
    #[uniffi::constructor]
    pub fn committed(dir: String) -> Arc<Self> {
        Arc::new(Self(SearchIndex::committed(dir, Default::default())))
    }

    pub fn document_count(&self) -> u32 {
        self.0.len() as u32
    }

    /// Positions in `entries` of documents that are new or changed.
    pub fn stale(&self, entries: Vec<NativeIndexEntry>) -> Vec<u32> {
        let entries: Vec<Entry> =
            entries.into_iter().map(|e| Entry { key: e.key, size: e.size, mtime: e.mtime }).collect();
        self.0.stale(&entries)
    }

    /// Forgets documents not in `keys`; returns how many.
    pub fn retain(&self, keys: Vec<String>) -> u32 {
        self.0.retain(&keys) as u32
    }

    pub fn has_room(&self) -> bool {
        self.0.has_room()
    }

    /// Indexes `text` as `key`; `false` when the index is full.
    /// `page_starts` are where PDF pages begin, in UTF-16 units of `text`.
    pub fn add(
        &self,
        key: String,
        size: u64,
        mtime: i64,
        text: String,
        page_starts: Vec<u32>,
    ) -> Result<bool, ReadError> {
        let added = self
            .0
            .add(&key, size, mtime, &text, &page_starts)
            .map_err(|e| ReadError::Io { detail: format!("cannot write the search index: {e}") })?;
        Ok(added == Added::Indexed)
    }

    pub fn query(&self, query: String, limit: u32) -> Vec<NativeSearchHit> {
        self.0
            .query(&query, limit as usize)
            .into_iter()
            .map(|hit| NativeSearchHit {
                key: hit.key,
                score: hit.score,
                snippet: hit.snippet,
                ranges: hit.ranges.into_iter().map(|(start, end)| NativeTextRange { start, end }).collect(),
                page: hit.page,
            })
            .collect()
    }

    pub fn save(&self) -> Result<(), ReadError> {
        self.0.save().map_err(|e| ReadError::Io { detail: format!("cannot save the search index: {e}") })
    }

    /// Deletes the index, in memory and on disk.
    pub fn clear(&self) {
        self.0.clear();
    }
}

/// A passage of a document for the Fily prompt.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NativePassage {
    pub text: String,
    /// The 1-based PDF page the passage starts on.
    pub page: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NativePassageSelection {
    /// In document order, within the budget the caller gave.
    pub passages: Vec<NativePassage>,
    /// True when some of the document was not selected or indexed.
    pub omitted: bool,
    /// Bytes of the document text, so the caller can see that the whole text fits its budget.
    pub document_bytes: u64,
}

/// Passage indexes of recently used documents, for answers about a document's text.
/// Thread-safe. Indexing is CPU work: call [`NativePassageCache::index`] off the main thread.
#[derive(uniffi::Object)]
pub struct NativePassageCache(PassageCache);

#[uniffi::export]
impl NativePassageCache {
    #[uniffi::constructor]
    pub fn new(max_documents: u32, max_bytes: u64) -> Arc<Self> {
        Arc::new(Self(PassageCache::new(max_documents as usize, max_bytes as usize)))
    }

    /// The passages of `key` that answer `question` within `budget_chars`, or `None` when the
    /// document is not indexed for this `mtime` and `size`.
    pub fn select(
        &self,
        key: String,
        mtime: i64,
        size: u64,
        question: String,
        budget_chars: u32,
    ) -> Option<NativePassageSelection> {
        let index = self.0.get(&key, mtime, size)?;
        Some(native_selection(index.select(&question, budget_chars as usize)))
    }

    /// Indexes `text` as `key`, replacing an older index. `page_starts` are where PDF pages begin,
    /// in UTF-16 units of `text`, as the platform reports them.
    pub fn index(&self, key: String, mtime: i64, size: u64, text: String, page_starts: Vec<u32>) {
        let starts = utf16_to_byte_starts(&text, &page_starts);
        let built = PassageIndex::new(&text, &starts, PassageConfig::default());
        self.0.insert(&key, mtime, size, built);
    }

    pub fn document_count(&self) -> u32 {
        self.0.document_count() as u32
    }
}

fn native_selection(selection: Selection) -> NativePassageSelection {
    NativePassageSelection {
        passages: selection.passages.into_iter().map(|p| NativePassage { text: p.text, page: p.page }).collect(),
        omitted: selection.omitted,
        document_bytes: selection.document_bytes as u64,
    }
}

/// Maps UTF-16 offsets (what the platform reports) to byte offsets, in one pass over `text`.
fn utf16_to_byte_starts(text: &str, utf16_starts: &[u32]) -> Vec<u32> {
    let mut wanted = utf16_starts.to_vec();
    wanted.sort_unstable();
    let mut wanted = wanted.into_iter().peekable();
    let mut out = Vec::with_capacity(wanted.len());
    let mut units = 0u32;
    for (byte, c) in text.char_indices() {
        while let Some(&start) = wanted.peek() {
            if start > units {
                break;
            }
            out.push(byte as u32);
            wanted.next();
        }
        units = units.saturating_add(c.len_utf16() as u32);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn utf16_offsets_map_to_byte_offsets_across_multibyte_text() {
        // "é" is 2 bytes and 1 UTF-16 unit; "😀" is 4 bytes and 2 units.
        let text = "é😀 page two";
        // "p" of "page" is at UTF-16 unit 4 (1 + 2 + the space) and byte 7.
        let starts = utf16_to_byte_starts(text, &[0, 4]);
        assert_eq!(starts, vec![0, 7]);
        assert_eq!(&text[starts[1] as usize..], "page two");
    }

    #[test]
    fn passage_cache_selects_only_for_matching_identity() {
        let cache = NativePassageCache::new(4, 1 << 20);
        cache.index("doc".into(), 5, 100, "Rent is due on the first day.\n\nPets need consent.".into(), vec![]);
        let hit = cache.select("doc".into(), 5, 100, "When is rent due?".into(), 500).unwrap();
        assert!(hit.passages[0].text.contains("Rent is due"));
        assert!(cache.select("doc".into(), 6, 100, "rent".into(), 500).is_none());
        assert_eq!(cache.document_count(), 1);
    }

    fn temp(name: &str, contents: &[u8]) -> std::path::PathBuf {
        let path = std::env::temp_dir().join(format!("ezfiles-ffi-search-{}-{name}", std::process::id()));
        std::fs::write(&path, contents).unwrap();
        path
    }

    fn read(name: &str, contents: &[u8], max: u32) -> (NativeDocumentKind, String) {
        let path = temp(name, contents);
        let reader =
            text_reader_open_path(path.display().to_string(), Some(name.into()), NativeLimits::default()).unwrap();
        let text = reader.read(max).unwrap();
        std::fs::remove_file(path).ok();
        (reader.kind(), text)
    }

    #[test]
    fn reads_plain_text_markdown_csv_and_html() {
        assert_eq!(
            read("a.txt", b"Hello\n\n  world  again\n", 1000),
            (NativeDocumentKind::Text, "Hello\nworld again".into())
        );
        assert_eq!(read("b.md", b"# Title\n\nSome *text*.\n", 1000).1, "Title\nSome text.");
        assert_eq!(read("c.csv", b"name,total\nAda,42\n", 1000).1, "name total\nAda 42");
        assert_eq!(read("d.html", b"<h1>Caf\xC3\xA9</h1><script>x</script><p>open</p>", 1000).1, "Caf\u{e9}\nopen");
    }

    #[test]
    fn stops_at_the_limit() {
        let long = "word ".repeat(100_000);
        assert_eq!(read("long.txt", long.as_bytes(), 9).1, "word word");
    }

    #[test]
    fn pdfs_report_their_kind_and_no_text() {
        let (kind, text) = read("e.pdf", b"%PDF-1.7\n%\xE2\xE3\n1 0 obj", 1000);
        assert_eq!(kind, NativeDocumentKind::Pdf);
        assert!(text.is_empty());
    }

    #[test]
    fn cancelled_reads_fail_with_cancelled() {
        let path = temp("cancel.txt", b"some text");
        let reader = text_reader_open_path(path.display().to_string(), None, NativeLimits::default()).unwrap();
        reader.cancel();
        assert!(matches!(reader.read(100), Err(ReadError::Cancelled { .. })));
        std::fs::remove_file(path).ok();
    }

    #[test]
    fn index_and_names_round_trip_through_the_exported_types() {
        let names = NativeNameIndex::new(vec!["Invoice_2025.pdf".into(), "Notes.txt".into()]);
        assert_eq!(names.search("invoce".into(), 10), vec![0]);
        assert_eq!(names.search("".into(), 10), vec![0, 1]);

        let dir = std::env::temp_dir().join(format!("ezfiles-ffi-index-{}", std::process::id()));
        let index = NativeSearchIndex::new(dir.display().to_string());
        index.clear();
        assert!(index.add("k".into(), 1, 2, "The quarterly invoice".into(), vec![]).unwrap());
        assert_eq!(index.stale(vec![NativeIndexEntry { key: "k".into(), size: 1, mtime: 2 }]), Vec::<u32>::new());
        let hits = index.query("invoice".into(), 5);
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].ranges, vec![NativeTextRange { start: 14, end: 21 }]);
        index.save().unwrap();
        assert_eq!(NativeSearchIndex::new(dir.display().to_string()).document_count(), 1);
        index.clear();
        std::fs::remove_dir_all(dir).ok();
    }
}
