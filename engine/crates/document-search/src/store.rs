//! The index file format, version 1. Little-endian throughout:
//!
//! ```text
//! "EZSI"  u32 version  u64 next_file
//! u32 doc_count   { str key, u64 size, i64 mtime, u32 len, u64 file, u32 text_bytes, u32 page_count, u32 page* }
//! u32 term_count  { str term, u32 count, (u32 doc, u32 tf)* }
//! u64 FNV-1a checksum of everything before it
//! ```
//!
//! `str` is a u32 byte length and UTF-8 bytes. Every length is checked
//! against what remains, so a damaged file can't make the reader allocate
//! much; any inconsistency rejects the whole file.

use std::collections::BTreeMap;

use crate::index::{Doc, Inner, Posting};

const MAGIC: &[u8; 4] = b"EZSI";
const VERSION: u32 = 1;
/// Larger files are not read; the budgets keep real ones far smaller.
pub const MAX_FILE_BYTES: u64 = 64 * 1024 * 1024;
const MAX_STR: usize = 64 * 1024;
const MAX_PAGES: usize = 10_000;

/// Serialises the live documents; call after compaction.
pub(crate) fn encode(inner: &Inner) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&VERSION.to_le_bytes());
    out.extend_from_slice(&inner.next_file.to_le_bytes());
    let live: Vec<(usize, &Doc)> = inner.docs.iter().enumerate().filter(|(_, d)| d.live).collect();
    // Slots are renumbered in case a dead document slipped through.
    let mut remap = vec![u32::MAX; inner.docs.len()];
    for (new, (old, _)) in live.iter().enumerate() {
        remap[*old] = new as u32;
    }
    out.extend_from_slice(&(live.len() as u32).to_le_bytes());
    for (_, doc) in &live {
        put_str(&mut out, &doc.key);
        out.extend_from_slice(&doc.size.to_le_bytes());
        out.extend_from_slice(&doc.mtime.to_le_bytes());
        out.extend_from_slice(&doc.len.to_le_bytes());
        out.extend_from_slice(&doc.file.to_le_bytes());
        out.extend_from_slice(&doc.text_bytes.to_le_bytes());
        let pages = &doc.pages[..doc.pages.len().min(MAX_PAGES)];
        out.extend_from_slice(&(pages.len() as u32).to_le_bytes());
        for page in pages {
            out.extend_from_slice(&page.to_le_bytes());
        }
    }
    let count_at = out.len();
    out.extend_from_slice(&0u32.to_le_bytes());
    let mut terms = 0u32;
    for (term, postings) in &inner.terms {
        let kept: Vec<Posting> = postings
            .iter()
            .filter_map(|p| {
                let doc = *remap.get(p.doc as usize)?;
                (doc != u32::MAX).then_some(Posting { doc, tf: p.tf })
            })
            .collect();
        if kept.is_empty() {
            continue;
        }
        terms += 1;
        put_str(&mut out, term);
        out.extend_from_slice(&(kept.len() as u32).to_le_bytes());
        for p in kept {
            out.extend_from_slice(&p.doc.to_le_bytes());
            out.extend_from_slice(&p.tf.to_le_bytes());
        }
    }
    out[count_at..count_at + 4].copy_from_slice(&terms.to_le_bytes());
    let sum = fnv1a(&out);
    out.extend_from_slice(&sum.to_le_bytes());
    out
}

/// Parses a file written by [`encode`]; `None` for anything else.
pub(crate) fn decode(bytes: &[u8]) -> Option<Inner> {
    let body_len = bytes.len().checked_sub(8)?;
    let (body, sum) = bytes.split_at(body_len);
    if fnv1a(body) != u64::from_le_bytes(sum.try_into().ok()?) {
        return None;
    }
    let mut r = Reader { bytes: body, pos: 0 };
    if r.take(4)? != MAGIC || r.u32()? != VERSION {
        return None;
    }
    let next_file = r.u64()?;
    let doc_count = r.u32()? as usize;
    // Each document takes at least 40 bytes.
    if doc_count > r.remaining() / 40 {
        return None;
    }
    let mut docs = Vec::with_capacity(doc_count);
    for _ in 0..doc_count {
        let key = r.str()?;
        let size = r.u64()?;
        let mtime = r.u64()? as i64;
        let len = r.u32()?;
        let file = r.u64()?;
        let text_bytes = r.u32()?;
        let page_count = r.u32()? as usize;
        if page_count > MAX_PAGES || page_count > r.remaining() / 4 {
            return None;
        }
        let pages = (0..page_count).map(|_| r.u32()).collect::<Option<Vec<u32>>>()?;
        docs.push(Doc { key, size, mtime, len, file, text_bytes, pages, live: true });
    }
    let term_count = r.u32()? as usize;
    if term_count > r.remaining() / 9 {
        return None;
    }
    let mut terms = BTreeMap::new();
    for _ in 0..term_count {
        let term = r.str()?;
        let count = r.u32()? as usize;
        if count == 0 || count > r.remaining() / 8 {
            return None;
        }
        let mut postings = Vec::with_capacity(count);
        for _ in 0..count {
            let doc = r.u32()?;
            let tf = r.u32()?;
            if doc as usize >= doc_count {
                return None;
            }
            postings.push(Posting { doc, tf });
        }
        terms.insert(term.into_boxed_str(), postings);
    }
    if r.remaining() != 0 {
        return None;
    }
    Some(Inner { docs, terms, next_file: next_file.max(1), ..Inner::default() })
}

