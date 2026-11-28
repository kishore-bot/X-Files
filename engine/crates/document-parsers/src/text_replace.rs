//! Find-and-replace inside Office Open XML packages (DOCX / PPTX).
//!
//! Operates on the raw XML text runs, preserving all formatting, styles,
//! images and layout. The result is a new ZIP with only the text-content
//! parts rewritten; every other part is copied byte-for-byte.

use std::io::{Cursor, Write};

use document_core::{CancelToken, EngineError, Limits};
use flate2::write::DeflateEncoder;
use flate2::Compression;

use crate::zip::ZipArchive;

pub struct ReplaceReport {
    pub matches: u32,
    pub output: Vec<u8>,
}

pub fn replace_in_docx(
    data: &[u8],
    find: &str,
    replace: &str,
    case_sensitive: bool,
) -> Result<ReplaceReport, EngineError> {
    let parts = &[
        "word/document.xml",
        "word/header1.xml",
        "word/header2.xml",
        "word/header3.xml",
        "word/footer1.xml",
        "word/footer2.xml",
        "word/footer3.xml",
    ];
    replace_in_ooxml(data, find, replace, case_sensitive, parts, "w:t")
}

pub fn replace_in_pptx(
    data: &[u8],
    find: &str,
    replace: &str,
    case_sensitive: bool,
) -> Result<ReplaceReport, EngineError> {
    let archive = open(data)?;
    let slide_names: Vec<String> = archive
        .entries()
        .iter()
        .filter(|e| e.name.starts_with("ppt/slides/slide") && e.name.ends_with(".xml"))
        .map(|e| e.name.clone())
        .collect();
    drop(archive);

    let parts: Vec<&str> = slide_names.iter().map(|s| s.as_str()).collect();
    replace_in_ooxml(data, find, replace, case_sensitive, &parts, "a:t")
}

pub fn replace_in_odt(
    data: &[u8],
    find: &str,
    replace: &str,
    case_sensitive: bool,
) -> Result<ReplaceReport, EngineError> {
    replace_in_odf(data, find, replace, case_sensitive)
}

pub fn replace_in_odp(
    data: &[u8],
    find: &str,
    replace: &str,
    case_sensitive: bool,
) -> Result<ReplaceReport, EngineError> {
    replace_in_odf(data, find, replace, case_sensitive)
}

fn replace_in_odf(
    data: &[u8],
    find: &str,
    replace: &str,
    case_sensitive: bool,
) -> Result<ReplaceReport, EngineError> {
    if find.is_empty() {
        return Err(EngineError::malformed("find string cannot be empty"));
    }
    let mut archive = open(data)?;
    let mut total_matches: u32 = 0;
    let mut modified: Vec<(String, Vec<u8>)> = Vec::new();

    for &part in &["content.xml"] {
        let xml_bytes = match archive.read_to_vec(part) {
            Ok(b) => b,
            Err(_) => continue,
        };
        let xml = String::from_utf8_lossy(&xml_bytes);
        let (new_xml, count) = replace_text_nodes(&xml, find, replace, case_sensitive);
        if count > 0 {
            total_matches += count;
            modified.push((part.to_owned(), new_xml.into_bytes()));
        }
    }

    let output = rebuild_zip(data, &modified)?;
    Ok(ReplaceReport { matches: total_matches, output })
}

fn replace_text_nodes(xml: &str, find: &str, replace: &str, case_sensitive: bool) -> (String, u32) {
    let mut result = String::with_capacity(xml.len());
    let mut count: u32 = 0;
    let bytes = xml.as_bytes();
    let mut i = 0;
    let mut seg_start = 0;
    let mut in_tag = false;

    while i < bytes.len() {
        if in_tag {
            if bytes[i] == b'>' {
                i += 1;
                result.push_str(&xml[seg_start..i]);
                seg_start = i;
                in_tag = false;
            } else {
                i += 1;
            }
        } else if bytes[i] == b'<' {
            if i > seg_start {
                let text = &xml[seg_start..i];
                let (replaced, c) = replace_escaped(text, find, replace, case_sensitive);
                count += c;
                result.push_str(&replaced);
            }
            seg_start = i;
            i += 1;
            in_tag = true;
        } else {
            i += 1;
        }
    }
    if !in_tag && seg_start < bytes.len() {
        let text = &xml[seg_start..];
        let (replaced, c) = replace_escaped(text, find, replace, case_sensitive);
        count += c;
        result.push_str(&replaced);
    }
    (result, count)
}

