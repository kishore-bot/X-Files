//! Opens a document through the engine and turns its blocks into plain-text sections.
//! A section is a slide, a sheet or a chapter; flowing documents have at most one.

use std::fs::File;
use std::path::Path;

use document_core::{Block, DocumentKind, Limits};
use document_parsers::TextLimits;

use crate::error::Result;
use crate::jobs::JobContext;

const MAX_PDF_PAGES: usize = 5_000;
const MAX_PDF_CHARS: usize = 8_000_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Section {
    pub title: Option<String>,
    pub text: String,
}

pub struct Loaded {
    pub format: &'static str,
    pub kind: DocumentKind,
    pub sections: Vec<Section>,
    /// The engine stopped early because its output budget ran out.
    pub truncated: bool,
}

impl Loaded {
    pub fn kind_name(&self) -> &'static str {
        match self.kind {
            DocumentKind::Text => "text",
            DocumentKind::Spreadsheet => "spreadsheet",
            DocumentKind::Presentation => "presentation",
            DocumentKind::Pdf => "pdf",
            DocumentKind::Archive => "archive",
            DocumentKind::Image => "image",
        }
    }

    /// What a section is called for this document: "Slide", "Sheet" or "Section".
    pub fn section_word(&self) -> &'static str {
        match self.kind {
            DocumentKind::Spreadsheet => "Sheet",
            DocumentKind::Presentation => "Slide",
            DocumentKind::Pdf => "Page",
            _ => "Section",
        }
    }
}

/// PDF text comes from the engine's content-stream walker, one section per page.
fn load_pdf(ctx: &JobContext, path: &Path, password: Option<&str>, format: &'static str) -> Result<Loaded> {
    let file = File::open(path)?;
    let limits = TextLimits { max_pages: MAX_PDF_PAGES, max_chars: MAX_PDF_CHARS };
    let text = document_parsers::extract_text(file, password, limits, ctx.cancel_token())?;
    let sections = text.pages.into_iter().map(|page| Section { title: None, text: page.text }).collect();
    Ok(Loaded { format, kind: DocumentKind::Pdf, sections, truncated: text.truncated })
}

pub fn load(ctx: &JobContext, path: &Path, password: Option<&str>, max_input_bytes: u64) -> Result<Loaded> {
    let file = File::open(path)?;
    let name = path.file_name().map(|name| name.to_string_lossy().into_owned());
    let limits = Limits { max_input_bytes, ..Limits::default() };
    let mut session = document_parsers::engine(limits).open_protected(file, name, password)?;
    let kind = session.info().kind;
    let format = session.info().format.label();
    if kind == DocumentKind::Pdf {
        drop(session);
        return load_pdf(ctx, path, password, format);
    }

    let mut builder = SectionBuilder::default();
    let mut truncated = false;
    while let Some(batch) = session.next_batch()? {
        ctx.check()?;
        for block in batch.blocks {
            builder.push(block);
        }
        if batch.truncated {
            truncated = true;
            break;
        }
    }
    Ok(Loaded { format, kind, sections: builder.finish(), truncated })
}

#[derive(Default)]
struct SectionBuilder {
    done: Vec<Section>,
    current: Option<Section>,
}

impl SectionBuilder {
    fn push(&mut self, block: Block) {
        match block {
            Block::Section { title } => {
                self.close();
                self.current = Some(Section { title: Some(title), text: String::new() });
            }
            Block::Image { alt, .. } => {
                if let Some(alt) = alt.filter(|alt| !alt.trim().is_empty()) {
                    self.line(&format!("[image: {alt}]"));
                }
            }
            other => {
                if let Some(text) = block_text(&other) {
                    self.line(&text);
                }
            }
        }
    }

    fn line(&mut self, text: &str) {
        let section = self.current.get_or_insert_with(|| Section { title: None, text: String::new() });
        if !section.text.is_empty() {
            section.text.push('\n');
        }
        section.text.push_str(text);
    }

