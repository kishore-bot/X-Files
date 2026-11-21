//! Word 97-2003 (`.doc`): the main document's text, as paragraphs.
//!
//! Text comes from the piece table (CLX) in the table stream, in ANSI or UTF-16
//! pieces. Paragraph properties (FKP pages) add headings, through the style
//! sheet's built-in Heading 1-9, and table rows. Both are best effort: a file
//! whose properties can't be read still shows its text, as plain paragraphs.
//!
//! This is text extraction, not layout. Dropped: fonts and inline styling,
//! pictures, list numbers and bullets, headers, footers, footnotes, comments,
//! text boxes, and link targets (field results stay, field codes go). Text
//! marked hidden and tracked deletions are not told apart from ordinary text,
//! so they show. Word 95 and older, and encrypted documents, are refused with a
//! typed error.

use std::collections::{HashMap, VecDeque};

use document_core::{Block, CancelToken, EngineError, ErrorKind, Result, Rich};

use crate::cfb::{CfbFile, MAX_STREAM_BYTES, le16, le32};
use crate::stream::{BlockSource, MAX_PENDING_TEXT};

const WORD_MAGIC: u16 = 0xA5EC;
const WORD6_MAGIC: u16 = 0xA5DC;
/// Word 97 writes 193; earlier beta files 192. Word 95 and older are below that.
const MIN_NFIB: u16 = 0xC0;
const FLAG_TABLE_STREAM_1: u16 = 0x0200;
const FLAG_ENCRYPTED: u16 = 0x0100;
const FLAG_OBFUSCATED: u16 = 0x8000;
const CCP_TEXT_INDEX: usize = 3;
const FC_STSHF_INDEX: usize = 1;
const FC_PLCF_BTE_PAPX_INDEX: usize = 13;
const FC_CLX_INDEX: usize = 33;
const FKP_PAGE: usize = 512;
const MAX_PIECES: usize = 1_000_000;
const MAX_PARAGRAPH_RUNS: usize = 1_000_000;
const MAX_FIELD_DEPTH: usize = 32;
const MAX_ROW_CELLS: usize = 256;
/// Characters decoded per [`BlockSource::pump`], bounding the block queue.
const CHUNK_CHARS: usize = 64 * 1024;

/// Windows-1252 for 0x80-0x9F; every other byte maps to the same code point.
const CP1252_HIGH: [u16; 32] = [
    0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D,
    0x017D, 0x008F, 0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A,
    0x0153, 0x009D, 0x017E, 0x0178,
];

fn cp1252(byte: u8) -> char {
    let unit = match byte {
        0x80..=0x9F => CP1252_HIGH[usize::from(byte - 0x80)],
        _ => u16::from(byte),
    };
    char::from_u32(u32::from(unit)).unwrap_or(char::REPLACEMENT_CHARACTER)
}

#[derive(Debug, Clone, Copy)]
struct Piece {
    /// Byte offset in the WordDocument stream.
    offset: usize,
    chars: usize,
    compressed: bool,
}

#[derive(Debug, Clone, Copy)]
struct ParagraphRun {
    start: u32,
    end: u32,
    heading: Option<u8>,
    in_table: bool,
    row_end: bool,
}

pub(crate) struct DocSource {
    word: Vec<u8>,
    pieces: Vec<Piece>,
    runs: Vec<ParagraphRun>,
    piece: usize,
    done_in_piece: usize,
    text: String,
    row: Vec<String>,
    /// One entry per open field: `true` while in its code, `false` in its result.
    fields: Vec<bool>,
    /// Fields past the nesting cap, whose marks are ignored.
    ignored_fields: usize,
    last_was_cell_mark: bool,
    cancel: CancelToken,
}

/// Where a FIB table entry lives in the stream: the header's variable-size parts come first.
fn fib_pair(word: &[u8], index: usize) -> Option<(usize, usize)> {
    let csw = usize::from(le16(word, 32)?);
    let lw_count_at = 34 + csw * 2;
    let cslw = usize::from(le16(word, lw_count_at)?);
    let pairs_count_at = lw_count_at + 2 + cslw * 4;
    let pairs = usize::from(le16(word, pairs_count_at)?);
    if index >= pairs {
        return None;
    }
    let at = pairs_count_at + 2 + index * 8;
    Some((le32(word, at)? as usize, le32(word, at + 4)? as usize))
}

