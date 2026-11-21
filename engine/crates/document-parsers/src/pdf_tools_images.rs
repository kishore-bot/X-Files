//! Image recompression for Compress's Balanced and Strong levels.
//!
//! Lossless Compress never gets here. A lossy level does three things:
//! 1. Placement: walks each page's content streams, tracking the current
//!    transformation matrix (a stack for `q`/`Q`, a product for `cm`), so every
//!    `Do` of an image XObject yields its drawn size in points. Forms recurse
//!    with their own `/Matrix` and `/Resources`. An image never drawn is sized
//!    as the full page width. The largest placement of each image is kept,
//!    because that is the one that needs the most pixels.
//! 2. Planning (on the file's thread): an image qualifies when its colour is
//!    DeviceGray, DeviceRGB, DeviceCMYK, ICCBased (N 1, 3 or 4) or Indexed over
//!    one of those, at 8 bits, with DCTDecode, FlateDecode or no filter (PNG
//!    predictors allowed), and when its effective DPI is well above the target.
//!    A `/Decode` that inverts the samples is applied to gray and RGB; any
//!    other `/Decode` leaves the image alone. CMYK is re-encoded only from a
//!    DCTDecode stream whose Adobe APP14 marker names its transform (0 or 2):
//!    the samples keep their stored convention, the marker and the `/Decode`
//!    are kept, so the page draws the same. DCT CMYK without that marker is
//!    left alone. Flate or raw CMYK is downsampled and re-deflated as Flate
//!    (no JPEG, so no Adobe inversion convention is assumed). Images with `/Mask`, `/ImageMask`,
//!    `/SMaskInData`, JPX or large palettes are left alone.
//!    A soft mask (`/SMask`) of a planned image is planned with it: it is
//!    downsampled to the colour image's new size as 8-bit Flate gray.
//!    Palettes of 16 colours or fewer keep their indices, downsampled with
//!    nearest-neighbour so no new colours appear; larger palettes are expanded
//!    to their base colour space and re-encoded.
//! 3. Re-encoding (worker threads, pure data): decode, apply the planned
//!    inversion, downsample, encode. The result is used only if it is smaller
//!    than the stored bytes.
//!
//! Memory: a worker holds one decoded image and one output buffer. The caller
//! runs at most `workers` images at a time, so the working set is bounded by
//! `workers * MAX_PIXELS * channels`. Pure Rust: `zune-jpeg` decodes and
//! `jpeg-encoder` encodes, with no C code, so the Android NDK builds unchanged.

use std::collections::HashMap;
use std::io::{Read, Seek, Write};
use std::thread;

use document_core::{EngineError, Result};
use flate2::Compression;
use flate2::read::ZlibDecoder;
use flate2::write::ZlibEncoder;
use jpeg_encoder::{ColorType, Encoder, SamplingFactor};
use zune_jpeg::JpegDecoder;
use zune_jpeg::zune_core::colorspace::ColorSpace;
use zune_jpeg::zune_core::options::DecoderOptions;

use crate::pdf_file::{LoadedObject, PdfFile};
use crate::pdf_syntax::{Dict, Obj};
use crate::pdf_tools::PdfToolObserver;

/// Pixels above which an image is left as it is. At three bytes a pixel a
/// worker holds about 72 MB of decoded data.
const MAX_PIXELS: u64 = 24_000_000;
/// Stream bytes read for content streams, forms and images.
const MAX_STREAM_BYTES: u64 = 64 * 1024 * 1024;
/// Operators one page's content may run before the walk gives up on it.
const OP_BUDGET: usize = 1_000_000;
const MAX_FORM_DEPTH: u8 = 8;
/// Most images one batch re-encodes at once, whatever the core count.
const MAX_WORKERS: usize = 4;
/// Images smaller than this on their longer side are not worth a re-encode.
const MIN_SIDE: u32 = 32;
/// Scale factors above this change too little to pay for another JPEG generation.
const MIN_SCALE_CHANGE: f64 = 0.9;
/// Palettes up to this size keep their indices (nearest-neighbour), so no colour is invented.
const MAX_INDEXED_FLATE_ENTRIES: usize = 16;

/// How hard Compress works on images. `Lossless` is the structural rewrite only.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum PdfImageQuality {
    #[default]
    Lossless,
    Balanced,
    Strong,
}

impl PdfImageQuality {
    /// The resolution images are brought down to, or `None` to leave them alone.
    pub(crate) fn target_dpi(self) -> Option<f64> {
        match self {
            Self::Lossless => None,
            Self::Balanced => Some(150.0),
            Self::Strong => Some(110.0),
        }
    }

    pub(crate) fn jpeg_quality(self) -> u8 {
        match self {
            Self::Lossless | Self::Balanced => 75,
            Self::Strong => 60,
        }
    }
}

/// Largest drawn size of each image XObject, by object number, in points.
pub(crate) type Placements = HashMap<u32, (f64, f64)>;

type Matrix = [f64; 6];
const IDENTITY: Matrix = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0];

/// `m` then `c`, in PDF's row-vector convention: the result is `m × c`.
fn multiply(m: Matrix, c: Matrix) -> Matrix {
    [
        m[0] * c[0] + m[1] * c[2],
        m[0] * c[1] + m[1] * c[3],
        m[2] * c[0] + m[3] * c[2],
        m[2] * c[1] + m[3] * c[3],
        m[4] * c[0] + m[5] * c[2] + c[4],
        m[4] * c[1] + m[5] * c[3] + c[5],
    ]
}

/// Finds the drawn size of every image XObject that some page draws.
pub(crate) fn scan_placements<R: Read + Seek>(
    file: &mut PdfFile<R>,
    observer: &mut dyn PdfToolObserver,
) -> Result<Placements> {
    let mut sizes = Placements::new();
    let mut budget = OP_BUDGET;
    for node in file.page_nodes()? {
        if observer.is_cancelled() {
            return Err(EngineError::cancelled());
        }
        let Obj::Dict(page) = file.object(node.page.object)? else { continue };
        let resources_value = page.get("Resources").or_else(|| node.inherited.get("Resources")).cloned();
        let resources = match resources_value {
            Some(value) => resolve_dict(file, &value)?.unwrap_or_default(),
            None => Dict::default(),
        };
        let content = page_content(file, &page)?;
        walk(file, &content, IDENTITY, &resources, 0, &mut sizes, &mut budget)?;

        // Images this page names but never draws are sized as a full-width picture.
        let page_width = node.page.geometry.width();
        for number in xobject_refs(file, &resources)? {
            if sizes.contains_key(&number) {
                continue;
            }
            let LoadedObject { value, .. } = file.load(number)?;
            if let Obj::Dict(dict) = value {
                if dict.get("Subtype").and_then(Obj::as_name) == Some(&b"Image"[..]) {
                    let (w, h) = (number_of(file, &dict, "Width")?, number_of(file, &dict, "Height")?);
                    if w > 0.0 && h > 0.0 {
                        sizes.insert(number, (page_width, page_width * h / w));
                    }
                }
            }
        }
    }
    Ok(sizes)
}

/// Object numbers of the XObjects a resource dictionary names directly.
fn xobject_refs<R: Read + Seek>(file: &mut PdfFile<R>, resources: &Dict) -> Result<Vec<u32>> {
    let Some(xobjects) = resource_dict(file, resources, "XObject")? else { return Ok(Vec::new()) };
    Ok(xobjects
        .0
        .iter()
        .filter_map(|(_, value)| match value {
            Obj::Ref(number, _) => Some(*number),
            _ => None,
        })
        .collect())
}

/// Walks one content stream. `q`/`Q` save and restore the matrix; `cm`
/// multiplies it; `Do` records image sizes and recurses into forms.
fn walk<R: Read + Seek>(
    file: &mut PdfFile<R>,
    content: &[u8],
    start: Matrix,
    resources: &Dict,
    depth: u8,
    sizes: &mut Placements,
    budget: &mut usize,
) -> Result<()> {
    let mut lexer = Lexer::new(content);
    let mut saved: Vec<Matrix> = Vec::new();
    let mut ctm = start;
    let mut operands: Vec<f64> = Vec::with_capacity(6);
    let mut name: Option<Vec<u8>> = None;
    while let Some(token) = lexer.next_token() {
        match token {
            Token::Num(value) => {
                if operands.len() == 6 {
                    operands.remove(0);
                }
                operands.push(value);
            }
            Token::Name(bytes) => name = Some(bytes),
            Token::Other => {}
            Token::Op(op) => {
                *budget = budget.saturating_sub(1);
                if *budget == 0 {
                    return Ok(());
                }
                match op {
                    b"q" if saved.len() < 256 => saved.push(ctm),
                    b"Q" => ctm = saved.pop().unwrap_or(start),
                    b"cm" if operands.len() == 6 => {
                        let m: Matrix = [operands[0], operands[1], operands[2], operands[3], operands[4], operands[5]];
                        ctm = multiply(m, ctm);
                    }
                    b"Do" => {
                        if let Some(bytes) = name.take() {
                            do_xobject(file, &bytes, ctm, resources, depth, sizes, budget)?;
                        }
                    }
                    b"ID" => lexer.skip_inline_image(),
                    _ => {}
                }
                operands.clear();
                name = None;
            }
        }
    }
    Ok(())
}

