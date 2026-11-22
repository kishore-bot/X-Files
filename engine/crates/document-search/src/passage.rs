//! Retrieval of the passages of one document that answer a question.
//!
//! A document is cut into chunks of about [`PassageConfig::target_chars`]
//! (roughly 250 tokens of English): paragraphs and sentences are kept whole
//! where they fit, long ones are split between words, and consecutive chunks
//! overlap by about a sentence. No chunk spans two PDF pages. Each chunk is
//! indexed with BM25. A question then takes the best candidates, drops
//! near-duplicates (maximal marginal relevance over term sets), adds the
//! chunk after each pick for context, and packs what fits a character budget,
//! in document order.
//!
//! [`PassageCache`] keeps indexes of recently used documents, keyed by an
//! identity and validated by modified time and size, within a count and a
//! byte bound. Building an index is CPU work: callers build it off the main
//! thread and then query it.

use std::cmp::Ordering;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use crate::index::{analyze, query_terms};
use crate::lru::Lru;

const K1: f32 = 1.2;
const B: f32 = 0.75;
/// Chunks sharing this much of their word set (Jaccard) count as duplicates.
const DUPLICATE_SIMILARITY: f32 = 0.6;
/// Joins passages in a prompt: "\n", an ellipsis, "\n". Kept in step with the Kotlin renderer.
pub const SEPARATOR_CHARS: usize = 3;
/// Question words that match almost every chunk; they are not searched.
const STOP_WORDS: &[&str] = &[
    "the", "and", "for", "are", "was", "were", "what", "which", "who", "whom", "when", "where", "why", "how",
    "does", "did", "this", "that", "these", "those", "there", "with", "from", "about", "into", "say", "says",
    "said", "tell", "show", "find", "give", "please", "file", "document", "doc", "page", "pages", "text", "can",
    "you", "your", "mine", "any", "all", "much", "many", "have", "has", "had", "its", "they", "them", "summarize",
    "summary", "read", "explain", "mention", "mentioned", "fily", "hey", "okay",
];

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PassageConfig {
    /// Characters per chunk; about 250 tokens at four characters a token.
    pub target_chars: usize,
    /// Characters of trailing text a chunk shares with the next one.
    pub overlap_chars: usize,
    /// Text beyond this is not indexed; the result is then marked omitted.
    pub max_text_bytes: usize,
    /// Passages returned at most.
    pub max_passages: usize,
    /// Best-scoring chunks considered for diversity.
    pub candidates: usize,
    /// Relevance weight against redundancy in maximal marginal relevance (1 = relevance only).
    pub mmr_lambda: f32,
}

impl Default for PassageConfig {
    fn default() -> Self {
        Self {
            target_chars: 1000,
            overlap_chars: 160,
            max_text_bytes: 4 * 1024 * 1024,
            max_passages: 6,
            candidates: 24,
            mmr_lambda: 0.7,
        }
    }
}

/// A passage for the prompt: whitespace collapsed to single spaces.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Passage {
    pub text: String,
    /// The 1-based PDF page the passage starts on.
    pub page: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Selection {
    /// In document order.
    pub passages: Vec<Passage>,
    /// True when some of the document's text was not selected or not indexed.
    pub omitted: bool,
    /// Bytes of the indexed text; a caller can tell from it that the whole document fits a budget.
    pub document_bytes: usize,
}

#[derive(Debug, Clone, Copy)]
struct Unit {
    start: usize,
    end: usize,
    page: Option<u32>,
}

struct Chunk {
    start: usize,
    end: usize,
    page: Option<u32>,
    chars: usize,
    /// Stemmed word counts; the chunk's length in words is their sum.
    terms: HashMap<String, u32>,
    words: u32,
}

/// The chunks of one document, with a BM25 posting list per term.
pub struct PassageIndex {
    text: String,
    chunks: Vec<Chunk>,
    /// Term to (chunk index, term frequency), in chunk order.
    postings: HashMap<String, Vec<(u32, u32)>>,
    average_words: f32,
    config: PassageConfig,
    truncated: bool,
    footprint: usize,
}