fn ccp_text(word: &[u8]) -> Option<usize> {
    let csw = usize::from(le16(word, 32)?);
    let lw_count_at = 34 + csw * 2;
    Some(le32(word, lw_count_at + 2 + CCP_TEXT_INDEX * 4)? as usize)
}

impl DocSource {
    pub fn open(cfb: &mut CfbFile, cancel: CancelToken) -> Result<Self> {
        let word = cfb.read_stream("WordDocument", MAX_STREAM_BYTES)?;
        match le16(&word, 0) {
            Some(WORD_MAGIC) => {}
            Some(WORD6_MAGIC) => return Err(EngineError::unsupported("Word 95 and older documents are not supported")),
            _ => return Err(EngineError::malformed("the Word document's header is not valid")),
        }
        let flags = le16(&word, 0x0A).ok_or_else(|| EngineError::malformed("the Word document's header is cut off"))?;
        if flags & (FLAG_ENCRYPTED | FLAG_OBFUSCATED) != 0 {
            return Err(EngineError::new(
                ErrorKind::UnsupportedEncryption,
                "This Word 97-2003 document uses an older kind of password protection that isn't supported.",
            ));
        }
        if le16(&word, 2).is_none_or(|nfib| nfib < MIN_NFIB) {
            return Err(EngineError::unsupported("Word 95 and older documents are not supported"));
        }
        let table_name = if flags & FLAG_TABLE_STREAM_1 != 0 { "1Table" } else { "0Table" };
        let table = cfb.read_stream(table_name, MAX_STREAM_BYTES)?;

        let cut_off = || EngineError::malformed("the Word document's header is cut off");
        let ccp = ccp_text(&word).ok_or_else(cut_off)?;
        let (fc_clx, lcb_clx) = fib_pair(&word, FC_CLX_INDEX).ok_or_else(cut_off)?;
        let clx = table
            .get(fc_clx..fc_clx.saturating_add(lcb_clx))
            .ok_or_else(|| EngineError::malformed("the Word document's text table lies outside its stream"))?;
        let pieces = parse_pieces(clx, ccp, word.len())?;
        cancel.check()?;
        let runs = read_paragraph_runs(&word, &table).unwrap_or_default();

        Ok(Self {
            word,
            pieces,
            runs,
            piece: 0,
            done_in_piece: 0,
            text: String::new(),
            row: Vec::new(),
            fields: Vec::new(),
            ignored_fields: 0,
            last_was_cell_mark: false,
            cancel,
        })
    }

    fn run_at(&self, offset: usize) -> Option<ParagraphRun> {
        let offset = u32::try_from(offset).ok()?;
        let index = self.runs.partition_point(|r| r.end <= offset);
        self.runs.get(index).copied().filter(|r| r.start <= offset)
    }

    fn take_text(&mut self) -> String {
        let mut text = std::mem::take(&mut self.text);
        text.truncate(text.trim_end().len());
        text
    }

    fn end_paragraph(&mut self, offset: usize, out: &mut VecDeque<Block>) {
        let run = self.run_at(offset);
        if run.is_some_and(|r| r.in_table) {
            // A paragraph inside a cell: the cell mark ends the cell, not this.
            if !self.text.is_empty() {
                self.text.push('\n');
            }
            return;
        }
        let text = self.take_text();
        if text.trim().is_empty() {
            return;
        }
        let heading = run.and_then(|r| r.heading);
        out.push_back(match heading {
            Some(level) => Block::Heading { level, text: Rich::plain(text) },
            None => Block::paragraph(text),
        });
    }

    /// Without paragraph properties, a row's end is recognised as a second
    /// mark in a row. That also ends a row early at an empty cell, which is
    /// the best that can be done.
    fn end_cell(&mut self, offset: usize, out: &mut VecDeque<Block>) {
        let row_end = match self.run_at(offset) {
            Some(run) => run.row_end,
            None => self.text.is_empty() && self.last_was_cell_mark,
        };
        if row_end {
            self.text.clear();
            self.flush_row(out);
        } else {
            let cell = self.take_text();
            self.row.push(cell);
            if self.row.len() >= MAX_ROW_CELLS {
                self.flush_row(out);
            }
        }
    }

    fn flush_row(&mut self, out: &mut VecDeque<Block>) {
        let cells = std::mem::take(&mut self.row);
        if cells.iter().any(|c| !c.is_empty()) {
            out.push_back(Block::TableRow { row: None, header: false, cells, styles: Vec::new() });
        }
    }

