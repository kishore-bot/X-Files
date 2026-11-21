//! Things in a document you can act on: dates, amounts, phone numbers,
//! emails, links and reference numbers, found by small hand-written
//! matchers (no regex engine in the library).
//!
//! Matchers run in a fixed order and a later one never takes text an earlier
//! one claimed, so "Order ID 9876543210" is a reference, not a phone number.
//! Anything shaped like a payment card (Luhn) or an Aadhaar number (Verhoeff)
//! is left out: the sheet never puts those on screen.

use std::collections::{HashMap, HashSet};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum ItemKind {
    Date,
    Amount,
    Phone,
    Email,
    Link,
    Reference,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Item {
    pub kind: ItemKind,
    /// As written in the document.
    pub text: String,
    /// For acting on: `YYYY-MM-DD`, a plain number (`1234.50`), `+`-prefixed
    /// or bare digits, a lowercase email, an `http(s)` URL, or the reference.
    pub value: String,
    /// The currency, a link's host, or what a reference is ("Invoice number").
    pub label: Option<String>,
    /// Byte offset in the text.
    pub start: usize,
}

const MAX_PER_KIND: usize = 10;
const MAX_ITEMS: usize = 40;
/// Per matcher, before overlaps and duplicates are removed.
const MAX_CANDIDATES: usize = 300;

struct Found {
    kind: ItemKind,
    start: usize,
    end: usize,
    text: Option<String>,
    value: String,
    label: Option<String>,
}

/// Everything found in `text`, in document order.
pub fn find(text: &str) -> Vec<Item> {
    let matchers: [fn(&str) -> Vec<Found>; 6] = [links, emails, dates, amounts, references, phones];
    let mut accepted: Vec<Found> = Vec::new();
    for matcher in matchers {
        let mut found = matcher(text);
        found.truncate(MAX_CANDIDATES);
        for f in found {
            if !accepted.iter().any(|a| a.start < f.end && f.start < a.end) {
                accepted.push(f);
            }
        }
    }
    accepted.retain(|f| !looks_sensitive(f));
    accepted.sort_by_key(|f| f.start);
    let mut seen: HashSet<(ItemKind, String)> = HashSet::new();
    let mut per_kind: HashMap<ItemKind, usize> = HashMap::new();
    let mut out = Vec::new();
    for f in accepted {
        if out.len() == MAX_ITEMS {
            break;
        }
        let count = per_kind.entry(f.kind).or_insert(0);
        if *count == MAX_PER_KIND || !seen.insert((f.kind, f.value.clone())) {
            continue;
        }
        *count += 1;
        out.push(Item {
            kind: f.kind,
            text: f.text.unwrap_or_else(|| text[f.start..f.end].to_owned()),
            value: f.value,
            label: f.label,
            start: f.start,
        });
    }
    out
}

// --- Shared helpers ----------------------------------------------------------

fn prev_char(text: &str, i: usize) -> Option<char> {
    text[..i].chars().next_back()
}

fn next_char(text: &str, i: usize) -> Option<char> {
    text[i..].chars().next()
}

fn word_start(text: &str, i: usize) -> bool {
    !prev_char(text, i).is_some_and(char::is_alphanumeric)
}

fn word_end(text: &str, i: usize) -> bool {
    !next_char(text, i).is_some_and(char::is_alphanumeric)
}

fn digits_at(b: &[u8], i: usize) -> usize {
    b[i.min(b.len())..].iter().take_while(|c| c.is_ascii_digit()).count()
}

fn parse(b: &[u8], i: usize, n: usize) -> u32 {
    b[i..i + n].iter().fold(0u32, |acc, c| acc.saturating_mul(10).saturating_add(u32::from(c - b'0')))
}

/// A number starts here: not inside a word or another number ("1.2.3").
fn number_start(b: &[u8], i: usize) -> bool {
    if i == 0 {
        return true;
    }
    let p = b[i - 1];
    if p.is_ascii_alphanumeric() {
        return false;
    }
    !(matches!(p, b'.' | b'/' | b'-' | b',' | b':') && i >= 2 && b[i - 2].is_ascii_digit())
}

/// ASCII words of `text` with their byte ranges.
fn ascii_words(text: &str) -> impl Iterator<Item = (usize, usize)> + '_ {
    let b = text.as_bytes();
    let mut i = 0;
    std::iter::from_fn(move || {
        while i < b.len() {
            if b[i].is_ascii_alphabetic() && word_start(text, i) {
                let start = i;
                while i < b.len() && b[i].is_ascii_alphabetic() {
                    i += 1;
                }
                if word_end(text, i) {
                    return Some((start, i));
                }
            } else {
                i += 1;
            }
        }
        None
    })
}

