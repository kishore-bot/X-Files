//! The passage of a document that best shows why it matched a query.

use crate::tokens::{for_each_token, stem};

/// A query word as the index compares it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QueryTerm {
    /// Folded, unstemmed: what a prefix must start with.
    pub folded: String,
    /// Folded and stemmed: what a whole word must equal.
    pub stemmed: String,
    /// The last word while it is still being typed also matches longer words.
    pub prefix: bool,
}

impl QueryTerm {
    pub fn matches(&self, folded_token: &str) -> bool {
        (self.prefix && folded_token.starts_with(&self.folded)) || stem(folded_token) == self.stemmed
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Snippet {
    pub text: String,
    /// Matched words as `[start, end)` in UTF-16 units of `text`.
    pub ranges: Vec<(u32, u32)>,
    /// Byte offset in the document of the passage's first match.
    pub anchor: usize,
}

const ELLIPSIS: char = '…';
/// Matches farther apart than this don't count as one passage.
const WINDOW_BYTES: usize = 120;
/// Enough to find a good passage in a long document without unbounded work.
const MAX_HITS: usize = 20_000;

/// The window of `text` with the most distinct query terms (then the most
/// matches, then the earliest), widened with context to about `max_chars`.
/// Without any match, the start of the text.
pub fn best_snippet(text: &str, terms: &[QueryTerm], max_chars: usize) -> Snippet {
    let terms = &terms[..terms.len().min(32)];
    let mut hits: Vec<(usize, usize, usize)> = Vec::new();
    for_each_token(text, |start, end, token| {
        if hits.len() < MAX_HITS {
            if let Some(t) = terms.iter().position(|term| term.matches(token)) {
                hits.push((start, end, t));
            }
        }
    });
    if hits.is_empty() {
        let mut end = advance_chars(text, 0, max_chars);
        if end < text.len() && !text[end..].starts_with(char::is_whitespace) {
            if let Some(space) = text[..end].rfind(char::is_whitespace) {
                end = space;
            }
        }
        return build(text, 0, end, &[], 0);
    }

    let mut counts = [0u32; 32];
    let mut distinct = 0;
    let mut best = (0usize, 0usize, 0usize, 0usize); // distinct, count, left, right
    let mut left = 0;
    for right in 0..hits.len() {
        let t = hits[right].2;
        if counts[t] == 0 {
            distinct += 1;
        }
        counts[t] += 1;
        // `left < right`: a single hit longer than the window must stay in it, not push `left` past the end.
        while left < right && hits[right].1 - hits[left].0 > WINDOW_BYTES {
            let lt = hits[left].2;
            counts[lt] -= 1;
            if counts[lt] == 0 {
                distinct -= 1;
            }
            left += 1;
        }
        let count = right - left + 1;
        if (distinct, count) > (best.0, best.1) {
            best = (distinct, count, left, right);
        }
    }
    let (_, _, left, right) = best;
    let (first, last) = (hits[left].0, hits[right].1);

    // Some context before the first match, the rest after the last.
    let span = text[first..last].chars().count();
    let spare = max_chars.saturating_sub(span);
    let mut start = retreat_chars(text, first, spare / 3);
    let mut end = advance_chars(text, last, spare - spare / 3);
    // Start and end at whole words.
    if start > 0 && !text[..start].ends_with(char::is_whitespace) {
        // Whitespace can be wider than a byte (no-break space), so step over the whole character.
        if let Some((space, c)) = text[start..first].char_indices().find(|(_, c)| c.is_whitespace()) {
            start += space + c.len_utf8();
        }
    }
    if end < text.len() && !text[end..].starts_with(char::is_whitespace) {
        if let Some(space) = text[last..end].rfind(char::is_whitespace) {
            end = last + space;
        }
    }
    let inside: Vec<(usize, usize)> = hits.iter().filter(|h| h.0 >= start && h.1 <= end).map(|h| (h.0, h.1)).collect();
    build(text, start, end, &inside, first)
}

fn build(text: &str, start: usize, end: usize, hits: &[(usize, usize)], anchor: usize) -> Snippet {
    let mut out = String::with_capacity(end - start + 8);
    let mut units = 0u32;
    if start > 0 {
        out.push(ELLIPSIS);
        units += 1;
    }
    let mut ranges = Vec::with_capacity(hits.len());
    let mut next_hit = 0;
    let mut open: Option<u32> = None;
    let mut last_was_space = false;
    for (offset, c) in text[start..end].char_indices() {
        let at = start + offset;
        if open.is_none() && next_hit < hits.len() && at == hits[next_hit].0 {
            open = Some(units);
        }
        let len = c.len_utf8();
        let c = if c.is_whitespace() || c.is_control() { ' ' } else { c };
        if c == ' ' && last_was_space {
            continue;
        }
        last_was_space = c == ' ';
        out.push(c);
        units += c.len_utf16() as u32;
        if let Some(from) = open {
            if at + len == hits[next_hit].1 {
                ranges.push((from, units));
                open = None;
                next_hit += 1;
            }
        }
    }
    let trimmed = out.trim_end().len();
    out.truncate(trimmed);
    if end < text.len() {
        out.push(ELLIPSIS);
    }
    Snippet { text: out, ranges, anchor }
}

fn advance_chars(text: &str, from: usize, n: usize) -> usize {
    text[from..].char_indices().nth(n).map_or(text.len(), |(i, _)| from + i)
}

fn retreat_chars(text: &str, from: usize, n: usize) -> usize {
    if n == 0 {
        return from;
    }
    text[..from].char_indices().rev().nth(n - 1).map_or(0, |(i, _)| i)
}

/// Which page (from 0) holds byte `anchor`, given each page's starting byte.
pub fn page_of(page_starts: &[u32], anchor: usize) -> Option<u32> {
    if page_starts.is_empty() {
        return None;
    }
    let after = page_starts.partition_point(|&p| p as usize <= anchor);
    Some(after.saturating_sub(1) as u32)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn term(word: &str, prefix: bool) -> QueryTerm {
        QueryTerm { folded: word.into(), stemmed: stem(word).into_owned(), prefix }
    }

    fn highlighted(s: &Snippet) -> Vec<String> {
        let units: Vec<u16> = s.text.encode_utf16().collect();
        s.ranges.iter().map(|&(a, b)| String::from_utf16(&units[a as usize..b as usize]).unwrap()).collect()
    }

    #[test]
    fn prefers_the_passage_with_most_distinct_terms() {
        let filler = "lorem ipsum dolor sit amet ".repeat(20);
        let text =
            format!("Budget mentioned here. {filler} The budget for the Paris trip is final. {filler} Paris again.");
        let terms = [term("budget", false), term("paris", false)];
        let s = best_snippet(&text, &terms, 80);
        assert_eq!(highlighted(&s), vec!["budget", "Paris"]);
        assert!(s.text.starts_with('…') && s.text.ends_with('…'), "{}", s.text);
        assert!(s.text.contains("The budget for the Paris trip"), "{}", s.text);
        assert_eq!(&text[s.anchor..s.anchor + 6], "budget");
    }

    #[test]
    fn highlights_in_utf16_with_folding_stemming_and_prefixes() {
        let text = "Ünïcode 😀 Invoices — résumé\nnext line";
        let s = best_snippet(text, &[term("invoice", false), term("resume", false), term("nex", true)], 200);
        assert_eq!(highlighted(&s), vec!["Invoices", "résumé", "next"]);
        assert_eq!(s.text, "Ünïcode 😀 Invoices — résumé next line");
    }

    #[test]
    fn falls_back_to_the_start_without_matches() {
        let s = best_snippet("alpha beta gamma delta", &[term("zeta", false)], 10);
        assert_eq!(s.text, "alpha beta…");
        assert!(s.ranges.is_empty());
        assert_eq!(s.anchor, 0);
    }

    #[test]
    fn finds_the_page_of_a_match() {
        assert_eq!(page_of(&[], 5), None);
        assert_eq!(page_of(&[0, 100, 250], 0), Some(0));
        assert_eq!(page_of(&[0, 100, 250], 99), Some(0));
        assert_eq!(page_of(&[0, 100, 250], 100), Some(1));
        assert_eq!(page_of(&[0, 100, 250], 9999), Some(2));
    }
}
