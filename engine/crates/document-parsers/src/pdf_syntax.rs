//! PDF object syntax: a bounded lexer and parser, and a lossless serializer for
//! the objects an incremental update has to write back.
//!
//! Everything here is untrusted input. Nesting depth and the number of values
//! per parse are capped, so memory stays proportional to a small multiple of
//! the buffer however it is crafted.

/// Containers nested deeper than this are refused.
const MAX_DEPTH: u32 = 32;
/// Values in one top-level parse. A real page has far fewer; a `/Kids` of this
/// size would already be an unusual tree.
const MAX_VALUES: u32 = 400_000;
const MAX_NUMBER_LEN: usize = 48;

#[derive(Debug, Clone, PartialEq)]
pub(crate) enum Obj {
    Null,
    Bool(bool),
    /// The source text, so values written back are unchanged to the digit.
    Num(String),
    /// Decoded (`#xx` resolved); escaped again when written.
    Name(Vec<u8>),
    Str(Vec<u8>),
    Array(Vec<Obj>),
    Dict(Dict),
    Ref(u32, u16),
}

impl Obj {
    pub(crate) fn as_f64(&self) -> Option<f64> {
        match self {
            Obj::Num(text) => text.parse::<f64>().ok().filter(|v| v.is_finite()),
            _ => None,
        }
    }

    pub(crate) fn as_i64(&self) -> Option<i64> {
        match self {
            Obj::Num(text) => text
                .parse::<i64>()
                .ok()
                .or_else(|| text.parse::<f64>().ok().filter(|v| v.is_finite()).map(|v| v as i64)),
            _ => None,
        }
    }

    pub(crate) fn as_name(&self) -> Option<&[u8]> {
        match self {
            Obj::Name(name) => Some(name),
            _ => None,
        }
    }

    pub(crate) fn number(value: f64) -> Obj {
        Obj::Num(format_number(value))
    }

    pub(crate) fn int(value: u64) -> Obj {
        Obj::Num(value.to_string())
    }

    pub(crate) fn name(value: &str) -> Obj {
        Obj::Name(value.as_bytes().to_vec())
    }
}

/// A dictionary, keeping source order so a rewritten object reads like the original.
#[derive(Debug, Clone, Default, PartialEq)]
pub(crate) struct Dict(pub(crate) Vec<(Vec<u8>, Obj)>);

impl Dict {
    pub(crate) fn get(&self, key: &str) -> Option<&Obj> {
        // A repeated key is invalid; the last one wins, as in most readers.
        self.0.iter().rev().find(|(k, _)| k == key.as_bytes()).map(|(_, v)| v)
    }

    pub(crate) fn set(&mut self, key: &str, value: Obj) {
        self.0.retain(|(k, _)| k != key.as_bytes());
        self.0.push((key.as_bytes().to_vec(), value));
    }

    pub(crate) fn remove(&mut self, key: &str) {
        self.0.retain(|(k, _)| k != key.as_bytes());
    }

    pub(crate) fn of(entries: Vec<(&str, Obj)>) -> Dict {
        Dict(entries.into_iter().map(|(k, v)| (k.as_bytes().to_vec(), v)).collect())
    }
}

/// Why a parse stopped.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum SyntaxError {
    /// The buffer ended inside a value; a larger window may complete it.
    Truncated,
    Invalid,
    /// Depth or value limit.
    TooLarge,
}

pub(crate) type SyntaxResult<T> = std::result::Result<T, SyntaxError>;

pub(crate) struct Lexer<'a> {
    pub(crate) buf: &'a [u8],
    pub(crate) pos: usize,
    values: u32,
}

pub(crate) fn is_space(byte: u8) -> bool {
    matches!(byte, 0 | 9 | 10 | 12 | 13 | 32)
}

fn is_delimiter(byte: u8) -> bool {
    matches!(byte, b'(' | b')' | b'<' | b'>' | b'[' | b']' | b'{' | b'}' | b'/' | b'%')
}

fn is_regular(byte: u8) -> bool {
    !is_space(byte) && !is_delimiter(byte)
}

fn hex_value(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}

impl<'a> Lexer<'a> {
    pub(crate) fn new(buf: &'a [u8], pos: usize) -> Self {
        Self { buf, pos, values: 0 }
    }

    pub(crate) fn skip_space(&mut self) {
        while let Some(&byte) = self.buf.get(self.pos) {
            if is_space(byte) {
                self.pos += 1;
            } else if byte == b'%' {
                while self.buf.get(self.pos).is_some_and(|b| *b != b'\n' && *b != b'\r') {
                    self.pos += 1;
                }
            } else {
                break;
            }
        }
    }