// --- Links -------------------------------------------------------------------

fn links(text: &str) -> Vec<Found> {
    let b = text.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < b.len() {
        let rest = &b[i..];
        let prefix = ["https://", "http://", "www."].into_iter().find(|p| rest.len() >= p.len() && rest[..p.len()].eq_ignore_ascii_case(p.as_bytes()));
        let Some(prefix) = prefix else {
            i += 1;
            continue;
        };
        if !word_start(text, i) || prev_char(text, i).is_some_and(|c| matches!(c, '/' | '@' | '.')) {
            i += 1;
            continue;
        }
        let mut end = i;
        for c in text[i..].chars() {
            if c.is_whitespace() || matches!(c, '<' | '>' | '"' | '`' | '{' | '}' | '|' | '\\' | '^') {
                break;
            }
            end += c.len_utf8();
        }
        end = trim_link(&text[i..end]) + i;
        let url = &text[i..end];
        if let Some(host) = link_host(&url[prefix.len().min(url.len())..]) {
            let www = prefix == "www.";
            let value = if www { format!("https://{url}") } else { url.to_owned() };
            let host = if www { format!("www.{host}") } else { host };
            out.push(Found { kind: ItemKind::Link, start: i, end, text: None, value, label: Some(host.to_ascii_lowercase()) });
        }
        i = end.max(i + 1);
    }
    out
}

/// Length of `url` without trailing punctuation that belongs to the sentence.
fn trim_link(url: &str) -> usize {
    let mut s = url;
    loop {
        let Some(last) = s.chars().next_back() else { return 0 };
        let drop = match last {
            '.' | ',' | ';' | ':' | '!' | '?' | '\'' | '*' | '”' | '’' => true,
            ')' => s.matches('(').count() < s.matches(')').count(),
            ']' => s.matches('[').count() < s.matches(']').count(),
            _ => false,
        };
        if !drop {
            return s.len();
        }
        s = &s[..s.len() - last.len_utf8()];
    }
}

/// The host of a link after its scheme, if it looks like a real domain.
fn link_host(rest: &str) -> Option<String> {
    let host = rest.split(['/', '?', '#', ':']).next()?;
    let valid = host.len() >= 4
        && host.contains('.')
        && !host.contains("..")
        && host.chars().all(|c| c.is_alphanumeric() || c == '-' || c == '.')
        && host.rsplit('.').next().is_some_and(|tld| tld.len() >= 2 && tld.chars().all(char::is_alphabetic));
    valid.then(|| host.to_owned())
}

// --- Emails ------------------------------------------------------------------

fn emails(text: &str) -> Vec<Found> {
    let b = text.as_bytes();
    let local = |c: u8| c.is_ascii_alphanumeric() || matches!(c, b'.' | b'_' | b'%' | b'+' | b'-');
    let domain = |c: u8| c.is_ascii_alphanumeric() || matches!(c, b'.' | b'-');
    let mut out = Vec::new();
    for (at, _) in text.match_indices('@') {
        let mut start = at;
        while start > 0 && local(b[start - 1]) {
            start -= 1;
        }
        while start < at && matches!(b[start], b'.' | b'-' | b'+') {
            start += 1;
        }
        let mut end = at + 1;
        while end < b.len() && domain(b[end]) {
            end += 1;
        }
        while end > at + 1 && matches!(b[end - 1], b'.' | b'-') {
            end -= 1;
        }
        let (user, host) = (&text[start..at], &text[at + 1..end]);
        let valid = !user.is_empty()
            && user.len() <= 64
            && !host.contains("..")
            && host.split('.').count() >= 2
            && host.split('.').all(|l| !l.is_empty())
            && host.rsplit('.').next().is_some_and(|tld| (2..=24).contains(&tld.len()) && tld.bytes().all(|c| c.is_ascii_alphabetic()));
        if valid {
            let value = format!("{user}@{}", host.to_ascii_lowercase());
            out.push(Found { kind: ItemKind::Email, start, end, text: None, value, label: None });
        }
    }
    out
}

