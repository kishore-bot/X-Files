//! Pure text operations over sections: page selection, character windows, search and glance.
//! Nothing here touches the file system, so each function is unit tested directly.

use document_glance::glance;
use serde_json::{Value, json};

use crate::document::Section;
use crate::error::{Result, ToolError};

/// Text matches are counted up to this many before the count is reported as truncated.
const MAX_COUNTED_MATCHES: usize = 10_000;

/// The sections `from..=to` (1-based, inclusive) joined for reading. A multi-section document
/// gets a header line before each section so the reader can see where a slide or sheet starts.
pub fn render(sections: &[Section], word: &str, from: Option<u32>, to: Option<u32>) -> Result<String> {
    let count = sections.len();
    if count == 0 {
        return Ok(String::new());
    }
    let first = from.map_or(1, |page| page as usize);
    let last = to.map_or(count, |page| page as usize);
    if first < 1 || last > count || first > last {
        return Err(ToolError::invalid(format!(
            "pages must be between 1 and {count}, and from_page must not be after to_page"
        )));
    }

    let mut out = String::new();
    for (offset, section) in sections[first - 1..last].iter().enumerate() {
        let number = first + offset;
        if !out.is_empty() {
            out.push_str("\n\n");
        }
        if count > 1 {
            match &section.title {
                Some(title) => out.push_str(&format!("=== {word} {number}: {title} ===\n")),
                None => out.push_str(&format!("=== {word} {number} ===\n")),
            }
        }
        out.push_str(&section.text);
    }
    Ok(out)
}

/// A character window of a text. Positions count Unicode scalar values, not bytes.
#[derive(Debug, PartialEq, Eq)]
pub struct Window {
    pub text: String,
    pub start_char: usize,
    pub returned_chars: usize,
    pub total_chars: usize,
    pub next_start_char: Option<usize>,
}

pub fn window(text: &str, start: usize, max_chars: usize) -> Window {
    let total_chars = text.chars().count();
    let start_char = start.min(total_chars);
    let byte_start = text.char_indices().nth(start_char).map_or(text.len(), |(index, _)| index);
    let rest = &text[byte_start..];
    let byte_end = rest.char_indices().nth(max_chars).map_or(rest.len(), |(index, _)| index);
    let body = rest[..byte_end].to_owned();
    let returned_chars = body.chars().count();
    let next = start_char + returned_chars;
    Window {
        text: body,
        start_char,
        returned_chars,
        total_chars,
        next_start_char: (next < total_chars).then_some(next),
    }
}

#[derive(Debug, PartialEq, Eq)]
pub struct Hit {
    /// 1-based section number (slide, sheet or section).
    pub section: usize,
    pub title: Option<String>,
    /// Character offset of the match inside its section.
    pub char_offset: usize,
    pub snippet: String,
}

#[derive(Debug, PartialEq, Eq)]
pub struct SearchOutcome {
    pub total_matches: usize,
    pub truncated: bool,
    pub hits: Vec<Hit>,
}

/// Case-insensitive search. Each match is one character for one character, so offsets stay exact.
pub fn search(sections: &[Section], query: &str, max_hits: usize, context: usize) -> SearchOutcome {
    let needle: Vec<char> = query.chars().map(fold).collect();
    let mut total = 0usize;
    let mut hits = Vec::new();
    let mut truncated = false;
    if needle.is_empty() {
        return SearchOutcome { total_matches: 0, truncated, hits };
    }

    'sections: for (index, section) in sections.iter().enumerate() {
        let original: Vec<char> = section.text.chars().collect();
        let folded: Vec<char> = original.iter().map(|c| fold(*c)).collect();
        let mut at = 0usize;
        while at + needle.len() <= folded.len() {
            if folded[at] == needle[0] && folded[at..at + needle.len()] == needle[..] {
                total += 1;
                if hits.len() < max_hits {
                    hits.push(Hit {
                        section: index + 1,
                        title: section.title.clone(),
                        char_offset: at,
                        snippet: snippet(&original, at, needle.len(), context),
                    });
                }
                if total >= MAX_COUNTED_MATCHES {
                    truncated = true;
                    break 'sections;
                }
                at += needle.len();
            } else {
                at += 1;
            }
        }
    }
    SearchOutcome { total_matches: total, truncated, hits }
}

fn fold(c: char) -> char {
    c.to_lowercase().next().unwrap_or(c)
}

fn snippet(chars: &[char], at: usize, len: usize, context: usize) -> String {
    let start = at.saturating_sub(context);
    let end = (at + len + context).min(chars.len());
    let mut out = String::new();
    if start > 0 {
        out.push_str("...");
    }
    out.extend(chars[start..end].iter().map(|c| if *c == '\n' { ' ' } else { *c }));
    if end < chars.len() {
        out.push_str("...");
    }
    out
}

