//! OpenDocument text, spreadsheet and presentation (`content.xml`), streamed.
//!
//! - ODT: headings (`text:h`), paragraphs, list items (by list nesting) and
//!   tables, with bold/italic/underline/strike from automatic styles and
//!   safe links. Pictures (`draw:image`) become image blocks in reading order,
//!   fetched on demand through [`BlockSource::resource`], also in ODP; those
//!   inside table cells are skipped.
//! - ODS: each sheet as a [`Block::Section`] followed by numbered rows. Cells
//!   show their displayed text, so number and date formats are kept. That text
//!   is optional in the format; without it a cell shows its typed value.
//! - ODP: each page as a laid-out slide, by [`crate::odp`].
//!
//! Rows inside `table:table-header-rows` are marked as header rows.
//!
//! Repeated rows and cells (`number-rows-repeated`, often ~1,000,000 empty
//! trailing rows) are expanded only when they hold content, and then at most
//! [`MAX_REPEAT`] times.

use std::collections::{HashMap, VecDeque};

use document_core::style::{BOLD, ITALIC, STRIKE, UNDERLINE};
use document_core::{Block, ErrorKind, FormatId, Result, Rich};

use crate::dom::{self, Node};
use crate::numfmt::general;
use crate::odp::OdpDeck;
use crate::ooxml::{is_safe_link, resolve_inside};
use crate::rich::RichBuilder;
use crate::stream::{BlockSource, MAX_PENDING_TEXT, Parts};
use crate::xlsx::MAX_COLUMNS;
use crate::xml::{Tag, Xml};

const MAX_REPEAT: u32 = 1000;

pub(crate) struct OdfSource {
    parts: Parts,
    format: FormatId,
    started: bool,
    text: RichBuilder,
    /// Automatic style name → style flags.
    styles: HashMap<String, u8>,
    /// Style being defined inside `office:automatic-styles`.
    defining: Option<String>,
    paragraph_style: u8,
    span_styles: Vec<u8>,
    links: Vec<Option<String>>,
    in_notes: bool,
    /// Element nesting of `text:list` around the current paragraph.
    list_depth: u8,
    /// Depth of `text:p`/`text:h` elements being read (nested notes, frames).
    paragraph_depth: u32,
    heading: Option<u8>,
    in_title_frame: bool,
    slide: u32,
    table: Option<Table>,
    /// Open `draw:frame`s; not tracked for spreadsheets.
    frames: Vec<Frame>,
    /// A picture sat in the current paragraph, which may have nothing else to show.
    paragraph_has_image: bool,
    /// Text before a picture already went out, carrying any list bullet.
    paragraph_split: bool,
    /// Presentations only.
    deck: Option<OdpDeck>,
}

struct Frame {
    width: Option<f32>,
    height: Option<f32>,
    has_image: bool,
    path: Option<String>,
    title: String,
    description: String,
    /// `paragraph_depth` at the frame: deeper text belongs to a text box.
    paragraph_depth: u32,
}

/// Longest alt text kept while reading a frame.
const MAX_ALT_TEXT: usize = 4096;

#[derive(Default)]
struct Table {
    depth: u32,
    row_number: u32,
    row_repeat: u32,
    cells: Vec<String>,
    cell: String,
    /// What the current cell shows if it has no displayed text.
    cell_value: String,
    cell_repeat: u32,
    column: usize,
    in_header_rows: bool,
}

impl OdfSource {
    pub fn new(parts: Parts, format: FormatId) -> Self {
        Self {
            parts,
            format,
            started: false,
            text: RichBuilder::default(),
            styles: HashMap::new(),
            defining: None,
            paragraph_style: 0,
            span_styles: Vec::new(),
            links: Vec::new(),
            in_notes: false,
            list_depth: 0,
            paragraph_depth: 0,
            heading: None,
            in_title_frame: false,
            slide: 0,
            table: None,
            frames: Vec::new(),
            paragraph_has_image: false,
            paragraph_split: false,
            deck: (format == FormatId::Odp).then(OdpDeck::default),
        }
    }

