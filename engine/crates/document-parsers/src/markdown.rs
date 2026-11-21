//! Markdown (CommonMark + GitHub tables, task lists, strikethrough).
//!
//! The text is parsed whole by pulldown-cmark (callers cap it at 8 MiB) into
//! blocks that the batching driver then hands out. Raw HTML is never
//! rendered or executed: HTML blocks are dropped and inline tags ignored.
//! Images point at relative files or the network, and neither is ever read:
//! one alone in a paragraph becomes an image block with no path, which shows
//! as a labelled placeholder; one among other text shows its alt text inline.

use std::collections::VecDeque;

use document_core::style::{BOLD, CODE, ITALIC, STRIKE};
use document_core::{Block, CancelToken, Result, Rich};
use pulldown_cmark::{CodeBlockKind, Event, HeadingLevel, Options, Parser, Tag, TagEnd};

use crate::rich::RichBuilder;
use crate::stream::BlockSource;

const MAX_LIST_DEPTH: u8 = 16;
const MAX_COLUMNS: usize = 64;

pub(crate) struct MarkdownSource {
    blocks: VecDeque<Block>,
    /// Kept for the rendered page ([`crate::markdown_html`]).
    text: String,
}

impl MarkdownSource {
    pub fn new(text: &str, cancel: &CancelToken) -> Result<Self> {
        let options = Options::ENABLE_TABLES | Options::ENABLE_STRIKETHROUGH | Options::ENABLE_TASKLISTS;
        let mut builder = Converter::default();
        for (i, event) in Parser::new_ext(text, options).enumerate() {
            if i % 4096 == 0 {
                cancel.check()?;
            }
            builder.event(event);
        }
        builder.flush();
        Ok(Self { blocks: builder.out, text: text.to_owned() })
    }
}

impl BlockSource for MarkdownSource {
    fn pump(&mut self, out: &mut VecDeque<Block>) -> Result<bool> {
        if self.blocks.is_empty() {
            return Ok(true);
        }
        out.extend(self.blocks.drain(..self.blocks.len().min(256)));
        Ok(false)
    }

    /// The file rendered as a page, as Android Studio's preview shows it.
    fn resource(&mut self, name: &str) -> Result<Vec<u8>> {
        if name == crate::markdown_html::PREVIEW_RESOURCE {
            return Ok(crate::markdown_html::render(&self.text));
        }
        Err(document_core::EngineError::unsupported("this document has no embedded resources"))
    }
}

enum ListKind {
    Bullet,
    Ordered(u64),
}

#[derive(Default)]
struct Converter {
    out: VecDeque<Block>,
    text: RichBuilder,
    heading: Option<u8>,
    bold: u32,
    italic: u32,
    strike: u32,
    links: Vec<String>,
    lists: Vec<ListKind>,
    /// Marker of the list item whose first paragraph has not been emitted.
    pending_marker: Option<Option<String>>,
    in_item: u32,
    quote: u32,
    code: Option<(Option<String>, String)>,
    table: Option<Table>,
    image_alt: Option<String>,
    /// Alt text of an image that is all this top-level paragraph holds so far.
    lone_image: Option<String>,
}

#[derive(Default)]
struct Table {
    in_head: bool,
    cells: Vec<String>,
    cell: String,
}

impl Converter {
    fn style(&self) -> u8 {
        (if self.bold > 0 { BOLD } else { 0 })
            | (if self.italic > 0 { ITALIC } else { 0 })
            | (if self.strike > 0 { STRIKE } else { 0 })
    }

    fn push_text(&mut self, text: &str, extra: u8) {
        if let Some(alt) = &mut self.image_alt {
            alt.push_str(text);
        } else if let Some(table) = &mut self.table {
            table.cell.push_str(text);
        } else {
            self.lone_image = None;
            let style = self.style() | extra;
            let link = self.links.last().map(String::as_str);
            self.text.push(text, style, link);
        }
    }

    /// Emits the text gathered so far as a heading, list item, quote or paragraph.
    fn flush(&mut self) {
        if self.text.is_empty() {
            return;
        }
        let text: Rich = self.text.take_trimmed();
        if text.text.is_empty() {
            return;
        }
        let block = if let Some(level) = self.heading {
            Block::Heading { level, text }
        } else if self.in_item > 0 {
            let depth = (self.lists.len().saturating_sub(1) as u8).min(MAX_LIST_DEPTH);
            match self.pending_marker.take() {
                Some(marker) => Block::ListItem { depth, marker, text },
                // Later paragraphs of the same item: indented, no marker.
                None => Block::ListItem { depth, marker: Some(String::new()), text },
            }
        } else if self.quote > 0 {
            Block::Quote { text }
        } else {
            Block::Paragraph { text }
        };
        self.out.push_back(block);
    }

