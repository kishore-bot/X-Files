//! EPUB 2/3: chapters in reading (spine) order, streamed.
//!
//! XHTML block elements become headings, paragraphs, list items (numbered
//! for `<ol>`), quotes, code blocks, dividers and table rows (`<th>` rows as
//! headers); inline `strong`/`em`/`u`/`del`/`code` and safe `<a href>` links
//! are kept as styling. Whitespace is collapsed as a browser would. Scripts,
//! styles and embedded SVG are skipped and never executed. `<img>` becomes an
//! image block in reading order, fetched on demand through
//! [`BlockSource::resource`], except inside tables, where it is skipped. CSS
//! layout and DRM-protected books are not supported.

use std::collections::{HashMap, VecDeque};

use document_core::style::{BOLD, CODE, ITALIC, STRIKE, UNDERLINE};
use document_core::{Block, EngineError, Result};

use crate::ooxml::{is_safe_link, resolve, resolve_inside};
use crate::rich::RichBuilder;
use crate::stream::{BlockSource, MAX_PENDING_TEXT, Parts};
use crate::xml::{Tag, Xml};

pub(crate) struct EpubSource {
    parts: Parts,
    /// The package file, and the book read from it once the reader asks.
    package: String,
    book: Option<crate::epub_book::Book>,
    chapters: VecDeque<String>,
    /// Directory of the current chapter, which `<img src>` is relative to.
    chapter_dir: String,
    in_chapter: bool,
    in_body: bool,
    text: RichBuilder,
    heading: Option<u8>,
    /// One entry per open list: the next number for `<ol>`, `None` for `<ul>`.
    lists: Vec<Option<u32>>,
    in_list_item: bool,
    quote_depth: u32,
    pre_depth: u32,
    /// Counts of open inline style elements, indexed like the style bits.
    styles: [u32; 5],
    links: Vec<Option<String>>,
    row: Option<Row>,
}

struct Row {
    cells: Vec<String>,
    header: bool,
}

impl EpubSource {
    pub fn open(mut parts: Parts) -> Result<Self> {
        if has_encrypted_content(&mut parts)? {
            return Err(EngineError::unsupported("DRM-protected e-books are not supported"));
        }
        let package = read_rootfile(&mut parts)?;
        let chapters = read_spine(&mut parts, &package)?;
        Ok(Self {
            parts,
            package,
            book: None,
            chapters,
            chapter_dir: String::new(),
            in_chapter: false,
            in_body: false,
            text: RichBuilder::default(),
            heading: None,
            lists: Vec::new(),
            in_list_item: false,
            quote_depth: 0,
            pre_depth: 0,
            styles: [0; 5],
            links: Vec::new(),
            row: None,
        })
    }

    fn style(&self) -> u8 {
        [BOLD, ITALIC, UNDERLINE, STRIKE, CODE]
            .iter()
            .zip(self.styles)
            .filter(|(_, count)| *count > 0)
            .fold(0, |acc, (bit, _)| acc | bit)
    }

    fn style_index(name: &str) -> Option<usize> {
        match name {
            "b" | "strong" => Some(0),
            "i" | "em" | "cite" | "var" | "dfn" => Some(1),
            "u" | "ins" => Some(2),
            "s" | "del" | "strike" => Some(3),
            "code" | "kbd" | "samp" | "tt" => Some(4),
            _ => None,
        }
    }

    /// Emits the text gathered so far as one block.
    fn flush(&mut self, out: &mut VecDeque<Block>) {
        if self.text.is_empty() {
            return;
        }
        if self.pre_depth > 0 {
            let text = self.text.take().text;
            if !text.trim().is_empty() {
                out.push_back(Block::Code {
                    language: None,
                    first_line: None,
                    text: text.trim_matches('\n').to_owned(),
                });
            }
            return;
        }
        let text = self.text.take_trimmed();
        if text.text.is_empty() {
            return;
        }
        if let Some(row) = &mut self.row {
            if let Some(cell) = row.cells.last_mut() {
                if !cell.is_empty() {
                    cell.push('\n');
                }
                cell.push_str(&text.text);
            }
            return;
        }
        let block = match (self.heading, self.in_list_item) {
            (Some(level), _) => Block::Heading { level, text },
            (None, true) => {
                let depth = (self.lists.len().saturating_sub(1)).min(15) as u8;
                let marker = match self.lists.last_mut() {
                    Some(Some(n)) => {
                        let marker = format!("{n}.");
                        *n += 1;
                        Some(marker)
                    }
                    _ => None,
                };
                // Further paragraphs of the same item continue without a marker.
                self.in_list_item = false;
                Block::ListItem { depth, marker, text }
            }
            (None, false) if self.quote_depth > 0 => Block::Quote { text },
            (None, false) => Block::Paragraph { text },
        };
        out.push_back(block);
    }

