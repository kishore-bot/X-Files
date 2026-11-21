//! Case and diacritic folding, so "Résumé", "RESUME" and "resume" compare equal.
//!
//! A small hand-written table covers Latin-1 and Latin Extended-A, which is
//! what Western and Central European file names and documents use. Combining
//! marks (decomposed accents) are dropped. Other scripts are only lowercased.

/// Appends the folded form of `c` to `out`: lowercase, without accents.
pub fn push_folded(c: char, out: &mut String) {
    if c.is_ascii() {
        out.push(c.to_ascii_lowercase());
        return;
    }
    if is_combining_mark(c) {
        return;
    }
    for lower in c.to_lowercase() {
        if is_combining_mark(lower) {
            continue;
        }
        match strip_accent(lower) {
            Some(base) => out.push_str(base),
            None => out.push(lower),
        }
    }
}

/// The folded form of a whole string.
pub fn fold(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        push_folded(c, &mut out);
    }
    out
}

/// Combining diacritical marks, which belong to the letter before them.
pub fn is_combining_mark(c: char) -> bool {
    matches!(c, '\u{0300}'..='\u{036F}' | '\u{1AB0}'..='\u{1AFF}' | '\u{1DC0}'..='\u{1DFF}' | '\u{20D0}'..='\u{20FF}' | '\u{FE20}'..='\u{FE2F}')
}

/// The unaccented spelling of a lowercase letter, where it has one.
fn strip_accent(c: char) -> Option<&'static str> {
    Some(match c {
        'à' | 'á' | 'â' | 'ã' | 'ä' | 'å' | 'ā' | 'ă' | 'ą' => "a",
        'æ' => "ae",
        'ç' | 'ć' | 'ĉ' | 'ċ' | 'č' => "c",
        'ď' | 'đ' | 'ð' => "d",
        'è' | 'é' | 'ê' | 'ë' | 'ē' | 'ĕ' | 'ė' | 'ę' | 'ě' => "e",
        'ĝ' | 'ğ' | 'ġ' | 'ģ' => "g",
        'ĥ' | 'ħ' => "h",
        'ì' | 'í' | 'î' | 'ï' | 'ĩ' | 'ī' | 'ĭ' | 'į' | 'ı' => "i",
        'ĳ' => "ij",
        'ĵ' => "j",
        'ķ' | 'ĸ' => "k",
        'ĺ' | 'ļ' | 'ľ' | 'ŀ' | 'ł' => "l",
        'ñ' | 'ń' | 'ņ' | 'ň' | 'ŉ' | 'ŋ' => "n",
        'ò' | 'ó' | 'ô' | 'õ' | 'ö' | 'ø' | 'ō' | 'ŏ' | 'ő' => "o",
        'œ' => "oe",
        'ŕ' | 'ŗ' | 'ř' => "r",
        'ś' | 'ŝ' | 'ş' | 'š' | 'ș' | 'ſ' => "s",
        'ß' => "ss",
        'ţ' | 'ť' | 'ŧ' | 'ț' => "t",
        'þ' => "th",
        'ù' | 'ú' | 'û' | 'ü' | 'ũ' | 'ū' | 'ŭ' | 'ů' | 'ű' | 'ų' => "u",
        'ŵ' => "w",
        'ý' | 'ÿ' | 'ŷ' => "y",
        'ź' | 'ż' | 'ž' => "z",
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn folds_case_and_accents() {
        assert_eq!(fold("Résumé"), "resume");
        assert_eq!(fold("ÉCOLE Straße"), "ecole strasse");
        assert_eq!(fold("Œuvre Łódź Ærø"), "oeuvre lodz aero");
        assert_eq!(fold("naïve façade"), "naive facade");
    }

    #[test]
    fn drops_combining_marks() {
        // "e" + COMBINING ACUTE ACCENT, as macOS spells file names.
        assert_eq!(fold("Cafe\u{301}"), "cafe");
        // Turkish dotted capital I lowercases to i + a combining dot.
        assert_eq!(fold("İstanbul"), "istanbul");
    }

    #[test]
    fn leaves_other_scripts_lowercased() {
        assert_eq!(fold("ΑΘΗΝΑ"), "αθηνα");
        assert_eq!(fold("東京"), "東京");
        assert_eq!(fold("Привет"), "привет");
    }
}
