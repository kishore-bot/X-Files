//! UniFFI surface for office find and replace (DOCX, PPTX, ODT, ODP, ODS).
//!
//! One [`NativeOfficeEditor`] per document session. It owns the working copy
//! path and a match index for the current query:
//!
//! - `start_search` builds nothing heavy; it inspects the package and returns.
//!   A repeat call with the same query key returns the cached index.
//! - `advance_search(n)` indexes the next `n` parts. The caller loops until
//!   `done`, which gives progress and a cancellation point between steps.
//! - `search_snippet(i)` is an O(1) lookup in the index.
//! - `replace` writes an edited copy and makes it the working path. A
//!   single-match replace rescans only the edited part and splices its entries
//!   into the index. A replace-all drops the index.
//!
//! The index is kept in memory and bounded by `MAX_INDEXED_MATCHES`.

use std::fs::File;
use std::sync::{Arc, Mutex, MutexGuard};

use document_core::CancelToken;
use document_parsers::office_edit::{self, IndexedMatch, Layout, OfficeQuery};

use crate::ReadError;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NativeOfficeQuery {
    pub find: String,
    pub case_sensitive: bool,
    pub whole_word: bool,
    pub include_number_cells: bool,
}

/// Progress of the match index. `total` is a lower bound when `truncated`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct NativeSearchState {
    pub total: u32,
    pub scanned_parts: u32,
    pub total_parts: u32,
    pub done: bool,
    pub truncated: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct NativeOfficeReplace {
    pub total: u32,
    pub replaced: u32,
}

/// The index for one query. Matches are sorted by part, then by position.
struct Search {
    key: String,
    query: OfficeQuery,
    layout: Layout,
    matches: Vec<IndexedMatch>,
    /// Parts indexed so far (a prefix of `layout.parts`).
    scanned: usize,
    truncated: bool,
}

impl Search {
    fn state(&self) -> NativeSearchState {
        let total_parts = self.layout.parts.len();
        NativeSearchState {
            total: u32::try_from(self.matches.len()).unwrap_or(u32::MAX),
            scanned_parts: u32::try_from(self.scanned).unwrap_or(u32::MAX),
            total_parts: u32::try_from(total_parts).unwrap_or(u32::MAX),
            done: self.scanned >= total_parts,
            truncated: self.truncated,
        }
    }
}

/// Handle for one find and replace session on a document file.
#[derive(uniffi::Object)]
pub struct NativeOfficeEditor {
    path: Mutex<String>,
    cancel: Mutex<CancelToken>,
    search: Mutex<Option<Search>>,
}

#[uniffi::export]
impl NativeOfficeEditor {
    #[uniffi::constructor]
    pub fn new(path: String) -> Arc<Self> {
        Arc::new(Self {
            path: Mutex::new(path),
            cancel: Mutex::new(CancelToken::default()),
            search: Mutex::new(None),
        })
    }

    /// Prepares the index for `query`. Returns the cached state when the
    /// same query already has an index for the current working copy.
    pub fn start_search(&self, query: NativeOfficeQuery) -> Result<NativeSearchState, ReadError> {
        let query = to_query(query);
        let key = query.cache_key();
        {
            let guard = lock(&self.search);
            if let Some(search) = guard.as_ref() {
                if search.key == key {
                    return Ok(search.state());
                }
            }
        }
        let token = self.fresh_token();
        let file = open_input(&self.current_path())?;
        let size = file_size(&file)?;
        let layout = office_edit::inspect(file, size, &token)?;
        let search = Search { key, query, layout, matches: Vec::new(), scanned: 0, truncated: false };
        let state = search.state();
        *lock(&self.search) = Some(search);
        Ok(state)
    }

    /// Indexes up to `max_parts` more parts of the current search.
    pub fn advance_search(&self, max_parts: u32) -> Result<NativeSearchState, ReadError> {
        let mut guard = lock(&self.search);
        let Some(search) = guard.as_mut() else {
            return Ok(NativeSearchState { total: 0, scanned_parts: 0, total_parts: 0, done: true, truncated: false });
        };
        let total_parts = search.layout.parts.len();
        if search.scanned >= total_parts {
            return Ok(search.state());
        }
        let token = self.fresh_token();
        let from = search.scanned;
        let to = total_parts.min(from.saturating_add(max_parts.max(1) as usize));
        let file = open_input(&self.current_path())?;
        let size = file_size(&file)?;
        let remaining = office_edit::MAX_INDEXED_MATCHES.saturating_sub(search.matches.len());
        let out = office_edit::scan_parts(file, size, &search.query, &search.layout, from..to, remaining, &token)?;
        search.matches.extend(out.matches);
        search.truncated |= out.truncated;
        search.scanned = if out.truncated { total_parts } else { to };
        Ok(search.state())
    }

    /// Context of match `index` in the current index, or empty when out of range.
    pub fn search_snippet(&self, index: u32) -> String {
        lock(&self.search).as_ref().and_then(|s| s.matches.get(index as usize)).map(|m| m.snippet.clone()).unwrap_or_default()
    }

