//! UniFFI facade over the document engine. OxiKT generates the Kotlin
//! Multiplatform bindings (package `com.xfiles.engine.ffi`) from this crate.
//!
//! The exported surface is deliberately small and coarse-grained: open a
//! session, pull bounded batches, cancel, close. Exported types mirror
//! `document-core` so the core crate stays free of FFI concerns. Panics inside
//! an exported call are caught by UniFFI and surface in Kotlin as
//! `InternalException`; see docs/architecture/ffi-contract.md.

use std::fs::File;
use std::sync::{Arc, Mutex};

use document_core::{
    Block, CancelToken, ContentBatch, DocumentInfo, DocumentKind, EngineError, ErrorKind, FormatId, Geometry, Limits,
    Session, Shape, Span,
};
use document_parsers::{
    PdfAnnotation, PdfDropped, PdfInkStyle, PdfPagePick, PdfToolInfo, PdfToolObserver, PdfToolOp, PdfToolReport,
    PdfToolSource, append_annotations, inspect_for_tools, inspect_pdf, run_pdf_tool, verify_appended,
    verify_pdf_tool_output,
};
use zeroize::Zeroizing;

mod fily;
mod glance;
mod sheet_edit;
mod slide_edit;
mod imaging;
mod tidy;

uniffi::setup_scaffolding!();

mod pdf_tools_ext;
mod search;
mod pdf_text_layer;
mod archive;

/// Caller-supplied limits; `0` selects the engine default for that field.
#[derive(Debug, Clone, Copy, Default, uniffi::Record)]
pub struct NativeLimits {
    pub max_input_bytes: u64,
    pub max_batch_bytes: u64,
    pub max_total_output_bytes: u64,
}

impl NativeLimits {
    fn resolve(self) -> Limits {
        let defaults = Limits::default();
        let pick = |value: u64, default: u64| if value == 0 { default } else { value };
        Limits {
            max_input_bytes: pick(self.max_input_bytes, defaults.max_input_bytes),
            max_batch_bytes: usize::try_from(pick(self.max_batch_bytes, defaults.max_batch_bytes as u64))
                .unwrap_or(usize::MAX),
            max_total_output_bytes: pick(self.max_total_output_bytes, defaults.max_total_output_bytes),
            // Container limits are not configurable across the FFI boundary.
            ..defaults
        }
    }
}

/// Generated in Kotlin as `ReadException` with one subclass per variant.
#[derive(Debug, uniffi::Error)]
pub enum ReadError {
    Io {
        detail: String,
    },
    UnsupportedFormat {
        detail: String,
    },
    Malformed {
        detail: String,
    },
    LimitExceeded {
        detail: String,
    },
    Cancelled {
        detail: String,
    },
    /// Encrypted, and no password was given.
    PasswordRequired {
        detail: String,
    },
    /// Encrypted, and the password given did not unlock it.
    WrongPassword {
        detail: String,
    },
    /// Encrypted with a scheme the engine does not implement.
    UnsupportedEncryption {
        detail: String,
    },
    Internal {
        detail: String,
    },
}

impl std::fmt::Display for ReadError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let (Self::Io { detail }
        | Self::UnsupportedFormat { detail }
        | Self::Malformed { detail }
        | Self::LimitExceeded { detail }
        | Self::Cancelled { detail }
        | Self::PasswordRequired { detail }
        | Self::WrongPassword { detail }
        | Self::UnsupportedEncryption { detail }
        | Self::Internal { detail }) = self;
        f.write_str(detail)
    }
}

impl std::error::Error for ReadError {}