fn do_xobject<R: Read + Seek>(
    file: &mut PdfFile<R>,
    name: &[u8],
    ctm: Matrix,
    resources: &Dict,
    depth: u8,
    sizes: &mut Placements,
    budget: &mut usize,
) -> Result<()> {
    let Some(xobjects) = resource_dict(file, resources, "XObject")? else { return Ok(()) };
    let Ok(key) = std::str::from_utf8(name) else { return Ok(()) };
    let Some(Obj::Ref(number, _)) = xobjects.get(key).cloned() else { return Ok(()) };
    if !file.has_object(number) {
        return Ok(());
    }
    let LoadedObject { value, stream_at, .. } = file.load(number)?;
    let Obj::Dict(dict) = value else { return Ok(()) };
    match dict.get("Subtype").and_then(Obj::as_name) {
        Some(b"Image") => {
            let size = ((ctm[0] * ctm[0] + ctm[1] * ctm[1]).sqrt(), (ctm[2] * ctm[2] + ctm[3] * ctm[3]).sqrt());
            let entry = sizes.entry(number).or_insert((0.0, 0.0));
            entry.0 = entry.0.max(size.0);
            entry.1 = entry.1.max(size.1);
        }
        Some(b"Form") if depth < MAX_FORM_DEPTH => {
            let Some(at) = stream_at else { return Ok(()) };
            let matrix = match dict.get("Matrix") {
                Some(value) => matrix_of(&file.resolve(value)?).unwrap_or(IDENTITY),
                None => IDENTITY,
            };
            let form_resources = match dict.get("Resources") {
                Some(value) => resolve_dict(file, value)?.unwrap_or_else(|| resources.clone()),
                None => resources.clone(),
            };
            if let Some(data) = decode_stream(file, &dict, at)? {
                walk(file, &data, multiply(matrix, ctm), &form_resources, depth + 1, sizes, budget)?;
            }
        }
        _ => {}
    }
    Ok(())
}

fn matrix_of(value: &Obj) -> Option<Matrix> {
    let Obj::Array(items) = value else { return None };
    if items.len() != 6 {
        return None;
    }
    let mut out = IDENTITY;
    for (slot, item) in out.iter_mut().zip(items) {
        *slot = item.as_f64()?;
    }
    Some(out)
}

/// The page's content streams, joined with newlines, within `MAX_STREAM_BYTES`.
fn page_content<R: Read + Seek>(file: &mut PdfFile<R>, page: &Dict) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    let Some(contents) = page.get("Contents").cloned() else { return Ok(out) };
    let parts = match contents {
        Obj::Array(items) => items,
        other => vec![other],
    };
    for part in parts {
        let Obj::Ref(number, _) = part else { continue };
        if !file.has_object(number) {
            continue;
        }
        let LoadedObject { value, stream_at, .. } = file.load(number)?;
        if let (Obj::Dict(dict), Some(at)) = (value, stream_at) {
            if let Some(bytes) = decode_stream(file, &dict, at)? {
                if out.len() as u64 + bytes.len() as u64 > MAX_STREAM_BYTES {
                    break;
                }
                out.extend_from_slice(&bytes);
                out.push(b'\n');
            }
        }
    }
    Ok(out)
}

/// The decoded data of a stream whose only filter, if any, is FlateDecode.
/// Anything else (or too large) is `None`, and the caller treats it as unknown.
fn decode_stream<R: Read + Seek>(file: &mut PdfFile<R>, dict: &Dict, at: u64) -> Result<Option<Vec<u8>>> {
    let length = number_of(file, dict, "Length")?.max(0.0) as u64;
    let extent = file.stream_extent(at, length)?;
    if extent > MAX_STREAM_BYTES {
        return Ok(None);
    }
    let raw = file.stream_raw(at, extent)?;
    Ok(match filter_names(dict).as_slice() {
        [] => Some(raw),
        [name] if name.as_slice() == b"FlateDecode" => inflate_bounded(&raw, MAX_STREAM_BYTES),
        _ => None,
    })
}

fn filter_names(dict: &Dict) -> Vec<Vec<u8>> {
    match dict.get("Filter") {
        Some(Obj::Name(name)) => vec![name.clone()],
        Some(Obj::Array(items)) => items.iter().filter_map(|item| item.as_name().map(<[u8]>::to_vec)).collect(),
        _ => Vec::new(),
    }
}

fn inflate_bounded(raw: &[u8], limit: u64) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    ZlibDecoder::new(raw).take(limit + 1).read_to_end(&mut out).ok()?;
    (out.len() as u64 <= limit).then_some(out)
}

fn resolve_dict<R: Read + Seek>(file: &mut PdfFile<R>, value: &Obj) -> Result<Option<Dict>> {
    Ok(match file.resolve(value)? {
        Obj::Dict(dict) => Some(dict),
        _ => None,
    })
}

/// A resource category (`XObject`, `ColorSpace`) as a dictionary, resolved.
fn resource_dict<R: Read + Seek>(file: &mut PdfFile<R>, resources: &Dict, key: &str) -> Result<Option<Dict>> {
    match resources.get(key) {
        Some(value) => {
            let value = value.clone();
            resolve_dict(file, &value)
        }
        None => Ok(None),
    }
}

/// A numeric entry, resolved; zero when missing or not a number.
fn number_of<R: Read + Seek>(file: &mut PdfFile<R>, dict: &Dict, key: &str) -> Result<f64> {
    Ok(match dict.get(key) {
        Some(value) => {
            let value = value.clone();
            file.resolve(&value)?.as_f64().unwrap_or(0.0)
        }
        None => 0.0,
    })
}

/// Tokens a content stream needs: numbers, names, operators. Strings, arrays
/// and dictionaries are skipped, as nothing that matters for placement is in them.
#[derive(Debug, PartialEq)]
enum Token {
    Num(f64),
    Name(Vec<u8>),
    Op(&'static [u8]),
    Other,
}

struct Lexer<'a> {
    bytes: &'a [u8],
    pos: usize,
}

fn is_space(byte: u8) -> bool {
    matches!(byte, b' ' | b'\t' | b'\n' | b'\r' | 0x0C | 0)
}

fn is_delimiter(byte: u8) -> bool {
    matches!(byte, b'(' | b')' | b'<' | b'>' | b'[' | b']' | b'{' | b'}' | b'/' | b'%')
}

impl<'a> Lexer<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, pos: 0 }
    }

    fn regular_run(&mut self) -> &'a [u8] {
        let start = self.pos;
        while self.pos < self.bytes.len() && !is_space(self.bytes[self.pos]) && !is_delimiter(self.bytes[self.pos]) {
            self.pos += 1;
        }
        &self.bytes[start..self.pos]
    }

    fn next_token(&mut self) -> Option<Token> {
        loop {
            while self.pos < self.bytes.len() && is_space(self.bytes[self.pos]) {
                self.pos += 1;
            }
            let &byte = self.bytes.get(self.pos)?;
            match byte {
                b'%' => {
                    while self.pos < self.bytes.len() && !matches!(self.bytes[self.pos], b'\n' | b'\r') {
                        self.pos += 1;
                    }
                }
                b'(' => {
                    self.skip_string();
                    return Some(Token::Other);
                }
                b'<' => {
                    // `<<` opens a dictionary; `<hex>` is skipped to its `>`.
                    if self.bytes.get(self.pos + 1) == Some(&b'<') {
                        self.pos += 2;
                    } else {
                        while self.pos < self.bytes.len() && self.bytes[self.pos] != b'>' {
                            self.pos += 1;
                        }
                        self.pos += 1;
                    }
                    return Some(Token::Other);
                }
                b'>' => {
                    self.pos += if self.bytes.get(self.pos + 1) == Some(&b'>') { 2 } else { 1 };
                    return Some(Token::Other);
                }
                b'[' | b']' | b'{' | b'}' | b')' => {
                    self.pos += 1;
                    return Some(Token::Other);
                }
                b'/' => {
                    self.pos += 1;
                    return Some(Token::Name(self.regular_run().to_vec()));
                }
                _ => {
                    let run = self.regular_run();
                    if run.is_empty() {
                        // A stray delimiter we don't model: step over it.
                        self.pos += 1;
                        return Some(Token::Other);
                    }
                    let first = run[0];
                    if first.is_ascii_digit() || matches!(first, b'+' | b'-' | b'.') {
                        return Some(match std::str::from_utf8(run).ok().and_then(|s| s.parse::<f64>().ok()) {
                            Some(value) => Token::Num(value),
                            None => Token::Other,
                        });
                    }
                    return Some(match known_operator(run) {
                        Some(op) => Token::Op(op),
                        None => Token::Other,
                    });
                }
            }
        }
    }

    /// Skips a literal string, honouring nested parentheses and backslashes.
    fn skip_string(&mut self) {
        let mut depth = 0usize;
        while self.pos < self.bytes.len() {
            match self.bytes[self.pos] {
                b'\\' => self.pos += 1,
                b'(' => depth += 1,
                b')' => {
                    depth -= 1;
                    if depth == 0 {
                        self.pos += 1;
                        return;
                    }
                }
                _ => {}
            }
            self.pos += 1;
        }
    }

    /// After `ID`, the image data is binary: skip to a whitespace-delimited `EI`.
    fn skip_inline_image(&mut self) {
        let bytes = self.bytes;
        let mut at = self.pos;
        while at + 2 <= bytes.len() {
            let before_ok = at == 0 || is_space(bytes[at - 1]);
            let after_ok = at + 2 == bytes.len() || is_space(bytes[at + 2]);
            if before_ok && after_ok && &bytes[at..at + 2] == b"EI" {
                self.pos = at + 2;
                return;
            }
            at += 1;
        }
        self.pos = bytes.len();
    }
}

