//! Excel 97-2003 (BIFF8 `.xls`): values and looks of every visible sheet.
//!
//! Like [`crate::xlsx`], each sheet is a [`Block::Section`] followed by one
//! [`Block::TableRow`] per non-empty row. Cells show their cached values, with
//! the cell's number format applied by [`crate::numfmt`]; formulas are not
//! evaluated. The `Workbook` stream is read whole, then walked record by
//! record. Excel 5/95 and encrypted workbooks are refused with a typed error.
//!
//! How cells look comes from the FONT, XF and PALETTE records, as `xlsx.rs`
//! reads them from the styles part: bold, italic, underline, strikethrough,
//! colour and size, solid fills, horizontal alignment and wrapping. Distinct
//! looks go out as [`Block::CellStyle`]s before the first sheet. Merged cells
//! (MERGEDCELLS) follow each sheet's rows as [`Block::Merge`]s, and shared
//! text with differently styled runs follows its row as [`Block::CellRuns`].
//! Column widths, borders, charts, pictures and comments are dropped.

use std::collections::{HashMap, VecDeque};

use document_core::style::{BOLD, ITALIC, STRIKE, UNDERLINE};
use document_core::{Block, CancelToken, CellStyle, EngineError, ErrorKind, Limits, MAX_CELL_RUNS, Result, Span};

use crate::cfb::{le16, le32};
use crate::numfmt::{NumFmt, builtin, general};
use crate::stream::BlockSource;
use crate::xlsx::INDEXED;

const BOF: u16 = 0x0809;
const EOF: u16 = 0x000A;
const CONTINUE: u16 = 0x003C;
const FILEPASS: u16 = 0x002F;
const DATE1904: u16 = 0x0022;
const FORMAT: u16 = 0x041E;
const XF: u16 = 0x00E0;
const BOUNDSHEET: u16 = 0x0085;
const SST: u16 = 0x00FC;
const LABELSST: u16 = 0x00FD;
const LABEL: u16 = 0x0204;
const RSTRING: u16 = 0x00D6;
const NUMBER: u16 = 0x0203;
const RK: u16 = 0x027E;
const MULRK: u16 = 0x00BD;
const FORMULA: u16 = 0x0006;
const STRING: u16 = 0x0207;
const BOOLERR: u16 = 0x0205;
const FONT: u16 = 0x0031;
const PALETTE: u16 = 0x0092;
const MERGEDCELLS: u16 = 0x00E5;
const BLANK: u16 = 0x0201;
const MULBLANK: u16 = 0x00BE;

const BIFF8: u16 = 0x0600;
const MAX_COLUMNS: usize = 256;
/// Excel allows about 65,000 cell formats; cells styled beyond this show plain values.
const MAX_CELL_FORMATS: usize = 65_536;
const MAX_FORMAT_CODE_BYTES: usize = 256 * 1024;
/// Three file bytes can make one empty string, so the count needs its own cap.
const MAX_SHARED_STRINGS: usize = 2_000_000;
const CANCEL_CHECK_RECORDS: u32 = 65_536;
/// Fonts read; real workbooks have tens.
const MAX_FONTS: usize = 4096;
/// Distinct cell looks kept; cells beyond show plain.
const MAX_LOOKS: usize = 4096;
/// Styled runs kept across the shared text; text beyond shows in its cell's look.
const MAX_SHARED_RUNS: usize = 200_000;
/// Merged ranges kept per sheet.
const MAX_MERGES: usize = 10_000;

pub(crate) struct XlsSource {
    data: Vec<u8>,
    shared: Vec<String>,
    styles: Styles,
    date1904: bool,
    sheets: VecDeque<(String, usize)>,
    /// Position in the current sheet; `None` between sheets.
    position: Option<usize>,
    depth: u32,
    row: Option<u16>,
    cells: Vec<String>,
    /// Palette index per cell of the current row; empty until a styled cell comes.
    looks: Vec<u16>,
    /// Styled runs by column, for cells of the current row that have them.
    runs: Vec<(u32, Vec<Span>)>,
    /// The current sheet's merged ranges, sent after its rows.
    merges: Vec<Block>,
    /// The palette went out, before the first sheet.
    looks_sent: bool,
    /// Formatting runs `(first character, font)` of shared strings that have them.
    shared_runs: HashMap<usize, Vec<(u16, u16)>>,
    /// A formula whose text result is in the following STRING record.
    pending_formula: Option<(u16, u16, usize)>,
    cancel: CancelToken,
}

struct Record<'a> {
    id: u16,
    body: &'a [u8],
    next: usize,
}

fn record(data: &[u8], at: usize) -> Result<Record<'_>> {
    let truncated = || EngineError::malformed("an Excel record is cut off");
    let id = le16(data, at).ok_or_else(truncated)?;
    let len = usize::from(le16(data, at + 2).ok_or_else(truncated)?);
    let start = at + 4;
    let body = data.get(start..start + len).ok_or_else(truncated)?;
    Ok(Record { id, body, next: start + len })
}

/// The body of a record plus those of the CONTINUE records after it.
fn with_continues<'a>(data: &'a [u8], first: &Record<'a>) -> Result<(Vec<&'a [u8]>, usize)> {
    let mut chunks = vec![first.body];
    let mut next = first.next;
    while le16(data, next) == Some(CONTINUE) {
        let more = record(data, next)?;
        chunks.push(more.body);
        next = more.next;
    }
    Ok((chunks, next))
}

/// Reads across record boundaries. Character data restarts with a new flags
/// byte at each boundary, because the two halves may differ in width; other
/// fields simply continue.
struct Fragments<'a> {
    chunks: Vec<&'a [u8]>,
    index: usize,
    position: usize,
}

impl<'a> Fragments<'a> {
    fn new(chunks: Vec<&'a [u8]>) -> Self {
        Self { chunks, index: 0, position: 0 }
    }

    fn cut_off() -> EngineError {
        EngineError::malformed("an Excel string is cut off")
    }

    /// Moves to the next non-empty chunk once the current one is used up.
    fn settle(&mut self) -> bool {
        while self.chunks.get(self.index).is_some_and(|c| self.position >= c.len()) {
            self.index += 1;
            self.position = 0;
        }
        self.index < self.chunks.len()
    }

    fn u8(&mut self) -> Result<u8> {
        if !self.settle() {
            return Err(Self::cut_off());
        }
        let byte = self.chunks[self.index][self.position];
        self.position += 1;
        Ok(byte)
    }

    fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_le_bytes([self.u8()?, self.u8()?]))
    }

    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_le_bytes([self.u8()?, self.u8()?, self.u8()?, self.u8()?]))
    }

    fn skip(&mut self, mut count: u64) -> Result<()> {
        while count > 0 {
            if !self.settle() {
                return Err(Self::cut_off());
            }
            let available = (self.chunks[self.index].len() - self.position) as u64;
            let step = available.min(count);
            self.position += step as usize;
            count -= step;
        }
        Ok(())
    }

    fn chars(&mut self, count: usize, mut wide: bool) -> Result<String> {
        let mut units: Vec<u16> = Vec::with_capacity(count.min(4096));
        while units.len() < count {
            if self.chunks.get(self.index).is_none_or(|c| self.position >= c.len()) {
                if !self.settle() {
                    return Err(Self::cut_off());
                }
                wide = self.u8()? & 1 != 0;
                continue;
            }
            let rest = &self.chunks[self.index][self.position..];
            let wanted = count - units.len();
            if wide {
                let take = wanted.min(rest.len() / 2);
                if take == 0 {
                    return Err(Self::cut_off());
                }
                units.extend(rest[..take * 2].chunks_exact(2).map(|b| u16::from_le_bytes([b[0], b[1]])));
                self.position += take * 2;
            } else {
                let take = wanted.min(rest.len());
                units.extend(rest[..take].iter().map(|&b| u16::from(b)));
                self.position += take;
            }
        }
        Ok(String::from_utf16_lossy(&units))
    }

    /// An `XLUnicodeString`: 16-bit length, flags, characters.
    fn unicode_string(&mut self) -> Result<String> {
        let count = self.u16()?;
        let flags = self.u8()?;
        self.chars(count.into(), flags & 1 != 0)
    }
}

impl XlsSource {
    /// Reads the workbook globals: sheets, shared strings, formats.
    pub fn open(data: Vec<u8>, limits: Limits, cancel: CancelToken) -> Result<Self> {
        let first = record(&data, 0)?;
        if first.id != BOF {
            return Err(EngineError::malformed("the Excel workbook does not start with a BOF record"));
        }
        if le16(first.body, 0) != Some(BIFF8) {
            return Err(EngineError::unsupported("Excel 95 and older workbooks are not supported"));
        }

        let mut sheets = VecDeque::new();
        let mut shared = Vec::new();
        let mut date1904 = false;
        let mut codes: HashMap<u32, String> = HashMap::new();
        let mut code_bytes = 0;
        let mut xfs: Vec<Xf> = Vec::new();
        let mut fonts: Vec<Font> = Vec::new();
        let mut colors: [u32; 56] = INDEXED[8..].try_into().expect("56 palette entries");
        let mut shared_runs = HashMap::new();
        let mut at = first.next;
        let mut since_check = 0;
        while at < data.len() {
            since_check += 1;
            if since_check >= CANCEL_CHECK_RECORDS {
                since_check = 0;
                cancel.check()?;
            }
            let item = record(&data, at)?;
            let mut next = item.next;
            match item.id {
                EOF => break,
                FILEPASS => {
                    return Err(EngineError::new(
                        ErrorKind::UnsupportedEncryption,
                        "This Excel 97-2003 workbook uses an older kind of password protection that isn't supported.",
                    ));
                }
                DATE1904 => date1904 = le16(item.body, 0).is_some_and(|v| v != 0),
                FORMAT => {
                    // A format is decoration: a damaged one leaves plain values.
                    let id = le16(item.body, 0).map(u32::from);
                    let code = item.body.get(2..).and_then(|b| Fragments::new(vec![b]).unicode_string().ok());
                    if let (Some(id), Some(code)) = (id, code) {
                        code_bytes += code.len();
                        if code_bytes <= MAX_FORMAT_CODE_BYTES {
                            codes.insert(id, code);
                        }
                    }
                }
                XF => {
                    // Positions matter: a cell's style index counts every XF.
                    if xfs.len() < MAX_CELL_FORMATS {
                        xfs.push(Xf::parse(item.body));
                    }
                }
                FONT => {
                    if fonts.len() < MAX_FONTS {
                        fonts.push(Font::parse(item.body));
                    }
                }
                PALETTE => {
                    // Replaces entries from 8 on; a short record changes what it holds.
                    let count = usize::from(le16(item.body, 0).unwrap_or(0));
                    let entries = item.body.get(2..).unwrap_or_default().chunks_exact(4).take(count);
                    for (slot, rgb) in colors.iter_mut().zip(entries) {
                        *slot = u32::from(rgb[0]) << 16 | u32::from(rgb[1]) << 8 | u32::from(rgb[2]);
                    }
                }
                BOUNDSHEET => {
                    if let Some(sheet) = parse_bound_sheet(item.body) {
                        sheets.push_back(sheet);
                    }
                }
                SST => {
                    let (chunks, after) = with_continues(&data, &item)?;
                    (shared, shared_runs) = read_shared_strings(chunks, limits.max_part_bytes)?;
                    next = after;
                }
                _ => {}
            }
            at = next;
        }
        if sheets.is_empty() {
            return Err(EngineError::malformed("the workbook lists no sheets"));
        }
        Ok(Self {
            data,
            shared,
            styles: Styles::compile(&xfs, &codes, &fonts, &colors),
            date1904,
            sheets,
            position: None,
            depth: 0,
            row: None,
            cells: Vec::new(),
            looks: Vec::new(),
            runs: Vec::new(),
            merges: Vec::new(),
            looks_sent: false,
            shared_runs,
            pending_formula: None,
            cancel,
        })
    }

    fn number(&self, xf: usize, value: f64) -> String {
        self.styles
            .format(xf)
            .and_then(|f| f.format_number(value, self.date1904))
            .or_else(|| general(value))
            .unwrap_or_else(|| value.to_string())
    }

    fn text(&self, xf: usize, value: String) -> String {
        self.styles.format(xf).and_then(|f| f.format_text(&value)).unwrap_or(value)
    }

    /// Emits the finished row when a cell of another row arrives.
    fn put(&mut self, row: u16, column: u16, xf: usize, value: String, out: &mut VecDeque<Block>) {
        if self.row != Some(row) {
            self.flush_row(out);
            self.row = Some(row);
        }
        let column = usize::from(column);
        if column >= MAX_COLUMNS {
            return;
        }
        let look = self.styles.look(xf);
        if look != 0 {
            if self.looks.len() <= column {
                self.looks.resize(column + 1, 0);
            }
            self.looks[column] = look;
        }
        if value.is_empty() {
            return;
        }
        if self.cells.len() <= column {
            self.cells.resize(column + 1, String::new());
        }
        self.cells[column] = value;
    }

    /// A cell with no value, which matters only for its fill.
    fn put_blank(&mut self, row: u16, column: u16, xf: usize, out: &mut VecDeque<Block>) {
        if self.styles.palette.get(usize::from(self.styles.look(xf))).is_some_and(|l| l.fill.is_some()) {
            self.put(row, column, xf, String::new(), out);
        }
    }

