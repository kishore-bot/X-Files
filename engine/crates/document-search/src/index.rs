//! A full-text index of the library's documents, ranked by BM25.
//!
//! In memory: one posting list per term (document slot and term frequency),
//! kept in a `BTreeMap` so a word still being typed can expand to the words it
//! starts. On disk, in a private directory:
//!
//! - `index.bin`: the documents and posting lists in a compact versioned
//!   binary format with a checksum ([`crate::store`]). Anything unreadable is
//!   discarded and the index starts empty, to be rebuilt.
//! - `texts/<id>.txt`: each document's text, read back only to cut snippets
//!   for the few results shown, so it never sits in memory.
//!
//! Every size is capped ([`IndexConfig`]): documents, words per document,
//! vocabulary, postings and stored text. When a cap is reached, [`SearchIndex::add`]
//! declines further documents rather than growing.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard};

use crate::snippet::{QueryTerm, best_snippet, page_of};
use crate::store;
use crate::tokens::{for_each_token, stem};

/// BM25 term-frequency saturation.
const K1: f32 = 1.2;
/// BM25 length normalisation.
const B: f32 = 0.75;
/// Weight of words a typed prefix expands to, relative to the word itself.
const PREFIX_WEIGHT: f32 = 0.7;
/// Words a prefix may expand to, so "a" can't touch the whole vocabulary.
const MAX_EXPANSIONS: usize = 48;
/// Words of a query that count; the rest are ignored.
const MAX_QUERY_TERMS: usize = 8;
const SNIPPET_CHARS: usize = 150;

/// Budgets. The defaults keep the index to a few MB of memory and tens of MB
/// of disk on a large library, which suits a 2 GB phone.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IndexConfig {
    pub max_docs: usize,
    /// Distinct words kept per document; the first ones in reading order.
    pub max_terms_per_doc: usize,
    pub max_vocabulary: usize,
    pub max_postings: usize,
    /// Stored for snippets; longer texts are cut at a character boundary.
    pub max_text_bytes_per_doc: usize,
    pub max_total_text_bytes: u64,
}

