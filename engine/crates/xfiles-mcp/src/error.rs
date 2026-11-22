//! Typed errors for tool calls. Each message is a sentence a person can act on.

use document_core::{EngineError, ErrorKind};
use thiserror::Error;

pub type Result<T, E = ToolError> = std::result::Result<T, E>;

#[derive(Debug, Error)]
pub enum ToolError {
    /// The arguments are well formed but not acceptable: a range, an extension, a missing field.
    #[error("{0}")]
    Invalid(String),
    /// The path lies outside the allowed folders, or resolves through a link that does.
    #[error("{0}")]
    Access(String),
    /// The file or folder named by the request does not exist.
    #[error("{0}")]
    Missing(String),
    /// The output path is taken and overwrite was not requested.
    #[error("{0}")]
    Exists(String),
    /// The engine could not read or change the document.
    #[error("{0}")]
    Document(String),
    #[error("{0}")]
    Io(String),
    #[error("the operation ran longer than {0} seconds and was stopped")]
    Timeout(u64),
    #[error("internal error: {0}")]
    Internal(String),
}

impl ToolError {
    pub fn invalid(message: impl Into<String>) -> Self {
        Self::Invalid(message.into())
    }
}

impl From<EngineError> for ToolError {
    fn from(err: EngineError) -> Self {
        match err.kind {
            ErrorKind::Io => Self::Io(err.message),
            _ => Self::Document(err.message),
        }
    }
}

impl From<std::io::Error> for ToolError {
    fn from(err: std::io::Error) -> Self {
        Self::Io(err.to_string())
    }
}
