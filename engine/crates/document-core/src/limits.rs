/// Resource budget applied to a single session.
///
/// All sizes are in bytes of UTF-8 output or raw input, never in characters,
/// so the budget maps directly to memory use.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Limits {
    /// Inputs larger than this are rejected before any parsing.
    pub max_input_bytes: u64,
    /// Upper bound on the text carried by one [`crate::ContentBatch`].
    pub max_batch_bytes: usize,
    /// Upper bound on the text extracted over the whole session. Reaching it
    /// ends the session with a truncated final batch rather than an error.
    pub max_total_output_bytes: u64,
    /// Upper bound on bytes decompressed from a container over the session.
    pub max_decompressed_bytes: u64,
    /// Decompressed/compressed ratio above which an entry is treated as a
    /// decompression bomb (checked once an entry exceeds 1 MiB).
    pub max_compression_ratio: u64,
    /// Upper bound on the number of entries in a container.
    pub max_archive_entries: usize,
    /// Upper bound on a container part read whole into memory (relationship
    /// files, workbook, shared-strings table, package manifest).
    pub max_part_bytes: u64,
}

impl Limits {
    /// The smallest batch that still guarantees progress: one UTF-8 scalar
    /// (up to 4 bytes) plus the per-block overhead of [`crate::Block::cost`].
    pub const MIN_BATCH_BYTES: usize = 5;
}

impl Default for Limits {
    fn default() -> Self {
        Self {
            max_input_bytes: 256 * 1024 * 1024,
            max_batch_bytes: 64 * 1024,
            max_total_output_bytes: 64 * 1024 * 1024,
            max_decompressed_bytes: 1024 * 1024 * 1024,
            max_compression_ratio: 250,
            max_archive_entries: 20_000,
            max_part_bytes: 32 * 1024 * 1024,
        }
    }
}