    fn event(&mut self, event: Event<'_>) {
        match event {
            Event::Start(tag) => self.start(tag),
            Event::End(tag) => self.end(tag),
            Event::Text(text) => {
                if let Some((_, code)) = &mut self.code {
                    code.push_str(&text);
                } else {
                    self.push_text(&text, 0);
                }
            }
            Event::Code(text) => self.push_text(&text, CODE),
            Event::SoftBreak => self.push_text(" ", 0),
            Event::HardBreak => self.push_text("\n", 0),
            Event::Rule => {
                self.flush();
                self.out.push_back(Block::Divider);
            }
            Event::TaskListMarker(done) => {
                if let Some(marker) = &mut self.pending_marker {
                    *marker = Some(if done { "☑" } else { "☐" }.to_owned());
                }
            }
            // Raw HTML, math and footnote references are not rendered.
            _ => {}
        }
    }

    fn start(&mut self, tag: Tag<'_>) {
        match tag {
            Tag::Heading { level, .. } => {
                self.flush();
                self.heading = Some(heading_level(level));
            }
            Tag::Paragraph => self.flush(),
            Tag::BlockQuote(_) => {
                self.flush();
                self.quote += 1;
            }
            Tag::CodeBlock(kind) => {
                self.flush();
                let language = match kind {
                    CodeBlockKind::Fenced(info) => info.split_whitespace().next().map(str::to_owned),
                    CodeBlockKind::Indented => None,
                };
                self.code = Some((language.filter(|l| !l.is_empty()), String::new()));
            }
            Tag::List(start) => {
                self.flush();
                self.lists.push(start.map_or(ListKind::Bullet, ListKind::Ordered));
            }
            Tag::Item => {
                self.flush();
                self.in_item += 1;
                let marker = match self.lists.last_mut() {
                    Some(ListKind::Ordered(n)) => {
                        let marker = format!("{n}.");
                        *n += 1;
                        Some(marker)
                    }
                    _ => None,
                };
                self.pending_marker = Some(marker);
            }
            Tag::Table(_) => {
                self.flush();
                self.table = Some(Table::default());
            }
            Tag::TableHead => {
                if let Some(table) = &mut self.table {
                    table.in_head = true;
                }
            }
            Tag::TableCell => {
                if let Some(table) = &mut self.table {
                    table.cell.clear();
                }
            }
            Tag::Emphasis => self.italic += 1,
            Tag::Strong => self.bold += 1,
            Tag::Strikethrough => self.strike += 1,
            Tag::Link { dest_url, .. } => self.links.push(dest_url.into_string()),
            Tag::Image { .. } => self.image_alt = Some(String::new()),
            _ => {}
        }
    }

    fn end(&mut self, tag: TagEnd) {
        match tag {
            TagEnd::Heading(_) => {
                self.flush();
                self.heading = None;
            }
            TagEnd::Paragraph => match self.lone_image.take() {
                Some(alt) => {
                    self.text.take();
                    self.out.push_back(Block::image(None, Some(&alt), None, None));
                }
                None => self.flush(),
            },
            TagEnd::BlockQuote(_) => {
                self.flush();
                self.quote = self.quote.saturating_sub(1);
            }
            TagEnd::CodeBlock => {
                if let Some((language, mut text)) = self.code.take() {
                    if text.ends_with('\n') {
                        text.pop();
                    }
                    self.out.push_back(Block::Code { language, first_line: None, text });
                }
            }
            TagEnd::List(_) => {
                self.flush();
                self.lists.pop();
            }
            TagEnd::Item => {
                self.flush();
                self.in_item = self.in_item.saturating_sub(1);
                self.pending_marker = None;
            }
            TagEnd::TableCell => {
                if let Some(table) = &mut self.table
                    && table.cells.len() < MAX_COLUMNS
                {
                    let cell = std::mem::take(&mut table.cell).trim().to_owned();
                    table.cells.push(cell);
                }
            }
            // The head row's cells arrive directly inside TableHead.
            TagEnd::TableHead => self.end_row(true),
            TagEnd::TableRow => self.end_row(false),
            TagEnd::Table => self.table = None,
            TagEnd::Emphasis => self.italic = self.italic.saturating_sub(1),
            TagEnd::Strong => self.bold = self.bold.saturating_sub(1),
            TagEnd::Strikethrough => self.strike = self.strike.saturating_sub(1),
            TagEnd::Link => {
                self.links.pop();
            }
            TagEnd::Image => {
                if let Some(alt) = self.image_alt.take() {
                    let alone = self.text.is_empty()
                        && self.table.is_none()
                        && self.links.is_empty()
                        && self.heading.is_none()
                        && self.in_item == 0
                        && self.quote == 0;
                    let label = if alt.trim().is_empty() { "Image" } else { &alt };
                    self.push_text(&format!("🖼 {label}"), ITALIC);
                    if alone {
                        self.lone_image = Some(alt);
                    }
                }
            }
            _ => {}
        }
    }

