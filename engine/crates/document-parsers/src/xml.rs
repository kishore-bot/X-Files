//! Streaming XML pull reader shared by the office and e-book parsers.
//!
//! Wraps quick-xml with what every caller needs: namespace prefixes stripped
//! from element and attribute names, text with references resolved, a nesting
//! limit, cancellation checks, and errors mapped to engine errors.
//!
//! Security: quick-xml never loads external resources and never expands
//! entities declared in a DTD. Only the five predefined entities, numeric
//! character references and a fixed table of common HTML entities are
//! resolved; anything else is kept literally. That rules out XXE and
//! entity-expansion ("billion laughs") attacks.

use std::io::BufRead;

use document_core::{CancelToken, EngineError, Result};
use quick_xml::events::Event;

use crate::zip::from_io;

const MAX_DEPTH: usize = 256;
const CANCEL_CHECK_INTERVAL: u32 = 4096;

/// An immutable string kept inline when it is at most `N` bytes, and on the
/// heap otherwise.
///
/// The reader produces millions of events on a large sheet or document, and a
/// heap allocation per name, attribute and text node was most of its cost: on
/// a 17 MB sheet, quick-xml alone took 109 ms and this wrapper 351 ms, nearly
/// all of it allocating. Element names (`c`, `v`, `row`, `p`, `rPr`), short
/// texts such as cell values and most tags' attributes fit inline.
#[derive(Clone)]
pub(crate) enum SmallStr<const N: usize> {
    Inline { len: u8, bytes: [u8; N] },
    Heap(Box<str>),
}

/// Element names.
pub(crate) type Name = SmallStr<22>;
/// Text nodes, as long as cell values and short runs usually are.
pub(crate) type Text = SmallStr<30>;
/// One tag's attribute keys and values; `<c r="B12" s="3" t="s">` takes 9 bytes.
type AttrText = SmallStr<46>;

impl<const N: usize> Default for SmallStr<N> {
    fn default() -> Self {
        SmallStr::Inline { len: 0, bytes: [0; N] }
    }
}

impl<const N: usize> SmallStr<N> {
    pub(crate) fn new(s: &str) -> Self {
        // `len` is a u8, so `N` must stay below 256.
        const { assert!(N < 256) };
        if s.len() <= N {
            let mut bytes = [0; N];
            bytes[..s.len()].copy_from_slice(s.as_bytes());
            SmallStr::Inline { len: s.len() as u8, bytes }
        } else {
            SmallStr::Heap(s.into())
        }
    }

    pub fn as_str(&self) -> &str {
        match self {
            // SAFETY: only `new` builds `Inline`, copying a whole `&str` of `len` bytes.
            SmallStr::Inline { len, bytes } => unsafe { std::str::from_utf8_unchecked(&bytes[..usize::from(*len)]) },
            SmallStr::Heap(s) => s,
        }
    }
}

impl<const N: usize> std::ops::Deref for SmallStr<N> {
    type Target = str;
    fn deref(&self) -> &str {
        self.as_str()
    }
}

impl<const N: usize> PartialEq<str> for SmallStr<N> {
    fn eq(&self, other: &str) -> bool {
        self.as_str() == other
    }
}

impl<const N: usize> PartialEq<&str> for SmallStr<N> {
    fn eq(&self, other: &&str) -> bool {
        self.as_str() == *other
    }
}

impl<const N: usize> std::fmt::Display for SmallStr<N> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

impl<const N: usize> std::fmt::Debug for SmallStr<N> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        std::fmt::Debug::fmt(self.as_str(), f)
    }
}

pub(crate) struct Tag {
    /// Local name, e.g. `p` for `<w:p>`.
    pub name: Name,
    /// Every attribute's local name and value, back to back.
    text: AttrText,
    attrs: Attrs,
}

/// Byte offsets into [`Tag::text`]: the key is `key_start..key_end` and the
/// value follows it directly, `key_end..value_end`.
#[derive(Clone, Copy, Default)]
struct Attr {
    prefixed: bool,
    key_start: u32,
    key_end: u32,
    value_end: u32,
}

/// The first [`Attrs::INLINE`] attributes inline, the rest (rare) on the heap.
#[derive(Default)]
struct Attrs {
    len: u8,
    inline: [Attr; Attrs::INLINE],
    more: Vec<Attr>,
}

impl Attrs {
    const INLINE: usize = 4;

    fn push(&mut self, attr: Attr) {
        if usize::from(self.len) < Self::INLINE {
            self.inline[usize::from(self.len)] = attr;
            self.len += 1;
        } else {
            self.more.push(attr);
        }
    }

    fn iter(&self) -> impl Iterator<Item = &Attr> + Clone {
        self.inline[..usize::from(self.len)].iter().chain(&self.more)
    }
}

impl Tag {
    fn key(&self, a: &Attr) -> &str {
        &self.text[a.key_start as usize..a.key_end as usize]
    }

    fn value(&self, a: &Attr) -> &str {
        &self.text[a.key_end as usize..a.value_end as usize]
    }

