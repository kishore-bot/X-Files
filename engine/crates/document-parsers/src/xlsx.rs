//! Excel (XLSX): every visible sheet, streamed row by row.
//!
//! Each sheet starts with a [`Block::Section`] naming it, followed by one
//! [`Block::TableRow`] per non-empty row, numbered as in the sheet. Cells show
//! their cached values; formulas are not evaluated. Each cell's style selects a
//! number format from `xl/styles.xml`, applied by [`crate::numfmt`] to show
//! dates, currency and percentages as the spreadsheet does, honouring the
//! workbook's `date1904` flag. A missing or malformed styles part, or a format
//! that cannot be applied, leaves the plain cached value. At most
//! [`MAX_COLUMNS`] columns are kept.
//!
//! How cells look comes from the same part: fonts (bold, italic, underline,
//! strikethrough, colour, size), solid fills and horizontal alignment, with
//! theme and indexed colours resolved. Distinct looks form a palette sent as
//! [`Block::CellStyle`]s before the first sheet; rows refer to it by index.
//! White fills count as none, as the grid is white. Merged cells follow each
//! sheet's rows as [`Block::Merge`]s, because the sheet lists them last.
//! Shared text with runs styled differently (one word in red, say) follows
//! its row as a [`Block::CellRuns`].

use std::collections::{HashMap, VecDeque};

use document_core::style::{BOLD, ITALIC, STRIKE, UNDERLINE};
use document_core::{Block, CellStyle, EngineError, ErrorKind, MAX_CELL_RUNS, Result, Span};

use crate::dom;
use crate::numfmt::{NumFmt, builtin, general};
use crate::ooxml::{relationships, target_of};
use crate::pptx::{from_hsl, hex, theme_colors, to_hsl};
use crate::rich::RichBuilder;
use crate::stream::{BlockSource, MAX_PENDING_TEXT, Parts};
use crate::xml::{Name, Xml};

pub(crate) const MAX_COLUMNS: usize = 256;
/// Excel allows 65,490 cell formats; cells styled beyond this show plain values.
const MAX_CELL_FORMATS: usize = 65_536;
/// Total size of the custom format codes kept; each one is also capped by
/// [`NumFmt::parse`]. Real workbooks hold a few hundred bytes of them.
const MAX_FORMAT_CODE_BYTES: usize = 256 * 1024;
/// Distinct cell looks kept; cells beyond show plain.
const MAX_LOOKS: usize = 4096;
/// Fonts and fills read; real workbooks have tens.
const MAX_FONTS_AND_FILLS: usize = 4096;
/// Styled runs kept across the shared text; text beyond shows in its cell's look.
const MAX_SHARED_RUNS: usize = 200_000;
/// Merged ranges kept per sheet.
const MAX_MERGES: u32 = 10_000;

pub(crate) struct XlsxSource {
    parts: Parts,
    sheets: VecDeque<(String, String)>,
    shared: Vec<String>,
    /// Styled runs of shared strings that have them, by index.
    shared_runs: HashMap<usize, Vec<Span>>,
    theme: Vec<u32>,
    styles: Styles,
    date1904: bool,
    in_sheet: bool,
    /// The palette went out, before the first sheet.
    looks_sent: bool,
    merges: u32,
    row: Row,
    cell: Cell,
}

#[derive(Default)]
struct Row {
    number: u32,
    cells: Vec<String>,
    /// Palette index per cell; empty until a styled cell comes.
    looks: Vec<u16>,
    /// Styled runs by column, for cells that have them.
    runs: Vec<(u32, Vec<Span>)>,
    next_column: usize,
}

impl Row {
    fn set_look(&mut self, column: usize, look: u16) {
        if look == 0 || column >= MAX_COLUMNS {
            return;
        }
        if self.looks.len() <= column {
            self.looks.resize(column + 1, 0);
        }
        self.looks[column] = look;
    }
}

/// Longer formulas are cut: the cell shows them for reference only.
const MAX_FORMULA_CHARS: usize = 1024;

#[derive(Default)]
struct Cell {
    column: usize,
    /// The `t` attribute: `s`, `n`, `b`, `e`, `str` or `inlineStr`; inline, so no allocation per cell.
    kind: Name,
    style: usize,
    value: String,
    /// The formula, shown when the file has no cached value for it.
    formula: String,
    in_value: bool,
    in_inline_text: bool,
    in_formula: bool,
    /// An inline string's text with its styled runs, and the current run's look.
    inline: RichBuilder,
    in_props: bool,
    run_flags: u8,
    run_color: Option<u32>,
}

impl XlsxSource {
    pub fn open(mut parts: Parts, workbook: String) -> Result<Self> {
        let rels = relationships(parts.archive(), &workbook)?;
        let targets: HashMap<&str, &str> =
            rels.iter().filter(|r| !r.external).map(|r| (r.id.as_str(), r.target.as_str())).collect();
        let (sheets, date1904) = read_workbook(&mut parts, &workbook)?;
        let sheets = sheets
            .into_iter()
            .filter_map(|(name, id)| targets.get(id.as_str()).map(|t| (name, t.to_string())))
            .collect();
        let theme = match target_of(&rels, "theme") {
            Some(path) => read_theme(&mut parts, &path)?,
            None => Vec::new(),
        };
        let (shared, shared_runs) = match target_of(&rels, "sharedStrings") {
            Some(path) if parts.archive().find(&path).is_some() => read_shared_strings(&mut parts, &path, &theme)?,
            _ => (Vec::new(), HashMap::new()),
        };
        let styles = match target_of(&rels, "styles") {
            Some(path) => read_styles(&mut parts, &path, &theme)?,
            None => Styles::default(),
        };
        Ok(Self {
            parts,
            sheets,
            shared,
            shared_runs,
            theme,
            styles,
            date1904,
            in_sheet: false,
            looks_sent: false,
            merges: 0,
            row: Row::default(),
            cell: Cell::default(),
        })
    }

    /// Starts a cell, keeping the value buffer's capacity from the last one.
    fn start_cell(&mut self, column: usize, kind: Option<&str>, style: usize) {
        let cell = &mut self.cell;
        cell.column = column;
        cell.kind = Name::new(kind.unwrap_or("n"));
        cell.style = style;
        cell.value.clear();
        cell.formula.clear();
        cell.in_value = false;
        cell.in_inline_text = false;
        cell.in_formula = false;
        cell.inline.take();
        cell.in_props = false;
        (cell.run_flags, cell.run_color) = (0, None);
    }

