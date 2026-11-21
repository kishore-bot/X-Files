//! Ranked, typo-tolerant matching of file names.
//!
//! Names are prepared once ([`NameIndex::new`]); each keystroke then scores
//! every name in one pass without allocating per name. A name matches when
//! every word of the query matches one of its words, by (best first): the
//! whole word, a prefix of it, a part of it, a bounded Damerau-Levenshtein
//! distance (one typo from 4 letters, two from 8), or trigram similarity.
//! Words with digits never match fuzzily, so "2025" doesn't find "2024".

use crate::fold::fold;
use crate::tokens::{NameWord, name_words};

const EXACT: f32 = 1.0;
const PART: f32 = 0.7;
const COMPACT_PART: f32 = 0.6;
const ONE_TYPO: f32 = 0.6;
const TWO_TYPOS: f32 = 0.45;
const TYPO_PREFIX: f32 = 0.45;
const TRIGRAM_WEIGHT: f32 = 0.5;
const TRIGRAM_MIN: f32 = 0.55;

struct PreparedName {
    words: Vec<PreparedWord>,
    /// Folded, separators kept: for queries that have no words at all.
    folded: String,
    /// Folded words joined without separators: "annualreport2025pdf".
    compact: String,
}

struct PreparedWord {
    word: NameWord,
    grams: Vec<u64>,
}

/// A match: the name's position in the list given to [`NameIndex::new`].
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct NameMatch {
    pub index: u32,
    pub score: f32,
}

/// File names prepared for searching.
pub struct NameIndex {
    names: Vec<PreparedName>,
}

impl NameIndex {
    pub fn new<S: AsRef<str>>(names: &[S]) -> Self {
        let names = names
            .iter()
            .map(|name| {
                let name = name.as_ref();
                let words: Vec<PreparedWord> = name_words(name)
                    .into_iter()
                    .map(|word| PreparedWord { grams: trigrams(&word.chars), word })
                    .collect();
                let compact = words.iter().map(|w| w.word.text.as_str()).collect();
                PreparedName { words, folded: fold(name), compact }
            })
            .collect();
        Self { names }
    }

    pub fn len(&self) -> usize {
        self.names.len()
    }

    pub fn is_empty(&self) -> bool {
        self.names.is_empty()
    }

    /// The names matching `query`, best first, at most `limit`. Equal scores
    /// keep the original order. A blank query matches every name.
    pub fn search(&self, query: &str, limit: usize) -> Vec<NameMatch> {
        let query_words: Vec<PreparedWord> =
            name_words(query).into_iter().map(|word| PreparedWord { grams: trigrams(&word.chars), word }).collect();
        let mut matches: Vec<NameMatch> = Vec::new();
        if query_words.is_empty() {
            let folded = fold(query.trim());
            for (i, name) in self.names.iter().enumerate() {
                if folded.is_empty() || name.folded.contains(&folded) {
                    matches.push(NameMatch { index: i as u32, score: 0.0 });
                }
            }
        } else {
            let query_compact: String = query_words.iter().map(|w| w.word.text.as_str()).collect();
            let mut scratch = Scratch::default();
            for (i, name) in self.names.iter().enumerate() {
                if let Some(score) = score_name(name, &query_words, &query_compact, &mut scratch) {
                    matches.push(NameMatch { index: i as u32, score });
                }
            }
        }
        // Stable: equal scores keep the caller's order (newest first, say).
        matches.sort_by(|a, b| b.score.total_cmp(&a.score));
        matches.truncate(limit);
        matches
    }
}

fn score_name(name: &PreparedName, query: &[PreparedWord], query_compact: &str, scratch: &mut Scratch) -> Option<f32> {
    let mut total = 0.0;
    for q in query {
        let mut best = 0.0f32;
        for (position, w) in name.words.iter().enumerate() {
            let score = score_word(q, w, scratch);
            // Earlier words weigh a little more: "report" ranks "Report 2024" above "Q3 report".
            let score = if score > 0.0 { score - 0.002 * position.min(10) as f32 } else { 0.0 };
            best = best.max(score);
            if best >= EXACT {
                break;
            }
        }
        if best <= 0.0 && name.compact.contains(q.word.text.as_str()) {
            // Words run together in the name: "annualreport" in "AnnualReport", "repo" in "myreports".
            best = COMPACT_PART;
        }
        if best <= 0.0 {
            return None;
        }
        total += best;
    }
    let mut score = total / query.len() as f32;
    if name.compact.starts_with(query_compact) {
        score += 0.1;
    } else if name.compact.contains(query_compact) {
        score += 0.05;
    }
    // Among equals, shorter names are closer to what was typed.
    score -= 0.0005 * (name.compact.len().min(200) as f32);
    Some(score)
}