    fn close(&mut self) {
        if let Some(section) = self.current.take() {
            self.done.push(section);
        }
    }

    fn finish(mut self) -> Vec<Section> {
        self.close();
        self.done
    }
}

/// The readable text of one block, or `None` when it carries no text.
fn block_text(block: &Block) -> Option<String> {
    let text = match block {
        Block::Heading { text, .. } | Block::Paragraph { text } | Block::Quote { text } => text.text.clone(),
        Block::ListItem { depth, marker, text } => {
            format!("{}{} {}", "  ".repeat(usize::from(*depth)), marker.as_deref().unwrap_or("-"), text.text)
        }
        Block::Code { text, .. } => text.clone(),
        Block::TableRow { cells, .. } => {
            if cells.iter().all(|cell| cell.trim().is_empty()) {
                return None;
            }
            cells.join(" | ")
        }
        Block::Entry { path, size, is_dir } => {
            if *is_dir {
                format!("{path}/")
            } else {
                format!("{path} ({size} bytes)")
            }
        }
        Block::Shape(shape) => {
            if !shape.table.is_empty() {
                shape.table.iter().map(|row| row.join(" | ")).collect::<Vec<_>>().join("\n")
            } else {
                shape
                    .paragraphs
                    .iter()
                    .filter(|p| !p.text.text.trim().is_empty())
                    .map(|p| match &p.bullet {
                        Some(bullet) => format!("{bullet} {}", p.text.text),
                        None => p.text.text.clone(),
                    })
                    .collect::<Vec<_>>()
                    .join("\n")
            }
        }
        _ => return None,
    };
    if text.trim().is_empty() { None } else { Some(text) }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::jobs::Jobs;
    use std::time::Duration;

    /// A one-page PDF with a correct cross-reference table, showing `text` in Helvetica.
    fn pdf_with_text(text: &str) -> Vec<u8> {
        let content = format!("BT /F1 12 Tf 72 700 Td ({text}) Tj ET");
        let bodies = [
            "<</Type /Catalog /Pages 2 0 R>>".to_owned(),
            "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 300 400] /Resources <</Font <</F1 4 0 R>>>> >>".to_owned(),
            "<</Type /Page /Parent 2 0 R /Contents 5 0 R>>".to_owned(),
            "<</Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding>>".to_owned(),
            format!("<</Length {}>>\nstream\n{content}\nendstream", content.len()),
        ];
        let mut out = b"%PDF-1.7\n".to_vec();
        let mut offsets = Vec::new();
        for (index, body) in bodies.iter().enumerate() {
            offsets.push(out.len());
            out.extend_from_slice(format!("{} 0 obj\n{body}\nendobj\n", index + 1).as_bytes());
        }
        let xref_at = out.len();
        out.extend_from_slice(format!("xref\n0 {}\n0000000000 65535 f \n", bodies.len() + 1).as_bytes());
        for offset in offsets {
            out.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
        }
        out.extend_from_slice(format!("trailer\n<</Size {} /Root 1 0 R>>\nstartxref\n{xref_at}\n%%EOF\n", bodies.len() + 1).as_bytes());
        out
    }

    #[tokio::test]
    async fn pdf_pages_load_as_sections_with_their_text() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("invoice.pdf");
        std::fs::write(&path, pdf_with_text("Quarterly invoice")).unwrap();
        let jobs = Jobs::new(Duration::from_secs(30), 1);
        let loaded = jobs
            .run(move |ctx| load(&ctx, &path, None, 64 * 1024 * 1024))
            .await
            .expect("pdf loads");
        assert_eq!(loaded.kind_name(), "pdf");
        assert_eq!(loaded.section_word(), "Page");
        assert_eq!(loaded.sections.len(), 1);
        assert_eq!(loaded.sections[0].text, "Quarterly invoice");
    }
}
