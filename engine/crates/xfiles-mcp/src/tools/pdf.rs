//! PDF tools: merge, split, compress, reorder, rotate, watermark and protect.
//! Every tool writes a new file; the input is only read.

use std::fs::{self, File};
use std::io::{BufWriter, Write};
use std::path::Path;
use std::sync::Arc;

use document_parsers::{
    PdfDropped, PdfImageQuality, PdfOverlay, PdfPagePick, PdfPageRange, PdfToolOp, PdfToolReport, PdfToolSource,
    PdfWatermark, PdfWatermarkPosition, apply_overlay, compress_pdf_with_quality, inspect_for_tools, plan_split,
    run_pdf_tool,
};
use schemars::JsonSchema;
use serde::Deserialize;
use serde_json::{Value, json};
use zeroize::Zeroizing;

use crate::error::{Result, ToolError};
use crate::jobs::{JobContext, Progress};
use crate::output::PendingOutput;
use crate::sandbox::{Sandbox, display};
use crate::services::Services;
use crate::validate;

const PDF: &[&str] = &["pdf"];
const MAX_MERGE_INPUTS: usize = 50;
const MAX_PASSWORD_BYTES: usize = 127;

/// A page of the source with an optional clockwise turn.
#[derive(Debug, Clone, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct PageTurn {
    /// 1-based page number in the source file.
    pub page: u32,
    /// Clockwise degrees: 0, 90, 180 or 270. Negative values turn counter-clockwise. Default 0.
    #[serde(default)]
    pub degrees: i32,
}

#[derive(Debug, Clone, Copy, Deserialize, JsonSchema, Default)]
#[serde(rename_all = "snake_case")]
pub enum SplitMode {
    /// Use the page ranges given in `ranges`; each range becomes one file.
    #[default]
    Ranges,
    /// Cut the document into files of `every` pages each.
    EveryN,
    /// One file per page.
    PerPage,
}

/// How much to shrink images. Lossless only rewrites the file structure and never changes pixels.
#[derive(Debug, Clone, Copy, Deserialize, JsonSchema, Default)]
#[serde(rename_all = "snake_case")]
pub enum Quality {
    /// Structure only: no image is re-encoded.
    Lossless,
    /// Images resampled to about 150 dpi and re-encoded.
    #[default]
    Balanced,
    /// Images resampled to about 110 dpi and re-encoded. Smallest file, most detail lost.
    Strong,
}

#[derive(Debug, Clone, Copy, Deserialize, JsonSchema, Default)]
#[serde(rename_all = "snake_case")]
pub enum WatermarkPosition {
    #[default]
    Center,
    TopLeft,
    TopCenter,
    TopRight,
    BottomLeft,
    BottomCenter,
    BottomRight,
    /// Repeated across the whole page in a staggered grid.
    Tiled,
}

#[derive(Debug, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct PdfMergeArgs {
    /// PDFs to join, in order. Two to fifty.
    pub inputs: Vec<String>,
    /// New PDF to write.
    pub output: String,
    /// Replace the output if it already exists. Default false.
    #[serde(default)]
    pub overwrite: bool,
}

#[derive(Debug, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct PdfSplitArgs {
    /// PDF to split.
    pub input: String,
    /// Existing folder inside an allowed root that receives the parts.
    pub output_dir: String,
    /// How to cut the document. Default ranges.
    #[serde(default)]
    pub mode: SplitMode,
    /// For mode ranges: inclusive 1-based page ranges such as "1-3,5,8-10". Ranges may not overlap.
    pub ranges: Option<String>,
    /// For mode every_n: pages per part, 1 to 10000.
    pub every: Option<u32>,
    /// File name prefix for the parts, such as "chapter". Default "part". Parts are named prefix-01.pdf and so on.
    pub prefix: Option<String>,
    /// Replace existing part files. Default false.
    #[serde(default)]
    pub overwrite: bool,
}

