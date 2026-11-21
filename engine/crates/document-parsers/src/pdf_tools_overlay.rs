//! Watermark and Sign: a visual layer drawn over existing pages.
//!
//! Both are appended as an incremental update, through the same writer the
//! annotations use. The original bytes are copied through untouched and no page
//! content is re-encoded. Each affected page gets its `/Contents` wrapped:
//! a shared `q` stream goes first, and a `Q` plus the overlay goes last. Its
//! `/Resources` is a copy with the font and opacity entries added. Adding the
//! wrap keeps the original content's graphics state from leaking into the overlay.
//!
//! A signature here is a drawing, not a cryptographic signature. Nothing in the
//! file claims otherwise. Signature strokes are vector paths, so no image is
//! decoded or embedded.
//!
//! Watermark text uses the standard Helvetica font, not embedded, in WinAnsi.
//! Characters beyond Latin-1 become `?`. Text width is estimated at 0.5 em per
//! character to place it, so a centred watermark can sit a few points off-centre.

use std::io::{Read, Seek, Write};

use document_core::{EngineError, ErrorKind, Result};

use crate::pdf_annot::Update;
use crate::pdf_file::{MAX_OBJECT_NUMBER, PageGeometry, PageNode, PdfFile, XrefEntry};
use crate::pdf_syntax::{Dict, Obj, format_number, write_dict};
use crate::pdf_tools::{PdfToolObserver, dangling_references};

const MAX_TEXT_CHARS: usize = 200;
const MAX_TILES_PER_PAGE: usize = 2_000;
const MAX_TARGET_PAGES: usize = 100_000;
const MAX_SIGN_STROKES: usize = 1_000;
const MAX_SIGN_POINTS: usize = 100_000;
/// Bytes of new content in one update, across all pages.
const MAX_OVERLAY_BYTES: usize = 16 * 1024 * 1024;
const CANCEL_EVERY: usize = 16;
const GS_NAME: &str = "XfWmGs";
const FONT_NAME: &str = "XfWmF";

/// Where a watermark sits on each page.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PdfWatermarkPosition {
    Center,
    TopLeft,
    TopCenter,
    TopRight,
    BottomLeft,
    BottomCenter,
    BottomRight,
    /// Repeated over the whole page in a staggered grid.
    Tiled,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PdfWatermark {
    pub text: String,
    /// Points, 6 to 300.
    pub font_size: f32,
    /// 0 to 1.
    pub opacity: f32,
    /// Degrees counter-clockwise on screen. Any finite value.
    pub angle_degrees: f32,
    /// 0xRRGGBB.
    pub color: u32,
    pub position: PdfWatermarkPosition,
    /// Zero-based pages. `None` is every page.
    pub pages: Option<Vec<u32>>,
}

/// A hand-drawn mark placed on one page.
#[derive(Debug, Clone, PartialEq)]
pub struct PdfSignature {
    pub page: u32,
    /// Placement as fractions of the displayed page, origin top-left: x, y, width, height.
    pub rect: [f32; 4],
    /// Strokes as fractions of the placement box, origin top-left.
    pub strokes: Vec<Vec<(f32, f32)>>,
    /// 0xRRGGBB. The alpha of an ARGB colour is not applied.
    pub color: u32,
    /// Points, 0.5 to 6.
    pub line_width: f32,
}

#[derive(Debug, Clone, PartialEq)]
pub enum PdfOverlay {
    Watermark(PdfWatermark),
    Sign(PdfSignature),
}

/// Sizes of a finished update; the original file is `original_len` bytes of it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PdfOverlayReport {
    pub page_count: u32,
    pub pages_changed: u32,
    pub original_len: u64,
    pub total_len: u64,
}

