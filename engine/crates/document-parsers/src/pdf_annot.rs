//! Annotations in a PDF: reading the ones a file has, and adding new ones as a
//! standard incremental update (ISO 32000-1, 7.5.6).
//!
//! The update appends objects and a new cross-reference section after the
//! original file, so every original byte survives as a prefix. Text stays text,
//! and a file with a signature keeps the bytes it signed. Annotations carry
//! appearance streams, because many readers draw only those.

use std::collections::HashMap;
use std::io::{Read, Seek, Write};

use document_core::{EngineError, Result};

use crate::pdf_file::{MAX_OBJECT_NUMBER, PageGeometry, PageRef, PdfFile, XrefEntry};
use crate::pdf_syntax::{Dict, Obj, format_number, write_dict, write_obj};

/// Added in one save.
const MAX_NEW_ANNOTATIONS: usize = 20_000;
const MAX_NEW_POINTS: usize = 1_000_000;
const MAX_NOTE_BYTES: usize = 64 * 1024;
/// Shown from a file: more than this is not worth the memory.
const MAX_READ_ANNOTATIONS: usize = 20_000;
const MAX_READ_POINTS: usize = 500_000;
/// Width of a sticky note's icon as a fraction of the displayed page width.
/// The reader's overlay draws at the same size.
const NOTE_ICON_FRACTION: f64 = 0.04;
const SIGNATURE_LABEL: &str = "Drawn signature (not a digital signature)";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PdfInkStyle {
    Pen,
    /// Translucent stroke that multiplies with the page, as a drawn highlight.
    Marker,
    /// A hand-drawn mark. No cryptography: labelled so in the file too.
    Signature,
}

/// Geometry is in fractions (0..1) of the page as displayed, origin top-left.
/// Colours are ARGB; alpha becomes the annotation's opacity.
#[derive(Debug, Clone, PartialEq)]
pub enum PdfAnnotation {
    Highlight { page: u32, color: u32, boxes: Vec<[f32; 4]> },
    Ink { page: u32, color: u32, width: f32, strokes: Vec<Vec<(f32, f32)>>, style: PdfInkStyle },
    Note { page: u32, color: u32, x: f32, y: f32, text: String },
}

impl PdfAnnotation {
    fn page(&self) -> u32 {
        match self {
            Self::Highlight { page, .. } | Self::Ink { page, .. } | Self::Note { page, .. } => *page,
        }
    }
}

/// What a reader may do with a file, and what it holds.
#[derive(Debug, Clone, PartialEq)]
pub struct PdfEditInfo {
    pub page_count: u32,
    /// Encrypted: no save is possible, and nothing else here is known.
    pub encrypted: bool,
    /// Has a digital signature or permissions dictionary; an update may invalidate it.
    pub signed: bool,
    /// Declares PDF/A conformance, which added annotations may break.
    pub archival: bool,
    pub annotations: Vec<PdfAnnotation>,
    /// More annotations exist than were returned.
    pub annotations_truncated: bool,
}

/// Sizes of a finished update; the original file is `original_len` bytes of it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PdfAppendReport {
    pub original_len: u64,
    pub total_len: u64,
}

/// Reads what the reader needs from a PDF. Fails with a typed error if the
/// structure can't be trusted, which also means it can't be saved to.
pub fn inspect_pdf<R: Read + Seek>(source: R) -> Result<PdfEditInfo> {
    let mut file = PdfFile::open(source)?;
    if file.trailer.get("Encrypt").is_some() {
        return Ok(PdfEditInfo {
            page_count: 0,
            encrypted: true,
            signed: false,
            archival: false,
            annotations: Vec::new(),
            annotations_truncated: false,
        });
    }
    let pages = file.pages()?;
    let (signed, archival) = file.warnings()?;
    let (annotations, annotations_truncated) = read_annotations(&mut file, &pages)?;
    Ok(PdfEditInfo {
        page_count: pages.len() as u32,
        encrypted: false,
        signed,
        archival,
        annotations,
        annotations_truncated,
    })
}

/// Writes the original file followed by an update adding `annotations`.
pub fn append_annotations<R: Read + Seek, W: Write>(
    source: R,
    out: &mut W,
    annotations: &[PdfAnnotation],
) -> Result<PdfAppendReport> {
    if annotations.is_empty() {
        return Err(EngineError::new(document_core::ErrorKind::Internal, "there are no annotations to save"));
    }
    check_budget(annotations)?;
    let mut file = PdfFile::open(source)?;
    if file.trailer.get("Encrypt").is_some() {
        return Err(EngineError::unsupported("password-protected PDFs can't be saved with annotations"));
    }
    let pages = file.pages()?;
    let update = build_update(&mut file, &pages, annotations)?;
    write_update(&mut file, &update, out)
}

/// Copies the whole original, then appends `update` as an incremental section.
/// Shared by every writer that adds objects without rewriting the original bytes.
pub(crate) fn write_update<R: Read + Seek, W: Write>(
    file: &mut PdfFile<R>,
    update: &Update,
    out: &mut W,
) -> Result<PdfAppendReport> {
    let original_len = file.len;
    // The last original byte decides whether a separator is needed.
    let total = file.copy_all(out)?;
    if total != original_len {
        return Err(EngineError::new(document_core::ErrorKind::Io, "the file changed while it was being saved"));
    }
    let needs_newline = file.last_byte()? != b'\n';
    let base = original_len + u64::from(needs_newline);
    if needs_newline {
        out.write_all(b"\n")?;
    }
    let section = update.serialize(&*file, base)?;
    out.write_all(&section)?;
    out.flush()?;
    Ok(PdfAppendReport { original_len, total_len: base + section.len() as u64 })
}

/// Re-reads a finished update and checks the annotations are reachable where
/// they should be: the last defence before a file is handed to the user.
pub fn verify_appended<R: Read + Seek>(saved: R, original_pages: u32, annotations: &[PdfAnnotation]) -> Result<()> {
    let mut file = PdfFile::open(saved)?;
    let pages = file.pages()?;
    if pages.len() as u32 != original_pages {
        return Err(EngineError::new(document_core::ErrorKind::Internal, "the saved PDF did not verify"));
    }
    let mut wanted: HashMap<u32, usize> = HashMap::new();
    for annotation in annotations {
        *wanted.entry(annotation.page()).or_default() += 1;
    }
    for (page, count) in wanted {
        let found = page_annots(&mut file, pages[page as usize].object)?.len();
        if found < count {
            return Err(EngineError::new(document_core::ErrorKind::Internal, "the saved PDF did not verify"));
        }
    }
    Ok(())
}

fn check_budget(annotations: &[PdfAnnotation]) -> Result<()> {
    if annotations.len() > MAX_NEW_ANNOTATIONS {
        return Err(EngineError::limit("too many annotations to save at once"));
    }
    let mut points = 0usize;
    for annotation in annotations {
        match annotation {
            PdfAnnotation::Highlight { boxes, .. } => points += boxes.len() * 4,
            PdfAnnotation::Ink { strokes, .. } => points += strokes.iter().map(Vec::len).sum::<usize>(),
            PdfAnnotation::Note { text, .. } => {
                if text.len() > MAX_NOTE_BYTES {
                    return Err(EngineError::limit("a note is too long to save"));
                }
            }
        }
    }
    if points > MAX_NEW_POINTS {
        return Err(EngineError::limit("the annotations are too detailed to save at once"));
    }
    Ok(())
}

impl<R: Read + Seek> PdfFile<R> {
    pub(crate) fn generation_of(&self, number: u32) -> u16 {
        match self.xref.get(&number) {
            Some(XrefEntry::Plain { generation, .. }) => *generation,
            _ => 0,
        }
    }

    pub(crate) fn next_object_number(&self) -> Result<u32> {
        let size = self.trailer.get("Size").and_then(Obj::as_i64).unwrap_or(0).max(0);
        let highest = self.xref.keys().copied().max().map_or(0, |n| i64::from(n) + 1);
        let next = size.max(highest);
        u32::try_from(next)
            .ok()
            .filter(|n| *n <= MAX_OBJECT_NUMBER)
            .ok_or_else(|| EngineError::malformed("the PDF's object count is out of range"))
    }
}

/// An update's objects, before any file position is known.
pub(crate) struct Update {
    pub(crate) objects: Vec<(u32, u16, Vec<u8>)>,
    pub(crate) next_number: u32,
}

