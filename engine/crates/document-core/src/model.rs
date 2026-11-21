//! Format-neutral document model shared by every parser.
//!
//! Variants are added only together with the first parser that produces them.

/// Format identified from content, never from a file extension alone.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FormatId {
    PlainText,
    /// Text with a `.md` name, rendered as CommonMark + GitHub tables.
    Markdown,
    /// Text with a `.csv`/`.tsv` name, shown as a grid.
    Csv,
    /// Text with a `.json` name, pretty-printed.
    Json,
    /// Text with a programming-language name, shown with line numbers.
    SourceCode,
    /// Text with an `.html`-family name: the platform renders the raw bytes, which
    /// the engine serves as a resource.
    Html,
    Pdf,
    /// A ZIP container whose contents have not been inspected yet. Detection
    /// produces this; the ZIP parser refines it into one of the formats below,
    /// or keeps it for a plain archive.
    Zip,
    Docx,
    Xlsx,
    Pptx,
    Odt,
    Ods,
    Odp,
    Epub,
    /// Word 97-2003: text and basic structure, not layout.
    Doc,
    /// Excel 97-2003: cached cell values with their looks.
    Xls,
    /// PowerPoint 97-2003: positioned slides, as PPTX.
    Ppt,
    /// PNG, JPEG, GIF, WebP, BMP, HEIF or AVIF, decoded by the platform.
    Image,
    /// OLE2 compound file whose contents have not been inspected yet. Detection
    /// produces this; the compound-file parser refines it into a legacy format
    /// above, or into the OOXML format inside a password-protected package.
    OleCompound,
    Unknown,
}

impl FormatId {
    /// Human-readable name for messages shown to users.
    pub fn label(self) -> &'static str {
        match self {
            FormatId::PlainText => "Plain text",
            FormatId::Markdown => "Markdown",
            FormatId::Csv => "CSV",
            FormatId::Json => "JSON",
            FormatId::SourceCode => "Source code",
            FormatId::Html => "HTML",
            FormatId::Pdf => "PDF",
            FormatId::Zip => "ZIP archive",
            FormatId::Docx => "Word (DOCX)",
            FormatId::Xlsx => "Excel (XLSX)",
            FormatId::Pptx => "PowerPoint (PPTX)",
            FormatId::Odt => "OpenDocument text",
            FormatId::Ods => "OpenDocument spreadsheet",
            FormatId::Odp => "OpenDocument presentation",
            FormatId::Epub => "EPUB",
            FormatId::Doc => "Word 97-2003 (DOC)",
            FormatId::Xls => "Excel 97-2003 (XLS)",
            FormatId::Ppt => "PowerPoint 97-2003 (PPT)",
            FormatId::Image => "Image",
            FormatId::OleCompound => "Legacy Microsoft Office or password-protected Office",
            FormatId::Unknown => "Unrecognised",
        }
    }
}

/// Selects the viewer on the Kotlin side.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DocumentKind {
    /// Flowing text: plain text, word processing, e-books, Markdown, code.
    Text,
    /// Sheets of numbered rows.
    Spreadsheet,
    /// Slides, each introduced by a [`Block::Section`].
    Presentation,
    /// Fixed-layout pages rendered natively by the platform; the engine
    /// validates the file but extracts no blocks.
    Pdf,
    /// A listing of archive entries; nothing is extracted.
    Archive,
    /// One picture, served whole as a resource and decoded by the platform.
    Image,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DocumentInfo {
    pub format: FormatId,
    pub kind: DocumentKind,
    pub byte_size: u64,
    /// Character encoding of text-based formats, e.g. `utf-8`.
    pub encoding: Option<&'static str>,
}

/// Inline style flags of a [`Span`].
pub mod style {
    pub const BOLD: u8 = 1;
    pub const ITALIC: u8 = 2;
    pub const UNDERLINE: u8 = 4;
    pub const STRIKE: u8 = 8;
    pub const CODE: u8 = 16;
}