// --- Dates -------------------------------------------------------------------

const MONTHS: &[(&str, u32)] = &[
    ("january", 1), ("jan", 1), ("february", 2), ("feb", 2), ("march", 3), ("mar", 3), ("april", 4), ("apr", 4), ("may", 5),
    ("june", 6), ("jun", 6), ("july", 7), ("jul", 7), ("august", 8), ("aug", 8), ("september", 9), ("sept", 9), ("sep", 9),
    ("october", 10), ("oct", 10), ("november", 11), ("nov", 11), ("december", 12), ("dec", 12),
];

fn days_in(year: u32, month: u32) -> u32 {
    match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if (year % 4 == 0 && year % 100 != 0) || year % 400 == 0 => 29,
        2 => 28,
        _ => 0,
    }
}

fn date(kind_start: usize, end: usize, y: u32, m: u32, d: u32) -> Option<Found> {
    ((1900..=2100).contains(&y) && (1..=12).contains(&m) && d >= 1 && d <= days_in(y, m)).then(|| Found {
        kind: ItemKind::Date,
        start: kind_start,
        end,
        text: None,
        value: format!("{y:04}-{m:02}-{d:02}"),
        label: None,
    })
}

struct NumericDate {
    start: usize,
    end: usize,
    a: u32,
    b: u32,
    year: u32,
}

fn dates(text: &str) -> Vec<Found> {
    let b = text.as_bytes();
    let mut out = Vec::new();
    // Day and month in either order: decided for the whole document below.
    let mut pending: Vec<NumericDate> = Vec::new();
    let mut i = 0;
    while i < b.len() {
        if !b[i].is_ascii_digit() || !number_start(b, i) {
            i += 1;
            continue;
        }
        let n1 = digits_at(b, i);
        if let Some(found) = numeric_date(b, i, n1) {
            match found {
                Ok(iso) => out.push(iso),
                Err(d) => pending.push(d),
            }
        }
        i += n1;
    }
    // Month-first only when the document itself shows it (a second part over 12).
    let month_first = pending.iter().filter(|d| d.b > 12).count() > pending.iter().filter(|d| d.a > 12).count();
    for d in pending {
        let (day, month) = if month_first { (d.b, d.a) } else { (d.a, d.b) };
        out.extend(date(d.start, d.end, d.year, month, day));
    }
    out.extend(written_dates(text));
    out.sort_by_key(|f| f.start);
    out
}

/// `2025-03-14`, or `14/03/2025`, `14.03.25`…; `Err` when day and month order is open.
fn numeric_date(b: &[u8], i: usize, n1: usize) -> Option<Result<Found, NumericDate>> {
    let sep = *b.get(i + n1)?;
    if !matches!(sep, b'/' | b'-' | b'.') {
        return None;
    }
    let j = i + n1 + 1;
    let n2 = digits_at(b, j);
    if !(1..=2).contains(&n2) || b.get(j + n2) != Some(&sep) {
        return None;
    }
    let k = j + n2 + 1;
    let n3 = digits_at(b, k);
    let end = k + n3;
    let after_ok = end == b.len()
        || (!b[end].is_ascii_alphanumeric() && !(matches!(b[end], b'.' | b'/' | b'-' | b':') && b.get(end + 1).is_some_and(u8::is_ascii_digit)));
    if !after_ok {
        return None;
    }
    if n1 == 4 && (1..=2).contains(&n3) {
        return date(i, end, parse(b, i, 4), parse(b, j, n2), parse(b, k, n3)).map(Ok);
    }
    if !(1..=2).contains(&n1) || !(n3 == 4 || (n3 == 2 && sep != b'.')) {
        return None;
    }
    let year = if n3 == 4 {
        parse(b, k, 4)
    } else {
        let yy = parse(b, k, 2);
        if yy < 70 { 2000 + yy } else { 1900 + yy }
    };
    Some(Err(NumericDate { start: i, end, a: parse(b, i, n1), b: parse(b, j, n2), year }))
}