fn open(data: &[u8]) -> Result<ZipArchive, EngineError> {
    let len = data.len() as u64;
    ZipArchive::open(
        Box::new(Cursor::new(data.to_vec())),
        len,
        Limits { max_part_bytes: u64::MAX, ..Limits::default() },
        CancelToken::default(),
    )
}

fn replace_in_ooxml(
    data: &[u8],
    find: &str,
    replace: &str,
    case_sensitive: bool,
    parts: &[&str],
    text_tag: &str,
) -> Result<ReplaceReport, EngineError> {
    if find.is_empty() {
        return Err(EngineError::malformed("find string cannot be empty"));
    }

    let mut archive = open(data)?;
    let mut total_matches: u32 = 0;
    let mut modified: Vec<(String, Vec<u8>)> = Vec::new();

    for &part in parts {
        let xml_bytes = match archive.read_to_vec(part) {
            Ok(b) => b,
            Err(_) => continue,
        };
        let xml = String::from_utf8_lossy(&xml_bytes);
        let (new_xml, count) = replace_in_xml(&xml, find, replace, case_sensitive, text_tag);
        if count > 0 {
            total_matches += count;
            modified.push((part.to_owned(), new_xml.into_bytes()));
        }
    }

    let output = rebuild_zip(data, &modified)?;
    Ok(ReplaceReport { matches: total_matches, output })
}

fn replace_in_xml(xml: &str, find: &str, replace: &str, case_sensitive: bool, text_tag: &str) -> (String, u32) {
    let open_tag = format!("<{}", text_tag);
    let close_tag = format!("</{}>", text_tag);
    let mut result = String::with_capacity(xml.len());
    let mut count: u32 = 0;
    let mut pos = 0;

    while pos < xml.len() {
        let Some(rel) = xml[pos..].find(&open_tag) else { break };
        let tag_start = pos + rel;
        let name_end = tag_start + open_tag.len();
        // Exact element only: `<w:t>` or `<w:t attr…>`, never `<w:tbl>`, `<w:tab/>`…
        let exact = matches!(xml.as_bytes().get(name_end), Some(b'>' | b' ' | b'\t' | b'\r' | b'\n'));
        let Some(gt) = xml[tag_start..].find('>') else { break };
        let content_start = tag_start + gt + 1;
        let self_closing = xml.as_bytes()[content_start - 2] == b'/';
        if !exact || self_closing {
            result.push_str(&xml[pos..content_start]);
            pos = content_start;
            continue;
        }
        let Some(cl) = xml[content_start..].find(&close_tag) else { break };
        let content_end = content_start + cl;
        result.push_str(&xml[pos..content_start]);
        let (replaced, c) = replace_escaped(&xml[content_start..content_end], find, replace, case_sensitive);
        count += c;
        result.push_str(&replaced);
        pos = content_end;
    }
    result.push_str(&xml[pos..]);
    (result, count)
}

/// Replaces inside escaped XML character data: decodes entities, replaces,
/// and re-escapes, so `&` / `<` in either string stay well-formed.
fn replace_escaped(raw: &str, find: &str, replace: &str, case_sensitive: bool) -> (String, u32) {
    let decoded = xml_unescape(raw);
    let (out, count) = replace_text(&decoded, find, replace, case_sensitive);
    if count == 0 {
        return (raw.to_owned(), 0);
    }
    (xml_escape(&out), count)
}

fn xml_unescape(s: &str) -> String {
    if !s.contains('&') {
        return s.to_owned();
    }
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(amp) = rest.find('&') {
        out.push_str(&rest[..amp]);
        rest = &rest[amp..];
        let decoded = rest.find(';').filter(|&semi| semi <= 10).and_then(|semi| {
            let ch = match &rest[1..semi] {
                "amp" => Some('&'),
                "lt" => Some('<'),
                "gt" => Some('>'),
                "quot" => Some('"'),
                "apos" => Some('\''),
                e if e.starts_with("#x") || e.starts_with("#X") => {
                    u32::from_str_radix(&e[2..], 16).ok().and_then(char::from_u32)
                }
                e if e.starts_with('#') => e[1..].parse().ok().and_then(char::from_u32),
                _ => None,
            };
            ch.map(|c| (c, semi + 1))
        });
        match decoded {
            Some((c, len)) => {
                out.push(c);
                rest = &rest[len..];
            }
            None => {
                out.push('&');
                rest = &rest[1..];
            }
        }
    }
    out.push_str(rest);
    out
}

