//! PDF: validated here, rendered by the platform (ADR-009).
//!
//! The engine checks the header and version so damaged or mislabelled files
//! fail with a typed error before reaching a renderer. It extracts no blocks:
//! pages are drawn by Android's `PdfRenderer` and iOS's PDFKit.

use std::io::Read;

use document_core::{
    DocumentInfo, DocumentKind, EngineError, Extracted, FormatId, Input, OpenContext, OpenDocument, Parser, Result,
};

pub struct PdfParser;

impl Parser for PdfParser {
    fn name(&self) -> &'static str {
        "pdf"
    }

    fn handles(&self, format: FormatId) -> bool {
        format == FormatId::Pdf
    }

    fn open(&self, mut input: Input, _ctx: OpenContext) -> Result<Box<dyn OpenDocument>> {
        let mut header = [0u8; 8];
        input.reader.read_exact(&mut header).map_err(|_| EngineError::malformed("the PDF header is damaged"))?;
        if !matches!(header, [b'%', b'P', b'D', b'F', b'-', b'1' | b'2', b'.', b'0'..=b'9']) {
            return Err(EngineError::malformed("the PDF header is damaged"));
        }
        let info =
            DocumentInfo { format: FormatId::Pdf, kind: DocumentKind::Pdf, byte_size: input.byte_size, encoding: None };
        Ok(Box::new(PdfDocument(info)))
    }
}

struct PdfDocument(DocumentInfo);

impl OpenDocument for PdfDocument {
    fn info(&self) -> &DocumentInfo {
        &self.0
    }

    fn next_blocks(&mut self, _max_bytes: usize) -> Result<Extracted> {
        Ok(Extracted { blocks: Vec::new(), finished: true })
    }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use document_core::{DocumentKind, ErrorKind, Limits};

    #[test]
    fn validates_header() {
        let session =
            crate::engine(Limits::default()).open(Cursor::new(b"%PDF-1.7\n%\xE2\xE3\n1 0 obj".to_vec())).unwrap();
        assert_eq!(session.info().kind, DocumentKind::Pdf);
        let err = crate::engine(Limits::default()).open(Cursor::new(b"%PDF-x".to_vec())).err().unwrap();
        assert_eq!(err.kind, ErrorKind::Malformed);
    }
}