    fn flush_row(&mut self, out: &mut VecDeque<Block>) {
        let mut cells = std::mem::take(&mut self.cells);
        let mut looks = std::mem::take(&mut self.looks);
        while cells.last().is_some_and(String::is_empty) {
            cells.pop();
        }
        // Past the values, only colour shows.
        while looks.len() > cells.len()
            && looks.last().is_some_and(|&l| self.styles.palette[usize::from(l)].fill.is_none())
        {
            looks.pop();
        }
        if looks.iter().all(|&l| l == 0) {
            looks.clear();
        }
        let row = u32::from(self.row.unwrap_or(0)) + 1;
        if !cells.is_empty() || !looks.is_empty() {
            out.push_back(Block::TableRow { row: Some(row), header: false, cells, styles: looks, cell_spans: Vec::new() });
        }
        for (column, spans) in std::mem::take(&mut self.runs) {
            out.push_back(Block::CellRuns { row, column, spans });
        }
    }

    /// Shared text, with its styled runs when the cell shows it as written.
    fn put_shared(&mut self, row: u16, column: u16, xf: usize, index: Option<usize>, out: &mut VecDeque<Block>) {
        let shared = index.and_then(|i| self.shared.get(i)).cloned().unwrap_or_default();
        let value = self.text(xf, shared.clone());
        let runs = index.filter(|_| value == shared).and_then(|i| self.shared_runs.get(&i));
        let spans = runs.map(|runs| self.styles.spans(runs, value.encode_utf16().count())).unwrap_or_default();
        self.put(row, column, xf, value, out);
        if !spans.is_empty() && usize::from(column) < MAX_COLUMNS && self.row == Some(row) {
            self.runs.retain(|(c, _)| *c != u32::from(column));
            self.runs.push((u32::from(column), spans));
        }
    }

    /// Handles one record of a sheet, returning where the next one starts.
    fn sheet_record(&mut self, at: usize, out: &mut VecDeque<Block>) -> Result<usize> {
        let data = std::mem::take(&mut self.data);
        let result = self.sheet_record_in(&data, at, out);
        self.data = data;
        result
    }

    fn sheet_record_in(&mut self, data: &[u8], at: usize, out: &mut VecDeque<Block>) -> Result<usize> {
        let item = record(data, at)?;
        match item.id {
            BOF => {
                self.depth += 1;
                return Ok(item.next);
            }
            EOF => {
                self.depth = self.depth.saturating_sub(1);
                return Ok(item.next);
            }
            // Embedded charts sit inside a sheet, between their own BOF and EOF.
            _ if self.depth != 1 => return Ok(item.next),
            _ => {}
        }
        let body = item.body;
        let cell = (|| Some((le16(body, 0)?, le16(body, 2)?, usize::from(le16(body, 4)?))))();
        let mut next = item.next;
        match (item.id, cell) {
            (LABELSST, Some((row, column, xf))) => {
                self.pending_formula = None;
                let index = le32(body, 6).map(|i| i as usize);
                self.put_shared(row, column, xf, index, out);
            }
            (LABEL | RSTRING, Some((row, column, xf))) => {
                self.pending_formula = None;
                let (mut chunks, after) = with_continues(data, &item)?;
                chunks[0] = &body[6.min(body.len())..];
                let value = Fragments::new(chunks).unicode_string()?;
                let value = self.text(xf, value);
                self.put(row, column, xf, value, out);
                next = after;
            }
            (NUMBER, Some((row, column, xf))) => {
                self.pending_formula = None;
                if let Some(value) = f64_at(body, 6) {
                    let value = self.number(xf, value);
                    self.put(row, column, xf, value, out);
                }
            }
            (RK, Some((row, column, xf))) => {
                self.pending_formula = None;
                if let Some(rk) = le32(body, 6) {
                    let value = self.number(xf, decode_rk(rk));
                    self.put(row, column, xf, value, out);
                }
            }
            (MULRK, Some((row, first, _))) => {
                self.pending_formula = None;
                // Six bytes per cell, between the leading row and column and a trailing last column.
                let cells = body.get(4..body.len().saturating_sub(2)).unwrap_or_default();
                for (offset, cell) in cells.chunks_exact(6).enumerate() {
                    let xf = usize::from(u16::from_le_bytes([cell[0], cell[1]]));
                    let rk = u32::from_le_bytes([cell[2], cell[3], cell[4], cell[5]]);
                    let value = self.number(xf, decode_rk(rk));
                    self.put(row, first.saturating_add(offset as u16), xf, value, out);
                }
            }
            (BLANK, Some((row, column, xf))) => {
                self.pending_formula = None;
                self.put_blank(row, column, xf, out);
            }
            (MULBLANK, Some((row, first, _))) => {
                self.pending_formula = None;
                // Two bytes per cell, between the leading row and column and a trailing last column.
                let cells = body.get(4..body.len().saturating_sub(2)).unwrap_or_default();
                for (offset, xf) in cells.chunks_exact(2).enumerate() {
                    let xf = usize::from(u16::from_le_bytes([xf[0], xf[1]]));
                    self.put_blank(row, first.saturating_add(offset as u16), xf, out);
                }
            }
            (MERGEDCELLS, _) => {
                let count = usize::from(le16(body, 0).unwrap_or(0));
                for range in body.get(2..).unwrap_or_default().chunks_exact(8).take(count) {
                    if self.merges.len() >= MAX_MERGES {
                        break;
                    }
                    let field = |i: usize| u32::from(u16::from_le_bytes([range[i], range[i + 1]]));
                    if let Some(merge) = merge_block(field(0), field(2), field(4), field(6)) {
                        self.merges.push(merge);
                    }
                }
            }
            (BOOLERR, Some((row, column, xf))) => {
                self.pending_formula = None;
                if let (Some(&value), Some(&is_error)) = (body.get(6), body.get(7)) {
                    self.put(row, column, xf, flag_or_error(value, is_error != 0), out);
                }
            }
            (FORMULA, Some((row, column, xf))) => {
                self.pending_formula = None;
                if let Some(result) = body.get(6..14) {
                    if result[6] == 0xFF && result[7] == 0xFF {
                        match result[0] {
                            0 => self.pending_formula = Some((row, column, xf)),
                            1 => self.put(row, column, xf, flag_or_error(result[2], false), out),
                            2 => self.put(row, column, xf, flag_or_error(result[2], true), out),
                            _ => self.put(row, column, xf, String::new(), out),
                        }
                    } else if let Some(value) = f64_at(body, 6) {
                        let value = self.number(xf, value);
                        self.put(row, column, xf, value, out);
                    }
                }
            }
            (STRING, _) => {
                if let Some((row, column, xf)) = self.pending_formula.take() {
                    let (chunks, after) = with_continues(data, &item)?;
                    let value = Fragments::new(chunks).unicode_string()?;
                    let value = self.text(xf, value);
                    self.put(row, column, xf, value, out);
                    next = after;
                }
            }
            _ => {}
        }
        Ok(next)
    }
}

