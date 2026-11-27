//! Bounded JSON requests for the iOS Swift target. Android still uses UniFFI.
use std::ffi::{CStr, CString, c_char};
use std::sync::Arc;

use serde_json::{Value, json};

use super::*;
use crate::archive::{ArchiveSession, open_archive_file};
use crate::pdf_tools_ext::{
    NativeImageQuality, NativePdfOverlay, NativeProtectionChoice, NativeSignature, NativeWatermark,
    NativeWatermarkPosition, pdf_tool_compress, pdf_tool_overlay,
};
use crate::search::{NativeIndexEntry, NativePassageCache, NativeSearchIndex, text_reader_open_path};
use crate::sheet_edit::{NativeCellEdit, NativeCellValue, xlsx_apply_cell_edits};

const PREVIEW_BYTES: u64 = 12 * 1024 * 1024;
const PREVIEW_NODES: usize = 25_000;
const SEARCH_CHARS: u32 = 240_000;
const MAX_PDF_PICKS: usize = 5_000;

pub struct IosJob {
    token: CancelToken,
    active: Mutex<Option<CancelToken>>,
}

impl IosJob {
    fn attach(&self, token: CancelToken) {
        if let Ok(mut active) = self.active.lock() {
            if self.token.is_cancelled() {
                token.cancel();
            }
            *active = Some(token);
        }
    }

