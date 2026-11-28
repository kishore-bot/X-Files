//! "At a glance": what a document says, worked out on the phone with small
//! classical algorithms. No models and no network; std plus the search
//! crate's word splitting.
//!
//! - [`textrank`]: the 3–5 most central sentences, quoted as written.
//! - [`keywords`]: YAKE-style statistical keywords.
//! - [`items`]: dates, amounts, phone numbers, emails, links and reference
//!   numbers that can be copied or handed to another app.
//! - [`language`]: the language, from its script and its most common words.
//!
//! The document itself is never changed; this only reads its plain text.

#![forbid(unsafe_code)]

pub mod items;
pub mod keywords;
pub mod language;
pub mod sentences;
pub mod stopwords;
pub mod textrank;

use document_search::for_each_token;

pub use items::{Item, ItemKind};
pub use language::Language;

/// Longer text is cut here (at a character boundary): enough for any summary,
/// bounded for a 2 GB phone.
pub const MAX_TEXT_BYTES: usize = 600_000;

/// Words per minute for reading time; ideographs count per character.
const WORDS_PER_MINUTE: u32 = 230;
const IDEOGRAPHS_PER_MINUTE: u32 = 500;

/// Everything the "At a glance" sheet shows.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Glance {
    /// In document order. Empty when the text is too short to summarise.
    pub key_sentences: Vec<String>,
    /// Strongest first, as written in the document.
    pub keywords: Vec<String>,
    /// In document order.
    pub items: Vec<Item>,
    pub words: u32,
    /// At least 1 when there are any words.
    pub reading_minutes: u32,
    pub language: Option<Language>,
}

/// Reads `text` (plain, whitespace-normalised, blocks separated by newlines).
pub fn glance(text: &str) -> Glance {
    let text = clip(text, MAX_TEXT_BYTES);
    let language = language::detect(text);
    let stop = stopwords::StopWords::for_language(language.map(|l| l.code));
    let sentences = sentences::split(text);
    let (words, ideographs) = count_words(text);
    let minutes = if words == 0 { 0 } else { (words - ideographs).div_ceil(WORDS_PER_MINUTE) + ideographs.div_ceil(IDEOGRAPHS_PER_MINUTE) };
    Glance {
        key_sentences: textrank::key_sentences(text, &sentences, &stop, textrank::MAX_KEY_SENTENCES),
        keywords: keywords::extract(text, &sentences, &stop, keywords::MAX_KEYWORDS),
        items: items::find(text),
        words,
        reading_minutes: minutes.max(u32::from(words > 0)),
        language,
    }
}

fn clip(text: &str, max: usize) -> &str {
    if text.len() <= max {
        return text;
    }
    let mut end = max;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    &text[..end]
}

/// Words, and how many of them are single ideographs or kana.
fn count_words(text: &str) -> (u32, u32) {
    let mut words = 0u32;
    let mut ideographs = 0u32;
    for_each_token(text, |start, end, _| {
        words = words.saturating_add(1);
        if end - start >= 3 && text[start..end].chars().count() == 1 && text[start..].chars().next().is_some_and(is_ideograph) {
            ideographs = ideographs.saturating_add(1);
        }
    });
    (words, ideographs)
}

fn is_ideograph(c: char) -> bool {
    matches!(c, '\u{3040}'..='\u{30FF}' | '\u{3400}'..='\u{4DBF}' | '\u{4E00}'..='\u{9FFF}' | '\u{F900}'..='\u{FAFF}')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_text_gives_nothing() {
        let g = glance("");
        assert_eq!(g, Glance::default());
    }

    #[test]
    fn reading_time_rounds_up() {
        let text = "word ".repeat(231);
        assert_eq!(glance(&text).reading_minutes, 2);
        assert_eq!(glance("Just a few words.").reading_minutes, 1);
    }

    #[test]
    fn clip_keeps_char_boundaries() {
        assert_eq!(clip("aé", 2), "a");
    }

    #[test]
    fn glance_handles_multibyte_text_at_reported_panic_offset() {
        // The old ordinal probe started at byte 1553, inside this three-byte symbol.
        let text = format!("{}₹ March 14, 2025. Paid\u{2003}Rs. 500 today.", " ".repeat(1552));
        let g = glance(&text);
        assert!(g.items.iter().any(|i| i.kind == ItemKind::Date && i.value == "2025-03-14"));
        assert!(g.items.iter().any(|i| i.kind == ItemKind::Amount && i.value == "500"));
    }
}
