//! Plain ZIP archives: a listing of entries from the central directory.
//!
//! Nothing is decompressed, so listing is safe even for decompression bombs.
//! Nested archives are listed as files and never opened.

use std::collections::VecDeque;

use document_core::{Block, Result};

use crate::stream::BlockSource;
use crate::zip::ZipArchive;

const ENTRIES_PER_PUMP: usize = 256;

pub(crate) struct ListingSource {
    entries: std::vec::IntoIter<Block>,
    archive: ZipArchive,
}

impl ListingSource {
    pub fn new(archive: ZipArchive) -> Self {
        let mut entries: Vec<Block> = archive
            .entries()
            .iter()
            .map(|e| Block::Entry { path: e.name.clone(), size: if e.is_dir { 0 } else { e.size }, is_dir: e.is_dir })
            .collect();
        entries.sort_by(|a, b| match (a, b) {
            (Block::Entry { path: a, .. }, Block::Entry { path: b, .. }) => a.cmp(b),
            _ => std::cmp::Ordering::Equal,
        });
        Self { entries: entries.into_iter(), archive }
    }
}

impl BlockSource for ListingSource {
    fn pump(&mut self, out: &mut VecDeque<Block>) -> Result<bool> {
        if self.entries.len() == 0 {
            return Ok(true);
        }
        out.extend(self.entries.by_ref().take(ENTRIES_PER_PUMP));
        Ok(false)
    }

    fn resource(&mut self, name: &str) -> Result<Vec<u8>> {
        self.archive.read_to_vec(name)
    }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use crate::testing::{open_zip, texts};
    use crate::zip::testing::zip;
    use document_core::{FormatId, Limits};

    #[test]
    fn lists_entries_sorted_without_extracting() {
        let (format, blocks) = open_zip(zip(&[("b.txt", b"bb"), ("a/", b""), ("a/inner.zip", b"PK..")]));
        assert_eq!(format, FormatId::Zip);
        assert_eq!(texts(&blocks), ["D a/", "E a/inner.zip 4", "E b.txt 2"]);
    }

    #[test]
    fn extracts_entry_by_name() {
        let bytes = zip(&[("hello.txt", b"world"), ("data.bin", &[0xDE, 0xAD])]);
        let mut session = crate::engine(Limits::default()).open(Cursor::new(bytes)).unwrap();
        while session.next_batch().unwrap().is_some() {}
        assert_eq!(session.resource("hello.txt").unwrap(), b"world");
        assert_eq!(session.resource("data.bin").unwrap(), &[0xDE, 0xAD]);
        assert!(session.resource("missing.txt").is_err());
    }
}
