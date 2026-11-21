//! Keywords in the style of YAKE (Campos et al., 2020): statistics of each
//! word within this one document (how often, how early, in how many
//! sentences, in capitals, next to how many different words) score it, and
//! phrases of up to three words combine their words' scores. Lower is better.
//! No corpus and no model, so it works on any single file offline.

use std::collections::{HashMap, HashSet};

use document_search::{for_each_token, stem};

use crate::sentences::Sentence;
use crate::stopwords::StopWords;

pub const MAX_KEYWORDS: usize = 8;
const MIN_WORD_CHARS: usize = 3;
const MAX_PHRASE_WORDS: usize = 3;
/// Too few words to say what a text is about.
const MIN_CONTENT_WORDS: usize = 20;
/// Longer texts need a phrase to appear twice before it counts.
const REPEAT_ABOVE_WORDS: usize = 400;
/// Bounded work on long books: the opening says what they are about.
const MAX_TOKENS: usize = 80_000;

/// Dates are listed under "In this file"; their words say nothing about the topic.
const CALENDAR_WORDS: &[&str] = &[
    "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
    "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec", "monday", "tuesday", "wednesday",
    "thursday", "friday", "saturday", "sunday", "today", "tomorrow", "yesterday",
];

/// "SRT" in "SRT-2025-88417": a piece of a code joined to digits, not a word.
fn part_of_code(text: &str, start: usize, end: usize) -> bool {
    let joined_after = text[end..].strip_prefix(['-', '/', '_']).is_some_and(|r| r.starts_with(|c: char| c.is_ascii_digit()));
    let joined_before = text[..start].strip_suffix(['-', '/', '_']).is_some_and(|l| l.ends_with(|c: char| c.is_ascii_digit()));
    joined_after || joined_before
}

struct Token {
    start: usize,
    end: usize,
    /// Term id of the stemmed, folded word.
    term: u32,
    sentence: u32,
    /// Punctuation or a new sentence separates it from the word before.
    breaks: bool,
    content: bool,
    upper: bool,
    acronym: bool,
}

#[derive(Default)]
struct Stat {
    tf: u32,
    upper: u32,
    acronym: u32,
    sentences: Vec<u32>,
    left: HashSet<u32>,
    left_total: u32,
    right: HashSet<u32>,
    right_total: u32,
}

#[derive(Default)]
struct Phrase {
    terms: Vec<u32>,
    tf: u32,
    surfaces: HashMap<String, u32>,
    first: usize,
}

/// Up to `max` keywords of `text`, strongest first, as written in it.
pub fn extract(text: &str, sentences: &[Sentence], stop: &StopWords, max: usize) -> Vec<String> {
    let mut ids: HashMap<String, u32> = HashMap::new();
    let tokens = tokenize(text, sentences, stop, &mut ids);
    let content_words = tokens.iter().filter(|t| t.content).count();
    if content_words < MIN_CONTENT_WORDS {
        return Vec::new();
    }
    let mut stats: HashMap<u32, Stat> = HashMap::new();
    for (i, t) in tokens.iter().enumerate() {
        if !t.content {
            continue;
        }
        let s = stats.entry(t.term).or_default();
        s.tf += 1;
        s.upper += u32::from(t.upper);
        s.acronym += u32::from(t.acronym);
        if s.sentences.last() != Some(&t.sentence) {
            s.sentences.push(t.sentence);
        }
        if i > 0 && !t.breaks {
            s.left.insert(tokens[i - 1].term);
            s.left_total += 1;
        }
        if let Some(next) = tokens.get(i + 1).filter(|n| !n.breaks) {
            s.right.insert(next.term);
            s.right_total += 1;
        }
    }
    let word_scores = word_scores(&stats, sentences.len().max(1));
    let min_tf = if content_words > REPEAT_ABOVE_WORDS { 2 } else { 1 };
    let phrases = phrases(text, &tokens);
    let mut scored: Vec<(f64, &Phrase)> = phrases
        .values()
        // A phrase must repeat to be one; a single word may appear once in a short text.
        .filter(|p| p.tf >= if p.terms.len() > 1 { 2 } else { min_tf })
        .map(|p| {
            let s: Vec<f64> = p.terms.iter().map(|t| word_scores[t]).collect();
            let product: f64 = s.iter().product();
            let sum: f64 = s.iter().sum();
            (product / (f64::from(p.tf) * (1.0 + sum)), p)
        })
        .collect();
    scored.sort_by(|a, b| a.0.total_cmp(&b.0).then(a.1.first.cmp(&b.1.first)));
    let mut picked: Vec<&Phrase> = Vec::new();
    for (_, p) in scored {
        if picked.len() == max {
            break;
        }
        // "solar", "panels" and "solar panels" are one keyword, and so are
        // "Goa resort" and "resort beach": half or more of the words shared.
        let overlaps = picked.iter().any(|q| {
            let shared = p.terms.iter().filter(|t| q.terms.contains(t)).count();
            shared * 2 >= p.terms.len().min(q.terms.len())
        });
        if !overlaps {
            picked.push(p);
        }
    }
    picked.into_iter().map(surface).collect()
}