impl BlockSource for XlsSource {
    fn pump(&mut self, out: &mut VecDeque<Block>) -> Result<bool> {
        self.cancel.check()?;
        if !self.looks_sent {
            self.looks_sent = true;
            for (index, style) in self.styles.palette.iter().enumerate().skip(1) {
                out.push_back(Block::CellStyle { index: index as u16, style: *style });
            }
        }
        let Some(mut at) = self.position else {
            let Some((name, at)) = self.sheets.pop_front() else {
                return Ok(true);
            };
            if record(&self.data, at).ok().is_none_or(|r| r.id != BOF) {
                return Err(EngineError::malformed("a sheet's position in the workbook is not valid"));
            }
            out.push_back(Block::Section { title: name });
            self.position = Some(at);
            self.depth = 0;
            self.row = None;
            self.cells.clear();
            self.looks.clear();
            self.runs.clear();
            self.merges.clear();
            self.pending_formula = None;
            return Ok(false);
        };
        let mut since_check = 0;
        loop {
            if at >= self.data.len() {
                // A sheet that ends without EOF still shows what it has.
                self.depth = 0;
            } else {
                at = self.sheet_record(at, out)?;
            }
            if self.depth == 0 {
                self.flush_row(out);
                out.extend(self.merges.drain(..));
                self.position = None;
                return Ok(false);
            }
            if !out.is_empty() {
                self.position = Some(at);
                return Ok(false);
            }
            since_check += 1;
            if since_check >= CANCEL_CHECK_RECORDS {
                since_check = 0;
                self.cancel.check()?;
            }
        }
    }
}

fn f64_at(data: &[u8], at: usize) -> Option<f64> {
    let bytes: [u8; 8] = data.get(at..at + 8)?.try_into().ok()?;
    Some(f64::from_le_bytes(bytes))
}

/// An RK value packs a number into 30 bits: bit 0 divides by 100, bit 1 picks
/// a signed integer over the top bits of a double.
fn decode_rk(rk: u32) -> f64 {
    let value =
        if rk & 2 != 0 { f64::from((rk as i32) >> 2) } else { f64::from_bits(u64::from(rk & 0xFFFF_FFFC) << 32) };
    if rk & 1 != 0 { value / 100.0 } else { value }
}

fn flag_or_error(value: u8, is_error: bool) -> String {
    if !is_error {
        return if value != 0 { "TRUE" } else { "FALSE" }.to_owned();
    }
    match value {
        0x00 => "#NULL!",
        0x07 => "#DIV/0!",
        0x0F => "#VALUE!",
        0x17 => "#REF!",
        0x1D => "#NAME?",
        0x24 => "#NUM!",
        0x2A => "#N/A",
        0x2B => "#GETTING_DATA",
        _ => "#ERROR!",
    }
    .to_owned()
}

/// (name, stream offset of its BOF) of a visible worksheet.
fn parse_bound_sheet(body: &[u8]) -> Option<(String, usize)> {
    let position = le32(body, 0)? as usize;
    let visibility = body.get(4)? & 0x03;
    let kind = *body.get(5)?;
    if visibility != 0 || kind != 0 {
        return None;
    }
    let count = usize::from(*body.get(6)?);
    let flags = *body.get(7)?;
    let name = Fragments::new(vec![body.get(8..)?]).chars(count, flags & 1 != 0).ok()?;
    Some((name, position))
}

/// Formatting runs `(first character, font)` of shared strings, by string index.
type SharedRuns = HashMap<usize, Vec<(u16, u16)>>;

fn read_shared_strings(chunks: Vec<&[u8]>, max_bytes: u64) -> Result<(Vec<String>, SharedRuns)> {
    let mut reader = Fragments::new(chunks);
    reader.skip(4)?; // total references
    let unique = reader.u32()? as usize;
    let mut strings = Vec::new();
    let mut runs_by_string = HashMap::new();
    let mut kept_runs = 0usize;
    let mut total = 0u64;
    while strings.len() < unique && reader.settle() {
        if strings.len() >= MAX_SHARED_STRINGS {
            return Err(EngineError::limit("the spreadsheet has too many shared strings"));
        }
        let count = reader.u16()?;
        let flags = reader.u8()?;
        let runs = if flags & 0x08 != 0 { reader.u16()? } else { 0 };
        let extended = if flags & 0x04 != 0 { reader.u32()? } else { 0 };
        let text = reader.chars(count.into(), flags & 1 != 0)?;
        let keep = if kept_runs < MAX_SHARED_RUNS { usize::from(runs).min(MAX_CELL_RUNS) } else { 0 };
        if keep > 0 {
            let mut kept = Vec::with_capacity(keep);
            for _ in 0..keep {
                kept.push((reader.u16()?, reader.u16()?));
            }
            kept_runs += keep;
            runs_by_string.insert(strings.len(), kept);
        }
        reader.skip((u64::from(runs) - keep as u64) * 4)?;
        reader.skip(u64::from(extended))?;
        total += text.len() as u64;
        if total > max_bytes {
            return Err(EngineError::limit("the spreadsheet's shared text is too large"));
        }
        strings.push(text);
    }
    Ok((strings, runs_by_string))
}

/// One MERGEDCELLS range as a merge; a single cell is none.
fn merge_block(first_row: u32, last_row: u32, first_column: u32, last_column: u32) -> Option<Block> {
    let (first_row, last_row) = (first_row.min(last_row) + 1, first_row.max(last_row) + 1);
    let (first_column, last_column) = (first_column.min(last_column), first_column.max(last_column));
    if (first_row, first_column) == (last_row, last_column) || first_column as usize >= MAX_COLUMNS {
        return None;
    }
    let last_column = last_column.min(MAX_COLUMNS as u32 - 1);
    Some(Block::Merge { first_row, first_column, last_row, last_column })
}

/// A FONT record: what a cell or a run of its text looks like.
#[derive(Debug, Default, Clone, Copy)]
struct Font {
    flags: u8,
    size: Option<f32>,
    /// Colour index; see [`color_of`].
    color: u16,
}

impl Font {
    fn parse(body: &[u8]) -> Self {
        let height = le16(body, 0).unwrap_or(0);
        let attributes = le16(body, 2).unwrap_or(0);
        let mut flags = 0;
        if attributes & 0x02 != 0 {
            flags |= ITALIC;
        }
        if attributes & 0x08 != 0 {
            flags |= STRIKE;
        }
        if le16(body, 6).is_some_and(|weight| weight >= 600) {
            flags |= BOLD;
        }
        if body.get(10).is_some_and(|&u| u != 0) {
            flags |= UNDERLINE;
        }
        // Twentieths of a point.
        let size = Some(f32::from(height) / 20.0).filter(|s| (1.0..=409.0).contains(s));
        Self { flags, size, color: le16(body, 4).unwrap_or(0x7FFF) }
    }
}