    /// Master pages, page sizes and styles of a presentation. They only
    /// improve how slides look, so a missing or broken part is ignored.
    fn read_deck_styles(&mut self) -> Result<()> {
        let Some(deck) = self.deck.as_mut() else { return Ok(()) };
        let limit = self.parts.archive().limits().max_part_bytes;
        if !self.parts.archive().find("styles.xml").is_some_and(|e| !e.is_dir && e.size <= limit) {
            return Ok(());
        }
        let cancel = self.parts.cancel();
        match self.parts.archive().read_to_vec("styles.xml").and_then(|bytes| dom::parse(&bytes, &cancel)) {
            Err(err) if err.kind == ErrorKind::Cancelled => Err(err),
            Ok(Some(root)) => {
                deck.read_styles(&root, &self.parts);
                Ok(())
            }
            _ => Ok(()),
        }
    }

    /// Reads presentation pages and their styles whole; gives back any other tag.
    fn on_deck_element(&mut self, tag: Tag, empty: bool, out: &mut VecDeque<Block>) -> Result<Option<Tag>> {
        let Some(deck) = self.deck.as_mut() else { return Ok(Some(tag)) };
        if !matches!(tag.name.as_str(), "automatic-styles" | "page") {
            return Ok(Some(tag));
        }
        let name = tag.name.clone();
        let node = if empty {
            Node { tag, children: Vec::new(), text: String::new(), tail: String::new() }
        } else {
            dom::subtree(self.parts.xml(), tag)?
        };
        if name == "page" {
            deck.page(&node, &self.parts, out);
        } else {
            deck.add_styles(&node);
        }
        Ok(None)
    }

    fn on_start(&mut self, tag: &Tag, out: &mut VecDeque<Block>) -> Result<()> {
        match tag.name.as_str() {
            // Revision history and annotations.
            "tracked-changes" | "annotation" | "office-annotation" => self.parts.xml().skip_element()?,
            "notes" if self.format == FormatId::Odp => self.in_notes = true,
            "notes" => self.parts.xml().skip_element()?,
            "style" => self.defining = tag.attr("name").map(str::to_owned),
            "h" | "p" => {
                if self.paragraph_depth == 0 {
                    self.paragraph_has_image = false;
                    self.paragraph_split = false;
                    self.text = RichBuilder::default();
                    self.paragraph_style =
                        tag.attr("style-name").and_then(|n| self.styles.get(n)).copied().unwrap_or(0);
                    self.heading =
                        (tag.name == "h").then(|| tag.attr("outline-level").and_then(|l| l.parse().ok()).unwrap_or(1));
                } else if !self.text.is_empty() {
                    self.text.push_plain(" ");
                }
                self.paragraph_depth += 1;
            }
            "span" => {
                let style = tag.attr("style-name").and_then(|n| self.styles.get(n)).copied().unwrap_or(0);
                self.span_styles.push(style);
            }
            "a" => self.links.push(tag.attr("href").filter(|h| is_safe_link(h)).map(str::to_owned)),
            "table-header-rows" => {
                if let Some(table) = self.table.as_mut().filter(|t| t.depth == 1) {
                    table.in_header_rows = true;
                }
            }
            "list" => self.list_depth = self.list_depth.saturating_add(1),
            "page" => {
                self.slide += 1;
                out.push_back(Block::Section { title: format!("Slide {}", self.slide) });
            }
            "frame" => {
                self.in_title_frame = tag.attr("class") == Some("title");
                if self.format != FormatId::Ods {
                    self.frames.push(Frame {
                        width: tag.attr("width").and_then(length_points),
                        height: tag.attr("height").and_then(length_points),
                        has_image: false,
                        path: None,
                        title: String::new(),
                        description: String::new(),
                        paragraph_depth: self.paragraph_depth,
                    });
                }
            }
            // Its children are embedded data or an empty paragraph, not text to show.
            "image" if !self.frames.is_empty() => {
                self.on_image(tag);
                self.parts.xml().skip_element()?;
            }
            // Alt text of a frame; without this it would read as paragraph text.
            "title" | "desc" if self.frames.last().is_some_and(|f| f.paragraph_depth == self.paragraph_depth) => {
                let text = self.parts.xml().element_text(MAX_ALT_TEXT)?;
                if let Some(frame) = self.frames.last_mut() {
                    *(if tag.name == "title" { &mut frame.title } else { &mut frame.description }) = text;
                }
            }
            "table" => {
                let table = self.table.get_or_insert_with(Table::default);
                table.depth += 1;
                if table.depth == 1 && self.format == FormatId::Ods {
                    out.push_back(Block::Section { title: tag.attr("name").unwrap_or("Sheet").to_owned() });
                }
            }
            "table-row" => {
                if let Some(table) = self.table.as_mut().filter(|t| t.depth == 1) {
                    table.cells.clear();
                    table.column = 0;
                    table.row_repeat = repeat(tag, "number-rows-repeated");
                }
            }
            "table-cell" | "covered-table-cell" => {
                if let Some(table) = self.table.as_mut().filter(|t| t.depth == 1) {
                    table.cell.clear();
                    table.cell_value = typed_value(tag);
                    table.cell_repeat = repeat(tag, "number-columns-repeated");
                }
            }
            _ => self.on_empty(tag, out),
        }
        Ok(())
    }

