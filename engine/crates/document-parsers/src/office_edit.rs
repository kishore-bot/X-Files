//! Find and replace inside office packages (DOCX, PPTX, ODT, ODP, ODS).
//!
//! HLD: a package is inspected once into a [`Layout`] (kind and target XML
//! parts). Matching runs on a *segment* per part: the logical text of the part,
//! where paragraph ends, tabs, line breaks and ODF spaces are real characters
//! ("\n", "\t", " "). A segment is the whole part for word processing and
//! presentation formats; for ODS each table cell is its own segment, so a match
//! never spans two cells.
//!
//! LLD:
//! - Tokens: a byte lexer produces tags, text and CDATA ranges. Nothing is
//!   re-serialised; only the ranges that change are replaced.
//! - Segment: `logical` text plus `pieces` that tile it. A piece is a text node
//!   (`Role::Text`), a removable separator element (`Role::Sep`: `w:tab`,
//!   `w:br`, `a:br`, `text:tab`, `text:line-break`, `text:s`) or a paragraph end
//!   (`Role::ParaEnd`, always kept).
//! - Matching: the query and the text are both normalised so every whitespace
//!   run is one ' '. KMP runs over the normalised chars; every normalised char
//!   carries the logical byte range it came from, so a hit maps back to a source
//!   range. A query space therefore matches a tab, a line break, a paragraph end
//!   or a run of spaces.
//! - Replacement: the replacement goes into one text piece (the one holding the
//!   match start, or the nearest text piece when the match starts on a
//!   separator). Text covered by the match is emptied. Separators covered by the
//!   match are removed. Paragraph ends covered by the match are kept, so the
//!   paragraph structure is unchanged. Formatting of the first run is kept.
//! - Index: [`scan_parts`] collects matches (part, segment, logical range,
//!   snippet) one part range at a time, so a caller can stream progress and
//!   cancel between parts.
//!
//! Limits: replacement text is written literally. A tab or newline in it is
//! stored as a plain character, not as a `w:tab` / `w:br` element.
//! Paragraphs are not joined when a match spans them.

use std::collections::HashSet;
use std::io::{Read, Seek, SeekFrom, Write};
use std::ops::Range;

use document_core::{CancelToken, EngineError, Limits, ReadSeek, Result};
use flate2::Compression;
use flate2::write::DeflateEncoder;

use crate::zip::{ZipArchive, from_io};

/// Longest accepted search text, in characters.
pub const MAX_FIND_CHARS: usize = 1_000;
/// Largest accepted replacement text, in bytes.
pub const MAX_REPLACEMENT_BYTES: usize = 64 * 1024;
/// Most matches one index keeps. Beyond this the index reports `truncated`.
pub const MAX_INDEXED_MATCHES: usize = 100_000;
/// Characters of context kept on each side of a match in a snippet.
const SNIPPET_CHARS: usize = 40;
/// Upper bound for one `text:s` run, so a corrupt count cannot blow up memory.
const MAX_SPACES: usize = 1_000;
/// Chunk size for streaming non-target entries through the writer.
const COPY_CHUNK: usize = 64 * 1024;

const STORED: u16 = 0;
const DEFLATED: u16 = 8;
const LOCAL_SIG: u32 = 0x0403_4b50;
const CENTRAL_SIG: u32 = 0x0201_4b50;
const EOCD_SIG: u32 = 0x0605_4b50;
/// General-purpose flag bit 11: names are UTF-8.
const UTF8_FLAG: u16 = 0x0800;
/// DOS date 1980-01-01, a fixed timestamp so output is reproducible.
const DOS_DATE: u16 = 0x0021;

/// The office format of a package, detected from its contents.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OfficeKind {
    Docx,
    Pptx,
    Odt,
    Odp,
    Ods,
}

/// What to look for. `find` is matched literally (whitespace runs are
/// collapsed), never as a pattern. `include_number_cells` also searches ODS
/// cells whose value is a number, date or similar; their stored value is not
/// changed by a replacement.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OfficeQuery {
    pub find: String,
    pub case_sensitive: bool,
    pub whole_word: bool,
    pub include_number_cells: bool,
}

impl OfficeQuery {
    /// Identity of the query. Equal keys yield equal matches in the same document.
    pub fn cache_key(&self) -> String {
        format!(
            "{}\u{1f}{}{}{}",
            self.find, self.case_sensitive as u8, self.whole_word as u8, self.include_number_cells as u8
        )
    }
}

/// The kind of a package and the XML parts that carry visible text, in archive order.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Layout {
    pub kind: OfficeKind,
    pub parts: Vec<String>,
}

/// One match in the index. `part` is the ordinal in [`Layout::parts`],
/// `segment` the segment ordinal inside that part, and `start..end` the byte
/// range inside the segment's logical text.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IndexedMatch {
    pub part: u32,
    pub segment: u32,
    pub start: u32,
    pub end: u32,
    pub snippet: String,
}

/// Matches found in a range of parts. `truncated` means the cap was reached.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ScanOutput {
    pub matches: Vec<IndexedMatch>,
    pub truncated: bool,
}

/// Result of a replacement. `total` counts all matches in the document before
/// the edit, `replaced` the ones actually rewritten.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct OfficeReplaceReport {
    pub total: u32,
    pub replaced: u32,
}

/// Inspects a package once: its kind and its target parts.
pub fn inspect<R: ReadSeek + 'static>(reader: R, size: u64, cancel: &CancelToken) -> Result<Layout> {
    let mut archive = open_archive(reader, size, cancel)?;
    let kind = detect_kind(&mut archive)?;
    Ok(Layout { kind, parts: target_names(&archive, kind) })
}

/// Collects matches in `layout.parts[parts]`, stopping at `limit` matches.
/// Callers run this per part range so progress can be reported and cancelled.
pub fn scan_parts<R: ReadSeek + 'static>(
    reader: R,
    size: u64,
    query: &OfficeQuery,
    layout: &Layout,
    parts: Range<usize>,
    limit: usize,
    cancel: &CancelToken,
) -> Result<ScanOutput> {
    let matcher = Matcher::new(query)?;
    let mut archive = open_archive(reader, size, cancel)?;
    let grammar = Grammar { kind: layout.kind, include_numbers: query.include_number_cells };
    let mut pass = Pass::new(&matcher, grammar, "", Mode::Collect { limit });
    let end = parts.end.min(layout.parts.len());
    for ordinal in parts.start..end {
        cancel.check()?;
        let name = layout.parts[ordinal].as_str();
        let bytes = archive.read_to_vec(name)?;
        let ctx = PartCtx { ordinal: u32_of(ordinal), name };
        rewrite_part(&mut pass, decode_part(&bytes)?, ctx, usize::MAX)?;
        if pass.truncated {
            break;
        }
    }
    Ok(ScanOutput { matches: std::mem::take(&mut pass.found), truncated: pass.truncated })
}

