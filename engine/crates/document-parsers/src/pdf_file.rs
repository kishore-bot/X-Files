//! The structure of a PDF file: cross-reference chain, object loading and page
//! tree, read through a seekable source without holding the file in memory.
//!
//! This is not a repairing reader. A file whose cross-reference data is wrong
//! gets a typed error, because writing an update on top of a guess could lose
//! the user's content. Viewing is unaffected: pages are drawn by the platform.

use std::collections::{HashMap, HashSet};
use std::io::{Read, Seek, SeekFrom};
use std::rc::Rc;

use document_core::{EngineError, Result};
use flate2::read::ZlibDecoder;

use crate::pdf_crypt::Decryptor;
use crate::pdf_syntax::{Dict, Lexer, Obj, SyntaxError, is_space};

/// PDF's own implementation limit for object numbers.
pub(crate) const MAX_OBJECT_NUMBER: u32 = 8_388_607;
const MAX_XREF_ENTRIES: usize = 1_000_000;
const MAX_XREF_SECTIONS: usize = 1024;
const MAX_XREF_WINDOW: usize = 32 * 1024 * 1024;
const MAX_OBJECT_WINDOW: usize = 16 * 1024 * 1024;
const FIRST_WINDOW: usize = 16 * 1024;
/// Largest stream read (compressed) or produced (decompressed) for structure.
const MAX_STREAM_BYTES: u64 = 64 * 1024 * 1024;
/// Everything inflated while walking one file, so alternating object streams
/// cannot keep the CPU busy forever.
const MAX_TOTAL_INFLATED: u64 = 1024 * 1024 * 1024;
const MAX_OBJSTM_CACHE_BYTES: usize = 64 * 1024 * 1024;
const MAX_OBJSTM_OBJECTS: usize = 200_000;
const MAX_REF_HOPS: u32 = 32;
const MAX_PAGES: usize = 200_000;
const MAX_TREE_DEPTH: usize = 64;
/// Encryption dictionaries are shallow; this only stops a crafted loop.
const MAX_RESOLVE_DEPTH: u32 = 8;
const TAIL_BYTES: u64 = 2048;
const SCAN_CHUNK: usize = 1024 * 1024;
/// Used by PDFium when a page has no `/MediaBox`.
const DEFAULT_MEDIA_BOX: [f64; 4] = [0.0, 0.0, 612.0, 792.0];

fn damaged_xref() -> EngineError {
    EngineError::malformed("the PDF's cross-reference data is damaged")
}