#[derive(Debug, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct PdfCompressArgs {
    /// PDF to compress.
    pub input: String,
    /// New PDF to write.
    pub output: String,
    /// Image quality level. Default balanced.
    #[serde(default)]
    pub quality: Quality,
    /// Replace the output if it already exists. Default false.
    #[serde(default)]
    pub overwrite: bool,
}

#[derive(Debug, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct PdfReorderArgs {
    /// PDF to reorder.
    pub input: String,
    /// New PDF to write.
    pub output: String,
    /// Every page of the source exactly once, in the new order. Each entry may also turn the page.
    pub order: Vec<PageTurn>,
    /// Replace the output if it already exists. Default false.
    #[serde(default)]
    pub overwrite: bool,
}

#[derive(Debug, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct PdfRotateArgs {
    /// PDF to rotate.
    pub input: String,
    /// New PDF to write.
    pub output: String,
    /// Pages to turn. Pages not listed keep their orientation. Every page is kept.
    pub rotations: Vec<PageTurn>,
    /// Replace the output if it already exists. Default false.
    #[serde(default)]
    pub overwrite: bool,
}

#[derive(Debug, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct PdfWatermarkArgs {
    /// PDF to watermark.
    pub input: String,
    /// New PDF to write.
    pub output: String,
    /// Watermark text, 1 to 200 characters.
    pub text: String,
    /// Font size in points, 6 to 300. Default 36.
    pub font_size: Option<f32>,
    /// Opacity from 0 (invisible) to 1 (solid). Default 0.25.
    pub opacity: Option<f32>,
    /// Rotation in degrees, counter-clockwise on screen, -360 to 360. Default 45.
    pub angle_degrees: Option<f32>,
    /// Text colour as #RRGGBB. Default #808080.
    pub color: Option<String>,
    /// Where the text goes. Default center.
    #[serde(default)]
    pub position: WatermarkPosition,
    /// 1-based pages to mark. Omit for every page.
    pub pages: Option<Vec<u32>>,
    /// Replace the output if it already exists. Default false.
    #[serde(default)]
    pub overwrite: bool,
}

#[derive(Debug, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct PdfProtectArgs {
    /// PDF to protect. Must not already be encrypted.
    pub input: String,
    /// New PDF to write. It is encrypted with AES-256.
    pub output: String,
    /// Password needed to open the new PDF. 1 to 127 bytes. It is not stored or echoed.
    pub user_password: String,
    /// Replace the output if it already exists. Default false.
    #[serde(default)]
    pub overwrite: bool,
}

pub async fn merge(svc: Arc<Services>, args: PdfMergeArgs) -> Result<Value> {
    if !(2..=MAX_MERGE_INPUTS).contains(&args.inputs.len()) {
        return Err(ToolError::invalid(format!("merge takes between 2 and {MAX_MERGE_INPUTS} inputs")));
    }
    let inputs = args
        .inputs
        .iter()
        .map(|raw| svc.input(raw))
        .collect::<Result<Vec<_>>>()?;
    let output = svc.output(&args.output, args.overwrite, PDF, &inputs)?;
    let overwrite = args.overwrite;

    svc.jobs
        .run(move |ctx| {
            let mut sources = Vec::with_capacity(inputs.len());
            for path in &inputs {
                sources.push(PdfToolSource { reader: File::open(path)?, password: None });
            }
            let mut pending = PendingOutput::create(output, overwrite)?;
            let report = {
                let mut writer = BufWriter::new(pending.file()?);
                let report = run_pdf_tool(sources, &PdfToolOp::Merge, &mut writer, &mut Progress(&ctx))?;
                writer.flush()?;
                report
            };
            let path = pending.commit()?;
            Ok(output_json(&path, &report))
        })
        .await
}