fn page_annots<R: Read + Seek>(file: &mut PdfFile<R>, page_object: u32) -> Result<Vec<Obj>> {
    let Obj::Dict(page) = file.object(page_object)? else {
        return Err(EngineError::malformed("the PDF's page tree is damaged"));
    };
    let annots = match page.get("Annots") {
        Some(value) => file.resolve(value)?,
        None => Obj::Null,
    };
    Ok(match annots {
        Obj::Array(items) => items,
        _ => Vec::new(),
    })
}

fn build_update<R: Read + Seek>(
    file: &mut PdfFile<R>,
    pages: &[PageRef],
    annotations: &[PdfAnnotation],
) -> Result<Update> {
    let mut next_number = file.next_object_number()?;
    let mut objects = Vec::new();
    let mut by_page: HashMap<u32, Vec<&PdfAnnotation>> = HashMap::new();
    for annotation in annotations {
        by_page.entry(annotation.page()).or_default().push(annotation);
    }
    // A stable file for the same input: pages in order, annotations in the order given.
    let mut page_numbers: Vec<u32> = by_page.keys().copied().collect();
    page_numbers.sort_unstable();
    for page_number in page_numbers {
        let page = pages.get(page_number as usize).ok_or_else(|| {
            EngineError::new(document_core::ErrorKind::Internal, "an annotation is on a page that doesn't exist")
        })?;
        let generation = file.generation_of(page.object);
        let mut annots = page_annots(file, page.object)?;
        for annotation in &by_page[&page_number] {
            // Annotation and appearance numbers come in pairs.
            let (annotation_number, form_number) = (next_number, next_number + 1);
            next_number += 2;
            if next_number > MAX_OBJECT_NUMBER {
                return Err(EngineError::limit("the PDF has no object numbers left"));
            }
            let (annotation_dict, form) = annotation_objects(
                annotation,
                &page.geometry,
                Obj::Ref(page.object, generation),
                annotation_number,
                form_number,
            )?;
            objects.push((annotation_number, 0, object_body(&annotation_dict)));
            objects.push((form_number, 0, form));
            annots.push(Obj::Ref(annotation_number, 0));
        }
        let Obj::Dict(mut page_dict) = file.object(page.object)? else {
            return Err(EngineError::malformed("the PDF's page tree is damaged"));
        };
        page_dict.set("Annots", Obj::Array(annots));
        objects.push((page.object, generation, object_body(&page_dict)));
    }
    Ok(Update { objects, next_number })
}

pub(crate) fn object_body(dict: &Dict) -> Vec<u8> {
    let mut out = Vec::new();
    write_dict(&mut out, dict);
    out
}

fn numbers(values: impl IntoIterator<Item = f64>) -> Obj {
    Obj::Array(values.into_iter().map(Obj::number).collect())
}

fn rgb_of(color: u32) -> [f64; 3] {
    [(color >> 16 & 0xFF) as f64 / 255.0, (color >> 8 & 0xFF) as f64 / 255.0, (color & 0xFF) as f64 / 255.0]
}

fn alpha_of(color: u32) -> f64 {
    (color >> 24 & 0xFF) as f64 / 255.0
}

fn clamp_unit(value: f32) -> Result<f64> {
    if value.is_finite() {
        Ok(f64::from(value).clamp(0.0, 1.0))
    } else {
        Err(EngineError::new(document_core::ErrorKind::Internal, "an annotation has an invalid position"))
    }
}

/// A growing bounding box in user space.
#[derive(Clone, Copy)]
struct Bounds([f64; 4]);

impl Bounds {
    fn empty() -> Self {
        Self([f64::MAX, f64::MAX, f64::MIN, f64::MIN])
    }

    fn add(&mut self, (x, y): (f64, f64)) {
        self.0 = [self.0[0].min(x), self.0[1].min(y), self.0[2].max(x), self.0[3].max(y)];
    }

    fn padded(self, by: f64) -> [f64; 4] {
        [self.0[0] - by, self.0[1] - by, self.0[2] + by, self.0[3] + by]
    }
}

fn point_text(out: &mut String, (x, y): (f64, f64), operator: &str) {
    out.push_str(&format_number(x));
    out.push(' ');
    out.push_str(&format_number(y));
    out.push(' ');
    out.push_str(operator);
    out.push('\n');
}

/// Returns the annotation dictionary and the full body of its appearance stream.
fn annotation_objects(
    annotation: &PdfAnnotation,
    geometry: &PageGeometry,
    page: Obj,
    number: u32,
    form_number: u32,
) -> Result<(Dict, Vec<u8>)> {
    let mut content = String::new();
    let (mut dict, rect, color, multiply) = match annotation {
        PdfAnnotation::Highlight { color, boxes, .. } => {
            if boxes.is_empty() {
                return Err(EngineError::new(document_core::ErrorKind::Internal, "a highlight covers nothing"));
            }
            let [r, g, b] = rgb_of(*color);
            content.push_str(&format!("/G0 gs {} {} {} rg\n", format_number(r), format_number(g), format_number(b)));
            let mut bounds = Bounds::empty();
            let mut quads = Vec::with_capacity(boxes.len() * 8);
            for [left, top, right, bottom] in boxes {
                let (left, top, right, bottom) =
                    (clamp_unit(*left)?, clamp_unit(*top)?, clamp_unit(*right)?, clamp_unit(*bottom)?);
                let corners = [
                    geometry.to_user(left, top),
                    geometry.to_user(right, top),
                    geometry.to_user(left, bottom),
                    geometry.to_user(right, bottom),
                ];
                for corner in corners {
                    bounds.add(corner);
                    quads.extend([corner.0, corner.1]);
                }
                point_text(&mut content, corners[0], "m");
                point_text(&mut content, corners[1], "l");
                point_text(&mut content, corners[3], "l");
                point_text(&mut content, corners[2], "l");
                content.push_str("h f\n");
            }
            let dict = Dict::of(vec![("Subtype", Obj::name("Highlight")), ("QuadPoints", numbers(quads))]);
            (dict, bounds.padded(0.5), *color, true)
        }
        PdfAnnotation::Ink { color, width, strokes, style, .. } => {
            if strokes.iter().all(Vec::is_empty) || !width.is_finite() {
                return Err(EngineError::new(document_core::ErrorKind::Internal, "a stroke has no points"));
            }
            let stroke_width = (f64::from(*width) * geometry.display_width()).clamp(0.1, 200.0);
            let [r, g, b] = rgb_of(*color);
            content.push_str(&format!(
                "/G0 gs {} {} {} RG {} w 1 J 1 j\n",
                format_number(r),
                format_number(g),
                format_number(b),
                format_number(stroke_width)
            ));
            let mut bounds = Bounds::empty();
            let mut ink_list = Vec::with_capacity(strokes.len());
            for stroke in strokes.iter().filter(|s| !s.is_empty()) {
                let mut path = Vec::with_capacity(stroke.len() * 2);
                for (index, (u, v)) in stroke.iter().enumerate() {
                    let point = geometry.to_user(clamp_unit(*u)?, clamp_unit(*v)?);
                    bounds.add(point);
                    path.extend([point.0, point.1]);
                    point_text(&mut content, point, if index == 0 { "m" } else { "l" });
                }
                // A tap: a zero-length line with round caps draws a dot in every reader.
                if stroke.len() == 1 {
                    let point = (path[0], path[1]);
                    point_text(&mut content, point, "l");
                }
                ink_list.push(numbers(path));
                content.push_str("S\n");
            }
            let border = Dict::of(vec![("W", Obj::number(stroke_width)), ("S", Obj::name("S"))]);
            let mut dict = Dict::of(vec![
                ("Subtype", Obj::name("Ink")),
                ("InkList", Obj::Array(ink_list)),
                ("BS", Obj::Dict(border)),
            ]);
            match style {
                PdfInkStyle::Pen => {}
                PdfInkStyle::Marker => {
                    dict.set("BM", Obj::name("Multiply"));
                    dict.set("Subj", Obj::Str(b"Highlight".to_vec()));
                }
                PdfInkStyle::Signature => {
                    dict.set("Subj", Obj::Str(SIGNATURE_LABEL.as_bytes().to_vec()));
                    dict.set("Contents", Obj::Str(SIGNATURE_LABEL.as_bytes().to_vec()));
                }
            }
            (dict, bounds.padded(stroke_width / 2.0 + 1.0), *color, *style == PdfInkStyle::Marker)
        }
        PdfAnnotation::Note { color, x, y, text, .. } => {
            let half_u = NOTE_ICON_FRACTION / 2.0;
            let display_height = geometry.width() * geometry.height() / geometry.display_width();
            let half_v = half_u * geometry.display_width() / display_height;
            let u = clamp_unit(*x)?.clamp(half_u, 1.0 - half_u);
            let v = clamp_unit(*y)?.clamp(half_v, 1.0 - half_v);
            let [r, g, b] = rgb_of(*color);
            let line = (geometry.display_width() * NOTE_ICON_FRACTION / 16.0).max(0.25);
            content.push_str(&format!(
                "/G0 gs {} {} {} rg 0.25 0.2 0.1 RG {} w\n",
                format_number(r),
                format_number(g),
                format_number(b),
                format_number(line)
            ));
            let mut bounds = Bounds::empty();
            // Drawn in display fractions, so the icon stays upright on rotated pages.
            let at = |du: f64, dv: f64| geometry.to_user(u + du * half_u, v + dv * half_v);
            for (index, corner) in [(-1.0, -1.0), (1.0, -1.0), (1.0, 1.0), (-1.0, 1.0)].into_iter().enumerate() {
                let point = at(corner.0, corner.1);
                bounds.add(point);
                point_text(&mut content, point, if index == 0 { "m" } else { "l" });
            }
            content.push_str("h B\n");
            for row in [-0.45, 0.0, 0.45] {
                point_text(&mut content, at(-0.6, row), "m");
                point_text(&mut content, at(0.6, row), "l");
                content.push_str("S\n");
            }
            let dict = Dict::of(vec![
                ("Subtype", Obj::name("Text")),
                ("Name", Obj::name("Comment")),
                ("Contents", Obj::Str(text_string(text))),
                ("Open", Obj::Bool(false)),
            ]);
            (dict, bounds.padded(line), *color, false)
        }
    };

    let alpha = alpha_of(color);
    let [r, g, b] = rgb_of(color);
    dict.set("Type", Obj::name("Annot"));
    dict.set("Rect", numbers(rect));
    dict.set("P", page);
    dict.set("C", numbers([r, g, b]));
    dict.set("CA", Obj::number(alpha));
    // Print: annotations should reach paper, too.
    dict.set("F", Obj::int(4));
    dict.set("NM", Obj::Str(format!("ezfiles-{number}").into_bytes()));
    dict.set("AP", Obj::Dict(Dict::of(vec![("N", Obj::Ref(form_number, 0))])));

    let mut graphics_state =
        Dict::of(vec![("Type", Obj::name("ExtGState")), ("ca", Obj::number(alpha)), ("CA", Obj::number(alpha))]);
    if multiply {
        graphics_state.set("BM", Obj::name("Multiply"));
    }
    let resources = Dict::of(vec![("ExtGState", Obj::Dict(Dict::of(vec![("G0", Obj::Dict(graphics_state))])))]);
    let form = Dict::of(vec![
        ("Type", Obj::name("XObject")),
        ("Subtype", Obj::name("Form")),
        ("FormType", Obj::int(1)),
        ("BBox", numbers(rect)),
        ("Resources", Obj::Dict(resources)),
        ("Length", Obj::int(content.len() as u64)),
    ]);
    let mut body = Vec::with_capacity(content.len() + 256);
    write_obj(&mut body, &Obj::Dict(form));
    body.extend_from_slice(b"\nstream\n");
    body.extend_from_slice(content.as_bytes());
    body.extend_from_slice(b"\nendstream");
    Ok((dict, body))
}

