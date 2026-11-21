//! "Tidy up": duplicates, near-duplicates, collections and sensitive-content
//! hints, exported over UniFFI. The algorithms live in `document-tidy`; this
//! module only maps types. The platform lists files, opens descriptors and
//! decodes thumbnails; everything that computes runs here.

use std::fs::File;

use document_tidy::collections::{CollectionKind, CollectionMatch};
use document_tidy::identical::{FileEntry, FileSource, find_identical};
use document_tidy::{Cancelled, NeverCancelled, Progress};

use crate::{ReadError, file_from_fd, open_for_read};

/// One file for [`tidy_find_identical`], with any hashes a previous scan of
/// the same unchanged file produced.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct NativeTidyFile {
    pub size: u64,
    pub partial_hash: Option<u64>,
    pub full_hash: Option<u64>,
}

/// An open file: a descriptor the engine takes ownership of, or a path
/// (host tests).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NativeTidySource {
    pub fd: i32,
    pub path: Option<String>,
}

/// Called on the thread running the scan. Must not throw.
#[uniffi::export(callback_interface)]
pub trait NativeTidyListener: Send + Sync {
    /// Opens file `index` for reading, or `None` if it can't be read now.
    fn open(&self, index: u32) -> Option<NativeTidySource>;
    fn on_progress(&self, done: u32, total: u32);
    fn is_cancelled(&self) -> bool;
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NativeIdenticalResult {
    /// Indices into the input, each group sorted; the most space to free first.
    pub groups: Vec<Vec<u32>>,
    /// Every hash known after the scan, by index, for the caller to keep.
    pub partial_hashes: Vec<Option<u64>>,
    pub full_hashes: Vec<Option<u64>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum NativeSimilarKind {
    Documents,
    Images,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum NativeCollection {
    Receipts,
    Travel,
    BankStatements,
    Certificates,
    PayAndTax,
    Medical,
    Resumes,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NativeCollectionMatch {
    pub collection: NativeCollection,
    pub score: u32,
    /// Words or phrases found in the text, strongest first, at most three.
    pub terms: Vec<String>,
    /// Words of the file name that matched.
    pub name_terms: Vec<String>,
    pub amounts: u32,
    pub dates: u32,
}

/// What a document's text says about it, from one pass over the text.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NativeTextFacts {
    /// SimHash; `None` when the text is too short to compare.
    pub fingerprint: Option<u64>,
    pub collection: Option<NativeCollectionMatch>,
}

/// Which kinds of sensitive number a text contains. Never the numbers.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct NativeSensitiveFindings {
    pub payment_card: bool,
    pub national_id: bool,
    pub bank_account: bool,
}

struct ListenerSource(Box<dyn NativeTidyListener>);

impl Progress for ListenerSource {
    fn is_cancelled(&self) -> bool {
        self.0.is_cancelled()
    }

    fn on_progress(&mut self, done: u64, total: u64) {
        let clamp = |n: u64| u32::try_from(n).unwrap_or(u32::MAX);
        self.0.on_progress(clamp(done), clamp(total));
    }
}

impl FileSource for ListenerSource {
    type Reader = File;

    fn open(&mut self, index: usize) -> Option<File> {
        let source = self.0.open(u32::try_from(index).ok()?)?;
        match source.path {
            Some(path) => open_for_read(&path).ok(),
            None => file_from_fd(source.fd).ok(),
        }
    }
}

fn cancelled(_: Cancelled) -> ReadError {
    ReadError::Cancelled { detail: "the scan was cancelled".into() }
}

/// Groups byte-identical files. Only files sharing a size are opened, only
/// their ends are read unless those match too, and every descriptor handed
/// over is closed before the next is asked for. Files that can't be opened
/// are left out.
#[uniffi::export]
pub fn tidy_find_identical(
    files: Vec<NativeTidyFile>,
    listener: Box<dyn NativeTidyListener>,
) -> Result<NativeIdenticalResult, ReadError> {
    let entries: Vec<FileEntry> =
        files.iter().map(|f| FileEntry { size: f.size, partial: f.partial_hash, full: f.full_hash }).collect();
    let mut source = ListenerSource(listener);
    let outcome = find_identical(&entries, &mut source).map_err(cancelled)?;
    let index = |i: usize| u32::try_from(i).unwrap_or(u32::MAX);
    Ok(NativeIdenticalResult {
        groups: outcome.groups.into_iter().map(|g| g.into_iter().map(index).collect()).collect(),
        partial_hashes: outcome.partial,
        full_hashes: outcome.full,
    })
}

/// Fingerprint and collection of a document, from its plain text and name.
#[uniffi::export]
pub fn tidy_analyze_text(name: String, text: String) -> NativeTextFacts {
    NativeTextFacts {
        fingerprint: document_tidy::simhash::simhash(&text),
        collection: document_tidy::collections::classify(&name, &text).map(Into::into),
    }
}

/// Collection of a file known only by its name (pictures, unreadable files).
#[uniffi::export]
pub fn tidy_classify_name(name: String) -> Option<NativeCollectionMatch> {
    document_tidy::collections::classify(&name, "").map(Into::into)
}

/// dHash of a small grayscale picture (`width` x `height`, one byte per
/// pixel, row-major); `None` if it is too small or nearly flat.
#[uniffi::export]
pub fn tidy_image_fingerprint(width: u32, height: u32, gray: Vec<u8>) -> Option<u64> {
    document_tidy::dhash::dhash(width, height, &gray)
}

/// Groups fingerprints of one kind that are near each other, as indices into
/// `fingerprints`; largest groups first.
#[uniffi::export]
pub fn tidy_group_similar(fingerprints: Vec<u64>, kind: NativeSimilarKind) -> Vec<Vec<u32>> {
    let distance = match kind {
        NativeSimilarKind::Documents => document_tidy::DOCUMENT_MAX_DISTANCE,
        NativeSimilarKind::Images => document_tidy::IMAGE_MAX_DISTANCE,
    };
    let groups = document_tidy::similar::group_similar(&fingerprints, distance, &NeverCancelled).unwrap_or_default();
    groups.into_iter().map(|g| g.into_iter().map(|i| u32::try_from(i).unwrap_or(u32::MAX)).collect()).collect()
}

/// Whether `text` shows a payment card, national ID or bank account number.
#[uniffi::export]
pub fn tidy_sensitive_scan(text: String) -> NativeSensitiveFindings {
    let found = document_tidy::sensitive::scan(&text);
    NativeSensitiveFindings {
        payment_card: found.payment_card,
        national_id: found.national_id,
        bank_account: found.bank_account,
    }
}

impl From<CollectionKind> for NativeCollection {
    fn from(kind: CollectionKind) -> Self {
        match kind {
            CollectionKind::Receipts => Self::Receipts,
            CollectionKind::Travel => Self::Travel,
            CollectionKind::BankStatements => Self::BankStatements,
            CollectionKind::Certificates => Self::Certificates,
            CollectionKind::PayAndTax => Self::PayAndTax,
            CollectionKind::Medical => Self::Medical,
            CollectionKind::Resumes => Self::Resumes,
        }
    }
}

impl From<CollectionMatch> for NativeCollectionMatch {
    fn from(found: CollectionMatch) -> Self {
        Self {
            collection: found.kind.into(),
            score: found.score,
            terms: found.terms,
            name_terms: found.name_terms,
            amounts: found.amounts,
            dates: found.dates,
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use super::*;

    struct PathListener {
        paths: Vec<String>,
        opened: Mutex<Vec<u32>>,
    }

    impl NativeTidyListener for PathListener {
        fn open(&self, index: u32) -> Option<NativeTidySource> {
            self.opened.lock().ok()?.push(index);
            Some(NativeTidySource { fd: -1, path: Some(self.paths.get(index as usize)?.clone()) })
        }

        fn on_progress(&self, _done: u32, _total: u32) {}

        fn is_cancelled(&self) -> bool {
            false
        }
    }

    #[test]
    fn finds_identical_files_on_disk() {
        let dir = std::env::temp_dir().join(format!("tidy-ffi-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let write = |name: &str, bytes: &[u8]| {
            let path = dir.join(name);
            std::fs::write(&path, bytes).unwrap();
            path.to_string_lossy().into_owned()
        };
        let paths = vec![write("a", b"same bytes"), write("b", b"other text"), write("c", b"same bytes")];
        let files = paths.iter().map(|p| NativeTidyFile { size: std::fs::metadata(p).unwrap().len(), partial_hash: None, full_hash: None }).collect();
        let listener = Box::new(PathListener { paths, opened: Mutex::new(Vec::new()) });
        let result = tidy_find_identical(files, listener).unwrap();
        assert_eq!(result.groups, vec![vec![0, 2]]);
        assert!(result.full_hashes[0].is_some());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn unopenable_files_are_skipped() {
        let files = vec![NativeTidyFile { size: 10, partial_hash: None, full_hash: None }; 2];
        let listener = Box::new(PathListener { paths: vec!["/no/such/file".into(); 2], opened: Mutex::new(Vec::new()) });
        assert!(tidy_find_identical(files, listener).unwrap().groups.is_empty());
    }

    #[test]
    fn text_facts_and_sensitive_scan_map_through() {
        let facts = tidy_analyze_text("receipt.pdf".into(), "Receipt total ₹ 120.00".into());
        assert_eq!(facts.fingerprint, None);
        assert_eq!(facts.collection.map(|c| c.collection), Some(NativeCollection::Receipts));
        assert!(tidy_sensitive_scan("4111 1111 1111 1111".into()).payment_card);
        assert_eq!(tidy_group_similar(vec![1, 3, u64::MAX], NativeSimilarKind::Images), vec![vec![0, 1]]);
    }
}