    fn push_char(&mut self, ch: char, offset: usize, out: &mut VecDeque<Block>) {
        match ch {
            '\u{13}' => {
                if self.fields.len() < MAX_FIELD_DEPTH {
                    self.fields.push(true);
                } else {
                    self.ignored_fields += 1;
                }
                return;
            }
            '\u{14}' => {
                if self.ignored_fields == 0
                    && let Some(in_code) = self.fields.last_mut()
                {
                    *in_code = false;
                }
                return;
            }
            '\u{15}' => {
                if self.ignored_fields > 0 {
                    self.ignored_fields -= 1;
                } else {
                    self.fields.pop();
                }
                return;
            }
            _ => {}
        }
        if self.fields.iter().any(|&in_code| in_code) {
            return;
        }
        let is_cell_mark = ch == '\u{7}';
        match ch {
            '\r' | '\u{c}' => self.end_paragraph(offset, out),
            '\u{7}' => self.end_cell(offset, out),
            '\u{b}' => self.text.push('\n'),
            '\t' => self.text.push('\t'),
            '\u{1e}' => self.text.push('-'),
            // Optional hyphens, and marks for pictures, footnotes and the like.
            c if c < ' ' => {}
            c => {
                self.text.push(c);
                if self.text.len() >= MAX_PENDING_TEXT {
                    let text = self.take_text();
                    out.push_back(Block::paragraph(text));
                }
            }
        }
        self.last_was_cell_mark = is_cell_mark;
    }
}

impl BlockSource for DocSource {
    fn pump(&mut self, out: &mut VecDeque<Block>) -> Result<bool> {
        self.cancel.check()?;
        let Some(piece) = self.pieces.get(self.piece).copied() else {
            if !self.text.trim().is_empty() {
                let text = self.take_text();
                out.push_back(Block::paragraph(text));
            }
            self.flush_row(out);
            return Ok(true);
        };
        let word = std::mem::take(&mut self.word);
        let mut count = (piece.chars - self.done_in_piece).min(CHUNK_CHARS);
        if piece.compressed {
            let start = piece.offset + self.done_in_piece;
            for (i, &byte) in word[start..start + count].iter().enumerate() {
                self.push_char(cp1252(byte), start + i, out);
            }
        } else {
            let start = piece.offset + self.done_in_piece * 2;
            // Keep a surrogate pair together across two chunks.
            let unit_at = |i: usize| u16::from_le_bytes([word[start + i * 2], word[start + i * 2 + 1]]);
            if self.done_in_piece + count < piece.chars && (0xD800..0xDC00).contains(&unit_at(count - 1)) {
                count += 1;
            }
            let mut index = 0;
            for decoded in char::decode_utf16((0..count).map(unit_at)) {
                let (ch, units) = match decoded {
                    Ok(ch) => (ch, ch.len_utf16()),
                    Err(_) => (char::REPLACEMENT_CHARACTER, 1),
                };
                self.push_char(ch, start + index * 2, out);
                index += units;
            }
        }
        self.word = word;
        self.done_in_piece += count;
        if self.done_in_piece >= piece.chars {
            self.piece += 1;
            self.done_in_piece = 0;
        }
        Ok(false)
    }
}

/// The pieces covering the main document's first `ccp` characters.
fn parse_pieces(clx: &[u8], ccp: usize, word_len: usize) -> Result<Vec<Piece>> {
    let bad = || EngineError::malformed("the Word document's text table is damaged");
    let mut at = 0;
    let plc = loop {
        match clx.get(at) {
            Some(1) => {
                let size = usize::from(le16(clx, at + 1).ok_or_else(bad)?);
                at += 3 + size;
            }
            Some(2) => {
                let size = le32(clx, at + 1).ok_or_else(bad)? as usize;
                break clx.get(at + 5..(at + 5).saturating_add(size)).ok_or_else(bad)?;
            }
            _ => return Err(bad()),
        }
    };
    if plc.len() < 4 || (plc.len() - 4) % 12 != 0 {
        return Err(bad());
    }
    let count = (plc.len() - 4) / 12;
    let descriptors = 4 * (count + 1);
    let mut pieces = Vec::new();
    let mut remaining = ccp;
    for i in 0..count {
        if remaining == 0 {
            break;
        }
        let start = le32(plc, i * 4).ok_or_else(bad)? as usize;
        let end = le32(plc, (i + 1) * 4).ok_or_else(bad)? as usize;
        if end < start {
            return Err(bad());
        }
        let chars = (end - start).min(remaining);
        remaining -= chars;
        if chars == 0 {
            continue;
        }
        if pieces.len() >= MAX_PIECES {
            return Err(EngineError::limit("the Word document is split into too many pieces"));
        }
        let raw = le32(plc, descriptors + i * 8 + 2).ok_or_else(bad)?;
        let compressed = raw & 0x4000_0000 != 0;
        let position = (raw & 0x3FFF_FFFF) as usize;
        let (offset, width) = if compressed { (position / 2, 1) } else { (position, 2) };
        let fits = chars.checked_mul(width).and_then(|len| offset.checked_add(len)).is_some_and(|end| end <= word_len);
        if !fits {
            return Err(EngineError::malformed("the Word document's text lies outside its stream"));
        }
        pieces.push(Piece { offset, chars, compressed });
    }
    Ok(pieces)
}