/// The parts of an XF record that cells show.
#[derive(Debug, Default, Clone, Copy)]
struct Xf {
    font: u16,
    format: u16,
    align: u8,
    wrap: bool,
    /// Colour index of a solid fill.
    fill: Option<u16>,
}

impl Xf {
    fn parse(body: &[u8]) -> Self {
        let alignment = body.get(6).copied().unwrap_or(0);
        let align = match alignment & 0x07 {
            1 => 1,
            2 | 6 => 2,
            3 => 3,
            _ => 0,
        };
        // The pattern is the top six bits of the fourth border word; 1 is solid.
        let solid = le32(body, 14).is_some_and(|w| w >> 26 == 1);
        let fill = le16(body, 18).filter(|_| solid).map(|w| w & 0x7F);
        Self {
            font: le16(body, 0).unwrap_or(0),
            format: le16(body, 2).unwrap_or(0),
            align,
            wrap: alignment & 0x08 != 0,
            fill,
        }
    }
}

/// An ARGB colour from its index: eight fixed colours, then the workbook's
/// palette. 64 and up are system colours and "automatic", which are the default.
fn color_of(index: u16, palette: &[u32; 56]) -> Option<u32> {
    let rgb = match index {
        0..=7 => INDEXED[usize::from(index)],
        8..=63 => palette[usize::from(index) - 8],
        _ => return None,
    };
    Some(0xFF00_0000 | rgb)
}

/// The number format and look of each XF record, as in `xlsx.rs`.
struct Styles {
    formats: Vec<NumFmt>,
    cell_formats: Vec<Option<usize>>,
    /// Distinct looks; 0 is plain.
    palette: Vec<CellStyle>,
    /// Index into `palette` per XF.
    looks: Vec<u16>,
    /// Fonts with their colour where it differs from the default font's.
    fonts: Vec<(Font, Option<u32>)>,
}

impl Styles {
    fn compile(xfs: &[Xf], codes: &HashMap<u32, String>, fonts: &[Font], colors: &[u32; 56]) -> Self {
        let default = fonts.first().copied().unwrap_or_default();
        let default_color = color_of(default.color, colors);
        // Only what differs from the workbook's default font counts.
        let fonts =
            fonts.iter().map(|f| (*f, color_of(f.color, colors).filter(|c| Some(*c) != default_color))).collect();
        let mut styles = Styles {
            formats: Vec::new(),
            cell_formats: Vec::new(),
            palette: vec![CellStyle::default()],
            looks: Vec::new(),
            fonts,
        };
        let mut compiled: HashMap<u16, Option<usize>> = HashMap::new();
        // A look's fields, with the size as bits so it can be hashed.
        type Key = (u8, Option<u32>, Option<u32>, Option<u32>, u8, bool);
        let mut seen: HashMap<Key, u16> = HashMap::new();
        seen.insert((0, None, None, None, 0, false), 0);
        for xf in xfs {
            let id = xf.format;
            let format = *compiled.entry(id).or_insert_with(|| {
                let key = u32::from(id);
                // General is what plain values show anyway.
                let code = codes.get(&key).map(String::as_str).or_else(|| builtin(key)).filter(|_| id != 0)?;
                styles.formats.push(NumFmt::parse(code)?);
                Some(styles.formats.len() - 1)
            });
            styles.cell_formats.push(format);

            let (font, color) = styles.font(xf.font).unwrap_or((default, None));
            let size = font.size.filter(|s| Some(*s) != default.size);
            // White fills count as none, as the grid is white.
            let fill = xf.fill.and_then(|i| color_of(i, colors)).filter(|c| c & 0xFF_FFFF != 0xFF_FFFF);
            let key = (font.flags, color, fill, size.map(f32::to_bits), xf.align, xf.wrap);
            let look = match seen.get(&key) {
                Some(&index) => index,
                None if styles.palette.len() >= MAX_LOOKS => 0,
                None => {
                    let index = styles.palette.len() as u16;
                    let (flags, align, wrap) = (font.flags, xf.align, xf.wrap);
                    styles.palette.push(CellStyle { flags, color, fill, size, align, wrap });
                    seen.insert(key, index);
                    index
                }
            };
            styles.looks.push(look);
        }
        styles
    }

    fn format(&self, xf: usize) -> Option<&NumFmt> {
        self.cell_formats.get(xf).copied().flatten().map(|i| &self.formats[i])
    }

    fn look(&self, xf: usize) -> u16 {
        self.looks.get(xf).copied().unwrap_or(0)
    }

    /// Font `index` as XF and run records count them: number 4 does not exist.
    fn font(&self, index: u16) -> Option<(Font, Option<u32>)> {
        let index = usize::from(index);
        let slot = if index > 4 { index - 1 } else { index };
        self.fonts.get(slot).copied()
    }

    /// Spans for formatting runs over text `len` UTF-16 units long.
    fn spans(&self, runs: &[(u16, u16)], len: usize) -> Vec<Span> {
        let len = len as u32;
        let mut spans = Vec::new();
        for (i, &(first, font)) in runs.iter().enumerate() {
            let start = u32::from(first).min(len);
            let end = runs.get(i + 1).map_or(len, |&(next, _)| u32::from(next).min(len));
            let Some((font, color)) = self.font(font) else { continue };
            if end > start && (font.flags != 0 || color.is_some()) {
                spans.push(Span { start, end, style: font.flags, link: None, color });
            }
        }
        spans
    }
}

#[cfg(test)]
pub(crate) mod testing {
    //! Writes minimal BIFF8 streams for fixtures.
    use super::*;

    pub fn rec(id: u16, body: &[u8]) -> Vec<u8> {
        let mut out = id.to_le_bytes().to_vec();
        out.extend_from_slice(&(body.len() as u16).to_le_bytes());
        out.extend_from_slice(body);
        out
    }

    pub fn bof(kind: u16) -> Vec<u8> {
        let mut body = BIFF8.to_le_bytes().to_vec();
        body.extend_from_slice(&kind.to_le_bytes());
        body.extend_from_slice(&[0; 12]);
        rec(BOF, &body)
    }

    pub fn cell(row: u16, column: u16, xf: u16) -> Vec<u8> {
        [row.to_le_bytes(), column.to_le_bytes(), xf.to_le_bytes()].concat()
    }

    pub fn bound_sheet(position: u32, visibility: u8, kind: u8, name: &str) -> Vec<u8> {
        let mut body = position.to_le_bytes().to_vec();
        body.extend_from_slice(&[visibility, kind, name.len() as u8, 0]);
        body.extend_from_slice(name.as_bytes());
        rec(BOUNDSHEET, &body)
    }

