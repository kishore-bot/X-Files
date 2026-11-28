//! An invisible, searchable text layer for image-only pages (ISO 32000-1, 9.4.4
//! and 9.3.6). Each recognised line is drawn in text render mode 3 (neither fill
//! nor stroke), so it selects, copies and searches while the scan stays as it was.
//!
//! The layer is a standard incremental update: each page gets one more content
//! stream, its `Contents` becomes an array ending in that stream, and its
//! resources gain a Helvetica (WinAnsi) font. Every original byte survives as a
//! prefix, like the annotation writer in `pdf_annot`.
//!
//! Geometry: a line box is a fraction of the displayed page, origin top-left.
//! The affine map from display to user space (`PageGeometry::to_user`) gives the
//! box's baseline origin and its along-line and up-line vectors. Font size comes
//! from the box height and horizontal scaling (Tz) from the box width, so the
//! glyphs cover the ink wherever the page is rotated.

use std::collections::BTreeMap;
use std::io::{Read, Seek, Write};

use document_core::{EngineError, ErrorKind, Result};

use crate::pdf_annot::{PdfAppendReport, Update, object_body, write_update};
use crate::pdf_file::{MAX_OBJECT_NUMBER, PageGeometry, PageRef, PdfFile};
use crate::pdf_syntax::{Dict, Obj, format_number, write_obj};

/// Lines added in one save.
const MAX_TEXT_LINES: usize = 100_000;
/// Characters in one line; a recognised line is far shorter.
const MAX_LINE_CHARS: usize = 2_000;
/// Resource name of the layer's font. Unlikely to clash with a page's own names.
const FONT_KEY: &str = "XfTextHelv";
/// Helvetica's ink height (cap height plus descender) in ems, so a line's box
/// holds its glyphs exactly.
const INK_EM: f64 = 0.93;
/// Helvetica's descender in ems: where the baseline sits above the box bottom.
const DESCENT_EM: f64 = 0.21;
/// Depth limit when walking a page's /Parent chain for inherited resources.
const MAX_PARENT_DEPTH: usize = 32;

/// One recognised line. Geometry is in fractions (0..1) of the displayed page,
/// origin top-left, as for `PdfAnnotation`.
#[derive(Debug, Clone, PartialEq)]
pub struct TextLine {
    pub page: u32,
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
    pub text: String,
}

/// Helvetica advance widths for bytes 32..=126 in 1/1000 em (AFM metrics).
const HELVETICA_WIDTHS: [u16; 95] = [
    278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, //
    556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, //
    1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, //
    667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, //
    333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, //
    556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
];

/// Maps text to WinAnsi bytes. Latin-1 and the common typographic marks keep
/// their codes; anything else becomes `?`, and white space becomes a space.
pub(crate) fn winansi(text: &str) -> Vec<u8> {
    text.chars()
        .map(|c| match c {
            '\t' | '\n' | '\r' => b' ',
            ' '..='~' | '\u{A0}'..='\u{FF}' => c as u8,
            '\u{20AC}' => 0x80,
            '\u{201A}' => 0x82,
            '\u{0192}' => 0x83,
            '\u{201E}' => 0x84,
            '\u{2026}' => 0x85,
            '\u{2020}' => 0x86,
            '\u{2021}' => 0x87,
            '\u{02C6}' => 0x88,
            '\u{2030}' => 0x89,
            '\u{0160}' => 0x8A,
            '\u{2039}' => 0x8B,
            '\u{0152}' => 0x8C,
            '\u{017D}' => 0x8E,
            '\u{2018}' => 0x91,
            '\u{2019}' => 0x92,
            '\u{201C}' => 0x93,
            '\u{201D}' => 0x94,
            '\u{2022}' => 0x95,
            '\u{2013}' => 0x96,
            '\u{2014}' => 0x97,
            '\u{02DC}' => 0x98,
            '\u{2122}' => 0x99,
            '\u{0161}' => 0x9A,
            '\u{203A}' => 0x9B,
            '\u{0153}' => 0x9C,
            '\u{017E}' => 0x9E,
            '\u{0178}' => 0x9F,
            _ => b'?',
        })
        .collect()
}

/// Advance of one WinAnsi byte in ems. Bytes outside the printable table use
/// the width of `n`, a fair middle value for accented letters.
fn glyph_em(byte: u8) -> f64 {
    match byte {
        32..=126 => f64::from(HELVETICA_WIDTHS[usize::from(byte - 32)]) / 1000.0,
        _ => 0.556,
    }
}