/// Heading level by style index, from the style sheet's built-in style ids.
fn heading_styles(word: &[u8], table: &[u8]) -> Option<HashMap<u16, u8>> {
    let (fc, lcb) = fib_pair(word, FC_STSHF_INDEX)?;
    let sheet = table.get(fc..fc.checked_add(lcb)?)?;
    let header = usize::from(le16(sheet, 0)?);
    let count = usize::from(le16(sheet, 2)?);
    let mut at = 2 + header;
    let mut styles = HashMap::new();
    for index in 0..count {
        let size = usize::from(le16(sheet, at)?);
        if size >= 2 {
            let built_in = le16(sheet, at + 2)? & 0x0FFF;
            if (1..=9).contains(&built_in) {
                styles.insert(index as u16, built_in as u8);
            }
        }
        at += 2 + size;
    }
    Some(styles)
}

/// Paragraph properties per stretch of the WordDocument stream, sorted.
/// `None` when the file's properties are unusable; the text does not need them.
fn read_paragraph_runs(word: &[u8], table: &[u8]) -> Option<Vec<ParagraphRun>> {
    let headings = heading_styles(word, table).unwrap_or_default();
    let (fc, lcb) = fib_pair(word, FC_PLCF_BTE_PAPX_INDEX)?;
    let plc = table.get(fc..fc.checked_add(lcb)?)?;
    if plc.len() < 4 || (plc.len() - 4) % 8 != 0 {
        return None;
    }
    let count = (plc.len() - 4) / 8;
    let mut runs = Vec::new();
    for i in 0..count {
        let page_number = le32(plc, 4 * (count + 1) + i * 4)? as usize;
        let start = page_number.checked_mul(FKP_PAGE)?;
        let page = word.get(start..start.checked_add(FKP_PAGE)?)?;
        read_fkp(page, &headings, &mut runs);
        if runs.len() >= MAX_PARAGRAPH_RUNS {
            return None;
        }
    }
    runs.sort_by_key(|r| r.start);
    Some(runs)
}

fn read_fkp(page: &[u8], headings: &HashMap<u16, u8>, runs: &mut Vec<ParagraphRun>) {
    let count = usize::from(page[FKP_PAGE - 1]);
    let descriptors = 4 * (count + 1);
    for i in 0..count {
        let (Some(start), Some(end)) = (le32(page, i * 4), le32(page, (i + 1) * 4)) else { return };
        let Some(&offset) = page.get(descriptors + i * 13) else { return };
        let mut run = ParagraphRun { start, end, heading: None, in_table: false, row_end: false };
        if offset != 0 {
            read_papx(page, usize::from(offset) * 2, headings, &mut run);
        }
        runs.push(run);
    }
}

/// A paragraph's style index and the two table flags among its properties.
fn read_papx(page: &[u8], at: usize, headings: &HashMap<u16, u8>, run: &mut ParagraphRun) {
    let Some(&first) = page.get(at) else { return };
    let (body, len) = if first == 0 {
        let Some(&second) = page.get(at + 1) else { return };
        (at + 2, usize::from(second) * 2)
    } else {
        (at + 1, usize::from(first) * 2 - 1)
    };
    let end = (body + len).min(FKP_PAGE - 1);
    let Some(style) = le16(page, body) else { return };
    run.heading = headings.get(&style).copied();

    let mut at = body + 2;
    while at + 2 < end {
        let Some(sprm) = le16(page, at) else { return };
        let Some(&value) = page.get(at + 2) else { return };
        at += 2;
        match sprm {
            // sprmPFInTable, sprmPFInnerTableCell
            0x2416 | 0x244B => run.in_table = value != 0,
            // sprmPFTtp, sprmPFInnerTtp
            0x2417 | 0x244C => run.row_end = value != 0,
            // Operands whose size is not in the sprm; nothing needed comes after them in practice.
            0xC615 | 0xD606 | 0xD608 => return,
            _ => {}
        }
        at += match sprm >> 13 {
            0 | 1 => 1,
            2 | 4 | 5 => 2,
            3 => 4,
            7 => 3,
            _ => 1 + usize::from(value),
        };
    }
}