    /// A workbook of `globals` records and sheets given as (name, visibility, records).
    pub fn workbook(globals: &[Vec<u8>], sheets: &[(&str, u8, Vec<u8>)]) -> Vec<u8> {
        let globals: Vec<u8> = globals.concat();
        let bound_len: usize = sheets.iter().map(|(name, ..)| bound_sheet(0, 0, 0, name).len()).sum();
        let mut position = bof(5).len() + globals.len() + bound_len + rec(EOF, &[]).len();
        let mut bound = Vec::new();
        for (name, visibility, records) in sheets {
            bound.extend(bound_sheet(position as u32, *visibility, 0, name));
            position += bof(0x10).len() + records.len() + rec(EOF, &[]).len();
        }
        let mut out = bof(5);
        out.extend(globals);
        out.extend(bound);
        out.extend(rec(EOF, &[]));
        for (_, _, records) in sheets {
            out.extend(bof(0x10));
            out.extend_from_slice(records);
            out.extend(rec(EOF, &[]));
        }
        out
    }

    pub fn xf(format: u16) -> Vec<u8> {
        let mut body = vec![0u8; 20];
        body[2..4].copy_from_slice(&format.to_le_bytes());
        rec(XF, &body)
    }

    pub fn format_record(id: u16, code: &str) -> Vec<u8> {
        let mut body = id.to_le_bytes().to_vec();
        body.extend_from_slice(&(code.len() as u16).to_le_bytes());
        body.push(0);
        body.extend_from_slice(code.as_bytes());
        rec(FORMAT, &body)
    }

    pub fn sst(strings: &[&str]) -> Vec<u8> {
        let mut body = (strings.len() as u32).to_le_bytes().to_vec();
        body.extend_from_slice(&(strings.len() as u32).to_le_bytes());
        for text in strings {
            body.extend_from_slice(&(text.len() as u16).to_le_bytes());
            body.push(0);
            body.extend_from_slice(text.as_bytes());
        }
        rec(SST, &body)
    }

    pub fn label_sst(row: u16, column: u16, xf: u16, index: u32) -> Vec<u8> {
        let mut body = cell(row, column, xf);
        body.extend_from_slice(&index.to_le_bytes());
        rec(LABELSST, &body)
    }

    pub fn number(row: u16, column: u16, xf: u16, value: f64) -> Vec<u8> {
        let mut body = cell(row, column, xf);
        body.extend_from_slice(&value.to_le_bytes());
        rec(NUMBER, &body)
    }

    /// A FONT record: size in points, attribute bits, colour index, weight, underline.
    pub fn font(points: u16, attributes: u16, color: u16, weight: u16, underline: u8) -> Vec<u8> {
        let mut body = (points * 20).to_le_bytes().to_vec();
        body.extend_from_slice(&attributes.to_le_bytes());
        body.extend_from_slice(&color.to_le_bytes());
        body.extend_from_slice(&weight.to_le_bytes());
        body.extend_from_slice(&[0, 0, underline, 0, 0, 0, 5, 0]);
        body.extend_from_slice(b"Arial");
        rec(FONT, &body)
    }

    /// A full XF record: font, format, alignment byte and an optional solid fill.
    pub fn styled_xf(font: u16, format: u16, alignment: u8, fill: Option<u16>) -> Vec<u8> {
        let mut body = vec![0u8; 20];
        body[0..2].copy_from_slice(&font.to_le_bytes());
        body[2..4].copy_from_slice(&format.to_le_bytes());
        body[6] = alignment;
        if let Some(fill) = fill {
            body[14..18].copy_from_slice(&(1u32 << 26).to_le_bytes());
            body[18..20].copy_from_slice(&(fill | 65 << 7).to_le_bytes());
        }
        rec(XF, &body)
    }

    pub fn simple_xls() -> Vec<u8> {
        workbook(&[sst(&["Region"]), xf(0)], &[("Data", 0, [label_sst(0, 0, 0, 0), number(1, 0, 0, 12.5)].concat())])
    }
}

#[cfg(test)]
mod tests {
    use super::testing::*;
    use super::*;
    use crate::cfb::testing::cfb;
    use crate::testing::{open_zip, open_zip_err, texts};

    fn rows(globals: &[Vec<u8>], records: Vec<u8>) -> Vec<String> {
        let book = workbook(globals, &[("S", 0, records)]);
        let (format, blocks) = open_zip(cfb(&[("Workbook", &book)]));
        assert_eq!(format, document_core::FormatId::Xls);
        let mut lines = texts(&blocks);
        lines.retain(|l| !l.starts_with("STYLE"));
        assert_eq!(lines.remove(0), "S S");
        lines
    }

    fn blank_styles() -> Vec<Vec<u8>> {
        vec![xf(0)]
    }

    #[test]
    fn reads_every_cell_kind() {
        let mut formula_number = cell(2, 0, 0);
        formula_number.extend_from_slice(&3.5f64.to_le_bytes());
        formula_number.extend_from_slice(&[0; 6]);
        let mut formula_text = cell(2, 1, 0);
        formula_text.extend_from_slice(&[0, 0, 0, 0, 0, 0, 0xFF, 0xFF]);
        formula_text.extend_from_slice(&[0; 6]);
        let mut formula_bool = cell(2, 3, 0);
        formula_bool.extend_from_slice(&[1, 0, 1, 0, 0, 0, 0xFF, 0xFF]);
        formula_bool.extend_from_slice(&[0; 6]);
        let mut formula_error = cell(2, 4, 0);
        formula_error.extend_from_slice(&[2, 0, 0x07, 0, 0, 0, 0xFF, 0xFF]);
        formula_error.extend_from_slice(&[0; 6]);
        let mut formula_empty = cell(2, 5, 0);
        formula_empty.extend_from_slice(&[3, 0, 0, 0, 0, 0, 0xFF, 0xFF]);
        formula_empty.extend_from_slice(&[0; 6]);

        let mut rk_int = cell(1, 0, 0);
        rk_int.extend_from_slice(&((-7i32 << 2) as u32 | 2).to_le_bytes());
        let mut rk_cents = cell(1, 1, 0);
        rk_cents.extend_from_slice(&((1234u32 << 2) | 3).to_le_bytes());
        // 1.0 as a double has only high bits set.
        let mut rk_double = cell(1, 2, 0);
        rk_double.extend_from_slice(&((1.0f64.to_bits() >> 32) as u32).to_le_bytes());
        let mut mulrk = 3u16.to_le_bytes().to_vec();
        mulrk.extend_from_slice(&1u16.to_le_bytes());
        for value in [5u32, 6] {
            mulrk.extend_from_slice(&0u16.to_le_bytes());
            mulrk.extend_from_slice(&((value << 2) | 2).to_le_bytes());
        }
        mulrk.extend_from_slice(&2u16.to_le_bytes());
        let mut bool_cell = cell(4, 0, 0);
        bool_cell.extend_from_slice(&[1, 0]);
        let mut error_cell = cell(4, 1, 0);
        error_cell.extend_from_slice(&[0x2A, 1]);
        let mut label = cell(5, 0, 0);
        label.extend_from_slice(&[2, 0, 1]);
        label.extend("é€".encode_utf16().flat_map(u16::to_le_bytes));

        let mut string_result = 4u16.to_le_bytes().to_vec();
        string_result.push(0);
        string_result.extend_from_slice(b"calc");

        let records = [
            label_sst(0, 0, 0, 0),
            label_sst(0, 2, 0, 1),
            label_sst(0, 3, 0, 99),
            rec(RK, &rk_int),
            rec(RK, &rk_cents),
            rec(RK, &rk_double),
            rec(FORMULA, &formula_number),
            rec(FORMULA, &formula_text),
            rec(STRING, &string_result),
            rec(FORMULA, &formula_bool),
            rec(FORMULA, &formula_error),
            rec(FORMULA, &formula_empty),
            rec(MULRK, &mulrk),
            rec(BOOLERR, &bool_cell),
            rec(BOOLERR, &error_cell),
            rec(LABEL, &label),
        ]
        .concat();
        assert_eq!(
            rows(&[sst(&["Region", "Total"]), xf(0)], records),
            ["R1 Region||Total", "R2 -7|12.34|1", "R3 3.5|calc||TRUE|#DIV/0!", "R4 |5|6", "R5 TRUE|#N/A", "R6 é€"]
        );
    }