/// The operators the walk acts on. Anything else is `Other`.
fn known_operator(run: &[u8]) -> Option<&'static [u8]> {
    const OPS: [&[u8]; 6] = [b"q", b"Q", b"cm", b"Do", b"ID", b"BI"];
    OPS.iter().find(|op| **op == run).copied()
}

/// The colour model of the stored samples, as the planner read it.
#[derive(Debug, Clone, PartialEq)]
pub(crate) enum Source {
    Gray,
    Rgb,
    Cmyk,
    /// 8-bit palette indices. `table` holds `entries` colours of `base` bytes each, in the base space.
    Indexed { base: usize, entries: usize, table: Vec<u8> },
}

impl Source {
    /// Bytes per pixel as stored. A palette index is one byte.
    pub(crate) fn stored_channels(&self) -> usize {
        match self {
            Self::Gray | Self::Indexed { .. } => 1,
            Self::Rgb => 3,
            Self::Cmyk => 4,
        }
    }
}

/// What a re-encoded image is written as.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Output {
    /// JPEG in the colour space of the samples (a palette is expanded to its base).
    Jpeg,
    /// Flate palette indices, downsampled by nearest-neighbour. The palette stays.
    IndexedFlate,
    /// Flate 8-bit gray: a soft mask.
    GrayFlate,
    /// Flate CMYK, downsampled. The decoded samples are written as they are, in the colour
    /// space the stream already names; `/Decode` has been applied, so it is dropped.
    CmykFlate,
}

/// The output for a palette of `entries` colours.
pub(crate) fn indexed_output(entries: usize) -> Output {
    if entries <= MAX_INDEXED_FLATE_ENTRIES { Output::IndexedFlate } else { Output::Jpeg }
}

/// What a re-encode needs, decided on the file's thread before any worker runs.
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct ImagePlan {
    pub(crate) at: u64,
    pub(crate) length: u64,
    pub(crate) width: u32,
    pub(crate) height: u32,
    pub(crate) source: Source,
    /// A `/Decode` of [1 0] per channel: the samples are inverted when decoded.
    /// Not applied to CMYK JPEGs, which keep their stored samples and `/Decode`.
    pub(crate) invert: bool,
    pub(crate) dst_width: u32,
    pub(crate) dst_height: u32,
    pub(crate) codec: Codec,
    pub(crate) output: Output,
    pub(crate) jpeg_quality: u8,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Codec {
    Jpeg,
    /// Unfiltered: the stored bytes are the pixels.
    Raw,
    /// FlateDecode; `row_len` is set when a PNG predictor (10 to 15) applies.
    Flate { row_len: Option<usize> },
}

/// The filter a re-encoded stream is written with.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Filter {
    Dct,
    Flate,
}

/// A re-encoded image, to replace the stored stream when smaller.
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct Replacement {
    pub(crate) bytes: Vec<u8>,
    pub(crate) width: u32,
    pub(crate) height: u32,
    pub(crate) filter: Filter,
    /// The colour space the new samples are in, when it changes. `None` keeps the stream's own.
    pub(crate) colour: Option<&'static str>,
    /// True when the new samples are in the stored convention, so the stream's `/Decode` still applies.
    pub(crate) keep_decode: bool,
}

/// Decides whether an image is worth re-encoding at `quality`, and how.
/// `None` means it stays as stored. The soft mask (if any) is planned by the caller.
pub(crate) fn plan_image<R: Read + Seek>(
    file: &mut PdfFile<R>,
    dict: &Dict,
    at: u64,
    length: u64,
    placed: (f64, f64),
    quality: PdfImageQuality,
) -> Result<Option<ImagePlan>> {
    let Some(target_dpi) = quality.target_dpi() else { return Ok(None) };
    if dict.get("Subtype").and_then(Obj::as_name) != Some(&b"Image"[..]) {
        return Ok(None);
    }
    // Stencils and colour-key masks can't be carried over by a plain re-encode.
    if is_set(dict, "ImageMask") || is_set(dict, "Mask") || is_set(dict, "SMaskInData") {
        return Ok(None);
    }
    if !matches!(dict.get("SMask"), None | Some(Obj::Null) | Some(Obj::Ref(..))) {
        return Ok(None);
    }
    let width = number_of(file, dict, "Width")?;
    let height = number_of(file, dict, "Height")?;
    let bits = number_of(file, dict, "BitsPerComponent")?;
    if width < 1.0 || height < 1.0 || bits != 8.0 {
        return Ok(None);
    }
    let (width, height) = (width as u32, height as u32);
    let Some(space_value) = dict.get("ColorSpace").cloned() else { return Ok(None) };
    let Some(space) = space_from(file, &space_value, true)? else { return Ok(None) };
    let Some(invert) = decode_inversion(file, dict, space.stored_channels())? else { return Ok(None) };
    let stored = space.stored_channels();
    let codec = match filter_names(dict).as_slice() {
        [name] if name.as_slice() == b"DCTDecode" => {
            // A JPEG decodes to the planned samples for gray and RGB, and for CMYK
            // when its Adobe marker says how the inks are stored (checked on re-encode).
            if !matches!(space, Source::Gray | Source::Rgb | Source::Cmyk) {
                return Ok(None);
            }
            Codec::Jpeg
        }
        [] => Codec::Raw,
        [name] if name.as_slice() == b"FlateDecode" => {
            match predictor_row_len(file, dict, width, stored as u8)? {
                Err(()) => return Ok(None),
                Ok(row_len) => Codec::Flate { row_len },
            }
        }
        _ => return Ok(None),
    };
    let (placed_w, placed_h) = placed;
    if placed_w <= 0.0 || placed_h <= 0.0 {
        return Ok(None);
    }
    let pixels = u64::from(width) * u64::from(height);
    if pixels > MAX_PIXELS || width.max(height) < MIN_SIDE {
        return Ok(None);
    }
    // The sharper axis sets the scale: the other axis is then at or below target.
    let dpi = (f64::from(width) / (placed_w / 72.0)).max(f64::from(height) / (placed_h / 72.0));
    let scale = target_dpi / dpi;
    if scale >= MIN_SCALE_CHANGE {
        return Ok(None);
    }
    let dst_width = ((f64::from(width) * scale).round() as u32).max(1);
    let dst_height = ((f64::from(height) * scale).round() as u32).max(1);
    // Flate or raw CMYK stays Flate: no JPEG is written, so no Adobe inversion convention is assumed.
    let output = match &space {
        Source::Indexed { entries, .. } => indexed_output(*entries),
        Source::Cmyk if codec != Codec::Jpeg => Output::CmykFlate,
        _ => Output::Jpeg,
    };
    Ok(Some(ImagePlan {
        at,
        length,
        width,
        height,
        source: space,
        invert,
        dst_width,
        dst_height,
        codec,
        output,
        jpeg_quality: quality.jpeg_quality(),
    }))
}

/// Plans the soft mask of a planned image: 8-bit gray, downsampled to `target`
/// (the colour image's new size). `None` when it can't be carried or is already smaller.
pub(crate) fn plan_soft_mask<R: Read + Seek>(
    file: &mut PdfFile<R>,
    dict: &Dict,
    at: u64,
    length: u64,
    target: (u32, u32),
) -> Result<Option<ImagePlan>> {
    if dict.get("Subtype").is_some_and(|v| v.as_name() != Some(&b"Image"[..])) {
        return Ok(None);
    }
    // A matte, decode map or stencil flag changes what the mask means.
    if is_set(dict, "ImageMask") || is_set(dict, "Decode") || is_set(dict, "Matte") || is_set(dict, "Mask") {
        return Ok(None);
    }
    let width = number_of(file, dict, "Width")?;
    let height = number_of(file, dict, "Height")?;
    if width < 1.0 || height < 1.0 || number_of(file, dict, "BitsPerComponent")? != 8.0 {
        return Ok(None);
    }
    // No ColorSpace is DeviceGray, the default for a mask.
    let gray = match dict.get("ColorSpace").cloned() {
        None | Some(Obj::Null) => true,
        Some(value) => file.resolve(&value)?.as_name() == Some(&b"DeviceGray"[..]),
    };
    if !gray {
        return Ok(None);
    }
    let (width, height) = (width as u32, height as u32);
    if u64::from(width) * u64::from(height) > MAX_PIXELS {
        return Ok(None);
    }
    let (dst_width, dst_height) = target;
    if dst_width >= width && dst_height >= height {
        return Ok(None);
    }
    let codec = match filter_names(dict).as_slice() {
        [] => Codec::Raw,
        [name] if name.as_slice() == b"FlateDecode" => match predictor_row_len(file, dict, width, 1)? {
            Err(()) => return Ok(None),
            Ok(row_len) => Codec::Flate { row_len },
        },
        _ => return Ok(None),
    };
    Ok(Some(ImagePlan {
        at,
        length,
        width,
        height,
        source: Source::Gray,
        invert: false,
        dst_width,
        dst_height,
        codec,
        output: Output::GrayFlate,
        jpeg_quality: 0,
    }))
}