impl From<EngineError> for ReadError {
    fn from(err: EngineError) -> Self {
        let detail = err.message;
        match err.kind {
            ErrorKind::Io => Self::Io { detail },
            ErrorKind::UnsupportedFormat => Self::UnsupportedFormat { detail },
            ErrorKind::Malformed => Self::Malformed { detail },
            ErrorKind::LimitExceeded => Self::LimitExceeded { detail },
            ErrorKind::Cancelled => Self::Cancelled { detail },
            ErrorKind::PasswordRequired => Self::PasswordRequired { detail },
            ErrorKind::WrongPassword => Self::WrongPassword { detail },
            ErrorKind::UnsupportedEncryption => Self::UnsupportedEncryption { detail },
            ErrorKind::Internal => Self::Internal { detail },
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum NativeFormat {
    PlainText,
    Markdown,
    Csv,
    Json,
    SourceCode,
    Html,
    Pdf,
    Zip,
    Docx,
    Xlsx,
    Pptx,
    Odt,
    Ods,
    Odp,
    Epub,
    Doc,
    Xls,
    Ppt,
    Image,
    OleCompound,
    Unknown,
}

impl From<FormatId> for NativeFormat {
    fn from(format: FormatId) -> Self {
        match format {
            FormatId::PlainText => Self::PlainText,
            FormatId::Markdown => Self::Markdown,
            FormatId::Csv => Self::Csv,
            FormatId::Json => Self::Json,
            FormatId::SourceCode => Self::SourceCode,
            FormatId::Html => Self::Html,
            FormatId::Pdf => Self::Pdf,
            FormatId::Zip => Self::Zip,
            FormatId::Docx => Self::Docx,
            FormatId::Xlsx => Self::Xlsx,
            FormatId::Pptx => Self::Pptx,
            FormatId::Odt => Self::Odt,
            FormatId::Ods => Self::Ods,
            FormatId::Odp => Self::Odp,
            FormatId::Epub => Self::Epub,
            FormatId::Doc => Self::Doc,
            FormatId::Xls => Self::Xls,
            FormatId::Ppt => Self::Ppt,
            FormatId::Image => Self::Image,
            FormatId::OleCompound => Self::OleCompound,
            FormatId::Unknown => Self::Unknown,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum NativeDocumentKind {
    Text,
    Spreadsheet,
    Presentation,
    Pdf,
    Archive,
    Image,
}

impl From<DocumentKind> for NativeDocumentKind {
    fn from(kind: DocumentKind) -> Self {
        match kind {
            DocumentKind::Text => Self::Text,
            DocumentKind::Spreadsheet => Self::Spreadsheet,
            DocumentKind::Presentation => Self::Presentation,
            DocumentKind::Pdf => Self::Pdf,
            DocumentKind::Archive => Self::Archive,
            DocumentKind::Image => Self::Image,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NativeDocumentInfo {
    pub format: NativeFormat,
    pub kind: NativeDocumentKind,
    pub byte_size: u64,
    pub encoding: Option<String>,
}

impl From<&DocumentInfo> for NativeDocumentInfo {
    fn from(info: &DocumentInfo) -> Self {
        Self {
            format: info.format.into(),
            kind: info.kind.into(),
            byte_size: info.byte_size,
            encoding: info.encoding.map(str::to_owned),
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum NativeBlock {
    Heading {
        level: u8,
        text: String,
        spans: Vec<NativeSpan>,
    },
    Paragraph {
        text: String,
        spans: Vec<NativeSpan>,
    },
    ListItem {
        depth: u8,
        marker: Option<String>,
        text: String,
        spans: Vec<NativeSpan>,
    },
    Quote {
        text: String,
        spans: Vec<NativeSpan>,
    },
    Code {
        language: Option<String>,
        first_line: Option<u32>,
        text: String,
    },
    Divider,
    /// `styles` index the `CellStyle`s sent before; 0 is plain. `cell_spans` are per cell, as in the core model.
    TableRow {
        row: Option<u32>,
        header: bool,
        cells: Vec<String>,
        styles: Vec<u16>,
        cell_spans: Vec<Vec<NativeSpan>>,
    },
    CellStyle {
        index: u16,
        style: NativeCellStyle,
    },
    /// Rows from 1, columns from 0, inclusive.
    Merge {
        first_row: u32,
        first_column: u32,
        last_row: u32,
        last_column: u32,
    },
    /// Styled runs inside one spreadsheet cell; row from 1, column from 0.
    CellRuns {
        row: u32,
        column: u32,
        spans: Vec<NativeSpan>,
    },
    /// How many slides the document has, sent before them.
    Count {
        total: u32,
    },
    Section {
        title: String,
    },
    Entry {
        path: String,
        size: u64,
        is_dir: bool,
    },
    /// A picture in the reading flow. `path` names it for `resource`; `None`
    /// means only the label can show. Sizes are hints in points.
    Image {
        path: Option<String>,
        alt: Option<String>,
        width: Option<f32>,
        height: Option<f32>,
    },
    /// The current slide's canvas in points.
    Slide {
        width: f32,
        height: f32,
        background: Option<u32>,
        background_image: Option<String>,
        transition: Option<String>,
    },
    Shape {
        shape: NativeShape,
    },
}

/// How a spreadsheet cell looks; colours are ARGB.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct NativeCellStyle {
    pub flags: u8,
    pub color: Option<u32>,
    pub fill: Option<u32>,
    pub size: Option<f32>,
    /// 0 as the value suggests, 1 left, 2 centre, 3 right.
    pub align: u8,
    pub wrap: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum NativeGeometry {
    Rect,
    RoundRect,
    Ellipse,
    Line,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NativeShapeText {
    pub text: String,
    pub spans: Vec<NativeSpan>,
    pub size: f32,
    pub color: Option<u32>,
    pub bold: bool,
    pub italic: bool,
    pub align: u8,
    pub bullet: Option<String>,
    pub level: u8,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct NativeBuild {
    pub step: u16,
    pub effect: u16,
}

/// A positioned slide element; geometry in points, colours ARGB.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NativeShape {
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
    pub rotation: f32,
    pub geometry: NativeGeometry,
    pub fill: Option<u32>,
    pub line: Option<u32>,
    pub line_width: f32,
    pub image: Option<String>,
    /// Embedded audio or video this shape plays; `image` is its poster.
    pub media: Option<String>,
    pub paragraphs: Vec<NativeShapeText>,
    pub anchor: u8,
    pub insets: Vec<f32>,
    pub table: Vec<Vec<String>>,
    pub table_header: bool,
    pub build: Option<NativeBuild>,
}

impl From<Shape> for NativeShape {
    fn from(s: Shape) -> Self {
        NativeShape {
            x: s.x,
            y: s.y,
            width: s.width,
            height: s.height,
            rotation: s.rotation,
            geometry: match s.geometry {
                Geometry::Rect => NativeGeometry::Rect,
                Geometry::RoundRect => NativeGeometry::RoundRect,
                Geometry::Ellipse => NativeGeometry::Ellipse,
                Geometry::Line => NativeGeometry::Line,
            },
            fill: s.fill,
            line: s.line,
            line_width: s.line_width,
            image: s.image,
            media: s.media,
            paragraphs: s
                .paragraphs
                .into_iter()
                .map(|p| NativeShapeText {
                    spans: spans(&p.text.spans),
                    text: p.text.text,
                    size: p.size,
                    color: p.color,
                    bold: p.bold,
                    italic: p.italic,
                    align: p.align,
                    bullet: p.bullet,
                    level: p.level,
                })
                .collect(),
            anchor: s.anchor,
            insets: s.insets.to_vec(),
            table: s.table,
            table_header: s.table_header,
            build: s.build.map(|b| NativeBuild { step: b.step, effect: b.effect }),
        }
    }
}

/// Inline styling over `start..end` (UTF-16 code units) of a block's text.
/// `style` is a bit set: 1 bold, 2 italic, 4 underline, 8 strikethrough, 16 code.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NativeSpan {
    pub start: u32,
    pub end: u32,
    pub style: u8,
    pub link: Option<String>,
    /// ARGB text colour, where it differs from the block's own.
    pub color: Option<u32>,
}

fn spans(rich: &[Span]) -> Vec<NativeSpan> {
    rich.iter()
        .map(|s| NativeSpan { start: s.start, end: s.end, style: s.style, link: s.link.clone(), color: s.color })
        .collect()
}

impl From<Block> for NativeBlock {
    fn from(block: Block) -> Self {
        match block {
            Block::Heading { level, text } => Self::Heading { level, spans: spans(&text.spans), text: text.text },
            Block::Paragraph { text } => Self::Paragraph { spans: spans(&text.spans), text: text.text },
            Block::ListItem { depth, marker, text } => {
                Self::ListItem { depth, marker, spans: spans(&text.spans), text: text.text }
            }
            Block::Quote { text } => Self::Quote { spans: spans(&text.spans), text: text.text },
            Block::Code { language, first_line, text } => Self::Code { language, first_line, text },
            Block::Divider => Self::Divider,
            Block::TableRow { row, header, cells, styles, cell_spans } => Self::TableRow {
                row,
                header,
                cells,
                styles,
                cell_spans: cell_spans.iter().map(|spans_of_cell| spans(spans_of_cell)).collect(),
            },
            Block::CellStyle { index, style } => Self::CellStyle {
                index,
                style: NativeCellStyle {
                    flags: style.flags,
                    color: style.color,
                    fill: style.fill,
                    size: style.size,
                    align: style.align,
                    wrap: style.wrap,
                },
            },
            Block::Merge { first_row, first_column, last_row, last_column } => {
                Self::Merge { first_row, first_column, last_row, last_column }
            }
            Block::CellRuns { row, column, spans: runs } => Self::CellRuns { row, column, spans: spans(&runs) },
            Block::Count { total } => Self::Count { total },
            Block::Section { title } => Self::Section { title },
            Block::Entry { path, size, is_dir } => Self::Entry { path, size, is_dir },
            Block::Image { path, alt, width, height } => Self::Image { path, alt, width, height },
            Block::Slide { width, height, background, background_image, transition } => {
                Self::Slide { width, height, background, background_image, transition }
            }
            Block::Shape(shape) => Self::Shape { shape: (*shape).into() },
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NativeBatch {
    pub sequence: u32,
    pub blocks: Vec<NativeBlock>,
    pub is_last: bool,
    pub truncated: bool,
}

impl From<ContentBatch> for NativeBatch {
    fn from(batch: ContentBatch) -> Self {
        Self {
            sequence: batch.sequence,
            blocks: batch.blocks.into_iter().map(NativeBlock::from).collect(),
            is_last: batch.is_last,
            truncated: batch.truncated,
        }
    }
}

/// One open document. In Kotlin this is an `AutoCloseable` object: `close()`
/// releases the session and its file. UniFFI defers the release until calls
/// already running on other threads have returned, and calls after `close()`
/// throw `IllegalStateException` instead of touching freed memory.
#[derive(uniffi::Object)]
pub struct EngineSession {
    info: NativeDocumentInfo,
    cancel: CancelToken,
    // Calls on one session are serialized; separate sessions run in parallel.
    session: Mutex<Session>,
}

#[uniffi::export]
impl EngineSession {
    pub fn info(&self) -> NativeDocumentInfo {
        self.info.clone()
    }

    /// Returns `None` once the final batch has been delivered.
    pub fn next_batch(&self) -> Result<Option<NativeBatch>, ReadError> {
        let mut session = self.session.lock().map_err(|_| ReadError::Internal {
            detail: "this document hit an internal error earlier and cannot be read further".into(),
        })?;
        Ok(session.next_batch()?.map(NativeBatch::from))
    }

    /// Bytes of an embedded resource named by a block, such as a slide
    /// picture (`Shape.image`), slide media (`Shape.media`) or a document
    /// picture (`Image.path`). Only pictures, audio and video inside the
    /// package are served, within size limits. Safe to
    /// call between batches: reading continues where it was.
    pub fn resource(&self, name: String) -> Result<Vec<u8>, ReadError> {
        let mut session = self.session.lock().map_err(|_| ReadError::Internal {
            detail: "this document hit an internal error earlier and cannot be read further".into(),
        })?;
        Ok(session.resource(&name)?)
    }

    /// Requests cancellation without waiting for a running batch, which stops
    /// at its next check point and fails with `Cancelled`.
    pub fn cancel(&self) {
        self.cancel.cancel();
    }
}

/// Opens a session over a file descriptor and takes ownership of it in every
/// case: it is closed on failure, or when the session is released.
///
/// File descriptors exist only on Unix (Android, iOS). The function is still
/// exported elsewhere so every target shares one binding surface; there it
/// fails with `Io`.
///
/// `password` unlocks an encrypted document and is ignored by any other; it is
/// wiped here and never stored. The descriptor is consumed even when the
/// password is missing or wrong, so a retry opens the file again.
#[uniffi::export(default(password = None))]
pub fn open_fd(
    fd: i32,
    name_hint: Option<String>,
    limits: NativeLimits,
    password: Option<String>,
) -> Result<Arc<EngineSession>, ReadError> {
    let password = password.map(Zeroizing::new);
    #[cfg(unix)]
    {
        use std::os::fd::FromRawFd;
        if fd < 0 {
            return Err(ReadError::Io { detail: "invalid file descriptor".into() });
        }
        // SAFETY: the caller transfers sole ownership of an open descriptor.
        open_file(unsafe { File::from_raw_fd(fd) }, name_hint, limits, password.as_ref().map(|p| p.as_str()))
    }
    #[cfg(not(unix))]
    {
        let _ = (fd, name_hint, limits, password);
        Err(ReadError::Io { detail: "file descriptors are not supported on this platform".into() })
    }
}

#[cfg(unix)]
fn duplicate_document_fd(fd: i32) -> Result<File, ReadError> {
    use std::os::fd::FromRawFd;

    // fcntl validates the raw descriptor before we take ownership of its duplicate.
    let copy = unsafe { libc::fcntl(fd, libc::F_DUPFD_CLOEXEC, 0) };
    if copy < 0 {
        return Err(ReadError::Io { detail: std::io::Error::last_os_error().to_string() });
    }
    Ok(unsafe { File::from_raw_fd(copy) })
}

#[cfg(not(unix))]
fn duplicate_document_fd(_fd: i32) -> Result<File, ReadError> {
    Err(ReadError::Io { detail: "borrowed descriptors require a Unix platform".into() })
}

/// Opens over a duplicate of a descriptor that remains owned by the caller.
#[uniffi::export(default(password = None))]
pub fn open_borrowed_fd(
    fd: i32,
    name_hint: Option<String>,
    limits: NativeLimits,
    password: Option<String>,
) -> Result<Arc<EngineSession>, ReadError> {
    let file = duplicate_document_fd(fd)?;
    let password = password.map(Zeroizing::new);
    open_file(file, name_hint, limits, password.as_ref().map(|p| p.as_str()))
}

/// Opens a session over the file at `path`. Used by host tests and for
/// app-private copies; platform adapters normally hand over a descriptor.
#[uniffi::export(default(password = None))]
pub fn open_path(
    path: String,
    name_hint: Option<String>,
    limits: NativeLimits,
    password: Option<String>,
) -> Result<Arc<EngineSession>, ReadError> {
    let password = password.map(Zeroizing::new);
    let file = File::open(&path).map_err(|e| ReadError::Io { detail: format!("cannot open file: {e}") })?;
    open_file(file, name_hint, limits, password.as_ref().map(|p| p.as_str()))
}

fn open_file(
    file: File,
    name_hint: Option<String>,
    limits: NativeLimits,
    password: Option<&str>,
) -> Result<Arc<EngineSession>, ReadError> {
    let session = document_parsers::engine(limits.resolve()).open_protected(file, name_hint, password)?;
    Ok(Arc::new(EngineSession {
        info: session.info().into(),
        cancel: session.cancel_token(),
        session: Mutex::new(session),
    }))
}

/// How an ink annotation should look and be labelled.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum NativePdfInkStyle {
    Pen,
    Marker,
    Signature,
}

/// One rectangle as fractions of the displayed page, origin top-left.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct NativePdfBox {
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
}

/// A stroke as `x, y, x, y, ...` fractions of the displayed page.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NativePdfStroke {
    pub points: Vec<f32>,
}

/// Colours are ARGB; alpha is the annotation's opacity.
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum NativePdfAnnotation {
    Highlight { page: u32, color: u32, boxes: Vec<NativePdfBox> },
    Ink { page: u32, color: u32, width: f32, strokes: Vec<NativePdfStroke>, style: NativePdfInkStyle },
    Note { page: u32, color: u32, x: f32, y: f32, text: String },
}

impl From<NativePdfAnnotation> for PdfAnnotation {
    fn from(annotation: NativePdfAnnotation) -> Self {
        match annotation {
            NativePdfAnnotation::Highlight { page, color, boxes } => Self::Highlight {
                page,
                color,
                boxes: boxes.into_iter().map(|b| [b.left, b.top, b.right, b.bottom]).collect(),
            },
            NativePdfAnnotation::Ink { page, color, width, strokes, style } => Self::Ink {
                page,
                color,
                width,
                strokes: strokes.iter().map(|s| s.points.chunks_exact(2).map(|p| (p[0], p[1])).collect()).collect(),
                style: match style {
                    NativePdfInkStyle::Pen => PdfInkStyle::Pen,
                    NativePdfInkStyle::Marker => PdfInkStyle::Marker,
                    NativePdfInkStyle::Signature => PdfInkStyle::Signature,
                },
            },
            NativePdfAnnotation::Note { page, color, x, y, text } => Self::Note { page, color, x, y, text },
        }
    }
}

impl From<PdfAnnotation> for NativePdfAnnotation {
    fn from(annotation: PdfAnnotation) -> Self {
        match annotation {
            PdfAnnotation::Highlight { page, color, boxes } => Self::Highlight {
                page,
                color,
                boxes: boxes
                    .into_iter()
                    .map(|[left, top, right, bottom]| NativePdfBox { left, top, right, bottom })
                    .collect(),
            },
            PdfAnnotation::Ink { page, color, width, strokes, style } => Self::Ink {
                page,
                color,
                width,
                strokes: strokes
                    .into_iter()
                    .map(|s| NativePdfStroke { points: s.into_iter().flat_map(|(x, y)| [x, y]).collect() })
                    .collect(),
                style: match style {
                    PdfInkStyle::Pen => NativePdfInkStyle::Pen,
                    PdfInkStyle::Marker => NativePdfInkStyle::Marker,
                    PdfInkStyle::Signature => NativePdfInkStyle::Signature,
                },
            },
            PdfAnnotation::Note { page, color, x, y, text } => Self::Note { page, color, x, y, text },
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NativePdfEditInfo {
    pub page_count: u32,
    pub encrypted: bool,
    pub is_signed: bool,
    pub archival: bool,
    pub annotations: Vec<NativePdfAnnotation>,
    pub annotations_truncated: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct NativePdfAppendReport {
    pub original_len: u64,
    pub total_len: u64,
}

#[cfg(unix)]
fn file_from_fd(fd: i32) -> Result<File, ReadError> {
    use std::os::fd::FromRawFd;
    if fd < 0 {
        return Err(ReadError::Io { detail: "invalid file descriptor".into() });
    }
    // SAFETY: the caller transfers sole ownership of an open descriptor.
    Ok(unsafe { File::from_raw_fd(fd) })
}

#[cfg(not(unix))]
fn file_from_fd(_fd: i32) -> Result<File, ReadError> {
    Err(ReadError::Io { detail: "file descriptors are not supported on this platform".into() })
}

fn open_for_read(path: &str) -> Result<File, ReadError> {
    File::open(path).map_err(|e| ReadError::Io { detail: format!("cannot open file: {e}") })
}

fn inspect_file(file: File) -> Result<NativePdfEditInfo, ReadError> {
    let info = inspect_pdf(file)?;
    Ok(NativePdfEditInfo {
        page_count: info.page_count,
        encrypted: info.encrypted,
        is_signed: info.signed,
        archival: info.archival,
        annotations: info.annotations.into_iter().map(Into::into).collect(),
        annotations_truncated: info.annotations_truncated,
    })
}

/// Writes the update to `target_path`, re-reads it to check it, and removes it
/// on any failure. The source is only read.
fn append_file(
    source: File,
    target_path: &str,
    annotations: Vec<NativePdfAnnotation>,
) -> Result<NativePdfAppendReport, ReadError> {
    let annotations: Vec<PdfAnnotation> = annotations.into_iter().map(Into::into).collect();
    let result = (|| -> Result<NativePdfAppendReport, ReadError> {
        let page_count = inspect_pdf(source.try_clone().map_err(EngineError::from)?)?.page_count;
        let mut target = std::io::BufWriter::new(File::create(target_path).map_err(EngineError::from)?);
        let report = append_annotations(source, &mut target, &annotations)?;
        let target = target.into_inner().map_err(|e| EngineError::from(e.into_error()))?;
        target.sync_all().map_err(EngineError::from)?;
        drop(target);
        verify_appended(open_for_read(target_path)?, page_count, &annotations)?;
        Ok(NativePdfAppendReport { original_len: report.original_len, total_len: report.total_len })
    })();
    if result.is_err() {
        std::fs::remove_file(target_path).ok();
    }
    result
}

/// What a PDF allows and holds, for a reader about to annotate it. Takes
/// ownership of `fd`. A typed error means its structure can't be trusted for
/// a save.
#[uniffi::export]
pub fn pdf_inspect_fd(fd: i32) -> Result<NativePdfEditInfo, ReadError> {
    inspect_file(file_from_fd(fd)?)
}

/// As [`pdf_inspect_fd`], by path. Used by host tests.
#[uniffi::export]
pub fn pdf_inspect_path(path: String) -> Result<NativePdfEditInfo, ReadError> {
    inspect_file(open_for_read(&path)?)
}

/// Writes the PDF at `fd` plus an incremental update with `annotations` to
/// `target_path`, a private file. Takes ownership of `fd`.
#[uniffi::export]
pub fn pdf_append_fd(
    fd: i32,
    target_path: String,
    annotations: Vec<NativePdfAnnotation>,
) -> Result<NativePdfAppendReport, ReadError> {
    append_file(file_from_fd(fd)?, &target_path, annotations)
}

/// As [`pdf_append_fd`], by path. Used by host tests.
#[uniffi::export]
pub fn pdf_append_path(
    path: String,
    target_path: String,
    annotations: Vec<NativePdfAnnotation>,
) -> Result<NativePdfAppendReport, ReadError> {
    append_file(open_for_read(&path)?, &target_path, annotations)
}

/// A PDF for a tool: a descriptor (ownership passes to the engine in every
/// case) or, with `fd` negative, a path. The password is wiped here after use.
/// Deliberately not `Debug`: this must never reach a log.
#[derive(uniffi::Record)]
pub struct NativePdfSource {
    pub fd: i32,
    pub path: Option<String>,
    pub password: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct NativePdfPageEdit {
    /// Zero-based, in the source.
    pub page: u32,
    /// Extra clockwise rotation, in multiples of 90.
    pub rotation: i32,
}

/// The rewrite to do. `InsertPages` takes two sources, the document then the
/// new pages; `Merge` takes one or more; the others take exactly one.
#[derive(uniffi::Enum)]
pub enum NativePdfToolOp {
    Merge,
    Rebuild { pages: Vec<NativePdfPageEdit> },
    /// Every page exactly once, in this order. Rejected unless it's a permutation.
    Reorder { pages: Vec<NativePdfPageEdit> },
    InsertPages { at_page: u32 },
    Resize { width: f32, height: f32 },
    AddPassword { password: String },
    RemovePassword,
    PrintCopy,
}

/// Called on the thread running the tool. Must not throw.
#[uniffi::export(callback_interface)]
pub trait NativePdfToolListener: Send + Sync {
    fn on_progress(&self, done: u32, total: u32);
    fn is_cancelled(&self) -> bool;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct NativePdfToolInfo {
    pub page_count: u32,
    pub encrypted: bool,
    pub is_signed: bool,
    pub archival: bool,
    pub printable: bool,
}

/// What the source had that the output couldn't carry.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct NativePdfDropped {
    pub outlines: bool,
    pub forms: bool,
    pub named_destinations: bool,
    pub structure_tags: bool,
    pub page_labels: bool,
    pub attachments: bool,
    pub links: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct NativePdfToolReport {
    pub page_count: u32,
    pub total_len: u64,
    pub dropped: NativePdfDropped,
}

impl From<PdfToolInfo> for NativePdfToolInfo {
    fn from(info: PdfToolInfo) -> Self {
        Self {
            page_count: info.page_count,
            encrypted: info.encrypted,
            is_signed: info.signed,
            archival: info.archival,
            printable: info.printable,
        }
    }
}

impl From<PdfDropped> for NativePdfDropped {
    fn from(dropped: PdfDropped) -> Self {
        Self {
            outlines: dropped.outlines,
            forms: dropped.forms,
            named_destinations: dropped.named_destinations,
            structure_tags: dropped.structure_tags,
            page_labels: dropped.page_labels,
            attachments: dropped.attachments,
            links: dropped.links,
        }
    }
}

impl From<PdfToolReport> for NativePdfToolReport {
    fn from(report: PdfToolReport) -> Self {
        Self { page_count: report.page_count, total_len: report.bytes, dropped: report.dropped.into() }
    }
}

struct ListenerObserver(Box<dyn NativePdfToolListener>);

impl PdfToolObserver for ListenerObserver {
    fn on_progress(&mut self, done: u32, total: u32) {
        self.0.on_progress(done, total);
    }

    fn is_cancelled(&self) -> bool {
        self.0.is_cancelled()
    }
}

fn open_source(source: &NativePdfSource) -> Result<File, ReadError> {
    match (&source.path, source.fd) {
        (Some(path), _) => open_for_read(path),
        (None, fd) => file_from_fd(fd),
    }
}

/// What a tool needs to know about a PDF. Takes ownership of the descriptor.
/// A file open to anyone but with owner limits answers `encrypted` with its
/// real page count; one that needs a password is `PasswordRequired`, a wrong
/// one `WrongPassword`.
#[uniffi::export]
pub fn pdf_tool_inspect(source: NativePdfSource) -> Result<NativePdfToolInfo, ReadError> {
    let file = open_source(&source)?;
    let password = source.password.map(Zeroizing::new);
    Ok(inspect_for_tools(file, password.as_ref().map(|p| p.as_str()))?.into())
}

/// Rewrites PDFs into `target_path`, a private file, re-reads the result to
/// check it, and removes it on any failure. The sources are only read, and
/// every descriptor is owned by the engine from here, even on failure.
#[uniffi::export]
pub fn pdf_tool_run(
    sources: Vec<NativePdfSource>,
    op: NativePdfToolOp,
    target_path: String,
    listener: Box<dyn NativePdfToolListener>,
) -> Result<NativePdfToolReport, ReadError> {
    // Everything opens first, so a bad source can't leave later descriptors unowned.
    let opened: Vec<_> = sources
        .into_iter()
        .map(|source| {
            let file = open_source(&source);
            (file, source.password.map(Zeroizing::new))
        })
        .collect();
    let mut inputs = Vec::with_capacity(opened.len());
    for (file, password) in opened {
        inputs.push(PdfToolSource { reader: file?, password });
    }
    let op = match op {
        NativePdfToolOp::Merge => PdfToolOp::Merge,
        NativePdfToolOp::Reorder { pages } => PdfToolOp::Reorder {
            pages: pages.iter().map(|edit| PdfPagePick { page: edit.page, rotation: edit.rotation }).collect(),
        },
        NativePdfToolOp::Rebuild { pages } => PdfToolOp::Rebuild {
            pages: pages.iter().map(|edit| PdfPagePick { page: edit.page, rotation: edit.rotation }).collect(),
        },
        NativePdfToolOp::InsertPages { at_page } => PdfToolOp::InsertPages { at_page },
        NativePdfToolOp::Resize { width, height } => {
            PdfToolOp::Resize { width: f64::from(width), height: f64::from(height) }
        }
        NativePdfToolOp::AddPassword { password } => PdfToolOp::AddPassword { password: Zeroizing::new(password) },
        NativePdfToolOp::RemovePassword => PdfToolOp::RemovePassword,
        NativePdfToolOp::PrintCopy => PdfToolOp::PrintCopy,
    };
    let mut observer = ListenerObserver(listener);
    let result = (|| -> Result<PdfToolReport, ReadError> {
        let mut target = std::io::BufWriter::new(File::create(&target_path).map_err(EngineError::from)?);
        let report = run_pdf_tool(inputs, &op, &mut target, &mut observer)?;
        let target = target.into_inner().map_err(|e| EngineError::from(e.into_error()))?;
        target.sync_all().map_err(EngineError::from)?;
        drop(target);
        let password = match &op {
            PdfToolOp::AddPassword { password } => Some(password.as_str()),
            _ => None,
        };
        verify_pdf_tool_output(open_for_read(&target_path)?, password, report.page_count)?;
        Ok(report)
    })();
    if result.is_err() {
        std::fs::remove_file(&target_path).ok();
    }
    Ok(result?.into())
}

/// Replaces every occurrence of `find_text` with `replace_text` in a DOCX or
/// PPTX file, writing the result to `target_path` (a private temporary file).
/// Returns the number of replacements made. The caller picks the save-as
/// target; the original file is never modified.
#[uniffi::export]
pub fn replace_in_document(
    path: String,
    target_path: String,
    find_text: String,
    replace_text: String,
    case_sensitive: bool,
) -> Result<u32, ReadError> {
    let data = std::fs::read(&path).map_err(|e| ReadError::Io { detail: format!("cannot read file: {e}") })?;
    let lower = path.to_lowercase();
    let report = if lower.ends_with(".docx") {
        document_parsers::text_replace::replace_in_docx(&data, &find_text, &replace_text, case_sensitive)?
    } else if lower.ends_with(".pptx") {
        document_parsers::text_replace::replace_in_pptx(&data, &find_text, &replace_text, case_sensitive)?
    } else if lower.ends_with(".odt") {
        document_parsers::text_replace::replace_in_odt(&data, &find_text, &replace_text, case_sensitive)?
    } else if lower.ends_with(".odp") {
        document_parsers::text_replace::replace_in_odp(&data, &find_text, &replace_text, case_sensitive)?
    } else {
        return Err(ReadError::UnsupportedFormat { detail: "only DOCX, PPTX, ODT and ODP are supported for text replacement".into() });
    };
    let result = (|| -> Result<(), ReadError> {
        std::fs::write(&target_path, &report.output)
            .map_err(|e| ReadError::Io { detail: format!("cannot write file: {e}") })
    })();
    if result.is_err() {
        std::fs::remove_file(&target_path).ok();
    }
    result?;
    Ok(report.matches)
}

/// Returns the number of slides in a PPTX file.
#[uniffi::export]
pub fn pptx_slide_count(path: String) -> Result<u32, ReadError> {
    let data = std::fs::read(&path).map_err(|e| ReadError::Io { detail: format!("cannot read file: {e}") })?;
    let info = document_parsers::slide_edit::pptx_slide_count(&data)?;
    Ok(info.slide_count)
}

/// Reorders and/or removes slides in a PPTX file. `keep_indices` is a list of
/// 0-based slide indices in the desired order; slides not listed are dropped.
/// Writes the result to `target_path`. Returns the new slide count.
#[uniffi::export]
pub fn pptx_reorder_slides(
    path: String,
    target_path: String,
    keep_indices: Vec<u32>,
) -> Result<u32, ReadError> {
    let data = std::fs::read(&path).map_err(|e| ReadError::Io { detail: format!("cannot read file: {e}") })?;
    let output = document_parsers::slide_edit::reorder_pptx_slides(&data, &keep_indices)?;
    let count = keep_indices.len() as u32;
    let result = (|| -> Result<(), ReadError> {
        std::fs::write(&target_path, &output)
            .map_err(|e| ReadError::Io { detail: format!("cannot write file: {e}") })
    })();
    if result.is_err() {
        std::fs::remove_file(&target_path).ok();
    }
    result?;
    Ok(count)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zero_limits_select_defaults() {
        assert_eq!(NativeLimits::default().resolve(), Limits::default());
    }

    #[test]
    fn password_errors_keep_their_type() {
        let error = |kind| ReadError::from(EngineError::new(kind, "x"));
        assert!(matches!(error(ErrorKind::PasswordRequired), ReadError::PasswordRequired { .. }));
        assert!(matches!(error(ErrorKind::WrongPassword), ReadError::WrongPassword { .. }));
        assert!(matches!(error(ErrorKind::UnsupportedEncryption), ReadError::UnsupportedEncryption { .. }));
    }
}