fn put_str(out: &mut Vec<u8>, s: &str) {
    let s = &s.as_bytes()[..s.len().min(MAX_STR)];
    out.extend_from_slice(&(s.len() as u32).to_le_bytes());
    out.extend_from_slice(s);
}

struct Reader<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn remaining(&self) -> usize {
        self.bytes.len() - self.pos
    }

    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        let end = self.pos.checked_add(n)?;
        let slice = self.bytes.get(self.pos..end)?;
        self.pos = end;
        Some(slice)
    }

    fn u32(&mut self) -> Option<u32> {
        Some(u32::from_le_bytes(self.take(4)?.try_into().ok()?))
    }

    fn u64(&mut self) -> Option<u64> {
        Some(u64::from_le_bytes(self.take(8)?.try_into().ok()?))
    }

    fn str(&mut self) -> Option<String> {
        let len = self.u32()? as usize;
        if len > MAX_STR {
            return None;
        }
        String::from_utf8(self.take(len)?.to_vec()).ok()
    }
}

/// 64-bit FNV-1a: detects damage, not tampering (the file is app-private).
fn fnv1a(bytes: &[u8]) -> u64 {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for &b in bytes {
        hash ^= u64::from(b);
        hash = hash.wrapping_mul(0x0100_0000_01b3);
    }
    hash
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Inner {
        let mut terms = BTreeMap::new();
        terms.insert("alpha".into(), vec![Posting { doc: 0, tf: 2 }, Posting { doc: 1, tf: 1 }]);
        terms.insert("béta".into(), vec![Posting { doc: 1, tf: 5 }]);
        let doc = |key: &str, file| Doc {
            key: key.into(),
            size: 10,
            mtime: -3,
            len: 7,
            file,
            text_bytes: 12,
            pages: vec![0, 40],
            live: true,
        };
        let mut inner =
            Inner { docs: vec![doc("a", 1), doc("content://b", 2)], terms, next_file: 3, ..Inner::default() };
        inner.recount();
        inner
    }

    #[test]
    fn round_trips() {
        let inner = sample();
        let decoded = decode(&encode(&inner)).unwrap();
        assert_eq!(decoded.docs, inner.docs);
        assert_eq!(decoded.terms, inner.terms);
        assert_eq!(decoded.next_file, 3);
    }

    #[test]
    fn drops_dead_documents_and_renumbers() {
        let mut inner = sample();
        inner.docs[0].live = false;
        let decoded = decode(&encode(&inner)).unwrap();
        assert_eq!(decoded.docs.len(), 1);
        assert_eq!(decoded.docs[0].key, "content://b");
        assert_eq!(decoded.terms["alpha"], vec![Posting { doc: 0, tf: 1 }]);
    }

    #[test]
    fn rejects_every_truncation_and_bit_flip() {
        let bytes = encode(&sample());
        for len in 0..bytes.len() {
            assert!(decode(&bytes[..len]).is_none(), "truncated to {len}");
        }
        for i in 0..bytes.len() {
            let mut damaged = bytes.clone();
            damaged[i] ^= 0x10;
            assert!(decode(&damaged).is_none(), "flip at {i}");
        }
    }

    #[test]
    fn rejects_other_versions_even_with_a_valid_checksum() {
        let mut bytes = encode(&sample());
        bytes.truncate(bytes.len() - 8);
        bytes[4] = 2;
        let sum = fnv1a(&bytes);
        bytes.extend_from_slice(&sum.to_le_bytes());
        assert!(decode(&bytes).is_none());
    }
}