    fn end_row(&mut self, header: bool) {
        if let Some(table) = &mut self.table {
            let cells = std::mem::take(&mut table.cells);
            if header {
                table.in_head = false;
            }
            self.out.push_back(Block::TableRow { row: None, header, cells, styles: Vec::new() });
        }
    }
}

fn heading_level(level: HeadingLevel) -> u8 {
    match level {
        HeadingLevel::H1 => 1,
        HeadingLevel::H2 => 2,
        HeadingLevel::H3 => 3,
        HeadingLevel::H4 => 4,
        HeadingLevel::H5 => 5,
        HeadingLevel::H6 => 6,
    }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use document_core::style::{BOLD, CODE, ITALIC};
    use document_core::{Block, FormatId, Limits, Span};

    use crate::testing::texts;

    fn read(markdown: &str) -> Vec<Block> {
        let mut session = crate::engine(Limits::default())
            .open_named(Cursor::new(markdown.as_bytes().to_vec()), Some("README.md".into()))
            .unwrap();
        assert_eq!(session.info().format, FormatId::Markdown);
        std::iter::from_fn(|| session.next_batch().unwrap()).flat_map(|b| b.blocks).collect()
    }

    #[test]
    fn renders_structure() {
        let blocks = read(
            "# Title\n\nSome **bold** and *italic* and `code` [link](https://x.io).\n\n\
             - one\n- two\n  1. nested\n  2. second\n- [x] done\n\n\
             > quoted\n\n```rust\nfn main() {}\n```\n\n---\n\n\
             | Name | Qty |\n|:-----|----:|\n| Apple | 3 |\n| Pear | 10 |\n\n<div>raw html</div>\n",
        );
        assert_eq!(
            texts(&blocks),
            [
                "H1 Title",
                "P Some bold and italic and code link.",
                "L0 one",
                "L0 two",
                "L1[1.] nested",
                "L1[2.] second",
                "L0[☑] done",
                "Q quoted",
                "C[rust] fn main() {}",
                "---",
                "T Name|Qty",
                "R Apple|3",
                "R Pear|10",
            ]
        );
        let Block::Paragraph { text } = &blocks[1] else { panic!() };
        assert_eq!(
            text.spans,
            [
                Span { start: 5, end: 9, style: BOLD, link: None, color: None },
                Span { start: 14, end: 20, style: ITALIC, link: None, color: None },
                Span { start: 25, end: 29, style: CODE, link: None, color: None },
                Span { start: 30, end: 34, style: 0, link: Some("https://x.io".into()), color: None },
            ]
        );
    }

    #[test]
    fn images_are_labelled_and_their_targets_are_never_kept() {
        let blocks = read(
            "![A map](https://evil.example/map.png)\n\n\
             ![](../../etc/passwd)\n\n\
             Text ![inline](http://evil.example/a.png) more.\n\n\
             [![badge](https://evil.example/b.svg)](https://x.io)\n\n\
             ![one](a.png) ![two](b.png)\n\n\
             - ![in list](c.png)\n\n\
             # ![in heading](d.png)\n\n\
             | ![in cell](e.png) |\n|---|\n",
        );
        assert_eq!(
            texts(&blocks),
            [
                "PIC - [A map]",
                "PIC - []",
                "P Text 🖼 inline more.",
                "P 🖼 badge",
                "P 🖼 one 🖼 two",
                "L0 🖼 in list",
                "H1 🖼 in heading",
                "T 🖼 in cell",
            ]
        );
        let shown = format!("{blocks:?}");
        for target in ["evil.example/map", "passwd", "evil.example/a", "b.svg", "a.png", "c.png", "d.png", "e.png"] {
            assert!(!shown.contains(target), "{target}");
        }
    }
}