    fn on_empty(&mut self, tag: &Tag, out: &mut VecDeque<Block>) {
        match tag.name.as_str() {
            "text-properties" => {
                if let Some(name) = self.defining.clone() {
                    let mut style = 0;
                    if tag.attr("font-weight").is_some_and(|w| w == "bold" || w.parse::<u32>().is_ok_and(|n| n >= 600))
                    {
                        style |= BOLD;
                    }
                    if matches!(tag.attr("font-style"), Some("italic" | "oblique")) {
                        style |= ITALIC;
                    }
                    if tag.attr("text-underline-style").is_some_and(|u| u != "none") {
                        style |= UNDERLINE;
                    }
                    if tag.attr("text-line-through-style").is_some_and(|u| u != "none") {
                        style |= STRIKE;
                    }
                    self.styles.insert(name, style);
                }
            }
            "s" => {
                let count = tag.attr("c").and_then(|c| c.parse::<usize>().ok()).unwrap_or(1).min(64);
                self.text.push_plain(&" ".repeat(count));
            }
            "tab" => self.text.push_plain("\t"),
            "line-break" => self.text.push_plain("\n"),
            "image" => self.on_image(tag),
            "page" => {
                self.slide += 1;
                out.push_back(Block::Section { title: format!("Slide {}", self.slide) });
            }
            "table-row" => {
                if let Some(table) = self.table.as_mut().filter(|t| t.depth == 1) {
                    table.row_number += repeat(tag, "number-rows-repeated");
                }
            }
            "table-cell" | "covered-table-cell" => {
                if let Some(table) = self.table.as_mut().filter(|t| t.depth == 1) {
                    table.cell.clear();
                    table.cell_value = typed_value(tag);
                    table.cell_repeat = repeat(tag, "number-columns-repeated");
                }
                self.end_cell();
            }
            _ => {}
        }
    }

    /// A frame may list several versions of its picture; the first that can
    /// be served wins. Relative targets start at the package root.
    fn on_image(&mut self, tag: &Tag) {
        let path =
            tag.attr("href").and_then(|href| resolve_inside("", href)).and_then(|path| self.parts.picture_path(&path));
        if let Some(frame) = self.frames.last_mut() {
            frame.has_image = true;
            frame.path = frame.path.take().or(path);
        }
    }