    /// Ends the text before the picture, so the two stay in reading order.
    fn on_image(&mut self, tag: &Tag, out: &mut VecDeque<Block>) {
        self.flush(out);
        if !self.in_body || self.row.is_some() {
            return;
        }
        let path = tag
            .attr("src")
            .and_then(|src| src.split(['#', '?']).next())
            .and_then(|src| resolve_inside(&self.chapter_dir, src))
            .and_then(|path| self.parts.picture_path(&path));
        let size = |name| tag.attr(name).and_then(css_points);
        out.push_back(Block::image(path, tag.attr("alt"), size("width"), size("height")));
    }

    /// Audio or video: shown as a picture block whose path is the media file,
    /// which the app plays. The element's fallback text is skipped.
    fn on_media(&mut self, tag: &Tag, out: &mut VecDeque<Block>) {
        self.flush(out);
        if !self.in_body || self.row.is_some() {
            return;
        }
        let path = tag
            .attr("src")
            .and_then(|src| src.split(['#', '?']).next())
            .and_then(|src| resolve_inside(&self.chapter_dir, src))
            .and_then(|path| self.parts.media_path(&path));
        let label = if tag.name == "video" { "Video" } else { "Audio" };
        out.push_back(Block::image(path, Some(label), None, None));
    }

    fn on_start(&mut self, tag: &Tag, out: &mut VecDeque<Block>) -> Result<()> {
        if let Some(index) = Self::style_index(&tag.name) {
            self.styles[index] += 1;
            return Ok(());
        }
        match tag.name.as_str() {
            "body" => self.in_body = true,
            "head" | "script" | "style" | "svg" | "math" | "noscript" | "template" => {
                self.parts.xml().skip_element()?
            }
            "h1" | "h2" | "h3" | "h4" | "h5" | "h6" => {
                self.flush(out);
                self.heading = tag.name[1..].parse().ok();
            }
            "ul" | "ol" => {
                self.flush(out);
                let start = tag.attr("start").and_then(|s| s.parse().ok()).unwrap_or(1);
                self.lists.push((tag.name == "ol").then_some(start));
            }
            "li" => {
                self.flush(out);
                self.in_list_item = true;
            }
            "blockquote" => {
                self.flush(out);
                self.quote_depth += 1;
            }
            "pre" => {
                self.flush(out);
                self.pre_depth += 1;
            }
            "a" => self.links.push(tag.attr("href").filter(|h| is_safe_link(h)).map(str::to_owned)),
            "tr" => {
                self.flush(out);
                self.row = Some(Row { cells: Vec::new(), header: false });
            }
            "td" | "th" => {
                self.flush(out);
                if let Some(row) = &mut self.row {
                    row.cells.push(String::new());
                    row.header |= tag.name == "th";
                }
            }
            "hr" => {
                self.flush(out);
                out.push_back(Block::Divider);
            }
            "img" => self.on_image(tag, out),
            "video" | "audio" => {
                self.on_media(tag, out);
                self.parts.xml().skip_element()?
            }
            name if is_block(name) => self.flush(out),
            "br" => self.text.push_plain("\n"),
            _ => {}
        }
        Ok(())
    }

