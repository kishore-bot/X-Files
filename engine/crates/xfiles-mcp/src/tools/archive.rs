//! ZIP tools: list_archive and extract_archive_entry.

use std::fs::{self, File};
use std::sync::Arc;

use document_parsers::archive_index::{ArchiveIndex, ExtractProgress, safe_relative};
use schemars::JsonSchema;
use serde::Deserialize;
use serde_json::{Value, json};

use crate::error::{Result, ToolError};
use crate::output::ScratchDir;
use crate::sandbox::display;
use crate::services::Services;
use crate::validate;

const DEFAULT_LIST_LIMIT: u32 = 500;
const MAX_LIST_LIMIT: u64 = 5_000;
const MAX_ENTRY_CHARS: usize = 4_096;

#[derive(Debug, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ListArchiveArgs {
    /// ZIP archive (.zip, or an Office/OpenDocument package read as ZIP).
    pub path: String,
    /// Most entries to return, 1 to 5000. Default 500. The total count is always reported.
    pub limit: Option<u32>,
}

#[derive(Debug, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ExtractArchiveEntryArgs {
    /// ZIP archive to read.
    pub path: String,
    /// Entry path inside the archive, exactly as list_archive shows it.
    pub entry: String,
    /// New file to write. Its folder must exist inside an allowed root. Any extension is accepted.
    pub output: String,
    /// Replace the output if it already exists. Default false.
    #[serde(default)]
    pub overwrite: bool,
    /// Password for an encrypted entry.
    pub password: Option<String>,
}

pub async fn list(svc: Arc<Services>, args: ListArchiveArgs) -> Result<Value> {
    let path = svc.input(&args.path)?;
    let limit = validate::bounded(u64::from(args.limit.unwrap_or(DEFAULT_LIST_LIMIT)), 1, MAX_LIST_LIMIT, "limit")? as usize;
    let size = fs::metadata(&path)?.len();
    let shown = display(&path);

    svc.jobs
        .run(move |ctx| {
            let index = ArchiveIndex::open(Box::new(File::open(&path)?), size, ctx.cancel_token().clone())?;
            let entries = index.entries();
            let total = entries.len();
            let items: Vec<Value> = entries
                .iter()
                .take(limit)
                .map(|entry| {
                    json!({
                        "path": entry.path,
                        "size": entry.size,
                        "compressed_size": entry.compressed_size,
                        "is_dir": entry.is_dir,
                        "encrypted": entry.encrypted,
                    })
                })
                .collect();
            Ok(json!({
                "path": shown,
                "entry_count": total,
                "returned": items.len(),
                "truncated": total > limit,
                "entries": items,
            }))
        })
        .await
}

pub async fn extract(svc: Arc<Services>, args: ExtractArchiveEntryArgs) -> Result<Value> {
    let archive = svc.input(&args.path)?;
    let entry = validate::required_text(&args.entry, "entry", MAX_ENTRY_CHARS)?.to_owned();
    let output = svc.output(&args.output, args.overwrite, &[], std::slice::from_ref(&archive))?;
    let size = fs::metadata(&archive)?.len();
    let password = args.password.map(String::into_bytes);
    let shown_archive = display(&archive);

    svc.jobs
        .run(move |ctx| {
            let mut index = ArchiveIndex::open(Box::new(File::open(&archive)?), size, ctx.cancel_token().clone())?;
            let folder = output
                .parent()
                .ok_or_else(|| ToolError::invalid("the output has no folder part"))?;
            let scratch = ScratchDir::create(folder)?;
            let progress = ExtractProgress::default();
            index.extract_to_dir(std::slice::from_ref(&entry), scratch.path(), &progress, password.as_deref())?;
            ctx.check()?;

            let relative = safe_relative(&entry)
                .ok_or_else(|| ToolError::invalid("the entry name is not a safe relative path"))?;
            let produced = scratch.path().join(relative);
            if !produced.is_file() {
                return Err(ToolError::invalid(format!("{entry} is a folder, so there is no single file to extract")));
            }
            fs::rename(&produced, &output)?;
            let bytes = fs::metadata(&output)?.len();
            Ok(json!({
                "archive": shown_archive,
                "entry": entry,
                "output": display(&output),
                "bytes": bytes,
            }))
        })
        .await
}