    /// Writes an edited copy to `target_path` and makes it the working copy.
    /// With `only_index` set, only that match is replaced; otherwise all are.
    /// Writes nothing when no match was replaced. `target_path` must differ
    /// from the working copy; the caller decides what to do with the result.
    pub fn replace(
        &self,
        target_path: String,
        query: NativeOfficeQuery,
        replacement: String,
        only_index: Option<u32>,
    ) -> Result<NativeOfficeReplace, ReadError> {
        let path = self.current_path();
        if target_path == path {
            return Err(ReadError::UnsupportedFormat {
                detail: "the edited copy must be written to a different file".into(),
            });
        }
        let query = to_query(query);
        let key = query.cache_key();
        let part = only_index.and_then(|index| self.indexed_part(&key, index));
        let token = self.fresh_token();
        match run_replace(&path, &target_path, &query, &replacement, only_index, &token) {
            Err(error) => {
                std::fs::remove_file(&target_path).ok();
                Err(error)
            }
            Ok(result) => {
                if result.replaced > 0 {
                    *lock(&self.path) = target_path.clone();
                    let refreshed = match part {
                        Some(part) => self.refresh_part(&target_path, &query, part, &token).is_ok(),
                        None => false,
                    };
                    if !refreshed {
                        *lock(&self.search) = None;
                    }
                }
                Ok(result)
            }
        }
    }

    pub fn cancel(&self) {
        lock(&self.cancel).cancel();
    }
}

impl NativeOfficeEditor {
    fn current_path(&self) -> String {
        lock(&self.path).clone()
    }

    /// A new token for one operation. A cancel from an earlier operation does
    /// not carry over.
    fn fresh_token(&self) -> CancelToken {
        let mut guard = lock(&self.cancel);
        *guard = CancelToken::default();
        guard.clone()
    }

    /// The part holding match `index`, when the index is complete, untruncated
    /// and built for `key`.
    fn indexed_part(&self, key: &str, index: u32) -> Option<usize> {
        let guard = lock(&self.search);
        let search = guard.as_ref()?;
        if search.key != key || search.truncated || search.scanned < search.layout.parts.len() {
            return None;
        }
        search.matches.get(index as usize).map(|m| m.part as usize)
    }

    /// Rescans one part of the new working copy and splices its matches into
    /// the index. Other parts keep their entries, so the index stays in order.
    fn refresh_part(&self, path: &str, query: &OfficeQuery, part: usize, token: &CancelToken) -> Result<(), ReadError> {
        let mut guard = lock(&self.search);
        let search = guard
            .as_mut()
            .ok_or_else(|| ReadError::Io { detail: "the match index is no longer available".into() })?;
        let lo = search.matches.partition_point(|m| (m.part as usize) < part);
        let hi = search.matches.partition_point(|m| (m.part as usize) <= part);
        let others = search.matches.len() - (hi - lo);
        let remaining = office_edit::MAX_INDEXED_MATCHES.saturating_sub(others);
        let file = open_input(path)?;
        let size = file_size(&file)?;
        let fresh = office_edit::scan_parts(file, size, query, &search.layout, part..part + 1, remaining, token)?;
        if fresh.truncated {
            return Err(ReadError::LimitExceeded { detail: "too many matches to keep in the index".into() });
        }
        let _removed: Vec<IndexedMatch> = search.matches.splice(lo..hi, fresh.matches).collect();
        Ok(())
    }
}

fn run_replace(
    path: &str,
    target_path: &str,
    query: &OfficeQuery,
    replacement: &str,
    only_index: Option<u32>,
    cancel: &CancelToken,
) -> Result<NativeOfficeReplace, ReadError> {
    let input = open_input(path)?;
    let size = file_size(&input)?;
    let target = File::create(target_path).map_err(|e| ReadError::Io { detail: format!("cannot write file: {e}") })?;
    let report = office_edit::replace_matches(input, size, target, query, replacement, only_index, cancel)?;
    let result = NativeOfficeReplace { total: report.total, replaced: report.replaced };
    if report.replaced == 0 {
        std::fs::remove_file(target_path).ok();
        return Ok(result);
    }
    // Re-open the written file through the engine before handing it back.
    let written = open_input(target_path)?;
    office_edit::verify_reopens(written, cancel)?;
    Ok(result)
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn to_query(query: NativeOfficeQuery) -> OfficeQuery {
    OfficeQuery {
        find: query.find,
        case_sensitive: query.case_sensitive,
        whole_word: query.whole_word,
        include_number_cells: query.include_number_cells,
    }
}

fn open_input(path: &str) -> Result<File, ReadError> {
    File::open(path).map_err(|e| ReadError::Io { detail: format!("cannot read file: {e}") })
}

fn file_size(file: &File) -> Result<u64, ReadError> {
    file.metadata().map(|m| m.len()).map_err(|e| ReadError::Io { detail: format!("cannot read file: {e}") })
}