    fn on_end(&mut self, name: &str, out: &mut VecDeque<Block>) {
        if let Some(index) = Self::style_index(name) {
            self.styles[index] = self.styles[index].saturating_sub(1);
            return;
        }
        match name {
            "h1" | "h2" | "h3" | "h4" | "h5" | "h6" => {
                self.flush(out);
                self.heading = None;
            }
            "li" => {
                self.flush(out);
                self.in_list_item = false;
            }
            "ul" | "ol" => {
                self.flush(out);
                self.lists.pop();
            }
            "blockquote" => {
                self.flush(out);
                self.quote_depth = self.quote_depth.saturating_sub(1);
            }
            "pre" => {
                self.flush(out);
                self.pre_depth = self.pre_depth.saturating_sub(1);
            }
            "a" => {
                self.links.pop();
            }
            "td" | "th" => self.flush(out),
            "tr" => {
                self.flush(out);
                if let Some(row) = self.row.take().filter(|r| r.cells.iter().any(|s| !s.is_empty())) {
                    out.push_back(Block::TableRow {
                        row: None,
                        header: row.header,
                        cells: row.cells,
                        styles: Vec::new(),
                    });
                }
            }
            "body" => {
                self.flush(out);
                self.in_body = false;
            }
            name if is_block(name) => self.flush(out),
            _ => {}
        }
    }
}

/// An `<img>` width or height: a CSS pixel count, which is 3/4 of a point.
/// Percentages and other units say nothing about the picture's own size.
fn css_points(value: &str) -> Option<f32> {
    let value = value.trim();
    let pixels: f32 = value.strip_suffix("px").unwrap_or(value).parse().ok()?;
    Some(pixels * 0.75)
}

fn is_block(name: &str) -> bool {
    matches!(
        name,
        "p" | "div"
            | "section"
            | "article"
            | "header"
            | "footer"
            | "aside"
            | "nav"
            | "figure"
            | "figcaption"
            | "dt"
            | "dd"
            | "table"
            | "caption"
    )
}

impl BlockSource for EpubSource {
    fn pump(&mut self, out: &mut VecDeque<Block>) -> Result<bool> {
        if !self.in_chapter {
            loop {
                let Some(path) = self.chapters.pop_front() else {
                    self.parts.close();
                    return Ok(true);
                };
                if self.parts.archive().find(&path).is_some() {
                    self.parts.open(&path)?;
                    self.chapter_dir = path.rsplit_once('/').map_or("", |(dir, _)| dir).to_owned();
                    self.in_chapter = true;
                    break;
                }
            }
        }
        let before = out.len();
        while out.len() == before {
            match self.parts.xml().next()? {
                Xml::Start(tag) => self.on_start(&tag, out)?,
                Xml::Empty(tag) if tag.name == "br" => self.text.push_plain("\n"),
                Xml::Empty(tag) if tag.name == "hr" => {
                    self.flush(out);
                    out.push_back(Block::Divider);
                }
                Xml::Empty(tag) if tag.name == "img" => self.on_image(&tag, out),
                Xml::Empty(tag) if matches!(tag.name.as_str(), "video" | "audio") => self.on_media(&tag, out),
                Xml::Text(text) if self.in_body => {
                    let style = self.style();
                    let link = self.links.iter().rev().find_map(Option::as_deref).map(str::to_owned);
                    if self.pre_depth > 0 {
                        self.text.push(&text, 0, None);
                    } else {
                        self.text.push_collapsed(&text, style, link.as_deref());
                    }
                    if self.text.len() > MAX_PENDING_TEXT {
                        self.flush(out);
                    }
                }
                Xml::End(name) => self.on_end(&name, out),
                Xml::Eof => {
                    self.flush(out);
                    self.parts.close();
                    self.in_chapter = false;
                    return Ok(false);
                }
                _ => {}
            }
        }
        Ok(false)
    }

    fn resource(&mut self, name: &str) -> Result<Vec<u8>> {
        use crate::epub_book::{FILE_PREFIX, MANIFEST_RESOURCE, READER_RESOURCE};
        if name == MANIFEST_RESOURCE || name == READER_RESOURCE {
            if self.book.is_none() {
                self.book = Some(crate::epub_book::read_book(&mut self.parts, &self.package)?);
            }
            let book = self.book.as_ref().expect("read just above");
            let text = if name == MANIFEST_RESOURCE {
                crate::epub_book::manifest_json(book)
            } else {
                crate::epub_book::reader_page(book)
            };
            return Ok(text.into_bytes());
        }
        if let Some(path) = name.strip_prefix(FILE_PREFIX) {
            return self.parts.file(path);
        }
        self.parts.picture(name)
    }
}