#[cfg(test)]
pub(crate) mod testing {
    //! Writes minimal `.doc` streams for fixtures.
    use super::*;
    use crate::cfb::testing::cfb;

    pub const FIB_LEN: usize = 34 + 28 + 2 + 88 + 2 + 8 * 100;
    const LW_START: usize = 34 + 28 + 2;
    const PAIRS_START: usize = LW_START + 88 + 2;

    pub enum Text<'a> {
        Ansi(&'a [u8]),
        Wide(&'a str),
    }

    pub struct Doc<'a> {
        pub pieces: Vec<Text<'a>>,
        pub flags: u16,
        pub nfib: u16,
        pub ccp: Option<u32>,
        /// `(start, end, papx)` of paragraph properties, as stream offsets
        /// relative to the start of the text.
        pub paragraphs: Vec<(u32, u32, Vec<u8>)>,
        /// Built-in style id per style index.
        pub styles: Vec<u16>,
    }

    impl Default for Doc<'_> {
        fn default() -> Self {
            Doc { pieces: Vec::new(), flags: 0, nfib: 193, ccp: None, paragraphs: Vec::new(), styles: Vec::new() }
        }
    }

    pub fn set_pair(fib: &mut [u8], index: usize, fc: u32, lcb: u32) {
        let at = PAIRS_START + index * 8;
        fib[at..at + 4].copy_from_slice(&fc.to_le_bytes());
        fib[at + 4..at + 8].copy_from_slice(&lcb.to_le_bytes());
    }

    /// Text starts at the first page boundary after the FIB.
    pub fn document(doc: &Doc) -> (Vec<u8>, Vec<u8>) {
        let mut fib = vec![0u8; FIB_LEN];
        fib[0..2].copy_from_slice(&WORD_MAGIC.to_le_bytes());
        fib[2..4].copy_from_slice(&doc.nfib.to_le_bytes());
        fib[0x0A..0x0C].copy_from_slice(&doc.flags.to_le_bytes());
        fib[32..34].copy_from_slice(&14u16.to_le_bytes());
        fib[34 + 28..34 + 30].copy_from_slice(&22u16.to_le_bytes());
        fib[LW_START + 88..LW_START + 90].copy_from_slice(&100u16.to_le_bytes());

        let text_start = FIB_LEN.next_multiple_of(FKP_PAGE);
        let mut word = fib;
        word.resize(text_start, 0);
        let mut cps = vec![0u32];
        let mut descriptors = Vec::new();
        let mut cp = 0;
        for piece in &doc.pieces {
            let offset = word.len() as u32;
            let (chars, raw) = match piece {
                Text::Ansi(bytes) => {
                    word.extend_from_slice(bytes);
                    (bytes.len() as u32, (offset * 2) | 0x4000_0000)
                }
                Text::Wide(text) => {
                    let units: Vec<u16> = text.encode_utf16().collect();
                    word.extend(units.iter().flat_map(|u| u.to_le_bytes()));
                    (units.len() as u32, offset)
                }
            };
            cp += chars;
            cps.push(cp);
            descriptors.extend_from_slice(&0u16.to_le_bytes());
            descriptors.extend_from_slice(&raw.to_le_bytes());
            descriptors.extend_from_slice(&0u16.to_le_bytes());
        }
        let ccp = doc.ccp.unwrap_or(cp);
        word[LW_START + CCP_TEXT_INDEX * 4..LW_START + CCP_TEXT_INDEX * 4 + 4].copy_from_slice(&ccp.to_le_bytes());

        let mut plc: Vec<u8> = cps.iter().flat_map(|c| c.to_le_bytes()).collect();
        plc.extend_from_slice(&descriptors);
        let mut table = vec![1u8, 2, 0, 0xAA, 0xBB]; // a Prc to step over
        table.push(2);
        table.extend_from_slice(&(plc.len() as u32).to_le_bytes());
        table.extend_from_slice(&plc);
        let clx_len = table.len() as u32;
        set_pair(&mut word, FC_CLX_INDEX, 0, clx_len);

        if !doc.styles.is_empty() {
            let at = table.len() as u32;
            let mut sheet = 2u16.to_le_bytes().to_vec();
            sheet.extend_from_slice(&(doc.styles.len() as u16).to_le_bytes());
            for &id in &doc.styles {
                sheet.extend_from_slice(&2u16.to_le_bytes());
                sheet.extend_from_slice(&id.to_le_bytes());
            }
            table.extend_from_slice(&sheet);
            set_pair(&mut word, FC_STSHF_INDEX, at, sheet.len() as u32);
        }
        if !doc.paragraphs.is_empty() {
            let text = text_start as u32;
            let page_number = word.len().div_ceil(FKP_PAGE) as u32;
            word.resize(word.len().next_multiple_of(FKP_PAGE), 0);
            let mut page = vec![0u8; FKP_PAGE];
            let count = doc.paragraphs.len();
            for (i, (start, end, _)) in doc.paragraphs.iter().enumerate() {
                page[i * 4..i * 4 + 4].copy_from_slice(&(text + start).to_le_bytes());
                page[(i + 1) * 4..(i + 1) * 4 + 4].copy_from_slice(&(text + end).to_le_bytes());
            }
            let mut body_at = FKP_PAGE - 1;
            for (i, (.., papx)) in doc.paragraphs.iter().enumerate() {
                body_at = (body_at - papx.len() - 1) & !1;
                page[body_at] = papx.len().div_ceil(2) as u8;
                if papx.len() % 2 == 0 {
                    // An even body needs the two-byte length form.
                    body_at -= 2;
                    page[body_at] = 0;
                    page[body_at + 1] = (papx.len() / 2) as u8;
                    page[body_at + 2..body_at + 2 + papx.len()].copy_from_slice(papx);
                } else {
                    page[body_at + 1..body_at + 1 + papx.len()].copy_from_slice(papx);
                }
                page[4 * (count + 1) + i * 13] = (body_at / 2) as u8;
            }
            page[FKP_PAGE - 1] = count as u8;
            word.extend_from_slice(&page);

            let at = table.len() as u32;
            let mut bte: Vec<u8> = (text).to_le_bytes().to_vec();
            bte.extend_from_slice(&(text + doc.paragraphs.last().map_or(0, |p| p.1)).to_le_bytes());
            bte.extend_from_slice(&page_number.to_le_bytes());
            table.extend_from_slice(&bte);
            set_pair(&mut word, FC_PLCF_BTE_PAPX_INDEX, at, bte.len() as u32);
        }
        (word, table)
    }

    pub fn doc_file(doc: &Doc) -> Vec<u8> {
        let (word, table) = document(doc);
        let name = if doc.flags & FLAG_TABLE_STREAM_1 != 0 { "1Table" } else { "0Table" };
        cfb(&[("WordDocument", &word), (name, &table)])
    }

    /// Paragraph properties: a style index, then these single-byte-operand sprms.
    pub fn papx(style: u16, sprms: &[u16]) -> Vec<u8> {
        let mut out = style.to_le_bytes().to_vec();
        for sprm in sprms {
            out.extend_from_slice(&sprm.to_le_bytes());
            out.push(1);
        }
        out
    }

    pub fn simple_doc() -> Vec<u8> {
        doc_file(&Doc { pieces: vec![Text::Ansi(b"Hello from Word\r")], ..Doc::default() })
    }
}

