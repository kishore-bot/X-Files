//! XLSX cell editing: writes text and number values into cells of a sheet,
//! and reads the pane freeze and a cell's raw value for the edit dialog.
//!
//! Only the sheet part, the shared-string table and the package index change.
//! Styles, merges, drawings and every other part are copied byte for byte.
//!
//! Design (LLD):
//! - The sheet XML is split into `head` (everything before the rows), a
//!   `BTreeMap<row, RowXml>` of rows, each holding a `BTreeMap<column, cell xml>`
//!   and `tail` (from `</sheetData>`). Untouched cell XML is copied verbatim, so
//!   cell attributes such as `s` (style) survive. Rendering walks the maps in
//!   order, so output rows and cells are always sorted.
//! - Text goes to the shared-string table through a `HashMap` index of plain
//!   entries (text to index). A known string reuses its index; a new one is
//!   appended. Without a shared-string part, text is written as an inline string.
//! - Formula cells (`<f>`, shared or array) are refused. Cached values are only
//!   ever dropped from cells that are rewritten, and rewritten cells never carry
//!   a formula.
//! - Writes go through `ooxml::rewrite_package`, which streams each kept part.

use std::collections::{BTreeMap, HashMap};

use document_core::{CancelToken, EngineError, Limits};

use crate::odf;
use crate::ooxml::{PackageEdit, relationships, rewrite_package};
use crate::zip::ZipArchive;

/// Highest 0-based column (XFD) and row accepted by the editor.
pub const MAX_COLUMN: u32 = 16_383;
pub const MAX_ROW: u32 = 1_048_575;
/// Excel's own limit for a cell's text.
pub const MAX_TEXT_CHARS: usize = 32_767;

const WORKBOOK: &str = "xl/workbook.xml";

#[derive(Debug, Clone, PartialEq)]
pub enum CellValue {
    Text(String),
    Number(f64),
}

/// One cell write. `row` and `column` are 0-based, as the reader uses them.
#[derive(Debug, Clone, PartialEq)]
pub struct CellEdit {
    pub sheet: String,
    pub row: u32,
    pub column: u32,
    pub value: CellValue,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CellKind {
    Empty,
    Text,
    Number,
    Bool,
    Error,
    Formula,
}

/// A cell's stored value. For formulas `raw` is the formula text (read-only).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CellSnapshot {
    pub kind: CellKind,
    pub raw: String,
}

/// Frozen rows and columns from the sheet's pane. Zero means not frozen.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Freeze {
    pub rows: u32,
    pub columns: u32,
}

/// Frozen panes of a sheet, read from `sheetView/pane`.
pub fn read_freeze(data: &[u8], sheet: &str) -> Result<Freeze, EngineError> {
    let mut archive = open(data)?;
    if is_ods(&archive) {
        return Ok(read_ods_freeze(&mut archive, sheet));
    }
    let part = sheet_part(&mut archive, sheet)?;
    let xml = read_text(&mut archive, &part)?;
    let head =&xml[..xml.find("<sheetData").unwrap_or(xml.len())];
    let Some(pos) = head.find("<pane") else { return Ok(Freeze::default()) };
    let tag = &head[pos..head[pos..].find('>').map_or(head.len(), |e| pos + e + 1)];
    if !matches!(attr(tag, "state"), Some("frozen" | "frozenSplit")) {
        return Ok(Freeze::default());
    }
    let split = |name: &str| attr(tag, name).and_then(|v| v.parse::<f64>().ok()).map_or(0, |v| v.max(0.0) as u32);
    Ok(Freeze { rows: split("ySplit"), columns: split("xSplit") })
}

/// Stored value of one cell (0-based coordinates), for the edit dialog.
pub fn read_cell(data: &[u8], sheet: &str, row: u32, column: u32) -> Result<CellSnapshot, EngineError> {
    let mut archive = open(data)?;
    if is_ods(&archive) {
        let content = read_text(&mut archive, ODS_CONTENT)?;
        return read_ods_cell(&content, sheet, row, column);
    }
    let part = sheet_part(&mut archive, sheet)?;
    let xml = read_text(&mut archive, &part)?;
    let sheet_xml = SheetXml::parse(&xml)?;
    let shared = match shared_strings_part(&mut archive)? {
        Some(path) => Some(SharedStrings::parse(&read_text(&mut archive, &path)?)),
        None => None,
    };
    let Some(cell) = sheet_xml.rows.get(&(row + 1)).and_then(|r| r.cells.get(&column)) else {
        return Ok(CellSnapshot { kind: CellKind::Empty, raw: String::new() });
    };
    if is_formula(cell) {
        return Ok(CellSnapshot { kind: CellKind::Formula, raw: unescape(formula_text(cell)) });
    }
    let value = inner_value(cell).unwrap_or("");
    let snapshot = match attr(open_tag(cell), "t") {
        Some("s") => {
            let text = value.trim().parse::<usize>().ok().and_then(|i| shared.as_ref()?.get(i)).unwrap_or_default();
            CellSnapshot { kind: CellKind::Text, raw: text }
        }
        Some("inlineStr") => CellSnapshot { kind: CellKind::Text, raw: inline_text(cell) },
        Some("str") => CellSnapshot { kind: CellKind::Text, raw: unescape(value) },
        Some("b") => CellSnapshot { kind: CellKind::Bool, raw: value.to_owned() },
        Some("e") => CellSnapshot { kind: CellKind::Error, raw: unescape(value) },
        _ if value.is_empty() => CellSnapshot { kind: CellKind::Empty, raw: String::new() },
        _ => CellSnapshot { kind: CellKind::Number, raw: value.to_owned() },
    };
    Ok(snapshot)
}

/// Applies cell writes and returns the new package. Rejects formula cells and
/// unknown sheets; the whole batch fails before anything is written.
pub fn apply_cell_edits(data: &[u8], edits: &[CellEdit]) -> Result<Vec<u8>, EngineError> {
    if edits.is_empty() {
        return Err(EngineError::malformed("no cell changes to save"));
    }
    for edit in edits {
        validate(edit)?;
    }
    if is_ods(&open(data)?) {
        return apply_ods(data, edits);
    }
    let mut archive = open(data)?;
    let mut by_sheet: BTreeMap<&str, Vec<&CellEdit>> = BTreeMap::new();
    for edit in edits {
        by_sheet.entry(edit.sheet.as_str()).or_default().push(edit);
    }
    let shared_path = shared_strings_part(&mut archive)?;
    let mut shared = match &shared_path {
        Some(path) => Some(SharedStrings::parse(&read_text(&mut archive, path)?)),
        None => None,
    };

    let mut package = PackageEdit::default();
    for (name, sheet_edits) in &by_sheet {
        let part = sheet_part(&mut archive, name)?;
        let xml = read_text(&mut archive, &part)?;
        let rendered = apply_sheet(&xml, sheet_edits, &mut shared)?;
        package.replace.insert(part, rendered.into_bytes());
    }
    if let (Some(path), Some(table)) = (shared_path, shared) {
        package.replace.insert(path, table.finish().into_bytes());
    }
    drop(archive);
    rewrite_package(data, &package)
}