pub async fn split(svc: Arc<Services>, args: PdfSplitArgs) -> Result<Value> {
    let input = svc.input(&args.input)?;
    let folder = svc.sandbox.directory(&args.output_dir)?;
    let prefix = validate::file_stem(args.prefix.as_deref().unwrap_or("part"), "prefix")?.to_owned();
    let overwrite = args.overwrite;
    let (mode, every, spec) = (args.mode, args.every, args.ranges);
    let sandbox = svc.sandbox.clone();

    svc.jobs
        .run(move |ctx| {
            let page_count = inspect_for_tools(File::open(&input)?, None)?.page_count;
            let ranges = split_ranges(mode, every, spec.as_deref(), page_count)?;
            let parts = plan_split(&ranges, page_count)?;
            let width = parts.len().to_string().len().max(2);

            let mut pending = Vec::with_capacity(parts.len());
            for index in 0..parts.len() {
                let name = format!("{prefix}-{:0width$}.pdf", index + 1, width = width);
                let target = output_in(&sandbox, &folder.join(&name), &name, overwrite, &input)?;
                pending.push(PendingOutput::create(target, overwrite)?);
            }

            let mut reports = Vec::with_capacity(parts.len());
            for (part, out) in parts.into_iter().zip(pending.iter_mut()) {
                let sources = vec![PdfToolSource { reader: File::open(&input)?, password: None }];
                let op = PdfToolOp::Rebuild { pages: part };
                reports.push(write_document(&ctx, sources, &op, out)?);
            }

            let mut outputs = Vec::with_capacity(pending.len());
            for (out, report) in pending.into_iter().zip(reports.iter()) {
                let path = out.commit()?;
                outputs.push(output_json(&path, report));
            }
            Ok(json!({ "source_pages": page_count, "part_count": outputs.len(), "parts": outputs }))
        })
        .await
}

pub async fn compress(svc: Arc<Services>, args: PdfCompressArgs) -> Result<Value> {
    let input = svc.input(&args.input)?;
    let output = svc.output(&args.output, args.overwrite, PDF, std::slice::from_ref(&input))?;
    let quality = match args.quality {
        Quality::Lossless => PdfImageQuality::Lossless,
        Quality::Balanced => PdfImageQuality::Balanced,
        Quality::Strong => PdfImageQuality::Strong,
    };
    let overwrite = args.overwrite;

    svc.jobs
        .run(move |ctx| {
            let mut pending = PendingOutput::create(output, overwrite)?;
            let report = {
                let mut writer = BufWriter::new(pending.file()?);
                let report = compress_pdf_with_quality(File::open(&input)?, &mut writer, &mut Progress(&ctx), quality)?;
                writer.flush()?;
                report
            };
            let path = pending.commit()?;
            let saved = report.before.saturating_sub(report.after);
            let percent = if report.before == 0 { 0.0 } else { saved as f64 * 100.0 / report.before as f64 };
            Ok(json!({
                "output": display(&path),
                "page_count": report.page_count,
                "before_bytes": report.before,
                "after_bytes": report.after,
                "saved_bytes": saved,
                "saved_percent": (percent * 10.0).round() / 10.0,
                "objects_before": report.objects_before,
                "objects_after": report.objects_after,
            }))
        })
        .await
}

pub async fn reorder(svc: Arc<Services>, args: PdfReorderArgs) -> Result<Value> {
    if args.order.is_empty() {
        return Err(ToolError::invalid("order must list every page once"));
    }
    let input = svc.input(&args.input)?;
    let output = svc.output(&args.output, args.overwrite, PDF, std::slice::from_ref(&input))?;
    let pages = picks(&args.order)?;
    let overwrite = args.overwrite;

    svc.jobs
        .run(move |ctx| {
            let op = PdfToolOp::Reorder { pages };
            let mut pending = PendingOutput::create(output, overwrite)?;
            let sources = vec![PdfToolSource { reader: File::open(&input)?, password: None }];
            let report = write_document(&ctx, sources, &op, &mut pending)?;
            let path = pending.commit()?;
            Ok(output_json(&path, &report))
        })
        .await
}