fn xml_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            _ => out.push(c),
        }
    }
    out
}

/// Byte length of the prefix of `hay` that equals `needle` ignoring case,
/// compared char by char on the original text (no offset mapping).
fn match_ignore_case(hay: &str, needle: &str) -> Option<usize> {
    let mut hay_chars = hay.char_indices();
    for n in needle.chars() {
        let (_, h) = hay_chars.next()?;
        if !h.to_lowercase().eq(n.to_lowercase()) {
            return None;
        }
    }
    Some(hay_chars.next().map_or(hay.len(), |(i, _)| i))
}

fn replace_text(text: &str, find: &str, replace: &str, case_sensitive: bool) -> (String, u32) {
    if case_sensitive {
        let parts: Vec<&str> = text.split(find).collect();
        let count = (parts.len() as u32).saturating_sub(1);
        (parts.join(replace), count)
    } else {
        let mut out = String::with_capacity(text.len());
        let mut count = 0u32;
        let mut last = 0;
        let mut i = 0;
        while i < text.len() {
            if let Some(len) = match_ignore_case(&text[i..], find) {
                out.push_str(&text[last..i]);
                out.push_str(replace);
                i += len;
                last = i;
                count += 1;
            } else {
                i += text[i..].chars().next().map_or(1, char::len_utf8);
            }
        }
        out.push_str(&text[last..]);
        (out, count)
    }
}

const MIMETYPE: &str = "mimetype";

pub(crate) fn rebuild_zip_pub(original: &[u8], modified: &[(String, Vec<u8>)]) -> Result<Vec<u8>, EngineError> {
    rebuild_zip(original, modified)
}

