//! Spreadsheet number-format codes (`0.00`, `m/d/yyyy`, `#,##0_);(#,##0)`).
//!
//! A small interpreter for the codes in `xl/styles.xml`, in en-US conventions:
//! a code's own literals are kept and nothing is localised.
//!
//! Supported: up to four `;` sections with `[<100]`-style conditions, digit
//! placeholders (`0 # ?`), thousands separators and scaling commas, percent,
//! scientific, fractions, `General`, text `@`, literals (`"x"`, `\x`, `[$€-407]`),
//! dates and times including `[h]` elapsed time and the Excel 1900 leap-year
//! bug. Colours are ignored, as are the `_x` and `*x` alignment paddings, which
//! leave a blank or nothing.
//!
//! Not supported: non-Gregorian calendars and native digits (`[$-1010409]`,
//! `[DBNum1]`), and era codes (`e`, `g`, `b`). [`NumFmt::parse`] rejects those
//! and anything malformed. Formatting returns `None` when a value cannot be
//! shown (a date out of range, an integer part beyond [`MAX_INTEGER_DIGITS`]).
//! In every such case the caller keeps the plain cached value.
//!
//! Safety: code length is capped by [`MAX_CODE_BYTES`], so tokens, padding and
//! literals are bounded by it, and text output by [`MAX_TEXT_OUTPUT`].

/// Excel itself stops at 255 characters.
const MAX_CODE_BYTES: usize = 1024;
/// Excel itself stops at 30.
const MAX_DECIMALS: usize = 30;
/// A longer integer part is noise, not a number anyone reads.
const MAX_INTEGER_DIGITS: i32 = 64;
const MAX_SUBSECOND_DIGITS: usize = 3;
/// Denominators are searched exhaustively, so their size stays small.
const MAX_DENOMINATOR_DIGITS: usize = 3;
/// Doubles carry no more than this; Excel displays no more either.
const SIGNIFICANT_DIGITS: usize = 15;
/// `@@@@` must not multiply a long shared string.
const MAX_TEXT_OUTPUT: usize = 64 * 1024;
/// 9999-12-31 in each date system.
const MAX_SERIAL_1900: f64 = 2_958_465.0;
const MAX_SERIAL_1904: f64 = 2_957_003.0;
/// Days from 1970-01-01 back to each system's zero, past the phantom 1900-02-29.
const EPOCH_1900: i64 = 25_569;
const EPOCH_1904: i64 = 24_107;

const MONTHS: [&str; 12] = [
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December",
];
const WEEKDAYS: [&str; 7] = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];

/// Code of a built-in format id. Ids 23-36 are reserved for East Asian locales
/// and have no fixed meaning.
pub(crate) fn builtin(id: u32) -> Option<&'static str> {
    Some(match id {
        0 => "General",
        1 => "0",
        2 => "0.00",
        3 => "#,##0",
        4 => "#,##0.00",
        5 => r##""$"#,##0_);\("$"#,##0\)"##,
        6 => r##""$"#,##0_);[Red]\("$"#,##0\)"##,
        7 => r##""$"#,##0.00_);\("$"#,##0.00\)"##,
        8 => r##""$"#,##0.00_);[Red]\("$"#,##0.00\)"##,
        9 => "0%",
        10 => "0.00%",
        11 => "0.00E+00",
        12 => "# ?/?",
        13 => "# ??/??",
        14 => "m/d/yyyy",
        15 => "d-mmm-yy",
        16 => "d-mmm",
        17 => "mmm-yy",
        18 => "h:mm AM/PM",
        19 => "h:mm:ss AM/PM",
        20 => "h:mm",
        21 => "h:mm:ss",
        22 => "m/d/yyyy h:mm",
        37 => r"#,##0_);\(#,##0\)",
        38 => r"#,##0_);[Red]\(#,##0\)",
        39 => r"#,##0.00_);\(#,##0.00\)",
        40 => r"#,##0.00_);[Red]\(#,##0.00\)",
        41 => r#"_(* #,##0_);_(* \(#,##0\);_(* "-"_);_(@_)"#,
        42 => r#"_("$"* #,##0_);_("$"* \(#,##0\);_("$"* "-"_);_(@_)"#,
        43 => r#"_(* #,##0.00_);_(* \(#,##0.00\);_(* "-"??_);_(@_)"#,
        44 => r#"_("$"* #,##0.00_);_("$"* \(#,##0.00\);_("$"* "-"??_);_(@_)"#,
        45 => "mm:ss",
        46 => "[h]:mm:ss",
        47 => "mm:ss.0",
        48 => "##0.0E+0",
        49 => "@",
        _ => return None,
    })
}

/// A number the way General shows it: no binary-float noise. `None` when that
/// would misstate it (beyond 15 digits, or too small for ten decimals).
pub(crate) fn general(value: f64) -> Option<String> {
    let abs = value.abs();
    if !value.is_finite() || (value != 0.0 && !(1e-9..1e15).contains(&abs)) {
        return None;
    }
    if value.fract() == 0.0 {
        return Some((value as i64).to_string());
    }
    // Trimmed in place: one allocation per number, which adds up on large sheets.
    let mut fixed = format!("{value:.10}");
    let kept = fixed.trim_end_matches('0').trim_end_matches('.').len();
    fixed.truncate(kept);
    Some(fixed)
}

/// A parsed format code.
pub(crate) struct NumFmt {
    /// One to three sections for numbers. Empty for a code that is only a text section.
    numeric: Vec<Section>,
    text: Option<Section>,
}

struct Section {
    tokens: Vec<Token>,
    condition: Option<(Cmp, f64)>,
    shape: Shape,
}

#[derive(Clone, Copy, PartialEq)]
enum Place {
    /// `0`: a digit, or a zero.
    Zero,
    /// `#`: a digit, or nothing.
    Hash,
    /// `?`: a digit, or a blank.
    Question,
}

#[derive(Clone, Copy, PartialEq)]
enum Field {
    Year,
    /// Also a minute until `resolve_minutes` has looked at its neighbours.
    Month,
    Minute,
    Day,
    Hour,
    Second,
    ElapsedHours,
    ElapsedMinutes,
    ElapsedSeconds,
    AmPm(&'static str, &'static str),
    Subsecond,
}

enum Token {
    Text(String),
    /// `_x`: a blank as wide as `x`.
    Pad,
    Digit(Place),
    Point,
    Comma,
    Percent,
    /// `/`, with the denominator when the code fixes one (`?/8`).
    Slash(Option<u32>),
    Exponent {
        plus: bool,
        width: usize,
    },
    General,
    At,
    Field(Field, usize),
}

#[derive(Clone, Copy)]
enum Cmp {
    Lt,
    Le,
    Gt,
    Ge,
    Eq,
    Ne,
}

impl Cmp {
    fn holds(self, a: f64, b: f64) -> bool {
        match self {
            Cmp::Lt => a < b,
            Cmp::Le => a <= b,
            Cmp::Gt => a > b,
            Cmp::Ge => a >= b,
            Cmp::Eq => a == b,
            Cmp::Ne => a != b,
        }
    }
}

enum Shape {
    /// Literals, with at most `General` or `@` standing for the value.
    Plain,
    Number {
        int_places: usize,
        decimals: usize,
        group: bool,
        /// Decimal shift from percent signs and scaling commas.
        shift: i32,
        scientific: bool,
        engineering: bool,
    },
    Fraction {
        whole: usize,
        numerator: usize,
        denominator: usize,
        group: bool,
    },
    Date {
        subsecond: usize,
        twelve_hour: bool,
    },
}

