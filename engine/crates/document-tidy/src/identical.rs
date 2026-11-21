//! Byte-identical files.
//!
//! Three passes, each only over the files the previous one could not tell
//! apart, so most files are never read at all:
//!
//! 1. group by size (free: the platform lists it);
//! 2. within a size, hash the first and last 64 KiB (files up to 128 KiB are
//!    read whole here, which makes this their full hash too);
//! 3. within a partial-hash group, stream the whole file through xxHash64.
//!
//! Memory is one 64 KiB buffer plus a few words per file. Hashes the caller
//! already knows (from a previous scan of an unchanged file) are reused, and
//! every hash computed is handed back so the caller can keep it.

use std::collections::HashMap;
use std::io::{Read, Seek, SeekFrom};

use crate::xxh64::Xxh64;
use crate::{Cancelled, Progress};

/// Bytes hashed from each end of a file in the partial pass.
pub const PARTIAL_BYTES: u64 = 64 * 1024;

/// How often, in bytes, a full hash checks for cancellation.
const CANCEL_CHECK_BYTES: u64 = 1024 * 1024;

/// One file as the caller knows it.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct FileEntry {
    pub size: u64,
    /// From an earlier scan of the same, unchanged file.
    pub partial: Option<u64>,
    pub full: Option<u64>,
}

/// Where file contents come from. The scan never writes.
pub trait FileSource: Progress {
    type Reader: Read + Seek;