/// Writes the original file followed by an update adding `overlay`.
pub fn apply_overlay<R: Read + Seek, W: Write>(
    source: R,
    out: &mut W,
    overlay: &PdfOverlay,
    observer: &mut dyn PdfToolObserver,
) -> Result<PdfOverlayReport> {
    let mut file = PdfFile::open(source)?;
    if file.trailer.get("Encrypt").is_some() {
        return Err(EngineError::unsupported("Remove the password before adding a watermark or signature."));
    }
    let nodes = file.page_nodes()?;
    let page_count = nodes.len() as u32;
    let targets = validate(overlay, page_count)?;
    let update = build(&mut file, &nodes, overlay, &targets, observer)?;
    let original_len = file.len;
    // The last original byte decides whether a separator is needed.
    let total = file.copy_all(out)?;
    if total != original_len {
        return Err(EngineError::new(ErrorKind::Io, "the file changed while it was being saved"));
    }
    let needs_newline = file.last_byte()? != b'\n';
    let base = original_len + u64::from(needs_newline);
    if needs_newline {
        out.write_all(b"\n")?;
    }
    let section = update.serialize(&file, base)?;
    out.write_all(&section)?;
    out.flush()?;
    Ok(PdfOverlayReport {
        page_count,
        pages_changed: targets.len() as u32,
        original_len,
        total_len: base + section.len() as u64,
    })
}

/// Re-reads a finished overlay: the page count must hold and nothing may dangle.
/// This is the last check before a file is handed to the user.
pub fn verify_overlay<R: Read + Seek>(saved: R, original_pages: u32) -> Result<()> {
    let not_verified = || EngineError::new(ErrorKind::Internal, "the finished PDF did not verify");
    let mut file = PdfFile::open(saved).map_err(|_| not_verified())?;
    let pages = file.pages().map_err(|_| not_verified())?;
    if pages.len() as u32 != original_pages {
        return Err(not_verified());
    }
    if !dangling_references(&mut file).map_err(|_| not_verified())?.is_empty() {
        return Err(not_verified());
    }
    Ok(())
}

/// Checks the request and returns the target pages, sorted and without repeats.
fn validate(overlay: &PdfOverlay, page_count: u32) -> Result<Vec<u32>> {
    match overlay {
        PdfOverlay::Watermark(watermark) => {
            let text = watermark.text.trim();
            if text.is_empty() {
                return Err(EngineError::malformed("Enter the text for the watermark."));
            }
            if text.chars().count() > MAX_TEXT_CHARS {
                return Err(EngineError::limit("the watermark text is too long"));
            }
            if !(6.0..=300.0).contains(&watermark.font_size) {
                return Err(EngineError::malformed("The watermark size has to be between 6 and 300 points."));
            }
            if !(0.0..=1.0).contains(&watermark.opacity) || !watermark.angle_degrees.is_finite() {
                return Err(EngineError::malformed("That opacity or angle isn't valid."));
            }
            if watermark.color > 0xFF_FF_FF {
                return Err(EngineError::malformed("That colour isn't valid."));
            }
            target_pages(watermark.pages.as_deref(), page_count)
        }
        PdfOverlay::Sign(signature) => {
            if signature.page >= page_count {
                return Err(EngineError::malformed("The signature's page isn't in this PDF."));
            }
            let [x, y, w, h] = signature.rect;
            let inside = |v: f32| v.is_finite() && (0.0..=1.0).contains(&v);
            if !(inside(x) && inside(y) && w.is_finite() && h.is_finite())
                || w <= 0.0
                || h <= 0.0
                || x + w > 1.0 + 1e-4
                || y + h > 1.0 + 1e-4
            {
                return Err(EngineError::malformed("The signature has to sit inside its page."));
            }
            if signature.strokes.len() > MAX_SIGN_STROKES {
                return Err(EngineError::limit("the signature has too many strokes"));
            }
            let mut points = 0usize;
            for stroke in &signature.strokes {
                points += stroke.len();
                if stroke.iter().any(|&(px, py)| !(inside(px) && inside(py))) {
                    return Err(EngineError::malformed("A signature stroke falls outside its box."));
                }
            }
            if points > MAX_SIGN_POINTS {
                return Err(EngineError::limit("the signature is too detailed to save"));
            }
            if !(0.5..=6.0).contains(&signature.line_width) || signature.color > 0xFF_FF_FF {
                return Err(EngineError::malformed("That line width or colour isn't valid."));
            }
            Ok(vec![signature.page])
        }
    }
}

/// `None` means every page. A list is sorted and de-duplicated, which is
/// O(n log n) and makes the page order stable whatever the caller sent.
fn target_pages(pages: Option<&[u32]>, page_count: u32) -> Result<Vec<u32>> {
    let mut targets: Vec<u32> = match pages {
        None => (0..page_count).collect(),
        Some(list) => list.to_vec(),
    };
    if targets.len() > MAX_TARGET_PAGES {
        return Err(EngineError::limit("too many pages to mark at once"));
    }
    targets.sort_unstable();
    targets.dedup();
    if targets.is_empty() {
        return Err(EngineError::malformed("Choose at least one page."));
    }
    if targets.last().is_some_and(|last| *last >= page_count) {
        return Err(EngineError::malformed("A chosen page isn't in this PDF."));
    }
    Ok(targets)
}