    fn end_cell(&mut self) {
        let cell = &mut self.cell;
        cell.in_value = false;
        cell.in_inline_text = false;
        cell.in_formula = false;
        self.row.next_column = cell.column + 1;
        if cell.column >= MAX_COLUMNS {
            return;
        }
        let raw = cell.value.as_str();
        let format = self.styles.format(cell.style);
        // Formulas aren't evaluated: without a cached result, the formula itself is shown.
        let mut runs = None;
        let value = if raw.is_empty() && !cell.formula.is_empty() {
            format!("={}", cell.formula)
        } else {
            match cell.kind.as_str() {
                "s" => {
                    let index = raw.trim().parse::<usize>().ok();
                    let shared = index.and_then(|i| self.shared.get(i)).map_or("", String::as_str);
                    let shown = format.and_then(|f| f.format_text(shared)).unwrap_or_else(|| shared.to_owned());
                    // Runs only fit the text as written.
                    if shown == shared {
                        runs = index.and_then(|i| self.shared_runs.get(&i)).cloned();
                    }
                    shown
                }
                "b" => if raw.trim() == "1" { "TRUE" } else { "FALSE" }.to_owned(),
                "e" => raw.to_owned(),
                "str" | "inlineStr" => {
                    let shown = format.and_then(|f| f.format_text(raw)).unwrap_or_else(|| raw.to_owned());
                    let mut rich = cell.inline.take();
                    if shown == raw && rich.text == raw && !rich.spans.is_empty() {
                        rich.spans.truncate(MAX_CELL_RUNS);
                        runs = Some(rich.spans);
                    }
                    shown
                }
                _ => format
                    .zip(raw.trim().parse::<f64>().ok())
                    .and_then(|(f, number)| f.format_number(number, self.date1904))
                    .unwrap_or_else(|| format_number(raw)),
            }
        };
        let column = cell.column;
        cell.value.clear();
        self.row.set_look(column, self.styles.look(cell.style));
        if value.is_empty() {
            return;
        }
        if self.row.cells.len() <= column {
            self.row.cells.resize(column + 1, String::new());
        }
        self.row.cells[column] = value;
        if let Some(runs) = runs {
            self.row.runs.retain(|(c, _)| *c as usize != column);
            self.row.runs.push((column as u32, runs));
        }
    }
}

impl BlockSource for XlsxSource {
    fn pump(&mut self, out: &mut VecDeque<Block>) -> Result<bool> {
        if !self.looks_sent {
            self.looks_sent = true;
            for (index, style) in self.styles.palette.iter().enumerate().skip(1) {
                out.push_back(Block::CellStyle { index: index as u16, style: *style });
            }
        }
        if !self.in_sheet {
            let Some((name, path)) = self.sheets.pop_front() else {
                self.parts.close();
                return Ok(true);
            };
            out.push_back(Block::Section { title: name });
            if self.parts.archive().find(&path).is_none() {
                return Ok(false);
            }
            self.parts.open(&path)?;
            self.in_sheet = true;
            self.merges = 0;
            self.row = Row::default();
            return Ok(false);
        }
        let before = out.len();
        while out.len() == before {
            match self.parts.xml().next()? {
                Xml::Start(tag) | Xml::Empty(tag) if tag.name == "row" => {
                    let number = tag.attr("r").and_then(|r| r.parse().ok()).unwrap_or(self.row.number + 1);
                    self.row = Row { number, ..Row::default() };
                }
                Xml::Start(tag) if tag.name == "c" => {
                    let column = tag.attr("r").and_then(column_index).unwrap_or(self.row.next_column);
                    let style = tag.attr("s").and_then(|s| s.parse().ok()).unwrap_or(0);
                    self.start_cell(column, tag.attr("t"), style);
                }
                Xml::Empty(tag) if tag.name == "c" => {
                    let column = tag.attr("r").and_then(column_index).unwrap_or(self.row.next_column);
                    self.row.next_column = column + 1;
                    // An empty cell matters only for its colour.
                    let look = tag.attr("s").and_then(|s| s.parse().ok()).map_or(0, |s| self.styles.look(s));
                    if self.styles.palette.get(usize::from(look)).is_some_and(|l| l.fill.is_some()) {
                        self.row.set_look(column, look);
                    }
                }
                Xml::Start(tag) | Xml::Empty(tag) if tag.name == "mergeCell" => {
                    if let Some(merge) = tag.attr("ref").and_then(merge_range)
                        && self.merges < MAX_MERGES
                    {
                        self.merges += 1;
                        out.push_back(merge);
                    }
                }
                Xml::Start(tag) if tag.name == "r" => (self.cell.run_flags, self.cell.run_color) = (0, None),
                Xml::Start(tag) if tag.name == "rPr" => self.cell.in_props = true,
                Xml::Start(tag) | Xml::Empty(tag) if self.cell.in_props => {
                    run_property(&tag, &self.theme, &mut self.cell.run_flags, &mut self.cell.run_color)
                }
                Xml::Start(tag) if tag.name == "v" => self.cell.in_value = true,
                Xml::Start(tag) if tag.name == "t" => self.cell.in_inline_text = true,
                Xml::Start(tag) if tag.name == "f" => self.cell.in_formula = true,
                Xml::Start(tag) if tag.name == "rPh" => self.parts.xml().skip_element()?,
                Xml::Text(text) if self.cell.in_value || self.cell.in_inline_text => {
                    if self.cell.value.len() < MAX_PENDING_TEXT {
                        self.cell.value.push_str(&text);
                        if self.cell.in_inline_text {
                            let cell = &mut self.cell;
                            cell.inline.push_colored(&text, cell.run_flags, None, cell.run_color);
                        }
                    }
                }
                Xml::Text(text) if self.cell.in_formula => {
                    if self.cell.formula.len() < MAX_FORMULA_CHARS {
                        self.cell.formula.push_str(&text);
                    }
                }
                Xml::End(name) => match name.as_str() {
                    "v" => self.cell.in_value = false,
                    "f" => self.cell.in_formula = false,
                    "t" => self.cell.in_inline_text = false,
                    "rPr" => self.cell.in_props = false,
                    "r" => (self.cell.run_flags, self.cell.run_color) = (0, None),
                    "c" => self.end_cell(),
                    "row" => {
                        let mut cells = std::mem::take(&mut self.row.cells);
                        let mut looks = std::mem::take(&mut self.row.looks);
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
                        if !cells.is_empty() || !looks.is_empty() {
                            out.push_back(Block::TableRow {
                                row: Some(self.row.number),
                                header: false,
                                cells,
                                styles: looks,
                                cell_spans: Vec::new(),
                            });
                        }
                        for (column, spans) in std::mem::take(&mut self.row.runs) {
                            out.push_back(Block::CellRuns { row: self.row.number, column, spans });
                        }
                    }
                    _ => {}
                },
                Xml::Eof => {
                    self.parts.close();
                    self.in_sheet = false;
                    return Ok(false);
                }
                _ => {}
            }
        }
        Ok(false)
    }
}

