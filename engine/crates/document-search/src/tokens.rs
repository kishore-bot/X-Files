//! Splitting text and file names into folded words.

use std::borrow::Cow;

use crate::fold::{is_combining_mark, push_folded};

/// Longer runs are not words (hashes, base64, long numbers) and are skipped.
pub const MAX_TERM_BYTES: usize = 40;

/// Ideographs and kana: written without spaces, so each is a word of its own.
fn is_unspaced(c: char) -> bool {
    matches!(c,
        '\u{3040}'..='\u{30FF}'   // Hiragana, Katakana
        | '\u{3400}'..='\u{4DBF}' // CJK Extension A
        | '\u{4E00}'..='\u{9FFF}' // CJK Unified Ideographs
        | '\u{F900}'..='\u{FAFF}' // CJK Compatibility Ideographs
        | '\u{20000}'..='\u{2FFFF}')
}

fn is_word_char(c: char) -> bool {
    c.is_alphanumeric()
}

/// Calls `f(start, end, folded)` for every word of `text`, in order; `start`
/// and `end` are byte offsets into `text`.
pub fn for_each_token(text: &str, mut f: impl FnMut(usize, usize, &str)) {
    let mut buf = String::new();
    let mut start = 0;
    let mut end = 0;
    let mut in_word = false;
    let mut too_long = false;
    let mut flush = |buf: &mut String, start: usize, end: usize, too_long: bool| {
        if !buf.is_empty() && !too_long {
            f(start, end, buf);
        }
        buf.clear();
    };
    for (i, c) in text.char_indices() {
        if is_unspaced(c) {
            if in_word {
                flush(&mut buf, start, end, too_long);
                in_word = false;
            }
            push_folded(c, &mut buf);
            flush(&mut buf, i, i + c.len_utf8(), false);
        } else if is_word_char(c) || (in_word && is_combining_mark(c)) {
            if !in_word {
                in_word = true;
                too_long = false;
                start = i;
            }
            if !too_long {
                push_folded(c, &mut buf);
                if buf.len() > MAX_TERM_BYTES {
                    too_long = true;
                    buf.clear();
                }
            }
            end = i + c.len_utf8();
        } else if in_word {
            flush(&mut buf, start, end, too_long);
            in_word = false;
        }
    }
    if in_word {
        flush(&mut buf, start, end, too_long);
    }
}

/// The folded words of `text`, in order.
pub fn tokens(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    for_each_token(text, |_, _, t| out.push(t.to_owned()));
    out
}

/// Light English stemming: plurals only ("invoices" → "invoice", "cities" →
/// "city"), so it never merges words that differ in meaning.
pub fn stem(term: &str) -> Cow<'_, str> {
    let b = term.as_bytes();
    if b.len() <= 3 || !b.iter().all(u8::is_ascii_lowercase) {
        return Cow::Borrowed(term);
    }
    if b.len() > 4 && term.ends_with("ies") && !term.ends_with("eies") && !term.ends_with("aies") {
        return Cow::Owned(format!("{}y", &term[..term.len() - 3]));
    }
    if term.ends_with("sses") {
        return Cow::Borrowed(&term[..term.len() - 2]);
    }
    if term.ends_with('s') && !term.ends_with("ss") && !term.ends_with("us") && !term.ends_with("is") {
        return Cow::Borrowed(&term[..term.len() - 1]);
    }
    Cow::Borrowed(term)
}

/// One word of a file name, folded.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NameWord {
    pub text: String,
    pub chars: Vec<char>,
    pub has_digit: bool,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Class {
    Lower,
    Upper,
    Digit,
    Other,
}

fn class(c: char) -> Class {
    if c.is_numeric() {
        Class::Digit
    } else if c.is_uppercase() {
        Class::Upper
    } else if c.is_alphabetic() || is_combining_mark(c) {
        Class::Lower
    } else {
        Class::Other
    }
}

/// The words of a file name: split at separators (`_ - . space` and any other
/// punctuation), between letters and digits, and at camelCase humps
/// ("AnnualReport2025" → annual, report, 2025; "PDFFile" → pdf, file).
pub fn name_words(name: &str) -> Vec<NameWord> {
    let chars: Vec<char> = name.chars().collect();
    let mut words = Vec::new();
    let mut current = String::new();
    let push_word = |current: &mut String, words: &mut Vec<NameWord>| {
        if !current.is_empty() {
            let chars: Vec<char> = current.chars().collect();
            let has_digit = chars.iter().any(|c| c.is_numeric());
            words.push(NameWord { text: std::mem::take(current), chars, has_digit });
        }
    };
    for (i, &c) in chars.iter().enumerate() {
        let here = class(c);
        if is_unspaced(c) {
            push_word(&mut current, &mut words);
            push_folded(c, &mut current);
            push_word(&mut current, &mut words);
            continue;
        }
        if here == Class::Other {
            push_word(&mut current, &mut words);
            continue;
        }
        if let Some(&prev) = i.checked_sub(1).and_then(|p| chars.get(p)) {
            let before = class(prev);
            let next_lower = chars.get(i + 1).is_some_and(|&n| class(n) == Class::Lower);
            let split = match (before, here) {
                (Class::Lower, Class::Upper) => true,
                (Class::Upper, Class::Upper) => next_lower,
                (Class::Digit, Class::Lower | Class::Upper) | (Class::Lower | Class::Upper, Class::Digit) => true,
                _ => false,
            };
            if split {
                push_word(&mut current, &mut words);
            }
        }
        push_folded(c, &mut current);
    }
    push_word(&mut current, &mut words);
    words
}

#[cfg(test)]
mod tests {
    use super::*;

    fn words(name: &str) -> Vec<String> {
        name_words(name).into_iter().map(|w| w.text).collect()
    }

    #[test]
    fn tokenises_unicode_text_with_offsets() {
        let text = "Hello, wörld! Ünïcode_42 東京";
        let mut seen = Vec::new();
        for_each_token(text, |s, e, t| seen.push((t.to_owned(), &text[s..e])));
        assert_eq!(
            seen,
            vec![
                ("hello".into(), "Hello"),
                ("world".into(), "wörld"),
                ("unicode".into(), "Ünïcode"),
                ("42".into(), "42"),
                ("東".into(), "東"),
                ("京".into(), "京"),
            ]
        );
    }

    #[test]
    fn skips_overlong_runs() {
        let long = "a".repeat(100);
        assert_eq!(tokens(&format!("x {long} y")), vec!["x", "y"]);
    }

    #[test]
    fn stems_plurals_only() {
        assert_eq!(stem("invoices"), "invoice");
        assert_eq!(stem("cities"), "city");
        assert_eq!(stem("classes"), "class");
        assert_eq!(stem("class"), "class");
        assert_eq!(stem("status"), "status");
        assert_eq!(stem("analysis"), "analysis");
        assert_eq!(stem("bus"), "bus");
        assert_eq!(stem("running"), "running");
        assert_eq!(stem("été"), "été");
    }

    #[test]
    fn splits_names_at_separators_humps_and_digits() {
        assert_eq!(words("Invoice_2025.pdf"), vec!["invoice", "2025", "pdf"]);
        assert_eq!(words("AnnualReport-final v2.docx"), vec!["annual", "report", "final", "v", "2", "docx"]);
        assert_eq!(words("PDFFile.txt"), vec!["pdf", "file", "txt"]);
        assert_eq!(words("Résumé (Copy).odt"), vec!["resume", "copy", "odt"]);
        assert_eq!(words("会議メモ.txt"), vec!["会", "議", "メ", "モ", "txt"]);
    }
}
