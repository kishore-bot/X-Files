//! UniFFI exports for the PDF tools that write several files or change page
//! content: Split, Compress, and the Watermark and Sign overlays. Reorder is a
//! `NativePdfToolOp` variant, run by `pdf_tool_run`.
//!
//! Each export follows `pdf_tool_run`. The sources are only read, every output
//! goes to a private target path, each output is re-read before it counts as
//! done, and anything that failed is removed. Progress is reported on the thread
//! running the tool, and cancellation is polled through the listener.

use std::fs::File;
use std::io::BufWriter;

use document_core::{EngineError, ErrorKind};
use document_parsers::{
    PdfCompressReport, PdfImageQuality, PdfOverlay, PdfOverlayReport, PdfPagePick, PdfPageRange, PdfProtectionChoice,
    PdfProtectionOutcome, PdfSignature, PdfToolObserver, PdfToolOp, PdfToolSource, PdfWatermark, PdfWatermarkPosition,
    ProtectionPlan, apply_overlay, compress_pdf_with_quality, inspect_for_tools, inspect_protection, plan_protection,
    plan_split, run_pdf_tool, verify_overlay, verify_pdf_tool_output,
};
use zeroize::Zeroizing;

use crate::{ListenerObserver, NativePdfSource, NativePdfStroke, NativePdfToolListener, NativePdfToolReport, ReadError};
use crate::{open_for_read, open_source};

/// One part of a split: zero-based and inclusive, like the engine's own pages.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct NativePdfPageRange {
    pub start: u32,
    pub end: u32,
}

/// How a protected source's protection carries into the result.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, uniffi::Enum)]
pub enum NativeProtectionChoice {
    /// The file's own algorithm, limits and passwords.
    #[default]
    SameAsOriginal,
    /// AES-256 with the file's limits.
    Aes256,
}

/// What happened to the result's protection. The UI names it in words.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum NativeProtectionOutcome {
    /// The source had no encryption, so the result has none.
    NotProtected,
    /// Protected again with the file's own algorithm and passwords.
    Kept,
    /// Protected with AES-256, as chosen.
    Upgraded,
    /// The limits came back under a new random owner password, which nobody is given.
    KeptNewOwner,
    /// Opened with the owner password, so the result opens with it too.
    OwnerPasswordOpens,
    /// Nothing to keep: no user password and no limits.
    SavedWithoutProtection,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct NativePdfCompressReport {
    pub page_count: u32,
    pub before_len: u64,
    pub after_len: u64,
    pub objects_before: u32,
    pub objects_after: u32,
    pub duplicates_merged: u32,
    pub streams_recompressed: u32,
    pub images_recompressed: u32,
    pub protection: NativeProtectionOutcome,
    /// The source's limits (printing, changing, ...) are written into the result.
    pub restrictions_kept: bool,
}

/// How hard Compress works on images. `Lossless` only restructures the file.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, uniffi::Enum)]
pub enum NativeImageQuality {
    #[default]
    Lossless,
    Balanced,
    Strong,
}