impl NumFmt {
    /// `None` when the code is malformed, too long or uses an unsupported feature.
    pub fn parse(code: &str) -> Option<Self> {
        if code.is_empty() || code.len() > MAX_CODE_BYTES {
            return None;
        }
        let mut sections = tokenize(code)?;
        if sections.len() > 4 {
            return None;
        }
        // The fourth section is for text, and so is a last one that uses `@`.
        let last_is_text = sections.len() == 4 || sections.last().is_some_and(|(tokens, _)| tokens.iter().any(is_at));
        let text = match if last_is_text { sections.pop() } else { None } {
            Some((tokens, condition)) => Some(finish_section(tokens, condition)?),
            None => None,
        };
        let numeric =
            sections.into_iter().map(|(tokens, condition)| finish_section(tokens, condition)).collect::<Option<_>>()?;
        Some(Self { numeric, text })
    }

    /// `value` as this format shows it, or `None` to keep the plain value.
    pub fn format_number(&self, value: f64, date1904: bool) -> Option<String> {
        if !value.is_finite() {
            return None;
        }
        let (section, unsigned) = self.choose(value);
        let shown = if unsigned { value.abs() } else { value };
        let abs = value.abs();
        let body = match &section.shape {
            Shape::Date { subsecond, twelve_hour } => {
                render_date(&section.tokens, shown, date1904, *subsecond, *twelve_hour)?
            }
            Shape::Plain => {
                let uses_value = section.tokens.iter().any(|t| matches!(t, Token::General | Token::At));
                let value = if uses_value { general(abs)? } else { String::new() };
                render_plain(&section.tokens, &value)
            }
            Shape::Number { .. } => render_number(section, abs)?,
            Shape::Fraction { .. } => render_fraction(section, abs)?,
        };
        let body = body.trim_matches(' ');
        Some(if shown < 0.0 { format!("-{body}") } else { body.to_owned() })
    }

    /// `text` as the text section shows it, or `None` when the code has no
    /// usable text section. `General` there stands for the text, like `@`.
    pub fn format_text(&self, text: &str) -> Option<String> {
        let section = self.text.as_ref().filter(|s| matches!(s.shape, Shape::Plain))?;
        let (mut size, mut uses) = (0usize, 0usize);
        for token in &section.tokens {
            match token {
                Token::Text(s) => size += s.len(),
                Token::At | Token::General => uses += 1,
                _ => {}
            }
        }
        if text.len().saturating_mul(uses).saturating_add(size) > MAX_TEXT_OUTPUT {
            return None;
        }
        let mut out = String::with_capacity(size + text.len() * uses);
        for token in &section.tokens {
            match token {
                Token::Text(s) => out.push_str(s),
                Token::At | Token::General => out.push_str(text),
                _ => {}
            }
        }
        Some(out)
    }

    /// The section for `value`, and whether that section drops the sign because
    /// it writes its own (`0;(0)`).
    fn choose(&self, value: f64) -> (&Section, bool) {
        let count = self.numeric.len();
        let Some(first) = self.numeric.first() else {
            // Only `@`: numbers pass through it as General.
            return (self.text.as_ref().expect("a format code has at least one section"), false);
        };
        let implicit_first = first.condition.is_none();
        let first_holds = match first.condition {
            Some((cmp, bound)) => cmp.holds(value, bound),
            None => match count {
                1 => true,
                2 => value >= 0.0,
                _ => value > 0.0,
            },
        };
        if first_holds {
            return (first, excludes_positive(first));
        }
        let Some(second) = self.numeric.get(1) else { return (first, false) };
        let second_holds = match second.condition {
            Some((cmp, bound)) => cmp.holds(value, bound),
            None => count == 2 || value < 0.0,
        };
        if second_holds {
            let implicit_negative = second.condition.is_none() && (count == 3 || implicit_first);
            return (second, implicit_negative || excludes_positive(second));
        }
        // `count >= 2` here: with two sections and a false condition this is the second.
        let last = self.numeric.last().unwrap_or(second);
        (last, excludes_positive(last))
    }
}

fn is_at(token: &Token) -> bool {
    matches!(token, Token::At)
}

/// `[<0]` and the like only see negative numbers, so they write their own sign.
fn excludes_positive(section: &Section) -> bool {
    matches!(section.condition, Some((Cmp::Lt | Cmp::Le, bound)) if bound <= 0.0)
}

type RawSection = (Vec<Token>, Option<(Cmp, f64)>);

/// Splits a code into its sections' tokens. Literal text and `[...]` may hold `;`.
fn tokenize(code: &str) -> Option<Vec<RawSection>> {
    let mut sections = Vec::new();
    let mut tokens: Vec<Token> = Vec::new();
    let mut condition = None;
    let mut rest = code;
    while let Some(c) = next_char(&mut rest) {
        match c {
            ';' => sections.push((std::mem::take(&mut tokens), condition.take())),
            '"' => {
                let (text, after) = rest.split_once('"')?;
                push_text(&mut tokens, text);
                rest = after;
            }
            '\\' => push_text(&mut tokens, next_char(&mut rest)?.encode_utf8(&mut [0; 4])),
            '_' => {
                next_char(&mut rest);
                tokens.push(Token::Pad);
            }
            '*' => {
                next_char(&mut rest);
            }
            '[' => {
                let (inner, after) = rest.split_once(']')?;
                rest = after;
                bracket(inner, &mut tokens, &mut condition)?;
            }
            '0' => tokens.push(Token::Digit(Place::Zero)),
            '#' => tokens.push(Token::Digit(Place::Hash)),
            '?' => tokens.push(Token::Digit(Place::Question)),
            '.' => tokens.push(Token::Point),
            ',' => tokens.push(Token::Comma),
            '%' => tokens.push(Token::Percent),
            '@' => tokens.push(Token::At),
            '/' => {
                let digits = rest.find(|d: char| !d.is_ascii_digit()).unwrap_or(rest.len());
                let (fixed, after) = rest.split_at(digits);
                if fixed.is_empty() || fixed.starts_with('0') {
                    tokens.push(Token::Slash(None));
                } else {
                    tokens.push(Token::Slash(Some(fixed.parse().ok()?)));
                    rest = after;
                }
            }
            'e' | 'E' => {
                let plus = match next_char(&mut rest)? {
                    '+' => true,
                    '-' => false,
                    _ => return None,
                };
                let places = rest.find(|d: char| !matches!(d, '0' | '#' | '?')).unwrap_or(rest.len());
                let (digits, after) = rest.split_at(places);
                if digits.is_empty() {
                    return None;
                }
                let width = digits.bytes().filter(|b| *b == b'0').count();
                tokens.push(Token::Exponent { plus, width });
                rest = after;
            }
            'g' | 'G' => {
                rest = strip_ignoring_case(rest, "eneral")?;
                tokens.push(Token::General);
            }
            'y' | 'Y' => tokens.push(Token::Field(Field::Year, run(&mut rest, 'y'))),
            'm' | 'M' => tokens.push(Token::Field(Field::Month, run(&mut rest, 'm'))),
            'd' | 'D' => tokens.push(Token::Field(Field::Day, run(&mut rest, 'd'))),
            'h' | 'H' => tokens.push(Token::Field(Field::Hour, run(&mut rest, 'h'))),
            's' | 'S' => tokens.push(Token::Field(Field::Second, run(&mut rest, 's'))),
            'a' | 'A' => {
                if let Some(after) = strip_ignoring_case(rest, "m/pm") {
                    rest = after;
                    tokens.push(Token::Field(Field::AmPm("AM", "PM"), 1));
                } else {
                    rest = strip_ignoring_case(rest, "/p")?;
                    let pair = if c == 'a' { ("a", "p") } else { ("A", "P") };
                    tokens.push(Token::Field(Field::AmPm(pair.0, pair.1), 1));
                }
            }
            // Characters Excel shows without quotes, digits that are not
            // placeholders, and symbols such as currency signs.
            '$'
            | '-'
            | '+'
            | '('
            | ')'
            | ':'
            | '!'
            | '^'
            | '&'
            | '\''
            | '~'
            | '{'
            | '}'
            | '<'
            | '>'
            | '='
            | ' '
            | '1'..='9' => push_text(&mut tokens, c.encode_utf8(&mut [0; 4])),
            c if !c.is_ascii() => push_text(&mut tokens, c.encode_utf8(&mut [0; 4])),
            _ => return None,
        }
    }
    sections.push((tokens, condition));
    Some(sections)
}

