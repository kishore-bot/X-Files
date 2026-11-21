//! Word (DOCX): the main document part, streamed.
//!
//! Extracts paragraphs with inline styling (bold, italic, underline,
//! strikethrough, hyperlinks), headings (from `HeadingN`/`Title` styles or
//! outline levels), numbered and bulleted lists (from `numbering.xml`), and
//! tables with their header rows. Pictures (`w:drawing`) become image blocks
//! in reading order, fetched on demand through [`BlockSource::resource`];
//! pictures inside table cells are skipped, because cells hold plain text.
//! Field codes and the fallback copies of text boxes are skipped.
//!
//! Paragraph styles from `styles.xml` fill in what a paragraph doesn't say
//! itself: list numbering (as "List Bullet" and "List Number" carry it),
//! heading level and quote styles, following each style's `basedOn` chain.
//!
//! Tracked changes show the document as if they were accepted: insertions are
//! ordinary text; deletions, moved-away text and deleted table rows stay hidden.
//!
//! The other parts are read one after another, around the main part, so reading
//! order is: header, body, footnotes, endnotes, comments, footer. Each is
//! streamed too, and none is held in memory:
//!
//! - **Notes.** A reference shows a superscript number in the text. Footnotes
//!   and endnotes share one sequence, in order of first reference, so a number
//!   always means one note and never relies on the file's own ids. The text
//!   follows the body under "Footnotes" and "Endnotes", in the order of the
//!   part, which is reference order in files Word saves. Notes nothing refers
//!   to, and separators, are skipped. Referring to a note that is not there
//!   leaves the number in the text and one line saying which are missing.
//! - **Comments.** A reference shows `[C1]`, and the comment follows as a quote
//!   with its author under a final "Comments" section. Placing each comment
//!   under its paragraph would mean holding every comment in memory while the
//!   body streams, because only one part can be read at a time; the marker
//!   keeps the link to the paragraph at no memory cost.
//! - **Header and footer.** The first non-empty default ones, once each, under
//!   their own label. Only `w:sectPr` says which part is the default rather than
//!   the first-page or even-page one, and it usually sits at the end of the
//!   body, so a document with headers or footers streams the body once just to
//!   find those references. Page-number fields show their saved value.
//!
//! Only the main part can fail the document. A missing or malformed extra part
//! ends where it stops making sense, keeping what was already read.

use std::collections::{HashMap, VecDeque};

use document_core::style::{BOLD, ITALIC, STRIKE, UNDERLINE};
use document_core::{Block, ErrorKind, Result, Rich};

use crate::ooxml::{Relationship, hyperlinks, relationships, target_of};
use crate::rich::RichBuilder;
use crate::stream::{BlockSource, MAX_PENDING_TEXT, Parts};
use crate::xml::{Tag, Xml};

const EMU_PER_POINT: f32 = 12700.0;

/// Notes and comments a document can refer to. Past this, references add no
/// marker, so a hostile body cannot grow the table without bound.
const MAX_REFERENCES: usize = 20_000;
/// Real ids are small numbers; longer ones are never stored.
const MAX_ID_BYTES: usize = 32;
const MAX_AUTHOR_CHARS: usize = 128;
/// Default headers (and footers) tried in search of a non-empty one.
const MAX_SECTION_PARTS: usize = 8;
/// Missing notes named before the message just gives how many more.
const MAX_MISSING_LISTED: usize = 50;

/// The kind of part being read, which decides how its content appears.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Role {
    Header,
    Main,
    Notes(NoteKind),
    Comments,
    Footer,
}

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
enum NoteKind {
    Foot,
    End,
}

impl Role {
    /// The element holding one item in a part of notes or comments.
    fn item_element(self) -> Option<&'static str> {
        match self {
            Role::Notes(NoteKind::Foot) => Some("footnote"),
            Role::Notes(NoteKind::End) => Some("endnote"),
            Role::Comments => Some("comment"),
            _ => None,
        }
    }

    /// The label shown once before the first thing a part adds.
    fn title(self) -> Option<&'static str> {
        match self {
            Role::Header => Some("Header"),
            Role::Main => None,
            Role::Notes(NoteKind::Foot) => Some("Footnotes"),
            Role::Notes(NoteKind::End) => Some("Endnotes"),
            Role::Comments => Some("Comments"),
            Role::Footer => Some("Footer"),
        }
    }
}

/// A part still to be read. `path` is `None` when the package has no such part.
struct Step {
    role: Role,
    path: Option<String>,
}

/// One note or comment the body refers to.
struct Anchor {
    number: u32,
    /// Its text was found in the part it belongs to.
    found: bool,
}

/// What the body referred to, by the file's own ids.
#[derive(Default)]
struct References {
    notes: HashMap<(NoteKind, String), Anchor>,
    comments: HashMap<String, Anchor>,
    last_note: u32,
    last_comment: u32,
}

impl References {
    fn is_full(&self) -> bool {
        self.notes.len() + self.comments.len() >= MAX_REFERENCES
    }

    /// The number of this note, given on its first reference.
    fn note(&mut self, kind: NoteKind, id: &str) -> Option<u32> {
        if id.len() > MAX_ID_BYTES {
            return None;
        }
        let key = (kind, id.to_owned());
        if let Some(anchor) = self.notes.get(&key) {
            return Some(anchor.number);
        }
        if self.is_full() {
            return None;
        }
        self.last_note += 1;
        self.notes.insert(key, Anchor { number: self.last_note, found: false });
        Some(self.last_note)
    }

    /// The number of this comment, given on its first reference.
    fn comment(&mut self, id: &str) -> Option<u32> {
        if id.len() > MAX_ID_BYTES {
            return None;
        }
        if let Some(anchor) = self.comments.get(id) {
            return Some(anchor.number);
        }
        if self.is_full() {
            return None;
        }
        self.last_comment += 1;
        self.comments.insert(id.to_owned(), Anchor { number: self.last_comment, found: false });
        Some(self.last_comment)
    }

    fn has_any(&self, role: Role) -> bool {
        match role {
            Role::Notes(kind) => self.notes.keys().any(|(k, _)| *k == kind),
            Role::Comments => !self.comments.is_empty(),
            _ => true,
        }
    }

    /// Marks an item of `role` as found the first time it appears, returning
    /// its number; `None` if nothing refers to it or it was seen already.
    fn find(&mut self, role: Role, id: &str) -> Option<u32> {
        if id.len() > MAX_ID_BYTES {
            return None;
        }
        let anchor = match role {
            Role::Notes(kind) => self.notes.get_mut(&(kind, id.to_owned())),
            Role::Comments => self.comments.get_mut(id),
            _ => None,
        }?;
        if anchor.found {
            return None;
        }
        anchor.found = true;
        Some(anchor.number)
    }

    /// Numbers of the items of `role` that were referred to but never found.
    fn missing(&self, role: Role) -> Vec<u32> {
        let mut numbers: Vec<u32> = match role {
            Role::Notes(kind) => {
                self.notes.iter().filter(|((k, _), a)| *k == kind && !a.found).map(|(_, a)| a.number).collect()
            }
            Role::Comments => self.comments.values().filter(|a| !a.found).map(|a| a.number).collect(),
            _ => Vec::new(),
        };
        numbers.sort_unstable();
        numbers
    }
}

/// `12` as `¹²`, for a note's marker.
fn superscript(number: u32) -> String {
    const DIGITS: [char; 10] = ['⁰', '¹', '²', '³', '⁴', '⁵', '⁶', '⁷', '⁸', '⁹'];
    number.to_string().bytes().map(|b| DIGITS[usize::from(b - b'0')]).collect()
}

/// A line naming the notes or comments that were referred to but not found.
fn missing_message(role: Role, numbers: &[u32]) -> Rich {
    let shown = numbers.iter().take(MAX_MISSING_LISTED);
    let listed: Vec<String> = match role {
        Role::Comments => shown.map(|n| format!("C{n}")).collect(),
        _ => shown.map(u32::to_string).collect(),
    };
    let subject = if role == Role::Comments { "Comments" } else { "Notes" };
    let more = numbers.len().saturating_sub(MAX_MISSING_LISTED);
    let tail = if more > 0 { format!(" and {more} more") } else { String::new() };
    let mut builder = RichBuilder::default();
    builder.push(&format!("{subject} not found in this document: {}{tail}", listed.join(", ")), ITALIC, None);
    builder.take()
}

pub(crate) struct DocxSource {
    parts: Parts,
    started: bool,
    part: String,
    plan: VecDeque<Step>,
    /// Footnotes, endnotes and comments, until the plan takes them.
    extra_parts: [(Role, Option<String>); 3],
    /// Relationship ids of the main part that are headers and footers.
    header_rels: HashMap<String, String>,
    footer_rels: HashMap<String, String>,
    role: Role,
    part_open: bool,
    /// The current part has added at least one block.
    emitted: bool,
    /// Text put before the first text of the current note or comment.
    label: Option<(String, u8)>,
    in_item: bool,
    references: References,
    links: HashMap<String, String>,
    numbering: Numbering,
    styles: ParagraphStyles,
    paragraph: Paragraph,
    run_style: u8,
    in_run_props: bool,
    in_paragraph_props: bool,
    link: Option<String>,
    in_text: bool,
    table: Option<TableState>,
    /// Relationship id -> entry name, for targets that can be served.
    images: HashMap<String, String>,
    drawing: Option<Drawing>,
}

