//! Common function words per language, folded like search folds text. They
//! are left out of keywords and key-sentence similarity, and the most common
//! of them identify a Latin-script language.

use std::collections::HashSet;

use document_search::tokens;

/// Each list is ordered by frequency; the first [`DETECT_WORDS`] identify the language.
pub(crate) const LISTS: &[(&str, &[&str])] = &[
    ("en", &[
        "the", "of", "and", "to", "in", "is", "that", "for", "it", "with", "as", "was", "on", "be", "by", "this", "are", "or", "from",
        "at", "which", "have", "an", "not", "but", "they", "has", "you", "were", "their", "we", "will", "been", "would", "there",
        "can", "if", "more", "when", "who", "all", "its", "a", "i", "he", "she", "his", "her", "him", "our", "your", "my", "me", "us",
        "them", "these", "those", "than", "then", "also", "into", "may", "any", "only", "other", "such", "should", "about", "up",
        "out", "do", "does", "did", "what", "where", "how", "why", "each", "per", "very", "just", "over", "under", "after", "before",
        "between", "both", "same", "some", "most", "much", "many", "own", "here", "being", "because", "while", "during", "through",
        "via", "shall", "must", "could", "upon", "within", "without", "so", "no", "yes", "one", "had", "am", "too", "off", "again",
    ]),
    ("es", &[
        "de", "la", "que", "el", "en", "y", "los", "del", "se", "las", "por", "un", "para", "con", "no", "una", "su", "al", "lo",
        "como", "más", "pero", "sus", "le", "ya", "o", "este", "porque", "esta", "entre", "cuando", "muy", "sin", "sobre", "también",
        "me", "hasta", "hay", "donde", "quien", "desde", "todo", "nos", "durante", "todos", "uno", "les", "ni", "contra", "otros",
        "ese", "eso", "ante", "ellos", "esto", "mí", "antes", "algunos", "unos", "yo", "otro", "otras", "otra", "él", "tanto",
        "esa", "estos", "mucho", "nada", "muchos", "cual", "poco", "ella", "estar", "estas", "algo", "nosotros", "es", "son", "fue",
    ]),
    ("fr", &[
        "de", "la", "le", "et", "les", "des", "en", "un", "du", "une", "que", "est", "pour", "qui", "dans", "par", "plus", "pas",
        "au", "sur", "ne", "se", "ce", "il", "sont", "avec", "ou", "son", "aux", "mais", "nous", "vous", "ils", "elle", "cette",
        "ont", "ses", "leur", "sa", "on", "y", "ces", "comme", "tout", "fait", "être", "sans", "entre", "même", "peut", "aussi",
        "dont", "très", "bien", "deux", "encore", "ainsi", "après", "avant", "été", "je", "tu", "mon", "ma", "mes", "lui", "à",
    ]),
    ("de", &[
        "der", "die", "und", "in", "den", "von", "zu", "das", "mit", "sich", "des", "auf", "für", "ist", "im", "dem", "nicht",
        "ein", "eine", "als", "auch", "es", "an", "werden", "aus", "er", "hat", "dass", "sie", "nach", "wird", "bei", "einer", "um",
        "am", "sind", "noch", "wie", "einem", "über", "einen", "so", "zum", "war", "haben", "nur", "oder", "aber", "vor", "zur",
        "bis", "mehr", "durch", "man", "sein", "wurde", "sei", "kann", "ich", "wir", "ihr", "diese", "dieser", "können", "wenn",
    ]),
    ("pt", &[
        "de", "a", "o", "que", "e", "do", "da", "em", "um", "para", "com", "não", "uma", "os", "no", "se", "na", "por", "mais",
        "as", "dos", "como", "mas", "ao", "ele", "das", "seu", "sua", "ou", "quando", "muito", "nos", "já", "eu", "também", "só",
        "pelo", "pela", "até", "isso", "ela", "entre", "depois", "sem", "mesmo", "aos", "seus", "quem", "nas", "me", "esse", "eles",
        "você", "essa", "num", "nem", "suas", "meu", "minha", "numa", "pelos", "elas", "qual", "lhe", "este", "foi", "são", "é",
    ]),
    ("it", &[
        "di", "e", "il", "la", "che", "in", "a", "per", "un", "del", "non", "una", "i", "le", "si", "da", "con", "al", "della",
        "sono", "lo", "dei", "nel", "come", "ma", "anche", "alla", "se", "gli", "delle", "più", "ha", "questo", "ed", "o", "suo",
        "nella", "essere", "tra", "sua", "dal", "loro", "era", "questa", "fra", "già", "ci", "mi", "ti", "quando", "dove", "molto",
        "sempre", "cui", "perché", "poi", "è", "stato", "degli", "alle", "nei", "sul", "sulla",
    ]),
    ("nl", &[
        "de", "en", "van", "het", "een", "in", "is", "dat", "op", "te", "zijn", "met", "voor", "niet", "aan", "er", "die", "ook",
        "als", "bij", "door", "om", "maar", "dan", "of", "wordt", "nog", "uit", "naar", "worden", "kan", "al", "over", "deze", "ze",
        "heeft", "hij", "was", "wel", "tot", "hun", "meer", "zich", "we", "wij", "je", "u", "ik", "geen", "dit", "wat", "zo", "na",
    ]),
    ("id", &[
        "yang", "dan", "di", "ini", "itu", "dengan", "untuk", "dari", "dalam", "tidak", "akan", "pada", "juga", "ke", "ada", "oleh",
        "adalah", "atau", "saya", "mereka", "kami", "kita", "anda", "sudah", "bisa", "karena", "telah", "lebih", "sebagai", "satu",
        "dia", "seperti", "bahwa", "harus", "hanya", "tersebut", "kepada", "namun", "para", "ia", "hal", "saat", "sangat", "setelah",
    ]),
    ("tr", &[
        "ve", "bir", "bu", "da", "de", "için", "ile", "çok", "gibi", "daha", "olarak", "ama", "en", "ne", "o", "kadar", "sonra",
        "var", "her", "mi", "olan", "ben", "sen", "biz", "siz", "onlar", "değil", "ki", "ya", "veya", "ise", "diye", "olduğu",
        "bunu", "şey", "yok", "nasıl", "neden", "hem", "yani", "bile", "ancak", "tüm", "kendi", "sadece",
    ]),
    ("hi", &[
        "है", "और", "के", "की", "का", "में", "से", "को", "पर", "यह", "था", "हैं", "नहीं", "लिए", "भी", "कि", "जो", "एक", "किया",
        "गया", "हो", "कर", "ने", "तो", "ही", "या", "वह", "इस", "उस", "थे", "थी", "रहा", "रही", "करने", "होता", "अपने",
    ]),
    ("mr", &[
        "आहे", "आणि", "या", "व", "ते", "आहेत", "हे", "होते", "त्या", "म्हणून", "केले", "होता", "नाही", "पण", "तर", "असे", "मध्ये",
        "साठी", "आता", "करून", "त्याचा", "त्याची", "त्यांनी", "होती", "आपण", "काही", "सर्व", "तसेच", "एक", "हा", "ही",
    ]),
];

/// How many of each list's most common words identify the language.
pub(crate) const DETECT_WORDS: usize = 30;

/// `word` as one folded search word, or `None` if search splits it (Devanagari
/// conjuncts with a virama, for example), since it could then never match.
pub(crate) fn as_word(word: &str) -> Option<String> {
    let mut t = tokens(word);
    if t.len() == 1 { t.pop() } else { None }
}

/// Folded stop words for a document: English always, plus its own language.
pub struct StopWords {
    words: HashSet<String>,
}

impl StopWords {
    pub fn for_language(code: Option<&str>) -> Self {
        let mut words = HashSet::new();
        for (lang, list) in LISTS {
            if *lang == "en" || Some(*lang) == code {
                words.extend(list.iter().filter_map(|w| as_word(w)));
            }
        }
        Self { words }
    }

    /// `word` must already be folded.
    pub fn contains(&self, word: &str) -> bool {
        self.words.contains(word)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn folds_accents_like_search() {
        let s = StopWords::for_language(Some("es"));
        assert!(s.contains(&as_word("También").unwrap()));
        assert!(s.contains("the"));
        assert!(!s.contains("invoice"));
    }
}