fn tokenize(text: &str, sentences: &[Sentence], stop: &StopWords, ids: &mut HashMap<String, u32>) -> Vec<Token> {
    let mut tokens: Vec<Token> = Vec::new();
    let mut si = 0usize;
    let mut prev: Option<(usize, u32)> = None;
    for_each_token(text, |start, end, word| {
        if tokens.len() >= MAX_TOKENS {
            return;
        }
        while si + 1 < sentences.len() && sentences[si].end <= start {
            si += 1;
        }
        let sentence = si as u32;
        let first_in_sentence = prev.is_none_or(|(_, s)| s != sentence);
        let breaks = first_in_sentence || prev.is_some_and(|(pe, _)| !text[pe..start].chars().all(|c| c == ' ' || c == '\t'));
        let surface = &text[start..end];
        let content = word.chars().count() >= MIN_WORD_CHARS
            && !word.chars().any(char::is_numeric)
            && !stop.contains(word)
            && !CALENDAR_WORDS.contains(&word)
            && !part_of_code(text, start, end);
        let key = stem(word);
        let next = ids.len() as u32;
        let term = *ids.entry(key.into_owned()).or_insert(next);
        let mut letters = surface.chars().filter(|c| c.is_alphabetic());
        let starts_upper = letters.next().is_some_and(char::is_uppercase);
        let acronym = surface.chars().count() >= 2 && surface.chars().all(|c| !c.is_alphabetic() || c.is_uppercase()) && starts_upper;
        tokens.push(Token { start, end, term, sentence, breaks, content, upper: starts_upper && !first_in_sentence && !acronym, acronym });
        prev = Some((end, sentence));
    });
    tokens
}

/// YAKE's per-word score from casing, position, frequency, relatedness to
/// context and spread over sentences.
fn word_scores(stats: &HashMap<u32, Stat>, total_sentences: usize) -> HashMap<u32, f64> {
    let n = stats.len().max(1) as f64;
    let mean = stats.values().map(|s| f64::from(s.tf)).sum::<f64>() / n;
    let variance = stats.values().map(|s| (f64::from(s.tf) - mean).powi(2)).sum::<f64>() / n;
    let std = variance.sqrt();
    let max_tf = stats.values().map(|s| s.tf).max().unwrap_or(1).max(1) as f64;
    stats
        .iter()
        .map(|(&term, s)| {
            let tf = f64::from(s.tf);
            let casing = f64::from(s.upper.max(s.acronym)) / (1.0 + tf.ln());
            let median = f64::from(s.sentences[s.sentences.len() / 2]);
            let position = (3.0 + median).ln().ln();
            let frequency = tf / (mean + std);
            let wl = if s.left_total == 0 { 0.0 } else { s.left.len() as f64 / f64::from(s.left_total) };
            let wr = if s.right_total == 0 { 0.0 } else { s.right.len() as f64 / f64::from(s.right_total) };
            let relatedness = 1.0 + (wl + wr) * tf / max_tf;
            let spread = s.sentences.len() as f64 / total_sentences as f64;
            let score = relatedness * position / (casing + frequency / relatedness + spread / relatedness);
            (term, score)
        })
        .collect()
}