#[derive(Default)]
struct Paragraph {
    text: RichBuilder,
    heading: Option<u8>,
    num_id: Option<String>,
    level: Option<u8>,
    /// `numId` 0: the paragraph turns off numbering its style would add.
    unnumbered: bool,
    /// The paragraph style's id, applied once by [`ParagraphStyles::apply`].
    style: Option<String>,
    styled: bool,
    quote: bool,
    /// A picture sat in this paragraph, so it may have nothing else to show.
    has_image: bool,
    /// Its list marker went out with the text before a picture.
    marked: bool,
}

#[derive(Default)]
struct Drawing {
    width: Option<f32>,
    height: Option<f32>,
    alt: Option<String>,
    /// More than one for a group of pictures.
    embeds: Vec<String>,
}

#[derive(Default)]
struct TableState {
    /// Nesting depth; tables inside cells are folded into the outer cell.
    depth: u32,
    first_row_is_header: bool,
    row_index: u32,
    row_is_header: bool,
    /// The row is a tracked deletion.
    row_deleted: bool,
    cells: Vec<String>,
    cell: String,
}

impl DocxSource {
    pub fn open(mut parts: Parts, part: String) -> Result<Self> {
        let rels = relationships(parts.archive(), &part)?;
        let numbering = match target_of(&rels, "numbering") {
            Some(path) if parts.archive().find(&path).is_some() => Numbering::read(&mut parts, &path)?,
            _ => Numbering::default(),
        };
        // Styles only add to what paragraphs say, so a broken part is ignored.
        let styles = match target_of(&rels, "styles") {
            Some(path) if parts.archive().find(&path).is_some() => {
                let styles = ParagraphStyles::read(&mut parts, &path).unwrap_or_default();
                parts.close();
                styles
            }
            _ => ParagraphStyles::default(),
        };
        let extra_parts = [
            (Role::Notes(NoteKind::Foot), inside_target(&rels, "footnotes", &part)),
            (Role::Notes(NoteKind::End), inside_target(&rels, "endnotes", &part)),
            (Role::Comments, inside_target(&rels, "comments", &part)),
        ];
        Ok(Self {
            parts,
            started: false,
            plan: VecDeque::new(),
            extra_parts,
            header_rels: section_rels(&rels, "header", &part),
            footer_rels: section_rels(&rels, "footer", &part),
            part,
            role: Role::Main,
            part_open: false,
            emitted: false,
            label: None,
            in_item: false,
            references: References::default(),
            links: HashMap::new(),
            numbering,
            styles,
            paragraph: Paragraph::default(),
            run_style: 0,
            in_run_props: false,
            in_paragraph_props: false,
            link: None,
            in_text: false,
            table: None,
            images: HashMap::new(),
            drawing: None,
        })
    }

    /// Finds the default headers and footers, then lays out what to read.
    fn make_plan(&mut self) -> Result<()> {
        let (headers, footers) = self.default_sections()?;
        self.plan.extend(headers.into_iter().map(|path| Step { role: Role::Header, path: Some(path) }));
        self.plan.push_back(Step { role: Role::Main, path: Some(self.part.clone()) });
        for (role, path) in &mut self.extra_parts {
            self.plan.push_back(Step { role: *role, path: path.take() });
        }
        self.plan.extend(footers.into_iter().map(|path| Step { role: Role::Footer, path: Some(path) }));
        Ok(())
    }

    /// Default header and footer parts in document order, from one pass over
    /// the main part. A failure here is left for the real pass to report.
    fn default_sections(&mut self) -> Result<(Vec<String>, Vec<String>)> {
        let (mut headers, mut footers) = (Vec::new(), Vec::new());
        if self.header_rels.is_empty() && self.footer_rels.is_empty() {
            return Ok((headers, footers));
        }
        let Ok(xml) = self.parts.open(&self.part) else { return Ok((headers, footers)) };
        loop {
            match xml.next() {
                Ok(Xml::Start(tag) | Xml::Empty(tag)) => {
                    let (rels, found) = match tag.name.as_str() {
                        "headerReference" => (&self.header_rels, &mut headers),
                        "footerReference" => (&self.footer_rels, &mut footers),
                        _ => continue,
                    };
                    if !matches!(tag.attr("type"), None | Some("default")) || found.len() >= MAX_SECTION_PARTS {
                        continue;
                    }
                    let path = tag.prefixed_attr("id").and_then(|id| rels.get(id));
                    if let Some(path) = path.filter(|p| !found.contains(*p)) {
                        found.push(path.clone());
                    }
                }
                Ok(Xml::Eof) => break,
                Ok(_) => {}
                Err(err) if err.kind == ErrorKind::Cancelled => return Err(err),
                Err(_) => break,
            }
        }
        self.parts.close();
        Ok((headers, footers))
    }

    /// Starts the next part, or closes the step at once if there is nothing to read.
    fn begin(&mut self, step: Step, out: &mut VecDeque<Block>) -> Result<()> {
        self.reset_part_state();
        self.role = step.role;
        self.emitted = false;
        if !self.references.has_any(step.role) {
            return Ok(());
        }
        let Some(path) = step.path else {
            self.end_part(out);
            return Ok(());
        };
        let rels = match relationships(self.parts.archive(), &path) {
            Ok(rels) => rels,
            Err(err) if step.role == Role::Main => return Err(err),
            Err(_) => Vec::new(),
        };
        self.links = hyperlinks(&rels);
        self.images =
            rels.iter().filter_map(|r| Some((r.id.clone(), self.parts.picture_path(r.inside.as_deref()?)?))).collect();
        match self.parts.open(&path) {
            Ok(_) => self.part_open = true,
            Err(err) if step.role == Role::Main || err.kind == ErrorKind::Cancelled => return Err(err),
            Err(_) => self.end_part(out),
        }
        Ok(())
    }

    /// Closes the current part and adds what follows it.
    fn end_part(&mut self, out: &mut VecDeque<Block>) {
        self.parts.close();
        self.part_open = false;
        self.reset_part_state();
        let role = self.role;
        match role {
            Role::Main => {}
            Role::Header | Role::Footer => {
                if self.emitted {
                    if role == Role::Header {
                        out.push_back(Block::Divider);
                    }
                    self.plan.retain(|step| step.role != role);
                }
            }
            Role::Notes(_) | Role::Comments => {
                let missing = self.references.missing(role);
                if !missing.is_empty() {
                    let text = missing_message(role, &missing);
                    let at = out.len();
                    self.add_title(out, at);
                    out.push_back(if role == Role::Comments {
                        Block::Quote { text }
                    } else {
                        Block::Paragraph { text }
                    });
                }
            }
        }
    }

    fn reset_part_state(&mut self) {
        self.paragraph = Paragraph::default();
        self.run_style = 0;
        self.in_run_props = false;
        self.in_paragraph_props = false;
        self.link = None;
        self.in_text = false;
        self.table = None;
        self.drawing = None;
        self.in_item = false;
        self.label = None;
    }

    /// Puts the part's label at `at` the first time the part adds anything.
    fn add_title(&mut self, out: &mut VecDeque<Block>, at: usize) {
        if !self.emitted {
            self.emitted = true;
            if let Some(title) = self.role.title() {
                out.insert(at, Block::Section { title: title.to_owned() });
            }
        }
    }

    /// Handles the start of a note or comment, or of anything outside one.
    fn on_start_outside_item(&mut self, tag: &Tag) -> Result<()> {
        if matches!(tag.name.as_str(), "footnotes" | "endnotes" | "comments") {
            return Ok(());
        }
        // Separators are not notes, even if something refers to them.
        let real = matches!(tag.attr("type"), None | Some("normal"));
        let number = if Some(tag.name.as_str()) == self.role.item_element() && real {
            tag.attr("id").and_then(|id| self.references.find(self.role, id))
        } else {
            None
        };
        let Some(number) = number else { return self.parts.xml().skip_element() };
        self.in_item = true;
        self.label = Some(if self.role == Role::Comments {
            let author: String = tag.attr("author").unwrap_or("").trim().chars().take(MAX_AUTHOR_CHARS).collect();
            let by = if author.is_empty() { String::new() } else { format!(" {author}:") };
            (format!("[C{number}]{by} "), BOLD)
        } else {
            (format!("{} ", superscript(number)), 0)
        });
        Ok(())
    }

    /// Adds the marker for a reference in the body, numbering it if it is new.
    fn on_reference(&mut self, tag: &Tag) {
        let Some(id) = tag.attr("id").filter(|_| self.role == Role::Main) else { return };
        let marker = match tag.name.as_str() {
            "footnoteReference" => self.references.note(NoteKind::Foot, id).map(superscript),
            "endnoteReference" => self.references.note(NoteKind::End, id).map(superscript),
            _ => self.references.comment(id).map(|n| format!("[C{n}]")),
        };
        if let Some(marker) = marker {
            self.paragraph.text.push_plain(&marker);
        }
    }

    fn apply_label(&mut self) {
        if let Some((label, style)) = self.label.take() {
            self.paragraph.text.push(&label, style, None);
        }
    }