/// Writes an edited copy of the package to `out`. With `only = None` every
/// match is replaced; with `Some(i)` only the match at index `i` (the index
/// order of [`scan_parts`] over the whole document). The output is not
/// verified here; see [`verify_reopens`].
pub fn replace_matches<R: ReadSeek + 'static, W: Write + Seek>(
    reader: R,
    size: u64,
    out: W,
    query: &OfficeQuery,
    replacement: &str,
    only: Option<u32>,
    cancel: &CancelToken,
) -> Result<OfficeReplaceReport> {
    if replacement.len() > MAX_REPLACEMENT_BYTES {
        return Err(EngineError::limit(format!(
            "the replacement text is longer than {MAX_REPLACEMENT_BYTES} bytes"
        )));
    }
    let matcher = Matcher::new(query)?;
    let mut archive = open_archive(reader, size, cancel)?;
    let kind = detect_kind(&mut archive)?;
    let grammar = Grammar { kind, include_numbers: query.include_number_cells };
    let targets: HashSet<String> = target_names(&archive, kind).into_iter().collect();
    let entries: Vec<(String, bool)> = archive.entries().iter().map(|e| (e.name.clone(), e.is_dir)).collect();
    let max_part = usize::try_from(archive.limits().max_part_bytes).unwrap_or(usize::MAX);
    let mut pass = Pass::new(&matcher, grammar, replacement, Mode::Replace { only });
    let mut zip = ZipWriter::new(out);

    for (name, is_dir) in entries {
        cancel.check()?;
        if is_dir {
            zip.write_entry(&name, STORED, &[])?;
        } else if targets.contains(&name) {
            let bytes = archive.read_to_vec(&name)?;
            let ctx = PartCtx { ordinal: 0, name: &name };
            let rewritten = rewrite_part(&mut pass, decode_part(&bytes)?, ctx, max_part)?;
            let data: &[u8] = match &rewritten {
                Some(new) => new,
                None => &bytes,
            };
            zip.write_entry(&name, DEFLATED, data)?;
        } else if name == "mimetype" {
            // ODF requires this entry to be stored and uncompressed.
            let bytes = archive.read_to_vec(&name)?;
            zip.write_entry(&name, STORED, &bytes)?;
        } else {
            zip.write_streamed(&mut archive, &name, cancel)?;
        }
    }
    zip.finish()?;
    Ok(OfficeReplaceReport { total: pass.seen, replaced: pass.replaced })
}

/// Re-opens a written file through the engine and drains every batch. A
/// failure means the output is not a document the engine can read.
pub fn verify_reopens<R: ReadSeek + 'static>(reader: R, cancel: &CancelToken) -> Result<()> {
    let mut session = crate::engine(Limits::default()).open(reader)?;
    while session.next_batch()?.is_some() {
        cancel.check()?;
    }
    Ok(())
}

fn open_archive<R: ReadSeek + 'static>(reader: R, size: u64, cancel: &CancelToken) -> Result<ZipArchive> {
    let limits = Limits::default();
    if size > limits.max_input_bytes {
        return Err(EngineError::limit(format!(
            "the file is {size} bytes; the limit for editing is {} bytes",
            limits.max_input_bytes
        )));
    }
    ZipArchive::open(Box::new(reader), size, limits, cancel.clone())
}

const UNSUPPORTED: &str = "only DOCX, PPTX, ODT, ODP and ODS files can be searched and edited";

fn detect_kind(archive: &mut ZipArchive) -> Result<OfficeKind> {
    if archive.find("word/document.xml").is_some() {
        return Ok(OfficeKind::Docx);
    }
    if archive.find("ppt/presentation.xml").is_some() {
        return Ok(OfficeKind::Pptx);
    }
    if archive.find("content.xml").is_some() && archive.find("mimetype").is_some() {
        let mime = archive.read_to_vec("mimetype")?;
        return match String::from_utf8_lossy(&mime).trim() {
            "application/vnd.oasis.opendocument.text" => Ok(OfficeKind::Odt),
            "application/vnd.oasis.opendocument.presentation" => Ok(OfficeKind::Odp),
            "application/vnd.oasis.opendocument.spreadsheet" => Ok(OfficeKind::Ods),
            _ => Err(EngineError::unsupported(UNSUPPORTED)),
        };
    }
    Err(EngineError::unsupported(UNSUPPORTED))
}

/// The XML parts that carry visible text, in archive order. Counting and
/// replacing walk this list in the same order, so match indices agree.
fn target_names(archive: &ZipArchive, kind: OfficeKind) -> Vec<String> {
    archive.entries().iter().filter(|e| !e.is_dir && is_target(kind, &e.name)).map(|e| e.name.clone()).collect()
}

fn is_target(kind: OfficeKind, name: &str) -> bool {
    match kind {
        OfficeKind::Docx => {
            matches!(name, "word/document.xml" | "word/footnotes.xml" | "word/endnotes.xml")
                || name.strip_prefix("word/").is_some_and(|rest| {
                    (rest.starts_with("header") || rest.starts_with("footer"))
                        && rest.ends_with(".xml")
                        && !rest.contains('/')
                })
        }
        OfficeKind::Pptx => {
            (name.starts_with("ppt/slides/slide") || name.starts_with("ppt/notesSlides/notesSlide"))
                && name.ends_with(".xml")
        }
        OfficeKind::Odt | OfficeKind::Odp | OfficeKind::Ods => matches!(name, "content.xml" | "styles.xml"),
    }
}

fn decode_part(bytes: &[u8]) -> Result<&str> {
    std::str::from_utf8(bytes).map_err(|_| EngineError::malformed("an office XML part is not UTF-8"))
}

fn u32_of(n: usize) -> u32 {
    u32::try_from(n).unwrap_or(u32::MAX)
}

// ---------------------------------------------------------------------------
// Pattern matching
// ---------------------------------------------------------------------------

#[derive(Debug)]
struct Matcher {
    pattern: Vec<char>,
    /// KMP failure table for `pattern`.
    fail: Vec<usize>,
    case_sensitive: bool,
    whole_word: bool,
}

impl Matcher {
    fn new(query: &OfficeQuery) -> Result<Self> {
        if query.find.is_empty() {
            return Err(EngineError::malformed("the text to find cannot be empty"));
        }
        if query.find.chars().count() > MAX_FIND_CHARS {
            return Err(EngineError::limit(format!("the text to find is longer than {MAX_FIND_CHARS} characters")));
        }
        let mut pattern = Vec::with_capacity(query.find.len());
        let mut in_space = false;
        for c in query.find.chars() {
            if c.is_whitespace() {
                if !in_space {
                    pattern.push(' ');
                }
                in_space = true;
            } else {
                in_space = false;
                fold_into(c, query.case_sensitive, &mut pattern);
            }
        }
        let fail = failure_table(&pattern);
        Ok(Self { pattern, fail, case_sensitive: query.case_sensitive, whole_word: query.whole_word })
    }
}

/// Appends the comparison form of `c`. Case-insensitive matching compares
/// lowercase forms, so one character can expand to several (e.g. `İ`).
fn fold_into(c: char, case_sensitive: bool, out: &mut Vec<char>) {
    if case_sensitive {
        out.push(c);
    } else {
        out.extend(c.to_lowercase());
    }
}

