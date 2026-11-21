use std::fmt;

/// Error categories, mapped one-to-one onto the FFI `ReadError` variants.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorKind {
    /// The input could not be read (I/O failure, not seekable, ...).
    Io,
    /// The content was recognised but no parser handles it, or it was not
    /// recognised at all.
    UnsupportedFormat,
    /// The content claims a format but violates it.
    Malformed,
    /// A resource limit (size, output, decompression, ...) was reached.
    LimitExceeded,
    /// The caller cancelled the operation.
    Cancelled,
    /// The document is encrypted and no password was given.
    PasswordRequired,
    /// The document is encrypted and the password given did not unlock it.
    WrongPassword,
    /// The document is encrypted with a scheme the engine does not implement.
    UnsupportedEncryption,
    /// A bug inside the engine, including caught panics.
    Internal,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EngineError {
    pub kind: ErrorKind,
    pub message: String,
}

pub type Result<T> = std::result::Result<T, EngineError>;

impl EngineError {
    pub fn new(kind: ErrorKind, message: impl Into<String>) -> Self {
        Self { kind, message: message.into() }
    }

    pub fn unsupported(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::UnsupportedFormat, message)
    }

    pub fn malformed(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Malformed, message)
    }

    pub fn limit(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::LimitExceeded, message)
    }

    pub fn cancelled() -> Self {
        Self::new(ErrorKind::Cancelled, "operation was cancelled")
    }
}

impl fmt::Display for EngineError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:?}: {}", self.kind, self.message)
    }
}

impl std::error::Error for EngineError {}

impl From<std::io::Error> for EngineError {
    fn from(err: std::io::Error) -> Self {
        Self::new(ErrorKind::Io, err.to_string())
    }
}