fn next_char(rest: &mut &str) -> Option<char> {
    let c = rest.chars().next()?;
    *rest = &rest[c.len_utf8()..];
    Some(c)
}

fn strip_ignoring_case<'a>(rest: &'a str, prefix: &str) -> Option<&'a str> {
    let head = rest.get(..prefix.len())?;
    head.eq_ignore_ascii_case(prefix).then(|| &rest[prefix.len()..])
}

/// Length of a run of `letter`, the first of which was already consumed.
fn run(rest: &mut &str, letter: char) -> usize {
    let more = rest.find(|c: char| !c.eq_ignore_ascii_case(&letter)).unwrap_or(rest.len());
    *rest = &rest[more..];
    more + 1
}

fn push_text(tokens: &mut Vec<Token>, text: &str) {
    if let Some(Token::Text(last)) = tokens.last_mut() {
        last.push_str(text);
    } else {
        tokens.push(Token::Text(text.to_owned()));
    }
}

/// Handles what `[...]` holds: a colour, condition, currency or elapsed time.
fn bracket(inner: &str, tokens: &mut Vec<Token>, condition: &mut Option<(Cmp, f64)>) -> Option<()> {
    if let Some(locale) = inner.strip_prefix('$') {
        let (symbol, lcid) = locale.split_once('-').unwrap_or((locale, ""));
        // The high bytes of an LCID select a calendar or digit set; we have neither.
        if u32::from_str_radix(lcid, 16).is_ok_and(|id| id > 0xFFFF) {
            return None;
        }
        if !symbol.is_empty() {
            push_text(tokens, symbol);
        }
        return Some(());
    }
    if let Some((cmp, bound)) = parse_condition(inner) {
        *condition = Some((cmp, bound));
        return Some(());
    }
    let lower = inner.to_ascii_lowercase();
    let same = |letter: char| !lower.is_empty() && lower.chars().all(|c| c == letter);
    if same('h') {
        tokens.push(Token::Field(Field::ElapsedHours, lower.len()));
    } else if same('m') {
        tokens.push(Token::Field(Field::ElapsedMinutes, lower.len()));
    } else if same('s') {
        tokens.push(Token::Field(Field::ElapsedSeconds, lower.len()));
    } else {
        let colour =
            matches!(lower.as_str(), "black" | "blue" | "cyan" | "green" | "magenta" | "red" | "white" | "yellow");
        let indexed = lower.strip_prefix("color").is_some_and(|n| n.trim().parse::<u32>().is_ok());
        if !colour && !indexed {
            return None;
        }
    }
    Some(())
}

fn parse_condition(inner: &str) -> Option<(Cmp, f64)> {
    let (cmp, bound) = if let Some(b) = inner.strip_prefix("<=") {
        (Cmp::Le, b)
    } else if let Some(b) = inner.strip_prefix(">=") {
        (Cmp::Ge, b)
    } else if let Some(b) = inner.strip_prefix("<>") {
        (Cmp::Ne, b)
    } else if let Some(b) = inner.strip_prefix('<') {
        (Cmp::Lt, b)
    } else if let Some(b) = inner.strip_prefix('>') {
        (Cmp::Gt, b)
    } else {
        (Cmp::Eq, inner.strip_prefix('=')?)
    };
    let bound: f64 = bound.trim().parse().ok()?;
    bound.is_finite().then_some((cmp, bound))
}

/// Decides what a section's tokens mean and normalises them for rendering.
fn finish_section(tokens: Vec<Token>, condition: Option<(Cmp, f64)>) -> Option<Section> {
    let (tokens, shape) = if tokens.iter().any(|t| matches!(t, Token::Field(..))) {
        finish_date(tokens)?
    } else if tokens.iter().any(|t| matches!(t, Token::Digit(_))) {
        finish_number(tokens)?
    } else {
        (finish_plain(tokens)?, Shape::Plain)
    };
    Some(Section { tokens, condition, shape })
}

fn finish_plain(tokens: Vec<Token>) -> Option<Vec<Token>> {
    let mut out = Vec::with_capacity(tokens.len());
    let mut values = 0;
    for token in tokens {
        match token {
            Token::Point => push_text(&mut out, "."),
            Token::Comma => push_text(&mut out, ","),
            Token::Percent => push_text(&mut out, "%"),
            Token::Slash(None) => push_text(&mut out, "/"),
            Token::Text(text) => push_text(&mut out, &text),
            Token::Pad => out.push(Token::Pad),
            Token::General | Token::At => {
                values += 1;
                out.push(token);
            }
            _ => return None,
        }
    }
    // `General` written twice is not something a number format means.
    if values > 1 && out.iter().any(|t| matches!(t, Token::General)) {
        return None;
    }
    Some(out)
}

fn finish_date(tokens: Vec<Token>) -> Option<(Vec<Token>, Shape)> {
    let mut out: Vec<Token> = Vec::with_capacity(tokens.len());
    let mut subsecond = 0;
    let mut twelve_hour = false;
    let mut tokens = tokens.into_iter().peekable();
    while let Some(token) = tokens.next() {
        match token {
            Token::Point => {
                let mut digits = 0;
                while matches!(tokens.peek(), Some(Token::Digit(Place::Zero))) {
                    tokens.next();
                    digits += 1;
                }
                let after_seconds = matches!(
                    out.iter().rev().find(|t| matches!(t, Token::Field(..))),
                    Some(Token::Field(Field::Second | Field::ElapsedSeconds, _))
                );
                if digits == 0 {
                    push_text(&mut out, ".");
                } else if !after_seconds || digits > MAX_SUBSECOND_DIGITS || subsecond > 0 {
                    return None;
                } else {
                    subsecond = digits;
                    out.push(Token::Field(Field::Subsecond, digits));
                }
            }
            Token::Comma => push_text(&mut out, ","),
            Token::Slash(None) => push_text(&mut out, "/"),
            Token::Text(text) => push_text(&mut out, &text),
            Token::Pad => out.push(Token::Pad),
            Token::Field(Field::AmPm(am, pm), n) => {
                twelve_hour = true;
                out.push(Token::Field(Field::AmPm(am, pm), n));
            }
            Token::Field(field, n) => out.push(Token::Field(field, n)),
            _ => return None,
        }
    }
    resolve_minutes(&mut out);
    Some((out, Shape::Date { subsecond, twelve_hour }))
}