/// `14 March 2025`, `14th Mar, 2025`, `14-Mar-2025`, `March 14, 2025`.
fn written_dates(text: &str) -> Vec<Found> {
    let b = text.as_bytes();
    let mut out = Vec::new();
    for (ws, we) in ascii_words(text) {
        let word = text[ws..we].to_ascii_lowercase();
        let Some(&(_, month)) = MONTHS.iter().find(|(name, _)| *name == word) else { continue };
        let after_month = if b.get(we) == Some(&b'.') { we + 1 } else { we };
        // Day before the month.
        let mut k = ws;
        let mut steps = 0;
        while k > 0 && matches!(b[k - 1], b' ' | b'-') && steps < 2 {
            k -= 1;
            steps += 1;
        }
        if steps > 0 && k >= 2 && is_ordinal_suffix(&b[k - 2..k]) {
            k -= 2;
        }
        let mut day_digits = 0;
        while day_digits < 2 && k > day_digits && b[k - day_digits - 1].is_ascii_digit() {
            day_digits += 1;
        }
        if steps > 0 && day_digits > 0 && number_start(b, k - day_digits) {
            let day = parse(b, k - day_digits, day_digits);
            if let Some(year_end) = year_after(b, after_month, b" ,.-") {
                out.extend(date(k - day_digits, year_end, parse(b, year_end - 4, 4), month, day));
                continue;
            }
        }
        // Day after the month.
        let mut p = after_month;
        let mut skipped = 0;
        while p < b.len() && matches!(b[p], b' ' | b'-') && skipped < 2 {
            p += 1;
            skipped += 1;
        }
        let n = digits_at(b, p);
        if skipped == 0 || !(1..=2).contains(&n) {
            continue;
        }
        let day = parse(b, p, n);
        let mut q = p + n;
        if b.get(q..q + 2).is_some_and(is_ordinal_suffix) {
            q += 2;
        }
        if let Some(year_end) = year_after(b, q, b" ,") {
            out.extend(date(ws, year_end, parse(b, year_end - 4, 4), month, day));
        }
    }
    out
}

fn is_ordinal_suffix(suffix: &[u8]) -> bool {
    [b"st", b"nd", b"rd", b"th"].iter().any(|ordinal| suffix.eq_ignore_ascii_case(*ordinal))
}

/// End of a four-digit year after one to three of `seps` from `from`.
fn year_after(b: &[u8], from: usize, seps: &[u8]) -> Option<usize> {
    let mut p = from;
    let mut skipped = 0;
    while p < b.len() && seps.contains(&b[p]) && skipped < 3 {
        p += 1;
        skipped += 1;
    }
    (skipped > 0 && digits_at(b, p) == 4 && b.get(p + 4).is_none_or(|c| !c.is_ascii_alphanumeric())).then_some(p + 4)
}

// --- Amounts -----------------------------------------------------------------

/// Currency marks, with the label shown for them. Letters need a word edge.
const CURRENCIES: &[(&str, &str)] = &[
    ("₹", "INR"), ("Rs.", "INR"), ("Rs", "INR"), ("INR", "INR"), ("US$", "USD"), ("USD", "USD"), ("$", "$"), ("€", "EUR"),
    ("EUR", "EUR"), ("£", "GBP"), ("GBP", "GBP"), ("¥", "¥"), ("JPY", "JPY"), ("AED", "AED"), ("SGD", "SGD"), ("AUD", "AUD"),
    ("CAD", "CAD"), ("CHF", "CHF"),
];