/// (name, relationship id) of each visible sheet, in workbook order, and
/// whether dates count from 1904.
fn read_workbook(parts: &mut Parts, workbook: &str) -> Result<(Vec<(String, String)>, bool)> {
    let xml = parts.open(workbook)?;
    let mut sheets = Vec::new();
    let mut date1904 = false;
    loop {
        match xml.next()? {
            Xml::Start(tag) | Xml::Empty(tag) if tag.name == "workbookPr" => {
                date1904 = tag
                    .attr("date1904")
                    .is_some_and(|v| v == "1" || v.eq_ignore_ascii_case("true") || v.eq_ignore_ascii_case("on"));
            }
            Xml::Start(tag) | Xml::Empty(tag) if tag.name == "sheet" => {
                let hidden = matches!(tag.attr("state"), Some("hidden" | "veryHidden"));
                if let (false, Some(name), Some(id)) = (hidden, tag.attr("name"), tag.prefixed_attr("id")) {
                    sheets.push((name.to_owned(), id.to_owned()));
                }
            }
            Xml::Eof => break,
            _ => {}
        }
    }
    parts.close();
    if sheets.is_empty() {
        return Err(EngineError::malformed("the workbook lists no sheets"));
    }
    Ok((sheets, date1904))
}

/// One property of a text run (`rPr` child): bold, italic, underline, strikethrough or colour.
fn run_property(tag: &crate::xml::Tag, theme: &[u32], flags: &mut u8, color: &mut Option<u32>) {
    let on = !matches!(tag.attr("val"), Some("0" | "false" | "none"));
    match tag.name.as_str() {
        "b" if on => *flags |= BOLD,
        "i" if on => *flags |= ITALIC,
        "u" if on => *flags |= UNDERLINE,
        "strike" if on => *flags |= STRIKE,
        "color" => *color = raw_color(tag).and_then(|c| resolve(c, theme)),
        _ => {}
    }
}

/// Styled runs of shared strings, by string index.
type SharedRuns = HashMap<usize, Vec<Span>>;

/// The shared text, and the styled runs of the strings that have them.
fn read_shared_strings(parts: &mut Parts, path: &str, theme: &[u32]) -> Result<(Vec<String>, SharedRuns)> {
    let limit = parts.archive().limits().max_part_bytes;
    let xml = parts.open(path)?;
    let mut strings = Vec::new();
    let mut runs = HashMap::new();
    let mut kept_runs = 0usize;
    let mut current = RichBuilder::default();
    let (mut in_text, mut in_props, mut total) = (false, false, 0u64);
    // The current run's look: style flags and colour.
    let (mut flags, mut color) = (0u8, None);
    loop {
        match xml.next()? {
            Xml::Start(tag) if tag.name == "si" => {
                current.take();
            }
            Xml::Start(tag) if tag.name == "r" => (flags, color) = (0, None),
            Xml::Start(tag) if tag.name == "rPr" => in_props = true,
            Xml::End(name) if name == "rPr" => in_props = false,
            Xml::Start(tag) | Xml::Empty(tag) if in_props => run_property(&tag, theme, &mut flags, &mut color),
            Xml::Start(tag) if tag.name == "t" => in_text = true,
            Xml::Start(tag) if tag.name == "rPh" => xml.skip_element()?,
            Xml::Text(text) if in_text => current.push_colored(&text, flags, None, color),
            Xml::End(name) if name == "t" => in_text = false,
            Xml::End(name) if name == "r" => (flags, color) = (0, None),
            Xml::End(name) if name == "si" => {
                let mut rich = current.take();
                total += rich.text.len() as u64;
                if total > limit {
                    return Err(EngineError::limit("the spreadsheet's shared text is too large"));
                }
                if !rich.spans.is_empty() && kept_runs < MAX_SHARED_RUNS {
                    rich.spans.truncate(MAX_CELL_RUNS);
                    kept_runs += rich.spans.len();
                    runs.insert(strings.len(), rich.spans);
                }
                strings.push(rich.text);
            }
            Xml::Eof => break,
            _ => {}
        }
    }
    parts.close();
    Ok((strings, runs))
}

/// The number format and look of each cell style.
struct Styles {
    formats: Vec<NumFmt>,
    /// Index into `formats` per `cellXfs` entry; `None` shows the plain value.
    cell_formats: Vec<Option<usize>>,
    /// Distinct looks; 0 is plain.
    palette: Vec<CellStyle>,
    /// Index into `palette` per `cellXfs` entry.
    looks: Vec<u16>,
}

impl Default for Styles {
    fn default() -> Self {
        Self { formats: Vec::new(), cell_formats: Vec::new(), palette: vec![CellStyle::default()], looks: Vec::new() }
    }
}

impl Styles {
    fn format(&self, style: usize) -> Option<&NumFmt> {
        self.cell_formats.get(style).copied().flatten().map(|i| &self.formats[i])
    }

    fn look(&self, style: usize) -> u16 {
        self.looks.get(style).copied().unwrap_or(0)
    }
}

/// A colour as the styles part writes it, resolved once the theme is known.
#[derive(Clone, Copy, PartialEq)]
enum RawColor {
    Rgb(u32),
    Theme(usize),
    Indexed(usize),
}

#[derive(Clone, Copy)]
struct Tinted {
    color: RawColor,
    tint: f32,
}

#[derive(Default, Clone, Copy)]
struct Font {
    flags: u8,
    size: Option<f32>,
    color: Option<Tinted>,
}

#[derive(Default, Clone, Copy)]
struct XfLook {
    font: usize,
    fill: usize,
    align: u8,
    wrap: bool,
}

/// `color`, `fgColor` and the like; `auto` and system colours are the default.
fn raw_color(tag: &crate::xml::Tag) -> Option<Tinted> {
    let color = if let Some(rgb) = tag.attr("rgb") {
        let rgb = rgb.trim();
        let argb = match rgb.len() {
            8 => u32::from_str_radix(rgb, 16).ok().map(|v| v | 0xFF00_0000),
            6 => hex(rgb),
            _ => None,
        }?;
        RawColor::Rgb(argb)
    } else if let Some(theme) = tag.attr("theme") {
        RawColor::Theme(theme.parse().ok()?)
    } else {
        RawColor::Indexed(tag.attr("indexed")?.parse().ok()?)
    };
    let tint = tag.attr("tint").and_then(|t| t.parse::<f32>().ok()).filter(|t| t.is_finite()).unwrap_or(0.0);
    Some(Tinted { color, tint: tint.clamp(-1.0, 1.0) })
}

/// Theme slots in the order cells number them: light and dark swap places.
const THEME_SLOTS: [&str; 12] =
    ["lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink"];