/// Styling over `start..end` of a block's text. Offsets count UTF-16 code
/// units, the unit of Kotlin and Swift strings, so the UI applies spans
/// without re-scanning the text.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Span {
    pub start: u32,
    pub end: u32,
    /// Bitwise OR of [`style`] flags.
    pub style: u8,
    pub link: Option<String>,
    /// ARGB text colour, where it differs from the block's own.
    pub color: Option<u32>,
}

/// Text with inline styling.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Rich {
    pub text: String,
    pub spans: Vec<Span>,
}

const SPAN_COST: usize = 16;
/// Spans kept per [`Block::CellRuns`].
pub const MAX_CELL_RUNS: usize = 64;
/// Fixed cost of an image block; at most `Limits::MIN_BATCH_BYTES`, so one
/// stripped of its path and alt text always fits a batch.
const IMAGE_COST: usize = 5;

impl Rich {
    pub fn plain(text: impl Into<String>) -> Self {
        Self { text: text.into(), spans: Vec::new() }
    }

    fn cost(&self) -> usize {
        self.text.len() + self.spans.iter().map(|s| SPAN_COST + s.link.as_ref().map_or(0, String::len)).sum::<usize>()
    }

    /// Splits so the head costs at most `max` bytes. Spans are clipped to
    /// each half; a head too small to hold its spans keeps only its text.
    fn split(self, max: usize) -> (Rich, Rich) {
        let span_budget = (self.cost() - self.text.len()).min(max / 2);
        let mut cut = floor_char_boundary(&self.text, max - span_budget);
        if cut == 0 {
            cut = self.text.chars().next().map_or(0, char::len_utf8);
        }
        let cut16 = self.text[..cut].encode_utf16().count() as u32;
        let mut head_spans = Vec::new();
        let mut rest_spans = Vec::new();
        for span in self.spans {
            if span.start < cut16 {
                head_spans.push(Span { end: span.end.min(cut16), ..span.clone() });
            }
            if span.end > cut16 {
                rest_spans.push(Span { start: span.start.max(cut16) - cut16, end: span.end - cut16, ..span });
            }
        }
        let mut text = self.text;
        let rest = text.split_off(cut);
        let mut head = Rich { text, spans: head_spans };
        if head.cost() > max {
            head.spans.clear();
        }
        (head, Rich { text: rest, spans: rest_spans })
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Block {
    /// `level` 1 is the most prominent.
    Heading { level: u8, text: Rich },
    /// A paragraph. Plain-text files produce one per line. Text that does not
    /// fit one batch continues in the next paragraph.
    Paragraph { text: Rich },
    /// A list item; `depth` 0 is the outermost list. `marker` is the visible
    /// label (`"3."`, `"☑"`), or `None` for a plain bullet.
    ListItem { depth: u8, marker: Option<String>, text: Rich },
    /// A quotation or call-out.
    Quote { text: Rich },
    /// Preformatted text. `first_line` numbers the lines of source files; it
    /// is `None` for code blocks inside documents.
    Code { language: Option<String>, first_line: Option<u32>, text: String },
    /// A horizontal rule.
    Divider,
    /// One table or spreadsheet row. `row` is the spreadsheet's 1-based row
    /// number (rows may be skipped); `None` for tables in flowing documents.
    /// `header` marks column-heading rows. `styles` gives each cell's index
    /// into the [`Block::CellStyle`]s sent before, 0 being plain; it may be
    /// shorter than `cells` (the rest are plain) or longer, for empty cells
    /// that are only coloured. Empty for unstyled rows. `cell_spans` gives each
    /// cell's styling and links, offsets in that cell's text; empty when the
    /// source has none, or it may be shorter than `cells`.
    TableRow { row: Option<u32>, header: bool, cells: Vec<String>, styles: Vec<u16>, cell_spans: Vec<Vec<Span>> },
    /// Entry `index` (from 1) of the workbook's cell-style palette, sent
    /// before the rows that use it.
    CellStyle { index: u16, style: CellStyle },
    /// Cells of the current sheet shown as one, its top-left cell's value
    /// across them. Rows count from 1, as [`Block::TableRow::row`]; columns
    /// from 0. Sent after the sheet's rows.
    Merge { first_row: u32, first_column: u32, last_row: u32, last_column: u32 },
    /// Differently styled runs inside one spreadsheet cell, such as one word
    /// in red. `row` counts from 1, as [`Block::TableRow::row`]; `column`
    /// from 0. Sent right after its row; at most [`MAX_CELL_RUNS`] spans.
    CellRuns { row: u32, column: u32, spans: Vec<Span> },
    /// How many slides (or other sections) the whole document has, sent
    /// first by sources that know it before reading them all, so a viewer
    /// can show "13 / 60" while later ones are still loading.
    Count { total: u32 },
    /// Start of a sheet, slide or chapter.
    Section { title: String },
    /// One archive entry. Directories have `size` 0.
    Entry { path: String, size: u64, is_dir: bool },
    /// A picture in the reading flow. `path` is a container path, fetched
    /// separately through `Session::resource`; `None` means nothing can be
    /// loaded (a remote, unsupported or missing target), so only a label
    /// shows. `width` and `height` are the intended size in points, if known.
    Image { path: Option<String>, alt: Option<String>, width: Option<f32>, height: Option<f32> },
    /// The canvas of the current slide (after its `Section`), in points.
    /// `background_image` and shape images are container paths, fetched
    /// separately through `Session::resource`.
    Slide {
        width: f32,
        height: f32,
        background: Option<u32>,
        background_image: Option<String>,
        transition: Option<String>,
    },
    /// One positioned element of the current slide.
    Shape(Box<Shape>),
}

/// How a spreadsheet cell looks.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct CellStyle {
    /// Bitwise OR of [`style`] flags.
    pub flags: u8,
    /// ARGB text colour; `None` for the default.
    pub color: Option<u32>,
    /// ARGB background; `None` for none.
    pub fill: Option<u32>,
    /// Font size in points; `None` for the sheet's usual size.
    pub size: Option<f32>,
    /// 0 as the value suggests (numbers right), 1 left, 2 centre, 3 right.
    pub align: u8,
    /// Text wraps within the cell instead of spilling.
    pub wrap: bool,
}

/// Outline of a slide element.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Geometry {
    Rect,
    RoundRect,
    Ellipse,
    Line,
}

