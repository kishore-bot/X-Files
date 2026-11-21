//! Builds [`Rich`] text from styled runs, as parsers walk inline markup.

use document_core::{Rich, Span};

#[derive(Default)]
pub(crate) struct RichBuilder {
    text: String,
    spans: Vec<Span>,
    utf16_len: u32,
}

impl RichBuilder {
    pub fn is_empty(&self) -> bool {
        self.text.is_empty()
    }

    pub fn len(&self) -> usize {
        self.text.len()
    }

    pub fn text(&self) -> &str {
        &self.text
    }

    /// Appends `text` with `style` flags and an optional link target.
    pub fn push(&mut self, text: &str, style: u8, link: Option<&str>) {
        self.push_colored(text, style, link, None);
    }

    /// Like [`RichBuilder::push`], with an ARGB text colour.
    pub fn push_colored(&mut self, text: &str, style: u8, link: Option<&str>, color: Option<u32>) {
        if text.is_empty() {
            return;
        }
        let start = self.utf16_len;
        self.text.push_str(text);
        self.utf16_len += text.encode_utf16().count() as u32;
        if style == 0 && link.is_none() && color.is_none() {
            return;
        }
        if let Some(last) = self.spans.last_mut()
            && last.end == start
            && last.style == style
            && last.link.as_deref() == link
            && last.color == color
        {
            last.end = self.utf16_len;
            return;
        }
        self.spans.push(Span { start, end: self.utf16_len, style, link: link.map(str::to_owned), color });
    }

    pub fn push_plain(&mut self, text: &str) {
        self.push(text, 0, None);
    }

    /// Appends text collapsing whitespace runs, as HTML rendering does.
    pub fn push_collapsed(&mut self, text: &str, style: u8, link: Option<&str>) {
        self.push_collapsed_colored(text, style, link, None);
    }

    /// Like [`RichBuilder::push_collapsed`], with an ARGB text colour.
    pub fn push_collapsed_colored(&mut self, text: &str, style: u8, link: Option<&str>, color: Option<u32>) {
        let mut out = String::with_capacity(text.len());
        let mut last_space = self.text.is_empty() || self.text.ends_with([' ', '\n']);
        for c in text.chars() {
            if c.is_whitespace() && c != '\u{a0}' {
                if !last_space {
                    out.push(' ');
                    last_space = true;
                }
            } else {
                out.push(c);
                last_space = false;
            }
        }
        self.push_colored(&out, style, link, color);
    }

    /// Takes the text built so far, leaving the builder empty.
    pub fn take(&mut self) -> Rich {
        self.utf16_len = 0;
        Rich { text: std::mem::take(&mut self.text), spans: std::mem::take(&mut self.spans) }
    }

    /// Like [`RichBuilder::take`], with surrounding whitespace removed and
    /// spans shifted to match.
    pub fn take_trimmed(&mut self) -> Rich {
        let rich = self.take();
        let leading = rich.text.len() - rich.text.trim_start().len();
        let text = rich.text.trim().to_owned();
        if leading == 0 && text.len() == rich.text.len() {
            return Rich { text, spans: rich.spans };
        }
        let shift = rich.text[..leading].encode_utf16().count() as u32;
        let len = text.encode_utf16().count() as u32;
        let spans = rich
            .spans
            .into_iter()
            .filter_map(|s| {
                let start = s.start.saturating_sub(shift).min(len);
                let end = s.end.saturating_sub(shift).min(len);
                (end > start).then_some(Span { start, end, ..s })
            })
            .collect();
        Rich { text, spans }
    }
}

/// Clears span colours equal to `shown`, the colour the whole block is
/// drawn in, and drops spans left with nothing to say.
pub(crate) fn drop_color(rich: &mut Rich, shown: Option<u32>) {
    for span in &mut rich.spans {
        if span.color == shown {
            span.color = None;
        }
    }
    rich.spans.retain(|s| s.style != 0 || s.link.is_some() || s.color.is_some());
}

#[cfg(test)]
mod tests {
    use document_core::style::{BOLD, ITALIC};

    use super::*;

    #[test]
    fn merges_adjacent_runs_and_counts_utf16() {
        let mut b = RichBuilder::default();
        b.push_plain("😀 ");
        b.push("bo", BOLD, None);
        b.push("ld", BOLD, None);
        b.push("!", ITALIC, None);
        let rich = b.take();
        assert_eq!(rich.text, "😀 bold!");
        assert_eq!(
            rich.spans,
            [
                Span { start: 3, end: 7, style: BOLD, link: None, color: None },
                Span { start: 7, end: 8, style: ITALIC, link: None, color: None },
            ]
        );
    }

    #[test]
    fn trimming_shifts_spans() {
        let mut b = RichBuilder::default();
        b.push_plain("  ");
        b.push("word", BOLD, None);
        b.push_plain("  ");
        assert_eq!(
            b.take_trimmed(),
            Rich { text: "word".into(), spans: vec![Span { start: 0, end: 4, style: BOLD, link: None, color: None }] }
        );
    }
}
