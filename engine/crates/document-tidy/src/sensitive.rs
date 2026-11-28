//! Does a document show numbers someone shouldn't screenshot?
//!
//! Only checksummed formats are reported, so ordinary numbers (phone numbers,
//! totals, order IDs) almost never trigger it:
//!
//! - payment card numbers: 13-19 digits, written whole or in the usual groups,
//!   with a known issuer prefix and a valid Luhn check digit;
//! - Aadhaar numbers: 12 digits starting 2-9, grouped 4-4-4 (or whole when
//!   the text says "Aadhaar"), with a valid Verhoeff check digit;
//! - IBANs: country code, check digits and a country-specific length that
//!   passes the ISO 7064 mod-97 check.
//!
//! The numbers themselves never leave this module: the result is three flags.

/// What kinds of sensitive number a text contains.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct SensitiveFindings {
    pub payment_card: bool,
    pub national_id: bool,
    pub bank_account: bool,
}

impl SensitiveFindings {
    pub fn any(&self) -> bool {
        self.payment_card || self.national_id || self.bank_account
    }
}

/// Text beyond this is not scanned.
const MAX_TEXT_BYTES: usize = 400_000;

pub fn scan(text: &str) -> SensitiveFindings {
    let mut end = text.len().min(MAX_TEXT_BYTES);
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    let text = &text[..end];
    let mentions_aadhaar = {
        let lower = text.to_lowercase();
        lower.contains("aadhaar") || lower.contains("aadhar") || lower.contains("uidai") || text.contains("आधार")
    };

    let mut found = SensitiveFindings::default();
    for groups in digit_sequences(text) {
        if !found.payment_card && has_card(&groups) {
            found.payment_card = true;
        }
        if !found.national_id && has_aadhaar(&groups, mentions_aadhaar) {
            found.national_id = true;
        }
        if found.payment_card && found.national_id {
            break;
        }
    }
    found.bank_account = has_iban(text);
    found
}

/// Runs of digit groups separated by a single space or hyphen, not glued to
/// letters or other digits. Each run is its list of groups.
fn digit_sequences(text: &str) -> Vec<Vec<&str>> {
    let bytes = text.as_bytes();
    let mut runs = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
        if !bytes[i].is_ascii_digit() || (i > 0 && (bytes[i - 1].is_ascii_alphanumeric() || bytes[i - 1] == b'.')) {
            i += 1;
            continue;
        }
        let mut groups = Vec::new();
        let mut start = i;
        let mut end = i;
        loop {
            while end < bytes.len() && bytes[end].is_ascii_digit() {
                end += 1;
            }
            groups.push(&text[start..end]);
            let joins = end + 1 < bytes.len() && matches!(bytes[end], b' ' | b'-') && bytes[end + 1].is_ascii_digit();
            if !joins || groups.len() >= 12 {
                break;
            }
            start = end + 1;
            end = start;
        }
        let glued = bytes.get(end).is_some_and(|&b| b.is_ascii_alphabetic() || b == b'.' && bytes.get(end + 1).is_some_and(u8::is_ascii_digit));
        if !glued {
            runs.push(groups);
        }
        i = end.max(i + 1);
    }
    runs
}

/// Any consecutive groups forming a card number: whole, 4-4-4-x, or 4-6-5.
fn has_card(groups: &[&str]) -> bool {
    for start in 0..groups.len() {
        let mut digits = String::new();
        for (n, group) in groups[start..].iter().enumerate() {
            digits.push_str(group);
            if digits.len() > 19 {
                break;
            }
            let lens: Vec<usize> = groups[start..=start + n].iter().map(|g| g.len()).collect();
            let shaped = lens.len() == 1
                || lens == [4, 6, 5]
                || lens == [4, 6, 4]
                || (lens[..lens.len() - 1].iter().all(|&l| l == 4) && (1..=4).contains(lens.last().unwrap_or(&0)));
            if shaped && (13..=19).contains(&digits.len()) && known_issuer(&digits) && !all_same(&digits) && luhn(&digits) {
                return true;
            }
        }
    }
    false
}

fn known_issuer(digits: &str) -> bool {
    let prefix = |n: usize| digits[..n].parse::<u32>().unwrap_or(0);
    let len = digits.len();
    match digits.as_bytes()[0] {
        b'4' => matches!(len, 13 | 16 | 19),                            // Visa
        b'5' => (51..=55).contains(&prefix(2)) && len == 16,            // Mastercard
        b'2' => (2221..=2720).contains(&prefix(4)) && len == 16,        // Mastercard 2-series
        b'3' => match prefix(2) {
            34 | 37 => len == 15,                                       // American Express
            35 => (16..=19).contains(&len),                             // JCB
            30 | 36 | 38 | 39 => (14..=19).contains(&len),              // Diners Club
            _ => false,
        },
        b'6' => (16..=19).contains(&len),                               // Discover, RuPay, UnionPay, Maestro
        b'8' => matches!(prefix(2), 81 | 82) && len == 16,              // RuPay
        _ => false,
    }
}