fn currency_before(text: &str, end: usize) -> Option<(usize, &'static str)> {
    CURRENCIES.iter().find_map(|&(mark, label)| {
        let start = end.checked_sub(mark.len())?;
        let found = text.get(start..end)?;
        let letters = mark.starts_with(|c: char| c.is_ascii_alphabetic());
        let same = if letters { found.eq_ignore_ascii_case(mark) } else { found == mark };
        (same && (!letters || word_start(text, start))).then_some((start, label))
    })
}

fn currency_after(text: &str, start: usize) -> Option<(usize, &'static str)> {
    CURRENCIES.iter().filter(|(mark, _)| *mark != "$" && *mark != "Rs").find_map(|&(mark, label)| {
        let found = text.get(start..start + mark.len())?;
        let letters = mark.starts_with(|c: char| c.is_ascii_alphabetic());
        (found == mark && (!letters || word_end(text, start + mark.len()))).then_some((start + mark.len(), label))
    })
}

fn amounts(text: &str) -> Vec<Found> {
    let b = text.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < b.len() {
        if !b[i].is_ascii_digit() || !(i == 0 || !b[i - 1].is_ascii_alphanumeric() && !matches!(b[i - 1], b'.' | b',')) {
            i += 1;
            continue;
        }
        let mut j = i;
        while j < b.len() && (b[j].is_ascii_digit() || (matches!(b[j], b',' | b'.') && b.get(j + 1).is_some_and(u8::is_ascii_digit))) {
            j += 1;
        }
        let Some(value) = plain_number(&text[i..j]) else {
            i = j;
            continue;
        };
        // Indian style "Rs. 500/-".
        let num_end = if text[j..].starts_with("/-") { j + 2 } else { j };
        let space_before = if text[..i].ends_with(' ') { 1 } else if text[..i].ends_with('\u{a0}') { 2 } else { 0 };
        let found = if let Some((start, label)) = currency_before(text, i - space_before) {
            word_end(text, num_end).then_some((start, num_end, label))
        } else {
            let gap = usize::from(text[num_end..].starts_with(' '));
            currency_after(text, num_end + gap).map(|(end, label)| (i, end, label))
        };
        if let Some((start, end, label)) = found {
            out.push(Found { kind: ItemKind::Amount, start, end, text: None, value, label: Some(label.to_owned()) });
        }
        i = j;
    }
    out
}

/// "1,23,456.50" → "123456.50"; "1.234,5" → "1234.5". The last separator is
/// the decimal point when one or two digits follow it.
fn plain_number(s: &str) -> Option<String> {
    let last_sep = s.rfind([',', '.']);
    let decimal = last_sep.filter(|&p| (1..=2).contains(&(s.len() - p - 1)));
    let mut out = String::with_capacity(s.len());
    for (i, c) in s.char_indices() {
        if c.is_ascii_digit() {
            out.push(c);
        } else if Some(i) == decimal {
            out.push('.');
        }
    }
    let digits = out.bytes().filter(u8::is_ascii_digit).count();
    (1..=15).contains(&digits).then_some(out)
}

// --- Reference numbers -------------------------------------------------------

/// The word that introduces a reference, what to call it, and whether it is
/// a reference on its own ("PNR 4XK2LM") or needs "no.", "ID" or a colon.
const REFERENCES: &[(&str, &str, bool)] = &[
    ("invoice", "Invoice number", false), ("order", "Order number", false), ("booking", "Booking ID", false),
    ("pnr", "PNR", true), ("transaction", "Transaction ID", false), ("txn", "Transaction ID", false), ("utr", "UTR", true),
    ("reference", "Reference", false), ("ref", "Reference", false), ("policy", "Policy number", false),
    ("ticket", "Ticket number", false), ("tracking", "Tracking number", false), ("awb", "Tracking number", true),
    ("receipt", "Receipt number", false), ("bill", "Bill number", false), ("customer", "Customer ID", false),
    ("consumer", "Consumer number", false), ("application", "Application number", false),
    ("registration", "Registration number", false), ("claim", "Claim number", false), ("case", "Case number", false),
    ("complaint", "Complaint number", false), ("folio", "Folio number", false), ("member", "Member ID", false),
    ("membership", "Member ID", false), ("confirmation", "Confirmation number", false), ("challan", "Challan number", false),
];

