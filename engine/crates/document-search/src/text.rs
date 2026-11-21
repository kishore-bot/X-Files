//! Plain text for search: whitespace-normalised and capped in UTF-16 units
//! (Kotlin string length), plus a small HTML-to-text pass.

/// Collects text block by block: blocks are separated by a newline, runs of
/// whitespace inside a block collapse to one space, and output stops at
/// `max_units` UTF-16 code units, at a character boundary.
pub struct PlainText {
    out: String,
    units: usize,
    max_units: usize,
    full: bool,
}

impl PlainText {
    pub fn new(max_units: usize) -> Self {
        Self { out: String::new(), units: 0, max_units, full: max_units == 0 }
    }

    /// The limit is reached; further pushes are ignored.
    pub fn is_full(&self) -> bool {
        self.full
    }

    /// Adds one block (paragraph, heading, cell row, page).
    pub fn push_block(&mut self, text: &str) {
        self.push_parts(std::iter::once(text));
    }

    /// Adds one block made of parts, such as table cells, separated by spaces.
    pub fn push_parts<'a>(&mut self, parts: impl IntoIterator<Item = &'a str>) {
        if self.full {
            return;
        }
        let mut started = false;
        let mut pending_space = false;
        for part in parts {
            pending_space |= started;
            for c in part.chars() {
                if c.is_whitespace() || c.is_control() {
                    pending_space |= started;
                    continue;
                }
                if !started && !self.out.is_empty() && !self.push_char('\n') {
                    return;
                }
                if pending_space && !self.push_char(' ') {
                    return;
                }
                pending_space = false;
                started = true;
                if !self.push_char(c) {
                    return;
                }
            }
        }
    }

    fn push_char(&mut self, c: char) -> bool {
        let units = c.len_utf16();
        if self.units + units > self.max_units {
            self.full = true;
            return false;
        }
        self.units += units;
        self.out.push(c);
        true
    }

    /// Length so far in UTF-16 code units.
    pub fn units(&self) -> usize {
        self.units
    }

    pub fn finish(self) -> String {
        self.out
    }
}

/// The readable text of an HTML page: tags removed, `script`, `style` and
/// comments skipped, common entities decoded, block elements on new lines.
pub fn html_to_text(html: &str, out: &mut PlainText) {
    let mut block = String::new();
    let bytes = html.as_bytes();
    let mut i = 0;
    let mut skip_until: Option<&'static str> = None;
    while i < html.len() && !out.is_full() {
        if let Some(end) = skip_until {
            match find_ci(&html[i..], end) {
                Some(at) => {
                    i += at + end.len();
                    // A closing tag ends at its '>'.
                    if end.starts_with("</") {
                        i += html[i..].find('>').map_or(html.len() - i, |gt| gt + 1);
                    }
                    skip_until = None;
                }
                None => break,
            }
            continue;
        }
        let c = bytes[i];
        if c == b'<' {
            if html[i..].starts_with("<!--") {
                skip_until = Some("-->");
                i += 4;
                continue;
            }
            let Some(close) = html[i..].find('>') else { break };
            let tag = &html[i + 1..i + close];
            let name: String = tag
                .trim_start_matches('/')
                .chars()
                .take_while(|c| c.is_ascii_alphanumeric())
                .map(|c| c.to_ascii_lowercase())
                .collect();
            i += close + 1;
            if !tag.starts_with('/') && (name == "script" || name == "style") {
                skip_until = if name == "script" { Some("</script") } else { Some("</style") };
                continue;
            }
            if is_block_tag(&name) {
                out.push_block(&block);
                block.clear();
            } else {
                block.push(' ');
            }
            continue;
        }
        if c == b'&' {
            if let Some((decoded, used)) = entity(&html[i..]) {
                block.push(decoded);
                i += used;
                continue;
            }
        }
        let ch = html[i..].chars().next().unwrap_or(' ');
        block.push(ch);
        i += ch.len_utf8();
        if block.len() > 64 * 1024 {
            out.push_block(&block);
            block.clear();
        }
    }
    out.push_block(&block);
}

fn find_ci(haystack: &str, needle: &str) -> Option<usize> {
    let (h, n) = (haystack.as_bytes(), needle.as_bytes());
    if n.is_empty() || h.len() < n.len() {
        return None;
    }
    (0..=h.len() - n.len()).find(|&start| h[start..start + n.len()].eq_ignore_ascii_case(n))
}

fn is_block_tag(name: &str) -> bool {
    matches!(
        name,
        "p" | "div"
            | "br"
            | "li"
            | "tr"
            | "td"
            | "th"
            | "h1"
            | "h2"
            | "h3"
            | "h4"
            | "h5"
            | "h6"
            | "section"
            | "article"
            | "header"
            | "footer"
            | "title"
            | "blockquote"
            | "pre"
            | "table"
            | "ul"
            | "ol"
            | "hr"
            | "dt"
            | "dd"
            | "figcaption"
            | "nav"
            | "aside"
            | "main"
    )
}

/// Decodes the entity at the start of `s`; returns it and the bytes used.
fn entity(s: &str) -> Option<(char, usize)> {
    // ';' is ASCII, so `end` is always a character boundary.
    let end = s.as_bytes().iter().take(12).position(|&b| b == b';')?;
    let body = &s[1..end];
    let decoded = match body {
        "amp" => '&',
        "lt" => '<',
        "gt" => '>',
        "quot" => '"',
        "apos" => '\'',
        "nbsp" => ' ',
        _ => {
            let number = body.strip_prefix('#')?;
            let code = match number.strip_prefix(['x', 'X']) {
                Some(hex) => u32::from_str_radix(hex, 16).ok()?,
                None => number.parse().ok()?,
            };
            char::from_u32(code)?
        }
    };
    Some((decoded, end + 1))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalises_whitespace_and_separates_blocks() {
        let mut text = PlainText::new(1000);
        text.push_block("  Hello \t  world \r\n again ");
        text.push_block("   ");
        text.push_parts(["Name", " ", "Total ", "", "42"]);
        assert_eq!(text.finish(), "Hello world again\nName Total 42");
    }

    #[test]
    fn caps_in_utf16_units_at_a_character_boundary() {
        let mut text = PlainText::new(5);
        text.push_block("ab😀cd");
        assert!(text.is_full());
        assert_eq!(text.units(), 5);
        assert_eq!(text.finish(), "ab😀c");

        // Never half a surrogate pair.
        let mut text = PlainText::new(3);
        text.push_block("ab😀");
        assert_eq!(text.finish(), "ab");

        let mut text = PlainText::new(3);
        text.push_block("ab");
        text.push_block("cd");
        assert_eq!(text.finish(), "ab\n");
    }

    #[test]
    fn strips_html() {
        let mut text = PlainText::new(1000);
        html_to_text(
            "<html><head><title>T&amp;C</title><style>p{color:red}</style><script>var x='<p>';</script></head>\
             <body><!-- hidden <p> --><h1>Caf&eacute; &#233;t&#xE9;</h1><p>One<b>bold</b> two&nbsp;three</p>\
             <SCRIPT>no</SCRIPT><ul><li>a</li><li>b</li></ul></body></html>",
            &mut text,
        );
        assert_eq!(text.finish(), "T&C\nCaf&eacute; été\nOne bold two three\na\nb");
    }
}