    /// Value of the attribute with this local name, preferring an unprefixed one.
    pub fn attr(&self, name: &str) -> Option<&str> {
        let mut first = None;
        for a in self.attrs.iter() {
            if self.key(a) == name {
                if !a.prefixed {
                    return Some(self.value(a));
                }
                first.get_or_insert(a);
            }
        }
        first.map(|a| self.value(a))
    }

    /// All attributes as (local name, value).
    pub fn attrs(&self) -> impl Iterator<Item = (&str, &str)> {
        self.attrs.iter().map(|a| (self.key(a), self.value(a)))
    }

    /// Value of a namespace-prefixed attribute, such as the relationship
    /// reference `r:id` on an element that also has a plain `id`.
    pub fn prefixed_attr(&self, name: &str) -> Option<&str> {
        self.attrs.iter().find(|a| a.prefixed && self.key(a) == name).map(|a| self.value(a))
    }
}

pub(crate) enum Xml {
    Start(Tag),
    /// A self-closing element; no matching `End` follows.
    Empty(Tag),
    /// Local name of the closed element.
    End(Name),
    Text(Text),
    Eof,
}

pub(crate) struct XmlReader<R: BufRead> {
    reader: quick_xml::Reader<R>,
    buf: Vec<u8>,
    /// Reused while collecting a tag's attributes, so only its final form allocates.
    scratch: String,
    depth: usize,
    since_check: u32,
    cancel: CancelToken,
}

impl<R: BufRead> XmlReader<R> {
    pub fn new(source: R, cancel: CancelToken) -> Self {
        let mut reader = quick_xml::Reader::from_reader(source);
        let config = reader.config_mut();
        // Tolerate sloppy XHTML in e-books; structure errors are not security issues.
        config.check_end_names = false;
        config.allow_unmatched_ends = true;
        Self { reader, buf: Vec::new(), scratch: String::new(), depth: 0, since_check: 0, cancel }
    }

    pub fn into_inner(self) -> R {
        self.reader.into_inner()
    }

    pub fn get_mut(&mut self) -> &mut R {
        self.reader.get_mut()
    }

    pub fn next(&mut self) -> Result<Xml> {
        loop {
            self.since_check += 1;
            if self.since_check >= CANCEL_CHECK_INTERVAL {
                self.since_check = 0;
                self.cancel.check()?;
            }
            self.buf.clear();
            let event = self.reader.read_event_into(&mut self.buf).map_err(map_error)?;
            return Ok(match event {
                Event::Start(e) => {
                    self.depth += 1;
                    if self.depth > MAX_DEPTH {
                        return Err(EngineError::malformed("the document is nested too deeply"));
                    }
                    Xml::Start(tag(&e, &mut self.scratch))
                }
                Event::Empty(e) => Xml::Empty(tag(&e, &mut self.scratch)),
                Event::End(e) => {
                    self.depth = self.depth.saturating_sub(1);
                    Xml::End(Name::new(e.local_name().as_ref()))
                }
                Event::Text(t) => Xml::Text(Text::new(&t.xml10_content())),
                Event::CData(t) => Xml::Text(Text::new(&t.into_inner())),
                Event::GeneralRef(r) => Xml::Text(match r.resolve_char_ref() {
                    Ok(Some(c)) => Text::new(c.encode_utf8(&mut [0; 4])),
                    _ => resolve_entity(&r.xml10_content()),
                }),
                Event::Eof => Xml::Eof,
                // Declarations, comments, processing instructions and DTDs.
                _ => continue,
            });
        }
    }

    /// Skips everything up to and including the end of the element just started.
    pub fn skip_element(&mut self) -> Result<()> {
        let mut depth = 1;
        while depth > 0 {
            match self.next()? {
                Xml::Start(_) => depth += 1,
                Xml::End(_) => depth -= 1,
                Xml::Eof => break,
                _ => {}
            }
        }
        Ok(())
    }

    /// Reads to the end of the element just started and returns its text,
    /// keeping at most about `max_bytes` of it.
    pub fn element_text(&mut self, max_bytes: usize) -> Result<String> {
        let mut text = String::new();
        let mut depth = 1;
        while depth > 0 {
            match self.next()? {
                Xml::Start(_) => depth += 1,
                Xml::End(_) => depth -= 1,
                Xml::Text(t) if text.len() < max_bytes => text.push_str(&t),
                Xml::Eof => break,
                _ => {}
            }
        }
        Ok(text)
    }
}

/// Parts are capped well below 4 GiB, but a tag over that keeps only the
/// attributes that fit rather than wrapping the offsets.
const MAX_TAG_TEXT: usize = u32::MAX as usize;

fn tag(e: &quick_xml::events::BytesStart<'_>, text: &mut String) -> Tag {
    text.clear();
    let mut attrs = Attrs::default();
    for a in e.attributes().with_checks(false).flatten() {
        let key_start = text.len();
        text.push_str(a.key.local_name().as_ref());
        let key_end = text.len();
        match a.normalized_value(quick_xml::XmlVersion::Implicit1_0) {
            Ok(value) => text.push_str(&value),
            Err(_) => text.push_str(&a.value),
        }
        if text.len() > MAX_TAG_TEXT {
            text.truncate(key_start);
            break;
        }
        attrs.push(Attr {
            prefixed: a.key.prefix().is_some(),
            key_start: key_start as u32,
            key_end: key_end as u32,
            value_end: text.len() as u32,
        });
    }
    Tag { name: Name::new(e.local_name().as_ref()), text: AttrText::new(text), attrs }
}