const NUMBER_WORDS: &[&str] = &["no", "number", "num", "nr", "id", "code"];

fn references(text: &str) -> Vec<Found> {
    let b = text.as_bytes();
    let mut out = Vec::new();
    for (ws, we) in ascii_words(text) {
        let word = text[ws..we].to_ascii_lowercase();
        let Some(&(_, label, alone)) = REFERENCES.iter().find(|(w, _, _)| *w == word) else { continue };
        let mut p = we;
        let mut marked = alone;
        for _ in 0..6 {
            while p < b.len() && matches!(b[p], b' ' | b'.' | b'-' | b':' | b'#' | b'(' | b')') {
                marked |= matches!(b[p], b':' | b'#');
                p += 1;
            }
            let n = b[p.min(b.len())..].iter().take_while(|c| c.is_ascii_alphabetic()).count();
            let next = text[p..p + n].to_ascii_lowercase();
            if n > 0 && word_end(text, p + n) && NUMBER_WORDS.contains(&next.as_str()) {
                marked = true;
                p += n;
            } else if n > 0 && word_end(text, p + n) && matches!(next.as_str(), "ref" | "reference") {
                p += n;
            } else {
                break;
            }
        }
        if !marked || p == we {
            continue;
        }
        let mut q = p;
        while q < b.len() && (b[q].is_ascii_alphanumeric() || matches!(b[q], b'/' | b'-' | b'_')) {
            q += 1;
        }
        while q > p && matches!(b[q - 1], b'/' | b'-' | b'_') {
            q -= 1;
        }
        let value = &text[p..q];
        let has_digit = value.bytes().any(|c| c.is_ascii_digit());
        let code_like = word == "pnr" && value.len() == 6 && value.bytes().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit());
        if (4..=30).contains(&value.len()) && word_end(text, q) && (has_digit || code_like) {
            out.push(Found {
                kind: ItemKind::Reference,
                start: ws,
                end: q,
                text: Some(value.to_owned()),
                value: value.to_owned(),
                label: Some(label.to_owned()),
            });
        }
    }
    out
}

// --- Phone numbers -----------------------------------------------------------

const PHONE_WORDS: &[&str] = &["phone", "telephone", "tel", "mobile", "mob", "call", "contact", "cell", "whatsapp", "ph", "helpline", "toll"];
const PHONE_CONTEXT_BYTES: usize = 24;

fn phones(text: &str) -> Vec<Found> {
    let b = text.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < b.len() {
        let plus = b[i] == b'+';
        let opens = b[i] == b'(';
        let starts = (b[i].is_ascii_digit() || plus || opens) && b.get(i + usize::from(plus || opens)).is_some_and(u8::is_ascii_digit);
        let edge = i == 0 || !(b[i - 1].is_ascii_alphanumeric() || matches!(b[i - 1], b'.' | b'/' | b'-' | b',' | b'+'));
        if !starts || !edge {
            i += 1;
            continue;
        }
        let mut j = i + usize::from(plus);
        let mut digits = String::new();
        let mut groups = Vec::new();
        let mut current = 0;
        let mut seps = 0;
        while j < b.len() {
            let c = b[j];
            if c.is_ascii_digit() {
                digits.push(c as char);
                current += 1;
                seps = 0;
            } else if matches!(c, b' ' | b'-' | b'.' | b'(' | b')') && seps < 2 {
                seps += 1;
                if current > 0 {
                    groups.push(current);
                    current = 0;
                }
            } else {
                break;
            }
            j += 1;
        }
        if current > 0 {
            groups.push(current);
        }
        let mut end = j;
        while end > i && !b[end - 1].is_ascii_digit() {
            end -= 1;
        }
        let n = digits.len();
        let dotted = text[i..end].contains('.');
        let first = digits.as_bytes().first().copied().unwrap_or(b'0');
        let mobile_start = |d: u8| (b'6'..=b'9').contains(&d);
        let accept = if !word_end(text, end) || n > 15 {
            false
        } else if plus {
            (8..=15).contains(&n)
        } else if has_phone_word(text, i) {
            (7..=13).contains(&n)
        } else {
            !dotted
                && ((n == 10 && mobile_start(first))
                    || (n == 11 && first == b'0' && mobile_start(digits.as_bytes()[1]))
                    || (n == 11 && digits.starts_with("1800"))
                    || (n == 12 && digits.starts_with("91") && mobile_start(digits.as_bytes()[2]) && groups != [4, 4, 4]))
        };
        if accept {
            let value = if plus { format!("+{digits}") } else { digits };
            out.push(Found { kind: ItemKind::Phone, start: i, end, text: None, value, label: None });
            i = end;
        } else {
            i = j.max(i + 1);
        }
    }
    out
}