/// The objects an update adds, and the page objects it replaces.
struct Builder {
    next: u32,
    objects: Vec<(u32, u16, Vec<u8>)>,
    bytes: usize,
}

impl Builder {
    fn alloc(&mut self) -> Result<u32> {
        if self.next >= MAX_OBJECT_NUMBER {
            return Err(EngineError::limit("the PDF has no object numbers left"));
        }
        let number = self.next;
        self.next += 1;
        Ok(number)
    }

    fn push(&mut self, number: u32, generation: u16, body: Vec<u8>) -> Result<()> {
        self.bytes += body.len();
        if self.bytes > MAX_OVERLAY_BYTES {
            return Err(EngineError::limit("the watermark or signature is too large to add"));
        }
        self.objects.push((number, generation, body));
        Ok(())
    }

    fn push_value(&mut self, value: &Dict) -> Result<u32> {
        let number = self.alloc()?;
        let mut body = Vec::new();
        write_dict(&mut body, value);
        self.push(number, 0, body)?;
        Ok(number)
    }

    fn push_stream(&mut self, mut dict: Dict, data: &[u8]) -> Result<u32> {
        let number = self.alloc()?;
        dict.set("Length", Obj::int(data.len() as u64));
        let mut body = Vec::with_capacity(data.len() + 64);
        write_dict(&mut body, &dict);
        body.extend_from_slice(b"\nstream\n");
        body.extend_from_slice(data);
        body.extend_from_slice(b"\nendstream");
        self.push(number, 0, body)?;
        Ok(number)
    }
}

fn build<R: Read + Seek>(
    file: &mut PdfFile<R>,
    nodes: &[PageNode],
    overlay: &PdfOverlay,
    targets: &[u32],
    observer: &mut dyn PdfToolObserver,
) -> Result<Update> {
    let mut builder = Builder { next: next_object_number(file)?, objects: Vec::new(), bytes: 0 };
    // Shared by every page this update touches.
    let prefix = builder.push_stream(Dict::default(), b"q\n")?;
    let (gs, font) = match overlay {
        PdfOverlay::Watermark(watermark) => {
            let opacity = format_number(f64::from(watermark.opacity));
            let gs = builder.push_value(&Dict::of(vec![
                ("Type", Obj::name("ExtGState")),
                ("ca", Obj::Num(opacity.clone())),
                ("CA", Obj::Num(opacity)),
            ]))?;
            let font = builder.push_value(&Dict::of(vec![
                ("Type", Obj::name("Font")),
                ("Subtype", Obj::name("Type1")),
                ("BaseFont", Obj::name("Helvetica")),
                ("Encoding", Obj::name("WinAnsiEncoding")),
            ]))?;
            (Some(gs), Some(font))
        }
        PdfOverlay::Sign(_) => (None, None),
    };

    let mut changed = 0usize;
    for (index, &page_index) in targets.iter().enumerate() {
        if index % CANCEL_EVERY == 0 {
            if observer.is_cancelled() {
                return Err(EngineError::cancelled());
            }
            observer.on_progress(index as u32, targets.len() as u32);
        }
        let node = &nodes[page_index as usize];
        let page_object = node.page.object;
        let geometry = node.page.geometry;
        let generation = generation_of(file, page_object);
        let Obj::Dict(mut page_dict) = file.object(page_object)? else {
            return Err(EngineError::malformed("the PDF's page tree is damaged"));
        };

        let body = match overlay {
            PdfOverlay::Watermark(watermark) => watermark_ops(watermark, geometry),
            PdfOverlay::Sign(signature) => sign_ops(signature, geometry),
        };
        let mut content = b"Q\nq\n".to_vec();
        if gs.is_some() {
            content.extend_from_slice(format!("/{GS_NAME} gs\n").as_bytes());
        }
        content.extend_from_slice(&body);
        content.extend_from_slice(b"Q\n");
        let overlay_stream = builder.push_stream(Dict::default(), &content)?;

        let mut resources = resources_for(file, node, &page_dict)?;
        if let (Some(gs), Some(font)) = (gs, font) {
            add_resource(file, &mut resources, "ExtGState", GS_NAME, Obj::Ref(gs, 0))?;
            add_resource(file, &mut resources, "Font", FONT_NAME, Obj::Ref(font, 0))?;
        }

        let mut contents = contents_of(file, &page_dict)?;
        contents.insert(0, Obj::Ref(prefix, 0));
        contents.push(Obj::Ref(overlay_stream, 0));
        page_dict.set("Contents", Obj::Array(contents));
        page_dict.set("Resources", Obj::Dict(resources));
        let mut body = Vec::new();
        write_dict(&mut body, &page_dict);
        builder.push(page_object, generation, body)?;
        changed += 1;
    }
    observer.on_progress(targets.len() as u32, targets.len() as u32);
    debug_assert_eq!(changed, targets.len());
    Ok(Update { objects: builder.objects, next_number: builder.next })
}