/// Excel's legacy palette for `indexed` colours; 64 and up are system colours.
pub(crate) const INDEXED: [u32; 64] = [
    0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF, 0x000000, 0xFFFFFF, 0xFF0000,
    0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF, 0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080,
    0xC0C0C0, 0x808080, 0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF, 0x000080,
    0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF, 0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99,
    0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99, 0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699,
    0x969696, 0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
];

fn resolve(color: Tinted, theme: &[u32]) -> Option<u32> {
    let base = match color.color {
        RawColor::Rgb(argb) => argb,
        RawColor::Theme(i) => *theme.get(i)?,
        RawColor::Indexed(i) => 0xFF00_0000 | *INDEXED.get(i)?,
    };
    if color.tint == 0.0 {
        return Some(base);
    }
    let channel = |shift: u32| ((base >> shift) & 0xFF) as f32 / 255.0;
    let (h, s, l) = to_hsl([channel(16), channel(8), channel(0)]);
    let l = if color.tint < 0.0 { l * (1.0 + color.tint) } else { l * (1.0 - color.tint) + color.tint };
    let rgb = from_hsl(h, s, l.clamp(0.0, 1.0));
    let byte = |c: f32| (c.clamp(0.0, 1.0) * 255.0).round() as u32;
    Some((base & 0xFF00_0000) | (byte(rgb[0]) << 16) | (byte(rgb[1]) << 8) | byte(rgb[2]))
}

/// The workbook theme's colours in cell order. Only colours depend on it, so
/// a missing or broken theme leaves theme colours as the default.
fn read_theme(parts: &mut Parts, path: &str) -> Result<Vec<u32>> {
    let limit = parts.archive().limits().max_part_bytes;
    if !parts.archive().find(path).is_some_and(|entry| entry.size <= limit) {
        return Ok(Vec::new());
    }
    let cancel = parts.cancel();
    let node = match parts.archive().read_to_vec(path).and_then(|bytes| dom::parse(&bytes, &cancel)) {
        Err(err) if err.kind == ErrorKind::Cancelled => return Err(err),
        Err(_) | Ok(None) => return Ok(Vec::new()),
        Ok(Some(node)) => node,
    };
    let colors = theme_colors(&node);
    Ok(THEME_SLOTS.iter().map_while(|slot| colors.get(*slot).copied()).collect())
}

/// `"B2:D3"` as a merge; a single cell is none.
fn merge_range(reference: &str) -> Option<Block> {
    let (start, end) = reference.split_once(':')?;
    let cell = |r: &str| -> Option<(u32, u32)> {
        let column = u32::try_from(column_index(r)?).ok()?;
        let row = r.trim_start_matches(|c: char| c.is_ascii_alphabetic()).parse::<u32>().ok()?;
        Some((row, column))
    };
    let ((r1, c1), (r2, c2)) = (cell(start)?, cell(end)?);
    let (first_row, last_row) = (r1.min(r2), r1.max(r2));
    let (first_column, last_column) = (c1.min(c2), c1.max(c2));
    if (first_row, first_column) == (last_row, last_column) || first_column as usize >= MAX_COLUMNS {
        return None;
    }
    let last_column = last_column.min(MAX_COLUMNS as u32 - 1);
    Some(Block::Merge { first_row, first_column, last_row, last_column })
}

/// Styles only improve how values look, so any failure here leaves plain values
/// rather than failing the document. Only cancellation is passed on.
fn read_styles(parts: &mut Parts, path: &str, theme: &[u32]) -> Result<Styles> {
    let limit = parts.archive().limits().max_part_bytes;
    if !parts.archive().find(path).is_some_and(|entry| entry.size <= limit) {
        return Ok(Styles::default());
    }
    let styles = match parse_styles(parts, path, theme) {
        Err(err) if err.kind == ErrorKind::Cancelled => return Err(err),
        Err(_) => Styles::default(),
        Ok(styles) => styles,
    };
    parts.close();
    Ok(styles)
}

fn parse_styles(parts: &mut Parts, path: &str, theme: &[u32]) -> Result<Styles> {
    let xml = parts.open(path)?;
    let mut codes: HashMap<u32, String> = HashMap::new();
    let mut cell_ids: Vec<Option<u32>> = Vec::new();
    let (mut in_codes, mut in_cells, mut code_bytes) = (false, false, 0);
    let (mut fonts, mut fills, mut xfs) = (Vec::<Font>::new(), Vec::<Option<Tinted>>::new(), Vec::<XfLook>::new());
    // Fonts and fills also appear inside `dxfs`, for conditional formats.
    let (mut in_fonts, mut in_fills, mut in_fill) = (false, false, false);
    let mut solid = false;
    loop {
        match xml.next()? {
            Xml::Start(tag) if tag.name == "fonts" => in_fonts = true,
            Xml::End(name) if name == "fonts" => in_fonts = false,
            Xml::Start(tag) if tag.name == "fills" => in_fills = true,
            Xml::End(name) if name == "fills" => in_fills = false,
            Xml::Start(tag) | Xml::Empty(tag) if in_fonts && tag.name == "font" => {
                if fonts.len() < MAX_FONTS_AND_FILLS {
                    fonts.push(Font::default());
                }
            }
            Xml::Start(tag) | Xml::Empty(tag) if in_fonts => {
                if let Some(font) = fonts.last_mut() {
                    let on = !matches!(tag.attr("val"), Some("0" | "false" | "none"));
                    match tag.name.as_str() {
                        "b" if on => font.flags |= BOLD,
                        "i" if on => font.flags |= ITALIC,
                        "u" if on => font.flags |= UNDERLINE,
                        "strike" if on => font.flags |= STRIKE,
                        "sz" => {
                            font.size =
                                tag.attr("val").and_then(|v| v.parse().ok()).filter(|v: &f32| (1.0..=409.0).contains(v))
                        }
                        "color" => font.color = raw_color(&tag),
                        _ => {}
                    }
                }
            }
            Xml::Start(tag) | Xml::Empty(tag) if in_fills && tag.name == "fill" => {
                // An empty `<fill/>` still takes its place in the list.
                in_fill = fills.len() < MAX_FONTS_AND_FILLS;
                if in_fill {
                    fills.push(None);
                }
                solid = false;
            }
            Xml::End(name) if name == "fill" => in_fill = false,
            Xml::Start(tag) | Xml::Empty(tag) if in_fills && in_fill && tag.name == "patternFill" => {
                solid = tag.attr("patternType") == Some("solid");
            }
            Xml::Start(tag) | Xml::Empty(tag) if in_fills && in_fill && solid && tag.name == "fgColor" => {
                if let Some(fill) = fills.last_mut() {
                    *fill = raw_color(&tag);
                }
            }
            Xml::Start(tag) | Xml::Empty(tag) if in_cells && tag.name == "alignment" => {
                if let Some(xf) = xfs.last_mut() {
                    xf.align = match tag.attr("horizontal") {
                        Some("left") => 1,
                        Some("center" | "centerContinuous") => 2,
                        Some("right") => 3,
                        _ => 0,
                    };
                    xf.wrap = matches!(tag.attr("wrapText"), Some("1" | "true"));
                }
            }
            // `numFmt` also appears inside `dxfs`, and `xf` inside `cellStyleXfs`.
            Xml::Start(tag) if tag.name == "numFmts" => in_codes = true,
            Xml::End(name) if name == "numFmts" => in_codes = false,
            Xml::Start(tag) if tag.name == "cellXfs" => in_cells = true,
            Xml::End(name) if name == "cellXfs" => in_cells = false,
            Xml::Start(tag) | Xml::Empty(tag) if in_codes && tag.name == "numFmt" => {
                let id = tag.attr("numFmtId").and_then(|id| id.parse().ok());
                if let (Some(id), Some(code)) = (id, tag.attr("formatCode")) {
                    code_bytes += code.len();
                    if code_bytes <= MAX_FORMAT_CODE_BYTES {
                        codes.insert(id, code.to_owned());
                    }
                }
            }
            Xml::Start(tag) | Xml::Empty(tag) if in_cells && tag.name == "xf" => {
                // Positions matter: a cell's `s` counts these, parsable or not.
                if cell_ids.len() < MAX_CELL_FORMATS {
                    cell_ids.push(tag.attr("numFmtId").and_then(|id| id.parse().ok()));
                    let index = |name| tag.attr(name).and_then(|v| v.parse().ok()).unwrap_or(0);
                    xfs.push(XfLook { font: index("fontId"), fill: index("fillId"), align: 0, wrap: false });
                }
            }
            Xml::Eof => break,
            _ => {}
        }
    }

    let mut styles = Styles::default();
    styles.looks = palette(&fonts, &fills, &xfs, theme, &mut styles.palette);
    let mut compiled: HashMap<u32, Option<usize>> = HashMap::new();
    for id in cell_ids {
        let format = id.and_then(|id| {
            *compiled.entry(id).or_insert_with(|| {
                // General is what plain values show anyway.
                let code = codes.get(&id).map(String::as_str).or_else(|| builtin(id)).filter(|_| id != 0)?;
                styles.formats.push(NumFmt::parse(code)?);
                Some(styles.formats.len() - 1)
            })
        });
        styles.cell_formats.push(format);
    }
    Ok(styles)
}