/// `m` and `mm` are minutes right after hours or right before seconds.
fn resolve_minutes(tokens: &mut [Token]) {
    let fields: Vec<usize> = tokens
        .iter()
        .enumerate()
        .filter(|(_, t)| matches!(t, Token::Field(f, _) if !matches!(f, Field::AmPm(..) | Field::Subsecond)))
        .map(|(i, _)| i)
        .collect();
    let field_at = |tokens: &[Token], i: usize| match tokens[i] {
        Token::Field(field, _) => Some(field),
        _ => None,
    };
    for (position, &index) in fields.iter().enumerate() {
        let Token::Field(Field::Month, count) = tokens[index] else { continue };
        if count > 2 {
            continue;
        }
        let after_hour = position
            .checked_sub(1)
            .and_then(|p| field_at(tokens, fields[p]))
            .is_some_and(|f| matches!(f, Field::Hour | Field::ElapsedHours));
        let before_second = fields
            .get(position + 1)
            .and_then(|&next| field_at(tokens, next))
            .is_some_and(|f| matches!(f, Field::Second | Field::ElapsedSeconds));
        if after_hour || before_second {
            tokens[index] = Token::Field(Field::Minute, count);
        }
    }
}

fn finish_number(tokens: Vec<Token>) -> Option<(Vec<Token>, Shape)> {
    let mut out: Vec<Token> = Vec::with_capacity(tokens.len());
    let (mut int_places, mut decimals, mut shift) = (0usize, 0usize, 0i32);
    let (mut group, mut seen_point, mut seen_exponent, mut seen_slash) = (false, false, false, false);
    let (mut engineering, mut percent) = (false, false);
    for (i, token) in tokens.iter().enumerate() {
        match token {
            Token::Digit(place) => {
                if seen_exponent {
                    return None;
                }
                if seen_point {
                    decimals += 1;
                } else {
                    int_places += 1;
                    engineering |= *place == Place::Hash;
                }
                out.push(Token::Digit(*place));
            }
            Token::Point if seen_exponent => return None,
            Token::Point if seen_point => push_text(&mut out, "."),
            Token::Point => {
                seen_point = true;
                out.push(Token::Point);
            }
            Token::Comma => {
                let next = tokens[i + 1..].iter().find(|t| !matches!(t, Token::Comma));
                let run_start = tokens[..i].iter().rposition(|t| !matches!(t, Token::Comma));
                let after_digit = run_start.is_some_and(|p| matches!(tokens[p], Token::Digit(_)));
                if !after_digit {
                    push_text(&mut out, ",");
                } else if matches!(next, Some(Token::Digit(_))) {
                    group |= !seen_point;
                } else {
                    shift -= 3;
                }
            }
            Token::Percent => {
                percent = true;
                shift += 2;
                out.push(Token::Percent);
            }
            Token::Exponent { plus, width } => {
                if seen_exponent {
                    return None;
                }
                seen_exponent = true;
                out.push(Token::Exponent { plus: *plus, width: *width });
            }
            Token::Slash(fixed) => {
                if seen_slash {
                    return None;
                }
                seen_slash = true;
                out.push(Token::Slash(*fixed));
            }
            Token::Text(text) => push_text(&mut out, text),
            Token::Pad => out.push(Token::Pad),
            Token::General | Token::At | Token::Field(..) => return None,
        }
    }
    if decimals > MAX_DECIMALS {
        return None;
    }
    if seen_slash {
        if seen_point || seen_exponent || percent || shift != 0 {
            return None;
        }
        let shape = fraction_shape(&out, group)?;
        return Some((out, shape));
    }
    let engineering = engineering && int_places > 1;
    let scientific = seen_exponent;
    Some((out, Shape::Number { int_places, decimals, group, shift, scientific, engineering }))
}

/// Placeholders right before the slash are the numerator, earlier ones the
/// whole part and later ones the denominator.
fn fraction_shape(tokens: &[Token], group: bool) -> Option<Shape> {
    let slash = tokens.iter().position(|t| matches!(t, Token::Slash(_)))?;
    let numerator = tokens[..slash].iter().rev().take_while(|t| matches!(t, Token::Digit(_))).count();
    let whole = tokens[..slash - numerator].iter().filter(|t| matches!(t, Token::Digit(_))).count();
    let denominator = tokens[slash + 1..].iter().take_while(|t| matches!(t, Token::Digit(_))).count();
    let later = tokens[slash + 1..].iter().filter(|t| matches!(t, Token::Digit(_))).count();
    let fixed = matches!(tokens[slash], Token::Slash(Some(_)));
    if numerator == 0 || later != denominator || fixed != (denominator == 0) || denominator > MAX_DENOMINATOR_DIGITS {
        return None;
    }
    Some(Shape::Fraction { whole, numerator, denominator, group })
}

/// A non-negative number as up to 15 significant decimal digits, which is what
/// Excel rounds from. Rounding the binary value instead would show 1.005 as
/// 1.00 under `0.00`, and invent digits past the 15th in large numbers.
struct Decimal {
    /// d.ddd × 10^exponent. Empty for zero.
    digits: Vec<u8>,
    exponent: i32,
}

impl Decimal {
    fn of(abs: f64) -> Self {
        if abs == 0.0 {
            return Self { digits: Vec::new(), exponent: 0 };
        }
        let text = format!("{:.*e}", SIGNIFICANT_DIGITS - 1, abs);
        let (mantissa, exponent) = text.split_once('e').unwrap_or((&text, "0"));
        let digits = mantissa.bytes().filter(u8::is_ascii_digit).map(|b| b - b'0').collect();
        Self { digits, exponent: exponent.parse().unwrap_or(0) }
    }

    /// Integer and fraction digits of this number × 10^shift, rounded half up to
    /// `decimals`. The integer part has no leading zeros, so it is empty below
    /// one. `None` when it would exceed [`MAX_INTEGER_DIGITS`].
    fn fixed(&self, shift: i32, decimals: usize) -> Option<(String, String)> {
        let mut point = self.exponent + shift + 1;
        let keep = point + decimals as i32;
        if self.digits.is_empty() || keep < 0 {
            return Some((String::new(), "0".repeat(decimals)));
        }
        if point > MAX_INTEGER_DIGITS {
            return None;
        }
        let keep = keep as usize;
        let mut kept: Vec<u8> = (0..keep).map(|i| self.digits.get(i).copied().unwrap_or(0)).collect();
        if self.digits.get(keep).is_some_and(|d| *d >= 5) {
            let mut i = keep;
            loop {
                if i == 0 {
                    kept.insert(0, 1);
                    point += 1;
                    break;
                }
                i -= 1;
                if kept[i] == 9 {
                    kept[i] = 0;
                } else {
                    kept[i] += 1;
                    break;
                }
            }
        }
        let text: String = kept.iter().map(|d| char::from(b'0' + d)).collect();
        if point <= 0 {
            return Some((String::new(), format!("{}{text}", "0".repeat(point.unsigned_abs() as usize))));
        }
        let (int, frac) = text.split_at(point as usize);
        Some((int.to_owned(), frac.to_owned()))
    }
}

/// Writes integer digits into placeholders from left to right. The leftmost
/// placeholder takes any digits that have no placeholder of their own.
struct Places<'a> {
    digits: &'a [u8],
    left: usize,
    first: bool,
    group: bool,
}

impl<'a> Places<'a> {
    fn new(digits: &'a str, count: usize, group: bool) -> Self {
        Self { digits: digits.as_bytes(), left: count, first: true, group }
    }

    fn emit(&mut self, out: &mut String, place: Place) {
        let Some(position) = self.left.checked_sub(1) else { return };
        self.left = position;
        let top = if self.first { self.digits.len().max(position + 1) - 1 } else { position };
        self.first = false;
        for at in (position..=top).rev() {
            let c = if at < self.digits.len() {
                char::from(self.digits[self.digits.len() - 1 - at])
            } else {
                match place {
                    Place::Zero => '0',
                    Place::Question => ' ',
                    Place::Hash => continue,
                }
            };
            out.push(c);
            if self.group && at > 0 && at % 3 == 0 {
                out.push(if c == ' ' { ' ' } else { ',' });
            }
        }
    }
}

