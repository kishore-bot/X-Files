//! Images: identified here, decoded by the platform.
//!
//! Detection has already matched the signature. The engine extracts no
//! blocks; it hands the encoded bytes to the viewer through
//! [`OpenDocument::resource`], within a size cap, so a huge file fails with a
//! typed error instead of exhausting memory.

use std::io::{Read, Seek, SeekFrom};

use document_core::{
    CancelToken, DocumentInfo, DocumentKind, EngineError, Extracted, FormatId, Input, OpenContext, OpenDocument,
    Parser, ReadSeek, Result,
};

const MAX_IMAGE_BYTES: u64 = 64 * 1024 * 1024;

pub struct ImageParser;

impl Parser for ImageParser {
    fn name(&self) -> &'static str {
        "image"
    }

    fn handles(&self, format: FormatId) -> bool {
        format == FormatId::Image
    }

    fn open(&self, input: Input, ctx: OpenContext) -> Result<Box<dyn OpenDocument>> {
        if input.byte_size > MAX_IMAGE_BYTES {
            return Err(EngineError::limit("the image is larger than 64 MiB"));
        }
        let info = DocumentInfo {
            format: FormatId::Image,
            kind: DocumentKind::Image,
            byte_size: input.byte_size,
            encoding: None,
        };
        Ok(Box::new(ImageDocument { info, reader: input.reader, cancel: ctx.cancel }))
    }
}

struct ImageDocument {
    info: DocumentInfo,
    reader: Box<dyn ReadSeek>,
    cancel: CancelToken,
}

impl OpenDocument for ImageDocument {
    fn info(&self) -> &DocumentInfo {
        &self.info
    }

    fn next_blocks(&mut self, _max_bytes: usize) -> Result<Extracted> {
        Ok(Extracted { blocks: Vec::new(), finished: true })
    }

    /// The whole encoded image; the name is ignored because there is one.
    fn resource(&mut self, _name: &str) -> Result<Vec<u8>> {
        self.cancel.check()?;
        self.reader.seek(SeekFrom::Start(0))?;
        let mut bytes = Vec::with_capacity(self.info.byte_size as usize);
        // The input can grow after open, so the cap is enforced on the read too.
        (&mut self.reader).take(MAX_IMAGE_BYTES).read_to_end(&mut bytes)?;
        Ok(bytes)
    }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use document_core::{DocumentKind, FormatId, Limits};

    #[test]
    fn serves_the_encoded_bytes() {
        let png = b"\x89PNG\r\n\x1a\nrest".to_vec();
        let mut session = crate::engine(Limits::default()).open(Cursor::new(png.clone())).unwrap();
        assert_eq!(session.info().format, FormatId::Image);
        assert_eq!(session.info().kind, DocumentKind::Image);
        assert_eq!(session.resource("image").unwrap(), png);
        assert!(session.next_batch().unwrap().is_none_or(|batch| batch.blocks.is_empty()));
        // Reading the image does not consume it.
        assert_eq!(session.resource("image").unwrap(), png);
    }
}