#[cfg(test)]
mod tests {
    use super::testing::*;
    use super::*;
    use crate::cfb::testing::cfb;
    use crate::testing::{open_zip, open_zip_err, texts};

    fn lines(doc: Doc) -> Vec<String> {
        let (format, blocks) = open_zip(doc_file(&doc));
        assert_eq!(format, document_core::FormatId::Doc);
        texts(&blocks)
    }

    #[test]
    fn reads_ansi_and_wide_pieces_in_order() {
        let doc = Doc {
            pieces: vec![Text::Ansi(b"caf\xE9 \x93quoted\x94 \x80\r"), Text::Wide("日本語 😀\rlast")],
            ..Doc::default()
        };
        assert_eq!(lines(doc), ["P café “quoted” €", "P 日本語 😀", "P last"]);
    }

    #[test]
    fn stops_at_the_end_of_the_main_text() {
        let doc = Doc { pieces: vec![Text::Ansi(b"body\rfootnote\r")], ccp: Some(5), ..Doc::default() };
        assert_eq!(lines(doc), ["P body"]);
    }

    #[test]
    fn honours_the_table_stream_flag() {
        let doc = Doc { pieces: vec![Text::Ansi(b"one\r")], flags: FLAG_TABLE_STREAM_1, ..Doc::default() };
        assert_eq!(lines(doc), ["P one"]);
    }

