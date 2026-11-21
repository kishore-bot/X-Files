//! Which language a document is written in.
//!
//! The script decides most languages outright (Tamil, Thai, Greek, Korean…).
//! Within a shared script, telltale letters (Urdu's ے, Ukrainian's ї) or the
//! most common words (Latin script; Hindi or Marathi in Devanagari) decide.
//! When the evidence is thin the answer is `None`, never a guess.

use document_search::for_each_token;

use crate::stopwords::{DETECT_WORDS, LISTS, as_word};

/// Only the start of a long text is needed.
const SAMPLE_BYTES: usize = 40_000;
/// Fewer letters than this say nothing reliable.
const MIN_LETTERS: u32 = 40;
/// The winning script must have at least this share of the letters.
const MIN_SCRIPT_SHARE: f32 = 0.6;
/// Common-word hits needed, and the margin over the runner-up.
const MIN_WORD_HITS: u32 = 4;
const WORD_MARGIN: f32 = 1.4;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Language {
    /// ISO 639-1.
    pub code: &'static str,
    /// In English, for display.
    pub name: &'static str,
}

const fn lang(code: &'static str, name: &'static str) -> Option<Language> {
    Some(Language { code, name })
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Script {
    Latin,
    Greek,
    Cyrillic,
    Armenian,
    Hebrew,
    Arabic,
    Devanagari,
    Bengali,
    Gurmukhi,
    Gujarati,
    Odia,
    Tamil,
    Telugu,
    Kannada,
    Malayalam,
    Sinhala,
    Thai,
    Georgian,
    Hangul,
    Kana,
    Han,
}

const SCRIPTS: usize = 21;

fn script(c: char) -> Option<Script> {
    use Script::*;
    Some(match c {
        'a'..='z' | 'A'..='Z' | '\u{00C0}'..='\u{024F}' | '\u{1E00}'..='\u{1EFF}' => Latin,
        '\u{0370}'..='\u{03FF}' => Greek,
        '\u{0400}'..='\u{04FF}' => Cyrillic,
        '\u{0530}'..='\u{058F}' => Armenian,
        '\u{0590}'..='\u{05FF}' => Hebrew,
        '\u{0600}'..='\u{06FF}' | '\u{0750}'..='\u{077F}' => Arabic,
        '\u{0900}'..='\u{097F}' => Devanagari,
        '\u{0980}'..='\u{09FF}' => Bengali,
        '\u{0A00}'..='\u{0A7F}' => Gurmukhi,
        '\u{0A80}'..='\u{0AFF}' => Gujarati,
        '\u{0B00}'..='\u{0B7F}' => Odia,
        '\u{0B80}'..='\u{0BFF}' => Tamil,
        '\u{0C00}'..='\u{0C7F}' => Telugu,
        '\u{0C80}'..='\u{0CFF}' => Kannada,
        '\u{0D00}'..='\u{0D7F}' => Malayalam,
        '\u{0D80}'..='\u{0DFF}' => Sinhala,
        '\u{0E00}'..='\u{0E7F}' => Thai,
        '\u{10A0}'..='\u{10FF}' => Georgian,
        '\u{1100}'..='\u{11FF}' | '\u{3130}'..='\u{318F}' | '\u{AC00}'..='\u{D7AF}' => Hangul,
        '\u{3040}'..='\u{30FF}' => Kana,
        '\u{3400}'..='\u{4DBF}' | '\u{4E00}'..='\u{9FFF}' => Han,
        _ => return None,
    })
}

/// The language of `text`, or `None` if it can't be told with confidence.
pub fn detect(text: &str) -> Option<Language> {
    let mut end = text.len().min(SAMPLE_BYTES);
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    let sample = &text[..end];
    let mut counts = [0u32; SCRIPTS];
    let mut letters = 0u32;
    for c in sample.chars().filter(|c| c.is_alphabetic()) {
        if let Some(s) = script(c) {
            counts[s as usize] += 1;
            letters += 1;
        }
    }
    if letters < MIN_LETTERS {
        return None;
    }
    let count = |s: Script| counts[s as usize];
    // Japanese mixes kana with Han; Chinese has no kana.
    let cjk = count(Script::Han) + count(Script::Kana);
    let (top, top_count) = (0..SCRIPTS)
        .map(|i| (i, counts[i]))
        .max_by_key(|&(_, n)| n)
        .unwrap_or((0, 0));
    let share = |n: u32| n as f32 / letters as f32;
    if share(cjk) >= MIN_SCRIPT_SHARE && cjk >= top_count {
        return if count(Script::Kana) * 10 >= cjk { lang("ja", "Japanese") } else { lang("zh", "Chinese") };
    }
    if share(top_count) < MIN_SCRIPT_SHARE {
        return None;
    }
    let has = |chars: &[char]| sample.chars().filter(|c| chars.contains(c)).count();
    use Script::*;
    match ALL[top] {
        Latin => by_common_words(sample, &["en", "es", "fr", "de", "pt", "it", "nl", "id", "tr"]),
        Devanagari => by_common_words(sample, &["hi", "mr"]),
        Greek => lang("el", "Greek"),
        Cyrillic if has(&['і', 'ї', 'є', 'ґ']) >= 2 => lang("uk", "Ukrainian"),
        Cyrillic => lang("ru", "Russian"),
        Armenian => lang("hy", "Armenian"),
        Hebrew => lang("he", "Hebrew"),
        Arabic if has(&['ے', 'ٹ', 'ڈ', 'ڑ', 'ں']) >= 2 => lang("ur", "Urdu"),
        Arabic if has(&['پ', 'چ', 'ژ', 'گ', 'ی']) >= 3 => lang("fa", "Persian"),
        Arabic => lang("ar", "Arabic"),
        Bengali => lang("bn", "Bengali"),
        Gurmukhi => lang("pa", "Punjabi"),
        Gujarati => lang("gu", "Gujarati"),
        Odia => lang("or", "Odia"),
        Tamil => lang("ta", "Tamil"),
        Telugu => lang("te", "Telugu"),
        Kannada => lang("kn", "Kannada"),
        Malayalam => lang("ml", "Malayalam"),
        Sinhala => lang("si", "Sinhala"),
        Thai => lang("th", "Thai"),
        Georgian => lang("ka", "Georgian"),
        Hangul => lang("ko", "Korean"),
        Kana | Han => None,
    }
}

const ALL: [Script; SCRIPTS] = {
    use Script::*;
    [
        Latin, Greek, Cyrillic, Armenian, Hebrew, Arabic, Devanagari, Bengali, Gurmukhi, Gujarati, Odia, Tamil, Telugu, Kannada,
        Malayalam, Sinhala, Thai, Georgian, Hangul, Kana, Han,
    ]
};

fn name_of(code: &str) -> &'static str {
    match code {
        "en" => "English",
        "es" => "Spanish",
        "fr" => "French",
        "de" => "German",
        "pt" => "Portuguese",
        "it" => "Italian",
        "nl" => "Dutch",
        "id" => "Indonesian",
        "tr" => "Turkish",
        "hi" => "Hindi",
        "mr" => "Marathi",
        _ => "",
    }
}