pub(crate) fn advance_em(bytes: &[u8]) -> f64 {
    bytes.iter().map(|&b| glyph_em(b)).sum()
}

/// Text placement for one line, in user space: `m` is the text matrix
/// `[a b c d e f]`, `size` the font size and `scale` the horizontal scaling in
/// percent (Tz).
#[derive(Debug, Clone, Copy, PartialEq)]
pub(crate) struct TextMatrix {
    pub(crate) size: f64,
    pub(crate) scale: f64,
    pub(crate) m: [f64; 6],
}

/// The box-to-text map (the DSA core): the box's bottom-left corner is the
/// baseline origin, shifted up by the descender; its right edge fixes the
/// horizontal scale so the advance of the text equals the box width.
pub(crate) fn text_matrix(
    left: f64,
    top: f64,
    right: f64,
    bottom: f64,
    geometry: &PageGeometry,
    advance: f64,
) -> Option<TextMatrix> {
    let origin = geometry.to_user(left, bottom);
    let across = sub(geometry.to_user(right, bottom), origin);
    let up = sub(geometry.to_user(left, top), origin);
    let width = hypot(across);
    let height = hypot(up);
    if !(width.is_finite() && height.is_finite() && width > 0.0 && height > 0.0) {
        return None;
    }
    let (ux, uy) = (across.0 / width, across.1 / width);
    let (vx, vy) = (up.0 / height, up.1 / height);
    let size = height / INK_EM;
    let advance_user = advance * size;
    if advance_user <= 0.0 {
        return None;
    }
    let scale = 100.0 * width / advance_user;
    let start = (origin.0 + vx * DESCENT_EM * size, origin.1 + vy * DESCENT_EM * size);
    Some(TextMatrix { size, scale, m: [ux, uy, vx, vy, start.0, start.1] })
}

fn sub(a: (f64, f64), b: (f64, f64)) -> (f64, f64) {
    (a.0 - b.0, a.1 - b.1)
}

fn hypot(v: (f64, f64)) -> f64 {
    v.0.hypot(v.1)
}

/// Writes `text` as a PDF literal string body: delimiters escaped, every byte
/// outside printable ASCII as a three-digit octal escape.
fn escape_into(out: &mut String, bytes: &[u8]) {
    for &byte in bytes {
        match byte {
            b'(' | b')' | b'\\' => {
                out.push('\\');
                out.push(char::from(byte));
            }
            32..=126 => out.push(char::from(byte)),
            _ => out.push_str(&format!("\\{byte:03o}")),
        }
    }
}

/// The operators that draw one line invisibly, or `None` if it has no text or no
/// placeable box.
fn line_operators(line: &TextLine, geometry: &PageGeometry) -> Option<String> {
    let text = line.text.trim();
    let bytes = winansi(text);
    if bytes.is_empty() {
        return None;
    }
    let matrix = text_matrix(
        f64::from(line.left).clamp(0.0, 1.0),
        f64::from(line.top).clamp(0.0, 1.0),
        f64::from(line.right).clamp(0.0, 1.0),
        f64::from(line.bottom).clamp(0.0, 1.0),
        geometry,
        advance_em(&bytes),
    )?;
    let [a, b, c, d, e, f] = matrix.m;
    let mut out = format!(
        "BT 3 Tr /{FONT_KEY} {} Tf {} Tz {} {} {} {} {} {} Tm (",
        format_number(matrix.size),
        format_number(matrix.scale),
        format_number(a),
        format_number(b),
        format_number(c),
        format_number(d),
        format_number(e),
        format_number(f),
    );
    escape_into(&mut out, &bytes);
    out.push_str(") Tj ET\n");
    Some(out)
}

/// The page's content stream for its lines, wrapped in q/Q so nothing the page
/// left in the graphics state can move the text. `None` if no line is placeable.
fn content_stream(lines: &[&TextLine], geometry: &PageGeometry) -> Option<Vec<u8>> {
    let mut body = String::new();
    for line in lines {
        if let Some(operators) = line_operators(line, geometry) {
            body.push_str(&operators);
        }
    }
    if body.is_empty() {
        return None;
    }
    Some(format!("q\n{body}Q\n").into_bytes())
}

fn stream_body(content: &[u8]) -> Vec<u8> {
    let mut body = Vec::with_capacity(content.len() + 96);
    write_obj(&mut body, &Obj::Dict(Dict::of(vec![("Length", Obj::int(content.len() as u64))])));
    body.extend_from_slice(b"\nstream\n");
    body.extend_from_slice(content);
    body.extend_from_slice(b"\nendstream");
    body
}

