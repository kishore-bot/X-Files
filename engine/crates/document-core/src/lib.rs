//! Platform-independent core of the EzFiles document engine.
//!
//! This crate owns the contracts every parser implements and the coordinator
//! that drives them. It has no knowledge of concrete parsers, FFI, or mobile
//! operating systems; parsers are injected as a static slice.

mod cancel;
mod detect;
mod engine;
mod error;
mod limits;
mod model;
mod parser;

pub use cancel::CancelToken;
pub use detect::{SNIFF_LEN, detect};
pub use engine::{Engine, Session};
pub use error::{EngineError, ErrorKind, Result};
pub use limits::Limits;
pub use model::{
    Block, Build, CellStyle, ContentBatch, DocumentInfo, DocumentKind, FormatId, Geometry, MAX_CELL_RUNS, Rich, Shape,
    ShapeText, Span, style,
};
pub use parser::{Extracted, Input, OpenContext, OpenDocument, Parser, ReadSeek};