/// The Adobe APP14 transform and the frame's component count of a JPEG, read from its
/// markers. `None` for no Adobe marker or more than one, no frame, more than one frame,
/// or a lossless or arithmetic frame.
fn adobe_jpeg_info(raw: &[u8]) -> Option<(u8, u8)> {
    if raw.get(..2)? != [0xFF, 0xD8] {
        return None;
    }
    let mut at = 2usize;
    let mut transform = None;
    let mut components = None;
    while at + 1 < raw.len() {
        if raw[at] != 0xFF {
            return None;
        }
        let marker = raw[at + 1];
        at += 2;
        match marker {
            // A fill byte before a marker: step back onto the next 0xFF.
            0xFF => at -= 1,
            0xD8 | 0x01 | 0xD0..=0xD7 => {}
            0xD9 | 0xDA => break,
            _ => {
                let len = usize::from(u16::from_be_bytes([*raw.get(at)?, *raw.get(at + 1)?]));
                if len < 2 {
                    return None;
                }
                // Segment payload, after the two length bytes.
                let seg = raw.get(at + 2..at + len)?;
                match marker {
                    // "Adobe", version (2), flags (2 + 2), transform (1).
                    0xEE if seg.len() >= 12 && &seg[..5] == b"Adobe" => {
                        if transform.is_some() {
                            return None;
                        }
                        transform = Some(seg[11]);
                    }
                    // Precision (1), height (2), width (2), components (1).
                    0xC0 | 0xC1 | 0xC2 => {
                        if components.is_some() || seg.len() < 6 {
                            return None;
                        }
                        components = Some(seg[5]);
                    }
                    0xC3 | 0xC5..=0xC7 | 0xC9..=0xCB | 0xCD..=0xCF => return None,
                    _ => {}
                }
                at += len;
            }
        }
    }
    Some((transform?, components?))
}

/// The soft mask an image names, if it names one by reference.
pub(crate) fn soft_mask_of(dict: &Dict) -> Option<u32> {
    match dict.get("SMask") {
        Some(Obj::Ref(number, _)) => Some(*number),
        _ => None,
    }
}

/// True for a key set to anything but `false` or null.
fn is_set(dict: &Dict, key: &str) -> bool {
    dict.get(key).is_some_and(|value| !matches!(value, Obj::Bool(false) | Obj::Null))
}

/// Maps a device colour space name to its source model.
fn device_space(name: &[u8]) -> Option<Source> {
    match name {
        b"DeviceGray" => Some(Source::Gray),
        b"DeviceRGB" => Some(Source::Rgb),
        b"DeviceCMYK" => Some(Source::Cmyk),
        _ => None,
    }
}

/// A colour space as a source model. An Indexed space takes its palette from
/// its lookup string or stream, and only over a device or ICC base (no nesting).
/// `None` for anything else (Separation, Lab, DeviceN, pattern, ...).
fn space_from<R: Read + Seek>(file: &mut PdfFile<R>, value: &Obj, allow_indexed: bool) -> Result<Option<Source>> {
    let value = file.resolve(value)?;
    match &value {
        Obj::Name(name) => Ok(device_space(name)),
        Obj::Array(items) if items.len() >= 2 => {
            let kind = items[0].as_name();
            if kind == Some(&b"ICCBased"[..]) {
                let Obj::Dict(profile) = file.resolve(&items[1])? else { return Ok(None) };
                return Ok(match number_of(file, &profile, "N")? as u8 {
                    1 => Some(Source::Gray),
                    3 => Some(Source::Rgb),
                    4 => Some(Source::Cmyk),
                    _ => None,
                });
            }
            if kind == Some(&b"Indexed"[..]) || kind == Some(&b"I"[..]) {
                if !allow_indexed || items.len() != 4 {
                    return Ok(None);
                }
                let Some(base) = space_from(file, &items[1], false)? else { return Ok(None) };
                let hival = file.resolve(&items[2])?.as_f64().unwrap_or(-1.0);
                if !(0.0..=255.0).contains(&hival) {
                    return Ok(None);
                }
                let entries = hival as usize + 1;
                let base_channels = base.stored_channels();
                let Some(lookup) = palette_bytes(file, &items[3])? else { return Ok(None) };
                let needed = entries * base_channels;
                if lookup.len() < needed {
                    return Ok(None);
                }
                return Ok(Some(Source::Indexed {
                    base: base_channels,
                    entries,
                    table: lookup[..needed].to_vec(),
                }));
            }
            Ok(None)
        }
        _ => Ok(None),
    }
}

/// The bytes of an Indexed palette: a string, or a (possibly filtered) stream.
fn palette_bytes<R: Read + Seek>(file: &mut PdfFile<R>, value: &Obj) -> Result<Option<Vec<u8>>> {
    match value {
        Obj::Str(bytes) => Ok(Some(bytes.clone())),
        Obj::Ref(number, _) => {
            if !file.has_object(*number) {
                return Ok(None);
            }
            let LoadedObject { value, stream_at, .. } = file.load(*number)?;
            Ok(match (value, stream_at) {
                (Obj::Dict(dict), Some(at)) => decode_stream(file, &dict, at)?,
                (Obj::Str(bytes), _) => Some(bytes),
                _ => None,
            })
        }
        _ => Ok(None),
    }
}

/// `Some(true)` when the `/Decode` array inverts every channel, `Some(false)` for
/// none (absent, or the default), and `None` for any other map, which is refused.
fn decode_inversion<R: Read + Seek>(file: &mut PdfFile<R>, dict: &Dict, channels: usize) -> Result<Option<bool>> {
    let Some(value) = dict.get("Decode").cloned() else { return Ok(Some(false)) };
    let value = file.resolve(&value)?;
    let items = match value {
        Obj::Null => return Ok(Some(false)),
        Obj::Array(items) => items,
        _ => return Ok(None),
    };
    let mut numbers = Vec::with_capacity(items.len());
    for item in &items {
        // A non-number anywhere makes the whole map unknown.
        match file.resolve(item)?.as_f64() {
            Some(number) => numbers.push(number),
            None => return Ok(None),
        }
    }
    Ok(inversion_of_pairs(&numbers, channels))
}

/// The inversion a flat `[min max ...]` array means for `channels` 8-bit channels.
/// The default map is `Some(false)`; `[1 0]` per channel is `Some(true)`.
pub(crate) fn inversion_of_pairs(numbers: &[f64], channels: usize) -> Option<bool> {
    // An Indexed image's default map is the full index range, `[0 255]`.
    if channels == 1 && numbers == &[0.0, 255.0][..] {
        return Some(false);
    }
    if numbers.len() != 2 * channels {
        return None;
    }
    let pairs: Vec<(f64, f64)> = numbers.chunks_exact(2).map(|p| (p[0], p[1])).collect();
    let plain = pairs.iter().all(|&(lo, hi)| lo == 0.0 && hi == 1.0);
    let inverted = pairs.iter().all(|&(lo, hi)| lo == 1.0 && hi == 0.0);
    match (plain, inverted) {
        (true, _) => Some(false),
        (_, true) => Some(true),
        _ => None,
    }
}

/// `Err(())` for a predictor this module can't undo; `Ok(None)` for none;
/// `Ok(Some(row_len))` for a PNG predictor whose rows match the image.
fn predictor_row_len<R: Read + Seek>(
    file: &mut PdfFile<R>,
    dict: &Dict,
    width: u32,
    channels: u8,
) -> Result<std::result::Result<Option<usize>, ()>> {
    let parms = match dict.get("DecodeParms") {
        Some(value) => {
            let value = value.clone();
            match file.resolve(&value)? {
                Obj::Dict(parms) => Some(parms),
                _ => None,
            }
        }
        None => None,
    };
    let Some(parms) = parms else { return Ok(Ok(None)) };
    let predictor = number_of(file, &parms, "Predictor")? as u32;
    if predictor <= 1 {
        return Ok(Ok(None));
    }
    let colors = number_of(file, &parms, "Colors")? as u32;
    let columns = number_of(file, &parms, "Columns")? as u32;
    let bits = number_of(file, &parms, "BitsPerComponent")? as u32;
    let colors = if colors == 0 { 1 } else { colors };
    let bits = if bits == 0 { 8 } else { bits };
    let columns = if columns == 0 { 1 } else { columns };
    let png = (10..=15).contains(&predictor);
    if !png || bits != 8 || colors != u32::from(channels) || columns != width {
        return Ok(Err(()));
    }
    Ok(Ok(Some(width as usize * channels as usize)))
}

/// Re-encodes a batch of planned images on worker threads and returns those
/// that came out smaller, keyed by the caller's index. The file is read here,
/// on the calling thread, and the workers only see the bytes.
pub(crate) fn recompress_batch<R: Read + Seek>(
    file: &mut PdfFile<R>,
    batch: &[(usize, ImagePlan)],
) -> Result<HashMap<usize, Replacement>> {
    let mut jobs = Vec::with_capacity(batch.len());
    for (index, plan) in batch {
        let raw = file.stream_raw(plan.at, plan.length)?;
        jobs.push((*index, plan.clone(), raw));
    }
    let results: Vec<(usize, Option<Replacement>)> = thread::scope(|scope| {
        let handles: Vec<_> = jobs
            .into_iter()
            .map(|(index, plan, raw)| {
                let stored = raw.len();
                scope.spawn(move || {
                    let out = reencode(&plan, &raw).filter(|rep| rep.bytes.len() < stored);
                    (index, out)
                })
            })
            .collect();
        // A worker that panicked leaves its image as stored.
        handles.into_iter().filter_map(|handle| handle.join().ok()).collect()
    });
    Ok(results.into_iter().filter_map(|(index, rep)| rep.map(|rep| (index, rep))).collect())
}