fn damaged_pages() -> EngineError {
    EngineError::malformed("the PDF's page tree is damaged")
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum XrefEntry {
    Free,
    Plain { offset: u64, generation: u16 },
    InStream { stream: u32, index: u32 },
}

/// One page in reading order, with inherited attributes resolved.
#[derive(Debug, Clone, Copy, PartialEq)]
pub(crate) struct PageRef {
    pub(crate) object: u32,
    pub(crate) geometry: PageGeometry,
}

/// A page with the attributes it inherits, as written in its ancestors: shared
/// so a huge dictionary on a tree node doesn't cost memory per page.
#[derive(Debug, Clone)]
pub(crate) struct PageNode {
    pub(crate) page: PageRef,
    /// `MediaBox`, `CropBox` and `Resources` from the nearest ancestor with each.
    pub(crate) inherited: Rc<Dict>,
}

/// An indirect object, decrypted, and where its stream data starts if it has one.
#[derive(Debug, Clone)]
pub(crate) struct LoadedObject {
    pub(crate) value: Obj,
    pub(crate) generation: u16,
    pub(crate) stream_at: Option<u64>,
}

/// Maps fractions of the displayed page (origin top-left, as the platform
/// renderer shows it) to PDF user space and back.
#[derive(Debug, Clone, Copy, PartialEq)]
pub(crate) struct PageGeometry {
    pub(crate) x0: f64,
    pub(crate) y0: f64,
    pub(crate) x1: f64,
    pub(crate) y1: f64,
    /// 0, 90, 180 or 270, clockwise.
    pub(crate) rotate: u16,
}

impl PageGeometry {
    fn new(media: [f64; 4], crop: Option<[f64; 4]>, rotate: i64) -> Self {
        let media = normalised_box(media);
        // PDFium shows the intersection of the two boxes, or the media box if they miss.
        let shown = crop
            .map(normalised_box)
            .map(|crop| [crop[0].max(media[0]), crop[1].max(media[1]), crop[2].min(media[2]), crop[3].min(media[3])])
            .filter(|b| b[2] > b[0] && b[3] > b[1])
            .unwrap_or(media);
        let shown = if shown[2] > shown[0] && shown[3] > shown[1] { shown } else { DEFAULT_MEDIA_BOX };
        Self { x0: shown[0], y0: shown[1], x1: shown[2], y1: shown[3], rotate: rotate.rem_euclid(360) as u16 / 90 * 90 }
    }

    pub(crate) fn width(&self) -> f64 {
        self.x1 - self.x0
    }

    pub(crate) fn height(&self) -> f64 {
        self.y1 - self.y0
    }

    /// Width of the page as displayed, in points.
    pub(crate) fn display_width(&self) -> f64 {
        if self.rotate % 180 == 0 { self.width() } else { self.height() }
    }

    pub(crate) fn to_user(self, u: f64, v: f64) -> (f64, f64) {
        match self.rotate {
            90 => (self.x0 + v * self.width(), self.y0 + u * self.height()),
            180 => (self.x1 - u * self.width(), self.y0 + v * self.height()),
            270 => (self.x1 - v * self.width(), self.y1 - u * self.height()),
            _ => (self.x0 + u * self.width(), self.y1 - v * self.height()),
        }
    }

    pub(crate) fn to_display(self, x: f64, y: f64) -> (f64, f64) {
        match self.rotate {
            90 => ((y - self.y0) / self.height(), (x - self.x0) / self.width()),
            180 => ((self.x1 - x) / self.width(), (y - self.y0) / self.height()),
            270 => ((self.y1 - y) / self.height(), (self.x1 - x) / self.width()),
            _ => ((x - self.x0) / self.width(), (self.y1 - y) / self.height()),
        }
    }
}

fn normalised_box(b: [f64; 4]) -> [f64; 4] {
    [b[0].min(b[2]), b[1].min(b[3]), b[0].max(b[2]), b[1].max(b[3])]
}

struct DecodedObjStm {
    bytes: Vec<u8>,
    /// Object number and absolute position in `bytes`.
    table: Vec<(u32, usize)>,
}

pub(crate) struct PdfFile<R> {
    source: R,
    pub(crate) len: u64,
    pub(crate) xref: HashMap<u32, XrefEntry>,
    /// The newest trailer (or cross-reference stream dictionary).
    pub(crate) trailer: Dict,
    pub(crate) last_xref_offset: u64,
    /// The newest section is a stream, so an update must also write a stream.
    pub(crate) last_xref_is_stream: bool,
    objstm_cache: HashMap<u32, Rc<DecodedObjStm>>,
    objstm_cached_bytes: usize,
    objstm_loading: HashSet<u32>,
    inflated: u64,
    /// Set once a password has unlocked an encrypted file.
    pub(crate) crypt: Option<Decryptor>,
}

impl<R: Read + Seek> PdfFile<R> {
    pub(crate) fn open(mut source: R) -> Result<Self> {
        let len = source.seek(SeekFrom::End(0))?;
        let mut header = [0u8; 5];
        source.seek(SeekFrom::Start(0))?;
        source.read_exact(&mut header).map_err(|_| EngineError::malformed("the PDF header is damaged"))?;
        // Junk before the header would shift every offset an update writes.
        if &header != b"%PDF-" {
            return Err(EngineError::malformed("the PDF header is damaged"));
        }
        let mut file = Self {
            source,
            len,
            xref: HashMap::new(),
            trailer: Dict::default(),
            last_xref_offset: 0,
            last_xref_is_stream: false,
            objstm_cache: HashMap::new(),
            objstm_cached_bytes: 0,
            objstm_loading: HashSet::new(),
            inflated: 0,
            crypt: None,
        };
        file.read_xref_chain()?;
        Ok(file)
    }

    fn read_at(&mut self, offset: u64, max: usize) -> Result<Vec<u8>> {
        let want = u64::try_from(max).unwrap_or(u64::MAX).min(self.len.saturating_sub(offset));
        let mut bytes = Vec::with_capacity(want as usize);
        self.source.seek(SeekFrom::Start(offset))?;
        (&mut self.source).take(want).read_to_end(&mut bytes)?;
        Ok(bytes)
    }

    /// Copies the whole file, unchanged; returns how many bytes it was.
    pub(crate) fn copy_all<W: std::io::Write>(&mut self, out: &mut W) -> Result<u64> {
        self.source.seek(SeekFrom::Start(0))?;
        Ok(std::io::copy(&mut self.source, out)?)
    }

    pub(crate) fn last_byte(&mut self) -> Result<u8> {
        let bytes = self.read_at(self.len.saturating_sub(1), 1)?;
        bytes.first().copied().ok_or_else(damaged_xref)
    }

    fn startxref(&mut self) -> Result<u64> {
        let start = self.len.saturating_sub(TAIL_BYTES);
        let tail = self.read_at(start, TAIL_BYTES as usize)?;
        let at = tail.windows(9).rposition(|w| w == b"startxref").ok_or_else(damaged_xref)?;
        let offset = Lexer::new(&tail, at + 9).unsigned().ok_or_else(damaged_xref)?;
        if offset >= self.len {
            return Err(damaged_xref());
        }
        Ok(offset)
    }

    fn read_xref_chain(&mut self) -> Result<()> {
        let first = self.startxref()?;
        self.last_xref_offset = first;
        let mut seen = HashSet::new();
        let mut next = Some(first);
        let mut newest = true;
        while let Some(offset) = next {
            if !seen.insert(offset) || seen.len() > MAX_XREF_SECTIONS {
                return Err(damaged_xref());
            }
            let (trailer, is_stream) = self.read_xref_section(offset)?;
            // Hybrid files: entries in /XRefStm rank between this table and /Prev.
            if let Some(hybrid) = trailer.get("XRefStm").and_then(Obj::as_i64) {
                let hybrid = u64::try_from(hybrid).map_err(|_| damaged_xref())?;
                if seen.insert(hybrid) {
                    self.read_xref_section(hybrid)?;
                }
            }
            next = match trailer.get("Prev") {
                None => None,
                Some(prev) => {
                    let prev = prev.as_i64().and_then(|p| u64::try_from(p).ok()).ok_or_else(damaged_xref)?;
                    if prev >= self.len {
                        return Err(damaged_xref());
                    }
                    Some(prev)
                }
            };
            if newest {
                self.trailer = trailer;
                self.last_xref_is_stream = is_stream;
                newest = false;
            }
        }
        if self.trailer.get("Root").is_none() {
            return Err(damaged_xref());
        }
        Ok(())
    }

    /// Reads one section; existing entries are newer and win.
    fn read_xref_section(&mut self, offset: u64) -> Result<(Dict, bool)> {
        let mut window = FIRST_WINDOW;
        loop {
            let bytes = self.read_at(offset, window)?;
            let complete = offset + bytes.len() as u64 >= self.len;
            let mut lexer = Lexer::new(&bytes, 0);
            let is_table = lexer.eat_keyword(b"xref");
            let attempt = if is_table {
                let mut entries = Vec::new();
                parse_xref_table(&mut lexer, &mut entries).map(|trailer| (trailer, entries))
            } else {
                Err(SyntaxError::Invalid)
            };
            match attempt {
                Ok((trailer, entries)) => {
                    self.merge_entries(entries)?;
                    return Ok((trailer, false));
                }
                Err(SyntaxError::Truncated) if is_table && !complete && window < MAX_XREF_WINDOW => window *= 4,
                Err(SyntaxError::TooLarge) if is_table => {
                    return Err(EngineError::limit("the PDF's cross-reference table is too large"));
                }
                Err(SyntaxError::Truncated) if is_table && !complete => {
                    return Err(EngineError::limit("the PDF's cross-reference table is too large"));
                }
                Err(_) if is_table => return Err(damaged_xref()),
                Err(_) => return self.read_xref_stream(offset),
            }
        }
    }

    fn merge_entries(&mut self, entries: Vec<(u32, XrefEntry)>) -> Result<()> {
        for (number, entry) in entries {
            if number > MAX_OBJECT_NUMBER {
                return Err(damaged_xref());
            }
            self.xref.entry(number).or_insert(entry);
            if self.xref.len() > MAX_XREF_ENTRIES {
                return Err(EngineError::limit("the PDF has too many objects to edit"));
            }
        }
        Ok(())
    }

    fn read_xref_stream(&mut self, offset: u64) -> Result<(Dict, bool)> {
        let (_, _, dict, data_at) = self.parse_indirect_at(offset)?;
        let data_at = data_at.ok_or_else(damaged_xref)?;
        if dict.get("Type").and_then(Obj::as_name) != Some(b"XRef") {
            return Err(damaged_xref());
        }
        // A cross-reference stream's /Length must be direct: nothing can be looked up yet.
        let length = match dict.get("Length") {
            Some(Obj::Num(_)) => dict.get("Length").and_then(Obj::as_i64),
            _ => None,
        };
        let length = length.and_then(|l| u64::try_from(l).ok()).ok_or_else(damaged_xref)?;
        let raw = self.stream_bytes(data_at, length)?;
        let data = self.decode_stream(&dict, raw).map_err(|e| match e.kind {
            document_core::ErrorKind::LimitExceeded => e,
            _ => damaged_xref(),
        })?;

        let widths = int_array(dict.get("W")).ok_or_else(damaged_xref)?;
        if widths.len() != 3 || widths.iter().any(|w| !(0..=8).contains(w)) {
            return Err(damaged_xref());
        }
        let [w0, w1, w2] = [widths[0] as usize, widths[1] as usize, widths[2] as usize];
        let entry_len = w0 + w1 + w2;
        if entry_len == 0 {
            return Err(damaged_xref());
        }
        let size = dict.get("Size").and_then(Obj::as_i64).filter(|s| *s >= 0).ok_or_else(damaged_xref)?;
        let index = match dict.get("Index") {
            Some(_) => int_array(dict.get("Index")).ok_or_else(damaged_xref)?,
            None => vec![0, size],
        };
        if index.len() % 2 != 0 {
            return Err(damaged_xref());
        }
        let mut entries = Vec::new();
        let mut records = data.chunks_exact(entry_len);
        for pair in index.chunks_exact(2) {
            let (start, count) = (pair[0], pair[1]);
            if start < 0 || count < 0 || start.saturating_add(count) > i64::from(MAX_OBJECT_NUMBER) + 1 {
                return Err(damaged_xref());
            }
            for number in start..start + count {
                let record = records.next().ok_or_else(damaged_xref)?;
                let field = |from: usize, width: usize| {
                    record[from..from + width].iter().fold(0u64, |v, b| v << 8 | u64::from(*b))
                };
                let kind = if w0 == 0 { 1 } else { field(0, w0) };
                let second = field(w0, w1);
                let third = field(w0 + w1, w2);
                let entry = match kind {
                    0 => XrefEntry::Free,
                    1 => XrefEntry::Plain { offset: second, generation: u16::try_from(third).unwrap_or(u16::MAX) },
                    2 => XrefEntry::InStream {
                        stream: u32::try_from(second).map_err(|_| damaged_xref())?,
                        index: u32::try_from(third).map_err(|_| damaged_xref())?,
                    },
                    // Reserved types read as null, per the specification.
                    _ => XrefEntry::Free,
                };
                entries.push((number as u32, entry));
                if entries.len() > MAX_XREF_ENTRIES {
                    return Err(EngineError::limit("the PDF has too many objects to edit"));
                }
            }
        }
        self.merge_entries(entries)?;
        Ok((dict, true))
    }

    /// Parses `N G obj <value>` at `offset`. Returns the numbers, the dictionary
    /// if the value is one, and where stream data starts if a stream follows.
    /// Non-dictionary values come back through [`Self::parse_value_at`].
    fn parse_indirect_at(&mut self, offset: u64) -> Result<(u32, u16, Dict, Option<u64>)> {
        let (number, generation, value, data_at) = self.parse_value_at(offset)?;
        match value {
            Obj::Dict(dict) => Ok((number, generation, dict, data_at)),
            _ => Err(damaged_xref()),
        }
    }

    fn parse_value_at(&mut self, offset: u64) -> Result<(u32, u16, Obj, Option<u64>)> {
        let mut window = FIRST_WINDOW;
        loop {
            let bytes = self.read_at(offset, window)?;
            let complete = offset + bytes.len() as u64 >= self.len;
            match parse_indirect(&bytes, complete) {
                Ok((number, generation, value, data_at)) => {
                    return Ok((number, generation, value, data_at.map(|at| offset + at as u64)));
                }
                Err(SyntaxError::Truncated) if !complete && window < MAX_OBJECT_WINDOW => window *= 4,
                Err(SyntaxError::TooLarge) => return Err(EngineError::limit("a PDF object is too large to edit")),
                Err(SyntaxError::Truncated) if !complete => {
                    return Err(EngineError::limit("a PDF object is too large to edit"));
                }
                Err(_) => return Err(damaged_xref()),
            }
        }
    }

    /// The raw bytes of a stream, trusting `/Length` only if `endstream` follows it.
    fn stream_bytes(&mut self, data_at: u64, length: u64) -> Result<Vec<u8>> {
        if length <= MAX_STREAM_BYTES && data_at.saturating_add(length) <= self.len {
            let bytes = self.read_at(data_at, length as usize)?;
            let after = self.read_at(data_at + length, 32)?;
            let mut lexer = Lexer::new(&after, 0);
            if lexer.eat_keyword(b"endstream") {
                return Ok(bytes);
            }
        }
        // A wrong length is common enough to look for the real end, within the same bound.
        let mut bytes = Vec::new();
        let mut at = data_at;
        while (bytes.len() as u64) < MAX_STREAM_BYTES {
            let chunk = self.read_at(at, SCAN_CHUNK)?;
            if chunk.is_empty() {
                return Err(EngineError::malformed("a PDF stream has no end"));
            }
            at += chunk.len() as u64;
            let overlap = bytes.len().saturating_sub(8);
            bytes.extend_from_slice(&chunk);
            if let Some(end) = bytes[overlap..].windows(9).position(|w| w == b"endstream") {
                let mut end = overlap + end;
                while end > 0 && is_space(bytes[end - 1]) && bytes.len() - end < 3 + 9 {
                    end -= 1;
                }
                bytes.truncate(end);
                return Ok(bytes);
            }
        }
        Err(EngineError::limit("a PDF stream is too large to read"))
    }

    fn decode_stream(&mut self, dict: &Dict, raw: Vec<u8>) -> Result<Vec<u8>> {
        let filter = self.resolve_opt(dict.get("Filter"))?;
        let parms = self.resolve_opt(dict.get("DecodeParms").or_else(|| dict.get("DP")))?;
        let (filter, parms) = match (filter, parms) {
            (Obj::Array(filters), parms) if filters.is_empty() => (Obj::Null, parms),
            (Obj::Array(mut filters), parms) if filters.len() == 1 => {
                let parms = match parms {
                    Obj::Array(mut all) if !all.is_empty() => all.swap_remove(0),
                    other => other,
                };
                (filters.swap_remove(0), parms)
            }
            (Obj::Array(_), _) => return Err(EngineError::unsupported("a PDF stream uses chained filters")),
            (filter, parms) => (filter, parms),
        };
        match &filter {
            Obj::Null => Ok(raw),
            Obj::Name(name) if name == b"FlateDecode" || name == b"Fl" => {
                let inflated = self.inflate(&raw)?;
                let parms = match self.resolve(&parms)? {
                    Obj::Dict(parms) => parms,
                    _ => Dict::default(),
                };
                undo_predictor(inflated, &parms)
            }
            _ => Err(EngineError::unsupported("a PDF stream uses an unsupported filter")),
        }
    }

    fn inflate(&mut self, raw: &[u8]) -> Result<Vec<u8>> {
        let mut out = Vec::new();
        let read = ZlibDecoder::new(raw)
            .take(MAX_STREAM_BYTES + 1)
            .read_to_end(&mut out)
            .map_err(|_| EngineError::malformed("a compressed PDF stream is damaged"))?;
        if read as u64 > MAX_STREAM_BYTES {
            return Err(EngineError::limit("a compressed PDF stream expands too far"));
        }
        self.inflated += read as u64;
        if self.inflated > MAX_TOTAL_INFLATED {
            return Err(EngineError::limit("the PDF's compressed structure is too large"));
        }
        Ok(out)
    }

    fn resolve_opt(&mut self, value: Option<&Obj>) -> Result<Obj> {
        match value {
            Some(value) => self.resolve(value),
            None => Ok(Obj::Null),
        }
    }

    /// Follows references until a direct value. Missing objects are null, per
    /// the specification; a chain that never ends is an error.
    pub(crate) fn resolve(&mut self, value: &Obj) -> Result<Obj> {
        let mut current = value.clone();
        for _ in 0..MAX_REF_HOPS {
            match current {
                Obj::Ref(number, _) => current = self.object(number)?,
                other => return Ok(other),
            }
        }
        Err(EngineError::malformed("a PDF reference never resolves"))
    }

    /// The value of an indirect object, without any stream data.
    pub(crate) fn object(&mut self, number: u32) -> Result<Obj> {
        match self.xref.get(&number).copied() {
            None | Some(XrefEntry::Free) => Ok(Obj::Null),
            Some(XrefEntry::Plain { offset, .. }) => {
                if offset >= self.len {
                    return Err(damaged_xref());
                }
                let (found, generation, mut value, _) = self.parse_value_at(offset)?;
                if found != number {
                    return Err(damaged_xref());
                }
                if let Some(crypt) = &self.crypt {
                    crypt.decrypt_strings(number, generation, &mut value)?;
                }
                Ok(value)
            }
            Some(XrefEntry::InStream { stream, index }) => {
                let decoded = self.object_stream(stream)?;
                // Trust the index if it agrees, else search: some writers get it wrong.
                let position = decoded
                    .table
                    .get(index as usize)
                    .filter(|(n, _)| *n == number)
                    .or_else(|| decoded.table.iter().find(|(n, _)| *n == number))
                    .map(|(_, at)| *at)
                    .ok_or_else(damaged_xref)?;
                Lexer::new(&decoded.bytes, position).object().map_err(|e| match e {
                    SyntaxError::TooLarge => EngineError::limit("a PDF object is too large to edit"),
                    _ => damaged_xref(),
                })
            }
        }
    }

    fn object_stream(&mut self, number: u32) -> Result<Rc<DecodedObjStm>> {
        if let Some(cached) = self.objstm_cache.get(&number) {
            return Ok(Rc::clone(cached));
        }
        // An object stream's /Length may live in another object stream, or itself.
        if !self.objstm_loading.insert(number) {
            return Err(damaged_xref());
        }
        let result = self.load_object_stream(number);
        self.objstm_loading.remove(&number);
        let decoded = Rc::new(result?);
        if self.objstm_cached_bytes + decoded.bytes.len() > MAX_OBJSTM_CACHE_BYTES {
            self.objstm_cache.clear();
            self.objstm_cached_bytes = 0;
        }
        self.objstm_cached_bytes += decoded.bytes.len();
        self.objstm_cache.insert(number, Rc::clone(&decoded));
        Ok(decoded)
    }

    fn load_object_stream(&mut self, number: u32) -> Result<DecodedObjStm> {
        let Some(XrefEntry::Plain { offset, .. }) = self.xref.get(&number).copied() else {
            return Err(damaged_xref());
        };
        let (found, generation, dict, data_at) = self.parse_indirect_at(offset)?;
        let data_at = data_at.ok_or_else(damaged_xref)?;
        if found != number || dict.get("Type").and_then(Obj::as_name) != Some(b"ObjStm") {
            return Err(damaged_xref());
        }
        let length = self.resolve_opt(dict.get("Length"))?.as_i64().unwrap_or(0).max(0) as u64;
        let raw = self.stream_bytes(data_at, length)?;
        let raw = self.decrypted_stream(number, generation, &dict, raw)?;
        let bytes = self.decode_stream(&dict, raw)?;
        let count = self.resolve_opt(dict.get("N"))?.as_i64().filter(|n| *n >= 0).ok_or_else(damaged_xref)? as usize;
        let first =
            self.resolve_opt(dict.get("First"))?.as_i64().filter(|f| *f >= 0).ok_or_else(damaged_xref)? as usize;
        if count > MAX_OBJSTM_OBJECTS {
            return Err(EngineError::limit("a PDF object stream holds too many objects"));
        }
        if first > bytes.len() {
            return Err(damaged_xref());
        }
        let mut table = Vec::new();
        let mut lexer = Lexer::new(&bytes[..first], 0);
        for _ in 0..count {
            let object = lexer.unsigned().ok_or_else(damaged_xref)?;
            let relative = lexer.unsigned().ok_or_else(damaged_xref)?;
            let at =
                first.checked_add(usize::try_from(relative).map_err(|_| damaged_xref())?).ok_or_else(damaged_xref)?;
            if at >= bytes.len() {
                return Err(damaged_xref());
            }
            table.push((u32::try_from(object).map_err(|_| damaged_xref())?, at));
        }
        Ok(DecodedObjStm { bytes, table })
    }

    /// Decoded data of a stream object, if `number` is one this file can decode.
    fn stream_object_data(&mut self, number: u32) -> Result<Option<Vec<u8>>> {
        let Some(XrefEntry::Plain { offset, .. }) = self.xref.get(&number).copied() else {
            return Ok(None);
        };
        let (found, generation, value, data_at) = self.parse_value_at(offset)?;
        let (Obj::Dict(dict), Some(data_at), true) = (value, data_at, found == number) else {
            return Ok(None);
        };
        let length = self.resolve_opt(dict.get("Length"))?.as_i64().unwrap_or(0).max(0) as u64;
        let raw = self.stream_bytes(data_at, length)?;
        let raw = self.decrypted_stream(number, generation, &dict, raw)?;
        Ok(Some(self.decode_stream(&dict, raw)?))
    }

    fn decrypted_stream(&self, number: u32, generation: u16, dict: &Dict, raw: Vec<u8>) -> Result<Vec<u8>> {
        match &self.crypt {
            Some(crypt) => crypt.decrypt_stream(number, generation, dict, &raw),
            None => Ok(raw),
        }
    }

    /// Checks `password` if the file is encrypted. From here on, strings and
    /// streams read through this file are decrypted.
    pub(crate) fn unlock(&mut self, password: Option<&str>) -> Result<()> {
        let Some(encrypt) = self.trailer.get("Encrypt").cloned() else { return Ok(()) };
        let encrypt_object = match encrypt {
            Obj::Ref(number, _) => Some(number),
            _ => None,
        };
        let Obj::Dict(dict) = self.resolve_deep(&encrypt, 0)? else {
            return Err(EngineError::malformed("the PDF's encryption data is damaged"));
        };
        let ids = self.resolve_opt(self.trailer.get("ID").cloned().as_ref())?;
        let id0 = match ids {
            Obj::Array(ids) => match ids.first() {
                Some(first) => match self.resolve(first)? {
                    Obj::Str(bytes) => bytes,
                    _ => Vec::new(),
                },
                None => Vec::new(),
            },
            _ => Vec::new(),
        };
        self.crypt = Some(Decryptor::unlock(&dict, &id0, encrypt_object, password)?);
        // Anything already cached was read without a key.
        self.objstm_cache.clear();
        self.objstm_cached_bytes = 0;
        Ok(())
    }

    fn resolve_deep(&mut self, value: &Obj, depth: u32) -> Result<Obj> {
        if depth > MAX_RESOLVE_DEPTH {
            return Err(EngineError::malformed("the PDF's encryption data is damaged"));
        }
        Ok(match self.resolve(value)? {
            Obj::Array(items) => {
                let mut out = Vec::with_capacity(items.len());
                for item in &items {
                    out.push(self.resolve_deep(item, depth + 1)?);
                }
                Obj::Array(out)
            }
            Obj::Dict(dict) => {
                let mut out = Dict::default();
                for (key, item) in &dict.0 {
                    out.0.push((key.clone(), self.resolve_deep(item, depth + 1)?));
                }
                Obj::Dict(out)
            }
            other => other,
        })
    }

    /// True if `number` names an object that exists.
    pub(crate) fn has_object(&self, number: u32) -> bool {
        matches!(self.xref.get(&number), Some(XrefEntry::Plain { .. } | XrefEntry::InStream { .. }))
    }

    pub(crate) fn load(&mut self, number: u32) -> Result<LoadedObject> {
        if let Some(XrefEntry::Plain { offset, .. }) = self.xref.get(&number).copied() {
            if offset >= self.len {
                return Err(damaged_xref());
            }
            let (found, generation, mut value, stream_at) = self.parse_value_at(offset)?;
            if found != number {
                return Err(damaged_xref());
            }
            if let Some(crypt) = &self.crypt {
                crypt.decrypt_strings(number, generation, &mut value)?;
            }
            return Ok(LoadedObject { value, generation, stream_at });
        }
        Ok(LoadedObject { value: self.object(number)?, generation: 0, stream_at: None })
    }

    /// The stream's real length: `/Length` if `endstream` follows it, otherwise
    /// where `endstream` is, within the structure bound.
    pub(crate) fn stream_extent(&mut self, data_at: u64, length: u64) -> Result<u64> {
        if data_at.saturating_add(length) <= self.len {
            let after = self.read_at(data_at + length, 32)?;
            if Lexer::new(&after, 0).eat_keyword(b"endstream") {
                return Ok(length);
            }
        }
        Ok(self.stream_bytes(data_at, u64::MAX)?.len() as u64)
    }

    /// The stream's bytes as stored, which must fit the structure bound.
    pub(crate) fn stream_raw(&mut self, data_at: u64, length: u64) -> Result<Vec<u8>> {
        self.stream_bytes(data_at, length)
    }

    /// Hands over `length` bytes at `at` in bounded pieces.
    pub(crate) fn read_chunks(
        &mut self,
        mut at: u64,
        mut length: u64,
        mut sink: impl FnMut(&[u8]) -> Result<()>,
    ) -> Result<()> {
        while length > 0 {
            let want = usize::try_from(length).unwrap_or(SCAN_CHUNK).min(SCAN_CHUNK);
            let chunk = self.read_at(at, want)?;
            if chunk.is_empty() {
                return Err(EngineError::new(document_core::ErrorKind::Io, "the file changed while it was being read"));
            }
            sink(&chunk)?;
            at += chunk.len() as u64;
            length -= chunk.len() as u64;
        }
        Ok(())
    }

    /// `(major, minor)` from the header, if it reads as one.
    pub(crate) fn header_version(&mut self) -> Result<(u8, u8)> {
        let header = self.read_at(0, 8)?;
        Ok(match header.as_slice() {
            [b'%', b'P', b'D', b'F', b'-', major @ b'0'..=b'9', b'.', minor @ b'0'..=b'9'] => {
                (major - b'0', minor - b'0')
            }
            _ => (1, 4),
        })
    }

    /// True if the catalog's XMP claims PDF/A. Unreadable metadata can't be
    /// shown to be free of the claim, so it counts as claiming it.
    pub(crate) fn metadata_declares_pdfa(&mut self, catalog: &Dict) -> bool {
        let Some(Obj::Ref(metadata, _)) = catalog.get("Metadata") else { return false };
        match self.stream_object_data(*metadata) {
            Ok(Some(xmp)) => xmp.windows(7).any(|w| w == b"pdfaid:"),
            _ => true,
        }
    }

    pub(crate) fn catalog(&mut self) -> Result<Dict> {
        let root = self.trailer.get("Root").cloned().ok_or_else(damaged_xref)?;
        match self.resolve(&root)? {
            Obj::Dict(dict) => Ok(dict),
            _ => Err(EngineError::malformed("the PDF has no document catalog")),
        }
    }

    /// Pages in reading order, the order the platform renderer numbers them.
    /// `/Count` is ignored, as it is untrusted and only a hint.
    pub(crate) fn pages(&mut self) -> Result<Vec<PageRef>> {
        Ok(self.page_nodes()?.into_iter().map(|node| node.page).collect())
    }

    /// As [`Self::pages`], with what each page inherits.
    pub(crate) fn page_nodes(&mut self) -> Result<Vec<PageNode>> {
        struct Inherited {
            media: Option<[f64; 4]>,
            crop: Option<[f64; 4]>,
            rotate: Option<i64>,
            attributes: Rc<Dict>,
        }
        struct Frame {
            kids: Vec<Obj>,
            next: usize,
            inherited: Inherited,
        }

        let catalog = self.catalog()?;
        let root = catalog.get("Pages").cloned().ok_or_else(damaged_pages)?;
        let Obj::Ref(root_number, _) = root else { return Err(damaged_pages()) };

        let mut pages = Vec::new();
        let mut visited = HashSet::new();
        let mut stack = vec![Frame {
            kids: vec![Obj::Ref(root_number, 0)],
            next: 0,
            inherited: Inherited { media: None, crop: None, rotate: None, attributes: Rc::new(Dict::default()) },
        }];
        while let Some(frame) = stack.last_mut() {
            let Some(kid) = frame.kids.get(frame.next).cloned() else {
                stack.pop();
                continue;
            };
            frame.next += 1;
            let Obj::Ref(number, _) = kid else { return Err(damaged_pages()) };
            if !visited.insert(number) {
                return Err(EngineError::malformed("the PDF's page tree has a loop"));
            }
            let Obj::Dict(node) = self.object(number)? else { return Err(damaged_pages()) };
            let parent = &stack.last().expect("frame above").inherited;
            let (parent_media, parent_crop, parent_rotate) = (parent.media, parent.crop, parent.rotate);
            let parent_attributes = Rc::clone(&parent.attributes);
            let media = self.rect_entry(&node, "MediaBox")?.or(parent_media);
            let crop = self.rect_entry(&node, "CropBox")?.or(parent_crop);
            let rotate = self.resolve_opt(node.get("Rotate"))?.as_i64().or(parent_rotate);

            let is_tree = node.get("Type").and_then(Obj::as_name) == Some(b"Pages")
                || (node.get("Type").is_none() && node.get("Kids").is_some());
            if is_tree {
                if stack.len() > MAX_TREE_DEPTH {
                    return Err(EngineError::limit("the PDF's page tree is too deep"));
                }
                let Obj::Array(kids) = self.resolve_opt(node.get("Kids"))? else { return Err(damaged_pages()) };
                let attributes = with_own_attributes(&parent_attributes, &node);
                stack.push(Frame { kids, next: 0, inherited: Inherited { media, crop, rotate, attributes } });
            } else {
                if pages.len() >= MAX_PAGES {
                    return Err(EngineError::limit("the PDF has too many pages to edit"));
                }
                let geometry = PageGeometry::new(media.unwrap_or(DEFAULT_MEDIA_BOX), crop, rotate.unwrap_or(0));
                pages.push(PageNode { page: PageRef { object: number, geometry }, inherited: parent_attributes });
            }
        }
        if pages.is_empty() {
            return Err(damaged_pages());
        }
        Ok(pages)
    }

    fn rect_entry(&mut self, dict: &Dict, key: &str) -> Result<Option<[f64; 4]>> {
        let Obj::Array(items) = self.resolve_opt(dict.get(key))? else { return Ok(None) };
        if items.len() != 4 {
            return Ok(None);
        }
        let mut rect = [0.0; 4];
        for (slot, item) in rect.iter_mut().zip(&items) {
            match self.resolve(item)?.as_f64() {
                Some(value) => *slot = value,
                None => return Ok(None),
            }
        }
        Ok(Some(rect))
    }

    /// Scans the raw file for markers that may sit outside anything parsed,
    /// in bounded memory. Returns (has `/ByteRange` or `/Sig`, has a PDF/A identifier).
    pub(crate) fn scan_markers(&mut self) -> Result<(bool, bool)> {
        const BYTE_RANGE: &[u8] = b"/ByteRange";
        const SIG: &[u8] = b"/Sig";
        const PDFA: &[u8] = b"pdfaid:";
        let keep = BYTE_RANGE.len().max(PDFA.len()) - 1;
        let (mut signed, mut archival) = (false, false);
        let mut at = 0u64;
        let mut carry: Vec<u8> = Vec::new();
        while at < self.len && !(signed && archival) {
            let chunk = self.read_at(at, SCAN_CHUNK)?;
            if chunk.is_empty() {
                break;
            }
            at += chunk.len() as u64;
            carry.extend_from_slice(&chunk);
            signed = signed || carry.windows(BYTE_RANGE.len()).any(|w| w == BYTE_RANGE);
            // The name alone, not `/SigFlags` or `/SigQ`.
            signed = signed
                || carry.windows(SIG.len() + 1).any(|w| w.starts_with(SIG) && !w[SIG.len()].is_ascii_alphanumeric());
            archival = archival || carry.windows(PDFA.len()).any(|w| w == PDFA);
            let drop = carry.len().saturating_sub(keep);
            carry.drain(..drop);
        }
        Ok((signed, archival))
    }

    /// What a save would have to warn about.
    pub(crate) fn warnings(&mut self) -> Result<(bool, bool)> {
        let (mut signed, mut archival) = self.scan_markers()?;
        let catalog = self.catalog()?;
        // Signed fields and permissions can be in object streams, out of the scan's sight.
        if catalog.get("Perms").is_some() {
            signed = true;
        }
        if let Obj::Dict(form) = self.resolve_opt(catalog.get("AcroForm"))? {
            if self.resolve_opt(form.get("SigFlags"))?.as_i64().is_some_and(|flags| flags & 1 != 0) {
                signed = true;
            }
        }
        // Compressed metadata hides the identifier from the scan. An unreadable one proves nothing.
        if !archival {
            if let Some(Obj::Ref(metadata, _)) = catalog.get("Metadata") {
                if let Ok(Some(xmp)) = self.stream_object_data(*metadata) {
                    archival = xmp.windows(7).any(|w| w == b"pdfaid:");
                }
            }
        }
        Ok((signed, archival))
    }
}

/// What pages below `node` inherit, sharing the parent's when nothing changes.
fn with_own_attributes(parent: &Rc<Dict>, node: &Dict) -> Rc<Dict> {
    const INHERITED: [&str; 3] = ["MediaBox", "CropBox", "Resources"];
    if INHERITED.iter().all(|key| node.get(key).is_none()) {
        return Rc::clone(parent);
    }
    let mut attributes = (**parent).clone();
    for key in INHERITED {
        if let Some(value) = node.get(key) {
            attributes.set(key, value.clone());
        }
    }
    Rc::new(attributes)
}

fn int_array(value: Option<&Obj>) -> Option<Vec<i64>> {
    match value {
        Some(Obj::Array(items)) => items.iter().map(Obj::as_i64).collect(),
        _ => None,
    }
}

fn parse_indirect(bytes: &[u8], complete: bool) -> std::result::Result<(u32, u16, Obj, Option<usize>), SyntaxError> {
    let mut lexer = Lexer::new(bytes, 0);
    let number = lexer.unsigned().ok_or(short_or_invalid(&lexer))?;
    let generation = lexer.unsigned().ok_or(short_or_invalid(&lexer))?;
    if !lexer.eat_keyword(b"obj") {
        return Err(short_or_invalid(&lexer));
    }
    let number = u32::try_from(number).map_err(|_| SyntaxError::Invalid)?;
    let generation = u16::try_from(generation).map_err(|_| SyntaxError::Invalid)?;
    // An object with no value, as some writers leave behind, is null.
    let value = if lexer.eat_keyword(b"endobj") { Obj::Null } else { lexer.object()? };
    // A window ending inside the keyword would otherwise lose the stream.
    lexer.skip_space();
    if !complete && lexer.pos + b"stream\r\n".len() >= bytes.len() {
        return Err(SyntaxError::Truncated);
    }
    let mut data_at = None;
    if matches!(value, Obj::Dict(_)) && lexer.eat_keyword(b"stream") {
        let mut at = lexer.pos;
        match bytes.get(at) {
            Some(b'\r') => {
                at += 1;
                if bytes.get(at) == Some(&b'\n') {
                    at += 1;
                }
            }
            Some(b'\n') => at += 1,
            None => return Err(SyntaxError::Truncated),
            _ => {}
        }
        data_at = Some(at);
    }
    Ok((number, generation, value, data_at))
}

fn short_or_invalid(lexer: &Lexer<'_>) -> SyntaxError {
    if lexer.pos + 32 >= lexer.buf.len() { SyntaxError::Truncated } else { SyntaxError::Invalid }
}

fn parse_xref_table(
    lexer: &mut Lexer<'_>,
    entries: &mut Vec<(u32, XrefEntry)>,
) -> std::result::Result<Dict, SyntaxError> {
    loop {
        if lexer.eat_keyword(b"trailer") {
            return match lexer.object()? {
                Obj::Dict(dict) => Ok(dict),
                _ => Err(SyntaxError::Invalid),
            };
        }
        let start = lexer.unsigned().ok_or(short_or_invalid(lexer))?;
        let count = lexer.unsigned().ok_or(short_or_invalid(lexer))?;
        if start.saturating_add(count) > u64::from(MAX_OBJECT_NUMBER) + 1 {
            return Err(SyntaxError::Invalid);
        }
        // `count` is untrusted: the loop stops at the first token that isn't there.
        for number in start..start + count {
            let offset = lexer.unsigned().ok_or(short_or_invalid(lexer))?;
            let generation = lexer.unsigned().ok_or(short_or_invalid(lexer))?;
            let entry = if lexer.eat_keyword(b"n") {
                XrefEntry::Plain { offset, generation: u16::try_from(generation).map_err(|_| SyntaxError::Invalid)? }
            } else if lexer.eat_keyword(b"f") {
                XrefEntry::Free
            } else {
                return Err(short_or_invalid(lexer));
            };
            entries.push((number as u32, entry));
            if entries.len() > MAX_XREF_ENTRIES {
                return Err(SyntaxError::TooLarge);
            }
        }
    }
}

fn undo_predictor(data: Vec<u8>, parms: &Dict) -> Result<Vec<u8>> {
    let predictor = parms.get("Predictor").and_then(Obj::as_i64).unwrap_or(1);
    if predictor == 1 {
        return Ok(data);
    }
    let colors = parms.get("Colors").and_then(Obj::as_i64).unwrap_or(1);
    let bits = parms.get("BitsPerComponent").and_then(Obj::as_i64).unwrap_or(8);
    let columns = parms.get("Columns").and_then(Obj::as_i64).unwrap_or(1);
    if !(10..=15).contains(&predictor) || colors != 1 || bits != 8 || !(1..=1 << 16).contains(&columns) {
        return Err(EngineError::unsupported("a PDF stream uses an unsupported predictor"));
    }
    let columns = columns as usize;
    let mut out = Vec::with_capacity(data.len());
    let mut previous = vec![0u8; columns];
    let mut current = vec![0u8; columns];
    for row in data.chunks(columns + 1) {
        let Some((&tag, values)) = row.split_first() else { continue };
        if values.len() != columns {
            return Err(EngineError::malformed("a PDF stream's predictor data is damaged"));
        }
        for (index, &value) in values.iter().enumerate() {
            let left = if index > 0 { current[index - 1] } else { 0 };
            let up = previous[index];
            let corner = if index > 0 { previous[index - 1] } else { 0 };
            current[index] = match tag {
                0 => value,
                1 => value.wrapping_add(left),
                2 => value.wrapping_add(up),
                3 => value.wrapping_add(((u16::from(left) + u16::from(up)) / 2) as u8),
                4 => value.wrapping_add(paeth(left, up, corner)),
                _ => return Err(EngineError::malformed("a PDF stream's predictor data is damaged")),
            };
        }
        out.extend_from_slice(&current);
        std::mem::swap(&mut previous, &mut current);
    }
    Ok(out)
}

fn paeth(left: u8, up: u8, corner: u8) -> u8 {
    let estimate = i32::from(left) + i32::from(up) - i32::from(corner);
    let (a, b, c) =
        ((estimate - i32::from(left)).abs(), (estimate - i32::from(up)).abs(), (estimate - i32::from(corner)).abs());
    if a <= b && a <= c {
        left
    } else if b <= c {
        up
    } else {
        corner
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn geometry_round_trips_at_every_rotation() {
        for rotate in [0, 90, 180, 270, -90, 450] {
            let page = PageGeometry::new([10.0, 20.0, 110.0, 220.0], None, rotate);
            let (x, y) = page.to_user(0.25, 0.75);
            let (u, v) = page.to_display(x, y);
            assert!((u - 0.25).abs() < 1e-9 && (v - 0.75).abs() < 1e-9, "rotate {rotate}");
        }
    }

    #[test]
    fn geometry_maps_the_displayed_top_left_corner() {
        let at = |rotate| PageGeometry::new([0.0, 0.0, 100.0, 200.0], None, rotate).to_user(0.0, 0.0);
        assert_eq!(at(0), (0.0, 200.0));
        assert_eq!(at(90), (0.0, 0.0));
        assert_eq!(at(180), (100.0, 0.0));
        assert_eq!(at(270), (100.0, 200.0));
    }

    #[test]
    fn geometry_shows_the_intersection_of_media_and_crop() {
        let page = PageGeometry::new([0.0, 0.0, 100.0, 100.0], Some([50.0, 50.0, 200.0, 200.0]), 0);
        assert_eq!((page.x0, page.y0, page.x1, page.y1), (50.0, 50.0, 100.0, 100.0));
        // A crop box off the page is ignored.
        let page = PageGeometry::new([0.0, 0.0, 100.0, 100.0], Some([500.0, 500.0, 600.0, 600.0]), 0);
        assert_eq!(page.x1, 100.0);
        // An empty media box falls back rather than dividing by zero.
        let page = PageGeometry::new([0.0, 0.0, 0.0, 0.0], None, 0);
        assert_eq!(page.x1, 612.0);
    }

    #[test]
    fn predictor_up_is_undone() {
        let parms = Dict::of(vec![("Predictor", Obj::int(12)), ("Columns", Obj::int(2))]);
        let out = undo_predictor(vec![2, 1, 2, 2, 1, 1], &parms).unwrap();
        assert_eq!(out, vec![1, 2, 2, 3]);
    }

    #[test]
    fn predictor_refuses_bad_input() {
        let parms = Dict::of(vec![("Predictor", Obj::int(12)), ("Columns", Obj::int(2))]);
        assert!(undo_predictor(vec![2, 1], &parms).is_err());
        assert!(undo_predictor(vec![9, 1, 1], &parms).is_err());
        let tiff = Dict::of(vec![("Predictor", Obj::int(2))]);
        assert!(undo_predictor(vec![1], &tiff).is_err());
    }
}
