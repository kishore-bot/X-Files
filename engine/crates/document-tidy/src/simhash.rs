//! 64-bit SimHash of a document's text over word 3-gram shingles.
//!
//! Words are maximal runs of letters or digits, lowercased (Unicode aware),
//! so punctuation, spacing and case don't matter. Each run of three
//! consecutive words is hashed; every shingle votes +1 or -1 on each of the 64
//! bits, and the fingerprint keeps the sign. Documents that share most of
//! their shingles get fingerprints a few bits apart; unrelated ones differ in
//! about half the bits. Memory is the 64 counters and the last three words.

use crate::xxh64::xxh64;

/// Below this many shingles a document is too short to call anything a near
/// copy of it (a title page, a one-line note).
pub const MIN_SHINGLES: usize = 40;

/// Upper bound on words looked at, so a huge text costs bounded time.
const MAX_WORDS: usize = 60_000;

/// Fingerprint of `text`, or `None` when it has too few words to judge.
pub fn simhash(text: &str) -> Option<u64> {
    let mut counts = [0i32; 64];
    let mut window = [0u64; 3];
    let mut words = 0usize;
    let mut shingles = 0usize;
    let mut word = String::new();

    let push_word = |word: &str, counts: &mut [i32; 64], window: &mut [u64; 3], words: &mut usize, shingles: &mut usize| {
        window.rotate_left(1);
        window[2] = xxh64(word.as_bytes(), 0);
        *words += 1;
        if *words >= 3 {
            let shingle = mix(window[0] ^ window[1].rotate_left(21) ^ window[2].rotate_left(42));
            for (bit, count) in counts.iter_mut().enumerate() {
                if shingle >> bit & 1 == 1 {
                    *count += 1;
                } else {
                    *count -= 1;
                }
            }
            *shingles += 1;
        }
    };

    for ch in text.chars() {
        if words >= MAX_WORDS {
            break;
        }
        if ch.is_alphanumeric() {
            word.extend(ch.to_lowercase());
        } else if !word.is_empty() {
            push_word(&word, &mut counts, &mut window, &mut words, &mut shingles);
            word.clear();
        }
    }
    if !word.is_empty() && words < MAX_WORDS {
        push_word(&word, &mut counts, &mut window, &mut words, &mut shingles);
    }

    if shingles < MIN_SHINGLES {
        return None;
    }
    Some(counts.iter().enumerate().fold(0u64, |fp, (bit, &count)| if count > 0 { fp | 1 << bit } else { fp }))
}

/// A strong 64-bit finaliser (from SplitMix64), so shingle bits are independent.
fn mix(mut z: u64) -> u64 {
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    z ^ (z >> 31)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::similar::hamming;

    /// A plausible report: varied English words, deterministic.
    pub(crate) fn report(words: usize, seed: u64) -> Vec<String> {
        const VOCAB: &[&str] = &[
            "the", "quarterly", "revenue", "grew", "across", "all", "regions", "while", "operating", "costs", "fell",
            "slightly", "due", "to", "lower", "freight", "and", "energy", "prices", "our", "team", "expects", "growth",
            "next", "year", "in", "new", "markets", "customers", "reported", "higher", "satisfaction", "with", "support",
            "product", "launch", "delayed", "by", "two", "weeks", "pending", "final", "review", "of", "safety", "tests",
            "board", "approved", "budget", "for", "hiring", "engineers", "sales", "staff", "office", "lease", "renewed",
        ];
        let mut state = seed;
        (0..words)
            .map(|_| {
                state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
                VOCAB[(state >> 33) as usize % VOCAB.len()].to_string()
            })
            .collect()
    }

    #[test]
    fn same_words_any_punctuation_or_case_match() {
        let words = report(200, 1);
        let plain = words.join(" ");
        let styled: String = words
            .iter()
            .enumerate()
            .map(|(i, w)| if i % 7 == 0 { format!("{}.\n", w.to_uppercase()) } else { format!("{w},  ") })
            .collect();
        assert_eq!(simhash(&plain), simhash(&styled));
        assert!(simhash(&plain).is_some());
    }

    #[test]
    fn short_texts_have_no_fingerprint() {
        assert_eq!(simhash("Meeting notes: call Bob"), None);
        assert_eq!(simhash(""), None);
    }

    #[test]
    fn a_small_edit_stays_close_and_unrelated_documents_are_far() {
        let original = report(1500, 3);
        let mut edited = original.clone();
        // report_final2: a sentence reworded and a figure changed.
        for (i, word) in ["revised", "figures", "attached", "below"].iter().enumerate() {
            edited[700 + i] = word.to_string();
        }
        edited[1200] = "2025".into();
        let a = simhash(&original.join(" ")).unwrap();
        let b = simhash(&edited.join(" ")).unwrap();
        let other = simhash(&report(1500, 99).join(" ")).unwrap();
        assert!(hamming(a, b) <= crate::DOCUMENT_MAX_DISTANCE, "edited copy is {} bits away", hamming(a, b));
        assert!(hamming(a, other) > 16, "unrelated document only {} bits away", hamming(a, other));
    }

    #[test]
    fn non_latin_text_is_fingerprinted() {
        let text = "यह एक परीक्षण दस्तावेज़ है जिसमें कई शब्द हैं ".repeat(10);
        assert!(simhash(&text).is_some());
    }
}
/// Measures how far edited copies drift, for tuning
/// [`crate::DOCUMENT_MAX_DISTANCE`]: `cargo test -p document-tidy tune -- --ignored --nocapture`.
/// On 2026-11-11: 1500 words with 15 words changed had a median of 5 bits
/// (26% within 3, 57% within 5); unrelated texts from the same small
/// vocabulary were never closer than 20.
#[cfg(test)]
mod tune {
    use super::*;
    use crate::similar::hamming;
    #[test]
    #[ignore]
    fn measure() {
        for (len, edits) in [(300usize, 3usize), (300, 10), (1500, 5), (1500, 15), (1500, 40), (4000, 20)] {
            let mut ds = Vec::new();
            for seed in 0..200u64 {
                let original = super::tests::report(len, seed * 31 + 5);
                let mut edited = original.clone();
                let mut s = seed;
                for _ in 0..edits {
                    s = s.wrapping_mul(6364136223846793005).wrapping_add(1);
                    let at = (s >> 33) as usize % len;
                    edited[at] = format!("edit{}", s % 1000);
                }
                ds.push(hamming(simhash(&original.join(" ")).unwrap(), simhash(&edited.join(" ")).unwrap()));
            }
            ds.sort();
            let within = |d: u32| ds.iter().filter(|&&x| x <= d).count();
            println!("len {len} edits {edits}: median {} p90 {} <=3 {}% <=4 {}% <=5 {}%", ds[100], ds[180], within(3)/2, within(4)/2, within(5)/2);
        }
        let mut min = 64;
        for seed in 0..200u64 {
            let a = simhash(&super::tests::report(1500, seed).join(" ")).unwrap();
            let b = simhash(&super::tests::report(1500, seed + 1000).join(" ")).unwrap();
            min = min.min(hamming(a, b));
        }
        println!("unrelated min {min}");
    }
}