/// Whether `encryption.xml` covers readable content. Books often list only
/// obfuscated fonts there, which does not prevent reading the text.
fn has_encrypted_content(parts: &mut Parts) -> Result<bool> {
    if parts.archive().find("META-INF/encryption.xml").is_none() {
        return Ok(false);
    }
    let xml = parts.open("META-INF/encryption.xml")?;
    let mut encrypted = false;
    loop {
        match xml.next()? {
            Xml::Start(tag) | Xml::Empty(tag) if tag.name == "CipherReference" => {
                let uri = tag.attr("URI").unwrap_or_default().to_ascii_lowercase();
                encrypted |= [".xhtml", ".html", ".htm", ".opf", ".xml"].iter().any(|ext| uri.ends_with(ext));
            }
            Xml::Eof => break,
            _ => {}
        }
    }
    parts.close();
    Ok(encrypted)
}

fn read_rootfile(parts: &mut Parts) -> Result<String> {
    let xml = parts.open("META-INF/container.xml")?;
    let mut rootfile = None;
    loop {
        match xml.next()? {
            Xml::Start(tag) | Xml::Empty(tag) if tag.name == "rootfile" && rootfile.is_none() => {
                rootfile = tag.attr("full-path").map(|p| resolve("", p));
            }
            Xml::Eof => break,
            _ => {}
        }
    }
    parts.close();
    rootfile.ok_or_else(|| EngineError::malformed("the e-book does not name its package file"))
}