    /// Consumes `word` if it comes next, as a whole token.
    pub(crate) fn eat_keyword(&mut self, word: &[u8]) -> bool {
        self.skip_space();
        let end = self.pos + word.len();
        if self.buf.get(self.pos..end) == Some(word) && self.buf.get(end).is_none_or(|b| !is_regular(*b)) {
            self.pos = end;
            true
        } else {
            false
        }
    }

    /// A non-negative integer token; the position is unchanged if there is none.
    pub(crate) fn unsigned(&mut self) -> Option<u64> {
        self.skip_space();
        let start = self.pos;
        let mut value = 0u64;
        let mut at = start;
        while let Some(byte) = self.buf.get(at).filter(|b| b.is_ascii_digit()) {
            value = value.checked_mul(10)?.checked_add(u64::from(byte - b'0'))?;
            at += 1;
        }
        if at == start || self.buf.get(at).is_some_and(|b| is_regular(*b)) {
            return None;
        }
        self.pos = at;
        Some(value)
    }

    pub(crate) fn object(&mut self) -> SyntaxResult<Obj> {
        self.values = 0;
        self.value(0)
    }

    fn value(&mut self, depth: u32) -> SyntaxResult<Obj> {
        self.values += 1;
        if depth > MAX_DEPTH || self.values > MAX_VALUES {
            return Err(SyntaxError::TooLarge);
        }
        self.skip_space();
        let byte = *self.buf.get(self.pos).ok_or(SyntaxError::Truncated)?;
        match byte {
            b'/' => self.name_token().map(Obj::Name),
            b'(' => self.literal_string().map(Obj::Str),
            b'[' => self.array(depth),
            b'<' if self.buf.get(self.pos + 1) == Some(&b'<') => self.dict(depth).map(Obj::Dict),
            b'<' => self.hex_string().map(Obj::Str),
            b'+' | b'-' | b'.' | b'0'..=b'9' => self.number_or_ref(),
            _ => self.word(),
        }
    }

    fn word(&mut self) -> SyntaxResult<Obj> {
        if self.eat_keyword(b"null") {
            Ok(Obj::Null)
        } else if self.eat_keyword(b"true") {
            Ok(Obj::Bool(true))
        } else if self.eat_keyword(b"false") {
            Ok(Obj::Bool(false))
        } else if self.pos + 5 >= self.buf.len() {
            Err(SyntaxError::Truncated)
        } else {
            Err(SyntaxError::Invalid)
        }
    }

    fn number_or_ref(&mut self) -> SyntaxResult<Obj> {
        let start = self.pos;
        while self.buf.get(self.pos).is_some_and(|b| is_regular(*b)) {
            self.pos += 1;
        }
        if self.pos == self.buf.len() {
            return Err(SyntaxError::Truncated);
        }
        let token = &self.buf[start..self.pos];
        if token.len() > MAX_NUMBER_LEN || !token.iter().all(|b| b.is_ascii_digit() || matches!(b, b'+' | b'-' | b'.'))
        {
            return Err(SyntaxError::Invalid);
        }
        let text = std::str::from_utf8(token).map_err(|_| SyntaxError::Invalid)?;
        // "12 0 R" is a reference; anything else leaves the lookahead unread.
        if token.iter().all(u8::is_ascii_digit) {
            let after_number = self.pos;
            if let (Ok(number), Some(generation)) = (text.parse::<u32>(), self.unsigned()) {
                if self.eat_keyword(b"R") {
                    return u16::try_from(generation).map(|g| Obj::Ref(number, g)).map_err(|_| SyntaxError::Invalid);
                }
            }
            self.pos = after_number;
        }
        // A lone sign or dot is not a number, though some writers emit "-.".
        if text.parse::<f64>().is_err() {
            return Err(SyntaxError::Invalid);
        }
        Ok(Obj::Num(text.to_owned()))
    }

    fn name_token(&mut self) -> SyntaxResult<Vec<u8>> {
        self.pos += 1;
        let mut name = Vec::new();
        while let Some(&byte) = self.buf.get(self.pos) {
            if !is_regular(byte) {
                return Ok(name);
            }
            self.pos += 1;
            if byte == b'#' {
                let high = self.buf.get(self.pos).copied().and_then(hex_value);
                let low = self.buf.get(self.pos + 1).copied().and_then(hex_value);
                if let (Some(high), Some(low)) = (high, low) {
                    name.push(high << 4 | low);
                    self.pos += 2;
                    continue;
                }
            }
            name.push(byte);
        }
        Err(SyntaxError::Truncated)
    }