/// A paragraph inside a [`Shape`], with resolved formatting.
#[derive(Debug, Clone, PartialEq)]
pub struct ShapeText {
    pub text: Rich,
    /// Font size in points, after autofit scaling.
    pub size: f32,
    /// ARGB.
    pub color: Option<u32>,
    pub bold: bool,
    pub italic: bool,
    /// 0 left, 1 centre, 2 right, 3 justified.
    pub align: u8,
    /// Visible bullet or number, if any.
    pub bullet: Option<String>,
    pub level: u8,
}

/// When a shape appears during a presentation: on the `step`-th click
/// (0 = as the slide opens), with PowerPoint's preset effect id.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Build {
    pub step: u16,
    pub effect: u16,
}

/// A slide element with its position and size in points.
#[derive(Debug, Clone, PartialEq)]
pub struct Shape {
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
    /// Degrees clockwise.
    pub rotation: f32,
    pub geometry: Geometry,
    /// ARGB fill and outline; `None` is transparent.
    pub fill: Option<u32>,
    pub line: Option<u32>,
    pub line_width: f32,
    pub image: Option<String>,
    /// Embedded audio or video played from this shape; [`Self::image`] is its poster.
    pub media: Option<String>,
    pub paragraphs: Vec<ShapeText>,
    /// 0 top, 1 middle, 2 bottom.
    pub anchor: u8,
    /// Text insets in points: left, top, right, bottom.
    pub insets: [f32; 4],
    pub table: Vec<Vec<String>>,
    pub table_header: bool,
    pub build: Option<Build>,
}