/// A text string: plain ASCII as is, anything else as UTF-16BE with its marker.
fn text_string(text: &str) -> Vec<u8> {
    if text.is_ascii() {
        return text.as_bytes().to_vec();
    }
    let mut bytes = vec![0xFE, 0xFF];
    for unit in text.encode_utf16() {
        bytes.extend_from_slice(&unit.to_be_bytes());
    }
    bytes
}

fn read_text_string(bytes: &[u8]) -> String {
    if let Some(utf16) = bytes.strip_prefix(&[0xFE, 0xFF]) {
        let units: Vec<u16> = utf16.chunks_exact(2).map(|pair| u16::from_be_bytes([pair[0], pair[1]])).collect();
        String::from_utf16_lossy(&units)
    } else if let Some(utf8) = bytes.strip_prefix(&[0xEF, 0xBB, 0xBF]) {
        String::from_utf8_lossy(utf8).into_owned()
    } else {
        // PDFDocEncoding matches Latin-1 for everything a note is likely to hold.
        bytes.iter().map(|b| char::from(*b)).collect()
    }
}

impl Update {
    /// The appended bytes, with offsets counted from `base`.
    pub(crate) fn serialize<R: Read + Seek>(&self, file: &PdfFile<R>, base: u64) -> Result<Vec<u8>> {
        let mut out = Vec::new();
        let mut entries: Vec<(u32, u16, u64)> = Vec::with_capacity(self.objects.len() + 1);
        for (number, generation, body) in &self.objects {
            entries.push((*number, *generation, base + out.len() as u64));
            out.extend_from_slice(format!("{number} {generation} obj\n").as_bytes());
            out.extend_from_slice(body);
            out.extend_from_slice(b"\nendobj\n");
        }
        // Keys a reader needs, carried over from the newest trailer.
        let mut trailer = Dict::default();
        for key in ["Root", "Info", "ID"] {
            if let Some(value) = file.trailer.get(key) {
                trailer.set(key, value.clone());
            }
        }
        trailer.set("Prev", Obj::int(file.last_xref_offset));
        if file.last_xref_is_stream {
            self.write_xref_stream(&mut out, trailer, entries, base);
        } else {
            self.write_xref_table(&mut out, trailer, entries, base);
        }
        Ok(out)
    }

    fn write_xref_table(&self, out: &mut Vec<u8>, mut trailer: Dict, mut entries: Vec<(u32, u16, u64)>, base: u64) {
        entries.sort_unstable();
        let xref_at = base + out.len() as u64;
        // The optional object-zero entry: some readers expect any table to open with it.
        out.extend_from_slice(b"xref\n0 1\n0000000000 65535 f \n");
        for run in contiguous_runs(&entries) {
            out.extend_from_slice(format!("{} {}\n", run[0].0, run.len()).as_bytes());
            for (_, generation, offset) in run {
                // Exactly 20 bytes each, as the format demands.
                out.extend_from_slice(format!("{offset:010} {generation:05} n \n").as_bytes());
            }
        }
        trailer.set("Size", Obj::int(u64::from(self.next_number)));
        out.extend_from_slice(b"trailer\n");
        write_dict(out, &trailer);
        out.extend_from_slice(format!("\nstartxref\n{xref_at}\n%%EOF\n").as_bytes());
    }

    /// A file whose newest section is a stream may have objects only streams can
    /// describe, so the update has to continue in that form.
    fn write_xref_stream(&self, out: &mut Vec<u8>, trailer: Dict, mut entries: Vec<(u32, u16, u64)>, base: u64) {
        let own_number = self.next_number;
        let xref_at = base + out.len() as u64;
        entries.push((own_number, 0, xref_at));
        entries.sort_unstable();
        let offset_width = (1..=8).find(|w: &usize| *w == 8 || xref_at < 1u64 << (8 * *w)).unwrap_or(8);
        let mut data = Vec::with_capacity(entries.len() * (offset_width + 3));
        let mut index = Vec::new();
        for run in contiguous_runs(&entries) {
            index.push(Obj::int(u64::from(run[0].0)));
            index.push(Obj::int(run.len() as u64));
            for (_, generation, offset) in run {
                data.push(1);
                data.extend_from_slice(&offset.to_be_bytes()[8 - offset_width..]);
                data.extend_from_slice(&generation.to_be_bytes());
            }
        }
        let mut dict = trailer;
        dict.set("Type", Obj::name("XRef"));
        dict.set("Size", Obj::int(u64::from(own_number) + 1));
        dict.set("W", Obj::Array(vec![Obj::int(1), Obj::int(offset_width as u64), Obj::int(2)]));
        dict.set("Index", Obj::Array(index));
        dict.set("Length", Obj::int(data.len() as u64));
        out.extend_from_slice(format!("{own_number} 0 obj\n").as_bytes());
        write_dict(out, &dict);
        out.extend_from_slice(b"\nstream\n");
        out.extend_from_slice(&data);
        out.extend_from_slice(format!("\nendstream\nendobj\nstartxref\n{xref_at}\n%%EOF\n").as_bytes());
    }
}

fn contiguous_runs(entries: &[(u32, u16, u64)]) -> impl Iterator<Item = &[(u32, u16, u64)]> {
    entries.chunk_by(|a, b| a.0 + 1 == b.0)
}