    fn literal_string(&mut self) -> SyntaxResult<Vec<u8>> {
        self.pos += 1;
        let mut out = Vec::new();
        let mut nesting = 1u32;
        loop {
            let byte = *self.buf.get(self.pos).ok_or(SyntaxError::Truncated)?;
            self.pos += 1;
            match byte {
                b'(' => {
                    nesting += 1;
                    out.push(byte);
                }
                b')' => {
                    nesting -= 1;
                    if nesting == 0 {
                        return Ok(out);
                    }
                    out.push(byte);
                }
                b'\\' => {
                    let escaped = *self.buf.get(self.pos).ok_or(SyntaxError::Truncated)?;
                    self.pos += 1;
                    match escaped {
                        b'n' => out.push(b'\n'),
                        b'r' => out.push(b'\r'),
                        b't' => out.push(b'\t'),
                        b'b' => out.push(8),
                        b'f' => out.push(12),
                        b'\r' => {
                            if self.buf.get(self.pos) == Some(&b'\n') {
                                self.pos += 1;
                            }
                        }
                        b'\n' => {}
                        b'0'..=b'7' => {
                            let mut code = u32::from(escaped - b'0');
                            for _ in 0..2 {
                                match self.buf.get(self.pos) {
                                    Some(digit @ b'0'..=b'7') => {
                                        code = code * 8 + u32::from(digit - b'0');
                                        self.pos += 1;
                                    }
                                    _ => break,
                                }
                            }
                            out.push(code as u8);
                        }
                        other => out.push(other),
                    }
                }
                other => out.push(other),
            }
        }
    }

    fn hex_string(&mut self) -> SyntaxResult<Vec<u8>> {
        self.pos += 1;
        let mut out = Vec::new();
        let mut pending: Option<u8> = None;
        loop {
            let byte = *self.buf.get(self.pos).ok_or(SyntaxError::Truncated)?;
            self.pos += 1;
            if byte == b'>' {
                if let Some(high) = pending {
                    out.push(high << 4);
                }
                return Ok(out);
            }
            if is_space(byte) {
                continue;
            }
            let digit = hex_value(byte).ok_or(SyntaxError::Invalid)?;
            match pending.take() {
                Some(high) => out.push(high << 4 | digit),
                None => pending = Some(digit),
            }
        }
    }

    fn array(&mut self, depth: u32) -> SyntaxResult<Obj> {
        self.pos += 1;
        let mut items = Vec::new();
        loop {
            self.skip_space();
            match self.buf.get(self.pos) {
                None => return Err(SyntaxError::Truncated),
                Some(b']') => {
                    self.pos += 1;
                    return Ok(Obj::Array(items));
                }
                Some(_) => items.push(self.value(depth + 1)?),
            }
        }
    }

    fn dict(&mut self, depth: u32) -> SyntaxResult<Dict> {
        self.pos += 2;
        let mut entries = Vec::new();
        loop {
            self.skip_space();
            match self.buf.get(self.pos) {
                None => return Err(SyntaxError::Truncated),
                Some(b'>') => {
                    return match self.buf.get(self.pos + 1) {
                        None => Err(SyntaxError::Truncated),
                        Some(b'>') => {
                            self.pos += 2;
                            Ok(Dict(entries))
                        }
                        Some(_) => Err(SyntaxError::Invalid),
                    };
                }
                Some(b'/') => {
                    let key = self.name_token()?;
                    let value = self.value(depth + 1)?;
                    entries.push((key, value));
                }
                Some(_) => return Err(SyntaxError::Invalid),
            }
        }
    }
}

/// Fixed-point text with no exponent or locale, as PDF requires.
pub(crate) fn format_number(value: f64) -> String {
    if !value.is_finite() {
        return "0".to_owned();
    }
    let mut text = format!("{value:.3}");
    if text.contains('.') {
        text.truncate(text.trim_end_matches('0').trim_end_matches('.').len());
    }
    if text == "-0" { "0".to_owned() } else { text }
}

pub(crate) fn write_obj(out: &mut Vec<u8>, obj: &Obj) {
    match obj {
        Obj::Null => out.extend_from_slice(b"null"),
        Obj::Bool(value) => out.extend_from_slice(if *value { b"true" } else { b"false" }),
        Obj::Num(text) => out.extend_from_slice(text.as_bytes()),
        Obj::Name(name) => write_name(out, name),
        Obj::Str(bytes) => write_string(out, bytes),
        Obj::Array(items) => {
            out.push(b'[');
            for (index, item) in items.iter().enumerate() {
                if index > 0 {
                    out.push(b' ');
                }
                write_obj(out, item);
            }
            out.push(b']');
        }
        Obj::Dict(dict) => write_dict(out, dict),
        Obj::Ref(number, generation) => out.extend_from_slice(format!("{number} {generation} R").as_bytes()),
    }
}