impl Default for IndexConfig {
    fn default() -> Self {
        Self {
            max_docs: 20_000,
            max_terms_per_doc: 4_000,
            max_vocabulary: 150_000,
            max_postings: 800_000,
            max_text_bytes_per_doc: 256 * 1024,
            max_total_text_bytes: 64 * 1024 * 1024,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Posting {
    pub doc: u32,
    pub tf: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Doc {
    pub key: String,
    pub size: u64,
    pub mtime: i64,
    /// Words in the document, for length normalisation.
    pub len: u32,
    /// Names its text file; 0 when no text is stored.
    pub file: u64,
    pub text_bytes: u32,
    /// Byte offset in the stored text where each PDF page starts.
    pub pages: Vec<u32>,
    pub live: bool,
}

#[derive(Debug, Default)]
pub(crate) struct Inner {
    pub docs: Vec<Doc>,
    pub by_key: HashMap<String, u32>,
    pub terms: BTreeMap<Box<str>, Vec<Posting>>,
    pub next_file: u64,
    pub live: u32,
    pub dead: u32,
    pub total_len: u64,
    pub postings: usize,
    pub text_bytes: u64,
    pub dirty: bool,
}

impl Inner {
    /// Rebuilds the derived counters after loading.
    pub(crate) fn recount(&mut self) {
        self.by_key =
            self.docs.iter().enumerate().filter(|(_, d)| d.live).map(|(i, d)| (d.key.clone(), i as u32)).collect();
        self.live = self.by_key.len() as u32;
        self.dead = self.docs.len() as u32 - self.live;
        self.total_len = self.docs.iter().filter(|d| d.live).map(|d| u64::from(d.len)).sum();
        self.text_bytes = self.docs.iter().filter(|d| d.live).map(|d| u64::from(d.text_bytes)).sum();
        self.postings = self.terms.values().map(Vec::len).sum();
        self.next_file = self.next_file.max(self.docs.iter().map(|d| d.file).max().unwrap_or(0) + 1);
    }

    fn kill(&mut self, slot: u32) {
        let Some(doc) = self.docs.get_mut(slot as usize).filter(|doc| doc.live) else {
            return;
        };
        doc.live = false;
        self.by_key.remove(&doc.key);
        self.live -= 1;
        self.dead += 1;
        self.total_len -= u64::from(doc.len);
        self.text_bytes -= u64::from(doc.text_bytes);
        self.dirty = true;
    }

    /// Drops dead documents and their postings, renumbering the rest.
    pub(crate) fn compact(&mut self) {
        if self.dead == 0 {
            return;
        }
        let mut remap = vec![u32::MAX; self.docs.len()];
        let mut docs = Vec::with_capacity(self.live as usize);
        for (old, doc) in std::mem::take(&mut self.docs).into_iter().enumerate() {
            if doc.live {
                remap[old] = docs.len() as u32;
                docs.push(doc);
            }
        }
        self.docs = docs;
        self.terms.retain(|_, postings| {
            postings.retain_mut(|p| {
                let new = remap[p.doc as usize];
                p.doc = new;
                new != u32::MAX
            });
            !postings.is_empty()
        });
        self.recount();
        self.dirty = true;
    }
}

/// One result of [`SearchIndex::query`].
#[derive(Debug, Clone, PartialEq)]
pub struct Hit {
    pub key: String,
    pub score: f32,
    pub snippet: String,
    /// Matched words in `snippet`, as `[start, end)` UTF-16 units.
    pub ranges: Vec<(u32, u32)>,
    /// PDF page of the snippet, from 0.
    pub page: Option<u32>,
}

/// Where a document stands, for [`SearchIndex::stale`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub key: String,
    pub size: u64,
    pub mtime: i64,
}

/// What [`SearchIndex::add`] did.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Added {
    Indexed,
    /// A budget is reached; the document was not added.
    Full,
}

/// Thread-safe: indexing and queries may run on different threads. Text is
/// analysed before the lock is taken, so queries wait only for the merge.
pub struct SearchIndex {
    dir: PathBuf,
    config: IndexConfig,
    inner: Mutex<Inner>,
}

const INDEX_FILE: &str = "index.bin";
const TEMP_FILE: &str = "index.tmp";
const TEXTS: &str = "texts";

impl SearchIndex {
    /// Opens the index kept in `dir`, creating it if needed. Never fails: a
    /// missing, damaged or older-format index starts empty.
    pub fn open(dir: impl Into<PathBuf>, config: IndexConfig) -> Self {
        let dir = dir.into();
        let inner = match load(&dir) {
            Some(mut inner) => {
                inner.recount();
                remove_orphan_texts(&dir, &inner);
                inner
            }
            None => {
                wipe(&dir);
                Inner { next_file: 1, ..Inner::default() }
            }
        };
        Self { dir, config, inner: Mutex::new(inner) }
    }

    fn lock(&self) -> MutexGuard<'_, Inner> {
        match self.inner.lock() {
            Ok(guard) => guard,
            Err(poisoned) => {
                // A panic mid-update may have left it inconsistent: start over.
                let mut guard = poisoned.into_inner();
                *guard = Inner { next_file: guard.next_file.max(1), dirty: true, ..Inner::default() };
                self.inner.clear_poison();
                guard
            }
        }
    }

    /// Documents indexed.
    pub fn len(&self) -> usize {
        self.lock().live as usize
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Positions in `entries` of documents that are new or changed (by size or
    /// modification time) and need indexing.
    pub fn stale(&self, entries: &[Entry]) -> Vec<u32> {
        let inner = self.lock();
        entries
            .iter()
            .enumerate()
            .filter(|(_, e)| match inner.by_key.get(&e.key) {
                Some(&slot) => {
                    let doc = &inner.docs[slot as usize];
                    doc.size != e.size || doc.mtime != e.mtime
                }
                None => true,
            })
            .map(|(i, _)| i as u32)
            .collect()
    }

    /// Forgets every document whose key is not in `keys`; returns how many.
    pub fn retain(&self, keys: &[String]) -> usize {
        let keep: HashSet<&str> = keys.iter().map(String::as_str).collect();
        let mut inner = self.lock();
        let gone: Vec<u32> =
            inner.by_key.iter().filter(|(k, _)| !keep.contains(k.as_str())).map(|(_, &slot)| slot).collect();
        for &slot in &gone {
            inner.kill(slot);
        }
        self.maybe_compact(&mut inner);
        gone.len()
    }

    pub fn remove(&self, key: &str) -> bool {
        let mut inner = self.lock();
        let Some(&slot) = inner.by_key.get(key) else { return false };
        inner.kill(slot);
        self.maybe_compact(&mut inner);
        true
    }

    /// Whether another document of average size would still fit.
    pub fn has_room(&self) -> bool {
        let inner = self.lock();
        (inner.live as usize) < self.config.max_docs
            && inner.postings + self.config.max_terms_per_doc / 4 <= self.config.max_postings
            && inner.terms.len() < self.config.max_vocabulary
            && inner.text_bytes < self.config.max_total_text_bytes
    }

    /// Indexes `text` as the document `key`, replacing an earlier version.
    /// `page_starts` are where PDF pages start, in UTF-16 units of `text`.
    /// A successfully read document with empty text is indexed as fresh.
    pub fn add(&self, key: &str, size: u64, mtime: i64, text: &str, page_starts: &[u32]) -> io::Result<Added> {
        // Analysis before the lock: queries keep running meanwhile.
        let stored = truncate_at_char(text, self.config.max_text_bytes_per_doc);
        let (counts, len) = analyze(stored, self.config.max_terms_per_doc);
        let pages = utf16_to_bytes(stored, page_starts);

        let mut inner = self.lock();
        if !self.fits(&inner, key, &counts, stored.len()) {
            // Dead postings may be what's in the way.
            inner.compact();
            if !self.fits(&inner, key, &counts, stored.len()) {
                return Ok(Added::Full);
            }
        }
        let old = inner.by_key.get(key).copied();
        let file = if stored.is_empty() {
            0
        } else {
            let file = inner.next_file;
            fs::create_dir_all(self.dir.join(TEXTS))?;
            fs::write(self.text_path(file), stored)?;
            inner.next_file += 1;
            file
        };
        if let Some(slot) = old {
            inner.kill(slot);
        }
        let slot = inner.docs.len() as u32;
        for (term, tf) in counts {
            inner.terms.entry(term.into_boxed_str()).or_default().push(Posting { doc: slot, tf });
            inner.postings += 1;
        }
        inner.docs.push(Doc {
            key: key.to_owned(),
            size,
            mtime,
            len,
            file,
            text_bytes: stored.len() as u32,
            pages,
            live: true,
        });
        inner.by_key.insert(key.to_owned(), slot);
        inner.live += 1;
        inner.total_len += u64::from(len);
        inner.text_bytes += stored.len() as u64;
        inner.dirty = true;
        self.maybe_compact(&mut inner);
        Ok(Added::Indexed)
    }

    fn fits(&self, inner: &Inner, key: &str, counts: &HashMap<String, u32>, text_bytes: usize) -> bool {
        let old = inner.by_key.get(key).map(|&slot| &inner.docs[slot as usize]);
        let old_bytes = old.map_or(0, |doc| u64::from(doc.text_bytes));
        let new_terms = counts.keys().filter(|t| !inner.terms.contains_key(t.as_str())).count();
        (inner.live as usize) - usize::from(old.is_some()) < self.config.max_docs
            && inner.postings + counts.len() <= self.config.max_postings
            && inner.terms.len() + new_terms <= self.config.max_vocabulary
            && inner.text_bytes - old_bytes + text_bytes as u64 <= self.config.max_total_text_bytes
    }

    fn maybe_compact(&self, inner: &mut Inner) {
        if inner.dead > 64 && inner.dead > inner.live / 3 {
            inner.compact();
        }
    }

    /// The best `limit` documents for `query`, with snippets. The last word,
    /// unless followed by a space, also matches words it begins.
    pub fn query(&self, query: &str, limit: usize) -> Vec<Hit> {
        let terms = query_terms(query);
        if terms.is_empty() || limit == 0 {
            return Vec::new();
        }
        let ranked: Vec<(f32, String, u64, Vec<u32>)> = {
            let inner = self.lock();
            if inner.live == 0 {
                return Vec::new();
            }
            let n = inner.live as f32;
            let avg_len = (inner.total_len as f32 / n).max(1.0);
            // Per document: score, and which query words it has.
            let mut scores: HashMap<u32, (f32, u32)> = HashMap::new();
            for (i, term) in terms.iter().enumerate() {
                let mut lists: Vec<(&[Posting], f32)> = Vec::new();
                if let Some(list) = inner.terms.get(term.stemmed.as_str()) {
                    lists.push((list, 1.0));
                }
                if term.prefix {
                    for (_, list) in inner
                        .terms
                        .range::<str, _>((std::ops::Bound::Included(term.folded.as_str()), std::ops::Bound::Unbounded))
                        .take_while(|(word, _)| word.starts_with(term.folded.as_str()))
                        .filter(|(word, _)| word.as_ref() != term.stemmed.as_str())
                        .take(MAX_EXPANSIONS)
                    {
                        lists.push((list, PREFIX_WEIGHT));
                    }
                }
                for (list, weight) in lists {
                    let df = list.iter().filter(|p| inner.docs[p.doc as usize].live).count() as f32;
                    if df == 0.0 {
                        continue;
                    }
                    let idf = (1.0 + (n - df + 0.5) / (df + 0.5)).ln();
                    for p in list {
                        let doc = &inner.docs[p.doc as usize];
                        if !doc.live {
                            continue;
                        }
                        let tf = p.tf as f32;
                        let norm = K1 * (1.0 - B + B * doc.len as f32 / avg_len);
                        let entry = scores.entry(p.doc).or_insert((0.0, 0));
                        entry.0 += weight * idf * tf * (K1 + 1.0) / (tf + norm);
                        entry.1 |= 1 << i;
                    }
                }
            }
            let wanted = terms.len() as f32;
            let mut ranked: Vec<(f32, u32)> = scores
                .into_iter()
                .map(|(doc, (score, mask))| {
                    // Documents with every word come first.
                    let coverage = mask.count_ones() as f32 / wanted;
                    (score * coverage * coverage, doc)
                })
                .collect();
            ranked.sort_by(|a, b| b.0.total_cmp(&a.0).then(a.1.cmp(&b.1)));
            ranked.truncate(limit);
            ranked
                .into_iter()
                .map(|(score, slot)| {
                    let doc = &inner.docs[slot as usize];
                    (score, doc.key.clone(), doc.file, doc.pages.clone())
                })
                .collect()
        };
        // Snippets read each text from disk, outside the lock.
        ranked
            .into_iter()
            .map(|(score, key, file, pages)| {
                let text = if file == 0 { String::new() } else { self.read_text(file) };
                let snippet = best_snippet(&text, &terms, SNIPPET_CHARS);
                let page = if snippet.ranges.is_empty() { None } else { page_of(&pages, snippet.anchor) };
                Hit { key, score, snippet: snippet.text, ranges: snippet.ranges, page }
            })
            .collect()
    }

    fn text_path(&self, file: u64) -> PathBuf {
        self.dir.join(TEXTS).join(format!("{file:x}.txt"))
    }

    fn read_text(&self, file: u64) -> String {
        let mut bytes = Vec::new();
        let limit = self.config.max_text_bytes_per_doc as u64 + 4;
        if let Ok(f) = fs::File::open(self.text_path(file)) {
            f.take(limit).read_to_end(&mut bytes).ok();
        }
        String::from_utf8(bytes).unwrap_or_else(|e| String::from_utf8_lossy(e.as_bytes()).into_owned())
    }

    /// Writes the index if it changed, atomically (temporary file, then rename).
    pub fn save(&self) -> io::Result<()> {
        let mut inner = self.lock();
        if !inner.dirty {
            return Ok(());
        }
        inner.compact();
        let bytes = store::encode(&inner);
        fs::create_dir_all(&self.dir)?;
        let temp = self.dir.join(TEMP_FILE);
        fs::write(&temp, &bytes)?;
        fs::rename(&temp, self.dir.join(INDEX_FILE))?;
        inner.dirty = false;
        // Older snippets are collected on the next open. Until then, queries that
        // already selected those snippets can still read them after this commit.
        Ok(())
    }

    /// Deletes everything, in memory and on disk.
    pub fn clear(&self) {
        let mut inner = self.lock();
        wipe(&self.dir);
        *inner = Inner { next_file: 1, ..Inner::default() };
    }
}

fn load(dir: &Path) -> Option<Inner> {
    let file = fs::File::open(dir.join(INDEX_FILE)).ok()?;
    let mut bytes = Vec::new();
    file.take(store::MAX_FILE_BYTES + 1).read_to_end(&mut bytes).ok()?;
    store::decode(&bytes)
}

fn wipe(dir: &Path) {
    fs::remove_dir_all(dir).ok();
    fs::create_dir_all(dir).ok();
}

/// Text files left by an index that was never saved, such as after a crash.
fn remove_orphan_texts(dir: &Path, inner: &Inner) {
    let known: HashSet<String> =
        inner.docs.iter().filter(|d| d.file != 0).map(|d| format!("{:x}.txt", d.file)).collect();
    let Ok(entries) = fs::read_dir(dir.join(TEXTS)) else { return };
    for entry in entries.flatten() {
        if !known.contains(entry.file_name().to_string_lossy().as_ref()) {
            fs::remove_file(entry.path()).ok();
        }
    }
}

/// Term frequencies (stemmed words) of `text`, at most `max_terms` distinct
/// ones, and the number of words.
pub fn analyze(text: &str, max_terms: usize) -> (HashMap<String, u32>, u32) {
    let mut counts: HashMap<String, u32> = HashMap::new();
    let mut len = 0u32;
    for_each_token(text, |_, _, token| {
        len = len.saturating_add(1);
        let term = stem(token);
        if let Some(count) = counts.get_mut(term.as_ref()) {
            *count += 1;
        } else if counts.len() < max_terms {
            counts.insert(term.into_owned(), 1);
        }
    });
    (counts, len)
}

/// The words of a query, de-duplicated; the last is a prefix while typing.
pub fn query_terms(query: &str) -> Vec<QueryTerm> {
    let mut terms: Vec<QueryTerm> = Vec::new();
    let mut last_end = 0;
    for_each_token(query, |_, end, token| {
        last_end = end;
        let stemmed = stem(token).into_owned();
        if terms.len() < MAX_QUERY_TERMS && !terms.iter().any(|t| t.stemmed == stemmed) {
            terms.push(QueryTerm { folded: token.to_owned(), stemmed, prefix: false });
        }
    });
    let typing = last_end == query.len();
    if let Some(last) = terms.last_mut() {
        last.prefix = typing;
    }
    terms
}

fn truncate_at_char(text: &str, max_bytes: usize) -> &str {
    if text.len() <= max_bytes {
        return text;
    }
    let mut end = max_bytes;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    &text[..end]
}

/// Converts ascending UTF-16 offsets into byte offsets of `text`, dropping
/// those past its end. At most 10 000 pages are kept.
fn utf16_to_bytes(text: &str, offsets: &[u32]) -> Vec<u32> {
    let mut out = Vec::with_capacity(offsets.len().min(10_000));
    let mut wanted = offsets.iter().copied().peekable();
    let mut units = 0u32;
    for (byte, c) in text.char_indices() {
        while let Some(&w) = wanted.peek() {
            if w > units {
                break;
            }
            if out.len() < 10_000 && out.last().is_none_or(|&l| l <= byte as u32) {
                out.push(byte as u32);
            }
            wanted.next();
        }
        units += c.len_utf16() as u32;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU32, Ordering};

    static NEXT: AtomicU32 = AtomicU32::new(0);

    /// A fresh directory under the system temp dir, removed on drop.
    struct TempDir(PathBuf);

    impl TempDir {
        fn new() -> Self {
            let n = NEXT.fetch_add(1, Ordering::Relaxed);
            let dir = std::env::temp_dir().join(format!("ezfiles-search-{}-{n}", std::process::id()));
            fs::remove_dir_all(&dir).ok();
            Self(dir)
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            fs::remove_dir_all(&self.0).ok();
        }
    }

    fn keys(hits: &[Hit]) -> Vec<&str> {
        hits.iter().map(|h| h.key.as_str()).collect()
    }

    fn sample(dir: &Path) -> SearchIndex {
        let index = SearchIndex::open(dir, IndexConfig::default());
        let docs = [
            ("trip", "The Paris trip budget is final. Hotels in Paris are booked."),
            ("budget", "Budget 2025: budget lines, budget owners and the budget review."),
            ("notes", "Meeting notes: nothing about money here."),
            ("recipe", "Crème brûlée recipe: cream, sugar, eggs."),
        ];
        for (key, text) in docs {
            assert_eq!(index.add(key, 1, 1, text, &[]).unwrap(), Added::Indexed);
        }
        index
    }

    #[test]
    fn ranks_by_bm25_with_all_words_first() {
        let dir = TempDir::new();
        let index = sample(&dir.0);
        assert_eq!(keys(&index.query("budget ", 10)), vec!["budget", "trip"]);
        assert_eq!(keys(&index.query("paris budget ", 10)), vec!["trip", "budget"]);
        assert!(index.query("zebra ", 10).is_empty());
        assert!(index.query("   ", 10).is_empty());
    }

    #[test]
    fn folds_accents_stems_plurals_and_expands_the_typed_prefix() {
        let dir = TempDir::new();
        let index = sample(&dir.0);
        assert_eq!(keys(&index.query("creme brulee", 10)), vec!["recipe"]);
        assert_eq!(keys(&index.query("hotel ", 10)), vec!["trip"]);
        assert_eq!(keys(&index.query("meet", 10)), vec!["notes"]);
        // Followed by a space, "meet" is a whole word and finds nothing.
        assert!(index.query("meet ", 10).is_empty());
    }

    #[test]
    fn snippets_highlight_matches_and_find_pdf_pages() {
        let dir = TempDir::new();
        let index = SearchIndex::open(&dir.0, IndexConfig::default());
        let text = "Page one is about apples.\nPage two talks about the quarterly invoice total.";
        let page_two = text.find("Page two").unwrap() as u32; // ASCII: bytes are UTF-16 units.
        index.add("doc", 1, 1, text, &[0, page_two]).unwrap();
        let hit = &index.query("invoice", 5)[0];
        assert_eq!(hit.page, Some(1));
        let units: Vec<u16> = hit.snippet.encode_utf16().collect();
        let (a, b) = hit.ranges[0];
        assert_eq!(String::from_utf16(&units[a as usize..b as usize]).unwrap(), "invoice");
    }

    #[test]
    fn freshness_updates_and_removal() {
        let dir = TempDir::new();
        let index = sample(&dir.0);
        let entries = vec![
            Entry { key: "trip".into(), size: 1, mtime: 1 },
            Entry { key: "budget".into(), size: 2, mtime: 1 },
            Entry { key: "new".into(), size: 1, mtime: 1 },
        ];
        assert_eq!(index.stale(&entries), vec![1, 2]);

        index.add("budget", 2, 1, "Nothing about money any more.", &[]).unwrap();
        assert_eq!(keys(&index.query("budget ", 10)), vec!["trip"]);
        assert_eq!(index.stale(&entries), vec![2]);

        assert_eq!(index.retain(&["trip".into(), "budget".into()]), 2);
        assert_eq!(index.len(), 2);
        assert!(index.query("creme", 10).is_empty());
        assert!(index.remove("trip"));
        assert!(!index.remove("trip"));
        assert!(index.query("paris", 10).is_empty());
    }

    #[test]
    fn saves_and_reloads() {
        let dir = TempDir::new();
        let before = {
            let index = sample(&dir.0);
            index.remove("notes");
            index.save().unwrap();
            index.query("budget", 10)
        };
        let index = SearchIndex::open(&dir.0, IndexConfig::default());
        assert_eq!(index.len(), 3);
        assert_eq!(index.query("budget", 10), before);
        assert!(index.query("meeting", 10).is_empty());
        // Another file id goes to the next document, not over an old text.
        index.add("more", 1, 1, "Paris again", &[]).unwrap();
        assert_eq!(index.query("paris", 10).len(), 2);
        assert_eq!(index.query("hotels", 10)[0].key, "trip");
    }

    #[test]
    fn unsaved_replacement_keeps_the_committed_snippet() {
        let dir = TempDir::new();
        let expected = {
            let index = sample(&dir.0);
            index.save().unwrap();
            let hits = index.query("hotels", 10);
            assert!(!hits.is_empty());
            index.add("trip", 2, 2, "different text", &[]).unwrap();
            hits
        };
        let reopened = SearchIndex::open(&dir.0, IndexConfig::default());
        assert_eq!(reopened.query("hotels", 10), expected);
    }

    #[test]
    fn damaged_files_start_empty_and_orphans_are_removed() {
        let dir = TempDir::new();
        {
            let index = sample(&dir.0);
            index.save().unwrap();
            // Indexed after the save: its text is an orphan on the next open.
            index.add("unsaved", 1, 1, "orphan text", &[]).unwrap();
        }
        let texts = || fs::read_dir(dir.0.join(TEXTS)).map(|d| d.count()).unwrap_or(0);
        assert_eq!(texts(), 5);
        let index = SearchIndex::open(&dir.0, IndexConfig::default());
        assert_eq!(index.len(), 4);
        assert_eq!(texts(), 4);
        drop(index);

        let path = dir.0.join(INDEX_FILE);
        let mut bytes = fs::read(&path).unwrap();
        let middle = bytes.len() / 2;
        bytes[middle] ^= 0x5A;
        fs::write(&path, &bytes).unwrap();
        let index = SearchIndex::open(&dir.0, IndexConfig::default());
        assert_eq!(index.len(), 0);
        assert_eq!(texts(), 0);
        assert!(index.query("budget", 10).is_empty());
    }

    #[test]
    fn clear_deletes_everything() {
        let dir = TempDir::new();
        let index = sample(&dir.0);
        index.save().unwrap();
        index.clear();
        assert_eq!(index.len(), 0);
        assert!(!dir.0.join(INDEX_FILE).exists());
        assert!(index.query("budget", 10).is_empty());
        index.add("again", 1, 1, "budget", &[]).unwrap();
        assert_eq!(index.query("budget", 10).len(), 1);
    }

    #[test]
    fn budgets_are_enforced() {
        let dir = TempDir::new();
        let config = IndexConfig { max_docs: 2, max_terms_per_doc: 3, ..IndexConfig::default() };
        let index = SearchIndex::open(&dir.0, config);
        index.add("a", 1, 1, "one two three four five", &[]).unwrap();
        assert!(index.query("five", 10).is_empty(), "words past the per-document cap are not indexed");
        assert_eq!(index.query("three", 10).len(), 1);
        index.add("b", 1, 1, "x", &[]).unwrap();
        assert!(!index.has_room());
        assert_eq!(index.add("c", 1, 1, "y", &[]).unwrap(), Added::Full);
        // Replacing a document that is already there still works.
        assert_eq!(index.add("b", 2, 1, "z", &[]).unwrap(), Added::Indexed);

        let dir = TempDir::new();
        let config = IndexConfig { max_text_bytes_per_doc: 8, ..IndexConfig::default() };
        let index = SearchIndex::open(&dir.0, config);
        index.add("a", 1, 1, "ééééé tail", &[]).unwrap();
        assert!(index.query("tail", 10).is_empty());
    }

    #[test]
    fn empty_text_is_recorded_as_fresh() {
        let dir = TempDir::new();
        let index = SearchIndex::open(&dir.0, IndexConfig::default());
        index.add("locked.pdf", 5, 7, "", &[]).unwrap();
        assert!(index.stale(&[Entry { key: "locked.pdf".into(), size: 5, mtime: 7 }]).is_empty());
        assert!(index.query("anything", 10).is_empty());
    }

    #[test]
    fn query_terms_mark_only_a_word_being_typed_as_prefix() {
        let terms = query_terms("Invoices invoice tot");
        assert_eq!(terms.len(), 2);
        assert_eq!(terms[0].stemmed, "invoice");
        assert!(!terms[0].prefix);
        assert!(terms[1].prefix);
        assert!(!query_terms("total.").last().unwrap().prefix);
    }

    #[test]
    fn converts_page_offsets() {
        assert_eq!(utf16_to_bytes("a😀bé c", &[0, 1, 3, 4, 99]), vec![0, 1, 5, 6]);
    }

    #[test]
    fn indexes_and_queries_a_large_library_quickly() {
        let dir = TempDir::new();
        let index = SearchIndex::open(&dir.0, IndexConfig::default());
        let words: Vec<String> = (0..3000).map(|i| format!("w{i}x")).collect();
        let start = std::time::Instant::now();
        for d in 0..300 {
            let text: Vec<&str> = (0..2000).map(|i| words[(d * 7 + i * 13) % words.len()].as_str()).collect();
            index.add(&format!("doc{d}"), 1, 1, &text.join(" "), &[]).unwrap();
        }
        let built = start.elapsed();
        let start = std::time::Instant::now();
        let hits = index.query("w42x w7", 20);
        assert!(!hits.is_empty());
        assert!(start.elapsed().as_millis() < 1000, "query took {:?}", start.elapsed());
        assert!(built.as_secs() < 30, "indexing took {built:?}");
        index.save().unwrap();
        let reopened = SearchIndex::open(&dir.0, IndexConfig::default());
        assert_eq!(reopened.len(), 300);
    }
}