/// The "at a glance" summary: key sentences, keywords, things to act on, and reading time.
pub fn glance_summary(sections: &[Section]) -> Value {
    let joined = sections
        .iter()
        .map(|section| section.text.as_str())
        .filter(|text| !text.is_empty())
        .collect::<Vec<_>>()
        .join("\n\n");
    let summary = glance(&joined);
    let items: Vec<Value> = summary
        .items
        .iter()
        .map(|item| {
            json!({
                "kind": format!("{:?}", item.kind).to_lowercase(),
                "text": item.text,
                "value": item.value,
                "label": item.label,
                "offset": item.start,
            })
        })
        .collect();
    json!({
        "words": summary.words,
        "reading_minutes": summary.reading_minutes,
        "language": summary.language.map(|language| json!({ "code": language.code, "name": language.name })),
        "key_sentences": summary.key_sentences,
        "keywords": summary.keywords,
        "items": items,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sections() -> Vec<Section> {
        vec![
            Section { title: Some("Intro".into()), text: "Hello World. Invoice total 42 EUR.".into() },
            Section { title: None, text: "Second page: hello again, HELLO!".into() },
        ]
    }

    #[test]
    fn render_adds_headers_only_for_multi_section_documents() {
        let out = render(&sections(), "Slide", None, None).unwrap();
        assert!(out.starts_with("=== Slide 1: Intro ===\nHello World"));
        assert!(out.contains("=== Slide 2 ===\nSecond page"));
        let single = vec![Section { title: None, text: "only".into() }];
        assert_eq!(render(&single, "Section", None, None).unwrap(), "only");
    }

    #[test]
    fn render_selects_pages_and_rejects_bad_ranges() {
        let only_second = render(&sections(), "Slide", Some(2), Some(2)).unwrap();
        assert!(!only_second.contains("Hello World"));
        assert!(render(&sections(), "Slide", Some(0), None).is_err());
        assert!(render(&sections(), "Slide", Some(2), Some(9)).is_err());
        assert!(render(&sections(), "Slide", Some(2), Some(1)).is_err());
        assert_eq!(render(&[], "Slide", Some(1), None).unwrap(), "");
    }

    #[test]
    fn window_counts_characters_not_bytes() {
        let text = "héllo wörld";
        let first = window(text, 0, 4);
        assert_eq!(first.text, "héll");
        assert_eq!(first.total_chars, 11);
        assert_eq!(first.next_start_char, Some(4));
        let rest = window(text, 4, 100);
        assert_eq!(rest.text, "o wörld");
        assert_eq!(rest.next_start_char, None);
    }

    #[test]
    fn window_clamps_start_past_the_end() {
        let w = window("abc", 99, 10);
        assert_eq!(w.start_char, 3);
        assert_eq!(w.text, "");
        assert_eq!(w.next_start_char, None);
    }

    #[test]
    fn search_is_case_insensitive_and_reports_sections() {
        let outcome = search(&sections(), "hello", 10, 5);
        assert_eq!(outcome.total_matches, 3);
        assert!(!outcome.truncated);
        assert_eq!(outcome.hits[0].section, 1);
        assert_eq!(outcome.hits[0].title.as_deref(), Some("Intro"));
        assert_eq!(outcome.hits[0].char_offset, 0);
        assert_eq!(outcome.hits[1].section, 2);
        assert!(outcome.hits[0].snippet.contains("Hello"));
    }

    #[test]
    fn search_caps_returned_hits_but_keeps_counting() {
        let text = "ab ".repeat(50);
        let one = vec![Section { title: None, text }];
        let outcome = search(&one, "ab", 5, 2);
        assert_eq!(outcome.total_matches, 50);
        assert_eq!(outcome.hits.len(), 5);
    }

    #[test]
    fn search_with_no_matches_or_empty_query_is_empty() {
        assert_eq!(search(&sections(), "zebra", 10, 5).total_matches, 0);
        assert_eq!(search(&sections(), "", 10, 5).total_matches, 0);
    }

    #[test]
    fn glance_finds_amounts_and_emails() {
        let text = vec![Section {
            title: None,
            text: "Please pay 1234.50 EUR by 2026-03-01. Contact billing@example.com for questions.".into(),
        }];
        let value = glance_summary(&text);
        let items = value["items"].as_array().unwrap();
        assert!(items.iter().any(|item| item["kind"] == "amount"));
        assert!(items.iter().any(|item| item["kind"] == "email" && item["value"] == "billing@example.com"));
        assert!(value["words"].as_u64().unwrap() > 0);
    }
}