/// One past the highest number the file uses or declares.
fn next_object_number<R: Read + Seek>(file: &PdfFile<R>) -> Result<u32> {
    let size = file.trailer.get("Size").and_then(Obj::as_i64).unwrap_or(0).max(0);
    let highest = file.xref.keys().copied().max().map_or(0, |n| i64::from(n) + 1);
    u32::try_from(size.max(highest))
        .ok()
        .filter(|n| *n <= MAX_OBJECT_NUMBER)
        .ok_or_else(|| EngineError::malformed("the PDF's object count is out of range"))
}

fn generation_of<R: Read + Seek>(file: &PdfFile<R>, number: u32) -> u16 {
    match file.xref.get(&number) {
        Some(XrefEntry::Plain { generation, .. }) => *generation,
        _ => 0,
    }
}

/// The page's own `/Resources`, else the nearest inherited ones, as a copy.
fn resources_for<R: Read + Seek>(file: &mut PdfFile<R>, node: &PageNode, page: &Dict) -> Result<Dict> {
    let value = match page.get("Resources") {
        Some(value) => file.resolve(value)?,
        None => match node.inherited.get("Resources") {
            Some(value) => file.resolve(value)?,
            None => Obj::Null,
        },
    };
    Ok(match value {
        Obj::Dict(dict) => dict,
        _ => Dict::default(),
    })
}

/// Adds `name -> target` under `category` (such as `Font`), copying the existing
/// category dictionary so nothing the page already uses is lost.
fn add_resource<R: Read + Seek>(
    file: &mut PdfFile<R>,
    resources: &mut Dict,
    category: &str,
    name: &str,
    target: Obj,
) -> Result<()> {
    let mut entries = match resources.get(category) {
        Some(value) => match file.resolve(value)? {
            Obj::Dict(dict) => dict,
            _ => Dict::default(),
        },
        None => Dict::default(),
    };
    entries.set(name, target);
    resources.set(category, Obj::Dict(entries));
    Ok(())
}

/// The page's existing content as a list of references, ready to extend.
fn contents_of<R: Read + Seek>(file: &mut PdfFile<R>, page: &Dict) -> Result<Vec<Obj>> {
    Ok(match page.get("Contents") {
        None => Vec::new(),
        Some(Obj::Array(items)) => items.clone(),
        Some(Obj::Ref(number, generation)) => match file.resolve(&Obj::Ref(*number, *generation))? {
            Obj::Array(items) => items,
            _ => vec![Obj::Ref(*number, *generation)],
        },
        Some(_) => Vec::new(),
    })
}

/// Estimated width of `text` at `size`, for placement only.
fn estimate_width(text: &str, size: f64) -> f64 {
    text.chars().count() as f64 * size * 0.5
}

/// Escapes text for a literal PDF string in WinAnsi. Characters past Latin-1 are
/// `?`, and control characters become spaces.
fn pdf_text(text: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity(text.len() + 2);
    out.push(b'(');
    for ch in text.chars() {
        let code = ch as u32;
        let byte = match code {
            0x00..=0x1F => b' ',
            0x20..=0xFF => code as u8,
            _ => b'?',
        };
        if matches!(byte, b'(' | b')' | b'\\') {
            out.push(b'\\');
        }
        out.push(byte);
    }
    out.push(b')');
    out
}

fn rgb(color: u32) -> [f64; 3] {
    [
        f64::from(color >> 16 & 0xFF) / 255.0,
        f64::from(color >> 8 & 0xFF) / 255.0,
        f64::from(color & 0xFF) / 255.0,
    ]
}