/// Each `cellXfs` entry's look as an index into `palette`, which gains the
/// distinct ones. Only what differs from the workbook's default font counts.
fn palette(
    fonts: &[Font],
    fills: &[Option<Tinted>],
    xfs: &[XfLook],
    theme: &[u32],
    palette: &mut Vec<CellStyle>,
) -> Vec<u16> {
    let default = fonts.first().copied().unwrap_or_default();
    let default_color = default.color.and_then(|c| resolve(c, theme));
    // A look's fields, with the size as bits so it can be hashed.
    type Key = (u8, Option<u32>, Option<u32>, Option<u32>, u8, bool);
    let mut seen: HashMap<Key, u16> = HashMap::new();
    seen.insert((0, None, None, None, 0, false), 0);
    xfs.iter()
        .map(|xf| {
            let font = fonts.get(xf.font).copied().unwrap_or(default);
            let color = font.color.and_then(|c| resolve(c, theme)).filter(|c| Some(*c) != default_color);
            let fill = fills
                .get(xf.fill)
                .copied()
                .flatten()
                .and_then(|c| resolve(c, theme))
                .filter(|c| c & 0xFF_FFFF != 0xFF_FFFF);
            let size = font.size.filter(|s| Some(*s) != default.size);
            let key = (font.flags, color, fill, size.map(f32::to_bits), xf.align, xf.wrap);
            if let Some(&index) = seen.get(&key) {
                return index;
            }
            if palette.len() >= MAX_LOOKS {
                return 0;
            }
            let index = palette.len() as u16;
            palette.push(CellStyle { flags: font.flags, color, fill, size, align: xf.align, wrap: xf.wrap });
            seen.insert(key, index);
            index
        })
        .collect()
}

/// `"BC12"` → 54 (zero-based column of BC).
fn column_index(reference: &str) -> Option<usize> {
    let letters = reference.bytes().take_while(u8::is_ascii_alphabetic);
    let mut index = 0usize;
    let mut any = false;
    for b in letters {
        index = index.checked_mul(26)?.checked_add(usize::from(b.to_ascii_uppercase() - b'A' + 1))?;
        any = true;
    }
    any.then(|| index - 1)
}