/// Runs of one to three content words with nothing between them but spaces.
fn phrases(text: &str, tokens: &[Token]) -> HashMap<Vec<u32>, Phrase> {
    let mut out: HashMap<Vec<u32>, Phrase> = HashMap::new();
    for i in 0..tokens.len() {
        for len in 1..=MAX_PHRASE_WORDS {
            let Some(run) = tokens.get(i..i + len) else { break };
            if !run[len - 1].content || (len > 1 && run[len - 1].breaks) {
                break;
            }
            if !run[0].content {
                break;
            }
            let terms: Vec<u32> = run.iter().map(|t| t.term).collect();
            // A word twice in one phrase ("very very") is not a keyword.
            if len > 1 && terms[..len - 1].contains(&terms[len - 1]) {
                break;
            }
            let entry = out.entry(terms.clone()).or_insert_with(|| Phrase { terms, first: i, ..Phrase::default() });
            entry.tf += 1;
            *entry.surfaces.entry(text[run[0].start..run[len - 1].end].to_owned()).or_insert(0) += 1;
        }
    }
    out
}

/// The way the document writes it most often; lowercase on a tie, since a
/// capital there is usually only the start of a sentence.
fn surface(p: &Phrase) -> String {
    p.surfaces
        .iter()
        .max_by_key(|(s, n)| (**n, !s.chars().any(char::is_uppercase), std::cmp::Reverse((*s).clone())))
        .map(|(s, _)| s.clone())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sentences::split;

    #[test]
    fn finds_the_topic() {
        let text = "Solar panels on the roof cut our electricity bill. The installer fitted twelve solar panels in March. \
            Each solar panel makes power on sunny days, and the inverter sends it to the house. \
            Our electricity bill fell by half after the solar panels were installed. \
            The inverter also shows how much power the solar panels make every hour. \
            Neighbours asked the installer about solar panels for their own houses.";
        let stop = StopWords::for_language(Some("en"));
        let k = extract(text, &split(text), &stop, 5);
        assert!(k.iter().any(|w| w.eq_ignore_ascii_case("solar panels")), "{k:?}");
        // No stop words, and no keyword repeated inside another.
        assert!(!k.iter().any(|w| w == "the"));
        assert!(!k.iter().any(|w| w == "solar" || w == "panels"), "{k:?}");
    }

    #[test]
    fn dates_and_codes_are_not_keywords() {
        let text = "Booking SRT-2025-88417 for the Goa resort. The resort trip starts in March. The Goa resort has a pool.             March is warm in Goa, and the resort beach is quiet. The resort staff meet guests at the airport in March.             Guests at the Goa resort get breakfast. The resort booking SRT-2025-88417 covers two guests.";
        let stop = StopWords::for_language(Some("en"));
        let k = extract(text, &split(text), &stop, 8);
        assert!(!k.iter().any(|w| w.eq_ignore_ascii_case("march") || w.eq_ignore_ascii_case("srt")), "{k:?}");
        assert!(k.iter().any(|w| w.to_lowercase().contains("resort")), "{k:?}");
        assert!(!k.iter().any(|w| w == "resort get"), "{k:?}");
    }

    #[test]
    fn too_short_has_none() {
        let text = "Total due today.";
        let stop = StopWords::for_language(None);
        assert!(extract(text, &split(text), &stop, 5).is_empty());
    }
}