impl Shape {
    fn cost(&self) -> usize {
        64 + self.image.as_ref().map_or(0, String::len)
            + self.media.as_ref().map_or(0, String::len)
            + self
                .paragraphs
                .iter()
                .map(|p| p.text.cost() + p.bullet.as_ref().map_or(0, String::len) + 16)
                .sum::<usize>()
            + self.table.iter().flatten().map(|c| c.len() + 1).sum::<usize>()
    }
}

impl Block {
    pub fn paragraph(text: impl Into<String>) -> Self {
        Block::Paragraph { text: Rich::plain(text) }
    }

    /// An image block with blank alt text and unusable size hints removed.
    /// Both come straight from the document, so they are not trusted.
    pub fn image(path: Option<String>, alt: Option<&str>, width: Option<f32>, height: Option<f32>) -> Self {
        let usable = |size: Option<f32>| size.filter(|s| s.is_finite() && *s > 0.0);
        let alt = alt.map(str::trim).filter(|a| !a.is_empty()).map(str::to_owned);
        Block::Image { path, alt, width: usable(width), height: usable(height) }
    }

    /// Bytes this block counts against the batch and session budgets.
    pub fn cost(&self) -> usize {
        // Each +1 keeps empty content from being free.
        match self {
            Block::Heading { text, .. } | Block::Paragraph { text } | Block::Quote { text } => text.cost() + 1,
            Block::ListItem { marker, text, .. } => text.cost() + marker.as_ref().map_or(0, String::len) + 1,
            Block::Code { language, text, .. } => text.len() + language.as_ref().map_or(0, String::len) + 5,
            Block::Divider => 1,
            Block::TableRow { cells, styles, cell_spans, .. } => {
                cells.iter().map(|c| c.len() + 1).sum::<usize>()
                    + 1
                    + styles.len() * 2
                    + cell_spans
                        .iter()
                        .flatten()
                        .map(|s| SPAN_COST + s.link.as_ref().map_or(0, String::len))
                        .sum::<usize>()
            }
            // Fixed, and at most `Limits::MIN_BATCH_BYTES`, so neither is ever split.
            Block::CellStyle { .. } | Block::Merge { .. } | Block::CellRuns { .. } | Block::Count { .. } => 5,
            Block::Section { title } => title.len() + 1,
            Block::Entry { path, .. } => path.len() + 9,
            Block::Image { path, alt, .. } => {
                path.as_ref().map_or(0, String::len) + alt.as_ref().map_or(0, String::len) + IMAGE_COST
            }
            Block::Slide { background_image, transition, .. } => {
                24 + background_image.as_ref().map_or(0, String::len) + transition.as_ref().map_or(0, String::len)
            }
            Block::Shape(shape) => shape.cost(),
        }
    }