/// Shows numbers as a person would type them: no binary-float noise. Numbers
/// beyond what that can show faithfully stay as written.
fn format_number(raw: &str) -> String {
    let raw = raw.trim();
    raw.parse().ok().and_then(general).unwrap_or_else(|| raw.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testing::{open_zip, texts};
    use crate::zip::testing::{zip, zip_with};

    #[test]
    fn columns_and_numbers() {
        assert_eq!(column_index("A1"), Some(0));
        assert_eq!(column_index("BC12"), Some(54));
        assert_eq!(column_index("12"), None);
        assert_eq!(format_number("0.30000000000000004"), "0.3");
        assert_eq!(format_number("42"), "42");
        assert_eq!(format_number("1E+3"), "1000");
        assert_eq!(format_number("1e300"), "1e300");
        assert_eq!(format_number("1234567890123456789"), "1234567890123456789");
        assert_eq!(format_number("1.5E-12"), "1.5E-12");
        assert_eq!(format_number("not a number"), "not a number");
    }

    #[test]
    fn extracts_sheets_in_order_with_shared_strings() {
        let rels = br#"<Relationships><Relationship Id="r1" Type="http://x/officeDocument" Target="xl/workbook.xml"/></Relationships>"#;
        let workbook = br#"<workbook xmlns:r="r"><sheets><sheet name="Sales" sheetId="1" r:id="rId1"/><sheet name="Secret" sheetId="2" state="hidden" r:id="rId2"/><sheet name="Notes" sheetId="3" r:id="rId3"/></sheets></workbook>"#;
        let wb_rels = br#"<Relationships><Relationship Id="rId1" Type="http://x/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://x/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="http://x/worksheet" Target="/xl/worksheets/sheet3.xml"/><Relationship Id="rId9" Type="http://x/sharedStrings" Target="sharedStrings.xml"/></Relationships>"#;
        let shared =
            br#"<sst><si><t>Region</t></si><si><r><t>Tot</t></r><r><t>al</t></r><rPh><t>x</t></rPh></si></sst>"#;
        let sheet1 = br#"<worksheet><sheetData>
<row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c></row>
<row r="2"><c r="A2" t="inlineStr"><is><t>North</t></is></c><c r="B2" t="b"><v>1</v></c><c r="C2"><f>1+2</f><v>3.0000000000000004</v></c></row>
<row r="7"><c r="A7"/></row>
<row r="9"><c r="B9" t="e"><v>#DIV/0!</v></c></row>
</sheetData></worksheet>"#;
        let sheet3 =
            br#"<worksheet><sheetData><row r="1"><c r="A1" t="str"><v>done</v></c></row></sheetData></worksheet>"#;
        let book = zip(&[
            ("_rels/.rels", rels),
            ("xl/workbook.xml", workbook),
            ("xl/_rels/workbook.xml.rels", wb_rels),
            ("xl/sharedStrings.xml", shared),
            ("xl/worksheets/sheet1.xml", sheet1),
            ("xl/worksheets/sheet2.xml", b"<worksheet/>"),
            ("xl/worksheets/sheet3.xml", sheet3),
        ]);
        let (format, blocks) = open_zip(book);
        assert_eq!(format, document_core::FormatId::Xlsx);
        assert_eq!(
            texts(&blocks),
            ["S Sales", "R1 Region||Total", "R2 North|TRUE|3", "R9 |#DIV/0!", "S Notes", "R1 done",]
        );
    }

    const RELS: &[u8] =
        br#"<Relationships><Relationship Id="r1" Type="http://x/officeDocument" Target="xl/workbook.xml"/></Relationships>"#;
    const WB_RELS: &[u8] = br#"<Relationships><Relationship Id="rId1" Type="http://x/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://x/styles" Target="styles.xml"/><Relationship Id="rId3" Type="http://x/sharedStrings" Target="sharedStrings.xml"/></Relationships>"#;
    const WORKBOOK: &str =
        r#"<workbook xmlns:r="r"><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>"#;
    const WORKBOOK_1904: &str = r#"<workbook xmlns:r="r"><workbookPr date1904="1"/><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>"#;
    const SHARED: &[u8] = br#"<sst><si><t>Widget</t></si></sst>"#;

    /// Style 0 General, 1 dates (id 14), 2 percent (id 9), 3 custom money with a
    /// child element, 4 custom unsupported, 5 text prefix, 6 unknown id.
    const STYLES: &str = r#"<styleSheet>
<numFmts count="3">
<numFmt numFmtId="164" formatCode="&quot;$&quot;#,##0.00;[Red]\(&quot;$&quot;#,##0.00\)"/>
<numFmt numFmtId="165" formatCode="[DBNum1]0"/>
<numFmt numFmtId="166" formatCode="&quot;SKU-&quot;@"/>
</numFmts>
<cellStyleXfs count="2"><xf numFmtId="10"/><xf numFmtId="11"/></cellStyleXfs>
<cellXfs count="7">
<xf numFmtId="0" fontId="0"/>
<xf numFmtId="14" applyNumberFormat="1"/>
<xf numFmtId="9"/>
<xf numFmtId="164"><alignment horizontal="right"/></xf>
<xf numFmtId="165"/>
<xf numFmtId="166"/>
<xf numFmtId="9999"/>
</cellXfs>
<dxfs count="1"><dxf><numFmt numFmtId="14" formatCode="0.000"/></dxf></dxfs>
</styleSheet>"#;

    fn book(workbook: &str, styles: Option<&[u8]>, sheet_data: &str) -> Vec<u8> {
        let sheet = format!("<worksheet><sheetData>{sheet_data}</sheetData></worksheet>");
        let mut parts: Vec<(&str, &[u8])> = vec![
            ("_rels/.rels", RELS),
            ("xl/workbook.xml", workbook.as_bytes()),
            ("xl/_rels/workbook.xml.rels", WB_RELS),
            ("xl/sharedStrings.xml", SHARED),
            ("xl/worksheets/sheet1.xml", sheet.as_bytes()),
        ];
        if let Some(styles) = styles {
            parts.push(("xl/styles.xml", styles));
        }
        zip(&parts)
    }

    /// The sheet's rows, without their looks.
    fn rows(workbook: &str, styles: Option<&[u8]>, sheet_data: &str) -> Vec<String> {
        let (_, blocks) = open_zip(book(workbook, styles, sheet_data));
        let rows: Vec<Block> = blocks
            .into_iter()
            .filter_map(|b| match b {
                Block::TableRow { row, header, cells, .. } => {
                    Some(Block::TableRow { row, header, cells, styles: Vec::new(), cell_spans: Vec::new() })
                }
                _ => None,
            })
            .collect();
        texts(&rows)
    }

    const LOOKS: &str = r#"<styleSheet>
<fonts count="5">
<font><sz val="11"/><color theme="1"/><name val="Calibri"/></font>
<font><b/><sz val="16"/><color rgb="FF1F4E79"/></font>
<font><i/><u val="none"/><strike/><sz val="11"/><color theme="4" tint="-0.5"/></font>
<font><b val="0"/><color indexed="10"/></font>
<font/>
</fonts>
<fills count="5">
<fill><patternFill patternType="none"/></fill>
<fill><patternFill patternType="gray125"/></fill>
<fill><patternFill patternType="solid"><fgColor theme="5" tint="0.7999816888943144"/><bgColor indexed="64"/></patternFill></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFFFFFFF"/></patternFill></fill>
<fill/>
</fills>
<cellStyleXfs count="1"><xf fontId="1" fillId="2"/></cellStyleXfs>
<cellXfs count="7">
<xf numFmtId="0" fontId="0" fillId="0"/>
<xf fontId="1" fillId="2"><alignment horizontal="center" wrapText="1"/></xf>
<xf fontId="2" fillId="0"/>
<xf fontId="3" fillId="3"><alignment horizontal="general"/></xf>
<xf fontId="1" fillId="2"><alignment horizontal="center" wrapText="1"/></xf>
<xf fontId="99" fillId="99"/>
<xf fontId="0" fillId="2"/>
</cellXfs>
<dxfs count="1"><dxf><font><b/></font><fill><patternFill><bgColor rgb="FFFF0000"/></patternFill></fill></dxf></dxfs>
</styleSheet>"#;

    const THEME: &[u8] = br#"<a:theme xmlns:a="a"><a:themeElements><a:clrScheme name="x"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="44546A"/></a:dk2><a:lt2><a:srgbClr val="E7E6E6"/></a:lt2><a:accent1><a:srgbClr val="4472C4"/></a:accent1><a:accent2><a:srgbClr val="ED7D31"/></a:accent2></a:clrScheme></a:themeElements></a:theme>"#;

    #[test]
    fn cells_carry_their_look_and_merges_follow_the_sheet() {
        let sheet = r#"<worksheet><cols><col min="1" max="3" width="20"/></cols><sheetData>
<row r="1"><c r="A1" t="inlineStr" s="1"><is><t>Title</t></is></c><c r="B1" s="1"/><c r="C1" s="1"/><c r="D1" s="3"/><c r="E1" s="5"/></row>
<row r="2"><c r="A2" s="2"><v>1</v></c><c r="B2" s="3"><v>2</v></c><c r="C2" s="4"><v>3</v></c><c r="D2"><v>4</v></c></row>
<row r="3"><c r="A3" s="6"/><c r="B3" s="3"/></row>
<row r="4"><c r="A4" s="2"/></row>
</sheetData><mergeCells count="4"><mergeCell ref="A1:C1"/><mergeCell ref="D3:B2"/><mergeCell ref="E5"/><mergeCell ref="bad"/></mergeCells></worksheet>"#;
        let wb_rels = r#"<Relationships><Relationship Id="rId1" Type="http://x/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://x/styles" Target="styles.xml"/><Relationship Id="rId4" Type="http://x/theme" Target="theme/theme1.xml"/></Relationships>"#;
        let book = zip(&[
            ("_rels/.rels", RELS),
            ("xl/workbook.xml", WORKBOOK.as_bytes()),
            ("xl/_rels/workbook.xml.rels", wb_rels.as_bytes()),
            ("xl/styles.xml", LOOKS.as_bytes()),
            ("xl/theme/theme1.xml", THEME),
            ("xl/worksheets/sheet1.xml", sheet.as_bytes()),
        ]);
        let (_, blocks) = open_zip(book);
        let title = CellStyle {
            flags: BOLD,
            color: Some(0xFF1F_4E79),
            // accent2 (theme 5) lightened by 80%.
            fill: Some(0xFFFB_E5D6),
            size: Some(16.0),
            align: 2,
            wrap: true,
        };
        let quiet = CellStyle {
            flags: ITALIC | STRIKE,
            // accent1 (theme 4) darkened by half.
            color: Some(0xFF20_3864),
            ..CellStyle::default()
        };
        let red = CellStyle { color: Some(0xFFFF_0000), ..CellStyle::default() };
        let tinted = CellStyle { fill: Some(0xFFFB_E5D6), ..CellStyle::default() };
        assert_eq!(
            blocks[..4],
            [
                Block::CellStyle { index: 1, style: title },
                Block::CellStyle { index: 2, style: quiet },
                Block::CellStyle { index: 3, style: red },
                Block::CellStyle { index: 4, style: tinted },
            ]
        );
        assert_eq!(
            texts(&blocks[4..]),
            [
                "S S",
                // The white fill and the unknown style don't count; the coloured empty cells do.
                "R1 Title [1, 1, 1]",
                "R2 1|2|3|4 [2, 3, 1]",
                "R3  [4]",
                "MERGE 1,0:1,2",
                "MERGE 2,1:3,3",
            ]
        );
    }

    #[test]
    fn rich_shared_text_keeps_its_runs() {
        let wb_rels = r#"<Relationships><Relationship Id="rId1" Type="http://x/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId3" Type="http://x/sharedStrings" Target="sharedStrings.xml"/><Relationship Id="rId4" Type="http://x/theme" Target="theme/theme1.xml"/></Relationships>"#;
        let shared = r#"<sst><si><t>Plain</t></si><si><r><t xml:space="preserve">Quarterly </t></r><r><rPr><b/><color rgb="FFC00000"/><sz val="11"/></rPr><t>review</t></r><r><rPr><i val="0"/><color theme="4"/></rPr><t xml:space="preserve"> due</t></r><rPh><t>x</t></rPh></si></sst>"#;
        let sheet = r#"<worksheet><sheetData><row r="2"><c r="A2" t="s"><v>0</v></c><c r="B2" t="s"><v>1</v></c><c r="C2" t="inlineStr"><is><r><t xml:space="preserve">Is </t></r><r><rPr><b val="1"/><color rgb="00C00000"/></rPr><t>late</t></r></is></c></row></sheetData></worksheet>"#;
        let book = zip(&[
            ("_rels/.rels", RELS),
            ("xl/workbook.xml", WORKBOOK.as_bytes()),
            ("xl/_rels/workbook.xml.rels", wb_rels.as_bytes()),
            ("xl/sharedStrings.xml", shared.as_bytes()),
            ("xl/theme/theme1.xml", THEME),
            ("xl/worksheets/sheet1.xml", sheet.as_bytes()),
        ]);
        let (_, blocks) = open_zip(book);
        // Theme slot 4 is accent1, 4472C4.
        let accent1 = 0xFF44_72C4u32;
        assert_eq!(
            texts(&blocks),
            [
                "S S".to_owned(),
                "R2 Plain|Quarterly review due|Is late".to_owned(),
                format!("RUNS 2,1 [(10, 16, {BOLD}, Some({})), (16, 20, 0, Some({accent1}))]", 0xFFC0_0000u32),
                // Inline strings keep their runs too; the file's 00 alpha still means opaque.
                format!("RUNS 2,2 [(3, 7, {BOLD}, Some({}))]", 0xFFC0_0000u32),
            ]
        );
    }

    #[test]
    fn a_broken_theme_leaves_theme_colours_out() {
        let wb_rels = r#"<Relationships><Relationship Id="rId1" Type="http://x/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://x/styles" Target="styles.xml"/><Relationship Id="rId4" Type="http://x/theme" Target="theme/theme1.xml"/></Relationships>"#;
        let sheet = r#"<worksheet><sheetData><row r="1"><c r="A1" s="6"><v>1</v></c><c r="B1" s="3"><v>2</v></c></row></sheetData></worksheet>"#;
        let book = zip(&[
            ("_rels/.rels", RELS),
            ("xl/workbook.xml", WORKBOOK.as_bytes()),
            ("xl/_rels/workbook.xml.rels", wb_rels.as_bytes()),
            ("xl/styles.xml", LOOKS.as_bytes()),
            ("xl/theme/theme1.xml", b"<a:theme><unclosed"),
            ("xl/worksheets/sheet1.xml", sheet.as_bytes()),
        ]);
        let (_, blocks) = open_zip(book);
        assert_eq!(texts(&blocks).last().unwrap(), "R1 1|2 [0, 3]");
    }

    const STYLED_ROW: &str = r#"<row r="1">
<c r="A1"><v>45231.5</v></c>
<c r="B1" s="1"><v>45231</v></c>
<c r="C1" s="2"><v>0.25</v></c>
<c r="D1" s="3"><v>-1234.5</v></c>
<c r="E1" s="4"><v>7.5</v></c>
<c r="F1" s="6"><v>8.5</v></c>
<c r="G1" s="99"><v>9.5</v></c>
<c r="H1" s="x"><v>10.5</v></c>
</row>"#;

    #[test]
    fn applies_cell_number_formats() {
        // Unknown ids, unsupported codes and bad style indexes keep plain values.
        assert_eq!(
            rows(WORKBOOK, Some(STYLES.as_bytes()), STYLED_ROW),
            ["R1 45231.5|11/1/2023|25%|($1,234.50)|7.5|8.5|9.5|10.5"]
        );
    }

    #[test]
    fn non_numeric_cells_ignore_number_formats() {
        let row = r#"<row r="1">
<c r="A1" t="b" s="2"><v>1</v></c>
<c r="B1" t="b" s="1"><v>0</v></c>
<c r="C1" t="e" s="2"><v>#N/A</v></c>
<c r="D1" t="inlineStr" s="2"><is><t>in</t><rPh><t>x</t></rPh></is></c>
<c r="E1" t="str" s="1"><f>A1</f><v>calc</v></c>
<c r="F1" t="s" s="1"><v>0</v></c>
<c r="G1" t="d" s="1"><v>2023-11-01T00:00:00</v></c>
<c r="H1" s="1"><v>not a number</v></c>
<c r="I1" s="1"><v></v></c>
</row>"#;
        assert_eq!(
            rows(WORKBOOK, Some(STYLES.as_bytes()), row),
            ["R1 TRUE|FALSE|#N/A|in|calc|Widget|2023-11-01T00:00:00|not a number"]
        );
    }

    #[test]
    fn text_sections_apply_to_string_cells() {
        let row = r#"<row r="1"><c r="A1" t="s" s="5"><v>0</v></c><c r="B1" t="inlineStr" s="5"><is><t>i</t></is></c><c r="C1" t="str" s="5"><v>c</v></c><c r="D1" s="5"><v>1.5</v></c></row>"#;
        assert_eq!(rows(WORKBOOK, Some(STYLES.as_bytes()), row), ["R1 SKU-Widget|SKU-i|SKU-c|SKU-1.5"]);
    }

    #[test]
    fn formulas_show_their_cached_value_or_else_the_formula() {
        let row = r#"<row r="1"><c r="A1"><f>SUM(B1:C1)</f><v>3</v></c><c r="B1"><f>SUM(B5:B12)</f></c><c r="C1"><f t="shared" si="0"/></c><c r="D1" t="str"><f>"a"&amp;"b"</f><v>ab</v></c></row>"#;
        assert_eq!(rows(WORKBOOK, None, row), ["R1 3|=SUM(B5:B12)||ab"]);
    }

    #[test]
    fn honours_the_1904_date_system() {
        let row = r#"<row r="1"><c r="A1" s="1"><v>45231</v></c></row>"#;
        assert_eq!(rows(WORKBOOK_1904, Some(STYLES.as_bytes()), row), ["R1 11/2/2027"]);
    }

    #[test]
    fn huge_and_negative_dates_keep_the_raw_value() {
        let row = r#"<row r="1"><c r="A1" s="1"><v>1e300</v></c><c r="B1" s="1"><v>-5</v></c><c r="C1" s="1"><v>1e999</v></c><c r="D1" s="1"><v>NaN</v></c></row>"#;
        assert_eq!(rows(WORKBOOK, Some(STYLES.as_bytes()), row), ["R1 1e300|-5|1e999|NaN"]);
    }

    #[test]
    fn missing_or_malformed_styles_show_plain_values() {
        let row = r#"<row r="1"><c r="A1" s="1"><v>45231</v></c><c r="B1" s="2"><v>0.25</v></c></row>"#;
        let plain = ["R1 45231|0.25"];
        assert_eq!(rows(WORKBOOK, None, row), plain);
        for broken in [
            &b""[..],
            b"not xml at all",
            b"<styleSheet",
            b"<styleSheet><cellXfs><xf numFmtId=\"14\"/></cellXfs><<<>>",
            b"<other/>",
            b"<styleSheet><cellXfs><xf/><xf numFmtId=\"oops\"/><xf numFmtId=\"-1\"/></cellXfs></styleSheet>",
            b"<styleSheet><cellXfs><xf/><xf numFmtId=\"99999999999\"/><xf/></cellXfs></styleSheet>",
        ] {
            assert_eq!(rows(WORKBOOK, Some(broken), row), plain, "{}", String::from_utf8_lossy(broken));
        }
    }

    #[test]
    fn corrupt_styles_part_does_not_fail_the_document() {
        let row = r#"<row r="1"><c r="A1" s="1"><v>45231</v></c></row>"#;
        let sheet = format!("<worksheet><sheetData>{row}</sheetData></worksheet>");
        let mut bytes = zip_with(
            &[
                ("_rels/.rels", RELS),
                ("xl/workbook.xml", WORKBOOK.as_bytes()),
                ("xl/_rels/workbook.xml.rels", WB_RELS),
                ("xl/worksheets/sheet1.xml", sheet.as_bytes()),
                ("xl/styles.xml", STYLES.as_bytes()),
            ],
            false,
        );
        // Stored, so this edit makes the part's checksum stop matching its content.
        let at = bytes.windows(10).position(|w| w == b"<styleShee".as_slice()).unwrap();
        bytes[at + 1] = b'x';
        let (_, blocks) = open_zip(bytes);
        assert_eq!(texts(&blocks), ["S S", "R1 45231"]);
    }

    #[test]
    fn hostile_styles_stay_bounded() {
        // Far more formats and codes than the caps, and an oversized code.
        let mut styles = String::from("<styleSheet><numFmts>");
        let long = "0".repeat(100_000);
        styles.push_str(&format!(r#"<numFmt numFmtId="164" formatCode="{long}"/>"#));
        for id in 165..20_000 {
            styles.push_str(&format!(r#"<numFmt numFmtId="{id}" formatCode="0.0"/>"#));
        }
        styles.push_str("</numFmts><cellXfs><xf numFmtId=\"164\"/><xf numFmtId=\"165\"/>");
        for _ in 0..MAX_CELL_FORMATS + 10 {
            styles.push_str("<xf numFmtId=\"2\"/>");
        }
        styles.push_str("</cellXfs></styleSheet>");
        let row = format!(
            r#"<row r="1"><c r="A1"><v>1.25</v></c><c r="B1" s="1"><v>1.25</v></c><c r="C1" s="2"><v>1.25</v></c><c r="D1" s="{}"><v>1.25</v></c></row>"#,
            MAX_CELL_FORMATS + 5
        );
        assert_eq!(rows(WORKBOOK, Some(styles.as_bytes()), &row), ["R1 1.25|1.3|1.25|1.25"]);
    }
}