fn score_word(q: &PreparedWord, w: &PreparedWord, scratch: &mut Scratch) -> f32 {
    let (qt, wt) = (&q.word.text, &w.word.text);
    if wt.starts_with(qt.as_str()) {
        // Exact is 1.0; a prefix scores more the more of the word it covers.
        return 0.8 + 0.2 * (q.word.chars.len() as f32 / w.word.chars.len() as f32);
    }
    let qlen = q.word.chars.len();
    if qlen >= 2 && wt.contains(qt.as_str()) {
        return PART;
    }
    if qlen < 4 || q.word.has_digit || w.word.has_digit {
        return 0.0;
    }
    let max_typos = if qlen >= 8 { 2 } else { 1 };
    if let Some(d) = bounded_osa(&q.word.chars, &w.word.chars, max_typos, scratch) {
        return if d <= 1 { ONE_TYPO } else { TWO_TYPOS };
    }
    // A typo in a word still being typed: "invl" ... "invoi" against "invoice".
    if qlen >= 5 && w.word.chars.len() > qlen {
        let prefix = &w.word.chars[..qlen];
        if bounded_osa(&q.word.chars, prefix, 1, scratch).is_some() {
            return TYPO_PREFIX;
        }
    }
    if qlen >= 6 {
        let sim = dice(&q.grams, &w.grams);
        if sim >= TRIGRAM_MIN {
            return TRIGRAM_WEIGHT * sim;
        }
    }
    0.0
}

#[derive(Default)]
struct Scratch {
    rows: [Vec<usize>; 3],
}

/// Optimal string alignment distance (Damerau-Levenshtein with adjacent
/// transpositions), or `None` once it must exceed `max`. Rows are reused.
fn bounded_osa(a: &[char], b: &[char], max: usize, scratch: &mut Scratch) -> Option<usize> {
    if a.len().abs_diff(b.len()) > max {
        return None;
    }
    let width = b.len() + 1;
    let [prev2, prev, cur] = &mut scratch.rows;
    for row in [&mut *prev2, &mut *prev, &mut *cur] {
        row.clear();
        row.resize(width, 0);
    }
    for (j, cell) in prev.iter_mut().enumerate() {
        *cell = j;
    }
    for i in 1..=a.len() {
        cur[0] = i;
        let mut row_min = cur[0];
        for j in 1..=b.len() {
            let cost = usize::from(a[i - 1] != b[j - 1]);
            let mut value = (prev[j] + 1).min(cur[j - 1] + 1).min(prev[j - 1] + cost);
            if i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1] {
                value = value.min(prev2[j - 2] + 1);
            }
            cur[j] = value;
            row_min = row_min.min(value);
        }
        if row_min > max {
            return None;
        }
        std::mem::swap(prev2, prev);
        std::mem::swap(prev, cur);
    }
    let d = prev[b.len()];
    (d <= max).then_some(d)
}

/// Sorted, de-duplicated trigrams of a word padded with a boundary mark.
fn trigrams(chars: &[char]) -> Vec<u64> {
    if chars.len() < 2 {
        return Vec::new();
    }
    let pad = '\u{1}';
    let padded: Vec<char> = std::iter::once(pad).chain(chars.iter().copied()).chain(std::iter::once(pad)).collect();
    let mut grams: Vec<u64> =
        padded.windows(3).map(|w| (u64::from(w[0]) << 42) | (u64::from(w[1]) << 21) | u64::from(w[2])).collect();
    grams.sort_unstable();
    grams.dedup();
    grams
}

/// Dice similarity of two sorted trigram sets.
fn dice(a: &[u64], b: &[u64]) -> f32 {
    if a.is_empty() || b.is_empty() {
        return 0.0;
    }
    let (mut i, mut j, mut shared) = (0, 0, 0usize);
    while i < a.len() && j < b.len() {
        match a[i].cmp(&b[j]) {
            std::cmp::Ordering::Less => i += 1,
            std::cmp::Ordering::Greater => j += 1,
            std::cmp::Ordering::Equal => {
                shared += 1;
                i += 1;
                j += 1;
            }
        }
    }
    2.0 * shared as f32 / (a.len() + b.len()) as f32
}

#[cfg(test)]
mod tests {
    use super::*;

    fn found(names: &[&str], query: &str) -> Vec<String> {
        let index = NameIndex::new(names);
        index.search(query, 100).into_iter().map(|m| names[m.index as usize].to_owned()).collect()
    }

    fn osa(a: &str, b: &str, max: usize) -> Option<usize> {
        let (a, b): (Vec<char>, Vec<char>) = (a.chars().collect(), b.chars().collect());
        bounded_osa(&a, &b, max, &mut Scratch::default())
    }