/// Converts display coordinates (points, origin top-left) to user space.
fn to_user(geometry: PageGeometry, width: f64, height: f64, x: f64, y: f64) -> (f64, f64) {
    geometry.to_user(x / width, y / height)
}

/// Displayed width and height, accounting for a quarter turn.
fn displayed_size(geometry: PageGeometry) -> (f64, f64) {
    if geometry.rotate % 180 == 0 {
        (geometry.width(), geometry.height())
    } else {
        (geometry.height(), geometry.width())
    }
}

fn watermark_ops(watermark: &PdfWatermark, geometry: PageGeometry) -> Vec<u8> {
    let (width, height) = displayed_size(geometry);
    let size = f64::from(watermark.font_size);
    let text_width = estimate_width(watermark.text.trim(), size);
    let theta = f64::from(watermark.angle_degrees).to_radians();
    // Unit vectors on screen (y down): along the text, and up from its baseline.
    let along = (theta.cos(), -theta.sin());
    let up = (-theta.sin(), -theta.cos());
    let margin = 0.04 * width.min(height).max(1.0);
    let (half_w, half_h) = (text_width / 2.0, size / 2.0);
    let centers: Vec<(f64, f64)> = match watermark.position {
        PdfWatermarkPosition::Center => vec![(width / 2.0, height / 2.0)],
        PdfWatermarkPosition::TopLeft => vec![(margin + half_w, margin + half_h)],
        PdfWatermarkPosition::TopCenter => vec![(width / 2.0, margin + half_h)],
        PdfWatermarkPosition::TopRight => vec![(width - margin - half_w, margin + half_h)],
        PdfWatermarkPosition::BottomLeft => vec![(margin + half_w, height - margin - half_h)],
        PdfWatermarkPosition::BottomCenter => vec![(width / 2.0, height - margin - half_h)],
        PdfWatermarkPosition::BottomRight => vec![(width - margin - half_w, height - margin - half_h)],
        PdfWatermarkPosition::Tiled => tile_centers(width, height, text_width, size),
    };
    let [r, g, b] = rgb(watermark.color);
    let mut out = Vec::new();
    out.extend_from_slice(format!("BT\n/{FONT_NAME} {} Tf\n{} {} {} rg\n", format_number(size), format_number(r), format_number(g), format_number(b)).as_bytes());
    for (cx, cy) in centers {
        // The baseline starts half a width back along the text, and 0.35 em
        // below the centre so the glyphs sit on the middle line.
        let start = (
            cx - along.0 * half_w - up.0 * size * 0.35,
            cy - along.1 * half_w - up.1 * size * 0.35,
        );
        let (sx, sy) = to_user(geometry, width, height, start.0, start.1);
        let (ex, ey) = to_user(geometry, width, height, start.0 + along.0, start.1 + along.1);
        let (dx, dy) = (ex - sx, ey - sy);
        let len = dx.hypot(dy).max(f64::MIN_POSITIVE);
        let (dx, dy) = (dx / len, dy / len);
        out.extend_from_slice(
            format!(
                "{} {} {} {} {} {} Tm\n",
                format_number(dx),
                format_number(dy),
                format_number(-dy),
                format_number(dx),
                format_number(sx),
                format_number(sy)
            )
            .as_bytes(),
        );
        out.extend_from_slice(&pdf_text(watermark.text.trim()));
        out.extend_from_slice(b" Tj\n");
    }
    out.extend_from_slice(b"ET\n");
    out
}

/// A staggered grid of centres covering the page. Alternate rows are offset by
/// half a step. Capped so a tiny font can't generate millions of copies.
fn tile_centers(width: f64, height: f64, text_width: f64, size: f64) -> Vec<(f64, f64)> {
    let step_x = text_width.max(size) + size * 4.0;
    let step_y = size * 5.0;
    let cols = (width / step_x).ceil() as usize + 1;
    let rows = (height / step_y).ceil() as usize + 1;
    let mut centers = Vec::with_capacity((cols * rows).min(MAX_TILES_PER_PAGE));
    for row in 0..rows {
        let offset = if row % 2 == 1 { step_x / 2.0 } else { 0.0 };
        for col in 0..cols {
            if centers.len() >= MAX_TILES_PER_PAGE {
                return centers;
            }
            centers.push((col as f64 * step_x + offset, row as f64 * step_y + step_y / 2.0));
        }
    }
    centers
}