    #[test]
    fn applies_formats_and_the_1904_flag() {
        let globals = [format_record(164, "\"$\"#,##0.00"), xf(0), xf(14), xf(9), xf(164), xf(9999), sst(&["Widget"])];
        let records = [
            number(0, 0, 0, 45231.5),
            number(0, 1, 1, 45231.0),
            number(0, 2, 2, 0.25),
            number(0, 3, 3, 1234.5),
            number(0, 4, 4, 8.5),
            number(0, 5, 77, 9.5),
        ]
        .concat();
        assert_eq!(rows(&globals, records.clone()), ["R1 45231.5|11/1/2023|25%|$1,234.50|8.5|9.5"]);

        let mut with_1904 = vec![rec(DATE1904, &1u16.to_le_bytes())];
        with_1904.extend_from_slice(&globals);
        assert_eq!(rows(&with_1904, number(0, 1, 1, 45231.0)), ["R1 |11/2/2027"]);
    }

    #[test]
    fn a_format_record_overrides_a_builtin() {
        assert_eq!(rows(&[format_record(14, "0.0"), xf(14)], number(0, 0, 0, 2.0)), ["R1 2.0"]);
    }

    #[test]
    fn skips_hidden_sheets_and_chart_sheets_and_nested_substreams() {
        let chart_inside =
            [number(0, 0, 0, 1.0), bof(0x20), number(5, 0, 0, 666.0), rec(EOF, &[]), number(1, 0, 0, 2.0)].concat();
        let book = workbook(
            &blank_styles(),
            &[("Shown", 0, chart_inside), ("Hidden", 1, number(0, 0, 0, 3.0)), ("Very", 2, number(0, 0, 0, 4.0))],
        );
        let (_, blocks) = open_zip(cfb(&[("Workbook", &book)]));
        assert_eq!(texts(&blocks), ["S Shown", "R1 1", "R2 2"]);

        // A chart sheet (kind 2) is listed but is not a worksheet.
        let mut globals = vec![xf(0)];
        globals.push(bound_sheet(0, 0, 2, "Chart"));
        let book = workbook(&globals, &[("Real", 0, number(0, 0, 0, 1.0))]);
        let (_, blocks) = open_zip(cfb(&[("Workbook", &book)]));
        assert_eq!(texts(&blocks), ["S Real", "R1 1"]);
    }

    #[test]
    fn reads_shared_strings_across_continue_records() {
        // One string starts narrow, then continues wide; a rich string's runs and
        // an extended string's data straddle a boundary too.
        let mut first = 3u32.to_le_bytes().to_vec();
        first.extend_from_slice(&3u32.to_le_bytes());
        first.extend_from_slice(&4u16.to_le_bytes());
        first.push(0);
        first.extend_from_slice(b"ab");
        let mut second = vec![1u8]; // the characters continue wide
        second.extend("c€".encode_utf16().flat_map(u16::to_le_bytes));
        // Rich: 1 run; the run's 4 bytes are split by the next boundary.
        second.extend_from_slice(&2u16.to_le_bytes());
        second.push(0x08);
        second.extend_from_slice(&1u16.to_le_bytes());
        second.extend_from_slice(b"de");
        second.extend_from_slice(&[9, 9]);
        let mut third = vec![9u8, 9];
        // Extended: 3 bytes after the text.
        third.extend_from_slice(&1u16.to_le_bytes());
        third.push(0x04);
        third.extend_from_slice(&3u32.to_le_bytes());
        third.push(b'f');
        third.extend_from_slice(&[7, 7, 7]);
        let globals = [xf(0), rec(SST, &first), rec(CONTINUE, &second), rec(CONTINUE, &third)];
        let records = [label_sst(0, 0, 0, 0), label_sst(0, 1, 0, 1), label_sst(0, 2, 0, 2)].concat();
        assert_eq!(rows(&globals, records), ["R1 abc€|de|f"]);
    }

    #[test]
    fn encrypted_workbooks_get_a_typed_error() {
        let book = workbook(&[rec(FILEPASS, &[0; 54])], &[("S", 0, number(0, 0, 0, 1.0))]);
        assert_eq!(open_zip_err(cfb(&[("Workbook", &book)])).kind, ErrorKind::UnsupportedEncryption);
    }

    #[test]
    fn older_excel_gets_a_typed_error() {
        let mut body = 0x0500u16.to_le_bytes().to_vec();
        body.extend_from_slice(&[5, 0, 0, 0, 0, 0, 0, 0]);
        let book = rec(BOF, &body);
        assert_eq!(open_zip_err(cfb(&[("Workbook", &book)])).kind, ErrorKind::UnsupportedFormat);
        assert_eq!(open_zip_err(cfb(&[("Book", &book)])).kind, ErrorKind::UnsupportedFormat);
    }