    /// Emits the frame's picture after any text that came before it, which
    /// keeps reading order; text after it continues in a new block. Table
    /// cells hold plain text, so pictures in them are skipped.
    fn end_frame(&mut self, out: &mut VecDeque<Block>) {
        self.in_title_frame = false;
        let Some(frame) = self.frames.pop().filter(|f| f.has_image) else { return };
        if self.table.is_some() || self.in_notes {
            return;
        }
        if self.paragraph_depth > 0 {
            if !self.text.is_empty() {
                let text = self.text.take();
                out.push_back(self.text_block(text));
                self.paragraph_split = true;
            }
            self.paragraph_has_image = true;
        }
        let alt = if frame.description.trim().is_empty() { &frame.title } else { &frame.description };
        out.push_back(Block::image(frame.path, Some(alt), frame.width, frame.height));
    }

    fn end_paragraph(&mut self, out: &mut VecDeque<Block>) {
        self.paragraph_depth = self.paragraph_depth.saturating_sub(1);
        if self.paragraph_depth > 0 {
            return;
        }
        let text = self.text.take();
        if let Some(table) = self.table.as_mut().filter(|t| t.depth > 0) {
            if table.cell.len() < MAX_PENDING_TEXT {
                if !table.cell.is_empty() {
                    table.cell.push('\n');
                }
                table.cell.push_str(&text.text);
            }
            return;
        }
        if self.format == FormatId::Odp && text.text.trim().is_empty() {
            return;
        }
        if self.paragraph_has_image && text.text.trim().is_empty() {
            return;
        }
        let block = self.text_block(text);
        self.heading = None;
        out.push_back(block);
    }

    fn text_block(&self, text: Rich) -> Block {
        if self.in_notes {
            return Block::Quote { text };
        }
        match (self.heading, self.in_title_frame, self.list_depth) {
            (Some(level), _, _) => Block::Heading { level, text },
            (None, true, _) => Block::Heading { level: 2, text },
            (None, false, 0) => Block::Paragraph { text },
            (None, false, depth) => {
                Block::ListItem { depth: depth - 1, marker: self.paragraph_split.then(String::new), text }
            }
        }
    }

    fn end_cell(&mut self) {
        let Some(table) = self.table.as_mut().filter(|t| t.depth == 1) else { return };
        let value = std::mem::take(&mut table.cell);
        let typed = std::mem::take(&mut table.cell_value);
        let value = if value.is_empty() { typed } else { value };
        let start = table.column;
        table.column = table.column.saturating_add(table.cell_repeat as usize);
        if value.is_empty() || start >= MAX_COLUMNS {
            return;
        }
        let end = table.column.min(MAX_COLUMNS);
        if table.cells.len() < end {
            table.cells.resize(end, String::new());
        }
        for slot in &mut table.cells[start..end] {
            slot.clone_from(&value);
        }
    }

    fn end_row(&mut self, out: &mut VecDeque<Block>) {
        let Some(table) = self.table.as_mut().filter(|t| t.depth == 1) else { return };
        let first = table.row_number + 1;
        table.row_number += table.row_repeat;
        let cells = std::mem::take(&mut table.cells);
        if cells.iter().all(String::is_empty) {
            return;
        }
        let numbered = self.format == FormatId::Ods;
        let header = table.in_header_rows;
        for i in 0..table.row_repeat.min(MAX_REPEAT) {
            out.push_back(Block::TableRow {
                row: numbered.then_some(first + i),
                header,
                cells: cells.clone(),
                styles: Vec::new(),
            });
        }
    }
}