    /// Splits this block so the first part costs at most `room` bytes
    /// (`room >= Limits::MIN_BATCH_BYTES`). Text continues in the returned
    /// remainder; rows, sections and entries are shortened instead, because a
    /// half row would be misleading.
    pub fn split_to_fit(self, room: usize) -> (Block, Option<Block>) {
        if self.cost() <= room {
            return (self, None);
        }
        match self {
            Block::Heading { level, text } => {
                let (head, rest) = text.split(room - 1);
                (Block::Heading { level, text: head }, Some(Block::Heading { level, text: rest }))
            }
            Block::Paragraph { text } => {
                let (head, rest) = text.split(room - 1);
                (Block::Paragraph { text: head }, Some(Block::Paragraph { text: rest }))
            }
            Block::Quote { text } => {
                let (head, rest) = text.split(room - 1);
                (Block::Quote { text: head }, Some(Block::Quote { text: rest }))
            }
            Block::ListItem { depth, marker, text } => {
                let marker_len = marker.as_ref().map_or(0, String::len);
                let (head, rest) = text.split((room - 1).saturating_sub(marker_len).max(4));
                (Block::ListItem { depth, marker, text: head }, Some(Block::Paragraph { text: rest }))
            }
            Block::Code { language, first_line, text } => {
                let language = language.filter(|l| l.len() + 5 < room / 2);
                let budget = room - 5 - language.as_ref().map_or(0, String::len);
                // Prefer to break between lines so line numbers stay right.
                let cut = floor_char_boundary(&text, budget);
                let cut = text[..cut].rfind('\n').map(|i| i + 1).filter(|&i| i > 0).unwrap_or(cut.max(1));
                let cut = floor_char_boundary(&text, cut).max(text.chars().next().map_or(0, char::len_utf8));
                let mut head = text;
                let rest = head.split_off(cut);
                let next_line = first_line.map(|n| n + head.matches('\n').count() as u32);
                (
                    Block::Code { language: language.clone(), first_line, text: head },
                    Some(Block::Code { language, first_line: next_line, text: rest }),
                )
            }
            Block::TableRow { row, header, mut cells, mut styles, mut cell_spans } => {
                // Only a row larger than a whole batch is split: its look goes first.
                styles.clear();
                cells.truncate((room - 1) / 2);
                while cells.iter().map(|c| c.len() + 1).sum::<usize>() + 1 > room {
                    let longest = cells.iter_mut().max_by_key(|c| c.len()).expect("cells is non-empty");
                    let keep = floor_char_boundary(longest, longest.len() / 2);
                    longest.truncate(keep);
                }
                // Spans keep their links, clipped to the text that is left in each cell.
                cell_spans.truncate(cells.len());
                for (spans, cell) in cell_spans.iter_mut().zip(&cells) {
                    let len16 = cell.encode_utf16().count() as u32;
                    spans.retain(|s| s.start < len16.min(s.end));
                    for span in spans.iter_mut() {
                        span.end = span.end.min(len16);
                    }
                }
                (Block::TableRow { row, header, cells, styles, cell_spans }, None)
            }
            Block::Section { mut title } => {
                title.truncate(floor_char_boundary(&title, room - 1));
                (Block::Section { title }, None)
            }
            Block::Entry { mut path, size, is_dir } => {
                path.truncate(floor_char_boundary(&path, room.saturating_sub(9)));
                (Block::Entry { path, size, is_dir }, None)
            }
            Block::Divider => (Block::Divider, None),
            // Alt text is shortened. A shortened path would name the wrong
            // entry, so a path that does not fit is dropped whole.
            Block::Image { mut path, mut alt, width, height } => {
                path = path.filter(|p| p.len() + IMAGE_COST <= room);
                let budget = room - IMAGE_COST - path.as_ref().map_or(0, String::len);
                if let Some(alt) = &mut alt {
                    alt.truncate(floor_char_boundary(alt, budget));
                }
                (Block::Image { path, alt, width, height }, None)
            }
            Block::Slide { .. }
            | Block::CellStyle { .. }
            | Block::Merge { .. }
            | Block::CellRuns { .. }
            | Block::Count { .. } => (self, None),
            // A shape is not split; trailing text is dropped until it fits.
            Block::Shape(mut shape) => {
                shape.table.clear();
                while shape.cost() > room && shape.paragraphs.pop().is_some() {}
                (Block::Shape(shape), None)
            }
        }
    }
}

fn floor_char_boundary(s: &str, index: usize) -> usize {
    let mut index = index.min(s.len());
    while !s.is_char_boundary(index) {
        index -= 1;
    }
    index
}