fn helvetica_font() -> Dict {
    Dict::of(vec![
        ("Type", Obj::name("Font")),
        ("Subtype", Obj::name("Type1")),
        ("BaseFont", Obj::name("Helvetica")),
        ("Encoding", Obj::name("WinAnsiEncoding")),
    ])
}

fn check_lines(lines: &[TextLine]) -> Result<()> {
    if lines.len() > MAX_TEXT_LINES {
        return Err(EngineError::limit("too many lines of text to add at once"));
    }
    for line in lines {
        if [line.left, line.top, line.right, line.bottom].iter().any(|v| !v.is_finite()) {
            return Err(EngineError::new(ErrorKind::Internal, "a text line has an invalid position"));
        }
        if line.text.chars().count() > MAX_LINE_CHARS {
            return Err(EngineError::limit("a line of text is too long"));
        }
    }
    Ok(())
}

/// Resources a page draws with: its own, else the nearest ancestor's (the
/// /Resources key is inheritable). Empty if none is found.
fn inherited_resources<R: Read + Seek>(file: &mut PdfFile<R>, page: &Dict) -> Result<Dict> {
    let mut found = page.get("Resources").cloned();
    let mut parent = page.get("Parent").cloned();
    for _ in 0..MAX_PARENT_DEPTH {
        if let Some(value) = found {
            return Ok(match file.resolve(&value)? {
                Obj::Dict(dict) => dict,
                _ => Dict::default(),
            });
        }
        let Some(reference) = parent else { break };
        let Obj::Dict(ancestor) = file.resolve(&reference)? else { break };
        found = ancestor.get("Resources").cloned();
        parent = ancestor.get("Parent").cloned();
    }
    Ok(Dict::default())
}

/// Builds the update: one font object shared by all pages, and per page a new
/// content stream plus the page dictionary with its /Contents and /Resources extended.
fn build_update<R: Read + Seek>(file: &mut PdfFile<R>, pages: &[PageRef], lines: &[TextLine]) -> Result<Update> {
    let mut by_page: BTreeMap<u32, Vec<&TextLine>> = BTreeMap::new();
    for line in lines.iter().filter(|line| !line.text.trim().is_empty()) {
        by_page.entry(line.page).or_default().push(line);
    }
    let mut next_number = file.next_object_number()?;
    let font_number = next_number;
    next_number += 1;
    let mut objects = vec![(font_number, 0, object_body(&helvetica_font()))];
    for (page_number, group) in by_page {
        let page = pages.get(page_number as usize).ok_or_else(|| {
            EngineError::new(ErrorKind::Internal, "a text line is on a page that doesn't exist")
        })?;
        let Some(content) = content_stream(&group, &page.geometry) else { continue };
        let stream_number = next_number;
        next_number += 1;
        if next_number > MAX_OBJECT_NUMBER {
            return Err(EngineError::limit("the PDF has no object numbers left"));
        }
        objects.push((stream_number, 0, stream_body(&content)));

        let generation = file.generation_of(page.object);
        let Obj::Dict(mut page_dict) = file.object(page.object)? else {
            return Err(EngineError::malformed("the PDF's page tree is damaged"));
        };
        let mut contents = match page_dict.get("Contents") {
            None | Some(Obj::Null) => Vec::new(),
            Some(Obj::Array(items)) => items.clone(),
            Some(other) => vec![other.clone()],
        };
        contents.push(Obj::Ref(stream_number, 0));

        let mut resources = inherited_resources(file, &page_dict)?;
        let mut fonts = match resources.get("Font").cloned() {
            Some(value) => match file.resolve(&value)? {
                Obj::Dict(dict) => dict,
                _ => Dict::default(),
            },
            None => Dict::default(),
        };
        fonts.set(FONT_KEY, Obj::Ref(font_number, 0));
        resources.set("Font", Obj::Dict(fonts));

        page_dict.set("Contents", Obj::Array(contents));
        page_dict.set("Resources", Obj::Dict(resources));
        objects.push((page.object, generation, object_body(&page_dict)));
    }
    if objects.len() == 1 {
        return Err(EngineError::new(ErrorKind::Internal, "none of the text lines could be placed"));
    }
    Ok(Update { objects, next_number })
}