fn render_plain(tokens: &[Token], value: &str) -> String {
    let mut out = String::new();
    for token in tokens {
        match token {
            Token::Text(s) => out.push_str(s),
            Token::Pad => out.push(' '),
            Token::General | Token::At => out.push_str(value),
            _ => {}
        }
    }
    out
}

fn render_number(section: &Section, abs: f64) -> Option<String> {
    let Shape::Number { int_places, decimals, group, shift, scientific, engineering } = section.shape else {
        return None;
    };
    let decimal = Decimal::of(abs);
    let mut exponent = 0;
    let (int, frac) = if scientific && !decimal.digits.is_empty() {
        let places = int_places as i32;
        let scaled = decimal.exponent + shift;
        let pick = |e: i32| if engineering { e.div_euclid(places) * places } else { e - (places - 1) };
        exponent = pick(scaled);
        let mut parts = decimal.fixed(shift - exponent, decimals)?;
        // Rounding up may leave one digit too many: 9.999 under 0.00E+00.
        if parts.0.len() > int_places {
            exponent = pick(scaled + 1);
            parts = decimal.fixed(shift - exponent, decimals)?;
        }
        parts
    } else {
        decimal.fixed(shift, decimals)?
    };
    let last_shown = frac.bytes().rposition(|b| b != b'0').map_or(0, |i| i + 1);

    let mut out = String::new();
    let mut places = Places::new(&int, int_places, group);
    let (mut seen_point, mut frac_index) = (false, 0);
    for token in &section.tokens {
        match token {
            Token::Text(s) => out.push_str(s),
            Token::Pad => out.push(' '),
            Token::Percent => out.push('%'),
            Token::Point => {
                if int_places == 0 {
                    out.push_str(&int);
                }
                out.push('.');
                seen_point = true;
            }
            Token::Digit(place) if !seen_point => places.emit(&mut out, *place),
            Token::Digit(place) => {
                match (frac.as_bytes().get(frac_index), *place) {
                    (Some(d), _) if frac_index < last_shown => out.push(char::from(*d)),
                    (_, Place::Zero) => out.push('0'),
                    (_, Place::Question) => out.push(' '),
                    (_, Place::Hash) => {}
                }
                frac_index += 1;
            }
            Token::Exponent { plus, width } => {
                out.push('E');
                if exponent < 0 {
                    out.push('-');
                } else if *plus {
                    out.push('+');
                }
                out.push_str(&format!("{:0width$}", exponent.unsigned_abs(), width = *width));
            }
            _ => {}
        }
    }
    Some(out)
}

fn render_fraction(section: &Section, abs: f64) -> Option<String> {
    let Shape::Fraction { whole, numerator, denominator, group } = section.shape else { return None };
    if abs >= 1e15 {
        return None;
    }
    let mut whole_value = if whole > 0 { abs.floor() as u64 } else { 0 };
    let rest = abs - whole_value as f64;
    let fixed = section.tokens.iter().find_map(|t| match t {
        Token::Slash(fixed) => *fixed,
        _ => None,
    });
    let (mut top, bottom) = match fixed {
        Some(d) => ((rest * f64::from(d)).round() as u64, u64::from(d)),
        None => closest_fraction(rest, 10u64.pow(denominator as u32) - 1),
    };
    if whole > 0 && top == bottom {
        whole_value += 1;
        top = 0;
    }
    let show_fraction = whole == 0 || top > 0;
    let whole_text = match (whole_value, show_fraction) {
        (0, true) => String::new(),
        (value, _) => value.to_string(),
    };
    let top_text = top.to_string();
    let bottom_text = bottom.to_string();

    let mut out = String::new();
    let mut whole_places = Places::new(&whole_text, whole, group);
    let mut top_places = Places::new(&top_text, numerator, false);
    let mut seen = 0;
    for token in &section.tokens {
        match token {
            Token::Text(s) => out.push_str(s),
            Token::Pad => out.push(' '),
            Token::Digit(place) => {
                if seen < whole {
                    whole_places.emit(&mut out, *place);
                } else if seen < whole + numerator {
                    if show_fraction {
                        top_places.emit(&mut out, *place);
                    }
                } else if show_fraction {
                    let at = seen - whole - numerator;
                    out.push(bottom_text.as_bytes().get(at).map_or(' ', |d| char::from(*d)));
                }
                seen += 1;
            }
            Token::Slash(fixed) if show_fraction => {
                out.push('/');
                if fixed.is_some() {
                    out.push_str(&bottom_text);
                }
            }
            _ => {}
        }
    }
    Some(out)
}

/// The closest n/d for `value` in `0..1` (or above, for improper fractions)
/// with `d <= max_denominator`, preferring the smallest denominator.
fn closest_fraction(value: f64, max_denominator: u64) -> (u64, u64) {
    let mut best = (value.round() as u64, 1);
    let mut best_error = (value - best.0 as f64).abs();
    for d in 2..=max_denominator {
        if best_error == 0.0 {
            break;
        }
        let n = (value * d as f64).round();
        let error = (value - n / d as f64).abs();
        if error < best_error {
            best = (n as u64, d);
            best_error = error;
        }
    }
    best
}

fn render_date(tokens: &[Token], serial: f64, date1904: bool, subsecond: usize, twelve_hour: bool) -> Option<String> {
    let max = if date1904 { MAX_SERIAL_1904 } else { MAX_SERIAL_1900 };
    if serial < 0.0 || serial >= max + 1.0 {
        return None;
    }
    let scale = 10u64.pow(subsecond as u32);
    let per_day = 86_400 * scale;
    let mut day = serial.floor() as i64;
    // Rounded to the finest unit the format can show; coarser fields then cut,
    // so 12:34:59 under h:mm is 12:34, as in Excel.
    let mut units = ((serial - serial.floor()) * per_day as f64).round() as u64;
    if units >= per_day {
        units -= per_day;
        day += 1;
    }
    if day as f64 > max {
        return None;
    }
    let (year, month, date, weekday) = calendar(day, date1904);
    let total = day as u64 * per_day + units;
    let values =
        FieldValues { year, month, date, weekday, seconds: total / scale, fraction: total % scale, twelve_hour };

    let mut out = String::new();
    for token in tokens {
        match token {
            Token::Text(s) => out.push_str(s),
            Token::Pad => out.push(' '),
            Token::Field(field, count) => write_field(&mut out, *field, *count, &values),
            _ => {}
        }
    }
    Some(out)
}

struct FieldValues {
    year: i64,
    month: usize,
    date: u32,
    weekday: usize,
    seconds: u64,
    fraction: u64,
    twelve_hour: bool,
}