impl PassageIndex {
    /// Indexes `text`. `page_starts` are the byte offsets where PDF pages begin, from page 1.
    pub fn new(text: &str, page_starts: &[u32], config: PassageConfig) -> Self {
        let (text, truncated) = prefix_within(text, config.max_text_bytes);
        let starts = clean_starts(page_starts, text.len());
        let units = units(text, &starts, config.target_chars);
        let chunks: Vec<Chunk> = pack(&units, &config)
            .into_iter()
            .map(|(start, end, page)| {
                let (terms, words) = analyze(&text[start..end], usize::MAX);
                Chunk { start, end, page, chars: text[start..end].chars().count(), terms, words }
            })
            .collect();
        let mut postings: HashMap<String, Vec<(u32, u32)>> = HashMap::new();
        let mut total_words = 0u64;
        let mut posting_count = 0usize;
        let mut term_bytes = 0usize;
        for (index, chunk) in chunks.iter().enumerate() {
            total_words += u64::from(chunk.words);
            for (term, &frequency) in &chunk.terms {
                posting_count += 1;
                let list = postings.entry(term.clone()).or_insert_with(|| {
                    term_bytes += term.len();
                    Vec::new()
                });
                list.push((index as u32, frequency));
            }
        }
        let average_words = if chunks.is_empty() { 1.0 } else { (total_words as f32 / chunks.len() as f32).max(1.0) };
        let footprint = text.len() + chunks.len() * 64 + posting_count * 24 + term_bytes;
        Self { text: text.to_owned(), chunks, postings, average_words, config, truncated, footprint }
    }

    /// Approximate heap use, for the cache's byte bound.
    pub fn footprint(&self) -> usize {
        self.footprint
    }

    pub fn chunk_count(&self) -> usize {
        self.chunks.len()
    }

    /// The best passages for `question` within `budget_chars`, including the
    /// separators and labels a prompt adds. Empty (and omitted) when no word of
    /// the question occurs in the document.
    pub fn select(&self, question: &str, budget_chars: usize) -> Selection {
        let terms: Vec<String> = query_terms(question)
            .into_iter()
            .filter(|term| !STOP_WORDS.contains(&term.folded.as_str()))
            .map(|term| term.stemmed)
            .collect();
        let scores = self.scores(&terms);
        let mut ranked: Vec<(usize, f32)> = scores.iter().enumerate().filter(|(_, s)| **s > 0.0).map(|(i, s)| (i, *s)).collect();
        if ranked.is_empty() {
            return Selection { passages: Vec::new(), omitted: true, document_bytes: self.text.len() };
        }
        ranked.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(Ordering::Equal).then(a.0.cmp(&b.0)));
        ranked.truncate(self.config.candidates);
        let top = ranked[0].1;

        let picks = self.diverse(ranked, top);
        // Each pick brings the chunk after it, which often holds the answer's second half.
        let mut ordered = picks.clone();
        for &index in &picks {
            let next = index + 1;
            let same_page = next < self.chunks.len() && self.chunks[next].page == self.chunks[index].page;
            if same_page && !ordered.contains(&next) && self.similarity(next, index) < DUPLICATE_SIMILARITY {
                ordered.push(next);
            }
        }