    fn cancel(&self) {
        self.token.cancel();
        if let Ok(active) = self.active.lock() {
            if let Some(token) = active.as_ref() {
                token.cancel();
            }
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn xfiles_ios_job_new() -> *mut IosJob {
    Box::into_raw(Box::new(IosJob { token: CancelToken::default(), active: Mutex::new(None) }))
}

/// Swift owns the job until its asynchronous request has returned.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn xfiles_ios_job_cancel(job: *mut IosJob) {
    if let Some(job) = unsafe { job.as_ref() } {
        job.cancel();
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn xfiles_ios_job_free(job: *mut IosJob) {
    if !job.is_null() {
        drop(unsafe { Box::from_raw(job) });
    }
}

/// `input` is valid UTF-8, NUL-terminated and borrowed for this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn xfiles_ios_call(input: *const c_char, job: *mut IosJob) -> *mut c_char {
    let result = std::panic::catch_unwind(|| {
        let job = unsafe { job.as_ref() }.ok_or_else(|| invalid("missing iOS job"))?;
        if input.is_null() {
            return Err(invalid("missing iOS request"));
        }
        let request: Value = serde_json::from_slice(unsafe { CStr::from_ptr(input) }.to_bytes())
            .map_err(|_| invalid("invalid iOS request"))?;
        dispatch(&request, job)
    });
    let response = match result {
        Ok(Ok(value)) => json!({ "value": value }),
        Ok(Err(error)) => json!({ "error": error.to_string(), "code": match error {
            ReadError::PasswordRequired { .. } => "password_required",
            ReadError::WrongPassword { .. } => "wrong_password",
            ReadError::Cancelled { .. } => "cancelled",
            _ => "engine_error",
        }}),
        Err(_) => json!({ "error": "The document engine stopped unexpectedly.", "code": "engine_error" }),
    };
    // JSON escapes embedded NULs. Null is still safe for Swift to handle.
    CString::new(response.to_string()).map(CString::into_raw).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn xfiles_ios_string_free(value: *mut c_char) {
    if !value.is_null() {
        drop(unsafe { CString::from_raw(value) });
    }
}

fn invalid(detail: &str) -> ReadError {
    ReadError::Malformed { detail: detail.into() }
}

fn required<'a>(value: &'a Value, key: &str) -> Result<&'a str, ReadError> {
    value[key].as_str().filter(|text| !text.is_empty()).ok_or_else(|| invalid("a required value is missing"))
}

fn number(value: &Value, key: &str) -> Result<u32, ReadError> {
    value[key].as_u64().and_then(|n| u32::try_from(n).ok()).ok_or_else(|| invalid("invalid page or cell index"))
}

fn cancelled(job: &IosJob) -> Result<(), ReadError> {
    job.token.check().map_err(ReadError::from)
}

fn archive(path: &str, job: &IosJob) -> Result<Arc<ArchiveSession>, ReadError> {
    open_archive_file(open_for_read(path)?, job.token.clone())
}

fn read_document(path: String, password: Option<String>, job: &IosJob) -> Result<Value, ReadError> {
    let session = open_path(
        path,
        None,
        NativeLimits { max_total_output_bytes: PREVIEW_BYTES, ..NativeLimits::default() },
        password,
    )?;
    job.attach(session.cancel.clone());
    let mut nodes = Vec::new();
    let mut truncated = false;
    let mut index = 0usize;
    'outer: while let Some(batch) = session.next_batch()? {
        cancelled(job)?;
        truncated |= batch.truncated;
        for block in batch.blocks {
            let item = index;
            index += 1;
            let mut node = match block {
                NativeBlock::Heading { level, text, spans } => {
                    json!({"kind":"heading","text":text,"level":level,"spans":display_spans(&spans, 0)})
                }
                NativeBlock::Paragraph { text, spans } => {
                    json!({"kind":"text","text":text,"spans":display_spans(&spans, 0)})
                }
                NativeBlock::ListItem { marker, text, spans, .. } => {
                    let prefix = format!("{} ", marker.as_deref().unwrap_or("•"));
                    json!({"kind":"list","text":format!("{prefix}{text}"),"spans":display_spans(&spans, prefix.encode_utf16().count() as u32)})
                }
                NativeBlock::Quote { text, spans } => {
                    json!({"kind":"quote","text":text,"spans":display_spans(&spans, 0)})
                }
                NativeBlock::Code { text, .. } => json!({"kind":"code","text":text}),
                NativeBlock::Divider => json!({"kind":"divider"}),
                NativeBlock::Section { title } => json!({"kind":"section","text":title}),
                NativeBlock::TableRow { row, cells, header, .. } => {
                    json!({"kind":"row","row":row,"cells":cells,"header":header})
                }
                NativeBlock::Image { path, alt, .. } => json!({"kind":"image","resource":path,"text":alt}),
                NativeBlock::Slide { width, height, background, .. } => {
                    json!({"kind":"slide","width":width,"height":height,"background":background})
                }
                NativeBlock::Shape { shape } => json!({
                    "kind":"shape","x":shape.x,"y":shape.y,"width":shape.width,"height":shape.height,
                    "rotation":shape.rotation,"background":shape.fill,"resource":shape.image,
                    "link":shape.link,"media":shape.media,"mediaExternal":shape.media_external,
                    "text":shape.paragraphs.into_iter().map(|p| p.text).collect::<Vec<_>>().join("\n"),
                    "cells":shape.table.into_iter().map(|row| row.join("  |  ")).collect::<Vec<_>>(),
                }),
                _ => continue,
            };
            node["sourceIndex"] = json!(item);
            nodes.push(node);
            if nodes.len() >= PREVIEW_NODES {
                truncated = true;
                break 'outer;
            }
        }
        if batch.is_last {
            break;
        }
    }
    let book: Option<Value> = if session.info().format == NativeFormat::Epub {
        session.resource("ezfiles:book.json".into()).ok().and_then(|bytes| serde_json::from_slice(&bytes).ok())
    } else {
        None
    };
    Ok(json!({"nodes":nodes,"truncated":truncated,"book":book}))
}

fn display_spans(spans: &[NativeSpan], prefix: u32) -> Vec<Value> {
    spans
        .iter()
        .map(|span| {
            json!({
                "start":span.start.saturating_add(prefix),"end":span.end.saturating_add(prefix),
                "style":span.style,"link":span.link,
            })
        })
        .collect()
}

fn indexed_text(path: &str, password: Option<&str>, job: &IosJob) -> Result<(String, Vec<u32>), ReadError> {
    if path.to_ascii_lowercase().ends_with(".pdf") {
        let extracted = document_parsers::extract_text(
            open_for_read(path)?,
            password,
            document_parsers::TextLimits { max_chars: SEARCH_CHARS as usize, max_pages: 2_000 },
            &job.token,
        )?;
        let mut text = String::new();
        let mut starts = Vec::new();
        let mut units = 0;
        for page in extracted.pages {
            starts.push(units);
            units += page.text.encode_utf16().count() as u32 + 1;
            text.push_str(&page.text);
            text.push('\n');
        }
        Ok((text, starts))
    } else {
        let reader = text_reader_open_path(path.into(), None, NativeLimits::default())?;
        job.attach(reader.cancel_token());
        Ok((reader.read(SEARCH_CHARS)?, Vec::new()))
    }
}

fn search(request: &Value, job: &IosJob) -> Result<Value, ReadError> {
    static INDEX_WRITER: Mutex<()> = Mutex::new(());
    let _writer = INDEX_WRITER.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    cancelled(job)?;
    let index = NativeSearchIndex::new(required(request, "index")?.into());
    let files = request["files"].as_array().ok_or_else(|| invalid("missing search files"))?;
    let mut entries = Vec::with_capacity(files.len());
    for file in files {
        let key = required(file, "id")?.to_owned();
        let size = file["size"].as_u64().unwrap_or_default();
        let mtime = file["stamp"].as_i64().unwrap_or_default();
        entries.push(NativeIndexEntry { key, size, mtime });
    }
    let keys: Vec<_> = entries.iter().map(|entry| entry.key.clone()).collect();
    index.retain(keys.clone());
    for item in index.stale(entries.clone()) {
        cancelled(job)?;
        if !index.has_room() {
            break;
        }
        let Some(entry) = entries.get(item as usize) else { continue };
        let path = required(&files[item as usize], "path")?;
        if let Ok((text, starts)) = indexed_text(path, None, job) {
            index.add(entry.key.clone(), entry.size, entry.mtime, text, starts)?;
        }
    }
    index.save()?;
    cancelled(job)?;
    let hits = index.query_allowed(required(request, "query")?.into(), 30, keys);
    Ok(json!(
        hits.into_iter().map(|hit| json!({"id":hit.key,"snippet":hit.snippet,"page":hit.page})).collect::<Vec<_>>()
    ))
}

fn passages(request: &Value, job: &IosJob) -> Result<Value, ReadError> {
    static CACHE: std::sync::LazyLock<Arc<NativePassageCache>> =
        std::sync::LazyLock::new(|| NativePassageCache::new(5, 12 * 1024 * 1024));
    let key = required(request, "id")?;
    let path = required(request, "path")?;
    let query = required(request, "query")?;
    let stamp = request["stamp"].as_i64().unwrap_or_default();
    let size = request["size"].as_u64().unwrap_or_default();
    let select = || CACHE.select(key.into(), stamp, size, query.into(), 2_900);
    let selection = match select() {
        Some(selection) => Some(selection),
        None => {
            let (text, starts) = indexed_text(path, None, job)?;
            cancelled(job)?;
            CACHE.index(key.into(), stamp, size, text, starts);
            select()
        }
    };
    Ok(json!(
        selection
            .into_iter()
            .flat_map(|selection| selection.passages)
            .map(|passage| json!({
                "id":key,"snippet":passage.text,"page":passage.page.map(|page| page.saturating_sub(1)),
            }))
            .collect::<Vec<_>>()
    ))
}

struct Observer(CancelToken);
impl NativePdfToolListener for Observer {
    fn on_progress(&self, _: u32, _: u32) {}
    fn is_cancelled(&self) -> bool {
        self.0.is_cancelled()
    }
}

fn source(item: &Value) -> Result<NativePdfSource, ReadError> {
    Ok(NativePdfSource {
        fd: -1,
        path: Some(required(item, "path")?.into()),
        password: item["password"].as_str().map(str::to_owned),
    })
}

fn tool(request: &Value, operation: &str, job: &IosJob) -> Result<Value, ReadError> {
    let target = required(request, "output")?.to_owned();
    match operation {
        "officeReplace" => {
            let count = replace_in_document(
                required(request, "path")?.into(),
                target,
                required(request, "find")?.into(),
                request["replacement"].as_str().unwrap_or_default().into(),
                request["caseSensitive"].as_bool().unwrap_or(false),
            )?;
            return Ok(json!({"count":count}));
        }
        "markup" => {
            let page = number(request, "page")?;
            let items = request["marks"].as_array().ok_or_else(|| invalid("add an annotation first"))?;
            if items.is_empty() || items.len() > 64 {
                return Err(invalid("invalid annotations"));
            }
            let unit = |value: &Value| value.as_f64().filter(|n| (0.0..=1.0).contains(n)).map(|n| n as f32);
            let mut marks = Vec::with_capacity(items.len());
            for item in items {
                match required(item, "kind")? {
                    "pen" => {
                        let values = item["points"].as_array().ok_or_else(|| invalid("invalid stroke"))?;
                        if values.len() < 4 || values.len() > 2_000 || values.len() % 2 != 0 {
                            return Err(invalid("invalid stroke"));
                        }
                        let points = values
                            .iter()
                            .map(|value| unit(value).ok_or_else(|| invalid("invalid stroke point")))
                            .collect::<Result<Vec<_>, _>>()?;
                        marks.push(NativePdfAnnotation::Ink {
                            page,
                            color: 0xFFBD1738,
                            width: 2.0,
                            strokes: vec![NativePdfStroke { points }],
                            style: NativePdfInkStyle::Pen,
                        });
                    }
                    "highlight" => {
                        let point = |key: &str| unit(&item[key]).ok_or_else(|| invalid("invalid highlight position"));
                        let (left, top, right, bottom) =
                            (point("left")?, point("top")?, point("right")?, point("bottom")?);
                        if left >= right || top >= bottom {
                            return Err(invalid("empty highlight"));
                        }
                        marks.push(NativePdfAnnotation::Highlight {
                            page,
                            color: 0x70FFD343,
                            boxes: vec![NativePdfBox { left, top, right, bottom }],
                        });
                    }
                    "note" => {
                        let (x, y) = (
                            unit(&item["left"]).ok_or_else(|| invalid("invalid note position"))?,
                            unit(&item["top"]).ok_or_else(|| invalid("invalid note position"))?,
                        );
                        marks.push(NativePdfAnnotation::Note {
                            page,
                            color: 0xFFFFC928,
                            x,
                            y,
                            text: required(item, "text")?.into(),
                        });
                    }
                    _ => return Err(invalid("unknown annotation")),
                }
            }
            pdf_append_path(required(request, "path")?.into(), target, marks)?;
            return Ok(json!({"count":items.len()}));
        }
        "note" => {
            let annotation = NativePdfAnnotation::Note {
                page: number(request, "page")?,
                color: 0xFFFFC928,
                x: 0.15,
                y: 0.15,
                text: required(request, "text")?.into(),
            };
            pdf_append_path(required(request, "path")?.into(), target, vec![annotation])?;
            return Ok(json!({"count":1}));
        }
        "slides" => {
            let indices = request["pages"]
                .as_array()
                .ok_or_else(|| invalid("pick slides"))?
                .iter()
                .map(|item| number(item, "page"))
                .collect::<Result<Vec<_>, _>>()?;
            let count = pptx_reorder_slides(required(request, "path")?.into(), target, indices)?;
            return Ok(json!({"count":count}));
        }
        "cellEdit" => {
            let value = request["text"].as_str().unwrap_or_default().to_owned();
            let edit = NativeCellEdit {
                sheet: required(request, "sheet")?.into(),
                row: number(request, "row")?,
                column: number(request, "column")?,
                value: NativeCellValue::Text { text: value },
            };
            let count = xlsx_apply_cell_edits(required(request, "path")?.into(), target, vec![edit])?;
            return Ok(json!({"count":count}));
        }
        "compress" => {
            let quality = match request["quality"].as_str() {
                Some("balanced") => NativeImageQuality::Balanced,
                Some("strong") => NativeImageQuality::Strong,
                _ => NativeImageQuality::Lossless,
            };
            pdf_tool_compress(
                source(request)?,
                target,
                quality,
                NativeProtectionChoice::SameAsOriginal,
                Box::new(Observer(job.token.clone())),
            )?;
        }
        "watermark" => {
            let watermark = NativeWatermark {
                text: required(request, "text")?.into(),
                font_size: 36.0,
                opacity: 0.22,
                angle_degrees: -35.0,
                color: 0x555555,
                position: NativeWatermarkPosition::Center,
                pages: None,
            };
            pdf_tool_overlay(
                source(request)?,
                NativePdfOverlay::Watermark { watermark },
                target,
                NativeProtectionChoice::SameAsOriginal,
                Box::new(Observer(job.token.clone())),
            )?;
        }
        "sign" => {
            let strokes = request["strokes"].as_array().ok_or_else(|| invalid("draw a signature first"))?;
            let strokes = strokes
                .iter()
                .filter_map(|stroke| {
                    let points: Vec<_> =
                        stroke.as_array()?.iter().filter_map(Value::as_f64).map(|x| x as f32).collect();
                    (points.len() >= 4 && points.len() % 2 == 0).then_some(NativePdfStroke { points })
                })
                .collect::<Vec<_>>();
            if strokes.is_empty() {
                return Err(invalid("draw a signature first"));
            }
            let signature = NativeSignature {
                page: number(request, "page")?,
                x: 0.60,
                y: 0.82,
                width: 0.32,
                height: 0.12,
                strokes,
                color: 0x222244,
                line_width: 2.0,
            };
            pdf_tool_overlay(
                source(request)?,
                NativePdfOverlay::Sign { signature },
                target,
                NativeProtectionChoice::SameAsOriginal,
                Box::new(Observer(job.token.clone())),
            )?;
        }
        other => {
            let inputs = if other == "merge" {
                request["inputs"]
                    .as_array()
                    .ok_or_else(|| invalid("pick PDFs first"))?
                    .iter()
                    .map(source)
                    .collect::<Result<Vec<_>, _>>()?
            } else {
                vec![source(request)?]
            };
            let op = match other {
                "merge" => NativePdfToolOp::Merge,
                "protect" => NativePdfToolOp::AddPassword { password: required(request, "newPassword")?.into() },
                "unlock" => NativePdfToolOp::RemovePassword,
                "pages" | "reorder" => {
                    let items = request["pages"].as_array().ok_or_else(|| invalid("pick PDF pages"))?;
                    if items.is_empty() || items.len() > MAX_PDF_PICKS {
                        return Err(invalid("invalid page selection"));
                    }
                    let picks = items
                        .iter()
                        .map(|item| {
                            Ok(NativePdfPageEdit {
                                page: number(item, "page")?,
                                rotation: item["rotation"]
                                    .as_i64()
                                    .and_then(|n| i32::try_from(n).ok())
                                    .unwrap_or_default(),
                            })
                        })
                        .collect::<Result<Vec<_>, ReadError>>()?;
                    if other == "pages" {
                        NativePdfToolOp::Rebuild { pages: picks }
                    } else {
                        NativePdfToolOp::Reorder { pages: picks }
                    }
                }
                _ => return Err(invalid("unknown editing operation")),
            };
            pdf_tool_run(inputs, op, target, Box::new(Observer(job.token.clone())))?;
        }
    }
    Ok(json!({"count":0}))
}

fn dispatch(request: &Value, job: &IosJob) -> Result<Value, ReadError> {
    cancelled(job)?;
    let operation = required(request, "operation")?;
    let path = || required(request, "path");
    match operation {
        "document" => read_document(path()?.into(), request["password"].as_str().map(str::to_owned), job),
        "resource" => {
            let session = open_path(
                path()?.into(),
                None,
                NativeLimits::default(),
                request["password"].as_str().map(str::to_owned),
            )?;
            job.attach(session.cancel.clone());
            let bytes = session.resource(required(request, "resource")?.into())?;
            let target = required(request, "output")?;
            std::fs::write(target, bytes).map_err(|error| ReadError::Io { detail: error.to_string() })?;
            Ok(json!({"count":0}))
        }
        "subtitleTimeline" => {
            use std::io::Read;
            let file = std::fs::File::open(path()?).map_err(|error| ReadError::Io { detail: error.to_string() })?;
            let mut text = String::new();
            file.take(document_parsers::subtitle_timeline::MAX_BYTES as u64 + 1)
                .read_to_string(&mut text).map_err(|error| ReadError::Io { detail: error.to_string() })?;
            let cues = crate::subtitle::parse_subtitle_timeline(text)?;
            Ok(json!(cues.into_iter().map(|cue| json!({
                "startMs": cue.start_ms, "endMs": cue.end_ms, "text": cue.text,
            })).collect::<Vec<_>>()))
        }
        "createArchive" => {
            use crate::archive::{NativeArchiveFormat, create_archive};
            let format = match required(request, "resource")? {
                "zip" => NativeArchiveFormat::Zip,
                "tar" => NativeArchiveFormat::Tar,
                "tar.gz" => NativeArchiveFormat::TarGzip,
                "7z" => NativeArchiveFormat::SevenZip,
                _ => return Err(invalid("Unsupported archive format")),
            };
            let sources = request["inputs"].as_array().ok_or_else(|| invalid("Pick files first"))?
                .iter().map(|input| required(input, "path").map(str::to_owned)).collect::<Result<Vec<_>, _>>()?;
            let cancel = CancelToken::default();
            job.attach(cancel.clone());
            let count = create_archive(sources, required(request, "output")?, format,
                request["password"].as_str().map(str::to_owned), &cancel, &Default::default())?;
            Ok(json!({"count":count}))
        }
        "archive" => Ok(json!(
            archive(path()?, job)?
                .entries()?
                .into_iter()
                .map(|entry| json!({
                    "path":entry.path,"size":entry.size,"directory":entry.is_dir,"protected":entry.encrypted,
                }))
                .collect::<Vec<_>>()
        )),
        "extract" => {
            let paths = request["entries"]
                .as_array()
                .ok_or_else(|| invalid("pick archive entries first"))?
                .iter()
                .map(|p| p.as_str().map(str::to_owned).ok_or_else(|| invalid("invalid archive entry")))
                .collect::<Result<Vec<_>, _>>()?;
            let snapshot = archive(path()?, job)?.extract(
                paths,
                required(request, "output")?.into(),
                request["password"].as_str().map(str::to_owned),
            )?;
            Ok(json!({"count":snapshot.entries_done}))
        }
        "search" => search(request, job),
        "passages" => passages(request, job),
        "text" => {
            let password = request["password"].as_str().map(|text| Zeroizing::new(text.to_owned()));
            Ok(json!(indexed_text(path()?, password.as_ref().map(|text| text.as_str()), job)?.0))
        }
        "pdfInfo" => {
            let info = pdf_tool_inspect(source(request)?)?;
            Ok(json!({"pageCount":info.page_count}))
        }
        "signatures" => Ok(json!(
            pdf_signatures_path(path()?.into())?
                .into_iter()
                .map(|item| json!({
                    "field":item.field,"signer":item.signer,"date":item.signed_at,"validRange":item.byte_range_ok,
                    "wholeFile":item.covers_whole_file,
                }))
                .collect::<Vec<_>>()
        )),
        _ => tool(request, operation, job),
    }
}