fn write_field(out: &mut String, field: Field, count: usize, v: &FieldValues) {
    let pad = |out: &mut String, value: u64, width: usize| out.push_str(&format!("{value:0width$}"));
    let hour = (v.seconds % 86_400) / 3600;
    match field {
        Field::Year if count <= 2 => pad(out, v.year.rem_euclid(100) as u64, 2),
        Field::Year => pad(out, v.year as u64, 4),
        Field::Month => match count {
            1 | 2 => pad(out, v.month as u64 + 1, count),
            3 => out.push_str(&MONTHS[v.month][..3]),
            5 => out.push_str(&MONTHS[v.month][..1]),
            _ => out.push_str(MONTHS[v.month]),
        },
        Field::Day => match count {
            1 | 2 => pad(out, u64::from(v.date), count),
            3 => out.push_str(&WEEKDAYS[v.weekday][..3]),
            _ => out.push_str(WEEKDAYS[v.weekday]),
        },
        Field::Hour => {
            let shown = match (v.twelve_hour, hour % 12) {
                (true, 0) => 12,
                (true, h) => h,
                (false, _) => hour,
            };
            pad(out, shown, count.min(2));
        }
        Field::Minute => pad(out, (v.seconds % 3600) / 60, count.min(2)),
        Field::Second => pad(out, v.seconds % 60, count.min(2)),
        Field::ElapsedHours => pad(out, v.seconds / 3600, count),
        Field::ElapsedMinutes => pad(out, v.seconds / 60, count),
        Field::ElapsedSeconds => pad(out, v.seconds, count),
        Field::AmPm(am, pm) => out.push_str(if hour < 12 { am } else { pm }),
        Field::Subsecond => {
            out.push('.');
            pad(out, v.fraction, count);
        }
    }
}

/// (year, zero-based month, day of month, zero-based weekday from Sunday).
fn calendar(serial: i64, date1904: bool) -> (i64, usize, u32, usize) {
    if !date1904 {
        // Excel believes 1900 was a leap year, and that 1900-01-01 was a Sunday.
        match serial {
            0 => return (1900, 0, 0, 6),
            60 => return (1900, 1, 29, 3),
            1..=59 => {
                let (year, month, date) = civil_from_days(serial - EPOCH_1900 + 1);
                return (year, month, date, (serial.rem_euclid(7) as usize + 6) % 7);
            }
            _ => {}
        }
    }
    let days = serial - if date1904 { EPOCH_1904 } else { EPOCH_1900 };
    let (year, month, date) = civil_from_days(days);
    (year, month, date, (days + 4).rem_euclid(7) as usize)
}