    #[test]
    fn shows_field_results_and_hides_field_codes() {
        let doc = Doc {
            pieces: vec![Text::Ansi(
                b"See \x13 HYPERLINK \"http://x\" \x14the \x13 NESTED \x14site\x15\x15 now\x13 PAGE \x15.\x01\x02\x08\r",
            )],
            ..Doc::default()
        };
        assert_eq!(lines(doc), ["P See the site now."]);
    }

    #[test]
    fn keeps_breaks_and_special_characters() {
        let doc = Doc { pieces: vec![Text::Ansi(b"a\x0Bb\tc\x1Ed\x1Fe\x0Cnext\r\r\r")], ..Doc::default() };
        assert_eq!(lines(doc), ["P a\nb\tc-de", "P next"]);
    }

    #[test]
    fn headings_come_from_built_in_styles() {
        let text = b"Title\rBody\rSub\r";
        let doc = Doc {
            pieces: vec![Text::Ansi(text)],
            // Style indexes 2 and 3 are built-in Heading 1 and Heading 2.
            styles: vec![0, 15, 1, 2],
            paragraphs: vec![(0, 6, papx(2, &[])), (6, 11, papx(1, &[])), (11, 15, papx(3, &[]))],
            ..Doc::default()
        };
        assert_eq!(lines(doc), ["H1 Title", "P Body", "H2 Sub"]);
    }

    #[test]
    fn tables_use_paragraph_properties() {
        // An empty middle cell, a two-paragraph cell, and a following paragraph.
        let text = b"a\x07\x07b\rc\x07\x07after\r";
        let table = papx(0, &[0x2416]);
        let row_end = papx(0, &[0x2416, 0x2417]);
        let doc = Doc {
            pieces: vec![Text::Ansi(text)],
            paragraphs: vec![
                (0, 2, table.clone()),
                (2, 3, table.clone()),
                (3, 5, table.clone()),
                (5, 7, table),
                (7, 8, row_end),
                (8, 14, papx(0, &[])),
            ],
            ..Doc::default()
        };
        assert_eq!(lines(doc), ["R a||b\nc", "P after"]);
    }

    #[test]
    fn tables_without_properties_use_the_double_mark() {
        let doc = Doc { pieces: vec![Text::Ansi(b"a\x07b\x07\x07c\x07d\x07\x07tail\r")], ..Doc::default() };
        assert_eq!(lines(doc), ["R a|b", "R c|d", "P tail"]);
    }

    #[test]
    fn long_runs_without_a_paragraph_mark_split() {
        let long = vec![b'x'; MAX_PENDING_TEXT + 10];
        let doc = Doc { pieces: vec![Text::Ansi(&long)], ..Doc::default() };
        let (_, blocks) = open_zip(doc_file(&doc));
        assert!(blocks.len() >= 2);
        let total: usize = blocks
            .iter()
            .map(|b| match b {
                Block::Paragraph { text } => text.text.len(),
                other => panic!("{other:?}"),
            })
            .sum();
        assert_eq!(total, long.len());
    }

    #[test]
    fn a_surrogate_pair_across_chunks_stays_whole() {
        let text = format!("{}😀\r", "x".repeat(CHUNK_CHARS - 1));
        let doc = Doc { pieces: vec![Text::Wide(&text)], ..Doc::default() };
        let (_, blocks) = open_zip(doc_file(&doc));
        let joined: String = texts(&blocks).concat();
        assert!(joined.contains('😀'), "the emoji was split");
    }

    #[test]
    fn encrypted_documents_get_a_typed_error() {
        for flags in [FLAG_ENCRYPTED, FLAG_ENCRYPTED | FLAG_OBFUSCATED] {
            let (word, _) = document(&Doc { flags, ..Doc::default() });
            // No table stream needed: the check comes first.
            let err = open_zip_err(cfb(&[("WordDocument", &word)]));
            assert_eq!(err.kind, ErrorKind::UnsupportedEncryption, "{err}");
        }
    }