impl From<NativeImageQuality> for PdfImageQuality {
    fn from(quality: NativeImageQuality) -> Self {
        match quality {
            NativeImageQuality::Lossless => Self::Lossless,
            NativeImageQuality::Balanced => Self::Balanced,
            NativeImageQuality::Strong => Self::Strong,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum NativeWatermarkPosition {
    Center,
    TopLeft,
    TopCenter,
    TopRight,
    BottomLeft,
    BottomCenter,
    BottomRight,
    Tiled,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NativeWatermark {
    pub text: String,
    pub font_size: f32,
    pub opacity: f32,
    pub angle_degrees: f32,
    /// 0xRRGGBB.
    pub color: u32,
    pub position: NativeWatermarkPosition,
    /// Zero-based pages; `None` is every page.
    pub pages: Option<Vec<u32>>,
}

/// A drawn signature. The box is a fraction of the displayed page, origin
/// top-left. Stroke points are fractions of that box, as flat x, y pairs.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NativeSignature {
    pub page: u32,
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
    pub strokes: Vec<NativePdfStroke>,
    pub color: u32,
    pub line_width: f32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum NativePdfOverlay {
    Watermark { watermark: NativeWatermark },
    Sign { signature: NativeSignature },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct NativePdfOverlayReport {
    pub page_count: u32,
    pub pages_changed: u32,
    pub total_len: u64,
    pub protection: NativeProtectionOutcome,
    /// The source's limits are written into the result.
    pub restrictions_kept: bool,
}

impl From<NativeProtectionChoice> for PdfProtectionChoice {
    fn from(choice: NativeProtectionChoice) -> Self {
        match choice {
            NativeProtectionChoice::SameAsOriginal => Self::SameAsOriginal,
            NativeProtectionChoice::Aes256 => Self::Aes256,
        }
    }
}

impl From<PdfProtectionOutcome> for NativeProtectionOutcome {
    fn from(outcome: PdfProtectionOutcome) -> Self {
        match outcome {
            PdfProtectionOutcome::NotProtected => Self::NotProtected,
            PdfProtectionOutcome::Kept => Self::Kept,
            PdfProtectionOutcome::Upgraded => Self::Upgraded,
            PdfProtectionOutcome::KeptNewOwner => Self::KeptNewOwner,
            PdfProtectionOutcome::OwnerPasswordOpens => Self::OwnerPasswordOpens,
            PdfProtectionOutcome::SavedWithoutProtection => Self::SavedWithoutProtection,
        }
    }
}

impl From<NativeWatermarkPosition> for PdfWatermarkPosition {
    fn from(position: NativeWatermarkPosition) -> Self {
        match position {
            NativeWatermarkPosition::Center => Self::Center,
            NativeWatermarkPosition::TopLeft => Self::TopLeft,
            NativeWatermarkPosition::TopCenter => Self::TopCenter,
            NativeWatermarkPosition::TopRight => Self::TopRight,
            NativeWatermarkPosition::BottomLeft => Self::BottomLeft,
            NativeWatermarkPosition::BottomCenter => Self::BottomCenter,
            NativeWatermarkPosition::BottomRight => Self::BottomRight,
            NativeWatermarkPosition::Tiled => Self::Tiled,
        }
    }
}

impl NativePdfCompressReport {
    fn from_engine(report: PdfCompressReport, protection: PdfProtectionOutcome, restrictions_kept: bool) -> Self {
        Self {
            page_count: report.page_count,
            before_len: report.before,
            after_len: report.after,
            objects_before: report.objects_before,
            objects_after: report.objects_after,
            duplicates_merged: report.duplicates_merged,
            streams_recompressed: report.streams_recompressed,
            images_recompressed: report.images_recompressed,
            protection: protection.into(),
            restrictions_kept,
        }
    }
}

impl NativePdfOverlayReport {
    fn from_engine(report: PdfOverlayReport, protection: PdfProtectionOutcome, restrictions_kept: bool) -> Self {
        Self {
            page_count: report.page_count,
            pages_changed: report.pages_changed,
            total_len: report.total_len,
            protection: protection.into(),
            restrictions_kept,
        }
    }
}

/// Converts the native overlay, refusing a stroke whose points don't pair up.
fn to_overlay(overlay: NativePdfOverlay) -> Result<PdfOverlay, ReadError> {
    Ok(match overlay {
        NativePdfOverlay::Watermark { watermark } => PdfOverlay::Watermark(PdfWatermark {
            text: watermark.text,
            font_size: watermark.font_size,
            opacity: watermark.opacity,
            angle_degrees: watermark.angle_degrees,
            color: watermark.color,
            position: watermark.position.into(),
            pages: watermark.pages,
        }),
        NativePdfOverlay::Sign { signature } => {
            let mut strokes = Vec::with_capacity(signature.strokes.len());
            for stroke in signature.strokes {
                if stroke.points.len() % 2 != 0 {
                    return Err(EngineError::malformed("A signature stroke has a point without a coordinate.").into());
                }
                strokes.push(stroke.points.chunks_exact(2).map(|pair| (pair[0], pair[1])).collect());
            }
            PdfOverlay::Sign(PdfSignature {
                page: signature.page,
                rect: [signature.x, signature.y, signature.width, signature.height],
                strokes,
                color: signature.color,
                line_width: signature.line_width,
            })
        }
    })
}

/// Shifts a part's progress into the whole split's count, so the bar moves
/// across parts instead of restarting at each one.
struct PartObserver<'a> {
    inner: &'a mut ListenerObserver,
    base: u32,
    total: u32,
}

impl PdfToolObserver for PartObserver<'_> {
    fn on_progress(&mut self, done: u32, _total: u32) {
        self.inner.on_progress(self.base + done, self.total);
    }

    fn is_cancelled(&self) -> bool {
        self.inner.is_cancelled()
    }
}

/// Writes one PDF per range, to `target_paths` in the same order. Ranges must
/// be in bounds and must not share a page. Each part is a plain page list, so it
/// keeps the same guarantees as any other tool. On failure every target is removed.
#[uniffi::export]
pub fn pdf_tool_split(
    source: NativePdfSource,
    ranges: Vec<NativePdfPageRange>,
    target_paths: Vec<String>,
    listener: Box<dyn NativePdfToolListener>,
) -> Result<Vec<NativePdfToolReport>, ReadError> {
    let file = open_source(&source)?;
    let password: Option<Zeroizing<String>> = source.password.map(Zeroizing::new);
    if ranges.len() != target_paths.len() {
        return Err(EngineError::new(document_core::ErrorKind::Internal, "each part needs its own target").into());
    }
    let ranges: Vec<PdfPageRange> = ranges.into_iter().map(|r| PdfPageRange { start: r.start, end: r.end }).collect();
    let page_count = inspect_for_tools(
        file.try_clone().map_err(EngineError::from)?,
        password.as_ref().map(|p| p.as_str()),
    )?
    .page_count;
    let parts = plan_split(&ranges, page_count)?;
    let total: u32 = parts.iter().map(|part| part.len() as u32).sum();
    let mut listener = ListenerObserver(listener);
    let result = split_parts(&file, password.as_ref(), &parts, &target_paths, total, &mut listener);
    if result.is_err() {
        for path in &target_paths {
            std::fs::remove_file(path).ok();
        }
    }
    result
}

fn split_parts(
    file: &File,
    password: Option<&Zeroizing<String>>,
    parts: &[Vec<PdfPagePick>],
    target_paths: &[String],
    total: u32,
    listener: &mut ListenerObserver,
) -> Result<Vec<NativePdfToolReport>, ReadError> {
    let mut reports = Vec::with_capacity(parts.len());
    let mut base = 0u32;
    for (pages, target_path) in parts.iter().zip(target_paths) {
        let op = PdfToolOp::Rebuild { pages: pages.clone() };
        let reader = file.try_clone().map_err(EngineError::from)?;
        let sources = vec![PdfToolSource { reader, password: password.cloned() }];
        let mut target = BufWriter::new(File::create(target_path).map_err(EngineError::from)?);
        let mut observer = PartObserver { inner: &mut *listener, base, total };
        let report = run_pdf_tool(sources, &op, &mut target, &mut observer)?;
        let target = target.into_inner().map_err(|e| EngineError::from(e.into_error()))?;
        target.sync_all().map_err(EngineError::from)?;
        drop(target);
        verify_pdf_tool_output(
            open_for_read(target_path)?,
            password.map(|p| p.as_str()),
            report.page_count,
        )?;
        base += pages.len() as u32;
        reports.push(report.into());
    }
    Ok(reports)
}

/// Runs one lossy or structural step on a plain copy of the source, and protects
/// the result as [`plan_protection`] says for the source's protection and `choice`.
///
/// Protected input: the step needs the file's content to be changeable. A file
/// with limits and no owner password is refused for steps that change content
/// (`may_modify`). Otherwise the plain copy is made with `PdfToolOp::Unlock`, which
/// doesn't refuse on limits, the step runs on it, and the result is written with
/// the plan's algorithm, `/P` and passwords. Each output is verified with its
/// user password before it counts. A file without encryption is never protected.
///
/// `step` gets the plain reader, the private target path and the observer. It returns its
/// report and the page count to verify against. The result is the step's value, the protection
/// outcome, and whether the source's limits were kept.
fn run_keeping_protection<T>(
    source: &NativePdfSource,
    target_path: &str,
    choice: NativeProtectionChoice,
    observer: &mut ListenerObserver,
    step: impl FnOnce(File, &str, &mut ListenerObserver) -> Result<(T, u32), ReadError>,
) -> Result<(T, PdfProtectionOutcome, bool), ReadError> {
    let password: Option<Zeroizing<String>> = source.password.clone().filter(|p| !p.is_empty()).map(Zeroizing::new);
    // One descriptor per source (it passes to the engine); clones share it, and reads seek.
    let file = open_source(source)?;
    let probe = file.try_clone().map_err(EngineError::from)?;
    let facts = inspect_protection(probe, password.as_ref().map(|p| p.as_str()))?;
    if !facts.encrypted {
        let (value, _) = step(file, target_path, observer)?;
        return Ok((value, PdfProtectionOutcome::NotProtected, false));
    }
    if !facts.is_owner && !facts.modifiable {
        return Err(EngineError::new(
            ErrorKind::PasswordRequired,
            "The owner of this PDF doesn't allow changing its content. Enter the owner password to continue.".to_owned(),
        )
        .into());
    }
    let typed = password.as_ref().map(|p| p.as_str());
    let (plan, outcome): (Option<ProtectionPlan>, PdfProtectionOutcome) =
        plan_protection(&facts, choice.into(), typed)?;
    let restrictions_kept = plan.as_ref().is_some_and(|plan| plan.keeps_restrictions());
    let verify_with: Option<Zeroizing<String>> = plan.as_ref().and_then(|p| p.user_password().map(str::to_owned)).map(Zeroizing::new);

    let plain_path = format!("{target_path}.plain.tmp");
    let stepped_path = format!("{target_path}.step.tmp");
    let result = (|| -> Result<T, ReadError> {
        // Phase 1: unlock to a private plain copy, verified before anything runs on it.
        let mut plain = BufWriter::new(File::create(&plain_path).map_err(EngineError::from)?);
        let unlock = PdfToolSource { reader: file.try_clone().map_err(EngineError::from)?, password: password.clone() };
        let unlocked = run_pdf_tool(vec![unlock], &PdfToolOp::Unlock, &mut plain, observer)?;
        let plain = plain.into_inner().map_err(|e| EngineError::from(e.into_error()))?;
        plain.sync_all().map_err(EngineError::from)?;
        drop(plain);
        verify_pdf_tool_output(open_for_read(&plain_path)?, None, unlocked.page_count)?;

        // Phase 2: the requested operation, on the plain copy.
        let (value, _pages) = step(open_for_read(&plain_path)?, &stepped_path, observer)?;

        // Phase 3: protect the result as planned, or hand over the plain result.
        match plan {
            Some(plan) => {
                let mut out = BufWriter::new(File::create(target_path).map_err(EngineError::from)?);
                let reader = open_for_read(&stepped_path)?;
                let protect = PdfToolSource { reader, password: None };
                let protected = run_pdf_tool(vec![protect], &PdfToolOp::Protect { plan }, &mut out, observer)?;
                let out = out.into_inner().map_err(|e| EngineError::from(e.into_error()))?;
                out.sync_all().map_err(EngineError::from)?;
                drop(out);
                verify_pdf_tool_output(
                    open_for_read(target_path)?,
                    verify_with.as_ref().map(|p| p.as_str()),
                    protected.page_count,
                )?;
            }
            None => {
                std::fs::rename(&stepped_path, target_path).map_err(EngineError::from)?;
            }
        }
        Ok(value)
    })();
    std::fs::remove_file(&plain_path).ok();
    std::fs::remove_file(&stepped_path).ok();
    if result.is_err() {
        std::fs::remove_file(target_path).ok();
    }
    result.map(|value| (value, outcome, restrictions_kept))
}

/// Re-writes a PDF with its objects compacted: unused objects dropped, identical
/// streams merged, Flate streams re-deflated when that makes them smaller, and,
/// at Balanced or Strong, large images re-encoded as JPEG at a lower resolution.
/// The output is verified before it's reported. A protected input is unlocked
/// for the work and protected again as `protection` asks (see [`run_keeping_protection`]).
#[uniffi::export]
pub fn pdf_tool_compress(
    source: NativePdfSource,
    target_path: String,
    quality: NativeImageQuality,
    protection: NativeProtectionChoice,
    listener: Box<dyn NativePdfToolListener>,
) -> Result<NativePdfCompressReport, ReadError> {
    let mut observer = ListenerObserver(listener);
    let (report, outcome, restrictions_kept) = run_keeping_protection(
        &source,
        &target_path,
        protection,
        &mut observer,
        |reader, out_path, observer| {
            let mut target = BufWriter::new(File::create(out_path).map_err(EngineError::from)?);
            let report = compress_pdf_with_quality(reader, &mut target, observer, quality.into())?;
            let target = target.into_inner().map_err(|e| EngineError::from(e.into_error()))?;
            target.sync_all().map_err(EngineError::from)?;
            drop(target);
            verify_pdf_tool_output(open_for_read(out_path)?, None, report.page_count)?;
            let pages = report.page_count;
            Ok((report, pages))
        },
    )?;
    Ok(NativePdfCompressReport::from_engine(report, outcome, restrictions_kept))
}

/// Adds a watermark or a drawn signature over the chosen pages. The result
/// keeps the original bytes of the plain copy and appends an update. It is
/// verified before it's reported. A protected input is handled as in
/// [`pdf_tool_compress`].
#[uniffi::export]
pub fn pdf_tool_overlay(
    source: NativePdfSource,
    overlay: NativePdfOverlay,
    target_path: String,
    protection: NativeProtectionChoice,
    listener: Box<dyn NativePdfToolListener>,
) -> Result<NativePdfOverlayReport, ReadError> {
    let overlay = to_overlay(overlay)?;
    let mut observer = ListenerObserver(listener);
    let (report, outcome, restrictions_kept) = run_keeping_protection(
        &source,
        &target_path,
        protection,
        &mut observer,
        |reader, out_path, observer| {
            let mut target = BufWriter::new(File::create(out_path).map_err(EngineError::from)?);
            let report = apply_overlay(reader, &mut target, &overlay, observer)?;
            let target = target.into_inner().map_err(|e| EngineError::from(e.into_error()))?;
            target.sync_all().map_err(EngineError::from)?;
            drop(target);
            verify_overlay(open_for_read(out_path)?, report.page_count)?;
            let pages = report.page_count;
            Ok((report, pages))
        },
    )?;
    Ok(NativePdfOverlayReport::from_engine(report, outcome, restrictions_kept))
}