    /// Reads one XML event of the open part; `true` once the part has ended.
    fn read_event(&mut self, out: &mut VecDeque<Block>) -> Result<bool> {
        match self.parts.xml().next()? {
            Xml::Start(tag) => self.on_start(&tag)?,
            Xml::Empty(tag) => self.on_empty(&tag),
            Xml::Text(text) if self.in_text => {
                let style = self.run_style;
                let mut text = text.as_str();
                if self.label.is_some() {
                    // The file's own space after the note's mark would double ours.
                    text = text.trim_start();
                    if !text.is_empty() {
                        self.apply_label();
                    }
                }
                self.paragraph.text.push(text, style, self.link.as_deref());
                if self.paragraph.text.len() > MAX_PENDING_TEXT && self.table.is_none() {
                    let text = self.paragraph.text.take();
                    out.push_back(if self.role == Role::Comments {
                        Block::Quote { text }
                    } else {
                        Block::Paragraph { text }
                    });
                }
            }
            Xml::Text(_) => {}
            Xml::End(name) => match name.as_str() {
                "t" => self.in_text = false,
                "rPr" => self.in_run_props = false,
                "pPr" => self.in_paragraph_props = false,
                "hyperlink" => self.link = None,
                "p" => self.end_paragraph(out),
                "drawing" => self.end_drawing(out),
                "tc" => {
                    if let Some(table) = self.table.as_mut().filter(|t| t.depth == 1) {
                        let cell = std::mem::take(&mut table.cell);
                        table.cells.push(cell);
                    }
                }
                "tr" => self.end_row(out),
                "tbl" => {
                    if let Some(table) = &mut self.table {
                        table.depth -= 1;
                        if table.depth == 0 {
                            self.table = None;
                        }
                    }
                }
                name if self.in_item && Some(name) == self.role.item_element() => {
                    self.in_item = false;
                    self.label = None;
                }
                _ => {}
            },
            Xml::Eof => return Ok(true),
        }
        Ok(false)
    }

    fn end_row(&mut self, out: &mut VecDeque<Block>) {
        let main = self.role == Role::Main;
        let Some(table) = self.table.as_mut().filter(|t| t.depth == 1) else { return };
        let cells = std::mem::take(&mut table.cells);
        let blank = !main && cells.iter().all(|c| c.trim().is_empty());
        if table.row_deleted || blank {
            return;
        }
        table.row_index += 1;
        out.push_back(Block::TableRow { row: None, header: table.row_is_header, cells, styles: Vec::new() });
    }

    fn end_paragraph(&mut self, out: &mut VecDeque<Block>) {
        let mut p = std::mem::take(&mut self.paragraph);
        if let Some(table) = &mut self.table {
            if table.cell.len() > MAX_PENDING_TEXT {
                return;
            }
            if !table.cell.is_empty() && !p.text.is_empty() {
                table.cell.push('\n');
            }
            table.cell.push_str(p.text.text());
            return;
        }
        let only_image = p.has_image && p.text.is_empty();
        // Headers and notes are full of empty paragraphs that only add space.
        let blank = self.role != Role::Main && p.text.text().trim().is_empty();
        let block = Self::text_block(self.role, &mut self.numbering, &self.styles, &mut p);
        if !only_image && !blank {
            out.push_back(block);
        }
    }

    /// Takes the paragraph's text so far as a block of its kind.
    fn text_block(role: Role, numbering: &mut Numbering, styles: &ParagraphStyles, p: &mut Paragraph) -> Block {
        styles.apply(p);
        let text = p.text.take();
        match role {
            Role::Main => {}
            Role::Comments => return Block::Quote { text },
            // List counters belong to the body, and a header comes before it.
            Role::Header | Role::Footer => return Block::Paragraph { text },
            // Headings in a note would read as sections of the document.
            Role::Notes(_) => p.heading = None,
        }
        match (p.heading, p.level) {
            (Some(level), _) => Block::Heading { level, text },
            (None, None) if p.quote => Block::Quote { text },
            (None, Some(depth)) => {
                let marker = if p.marked {
                    Some(String::new())
                } else {
                    p.num_id.as_deref().and_then(|id| numbering.next_marker(id, depth))
                };
                p.marked = true;
                Block::ListItem { depth, marker, text }
            }
            (None, None) => Block::Paragraph { text },
        }
    }

    /// Emits the drawing's pictures after any text that came before them, which
    /// keeps reading order; text after them continues in a new block.
    fn end_drawing(&mut self, out: &mut VecDeque<Block>) {
        let Some(drawing) = self.drawing.take() else { return };
        if self.table.is_some() || drawing.embeds.is_empty() {
            return;
        }
        self.apply_label();
        if !self.paragraph.text.is_empty() {
            out.push_back(Self::text_block(self.role, &mut self.numbering, &self.styles, &mut self.paragraph));
        }
        self.paragraph.has_image = true;
        // The extent of a group describes the group, not each of its pictures.
        let single = drawing.embeds.len() == 1;
        for id in &drawing.embeds {
            let (width, height) = if single { (drawing.width, drawing.height) } else { (None, None) };
            out.push_back(Block::image(self.images.get(id).cloned(), drawing.alt.as_deref(), width, height));
        }
    }

    fn on_start(&mut self, tag: &Tag) -> Result<()> {
        if self.role.item_element().is_some() && !self.in_item {
            return self.on_start_outside_item(tag);
        }
        match tag.name.as_str() {
            "p" => self.paragraph = Paragraph::default(),
            "pPr" => self.in_paragraph_props = true,
            "r" => self.run_style = 0,
            "rPr" => self.in_run_props = true,
            "t" => self.in_text = true,
            "hyperlink" => self.link = tag.prefixed_attr("id").and_then(|id| self.links.get(id)).cloned(),
            // Deleted and moved-away text, field instructions and duplicate fallback content.
            "del" | "moveFrom" | "delText" | "instrText" | "Fallback" => self.parts.xml().skip_element()?,
            "drawing" => self.drawing = Some(Drawing::default()),
            "tbl" => self.table.get_or_insert_with(TableState::default).depth += 1,
            "tr" => {
                if let Some(table) = self.table.as_mut().filter(|t| t.depth == 1) {
                    table.row_is_header = table.row_index == 0 && table.first_row_is_header;
                    table.row_deleted = false;
                }
            }
            "tc" if self.table.as_ref().is_some_and(|t| t.depth == 1) => {
                self.table.as_mut().expect("in table").cell.clear();
            }
            _ => self.on_empty(tag),
        }
        Ok(())
    }

    fn on_empty(&mut self, tag: &Tag) {
        if self.role.item_element().is_some() && !self.in_item {
            // An empty note: found, with nothing to show.
            if Some(tag.name.as_str()) == self.role.item_element()
                && let Some(id) = tag.attr("id")
            {
                self.references.find(self.role, id);
            }
            return;
        }
        let paragraph = &mut self.paragraph;
        match tag.name.as_str() {
            "footnoteReference" | "endnoteReference" | "commentReference" => self.on_reference(tag),
            // In a row's properties; in a run's it only marks the paragraph mark.
            "del" if !self.in_run_props => {
                if let Some(table) = self.table.as_mut().filter(|t| t.depth == 1) {
                    table.row_deleted = true;
                }
            }
            "pStyle" => {
                if let Some(level) = tag.attr("val").and_then(heading_level) {
                    paragraph.heading = Some(level);
                }
                paragraph.style = tag.attr("val").map(str::to_owned);
            }
            "outlineLvl" => {
                if let Some(level) = tag.attr("val").and_then(|v| v.parse::<u8>().ok()).filter(|l| *l < 9) {
                    paragraph.heading.get_or_insert((level + 2).min(6));
                }
            }
            "ilvl" => paragraph.level = Some(tag.attr("val").and_then(|v| v.parse().ok()).unwrap_or(0)),
            "numId" => {
                // numId 0 explicitly removes numbering.
                let id = tag.attr("val").unwrap_or("0");
                if id != "0" {
                    paragraph.num_id = Some(id.to_owned());
                    paragraph.level.get_or_insert(0);
                } else {
                    paragraph.unnumbered = true;
                }
            }
            "numPr" => {
                paragraph.level.get_or_insert(0);
            }
            "extent" => {
                if let Some(drawing) = &mut self.drawing {
                    drawing.width = tag.attr("cx").and_then(|v| v.parse::<f32>().ok()).map(|v| v / EMU_PER_POINT);
                    drawing.height = tag.attr("cy").and_then(|v| v.parse::<f32>().ok()).map(|v| v / EMU_PER_POINT);
                }
            }
            "docPr" => {
                if let Some(drawing) = &mut self.drawing {
                    drawing.alt = tag.attr("descr").map(str::to_owned);
                }
            }
            "blip" => {
                if let (Some(drawing), Some(id)) = (&mut self.drawing, tag.prefixed_attr("embed")) {
                    drawing.embeds.push(id.to_owned());
                }
            }
            // Formatting of the paragraph mark itself is not run formatting.
            name if self.in_run_props && !self.in_paragraph_props => {
                let on = toggle_on(tag);
                let flag = match name {
                    "b" => BOLD,
                    "i" => ITALIC,
                    "u" => {
                        if tag.attr("val") == Some("none") {
                            0
                        } else {
                            UNDERLINE
                        }
                    }
                    "strike" | "dstrike" => STRIKE,
                    _ => 0,
                };
                if on {
                    self.run_style |= flag;
                } else {
                    self.run_style &= !flag;
                }
            }
            "tblLook" => {
                if let Some(table) = self.table.as_mut().filter(|t| t.depth == 1) {
                    // Word writes either explicit attributes or a hex bitmask (0x0020 = first row).
                    table.first_row_is_header = match tag.attr("firstRow") {
                        Some(v) => v == "1" || v == "true",
                        None => {
                            tag.attr("val").and_then(|v| u32::from_str_radix(v, 16).ok()).is_some_and(|m| m & 0x20 != 0)
                        }
                    };
                }
            }
            "tblHeader" => {
                if let Some(table) = self.table.as_mut().filter(|t| t.depth == 1) {
                    table.row_is_header = toggle_on(tag);
                }
            }
            // Tab stops in paragraph properties carry `val`; run tabs do not.
            "tab" if tag.attr("val").is_none() => paragraph.text.push_plain("\t"),
            "br" | "cr" => paragraph.text.push_plain("\n"),
            _ => {}
        }
    }
}