    #[test]
    fn distance_counts_transpositions_once_and_stops_early() {
        assert_eq!(osa("report", "report", 2), Some(0));
        assert_eq!(osa("reprot", "report", 2), Some(1));
        assert_eq!(osa("invoce", "invoice", 1), Some(1));
        assert_eq!(osa("kitten", "sitting", 2), None);
        assert_eq!(osa("kitten", "sitting", 3), Some(3));
        assert_eq!(osa("a", "abcdef", 2), None);
        assert_eq!(osa("", "ab", 2), Some(2));
    }

    #[test]
    fn finds_names_despite_typos() {
        let names = ["Invoice_2025.pdf", "Notes.txt", "Holiday photos.zip"];
        assert_eq!(found(&names, "invoce"), vec!["Invoice_2025.pdf"]);
        assert_eq!(found(&names, "INVOICE"), vec!["Invoice_2025.pdf"]);
        assert_eq!(found(&names, "holdiay"), vec!["Holiday photos.zip"]);
        assert!(found(&names, "zzz").is_empty());
    }

    #[test]
    fn exact_and_prefix_rank_above_fuzzy() {
        let names = ["Reprot draft.docx", "Report.docx", "Quarterly report.pdf", "Reporting guide.pdf"];
        assert_eq!(
            found(&names, "report"),
            vec!["Report.docx", "Quarterly report.pdf", "Reporting guide.pdf", "Reprot draft.docx"]
        );
    }

    #[test]
    fn every_query_word_must_match() {
        let names = ["Annual_Report_2024.pdf", "Annual budget.xlsx", "Report card.pdf"];
        assert_eq!(found(&names, "annual report"), vec!["Annual_Report_2024.pdf"]);
        assert_eq!(found(&names, "report annual"), vec!["Annual_Report_2024.pdf"]);
    }

    #[test]
    fn camel_case_separators_and_accents() {
        let names = ["AnnualReport.pdf", "my-cv.docx", "Résumé.odt", "meeting_notes.md"];
        assert_eq!(found(&names, "annual report"), vec!["AnnualReport.pdf"]);
        assert_eq!(found(&names, "annualreport"), vec!["AnnualReport.pdf"]);
        assert_eq!(found(&names, "resume"), vec!["Résumé.odt"]);
        assert_eq!(found(&names, "meeting notes"), vec!["meeting_notes.md"]);
        assert_eq!(found(&names, "meeting_notes.md"), vec!["meeting_notes.md"]);
    }

    #[test]
    fn numbers_are_never_fuzzy() {
        let names = ["Invoice 2024.pdf", "Invoice 2025.pdf"];
        assert_eq!(found(&names, "2025"), vec!["Invoice 2025.pdf"]);
        assert_eq!(found(&names, "invoice 2025"), vec!["Invoice 2025.pdf"]);
    }

    #[test]
    fn short_queries_match_parts_but_not_fuzzily() {
        let names = ["cat.txt", "car.txt", "scatter.csv"];
        assert_eq!(found(&names, "cat"), vec!["cat.txt", "scatter.csv"]);
        assert_eq!(found(&names, "c"), vec!["cat.txt", "car.txt", "scatter.csv"]);
    }

    #[test]
    fn blank_and_punctuation_queries() {
        let names = ["a_b.txt", "c.txt"];
        assert_eq!(found(&names, "   "), vec!["a_b.txt", "c.txt"]);
        assert_eq!(found(&names, "_"), vec!["a_b.txt"]);
    }

    #[test]
    fn equal_scores_keep_the_given_order_and_limit_applies() {
        let names = ["b report.pdf", "a report.pdf", "c report.pdf"];
        let index = NameIndex::new(&names);
        let hits: Vec<u32> = index.search("report", 2).iter().map(|m| m.index).collect();
        assert_eq!(hits, vec![0, 1]);
    }

    #[test]
    fn trigrams_catch_heavier_misspellings_of_long_words() {
        let names = ["Acknowledgement letter.docx", "Agenda.docx"];
        assert_eq!(found(&names, "acknowlegdment"), vec!["Acknowledgement letter.docx"]);
    }

    #[test]
    fn thousands_of_names_search_quickly() {
        let names: Vec<String> = (0..5000).map(|i| format!("Document_{i}_Quarterly-Summary v{}.pdf", i % 7)).collect();
        let index = NameIndex::new(&names);
        let start = std::time::Instant::now();
        for query in ["quartrly", "summary 42", "doc", "zzzzzz"] {
            index.search(query, 200);
        }
        // Generous for debug builds on slow CI; release is far faster.
        assert!(start.elapsed().as_millis() < 2000, "{:?}", start.elapsed());
    }
}