fn has_phone_word(text: &str, at: usize) -> bool {
    let mut from = at.saturating_sub(PHONE_CONTEXT_BYTES);
    while !text.is_char_boundary(from) {
        from += 1;
    }
    // Only words since the last number or line: "Mob 98… Ref 12…" labels one number, not two.
    let window = &text[from..at];
    let window = window.rfind(|c: char| c.is_ascii_digit() || c == '\n').map_or(window, |p| &window[p + 1..]).to_ascii_lowercase();
    let w = window.as_bytes();
    PHONE_WORDS.iter().any(|word| {
        window.match_indices(word).any(|(p, _)| {
            let before = p == 0 || !w[p - 1].is_ascii_alphabetic();
            let after = w.get(p + word.len()).is_none_or(|c| !c.is_ascii_alphabetic());
            before && after
        })
    })
}

// --- Never shown -------------------------------------------------------------

fn looks_sensitive(f: &Found) -> bool {
    if !matches!(f.kind, ItemKind::Phone | ItemKind::Reference | ItemKind::Amount) {
        return false;
    }
    let digits: Vec<u8> = f.value.bytes().filter(u8::is_ascii_digit).map(|c| c - b'0').collect();
    let n = digits.len();
    if digits.len() * 10 < f.value.len() * 7 {
        return false; // mostly letters: not a card or ID number
    }
    ((13..=19).contains(&n) && luhn(&digits)) || (n == 12 && !f.value.starts_with('+') && digits[0] >= 2 && verhoeff(&digits))
}

fn luhn(digits: &[u8]) -> bool {
    let sum: u32 = digits
        .iter()
        .rev()
        .enumerate()
        .map(|(i, &d)| {
            let d = u32::from(d);
            if i % 2 == 1 { if d * 2 > 9 { d * 2 - 9 } else { d * 2 } } else { d }
        })
        .sum();
    sum % 10 == 0
}