/// Worker count: the cores, capped at `MAX_WORKERS`.
pub(crate) fn worker_count() -> usize {
    thread::available_parallelism().map_or(1, |n| n.get()).clamp(1, MAX_WORKERS)
}

/// Decode, invert if planned, expand a palette, downsample, encode. `None` when
/// the stream doesn't match its plan.
fn reencode(plan: &ImagePlan, raw: &[u8]) -> Option<Replacement> {
    if matches!(plan.source, Source::Cmyk) && plan.codec == Codec::Jpeg {
        return reencode_cmyk_jpeg(plan, raw);
    }
    let (width, height) = (plan.width as usize, plan.height as usize);
    let stored = plan.source.stored_channels();
    let expected = width * height * stored;
    let mut samples = match plan.codec {
        Codec::Jpeg => decode_jpeg_as(raw, expected, if stored == 1 { ColorSpace::Luma } else { ColorSpace::RGB })?,
        Codec::Raw => {
            if raw.len() != expected {
                return None;
            }
            raw.to_vec()
        }
        Codec::Flate { row_len } => {
            let data = inflate_bounded(raw, expected as u64 + height as u64 * 2 + 1024)?;
            let data = match row_len {
                None => data,
                Some(row_len) => undo_png_predictor(&data, row_len, stored)?,
            };
            if data.len() != expected {
                return None;
            }
            data
        }
    };
    if plan.invert {
        invert_samples(&mut samples);
    }
    let (dw, dh) = (plan.dst_width as usize, plan.dst_height as usize);

    if let (Source::Indexed { .. }, Output::IndexedFlate) = (&plan.source, plan.output) {
        let small = nearest_resample(&samples, width, height, dw, dh);
        return Some(Replacement {
            bytes: deflate_bytes(&small)?,
            width: plan.dst_width,
            height: plan.dst_height,
            filter: Filter::Flate,
            colour: None,
            keep_decode: false,
        });
    }

    let (colours, channels) = match &plan.source {
        Source::Indexed { base, entries, table } => (expand_palette(&samples, table, *base, *entries)?, *base),
        source => (samples, source.stored_channels()),
    };
    let small = box_resample(&colours, width, height, channels, dw, dh);
    drop(colours);
    match plan.output {
        Output::GrayFlate if channels == 1 => Some(Replacement {
            bytes: deflate_bytes(&small)?,
            width: plan.dst_width,
            height: plan.dst_height,
            filter: Filter::Flate,
            colour: Some("DeviceGray"),
            keep_decode: false,
        }),
        Output::CmykFlate if channels == 4 => Some(Replacement {
            bytes: deflate_bytes(&small)?,
            width: plan.dst_width,
            height: plan.dst_height,
            filter: Filter::Flate,
            colour: None,
            keep_decode: false,
        }),
        Output::Jpeg if channels != 4 => {
            let bytes = encode_jpeg(&small, dw, dh, channels, plan.jpeg_quality)?;
            Some(Replacement {
                bytes,
                width: plan.dst_width,
                height: plan.dst_height,
                filter: Filter::Dct,
                colour: Some(device_name(channels)),
                keep_decode: false,
            })
        }
        _ => None,
    }
}

/// Re-encodes a DCTDecode CMYK JPEG. Only a JPEG whose single Adobe APP14 marker
/// names transform 0 (CMYK) or 2 (YCCK) qualifies, with four components in its frame.
/// The stored samples are kept in their own convention: the output has the same
/// transform and the same sample values, downsampled, and the stream keeps its
/// `/Decode` and colour space, so a viewer shows the page as before. Anything else
/// (no marker, another transform, a frame of another size) returns `None`.
fn reencode_cmyk_jpeg(plan: &ImagePlan, raw: &[u8]) -> Option<Replacement> {
    let (transform, components) = adobe_jpeg_info(raw)?;
    if components != 4 {
        return None;
    }
    let (width, height) = (plan.width as usize, plan.height as usize);
    let (dw, dh) = (plan.dst_width as usize, plan.dst_height as usize);
    // Transform 0 decodes to the stored CMYK values; transform 2 to the stored YCC plus K.
    // Neither is converted, so the samples are the ones the marker describes.
    let out_space = match transform {
        0 => ColorSpace::CMYK,
        2 => ColorSpace::YCCK,
        _ => return None,
    };
    let samples = decode_jpeg_as(raw, width * height * 4, out_space)?;
    let small = box_resample(&samples, width, height, 4, dw, dh);
    drop(samples);
    let bytes = encode_cmyk_jpeg(&small, dw, dh, plan.jpeg_quality, transform)?;
    Some(Replacement {
        bytes,
        width: plan.dst_width,
        height: plan.dst_height,
        filter: Filter::Dct,
        colour: None,
        keep_decode: true,
    })
}

/// The device colour space name for `channels` (1, 3 or 4).
fn device_name(channels: usize) -> &'static str {
    match channels {
        1 => "DeviceGray",
        3 => "DeviceRGB",
        _ => "DeviceCMYK",
    }
}

/// Decodes a JPEG to `space`. For CMYK or YCCK the frame is not converted. Gray decodes
/// as Luma and RGB as RGB: zune's default output is RGB, which turned gray into 3 channels.
/// The output length must be `expected`, or the stream is refused.
fn decode_jpeg_as(raw: &[u8], expected: usize, space: ColorSpace) -> Option<Vec<u8>> {
    let options = DecoderOptions::default().jpeg_set_out_colorspace(space);
    let mut decoder = JpegDecoder::new_with_options(raw, options);
    let pixels = decoder.decode().ok()?;
    (pixels.len() == expected).then_some(pixels)
}

/// JPEG of gray or RGB samples at `quality`, as they are.
fn encode_jpeg(samples: &[u8], width: usize, height: usize, channels: usize, quality: u8) -> Option<Vec<u8>> {
    let (w16, h16) = (u16::try_from(width).ok()?, u16::try_from(height).ok()?);
    let mut bytes = Vec::with_capacity(samples.len() / 8);
    match channels {
        1 => Encoder::new(&mut bytes, quality).encode(samples, w16, h16, ColorType::Luma).ok()?,
        3 => Encoder::new(&mut bytes, quality).encode(samples, w16, h16, ColorType::Rgb).ok()?,
        _ => return None,
    }
    Some(bytes)
}

/// JPEG of four-channel samples at `quality`, written with an Adobe APP14 marker
/// whose transform is `transform` (0 for CMYK, 2 for YCCK). The samples are stored
/// unchanged: jpeg-encoder's CMYK writer inverts what it is given, so this inverts
/// first to cancel that out. Sampling is 4:4:4 because each plane is a separation
/// or a luma-like channel that subsampling would blur.
fn encode_cmyk_jpeg(samples: &[u8], width: usize, height: usize, quality: u8, transform: u8) -> Option<Vec<u8>> {
    let (w16, h16) = (u16::try_from(width).ok()?, u16::try_from(height).ok()?);
    let inverted: Vec<u8>;
    let (data, colour) = match transform {
        0 => {
            inverted = samples.iter().map(|b| 255 - *b).collect();
            (inverted.as_slice(), ColorType::Cmyk)
        }
        2 => (samples, ColorType::Ycck),
        _ => return None,
    };
    let mut bytes = Vec::with_capacity(samples.len() / 8);
    let mut encoder = Encoder::new(&mut bytes, quality);
    encoder.set_sampling_factor(SamplingFactor::R_4_4_4);
    encoder.encode(data, w16, h16, colour).ok()?;
    Some(bytes)
}

/// Zlib-compresses at the best level, as PDF's FlateDecode expects.
fn deflate_bytes(data: &[u8]) -> Option<Vec<u8>> {
    let mut encoder = ZlibEncoder::new(Vec::with_capacity(data.len() / 4), Compression::best());
    encoder.write_all(data).ok()?;
    encoder.finish().ok()
}

/// Inverts 8-bit samples in place: the `/Decode` [1 0] map.
fn invert_samples(samples: &mut [u8]) {
    for sample in samples.iter_mut() {
        *sample = 255 - *sample;
    }
}

/// Palette expansion. `table` holds `entries` colours of `base` bytes each. Each
/// index is a direct lookup; an index past the palette takes the last colour.
pub(crate) fn expand_palette(indices: &[u8], table: &[u8], base: usize, entries: usize) -> Option<Vec<u8>> {
    if entries == 0 || base == 0 || table.len() < entries * base {
        return None;
    }
    let mut out = Vec::with_capacity(indices.len() * base);
    for &index in indices {
        let at = usize::from(index).min(entries - 1) * base;
        out.extend_from_slice(&table[at..at + base]);
    }
    Some(out)
}

