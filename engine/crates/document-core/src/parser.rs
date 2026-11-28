use std::io::{Read, Seek};

use crate::{Block, CancelToken, DocumentInfo, FormatId, Limits, Result};

pub trait ReadSeek: Read + Seek + Send {}
impl<T: Read + Seek + Send> ReadSeek for T {}

/// A validated, seekable input positioned at offset 0.
pub struct Input {
    pub reader: Box<dyn ReadSeek>,
    pub byte_size: u64,
}

pub struct OpenContext<'a> {
    pub limits: Limits,
    pub cancel: CancelToken,
    /// What the user typed to unlock an encrypted document. Borrowed so that
    /// no copy outlives the open call; never log or store it.
    pub password: Option<&'a str>,
    /// The file's display name, if known. Parsers may use its extension only
    /// to choose how to present content already identified by its bytes
    /// (e.g. plain text shown as Markdown or CSV), never to decide what the
    /// bytes are.
    pub name_hint: Option<String>,
}

/// A parser for one family of formats. Implementations are stateless and
/// registered statically; per-document state lives in [`OpenDocument`].
pub trait Parser: Sync {
    fn name(&self) -> &'static str;

    /// Whether this parser handles a detected format.
    fn handles(&self, format: FormatId) -> bool;

    /// Validates the input and prepares incremental extraction. Must not
    /// extract the whole document up front.
    fn open(&self, input: Input, ctx: OpenContext) -> Result<Box<dyn OpenDocument>>;
}

/// Output of one [`OpenDocument::next_blocks`] call.
pub struct Extracted {
    pub blocks: Vec<Block>,
    /// The document has no more content.
    pub finished: bool,
}

pub trait OpenDocument: Send {
    fn info(&self) -> &DocumentInfo;

    /// Extracts the next blocks, whose total [`Block::cost`] must not exceed
    /// `max_bytes` (which is at least [`Limits::MIN_BATCH_BYTES`]). Session
    /// budgets, sequencing and cancellation between calls are handled by the
    /// coordinator.
    fn next_blocks(&mut self, max_bytes: usize) -> Result<Extracted>;

    /// Bytes of an embedded resource referenced by a block (e.g. a slide
    /// picture's container path). Formats without resources refuse.
    fn resource(&mut self, _name: &str) -> Result<Vec<u8>> {
        Err(crate::EngineError::unsupported("this document has no embedded resources"))
    }
}