    #[test]
    fn hostile_workbooks_fail_cleanly() {
        let malformed = |book: Vec<u8>| {
            assert_eq!(open_zip_err(cfb(&[("Workbook", &book)])).kind, ErrorKind::Malformed);
        };
        // Empty stream, truncated record, no BOF, no sheets.
        malformed(Vec::new());
        malformed(vec![0x09, 0x08, 0xFF]);
        malformed(rec(EOF, &[]));
        malformed([bof(5), rec(EOF, &[])].concat());
        // A record's length overruns the stream.
        let mut cut = workbook(&blank_styles(), &[("S", 0, number(0, 0, 0, 1.0))]);
        cut.truncate(cut.len() - 6);
        malformed(cut);
        // A sheet offset outside the stream or not at a BOF.
        malformed([bof(5), bound_sheet(0xFFFF_FFF0, 0, 0, "S"), rec(EOF, &[])].concat());
        malformed([bof(5), bound_sheet(20, 0, 0, "S"), rec(EOF, &[])].concat());
        // A shared-string table promising more than it holds.
        let mut sst_body = 1u32.to_le_bytes().to_vec();
        sst_body.extend_from_slice(&1u32.to_le_bytes());
        sst_body.extend_from_slice(&[0xFF, 0xFF, 0]);
        malformed(workbook(&[rec(SST, &sst_body)], &[("S", 0, Vec::new())]));
        // Extended data larger than the file.
        let mut sst_body = 1u32.to_le_bytes().to_vec();
        sst_body.extend_from_slice(&1u32.to_le_bytes());
        sst_body.extend_from_slice(&[0, 0, 0x04, 0xFF, 0xFF, 0xFF, 0xFF]);
        malformed(workbook(&[rec(SST, &sst_body)], &[("S", 0, Vec::new())]));
    }

    #[test]
    fn hostile_cells_stay_bounded() {
        // Columns past the cap are dropped; truncated cell records are ignored.
        let records = [
            number(0, 300, 0, 1.0),
            number(0, u16::MAX, 0, 1.0),
            rec(NUMBER, &[0; 3]),
            rec(MULRK, &[0; 3]),
            rec(FORMULA, &[0; 7]),
            rec(BOOLERR, &[0; 7]),
            rec(LABELSST, &[0; 8]),
            number(1, 0, 0, 5.0),
        ]
        .concat();
        assert_eq!(rows(&blank_styles(), records), ["R2 5"]);
    }

    #[test]
    fn cells_carry_fonts_fills_alignment_merges_and_runs() {
        let mut palette = 3u16.to_le_bytes().to_vec();
        palette.extend_from_slice(&[0, 0, 0, 0, 0, 0, 0, 0, 0x12, 0x34, 0x56, 0]);
        // One shared string with two runs: plain, then bold red from character 4.
        let mut rich = 1u32.to_le_bytes().to_vec();
        rich.extend_from_slice(&1u32.to_le_bytes());
        rich.extend_from_slice(&8u16.to_le_bytes());
        rich.push(0x08);
        rich.extend_from_slice(&2u16.to_le_bytes());
        rich.extend_from_slice(b"two bold");
        rich.extend_from_slice(&[0, 0, 0, 0, 4, 0, 1, 0]);
        let globals = [
            font(10, 0, 0x7FFF, 400, 0),
            font(12, 0, 2, 700, 0),
            font(10, 0x02 | 0x08, 0x7FFF, 400, 1),
            font(10, 0, 0x7FFF, 400, 0),
            font(10, 0, 10, 400, 0),
            rec(PALETTE, &palette),
            styled_xf(0, 0, 0, None),
            styled_xf(1, 0, 2, None),
            styled_xf(0, 0, 0x08, Some(5)),
            styled_xf(5, 0, 3, None),
            styled_xf(3, 0, 0, Some(1)),
            styled_xf(2, 0, 0, None),
            rec(SST, &rich),
        ];
        let blank = cell(1, 2, 2);
        let mut mulblank = cell(2, 0, 4);
        mulblank.extend_from_slice(&4u16.to_le_bytes());
        mulblank.extend_from_slice(&1u16.to_le_bytes());
        let mut merged = 2u16.to_le_bytes().to_vec();
        merged.extend_from_slice(&[0, 0, 0, 0, 0, 0, 1, 0]);
        merged.extend_from_slice(&[3, 0, 3, 0, 1, 0, 1, 0]);
        let records = [
            label_sst(0, 0, 1, 0),
            number(0, 1, 3, 2.0),
            number(0, 2, 5, 3.0),
            rec(BLANK, &blank),
            rec(MULBLANK, &mulblank),
            rec(MERGEDCELLS, &merged),
        ]
        .concat();
        let lines = rows(&globals, records);
        assert_eq!(
            lines,
            ["R1 two bold|2|3 [1, 3, 4]", "RUNS 1,0 [(4, 8, 1, Some(4294901760))]", "R2  [0, 0, 2]", "MERGE 1,0:1,1",]
        );
        let book = workbook(&globals, &[("S", 0, number(0, 0, 1, 1.0))]);
        let (_, blocks) = open_zip(cfb(&[("Workbook", &book)]));
        let styles: Vec<_> = blocks.iter().filter(|b| matches!(b, Block::CellStyle { .. })).cloned().collect();
        let look = |flags, color, fill, size, align, wrap| CellStyle { flags, color, fill, size, align, wrap };
        assert_eq!(
            styles,
            [
                Block::CellStyle { index: 1, style: look(BOLD, Some(0xFFFF_0000), None, Some(12.0), 2, false) },
                Block::CellStyle { index: 2, style: look(0, None, Some(0xFFFF_FF00), None, 0, true) },
                Block::CellStyle { index: 3, style: look(0, Some(0xFF12_3456), None, None, 3, false) },
                Block::CellStyle { index: 4, style: look(ITALIC | STRIKE | UNDERLINE, None, None, None, 0, false) },
            ]
        );
    }

    #[test]
    fn hostile_styles_stay_bounded() {
        // Cut-off FONT, XF, PALETTE and MERGEDCELLS records leave plain cells.
        let globals =
            [rec(FONT, &[1]), rec(XF, &[0; 3]), rec(PALETTE, &[0xFF, 0xFF, 1, 2]), styled_xf(9, 0, 0xFF, Some(0x7F))];
        let mut merged = 0xFFFFu16.to_le_bytes().to_vec();
        merged.extend_from_slice(&[0, 0, 0, 0, 0, 1, 0xFF, 0xFF]);
        merged.extend_from_slice(&[1, 2, 3]);
        let records = [number(0, 0, 1, 1.0), number(0, 1, 500, 2.0), rec(MERGEDCELLS, &merged)].concat();
        assert_eq!(rows(&globals, records), ["R1 1|2 [1]"]);
    }

    #[test]
    fn simple_fixture_opens() {
        let (format, blocks) = open_zip(cfb(&[("Workbook", &simple_xls())]));
        assert_eq!(format, document_core::FormatId::Xls);
        assert_eq!(texts(&blocks), ["S Data", "R1 Region", "R2 12.5"]);
    }
}
