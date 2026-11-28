//! MCP surface: registers each tool with its description and schema, and shapes results.
//! Failures become tool results with `isError` set and a readable message, never protocol errors.

use std::sync::Arc;

use rmcp::handler::server::router::tool::ToolRouter;
use rmcp::handler::server::wrapper::Parameters;
use rmcp::model::{CallToolResult, ContentBlock, Implementation, InitializeResult, ServerCapabilities, ServerConfig};
use rmcp::{ServerHandler, tool, tool_handler, tool_router};
use serde_json::{Value, json};

use crate::error::Result;
use crate::services::Services;
use crate::tools::archive::{ExtractArchiveEntryArgs, ListArchiveArgs};
use crate::tools::office::OfficeFindReplaceArgs;
use crate::tools::pdf::{
    PdfCompressArgs, PdfMergeArgs, PdfProtectArgs, PdfReorderArgs, PdfRotateArgs, PdfSplitArgs, PdfWatermarkArgs,
};
use crate::tools::read::{GlanceArgs, ReadDocumentArgs, SearchDocumentArgs};
use crate::tools::{archive, office, pdf, read};

const INSTRUCTIONS: &str = "Xfiles document tools. Read, search and summarise documents including PDFs, merge, split, compress, reorder, rotate, watermark or protect PDFs, find and \
replace text in Office and OpenDocument files, and list or extract ZIP entries. Inputs are read only. \
Every change writes a new file at an output path you give; existing files are only replaced with \
overwrite set to true. Only paths under the server's allowed folders are accessible.";

#[derive(Clone)]
pub struct XfilesServer {
    services: Arc<Services>,
    tool_router: ToolRouter<Self>,
}

#[tool_router]
impl XfilesServer {
    pub fn new(services: Services) -> Self {
        Self { services: Arc::new(services), tool_router: Self::tool_router() }
    }