/// Writes the original file followed by an update that adds `lines` as an
/// invisible, searchable text layer. Lines on pages that don't exist are refused.
pub fn add_text_layer<R: Read + Seek, W: Write>(
    source: R,
    out: &mut W,
    lines: &[TextLine],
) -> Result<PdfAppendReport> {
    check_lines(lines)?;
    let mut file = PdfFile::open(source)?;
    if file.trailer.get("Encrypt").is_some() {
        return Err(EngineError::unsupported("password-protected PDFs can't get a text layer"));
    }
    let pages = file.pages()?;
    let update = build_update(&mut file, &pages, lines)?;
    write_update(&mut file, &update, out)
}

/// Re-reads a finished text layer and checks the page count is unchanged and
/// every page still parses.
pub fn verify_text_layer<R: Read + Seek>(saved: R, original_pages: u32) -> Result<()> {
    let mut file = PdfFile::open(saved)?;
    let pages = file.pages()?;
    if pages.len() as u32 != original_pages {
        return Err(EngineError::new(ErrorKind::Internal, "the saved PDF did not verify"));
    }
    for page in &pages {
        file.object(page.object)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;

    /// A classic PDF with the given object bodies, numbered from 1.
    fn classic_pdf(objects: &[&str]) -> Vec<u8> {
        let mut out = b"%PDF-1.7\n%\xE2\xE3\xCF\xD3\n".to_vec();
        let mut offsets = Vec::new();
        for (index, body) in objects.iter().enumerate() {
            offsets.push(out.len());
            out.extend_from_slice(format!("{} 0 obj\n{body}\nendobj\n", index + 1).as_bytes());
        }
        let xref_at = out.len();
        out.extend_from_slice(format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).as_bytes());
        for offset in offsets {
            out.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
        }
        out.extend_from_slice(
            format!("trailer\n<</Size {} /Root 1 0 R>>\nstartxref\n{xref_at}\n%%EOF\n", objects.len() + 1).as_bytes(),
        );
        out
    }

    /// One 600 x 800 page whose resources are inherited from the page tree.
    fn scanned_page(rotate: u32) -> Vec<u8> {
        classic_pdf(&[
            "<</Type /Catalog /Pages 2 0 R>>",
            "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 600 800] /Resources <</ProcSet [/PDF]>>>>",
            &format!("<</Type /Page /Parent 2 0 R /Contents 4 0 R /Rotate {rotate}>>"),
            "<</Length 0>>\nstream\n\nendstream",
        ])
    }

    fn geometry_of(bytes: &[u8]) -> PageGeometry {
        let mut file = PdfFile::open(Cursor::new(bytes)).unwrap();
        file.pages().unwrap()[0].geometry
    }

    fn line(text: &str, left: f32, top: f32, right: f32, bottom: f32) -> TextLine {
        TextLine { page: 0, left, top, right, bottom, text: text.to_owned() }
    }

    fn contains(haystack: &[u8], needle: &[u8]) -> bool {
        haystack.windows(needle.len()).any(|w| w == needle)
    }

    #[test]
    fn winansi_keeps_latin1_maps_marks_and_falls_back() {
        assert_eq!(winansi("é€ “x”"), vec![0xE9, 0x80, b' ', 0x93, b'x', 0x94]);
        assert_eq!(winansi("漢a\tb"), vec![b'?', b'a', b' ', b'b']);
    }

    #[test]
    fn delimiters_and_high_bytes_are_escaped() {
        let mut out = String::new();
        escape_into(&mut out, b"(a)\\\xE9\n");
        assert_eq!(out, "\\(a\\)\\\\\\351\\012");
    }

    #[test]
    fn advances_follow_helvetica_metrics() {
        // H 722, e 556, l 222, l 222, o 556 (1/1000 em).
        assert!((advance_em(b"Hello") - 2.278).abs() < 1e-9);
        assert!((advance_em(&[0xE9]) - 0.556).abs() < 1e-9);
    }

    #[test]
    fn upright_box_maps_to_baseline_and_width() {
        let bytes = scanned_page(0);
        let geometry = geometry_of(&bytes);
        let advance = advance_em(b"Invoice");
        let m = text_matrix(0.1, 0.1, 0.5, 0.2, &geometry, advance).unwrap();
        // The box is 80 pt high on an 800 pt page: its bottom edge is at y = 640.
        let size = 80.0 / INK_EM;
        assert!((m.size - size).abs() < 1e-9);
        assert!((m.m[4] - 60.0).abs() < 1e-9, "x starts at 0.1 of 600");
        assert!((m.m[5] - (640.0 + DESCENT_EM * size)).abs() < 1e-9);
        assert!((m.m[0] - 1.0).abs() < 1e-9 && m.m[1].abs() < 1e-9);
        assert!(m.m[2].abs() < 1e-9 && (m.m[3] - 1.0).abs() < 1e-9);
        // The advance, scaled by Tz, fills exactly the box width of 240 pt.
        assert!((advance * m.size * m.scale / 100.0 - 240.0).abs() < 1e-9);
    }

    #[test]
    fn rotated_page_keeps_the_width_and_turns_the_text() {
        let bytes = scanned_page(90);
        let geometry = geometry_of(&bytes);
        let advance = advance_em(b"Rotated");
        let m = text_matrix(0.1, 0.1, 0.5, 0.2, &geometry, advance).unwrap();
        // Unit vectors: the text runs along a unit direction in user space.
        assert!((m.m[0].hypot(m.m[1]) - 1.0).abs() < 1e-9);
        assert!((m.m[2].hypot(m.m[3]) - 1.0).abs() < 1e-9);
        // The line's width in user space is 0.4 of the displayed width.
        let expected = 0.4 * geometry.display_width();
        assert!((advance * m.size * m.scale / 100.0 - expected).abs() < 1e-6);
        assert!(m.m[0].abs() < 1e-9 || m.m[1].abs() < 1e-9, "a quarter turn is axis-aligned");
    }

    #[test]
    fn degenerate_boxes_are_skipped() {
        let geometry = geometry_of(&scanned_page(0));
        assert!(text_matrix(0.5, 0.1, 0.5, 0.2, &geometry, 1.0).is_none());
        assert!(text_matrix(0.1, 0.2, 0.5, 0.2, &geometry, 1.0).is_none());
        assert!(line_operators(&line("   ", 0.1, 0.1, 0.5, 0.2), &geometry).is_none());
    }

    #[test]
    fn text_layer_is_an_incremental_update_with_invisible_text() {
        let original = scanned_page(0);
        let lines = [line("Invoice 42 (paid)", 0.1, 0.1, 0.5, 0.2)];
        let mut out = Vec::new();
        let report = add_text_layer(Cursor::new(&original), &mut out, &lines).unwrap();
        assert_eq!(report.original_len, original.len() as u64);
        assert_eq!(report.total_len, out.len() as u64);
        assert!(out.starts_with(&original));
        for needle in [
            &b"3 Tr"[..],
            b"/BaseFont /Helvetica",
            b"/Encoding /WinAnsiEncoding",
            b"/XfTextHelv",
            b"Invoice 42 \\(paid\\)",
        ] {
            assert!(contains(&out, needle), "missing {}", String::from_utf8_lossy(needle));
        }
        verify_text_layer(Cursor::new(&out), 1).unwrap();
    }

    #[test]
    fn contents_become_an_array_and_inherited_resources_are_kept() {
        let original = scanned_page(0);
        let lines = [line("Hi", 0.1, 0.1, 0.5, 0.2), line("There", 0.1, 0.3, 0.5, 0.4)];
        let mut out = Vec::new();
        add_text_layer(Cursor::new(&original), &mut out, &lines).unwrap();
        let mut file = PdfFile::open(Cursor::new(&out)).unwrap();
        let pages = file.pages().unwrap();
        let Obj::Dict(page) = file.object(pages[0].object).unwrap() else { panic!("page is not a dict") };
        let Some(Obj::Array(contents)) = page.get("Contents") else { panic!("contents are not an array") };
        assert_eq!(contents.len(), 2, "the original stream plus the text layer");
        let Some(Obj::Dict(resources)) = page.get("Resources") else { panic!("resources were not written") };
        assert!(resources.get("ProcSet").is_some(), "the inherited ProcSet survives");
        let Some(Obj::Dict(fonts)) = resources.get("Font") else { panic!("no font dictionary") };
        assert!(fonts.get(FONT_KEY).is_some());
    }

    #[test]
    fn bad_input_is_refused_without_writing() {
        let original = scanned_page(0);
        let mut out = Vec::new();
        let nan = [line("x", f32::NAN, 0.1, 0.5, 0.2)];
        let err = add_text_layer(Cursor::new(&original), &mut out, &nan).unwrap_err();
        assert_eq!(err.kind, ErrorKind::Internal);
        assert!(out.is_empty());

        let blank = [line("   ", 0.1, 0.1, 0.5, 0.2)];
        assert!(add_text_layer(Cursor::new(&original), &mut out, &blank).is_err());

        let off_page = [TextLine { page: 9, ..line("x", 0.1, 0.1, 0.5, 0.2) }];
        assert!(add_text_layer(Cursor::new(&original), &mut out, &off_page).is_err());
        assert!(out.is_empty());
    }
}