fn read_annotations<R: Read + Seek>(file: &mut PdfFile<R>, pages: &[PageRef]) -> Result<(Vec<PdfAnnotation>, bool)> {
    let mut out = Vec::new();
    let mut points = 0usize;
    for (index, page) in pages.iter().enumerate() {
        // A broken annotation hides itself, not the page or the document.
        let Ok(annots) = page_annots(file, page.object) else { continue };
        for entry in &annots {
            if out.len() >= MAX_READ_ANNOTATIONS || points >= MAX_READ_POINTS {
                return Ok((out, true));
            }
            let Ok(Obj::Dict(dict)) = file.resolve(entry) else { continue };
            if let Some(annotation) = read_annotation(file, &dict, index as u32, &page.geometry, &mut points) {
                out.push(annotation);
            }
        }
    }
    Ok((out, false))
}

fn number_list<R: Read + Seek>(file: &mut PdfFile<R>, value: Option<&Obj>) -> Option<Vec<f64>> {
    let Obj::Array(items) = file.resolve(value?).ok()? else { return None };
    let mut numbers = Vec::with_capacity(items.len());
    for item in &items {
        numbers.push(file.resolve(item).ok()?.as_f64()?);
    }
    Some(numbers)
}

fn read_annotation<R: Read + Seek>(
    file: &mut PdfFile<R>,
    dict: &Dict,
    page: u32,
    geometry: &PageGeometry,
    points: &mut usize,
) -> Option<PdfAnnotation> {
    // Hidden or NoView: not for showing.
    let flags = dict.get("F").and_then(Obj::as_i64).unwrap_or(0);
    if flags & 2 != 0 || flags & 32 != 0 {
        return None;
    }
    let subtype = dict.get("Subtype").and_then(Obj::as_name)?.to_vec();
    let opacity = dict.get("CA").and_then(Obj::as_f64).unwrap_or(1.0).clamp(0.0, 1.0);
    let color = |fallback: u32| {
        let rgb = number_list(&mut *file, dict.get("C")).and_then(|c| color_from_components(&c)).unwrap_or(fallback);
        (rgb & 0x00FF_FFFF) | ((opacity * 255.0).round() as u32) << 24
    };
    let unit = |value: f64| value.clamp(0.0, 1.0) as f32;
    match subtype.as_slice() {
        b"Highlight" => {
            let color = {
                let mut color = color;
                color(0xFFEB3B)
            };
            let quads = number_list(file, dict.get("QuadPoints")).filter(|q| q.len() >= 8);
            let quads = quads.or_else(|| {
                let r = number_list(file, dict.get("Rect")).filter(|r| r.len() == 4)?;
                Some(vec![r[0], r[3], r[2], r[3], r[0], r[1], r[2], r[1]])
            })?;
            let boxes: Vec<[f32; 4]> = quads
                .chunks_exact(8)
                .map(|quad| {
                    let corners: Vec<(f64, f64)> =
                        quad.chunks_exact(2).map(|p| geometry.to_display(p[0], p[1])).collect();
                    let min_u = corners.iter().map(|c| c.0).fold(f64::MAX, f64::min);
                    let max_u = corners.iter().map(|c| c.0).fold(f64::MIN, f64::max);
                    let min_v = corners.iter().map(|c| c.1).fold(f64::MAX, f64::min);
                    let max_v = corners.iter().map(|c| c.1).fold(f64::MIN, f64::max);
                    [unit(min_u), unit(min_v), unit(max_u), unit(max_v)]
                })
                .collect();
            *points += boxes.len() * 4;
            Some(PdfAnnotation::Highlight { page, color, boxes })
        }
        b"Ink" => {
            let color = {
                let mut color = color;
                color(0)
            };
            let Obj::Array(lists) = file.resolve(dict.get("InkList")?).ok()? else { return None };
            let mut strokes = Vec::with_capacity(lists.len());
            for list in &lists {
                let flat = number_list(file, Some(list))?;
                *points += flat.len() / 2;
                if *points > MAX_READ_POINTS {
                    return None;
                }
                let stroke: Vec<(f32, f32)> = flat
                    .chunks_exact(2)
                    .map(|p| {
                        let (u, v) = geometry.to_display(p[0], p[1]);
                        (unit(u), unit(v))
                    })
                    .collect();
                strokes.push(stroke);
            }
            let width_points = match file.resolve(dict.get("BS").unwrap_or(&Obj::Null)).ok() {
                Some(Obj::Dict(border)) => border.get("W").and_then(Obj::as_f64),
                _ => None,
            }
            .or_else(|| number_list(file, dict.get("Border")).filter(|b| b.len() >= 3).map(|b| b[2]))
            .unwrap_or(1.0);
            let style = if dict.get("BM").and_then(Obj::as_name) == Some(b"Multiply") {
                PdfInkStyle::Marker
            } else if dict.get("Subj") == Some(&Obj::Str(SIGNATURE_LABEL.as_bytes().to_vec())) {
                PdfInkStyle::Signature
            } else {
                PdfInkStyle::Pen
            };
            Some(PdfAnnotation::Ink {
                page,
                color,
                width: (width_points / geometry.display_width()) as f32,
                strokes,
                style,
            })
        }
        b"Text" => {
            let color = {
                let mut color = color;
                color(0xFFD54F)
            };
            let rect = number_list(file, dict.get("Rect")).filter(|r| r.len() == 4)?;
            let (u, v) = geometry.to_display((rect[0] + rect[2]) / 2.0, (rect[1] + rect[3]) / 2.0);
            let text = match file.resolve(dict.get("Contents").unwrap_or(&Obj::Null)).ok() {
                Some(Obj::Str(bytes)) => read_text_string(&bytes),
                _ => String::new(),
            };
            Some(PdfAnnotation::Note { page, color, x: unit(u), y: unit(v), text })
        }
        _ => None,
    }
}