fn failure_table(pattern: &[char]) -> Vec<usize> {
    let mut fail = vec![0; pattern.len()];
    let mut k = 0;
    for i in 1..pattern.len() {
        while k > 0 && pattern[i] != pattern[k] {
            k = fail[k - 1];
        }
        if pattern[i] == pattern[k] {
            k += 1;
        }
        fail[i] = k;
    }
    fail
}

/// Non-overlapping matches as byte ranges of `logical`, in order. Every run of
/// whitespace (including separators) is one normalised ' ', mapped back to the
/// whole run's source range.
fn find_matches(logical: &str, m: &Matcher) -> Vec<(usize, usize)> {
    // Normalised chars, each tagged with the logical byte range it came from.
    let mut folded: Vec<(char, usize, usize)> = Vec::with_capacity(logical.len());
    let mut buf: Vec<char> = Vec::with_capacity(4);
    let mut in_space = false;
    for (i, c) in logical.char_indices() {
        let end = i + c.len_utf8();
        if c.is_whitespace() {
            if in_space {
                if let Some(last) = folded.last_mut() {
                    last.2 = end;
                }
            } else {
                folded.push((' ', i, end));
                in_space = true;
            }
            continue;
        }
        in_space = false;
        buf.clear();
        fold_into(c, m.case_sensitive, &mut buf);
        folded.extend(buf.iter().map(|&f| (f, i, end)));
    }

    let p = &m.pattern;
    let plen = p.len();
    let mut found = Vec::new();
    let mut q = 0usize;
    for idx in 0..folded.len() {
        let c = folded[idx].0;
        while q > 0 && c != p[q] {
            q = m.fail[q - 1];
        }
        if c == p[q] {
            q += 1;
        }
        if q == plen {
            let first = idx + 1 - plen;
            let (s, e) = (folded[first].1, folded[idx].2);
            if !m.whole_word || is_whole_word(logical, s, e) {
                found.push((s, e));
                q = 0;
            } else {
                q = m.fail[q - 1];
            }
        }
    }
    found
}

fn is_word_char(c: char) -> bool {
    c.is_alphanumeric() || c == '_'
}

fn is_whole_word(logical: &str, s: usize, e: usize) -> bool {
    let before = logical[..s].chars().next_back().is_some_and(is_word_char);
    let after = logical[e..].chars().next().is_some_and(is_word_char);
    !before && !after
}

/// Context around `[s, e)`, up to `SNIPPET_CHARS` characters on each side.
/// Line breaks and tabs show as spaces.
fn snippet(logical: &str, s: usize, e: usize) -> String {
    let start = logical[..s].char_indices().rev().nth(SNIPPET_CHARS - 1).map_or(0, |(i, _)| i);
    let end = logical[e..].char_indices().nth(SNIPPET_CHARS).map_or(logical.len(), |(i, _)| e + i);
    logical[start..end].replace(['\n', '\t'], " ")
}

// ---------------------------------------------------------------------------
// XML tokens
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Kind {
    Start,
    End,
    Empty,
    Text,
    Cdata,
    Other,
}

/// One lexical token of the part. `start..end` is its raw byte range and
/// `name` the element name for tags (empty otherwise).
#[derive(Debug, Clone, Copy)]
struct Tok {
    start: usize,
    end: usize,
    kind: Kind,
    name: (usize, usize),
}

fn tokenize(xml: &str) -> Result<Vec<Tok>> {
    let b = xml.as_bytes();
    let mut toks = Vec::with_capacity(b.len() / 16 + 8);
    let mut i = 0;
    while i < b.len() {
        if b[i] != b'<' {
            let end = b[i..].iter().position(|&c| c == b'<').map_or(b.len(), |p| i + p);
            toks.push(Tok { start: i, end, kind: Kind::Text, name: (i, i) });
            i = end;
            continue;
        }
        let (end, kind, name) = if b[i..].starts_with(b"<!--") {
            (find_seq(b, i + 4, b"-->")? + 3, Kind::Other, (i, i))
        } else if b[i..].starts_with(b"<![CDATA[") {
            (find_seq(b, i + 9, b"]]>")? + 3, Kind::Cdata, (i, i))
        } else if b[i..].starts_with(b"<?") {
            (find_seq(b, i + 2, b"?>")? + 2, Kind::Other, (i, i))
        } else if b[i..].starts_with(b"<!") {
            (find_byte(b, i + 2, b'>')? + 1, Kind::Other, (i, i))
        } else {
            let end = tag_end(b, i)?;
            let closing = b.get(i + 1) == Some(&b'/');
            let name_start = if closing { i + 2 } else { i + 1 };
            let mut name_end = name_start;
            while name_end < end && !matches!(b[name_end], b' ' | b'\t' | b'\r' | b'\n' | b'/' | b'>') {
                name_end += 1;
            }
            let kind = if closing {
                Kind::End
            } else if b[end - 2] == b'/' {
                Kind::Empty
            } else {
                Kind::Start
            };
            (end, kind, (name_start, name_end))
        };
        toks.push(Tok { start: i, end, kind, name });
        i = end;
    }
    Ok(toks)
}

/// Index just past the `>` that closes the tag opened at `from`. Quoted
/// attribute values may contain `>`.
fn tag_end(b: &[u8], from: usize) -> Result<usize> {
    let mut quote = 0u8;
    for (j, &c) in b.iter().enumerate().skip(from + 1) {
        if quote != 0 {
            if c == quote {
                quote = 0;
            }
        } else if c == b'"' || c == b'\'' {
            quote = c;
        } else if c == b'>' {
            return Ok(j + 1);
        }
    }
    Err(EngineError::malformed("an XML tag is not terminated"))
}

fn find_byte(b: &[u8], from: usize, byte: u8) -> Result<usize> {
    b[from..]
        .iter()
        .position(|&c| c == byte)
        .map(|p| from + p)
        .ok_or_else(|| EngineError::malformed("unterminated XML markup"))
}

fn find_seq(b: &[u8], from: usize, pat: &[u8]) -> Result<usize> {
    b[from..]
        .windows(pat.len())
        .position(|w| w == pat)
        .map(|p| from + p)
        .ok_or_else(|| EngineError::malformed("unterminated XML markup"))
}

fn name_of<'a>(b: &'a [u8], tok: &Tok) -> &'a [u8] {
    &b[tok.name.0..tok.name.1]
}

/// Value of attribute `name` in a raw tag, without the quotes.
fn attr<'a>(raw: &'a str, name: &str) -> Option<&'a str> {
    let key = format!("{name}=\"");
    let start = raw.find(key.as_str())? + key.len();
    let end = raw[start..].find('"')? + start;
    Some(&raw[start..end])
}

// ---------------------------------------------------------------------------
// Grammar and segments
// ---------------------------------------------------------------------------

