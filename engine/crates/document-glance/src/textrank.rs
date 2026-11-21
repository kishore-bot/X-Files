//! TextRank (Mihalcea and Tarau, 2004): sentences are nodes, linked by how
//! many content words they share, and PageRank finds the most central ones.
//! They are returned quoted exactly as written, in document order.

use std::collections::HashMap;

use document_search::{for_each_token, stem};

use crate::sentences::Sentence;
use crate::stopwords::StopWords;

pub const MAX_KEY_SENTENCES: usize = 5;
const MIN_KEY_SENTENCES: usize = 3;
/// Shorter documents are read faster than any summary.
const MIN_CANDIDATES: usize = 6;
/// Only the first sentences take part: bounded work on long books.
const MAX_CANDIDATES: usize = 300;
const MIN_WORDS: usize = 6;
const MAX_WORDS: usize = 60;
const MIN_CONTENT_TERMS: usize = 3;
const DAMPING: f32 = 0.85;
const MAX_ITERATIONS: usize = 60;
const TOLERANCE: f32 = 1e-6;
/// Two picks sharing this much of their words say the same thing.
const MAX_OVERLAP: f32 = 0.5;

struct Candidate {
    start: usize,
    end: usize,
    terms: Vec<u32>,
}

/// Up to `max` key sentences of `text`, or none when it is too short.
pub fn key_sentences(text: &str, sentences: &[Sentence], stop: &StopWords, max: usize) -> Vec<String> {
    let candidates = candidates(text, sentences, stop);
    let n = candidates.len();
    if n < MIN_CANDIDATES {
        return Vec::new();
    }
    let scores = rank(&candidates);
    let want = (n / 8).clamp(MIN_KEY_SENTENCES, max);
    let mut order: Vec<usize> = (0..n).collect();
    // Higher score first; earlier sentence on a tie.
    order.sort_by(|&a, &b| scores[b].total_cmp(&scores[a]).then(a.cmp(&b)));
    let mut picked: Vec<usize> = Vec::with_capacity(want);
    for i in order {
        if picked.len() == want {
            break;
        }
        if picked.iter().all(|&p| jaccard(&candidates[p].terms, &candidates[i].terms) < MAX_OVERLAP) {
            picked.push(i);
        }
    }
    picked.sort_unstable();
    picked.into_iter().map(|i| text[candidates[i].start..candidates[i].end].to_owned()).collect()
}

fn candidates(text: &str, sentences: &[Sentence], stop: &StopWords) -> Vec<Candidate> {
    let mut ids: HashMap<String, u32> = HashMap::new();
    let mut out = Vec::new();
    for s in sentences {
        if out.len() == MAX_CANDIDATES {
            break;
        }
        let slice = &text[s.start..s.end];
        // Headings and labels ("Total:") are not sentences.
        if slice.ends_with(':') {
            continue;
        }
        let mut words = 0usize;
        let mut numeric = 0usize;
        let mut terms = Vec::new();
        for_each_token(slice, |_, _, word| {
            words += 1;
            if word.chars().any(|c| c.is_numeric()) {
                numeric += 1;
                return;
            }
            if word.chars().count() < 2 || stop.contains(word) {
                return;
            }
            let key = stem(word);
            let next = ids.len() as u32;
            let id = *ids.entry(key.into_owned()).or_insert(next);
            terms.push(id);
        });
        // Rows of a table or a list of figures are not prose.
        if !(MIN_WORDS..=MAX_WORDS).contains(&words) || numeric * 3 > words {
            continue;
        }
        terms.sort_unstable();
        terms.dedup();
        if terms.len() >= MIN_CONTENT_TERMS {
            out.push(Candidate { start: s.start, end: s.end, terms });
        }
    }
    out
}

/// Shared terms, normalised by sentence length as in the paper.
fn similarity(a: &[u32], b: &[u32]) -> f32 {
    let shared = shared(a, b);
    if shared == 0 {
        return 0.0;
    }
    shared as f32 / ((a.len() as f32).ln() + (b.len() as f32).ln())
}

fn shared(a: &[u32], b: &[u32]) -> usize {
    let (mut i, mut j, mut n) = (0, 0, 0);
    while i < a.len() && j < b.len() {
        match a[i].cmp(&b[j]) {
            std::cmp::Ordering::Less => i += 1,
            std::cmp::Ordering::Greater => j += 1,
            std::cmp::Ordering::Equal => {
                n += 1;
                i += 1;
                j += 1;
            }
        }
    }
    n
}

fn jaccard(a: &[u32], b: &[u32]) -> f32 {
    let s = shared(a, b);
    s as f32 / (a.len() + b.len() - s).max(1) as f32
}

fn rank(candidates: &[Candidate]) -> Vec<f32> {
    let n = candidates.len();
    let mut edges: Vec<Vec<(usize, f32)>> = vec![Vec::new(); n];
    for i in 0..n {
        for j in i + 1..n {
            let w = similarity(&candidates[i].terms, &candidates[j].terms);
            if w > 0.0 {
                edges[i].push((j, w));
                edges[j].push((i, w));
            }
        }
    }
    let out_weight: Vec<f32> = edges.iter().map(|e| e.iter().map(|&(_, w)| w).sum()).collect();
    let base = (1.0 - DAMPING) / n as f32;
    let mut score = vec![1.0 / n as f32; n];
    let mut next = vec![0.0f32; n];
    for _ in 0..MAX_ITERATIONS {
        for i in 0..n {
            let incoming: f32 = edges[i].iter().map(|&(j, w)| w / out_weight[j] * score[j]).sum();
            next[i] = base + DAMPING * incoming;
        }
        let delta: f32 = score.iter().zip(&next).map(|(a, b)| (a - b).abs()).sum();
        std::mem::swap(&mut score, &mut next);
        if delta < TOLERANCE {
            break;
        }
    }
    score
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sentences::split;

    #[test]
    fn central_sentences_win_and_keep_order() {
        let text = "Solar panels turn sunlight into electricity for homes. \
            The weather was pleasant on the day of the picnic. \
            Modern solar panels convert more sunlight into electricity than older panels. \
            My cousin likes to collect old coins from markets. \
            Homes with solar panels pay less for electricity each month. \
            A good breakfast includes fruit and some bread. \
            Installing solar panels on homes reduces electricity bills over time. \
            The train arrived late because of heavy fog.";
        let stop = StopWords::for_language(Some("en"));
        let picks = key_sentences(text, &split(text), &stop, 5);
        assert_eq!(picks.len(), 3);
        assert!(picks.iter().all(|s| s.to_lowercase().contains("solar")), "{picks:?}");
        let positions: Vec<usize> = picks.iter().map(|s| text.find(s.as_str()).unwrap()).collect();
        assert!(positions.windows(2).all(|w| w[0] < w[1]));
    }

    #[test]
    fn short_text_has_no_summary() {
        let text = "Just one sentence here with a few words in it.";
        let stop = StopWords::for_language(Some("en"));
        assert!(key_sentences(text, &split(text), &stop, 5).is_empty());
    }
}
