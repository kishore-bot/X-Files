//! Concrete parsers and the static registry the engine selects from.
//!
//! Each format lives in its own module and depends only on `document-core`
//! and the shared infrastructure here (`zip`, `xml`, `stream`, `ooxml`);
//! format modules never use each other. A format moves to its own crate only
//! when its third-party dependencies need isolating (feature-gating, licence
//! review, or independent fuzzing).

mod cfb;
mod code;
mod csv;
mod docx;
mod docx_html;
mod dom;
mod encrypted;
mod epub;
mod epub_book;
mod image;
mod legacy_doc;
mod legacy_ppt;
mod legacy_ppt_draw;
mod legacy_xls;
mod listing;
mod markdown;
mod markdown_html;
mod numfmt;
mod odf;
mod odp;
mod odt_html;
mod ooxml;
mod package;
mod pdf;
mod pdf_annot;
mod pdf_crypt;
mod pdf_file;
mod pdf_syntax;
mod pdf_tools;
mod pdf_tools_compress;
mod pdf_tools_images;
mod pdf_tools_overlay;
mod pdf_text_layer;
mod pdf_text;
mod pdf_tools_split;
#[cfg(test)]
mod pdf_tools_ops_tests;
mod pptx;
mod readahead;
mod rich;
mod stream;
mod text;
mod xlsx;
pub mod sheet_edit;
pub mod slide_edit;
pub mod text_replace;
pub mod office_edit;
pub mod archive_index;
mod archive_crypto;
mod xml;
mod zip;

use document_core::{Engine, Limits, Parser};

pub use image::ImageParser;
pub use package::{CompoundParser, ZipParser};
pub use pdf::PdfParser;
pub use pdf_annot::{
    PdfAnnotation, PdfAppendReport, PdfEditInfo, PdfInkStyle, append_annotations, inspect_pdf, verify_appended,
};
pub use pdf_crypt::CryptAlgorithm;
pub use pdf_tools::{
    PdfDropped, PdfPagePick, PdfProtection, PdfProtectionChoice, PdfProtectionOutcome, PdfToolInfo, PdfToolObserver,
    PdfToolOp, PdfToolReport, PdfToolSource, ProtectionPlan, inspect_for_tools, inspect_protection, plan_protection,
    run_pdf_tool, verify_pdf_tool_output,
};
pub use pdf_tools_compress::{PdfCompressReport, compress_pdf, compress_pdf_with_quality};
pub use pdf_tools_images::PdfImageQuality;
pub use pdf_tools_overlay::{
    PdfOverlay, PdfOverlayReport, PdfSignature, PdfWatermark, PdfWatermarkPosition, apply_overlay, verify_overlay,
};
pub use pdf_text_layer::{TextLine, add_text_layer, verify_text_layer};
pub use pdf_text::{DocumentText, PageText, TextLimits, extract_text};
pub use pdf_tools_split::{MAX_SPLIT_PARTS, PdfPageRange, plan_split};
pub use text::TextParser;

/// Every available parser. Order matters only if two parsers handle the same
/// format, in which case the first wins.
pub static PARSERS: &[&dyn Parser] = &[&TextParser, &PdfParser, &ZipParser, &CompoundParser, &ImageParser];

/// An engine wired with every registered parser.
pub fn engine(limits: Limits) -> Engine {
    Engine::new(PARSERS, limits)
}

#[cfg(test)]
pub(crate) mod testing {
    //! Opens test documents through the full engine and renders blocks as
    //! compact strings for assertions.
    use std::io::Cursor;

    use document_core::{Block, EngineError, FormatId, Limits};

    pub fn open_zip(bytes: Vec<u8>) -> (FormatId, Vec<Block>) {
        let mut session = crate::engine(Limits::default()).open(Cursor::new(bytes)).expect("document opens");
        let format = session.info().format;
        let mut blocks = Vec::new();
        while let Some(batch) = session.next_batch().expect("batch reads") {
            blocks.extend(batch.blocks);
        }
        (format, blocks)
    }

    pub fn open_zip_err(bytes: Vec<u8>) -> EngineError {
        let mut session = match crate::engine(Limits::default()).open(Cursor::new(bytes)) {
            Ok(session) => session,
            Err(err) => return err,
        };
        loop {
            match session.next_batch() {
                Ok(Some(_)) => {}
                Ok(None) => panic!("document read without error"),
                Err(err) => return err,
            }
        }
    }

    pub fn texts(blocks: &[Block]) -> Vec<String> {
        blocks
            .iter()
            .map(|b| match b {
                Block::Heading { level, text } => format!("H{level} {}", text.text),
                Block::Paragraph { text } => format!("P {}", text.text),
                Block::ListItem { depth, marker: None, text } => format!("L{depth} {}", text.text),
                Block::ListItem { depth, marker: Some(m), text } => format!("L{depth}[{m}] {}", text.text),
                Block::Quote { text } => format!("Q {}", text.text),
                Block::Code { language: Some(l), text, .. } => format!("C[{l}] {text}"),
                Block::Code { language: None, text, .. } => format!("C {text}"),
                Block::Divider => "---".to_owned(),
                Block::TableRow { row, header, cells, styles, .. } => format!(
                    "{}{} {}{}",
                    if *header { "T" } else { "R" },
                    row.map(|r| r.to_string()).unwrap_or_default(),
                    cells.join("|"),
                    if styles.is_empty() { String::new() } else { format!(" {styles:?}") }
                ),
                Block::CellStyle { index, style } => format!("STYLE{index} {style:?}"),
                Block::Merge { first_row, first_column, last_row, last_column } => {
                    format!("MERGE {first_row},{first_column}:{last_row},{last_column}")
                }
                Block::CellRuns { row, column, spans } => format!(
                    "RUNS {row},{column} {:?}",
                    spans.iter().map(|s| (s.start, s.end, s.style, s.color)).collect::<Vec<_>>()
                ),
                Block::Count { total } => format!("COUNT {total}"),
                Block::Section { title } => format!("S {title}"),
                Block::Entry { path, is_dir: true, .. } => format!("D {path}"),
                Block::Entry { path, size, .. } => format!("E {path} {size}"),
                Block::Image { path, alt, .. } => {
                    format!("PIC {} [{}]", path.as_deref().unwrap_or("-"), alt.as_deref().unwrap_or(""))
                }
                Block::Slide { width, height, transition, .. } => {
                    format!("SLIDE {}x{} {}", *width as i32, *height as i32, transition.as_deref().unwrap_or("-"))
                }
                Block::Shape(shape) => match (&shape.image, shape.table.is_empty()) {
                    (Some(image), _) => format!("IMG {image}"),
                    (None, false) => {
                        format!("TBL {}", shape.table.iter().map(|r| r.join("|")).collect::<Vec<_>>().join(" / "))
                    }
                    (None, true) => format!(
                        "SH {}",
                        shape
                            .paragraphs
                            .iter()
                            .map(|p| match &p.bullet {
                                Some(b) => format!("{b} {}", p.text.text),
                                None => p.text.text.clone(),
                            })
                            .collect::<Vec<_>>()
                            .join(" / ")
                    ),
                },
            })
            .collect()
    }
}
