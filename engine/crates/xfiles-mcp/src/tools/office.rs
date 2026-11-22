//! office_find_replace: preview or apply a text replacement in DOCX, PPTX, ODT, ODP and ODS files.

use std::fs::{self, File};
use std::sync::Arc;

use document_parsers::office_edit::{self, IndexedMatch, Layout, OfficeQuery};
use schemars::JsonSchema;
use serde::Deserialize;
use serde_json::{Value, json};

use crate::error::{Result, ToolError};
use crate::output::PendingOutput;
use crate::sandbox::display;
use crate::services::Services;
use crate::validate;

const SUPPORTED: [&str; 5] = ["docx", "pptx", "odt", "odp", "ods"];
const PREVIEW_LIMIT: usize = 10_000;
const SAMPLE_LIMIT: usize = 25;
const MAX_FIND_CHARS: usize = 1_000;

#[derive(Debug, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct OfficeFindReplaceArgs {
    /// Document to search: .docx, .pptx, .odt, .odp or .ods.
    pub path: String,
    /// Text to find, 1 to 1000 characters.
    pub find: String,
    /// Replacement text. Required when apply is true. May be empty to delete matches.
    pub replace: Option<String>,
    /// false (default): only count matches and show samples, writing nothing.
    /// true: write a new file with the replacements applied.
    #[serde(default)]
    pub apply: bool,
    /// New file to write when apply is true. It must have the same extension as the input.
    pub output: Option<String>,
    /// Match letter case exactly. Default false.
    #[serde(default)]
    pub case_sensitive: bool,
    /// Match only whole words. Default false.
    #[serde(default)]
    pub whole_word: bool,
    /// Also match inside numeric spreadsheet cells. Default false.
    #[serde(default)]
    pub include_numbers: bool,
    /// Replace the output if it already exists. Default false.
    #[serde(default)]
    pub overwrite: bool,
}

pub async fn find_replace(svc: Arc<Services>, args: OfficeFindReplaceArgs) -> Result<Value> {
    let input = svc.input(&args.path)?;
    let extension = input
        .extension()
        .and_then(|ext| ext.to_str())
        .map(str::to_ascii_lowercase)
        .unwrap_or_default();
    if !SUPPORTED.contains(&extension.as_str()) {
        return Err(ToolError::invalid("office_find_replace supports .docx, .pptx, .odt, .odp and .ods files"));
    }
    let find = validate::required_text(&args.find, "find", MAX_FIND_CHARS)?.to_owned();
    if args.apply && args.replace.is_none() {
        return Err(ToolError::invalid("replace is required when apply is true (use an empty string to delete)"));
    }
    let replace = args.replace.clone().unwrap_or_default();
    let output = if args.apply {
        let raw = args.output.as_deref().ok_or_else(|| ToolError::invalid("output is required when apply is true"))?;
        Some(svc.output(raw, args.overwrite, &[extension.as_str()], std::slice::from_ref(&input))?)
    } else {
        None
    };
    let query = OfficeQuery {
        find,
        case_sensitive: args.case_sensitive,
        whole_word: args.whole_word,
        include_number_cells: args.include_numbers,
    };
    let size = fs::metadata(&input)?.len();
    let (apply, overwrite, shown) = (args.apply, args.overwrite, display(&input));

    svc.jobs
        .run(move |ctx| {
            let cancel = ctx.cancel_token();
            let layout = office_edit::inspect(File::open(&input)?, size, cancel)?;
            let scan = office_edit::scan_parts(
                File::open(&input)?,
                size,
                &query,
                &layout,
                0..layout.parts.len(),
                PREVIEW_LIMIT,
                cancel,
            )?;

            let Some(target) = output.filter(|_| apply) else {
                return Ok(json!({
                    "path": shown,
                    "applied": false,
                    "matches_found": scan.matches.len(),
                    "truncated": scan.truncated,
                    "samples": samples(&layout, &scan.matches),
                    "note": "Nothing was written. Set apply to true with an output path to write a copy.",
                }));
            };

            let mut pending = PendingOutput::create(target, overwrite)?;
            let report = office_edit::replace_matches(
                File::open(&input)?,
                size,
                pending.file()?,
                &query,
                &replace,
                None,
                cancel,
            )?;
            office_edit::verify_reopens(File::open(pending.temp_path())?, cancel)?;
            let path = pending.commit()?;
            Ok(json!({
                "path": shown,
                "applied": true,
                "output": display(&path),
                "total_matches": report.total,
                "replaced": report.replaced,
            }))
        })
        .await
}

fn samples(layout: &Layout, matches: &[IndexedMatch]) -> Vec<Value> {
    matches
        .iter()
        .take(SAMPLE_LIMIT)
        .map(|found| json!({ "part": layout.parts.get(found.part as usize), "snippet": found.snippet }))
        .collect()
}