fn rebuild_zip(original: &[u8], modified: &[(String, Vec<u8>)]) -> Result<Vec<u8>, EngineError> {
    let mut archive = open(original)?;
    let mut entries: Vec<_> = archive.entries().to_vec();
    // OpenDocument (and EPUB) require `mimetype` as the first entry, stored uncompressed, so readers can
    // identify the package from its leading bytes. A stable sort keeps every other entry in place.
    entries.sort_by_key(|entry| entry.name != MIMETYPE);
    let mut out = Cursor::new(Vec::new());
    let mut central = Vec::new();

    for entry in &entries {
        let raw_data = if let Some((_, new)) = modified.iter().find(|(n, _)| *n == entry.name) {
            new.clone()
        } else {
            archive.read_to_vec(&entry.name)?
        };

        let offset = out.position() as u32;
        let crc = crc32fast::hash(&raw_data);
        let (method, compressed): (u16, Vec<u8>) = if entry.name == MIMETYPE {
            (0, raw_data.clone())
        } else {
            let mut enc = DeflateEncoder::new(Vec::new(), Compression::default());
            enc.write_all(&raw_data).map_err(EngineError::from)?;
            (8, enc.finish().map_err(EngineError::from)?)
        };

        // Local file header
        let mut local = Vec::new();
        local.extend(0x0403_4b50u32.to_le_bytes());
        local.extend([20, 0, 0, 0]); // version, flags
        local.extend(method.to_le_bytes());
        local.extend([0; 4]); // mod time/date
        local.extend(crc.to_le_bytes());
        local.extend((compressed.len() as u32).to_le_bytes());
        local.extend((raw_data.len() as u32).to_le_bytes());
        local.extend((entry.name.len() as u16).to_le_bytes());
        local.extend(0u16.to_le_bytes()); // extra len
        local.extend(entry.name.as_bytes());
        out.write_all(&local).map_err(EngineError::from)?;
        out.write_all(&compressed).map_err(EngineError::from)?;

        // Central directory entry
        central.extend(0x0201_4b50u32.to_le_bytes());
        central.extend([20, 0, 20, 0, 0, 0]); // versions, flags
        central.extend(method.to_le_bytes());
        central.extend([0; 4]); // mod time/date
        central.extend(crc.to_le_bytes());
        central.extend((compressed.len() as u32).to_le_bytes());
        central.extend((raw_data.len() as u32).to_le_bytes());
        central.extend((entry.name.len() as u16).to_le_bytes());
        central.extend([0; 12]); // extra, comment, disk, attrs
        central.extend(offset.to_le_bytes());
        central.extend(entry.name.as_bytes());
    }

    let cd_offset = out.position() as u32;
    out.write_all(&central).map_err(EngineError::from)?;

    // End of central directory
    let mut eocd = Vec::new();
    eocd.extend(0x0605_4b50u32.to_le_bytes());
    eocd.extend([0; 4]); // disk numbers
    eocd.extend((entries.len() as u16).to_le_bytes());
    eocd.extend((entries.len() as u16).to_le_bytes());
    eocd.extend((central.len() as u32).to_le_bytes());
    eocd.extend(cd_offset.to_le_bytes());
    eocd.extend(0u16.to_le_bytes()); // comment len
    out.write_all(&eocd).map_err(EngineError::from)?;

    Ok(out.into_inner())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::zip::testing::zip;

    #[test]
    fn replace_in_xml_simple() {
        let xml = r#"<w:p><w:r><w:t>Hello World</w:t></w:r></w:p>"#;
        let (result, count) = replace_in_xml(xml, "World", "Rust", true, "w:t");
        assert_eq!(count, 1);
        assert!(result.contains("Hello Rust"));
    }

    #[test]
    fn replace_case_insensitive() {
        let xml = r#"<w:p><w:r><w:t>Hello WORLD</w:t></w:r></w:p>"#;
        let (result, count) = replace_in_xml(xml, "world", "Rust", false, "w:t");
        assert_eq!(count, 1);
        assert!(result.contains("Hello Rust"));
    }

    #[test]
    fn replace_multiple_occurrences() {
        let xml = r#"<w:p><w:r><w:t>foo bar foo</w:t></w:r></w:p>"#;
        let (result, count) = replace_in_xml(xml, "foo", "baz", true, "w:t");
        assert_eq!(count, 2);
        assert!(result.contains("baz bar baz"));
    }

    #[test]
    fn no_match_returns_zero() {
        let xml = r#"<w:p><w:r><w:t>Hello</w:t></w:r></w:p>"#;
        let (_, count) = replace_in_xml(xml, "xyz", "abc", true, "w:t");
        assert_eq!(count, 0);
    }

    #[test]
    fn pptx_tag() {
        let xml = r#"<a:p><a:r><a:t>Slide Title</a:t></a:r></a:p>"#;
        let (result, count) = replace_in_xml(xml, "Title", "Header", true, "a:t");
        assert_eq!(count, 1);
        assert!(result.contains("Slide Header"));
    }

    #[test]
    fn round_trip_docx() {
        let doc_xml = br#"<?xml version="1.0"?><w:body><w:p><w:r><w:t>Hello World</w:t></w:r></w:p></w:body>"#;
        let rels = b"rels";
        let data = zip(&[
            ("word/document.xml", doc_xml),
            ("_rels/.rels", rels),
        ]);
        let report = replace_in_docx(&data, "World", "Rust", true).unwrap();
        assert_eq!(report.matches, 1);

        let mut out = open(&report.output).unwrap();
        let result = String::from_utf8(out.read_to_vec("word/document.xml").unwrap()).unwrap();
        assert!(result.contains("Hello Rust"));
        assert_eq!(out.read_to_vec("_rels/.rels").unwrap(), rels);
    }

    #[test]
    fn round_trip_pptx() {
        let slide = br#"<p:sld><a:p><a:r><a:t>Old Title</a:t></a:r></a:p></p:sld>"#;
        let data = zip(&[
            ("ppt/slides/slide1.xml", slide),
            ("[Content_Types].xml", b"types"),
        ]);
        let report = replace_in_pptx(&data, "Old", "New", true).unwrap();
        assert_eq!(report.matches, 1);

        let mut out = open(&report.output).unwrap();
        let result = String::from_utf8(out.read_to_vec("ppt/slides/slide1.xml").unwrap()).unwrap();
        assert!(result.contains("New Title"));
    }

    #[test]
    fn similar_tags_are_not_text() {
        let xml = r#"<w:tbl><w:tr><w:tc><w:p><w:r><w:tab/><w:t>tbl</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"#;
        let (out, n) = replace_in_xml(xml, "w", "X", true, "w:t");
        assert_eq!(n, 0);
        assert_eq!(out, xml);
        let (out, n) = replace_in_xml(xml, "tbl", "T", true, "w:t");
        assert_eq!(n, 1);
        assert!(out.starts_with("<w:tbl><w:tr><w:tc>") && out.contains("<w:t>T</w:t>"));
    }

    #[test]
    fn entities_are_decoded_and_escaped() {
        let xml = r#"<w:t xml:space="preserve">A &amp; B</w:t>"#;
        let (out, n) = replace_in_xml(xml, "&", "<and>", true, "w:t");
        assert_eq!(n, 1);
        assert_eq!(out, r#"<w:t xml:space="preserve">A &lt;and&gt; B</w:t>"#);
    }

    #[test]
    fn ignore_case_handles_length_changing_lowercase() {
        let (out, n) = replace_text("İx İX ok", "ix", "y", false);
        assert_eq!(n, 0);
        assert_eq!(out, "İx İX ok");
        let (out, n) = replace_text("Straße STRASSE straße", "STRAßE", "road", false);
        assert_eq!(n, 2);
        assert_eq!(out, "road STRASSE road");
    }

    #[test]
    fn empty_find_is_rejected() {
        let data = zip(&[("word/document.xml", b"<w:t>x</w:t>")]);
        assert!(replace_in_docx(&data, "", "y", true).is_err());
    }

    #[test]
    fn odt_replace_text_nodes() {
        let xml = r#"<text:p text:style-name="P1">Hello <text:span text:style-name="T1">World</text:span> end</text:p>"#;
        let (result, count) = replace_text_nodes(xml, "World", "Rust", true);
        assert_eq!(count, 1);
        assert!(result.contains("Rust"));
        assert!(result.contains("Hello "));
        assert!(result.contains(" end"));
    }

    #[test]
    fn odt_replace_mixed_content() {
        let xml = r#"<text:p>foo bar</text:p><text:p>foo baz</text:p>"#;
        let (result, count) = replace_text_nodes(xml, "foo", "qux", true);
        assert_eq!(count, 2);
        assert!(result.contains("qux bar"));
        assert!(result.contains("qux baz"));
    }

    #[test]
    fn round_trip_odt() {
        let content = br#"<?xml version="1.0"?><office:document-content><office:body><office:text><text:p>Hello World</text:p></office:text></office:body></office:document-content>"#;
        let data = zip(&[
            ("content.xml", content),
            ("META-INF/manifest.xml", b"manifest"),
        ]);
        let report = replace_in_odt(&data, "World", "Rust", true).unwrap();
        assert_eq!(report.matches, 1);
        let mut out = open(&report.output).unwrap();
        let result = String::from_utf8(out.read_to_vec("content.xml").unwrap()).unwrap();
        assert!(result.contains("Hello Rust"));
    }

    #[test]
    fn round_trip_odp() {
        let content = br#"<?xml version="1.0"?><office:document-content><office:body><office:presentation><draw:page><draw:frame><draw:text-box><text:p>Slide Text</text:p></draw:text-box></draw:frame></draw:page></office:presentation></office:body></office:document-content>"#;
        let data = zip(&[("content.xml", content)]);
        let report = replace_in_odp(&data, "Slide", "New", true).unwrap();
        assert_eq!(report.matches, 1);
        let mut out = open(&report.output).unwrap();
        let result = String::from_utf8(out.read_to_vec("content.xml").unwrap()).unwrap();
        assert!(result.contains("New Text"));
    }

    #[test]
    fn rebuild_stores_mimetype_first() {
        let original = zip(&[("content.xml", b"<x/>".as_slice()), ("mimetype", b"application/vnd.oasis.opendocument.text".as_slice())]);
        let out = rebuild_zip(&original, &[]).unwrap();
        // First local header: method at byte 8, name length at 26, name at 30.
        assert_eq!(u16::from_le_bytes([out[8], out[9]]), 0, "mimetype must be stored");
        let name_len = u16::from_le_bytes([out[26], out[27]]) as usize;
        assert_eq!(&out[30..30 + name_len], b"mimetype");
        assert_eq!(&out[30 + name_len..30 + name_len + 8], b"applicat");
    }
}