/// Which elements form paragraphs, cells, separators and text, per format.
#[derive(Debug, Clone, Copy)]
struct Grammar {
    kind: OfficeKind,
    include_numbers: bool,
}

/// What a separator element contributes to the logical text.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Sep {
    Literal(&'static str),
    Spaces(usize),
}

impl Grammar {
    fn is_unit(self, name: &[u8]) -> bool {
        match self.kind {
            OfficeKind::Docx => name == b"w:p",
            OfficeKind::Pptx => name == b"a:p",
            OfficeKind::Odt | OfficeKind::Odp | OfficeKind::Ods => name == b"text:p" || name == b"text:h",
        }
    }

    /// ODS cells are segment boundaries.
    fn is_cell(self, name: &[u8]) -> bool {
        self.kind == OfficeKind::Ods && name == b"table:table-cell"
    }

    /// Text nodes count only when their innermost open element is one of these.
    fn captures(self, name: &[u8]) -> bool {
        match self.kind {
            OfficeKind::Docx => name == b"w:t",
            OfficeKind::Pptx => name == b"a:t",
            OfficeKind::Odt | OfficeKind::Odp | OfficeKind::Ods => {
                name == b"text:p" || name == b"text:h" || name == b"text:span" || name == b"text:a"
            }
        }
    }

    /// The separator for an empty element `name` inside `parent`, if any. The
    /// parent check keeps `w:tabs` tab stops out of the text.
    fn separator(self, parent: &[u8], name: &[u8], raw: &str) -> Option<Sep> {
        match self.kind {
            OfficeKind::Docx if parent == b"w:r" => match name {
                b"w:tab" => Some(Sep::Literal("\t")),
                b"w:br" | b"w:cr" => Some(Sep::Literal("\n")),
                _ => None,
            },
            OfficeKind::Pptx if parent == b"a:p" => match name {
                b"a:br" => Some(Sep::Literal("\n")),
                _ => None,
            },
            OfficeKind::Docx | OfficeKind::Pptx => None,
            _ => {
                let text_parent = parent == b"text:p"
                    || parent == b"text:h"
                    || parent == b"text:span"
                    || parent == b"text:a";
                if !text_parent {
                    return None;
                }
                match name {
                    b"text:tab" => Some(Sep::Literal("\t")),
                    b"text:line-break" => Some(Sep::Literal("\n")),
                    b"text:s" => {
                        let count = attr(raw, "text:c").and_then(|v| v.parse::<usize>().ok()).unwrap_or(1);
                        Some(Sep::Spaces(count.clamp(1, MAX_SPACES)))
                    }
                    _ => None,
                }
            }
        }
    }

    /// DOCX text needs `xml:space="preserve"` to keep leading or trailing spaces.
    fn preserves_space(self) -> bool {
        self.kind == OfficeKind::Docx
    }
}

