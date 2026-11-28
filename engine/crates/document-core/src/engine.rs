use std::io::{Read, Seek, SeekFrom};

use crate::detect::{SNIFF_LEN, detect};
use crate::{
    CancelToken, ContentBatch, DocumentInfo, EngineError, ErrorKind, FormatId, Input, Limits, OpenContext,
    OpenDocument, Parser, ReadSeek, Result,
};

/// Extraction coordinator: validates input, detects the format, selects a
/// parser and wraps the result in a budgeted [`Session`].
pub struct Engine {
    parsers: &'static [&'static dyn Parser],
    limits: Limits,
}

impl Engine {
    pub fn new(parsers: &'static [&'static dyn Parser], limits: Limits) -> Self {
        Self { parsers, limits }
    }

    pub fn open(&self, reader: impl ReadSeek + 'static) -> Result<Session> {
        self.open_named(reader, None)
    }

    /// Like [`Engine::open`], passing the file name as a presentation hint
    /// (see [`OpenContext::name_hint`]).
    pub fn open_named(&self, reader: impl ReadSeek + 'static, name_hint: Option<String>) -> Result<Session> {
        self.open_protected(reader, name_hint, None)
    }

    /// Like [`Engine::open_named`], with a password for encrypted documents.
    /// Ignored by documents that are not encrypted.
    pub fn open_protected(
        &self,
        reader: impl ReadSeek + 'static,
        name_hint: Option<String>,
        password: Option<&str>,
    ) -> Result<Session> {
        let mut reader: Box<dyn ReadSeek> = Box::new(reader);
        let byte_size = reader
            .seek(SeekFrom::End(0))
            .map_err(|e| EngineError::new(ErrorKind::Io, format!("input is not seekable: {e}")))?;
        if byte_size > self.limits.max_input_bytes {
            return Err(EngineError::limit(format!(
                "file is {byte_size} bytes; the limit is {} bytes",
                self.limits.max_input_bytes
            )));
        }

        reader.seek(SeekFrom::Start(0))?;
        let mut head = Vec::with_capacity(SNIFF_LEN);
        reader.by_ref().take(SNIFF_LEN as u64).read_to_end(&mut head)?;
        reader.seek(SeekFrom::Start(0))?;

        let format = detect(&head, head.len() as u64 == byte_size);
        let parser = self.parser_for(format)?;
        let cancel = CancelToken::default();
        let ctx = OpenContext { limits: self.limits, cancel: cancel.clone(), password, name_hint };
        let document = parser.open(Input { reader, byte_size }, ctx)?;
        Ok(Session { document, cancel, limits: self.limits, emitted: 0, sequence: 0, done: false })
    }

    fn parser_for(&self, format: FormatId) -> Result<&'static dyn Parser> {
        if format == FormatId::Unknown {
            return Err(EngineError::unsupported("the file content is not a recognised document format"));
        }
        self.parsers
            .iter()
            .copied()
            .find(|p| p.handles(format))
            .ok_or_else(|| EngineError::unsupported(format!("{} documents are not supported yet", format.label())))
    }
}

/// One open document. Enforces the session output budget, numbers batches and
/// stops yielding after the last one.
pub struct Session {
    document: Box<dyn OpenDocument>,
    cancel: CancelToken,
    limits: Limits,
    emitted: u64,
    sequence: u32,
    done: bool,
}

impl Session {
    pub fn info(&self) -> &DocumentInfo {
        self.document.info()
    }

    /// A token that cancels this session from another thread.
    pub fn cancel_token(&self) -> CancelToken {
        self.cancel.clone()
    }

    /// An embedded resource named by a block, such as a slide picture.
    pub fn resource(&mut self, name: &str) -> Result<Vec<u8>> {
        self.cancel.check()?;
        self.document.resource(name)
    }