/// Nearest-neighbour downsample of single-byte samples: each destination pixel
/// copies the source pixel at the centre of its cell. No new values appear, so
/// palette indices stay valid.
pub(crate) fn nearest_resample(src: &[u8], w: usize, h: usize, dw: usize, dh: usize) -> Vec<u8> {
    let xs: Vec<usize> = (0..dw).map(|x| ((2 * x + 1) * w / (2 * dw)).min(w - 1)).collect();
    let mut out = Vec::with_capacity(dw * dh);
    for dy in 0..dh {
        let sy = ((2 * dy + 1) * h / (2 * dh)).min(h - 1);
        let row = &src[sy * w..sy * w + w];
        out.extend(xs.iter().map(|&sx| row[sx]));
    }
    out
}

/// Undoes PNG row predictors. Each row starts with its filter type byte.
fn undo_png_predictor(data: &[u8], row_len: usize, bpp: usize) -> Option<Vec<u8>> {
    let stride = row_len + 1;
    if row_len == 0 || data.len() % stride != 0 {
        return None;
    }
    let rows = data.len() / stride;
    let mut out = vec![0u8; rows * row_len];
    for row in 0..rows {
        let filter = data[row * stride];
        let src = &data[row * stride + 1..(row + 1) * stride];
        let (done, rest) = out.split_at_mut(row * row_len);
        let prev: Option<&[u8]> = if row == 0 { None } else { Some(&done[(row - 1) * row_len..]) };
        let cur = &mut rest[..row_len];
        for i in 0..row_len {
            let left = if i >= bpp { cur[i - bpp] } else { 0 };
            let up = prev.map_or(0, |p| p[i]);
            let up_left = match prev {
                Some(p) if i >= bpp => p[i - bpp],
                _ => 0,
            };
            let x = src[i];
            cur[i] = match filter {
                0 => x,
                1 => x.wrapping_add(left),
                2 => x.wrapping_add(up),
                3 => x.wrapping_add(((u16::from(left) + u16::from(up)) / 2) as u8),
                4 => x.wrapping_add(paeth(left, up, up_left)),
                _ => return None,
            };
        }
    }
    Some(out)
}

fn paeth(a: u8, b: u8, c: u8) -> u8 {
    let p = i16::from(a) + i16::from(b) - i16::from(c);
    let (pa, pb, pc) = ((p - i16::from(a)).abs(), (p - i16::from(b)).abs(), (p - i16::from(c)).abs());
    if pa <= pb && pa <= pc {
        a
    } else if pb <= pc {
        b
    } else {
        c
    }
}

/// Box-filter downsample. Each destination pixel is the mean of the source
/// rectangle it covers. Each source row is summed through prefix sums, so the
/// cost is one pass over the source, and only one destination row's sums are
/// held at a time.
pub(crate) fn box_resample(src: &[u8], w: usize, h: usize, ch: usize, dw: usize, dh: usize) -> Vec<u8> {
    let span = |index: usize, from: usize, to: usize| -> (usize, usize) {
        let start = index * from / to;
        let end = ((index + 1) * from / to).max(start + 1).min(from);
        (start, end)
    };
    let mut out = vec![0u8; dw * dh * ch];
    let mut row_sums = vec![0u64; dw * ch];
    let mut prefix = vec![0u64; (w + 1) * ch];
    let x_spans: Vec<(usize, usize)> = (0..dw).map(|x| span(x, w, dw)).collect();
    for dy in 0..dh {
        let (y0, y1) = span(dy, h, dh);
        row_sums.iter_mut().for_each(|v| *v = 0);
        for y in y0..y1 {
            let row = &src[y * w * ch..(y + 1) * w * ch];
            for i in 0..w {
                for c in 0..ch {
                    prefix[(i + 1) * ch + c] = prefix[i * ch + c] + u64::from(row[i * ch + c]);
                }
            }
            for (x, &(xs, xe)) in x_spans.iter().enumerate() {
                for c in 0..ch {
                    row_sums[x * ch + c] += prefix[xe * ch + c] - prefix[xs * ch + c];
                }
            }
        }
        for (x, &(xs, xe)) in x_spans.iter().enumerate() {
            let count = ((xe - xs) * (y1 - y0)) as u64;
            for c in 0..ch {
                let mean = (row_sums[x * ch + c] + count / 2) / count;
                out[(dy * dw + x) * ch + c] = mean.min(255) as u8;
            }
        }
    }
    out
}