        let mut kept = Vec::new();
        let mut used = 0usize;
        for &index in &ordered {
            let cost = self.cost(index) + if kept.is_empty() { 0 } else { SEPARATOR_CHARS };
            if used + cost <= budget_chars {
                kept.push(index);
                used += cost;
            }
        }
        if kept.is_empty() {
            // The best chunk alone is larger than the budget: a truncated copy beats nothing.
            let head = ordered[0];
            let label = label_chars(self.chunks[head].page);
            let body = take_chars(&collapse(self.chunk_text(head)), budget_chars.saturating_sub(label));
            return Selection {
                passages: vec![Passage { text: body, page: self.chunks[head].page }],
                omitted: true,
                document_bytes: self.text.len(),
            };
        }
        kept.sort_unstable();
        let omitted = self.truncated || kept.len() < self.chunks.len();
        Selection { passages: self.merge_runs(&kept), omitted, document_bytes: self.text.len() }
    }

    /// Maximal marginal relevance: each step takes the candidate that balances score
    /// against similarity to the chunks already taken.
    fn diverse(&self, mut pool: Vec<(usize, f32)>, top: f32) -> Vec<usize> {
        let lambda = self.config.mmr_lambda;
        let mut chosen: Vec<usize> = Vec::new();
        while chosen.len() < self.config.max_passages && !pool.is_empty() {
            let mut best = 0;
            let mut best_value = f32::NEG_INFINITY;
            for (position, &(index, score)) in pool.iter().enumerate() {
                let redundancy = chosen.iter().map(|&c| self.similarity(index, c)).fold(0.0, f32::max);
                let value = lambda * (score / top) - (1.0 - lambda) * redundancy;
                if value > best_value {
                    best_value = value;
                    best = position;
                }
            }
            chosen.push(pool.swap_remove(best).0);
        }
        chosen
    }

    /// Jaccard similarity of two chunks' word sets.
    fn similarity(&self, a: usize, b: usize) -> f32 {
        let (left, right) = (&self.chunks[a].terms, &self.chunks[b].terms);
        let (small, large) = if left.len() <= right.len() { (left, right) } else { (right, left) };
        let shared = small.keys().filter(|term| large.contains_key(*term)).count();
        let union = left.len() + right.len() - shared;
        if union == 0 { 0.0 } else { shared as f32 / union as f32 }
    }

    /// BM25 score of every chunk for the given stemmed terms.
    fn scores(&self, terms: &[String]) -> Vec<f32> {
        let count = self.chunks.len() as f32;
        let mut scores = vec![0.0f32; self.chunks.len()];
        for term in terms {
            let Some(list) = self.postings.get(term) else { continue };
            let documents = list.len() as f32;
            let idf = ((count - documents + 0.5) / (documents + 0.5) + 1.0).ln();
            for &(index, frequency) in list {
                let frequency = frequency as f32;
                let length = self.chunks[index as usize].words as f32 / self.average_words;
                let norm = K1 * (1.0 - B + B * length);
                scores[index as usize] += idf * frequency * (K1 + 1.0) / (frequency + norm);
            }
        }
        scores
    }

    fn chunk_text(&self, index: usize) -> &str {
        &self.text[self.chunks[index].start..self.chunks[index].end]
    }

    fn cost(&self, index: usize) -> usize {
        let body = collapse(self.chunk_text(index)).chars().count();
        label_chars(self.chunks[index].page) + body
    }

    /// Joins runs of consecutive chunks on one page into one passage, cut from the document text.
    fn merge_runs(&self, kept: &[usize]) -> Vec<Passage> {
        let mut passages = Vec::new();
        let mut run_start = kept[0];
        let mut run_end = kept[0];
        for &index in &kept[1..] {
            if index == run_end + 1 && self.chunks[index].page == self.chunks[run_start].page {
                run_end = index;
            } else {
                passages.push(self.passage(run_start, run_end));
                run_start = index;
                run_end = index;
            }
        }
        passages.push(self.passage(run_start, run_end));
        passages
    }

    fn passage(&self, first: usize, last: usize) -> Passage {
        let text = &self.text[self.chunks[first].start..self.chunks[last].end];
        Passage { text: collapse(text), page: self.chunks[first].page }
    }

    /// Characters per chunk, for tests and the benchmark.
    pub fn chunk_chars(&self) -> Vec<usize> {
        self.chunks.iter().map(|chunk| chunk.chars).collect()
    }

    /// The chunk ranges `(start, end, page)`, for tests.
    pub fn chunk_ranges(&self) -> Vec<(usize, usize, Option<u32>)> {
        self.chunks.iter().map(|chunk| (chunk.start, chunk.end, chunk.page)).collect()
    }
}

fn label_chars(page: Option<u32>) -> usize {
    match page {
        Some(page) => "[page ] ".len() + page.to_string().len(),
        None => 0,
    }
}

