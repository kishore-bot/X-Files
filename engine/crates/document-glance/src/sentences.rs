//! Splitting plain text into sentences.
//!
//! A newline always ends a sentence (the text separates blocks and pages with
//! one). Full stops end one only when followed by a space and not part of a
//! common abbreviation, an initial or a number, so "Dr. Rao paid Rs. 4.50."
//! stays whole.

/// A sentence as byte offsets into the text, trimmed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Sentence {
    pub start: usize,
    pub end: usize,
}

const ABBREVIATIONS: &[&str] = &[
    "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "eg", "e.g", "ie", "i.e", "no", "nos", "rs", "inc", "ltd", "co",
    "corp", "fig", "figs", "approx", "dept", "est", "vol", "pp", "ch", "sec", "art", "jan", "feb", "mar", "apr", "jun", "jul", "aug",
    "sep", "sept", "oct", "nov", "dec", "mt", "ft", "govt", "pvt", "ref", "tel", "ph", "op", "cf", "al", "viz",
];

fn is_terminal(c: char) -> bool {
    matches!(c, '.' | '!' | '?' | '…' | '。' | '！' | '？' | '।' | '॥' | '؟')
}

/// Terminals that end a sentence without a following space.
fn is_unspaced_terminal(c: char) -> bool {
    matches!(c, '。' | '！' | '？')
}

fn is_closer(c: char) -> bool {
    matches!(c, '"' | '\'' | '”' | '’' | ')' | ']' | '»')
}

/// The sentences of `text`, in order.
pub fn split(text: &str) -> Vec<Sentence> {
    let mut out = Vec::new();
    let mut start = 0usize;
    let chars: Vec<(usize, char)> = text.char_indices().collect();
    let mut i = 0;
    while i < chars.len() {
        let (at, c) = chars[i];
        if c == '\n' {
            push(text, start, at, &mut out);
            start = at + 1;
        } else if is_terminal(c) {
            // Take the whole run of terminals and closing quotes.
            let mut j = i + 1;
            while j < chars.len() && (is_terminal(chars[j].1) || is_closer(chars[j].1)) {
                j += 1;
            }
            let end = chars.get(j).map_or(text.len(), |&(b, _)| b);
            let next = chars.get(j).map(|&(_, n)| n);
            let spaced = next.is_none_or(char::is_whitespace);
            let ends = if is_unspaced_terminal(c) {
                true
            } else if !spaced {
                false
            } else if c == '.' && j == i + 1 {
                !is_abbreviation(text, at) && !next_starts_lowercase(&chars, j)
            } else {
                true
            };
            if ends {
                push(text, start, end, &mut out);
                start = end;
            }
            i = j;
            continue;
        }
        i += 1;
    }
    push(text, start, text.len(), &mut out);
    out
}

fn push(text: &str, start: usize, end: usize, out: &mut Vec<Sentence>) {
    let slice = &text[start..end];
    let trimmed_start = start + (slice.len() - slice.trim_start().len());
    let trimmed_end = start + slice.trim_end().len();
    if trimmed_end > trimmed_start {
        out.push(Sentence { start: trimmed_start, end: trimmed_end });
    }
}

/// Whether the word before the full stop at `dot` is an abbreviation, an initial or a number.
fn is_abbreviation(text: &str, dot: usize) -> bool {
    let before = &text[..dot];
    let word = before.rsplit(|c: char| c.is_whitespace() || c == '(').next().unwrap_or_default();
    if word.is_empty() {
        return false;
    }
    let mut letters = word.chars().filter(|c| c.is_alphabetic());
    let first = letters.next();
    if first.is_some() && letters.next().is_none() && word.chars().count() == 1 {
        return first.is_some_and(char::is_uppercase); // "J. K. Rowling"
    }
    let lower = word.to_lowercase();
    ABBREVIATIONS.contains(&lower.as_str())
}

fn next_starts_lowercase(chars: &[(usize, char)], from: usize) -> bool {
    chars[from..].iter().map(|&(_, c)| c).find(|c| !c.is_whitespace()).is_some_and(char::is_lowercase)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn texts(text: &str) -> Vec<&str> {
        split(text).into_iter().map(|s| &text[s.start..s.end]).collect()
    }

    #[test]
    fn splits_on_full_stops_and_newlines() {
        assert_eq!(texts("One here. Two there!\nThree"), vec!["One here.", "Two there!", "Three"]);
    }

    #[test]
    fn keeps_abbreviations_and_numbers() {
        assert_eq!(texts("Dr. Rao paid Rs. 4.50 today. Done."), vec!["Dr. Rao paid Rs. 4.50 today.", "Done."]);
        assert_eq!(texts("J. K. Rowling wrote it. Yes."), vec!["J. K. Rowling wrote it.", "Yes."]);
        assert_eq!(texts("See e.g. the table."), vec!["See e.g. the table."]);
    }

    #[test]
    fn abbreviations_after_unicode_whitespace_keep_character_boundaries() {
        for space in ['\u{a0}', '\u{2003}', '\u{202f}', '\u{3000}'] {
            let text = format!("Paid{space}Rs. 500 today. Done.");
            assert_eq!(texts(&text), vec![format!("Paid{space}Rs. 500 today."), "Done.".to_owned()]);
        }
    }

    #[test]
    fn closing_quotes_stay_with_the_sentence() {
        assert_eq!(texts("He said \"go.\" Then left."), vec!["He said \"go.\"", "Then left."]);
    }

    #[test]
    fn ideographic_full_stop() {
        assert_eq!(texts("今日は晴れ。明日は雨。"), vec!["今日は晴れ。", "明日は雨。"]);
    }
}