fn all_same(digits: &str) -> bool {
    let first = digits.as_bytes()[0];
    digits.bytes().all(|b| b == first)
}

pub(crate) fn luhn(digits: &str) -> bool {
    let mut sum = 0u32;
    for (i, b) in digits.bytes().rev().enumerate() {
        let mut d = u32::from(b - b'0');
        if i % 2 == 1 {
            d *= 2;
            if d > 9 {
                d -= 9;
            }
        }
        sum += d;
    }
    sum % 10 == 0
}

/// 4-4-4 groups, or 12 digits whole when the text names Aadhaar.
fn has_aadhaar(groups: &[&str], mentions_aadhaar: bool) -> bool {
    let check = |digits: &str| {
        digits.len() == 12 && (b'2'..=b'9').contains(&digits.as_bytes()[0]) && !all_same(digits) && verhoeff(digits)
    };
    groups.windows(3).any(|w| w.iter().all(|g| g.len() == 4) && check(&w.concat()))
        || (mentions_aadhaar && groups.iter().any(|g| check(g)))
}

const VERHOEFF_D: [[u8; 10]; 10] = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
    [1, 2, 3, 4, 0, 6, 7, 8, 9, 5],
    [2, 3, 4, 0, 1, 7, 8, 9, 5, 6],
    [3, 4, 0, 1, 2, 8, 9, 5, 6, 7],
    [4, 0, 1, 2, 3, 9, 5, 6, 7, 8],
    [5, 9, 8, 7, 6, 0, 4, 3, 2, 1],
    [6, 5, 9, 8, 7, 1, 0, 4, 3, 2],
    [7, 6, 5, 9, 8, 2, 1, 0, 4, 3],
    [8, 7, 6, 5, 9, 3, 2, 1, 0, 4],
    [9, 8, 7, 6, 5, 4, 3, 2, 1, 0],
];

const VERHOEFF_P: [[u8; 10]; 8] = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
    [1, 5, 7, 6, 2, 8, 3, 0, 9, 4],
    [5, 8, 0, 3, 7, 9, 6, 1, 4, 2],
    [8, 9, 1, 6, 0, 4, 3, 5, 2, 7],
    [9, 4, 5, 3, 1, 2, 6, 8, 7, 0],
    [4, 2, 8, 6, 5, 7, 3, 9, 0, 1],
    [2, 7, 9, 3, 8, 0, 6, 4, 1, 5],
    [7, 0, 4, 6, 9, 1, 3, 2, 5, 8],
];

pub(crate) fn verhoeff(digits: &str) -> bool {
    let mut c = 0u8;
    for (i, b) in digits.bytes().rev().enumerate() {
        c = VERHOEFF_D[c as usize][VERHOEFF_P[i % 8][(b - b'0') as usize] as usize];
    }
    c == 0
}

/// IBAN lengths for countries that use them (ISO 13616 registry).
const IBAN_LENGTHS: &[(&str, usize)] = &[
    ("AD", 24), ("AE", 23), ("AL", 28), ("AT", 20), ("AZ", 28), ("BA", 20), ("BE", 16), ("BG", 22), ("BH", 22),
    ("BR", 29), ("CH", 21), ("CR", 22), ("CY", 28), ("CZ", 24), ("DE", 22), ("DK", 18), ("DO", 28), ("EE", 20),
    ("EG", 29), ("ES", 24), ("FI", 18), ("FO", 18), ("FR", 27), ("GB", 22), ("GE", 22), ("GI", 23), ("GL", 18),
    ("GR", 27), ("GT", 28), ("HR", 21), ("HU", 28), ("IE", 22), ("IL", 23), ("IQ", 23), ("IS", 26), ("IT", 27),
    ("JO", 30), ("KW", 30), ("KZ", 20), ("LB", 28), ("LI", 21), ("LT", 20), ("LU", 20), ("LV", 21), ("MC", 27),
    ("MD", 24), ("ME", 22), ("MK", 19), ("MR", 27), ("MT", 31), ("MU", 30), ("NL", 18), ("NO", 15), ("PK", 24),
    ("PL", 28), ("PS", 29), ("PT", 25), ("QA", 29), ("RO", 24), ("RS", 22), ("SA", 24), ("SE", 24), ("SI", 19),
    ("SK", 24), ("SM", 27), ("TN", 24), ("TR", 26), ("UA", 29), ("VG", 24), ("XK", 20),
];