pub(crate) fn write_dict(out: &mut Vec<u8>, dict: &Dict) {
    out.extend_from_slice(b"<<");
    for (key, value) in &dict.0 {
        write_name(out, key);
        out.push(b' ');
        write_obj(out, value);
        out.push(b' ');
    }
    out.extend_from_slice(b">>");
}

fn write_name(out: &mut Vec<u8>, name: &[u8]) {
    out.push(b'/');
    for &byte in name {
        if byte == b'#' || !is_regular(byte) || !(0x21..=0x7E).contains(&byte) {
            out.extend_from_slice(format!("#{byte:02X}").as_bytes());
        } else {
            out.push(byte);
        }
    }
}

/// Hex when mostly binary (IDs, UTF-16), literal otherwise.
fn write_string(out: &mut Vec<u8>, bytes: &[u8]) {
    let printable = bytes.iter().all(|b| (0x20..=0x7E).contains(b));
    if printable {
        out.push(b'(');
        for &byte in bytes {
            if matches!(byte, b'(' | b')' | b'\\') {
                out.push(b'\\');
            }
            out.push(byte);
        }
        out.push(b')');
    } else {
        out.push(b'<');
        for &byte in bytes {
            out.extend_from_slice(format!("{byte:02X}").as_bytes());
        }
        out.push(b'>');
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(text: &[u8]) -> SyntaxResult<Obj> {
        Lexer::new(text, 0).object()
    }

    #[test]
    fn parses_every_value_kind() {
        let obj = parse(b"<</A 1/B -2.5/C(t\\(x\\)\\101)/D<4142 4>/E[1 0 R 7 true null]/N#41me /x#20y>> ").unwrap();
        let Obj::Dict(dict) = obj else { panic!("not a dict") };
        assert_eq!(dict.get("A").unwrap().as_i64(), Some(1));
        assert_eq!(dict.get("B").unwrap().as_f64(), Some(-2.5));
        assert_eq!(dict.get("C"), Some(&Obj::Str(b"t(x)A".to_vec())));
        assert_eq!(dict.get("D"), Some(&Obj::Str(vec![0x41, 0x42, 0x40])));
        let Some(Obj::Array(items)) = dict.get("E") else { panic!("not an array") };
        assert_eq!(items[0], Obj::Ref(1, 0));
        assert_eq!(items[1], Obj::Num("7".into()));
        assert_eq!(items[2], Obj::Bool(true));
        assert_eq!(dict.get("NAme").unwrap().as_name(), Some(&b"x y"[..]));
    }

    #[test]
    fn round_trips_through_the_writer() {
        let source: &[u8] = b"<</Type /Page /Odd#20Name 1.50 /S (a\\)b) /H <00FF> /L [1 0 R [2] <</X null >> ] >> ";
        let first = parse(source).unwrap();
        let mut out = Vec::new();
        write_obj(&mut out, &first);
        out.push(b' ');
        assert_eq!(parse(&out).unwrap(), first);
        // Numbers keep their exact source text.
        assert!(out.windows(4).any(|w| w == b"1.50"));
    }

    #[test]
    fn refuses_excess_nesting_and_values() {
        let deep = vec![b'['; 5000];
        assert_eq!(parse(&deep), Err(SyntaxError::TooLarge));
        let mut many = vec![b'['];
        many.extend(std::iter::repeat_n(b"1 ".as_slice(), 500_000).flatten());
        many.push(b']');
        assert_eq!(parse(&many), Err(SyntaxError::TooLarge));
    }

    #[test]
    fn distinguishes_truncation_from_garbage() {
        assert_eq!(parse(b"<</A (never closed"), Err(SyntaxError::Truncated));
        assert_eq!(parse(b"<</A 1"), Err(SyntaxError::Truncated));
        assert_eq!(parse(b"<</A @@@@@@@@ >> "), Err(SyntaxError::Invalid));
        assert_eq!(parse(b"<<1 2>> "), Err(SyntaxError::Invalid));
    }

    #[test]
    fn formats_numbers_without_exponents() {
        assert_eq!(format_number(1.5), "1.5");
        assert_eq!(format_number(100.0), "100");
        assert_eq!(format_number(-0.0001), "0");
        assert_eq!(format_number(0.1234), "0.123");
        assert_eq!(format_number(f64::NAN), "0");
    }
}