/// `<w:b/>` and `<w:b w:val="true"/>` switch a property on; `val="0"`/`false` off.
fn toggle_on(tag: &Tag) -> bool {
    !matches!(tag.attr("val"), Some("0" | "false" | "off" | "none"))
}

impl BlockSource for DocxSource {
    fn pump(&mut self, out: &mut VecDeque<Block>) -> Result<bool> {
        if !self.started {
            self.started = true;
            self.make_plan()?;
        }
        let before = out.len();
        while out.len() == before {
            if !self.part_open {
                let Some(step) = self.plan.pop_front() else { return Ok(true) };
                self.begin(step, out)?;
                continue;
            }
            let at = out.len();
            let ended = match self.read_event(out) {
                Ok(ended) => ended,
                Err(err) if self.role == Role::Main || err.kind == ErrorKind::Cancelled => return Err(err),
                Err(_) => true,
            };
            if out.len() > at {
                self.add_title(out, at);
            }
            if ended {
                self.end_part(out);
            }
        }
        Ok(false)
    }

    /// Pictures, and the whole document as print-layout pages (see [`crate::docx_html`]).
    fn resource(&mut self, name: &str) -> Result<Vec<u8>> {
        if name == crate::docx_html::PAGES_RESOURCE {
            let part = self.part.clone();
            return crate::docx_html::render(&mut self.parts, &part);
        }
        self.parts.picture(name)
    }
}

/// Where the first relationship of this kind leads, unless that is `main`:
/// reading the body again as if it were a note would only repeat it.
fn inside_target(rels: &[Relationship], suffix: &str, main: &str) -> Option<String> {
    rels.iter()
        .filter(|r| !r.external && r.kind.rsplit('/').next() == Some(suffix))
        .find_map(|r| r.inside.clone().filter(|path| path != main))
}

/// Relationship id -> part, for every relationship of this kind.
fn section_rels(rels: &[Relationship], suffix: &str, main: &str) -> HashMap<String, String> {
    rels.iter()
        .filter(|r| !r.external && r.kind.rsplit('/').next() == Some(suffix))
        .filter_map(|r| Some((r.id.clone(), r.inside.clone().filter(|path| path != main)?)))
        .collect()
}

/// What a paragraph style says, before its `basedOn` chain is followed.
#[derive(Default)]
struct StyleInfo {
    based_on: Option<String>,
    /// `Some("0")` turns numbering off.
    num_id: Option<String>,
    level: Option<u8>,
    heading: Option<u8>,
    quote: bool,
}

/// Paragraph styles from `styles.xml`, by style id.
#[derive(Default)]
struct ParagraphStyles {
    styles: HashMap<String, StyleInfo>,
}

/// More is not a real document, and the table must stay small.
const MAX_STYLES: usize = 4096;
/// Longest `basedOn` chain followed; cycles end here too.
const MAX_STYLE_CHAIN: usize = 12;

impl ParagraphStyles {
    fn read(parts: &mut Parts, path: &str) -> Result<Self> {
        let xml = parts.open(path)?;
        let mut styles = HashMap::new();
        let mut current: Option<(String, StyleInfo)> = None;
        loop {
            match xml.next()? {
                Xml::Start(tag) | Xml::Empty(tag) => {
                    let val = || tag.attr("val").map(str::to_owned);
                    match (tag.name.as_str(), current.as_mut()) {
                        ("style", _) => {
                            current = tag
                                .attr("styleId")
                                .filter(|_| tag.attr("type") == Some("paragraph"))
                                .map(|id| (id.to_owned(), StyleInfo::default()));
                        }
                        ("name", Some((id, info))) => {
                            let name = tag.attr("val").unwrap_or_default().to_ascii_lowercase();
                            info.heading = info.heading.or_else(|| heading_level(&name.replace(' ', "")));
                            info.heading = info.heading.or_else(|| heading_level(id));
                            info.quote = name.contains("quote");
                        }
                        ("basedOn", Some((_, info))) => info.based_on = val(),
                        ("numId", Some((_, info))) => info.num_id = val(),
                        ("ilvl", Some((_, info))) => info.level = tag.attr("val").and_then(|v| v.parse().ok()),
                        ("outlineLvl", Some((_, info))) => {
                            if let Some(level) = tag.attr("val").and_then(|v| v.parse::<u8>().ok()).filter(|l| *l < 9) {
                                info.heading.get_or_insert((level + 2).min(6));
                            }
                        }
                        _ => {}
                    }
                }
                Xml::End(name) if name == "style" => {
                    if let Some((id, info)) = current.take()
                        && styles.len() < MAX_STYLES
                    {
                        styles.insert(id, info);
                    }
                }
                Xml::Eof => break,
                _ => {}
            }
        }
        Ok(Self { styles })
    }

    /// Fills in what `p` doesn't say from its style chain, once.
    fn apply(&self, p: &mut Paragraph) {
        if std::mem::replace(&mut p.styled, true) {
            return;
        }
        let mut id = p.style.clone();
        let (mut num_id, mut level, mut heading, mut quote) = (None, None, None, false);
        for _ in 0..MAX_STYLE_CHAIN {
            let Some(info) = id.as_deref().and_then(|id| self.styles.get(id)) else { break };
            num_id = num_id.or_else(|| info.num_id.clone());
            level = level.or(info.level);
            heading = heading.or(info.heading);
            quote |= info.quote;
            id = info.based_on.clone();
        }
        p.heading = p.heading.or(heading);
        if p.unnumbered && p.num_id.is_none() {
            p.level = None;
        }
        if p.num_id.is_none()
            && !p.unnumbered
            && let Some(num) = num_id.filter(|n| n != "0")
        {
            p.num_id = Some(num);
            p.level = p.level.or(level).or(Some(0));
        }
        p.quote = quote;
    }
}

/// List formats from `numbering.xml`, with running counters per list level.
#[derive(Default)]
struct Numbering {
    /// numId → (level → format).
    formats: HashMap<String, HashMap<u8, String>>,
    counters: HashMap<String, [u32; 10]>,
}

impl Numbering {
    fn read(parts: &mut Parts, path: &str) -> Result<Self> {
        let xml = parts.open(path)?;
        let mut abstract_formats: HashMap<String, HashMap<u8, String>> = HashMap::new();
        let mut num_to_abstract: HashMap<String, String> = HashMap::new();
        let (mut current_abstract, mut current_num, mut current_level) = (None::<String>, None::<String>, 0u8);
        loop {
            match xml.next()? {
                Xml::Start(tag) | Xml::Empty(tag) => match tag.name.as_str() {
                    "abstractNum" => current_abstract = tag.attr("abstractNumId").map(str::to_owned),
                    "num" => current_num = tag.attr("numId").map(str::to_owned),
                    "lvl" => current_level = tag.attr("ilvl").and_then(|l| l.parse().ok()).unwrap_or(0),
                    "numFmt" => {
                        if let (Some(id), Some(format)) = (&current_abstract, tag.attr("val")) {
                            abstract_formats.entry(id.clone()).or_default().insert(current_level, format.to_owned());
                        }
                    }
                    "abstractNumId" => {
                        if let (Some(num), Some(id)) = (&current_num, tag.attr("val")) {
                            num_to_abstract.insert(num.clone(), id.to_owned());
                        }
                    }
                    _ => {}
                },
                Xml::End(name) if name == "abstractNum" => current_abstract = None,
                Xml::End(name) if name == "num" => current_num = None,
                Xml::Eof => break,
                _ => {}
            }
        }
        parts.close();
        let formats = num_to_abstract
            .into_iter()
            .filter_map(|(num, abs)| abstract_formats.get(&abs).map(|f| (num, f.clone())))
            .collect();
        Ok(Self { formats, counters: HashMap::new() })
    }

    /// The visible marker for the next item at `level`, or `None` for bullets.
    fn next_marker(&mut self, num_id: &str, level: u8) -> Option<String> {
        let format = self.formats.get(num_id)?.get(&level)?.clone();
        if format == "bullet" || format == "none" {
            return None;
        }
        let level = usize::from(level.min(9));
        let counters = self.counters.entry(num_id.to_owned()).or_insert([0; 10]);
        counters[level] += 1;
        for deeper in &mut counters[level + 1..] {
            *deeper = 0;
        }
        let n = counters[level];
        Some(match format.as_str() {
            "lowerLetter" => format!("{}.", letters(n, b'a')),
            "upperLetter" => format!("{}.", letters(n, b'A')),
            "lowerRoman" => format!("{}.", roman(n).to_lowercase()),
            "upperRoman" => format!("{}.", roman(n)),
            _ => format!("{n}."),
        })
    }
}

fn letters(mut n: u32, base: u8) -> String {
    let mut out = Vec::new();
    while n > 0 {
        n -= 1;
        out.push(base + (n % 26) as u8);
        n /= 26;
    }
    out.reverse();
    String::from_utf8(out).unwrap_or_default()
}