/// An IBAN, compact or printed in groups of four.
fn has_iban(text: &str) -> bool {
    let bytes = text.as_bytes();
    let mut i = 0;
    while i + 4 <= bytes.len() {
        let starts = bytes[i].is_ascii_uppercase()
            && bytes[i + 1].is_ascii_uppercase()
            && bytes[i + 2].is_ascii_digit()
            && bytes[i + 3].is_ascii_digit()
            && (i == 0 || !bytes[i - 1].is_ascii_alphanumeric());
        if !starts {
            i += 1;
            continue;
        }
        let Some(&(_, len)) = IBAN_LENGTHS.iter().find(|(cc, _)| cc.as_bytes() == &bytes[i..i + 2]) else {
            i += 1;
            continue;
        };
        // Gather up to `len` characters, allowing single spaces between groups.
        let mut compact = Vec::with_capacity(len);
        let mut j = i;
        while j < bytes.len() && compact.len() < len {
            let b = bytes[j];
            if b.is_ascii_uppercase() || b.is_ascii_digit() {
                compact.push(b);
            } else if b == b' ' && bytes.get(j + 1).is_some_and(|n| n.is_ascii_alphanumeric()) && compact.len() % 4 == 0 {
                // a group break
            } else {
                break;
            }
            j += 1;
        }
        let ends_cleanly = bytes.get(j).is_none_or(|b| !b.is_ascii_alphanumeric());
        if compact.len() == len && ends_cleanly && mod97(&compact) == 1 {
            return true;
        }
        i += 1;
    }
    false
}

/// ISO 7064 mod 97-10 of an IBAN: first four characters moved to the end,
/// letters as 10..35.
fn mod97(iban: &[u8]) -> u32 {
    let mut rem = 0u32;
    for &b in iban[4..].iter().chain(&iban[..4]) {
        let value = if b.is_ascii_digit() { u32::from(b - b'0') } else { u32::from(b - b'A') + 10 };
        rem = if value >= 10 { (rem * 100 + value) % 97 } else { (rem * 10 + value) % 97 };
    }
    rem
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn checksums_match_known_values() {
        assert!(luhn("4111111111111111"));
        assert!(!luhn("4111111111111112"));
        assert!(verhoeff("2363"), "Verhoeff example: 236 with check digit 3");
        assert!(!verhoeff("2364"));
        assert_eq!(mod97(b"GB82WEST12345698765432"), 1);
        assert_eq!(mod97(b"DE89370400440532013000"), 1);
    }

    #[test]
    fn finds_cards_in_their_usual_shapes() {
        for text in [
            "Card: 4111 1111 1111 1111 exp 12/27",
            "card no 4111-1111-1111-1111.",
            "Paid with 5555555555554444",
            "Amex 3782 822463 10005",
        ] {
            assert!(scan(text).payment_card, "{text}");
        }
    }

    #[test]
    fn ordinary_numbers_are_not_cards() {
        for text in [
            "Order 4111111111111112 shipped",          // Luhn fails
            "Call +91 98765 43210 or 1800 123 4567",   // phone numbers
            "Invoice INV4111111111111111",             // glued to letters
            "Total 1,234,567.00 on 2024-05-12",
            "0000 0000 0000 0000",
            "Reference 1234 5678 9012 3456",           // no issuer starts with 1
        ] {
            assert!(!scan(text).payment_card, "{text}");
        }
    }

    #[test]
    fn finds_aadhaar_numbers_only_when_they_check_out() {
        // 2345 6789 0124 has a valid Verhoeff check digit.
        assert!(verhoeff("234567890124"));
        assert!(scan("Aadhaar no. 2345 6789 0124").national_id);
        assert!(scan("UID: 2345-6789-0124").national_id);
        assert!(scan("आधार 234567890124").national_id);
        // Whole, without saying Aadhaar: too likely to be anything else.
        assert!(!scan("Ref 234567890124").national_id);
        // Wrong check digit, or a first digit of 0 or 1.
        assert!(!scan("2345 6789 0125").national_id);
        assert!(verhoeff("134567890129"));
        assert!(!scan("1345 6789 0129").national_id);
    }

    #[test]
    fn finds_ibans_compact_and_grouped() {
        assert!(scan("IBAN: GB82 WEST 1234 5698 7654 32").bank_account);
        assert!(scan("Pay to DE89370400440532013000 by Friday").bank_account);
        assert!(!scan("IBAN: GB82 WEST 1234 5698 7654 33").bank_account);
        assert!(!scan("Model GB82WEST1234").bank_account);
    }

    #[test]
    fn plain_text_has_no_findings() {
        let text = "Minutes of the meeting held on 12 March 2024. Budget 45,000.00. Room 101.";
        assert_eq!(scan(text), SensitiveFindings::default());
        assert!(!scan(text).any());
    }
}