/// Days since 1970-01-01 to a proleptic Gregorian date.
fn civil_from_days(days: i64) -> (i64, usize, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let date = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let month = if mp < 10 { mp + 2 } else { mp - 10 };
    let year = yoe + era * 400 + i64::from(month <= 1);
    (year, month as usize, date)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fmt(code: &str, value: f64) -> String {
        NumFmt::parse(code)
            .unwrap_or_else(|| panic!("{code} parses"))
            .format_number(value, false)
            .unwrap_or_else(|| panic!("{code} formats {value}"))
    }

    fn text(code: &str, value: &str) -> Option<String> {
        NumFmt::parse(code).unwrap().format_text(value)
    }

    #[test]
    fn dates() {
        assert_eq!(fmt("m/d/yyyy", 45231.0), "11/1/2023");
        assert_eq!(fmt("yyyy-mm-dd", 45231.0), "2023-11-01");
        assert_eq!(fmt("dd/mm/yy", 45231.0), "01/11/23");
        assert_eq!(fmt("d-mmm-yy", 45231.0), "1-Nov-23");
        assert_eq!(fmt("mmm-yy", 45231.0), "Nov-23");
        assert_eq!(fmt("mmmmm", 45231.0), "N");
        assert_eq!(fmt("dddd, mmmm d, yyyy", 45231.0), "Wednesday, November 1, 2023");
        assert_eq!(fmt("ddd", 45231.0), "Wed");
        assert_eq!(fmt("[$-F800]dddd, mmmm dd, yyyy", 45231.0), "Wednesday, November 01, 2023");
        assert_eq!(fmt("YYYY-MM-DD", 45231.0), "2023-11-01");
        assert_eq!(fmt("m/d/yyyy", 2958465.0), "12/31/9999");
    }

    #[test]
    fn excel_1900_leap_year_bug() {
        assert_eq!(fmt("m/d/yyyy", 0.0), "1/0/1900");
        assert_eq!(fmt("m/d/yyyy", 1.0), "1/1/1900");
        assert_eq!(fmt("m/d/yyyy", 59.0), "2/28/1900");
        assert_eq!(fmt("m/d/yyyy", 60.0), "2/29/1900");
        assert_eq!(fmt("m/d/yyyy", 61.0), "3/1/1900");
        // Excel's own weekdays around the phantom day.
        assert_eq!(fmt("dddd", 0.0), "Saturday");
        assert_eq!(fmt("dddd", 1.0), "Sunday");
        assert_eq!(fmt("dddd", 59.0), "Tuesday");
        assert_eq!(fmt("dddd", 60.0), "Wednesday");
        assert_eq!(fmt("dddd", 61.0), "Thursday");
    }

    #[test]
    fn date1904_system() {
        let date = NumFmt::parse("yyyy-mm-dd dddd").unwrap();
        assert_eq!(date.format_number(0.0, true).unwrap(), "1904-01-01 Friday");
        // No phantom day in this system: serial 60 is an ordinary date.
        assert_eq!(date.format_number(60.0, true).unwrap(), "1904-03-01 Tuesday");
        assert_eq!(date.format_number(45231.0, true).unwrap(), "2027-11-02 Tuesday");
        assert_eq!(date.format_number(2957003.0, true).unwrap(), "9999-12-31 Friday");
        assert_eq!(date.format_number(2957004.0, true), None);
    }

    #[test]
    fn times() {
        assert_eq!(fmt("h:mm", 0.75), "18:00");
        assert_eq!(fmt("h:mm:ss", 0.5 + 1.0 / 86400.0), "12:00:01");
        assert_eq!(fmt("h:mm AM/PM", 0.5), "12:00 PM");
        assert_eq!(fmt("h:mm AM/PM", 0.0), "12:00 AM");
        assert_eq!(fmt("hh:mm am/pm", 0.25), "06:00 AM");
        assert_eq!(fmt("h A/P", 0.75), "6 P");
        assert_eq!(fmt("mm:ss", 90.0 / 86400.0), "01:30");
        assert_eq!(fmt("mm:ss.0", 90.3 / 86400.0), "01:30.3");
        assert_eq!(fmt("ss.000", 1.5 / 86400.0), "01.500");
        assert_eq!(fmt("m/d/yyyy h:mm", 45231.5), "11/1/2023 12:00");
        // m is a month unless it touches hours or seconds.
        assert_eq!(fmt("yyyy-mm-dd hh:mm:ss", 45231.75), "2023-11-01 18:00:00");
        assert_eq!(fmt("h:mm:ss m", 45231.75), "18:00:00 11");
        // A shown field is cut, not rounded, once the seconds are settled.
        assert_eq!(fmt("h:mm", (12.0 * 3600.0 + 34.0 * 60.0 + 59.0) / 86400.0), "12:34");
    }

    #[test]
    fn time_rounding_carries_into_the_date() {
        assert_eq!(fmt("m/d/yyyy h:mm:ss", 45231.9999999), "11/2/2023 0:00:00");
        assert_eq!(NumFmt::parse("m/d/yyyy").unwrap().format_number(2958465.9999999, false), None);
    }

    #[test]
    fn elapsed_time() {
        assert_eq!(fmt("[h]:mm:ss", 1.5), "36:00:00");
        assert_eq!(fmt("[h]:mm", 0.25), "6:00");
        assert_eq!(fmt("[mm]:ss", 1.0 / 24.0), "60:00");
        assert_eq!(fmt("[s]", 1.0 / 1440.0), "60");
        assert_eq!(fmt("[hhh]:mm", 0.25), "006:00");
        assert_eq!(fmt("[h]:mm;-[h]:mm", -1.5), "-36:00");
    }

    #[test]
    fn numbers_percent_and_decimals() {
        assert_eq!(fmt("0", 2.5), "3");
        assert_eq!(fmt("0", 0.0), "0");
        assert_eq!(fmt("0.00", 2.14159), "2.14");
        assert_eq!(fmt("0.00", 2.0), "2.00");
        assert_eq!(fmt("0%", 0.25), "25%");
        assert_eq!(fmt("0.00%", 0.1234), "12.34%");
        assert_eq!(fmt("0.0%", 0.07), "7.0%");
        assert_eq!(fmt("#", 0.0), "");
        assert_eq!(fmt("#.##", 5.0), "5.");
        assert_eq!(fmt("0.0#", 1.5), "1.5");
        assert_eq!(fmt("0.0#", 1.55), "1.55");
        assert_eq!(fmt("#.00", 0.5), ".50");
        assert_eq!(fmt(".00", 12.5), "12.50");
        assert_eq!(fmt("000", 7.0), "007");
        assert_eq!(fmt("000-00-0000", 123456789.0), "123-45-6789");
        assert_eq!(fmt("(###) ###-####", 5551234567.0), "(555) 123-4567");
    }

    #[test]
    fn rounds_decimal_digits_like_excel_not_binary() {
        // Each of these lies just below the half in binary.
        assert_eq!(fmt("0.00", 1.005), "1.01");
        assert_eq!(fmt("0.00", 2.675), "2.68");
        assert_eq!(fmt("#,##0.00", 1234.565), "1,234.57");
        assert_eq!(fmt("0.00", 0.999), "1.00");
        assert_eq!(fmt("0", 0.5), "1");
        assert_eq!(fmt("0", 9.5), "10");
        // A sum with float noise, past the digits a double really holds.
        assert_eq!(fmt("0.00000000000000000000", 0.1 + 0.2), "0.30000000000000000000");
        assert_eq!(fmt("0", 1234567890123456789.0), "1234567890123460000");
    }

    #[test]
    fn thousands_and_scaling() {
        assert_eq!(fmt("#,##0", 1234567.0), "1,234,567");
        assert_eq!(fmt("#,##0", 999.0), "999");
        assert_eq!(fmt("#,##0", 0.0), "0");
        assert_eq!(fmt("#,##0.00", 1234.5), "1,234.50");
        assert_eq!(fmt("#,##0.00", 1000000.0), "1,000,000.00");
        assert_eq!(fmt("0,000", 5.0), "0,005");
        assert_eq!(fmt("#,##0,", 1234567.0), "1,235");
        assert_eq!(fmt(r#"0.0,,"M""#, 2500000.0), "2.5M");
    }

    #[test]
    fn currency_and_literals() {
        assert_eq!(fmt("\"$\"#,##0.00", 1234.5), "$1,234.50");
        assert_eq!(fmt("$#,##0", 1234.0), "$1,234");
        assert_eq!(fmt("[$€-407]#,##0.00", 9.5), "€9.50");
        assert_eq!(fmt("[$USD] 0.00", 9.5), "USD 9.50");
        assert_eq!(fmt("€0.00", 9.5), "€9.50");
        assert_eq!(fmt("£#,##0", 2000.0), "£2,000");
        assert_eq!(fmt(r#"0.00" kg""#, 1.5), "1.50 kg");
        assert_eq!(fmt(r"0\h", 3.0), "3h");
        assert_eq!(fmt(r#""a;b"0"#, 3.0), "a;b3");
        assert_eq!(fmt("[Red]0.0", 1.0), "1.0");
        assert_eq!(fmt("[Color 12]0.0", 1.0), "1.0");
    }

    #[test]
    fn negative_sections() {
        assert_eq!(fmt("0.00", -1.5), "-1.50");
        assert_eq!(fmt(r#""$"0.00"#, -1.5), "-$1.50");
        assert_eq!(fmt("0;(0)", -5.0), "(5)");
        assert_eq!(fmt("0;(0)", 5.0), "5");
        assert_eq!(fmt("0;(0)", 0.0), "0");
        assert_eq!(fmt(r#"0;-0;"zero""#, 0.0), "zero");
        assert_eq!(fmt(r#"0;-0;"zero""#, -3.0), "-3");
        assert_eq!(fmt("0.0;[Red]0.0", -1.0), "1.0");
        assert_eq!(fmt("0;;", -1.0), "");
        assert_eq!(fmt(r#""Yes";"Yes";"No""#, -2.0), "Yes");
        assert_eq!(fmt("#,##0.00_);(#,##0.00)", -1234.5), "(1,234.50)");
    }

    #[test]
    fn conditions() {
        let code = r#"[<100]"low";[>=1000]"high";"mid""#;
        assert_eq!(fmt(code, 50.0), "low");
        assert_eq!(fmt(code, 5000.0), "high");
        assert_eq!(fmt(code, 500.0), "mid");
        assert_eq!(fmt("[>=100]0;0.0", 5.5), "5.5");
        assert_eq!(fmt("[>=100]0;0.0", -5.5), "-5.5");
        assert_eq!(fmt("[<0]0;0.0", -5.0), "5");
        assert_eq!(fmt("[=1]0;0.0", 1.0), "1");
        assert_eq!(fmt("[<>1]0;0.0", 1.0), "1.0");
    }

    #[test]
    fn scientific() {
        assert_eq!(fmt("0.00E+00", 12345.0), "1.23E+04");
        assert_eq!(fmt("0.00E+00", 0.00012), "1.20E-04");
        assert_eq!(fmt("0.00E-00", 12345.0), "1.23E04");
        assert_eq!(fmt("0.00E+00", 0.0), "0.00E+00");
        assert_eq!(fmt("0.00E+00", -12345.0), "-1.23E+04");
        assert_eq!(fmt("0.00E+00", 1e300), "1.00E+300");
        assert_eq!(fmt("0.00E+00", 9.999), "1.00E+01");
        assert_eq!(fmt("##0.0E+0", 12345.0), "12.3E+3");
        assert_eq!(fmt("##0.0E+0", 1234567.0), "1.2E+6");
        assert_eq!(fmt("##0.0E+0", 999999.0), "1.0E+6");
        assert_eq!(fmt("00.0E+0", 12345.0), "12.3E+3");
    }

    #[test]
    fn fractions() {
        assert_eq!(fmt("# ?/?", 0.5), "1/2");
        assert_eq!(fmt("# ?/?", 1.5), "1 1/2");
        assert_eq!(fmt("# ?/?", 5.0), "5");
        assert_eq!(fmt("# ?/?", 0.0), "0");
        assert_eq!(fmt("# ?/?", -1.25), "-1 1/4");
        assert_eq!(fmt("# ?/?", 0.7), "5/7");
        assert_eq!(fmt("# ??/??", 2.14159), "2 14/99");
        assert_eq!(fmt("# ??/??", 3.5), "3  1/2");
        assert_eq!(fmt("# ?/8", 2.375), "2 3/8");
        assert_eq!(fmt("?/100", 0.25), "25/100");
        assert_eq!(fmt("?/?", 2.5), "5/2");
        assert_eq!(fmt("# ?/?", 0.9999), "1");
    }

    #[test]
    fn general_and_text_sections() {
        assert_eq!(fmt("General", 1.5), "1.5");
        assert_eq!(fmt("General", -42.0), "-42");
        assert_eq!(fmt(r#"General" units""#, 3.0), "3 units");
        assert_eq!(fmt("@", 42.5), "42.5");
        assert_eq!(fmt("\"#\"@", 7.0), "#7");
        assert_eq!(fmt("0;-0;0;@", 7.0), "7");
        assert_eq!(text("@", "abc").as_deref(), Some("abc"));
        assert_eq!(text(r#""Name: "@"#, "abc").as_deref(), Some("Name: abc"));
        assert_eq!(text(r#"0;-0;0;"T:"@"#, "abc").as_deref(), Some("T:abc"));
        assert_eq!(text("0;-0;0;", "abc").as_deref(), Some(""));
        assert_eq!(text("0.00", "abc"), None);
        assert_eq!(text("0;0;0;General", "abc").as_deref(), Some("abc"));
        assert_eq!(text("0;0;0;0", "abc"), None);
        assert_eq!(text("_(@_)", " abc ").as_deref(), Some(" abc "));
    }

    #[test]
    fn every_builtin_parses() {
        for id in (0..=22).chain(37..=49) {
            let code = builtin(id).unwrap();
            let format = NumFmt::parse(code).unwrap_or_else(|| panic!("built-in {id}: {code}"));
            for value in [0.0, 1.0, -1.0, 0.5, 45231.25, -45231.25, 1e300, -1e300, f64::NAN] {
                let _ = format.format_number(value, false);
            }
        }
        assert_eq!(builtin(23), None);
        assert_eq!(builtin(50), None);
    }

    #[test]
    fn accounting_builtins() {
        let run = |id, value| fmt(builtin(id).unwrap(), value);
        assert_eq!(run(5, 1234.0), "$1,234");
        assert_eq!(run(5, -1234.0), "($1,234)");
        assert_eq!(run(8, -1234.5), "($1,234.50)");
        assert_eq!(run(37, -1234.0), "(1,234)");
        assert_eq!(run(41, 1234.0), "1,234");
        assert_eq!(run(42, -1234.0), "$(1,234)");
        assert_eq!(run(43, 0.0), "-");
        assert_eq!(run(44, 1234.5), "$1,234.50");
        assert_eq!(run(44, 0.0), "$-");
        assert_eq!(NumFmt::parse(builtin(44).unwrap()).unwrap().format_text("Total").as_deref(), Some("Total"));
    }

    #[test]
    fn out_of_range_values_fall_back() {
        for code in ["m/d/yyyy", "h:mm", "[h]:mm", "dddd"] {
            let format = NumFmt::parse(code).unwrap();
            for value in [1e300, -1e300, -1.0, -0.5, 2958466.0, f64::MAX, f64::MIN, f64::NAN, f64::INFINITY] {
                assert_eq!(format.format_number(value, false), None, "{code} of {value}");
                assert_eq!(format.format_number(value, true), None, "{code} of {value}");
            }
        }
        let number = NumFmt::parse("#,##0.00").unwrap();
        assert_eq!(number.format_number(1e300, false), None);
        assert_eq!(number.format_number(f64::INFINITY, false), None);
        assert_eq!(number.format_number(f64::NAN, false), None);
        assert_eq!(NumFmt::parse("# ?/?").unwrap().format_number(1e300, false), None);
        assert_eq!(NumFmt::parse("General").unwrap().format_number(1e300, false), None);
        assert_eq!(NumFmt::parse("@").unwrap().format_number(1e300, false), None);
        // Tiny and subnormal values are simply zero at this precision.
        assert_eq!(number.format_number(5e-324, false).as_deref(), Some("0.00"));
        assert_eq!(fmt("0.00E+00", 5e-324), "4.94E-324");
    }

    #[test]
    fn rejects_malformed_and_unsupported_codes() {
        for code in [
            "",
            "\"unterminated",
            "\\",
            "[Red",
            "[NotAColour]0",
            "[DBNum1]0",
            "[$-1010409]yyyy",
            "[$-D07041E]yyyy",
            "xyz",
            "0;0;0;0;0",
            "0 yyyy",
            "e",
            "E0",
            "E+",
            "0.0E+0E+0",
            "0E+0.0",
            "yyyy e",
            "b",
            "g",
            "0.0/2",
            "0/0/0",
            "0 / ",
            "?/????",
            "0.00.0E+0/1",
            "General 0",
            "General General",
            "s.0.0",
            "h.0",
            "s.0000",
        ] {
            assert!(NumFmt::parse(code).is_none(), "{code:?} should be rejected");
        }
        // A slash with no digits around it is ordinary text.
        assert_eq!(fmt("/", 1.0), "/");
    }

    #[test]
    fn hostile_codes_stay_bounded() {
        let over = "0".repeat(MAX_CODE_BYTES + 1);
        assert!(NumFmt::parse(&over).is_none());
        assert!(NumFmt::parse(&format!("0.{}", "0".repeat(MAX_DECIMALS + 1))).is_none());

        for code in [
            "0".repeat(MAX_CODE_BYTES),
            ",".repeat(MAX_CODE_BYTES),
            "%".repeat(MAX_CODE_BYTES),
            format!("0{}", "%".repeat(MAX_CODE_BYTES - 1)),
            format!("0{}", ",".repeat(MAX_CODE_BYTES - 1)),
            format!("[{}]", "h".repeat(MAX_CODE_BYTES - 2)),
            "m".repeat(MAX_CODE_BYTES),
            "#,".repeat(MAX_CODE_BYTES / 2),
            r#""x""#.repeat(MAX_CODE_BYTES / 3),
            "_x".repeat(MAX_CODE_BYTES / 2),
        ] {
            let format = NumFmt::parse(&code).unwrap_or_else(|| panic!("{code} parses"));
            for value in [0.0, 1.0, -1.0, 0.5, 45231.25, 1e15, 1e300, -1e300, 5e-324] {
                if let Some(shown) = format.format_number(value, false) {
                    assert!(shown.len() <= 2 * MAX_CODE_BYTES + 80, "{} bytes", shown.len());
                }
            }
        }

        // `@` repeated must not multiply a long string.
        let many = NumFmt::parse(&"@".repeat(MAX_CODE_BYTES)).unwrap();
        assert_eq!(many.format_text(&"x".repeat(MAX_TEXT_OUTPUT)), None);
        assert_eq!(many.format_text("ab").map(|s| s.len()), Some(2 * MAX_CODE_BYTES));
    }

    #[test]
    fn never_panics_on_odd_bytes() {
        // Every prefix and suffix of codes full of multi-byte and special characters.
        let samples = [
            r#"[$€-407]"é"\é_é*é0.0é;é"#,
            r#"_(* #,##0.00_);_(* \(#,##0.00\);_(* "-"??_);_(@_)"#,
            "[h]:mm:ss.000 AM/PM a/p yyyy mmmmm dddd [<=-1.5e3]",
            "# ??/?? ?/8 0.0E+00 %,,, General @",
        ];
        for sample in samples {
            for (i, _) in sample.char_indices() {
                for part in [&sample[i..], &sample[..i]] {
                    if let Some(format) = NumFmt::parse(part) {
                        for value in [0.0, 1.5, -1.5, 45231.5, 1e300, f64::NAN] {
                            let _ = format.format_number(value, false);
                            let _ = format.format_number(value, true);
                        }
                        let _ = format.format_text("é");
                    }
                }
            }
        }
    }

    #[test]
    fn general_avoids_float_noise_and_invented_digits() {
        assert_eq!(general(0.30000000000000004).as_deref(), Some("0.3"));
        assert_eq!(general(42.0).as_deref(), Some("42"));
        assert_eq!(general(-0.0).as_deref(), Some("0"));
        assert_eq!(general(1e15), None);
        assert_eq!(general(1e-12), None);
        assert_eq!(general(f64::NAN), None);
    }
}