/// XHTML documents in spine order, as archive paths.
fn read_spine(parts: &mut Parts, package: &str) -> Result<VecDeque<String>> {
    let base = package.rsplit_once('/').map_or("", |(dir, _)| dir);
    let xml = parts.open(package)?;
    let mut manifest = HashMap::new();
    let mut spine = Vec::new();
    loop {
        match xml.next()? {
            Xml::Start(tag) | Xml::Empty(tag) if tag.name == "item" => {
                if let (Some(id), Some(href)) = (tag.attr("id"), tag.attr("href")) {
                    let html = matches!(tag.attr("media-type"), Some("application/xhtml+xml" | "text/html"));
                    manifest.insert(id.to_owned(), (resolve(base, href), html));
                }
            }
            Xml::Start(tag) | Xml::Empty(tag) if tag.name == "itemref" => {
                if tag.attr("linear") != Some("no")
                    && let Some(id) = tag.attr("idref")
                {
                    spine.push(id.to_owned());
                }
            }
            Xml::Eof => break,
            _ => {}
        }
    }
    parts.close();
    let chapters: VecDeque<String> = spine
        .iter()
        .filter_map(|id| manifest.get(id))
        .filter(|(_, html)| *html)
        .map(|(path, _)| path.split('#').next().unwrap_or(path).to_owned())
        .collect();
    if chapters.is_empty() {
        return Err(EngineError::malformed("the e-book has no readable chapters"));
    }
    Ok(chapters)
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use crate::testing::{open_zip, texts};
    use crate::zip::testing::zip_with;
    use document_core::{Block, ErrorKind, FormatId, Limits};

    fn book(extra: &[(&str, &[u8])]) -> Vec<u8> {
        let container = br#"<container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"#;
        let opf = br#"<package><manifest>
<item id="c2" href="text/ch%202.xhtml" media-type="application/xhtml+xml"/>
<item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
<item id="css" href="style.css" media-type="text/css"/></manifest>
<spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>"#;
        let ch1 = br#"<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title><style>p{}</style></head>
<body><h1>Chapter   One</h1><p>It was a <em>dark</em>
   and stormy&nbsp;night&#8230;</p><script>alert(1)</script><ul><li>first</li><li>second<ul><li>inner</li></ul></li></ul></body></html>"#;
        let ch2 = br#"<html><body><div>Two<br/>lines</div><table><tr><th>K</th><td>V</td></tr></table></body></html>"#;
        let mut files: Vec<(&str, &[u8])> = vec![
            ("mimetype", b"application/epub+zip"),
            ("META-INF/container.xml", container),
            ("OEBPS/content.opf", opf),
            ("OEBPS/text/ch1.xhtml", ch1),
            ("OEBPS/text/ch 2.xhtml", ch2),
        ];
        files.extend_from_slice(extra);
        zip_with(&files, false)
    }

    #[test]
    fn reads_chapters_in_spine_order() {
        let (format, blocks) = open_zip(book(&[]));
        assert_eq!(format, FormatId::Epub);
        assert_eq!(
            texts(&blocks),
            [
                "H1 Chapter One",
                "P It was a dark and stormy\u{a0}night…",
                "L0 first",
                "L0 second",
                "L1 inner",
                "P Two\nlines",
                "T K|V",
            ]
        );
    }

    #[test]
    fn refuses_drm_but_allows_font_obfuscation() {
        let fonts = br#"<encryption><EncryptedData><CipherData><CipherReference URI="OEBPS/fonts/a.otf"/></CipherData></EncryptedData></encryption>"#;
        assert_eq!(open_zip(book(&[("META-INF/encryption.xml", fonts)])).0, FormatId::Epub);

        let drm = br#"<encryption><EncryptedData><CipherData><CipherReference URI="OEBPS/text/ch1.xhtml"/></CipherData></EncryptedData></encryption>"#;
        let err = crate::testing::open_zip_err(book(&[("META-INF/encryption.xml", drm)]));
        assert_eq!(err.kind, ErrorKind::UnsupportedFormat);
    }

    const PNG: &[u8] = b"\x89PNG fake";

    /// A book with one chapter at `OEBPS/text/ch.xhtml`.
    fn illustrated(body: &str) -> Vec<u8> {
        let container = br#"<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>"#;
        let opf = br#"<package><manifest><item id="c" href="text/ch.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>"#;
        let chapter = format!("<html><head><title>x</title></head><body>{body}</body></html>");
        zip_with(
            &[
                ("mimetype", b"application/epub+zip"),
                ("META-INF/container.xml", container),
                ("OEBPS/content.opf", opf),
                ("OEBPS/text/ch.xhtml", chapter.as_bytes()),
                ("OEBPS/images/a b.png", PNG),
                ("OEBPS/text/near.JPG", PNG),
                ("OEBPS/images/logo.svg", b"<svg/>"),
                ("OEBPS/media/clip.mp4", b"fake movie"),
                ("OEBPS/images/", b""),
                ("etc/passwd", b"root"),
            ],
            false,
        )
    }

    #[test]
    fn images_appear_in_reading_order_relative_to_the_chapter() {
        let body = r#"<p>Before <img src="../images/a%20b.png#frag" alt=" Map " width="200" height="100px"/> after</p>
<figure><img src="near.JPG?v=2" width="50%"></img><figcaption>Caption</figcaption></figure><img src="/OEBPS/images/a%20b.png" alt=""/>"#;
        let (_, blocks) = open_zip(illustrated(body));
        assert_eq!(
            texts(&blocks),
            [
                "P Before",
                "PIC OEBPS/images/a b.png [Map]",
                "P after",
                "PIC OEBPS/text/near.JPG []",
                "P Caption",
                "PIC OEBPS/images/a b.png []",
            ]
        );
        let Block::Image { width, height, .. } = &blocks[1] else { panic!() };
        assert_eq!((*width, *height), (Some(150.0), Some(75.0)));
        let Block::Image { width, .. } = &blocks[3] else { panic!() };
        assert_eq!(*width, None);
    }

    #[test]
    fn unservable_images_show_a_label_and_cannot_be_read() {
        let targets = [
            "../../../etc/passwd",
            "../../etc/passwd",
            "../../../etc/a.png",
            "%2e%2e/%2e%2e/%2e%2e/etc/a.png",
            "/etc/passwd",
            "http://evil.example/a.png",
            "//evil.example/a.png",
            "file:///etc/a.png",
            "data:image/png;base64,AAAA",
            "../images/logo.svg",
            "../images/missing.png",
            "../images/",
        ];
        let body: String = targets.iter().map(|t| format!(r#"<img src="{t}" alt="label"/>"#)).collect();
        let bytes = illustrated(&format!(r#"{body}<img alt="no source"/>"#));
        let (_, blocks) = open_zip(bytes.clone());
        let mut expected = vec!["PIC - [label]".to_owned(); targets.len()];
        expected.push("PIC - [no source]".to_owned());
        assert_eq!(texts(&blocks), expected);

        let mut session = crate::engine(Limits::default()).open(Cursor::new(bytes)).unwrap();
        assert_eq!(session.resource("OEBPS/images/a b.png").unwrap(), PNG);
        for name in ["etc/passwd", "OEBPS/text/ch.xhtml", "OEBPS/images/logo.svg", "OEBPS/images/none.png"] {
            assert!(session.resource(name).is_err(), "{name}");
        }
    }

    #[test]
    fn audio_and_video_become_playable_blocks_without_their_fallback_text() {
        let body = r#"<p>Watch</p><video controls="controls" src="../media/clip.mp4">Your reader cannot play video.</video><audio src="../media/missing.mp3"/><audio src="/etc/passwd.mp3">x</audio><p>End</p>"#;
        let bytes = illustrated(body);
        let (_, blocks) = open_zip(bytes.clone());
        assert_eq!(
            texts(&blocks),
            ["P Watch", "PIC OEBPS/media/clip.mp4 [Video]", "PIC - [Audio]", "PIC - [Audio]", "P End"]
        );
        let mut session = crate::engine(Limits::default()).open(Cursor::new(bytes)).unwrap();
        assert_eq!(session.resource("OEBPS/media/clip.mp4").unwrap(), b"fake movie");
        assert!(session.resource("OEBPS/text/ch.xhtml").is_err());
    }

    #[test]
    fn images_in_tables_and_outside_the_body_are_skipped() {
        let body = r#"<table><tr><td>Cell<img src="near.JPG" alt="x"/></td></tr></table><p>Text</p>"#;
        assert_eq!(texts(&open_zip(illustrated(body)).1), ["R Cell", "P Text"]);
    }

    #[test]
    fn images_can_be_read_in_the_middle_of_a_chapter() {
        let paragraphs: String =
            (0..20_000u32).map(|i| format!("<p>{:x}</p>", i.wrapping_mul(2_654_435_761))).collect();
        let bytes = illustrated(&format!(r#"<img src="near.JPG"/>{paragraphs}"#));
        let limits = Limits { max_batch_bytes: 512, ..Limits::default() };
        let mut session = crate::engine(limits).open(Cursor::new(bytes)).unwrap();
        let mut blocks = Vec::new();
        while let Some(batch) = session.next_batch().unwrap() {
            assert_eq!(session.resource("OEBPS/text/near.JPG").unwrap(), PNG);
            blocks.extend(batch.blocks);
        }
        assert_eq!(blocks.len(), 20_001);
        assert_eq!(texts(&blocks[20_000..]), [format!("P {:x}", 19_999u32.wrapping_mul(2_654_435_761))]);
    }

    #[test]
    fn the_book_reader_gets_its_contents_chapters_and_files() {
        let nav = br##"<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="landmarks"><ol><li><a href="text/ch1.xhtml">Start</a></li></ol></nav>
<nav epub:type="toc"><ol><li><a href="text/ch1.xhtml">One &amp; <em>only</em></a><ol><li><a href="text/ch%202.xhtml#s2">Two, part 2</a></li></ol></li>
<li><a href="https://example.com">Away</a></li><li><a href="missing.xhtml">Gone</a></li></ol></nav></body></html>"##;
        let opf = br#"<package><metadata><dc:title>The   Book</dc:title><dc:creator>A. Writer</dc:creator><dc:language>en</dc:language></metadata><manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="c2" href="text/ch%202.xhtml" media-type="application/xhtml+xml"/>
<item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
<spine page-progression-direction="rtl"><itemref idref="c1"/><itemref idref="nav" linear="no"/><itemref idref="c2"/></spine></package>"#;
        let mut bytes = book(&[("OEBPS/nav.xhtml", nav)]);
        // The helper's package is replaced by this one.
        bytes = {
            let container = br#"<container><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>"#;
            let _ = bytes;
            zip_with(
                &[
                    ("mimetype", b"application/epub+zip".as_slice()),
                    ("META-INF/container.xml", container),
                    ("OEBPS/book.opf", opf),
                    ("OEBPS/nav.xhtml", nav),
                    ("OEBPS/text/ch1.xhtml", b"<html><body><p>1</p></body></html>"),
                    ("OEBPS/text/ch 2.xhtml", b"<html><body><p id=\"s2\">2</p></body></html>"),
                    ("OEBPS/style.css", b"p{color:red}"),
                ],
                false,
            )
        };
        let mut session = crate::engine(Limits::default()).open(Cursor::new(bytes)).unwrap();
        let json = String::from_utf8(session.resource(crate::epub_book::MANIFEST_RESOURCE).unwrap()).unwrap();
        assert_eq!(
            json,
            r#"{"title":"The Book","author":"A. Writer","language":"en","rtl":true,"fixed":false,"chapters":[{"href":"OEBPS/text/ch1.xhtml","size":34},{"href":"OEBPS/text/ch 2.xhtml","size":42}],"toc":[{"title":"One \u0026 only","href":"OEBPS/text/ch1.xhtml","depth":0},{"title":"Two, part 2","href":"OEBPS/text/ch 2.xhtml#s2","depth":1}]}"#
        );
        let page = String::from_utf8(session.resource(crate::epub_book::READER_RESOURCE).unwrap()).unwrap();
        assert!(page.contains(r#"<html lang="en">"#));
        assert!(page.contains(&format!("const BOOK = {json};")));
        assert!(page.contains("__EZTOKEN__") && page.contains("__EZSTATE__"), "the app fills these in");
        assert_eq!(session.resource("ezfiles:book/OEBPS/style.css").unwrap(), b"p{color:red}");
        for refused in [
            "ezfiles:book/META-INF/container.xml",
            "ezfiles:book/mimetype",
            "ezfiles:book/OEBPS/../mimetype",
            "ezfiles:book/OEBPS/none.css",
        ] {
            assert!(session.resource(refused).is_err(), "{refused}");
        }
        // Reading the book's files doesn't disturb the streamed text.
        let mut blocks = Vec::new();
        while let Some(batch) = session.next_batch().unwrap() {
            blocks.extend(batch.blocks);
        }
        assert_eq!(texts(&blocks), ["P 1", "P 2"]);
    }

    #[test]
    fn an_epub2_book_takes_its_contents_from_the_ncx() {
        let ncx = br#"<ncx><navMap><navPoint id="a"><navLabel><text>First</text></navLabel><content src="text/ch1.xhtml"/>
<navPoint id="b"><navLabel><text>Inner</text></navLabel><content src="text/ch1.xhtml#x"/></navPoint></navPoint></navMap></ncx>"#;
        let opf = br#"<package><metadata><meta name="fixed-layout" content="true"/></metadata><manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
<item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/></manifest><spine toc="ncx"><itemref idref="c1"/></spine></package>"#;
        let container = br#"<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>"#;
        let bytes = zip_with(
            &[
                ("mimetype", b"application/epub+zip".as_slice()),
                ("META-INF/container.xml", container),
                ("OEBPS/content.opf", opf),
                ("OEBPS/toc.ncx", ncx),
                ("OEBPS/text/ch1.xhtml", b"<html><body><p>1</p></body></html>"),
            ],
            false,
        );
        let mut session = crate::engine(Limits::default()).open(Cursor::new(bytes)).unwrap();
        let json = String::from_utf8(session.resource(crate::epub_book::MANIFEST_RESOURCE).unwrap()).unwrap();
        assert!(json.contains(r#""fixed":true"#), "{json}");
        assert!(json.ends_with(r#""toc":[{"title":"First","href":"OEBPS/text/ch1.xhtml","depth":0},{"title":"Inner","href":"OEBPS/text/ch1.xhtml#x","depth":1}]}"#), "{json}");
    }
}