fn verhoeff(digits: &[u8]) -> bool {
    const D: [[u8; 10]; 10] = [
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
    const P: [[u8; 10]; 8] = [
        [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
        [1, 5, 7, 6, 2, 8, 3, 0, 9, 4],
        [5, 8, 0, 3, 7, 9, 6, 1, 4, 2],
        [8, 9, 1, 6, 0, 4, 3, 5, 2, 7],
        [9, 4, 5, 3, 1, 2, 6, 8, 7, 0],
        [4, 2, 8, 6, 5, 7, 3, 9, 0, 1],
        [2, 7, 9, 3, 8, 0, 6, 4, 1, 5],
        [7, 0, 4, 6, 9, 1, 3, 2, 5, 8],
    ];
    let mut c = 0u8;
    for (i, &d) in digits.iter().rev().enumerate() {
        c = D[c as usize][P[i % 8][d as usize] as usize];
    }
    c == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    fn kinds(text: &str) -> Vec<(ItemKind, String)> {
        find(text).into_iter().map(|i| (i.kind, i.value)).collect()
    }

    #[test]
    fn dates_in_many_forms() {
        let v = kinds("Due 14/03/2025, issued 2025-03-01, travel on 5th April 2025 and March 7, 2026 or 9-Jan-2024.");
        let dates: Vec<&str> = v.iter().filter(|(k, _)| *k == ItemKind::Date).map(|(_, v)| v.as_str()).collect();
        assert_eq!(dates, vec!["2025-03-14", "2025-03-01", "2025-04-05", "2026-03-07", "2024-01-09"]);
    }

    #[test]
    fn month_after_multibyte_text_keeps_character_boundaries() {
        for prefix in ["é", "₹", "文", "📄"] {
            let text = format!("{prefix} March 14TH, 2025; 5th April 2025");
            let found = find(&text);
            let dates: Vec<&str> = found.iter().filter(|i| i.kind == ItemKind::Date).map(|i| i.value.as_str()).collect();
            assert_eq!(dates, vec!["2025-03-14", "2025-04-05"]);
            for item in found {
                assert!(text.is_char_boundary(item.start));
            }
        }
    }

    #[test]
    fn month_first_when_the_document_shows_it() {
        let v = kinds("Paid 12/25/2024 and 03/04/2025.");
        assert!(v.contains(&(ItemKind::Date, "2024-12-25".into())));
        assert!(v.contains(&(ItemKind::Date, "2025-03-04".into())));
    }

    #[test]
    fn invalid_dates_and_versions_are_skipped() {
        assert!(kinds("Version 1.2.3 and 31/02/2025 and 192.168.1.10").iter().all(|(k, _)| *k != ItemKind::Date));
    }

    #[test]
    fn amounts_with_currency() {
        let v = kinds("Total ₹1,23,456.50 paid. Fee Rs. 500/- and $19.99, plus 250 EUR.");
        let amounts: Vec<&str> = v.iter().filter(|(k, _)| *k == ItemKind::Amount).map(|(_, v)| v.as_str()).collect();
        assert_eq!(amounts, vec!["123456.50", "500", "19.99", "250"]);
    }

    #[test]
    fn phones_need_a_plus_a_label_or_a_mobile_shape() {
        let v = kinds("Call +91 98765 43210 or mobile: 022-2345 6789. Order 4 items. Mob 9123456789. Ref 1234567890.");
        let phones: Vec<&str> = v.iter().filter(|(k, _)| *k == ItemKind::Phone).map(|(_, v)| v.as_str()).collect();
        assert_eq!(phones, vec!["+919876543210", "02223456789", "9123456789"]);
    }

    #[test]
    fn emails_and_links() {
        let v = kinds("Write to Help@Example.co.in or see https://example.com/help). Also www.ezfiles.app.");
        assert!(v.contains(&(ItemKind::Email, "Help@example.co.in".into())));
        assert!(v.contains(&(ItemKind::Link, "https://example.com/help".into())));
        assert!(v.contains(&(ItemKind::Link, "https://www.ezfiles.app".into())));
    }

    #[test]
    fn references_are_not_phones() {
        let found = find("Order ID: 9876543210. Invoice No. INV-2025/0042. PNR 4XK2LM. Booking for 2 people.");
        let refs: Vec<(&str, Option<&str>)> =
            found.iter().filter(|i| i.kind == ItemKind::Reference).map(|i| (i.value.as_str(), i.label.as_deref())).collect();
        assert_eq!(
            refs,
            vec![("9876543210", Some("Order number")), ("INV-2025/0042", Some("Invoice number")), ("4XK2LM", Some("PNR"))]
        );
        assert!(found.iter().all(|i| i.kind != ItemKind::Phone));
    }

    #[test]
    fn card_and_aadhaar_numbers_are_never_listed() {
        // A Luhn-valid test card number and a Verhoeff-valid 12-digit number.
        let found = find("Ref no: 4111111111111111. Customer ID: 234123412346.");
        assert!(found.is_empty(), "{found:?}");
    }
}