impl BlockSource for OdfSource {
    fn pump(&mut self, out: &mut VecDeque<Block>) -> Result<bool> {
        if !self.started {
            self.started = true;
            self.read_deck_styles()?;
            self.parts.open("content.xml")?;
        }
        let before = out.len();
        while out.len() == before {
            match self.parts.xml().next()? {
                Xml::Start(tag) => {
                    if let Some(tag) = self.on_deck_element(tag, false, out)? {
                        self.on_start(&tag, out)?;
                    }
                }
                Xml::Empty(tag) => {
                    if let Some(tag) = self.on_deck_element(tag, true, out)? {
                        self.on_empty(&tag, out);
                    }
                }
                Xml::Text(text) if self.paragraph_depth > 0 => {
                    let style = self.span_styles.iter().fold(self.paragraph_style, |acc, s| acc | s);
                    let link = self.links.iter().rev().find_map(Option::as_deref).map(str::to_owned);
                    self.text.push(&text, style, link.as_deref());
                    if self.text.len() > MAX_PENDING_TEXT && self.table.is_none() {
                        out.push_back(Block::Paragraph { text: self.text.take() });
                    }
                }
                Xml::Text(_) => {}
                Xml::End(name) => match name.as_str() {
                    "h" | "p" => self.end_paragraph(out),
                    "style" => self.defining = None,
                    "span" => {
                        self.span_styles.pop();
                    }
                    "a" => {
                        self.links.pop();
                    }
                    "notes" => self.in_notes = false,
                    "table-header-rows" => {
                        if let Some(table) = self.table.as_mut().filter(|t| t.depth == 1) {
                            table.in_header_rows = false;
                        }
                    }
                    "list" => self.list_depth = self.list_depth.saturating_sub(1),
                    "frame" => self.end_frame(out),
                    "table-cell" | "covered-table-cell" => self.end_cell(),
                    "table-row" => self.end_row(out),
                    "table" => {
                        if let Some(table) = &mut self.table {
                            table.depth -= 1;
                            if table.depth == 0 {
                                self.table = None;
                            }
                        }
                    }
                    _ => {}
                },
                Xml::Eof => {
                    self.parts.close();
                    return Ok(true);
                }
            }
        }
        Ok(false)
    }

    /// Pictures, and a text document as print-layout pages (see [`crate::odt_html`]).
    fn resource(&mut self, name: &str) -> Result<Vec<u8>> {
        if name == crate::docx_html::PAGES_RESOURCE && self.format == FormatId::Odt {
            return crate::odt_html::render(&mut self.parts);
        }
        self.parts.picture(name)
    }
}

/// A cell's `office:value-type` value, for cells with no `text:p`.
fn typed_value(tag: &Tag) -> String {
    let attr = |name| tag.attr(name).unwrap_or_default().to_owned();
    match tag.attr("value-type") {
        Some("float") => attr("value"),
        Some("percentage") => {
            let percent = tag.attr("value").and_then(|v| v.parse::<f64>().ok()).and_then(|v| general(v * 100.0));
            percent.map_or_else(|| attr("value"), |p| format!("{p}%"))
        }
        Some("currency") => format!("{} {}", attr("value"), attr("currency")).trim().to_owned(),
        Some("date") => attr("date-value"),
        Some("time") => attr("time-value"),
        Some("boolean") => attr("boolean-value").to_ascii_uppercase(),
        Some("string") => attr("string-value"),
        _ => String::new(),
    }
}

fn repeat(tag: &Tag, attr: &str) -> u32 {
    tag.attr(attr).and_then(|v| v.parse().ok()).unwrap_or(1).max(1)
}