/// Whitespace runs become single spaces.
fn collapse(text: &str) -> String {
    text.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn take_chars(text: &str, count: usize) -> String {
    text.chars().take(count).collect()
}

/// The longest prefix of `text` of at most `max_bytes`, cut at a character boundary.
fn prefix_within(text: &str, max_bytes: usize) -> (&str, bool) {
    if text.len() <= max_bytes {
        return (text, false);
    }
    let mut end = max_bytes;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    (&text[..end], true)
}

/// Page starts as usable offsets: in range, ascending, without repeats.
fn clean_starts(page_starts: &[u32], len: usize) -> Vec<usize> {
    let mut starts: Vec<usize> = page_starts.iter().map(|&s| s as usize).filter(|&s| s < len).collect();
    starts.sort_unstable();
    starts.dedup();
    starts
}

/// Sentence-sized units per page. A page without a known start is one unit stream of its own.
fn units(text: &str, starts: &[usize], max_chars: usize) -> Vec<Unit> {
    let mut out = Vec::new();
    if starts.is_empty() {
        split_segment(text, 0, text.len(), None, max_chars, &mut out);
        return out;
    }
    if starts[0] > 0 {
        split_segment(text, 0, starts[0], None, max_chars, &mut out);
    }
    for (index, &start) in starts.iter().enumerate() {
        let end = starts.get(index + 1).copied().unwrap_or(text.len());
        split_segment(text, start, end, Some(index as u32 + 1), max_chars, &mut out);
    }
    out
}

/// Splits at line breaks and after sentence punctuation followed by a space.
fn split_segment(text: &str, start: usize, end: usize, page: Option<u32>, max_chars: usize, out: &mut Vec<Unit>) {
    let slice = &text[start..end];
    let mut unit_start = start;
    for (offset, c) in slice.char_indices() {
        let at = start + offset;
        let stop = match c {
            '\n' => Some(at),
            '.' | '!' | '?' if slice[offset + 1..].chars().next().is_none_or(char::is_whitespace) => Some(at + 1),
            _ => None,
        };
        if let Some(stop) = stop {
            push_unit(text, unit_start, stop, page, max_chars, out);
            unit_start = at + c.len_utf8();
        }
    }
    push_unit(text, unit_start, end, page, max_chars, out);
}

fn push_unit(text: &str, start: usize, end: usize, page: Option<u32>, max_chars: usize, out: &mut Vec<Unit>) {
    let raw = &text[start..end];
    let start = start + (raw.len() - raw.trim_start().len());
    let trimmed = text[start..end].trim_end();
    if trimmed.is_empty() {
        return;
    }
    let end = start + trimmed.len();
    if trimmed.chars().count() <= max_chars {
        out.push(Unit { start, end, page });
        return;
    }
    // Long runs without punctuation (PDF text often lacks it): split between words.
    let mut word_start: Option<usize> = None;
    let mut words = Vec::new();
    for (offset, c) in text[start..end].char_indices() {
        match (c.is_whitespace(), word_start) {
            (false, None) => word_start = Some(start + offset),
            (true, Some(s)) => {
                words.push((s, start + offset));
                word_start = None;
            }
            _ => {}
        }
    }
    if let Some(s) = word_start {
        words.push((s, end));
    }
    let mut piece_start = words[0].0;
    let mut piece_end = words[0].1;
    let mut piece_chars = text[piece_start..piece_end].chars().count();
    for &(word_begin, word_finish) in &words[1..] {
        let word_chars = text[word_begin..word_finish].chars().count();
        if piece_chars + 1 + word_chars > max_chars {
            out.push(Unit { start: piece_start, end: piece_end, page });
            piece_start = word_begin;
            piece_chars = word_chars;
        } else {
            piece_chars += 1 + word_chars;
        }
        piece_end = word_finish;
    }
    out.push(Unit { start: piece_start, end: piece_end, page });
}

/// Packs units into chunks of at most `target_chars`, never across a page, and
/// steps back so each chunk repeats up to `overlap_chars` of the one before.
fn pack(units: &[Unit], config: &PassageConfig) -> Vec<(usize, usize, Option<u32>)> {
    let mut ranges = Vec::new();
    let mut first = 0;
    while first < units.len() {
        let page = units[first].page;
        let mut last = first;
        let mut chars = unit_chars(&units[first]);
        while last + 1 < units.len() && units[last + 1].page == page {
            let next = unit_chars(&units[last + 1]) + 1;
            if chars + next > config.target_chars {
                break;
            }
            chars += next;
            last += 1;
        }
        ranges.push((units[first].start, units[last].end, page));
        if last + 1 >= units.len() {
            break;
        }
        // Always move forward: the next chunk starts after `first`.
        let mut next_first = last + 1;
        let mut carried = 0;
        while next_first > first + 1 && units[next_first - 1].page == page {
            let size = unit_chars(&units[next_first - 1]) + 1;
            // One sentence always carries over, even when it is longer than the overlap budget.
            if carried > 0 && carried + size > config.overlap_chars {
                break;
            }
            carried += size;
            next_first -= 1;
        }
        first = next_first;
    }
    ranges
}

/// The character length of a unit. Units are cut from text, so this is exact.
fn unit_chars(unit: &Unit) -> usize {
    unit.end - unit.start
}

/// Indexes held by a [`PassageCache`], with their validators.
struct Cached {
    mtime: i64,
    size: u64,
    index: Arc<PassageIndex>,
}

struct CacheState {
    entries: Lru<String, Cached>,
    footprint: usize,
}

/// Indexes of recently used documents, bounded by count and by bytes.
/// Safe to share between threads; the lock is held only for lookups and inserts.
pub struct PassageCache {
    state: Mutex<CacheState>,
    max_documents: usize,
    max_bytes: usize,
    config: PassageConfig,
}

impl PassageCache {
    pub fn new(max_documents: usize, max_bytes: usize) -> Self {
        Self::with_config(max_documents, max_bytes, PassageConfig::default())
    }

    pub fn with_config(max_documents: usize, max_bytes: usize, config: PassageConfig) -> Self {
        let state = CacheState { entries: Lru::default(), footprint: 0 };
        Self { state: Mutex::new(state), max_documents: max_documents.max(1), max_bytes, config }
    }

    /// The index for `key` if it was built from a document with this `mtime` and `size`.
    pub fn get(&self, key: &str, mtime: i64, size: u64) -> Option<Arc<PassageIndex>> {
        let mut state = self.state.lock().ok()?;
        let owned = key.to_owned();
        let cached = state.entries.get(&owned)?;
        (cached.mtime == mtime && cached.size == size).then(|| cached.index.clone())
    }

    /// Caches `index` for `key`, evicting the least recently used documents over either bound.
    pub fn insert(&self, key: &str, mtime: i64, size: u64, index: PassageIndex) -> Arc<PassageIndex> {
        let index = Arc::new(index);
        let Ok(mut state) = self.state.lock() else { return index };
        let footprint = index.footprint();
        let cached = Cached { mtime, size, index: index.clone() };
        if let Some(old) = state.entries.insert(key.to_owned(), cached) {
            state.footprint -= old.index.footprint();
        }
        state.footprint += footprint;
        while state.entries.len() > self.max_documents || state.footprint > self.max_bytes {
            let Some((_, evicted)) = state.entries.pop_oldest() else { break };
            state.footprint -= evicted.index.footprint();
        }
        index
    }

    /// The cached index for `key`, or a new one built from `text` and cached.
    pub fn get_or_build(&self, key: &str, mtime: i64, size: u64, text: &str, page_starts: &[u32]) -> Arc<PassageIndex> {
        if let Some(index) = self.get(key, mtime, size) {
            return index;
        }
        let index = PassageIndex::new(text, page_starts, self.config);
        self.insert(key, mtime, size, index)
    }

    pub fn document_count(&self) -> usize {
        self.state.lock().map_or(0, |state| state.entries.len())
    }

    pub fn footprint(&self) -> usize {
        self.state.lock().map_or(0, |state| state.footprint)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn small() -> PassageConfig {
        PassageConfig { target_chars: 120, overlap_chars: 30, max_text_bytes: 1 << 20, max_passages: 3, candidates: 12, mmr_lambda: 0.7 }
    }

    fn paragraph(topic: &str, filler: &str) -> String {
        format!("{topic} {filler}. {filler} {filler}.")
    }

    #[test]
    fn chunks_stay_near_the_target_and_cover_the_text_in_order() {
        let text = (0..40).map(|i| format!("Sentence number {i} talks about storage and renewal terms.")).collect::<Vec<_>>().join(" ");
        let index = PassageIndex::new(&text, &[], small());
        let ranges = index.chunk_ranges();
        assert!(ranges.len() > 5, "expected several chunks, got {}", ranges.len());
        assert_eq!(ranges[0].0, 0);
        assert_eq!(ranges.last().unwrap().1, text.trim_end().len());
        for pair in ranges.windows(2) {
            assert!(pair[1].0 > pair[0].0, "chunks must advance");
            assert!(pair[1].0 < pair[0].1, "consecutive chunks overlap (or touch) by design");
        }
        for chars in index.chunk_chars() {
            assert!(chars <= small().target_chars + 40, "chunk of {chars} chars exceeds target");
        }
    }

    #[test]
    fn no_chunk_spans_two_pages_and_pages_are_numbered_from_one() {
        let page_one = paragraph("Invoice", "amount due");
        let page_two = paragraph("Terms", "payment within thirty days");
        let text = format!("{page_one}\n\n{page_two}");
        let starts = [0, page_one.len() as u32 + 2];
        let index = PassageIndex::new(&text, &starts, small());
        for (start, end, page) in index.chunk_ranges() {
            let expected = if start < page_one.len() { 1 } else { 2 };
            assert_eq!(page, Some(expected));
            assert!(end <= if expected == 1 { page_one.len() } else { text.len() });
        }
    }

    #[test]
    fn bm25_ranks_the_chunk_with_the_rare_term_first() {
        let common = "The report lists the usual items. ".repeat(3);
        let text = format!("{common}\n\n{common}\n\nThe passport number is XQ-7731 issued in Lisbon. {common}");
        let index = PassageIndex::new(&text, &[], small());
        let selection = index.select("What is the passport number?", 400);
        assert!(!selection.passages.is_empty());
        assert!(selection.passages[0].text.contains("XQ-7731"), "got {:?}", selection.passages[0].text);
    }

    #[test]
    fn stop_words_only_question_selects_nothing_and_is_omitted() {
        let index = PassageIndex::new("Some text about payments.", &[], small());
        let selection = index.select("what does this say?", 500);
        assert!(selection.passages.is_empty());
        assert!(selection.omitted);
    }

    #[test]
    fn budget_is_never_exceeded_including_labels_and_separators() {
        let text = (0..60).map(|i| format!("Clause {i} covers liability for damage and repairs at the site.")).collect::<Vec<_>>().join(" ");
        let index = PassageIndex::new(&text, &[], small());
        for budget in [60, 150, 300, 900] {
            let selection = index.select("liability damage repairs", budget);
            let rendered: usize = selection
                .passages
                .iter()
                .map(|p| label_chars(p.page) + p.text.chars().count())
                .sum::<usize>()
                + SEPARATOR_CHARS * selection.passages.len().saturating_sub(1);
            assert!(rendered <= budget, "budget {budget}: rendered {rendered}");
            assert!(!selection.passages.is_empty(), "budget {budget} should still return something");
        }
    }

    #[test]
    fn oversized_best_chunk_is_truncated_rather_than_dropped() {
        let text = format!("Liability {}", "word ".repeat(200));
        let index = PassageIndex::new(&text, &[], small());
        let selection = index.select("liability", 50);
        assert_eq!(selection.passages.len(), 1);
        assert!(selection.passages[0].text.chars().count() <= 50);
        assert!(selection.omitted);
    }

    #[test]
    fn near_duplicate_chunks_are_not_both_selected() {
        let repeated = "Payment is due within thirty days of the invoice date for all services.";
        let text = format!("{repeated}\n\n{repeated}\n\nSeparate clause about warranty and returns for defects.");
        let index = PassageIndex::new(&text, &[], small());
        let selection = index.select("payment due days", 1000);
        let payments = selection.passages.iter().filter(|p| p.text.contains("Payment is due")).count();
        assert_eq!(payments, 1, "duplicates should be dropped: {:?}", selection.passages);
    }

    #[test]
    fn long_text_without_punctuation_splits_between_words() {
        let text = "alpha beta gamma delta ".repeat(40);
        let index = PassageIndex::new(&text, &[], small());
        for chars in index.chunk_chars() {
            assert!(chars <= small().target_chars + 40);
        }
        let first_word_ok = index.chunk_ranges().iter().all(|&(s, e, _)| text[s..e].chars().all(|c| c.is_ascii()));
        assert!(first_word_ok);
    }

    #[test]
    fn text_past_the_cap_is_omitted_and_flagged() {
        let config = PassageConfig { max_text_bytes: 64, ..small() };
        let index = PassageIndex::new(&"needle ".repeat(100), &[], config);
        let selection = index.select("needle", 500);
        assert!(selection.omitted);
    }

    #[test]
    fn cache_validates_mtime_and_size_and_evicts_least_recent() {
        let cache = PassageCache::with_config(2, usize::MAX, small());
        cache.get_or_build("a", 1, 10, "alpha text", &[]);
        cache.get_or_build("b", 1, 10, "beta text", &[]);
        assert!(cache.get("a", 1, 10).is_some());
        assert!(cache.get("a", 2, 10).is_none(), "a newer mtime must not reuse the index");
        cache.get_or_build("c", 1, 10, "gamma text", &[]);
        assert_eq!(cache.document_count(), 2);
        assert!(cache.get("b", 1, 10).is_none(), "b was least recently used");
        assert!(cache.get("a", 1, 10).is_some());
    }

    #[test]
    fn cache_respects_its_byte_bound() {
        let big = "x ".repeat(5_000);
        let one = PassageIndex::new(&big, &[], small()).footprint();
        let cache = PassageCache::with_config(10, one + one / 2, small());
        cache.get_or_build("a", 1, 1, &big, &[]);
        cache.get_or_build("b", 1, 1, &big, &[]);
        assert_eq!(cache.document_count(), 1);
        assert!(cache.footprint() <= one + one / 2);
    }

    /// Cost of indexing and answering on a 1 MB, 215-page document. Run with
    /// `cargo test -p document-search passage_benchmark --release -- --ignored --nocapture`.
    /// Measured on the WSL x86-64 host, release build: 1,713 chunks, build 28 ms, query 286 us
    /// average (3 passages), footprint 2.7 MB. A phone is several times slower; the first question
    /// per document pays the build, later ones pay only the query.
    #[test]
    #[ignore]
    fn passage_benchmark() {
        let sentences = [
            "The tenant pays the monthly rent on the first day of each month by bank transfer.",
            "Late payments incur a fee of two percent after a seven day grace period.",
            "Either party may terminate this agreement with ninety days written notice.",
            "The landlord repairs structural defects within a reasonable time after notice.",
            "Pets require prior written consent and a refundable deposit of one month rent.",
        ];
        let mut text = String::new();
        let mut page_starts = Vec::new();
        let mut index = 0usize;
        while text.len() < 1_000_000 {
            if index % 60 == 0 {
                page_starts.push(text.len() as u32);
            }
            text.push_str(sentences[index % sentences.len()]);
            text.push_str(if index % 4 == 3 { "\n\n" } else { " " });
            index += 1;
        }
        let started = std::time::Instant::now();
        let built = PassageIndex::new(&text, &page_starts, PassageConfig::default());
        let build_ms = started.elapsed().as_secs_f64() * 1000.0;
        let questions = ["When is the rent paid?", "What is the late payment fee?", "Pets deposit"];
        let started = std::time::Instant::now();
        let mut passages = 0;
        for _ in 0..200 {
            for question in questions {
                passages += built.select(question, 3000).passages.len();
            }
        }
        let query_us = started.elapsed().as_secs_f64() * 1_000_000.0 / 600.0;
        println!(
            "passage benchmark: {} bytes, {} pages, {} chunks, build {build_ms:.1} ms, query {query_us:.1} us avg, {} passages, footprint {} KB",
            text.len(),
            page_starts.len(),
            built.chunk_count(),
            passages as f64 / 600.0,
            built.footprint() / 1024,
        );
    }
}