/// Whether an ODS cell's text is searched: text cells always, other value
/// types only when `include_numbers` is set.
fn cell_eligible(raw: &str, include_numbers: bool) -> bool {
    match attr(raw, "office:value-type") {
        Some("string") => true,
        Some(_) => include_numbers,
        None => false,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Role {
    Text,
    Sep,
    ParaEnd,
}

/// A piece of a segment's logical text. `tok` is the token that renders it
/// (text or separator element; unused for paragraph ends), `open` the element
/// that encloses a text node.
#[derive(Debug, Clone, Copy)]
struct Piece {
    role: Role,
    tok: usize,
    open: usize,
    start: usize,
    end: usize,
}

/// Logical text of one segment. Pieces tile `logical` in order.
#[derive(Debug, Default)]
struct Segment {
    logical: String,
    pieces: Vec<Piece>,
}

impl Segment {
    fn push(&mut self, role: Role, tok: usize, open: usize, text: &str) {
        let start = self.logical.len();
        self.logical.push_str(text);
        self.pieces.push(Piece { role, tok, open, start, end: self.logical.len() });
    }
}

fn build_segments(xml: &str, toks: &[Tok], grammar: Grammar, part: &str) -> Result<Vec<Segment>> {
    let b = xml.as_bytes();
    let mut segments = vec![Segment::default()];
    let mut open: Vec<usize> = Vec::new();
    // One entry per open unit (paragraph): whether its text is searched.
    let mut unit_accept: Vec<bool> = Vec::new();
    // One entry per open ODS cell: whether its text is searched.
    let mut cell_ok: Vec<bool> = Vec::new();
    let ods_styles = grammar.kind == OfficeKind::Ods && part == "styles.xml";

    for (ti, tok) in toks.iter().enumerate() {
        match tok.kind {
            Kind::Start => {
                let name = name_of(b, tok);
                if grammar.is_cell(name) {
                    cell_ok.push(cell_eligible(&xml[tok.start..tok.end], grammar.include_numbers));
                    segments.push(Segment::default());
                }
                if grammar.is_unit(name) {
                    let accept = match grammar.kind {
                        OfficeKind::Ods => ods_styles || cell_ok.last() == Some(&true),
                        _ => true,
                    };
                    unit_accept.push(accept);
                }
                open.push(ti);
            }
            Kind::End => {
                let name = name_of(b, tok);
                let Some(top) = open.pop() else {
                    return Err(EngineError::malformed("an XML closing tag has no opening tag"));
                };
                if name_of(b, &toks[top]) != name {
                    return Err(EngineError::malformed("XML elements are not properly nested"));
                }
                if grammar.is_unit(name) && unit_accept.pop() == Some(true) {
                    if let Some(seg) = segments.last_mut() {
                        seg.push(Role::ParaEnd, usize::MAX, usize::MAX, "\n");
                    }
                }
                if grammar.is_cell(name) {
                    cell_ok.pop();
                    segments.push(Segment::default());
                }
            }
            Kind::Empty => {
                if unit_accept.last() != Some(&true) {
                    continue;
                }
                let Some(&parent) = open.last() else { continue };
                let name = name_of(b, tok);
                let parent_name = name_of(b, &toks[parent]);
                let raw = &xml[tok.start..tok.end];
                let Some(sep) = grammar.separator(parent_name, name, raw) else { continue };
                if let Some(seg) = segments.last_mut() {
                    match sep {
                        Sep::Literal(text) => seg.push(Role::Sep, ti, parent, text),
                        Sep::Spaces(n) => seg.push(Role::Sep, ti, parent, &" ".repeat(n)),
                    }
                }
            }
            Kind::Text | Kind::Cdata => {
                if unit_accept.last() != Some(&true) {
                    continue;
                }
                let Some(&parent) = open.last() else { continue };
                if !grammar.captures(name_of(b, &toks[parent])) {
                    continue;
                }
                let decoded = if tok.kind == Kind::Cdata {
                    xml[tok.start + 9..tok.end - 3].to_owned()
                } else {
                    unescape(&xml[tok.start..tok.end])
                };
                if let Some(seg) = segments.last_mut() {
                    seg.push(Role::Text, ti, parent, &decoded);
                }
            }
            Kind::Other => {}
        }
    }
    if !open.is_empty() {
        return Err(EngineError::malformed("an XML element is not closed"));
    }
    Ok(segments)
}

fn unescape(raw: &str) -> String {
    if !raw.contains('&') {
        return raw.to_owned();
    }
    let mut out = String::with_capacity(raw.len());
    let mut rest = raw;
    while let Some(pos) = rest.find('&') {
        out.push_str(&rest[..pos]);
        let after = &rest[pos..];
        let decoded = after
            .find(';')
            .filter(|&semi| semi <= 12)
            .and_then(|semi| decode_entity(&after[1..semi]).map(|c| (c, semi + 1)));
        match decoded {
            Some((c, used)) => {
                out.push(c);
                rest = &after[used..];
            }
            None => {
                out.push('&');
                rest = &after[1..];
            }
        }
    }
    out.push_str(rest);
    out
}

fn decode_entity(name: &str) -> Option<char> {
    match name {
        "amp" => Some('&'),
        "lt" => Some('<'),
        "gt" => Some('>'),
        "quot" => Some('"'),
        "apos" => Some('\''),
        _ => {
            let num = name.strip_prefix('#')?;
            let code = match num.strip_prefix('x').or_else(|| num.strip_prefix('X')) {
                Some(hex) => u32::from_str_radix(hex, 16).ok()?,
                None => num.parse::<u32>().ok()?,
            };
            char::from_u32(code)
        }
    }
}

fn escape_into(text: &str, out: &mut Vec<u8>) {
    for c in text.chars() {
        match c {
            '&' => out.extend_from_slice(b"&amp;"),
            '<' => out.extend_from_slice(b"&lt;"),
            '>' => out.extend_from_slice(b"&gt;"),
            _ => {
                let mut buf = [0u8; 4];
                out.extend_from_slice(c.encode_utf8(&mut buf).as_bytes());
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Planning and rendering edits
// ---------------------------------------------------------------------------

/// One replacement inside a segment. `piece` receives the replacement text at
/// logical offset `at`.
#[derive(Debug, Clone, Copy)]
struct Edit {
    s: usize,
    e: usize,
    piece: usize,
    at: usize,
}

/// Chooses where a match's replacement goes. Returns None when the segment has
/// no text piece at all.
fn plan_edit(seg: &Segment, s: usize, e: usize) -> Option<Edit> {
    let pieces = &seg.pieces;
    let first = pieces.partition_point(|p| p.end <= s);
    if let Some(p) = pieces.get(first) {
        if p.role == Role::Text && p.start <= s {
            return Some(Edit { s, e, piece: first, at: s });
        }
    }
    // The first text piece the match covers.
    let mut i = first;
    while let Some(p) = pieces.get(i) {
        if p.start >= e {
            break;
        }
        if p.role == Role::Text {
            return Some(Edit { s, e, piece: i, at: p.start.max(s) });
        }
        i += 1;
    }
    // A match made only of separators: the nearest text before, else after.
    if let Some(i) = pieces[..first].iter().rposition(|p| p.role == Role::Text) {
        return Some(Edit { s, e, piece: i, at: pieces[i].end });
    }
    let after = first + pieces[first..].iter().position(|p| p.role == Role::Text)?;
    Some(Edit { s, e, piece: after, at: pieces[after].start })
}

/// Whether any edit (sorted, non-overlapping) touches `[ps, pe)`.
fn overlaps(edits: &[Edit], ps: usize, pe: usize) -> bool {
    let first = edits.partition_point(|ed| ed.e <= ps);
    edits.get(first).is_some_and(|ed| ed.s < pe)
}

/// Text of text piece `pi` after the edits: covered text is dropped and the
/// replacement is inserted at each anchored edit's position.
fn render_text(seg: &Segment, pi: usize, edits: &[Edit], anchored: &[usize], replacement: &str) -> String {
    let p = seg.pieces[pi];
    // (position, kind, end): kind 0 inserts the replacement, kind 1 drops
    // `position..end`. Inserts sort before drops at the same position.
    let mut events: Vec<(usize, u8, usize)> = Vec::new();
    let first = edits.partition_point(|ed| ed.e <= p.start);
    for ed in edits[first..].iter().take_while(|ed| ed.s < p.end) {
        events.push((ed.s.max(p.start), 1, ed.e.min(p.end)));
    }
    for &ei in anchored {
        events.push((edits[ei].at, 0, 0));
    }
    events.sort_unstable();

    let mut out = String::with_capacity(p.end - p.start + replacement.len());
    let mut cur = p.start;
    for (pos, kind, end) in events {
        if pos > cur {
            out.push_str(&seg.logical[cur..pos]);
            cur = pos;
        }
        if kind == 0 {
            out.push_str(replacement);
        } else {
            cur = cur.max(end);
        }
    }
    if cur < p.end {
        out.push_str(&seg.logical[cur..p.end]);
    }
    out
}

// ---------------------------------------------------------------------------
// Rewriting one part
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Mode {
    /// Collect matches for the index, at most `limit` of them.
    Collect { limit: usize },
    /// Replace matches; `only` restricts it to one document index.
    Replace { only: Option<u32> },
}

/// Where a part sits in the layout. `ordinal` is only meaningful when collecting.
#[derive(Debug, Clone, Copy)]
struct PartCtx<'n> {
    ordinal: u32,
    name: &'n str,
}

struct Pass<'a> {
    matcher: &'a Matcher,
    grammar: Grammar,
    replacement: &'a str,
    mode: Mode,
    /// Matches seen so far across the document; this is the global index.
    seen: u32,
    replaced: u32,
    found: Vec<IndexedMatch>,
    truncated: bool,
}

impl<'a> Pass<'a> {
    fn new(matcher: &'a Matcher, grammar: Grammar, replacement: &'a str, mode: Mode) -> Self {
        Self { matcher, grammar, replacement, mode, seen: 0, replaced: 0, found: Vec::new(), truncated: false }
    }
}

struct Modification {
    start: usize,
    end: usize,
    bytes: Vec<u8>,
}

/// Rewrites one part. In collect mode it only fills `pass.found` and returns
/// None. In replace mode it returns None when nothing changed, so the caller
/// can keep the original bytes.
fn rewrite_part(pass: &mut Pass<'_>, xml: &str, part: PartCtx<'_>, max_bytes: usize) -> Result<Option<Vec<u8>>> {
    let toks = tokenize(xml)?;
    let segments = build_segments(xml, &toks, pass.grammar, part.name)?;

    let only = match pass.mode {
        Mode::Collect { limit } => {
            for (seg_no, seg) in segments.iter().enumerate() {
                for (s, e) in find_matches(&seg.logical, pass.matcher) {
                    if pass.found.len() >= limit {
                        pass.truncated = true;
                        return Ok(None);
                    }
                    pass.found.push(IndexedMatch {
                        part: part.ordinal,
                        segment: u32_of(seg_no),
                        start: u32_of(s),
                        end: u32_of(e),
                        snippet: snippet(&seg.logical, s, e),
                    });
                }
            }
            return Ok(None);
        }
        Mode::Replace { only } => only,
    };

    let b = xml.as_bytes();
    let mut mods: Vec<Modification> = Vec::new();
    let mut preserved: HashSet<usize> = HashSet::new();

    for seg in &segments {
        if seg.pieces.is_empty() {
            continue;
        }
        let matches = find_matches(&seg.logical, pass.matcher);
        if matches.is_empty() {
            continue;
        }
        let mut edits: Vec<Edit> = Vec::new();
        for (s, e) in matches {
            let index = pass.seen;
            pass.seen = pass.seen.saturating_add(1);
            if only.is_none_or(|o| o == index) {
                if let Some(edit) = plan_edit(seg, s, e) {
                    edits.push(edit);
                    pass.replaced += 1;
                }
            }
        }
        if edits.is_empty() {
            continue;
        }
        let mut anchored: Vec<Vec<usize>> = vec![Vec::new(); seg.pieces.len()];
        for (ei, ed) in edits.iter().enumerate() {
            anchored[ed.piece].push(ei);
        }

        for (pi, piece) in seg.pieces.iter().enumerate() {
            match piece.role {
                Role::Text => {
                    if anchored[pi].is_empty() && !overlaps(&edits, piece.start, piece.end) {
                        continue;
                    }
                    let text = render_text(seg, pi, &edits, &anchored[pi], pass.replacement);
                    let tok = toks[piece.tok];
                    let mut bytes = Vec::with_capacity(text.len());
                    escape_into(&text, &mut bytes);
                    if pass.grammar.preserves_space() && needs_preserve(&text) {
                        let open = toks[piece.open];
                        let raw = &xml[open.start..open.end];
                        if !raw.contains("xml:space") && preserved.insert(piece.open) {
                            let mut open_bytes = Vec::with_capacity(raw.len() + 22);
                            open_bytes.extend_from_slice(&b[open.start..open.end - 1]);
                            open_bytes.extend_from_slice(b" xml:space=\"preserve\">");
                            mods.push(Modification { start: open.start, end: open.end, bytes: open_bytes });
                        }
                    }
                    mods.push(Modification { start: tok.start, end: tok.end, bytes });
                }
                Role::Sep => {
                    if overlaps(&edits, piece.start, piece.end) {
                        let tok = toks[piece.tok];
                        mods.push(Modification { start: tok.start, end: tok.end, bytes: Vec::new() });
                    }
                }
                Role::ParaEnd => {}
            }
        }
    }

    if mods.is_empty() {
        return Ok(None);
    }
    mods.sort_by_key(|m| m.start);
    let mut out = Vec::with_capacity(xml.len() + 64);
    let mut pos = 0;
    for m in &mods {
        out.extend_from_slice(&b[pos..m.start]);
        out.extend_from_slice(&m.bytes);
        pos = m.end;
    }
    out.extend_from_slice(&b[pos..]);
    if out.len() > max_bytes {
        return Err(EngineError::limit("the edited document part grew past the part size limit"));
    }
    Ok(Some(out))
}

fn needs_preserve(text: &str) -> bool {
    text.starts_with(char::is_whitespace) || text.ends_with(char::is_whitespace)
}

// ---------------------------------------------------------------------------
// ZIP output
// ---------------------------------------------------------------------------

struct CentralRecord {
    name: String,
    method: u16,
    crc: u32,
    compressed: u32,
    size: u32,
    header_offset: u32,
}

/// Sequential ZIP writer. Only the local headers are patched after the fact,
/// so the output needs `Seek` but never a second full copy.
struct ZipWriter<W: Write + Seek> {
    out: W,
    central: Vec<CentralRecord>,
}

impl<W: Write + Seek> ZipWriter<W> {
    fn new(out: W) -> Self {
        Self { out, central: Vec::new() }
    }

    /// Writes an entry whose bytes are already in memory.
    fn write_entry(&mut self, name: &str, method: u16, data: &[u8]) -> Result<()> {
        let crc = crc32fast::hash(data);
        let compressed: Vec<u8>;
        let payload: &[u8] = if method == DEFLATED {
            let mut enc = DeflateEncoder::new(Vec::new(), Compression::default());
            enc.write_all(data)?;
            compressed = enc.finish()?;
            &compressed
        } else {
            data
        };
        let header_offset = to_u32(self.out.stream_position()?)?;
        let csize = to_u32(payload.len() as u64)?;
        let size = to_u32(data.len() as u64)?;
        self.write_local_header(name, method, crc, csize, size)?;
        self.out.write_all(payload)?;
        self.central.push(CentralRecord { name: name.to_owned(), method, crc, compressed: csize, size, header_offset });
        Ok(())
    }

    /// Copies a non-target entry: decompress in chunks, recompress into the
    /// output, then patch CRC and sizes into the header.
    fn write_streamed(&mut self, archive: &mut ZipArchive, name: &str, cancel: &CancelToken) -> Result<()> {
        let header_pos = self.out.stream_position()?;
        let header_offset = to_u32(header_pos)?;
        self.write_local_header(name, DEFLATED, 0, 0, 0)?;
        let data_start = self.out.stream_position()?;
        let mut hasher = crc32fast::Hasher::new();
        let mut size: u64 = 0;
        let mut buf = vec![0u8; COPY_CHUNK];
        {
            let mut enc = DeflateEncoder::new(&mut self.out, Compression::default());
            let mut entry = archive.stream(name)?;
            loop {
                cancel.check()?;
                let n = entry.read(&mut buf).map_err(|e| from_io(&e))?;
                if n == 0 {
                    break;
                }
                hasher.update(&buf[..n]);
                size += n as u64;
                enc.write_all(&buf[..n])?;
            }
            archive.finish(entry);
            enc.finish()?;
        }
        let end = self.out.stream_position()?;
        let crc = hasher.finalize();
        let csize = to_u32(end - data_start)?;
        let size32 = to_u32(size)?;
        self.out.seek(SeekFrom::Start(header_pos + 14))?;
        let mut patch = Vec::with_capacity(12);
        push_u32(&mut patch, crc);
        push_u32(&mut patch, csize);
        push_u32(&mut patch, size32);
        self.out.write_all(&patch)?;
        self.out.seek(SeekFrom::Start(end))?;
        self.central.push(CentralRecord {
            name: name.to_owned(),
            method: DEFLATED,
            crc,
            compressed: csize,
            size: size32,
            header_offset,
        });
        Ok(())
    }

    fn write_local_header(&mut self, name: &str, method: u16, crc: u32, csize: u32, size: u32) -> Result<()> {
        let name_len = u16::try_from(name.len()).map_err(|_| EngineError::limit("a ZIP entry name is too long"))?;
        let mut h = Vec::with_capacity(30 + name.len());
        push_u32(&mut h, LOCAL_SIG);
        push_u16(&mut h, 20);
        push_u16(&mut h, UTF8_FLAG);
        push_u16(&mut h, method);
        push_u16(&mut h, 0);
        push_u16(&mut h, DOS_DATE);
        push_u32(&mut h, crc);
        push_u32(&mut h, csize);
        push_u32(&mut h, size);
        push_u16(&mut h, name_len);
        push_u16(&mut h, 0);
        h.extend_from_slice(name.as_bytes());
        self.out.write_all(&h)?;
        Ok(())
    }

    fn finish(mut self) -> Result<()> {
        let cd_start = self.out.stream_position()?;
        let mut dir = Vec::new();
        for r in &self.central {
            let name_len =
                u16::try_from(r.name.len()).map_err(|_| EngineError::limit("a ZIP entry name is too long"))?;
            push_u32(&mut dir, CENTRAL_SIG);
            push_u16(&mut dir, 20);
            push_u16(&mut dir, 20);
            push_u16(&mut dir, UTF8_FLAG);
            push_u16(&mut dir, r.method);
            push_u16(&mut dir, 0);
            push_u16(&mut dir, DOS_DATE);
            push_u32(&mut dir, r.crc);
            push_u32(&mut dir, r.compressed);
            push_u32(&mut dir, r.size);
            push_u16(&mut dir, name_len);
            push_u16(&mut dir, 0); // extra length
            push_u16(&mut dir, 0); // comment length
            push_u16(&mut dir, 0); // disk number
            push_u16(&mut dir, 0); // internal attributes
            push_u32(&mut dir, 0); // external attributes
            push_u32(&mut dir, r.header_offset);
            dir.extend_from_slice(r.name.as_bytes());
        }
        self.out.write_all(&dir)?;
        let count = u16::try_from(self.central.len()).map_err(|_| EngineError::limit("too many ZIP entries"))?;
        let mut end = Vec::with_capacity(22);
        push_u32(&mut end, EOCD_SIG);
        push_u16(&mut end, 0);
        push_u16(&mut end, 0);
        push_u16(&mut end, count);
        push_u16(&mut end, count);
        push_u32(&mut end, to_u32(dir.len() as u64)?);
        push_u32(&mut end, to_u32(cd_start)?);
        push_u16(&mut end, 0);
        self.out.write_all(&end)?;
        self.out.flush()?;
        Ok(())
    }
}

fn to_u32(n: u64) -> Result<u32> {
    u32::try_from(n).map_err(|_| EngineError::limit("the edited file is too large for this format"))
}

fn push_u16(out: &mut Vec<u8>, v: u16) {
    out.extend_from_slice(&v.to_le_bytes());
}

fn push_u32(out: &mut Vec<u8>, v: u32) {
    out.extend_from_slice(&v.to_le_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::zip::testing::zip;
    use std::io::Cursor;

    fn query(find: &str, case_sensitive: bool, whole_word: bool) -> OfficeQuery {
        OfficeQuery { find: find.to_owned(), case_sensitive, whole_word, include_number_cells: false }
    }

    /// Rewrites one part in replace mode. Returns the new text (None when
    /// unchanged) and the number of matches replaced.
    fn run_in(
        xml: &str,
        kind: OfficeKind,
        name: &str,
        q: &OfficeQuery,
        replacement: &str,
        only: Option<u32>,
    ) -> (Option<String>, u32) {
        let matcher = Matcher::new(q).expect("valid query");
        let grammar = Grammar { kind, include_numbers: q.include_number_cells };
        let mut pass = Pass::new(&matcher, grammar, replacement, Mode::Replace { only });
        let ctx = PartCtx { ordinal: 0, name };
        let out = rewrite_part(&mut pass, xml, ctx, usize::MAX).expect("part rewrites");
        (out.map(|v| String::from_utf8(v).expect("utf-8 output")), pass.replaced)
    }

    fn run(xml: &str, kind: OfficeKind, q: &OfficeQuery, replacement: &str, only: Option<u32>) -> (Option<String>, u32) {
        run_in(xml, kind, "word/document.xml", q, replacement, only)
    }

    #[test]
    fn match_split_across_runs_keeps_first_run_formatting() {
        let xml = "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Hel</w:t></w:r>\
                   <w:r><w:t>lo wor</w:t></w:r><w:r><w:t>ld</w:t></w:r></w:p>";
        let (out, n) = run(xml, OfficeKind::Docx, &query("Hello world", true, false), "Hi there", None);
        assert_eq!(n, 1);
        assert_eq!(
            out.unwrap(),
            "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Hi there</w:t></w:r>\
             <w:r><w:t></w:t></w:r><w:r><w:t></w:t></w:r></w:p>"
        );
    }

    #[test]
    fn match_inside_one_run_leaves_neighbours_intact() {
        let xml = "<w:p><w:r><w:t>one two</w:t></w:r><w:r><w:t> three</w:t></w:r></w:p>";
        let (out, n) = run(xml, OfficeKind::Docx, &query("two", true, false), "2", None);
        assert_eq!(n, 1);
        assert_eq!(out.unwrap(), "<w:p><w:r><w:t>one 2</w:t></w:r><w:r><w:t> three</w:t></w:r></w:p>");
    }

    #[test]
    fn entities_are_decoded_for_matching_and_escaped_on_output() {
        let xml = "<w:p><w:r><w:t>Tom &amp; Jerry</w:t></w:r></w:p>";
        let (out, n) = run(xml, OfficeKind::Docx, &query("Tom & Jerry", true, false), "Tom & Tim <3", None);
        assert_eq!(n, 1);
        assert_eq!(out.unwrap(), "<w:p><w:r><w:t>Tom &amp; Tim &lt;3</w:t></w:r></w:p>");
    }

    #[test]
    fn case_insensitive_matching_ignores_case_only_when_asked() {
        let xml = "<w:p><w:r><w:t>Cat CAT cat</w:t></w:r></w:p>";
        let (_, sensitive) = run(xml, OfficeKind::Docx, &query("cat", true, false), "x", None);
        let (out, insensitive) = run(xml, OfficeKind::Docx, &query("cat", false, false), "dog", None);
        assert_eq!(sensitive, 1);
        assert_eq!(insensitive, 3);
        assert_eq!(out.unwrap(), "<w:p><w:r><w:t>dog dog dog</w:t></w:r></w:p>");
    }

    #[test]
    fn whole_word_skips_substrings() {
        let xml = "<w:p><w:r><w:t>cat catalog cat_x cat</w:t></w:r></w:p>";
        let (_, n) = run(xml, OfficeKind::Docx, &query("cat", true, true), "dog", None);
        assert_eq!(n, 2);
    }

    #[test]
    fn replace_one_touches_only_the_requested_index() {
        let xml = "<w:p><w:r><w:t>a a a</w:t></w:r></w:p>";
        let (out, n) = run(xml, OfficeKind::Docx, &query("a", true, true), "b", Some(1));
        assert_eq!(n, 1);
        assert_eq!(out.unwrap(), "<w:p><w:r><w:t>a b a</w:t></w:r></w:p>");
    }

    #[test]
    fn match_spanning_paragraphs_keeps_paragraph_structure() {
        let xml = "<w:p><w:r><w:t>foo</w:t></w:r></w:p><w:p><w:r><w:t>bar</w:t></w:r></w:p>";
        let (out, n) = run(xml, OfficeKind::Docx, &query("foo bar", true, false), "x", None);
        assert_eq!(n, 1);
        assert_eq!(
            out.unwrap(),
            "<w:p><w:r><w:t>x</w:t></w:r></w:p><w:p><w:r><w:t></w:t></w:r></w:p>"
        );
    }

    #[test]
    fn query_space_matches_tab_and_line_break_and_removes_them() {
        let tab = "<w:p><w:r><w:t>a</w:t><w:tab/><w:t>b</w:t></w:r></w:p>";
        let (out, n) = run(tab, OfficeKind::Docx, &query("a  b", true, false), "X", None);
        assert_eq!(n, 1);
        assert_eq!(out.unwrap(), "<w:p><w:r><w:t>X</w:t><w:t></w:t></w:r></w:p>");

        let br = "<w:p><w:r><w:t>end</w:t><w:br/><w:t>of line</w:t></w:r></w:p>";
        let (out, n) = run(br, OfficeKind::Docx, &query("end of line", true, false), "END", None);
        assert_eq!(n, 1);
        assert_eq!(out.unwrap(), "<w:p><w:r><w:t>END</w:t><w:t></w:t></w:r></w:p>");
    }

    #[test]
    fn tab_stops_in_paragraph_properties_are_not_text() {
        let xml = "<w:p><w:pPr><w:tabs><w:tab w:val=\"left\" w:pos=\"720\"/></w:tabs></w:pPr><w:r><w:t>a</w:t></w:r></w:p>";
        let (_, n) = run(xml, OfficeKind::Docx, &query("a", true, false), "b", None);
        assert_eq!(n, 1);
        let (_, none) = run(xml, OfficeKind::Docx, &query("a b", true, false), "b", None);
        assert_eq!(none, 0);
    }

    #[test]
    fn leading_space_in_replacement_adds_xml_space_preserve() {
        let xml = "<w:p><w:r><w:t>Hello</w:t></w:r></w:p>";
        let (out, _) = run(xml, OfficeKind::Docx, &query("Hello", true, false), " Hi ", None);
        assert_eq!(out.unwrap(), "<w:p><w:r><w:t xml:space=\"preserve\"> Hi </w:t></w:r></w:p>");
    }

    #[test]
    fn pptx_runs_are_matched_through_drawingml_text() {
        let xml = "<a:p><a:r><a:rPr lang=\"en\"/><a:t>Quar</a:t></a:r><a:r><a:t>terly</a:t></a:r></a:p>";
        let (out, n) = run(xml, OfficeKind::Pptx, &query("Quarterly", true, false), "Annual", None);
        assert_eq!(n, 1);
        assert_eq!(
            out.unwrap(),
            "<a:p><a:r><a:rPr lang=\"en\"/><a:t>Annual</a:t></a:r><a:r><a:t></a:t></a:r></a:p>"
        );
    }

    #[test]
    fn odf_text_inside_spans_is_matched_across_spans() {
        let xml = "<text:p>Some <text:span text:style-name=\"T1\">bold</text:span> text</text:p>";
        let (out, n) = run(xml, OfficeKind::Odt, &query("Some bold text", true, false), "Hi", None);
        assert_eq!(n, 1);
        assert_eq!(out.unwrap(), "<text:p>Hi<text:span text:style-name=\"T1\"></text:span></text:p>");
    }

    #[test]
    fn ods_cells_are_separate_segments_and_numbers_are_skipped_by_default() {
        let xml = "<table:table-cell office:value-type=\"string\"><text:p>Total <text:span>cost</text:span></text:p></table:table-cell>\
                   <table:table-cell office:value-type=\"float\" office:value=\"12\"><text:p>12</text:p></table:table-cell>";
        let (_, across) = run_in(xml, OfficeKind::Ods, "content.xml", &query("cost 12", true, false), "x", None);
        assert_eq!(across, 0);
        let (_, number) = run_in(xml, OfficeKind::Ods, "content.xml", &query("12", true, false), "99", None);
        assert_eq!(number, 0);
        let (out, text) = run_in(xml, OfficeKind::Ods, "content.xml", &query("Total cost", true, false), "Sum", None);
        assert_eq!(text, 1);
        assert_eq!(
            out.unwrap(),
            "<table:table-cell office:value-type=\"string\"><text:p>Sum<text:span></text:span></text:p></table:table-cell>\
             <table:table-cell office:value-type=\"float\" office:value=\"12\"><text:p>12</text:p></table:table-cell>"
        );
        let numbers = OfficeQuery { include_number_cells: true, ..query("12", true, false) };
        let (_, included) = run_in(xml, OfficeKind::Ods, "content.xml", &numbers, "99", None);
        assert_eq!(included, 1);
    }

    #[test]
    fn collect_reports_matches_with_snippets_without_rewriting() {
        let xml = "<w:p><w:r><w:t>alpha beta</w:t></w:r><w:r><w:t> beta gamma</w:t></w:r></w:p>";
        let matcher = Matcher::new(&query("beta", true, false)).unwrap();
        let grammar = Grammar { kind: OfficeKind::Docx, include_numbers: false };
        let mut pass = Pass::new(&matcher, grammar, "", Mode::Collect { limit: 10 });
        let ctx = PartCtx { ordinal: 3, name: "word/document.xml" };
        assert!(rewrite_part(&mut pass, xml, ctx, usize::MAX).unwrap().is_none());
        assert_eq!(pass.found.len(), 2);
        assert_eq!((pass.found[0].part, pass.found[0].start, pass.found[0].end), (3, 6, 10));
        assert_eq!(pass.found[0].snippet.trim_end(), "alpha beta beta gamma");
    }

    #[test]
    fn full_package_round_trip_keeps_other_parts_and_reopens() {
        let document = b"<w:document><w:body><w:p><w:r><w:t>Draft</w:t></w:r></w:p></w:body></w:document>";
        let source = zip(&[("word/document.xml", &document[..]), ("word/media/a.bin", &b"\x00\x01\x02"[..])]);
        let len = source.len() as u64;
        let mut out = Cursor::new(Vec::new());
        let cancel = CancelToken::default();
        let report = replace_matches(
            Cursor::new(source),
            len,
            &mut out,
            &query("Draft", true, false),
            "Final",
            None,
            &cancel,
        )
        .expect("replace succeeds");
        assert_eq!(report, OfficeReplaceReport { total: 1, replaced: 1 });
        let bytes = out.into_inner();
        let mut archive = ZipArchive::open(
            Box::new(Cursor::new(bytes.clone())),
            bytes.len() as u64,
            Limits::default(),
            CancelToken::default(),
        )
        .expect("output opens");
        let text = archive.read_to_vec("word/document.xml").unwrap();
        assert!(String::from_utf8(text).unwrap().contains("<w:t>Final</w:t>"));
        assert_eq!(archive.read_to_vec("word/media/a.bin").unwrap(), b"\x00\x01\x02");
    }
}
