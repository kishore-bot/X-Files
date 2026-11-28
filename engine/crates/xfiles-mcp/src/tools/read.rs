//! Reading tools: read_document, search_document and glance.

use std::sync::Arc;

use schemars::JsonSchema;
use serde::Deserialize;
use serde_json::{Value, json};

use crate::document;
use crate::error::Result;
use crate::sandbox::display;
use crate::services::Services;
use crate::text;
use crate::validate;

const DEFAULT_MAX_CHARS: u32 = 20_000;
const MAX_CHARS_LIMIT: u64 = 200_000;
const DEFAULT_MAX_MATCHES: u32 = 50;
const MAX_MATCHES_LIMIT: u64 = 500;
const DEFAULT_CONTEXT_CHARS: u32 = 80;
const MAX_CONTEXT_CHARS: u64 = 400;
const MAX_QUERY_CHARS: usize = 500;

#[derive(Debug, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ReadDocumentArgs {
    /// Document to read: PDF, DOCX, PPTX, XLSX, ODT, ODP, ODS, EPUB, TXT, MD, CSV, HTML, RTF and ZIP
    /// listings are supported. Relative paths resolve against the first --root.
    pub path: String,
    /// Password for an encrypted Office or OpenDocument file. Omit for unencrypted files.
    pub password: Option<String>,
    /// Character offset to start from, counted in the extracted text from 0. Default 0.
    pub start_char: Option<u64>,
    /// Characters to return, 1 to 200000. Default 20000. Use next_start_char to read on.
    pub max_chars: Option<u32>,
    /// First page to read, 1-based. Pages are PDF pages, slides (presentations), sheets
    /// (spreadsheets) or sections. A flowing document counts as one page.
    pub from_page: Option<u32>,
    /// Last page to read, 1-based and inclusive. Defaults to the last page.
    pub to_page: Option<u32>,
}

#[derive(Debug, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct SearchDocumentArgs {
    /// Document to search, in the same formats as read_document.
    pub path: String,
    /// Password for an encrypted Office or OpenDocument file.
    pub password: Option<String>,
    /// Text to find. Matching ignores letter case.
    #[schemars(length(min = 1, max = 500))]
    pub query: String,
    /// Most matches to return, 1 to 500. Default 50. The total count is reported either way.
    pub max_matches: Option<u32>,
    /// Characters of context shown on each side of a match, 0 to 400. Default 80.
    pub context_chars: Option<u32>,
}

#[derive(Debug, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GlanceArgs {
    /// Document to summarise, in the same formats as read_document.
    pub path: String,
    /// Password for an encrypted Office or OpenDocument file.
    pub password: Option<String>,
}

pub async fn read_document(svc: Arc<Services>, args: ReadDocumentArgs) -> Result<Value> {
    let path = svc.input(&args.path)?;
    let max_chars = validate::bounded(u64::from(args.max_chars.unwrap_or(DEFAULT_MAX_CHARS)), 1, MAX_CHARS_LIMIT, "max_chars")?;
    let start = usize::try_from(args.start_char.unwrap_or(0)).unwrap_or(usize::MAX);
    let (from, to) = (args.from_page, args.to_page);
    let password = args.password;
    let max_input = svc.max_input_bytes;
    let shown = display(&path);

    svc.jobs
        .run(move |ctx| {
            let loaded = document::load(&ctx, &path, password.as_deref(), max_input)?;
            let rendered = text::render(&loaded.sections, loaded.section_word(), from, to)?;
            let window = text::window(&rendered, start, max_chars as usize);
            Ok(json!({
                "path": shown,
                "format": loaded.format,
                "kind": loaded.kind_name(),
                "page_count": loaded.sections.len(),
                "total_chars": window.total_chars,
                "start_char": window.start_char,
                "returned_chars": window.returned_chars,
                "next_start_char": window.next_start_char,
                "engine_truncated": loaded.truncated,
                "text": window.text,
            }))
        })
        .await
}

pub async fn search_document(svc: Arc<Services>, args: SearchDocumentArgs) -> Result<Value> {
    let path = svc.input(&args.path)?;
    let query = validate::required_text(&args.query, "query", MAX_QUERY_CHARS)?.to_owned();
    let max_matches = validate::bounded(u64::from(args.max_matches.unwrap_or(DEFAULT_MAX_MATCHES)), 1, MAX_MATCHES_LIMIT, "max_matches")?;
    let context = validate::bounded(
        u64::from(args.context_chars.unwrap_or(DEFAULT_CONTEXT_CHARS)),
        0,
        MAX_CONTEXT_CHARS,
        "context_chars",
    )?;
    let password = args.password;
    let max_input = svc.max_input_bytes;
    let shown = display(&path);

    svc.jobs
        .run(move |ctx| {
            let loaded = document::load(&ctx, &path, password.as_deref(), max_input)?;
            let word = loaded.section_word();
            let outcome = text::search(&loaded.sections, &query, max_matches as usize, context as usize);
            let matches: Vec<Value> = outcome
                .hits
                .iter()
                .map(|hit| {
                    json!({
                        "page": hit.section,
                        "page_title": hit.title,
                        "char_offset": hit.char_offset,
                        "snippet": hit.snippet,
                    })
                })
                .collect();
            Ok(json!({
                "path": shown,
                "query": query,
                "page_word": word,
                "total_matches": outcome.total_matches,
                "truncated": outcome.truncated,
                "returned_matches": matches.len(),
                "matches": matches,
            }))
        })
        .await
}

pub async fn glance(svc: Arc<Services>, args: GlanceArgs) -> Result<Value> {
    let path = svc.input(&args.path)?;
    let password = args.password;
    let max_input = svc.max_input_bytes;
    let shown = display(&path);

    svc.jobs
        .run(move |ctx| {
            let loaded = document::load(&ctx, &path, password.as_deref(), max_input)?;
            let mut summary = text::glance_summary(&loaded.sections);
            if let Value::Object(map) = &mut summary {
                map.insert("path".into(), json!(shown));
                map.insert("format".into(), json!(loaded.format));
                map.insert("kind".into(), json!(loaded.kind_name()));
            }
            Ok(summary)
        })
        .await
}