pub async fn rotate(svc: Arc<Services>, args: PdfRotateArgs) -> Result<Value> {
    let input = svc.input(&args.input)?;
    let output = svc.output(&args.output, args.overwrite, PDF, std::slice::from_ref(&input))?;
    let turns = args
        .rotations
        .iter()
        .map(|turn| Ok((validate::page(turn.page, "page")?, validate::rotation(turn.degrees)?)))
        .collect::<Result<Vec<(u32, i32)>>>()?;
    let overwrite = args.overwrite;

    svc.jobs
        .run(move |ctx| {
            let page_count = inspect_for_tools(File::open(&input)?, None)?.page_count;
            let mut rotation = vec![0i32; page_count as usize];
            for (page, degrees) in turns {
                let slot = rotation
                    .get_mut(page as usize)
                    .ok_or_else(|| ToolError::invalid(format!("page {} is past the last page ({page_count})", page + 1)))?;
                *slot = degrees;
            }
            let pages = (0..page_count)
                .map(|page| PdfPagePick { page, rotation: rotation[page as usize] })
                .collect();
            let op = PdfToolOp::Rebuild { pages };
            let mut pending = PendingOutput::create(output, overwrite)?;
            let sources = vec![PdfToolSource { reader: File::open(&input)?, password: None }];
            let report = write_document(&ctx, sources, &op, &mut pending)?;
            let path = pending.commit()?;
            Ok(output_json(&path, &report))
        })
        .await
}

pub async fn watermark(svc: Arc<Services>, args: PdfWatermarkArgs) -> Result<Value> {
    let input = svc.input(&args.input)?;
    let output = svc.output(&args.output, args.overwrite, PDF, std::slice::from_ref(&input))?;
    let text = validate::required_text(&args.text, "text", 200)?.to_owned();
    let font_size = validate::float_in(args.font_size.unwrap_or(36.0), 6.0, 300.0, "font_size")?;
    let opacity = validate::float_in(args.opacity.unwrap_or(0.25), 0.0, 1.0, "opacity")?;
    let angle = validate::float_in(args.angle_degrees.unwrap_or(45.0), -360.0, 360.0, "angle_degrees")?;
    let color = validate::color(args.color.as_deref().unwrap_or("#808080"))?;
    let pages = match &args.pages {
        Some(list) if list.is_empty() => return Err(ToolError::invalid("pages must list at least one page")),
        Some(list) => Some(list.iter().map(|page| validate::page(*page, "pages")).collect::<Result<Vec<_>>>()?),
        None => None,
    };
    let overlay = PdfOverlay::Watermark(PdfWatermark {
        text,
        font_size,
        opacity,
        angle_degrees: angle,
        color,
        position: position_of(args.position),
        pages,
    });
    let overwrite = args.overwrite;

    svc.jobs
        .run(move |ctx| {
            let mut pending = PendingOutput::create(output, overwrite)?;
            {
                let mut writer = BufWriter::new(pending.file()?);
                apply_overlay(File::open(&input)?, &mut writer, &overlay, &mut Progress(&ctx))?;
                writer.flush()?;
            }
            let path = pending.commit()?;
            Ok(json!({ "output": display(&path), "bytes": fs::metadata(&path)?.len() }))
        })
        .await
}

pub async fn protect(svc: Arc<Services>, args: PdfProtectArgs) -> Result<Value> {
    let input = svc.input(&args.input)?;
    let output = svc.output(&args.output, args.overwrite, PDF, std::slice::from_ref(&input))?;
    if args.user_password.is_empty() {
        return Err(ToolError::invalid("user_password is required"));
    }
    if args.user_password.len() > MAX_PASSWORD_BYTES {
        return Err(ToolError::invalid(format!("user_password is longer than {MAX_PASSWORD_BYTES} bytes")));
    }
    let password = Zeroizing::new(args.user_password);
    let overwrite = args.overwrite;

    svc.jobs
        .run(move |ctx| {
            let op = PdfToolOp::AddPassword { password };
            let mut pending = PendingOutput::create(output, overwrite)?;
            let sources = vec![PdfToolSource { reader: File::open(&input)?, password: None }];
            let report = write_document(&ctx, sources, &op, &mut pending)?;
            let path = pending.commit()?;
            Ok(output_json(&path, &report))
        })
        .await
}