    /// Opens the file at `index`, or `None` if it can no longer be read
    /// (it is then simply left out of every group).
    fn open(&mut self, index: usize) -> Option<Self::Reader>;
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct IdenticalOutcome {
    /// Indices of identical files, each group sorted, largest waste first.
    pub groups: Vec<Vec<usize>>,
    /// Every partial hash known after the scan, by index.
    pub partial: Vec<Option<u64>>,
    /// Every full hash known after the scan, by index.
    pub full: Vec<Option<u64>>,
}

pub fn find_identical<S: FileSource>(files: &[FileEntry], source: &mut S) -> Result<IdenticalOutcome, Cancelled> {
    let mut partial: Vec<Option<u64>> = files.iter().map(|f| f.partial).collect();
    let mut full: Vec<Option<u64>> = files.iter().map(|f| f.full).collect();
    let mut buffer = vec![0u8; PARTIAL_BYTES as usize];

    // Pass 1: sizes. Empty files are all "identical" and free nothing.
    let by_size = groups_of(files.iter().enumerate().filter(|(_, f)| f.size > 0).map(|(i, f)| (f.size, i)));

    // Pass 2: partial hashes, for sizes shared by two or more files.
    let partial_work: Vec<usize> = by_size.iter().flatten().copied().filter(|&i| partial[i].is_none()).collect();
    let mut done = 0u64;
    let mut total = partial_work.len() as u64;
    source.on_progress(done, total);
    for &index in &partial_work {
        source.check()?;
        let size = files[index].size;
        if let Some(mut reader) = source.open(index) {
            if let Some(hash) = partial_hash(&mut reader, size, &mut buffer) {
                partial[index] = Some(hash);
                if size <= 2 * PARTIAL_BYTES {
                    full[index] = Some(hash);
                }
            }
        }
        done += 1;
        source.on_progress(done, total);
    }

    // Pass 3: full hashes, for files whose size and partial hash both match.
    let by_partial: Vec<Vec<usize>> = by_size
        .iter()
        .flat_map(|group| groups_of(group.iter().filter_map(|&i| partial[i].map(|h| (h, i)))))
        .collect();
    let full_work: Vec<usize> = by_partial.iter().flatten().copied().filter(|&i| full[i].is_none()).collect();
    total += full_work.len() as u64;
    source.on_progress(done, total);
    for &index in &full_work {
        source.check()?;
        if let Some(mut reader) = source.open(index) {
            full[index] = full_hash(&mut reader, files[index].size, &mut buffer, &*source)?;
        }
        done += 1;
        source.on_progress(done, total);
    }

    let mut groups: Vec<Vec<usize>> = by_partial
        .iter()
        .flat_map(|group| groups_of(group.iter().filter_map(|&i| full[i].map(|h| (h, i)))))
        .collect();
    for group in &mut groups {
        group.sort_unstable();
    }
    groups.sort_by(|a, b| {
        let waste = |g: &Vec<usize>| files[g[0]].size.saturating_mul(g.len() as u64 - 1);
        waste(b).cmp(&waste(a)).then_with(|| a[0].cmp(&b[0]))
    });
    Ok(IdenticalOutcome { groups, partial, full })
}

/// Buckets `(key, index)` pairs and keeps buckets of two or more, ordered by
/// their first index so results don't depend on hash-map order.
fn groups_of<K: std::hash::Hash + Eq>(items: impl Iterator<Item = (K, usize)>) -> Vec<Vec<usize>> {
    let mut buckets: HashMap<K, Vec<usize>> = HashMap::new();
    for (key, index) in items {
        buckets.entry(key).or_default().push(index);
    }
    let mut groups: Vec<Vec<usize>> = buckets.into_values().filter(|g| g.len() > 1).collect();
    groups.sort_by_key(|g| g[0]);
    groups
}

/// Hash of the size and both ends of the file; the whole file when it is small.
fn partial_hash<R: Read + Seek>(reader: &mut R, size: u64, buffer: &mut [u8]) -> Option<u64> {
    let mut hasher = Xxh64::new(size);
    if size <= 2 * PARTIAL_BYTES {
        let read = copy_into(reader, size, buffer, &mut hasher).ok()?;
        return (read == size).then(|| hasher.finish());
    }
    let head = copy_into(reader, PARTIAL_BYTES, buffer, &mut hasher).ok()?;
    reader.seek(SeekFrom::Start(size - PARTIAL_BYTES)).ok()?;
    let tail = copy_into(reader, PARTIAL_BYTES, buffer, &mut hasher).ok()?;
    (head == PARTIAL_BYTES && tail == PARTIAL_BYTES).then(|| hasher.finish())
}

/// Hash of the whole file, seeded with its size like [`partial_hash`] so a
/// small file's two hashes are the same value. `None` if the file changed
/// length or could not be read.
fn full_hash<R: Read + Seek, P: Progress + ?Sized>(
    reader: &mut R,
    size: u64,
    buffer: &mut [u8],
    progress: &P,
) -> Result<Option<u64>, Cancelled> {
    let mut hasher = Xxh64::new(size);
    if reader.seek(SeekFrom::Start(0)).is_err() {
        return Ok(None);
    }
    let mut read = 0u64;
    let mut since_check = 0u64;
    loop {
        let n = match reader.read(buffer) {
            Ok(0) => break,
            Ok(n) => n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => return Ok(None),
        };
        hasher.update(&buffer[..n]);
        read += n as u64;
        if read > size {
            return Ok(None);
        }
        since_check += n as u64;
        if since_check >= CANCEL_CHECK_BYTES {
            since_check = 0;
            progress.check()?;
        }
    }
    Ok((read == size).then(|| hasher.finish()))
}

/// Feeds up to `limit` bytes from `reader` to `hasher`; returns how many it got.
fn copy_into<R: Read>(reader: &mut R, limit: u64, buffer: &mut [u8], hasher: &mut Xxh64) -> std::io::Result<u64> {
    let mut read = 0u64;
    while read < limit {
        let want = (limit - read).min(buffer.len() as u64) as usize;
        let n = match reader.read(&mut buffer[..want]) {
            Ok(0) => break,
            Ok(n) => n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        };
        hasher.update(&buffer[..n]);
        read += n as u64;
    }
    Ok(read)
}

#[cfg(test)]
mod tests {
    use std::cell::Cell;
    use std::io::Cursor;

    use super::*;

    struct Memory {
        files: Vec<Vec<u8>>,
        opened: Vec<usize>,
        cancel_after_opens: Option<usize>,
        progress: Cell<(u64, u64)>,
    }

    impl Memory {
        fn new(files: Vec<Vec<u8>>) -> Self {
            Self { files, opened: Vec::new(), cancel_after_opens: None, progress: Cell::new((0, 0)) }
        }

        fn entries(&self) -> Vec<FileEntry> {
            self.files.iter().map(|f| FileEntry { size: f.len() as u64, ..Default::default() }).collect()
        }
    }