#[derive(Debug, Clone, PartialEq)]
pub struct ContentBatch {
    /// Zero-based position of this batch within the session.
    pub sequence: u32,
    pub blocks: Vec<Block>,
    /// No further batches follow.
    pub is_last: bool,
    /// The session stopped early because `max_total_output_bytes` was reached.
    pub truncated: bool,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn split_text_blocks_without_loss() {
        let text = "ééééé".to_owned(); // 10 bytes
        let (head, rest) = Block::paragraph(text.clone()).split_to_fit(6);
        assert!(head.cost() <= 6);
        let (Block::Paragraph { text: a }, Some(Block::Paragraph { text: b })) = (head, rest) else { panic!() };
        assert_eq!(a.text + &b.text, text);
    }

    #[test]
    fn spans_are_clipped_to_each_half() {
        let rich = Rich {
            text: "abcdefghij".repeat(10),
            spans: vec![Span { start: 40, end: 60, style: style::BOLD, link: None, color: None }],
        };
        let (head, rest) = Block::Paragraph { text: rich }.split_to_fit(61);
        let (Block::Paragraph { text: head }, Some(Block::Paragraph { text: rest })) = (head, rest) else { panic!() };
        let cut = head.text.len() as u32;
        assert_eq!(head.spans, [Span { start: 40, end: cut, style: style::BOLD, link: None, color: None }]);
        assert_eq!(rest.spans, [Span { start: 0, end: 60 - cut, style: style::BOLD, link: None, color: None }]);
    }

    #[test]
    fn code_splits_between_lines_and_keeps_numbering() {
        let text = (1..=20).map(|i| format!("line {i}\n")).collect::<String>();
        let block = Block::Code { language: None, first_line: Some(1), text: text.clone() };
        let (head, rest) = block.split_to_fit(40);
        let (Block::Code { text: a, .. }, Some(Block::Code { text: b, first_line, .. })) = (head, rest) else {
            panic!()
        };
        assert!(a.ends_with('\n'));
        assert_eq!(first_line, Some(1 + a.matches('\n').count() as u32));
        assert_eq!(a + &b, text);
    }

    #[test]
    fn oversized_rows_are_shortened_to_fit() {
        let row = Block::TableRow {
            row: Some(1),
            header: false,
            cells: vec!["x".repeat(100), "y".repeat(50), "z".into()],
            styles: Vec::new(),
            cell_spans: Vec::new(),
        };
        let (head, rest) = row.split_to_fit(40);
        assert!(head.cost() <= 40, "{}", head.cost());
        assert!(rest.is_none());
    }

    #[test]
    fn images_shorten_alt_and_never_cut_a_path() {
        let image = Block::image(Some("media/a.png".into()), Some(&"é".repeat(50)), Some(10.0), Some(5.0));
        let (head, rest) = image.clone().split_to_fit(40);
        assert!(head.cost() <= 40, "{}", head.cost());
        assert!(rest.is_none());
        let Block::Image { path, alt, width, .. } = head else { panic!() };
        assert_eq!(path.as_deref(), Some("media/a.png"));
        assert_eq!(alt.as_deref(), Some("é".repeat(12).as_str()));
        assert_eq!(width, Some(10.0));

        let (head, _) = image.split_to_fit(crate::Limits::MIN_BATCH_BYTES);
        assert_eq!(head, Block::Image { path: None, alt: Some(String::new()), width: Some(10.0), height: Some(5.0) });
        assert!(head.cost() <= crate::Limits::MIN_BATCH_BYTES);
    }

    #[test]
    fn image_ignores_blank_alt_and_unusable_sizes() {
        let image = Block::image(None, Some("  "), Some(f32::NAN), Some(-1.0));
        assert_eq!(image, Block::Image { path: None, alt: None, width: None, height: None });
        let image = Block::image(None, None, Some(f32::INFINITY), Some(0.0));
        assert_eq!(image, Block::Image { path: None, alt: None, width: None, height: None });
    }
}