fn color_from_components(components: &[f64]) -> Option<u32> {
    let byte = |value: f64| (value.clamp(0.0, 1.0) * 255.0).round() as u32;
    match components {
        [gray] => Some(byte(*gray) << 16 | byte(*gray) << 8 | byte(*gray)),
        [r, g, b] => Some(byte(*r) << 16 | byte(*g) << 8 | byte(*b)),
        [c, m, y, k] => {
            Some(byte((1.0 - c) * (1.0 - k)) << 16 | byte((1.0 - m) * (1.0 - k)) << 8 | byte((1.0 - y) * (1.0 - k)))
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use document_core::ErrorKind;
    use flate2::Compression;
    use flate2::write::ZlibEncoder;

    use super::*;

    /// A classic PDF with the given object bodies, numbered from 1, where object
    /// 1 must be the catalog. Offsets and trailer are computed.
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

    fn one_page() -> Vec<u8> {
        classic_pdf(&[
            "<</Type /Catalog /Pages 2 0 R>>",
            "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 600 800]>>",
            "<</Type /Page /Parent 2 0 R /Contents 4 0 R /Marker (keep me)>>",
            "<</Length 0>>\nstream\n\nendstream",
        ])
    }

    fn deflate(bytes: &[u8]) -> Vec<u8> {
        let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(bytes).unwrap();
        encoder.finish().unwrap()
    }

    /// A PDF 1.5 file: page and tree inside a compressed object stream, located
    /// by a compressed cross-reference stream with a PNG-up predictor.
    fn compressed_pdf() -> Vec<u8> {
        let mut out = b"%PDF-1.5\n".to_vec();
        let catalog_at = out.len();
        out.extend_from_slice(b"1 0 obj\n<</Type /Catalog /Pages 2 0 R>>\nendobj\n");
        let inner = [
            "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 400 400] /Rotate 90>>",
            "<</Type /Page /Parent 2 0 R>>",
        ];
        let body = format!("{} {}", inner[0], inner[1]);
        let header = format!("2 0 3 {} ", inner[0].len() + 1);
        let stream = deflate(format!("{header}{body}").as_bytes());
        let objstm_at = out.len();
        out.extend_from_slice(
            format!(
                "4 0 obj\n<</Type /ObjStm /N 2 /First {} /Filter /FlateDecode /Length {}>>\nstream\n",
                header.len(),
                stream.len()
            )
            .as_bytes(),
        );
        out.extend_from_slice(&stream);
        out.extend_from_slice(b"\nendstream\nendobj\n");
        let xref_at = out.len();
        let rows: Vec<[u8; 4]> = vec![
            [0, 0, 0, 0],
            [1, (catalog_at >> 8) as u8, catalog_at as u8, 0],
            [2, 0, 4, 0],
            [2, 0, 4, 1],
            [1, (objstm_at >> 8) as u8, objstm_at as u8, 0],
            [1, (xref_at >> 8) as u8, xref_at as u8, 0],
        ];
        let mut predicted = Vec::new();
        let mut previous = [0u8; 4];
        for row in rows {
            predicted.push(2);
            for (value, up) in row.iter().zip(previous) {
                predicted.push(value.wrapping_sub(up));
            }
            previous = row;
        }
        let stream = deflate(&predicted);
        out.extend_from_slice(
            format!(
                "5 0 obj\n<</Type /XRef /Size 6 /W [1 2 1] /Root 1 0 R /Filter /FlateDecode /DecodeParms <</Predictor 12 /Columns 4>> /Length {}>>\nstream\n",
                stream.len()
            )
            .as_bytes(),
        );
        out.extend_from_slice(&stream);
        out.extend_from_slice(format!("\nendstream\nendobj\nstartxref\n{xref_at}\n%%EOF\n").as_bytes());
        out
    }

    fn all_kinds() -> Vec<PdfAnnotation> {
        vec![
            PdfAnnotation::Highlight {
                page: 0,
                color: 0x66FF_EB3B,
                boxes: vec![[0.1, 0.1, 0.5, 0.15], [0.1, 0.16, 0.3, 0.2]],
            },
            PdfAnnotation::Ink {
                page: 0,
                color: 0xFF11_2233,
                width: 0.005,
                strokes: vec![vec![(0.2, 0.3), (0.4, 0.5)], vec![(0.6, 0.6)]],
                style: PdfInkStyle::Pen,
            },
            PdfAnnotation::Ink {
                page: 0,
                color: 0x66FF_0000,
                width: 0.02,
                strokes: vec![vec![(0.1, 0.8), (0.9, 0.8)]],
                style: PdfInkStyle::Marker,
            },
            PdfAnnotation::Ink {
                page: 0,
                color: 0xFF00_0000,
                width: 0.003,
                strokes: vec![vec![(0.5, 0.9), (0.7, 0.92)]],
                style: PdfInkStyle::Signature,
            },
            PdfAnnotation::Note { page: 0, color: 0xFFFF_D54F, x: 0.8, y: 0.2, text: "Check (this) — naïve".into() },
        ]
    }

    fn save(original: &[u8], annotations: &[PdfAnnotation]) -> Vec<u8> {
        let mut out = Vec::new();
        let report = append_annotations(Cursor::new(original), &mut out, annotations).unwrap();
        assert_eq!(report.original_len, original.len() as u64);
        assert_eq!(report.total_len, out.len() as u64);
        out
    }

    fn inspect(bytes: &[u8]) -> Result<PdfEditInfo> {
        inspect_pdf(Cursor::new(bytes))
    }

    fn contains(haystack: &[u8], needle: &[u8]) -> bool {
        haystack.windows(needle.len()).any(|w| w == needle)
    }

    #[test]
    fn saved_file_keeps_the_original_as_a_prefix_and_reparses_with_annotations() {
        let original = one_page();
        let saved = save(&original, &all_kinds());
        assert!(saved.starts_with(&original));
        verify_appended(Cursor::new(&saved), 1, &all_kinds()).unwrap();
        let info = inspect(&saved).unwrap();
        assert_eq!(info.page_count, 1);
        assert_eq!(info.annotations.len(), 5);
        // The page kept what it had.
        let mut file = PdfFile::open(Cursor::new(&saved)).unwrap();
        let pages = file.pages().unwrap();
        let Obj::Dict(page) = file.object(pages[0].object).unwrap() else { panic!("page is not a dict") };
        assert_eq!(page.get("Marker"), Some(&Obj::Str(b"keep me".to_vec())));
        assert_eq!(page.get("Contents"), Some(&Obj::Ref(4, 0)));
        // The update chains to the original section.
        assert_ne!(file.last_xref_offset, 0);
        assert!(file.trailer.get("Prev").is_some());
    }

    #[test]
    fn annotations_read_back_as_written() {
        let saved = save(&one_page(), &all_kinds());
        let read = inspect(&saved).unwrap().annotations;
        let PdfAnnotation::Highlight { color, boxes, .. } = &read[0] else { panic!("{:?}", read[0]) };
        assert_eq!(*color >> 24, 0x66);
        assert_eq!(boxes.len(), 2);
        assert!((boxes[0][0] - 0.1).abs() < 1e-3 && (boxes[0][3] - 0.15).abs() < 1e-3, "{boxes:?}");
        let PdfAnnotation::Ink { strokes, width, style, .. } = &read[1] else { panic!("{:?}", read[1]) };
        assert_eq!(*style, PdfInkStyle::Pen);
        assert!((*width - 0.005).abs() < 1e-3);
        assert_eq!(strokes.len(), 2);
        assert!((strokes[0][1].0 - 0.4).abs() < 1e-3 && (strokes[0][1].1 - 0.5).abs() < 1e-3);
        assert!(matches!(read[2], PdfAnnotation::Ink { style: PdfInkStyle::Marker, .. }));
        assert!(matches!(read[3], PdfAnnotation::Ink { style: PdfInkStyle::Signature, .. }));
        let PdfAnnotation::Note { text, x, y, .. } = &read[4] else { panic!("{:?}", read[4]) };
        assert_eq!(text, "Check (this) — naïve");
        assert!((*x - 0.8).abs() < 1e-3 && (*y - 0.2).abs() < 1e-3);
    }

    #[test]
    fn written_annotations_are_standard_with_appearance_streams() {
        let saved = save(&one_page(), &all_kinds());
        for needle in [
            &b"/Subtype /Highlight"[..],
            b"/QuadPoints",
            b"/Subtype /Ink",
            b"/InkList",
            b"/Subtype /Text",
            b"/AP <</N ",
            b"/BM /Multiply",
            b"Drawn signature \\(not a digital signature\\)",
        ] {
            assert!(contains(&saved, needle), "missing {}", String::from_utf8_lossy(needle));
        }
        assert!(!contains(&saved, b"/Sig "), "a drawn signature must not claim to be a signature field");
        assert!(!contains(&saved, b"/ByteRange"));
    }

    #[test]
    fn a_second_save_stacks_on_the_first() {
        let first = save(&one_page(), &all_kinds()[..1]);
        let second = save(&first, &all_kinds()[1..2]);
        assert!(second.starts_with(&first));
        assert_eq!(inspect(&second).unwrap().annotations.len(), 2);
    }

    #[test]
    fn existing_annotations_survive_direct_and_indirect() {
        let direct = classic_pdf(&[
            "<</Type /Catalog /Pages 2 0 R>>",
            "<</Type /Pages /Kids [3 0 R] /Count 1>>",
            "<</Type /Page /MediaBox [0 0 100 100] /Annots [<</Subtype /Link /Rect [0 0 1 1]>> 4 0 R]>>",
            "<</Subtype /Square /Rect [1 1 2 2]>>",
        ]);
        let saved = save(&direct, &all_kinds()[..1]);
        let mut file = PdfFile::open(Cursor::new(&saved)).unwrap();
        let pages = file.pages().unwrap();
        assert_eq!(page_annots(&mut file, pages[0].object).unwrap().len(), 3);

        let indirect = classic_pdf(&[
            "<</Type /Catalog /Pages 2 0 R>>",
            "<</Type /Pages /Kids [3 0 R] /Count 1>>",
            "<</Type /Page /MediaBox [0 0 100 100] /Annots 4 0 R>>",
            "[5 0 R]",
            "<</Subtype /Square /Rect [1 1 2 2]>>",
        ]);
        let saved = save(&indirect, &all_kinds()[..1]);
        let mut file = PdfFile::open(Cursor::new(&saved)).unwrap();
        let pages = file.pages().unwrap();
        assert_eq!(page_annots(&mut file, pages[0].object).unwrap().len(), 2);
    }

    #[test]
    fn compressed_files_get_a_stream_update() {
        let original = compressed_pdf();
        let info = inspect(&original).unwrap();
        assert_eq!(info.page_count, 1);
        let saved = save(&original, &all_kinds());
        assert!(saved.starts_with(&original));
        let file = PdfFile::open(Cursor::new(&saved)).unwrap();
        assert!(file.last_xref_is_stream);
        let info = inspect(&saved).unwrap();
        assert_eq!(info.annotations.len(), 5);
        // A 90 degree page still reads back where it was placed.
        let PdfAnnotation::Note { x, y, .. } = &info.annotations[4] else { panic!("not a note") };
        assert!((*x - 0.8).abs() < 1e-3 && (*y - 0.2).abs() < 1e-3);
    }

    #[test]
    fn a_missing_final_newline_gets_a_separator_not_an_edit() {
        let mut original = one_page();
        while original.last() == Some(&b'\n') {
            original.pop();
        }
        let saved = save(&original, &all_kinds()[..1]);
        assert!(saved.starts_with(&original));
        assert_eq!(saved[original.len()], b'\n');
        assert_eq!(inspect(&saved).unwrap().annotations.len(), 1);
    }

    #[test]
    fn pages_follow_tree_order_with_inherited_geometry() {
        let bytes = classic_pdf(&[
            "<</Type /Catalog /Pages 2 0 R>>",
            "<</Type /Pages /Kids [3 0 R 6 0 R] /MediaBox [0 0 100 200] /Rotate 90>>",
            "<</Type /Pages /Kids [4 0 R 5 0 R] /CropBox [10 10 50 50]>>",
            "<</Type /Page>>",
            "<</Type /Page /Rotate 180>>",
            "<</Type /Page /MediaBox [0 0 300 300]>>",
        ]);
        let mut file = PdfFile::open(Cursor::new(&bytes)).unwrap();
        let pages = file.pages().unwrap();
        assert_eq!(pages.iter().map(|p| p.object).collect::<Vec<_>>(), vec![4, 5, 6]);
        assert_eq!((pages[0].geometry.x1, pages[0].geometry.rotate), (50.0, 90));
        assert_eq!(pages[1].geometry.rotate, 180);
        assert_eq!((pages[2].geometry.x1, pages[2].geometry.rotate), (300.0, 90));
    }

    #[test]
    fn reports_encryption_and_refuses_to_save() {
        let mut bytes = one_page();
        let at = bytes.windows(5).rposition(|w| w == b"/Root").unwrap();
        bytes.splice(at..at, b"/Encrypt 9 0 R ".iter().copied());
        let info = inspect(&bytes).unwrap();
        assert!(info.encrypted);
        let err = append_annotations(Cursor::new(&bytes), &mut Vec::new(), &all_kinds()).unwrap_err();
        assert_eq!(err.kind, ErrorKind::UnsupportedFormat);
    }

    #[test]
    fn detects_signed_and_archival_files() {
        assert!(!inspect(&one_page()).unwrap().signed);
        assert!(!inspect(&one_page()).unwrap().archival);

        let byte_range = classic_pdf(&[
            "<</Type /Catalog /Pages 2 0 R>>",
            "<</Type /Pages /Kids [3 0 R]>>",
            "<</Type /Page /MediaBox [0 0 100 100]>>",
            "<</Type /Sig /ByteRange [0 10 20 30]>>",
        ]);
        assert!(inspect(&byte_range).unwrap().signed);

        let sig_flags = classic_pdf(&[
            "<</Type /Catalog /Pages 2 0 R /AcroForm 4 0 R>>",
            "<</Type /Pages /Kids [3 0 R]>>",
            "<</Type /Page /MediaBox [0 0 100 100]>>",
            "<</Fields [] /SigFlags 3>>",
        ]);
        assert!(inspect(&sig_flags).unwrap().signed);

        let perms = classic_pdf(&[
            "<</Type /Catalog /Pages 2 0 R /Perms <</DocMDP 4 0 R>>>>",
            "<</Type /Pages /Kids [3 0 R]>>",
            "<</Type /Page /MediaBox [0 0 100 100]>>",
            "<<>>",
        ]);
        assert!(inspect(&perms).unwrap().signed);

        let xmp = "<rdf:Description xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\" pdfaid:part=\"2\"/>";
        let plain = format!("<</Type /Metadata /Subtype /XML /Length {}>>\nstream\n{xmp}\nendstream", xmp.len());
        let archival = classic_pdf(&[
            "<</Type /Catalog /Pages 2 0 R /Metadata 4 0 R>>",
            "<</Type /Pages /Kids [3 0 R]>>",
            "<</Type /Page /MediaBox [0 0 100 100]>>",
            &plain,
        ]);
        assert!(inspect(&archival).unwrap().archival);
    }

    #[test]
    fn detects_archival_metadata_that_is_compressed() {
        let mut out = b"%PDF-1.7\n".to_vec();
        let mut offsets = Vec::new();
        let objects = [
            "<</Type /Catalog /Pages 2 0 R /Metadata 4 0 R>>",
            "<</Type /Pages /Kids [3 0 R]>>",
            "<</Type /Page /MediaBox [0 0 100 100]>>",
        ];
        for (index, body) in objects.iter().enumerate() {
            offsets.push(out.len());
            out.extend_from_slice(format!("{} 0 obj\n{body}\nendobj\n", index + 1).as_bytes());
        }
        let stream = deflate(b"<x pdfaid:part=\"1\"/>");
        offsets.push(out.len());
        out.extend_from_slice(
            format!("4 0 obj\n<</Filter /FlateDecode /Length {}>>\nstream\n", stream.len()).as_bytes(),
        );
        out.extend_from_slice(&stream);
        out.extend_from_slice(b"\nendstream\nendobj\n");
        let xref_at = out.len();
        out.extend_from_slice(b"xref\n0 5\n0000000000 65535 f \n");
        for offset in offsets {
            out.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
        }
        out.extend_from_slice(format!("trailer\n<</Size 5 /Root 1 0 R>>\nstartxref\n{xref_at}\n%%EOF\n").as_bytes());
        assert!(inspect(&out).unwrap().archival);
    }

    #[test]
    fn budgets_refuse_excess_input() {
        let many = vec![
            PdfAnnotation::Note { page: 0, color: 0, x: 0.5, y: 0.5, text: String::new() };
            MAX_NEW_ANNOTATIONS + 1
        ];
        let err = append_annotations(Cursor::new(one_page()), &mut Vec::new(), &many).unwrap_err();
        assert_eq!(err.kind, ErrorKind::LimitExceeded);

        let long =
            vec![PdfAnnotation::Note { page: 0, color: 0, x: 0.5, y: 0.5, text: "x".repeat(MAX_NOTE_BYTES + 1) }];
        let err = append_annotations(Cursor::new(one_page()), &mut Vec::new(), &long).unwrap_err();
        assert_eq!(err.kind, ErrorKind::LimitExceeded);

        let dense = vec![PdfAnnotation::Ink {
            page: 0,
            color: 0,
            width: 0.01,
            strokes: vec![vec![(0.5, 0.5); MAX_NEW_POINTS + 1]],
            style: PdfInkStyle::Pen,
        }];
        let err = append_annotations(Cursor::new(one_page()), &mut Vec::new(), &dense).unwrap_err();
        assert_eq!(err.kind, ErrorKind::LimitExceeded);
    }

    #[test]
    fn invalid_annotations_are_refused_without_writing() {
        let original = one_page();
        for bad in [
            PdfAnnotation::Note { page: 7, color: 0, x: 0.5, y: 0.5, text: String::new() },
            PdfAnnotation::Note { page: 0, color: 0, x: f32::NAN, y: 0.5, text: String::new() },
            PdfAnnotation::Highlight { page: 0, color: 0, boxes: Vec::new() },
            PdfAnnotation::Ink { page: 0, color: 0, width: 0.01, strokes: vec![Vec::new()], style: PdfInkStyle::Pen },
        ] {
            let mut out = Vec::new();
            let err = append_annotations(Cursor::new(&original), &mut out, &[bad]).unwrap_err();
            assert_eq!(err.kind, ErrorKind::Internal);
            assert!(out.is_empty(), "nothing is written before the update is built");
        }
    }

    // Hostile files. Every one must end in a typed error, quickly.

    fn assert_refused(bytes: &[u8], kinds: &[ErrorKind]) {
        match inspect(bytes) {
            Ok(info) => panic!("accepted a hostile file: {info:?}"),
            Err(err) => assert!(kinds.contains(&err.kind), "{err:?}"),
        }
    }

    const BAD: &[ErrorKind] = &[ErrorKind::Malformed];

    #[test]
    fn hostile_headers_and_startxref() {
        assert_refused(b"", BAD);
        assert_refused(b"%PDF", BAD);
        assert_refused(b"not a pdf at all", BAD);
        assert_refused(b"%PDF-1.7\nno trailer here", BAD);
        assert_refused(b"%PDF-1.7\nstartxref\n999999999\n%%EOF", BAD);
        assert_refused(b"%PDF-1.7\nstartxref\nabc\n%%EOF", BAD);
        assert_refused(b"%PDF-1.7\nstartxref\n0\n%%EOF", BAD);
        // Junk before the header would shift every offset.
        let mut shifted = b"junk".to_vec();
        shifted.extend_from_slice(&one_page());
        assert_refused(&shifted, BAD);
    }

    #[test]
    fn hostile_xref_offsets() {
        // Every offset wrong: objects are looked for where they aren't.
        let good = one_page();
        let xref_at = good.windows(5).position(|w| w == b"xref\n").unwrap();
        let mut bad = good[..xref_at].to_vec();
        bad.extend_from_slice(b"xref\n0 5\n0000000000 65535 f \n");
        for _ in 0..4 {
            bad.extend_from_slice(b"0000000003 00000 n \n");
        }
        bad.extend_from_slice(format!("trailer\n<</Size 5 /Root 1 0 R>>\nstartxref\n{xref_at}\n%%EOF\n").as_bytes());
        assert_refused(&bad, BAD);

        // An offset past the end of the file.
        let mut past = good[..xref_at].to_vec();
        past.extend_from_slice(b"xref\n0 2\n0000000000 65535 f \n9999999999 00000 n \n");
        past.extend_from_slice(format!("trailer\n<</Size 2 /Root 1 0 R>>\nstartxref\n{xref_at}\n%%EOF\n").as_bytes());
        assert_refused(&past, BAD);

        // The object found has a different number.
        let swapped =
            classic_pdf(&["<</Type /Catalog /Pages 2 0 R>>", "<</Type /Pages /Kids [3 0 R]>>", "<</Type /Page>>"]);
        let text = swapped.clone();
        let two = text.windows(7).position(|w| w == b"2 0 obj").unwrap();
        let mut swapped = swapped;
        swapped[two] = b'7';
        assert_refused(&swapped, BAD);
    }

    #[test]
    fn hostile_prev_loop_and_chain() {
        let mut bytes = b"%PDF-1.7\n".to_vec();
        let xref_at = bytes.len();
        bytes.extend_from_slice(
            format!("xref\n0 0\ntrailer\n<</Size 1 /Root 1 0 R /Prev {xref_at}>>\nstartxref\n{xref_at}\n%%EOF\n")
                .as_bytes(),
        );
        assert_refused(&bytes, BAD);

        // Two sections pointing at each other.
        let mut bytes = b"%PDF-1.7\n".to_vec();
        let first = bytes.len();
        let second_guess = first + format!("xref\n0 0\ntrailer\n<</Size 1 /Root 1 0 R /Prev {:05}>>\n", 0).len();
        bytes.extend_from_slice(
            format!("xref\n0 0\ntrailer\n<</Size 1 /Root 1 0 R /Prev {second_guess:05}>>\n").as_bytes(),
        );
        assert_eq!(bytes.len(), second_guess);
        bytes.extend_from_slice(
            format!("xref\n0 0\ntrailer\n<</Size 1 /Root 1 0 R /Prev {first:05}>>\nstartxref\n{first}\n%%EOF\n")
                .as_bytes(),
        );
        assert_refused(&bytes, BAD);

        // Prev past the end.
        let mut bytes = b"%PDF-1.7\n".to_vec();
        let at = bytes.len();
        bytes.extend_from_slice(
            format!("xref\n0 0\ntrailer\n<</Size 1 /Root 1 0 R /Prev 99999999>>\nstartxref\n{at}\n%%EOF\n").as_bytes(),
        );
        assert_refused(&bytes, BAD);
    }

    #[test]
    fn hostile_counts_stay_bounded() {
        // A subsection claiming billions of entries, in a tiny file.
        let mut bytes = b"%PDF-1.7\n".to_vec();
        let at = bytes.len();
        bytes.extend_from_slice(
            format!("xref\n0 4294967295\n0000000000 65535 f \ntrailer\n<</Root 1 0 R>>\nstartxref\n{at}\n%%EOF\n")
                .as_bytes(),
        );
        assert_refused(&bytes, BAD);

        // Within the number limit, but with almost no entries behind it.
        let mut bytes = b"%PDF-1.7\n".to_vec();
        let at = bytes.len();
        bytes.extend_from_slice(
            format!("xref\n0 8000000\n0000000000 65535 f \ntrailer\n<</Root 1 0 R>>\nstartxref\n{at}\n%%EOF\n")
                .as_bytes(),
        );
        assert_refused(&bytes, BAD);

        // An object number above PDF's limit.
        let mut bytes = b"%PDF-1.7\n".to_vec();
        let at = bytes.len();
        bytes.extend_from_slice(
            format!("xref\n99999999 1\n0000000000 00000 n \ntrailer\n<</Root 1 0 R>>\nstartxref\n{at}\n%%EOF\n")
                .as_bytes(),
        );
        assert_refused(&bytes, BAD);

        // A trailer /Size that leaves no room for new objects refuses to save.
        let mut huge_size = one_page();
        let at = huge_size.windows(6).rposition(|w| w == b"/Size ").unwrap();
        let end = at + huge_size[at..].iter().position(|b| *b == b'/').unwrap() + 1;
        let end = end + huge_size[end..].iter().position(|b| *b == b'/').unwrap();
        huge_size.splice(at..end, b"/Size 4294967290 ".iter().copied());
        let err = append_annotations(Cursor::new(&huge_size), &mut Vec::new(), &all_kinds()).unwrap_err();
        assert_eq!(err.kind, ErrorKind::Malformed);
    }

    #[test]
    fn hostile_xref_entries_over_the_limit() {
        let mut bytes = b"%PDF-1.7\n".to_vec();
        let at = bytes.len();
        bytes.extend_from_slice(format!("xref\n0 {}\n", MAX_XREF_ENTRIES_FOR_TEST + 1).as_bytes());
        for _ in 0..=MAX_XREF_ENTRIES_FOR_TEST {
            bytes.extend_from_slice(b"0000000000 00000 f \n");
        }
        bytes.extend_from_slice(format!("trailer\n<</Root 1 0 R>>\nstartxref\n{at}\n%%EOF\n").as_bytes());
        assert_refused(&bytes, &[ErrorKind::LimitExceeded]);
    }

    const MAX_XREF_ENTRIES_FOR_TEST: usize = 1_000_000;

    #[test]
    fn hostile_page_trees() {
        // A node that is its own child.
        let bytes = classic_pdf(&["<</Type /Catalog /Pages 2 0 R>>", "<</Type /Pages /Kids [2 0 R]>>"]);
        assert_refused(&bytes, BAD);
        // A longer cycle.
        let bytes = classic_pdf(&[
            "<</Type /Catalog /Pages 2 0 R>>",
            "<</Type /Pages /Kids [3 0 R]>>",
            "<</Type /Pages /Kids [4 0 R]>>",
            "<</Type /Pages /Kids [2 0 R]>>",
        ]);
        assert_refused(&bytes, BAD);
        // A diamond: one page reached twice would shift all later page numbers.
        let bytes = classic_pdf(&[
            "<</Type /Catalog /Pages 2 0 R>>",
            "<</Type /Pages /Kids [3 0 R 3 0 R]>>",
            "<</Type /Page>>",
        ]);
        assert_refused(&bytes, BAD);
        // No pages at all, a tree of the wrong type, kids that aren't references.
        assert_refused(&classic_pdf(&["<</Type /Catalog /Pages 2 0 R>>", "<</Type /Pages /Kids []>>"]), BAD);
        assert_refused(&classic_pdf(&["<</Type /Catalog /Pages 2 0 R>>", "42"]), BAD);
        assert_refused(&classic_pdf(&["<</Type /Catalog /Pages 2 0 R>>", "<</Type /Pages /Kids [1 2 3]>>"]), BAD);
        assert_refused(&classic_pdf(&["<</Type /Catalog>>"]), BAD);
        assert_refused(&classic_pdf(&["[]"]), BAD);
        // Kids pointing at nothing.
        assert_refused(&classic_pdf(&["<</Type /Catalog /Pages 2 0 R>>", "<</Type /Pages /Kids [99 0 R]>>"]), BAD);
    }

    #[test]
    fn a_huge_count_changes_nothing() {
        let bytes = classic_pdf(&[
            "<</Type /Catalog /Pages 2 0 R>>",
            "<</Type /Pages /Kids [3 0 R] /Count 99999999999>>",
            "<</Type /Page /MediaBox [0 0 10 10]>>",
        ]);
        assert_eq!(inspect(&bytes).unwrap().page_count, 1);
    }

    #[test]
    fn hostile_deep_page_tree() {
        let mut bodies = vec!["<</Type /Catalog /Pages 2 0 R>>".to_owned()];
        for number in 2..200 {
            bodies.push(format!("<</Type /Pages /Kids [{} 0 R]>>", number + 1));
        }
        bodies.push("<</Type /Page>>".to_owned());
        let refs: Vec<&str> = bodies.iter().map(String::as_str).collect();
        assert_refused(&classic_pdf(&refs), &[ErrorKind::LimitExceeded]);
    }

    #[test]
    fn hostile_reference_cycles() {
        let bytes = classic_pdf(&["<</Type /Catalog /Pages 2 0 R>>", "<</Type /Pages /Kids 3 0 R>>", "4 0 R", "3 0 R"]);
        assert_refused(&bytes, BAD);
    }

    #[test]
    fn hostile_deep_nesting_in_an_object() {
        let nested = "[".repeat(10_000);
        let bytes = classic_pdf(&["<</Type /Catalog /Pages 2 0 R>>", &nested]);
        assert_refused(&bytes, &[ErrorKind::LimitExceeded]);
    }

    /// An xref stream and one object stream with the given dictionary extras and data.
    fn with_object_stream(objstm_dict: &str, objstm_data: &[u8]) -> Vec<u8> {
        let mut out = b"%PDF-1.5\n".to_vec();
        let catalog_at = out.len();
        out.extend_from_slice(b"1 0 obj\n<</Type /Catalog /Pages 2 0 R>>\nendobj\n");
        let objstm_at = out.len();
        out.extend_from_slice(
            format!("3 0 obj\n<</Type /ObjStm {objstm_dict} /Length {}>>\nstream\n", objstm_data.len()).as_bytes(),
        );
        out.extend_from_slice(objstm_data);
        out.extend_from_slice(b"\nendstream\nendobj\n");
        let xref_at = out.len();
        let mut rows = Vec::new();
        for row in [
            [0u8, 0, 0, 0],
            [1, (catalog_at >> 8) as u8, catalog_at as u8, 0],
            [2, 0, 3, 0],
            [1, (objstm_at >> 8) as u8, objstm_at as u8, 0],
            [1, (xref_at >> 8) as u8, xref_at as u8, 0],
        ] {
            rows.extend_from_slice(&row);
        }
        out.extend_from_slice(
            format!("4 0 obj\n<</Type /XRef /Size 5 /W [1 2 1] /Root 1 0 R /Length {}>>\nstream\n", rows.len())
                .as_bytes(),
        );
        out.extend_from_slice(&rows);
        out.extend_from_slice(format!("\nendstream\nendobj\nstartxref\n{xref_at}\n%%EOF\n").as_bytes());
        out
    }

    #[test]
    fn hostile_object_streams() {
        // Huge object count.
        assert_refused(&with_object_stream("/N 99999999 /First 4", b"2 0 <<>>"), &[ErrorKind::LimitExceeded]);
        // Fewer entries than claimed; /First past the data.
        assert_refused(&with_object_stream("/N 5 /First 4", b"2 0 <<>>"), BAD);
        assert_refused(&with_object_stream("/N 1 /First 9999", b"2 0 <<>>"), BAD);
        // An offset past the data.
        assert_refused(&with_object_stream("/N 1 /First 8", b"2 99999 <<>>"), BAD);
        // An unknown filter, and garbage claiming to be compressed.
        assert_refused(
            &with_object_stream("/N 1 /First 4 /Filter /JPXDecode", b"2 0 <<>>"),
            &[ErrorKind::UnsupportedFormat],
        );
        assert_refused(&with_object_stream("/N 1 /First 4 /Filter /FlateDecode", b"not zlib"), BAD);
    }

    #[test]
    fn hostile_decompression_bomb_is_cut_off() {
        // 200 MiB of zeros compresses to a few hundred KiB.
        let mut encoder = ZlibEncoder::new(Vec::new(), Compression::best());
        let zeros = vec![0u8; 1024 * 1024];
        for _ in 0..200 {
            encoder.write_all(&zeros).unwrap();
        }
        let bomb = encoder.finish().unwrap();
        assert_refused(&with_object_stream("/N 1 /First 4 /Filter /FlateDecode", &bomb), &[ErrorKind::LimitExceeded]);
    }

    #[test]
    fn hostile_xref_streams() {
        let stream_file = |dict: &str, data: &[u8]| {
            let mut out = b"%PDF-1.5\n".to_vec();
            let at = out.len();
            out.extend_from_slice(
                format!("1 0 obj\n<</Type /XRef /Root 1 0 R {dict} /Length {}>>\nstream\n", data.len()).as_bytes(),
            );
            out.extend_from_slice(data);
            out.extend_from_slice(format!("\nendstream\nendobj\nstartxref\n{at}\n%%EOF\n").as_bytes());
            out
        };
        assert_refused(&stream_file("/Size 2 /W [9 9 9]", b""), BAD);
        assert_refused(&stream_file("/Size 2 /W [0 0 0]", b""), BAD);
        assert_refused(&stream_file("/Size 2", b""), BAD);
        // Claims many entries but has data for none.
        assert_refused(&stream_file("/Size 8000000 /W [1 2 1]", b"\x00\x00"), BAD);
        // /Index with an odd length and with a huge count.
        assert_refused(&stream_file("/Size 2 /W [1 2 1] /Index [0]", b""), BAD);
        assert_refused(&stream_file("/Size 2 /W [1 2 1] /Index [0 99999999999]", b""), BAD);
        // A stream that is not a cross-reference stream at all.
        let mut wrong = b"%PDF-1.5\n".to_vec();
        let at = wrong.len();
        wrong.extend_from_slice(
            format!("1 0 obj\n<</Type /Page /Length 0>>\nstream\n\nendstream\nendobj\nstartxref\n{at}\n%%EOF\n")
                .as_bytes(),
        );
        assert_refused(&wrong, BAD);
    }

    #[test]
    fn a_wrong_stream_length_is_recovered_within_bounds() {
        let mut bytes = with_object_stream("/N 1 /First 4", b"2 0 <</Type /Pages /Kids [5 0 R]>>");
        let at = bytes.windows(9).position(|w| w == b"/Length 3").unwrap();
        bytes[at + 8] = b'9';
        let mut file = PdfFile::open(Cursor::new(&bytes)).unwrap();
        // The object is read despite the bad length.
        assert!(matches!(file.object(2).unwrap(), Obj::Dict(_)));
    }

    #[test]
    fn broken_annotations_hide_themselves_only() {
        let bytes = classic_pdf(&[
            "<</Type /Catalog /Pages 2 0 R>>",
            "<</Type /Pages /Kids [3 0 R]>>",
            "<</Type /Page /MediaBox [0 0 100 100] /Annots [4 0 R 5 0 R 99 0 R 6 0 R 7 0 R 8 0 R]>>",
            "<</Subtype /Ink /InkList 12>>",
            "<</Subtype /Highlight>>",
            "<</Subtype /Ink /F 2 /InkList [[1 1 2 2]]>>",
            "<</Subtype /Square /Rect [0 0 1 1]>>",
            "<</Subtype /Ink /C [1 0 0] /CA 0.5 /InkList [[10 90 20 80]]>>",
        ]);
        let info = inspect(&bytes).unwrap();
        assert_eq!(info.annotations.len(), 1);
        let PdfAnnotation::Ink { color, strokes, width, .. } = &info.annotations[0] else { panic!("not ink") };
        assert_eq!(*color, 0x80FF_0000);
        assert_eq!(strokes[0][0], (0.1, 0.1));
        assert!((*width - 0.01).abs() < 1e-6);
    }

    #[test]
    fn reads_foreign_colours_and_text_encodings() {
        assert_eq!(color_from_components(&[0.5]), Some(0x808080));
        assert_eq!(color_from_components(&[0.0, 0.0, 0.0, 1.0]), Some(0));
        assert_eq!(color_from_components(&[1.0, 0.0]), None);
        assert_eq!(read_text_string(b"caf\xE9"), "café");
        assert_eq!(read_text_string(b"\xEF\xBB\xBFcaf\xC3\xA9"), "café");
        assert_eq!(read_text_string(b"\xFE\xFF\x00c\x00a\x00f\x00\xE9"), "café");
    }

    #[test]
    fn verification_catches_a_missing_annotation() {
        let saved = save(&one_page(), &all_kinds());
        assert!(verify_appended(Cursor::new(&one_page()), 1, &all_kinds()).is_err());
        assert!(verify_appended(Cursor::new(&saved), 2, &all_kinds()).is_err());
    }
}