    /// Returns `Ok(None)` once the final batch has been delivered.
    pub fn next_batch(&mut self) -> Result<Option<ContentBatch>> {
        if self.done {
            return Ok(None);
        }
        self.cancel.check()?;

        let remaining = self.limits.max_total_output_bytes.saturating_sub(self.emitted);
        let batch_bytes = self.limits.max_batch_bytes.max(Limits::MIN_BATCH_BYTES);
        let budget = usize::try_from(remaining).unwrap_or(usize::MAX).min(batch_bytes);
        let truncated = budget < Limits::MIN_BATCH_BYTES;
        let extracted = if truncated {
            crate::Extracted { blocks: Vec::new(), finished: true }
        } else {
            self.document.next_blocks(budget)?
        };

        self.emitted += extracted.blocks.iter().map(|b| b.cost() as u64).sum::<u64>();
        self.done = extracted.finished || truncated;
        let batch = ContentBatch { sequence: self.sequence, blocks: extracted.blocks, is_last: self.done, truncated };
        self.sequence += 1;
        Ok(Some(batch))
    }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;
    use crate::{Block, DocumentKind, Extracted};

    /// Fills every batch completely and never finishes, so only the session
    /// budget can end it.
    struct Endless;
    struct EndlessDoc(DocumentInfo);

    impl Parser for Endless {
        fn name(&self) -> &'static str {
            "endless"
        }
        fn handles(&self, format: FormatId) -> bool {
            format == FormatId::PlainText
        }
        fn open(&self, input: Input, _ctx: OpenContext) -> Result<Box<dyn OpenDocument>> {
            let info = DocumentInfo {
                format: FormatId::PlainText,
                kind: DocumentKind::Text,
                byte_size: input.byte_size,
                encoding: None,
            };
            Ok(Box::new(EndlessDoc(info)))
        }
    }

    impl OpenDocument for EndlessDoc {
        fn info(&self) -> &DocumentInfo {
            &self.0
        }
        fn next_blocks(&mut self, max_bytes: usize) -> Result<Extracted> {
            let text = "x".repeat(max_bytes - 1);
            Ok(Extracted { blocks: vec![Block::paragraph(text)], finished: false })
        }
    }

    static PARSERS: &[&dyn Parser] = &[&Endless];

    fn engine(limits: Limits) -> Engine {
        Engine::new(PARSERS, limits)
    }

    #[test]
    fn rejects_unknown_and_unregistered_formats() {
        let err = engine(Limits::default()).open(Cursor::new(vec![0u8; 16])).err().unwrap();
        assert_eq!(err.kind, ErrorKind::UnsupportedFormat);
        let err = engine(Limits::default()).open(Cursor::new(b"%PDF-1.4".to_vec())).err().unwrap();
        assert_eq!(err.kind, ErrorKind::UnsupportedFormat);
        assert_eq!(err.message, "PDF documents are not supported yet");
    }

    #[test]
    fn rejects_oversized_input_before_parsing() {
        let limits = Limits { max_input_bytes: 4, ..Limits::default() };
        let err = engine(limits).open(Cursor::new(b"hello".to_vec())).err().unwrap();
        assert_eq!(err.kind, ErrorKind::LimitExceeded);
    }

    #[test]
    fn session_budget_truncates_and_ends() {
        let limits = Limits { max_batch_bytes: 10, max_total_output_bytes: 25, ..Limits::default() };
        let mut session = engine(limits).open(Cursor::new(b"text".to_vec())).unwrap();
        let mut total = 0;
        let mut last = None;
        while let Some(batch) = session.next_batch().unwrap() {
            total += batch.blocks.iter().map(Block::cost).sum::<usize>();
            last = Some(batch);
        }
        let last = last.unwrap();
        assert!(last.is_last && last.truncated);
        assert!(total <= 25, "emitted {total}");
        assert_eq!(session.next_batch().unwrap(), None);
    }

    #[test]
    fn cancellation_is_observed_between_batches() {
        let mut session = engine(Limits::default()).open(Cursor::new(b"text".to_vec())).unwrap();
        session.next_batch().unwrap();
        session.cancel_token().cancel();
        assert_eq!(session.next_batch().err().unwrap().kind, ErrorKind::Cancelled);
    }
}