fn roman(mut n: u32) -> String {
    const TABLE: [(u32, &str); 13] = [
        (1000, "M"),
        (900, "CM"),
        (500, "D"),
        (400, "CD"),
        (100, "C"),
        (90, "XC"),
        (50, "L"),
        (40, "XL"),
        (10, "X"),
        (9, "IX"),
        (5, "V"),
        (4, "IV"),
        (1, "I"),
    ];
    let mut out = String::new();
    for (value, symbol) in TABLE {
        while n >= value {
            out.push_str(symbol);
            n -= value;
        }
    }
    out
}

/// `Title` → 1; `Heading1`…`Heading9` (any case, optional space) → 2…6.
/// Word documents usually have one Title above their Heading 1 sections, so
/// sections sit one level below it.
fn heading_level(style: &str) -> Option<u8> {
    let lower = style.to_ascii_lowercase();
    if lower == "title" {
        return Some(1);
    }
    let digits = lower.strip_prefix("heading")?.trim_start();
    digits.parse::<u8>().ok().filter(|l| (1..=9).contains(l)).map(|l| (l + 1).min(6))
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use document_core::style::{BOLD, ITALIC, UNDERLINE};
    use document_core::{Block, ErrorKind, Limits, Span};

    use crate::testing::{open_zip, open_zip_err, texts};
    use crate::zip::testing::{zip, zip_with};

    const RELS: &[u8] = br#"<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"#;
    const DOC_RELS: &[u8] = br#"<Relationships><Relationship Id="rId7" Type="http://x/numbering" Target="numbering.xml"/><Relationship Id="rId9" Type="http://x/hyperlink" Target="https://example.com" TargetMode="External"/><Relationship Id="rId10" Type="http://x/hyperlink" Target="javascript:alert(1)" TargetMode="External"/></Relationships>"#;
    const NUMBERING: &[u8] = br#"<w:numbering><w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/></w:lvl><w:lvl w:ilvl="1"><w:numFmt w:val="lowerLetter"/></w:lvl></w:abstractNum><w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/></w:lvl></w:abstractNum><w:num w:numId="3"><w:abstractNumId w:val="0"/></w:num><w:num w:numId="4"><w:abstractNumId w:val="1"/></w:num></w:numbering>"#;

    fn item(num: u32, level: u32, text: &str) -> String {
        format!(
            r#"<w:p><w:pPr><w:numPr><w:ilvl w:val="{level}"/><w:numId w:val="{num}"/></w:numPr></w:pPr><w:r><w:t>{text}</w:t></w:r></w:p>"#
        )
    }

    #[test]
    fn extracts_structure_styles_and_numbering() {
        let document = format!(
            r#"<?xml version="1.0"?>
<w:document xmlns:w="w" xmlns:r="r"><w:body>
<w:p><w:pPr><w:pStyle w:val="Heading1"/><w:rPr><w:b/></w:rPr></w:pPr><w:r><w:t>Quarterly report</w:t></w:r></w:p>
<w:p><w:r><w:t xml:space="preserve">Revenue </w:t></w:r><w:r><w:rPr><w:b/><w:i/></w:rPr><w:t>grew</w:t></w:r><w:r><w:rPr><w:u w:val="single"/></w:rPr><w:t xml:space="preserve"> 12%</w:t></w:r><w:del><w:r><w:delText>shrank</w:delText></w:r></w:del></w:p>
<w:p><w:hyperlink r:id="rId9"><w:r><w:t>site</w:t></w:r></w:hyperlink><w:hyperlink r:id="rId10"><w:r><w:t>bad</w:t></w:r></w:hyperlink></w:p>
{}{}{}{}
<w:tbl><w:tblPr><w:tblLook w:val="04A0"/></w:tblPr><w:tr><w:tc><w:p><w:r><w:t>Region</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Total</w:t></w:r></w:p></w:tc></w:tr>
<w:tr><w:tc><w:p><w:r><w:t>North</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>42</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
<w:p><w:r><w:t>After</w:t><w:br/><w:t>break</w:t></w:r></w:p>
</w:body></w:document>"#,
            item(3, 0, "First"),
            item(3, 1, "Sub"),
            item(3, 0, "Second"),
            item(4, 0, "Bullet"),
        );
        let doc = zip(&[
            ("[Content_Types].xml", b"<Types/>"),
            ("_rels/.rels", RELS),
            ("word/document.xml", document.as_bytes()),
            ("word/_rels/document.xml.rels", DOC_RELS),
            ("word/numbering.xml", NUMBERING),
        ]);
        let (format, blocks) = open_zip(doc);
        assert_eq!(format, document_core::FormatId::Docx);
        assert_eq!(
            texts(&blocks),
            [
                "H2 Quarterly report",
                "P Revenue grew 12%",
                "P sitebad",
                "L0[1.] First",
                "L1[a.] Sub",
                "L0[2.] Second",
                "L0 Bullet",
                "T Region|Total",
                "R North|42",
                "P After\nbreak",
            ]
        );
        let Block::Heading { text, .. } = &blocks[0] else { panic!() };
        assert!(text.spans.is_empty(), "paragraph-mark formatting must not style the text");
        let Block::Paragraph { text } = &blocks[1] else { panic!() };
        assert_eq!(
            text.spans,
            [
                Span { start: 8, end: 12, style: BOLD | ITALIC, link: None, color: None },
                Span { start: 12, end: 16, style: UNDERLINE, link: None, color: None },
            ]
        );
        let Block::Paragraph { text } = &blocks[2] else { panic!() };
        assert_eq!(
            text.spans,
            [Span { start: 0, end: 4, style: 0, link: Some("https://example.com".into()), color: None }]
        );
    }

    const PNG: &[u8] = b"\x89PNG fake";

    fn drawing(id: &str, alt: &str) -> String {
        format!(
            r#"<w:drawing><wp:inline><wp:extent cx="1270000" cy="635000"/><wp:effectExtent l="1" t="1" r="1" b="1"/><wp:docPr id="1" name="Picture 1" descr="{alt}"/><a:graphic><a:graphicData><pic:pic><pic:blipFill><a:blip r:embed="{id}"><a:extLst/></a:blip></pic:blipFill><pic:spPr><a:xfrm><a:ext cx="9" cy="9"/></a:xfrm></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing>"#
        )
    }

    fn with_pictures(body: &str, rels: &str) -> Vec<u8> {
        let document = format!(r#"<w:document><w:body>{body}</w:body></w:document>"#);
        let rels = format!("<Relationships>{rels}</Relationships>");
        zip(&[
            ("[Content_Types].xml", b"<Types/>"),
            ("_rels/.rels", RELS),
            ("word/document.xml", document.as_bytes()),
            ("word/_rels/document.xml.rels", rels.as_bytes()),
            ("word/media/a b.png", PNG),
            ("word/media/chart.emf", b"emf"),
            ("word/settings.xml", b"<w:settings/>"),
            ("word/numbering.xml", NUMBERING),
        ])
    }

    fn rel(id: &str, target: &str) -> String {
        format!(r#"<Relationship Id="{id}" Type="http://x/image" Target="{target}"/>"#)
    }

    #[test]
    fn pictures_appear_in_reading_order_with_size_and_alt() {
        let body = format!(
            r#"<w:p><w:r><w:t>Before</w:t></w:r><w:r>{}</w:r><w:r><w:t>After</w:t></w:r></w:p><w:p><w:r>{}</w:r></w:p><w:p><w:r><w:t>End</w:t></w:r></w:p>"#,
            drawing("rId1", "A &amp; B"),
            drawing("rId2", "Absolute"),
        );
        let rels = rel("rId1", "media/a%20b.png") + &rel("rId2", "/word/media/a%20b.png");
        let (_, blocks) = open_zip(with_pictures(&body, &rels));
        assert_eq!(
            texts(&blocks),
            ["P Before", "PIC word/media/a b.png [A & B]", "P After", "PIC word/media/a b.png [Absolute]", "P End"]
        );
        let Block::Image { width, height, .. } = &blocks[1] else { panic!() };
        assert_eq!((*width, *height), (Some(100.0), Some(50.0)));
    }

    #[test]
    fn list_items_keep_their_numbers_around_pictures() {
        let picture = drawing("rId1", "");
        let item = |content: String| {
            format!(r#"<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="3"/></w:numPr></w:pPr>{content}</w:p>"#)
        };
        let body = item(format!("<w:r><w:t>One</w:t></w:r><w:r>{picture}</w:r><w:r><w:t>more</w:t></w:r>"))
            + &item(format!("<w:r>{picture}</w:r>"))
            + &item("<w:r><w:t>Three</w:t></w:r>".to_owned());
        let rels = rel("rId1", "media/a%20b.png")
            + r#"<Relationship Id="rId7" Type="http://x/numbering" Target="numbering.xml"/>"#;
        assert_eq!(
            texts(&open_zip(with_pictures(&body, &rels)).1),
            ["L0[1.] One", "PIC word/media/a b.png []", "L0[] more", "PIC word/media/a b.png []", "L0[3.] Three"]
        );
    }

    #[test]
    fn unservable_targets_show_a_label_and_cannot_be_read() {
        let targets = [
            rel("r1", "../../etc/passwd"),
            rel("r2", "/etc/passwd"),
            rel("r3", "../../../word/media/a%20b.png"),
            r#"<Relationship Id="r4" Type="http://x/image" Target="http://evil.example/a.png" TargetMode="External"/>"#
                .to_owned(),
            rel("r5", "http://evil.example/a.png"),
            rel("r6", "media/chart.emf"),
            rel("r7", "media/missing.png"),
            rel("r8", "settings.xml"),
        ];
        let body: String =
            (1..=8).map(|i| format!("<w:p><w:r>{}</w:r></w:p>", drawing(&format!("r{i}"), "label"))).collect();
        let bytes = with_pictures(&body, &targets.concat());
        let (_, blocks) = open_zip(bytes.clone());
        assert_eq!(blocks.len(), 8);
        assert!(texts(&blocks).iter().all(|t| t == "PIC - [label]"), "{:?}", texts(&blocks));

        let mut session = crate::engine(Limits::default()).open(Cursor::new(bytes)).unwrap();
        assert_eq!(session.resource("word/media/a b.png").unwrap(), PNG);
        for (name, kind) in [
            ("word/document.xml", ErrorKind::UnsupportedFormat),
            ("word/media/chart.emf", ErrorKind::UnsupportedFormat),
            ("../../etc/passwd", ErrorKind::UnsupportedFormat),
            ("../word/media/a b.png", ErrorKind::UnsupportedFormat),
            ("word/media/missing.png", ErrorKind::Malformed),
            ("/etc/shadow.png", ErrorKind::Malformed),
        ] {
            assert_eq!(session.resource(name).unwrap_err().kind, kind, "{name}");
        }
    }

    #[test]
    fn oversized_pictures_are_refused() {
        let big = vec![0u8; 16 * 1024 * 1024 + 1];
        let bytes = zip_with(
            &[("_rels/.rels", RELS), ("word/document.xml", b"<w:document/>"), ("word/media/big.png", &big)],
            false,
        );
        let mut session = crate::engine(Limits::default()).open(Cursor::new(bytes)).unwrap();
        assert_eq!(session.resource("word/media/big.png").unwrap_err().kind, ErrorKind::LimitExceeded);
    }

    #[test]
    fn pictures_in_table_cells_are_skipped_and_deleted_ones_dropped() {
        let picture = drawing("rId1", "alt");
        let body = format!(
            r#"<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Cell</w:t></w:r><w:r>{picture}</w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:del><w:r>{picture}</w:r></w:del><w:r><w:t>Text</w:t></w:r></w:p>"#
        );
        let (_, blocks) = open_zip(with_pictures(&body, &rel("rId1", "media/a%20b.png")));
        assert_eq!(texts(&blocks), ["R Cell", "P Text"]);
    }

    #[test]
    fn group_pictures_each_appear_and_charts_add_nothing() {
        let group = r#"<w:p><w:r><w:drawing><wp:inline><wp:extent cx="100" cy="100"/><wpg:wgp><a:blip r:embed="rId1"/><a:blip r:embed="rId1"/></wpg:wgp></wp:inline></w:drawing></w:r></w:p>"#;
        let chart = r#"<w:p><w:r><w:drawing><wp:inline><wp:extent cx="100" cy="100"/><c:chart r:id="rId1"/></wp:inline></w:drawing></w:r><w:r><w:t>Chart</w:t></w:r></w:p>"#;
        let (_, blocks) = open_zip(with_pictures(&format!("{group}{chart}"), &rel("rId1", "media/a%20b.png")));
        assert_eq!(texts(&blocks), ["PIC word/media/a b.png []", "PIC word/media/a b.png []", "P Chart"]);
        let Block::Image { width, .. } = &blocks[0] else { panic!() };
        assert_eq!(*width, None);
    }

    #[test]
    fn pictures_can_be_read_in_the_middle_of_the_document() {
        // Hard to compress and larger than every read buffer, so the part is
        // still being read from the file when the pictures are requested.
        let paragraphs: String = (0..20_000u32)
            .map(|i| format!("<w:p><w:r><w:t>{:x}</w:t></w:r></w:p>", i.wrapping_mul(2_654_435_761)))
            .collect();
        let body = format!("<w:p><w:r>{}</w:r></w:p>{paragraphs}", drawing("rId1", ""));
        let rels = format!("<Relationships>{}</Relationships>", rel("rId1", "media/a.png"));
        let document = format!(r#"<w:document><w:body>{body}</w:body></w:document>"#);
        for deflate in [false, true] {
            let bytes = zip_with(
                &[
                    ("_rels/.rels", RELS),
                    ("word/document.xml", document.as_bytes()),
                    ("word/_rels/document.xml.rels", rels.as_bytes()),
                    ("word/media/a.png", PNG),
                ],
                deflate,
            );
            let limits = Limits { max_batch_bytes: 512, ..Limits::default() };
            let mut session = crate::engine(limits).open(Cursor::new(bytes)).unwrap();
            let mut blocks = Vec::new();
            while let Some(batch) = session.next_batch().unwrap() {
                assert_eq!(session.resource("word/media/a.png").unwrap(), PNG);
                blocks.extend(batch.blocks);
            }
            assert_eq!(texts(&blocks).first().unwrap(), "PIC word/media/a.png []");
            assert_eq!(blocks.len(), 20_001);
            let Block::Paragraph { text } = &blocks[20_000] else { panic!() };
            assert_eq!(text.text, format!("{:x}", 19_999u32.wrapping_mul(2_654_435_761)));
            assert_eq!(session.resource("word/media/a.png").unwrap(), PNG);
        }
    }

    /// A package with the given main body, extra relationships (ids `x0`, `x1`...) and parts.
    fn package(body: &str, rels: &[(&str, &str)], parts: &[(&str, &str)]) -> Vec<u8> {
        let document = format!(r#"<w:document xmlns:w="w" xmlns:r="r"><w:body>{body}</w:body></w:document>"#);
        let rels: String = rels
            .iter()
            .enumerate()
            .map(|(i, (kind, target))| format!(r#"<Relationship Id="x{i}" Type="http://x/{kind}" Target="{target}"/>"#))
            .collect();
        let rels = format!("<Relationships>{rels}</Relationships>");
        let mut entries: Vec<(&str, &[u8])> = vec![
            ("[Content_Types].xml", b"<Types/>"),
            ("_rels/.rels", RELS),
            ("word/document.xml", document.as_bytes()),
            ("word/_rels/document.xml.rels", rels.as_bytes()),
        ];
        entries.extend(parts.iter().map(|(name, text)| (*name, text.as_bytes())));
        zip(&entries)
    }

    fn run(text: &str) -> String {
        format!(r#"<w:p><w:r><w:t xml:space="preserve">{text}</w:t></w:r></w:p>"#)
    }

    #[test]
    fn paragraph_styles_give_lists_headings_and_quotes() {
        let styles = r#"<w:styles>
<w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="ListBullet"><w:name w:val="List Bullet"/><w:basedOn w:val="Normal"/><w:pPr><w:numPr><w:numId w:val="4"/></w:numPr></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="ListNumber"><w:name w:val="List Number"/><w:pPr><w:numPr><w:numId w:val="3"/></w:numPr></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Steps"><w:name w:val="Steps"/><w:basedOn w:val="ListNumber"/></w:style>
<w:style w:type="paragraph" w:styleId="berschrift1"><w:name w:val="heading 1"/></w:style>
<w:style w:type="paragraph" w:styleId="Chapter"><w:name w:val="Chapter"/><w:pPr><w:outlineLvl w:val="0"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="IntenseQuote"><w:name w:val="Intense Quote"/></w:style>
<w:style w:type="paragraph" w:styleId="LoopA"><w:basedOn w:val="LoopB"/></w:style>
<w:style w:type="paragraph" w:styleId="LoopB"><w:basedOn w:val="LoopA"/></w:style>
<w:style w:type="character" w:styleId="ListNumberChar"><w:name w:val="List Number Char"/></w:style>
</w:styles>"#;
        let styled = |style: &str, text: &str| {
            format!(r#"<w:p><w:pPr><w:pStyle w:val="{style}"/></w:pPr><w:r><w:t>{text}</w:t></w:r></w:p>"#)
        };
        let body = [
            styled("ListBullet", "Dot"),
            styled("ListNumber", "One"),
            styled("Steps", "Two"),
            // The paragraph's own level wins; numId 0 turns the style's list off.
            r#"<w:p><w:pPr><w:pStyle w:val="ListNumber"/><w:numPr><w:ilvl w:val="1"/></w:numPr></w:pPr><w:r><w:t>Sub</w:t></w:r></w:p>"#.into(),
            r#"<w:p><w:pPr><w:pStyle w:val="ListNumber"/><w:numPr><w:numId w:val="0"/></w:numPr></w:pPr><w:r><w:t>Plain</w:t></w:r></w:p>"#.into(),
            styled("berschrift1", "Kapitel"),
            styled("Chapter", "Outline"),
            styled("IntenseQuote", "Said"),
            styled("LoopA", "Loop"),
            styled("Missing", "Unknown"),
        ]
        .concat();
        let bytes = package(
            &body,
            &[("numbering", "numbering.xml"), ("styles", "styles.xml")],
            &[("word/numbering.xml", std::str::from_utf8(NUMBERING).unwrap()), ("word/styles.xml", styles)],
        );
        assert_eq!(
            texts(&open_zip(bytes).1),
            [
                "L0 Dot",
                "L0[1.] One",
                "L0[2.] Two",
                "L1[a.] Sub",
                "P Plain",
                "H2 Kapitel",
                "H2 Outline",
                "Q Said",
                "P Loop",
                "P Unknown"
            ]
        );
    }

    #[test]
    fn a_broken_styles_part_is_ignored() {
        let body = r#"<w:p><w:pPr><w:pStyle w:val="ListBullet"/></w:pPr><w:r><w:t>Text</w:t></w:r></w:p>"#;
        let bytes = package(body, &[("styles", "styles.xml")], &[("word/styles.xml", "<w:styles><w:style")]);
        assert_eq!(texts(&open_zip(bytes).1), ["P Text"]);
    }

    fn note(element: &str, id: &str, text: &str) -> String {
        format!(
            r#"<w:{element} w:id="{id}"><w:p><w:r><w:{element}Ref/></w:r><w:r><w:t xml:space="preserve"> {text}</w:t></w:r></w:p><w:p/></w:{element}>"#
        )
    }

    const SEPARATORS: &str = r#"<w:footnote w:type="separator" w:id="-1"><w:p><w:r><w:t>SEP</w:t></w:r></w:p></w:footnote><w:footnote w:type="continuationSeparator" w:id="0"><w:p><w:r><w:t>CONT</w:t></w:r></w:p></w:footnote>"#;

    fn footnote_ref(id: &str) -> String {
        format!(r#"<w:r><w:footnoteReference w:id="{id}"/></w:r>"#)
    }

    #[test]
    fn notes_get_one_sequence_and_follow_the_body() {
        let body = format!(
            r#"<w:p><w:r><w:t>One</w:t></w:r>{}<w:r><w:t xml:space="preserve"> two</w:t></w:r><w:r><w:endnoteReference w:id="7"/></w:r><w:r><w:t xml:space="preserve"> three</w:t></w:r>{}</w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Cell</w:t></w:r>{}</w:p></w:tc></w:tr></w:tbl>"#,
            footnote_ref("2"),
            footnote_ref("3"),
            footnote_ref("2"),
        );
        let footnotes = format!(
            "<w:footnotes>{SEPARATORS}{}{}{}</w:footnotes>",
            note("footnote", "2", "First &amp; note"),
            note("footnote", "3", "Second"),
            note("footnote", "99", "Nothing refers to this"),
        );
        let endnotes = format!("<w:endnotes>{}</w:endnotes>", note("endnote", "7", "An endnote"));
        let doc = package(
            &body,
            &[("footnotes", "footnotes.xml"), ("endnotes", "endnotes.xml")],
            &[("word/footnotes.xml", &footnotes), ("word/endnotes.xml", &endnotes)],
        );
        assert_eq!(
            texts(&open_zip(doc).1),
            [
                "P One¹ two² three³",
                "R Cell¹",
                "S Footnotes",
                "P ¹ First & note",
                "P ³ Second",
                "S Endnotes",
                "P ² An endnote",
            ]
        );
    }

    #[test]
    fn numbers_past_nine_are_superscript() {
        let body: String = (1..=12).map(|i| format!("<w:p>{}</w:p>", footnote_ref(&i.to_string()))).collect();
        let notes: String = (1..=12).map(|i| note("footnote", &i.to_string(), "n")).collect();
        let doc = package(
            &body,
            &[("footnotes", "footnotes.xml")],
            &[("word/footnotes.xml", &format!("<w:footnotes>{notes}</w:footnotes>"))],
        );
        assert!(texts(&open_zip(doc).1).contains(&"P ¹² n".to_owned()));
    }

    #[test]
    fn a_note_can_have_several_paragraphs_a_link_and_a_picture() {
        let footnotes = format!(
            r#"<w:footnotes><w:footnote w:id="1"><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Head</w:t></w:r></w:p><w:p><w:hyperlink r:id="l"><w:r><w:t>site</w:t></w:r></w:hyperlink></w:p><w:p><w:r>{}</w:r></w:p></w:footnote></w:footnotes>"#,
            drawing("p", "pic")
        );
        let document = format!("<w:document><w:body><w:p>{}</w:p></w:body></w:document>", footnote_ref("1"));
        let doc = zip(&[
            ("_rels/.rels", RELS),
            ("word/document.xml", document.as_bytes()),
            (
                "word/_rels/document.xml.rels",
                br#"<Relationships><Relationship Id="f" Type="http://x/footnotes" Target="footnotes.xml"/></Relationships>"#,
            ),
            ("word/footnotes.xml", footnotes.as_bytes()),
            (
                "word/_rels/footnotes.xml.rels",
                br#"<Relationships><Relationship Id="l" Type="http://x/hyperlink" Target="https://example.com" TargetMode="External"/><Relationship Id="p" Type="http://x/image" Target="media/a.png"/></Relationships>"#,
            ),
            ("word/media/a.png", PNG),
        ]);
        let (_, blocks) = open_zip(doc);
        assert_eq!(texts(&blocks), ["P ¹", "S Footnotes", "P ¹ Head", "P site", "PIC word/media/a.png [pic]"]);
        let Block::Paragraph { text } = &blocks[3] else { panic!() };
        assert_eq!(
            text.spans,
            [Span { start: 0, end: 4, style: 0, link: Some("https://example.com".into()), color: None }]
        );
    }

    #[test]
    fn comments_follow_as_quotes_with_their_authors() {
        let body = r#"<w:p><w:r><w:t>Text</w:t></w:r><w:r><w:commentReference w:id="4"/></w:r></w:p><w:p><w:r><w:commentReference w:id="5"/></w:r></w:p>"#;
        let comments = r#"<w:comments><w:comment w:id="4" w:author="Jane &amp; Co" w:initials="J"><w:p><w:r><w:annotationRef/></w:r><w:r><w:t>Check this</w:t></w:r></w:p><w:p><w:r><w:t>Second line</w:t></w:r></w:p></w:comment><w:comment w:id="5"><w:p><w:r><w:t>No author</w:t></w:r></w:p></w:comment><w:comment w:id="6" w:author="Bob"><w:p><w:r><w:t>Not anchored</w:t></w:r></w:p></w:comment></w:comments>"#;
        let doc = package(body, &[("comments", "comments.xml")], &[("word/comments.xml", comments)]);
        let (_, blocks) = open_zip(doc);
        assert_eq!(
            texts(&blocks),
            ["P Text[C1]", "P [C2]", "S Comments", "Q [C1] Jane & Co: Check this", "Q Second line", "Q [C2] No author"]
        );
        let Block::Quote { text } = &blocks[3] else { panic!() };
        assert_eq!(text.spans, [Span { start: 0, end: 16, style: BOLD, link: None, color: None }]);
    }

    #[test]
    fn first_nonempty_default_header_and_footer_show_once() {
        // Section one's default header is blank, so section two's is used;
        // first-page and even-page parts never are, even when listed first.
        let first = r#"<w:sectPr><w:headerReference w:type="first" r:id="x0"/><w:headerReference w:type="even" r:id="x0"/><w:headerReference w:type="default" r:id="x1"/><w:footerReference w:type="default" r:id="x3"/></w:sectPr>"#;
        let last = r#"<w:sectPr><w:headerReference w:type="default" r:id="x2"/><w:headerReference w:type="default" r:id="x2"/><w:footerReference w:type="default" r:id="x3"/></w:sectPr>"#;
        let body = format!(r#"<w:p><w:pPr>{first}</w:pPr><w:r><w:t>Body</w:t></w:r></w:p>{}{last}"#, run("More"));
        let doc = package(
            &body,
            &[
                ("header", "header1.xml"),
                ("header", "header2.xml"),
                ("header", "header3.xml"),
                ("footer", "footer1.xml"),
            ],
            &[
                ("word/header1.xml", &format!("<w:hdr>{}</w:hdr>", run("First page only"))),
                ("word/header2.xml", "<w:hdr><w:p/><w:p><w:r><w:t> </w:t></w:r></w:p></w:hdr>"),
                ("word/header3.xml", &format!("<w:hdr>{}</w:hdr>", run("Letterhead"))),
                ("word/footer1.xml", &format!("<w:ftr>{}</w:ftr>", run("Confidential"))),
            ],
        );
        assert_eq!(
            texts(&open_zip(doc).1),
            ["S Header", "P Letterhead", "---", "P Body", "P More", "S Footer", "P Confidential"]
        );
    }

    #[test]
    fn a_header_table_counts_only_if_it_has_text_and_body_numbering_is_untouched() {
        let body = format!(
            r#"{}<w:sectPr><w:headerReference w:type="default" r:id="x1"/><w:headerReference w:type="default" r:id="x2"/></w:sectPr>"#,
            item(3, 0, "First")
        );
        let doc = package(
            &body,
            &[("numbering", "numbering.xml"), ("header", "header1.xml"), ("header", "header2.xml")],
            &[
                ("word/numbering.xml", std::str::from_utf8(NUMBERING).unwrap()),
                ("word/header1.xml", "<w:hdr><w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></w:hdr>"),
                ("word/header2.xml", &format!("<w:hdr>{}</w:hdr>", item(3, 0, "In header"))),
            ],
        );
        assert_eq!(texts(&open_zip(doc).1), ["S Header", "P In header", "---", "L0[1.] First"]);
    }

    #[test]
    fn tracked_changes_read_as_accepted() {
        let body = r#"<w:p><w:r><w:t xml:space="preserve">Kept </w:t></w:r><w:ins w:id="1" w:author="A"><w:r><w:t>inserted</w:t></w:r></w:ins><w:del w:id="2" w:author="A"><w:r><w:delText>deleted</w:delText></w:r></w:del><w:moveFrom w:id="3" w:author="A"><w:r><w:t>moved away</w:t></w:r></w:moveFrom><w:moveTo w:id="4" w:author="A"><w:r><w:t xml:space="preserve"> moved here</w:t></w:r></w:moveTo></w:p><w:p><w:pPr><w:rPr><w:del w:id="5" w:author="A"/></w:rPr></w:pPr><w:r><w:t>Mark deleted</w:t></w:r></w:p><w:tbl><w:tr><w:trPr><w:del w:id="6" w:author="A"/></w:trPr><w:tc><w:p><w:r><w:t>Gone</w:t></w:r></w:p></w:tc></w:tr><w:tr><w:trPr><w:ins w:id="7" w:author="A"/></w:trPr><w:tc><w:p><w:r><w:t>New row</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"#;
        // A note referred to only from deleted text stays hidden too.
        let footnote_in_deletion = format!("<w:p><w:del>{}</w:del></w:p>", footnote_ref("1"));
        let doc = package(
            &format!("{body}{footnote_in_deletion}"),
            &[("footnotes", "footnotes.xml")],
            &[("word/footnotes.xml", &format!("<w:footnotes>{}</w:footnotes>", note("footnote", "1", "Hidden")))],
        );
        assert_eq!(texts(&open_zip(doc).1), ["P Kept inserted moved here", "P Mark deleted", "R New row", "P "]);
    }

    #[test]
    fn hostile_references_and_notes_do_not_fail_the_document() {
        let long_id = "9".repeat(5000);
        let body = format!(
            r#"<w:p><w:r><w:t>A</w:t></w:r>{}{}{}{}<w:r><w:footnoteReference/></w:r><w:r><w:commentReference w:id="404"/></w:r></w:p>"#,
            footnote_ref("18446744073709551616000"),
            footnote_ref(&long_id),
            footnote_ref("-1"),
            footnote_ref("5"),
        );
        let footnotes = format!(
            r#"<w:footnotes>{SEPARATORS}<w:p><w:r><w:t>Stray</w:t></w:r></w:p>{}{}<w:footnote w:id="{long_id}"><w:p><w:r><w:t>Huge</w:t></w:r></w:p></w:footnote></w:footnotes>"#,
            note("footnote", "5", "Real"),
            note("footnote", "5", "Duplicate"),
        );
        let doc = package(
            &body,
            &[("footnotes", "footnotes.xml"), ("comments", "comments.xml")],
            &[("word/footnotes.xml", &footnotes), ("word/comments.xml", "<w:comments/>")],
        );
        assert_eq!(
            texts(&open_zip(doc).1),
            [
                "P A¹²³[C1]",
                "S Footnotes",
                "P ³ Real",
                "P Notes not found in this document: 1, 2",
                "S Comments",
                "Q Comments not found in this document: C1",
            ]
        );
    }

    #[test]
    fn a_flood_of_references_stays_bounded() {
        let refs: String = (0..20_500).map(|i| format!(r#"<w:r><w:footnoteReference w:id="{i}"/></w:r>"#)).collect();
        let doc = package(&format!("<w:p>{refs}</w:p>"), &[("footnotes", "footnotes.xml")], &[]);
        let got = texts(&open_zip(doc).1);
        let last = got.last().unwrap();
        assert!(last.ends_with(&format!("and {} more", 20_000 - 50)), "{last}");
        assert_eq!(got.iter().filter(|t| *t == "S Footnotes").count(), 1);
    }

    #[test]
    fn missing_or_malformed_extra_parts_do_not_fail_the_document() {
        let body = format!(
            r#"<w:p><w:r><w:t>Body</w:t></w:r>{}<w:r><w:endnoteReference w:id="1"/></w:r><w:r><w:commentReference w:id="1"/></w:r></w:p><w:sectPr><w:headerReference w:type="default" r:id="x3"/><w:footerReference w:type="default" r:id="x4"/></w:sectPr>"#,
            footnote_ref("1"),
        );
        // Footnotes break after one note; endnotes are not XML; comments and
        // the header do not exist; the footer's relationships part is broken.
        let broken_notes = format!(
            r#"<w:footnotes>{}<w:footnote w:id="2"><w:p><w:r><w:t>cut</w:t></a"#,
            note("footnote", "1", "Good")
        );
        let document = format!("<w:document><w:body>{body}</w:body></w:document>");
        let doc = zip(&[
            ("_rels/.rels", RELS),
            ("word/document.xml", document.as_bytes()),
            (
                "word/_rels/document.xml.rels",
                br#"<Relationships><Relationship Id="x0" Type="http://x/footnotes" Target="footnotes.xml"/><Relationship Id="x1" Type="http://x/endnotes" Target="endnotes.xml"/><Relationship Id="x2" Type="http://x/comments" Target="comments.xml"/><Relationship Id="x3" Type="http://x/header" Target="missing.xml"/><Relationship Id="x4" Type="http://x/footer" Target="footer1.xml"/></Relationships>"#,
            ),
            ("word/footnotes.xml", broken_notes.as_bytes()),
            ("word/endnotes.xml", b"<w:endnotes><<<not xml"),
            ("word/footer1.xml", b"<w:ftr><w:p><w:r><w:t>Foot</w:t></w:r></w:p></w:ftr>"),
            ("word/_rels/footer1.xml.rels", b"<<<"),
        ]);
        assert_eq!(
            texts(&open_zip(doc).1),
            [
                "P Body¹²[C1]",
                "S Footnotes",
                "P ¹ Good",
                "S Endnotes",
                "P Notes not found in this document: 2",
                "S Comments",
                "Q Comments not found in this document: C1",
                "S Footer",
                "P Foot",
            ]
        );
    }

    #[test]
    fn extra_parts_cannot_repeat_the_body() {
        let body = format!(
            r#"{}<w:p>{}</w:p><w:sectPr><w:headerReference w:type="default" r:id="x1"/></w:sectPr>"#,
            run("Once"),
            footnote_ref("1")
        );
        let doc = package(&body, &[("footnotes", "document.xml"), ("header", "document.xml")], &[]);
        assert_eq!(texts(&open_zip(doc).1), ["P Once", "P ¹", "S Footnotes", "P Notes not found in this document: 1"]);
    }

    #[test]
    fn the_body_and_its_notes_still_stream() {
        // Batches stay small through the header pass and the extra parts, and
        // pictures can be read in the middle of a note just as in the body.
        let paragraphs: String = (0..20_000u32).map(|i| run(&format!("{:x}", i.wrapping_mul(2_654_435_761)))).collect();
        let body = format!(
            r#"<w:p>{}</w:p>{paragraphs}<w:sectPr><w:headerReference w:type="default" r:id="x1"/></w:sectPr>"#,
            footnote_ref("1")
        );
        let footnotes = format!(
            r#"<w:footnotes><w:footnote w:id="1"><w:p><w:r><w:t>N</w:t></w:r><w:r>{}</w:r></w:p>{}</w:footnote></w:footnotes>"#,
            drawing("p", ""),
            run("After")
        );
        let document = format!("<w:document><w:body>{body}</w:body></w:document>");
        let bytes = zip_with(
            &[
                ("_rels/.rels", RELS),
                ("word/document.xml", document.as_bytes()),
                (
                    "word/_rels/document.xml.rels",
                    br#"<Relationships><Relationship Id="x0" Type="http://x/footnotes" Target="footnotes.xml"/><Relationship Id="x1" Type="http://x/header" Target="header1.xml"/></Relationships>"#,
                ),
                ("word/footnotes.xml", footnotes.as_bytes()),
                (
                    "word/_rels/footnotes.xml.rels",
                    br#"<Relationships><Relationship Id="p" Type="http://x/image" Target="media/a.png"/></Relationships>"#,
                ),
                ("word/header1.xml", b"<w:hdr><w:p><w:r><w:t>Head</w:t></w:r></w:p></w:hdr>"),
                ("word/media/a.png", PNG),
            ],
            true,
        );
        let limits = Limits { max_batch_bytes: 512, ..Limits::default() };
        let mut session = crate::engine(limits).open(Cursor::new(bytes)).unwrap();
        let mut blocks = Vec::new();
        let mut batches = 0;
        while let Some(batch) = session.next_batch().unwrap() {
            batches += 1;
            assert_eq!(session.resource("word/media/a.png").unwrap(), PNG);
            blocks.extend(batch.blocks);
        }
        assert!(batches > 100, "{batches} batches");
        let got = texts(&blocks);
        assert_eq!(got[..4], ["S Header", "P Head", "---", "P ¹"]);
        assert_eq!(got[got.len() - 4..], ["S Footnotes", "P ¹ N", "PIC word/media/a.png []", "P After"]);
    }

    #[test]
    fn a_broken_body_still_fails_the_document() {
        let body = r#"<w:sectPr><w:headerReference w:type="default" r:id="x0"/></w:sectPr><w:p w:broken=""#;
        let broken = package(
            body,
            &[("header", "header1.xml")],
            &[("word/header1.xml", "<w:hdr><w:p><w:r><w:t>Head</w:t></w:r></w:p></w:hdr>")],
        );
        assert_eq!(open_zip_err(broken).kind, ErrorKind::Malformed);
    }
}