/// The stream dictionary for a re-encoded image: its other keys stay, the
/// filter and size describe the new samples, and the colour space changes only
/// when the replacement says so. `Decode` is gone, since the samples were
/// decoded and inverted already.
pub(crate) fn replacement_dict(mut dict: Dict, rep: &Replacement) -> Dict {
    for key in ["Length", "Filter", "DecodeParms", "DP", "Width", "Height", "BitsPerComponent"] {
        dict.remove(key);
    }
    if !rep.keep_decode {
        dict.remove("Decode");
    }
    if let Some(colour) = rep.colour {
        dict.set("ColorSpace", Obj::name(colour));
    }
    dict.set("Type", Obj::name("XObject"));
    dict.set("Subtype", Obj::name("Image"));
    dict.set("Width", Obj::int(u64::from(rep.width)));
    dict.set("Height", Obj::int(u64::from(rep.height)));
    dict.set("BitsPerComponent", Obj::int(8));
    dict.set(
        "Filter",
        Obj::name(match rep.filter {
            Filter::Dct => "DCTDecode",
            Filter::Flate => "FlateDecode",
        }),
    );
    dict
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn multiply_applies_the_outer_matrix_last() {
        // Scale by 2, then translate the scaled space by (10, 20) in the outer frame.
        let scale = [2.0, 0.0, 0.0, 2.0, 0.0, 0.0];
        let translate = [1.0, 0.0, 0.0, 1.0, 10.0, 20.0];
        let m = multiply(translate, scale);
        assert_eq!(m, [2.0, 0.0, 0.0, 2.0, 10.0, 20.0]);
        assert_eq!(multiply(IDENTITY, scale), scale);
    }

    #[test]
    fn lexer_reads_cm_and_do_and_skips_strings() {
        let mut lexer = Lexer::new(b"q 200 0 0 100 5 6 cm (ignored Do) Tj /Im1 Do Q");
        let mut ops = Vec::new();
        while let Some(token) = lexer.next_token() {
            if let Token::Op(op) = token {
                ops.push(op);
            }
        }
        assert_eq!(ops, vec![&b"q"[..], &b"cm"[..], &b"Do"[..], &b"Q"[..]]);
    }

    #[test]
    fn inline_image_data_is_skipped_up_to_ei() {
        let mut lexer = Lexer::new(b"ID \x00\x01 EI q");
        assert_eq!(lexer.next_token(), Some(Token::Op(b"ID")));
        lexer.skip_inline_image();
        assert_eq!(lexer.next_token(), Some(Token::Op(b"q")));
    }

    #[test]
    fn png_up_and_paeth_rows_undo_to_the_original() {
        // Two rows of 3 bytes, bpp 1: row 0 uses Sub, row 1 uses Up.
        let encoded = [1u8, 10, 5, 5, 2, 1, 1, 1];
        let decoded = undo_png_predictor(&encoded, 3, 1).expect("valid");
        assert_eq!(decoded, vec![10, 15, 20, 11, 16, 21]);
        assert_eq!(paeth(10, 20, 10), 20);
        assert!(undo_png_predictor(&[9u8, 1, 2, 3], 3, 1).is_none());
    }

    #[test]
    fn box_filter_averages_blocks_and_keeps_flat_images_flat() {
        // 4x4 gray, left half 0, right half 200: a 2x2 result is 0 and 200 per row.
        let mut src = Vec::new();
        for _ in 0..4 {
            src.extend_from_slice(&[0, 0, 200, 200]);
        }
        assert_eq!(box_resample(&src, 4, 4, 1, 2, 2), vec![0, 200, 0, 200]);
        let flat = vec![77u8; 5 * 5 * 3];
        assert!(box_resample(&flat, 5, 5, 3, 2, 3).iter().all(|&v| v == 77));
    }

    #[test]
    fn quality_levels_map_to_their_targets() {
        assert_eq!(PdfImageQuality::Lossless.target_dpi(), None);
        assert_eq!(PdfImageQuality::Balanced.target_dpi(), Some(150.0));
        assert_eq!(PdfImageQuality::Strong.target_dpi(), Some(110.0));
        assert!(PdfImageQuality::Strong.jpeg_quality() < PdfImageQuality::Balanced.jpeg_quality());
    }

    #[test]
    fn palette_expansion_is_a_lookup_and_clamps_past_the_end() {
        // Two colours over RGB: red, green. Index 5 is past the palette and takes the last colour.
        let table = [255u8, 0, 0, 0, 255, 0];
        let out = expand_palette(&[0, 1, 5], &table, 3, 2).unwrap();
        assert_eq!(out, vec![255, 0, 0, 0, 255, 0, 0, 255, 0]);
        // A table shorter than its entries is refused, never read past its end.
        assert!(expand_palette(&[0], &table[..4], 3, 2).is_none());
        assert!(expand_palette(&[0], &table, 3, 0).is_none());
    }

    #[test]
    fn nearest_downsample_only_copies_existing_indices() {
        // 4x1 indices to 2x1: the centres of each half-cell, so no new value appears.
        assert_eq!(nearest_resample(&[10, 20, 30, 40], 4, 1, 2, 1), vec![20, 40]);
        let indices: Vec<u8> = (0..16).collect();
        let small = nearest_resample(&indices, 4, 4, 2, 2);
        assert!(small.iter().all(|v| indices.contains(v)));
    }

    #[test]
    fn decode_maps_that_invert_are_applied_and_others_refused() {
        assert_eq!(inversion_of_pairs(&[0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0], 4), Some(false));
        assert_eq!(inversion_of_pairs(&[1.0, 0.0, 1.0, 0.0, 1.0, 0.0], 3), Some(true));
        assert_eq!(inversion_of_pairs(&[0.0, 0.5], 1), None);
        assert_eq!(inversion_of_pairs(&[0.0, 1.0], 3), None, "wrong number of pairs");
        let mut samples = vec![0u8, 255, 10];
        invert_samples(&mut samples);
        assert_eq!(samples, vec![255, 0, 245]);
    }

    #[test]
    fn indexed_small_palettes_keep_indices_large_ones_expand() {
        assert_eq!(indexed_output(16), Output::IndexedFlate);
        assert_eq!(indexed_output(17), Output::Jpeg);
        assert_eq!(Source::Indexed { base: 3, entries: 2, table: vec![0; 6] }.stored_channels(), 1);
        assert_eq!(Source::Cmyk.stored_channels(), 4);
    }

    #[test]
    fn deflate_round_trips() {
        use std::io::Read as _;
        let data = b"palette indices ".repeat(40);
        let packed = deflate_bytes(&data).unwrap();
        assert!(packed.len() < data.len());
        let mut back = Vec::new();
        ZlibDecoder::new(packed.as_slice()).read_to_end(&mut back).unwrap();
        assert_eq!(back, data);
    }

    /// A deterministic CMYK test image: each channel is a different ramp.
    fn cmyk_ramp(width: usize, height: usize) -> Vec<u8> {
        let mut out = Vec::with_capacity(width * height * 4);
        for y in 0..height {
            for x in 0..width {
                out.push((x * 255 / (width - 1)) as u8);
                out.push((y * 255 / (height - 1)) as u8);
                out.push(((x + y) * 255 / (width + height - 2)) as u8);
                out.push((40 + (x * 3 + y * 2) % 120) as u8);
            }
        }
        out
    }

    /// Removes the APP14 segment, so the JPEG has no Adobe marker.
    fn strip_app14(jpeg: &[u8]) -> Vec<u8> {
        let at = jpeg.windows(2).position(|w| w == [0xFF, 0xEE]).expect("has APP14");
        let len = usize::from(u16::from_be_bytes([jpeg[at + 2], jpeg[at + 3]]));
        let mut out = jpeg[..at].to_vec();
        out.extend_from_slice(&jpeg[at + 2 + len..]);
        out
    }

    /// Largest and mean absolute difference between two sample buffers.
    fn diff(a: &[u8], b: &[u8]) -> (u8, f64) {
        assert_eq!(a.len(), b.len());
        let max = a.iter().zip(b).map(|(x, y)| x.abs_diff(*y)).max().unwrap_or(0);
        let mean = a.iter().zip(b).map(|(x, y)| f64::from(x.abs_diff(*y))).sum::<f64>() / a.len() as f64;
        (max, mean)
    }

    fn cmyk_plan(width: u32, height: u32, dst: (u32, u32)) -> ImagePlan {
        ImagePlan {
            at: 0,
            length: 0,
            width,
            height,
            source: Source::Cmyk,
            invert: false,
            dst_width: dst.0,
            dst_height: dst.1,
            codec: Codec::Jpeg,
            output: Output::Jpeg,
            jpeg_quality: 95,
        }
    }

    #[test]
    fn cmyk_jpeg_writer_stores_samples_and_marks_the_transform() {
        // The writer's stored values must equal the samples for both transforms, or the
        // output would be inverted against the source.
        let (w, h) = (64, 48);
        let samples = cmyk_ramp(w, h);
        for transform in [0u8, 2] {
            let bytes = encode_cmyk_jpeg(&samples, w, h, 95, transform).expect("encodes");
            assert_eq!(&bytes[..2], &[0xFF, 0xD8], "starts with SOI");
            let at = bytes.windows(5).position(|v| v == b"Adobe").expect("Adobe APP14");
            assert_eq!(bytes[at + 11], transform, "APP14 transform byte");
            let space = if transform == 0 { ColorSpace::CMYK } else { ColorSpace::YCCK };
            let back = decode_jpeg_as(&bytes, samples.len(), space).expect("decodes");
            let (max, mean) = diff(&back, &samples);
            assert!(mean < 2.0 && max < 24, "transform {transform}: max {max}, mean {mean}");
        }
    }

    #[test]
    fn adobe_marker_and_frame_are_read_from_markers() {
        let samples = cmyk_ramp(16, 16);
        let cmyk = encode_cmyk_jpeg(&samples, 16, 16, 75, 0).expect("encodes");
        assert_eq!(adobe_jpeg_info(&cmyk), Some((0, 4)));
        let ycck = encode_cmyk_jpeg(&samples, 16, 16, 75, 2).expect("encodes");
        assert_eq!(adobe_jpeg_info(&ycck), Some((2, 4)));
        assert_eq!(adobe_jpeg_info(&strip_app14(&cmyk)), None, "no marker, no answer");
        let rgb = encode_jpeg(&cmyk_ramp(16, 16)[..16 * 16 * 3], 16, 16, 3, 75).expect("encodes");
        assert_eq!(adobe_jpeg_info(&rgb), None, "RGB without a marker");
        assert_eq!(adobe_jpeg_info(b"not a jpeg"), None);
    }

    /// The round trip the recompress path does: decode, box-downsample, encode. The
    /// decoded result must match the downsampled source in the source's own convention.
    /// An inverted output would differ by about 255 and fail here.
    #[test]
    fn cmyk_jpeg_reencode_keeps_convention_and_downsamples() {
        let (w, h) = (64usize, 48usize);
        let samples = cmyk_ramp(w, h);
        for transform in [0u8, 2] {
            let source = encode_cmyk_jpeg(&samples, w, h, 95, transform).expect("encodes");
            let rep = reencode(&cmyk_plan(w as u32, h as u32, (32, 24)), &source).expect("re-encodes");
            assert_eq!(rep.filter, Filter::Dct);
            assert_eq!(rep.colour, None, "colour space stays as stored");
            assert!(rep.keep_decode, "the stream's /Decode still applies");
            assert!(rep.bytes.len() < source.len(), "smaller than the stored JPEG");
            assert_eq!(adobe_jpeg_info(&rep.bytes), Some((transform, 4)), "same marker in the output");

            let space = if transform == 0 { ColorSpace::CMYK } else { ColorSpace::YCCK };
            let back = decode_jpeg_as(&rep.bytes, 32 * 24 * 4, space).expect("decodes");
            let expected = box_resample(&samples, w, h, 4, 32, 24);
            let (max, mean) = diff(&back, &expected);
            assert!(mean < 3.0 && max < 32, "transform {transform}: max {max}, mean {mean}");
        }
    }

    #[test]
    fn cmyk_jpeg_without_adobe_marker_is_left_alone() {
        let samples = cmyk_ramp(64, 48);
        let source = strip_app14(&encode_cmyk_jpeg(&samples, 64, 48, 95, 0).expect("encodes"));
        assert!(reencode(&cmyk_plan(64, 48, (32, 24)), &source).is_none());
    }

    fn gray_ramp(width: usize, height: usize) -> Vec<u8> {
        let mut out = Vec::with_capacity(width * height);
        for y in 0..height {
            for x in 0..width {
                out.push(((x * 255 / (width - 1)) as u8).wrapping_add((y % 7) as u8));
            }
        }
        out
    }

    fn gray_plan(width: u32, height: u32, dst: (u32, u32)) -> ImagePlan {
        ImagePlan {
            at: 0,
            length: 0,
            width,
            height,
            source: Source::Gray,
            invert: false,
            dst_width: dst.0,
            dst_height: dst.1,
            codec: Codec::Jpeg,
            output: Output::Jpeg,
            jpeg_quality: 95,
        }
    }

    #[test]
    fn gray_jpeg_is_re_encoded_as_gray() {
        let (w, h) = (64, 48);
        let samples = gray_ramp(w, h);
        let source = encode_jpeg(&samples, w, h, 1, 95).expect("encodes");
        let rep = reencode(&gray_plan(w as u32, h as u32, (32, 24)), &source).expect("re-encodes");
        assert_eq!(rep.filter, Filter::Dct);
        assert_eq!(rep.colour, Some("DeviceGray"));
        let back = decode_jpeg_as(&rep.bytes, 32 * 24, ColorSpace::Luma).expect("decodes");
        let (max, mean) = diff(&back, &box_resample(&samples, w, h, 1, 32, 24));
        assert!(mean < 3.0 && max < 32, "max {max}, mean {mean}");
    }

    #[test]
    fn cmyk_flate_is_downsampled_and_stays_flate() {
        let (w, h) = (64usize, 48usize);
        let samples = cmyk_ramp(w, h);
        let plan = ImagePlan {
            codec: Codec::Flate { row_len: None },
            output: Output::CmykFlate,
            ..cmyk_plan(w as u32, h as u32, (32, 24))
        };
        let rep = reencode(&plan, &deflate_bytes(&samples).unwrap()).expect("re-encodes");
        assert_eq!(rep.filter, Filter::Flate);
        assert_eq!(rep.colour, None);
        assert!(!rep.keep_decode);
        let mut back = Vec::new();
        ZlibDecoder::new(rep.bytes.as_slice()).read_to_end(&mut back).unwrap();
        assert_eq!(back, box_resample(&samples, w, h, 4, 32, 24), "Flate is lossless: exact match");
    }

    fn stream_object(dict: &str, data: &[u8]) -> Vec<u8> {
        let mut out = format!("<<{dict} /Length {}>>\nstream\n", data.len()).into_bytes();
        out.extend_from_slice(data);
        out.extend_from_slice(b"\nendstream");
        out
    }

    /// A one-page PDF drawing `data` (an image XObject with `image_dict`) at 72 points,
    /// so a 200-pixel side is 200 dpi.
    fn pdf_with_image(image_dict: &str, data: &[u8]) -> Vec<u8> {
        let bodies: Vec<Vec<u8>> = vec![
            b"<</Type /Catalog /Pages 2 0 R>>".to_vec(),
            b"<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 300 400] /Resources <</XObject <</Im1 5 0 R>>>>>>".to_vec(),
            b"<</Type /Page /Parent 2 0 R /Contents 4 0 R>>".to_vec(),
            stream_object("", b"q 72 0 0 72 0 0 cm /Im1 Do Q"),
            stream_object(image_dict, data),
        ];
        let mut out = b"%PDF-1.7\n%\xE2\xE3\xCF\xD3\n".to_vec();
        let mut offsets = Vec::new();
        for (index, body) in bodies.iter().enumerate() {
            offsets.push(out.len());
            out.extend_from_slice(format!("{} 0 obj\n", index + 1).as_bytes());
            out.extend_from_slice(body);
            out.extend_from_slice(b"\nendobj\n");
        }
        let xref = out.len();
        out.extend_from_slice(format!("xref\n0 {}\n0000000000 65535 f \n", bodies.len() + 1).as_bytes());
        for offset in offsets {
            out.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
        }
        out.extend_from_slice(
            format!("trailer\n<</Size {} /Root 1 0 R>>\nstartxref\n{xref}\n%%EOF\n", bodies.len() + 1).as_bytes(),
        );
        out
    }

    /// The image objects of a compressed PDF, with their raw stream bytes.
    fn compressed_images(bytes: Vec<u8>) -> Vec<(Dict, Vec<u8>)> {
        let mut file = PdfFile::open(Cursor::new(bytes)).unwrap();
        file.unlock(None).unwrap();
        let mut found = Vec::new();
        for number in 1..=32u32 {
            if !file.has_object(number) {
                continue;
            }
            let loaded = file.load(number).unwrap();
            let (Obj::Dict(dict), Some(at)) = (loaded.value, loaded.stream_at) else { continue };
            if dict.get("Subtype").and_then(Obj::as_name) != Some(&b"Image"[..]) {
                continue;
            }
            let length = dict.get("Length").and_then(Obj::as_f64).unwrap() as u64;
            let raw = file.stream_raw(at, length).unwrap();
            found.push((dict, raw));
        }
        found
    }

    fn dim(dict: &Dict, key: &str) -> usize {
        dict.get(key).and_then(Obj::as_f64).unwrap() as usize
    }

    /// The decoded samples of an image XObject written by the compressor.
    fn decoded(dict: &Dict, raw: &[u8]) -> Vec<u8> {
        let (w, h) = (dim(dict, "Width"), dim(dict, "Height"));
        let channels = match dict.get("ColorSpace").and_then(Obj::as_name) {
            Some(name) if name == &b"DeviceCMYK"[..] => 4,
            Some(name) if name == &b"DeviceGray"[..] => 1,
            _ => 3,
        };
        if dict.get("Filter").and_then(Obj::as_name) == Some(&b"DCTDecode"[..]) {
            let space = match channels {
                1 => ColorSpace::Luma,
                4 => ColorSpace::CMYK,
                _ => ColorSpace::RGB,
            };
            return decode_jpeg_as(raw, w * h * channels, space).expect("decodes");
        }
        let mut out = Vec::new();
        ZlibDecoder::new(raw).read_to_end(&mut out).unwrap();
        out
    }

    struct Quiet;

    impl crate::pdf_tools::PdfToolObserver for Quiet {
        fn on_progress(&mut self, _done: u32, _total: u32) {}

        fn is_cancelled(&self) -> bool {
            false
        }
    }

    #[test]
    fn end_to_end_gray_jpeg_is_re_encoded_in_a_pdf() {
        let samples = gray_ramp(200, 200);
        let jpeg = encode_jpeg(&samples, 200, 200, 1, 95).expect("encodes");
        let pdf = pdf_with_image(
            "/Type /XObject /Subtype /Image /Width 200 /Height 200 /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /DCTDecode",
            &jpeg,
        );
        let mut out = Vec::new();
        let report = crate::pdf_tools_compress::compress_pdf_with_quality(
            std::io::Cursor::new(pdf),
            &mut out,
            &mut Quiet,
            PdfImageQuality::Balanced,
        )
        .unwrap();
        assert_eq!(report.images_recompressed, 1);
        let images = compressed_images(out);
        assert_eq!(images.len(), 1);
        let (dict, raw) = &images[0];
        assert_eq!((dim(dict, "Width"), dim(dict, "Height")), (150, 150));
        assert_eq!(dict.get("Filter").and_then(Obj::as_name), Some(&b"DCTDecode"[..]));
        assert_eq!(dict.get("ColorSpace").and_then(Obj::as_name), Some(&b"DeviceGray"[..]));
        let (max, mean) = diff(&decoded(dict, raw), &box_resample(&samples, 200, 200, 1, 150, 150));
        assert!(mean < 3.0 && max < 32, "max {max}, mean {mean}");
    }

    #[test]
    fn end_to_end_cmyk_flate_is_downsampled_and_stays_cmyk_in_a_pdf() {
        let samples = cmyk_ramp(200, 200);
        let pdf = pdf_with_image(
            "/Type /XObject /Subtype /Image /Width 200 /Height 200 /ColorSpace /DeviceCMYK /BitsPerComponent 8 /Filter /FlateDecode",
            &deflate_bytes(&samples).unwrap(),
        );
        let mut out = Vec::new();
        let report = crate::pdf_tools_compress::compress_pdf_with_quality(
            std::io::Cursor::new(pdf),
            &mut out,
            &mut Quiet,
            PdfImageQuality::Balanced,
        )
        .unwrap();
        assert_eq!(report.images_recompressed, 1);
        let images = compressed_images(out);
        assert_eq!(images.len(), 1);
        let (dict, raw) = &images[0];
        assert_eq!((dim(dict, "Width"), dim(dict, "Height")), (150, 150));
        assert_eq!(dict.get("ColorSpace").and_then(Obj::as_name), Some(&b"DeviceCMYK"[..]));
        assert_eq!(dict.get("Filter").and_then(Obj::as_name), Some(&b"FlateDecode"[..]));
        assert!(dict.get("Decode").is_none());
        assert_eq!(decoded(dict, raw), box_resample(&samples, 200, 200, 4, 150, 150), "exact: Flate is lossless");
    }

    #[test]
    fn cmyk_replacement_keeps_decode_and_colour_space() {
        let rep = Replacement { bytes: vec![], width: 32, height: 24, filter: Filter::Dct, colour: None, keep_decode: true };
        let dict = Dict::of(vec![
            ("ColorSpace", Obj::name("DeviceCMYK")),
            ("Decode", Obj::int(1)),
            ("Filter", Obj::name("DCTDecode")),
        ]);
        let out = replacement_dict(dict, &rep);
        assert_eq!(out.get("ColorSpace"), Some(&Obj::name("DeviceCMYK")));
        assert_eq!(out.get("Decode"), Some(&Obj::int(1)));
        assert_eq!(out.get("Filter"), Some(&Obj::name("DCTDecode")));
    }

    #[test]
    fn replacement_dict_describes_the_new_samples() {
        let rgb = Replacement { bytes: vec![], width: 1000, height: 750, filter: Filter::Dct, colour: Some("DeviceRGB"), keep_decode: false };
        let dict = Dict::of(vec![
            ("Filter", Obj::name("FlateDecode")),
            ("ColorSpace", Obj::name("DeviceCMYK")),
            ("Width", Obj::int(4000)),
            ("Height", Obj::int(3000)),
            ("Decode", Obj::int(1)),
            ("Interpolate", Obj::Bool(true)),
        ]);
        let out = replacement_dict(dict, &rgb);
        assert_eq!(out.get("Filter"), Some(&Obj::name("DCTDecode")));
        assert_eq!(out.get("ColorSpace"), Some(&Obj::name("DeviceRGB")));
        assert_eq!(out.get("Width"), Some(&Obj::int(1000)));
        assert_eq!(out.get("Interpolate"), Some(&Obj::Bool(true)));
        assert!(out.get("Decode").is_none());

        // A palette kept as indices keeps its colour space and uses Flate.
        let indices = Replacement { bytes: vec![], width: 10, height: 10, filter: Filter::Flate, colour: None, keep_decode: false };
        let kept = replacement_dict(Dict::of(vec![("ColorSpace", Obj::name("DeviceGray"))]), &indices);
        assert_eq!(kept.get("Filter"), Some(&Obj::name("FlateDecode")));
        assert_eq!(kept.get("ColorSpace"), Some(&Obj::name("DeviceGray")));
    }
}