    #[tool(
        description = "Read the text of a document (DOCX, PPTX, XLSX, ODT, ODP, ODS, EPUB, TXT, MD, CSV, HTML, RTF or ZIP listing). Returns a window of text from start_char, up to max_chars, with next_start_char for paging. Optional from_page/to_page select PDF pages, slides, sheets or sections.",
        annotations(read_only_hint = true)
    )]
    async fn read_document(&self, Parameters(args): Parameters<ReadDocumentArgs>) -> CallToolResult {
        reply(read::read_document(self.services.clone(), args).await)
    }

    #[tool(
        description = "Search a document for text (case-insensitive). Returns the total match count and up to max_matches hits with page (slide, sheet or section number), char_offset and a snippet of context.",
        annotations(read_only_hint = true)
    )]
    async fn search_document(&self, Parameters(args): Parameters<SearchDocumentArgs>) -> CallToolResult {
        reply(read::search_document(self.services.clone(), args).await)
    }

    #[tool(
        description = "Summarise a document offline: key sentences, keywords, detected dates, amounts, emails, phone numbers, links and reference numbers, plus word count, reading time and language.",
        annotations(read_only_hint = true)
    )]
    async fn glance(&self, Parameters(args): Parameters<GlanceArgs>) -> CallToolResult {
        reply(read::glance(self.services.clone(), args).await)
    }

    #[tool(
        description = "Merge two to fifty PDFs, in order, into a new PDF. Reports any bookmarks or form fields that could not be carried over.",
        annotations(read_only_hint = false)
    )]
    async fn pdf_merge(&self, Parameters(args): Parameters<PdfMergeArgs>) -> CallToolResult {
        reply(pdf::merge(self.services.clone(), args).await)
    }

    #[tool(
        description = "Split a PDF into several new PDFs written to an existing folder. Modes: ranges (inclusive page ranges such as \"1-3,5\"), every_n (pages per part) or per_page. Parts are named prefix-01.pdf and so on.",
        annotations(read_only_hint = false)
    )]
    async fn pdf_split(&self, Parameters(args): Parameters<PdfSplitArgs>) -> CallToolResult {
        reply(pdf::split(self.services.clone(), args).await)
    }

    #[tool(
        description = "Compress a PDF into a new file. quality: lossless (structure only), balanced (images about 150 dpi) or strong (images about 110 dpi, smallest). Reports bytes before and after.",
        annotations(read_only_hint = false)
    )]
    async fn pdf_compress(&self, Parameters(args): Parameters<PdfCompressArgs>) -> CallToolResult {
        reply(pdf::compress(self.services.clone(), args).await)
    }

    #[tool(
        description = "Reorder the pages of a PDF into a new file. order must list every page exactly once, as 1-based source page numbers in the new sequence; each entry may also turn the page.",
        annotations(read_only_hint = false)
    )]
    async fn pdf_reorder(&self, Parameters(args): Parameters<PdfReorderArgs>) -> CallToolResult {
        reply(pdf::reorder(self.services.clone(), args).await)
    }

    #[tool(
        description = "Rotate selected pages of a PDF into a new file. Each rotation names a 1-based page and clockwise degrees (multiples of 90). Pages not listed keep their orientation.",
        annotations(read_only_hint = false)
    )]
    async fn pdf_rotate(&self, Parameters(args): Parameters<PdfRotateArgs>) -> CallToolResult {
        reply(pdf::rotate(self.services.clone(), args).await)
    }

    #[tool(
        description = "Stamp a text watermark onto PDF pages (all pages, or the 1-based pages given) into a new file. Set font size, opacity, angle, colour and position (center, corners, or tiled).",
        annotations(read_only_hint = false)
    )]
    async fn pdf_watermark(&self, Parameters(args): Parameters<PdfWatermarkArgs>) -> CallToolResult {
        reply(pdf::watermark(self.services.clone(), args).await)
    }

    #[tool(
        description = "Protect a PDF with a user password (AES-256) into a new file. The input must not already be encrypted. The password is never echoed back.",
        annotations(read_only_hint = false)
    )]
    async fn pdf_protect(&self, Parameters(args): Parameters<PdfProtectArgs>) -> CallToolResult {
        reply(pdf::protect(self.services.clone(), args).await)
    }

    #[tool(
        description = "Find and replace text in a DOCX, PPTX, ODT, ODP or ODS file. With apply false (default) it only counts matches and returns samples. With apply true it writes a new file with replacements applied, then re-opens it to check it is valid.",
        annotations(read_only_hint = false)
    )]
    async fn office_find_replace(&self, Parameters(args): Parameters<OfficeFindReplaceArgs>) -> CallToolResult {
        reply(office::find_replace(self.services.clone(), args).await)
    }

    #[tool(
        description = "List the entries of a ZIP archive: path, sizes, whether each is a folder, and whether it is encrypted.",
        annotations(read_only_hint = true)
    )]
    async fn list_archive(&self, Parameters(args): Parameters<ListArchiveArgs>) -> CallToolResult {
        reply(archive::list(self.services.clone(), args).await)
    }

    #[tool(
        description = "Extract one file from a ZIP archive into a new file. The entry path must match list_archive exactly. Folders cannot be extracted this way.",
        annotations(read_only_hint = false)
    )]
    async fn extract_archive_entry(&self, Parameters(args): Parameters<ExtractArchiveEntryArgs>) -> CallToolResult {
        reply(archive::extract(self.services.clone(), args).await)
    }
}

#[tool_handler(router = self.tool_router)]
impl ServerHandler for XfilesServer {
    fn get_info(&self) -> ServerConfig {
        InitializeResult::new(ServerCapabilities::builder().enable_tools().build())
            .with_server_info(Implementation::new("xfiles-mcp", env!("CARGO_PKG_VERSION")))
            .with_instructions(INSTRUCTIONS)
    }
}

/// Turns a tool outcome into an MCP result. Errors keep a readable text block and `isError`.
fn reply(outcome: Result<Value>) -> CallToolResult {
    match outcome {
        Ok(value) => CallToolResult::structured(value),
        Err(err) => {
            let message = err.to_string();
            let mut result = CallToolResult::structured_error(json!({ "error": message }));
            result.content = vec![ContentBlock::text(message)];
            result
        }
    }
}