fn validate(edit: &CellEdit) -> Result<(), EngineError> {
    if edit.row > MAX_ROW || edit.column > MAX_COLUMN {
        return Err(EngineError::malformed("cell outside the sheet grid"));
    }
    match &edit.value {
        CellValue::Text(text) if text.chars().count() > MAX_TEXT_CHARS => {
            Err(EngineError::malformed("cell text is longer than 32767 characters"))
        }
        CellValue::Number(n) if !n.is_finite() => Err(EngineError::malformed("numbers must be finite")),
        _ => Ok(()),
    }
}

fn apply_sheet(xml: &str, edits: &[&CellEdit], shared: &mut Option<SharedStrings>) -> Result<String, EngineError> {
    let mut sheet = SheetXml::parse(xml)?;
    for edit in edits {
        let row1 = edit.row + 1;
        let column = edit.column;
        let blank = matches!(&edit.value, CellValue::Text(t) if t.is_empty());
        let existing = sheet.rows.get(&row1).and_then(|r| r.cells.get(&column));
        if existing.is_none() && blank {
            continue;
        }
        if existing.is_some_and(|cell| is_formula(cell)) {
            return Err(EngineError::malformed("formula cells cannot be edited"));
        }
        let style = existing.and_then(|cell| attr(open_tag(cell), "s")).map(str::to_owned);
        let cell_xml = encode_cell(row1, column, style.as_deref(), &edit.value, shared);
        let row = sheet.rows.entry(row1).or_insert_with(|| RowXml {
            open: format!(r#"<row r="{row1}">"#),
            cells: BTreeMap::new(),
        });
        // The spans hint would be stale once a cell is added.
        if has_attr(&row.open, "spans") {
            row.open = remove_attr(&row.open, "spans");
        }
        if row.open.ends_with("/>") {
            row.open.truncate(row.open.len() - 2);
            row.open.push('>');
        }
        row.cells.insert(column, cell_xml);
        sheet.expand_dimension(row1, column);
    }
    Ok(sheet.render())
}

fn encode_cell(row1: u32, column: u32, style: Option<&str>, value: &CellValue, shared: &mut Option<SharedStrings>) -> String {
    let reference = format!("{}{row1}", column_letters(column));
    let style_attr = style.map(|s| format!(r#" s="{}""#, escape(s))).unwrap_or_default();
    match value {
        CellValue::Number(n) => format!(r#"<c r="{reference}"{style_attr}><v>{n}</v></c>"#),
        CellValue::Text(text) if text.is_empty() => format!(r#"<c r="{reference}"{style_attr}/>"#),
        CellValue::Text(text) => match shared {
            Some(table) => {
                let index = table.index_for(text);
                format!(r#"<c r="{reference}"{style_attr} t="s"><v>{index}</v></c>"#)
            }
            None => format!(
                r#"<c r="{reference}"{style_attr} t="inlineStr"><is><t xml:space="preserve">{}</t></is></c>"#,
                escape(text)
            ),
        },
    }
}

/// The sheet part split into head, rows and tail. Rows and cells are kept in
/// BTreeMaps so output order is always sorted.
struct SheetXml {
    head: String,
    rows: BTreeMap<u32, RowXml>,
    tail: String,
}

struct RowXml {
    /// Start tag as written, e.g. `<row r="3" spans="1:4">`.
    open: String,
    /// Column index to the cell's XML, copied verbatim.
    cells: BTreeMap<u32, String>,
}

impl SheetXml {
    fn parse(xml: &str) -> Result<Self, EngineError> {
        let Some(start) = xml.find("<sheetData") else {
            return Err(EngineError::malformed("sheet has no sheetData"));
        };
        let gt = start + xml[start..].find('>').ok_or_else(|| EngineError::malformed("broken sheetData"))?;
        if xml.as_bytes()[gt - 1] == b'/' {
            let head = format!("{}>", &xml[..gt - 1]);
            return Ok(Self { head, rows: BTreeMap::new(), tail: format!("</sheetData>{}", &xml[gt + 1..]) });
        }
        let body_start = gt + 1;
        let close = body_start
            + xml[body_start..].find("</sheetData>").ok_or_else(|| EngineError::malformed("sheetData not closed"))?;
        Ok(Self {
            head: xml[..body_start].to_owned(),
            rows: parse_rows(&xml[body_start..close])?,
            tail: xml[close..].to_owned(),
        })
    }

    /// Grows the `<dimension ref>` so it covers an edited cell.
    fn expand_dimension(&mut self, row1: u32, column: u32) {
        let Some(pos) = self.head.find("<dimension") else { return };
        let Some(end) = self.head[pos..].find('>').map(|e| pos + e) else { return };
        let tag = self.head[pos..end].to_owned();
        let Some(old) = attr(&tag, "ref") else { return };
        let (first, last) = old.split_once(':').unwrap_or((old, old));
        let (Some((r0, c0)), Some((r1, c1))) = (parse_reference(first), parse_reference(last)) else { return };
        let (nr0, nc0) = (r0.min(row1), c0.min(column));
        let (nr1, nc1) = (r1.max(row1), c1.max(column));
        if (nr0, nc0, nr1, nc1) == (r0, c0, r1, c1) {
            return;
        }
        let new_ref = format!(
            "{}{}:{}{}",
            column_letters(nc0),
            nr0,
            column_letters(nc1),
            nr1
        );
        let new_tag = tag.replacen(&format!(r#"ref="{old}""#), &format!(r#"ref="{new_ref}""#), 1);
        self.head.replace_range(pos..end, &new_tag);
    }

    fn render(&self) -> String {
        let mut out = String::with_capacity(self.head.len() + self.tail.len() + 64 * self.rows.len());
        out.push_str(&self.head);
        for row in self.rows.values() {
            if row.cells.is_empty() && row.open.ends_with("/>") {
                out.push_str(&row.open);
                continue;
            }
            out.push_str(&row.open);
            for cell in row.cells.values() {
                out.push_str(cell);
            }
            out.push_str("</row>");
        }
        out.push_str(&self.tail);
        out
    }
}

fn parse_rows(body: &str) -> Result<BTreeMap<u32, RowXml>, EngineError> {
    let mut rows = BTreeMap::new();
    let mut pos = 0;
    let mut last_row = 0u32;
    while let Some(span) = next_element(body, pos, "row") {
        let open = &body[span.start..span.open_end];
        let row1 = attr(open, "r").and_then(|v| v.parse::<u32>().ok()).unwrap_or(last_row + 1);
        last_row = row1;
        let mut cells = BTreeMap::new();
        if !span.self_closing {
            let inner = &body[span.open_end..span.end - "</row>".len()];
            let mut cell_pos = 0;
            let mut last_col: Option<u32> = None;
            while let Some(cell) = next_element(inner, cell_pos, "c") {
                let cell_open = &inner[cell.start..cell.open_end];
                let column = match attr(cell_open, "r").and_then(parse_reference) {
                    Some((_, column)) => column,
                    None => last_col.map_or(0, |c| c + 1),
                };
                last_col = Some(column);
                cells.insert(column, inner[cell.start..cell.end].to_owned());
                cell_pos = cell.end;
            }
        }
        rows.insert(row1, RowXml { open: open.to_owned(), cells });
        pos = span.end;
    }
    Ok(rows)
}

/// Plain-text entries of the shared-string table with a text to index map.
struct SharedStrings {
    xml: String,
    /// Total `<si>` entries, including rich-text ones (they keep their index).
    total: usize,
    /// Plain text to index, for reuse.
    index: HashMap<String, usize>,
    /// Escaped `<si>` fragments appended by this save.
    appended: Vec<String>,
    /// Cell references that point at new or reused entries in this save.
    refs_added: usize,
}

impl SharedStrings {
    fn parse(xml: &str) -> Self {
        let mut index = HashMap::new();
        let mut total = 0usize;
        let mut pos = 0;
        while let Some(span) = next_element(xml, pos, "si") {
            if !span.self_closing {
                let body = &xml[span.open_end..span.end - "</si>".len()];
                if !body.contains("<r>") && !body.contains("<r ") {
                    if let Some(text) = next_element(body, 0, "t").filter(|t| !t.self_closing) {
                        let raw = &body[text.open_end..text.end - "</t>".len()];
                        index.entry(unescape(raw)).or_insert(total);
                    }
                }
            }
            total += 1;
            pos = span.end;
        }
        Self { xml: xml.to_owned(), total, index, appended: Vec::new(), refs_added: 0 }
    }

    fn get(&self, index: usize) -> Option<String> {
        let mut seen = 0usize;
        let mut pos = 0;
        while let Some(span) = next_element(&self.xml, pos, "si") {
            if seen == index {
                let body = &self.xml[span.open_end..span.end.saturating_sub("</si>".len())];
                return Some(plain_text(body));
            }
            seen += 1;
            pos = span.end;
        }
        None
    }

    fn index_for(&mut self, text: &str) -> usize {
        self.refs_added += 1;
        if let Some(&existing) = self.index.get(text) {
            return existing;
        }
        let index = self.total;
        self.total += 1;
        self.index.insert(text.to_owned(), index);
        self.appended.push(format!(r#"<si><t xml:space="preserve">{}</t></si>"#, escape(text)));
        index
    }

    fn finish(self) -> String {
        let SharedStrings { xml, total, appended, refs_added, .. } = self;
        let mut xml = match xml.rfind("</sst>") {
            Some(pos) => {
                let mut out = String::with_capacity(xml.len() + appended.iter().map(String::len).sum::<usize>());
                out.push_str(&xml[..pos]);
                for si in &appended {
                    out.push_str(si);
                }
                out.push_str(&xml[pos..]);
                out
            }
            None => xml,
        };
        if let Some(pos) = xml.find("<sst") {
            if let Some(end) = xml[pos..].find('>').map(|e| pos + e) {
                let tag = xml[pos..end].to_owned();
                let mut new_tag = tag.clone();
                if let Some(old) = attr(&tag, "uniqueCount") {
                    new_tag = new_tag.replacen(&format!(r#"uniqueCount="{old}""#), &format!(r#"uniqueCount="{total}""#), 1);
                }
                if let Some(old) = attr(&tag, "count").and_then(|v| v.parse::<usize>().ok()) {
                    new_tag = new_tag.replacen(
                        &format!(r#"count="{old}""#),
                        &format!(r#"count="{}""#, old + refs_added),
                        1,
                    );
                }
                xml.replace_range(pos..end, &new_tag);
            }
        }
        xml
    }
}

fn open(data: &[u8]) -> Result<ZipArchive, EngineError> {
    ZipArchive::open(
        Box::new(std::io::Cursor::new(data.to_vec())),
        data.len() as u64,
        Limits::default(),
        CancelToken::default(),
    )
}

fn read_text(archive: &mut ZipArchive, name: &str) -> Result<String, EngineError> {
    let bytes = archive.read_to_vec(name).map_err(|_| EngineError::malformed("missing workbook part"))?;
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

fn shared_strings_part(archive: &mut ZipArchive) -> Result<Option<String>, EngineError> {
    Ok(relationships(archive, WORKBOOK)?
        .into_iter()
        .find(|r| !r.external && r.kind.ends_with("/sharedStrings"))
        .map(|r| r.target))
}

/// Archive path of the worksheet part with the given tab name.
fn sheet_part(archive: &mut ZipArchive, name: &str) -> Result<String, EngineError> {
    let workbook = read_text(archive, WORKBOOK)?;
    let mut pos = 0;
    let mut rid = None;
    while let Some(span) = next_element(&workbook, pos, "sheet") {
        let tag = &workbook[span.start..span.open_end];
        if attr(tag, "name").map(unescape).as_deref() == Some(name) {
            rid = attr(tag, "r:id").map(str::to_owned);
            break;
        }
        pos = span.end;
    }
    let rid = rid.ok_or_else(|| EngineError::malformed("sheet not found"))?;
    relationships(archive, WORKBOOK)?
        .into_iter()
        .find(|r| r.id == rid && !r.external)
        .map(|r| r.target)
        .ok_or_else(|| EngineError::malformed("sheet part missing"))
}

struct Span {
    start: usize,
    open_end: usize,
    end: usize,
    self_closing: bool,
}

/// Finds the next `<name ...>...</name>` or `<name .../>` at or after `from`.
fn next_element(xml: &str, from: usize, name: &str) -> Option<Span> {
    let open = format!("<{name}");
    let close = format!("</{name}>");
    let bytes = xml.as_bytes();
    let mut pos = from;
    loop {
        let start = pos + xml.get(pos..)?.find(&open)?;
        let after = start + open.len();
        if !matches!(bytes.get(after), Some(b' ' | b'>' | b'/' | b'\t' | b'\r' | b'\n')) {
            pos = after;
            continue;
        }
        let gt = start + xml[start..].find('>')?;
        if bytes[gt - 1] == b'/' {
            return Some(Span { start, open_end: gt + 1, end: gt + 1, self_closing: true });
        }
        let close_at = gt + 1 + xml[gt + 1..].find(&close)?;
        return Some(Span { start, open_end: gt + 1, end: close_at + close.len(), self_closing: false });
    }
}

fn is_formula(cell: &str) -> bool {
    let body = inner(cell).unwrap_or("");
    body.contains("<f>") || body.contains("<f ") || body.contains("<f/>")
}

/// Start tag of an element (up to and including its `>`).
fn open_tag(element: &str) -> &str {
    match element.find('>') {
        Some(end) => &element[..=end],
        None => element,
    }
}

/// Text between a start tag and its end tag, or the whole text of a self-closed element.
fn inner(element: &str) -> Option<&str> {
    let gt = element.find('>')?;
    if element.as_bytes().get(gt.checked_sub(1)?) == Some(&b'/') {
        return Some("");
    }
    let close = element.rfind("</")?;
    Some(&element[gt + 1..close])
}

/// Text of the cell's `<f>` element (empty for shared-formula followers).
fn formula_text(cell: &str) -> &str {
    let Some(pos) = cell.find("<f") else { return "" };
    let rest = &cell[pos..];
    let Some(gt) = rest.find('>') else { return "" };
    if rest.as_bytes()[gt - 1] == b'/' {
        return "";
    }
    let body = &rest[gt + 1..];
    match body.find("</f>") {
        Some(end) => &body[..end],
        None => "",
    }
}

fn inner_value(cell: &str) -> Option<&str> {
    let body = inner(cell)?;
    let start = body.find("<v>")? + 3;
    let end = body[start..].find("</v>")? + start;
    Some(&body[start..end])
}

fn inline_text(cell: &str) -> String {
    let body = inner(cell).unwrap_or("");
    plain_text(body)
}

/// Concatenated `<t>` text of a `<si>` or `<is>` body, unescaped.
fn plain_text(body: &str) -> String {
    let mut out = String::new();
    let mut pos = 0;
    while let Some(span) = next_element(body, pos, "t") {
        if !span.self_closing {
            out.push_str(&unescape(&body[span.open_end..span.end - "</t>".len()]));
        }
        pos = span.end;
    }
    out
}

fn has_attr(tag: &str, name: &str) -> bool {
    attr(tag, name).is_some()
}

fn remove_attr(tag: &str, name: &str) -> String {
    let needle = format!(" {name}=\"");
    match tag.find(&needle) {
        Some(start) => match tag[start + needle.len()..].find('"') {
            Some(end) => format!("{}{}", &tag[..start], &tag[start + needle.len() + end + 1..]),
            None => tag.to_owned(),
        },
        None => tag.to_owned(),
    }
}

/// Value of attribute `name` in a start tag, still escaped.
fn attr<'a>(tag: &'a str, name: &str) -> Option<&'a str> {
    let needle = format!(" {name}=\"");
    let start = tag.find(&needle)? + needle.len();
    let end = tag[start..].find('"')? + start;
    Some(&tag[start..end])
}

/// Column letters to 0-based index, plus row number (1-based), e.g. `B3` to (3, 1).
fn parse_reference(reference: &str) -> Option<(u32, u32)> {
    let split = reference.find(|c: char| c.is_ascii_digit())?;
    let (letters, digits) = reference.split_at(split);
    if letters.is_empty() || !letters.bytes().all(|b| b.is_ascii_uppercase()) {
        return None;
    }
    let mut column: u32 = 0;
    for b in letters.bytes() {
        column = column.checked_mul(26)?.checked_add(u32::from(b - b'A' + 1))?;
    }
    let row: u32 = digits.parse().ok()?;
    Some((row, column.checked_sub(1)?))
}

fn column_letters(column: u32) -> String {
    let mut n = column + 1;
    let mut letters = Vec::new();
    while n > 0 {
        let rem = (n - 1) % 26;
        letters.push(b'A' + rem as u8);
        n = (n - 1) / 26;
    }
    letters.reverse();
    String::from_utf8(letters).unwrap_or_default()
}

fn escape(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&apos;"),
            _ => out.push(c),
        }
    }
    out
}

fn unescape(text: &str) -> String {
    if !text.contains('&') {
        return text.to_owned();
    }
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(amp) = rest.find('&') {
        out.push_str(&rest[..amp]);
        rest = &rest[amp..];
        let Some(semi) = rest.find(';').filter(|&s| s <= 12) else {
            out.push('&');
            rest = &rest[1..];
            continue;
        };
        let entity = &rest[1..semi];
        let decoded = match entity {
            "amp" => Some('&'),
            "lt" => Some('<'),
            "gt" => Some('>'),
            "quot" => Some('"'),
            "apos" => Some('\''),
            _ => entity
                .strip_prefix("#x")
                .and_then(|h| u32::from_str_radix(h, 16).ok())
                .or_else(|| entity.strip_prefix('#').and_then(|d| d.parse::<u32>().ok()))
                .and_then(char::from_u32),
        };
        match decoded {
            Some(c) => out.push(c),
            None => out.push_str(&rest[..=semi]),
        }
        rest = &rest[semi + 1..];
    }
    out.push_str(rest);
    out
}

// ---------------------------------------------------------------------------
// OpenDocument spreadsheets (ODS): cells in `content.xml`.
//
// Same contract as XLSX: string and float cells are written, formula cells
// are refused, other parts are copied as they are. A repeated row or cell
// (`number-rows-repeated`, `number-columns-repeated`) is split only where the
// edited position falls, so a 1048576-row sheet stays cheap. Sheets are found
// by `table:name`; the body is assumed not to nest tables.
// ---------------------------------------------------------------------------

const ODS_CONTENT: &str = "content.xml";
const ODS_SETTINGS: &str = "settings.xml";

/// Frozen panes of an ODS sheet, from the first view in `settings.xml` (see
/// [`odf::ods_frozen_panes`]). Missing or unreadable settings mean no frozen panes;
/// view settings never fail the read.
fn read_ods_freeze(archive: &mut ZipArchive, sheet: &str) -> Freeze {
    if archive.find(ODS_SETTINGS).is_none() {
        return Freeze::default();
    }
    let Ok(bytes) = archive.read_to_vec(ODS_SETTINGS) else {
        return Freeze::default();
    };
    match odf::ods_frozen_panes(&bytes).get(sheet) {
        Some(&(rows, columns)) => Freeze { rows: rows.min(MAX_ROW), columns: columns.min(MAX_COLUMN) },
        None => Freeze::default(),
    }
}

fn is_ods(archive: &ZipArchive) -> bool {
    archive.find(ODS_CONTENT).is_some() && archive.find(WORKBOOK).is_none()
}

/// A row of the sheet body: its start tag, inner cells and how many rows it stands for.
enum OdsSegment {
    Raw(String),
    Row { open: String, cells: String, repeat: u32 },
}

/// Byte range of the named table's body inside `content`.
fn ods_body(content: &str, sheet: &str) -> Result<(usize, usize), EngineError> {
    let mut pos = 0;
    while let Some(span) = next_element(content, pos, "table:table") {
        let open = &content[span.start..span.open_end];
        if attr(open, "table:name").map(unescape).as_deref() == Some(sheet) {
            if span.self_closing {
                return Err(EngineError::malformed("sheet has no rows"));
            }
            return Ok((span.open_end, span.end - "</table:table>".len()));
        }
        pos = span.end.max(span.start + 1);
    }
    Err(EngineError::malformed("sheet not found"))
}

fn ods_segments(body: &str) -> Vec<OdsSegment> {
    let mut segments = Vec::new();
    let mut pos = 0;
    while let Some(span) = next_element(body, pos, "table:table-row") {
        if span.start > pos {
            segments.push(OdsSegment::Raw(body[pos..span.start].to_owned()));
        }
        let open = body[span.start..span.open_end].to_owned();
        let cells = if span.self_closing {
            String::new()
        } else {
            body[span.open_end..span.end - "</table:table-row>".len()].to_owned()
        };
        let repeat = attr(&open, "table:number-rows-repeated").and_then(|v| v.parse::<u32>().ok()).unwrap_or(1).max(1);
        segments.push(OdsSegment::Row { open, cells, repeat });
        pos = span.end;
    }
    if pos < body.len() {
        segments.push(OdsSegment::Raw(body[pos..].to_owned()));
    }
    segments
}

fn ods_render(segments: &[OdsSegment]) -> String {
    let mut out = String::new();
    for segment in segments {
        match segment {
            OdsSegment::Raw(raw) => out.push_str(raw),
            OdsSegment::Row { open, cells, .. } => {
                out.push_str(open);
                if !(cells.is_empty() && open.ends_with("/>")) {
                    out.push_str(cells);
                    out.push_str("</table:table-row>");
                }
            }
        }
    }
    out
}

/// Segment index of row `index` (0-based), splitting a repeat that covers it. Rows past the
/// last segment are appended (an empty repeat for the gap, then the row itself).
fn ods_row_for_edit(segments: &mut Vec<OdsSegment>, index: u32) -> usize {
    let mut top = 0u32;
    for i in 0..segments.len() {
        let (open, cells, repeat) = match &segments[i] {
            OdsSegment::Row { open, cells, repeat } => (open.clone(), cells.clone(), *repeat),
            OdsSegment::Raw(_) => continue,
        };
        if index < top + repeat {
            if repeat == 1 {
                return i;
            }
            let before = index - top;
            let after = top + repeat - index - 1;
            let mut replacement = Vec::new();
            if before > 0 {
                replacement.push(OdsSegment::Row { open: with_row_repeat(&open, before), cells: cells.clone(), repeat: before });
            }
            let target = replacement.len();
            replacement.push(OdsSegment::Row { open: with_row_repeat(&open, 1), cells: cells.clone(), repeat: 1 });
            if after > 0 {
                replacement.push(OdsSegment::Row { open: with_row_repeat(&open, after), cells, repeat: after });
            }
            segments.splice(i..=i, replacement);
            return i + target;
        }
        top += repeat;
    }
    let missing = index - top;
    if missing > 0 {
        segments.push(OdsSegment::Row {
            open: format!(r#"<table:table-row table:number-rows-repeated="{missing}">"#),
            cells: "<table:table-cell/>".to_owned(),
            repeat: missing,
        });
    }
    segments.push(OdsSegment::Row { open: "<table:table-row>".to_owned(), cells: String::new(), repeat: 1 });
    segments.len() - 1
}

/// Read-only lookup of a row's segment index.
fn ods_row_lookup(segments: &[OdsSegment], index: u32) -> Option<usize> {
    let mut top = 0u32;
    for (i, segment) in segments.iter().enumerate() {
        if let OdsSegment::Row { repeat, .. } = segment {
            if index < top + repeat {
                return Some(i);
            }
            top += repeat;
        }
    }
    None
}

/// Start tag with its row repeat set to `count` (no attribute when 1).
fn with_row_repeat(open: &str, count: u32) -> String {
    set_repeat(&remove_attr(open, "table:number-rows-repeated"), "table:number-rows-repeated", count)
}

/// Start tag with its column repeat set to `count` (no attribute when 1).
fn with_column_repeat(open: &str, count: u32) -> String {
    set_repeat(&remove_attr(open, "table:number-columns-repeated"), "table:number-columns-repeated", count)
}

fn set_repeat(stripped: &str, name: &str, count: u32) -> String {
    if count <= 1 {
        return stripped.to_owned();
    }
    let (head, close) = match stripped.strip_suffix("/>") {
        Some(head) => (head, "/>"),
        None => (&stripped[..stripped.len() - 1], ">"),
    };
    format!(r#"{head} {name}="{count}"{close}"#)
}

fn ods_cell_repeat(open: &str) -> u32 {
    attr(open, "table:number-columns-repeated").and_then(|v| v.parse::<u32>().ok()).unwrap_or(1).max(1)
}

/// Cell elements of a row's inner XML as (start, end) byte ranges, in order.
fn ods_cells(cells: &str) -> Vec<(usize, usize)> {
    let mut out = Vec::new();
    let mut pos = 0;
    loop {
        let table = next_element(cells, pos, "table:table-cell");
        let covered = next_element(cells, pos, "table:covered-table-cell");
        let next = match (table, covered) {
            (Some(a), Some(b)) => {
                if a.start <= b.start {
                    a
                } else {
                    b
                }
            }
            (Some(a), None) => a,
            (None, Some(b)) => b,
            (None, None) => break,
        };
        out.push((next.start, next.end));
        pos = next.end;
    }
    out
}

/// Rewrites the cell at `column` (0-based) in a row's inner XML. `build` receives the existing
/// cell's start tag (None for a gap) and returns the replacement cell XML.
fn ods_replace_cell(
    cells: &str,
    column: u32,
    build: impl Fn(Option<&str>) -> String,
) -> String {
    let mut out = String::with_capacity(cells.len() + 64);
    let mut col = 0u32;
    let mut done = false;
    let mut last_end = 0usize;
    for (start, end) in ods_cells(cells) {
        out.push_str(&cells[last_end..start]);
        last_end = end;
        let element = &cells[start..end];
        let open = open_tag(element);
        let repeat = ods_cell_repeat(open);
        if done || column < col || column >= col + repeat {
            out.push_str(element);
            col += repeat;
            continue;
        }
        let before = column - col;
        let after = col + repeat - column - 1;
        if before > 0 {
            out.push_str(&with_column_repeat(open, before));
            out.push_str(&element[open.len()..]);
        }
        out.push_str(&build(Some(open)));
        if after > 0 {
            out.push_str(&with_column_repeat(open, after));
            out.push_str(&element[open.len()..]);
        }
        col += repeat;
        done = true;
    }
    out.push_str(&cells[last_end..]);
    if !done {
        if column > col {
            out.push_str(&format!(r#"<table:table-cell table:number-columns-repeated="{}"/>"#, column - col));
        }
        out.push_str(&build(None));
    }
    out
}

/// The element covering `column` (0-based) in a row's inner XML, if any.
fn ods_cell_for(cells: &str, column: u32) -> Option<String> {
    let mut col = 0u32;
    for (start, end) in ods_cells(cells) {
        let element = &cells[start..end];
        let repeat = ods_cell_repeat(open_tag(element));
        if column >= col && column < col + repeat {
            return Some(element.to_owned());
        }
        col += repeat;
    }
    None
}

/// Plain text of a cell's `text:p` paragraphs (one per line), with spaces, tabs and breaks.
fn ods_text(inner: &str) -> String {
    let mut out = String::new();
    let mut rest = inner;
    while let Some(start) = rest.find('<') {
        out.push_str(&unescape(&rest[..start]));
        let Some(end) = rest[start..].find('>').map(|e| start + e) else { break };
        let tag = &rest[start..=end];
        if tag.starts_with("<text:s") {
            let count = attr(tag, "text:c").and_then(|v| v.parse::<usize>().ok()).unwrap_or(1);
            out.push_str(&" ".repeat(count.min(1024)));
        } else if tag.starts_with("<text:line-break") {
            out.push('\n');
        } else if tag.starts_with("<text:tab") {
            out.push('\t');
        }
        rest = &rest[end + 1..];
    }
    out.push_str(&unescape(rest));
    out
}

fn ods_text_block(cell: &str) -> String {
    let inner = inner(cell).unwrap_or("");
    let mut out = String::new();
    let mut pos = 0;
    while let Some(span) = next_element(inner, pos, "text:p") {
        if !span.self_closing {
            if !out.is_empty() {
                out.push('\n');
            }
            out.push_str(&ods_text(&inner[span.open_end..span.end - "</text:p>".len()]));
        }
        pos = span.end;
    }
    out
}

fn read_ods_cell(content: &str, sheet: &str, row: u32, column: u32) -> Result<CellSnapshot, EngineError> {
    let (start, end) = ods_body(content, sheet)?;
    let segments = ods_segments(&content[start..end]);
    let empty = CellSnapshot { kind: CellKind::Empty, raw: String::new() };
    let Some(OdsSegment::Row { cells, .. }) = ods_row_lookup(&segments, row).map(|i| &segments[i]) else {
        return Ok(empty);
    };
    let Some(cell) = ods_cell_for(cells, column) else {
        return Ok(empty);
    };
    let open = open_tag(&cell);
    if let Some(formula) = attr(open, "table:formula") {
        return Ok(CellSnapshot { kind: CellKind::Formula, raw: unescape(formula) });
    }
    let snapshot = match attr(open, "office:value-type") {
        Some("float" | "percentage" | "currency") => CellSnapshot {
            kind: CellKind::Number,
            raw: attr(open, "office:value").unwrap_or_default().to_owned(),
        },
        Some("boolean") => CellSnapshot {
            kind: CellKind::Bool,
            raw: attr(open, "office:boolean-value").unwrap_or_default().to_owned(),
        },
        Some("string") => CellSnapshot { kind: CellKind::Text, raw: ods_text_block(&cell) },
        _ if cell.contains("<text:p") => CellSnapshot { kind: CellKind::Text, raw: ods_text_block(&cell) },
        _ => empty,
    };
    Ok(snapshot)
}

fn ods_cell_xml(style_from: Option<&str>, value: &CellValue) -> String {
    let style = style_from
        .and_then(|open| attr(open, "table:style-name"))
        .map(|s| format!(r#" table:style-name="{}""#, escape(s)))
        .unwrap_or_default();
    match value {
        CellValue::Number(n) => format!(
            r#"<table:table-cell{style} office:value-type="float" office:value="{n}"><text:p>{n}</text:p></table:table-cell>"#
        ),
        CellValue::Text(text) if text.is_empty() => format!("<table:table-cell{style}/>"),
        CellValue::Text(text) => {
            let body = escape(text).replace('\n', "<text:line-break/>");
            format!(r#"<table:table-cell{style} office:value-type="string"><text:p>{body}</text:p></table:table-cell>"#)
        }
    }
}

fn apply_ods(data: &[u8], edits: &[CellEdit]) -> Result<Vec<u8>, EngineError> {
    let mut archive = open(data)?;
    let mut content = read_text(&mut archive, ODS_CONTENT)?;
    drop(archive);
    // Edits apply in order; each re-renders the sheet body it touches.
    for edit in edits {
        let (start, end) = ods_body(&content, &edit.sheet)?;
        let mut segments = ods_segments(&content[start..end]);
        let row_index = ods_row_for_edit(&mut segments, edit.row);
        let (open, cells) = match &segments[row_index] {
            OdsSegment::Row { open, cells, .. } => (open.clone(), cells.clone()),
            OdsSegment::Raw(_) => return Err(EngineError::malformed("row not found")),
        };
        let existing = ods_cell_for(&cells, edit.column);
        if existing.as_deref().is_some_and(|cell| attr(open_tag(cell), "table:formula").is_some()) {
            return Err(EngineError::malformed("formula cells cannot be edited"));
        }
        let blank = matches!(&edit.value, CellValue::Text(t) if t.is_empty());
        if existing.is_none() && blank {
            continue;
        }
        let new_cells = ods_replace_cell(&cells, edit.column, |style_from| ods_cell_xml(style_from, &edit.value));
        segments[row_index] = OdsSegment::Row { open, cells: new_cells, repeat: 1 };
        let body = ods_render(&segments);
        content = format!("{}{}{}", &content[..start], body, &content[end..]);
    }
    let mut package = PackageEdit::default();
    package.replace.insert(ODS_CONTENT.to_owned(), content.into_bytes());
    rewrite_package(data, &package)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::zip::testing::zip;

    const ODS_BODY: &str = r#"<office:document-content><office:body><office:spreadsheet><table:table table:name="Stock"><table:table-row><table:table-cell office:value-type="string"><text:p>Item</text:p></table:table-cell><table:table-cell table:number-columns-repeated="2"/><table:table-cell office:value-type="float" office:value="3"><text:p>3</text:p></table:table-cell></table:table-row><table:table-row table:number-rows-repeated="3"><table:table-cell table:style-name="ce1" office:value-type="float" office:value="1"><text:p>1</text:p></table:table-cell></table:table-row><table:table-row><table:table-cell table:formula="of:=SUM([.A1])" office:value-type="float" office:value="9"><text:p>9</text:p></table:table-cell></table:table-row></table:table></office:spreadsheet></office:body></office:document-content>"#;

    fn ods_book() -> Vec<u8> {
        let mime: &[u8] = b"application/vnd.oasis.opendocument.spreadsheet";
        zip(&[("mimetype", mime), ("content.xml", ODS_BODY.as_bytes())])
    }

    fn ods_content(data: &[u8]) -> String {
        let mut archive = open(data).unwrap();
        read_text(&mut archive, ODS_CONTENT).unwrap()
    }

    fn ods_edit(row: u32, column: u32, value: CellValue) -> CellEdit {
        CellEdit { sheet: "Stock".into(), row, column, value }
    }

    #[test]
    fn ods_reads_text_number_and_formula() {
        let data = ods_book();
        assert_eq!(read_cell(&data, "Stock", 0, 0).unwrap(), CellSnapshot { kind: CellKind::Text, raw: "Item".into() });
        assert_eq!(read_cell(&data, "Stock", 0, 3).unwrap(), CellSnapshot { kind: CellKind::Number, raw: "3".into() });
        assert_eq!(read_cell(&data, "Stock", 4, 0).unwrap().kind, CellKind::Formula);
        assert_eq!(read_cell(&data, "Stock", 0, 1).unwrap().kind, CellKind::Empty);
    }

    #[test]
    fn ods_edit_splits_only_the_covering_repeat() {
        let out = apply_cell_edits(&ods_book(), &[ods_edit(0, 1, CellValue::Text("mid".into()))]).unwrap();
        let xml = ods_content(&out);
        assert!(xml.contains(r#"<table:table-cell office:value-type="string"><text:p>mid</text:p></table:table-cell><table:table-cell office:value-type="float" office:value="3">"#));
        assert!(xml.contains(
            r#"<text:p>mid</text:p></table:table-cell><table:table-cell/><table:table-cell office:value-type="float" office:value="3">"#
        ));
    }

    #[test]
    fn ods_edit_in_repeated_row_keeps_neighbours_and_style() {
        let out = apply_cell_edits(&ods_book(), &[ods_edit(1, 0, CellValue::Number(7.5))]).unwrap();
        let xml = ods_content(&out);
        assert!(xml.contains(
            r#"<table:table-row><table:table-cell table:style-name="ce1" office:value-type="float" office:value="7.5"><text:p>7.5</text:p></table:table-cell></table:table-row>"#
        ));
        assert!(read_cell(&out, "Stock", 2, 0).unwrap().raw == "1");
        assert!(read_cell(&out, "Stock", 1, 0).unwrap().raw == "7.5");
    }

    /// Two sheets: `Stock` frozen (2 rows, 1 column above and left), `Plain` only split (mode 1).
    const ODS_SETTINGS_XML: &[u8] = br#"<?xml version="1.0"?><office:document-settings><office:settings><config:config-item-set config:name="ooo:view-settings"><config:config-item-map-indexed config:name="Views"><config:config-item-map-entry><config:config-item config:name="ActiveTable" config:type="string">Stock</config:config-item><config:config-item-map-named config:name="Tables"><config:config-item-map-entry config:name="Stock"><config:config-item config:name="HorizontalSplitMode" config:type="short">2</config:config-item><config:config-item config:name="VerticalSplitMode" config:type="short">2</config:config-item><config:config-item config:name="HorizontalSplitPosition" config:type="int">1</config:config-item><config:config-item config:name="VerticalSplitPosition" config:type="int">2</config:config-item></config:config-item-map-entry><config:config-item-map-entry config:name="Plain"><config:config-item config:name="HorizontalSplitMode" config:type="short">1</config:config-item><config:config-item config:name="VerticalSplitMode" config:type="short">1</config:config-item><config:config-item config:name="HorizontalSplitPosition" config:type="int">3</config:config-item><config:config-item config:name="VerticalSplitPosition" config:type="int">4</config:config-item></config:config-item-map-entry></config:config-item-map-named></config:config-item-map-entry></config:config-item-map-indexed></config:config-item-set></office:settings></office:document-settings>"#;

    fn ods_book_with_settings() -> Vec<u8> {
        let mime: &[u8] = b"application/vnd.oasis.opendocument.spreadsheet";
        zip(&[("mimetype", mime), ("content.xml", ODS_BODY.as_bytes()), ("settings.xml", ODS_SETTINGS_XML)])
    }

    #[test]
    fn ods_frozen_panes_come_from_settings() {
        let data = ods_book_with_settings();
        assert_eq!(read_freeze(&data, "Stock").unwrap(), Freeze { rows: 2, columns: 1 });
        // A plain split (mode 1) is not a freeze.
        assert_eq!(read_freeze(&data, "Plain").unwrap(), Freeze::default());
        // A sheet the settings do not name.
        assert_eq!(read_freeze(&data, "Nope").unwrap(), Freeze::default());
    }

    #[test]
    fn ods_without_settings_has_no_freeze() {
        assert_eq!(read_freeze(&ods_book(), "Stock").unwrap(), Freeze::default());
    }

    #[test]
    fn ods_formula_cells_are_refused() {
        assert!(apply_cell_edits(&ods_book(), &[ods_edit(4, 0, CellValue::Number(1.0))]).is_err());
    }

    #[test]
    fn ods_text_escapes_and_breaks_lines() {
        let out = apply_cell_edits(&ods_book(), &[ods_edit(0, 0, CellValue::Text("a & b\nc".into()))]).unwrap();
        assert_eq!(read_cell(&out, "Stock", 0, 0).unwrap().raw, "a & b\nc");
    }

    #[test]
    fn ods_rows_past_the_end_are_added() {
        let out = apply_cell_edits(&ods_book(), &[ods_edit(9, 2, CellValue::Number(4.0))]).unwrap();
        assert_eq!(read_cell(&out, "Stock", 9, 2).unwrap().raw, "4");
    }

    const WORKBOOK_XML: &[u8] = br#"<?xml version="1.0"?><workbook><sheets><sheet name="Data" sheetId="1" r:id="rId1"/><sheet name="Other &amp; more" sheetId="2" r:id="rId2"/></sheets></workbook>"#;
    const WB_RELS: &[u8] = br#"<Relationships><Relationship Id="rId1" Type="http://x/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://x/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="http://x/sharedStrings" Target="sharedStrings.xml"/></Relationships>"#;
    const SHEET1: &[u8] = br#"<?xml version="1.0"?><worksheet><dimension ref="A1:C2"/><sheetViews><sheetView><pane xSplit="1" ySplit="1" topLeftCell="B2" activePane="bottomRight" state="frozen"/></sheetView></sheetViews><sheetData><row r="1" spans="1:3"><c r="A1" t="s"><v>0</v></c><c r="B1" s="2"><v>5</v></c></row><row r="2"><c r="C2"><f>SUM(B1:B1)</f><v>5</v></c></row></sheetData><pageMargins/></worksheet>"#;
    const SHEET2: &[u8] = br#"<worksheet><sheetData/></worksheet>"#;
    const SHARED: &[u8] = br#"<sst count="2" uniqueCount="2"><si><t>Name</t></si><si><r><t>Rich</t></r></si></sst>"#;

    fn book() -> Vec<u8> {
        zip(&[
            ("xl/workbook.xml", WORKBOOK_XML),
            ("xl/_rels/workbook.xml.rels", WB_RELS),
            ("xl/worksheets/sheet1.xml", SHEET1),
            ("xl/worksheets/sheet2.xml", SHEET2),
            ("xl/sharedStrings.xml", SHARED),
        ])
    }

    fn part(data: &[u8], name: &str) -> String {
        let mut archive = open(data).unwrap();
        read_text(&mut archive, name).unwrap()
    }

    fn edit(sheet: &str, row: u32, column: u32, value: CellValue) -> CellEdit {
        CellEdit { sheet: sheet.into(), row, column, value }
    }

    #[test]
    fn column_letters_round_trip() {
        for column in [0, 1, 25, 26, 27, 701, 702, 16_383] {
            let reference = format!("{}1", column_letters(column));
            assert_eq!(parse_reference(&reference), Some((1, column)), "{reference}");
        }
        assert_eq!(column_letters(26), "AA");
    }

    #[test]
    fn freeze_comes_from_the_pane() {
        let freeze = read_freeze(&book(), "Data").unwrap();
        assert_eq!(freeze, Freeze { rows: 1, columns: 1 });
        assert_eq!(read_freeze(&book(), "Other & more").unwrap(), Freeze::default());
    }

    #[test]
    fn reads_shared_text_formula_and_number() {
        let data = book();
        assert_eq!(read_cell(&data, "Data", 0, 0).unwrap(), CellSnapshot { kind: CellKind::Text, raw: "Name".into() });
        assert_eq!(read_cell(&data, "Data", 0, 1).unwrap(), CellSnapshot { kind: CellKind::Number, raw: "5".into() });
        assert_eq!(read_cell(&data, "Data", 1, 2).unwrap().kind, CellKind::Formula);
        assert_eq!(read_cell(&data, "Data", 9, 9).unwrap().kind, CellKind::Empty);
    }

    #[test]
    fn known_text_reuses_its_shared_index() {
        let out = apply_cell_edits(&book(), &[edit("Data", 0, 0, CellValue::Text("Name".into()))]).unwrap();
        let sheet = part(&out, "xl/worksheets/sheet1.xml");
        assert!(sheet.contains(r#"<c r="A1" t="s"><v>0</v></c>"#));
        let shared = part(&out, "xl/sharedStrings.xml");
        assert!(shared.contains(r#"uniqueCount="2""#));
    }

    #[test]
    fn new_text_is_appended_to_the_table() {
        let out = apply_cell_edits(&book(), &[edit("Data", 0, 0, CellValue::Text("Fish & <chips>".into()))]).unwrap();
        let shared = part(&out, "xl/sharedStrings.xml");
        assert!(shared.contains(r#"<si><t xml:space="preserve">Fish &amp; &lt;chips&gt;</t></si></sst>"#));
        assert!(shared.contains(r#"uniqueCount="3""#));
        let sheet = part(&out, "xl/worksheets/sheet1.xml");
        assert!(sheet.contains(r#"<c r="A1" t="s"><v>2</v></c>"#));
    }

    #[test]
    fn number_keeps_style_and_drops_old_value_type() {
        let out = apply_cell_edits(&book(), &[edit("Data", 0, 1, CellValue::Number(7.5))]).unwrap();
        let sheet = part(&out, "xl/worksheets/sheet1.xml");
        assert!(sheet.contains(r#"<c r="B1" s="2"><v>7.5</v></c>"#));
    }

    #[test]
    fn formula_cells_are_refused() {
        assert!(apply_cell_edits(&book(), &[edit("Data", 1, 2, CellValue::Number(1.0))]).is_err());
    }

    #[test]
    fn new_cell_in_existing_row_and_new_row_keep_order_and_dimension() {
        let edits = [
            edit("Data", 1, 0, CellValue::Text("left".into())),
            edit("Data", 4, 3, CellValue::Number(2.0)),
        ];
        let out = apply_cell_edits(&book(), &edits).unwrap();
        let sheet = part(&out, "xl/worksheets/sheet1.xml");
        assert!(sheet.contains(r#"<row r="2"><c r="A2" t="s">"#));
        assert!(sheet.contains(r#"<row r="5"><c r="D5"><v>2</v></c></row>"#));
        assert!(sheet.contains(r#"<dimension ref="A1:D5"/>"#));
        assert!(sheet.find(r#"<row r="2""#).unwrap() < sheet.find(r#"<row r="5""#).unwrap());
        assert!(sheet.contains("<pageMargins/></worksheet>"));
    }

    #[test]
    fn inline_text_when_no_shared_table() {
        let data = zip(&[
            ("xl/workbook.xml", WORKBOOK_XML),
            ("xl/_rels/workbook.xml.rels", WB_RELS),
            ("xl/worksheets/sheet1.xml", SHEET1),
            ("xl/worksheets/sheet2.xml", SHEET2),
        ]);
        let out = apply_cell_edits(&data, &[edit("Data", 0, 0, CellValue::Text("x".into()))]).unwrap();
        let sheet = part(&out, "xl/worksheets/sheet1.xml");
        assert!(sheet.contains(r#"<c r="A1" t="inlineStr"><is><t xml:space="preserve">x</t></is></c>"#));
    }

    #[test]
    fn unknown_sheet_and_empty_batch_fail() {
        assert!(apply_cell_edits(&book(), &[edit("Nope", 0, 0, CellValue::Number(1.0))]).is_err());
        assert!(apply_cell_edits(&book(), &[]).is_err());
    }

    #[test]
    fn non_finite_numbers_are_refused() {
        assert!(apply_cell_edits(&book(), &[edit("Data", 0, 1, CellValue::Number(f64::NAN))]).is_err());
    }

    #[test]
    fn escape_and_unescape_round_trip() {
        let text = r#"a & b < c > "d" 'e'"#;
        assert_eq!(unescape(&escape(text)), text);
        assert_eq!(unescape("&#x41;&#66;&bogus;"), "AB&bogus;");
    }
}
