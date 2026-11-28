//! Offline, classical algorithms for keeping a user's folders tidy.
//!
//! Everything here is deterministic and explainable: hashing, fingerprints,
//! keyword tables and checksums. No models, no network, standard library only.
//! The crate never touches the file system itself; callers hand it readers or
//! already-extracted text, and it never writes or deletes anything.
//!
//! - [`identical`]: byte-identical files (size, partial hash, full hash).
//! - [`simhash`] + [`similar`]: near-duplicate documents.
//! - [`dhash`] + [`similar`]: near-duplicate pictures.
//! - [`collections`]: rule-based "Receipts", "Tickets", ... with reasons.
//! - [`sensitive`]: card, Aadhaar and IBAN numbers, by checksum.

pub mod collections;
pub mod dhash;
pub mod identical;
pub mod sensitive;
pub mod similar;
pub mod simhash;
pub mod xxh64;

/// Documents whose SimHash fingerprints differ in at most this many bits are
/// "similar". Measured with `simhash::tune`: three bits (the textbook value
/// for web pages) missed three in four copies of a 1500-word report with 1%
/// of its words changed; six catches most of them, while unrelated texts stay
/// 20 or more bits apart and a random pair is within six with odds of about
/// 1 in 10^11.
pub const DOCUMENT_MAX_DISTANCE: u32 = 6;

/// Pictures whose dHash fingerprints differ in at most this many bits are
/// "similar": the same shot resized, recompressed or slightly brightened.
/// Tuned with the tests in [`dhash`].
pub const IMAGE_MAX_DISTANCE: u32 = 6;

/// The operation was cancelled by the caller.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Cancelled;

/// Cancellation and progress for a long operation.
pub trait Progress {
    fn is_cancelled(&self) -> bool;

    fn on_progress(&mut self, _done: u64, _total: u64) {}

    fn check(&self) -> Result<(), Cancelled> {
        if self.is_cancelled() { Err(Cancelled) } else { Ok(()) }
    }
}

/// For calls that can't be cancelled, and tests.
pub struct NeverCancelled;

impl Progress for NeverCancelled {
    fn is_cancelled(&self) -> bool {
        false
    }
}