/// An ODF length such as `16.51cm`, in points.
pub(crate) fn length_points(value: &str) -> Option<f32> {
    let unit_at = value.find(|c: char| c.is_ascii_alphabetic())?;
    let (number, unit) = value.split_at(unit_at);
    let number: f32 = number.trim().parse().ok()?;
    Some(match unit {
        "pt" => number,
        "in" => number * 72.0,
        "cm" => number * 72.0 / 2.54,
        "mm" => number * 72.0 / 25.4,
        "pc" => number * 12.0,
        "px" => number * 0.75,
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use crate::testing::{open_zip, texts};
    use crate::zip::testing::zip_with;
    use document_core::{Block, FormatId, Limits};

    fn package(mime: &str, content: &str) -> Vec<u8> {
        zip_with(&[("mimetype", mime.as_bytes()), ("content.xml", content.as_bytes())], false)
    }

    #[test]
    fn odt_structure() {
        let content = r#"<office:document-content xmlns:office="o" xmlns:text="t" xmlns:table="tb"><office:body><office:text>
<text:h text:outline-level="2">Intro</text:h>
<text:p>Hello<text:s text:c="2"/>world<text:tab/>!</text:p>
<text:list><text:list-item><text:p>Item</text:p><text:list><text:list-item><text:p>Sub</text:p></text:list-item></text:list></text:list-item></text:list>
<table:table><table:table-row><table:table-cell><text:p>A</text:p></table:table-cell><table:table-cell table:number-columns-repeated="2"><text:p>B</text:p></table:table-cell></table:table-row></table:table>
<office:annotation><text:p>hidden comment</text:p></office:annotation>
</office:text></office:body></office:document-content>"#;
        let (format, blocks) = open_zip(package("application/vnd.oasis.opendocument.text", content));
        assert_eq!(format, FormatId::Odt);
        assert_eq!(texts(&blocks), ["H2 Intro", "P Hello  world\t!", "L0 Item", "L1 Sub", "R A|B|B"]);
    }

    #[test]
    fn ods_skips_repeated_empty_rows() {
        let content = r#"<office:document-content><office:body><office:spreadsheet>
<table:table table:name="Budget">
<table:table-row><table:table-cell><text:p>Item</text:p></table:table-cell><table:table-cell table:number-columns-repeated="3"/><table:table-cell><text:p>€ 1,200.00</text:p></table:table-cell></table:table-row>
<table:table-row table:number-rows-repeated="1048570"><table:table-cell table:number-columns-repeated="1024"/></table:table-row>
<table:table-row><table:table-cell><text:p>Total</text:p></table:table-cell></table:table-row>
</table:table></office:spreadsheet></office:body></office:document-content>"#;
        let (format, blocks) = open_zip(package("application/vnd.oasis.opendocument.spreadsheet", content));
        assert_eq!(format, FormatId::Ods);
        assert_eq!(texts(&blocks), ["S Budget", "R1 Item||||€ 1,200.00", "R1048572 Total"]);
    }

    #[test]
    fn ods_displayed_text_wins_over_typed_values() {
        let content = r#"<office:document-content><office:body><office:spreadsheet><table:table table:name="S"><table:table-row>
<table:table-cell office:value-type="date" office:date-value="2023-11-01"><text:p>01/11/23</text:p></table:table-cell>
<table:table-cell office:value-type="percentage" office:value="0.25"><text:p>25.0%</text:p></table:table-cell>
<table:table-cell office:value-type="currency" office:currency="EUR" office:value="1200"><text:p>1.200,00 €</text:p></table:table-cell>
<table:table-cell office:value-type="float" office:value="1.5" calcext:value-type="float"><text:p>1.50</text:p></table:table-cell>
<table:table-cell office:value-type="boolean" office:boolean-value="true"><text:p>YES</text:p></table:table-cell>
<table:table-cell office:value-type="string" calcext:value-type="error"><text:p>#DIV/0!</text:p></table:table-cell>
</table:table-row></table:table></office:spreadsheet></office:body></office:document-content>"#;
        let (_, blocks) = open_zip(package("application/vnd.oasis.opendocument.spreadsheet", content));
        assert_eq!(texts(&blocks), ["S S", "R1 01/11/23|25.0%|1.200,00 €|1.50|YES|#DIV/0!"]);
    }

    #[test]
    fn ods_cells_without_displayed_text_show_their_typed_value() {
        let content = r#"<office:document-content><office:body><office:spreadsheet><table:table table:name="S"><table:table-row>
<table:table-cell office:value-type="float" office:value="3.5"/>
<table:table-cell office:value-type="percentage" office:value="0.07"></table:table-cell>
<table:table-cell office:value-type="currency" office:currency="USD" office:value="12"><text:p/></table:table-cell>
<table:table-cell office:value-type="date" office:date-value="2023-11-01"/>
<table:table-cell office:value-type="time" office:time-value="PT12H00M00S"/>
<table:table-cell office:value-type="boolean" office:boolean-value="false"/>
<table:table-cell office:value-type="string" office:string-value="hi"/>
<table:table-cell office:value-type="percentage" office:value="1e300"/>
<table:table-cell/>
<table:table-cell office:value-type="float" office:value="9" table:number-columns-repeated="2"/>
</table:table-row></table:table></office:spreadsheet></office:body></office:document-content>"#;
        let (_, blocks) = open_zip(package("application/vnd.oasis.opendocument.spreadsheet", content));
        assert_eq!(texts(&blocks), ["S S", "R1 3.5|7%|12 USD|2023-11-01|PT12H00M00S|FALSE|hi|1e300||9|9"]);
    }

    #[test]
    fn odp_pages_and_titles() {
        let content = r#"<office:document-content><office:body><office:presentation>
<draw:page draw:name="p1"><draw:frame presentation:class="title"><draw:text-box><text:p>Welcome</text:p></draw:text-box></draw:frame>
<draw:frame><draw:text-box><text:p>Agenda</text:p></draw:text-box></draw:frame><presentation:notes><text:p>speaker</text:p></presentation:notes></draw:page>
<draw:page draw:name="p2"/>
</office:presentation></office:body></office:document-content>"#;
        let (format, blocks) = open_zip(package("application/vnd.oasis.opendocument.presentation", content));
        assert_eq!(format, FormatId::Odp);
        assert_eq!(
            texts(&blocks),
            ["S Slide 1", "SLIDE 793x446 -", "SH Welcome", "SH Agenda", "Q speaker", "S Slide 2", "SLIDE 793x446 -"]
        );
    }

    const PNG: &[u8] = b"\x89PNG fake";
    const TEXT_MIME: &str = "application/vnd.oasis.opendocument.text";

    fn with_pictures(mime: &str, body: &str) -> Vec<u8> {
        let content = format!("<office:document-content><office:body>{body}</office:body></office:document-content>");
        zip_with(
            &[
                ("mimetype", mime.as_bytes()),
                ("content.xml", content.as_bytes()),
                ("Pictures/a b.png", PNG),
                ("Pictures/vector.svg", b"<svg/>"),
                ("ObjectReplacements/Object 1", b"svm"),
            ],
            false,
        )
    }

    fn frame(href: &str, labels: &str) -> String {
        format!(
            r#"<draw:frame svg:width="2.54cm" svg:height="36pt"><draw:image xlink:href="{href}" xlink:type="simple"/>{labels}</draw:frame>"#
        )
    }

    #[test]
    fn pictures_appear_in_reading_order_without_their_labels_as_text() {
        let body = format!(
            r#"<text:p>Before {}after</text:p><text:p>{}</text:p>{}<text:h text:outline-level="1">End</text:h>"#,
            frame("Pictures/a%20b.png", "<svg:title>Title</svg:title><svg:desc>Described</svg:desc>"),
            frame("./Pictures/a%20b.png", "<svg:title>Only title</svg:title>"),
            r#"<draw:frame><draw:image xlink:href="Pictures/a%20b.png"><office:binary-data>QUJD</office:binary-data><text:p/></draw:image></draw:frame>"#,
        );
        let (_, blocks) = open_zip(with_pictures(TEXT_MIME, &body));
        assert_eq!(
            texts(&blocks),
            [
                "P Before ",
                "PIC Pictures/a b.png [Described]",
                "P after",
                "PIC Pictures/a b.png [Only title]",
                "PIC Pictures/a b.png []",
                "H1 End",
            ]
        );
        let Block::Image { width, height, .. } = &blocks[1] else { panic!() };
        assert_eq!((*width, *height), (Some(72.0), Some(36.0)));
    }

    #[test]
    fn captions_list_items_and_fallback_versions_stay_correct() {
        let captioned = format!(
            r#"<text:p><draw:frame svg:width="10cm"><draw:text-box><text:p>{}Figure <text:title>Report</text:title></text:p></draw:text-box></draw:frame></text:p>"#,
            frame("Pictures/a%20b.png", ""),
        );
        let item = format!(
            r#"<text:list><text:list-item><text:p>One{}more</text:p></text:list-item></text:list>"#,
            frame("Pictures/a%20b.png", ""),
        );
        let versions = r#"<text:p><draw:frame><draw:image xlink:href="Pictures/vector.svg"/><draw:image xlink:href="Pictures/a%20b.png"/></draw:frame></text:p>"#;
        let (_, blocks) = open_zip(with_pictures(TEXT_MIME, &format!("{captioned}{item}{versions}")));
        assert_eq!(
            texts(&blocks),
            [
                "PIC Pictures/a b.png []",
                "P Figure Report",
                "L0 One",
                "PIC Pictures/a b.png []",
                "L0[] more",
                "PIC Pictures/a b.png []",
            ]
        );
    }

    #[test]
    fn unservable_pictures_show_a_label_and_cannot_be_read() {
        let targets = [
            "../../etc/passwd",
            "../Pictures/a%20b.png",
            "/etc/passwd",
            "http://evil.example/a.png",
            "file:///etc/a.png",
            "Pictures/vector.svg",
            "Pictures/missing.png",
            "./ObjectReplacements/Object 1",
        ];
        let body: String =
            targets.iter().map(|t| format!("<text:p>{}</text:p>", frame(t, "<svg:desc>label</svg:desc>"))).collect();
        let bytes = with_pictures(TEXT_MIME, &body);
        let (_, blocks) = open_zip(bytes.clone());
        assert_eq!(texts(&blocks), vec!["PIC - [label]"; targets.len()]);

        let mut session = crate::engine(Limits::default()).open(Cursor::new(bytes)).unwrap();
        assert_eq!(session.resource("Pictures/a b.png").unwrap(), PNG);
        for name in ["content.xml", "Pictures/vector.svg", "Pictures/missing.png", "../Pictures/a b.png"] {
            assert!(session.resource(name).is_err(), "{name}");
        }
    }

    #[test]
    fn pictures_in_cells_are_skipped_and_spreadsheets_are_unchanged() {
        let cell = format!(
            "<table:table><table:table-row><table:table-cell><text:p>Cell{}</text:p></table:table-cell></table:table-row></table:table>",
            frame("Pictures/a%20b.png", "<svg:desc>alt</svg:desc>"),
        );
        assert_eq!(texts(&open_zip(with_pictures(TEXT_MIME, &cell)).1), ["R Cell"]);

        let sheet = cell.replace("<table:table>", r#"<table:table table:name="S">"#);
        let (format, blocks) = open_zip(with_pictures("application/vnd.oasis.opendocument.spreadsheet", &sheet));
        assert_eq!(format, FormatId::Ods);
        assert_eq!(texts(&blocks), ["S S", "R1 Cellalt"], "ODS keeps its behaviour");
    }

    #[test]
    fn presentations_show_pictures_too() {
        let body = format!(r#"<draw:page>{}</draw:page>"#, frame("Pictures/a%20b.png", ""));
        let mime = "application/vnd.oasis.opendocument.presentation";
        assert_eq!(
            texts(&open_zip(with_pictures(mime, &body)).1),
            ["S Slide 1", "SLIDE 793x446 -", "IMG Pictures/a b.png"]
        );
    }

    #[test]
    fn pictures_can_be_read_in_the_middle_of_the_document() {
        let paragraphs: String =
            (0..20_000u32).map(|i| format!("<text:p>{:x}</text:p>", i.wrapping_mul(2_654_435_761))).collect();
        let body = format!("{}{paragraphs}", frame("Pictures/a%20b.png", ""));
        let limits = Limits { max_batch_bytes: 512, ..Limits::default() };
        let mut session = crate::engine(limits).open(Cursor::new(with_pictures(TEXT_MIME, &body))).unwrap();
        let mut blocks = Vec::new();
        while let Some(batch) = session.next_batch().unwrap() {
            assert_eq!(session.resource("Pictures/a b.png").unwrap(), PNG);
            blocks.extend(batch.blocks);
        }
        assert_eq!(blocks.len(), 20_001);
        assert_eq!(texts(&blocks[20_000..]), [format!("P {:x}", 19_999u32.wrapping_mul(2_654_435_761))]);
    }
}