/// The page ranges a split mode asks for, zero-based and inclusive.
fn split_ranges(mode: SplitMode, every: Option<u32>, spec: Option<&str>, page_count: u32) -> Result<Vec<PdfPageRange>> {
    match mode {
        SplitMode::Ranges => {
            let spec = spec.ok_or_else(|| ToolError::invalid("ranges is required when mode is ranges"))?;
            validate::page_ranges(spec)
        }
        SplitMode::EveryN => {
            let step = every.ok_or_else(|| ToolError::invalid("every is required when mode is every_n"))?;
            let step = validate::bounded(u64::from(step), 1, 10_000, "every")? as u32;
            Ok((0..page_count)
                .step_by(step as usize)
                .map(|start| PdfPageRange { start, end: (start + step - 1).min(page_count.saturating_sub(1)) })
                .collect())
        }
        SplitMode::PerPage => Ok((0..page_count).map(|page| PdfPageRange { start: page, end: page }).collect()),
    }
}

/// Checks a planned output path against the sandbox rules, including that it is not an input.
fn output_in(sandbox: &Sandbox, target: &Path, label: &str, overwrite: bool, input: &Path) -> Result<std::path::PathBuf> {
    sandbox.output_path(target, label, overwrite, PDF, std::slice::from_ref(&input.to_path_buf()))
}

/// Writes one rebuilt or reordered document into a pending output.
fn write_document(
    ctx: &JobContext,
    sources: Vec<PdfToolSource<File>>,
    op: &PdfToolOp,
    pending: &mut PendingOutput,
) -> Result<PdfToolReport> {
    let mut writer = BufWriter::new(pending.file()?);
    let report = run_pdf_tool(sources, op, &mut writer, &mut Progress(ctx))?;
    writer.flush()?;
    Ok(report)
}

fn picks(turns: &[PageTurn]) -> Result<Vec<PdfPagePick>> {
    let mut out = Vec::with_capacity(turns.len());
    for turn in turns {
        out.push(PdfPagePick {
            page: validate::page(turn.page, "page")?,
            rotation: validate::rotation(turn.degrees)?,
        });
    }
    Ok(out)
}

fn position_of(position: WatermarkPosition) -> PdfWatermarkPosition {
    match position {
        WatermarkPosition::Center => PdfWatermarkPosition::Center,
        WatermarkPosition::TopLeft => PdfWatermarkPosition::TopLeft,
        WatermarkPosition::TopCenter => PdfWatermarkPosition::TopCenter,
        WatermarkPosition::TopRight => PdfWatermarkPosition::TopRight,
        WatermarkPosition::BottomLeft => PdfWatermarkPosition::BottomLeft,
        WatermarkPosition::BottomCenter => PdfWatermarkPosition::BottomCenter,
        WatermarkPosition::BottomRight => PdfWatermarkPosition::BottomRight,
        WatermarkPosition::Tiled => PdfWatermarkPosition::Tiled,
    }
}

fn output_json(path: &Path, report: &PdfToolReport) -> Value {
    json!({
        "output": display(path),
        "page_count": report.page_count,
        "bytes": report.bytes,
        "dropped": dropped_json(report.dropped),
    })
}

/// What the source held that the output could not keep, in plain words.
fn dropped_json(dropped: PdfDropped) -> Value {
    let mut notes: Vec<String> = Vec::new();
    if dropped.outlines {
        notes.push("bookmarks (outlines) were not carried over".to_owned());
    }
    if dropped.forms {
        notes.push("interactive form fields were not carried over".to_owned());
    }
    if dropped.named_destinations {
        notes.push("named destinations were not carried over".to_owned());
    }
    if dropped.structure_tags {
        notes.push("accessibility structure tags were not carried over".to_owned());
    }
    if dropped.page_labels {
        notes.push("page labels were not carried over".to_owned());
    }
    if dropped.attachments {
        notes.push("embedded attachments were not carried over".to_owned());
    }
    if dropped.links > 0 {
        notes.push(format!("{} links pointing at pages that are not in the output were removed", dropped.links));
    }
    json!({ "notes": notes })
}