fn map_error(err: quick_xml::Error) -> EngineError {
    match err {
        quick_xml::Error::Io(io) => from_io(&io),
        other => EngineError::malformed(format!("the document's XML is invalid: {other}")),
    }
}

fn resolve_entity(name: &str) -> Text {
    let resolved = match name {
        "amp" => "&",
        "lt" => "<",
        "gt" => ">",
        "quot" => "\"",
        "apos" => "'",
        "nbsp" => "\u{a0}",
        "shy" => "\u{ad}",
        "ensp" => "\u{2002}",
        "emsp" => "\u{2003}",
        "thinsp" => "\u{2009}",
        "ndash" => "–",
        "mdash" => "—",
        "lsquo" => "‘",
        "rsquo" => "’",
        "ldquo" => "“",
        "rdquo" => "”",
        "laquo" => "«",
        "raquo" => "»",
        "hellip" => "…",
        "bull" => "•",
        "middot" => "·",
        "copy" => "©",
        "reg" => "®",
        "trade" => "™",
        "deg" => "°",
        "times" => "×",
        "euro" => "€",
        "pound" => "£",
        "sect" => "§",
        "para" => "¶",
        _ => return Text::new(&format!("&{name};")),
    };
    Text::new(resolved)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn events(xml: &str) -> Vec<String> {
        let mut reader = XmlReader::new(xml.as_bytes(), CancelToken::default());
        let mut out = Vec::new();
        loop {
            match reader.next().unwrap() {
                Xml::Start(t) => out.push(format!("<{}>", t.name)),
                Xml::Empty(t) => out.push(format!("<{}/>", t.name)),
                Xml::End(n) => out.push(format!("</{n}>")),
                Xml::Text(t) => out.push(t.to_string()),
                Xml::Eof => return out,
            }
        }
    }

    #[test]
    fn strips_prefixes_and_resolves_references() {
        assert_eq!(
            events("<w:p><w:t>a &amp; b&#x263A;&nbsp;</w:t><w:br/></w:p>"),
            ["<p>", "<t>", "a ", "&", " b", "☺", "\u{a0}", "</t>", "<br/>", "</p>"]
        );
    }

    fn first_tag(xml: &str) -> Tag {
        let mut reader = XmlReader::new(xml.as_bytes(), CancelToken::default());
        match reader.next().unwrap() {
            Xml::Start(t) | Xml::Empty(t) => t,
            _ => panic!("no tag"),
        }
    }

    #[test]
    fn long_names_and_texts_survive_the_inline_limit() {
        let name = "n".repeat(40);
        let text = "é".repeat(40);
        assert_eq!(events(&format!("<{name}>{text}</{name}>")), [format!("<{name}>"), text, format!("</{name}>")]);
        // Right at the limits stays inline and intact too.
        let (name, text) = ("m".repeat(22), "t".repeat(30));
        assert_eq!(events(&format!("<{name}>{text}</{name}>")), [format!("<{name}>"), text, format!("</{name}>")]);
    }

    #[test]
    fn attributes_beyond_the_inline_ones_and_long_values_are_kept() {
        let long = "v".repeat(100);
        let tag = first_tag(&format!(r#"<c a="1" b="2" c="3" d="4" e="5" f="{long}" g="&amp;"/>"#));
        assert_eq!(
            tag.attrs().collect::<Vec<_>>(),
            [("a", "1"), ("b", "2"), ("c", "3"), ("d", "4"), ("e", "5"), ("f", long.as_str()), ("g", "&")]
        );
        assert_eq!(tag.attr("f"), Some(long.as_str()));
        assert_eq!(tag.attr("missing"), None);
    }

    #[test]
    fn plain_attributes_win_over_prefixed_ones() {
        let tag = first_tag(r#"<rel r:id="rId7" id="plain"/>"#);
        assert_eq!(tag.attr("id"), Some("plain"));
        assert_eq!(tag.prefixed_attr("id"), Some("rId7"));
        // With only a prefixed one, it is still found.
        assert_eq!(first_tag(r#"<rel r:id="rId7"/>"#).attr("id"), Some("rId7"));
        assert_eq!(first_tag(r#"<rel id="x"/>"#).prefixed_attr("id"), None);
    }

    #[test]
    fn never_expands_dtd_entities() {
        let bomb = r#"<!DOCTYPE x [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;">]><x>&b;</x>"#;
        assert_eq!(events(bomb), ["<x>", "&b;", "</x>"]);
    }

    #[test]
    fn rejects_excessive_nesting() {
        let deep = "<a>".repeat(MAX_DEPTH + 1);
        let mut reader = XmlReader::new(deep.as_bytes(), CancelToken::default());
        let err = std::iter::from_fn(|| Some(reader.next())).find_map(Result::err).unwrap();
        assert_eq!(err.kind, document_core::ErrorKind::Malformed);
    }
}