    #[test]
    fn older_word_gets_a_typed_error() {
        let (mut word, _) = document(&Doc { nfib: 104, ..Doc::default() });
        assert_eq!(open_zip_err(cfb(&[("WordDocument", &word)])).kind, ErrorKind::UnsupportedFormat);
        // Word 6 and 95 have their own magic number.
        word[0..2].copy_from_slice(&WORD6_MAGIC.to_le_bytes());
        assert_eq!(open_zip_err(cfb(&[("WordDocument", &word)])).kind, ErrorKind::UnsupportedFormat);
    }

    #[test]
    fn hostile_documents_fail_cleanly() {
        let malformed = |word: &[u8], table: &[u8]| {
            let err = open_zip_err(cfb(&[("WordDocument", word), ("0Table", table)]));
            assert_eq!(err.kind, ErrorKind::Malformed, "{err}");
        };
        let doc = Doc { pieces: vec![Text::Ansi(b"text\r")], ..Doc::default() };
        let (word, table) = document(&doc);

        // No magic; a header cut off before the tables.
        malformed(&[0; 64], &table);
        malformed(&word[..40], &table);
        // The missing table stream.
        assert_eq!(open_zip_err(cfb(&[("WordDocument", &word)])).kind, ErrorKind::Malformed);
        // The text table outside its stream.
        let mut bad = word.clone();
        set_pair(&mut bad, FC_CLX_INDEX, 0xFFFF_FF00, 0x1000);
        malformed(&bad, &table);
        // A CLX of unknown kind, and one that overruns itself.
        malformed(&word, &[9, 9, 9]);
        malformed(&word, &[2, 0xFF, 0xFF, 0xFF, 0x7F, 0, 0]);
        // A piece table of an impossible size.
        malformed(&word, &[2, 5, 0, 0, 0, 1, 2, 3, 4, 5]);
    }

    #[test]
    fn hostile_pieces_fail_cleanly() {
        let table_with = |cps: &[u32], raw: u32| {
            let mut plc: Vec<u8> = cps.iter().flat_map(|c| c.to_le_bytes()).collect();
            plc.extend_from_slice(&[0, 0]);
            plc.extend_from_slice(&raw.to_le_bytes());
            plc.extend_from_slice(&[0, 0]);
            let mut table = vec![2u8];
            table.extend_from_slice(&(plc.len() as u32).to_le_bytes());
            table.extend_from_slice(&plc);
            table
        };
        let doc = Doc { pieces: vec![Text::Ansi(b"text\r")], ccp: Some(u32::MAX), ..Doc::default() };
        let (mut word, _) = document(&doc);
        let check = |word: &[u8], table: &[u8]| {
            let err = open_zip_err(cfb(&[("WordDocument", word), ("0Table", table)]));
            assert_eq!(err.kind, ErrorKind::Malformed, "{err}");
        };
        // Characters running past the stream, and backwards positions.
        let table = table_with(&[0, 0x7FFF_FFFF], 0x4000_0000);
        set_pair(&mut word, FC_CLX_INDEX, 0, table.len() as u32);
        check(&word, &table);
        let table = table_with(&[100, 50], 0x4000_0000);
        set_pair(&mut word, FC_CLX_INDEX, 0, table.len() as u32);
        check(&word, &table);
    }

    #[test]
    fn broken_style_data_leaves_plain_text() {
        let (mut word, table) = document(&Doc { pieces: vec![Text::Ansi(b"text\r")], ..Doc::default() });
        set_pair(&mut word, FC_STSHF_INDEX, 0xFFFF_FFFF, 8);
        set_pair(&mut word, FC_PLCF_BTE_PAPX_INDEX, 0xFFFF_FFF0, 12);
        let (_, blocks) = open_zip(cfb(&[("WordDocument", &word), ("0Table", &table)]));
        assert_eq!(texts(&blocks), ["P text"]);
    }

    #[test]
    fn deep_fields_stay_bounded() {
        let text = [vec![0x13u8; 10_000], b"x\r".to_vec()].concat();
        let (_, blocks) = open_zip(doc_file(&Doc { pieces: vec![Text::Ansi(&text)], ..Doc::default() }));
        assert!(blocks.is_empty());
    }

    #[test]
    fn simple_fixture_opens() {
        let (format, blocks) = open_zip(simple_doc());
        assert_eq!(format, document_core::FormatId::Doc);
        assert_eq!(texts(&blocks), ["P Hello from Word"]);
    }
}