/// Counts each candidate's most common words in the sample; a clear winner wins.
fn by_common_words(sample: &str, candidates: &[&'static str]) -> Option<Language> {
    let sets: Vec<(&'static str, Vec<String>)> = LISTS
        .iter()
        .filter(|(code, _)| candidates.contains(code))
        .map(|(code, list)| (*code, list.iter().take(DETECT_WORDS).filter_map(|w| as_word(w)).collect()))
        .collect();
    let mut hits = vec![0u32; sets.len()];
    for_each_token(sample, |_, _, word| {
        for (i, (_, set)) in sets.iter().enumerate() {
            if set.iter().any(|w| w == word) {
                hits[i] += 1;
            }
        }
    });
    let mut order: Vec<usize> = (0..sets.len()).collect();
    order.sort_by_key(|&i| std::cmp::Reverse(hits[i]));
    let best = hits[order[0]];
    let second = order.get(1).map_or(0, |&i| hits[i]);
    if best < MIN_WORD_HITS || (best as f32) < second as f32 * WORD_MARGIN {
        return None;
    }
    let code = sets[order[0]].0;
    lang(code, name_of(code))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn code(text: &str) -> Option<&'static str> {
        detect(text).map(|l| l.code)
    }

    #[test]
    fn latin_languages_by_common_words() {
        assert_eq!(code("The invoice for the month of March is attached, and the amount is due by the end of the week."), Some("en"));
        assert_eq!(code("La factura del mes de marzo está adjunta y el importe vence al final de la semana que viene."), Some("es"));
        assert_eq!(code("Die Rechnung für den Monat März ist beigefügt und der Betrag ist bis zum Ende der Woche fällig."), Some("de"));
        assert_eq!(code("La facture du mois de mars est jointe et le montant est dû à la fin de la semaine prochaine."), Some("fr"));
    }

    #[test]
    fn scripts() {
        assert_eq!(code("இந்த ஆவணம் தமிழில் எழுதப்பட்டுள்ளது மற்றும் இது ஒரு சோதனை மட்டுமே ஆகும்"), Some("ta"));
        assert_eq!(code("Этот документ написан на русском языке и является только тестом для проверки"), Some("ru"));
        assert_eq!(code("この文書は日本語で書かれています。これはテストです。言語を正しく判定できるか確認します。"), Some("ja"));
    }

    #[test]
    fn hindi_and_marathi() {
        assert_eq!(code("यह दस्तावेज़ हिंदी में लिखा गया है और इस में कुछ वाक्य हैं जो परीक्षण के लिए है"), Some("hi"));
        assert_eq!(code("हा दस्तऐवज मराठी मध्ये लिहिला आहे आणि यात काही वाक्ये आहेत जी चाचणी साठी आहेत"), Some("mr"));
    }

    #[test]
    fn too_little_text_says_nothing() {
        assert_eq!(code("Invoice 2025"), None);
        assert_eq!(code("1234 5678 9012 3456 7890 1234 5678 9012 3456 7890 1234 5678"), None);
    }
}