fn sign_ops(signature: &PdfSignature, geometry: PageGeometry) -> Vec<u8> {
    let [x, y, w, h] = signature.rect.map(f64::from);
    let [r, g, b] = rgb(signature.color);
    let mut out = Vec::new();
    out.extend_from_slice(
        format!(
            "{} {} {} RG\n{} w\n1 J\n1 j\n",
            format_number(r),
            format_number(g),
            format_number(b),
            format_number(f64::from(signature.line_width))
        )
        .as_bytes(),
    );
    for stroke in &signature.strokes {
        for (index, &(px, py)) in stroke.iter().enumerate() {
            // Fractions of the page map straight to user space.
            let (ux, uy) = geometry.to_user(x + f64::from(px) * w, y + f64::from(py) * h);
            let op = if index == 0 { "m" } else { "l" };
            out.extend_from_slice(format!("{} {} {op}\n", format_number(ux), format_number(uy)).as_bytes());
        }
        if !stroke.is_empty() {
            out.extend_from_slice(b"S\n");
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn text_is_escaped_and_latin1_kept() {
        assert_eq!(pdf_text("a(b)c\\d"), b"(a\\(b\\)c\\\\d)".to_vec());
        assert_eq!(pdf_text("caf\u{e9}"), b"(caf\xe9)".to_vec());
        assert_eq!(pdf_text("\u{4e2d}"), b"(?)".to_vec());
        assert_eq!(pdf_text("a\nb"), b"(a b)".to_vec());
    }

    #[test]
    fn target_pages_sorts_dedups_and_bounds_checks() {
        assert_eq!(target_pages(Some(&[4, 1, 1, 0]), 5).unwrap(), vec![0, 1, 4]);
        assert_eq!(target_pages(None, 3).unwrap(), vec![0, 1, 2]);
        assert!(target_pages(Some(&[5]), 5).is_err());
        assert!(target_pages(Some(&[]), 5).is_err());
    }

    #[test]
    fn tiles_stay_under_the_cap_and_cover_the_page() {
        let centers = tile_centers(612.0, 792.0, 100.0, 12.0);
        assert!(centers.len() <= MAX_TILES_PER_PAGE);
        assert!(centers.iter().any(|&(x, _)| x > 300.0));
        // A tiny font on a big page would be unbounded without the cap.
        assert_eq!(tile_centers(10_000.0, 10_000.0, 0.1, 0.1).len(), MAX_TILES_PER_PAGE);
    }

    #[test]
    fn signature_rect_and_points_are_validated() {
        let page = PdfOverlay::Sign(PdfSignature {
            page: 0,
            rect: [0.5, 0.5, 0.4, 0.2],
            strokes: vec![vec![(0.0, 0.0), (1.0, 1.0)]],
            color: 0,
            line_width: 1.5,
        });
        assert!(validate(&page, 1).is_ok());
        let outside = PdfOverlay::Sign(PdfSignature {
            page: 0,
            rect: [0.8, 0.0, 0.4, 0.2],
            strokes: Vec::new(),
            color: 0,
            line_width: 1.5,
        });
        assert!(validate(&outside, 1).is_err());
        let off_page = PdfOverlay::Sign(PdfSignature {
            page: 2,
            rect: [0.0, 0.0, 0.1, 0.1],
            strokes: Vec::new(),
            color: 0,
            line_width: 1.5,
        });
        assert!(validate(&off_page, 2).is_err());
    }

    #[test]
    fn watermark_inputs_are_checked() {
        let base = PdfWatermark {
            text: "DRAFT".to_owned(),
            font_size: 48.0,
            opacity: 0.3,
            angle_degrees: 45.0,
            color: 0x80_80_80,
            position: PdfWatermarkPosition::Center,
            pages: None,
        };
        assert!(validate(&PdfOverlay::Watermark(base.clone()), 2).is_ok());
        assert!(validate(&PdfOverlay::Watermark(PdfWatermark { text: "  ".to_owned(), ..base.clone() }), 2).is_err());
        assert!(validate(&PdfOverlay::Watermark(PdfWatermark { font_size: 2.0, ..base.clone() }), 2).is_err());
        assert!(validate(&PdfOverlay::Watermark(PdfWatermark { opacity: 1.5, ..base }), 2).is_err());
    }
}