    impl Progress for Memory {
        fn is_cancelled(&self) -> bool {
            self.cancel_after_opens.is_some_and(|n| self.opened.len() >= n)
        }

        fn on_progress(&mut self, done: u64, total: u64) {
            self.progress.set((done, total));
        }
    }

    impl FileSource for Memory {
        type Reader = Cursor<Vec<u8>>;

        fn open(&mut self, index: usize) -> Option<Self::Reader> {
            self.opened.push(index);
            Some(Cursor::new(self.files[index].clone()))
        }
    }

    fn big(fill: u8, len: usize) -> Vec<u8> {
        (0..len).map(|i| fill ^ (i % 251) as u8).collect()
    }

    #[test]
    fn groups_identical_files_and_never_opens_unique_sizes() {
        let a = b"hello world".to_vec();
        let mut source = Memory::new(vec![a.clone(), b"unique size!".to_vec(), a.clone(), b"hello there".to_vec()]);
        let outcome = find_identical(&source.entries(), &mut source).unwrap();
        assert_eq!(outcome.groups, vec![vec![0, 2]]);
        assert!(!source.opened.contains(&1), "a file with a unique size is never read");
        // Small files: one read gives both hashes.
        assert_eq!(outcome.partial[0], outcome.full[0]);
        assert_eq!(source.opened.iter().filter(|&&i| i == 0).count(), 1);
    }

    #[test]
    fn same_ends_different_middle_are_told_apart_by_the_full_hash() {
        let len = (PARTIAL_BYTES * 3) as usize;
        let a = big(1, len);
        let mut b = a.clone();
        b[len / 2] ^= 0xFF;
        let mut source = Memory::new(vec![a.clone(), b, a]);
        let outcome = find_identical(&source.entries(), &mut source).unwrap();
        assert_eq!(outcome.partial[0], outcome.partial[1], "ends are the same");
        assert_ne!(outcome.full[0], outcome.full[1]);
        assert_eq!(outcome.groups, vec![vec![0, 2]]);
    }

    #[test]
    fn reuses_known_hashes_without_reading() {
        let a = big(3, 1000);
        let mut source = Memory::new(vec![a.clone(), a]);
        let first = find_identical(&source.entries(), &mut source).unwrap();
        source.opened.clear();
        let known: Vec<FileEntry> = (0..2)
            .map(|i| FileEntry { size: 1000, partial: first.partial[i], full: first.full[i] })
            .collect();
        let second = find_identical(&known, &mut source).unwrap();
        assert!(source.opened.is_empty());
        assert_eq!(second.groups, first.groups);
    }

    #[test]
    fn largest_waste_comes_first_and_empty_files_are_ignored() {
        let small = b"ab".to_vec();
        let large = big(9, 5000);
        let mut source = Memory::new(vec![small.clone(), small, large.clone(), large, Vec::new(), Vec::new()]);
        let outcome = find_identical(&source.entries(), &mut source).unwrap();
        assert_eq!(outcome.groups, vec![vec![2, 3], vec![0, 1]]);
    }

    #[test]
    fn a_file_that_changed_length_is_left_out() {
        let a = big(5, 400);
        let mut source = Memory::new(vec![a.clone(), a.clone(), a]);
        let mut entries = source.entries();
        entries[1].size = 400;
        source.files[1].truncate(300); // shrank since it was listed
        let outcome = find_identical(&entries, &mut source).unwrap();
        assert_eq!(outcome.groups, vec![vec![0, 2]]);
        assert_eq!(outcome.partial[1], None);
    }

    #[test]
    fn cancellation_stops_the_scan() {
        let a = big(7, 100);
        let mut source = Memory::new(vec![a.clone(), a.clone(), a]);
        source.cancel_after_opens = Some(1);
        assert_eq!(find_identical(&source.entries(), &mut source), Err(Cancelled));
        assert_eq!(source.opened.len(), 1);
    }

    #[test]
    fn progress_reaches_its_total() {
        let len = (PARTIAL_BYTES * 3) as usize;
        let a = big(1, len);
        let mut source = Memory::new(vec![a.clone(), a]);
        find_identical(&source.entries(), &mut source).unwrap();
        let (done, total) = source.progress.get();
        assert_eq!((done, total), (4, 4), "two partial and two full reads");
    }
}
