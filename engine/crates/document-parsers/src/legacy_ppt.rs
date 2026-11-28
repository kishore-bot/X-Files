//! PowerPoint 97-2003 (`.ppt`): slides as designed, and speaker notes.
//!
//! Each slide is a [`Block::Section`], then a [`Block::Slide`] canvas of the
//! deck's page size and the slide's background, then a [`Block::Shape`] per
//! drawn element, as PPTX and ODP produce: positions, fills, outlines,
//! pictures and text with its sizes, colours, bold, italic, underline,
//! alignment and bullets (see [`crate::legacy_ppt_draw`]). The master's
//! own graphics are drawn under the slide's, its colour scheme fills in
//! scheme colours, and its placeholders and text styles fill in what the
//! slide's placeholders leave out. Notes follow as quotes.
//!
//! Slides are found the way PowerPoint finds them: `Current User` names the
//! newest edit, each edit's persist directory maps ids to records, and the
//! document's slide list gives the order. Superseded versions of a slide kept
//! by fast save are never read. A slide whose record is missing, or that has
//! no drawing, falls back to its text from the slide list.
//!
//! Approximate: custom shapes draw as rectangles, gradients and patterns as
//! their first colour, lines ignore flips, metafile (EMF/WMF/PICT) and TIFF
//! pictures are not shown, and the deck's fonts, line spacing, indents,
//! tables, charts, animations and transitions are dropped. Encrypted
//! presentations are refused with a typed error.

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::Arc;

use document_core::{Block, CancelToken, EngineError, ErrorKind, Result, Rich};

use crate::cfb::{CfbFile, MAX_STREAM_BYTES, le16, le32};
use crate::legacy_ppt_draw::{
    Blip, DEFAULT_SCHEME, Drawing, MAX_SHAPES, Props, RawShape, ShapeContext, TEXT_CENTER_TITLE, TEXT_NOTES,
    TEXT_TITLE, TextCollector, TextContext, TextItem, TextSource, TextStyles, background, child, children, clean,
    picture_file, read_blips, read_defaults, read_drawing, read_fonts, read_scheme, to_shape, units,
};
use crate::stream::BlockSource;

const RT_DOCUMENT: u16 = 0x03E8;
const RT_DOCUMENT_ATOM: u16 = 0x03E9;
const RT_SLIDE: u16 = 0x03EE;
const RT_SLIDE_ATOM: u16 = 0x03EF;
const RT_SLIDE_PERSIST: u16 = 0x03F3;
const RT_ENVIRONMENT: u16 = 0x03F2;
const RT_MAIN_MASTER: u16 = 0x03F8;
const RT_DRAWING_GROUP: u16 = 0x040B;
const RT_DRAWING: u16 = 0x040C;
const RT_FONT_COLLECTION: u16 = 0x07D5;
const RT_SLIDE_LIST: u16 = 0x0FF0;
const RT_USER_EDIT: u16 = 0x0FF5;
const RT_PERSIST_DIRECTORY: u16 = 0x1772;

const SLIDE_LIST_SLIDES: u16 = 0;
const SLIDE_LIST_MASTERS: u16 = 1;
const SLIDE_LIST_NOTES: u16 = 2;

// SlideAtom flags: the slide follows its master's objects, scheme and background.
const FOLLOW_OBJECTS: u16 = 0x1;
const FOLLOW_SCHEME: u16 = 0x2;
const FOLLOW_BACKGROUND: u16 = 0x4;

const ENCRYPTED_TOKEN: u32 = 0xF3D1_C4DF;
const MAX_CURRENT_USER_BYTES: u64 = 1024 * 1024;
const MAX_PERSIST_ENTRIES: usize = 1 << 20;
const MAX_EDITS: usize = 4096;
const MAX_DEPTH: u32 = 32;
const MAX_WALK_RECORDS: u32 = 1 << 20;
/// Far above any real slide; bounds what one hostile slide can queue.
const MAX_SLIDE_TEXT_BYTES: usize = 4 * 1024 * 1024;
/// A title master names a main master; nothing goes deeper.
const MAX_MASTER_DEPTH: u32 = 2;
/// Pictures larger than this are not served.
const MAX_PICTURE_BYTES: u32 = 16 * 1024 * 1024;
/// PowerPoint's default page, 10 by 7.5 inches.
const DEFAULT_SIZE: (f32, f32) = (720.0, 540.0);

fn damaged(detail: &str) -> EngineError {
    EngineError::malformed(format!("the PowerPoint file is damaged: {detail}"))
}

#[derive(Debug, Clone, Copy)]
pub(crate) struct Header {
    pub is_container: bool,
    pub instance: u16,
    pub kind: u16,
    pub body: usize,
    pub end: usize,
}

/// The record at `at`, if it fits inside `limit`.
pub(crate) fn header(data: &[u8], at: usize, limit: usize) -> Option<Header> {
    let head = data.get(at..at.checked_add(8)?)?;
    let options = u16::from_le_bytes([head[0], head[1]]);
    let kind = u16::from_le_bytes([head[2], head[3]]);
    let len = u32::from_le_bytes([head[4], head[5], head[6], head[7]]) as usize;
    let body = at + 8;
    let end = body.checked_add(len)?;
    (end <= limit).then_some(Header { is_container: options & 0x0F == 0x0F, instance: options >> 4, kind, body, end })
}

#[derive(Debug, Clone, Default)]
struct SlideEntry {
    persist: u32,
    /// The id other records refer to the slide by.
    id: u32,
    texts: Vec<TextItem>,
}

/// What a slide takes from its master.
#[derive(Default)]
struct Master {
    scheme: Option<[u32; 8]>,
    styles: TextStyles,
    background: Option<RawShape>,
    /// Graphics drawn under every slide that follows the master's objects.
    objects: Vec<RawShape>,
    placeholders: Vec<RawShape>,
}

pub(crate) struct PptSource {
    data: Vec<u8>,
    cfb: CfbFile,
    persist: HashMap<u32, u32>,
    slides: VecDeque<SlideEntry>,
    slide_count: u32,
    counted: bool,
    notes: HashMap<u32, Vec<TextItem>>,
    /// Notes pages and masters by the ids slides name them with.
    notes_ids: HashMap<u32, u32>,
    master_ids: HashMap<u32, u32>,
    masters: HashMap<u32, Arc<Master>>,
    size: (f32, f32),
    fonts: Vec<String>,
    /// The document's default styles, under each master's.
    styles: TextStyles,
    defaults: Props,
    blips: Vec<Blip>,
    number: u32,
    cancel: CancelToken,
}

impl PptSource {
    pub fn open(mut cfb: CfbFile, cancel: CancelToken) -> Result<Self> {
        let encrypted = || {
            EngineError::new(
                ErrorKind::UnsupportedEncryption,
                "This PowerPoint 97-2003 file uses an older kind of password protection that isn't supported.",
            )
        };
        if cfb.contains("EncryptedSummary") {
            return Err(encrypted());
        }
        let user = cfb.read_stream("Current User", MAX_CURRENT_USER_BYTES)?;
        if le32(&user, 12) == Some(ENCRYPTED_TOKEN) {
            return Err(encrypted());
        }
        let current_edit = le32(&user, 16).ok_or_else(|| damaged("the current user record is cut off"))?;
        let data = cfb.read_stream("PowerPoint Document", MAX_STREAM_BYTES)?;
        let (persist, document) = read_edits(&data, current_edit)?;
        cancel.check()?;

        let document = persist.get(&document).ok_or_else(|| damaged("the document record is missing"))?;
        let record = header(&data, *document as usize, data.len())
            .filter(|h| h.kind == RT_DOCUMENT)
            .ok_or_else(|| damaged("the document record is missing"))?;

        let mut source = Self {
            data: Vec::new(),
            cfb,
            persist,
            slides: VecDeque::new(),
            slide_count: 0,
            counted: false,
            notes: HashMap::new(),
            notes_ids: HashMap::new(),
            master_ids: HashMap::new(),
            masters: HashMap::new(),
            size: DEFAULT_SIZE,
            fonts: Vec::new(),
            styles: TextStyles::default(),
            defaults: Props::default(),
            blips: Vec::new(),
            number: 0,
            cancel,
        };
        for part in children(&data, record) {
            match part.kind {
                RT_DOCUMENT_ATOM => source.size = slide_size(&data, part).unwrap_or(DEFAULT_SIZE),
                RT_ENVIRONMENT => {
                    source.styles = TextStyles::read(&data, part);
                    if let Some(fonts) = child(&data, part, RT_FONT_COLLECTION) {
                        source.fonts = read_fonts(&data, fonts);
                    }
                }
                RT_DRAWING_GROUP => {
                    source.defaults = read_defaults(&data, part);
                    source.blips = read_blips(&data, part);
                }
                RT_SLIDE_LIST => {
                    let entries = read_slide_list(&data, part);
                    match part.instance {
                        SLIDE_LIST_SLIDES => source.slides.extend(entries),
                        SLIDE_LIST_MASTERS => source.master_ids.extend(ids(&entries)),
                        SLIDE_LIST_NOTES => {
                            source.notes_ids.extend(ids(&entries));
                            source.notes.extend(entries.into_iter().map(|e| (e.persist, e.texts)));
                        }
                        _ => {}
                    }
                }
                _ => {}
            }
        }
        source.slide_count = source.slides.len() as u32;
        source.data = data;
        Ok(source)
    }

    fn persist_record(&self, id: u32) -> Option<Header> {
        let offset = *self.persist.get(&id)?;
        header(&self.data, offset as usize, self.data.len())
    }

    /// The persist id of a record named by its slide id; files that store the
    /// persist id itself are read too.
    fn by_id(ids: &HashMap<u32, u32>, id: u32) -> u32 {
        ids.get(&id).copied().unwrap_or(id)
    }

    /// Text items anywhere inside the record stored under `id`.
    fn shape_texts(&self, id: u32, into: &mut Vec<TextItem>) {
        if let Some(record) = self.persist_record(id) {
            let mut texts = TextCollector::default();
            let mut budget = MAX_WALK_RECORDS;
            walk(&self.data, record, 0, &mut budget, &mut texts);
            into.extend(texts.items);
        }
    }

    /// The master stored under `persist`, read once. A title master takes
    /// what it lacks from the main master it names.
    fn master(&mut self, persist: u32, depth: u32) -> Option<Arc<Master>> {
        if let Some(master) = self.masters.get(&persist) {
            return Some(master.clone());
        }
        let record = self.persist_record(persist).filter(|r| matches!(r.kind, RT_MAIN_MASTER | RT_SLIDE))?;
        let atom = slide_atom(&self.data, record);
        let parent = atom
            .filter(|_| depth < MAX_MASTER_DEPTH)
            .map(|a| Self::by_id(&self.master_ids, a.master))
            .filter(|&p| p != persist && p != 0)
            .and_then(|p| self.master(p, depth + 1));
        let data = &self.data;
        let drawing = read_drawing(data, record, RT_DRAWING).unwrap_or_default();
        let flags = atom.map_or(FOLLOW_OBJECTS | FOLLOW_SCHEME | FOLLOW_BACKGROUND, |a| a.flags);
        let mut master = Master {
            scheme: read_scheme(data, record, 1).or_else(|| parent.as_ref().and_then(|p| p.scheme)),
            styles: TextStyles::read(data, record)
                .or(&parent.as_ref().map_or(self.styles.clone(), |p| p.styles.clone())),
            background: drawing.background.or_else(|| parent.as_ref().and_then(|p| p.background.clone())),
            ..Master::default()
        };
        if let Some(parent) = parent.as_ref().filter(|_| flags & FOLLOW_OBJECTS != 0) {
            master.objects.extend(parent.objects.iter().cloned());
        }
        for mut shape in drawing.shapes {
            if shape.placeholder.is_some() {
                master.placeholders.push(shape);
            } else {
                // Their outline text belongs to the master, not to the slides.
                if matches!(shape.text, Some(TextSource::Outline(_))) {
                    shape.text = None;
                }
                master.objects.push(shape);
            }
        }
        if let Some(parent) = &parent {
            master.placeholders.extend(parent.placeholders.iter().cloned());
        }
        master.objects.truncate(MAX_SHAPES);
        master.placeholders.truncate(MAX_SHAPES);
        let master = Arc::new(master);
        self.masters.insert(persist, master.clone());
        Some(master)
    }

    /// The slide as designed; `false` if it has no drawing to show.
    fn draw_slide(&mut self, record: Header, slide: &SlideEntry, out: &mut VecDeque<Block>) -> bool {
        let atom = slide_atom(&self.data, record);
        let flags = atom.map_or(FOLLOW_OBJECTS | FOLLOW_SCHEME | FOLLOW_BACKGROUND, |a| a.flags);
        let master = atom
            .map(|a| Self::by_id(&self.master_ids, a.master))
            .filter(|&p| p != 0)
            .and_then(|p| self.master(p, 0))
            .unwrap_or_default();
        let data = &self.data;
        let Some(drawing) = read_drawing(data, record, RT_DRAWING) else { return false };
        let own_scheme = read_scheme(data, record, 1).filter(|_| flags & FOLLOW_SCHEME == 0);
        let scheme = own_scheme.or(master.scheme).unwrap_or(DEFAULT_SCHEME);
        let styles = if master.styles.is_empty() { &self.styles } else { &master.styles };
        let ctx = ShapeContext {
            text: TextContext { data, styles, scheme: &scheme, fonts: &self.fonts },
            defaults: &self.defaults,
            blips: &self.blips,
            outline: &slide.texts,
            placeholders: &master.placeholders,
            slide_number: self.number,
        };
        let Drawing { shapes, background: own_background } = drawing;
        let shown = if flags & FOLLOW_BACKGROUND != 0 { master.background.as_ref() } else { own_background.as_ref() };
        let (color, image) = shown.or(master.background.as_ref()).map(|b| background(b, &ctx)).unwrap_or_default();
        out.push_back(Block::Slide {
            width: self.size.0,
            height: self.size.1,
            background: Some(color.unwrap_or(scheme[0])),
            background_image: image,
            transition: None,
        });
        let mut budget = MAX_SLIDE_TEXT_BYTES;
        let behind = if flags & FOLLOW_OBJECTS != 0 { master.objects.as_slice() } else { &[] };
        for raw in behind.iter().chain(&shapes) {
            if let Some(shape) = to_shape(raw, &ctx, &mut budget) {
                out.push_back(Block::Shape(Box::new(shape)));
            }
        }
        true
    }

    /// The slide's text alone, for a slide that cannot be drawn.
    fn slide_text(&self, slide: &SlideEntry, out: &mut VecDeque<Block>) {
        let mut texts = slide.texts.clone();
        self.shape_texts(slide.persist, &mut texts);
        let mut budget = MAX_SLIDE_TEXT_BYTES;
        for text in &texts {
            for line in lines(&self.data, text, &mut budget) {
                out.push_back(match text.text_type {
                    TEXT_TITLE | TEXT_CENTER_TITLE => Block::Heading { level: 2, text: Rich::plain(line) },
                    _ => Block::paragraph(line),
                });
            }
        }
    }
}

/// Slide ids mapped to persist ids.
fn ids(entries: &[SlideEntry]) -> Vec<(u32, u32)> {
    entries.iter().filter(|e| e.id != 0).map(|e| (e.id, e.persist)).collect()
}

/// The page size from the `DocumentAtom`, in points.
fn slide_size(data: &[u8], atom: Header) -> Option<(f32, f32)> {
    let field = |at| le32(data, atom.body + at).filter(|_| atom.body + at + 4 <= atom.end).map(|v| v as i32);
    let points = |v: i32| (v > 0 && v <= 576 * 200).then_some(v as f32 / 8.0);
    Some((points(field(0)?)?, points(field(4)?)?))
}

#[derive(Debug, Clone, Copy)]
struct SlideAtom {
    master: u32,
    notes: u32,
    flags: u16,
}

fn slide_atom(data: &[u8], record: Header) -> Option<SlideAtom> {
    let atom = child(data, record, RT_SLIDE_ATOM)?;
    let field = |at| le32(data, atom.body + at).filter(|_| atom.body + at + 4 <= atom.end);
    let flags = le16(data, atom.body + 20).filter(|_| atom.body + 22 <= atom.end);
    Some(SlideAtom {
        master: field(12).unwrap_or(0),
        notes: field(16).unwrap_or(0),
        flags: flags.unwrap_or(FOLLOW_OBJECTS | FOLLOW_SCHEME | FOLLOW_BACKGROUND),
    })
}

impl BlockSource for PptSource {
    fn pump(&mut self, out: &mut VecDeque<Block>) -> Result<bool> {
        self.cancel.check()?;
        if !self.counted {
            self.counted = true;
            out.push_back(Block::Count { total: self.slide_count });
        }
        let Some(slide) = self.slides.pop_front() else {
            return Ok(true);
        };
        self.number += 1;
        out.push_back(Block::Section { title: format!("Slide {}", self.number) });

        let record = self.persist_record(slide.persist).filter(|r| r.kind == RT_SLIDE);
        let drawn = record.is_some_and(|r| self.draw_slide(r, &slide, out));
        if !drawn {
            self.slide_text(&slide, out);
        }

        let notes = record.and_then(|r| slide_atom(&self.data, r)).map(|a| a.notes).filter(|&n| n != 0);
        if let Some(id) = notes.map(|n| Self::by_id(&self.notes_ids, n)) {
            let mut notes = self.notes.remove(&id).unwrap_or_default();
            self.shape_texts(id, &mut notes);
            let mut budget = MAX_SLIDE_TEXT_BYTES;
            for text in notes.iter().filter(|t| t.text_type == TEXT_NOTES) {
                for line in lines(&self.data, text, &mut budget) {
                    out.push_back(Block::Quote { text: Rich::plain(line) });
                }
            }
        }
        Ok(false)
    }

    /// Pictures from the `Pictures` stream, named `pictures/<store index>.<ext>`.
    fn resource(&mut self, name: &str) -> Result<Vec<u8>> {
        let missing = || EngineError::unsupported("this presentation has no such picture");
        let index = name
            .strip_prefix("pictures/")
            .and_then(|rest| rest.split_once('.'))
            .and_then(|(index, _)| index.parse::<usize>().ok())
            .filter(|&i| i > 0)
            .ok_or_else(missing)?;
        let blip = *self.blips.get(index - 1).ok_or_else(missing)?;
        if blip.extension().is_none() || !self.cfb.contains("Pictures") {
            return Err(missing());
        }
        let head = self.cfb.read_stream_range("Pictures", u64::from(blip.offset), 8)?;
        let len = le32(&head, 4).unwrap_or(0);
        if len > MAX_PICTURE_BYTES {
            return Err(EngineError::limit("the picture is too large"));
        }
        let mut record = head;
        record.extend(self.cfb.read_stream_range("Pictures", u64::from(blip.offset) + 8, len as usize)?);
        picture_file(&record).ok_or_else(|| EngineError::unsupported("this picture's format can't be shown"))
    }
}

/// Walks the edits from newest to oldest. The newest entry for an id wins.
/// Returns the persist map and the document's persist id.
fn read_edits(data: &[u8], newest: u32) -> Result<(HashMap<u32, u32>, u32)> {
    let mut persist = HashMap::new();
    let mut seen = HashSet::new();
    let mut document = None;
    let mut next = newest;
    loop {
        if !seen.insert(next) {
            return Err(damaged("its edit history loops"));
        }
        if seen.len() > MAX_EDITS {
            return Err(EngineError::limit("the PowerPoint file has too many saved edits"));
        }
        let edit = header(data, next as usize, data.len())
            .filter(|h| h.kind == RT_USER_EDIT)
            .ok_or_else(|| damaged("an edit record is missing"))?;
        let field = |at| le32(data, edit.body + at).filter(|_| edit.body + at + 4 <= edit.end);
        let previous = field(8).ok_or_else(|| damaged("an edit record is cut off"))?;
        let directory = field(12).ok_or_else(|| damaged("an edit record is cut off"))?;
        let document_id = field(16).ok_or_else(|| damaged("an edit record is cut off"))?;
        document.get_or_insert(document_id);
        read_persist_directory(data, directory, &mut persist)?;
        if previous == 0 {
            break;
        }
        next = previous;
    }
    Ok((persist, document.unwrap_or(0)))
}

fn read_persist_directory(data: &[u8], at: u32, persist: &mut HashMap<u32, u32>) -> Result<()> {
    let record = header(data, at as usize, data.len())
        .filter(|h| h.kind == RT_PERSIST_DIRECTORY)
        .ok_or_else(|| damaged("a persist directory is missing"))?;
    let mut at = record.body;
    while at + 4 <= record.end {
        let word = le32(data, at).ok_or_else(|| damaged("a persist directory is cut off"))?;
        at += 4;
        let first = word & 0x000F_FFFF;
        let count = (word >> 20) as usize;
        if at + count * 4 > record.end {
            return Err(damaged("a persist directory is cut off"));
        }
        for i in 0..count {
            let offset = le32(data, at + i * 4).ok_or_else(|| damaged("a persist directory is cut off"))?;
            persist.entry(first + i as u32).or_insert(offset);
        }
        if persist.len() > MAX_PERSIST_ENTRIES {
            return Err(EngineError::limit("the PowerPoint file has too many records"));
        }
        at += count * 4;
    }
    Ok(())
}

/// One slide list: each `SlidePersistAtom` starts a slide; the text after it is that slide's.
fn read_slide_list(data: &[u8], list: Header) -> Vec<SlideEntry> {
    let mut entries: Vec<SlideEntry> = Vec::new();
    let mut texts = TextCollector::default();
    for child in children(data, list) {
        if child.kind == RT_SLIDE_PERSIST {
            if let Some(persist) = le32(data, child.body).filter(|_| child.body + 4 <= child.end) {
                if let Some(entry) = entries.last_mut() {
                    entry.texts = std::mem::take(&mut texts.items);
                }
                let id = le32(data, child.body + 12).filter(|_| child.body + 16 <= child.end).unwrap_or(0);
                entries.push(SlideEntry { persist, id, texts: Vec::new() });
            }
        } else if !entries.is_empty() {
            texts.take(data, child);
        }
    }
    if let Some(entry) = entries.last_mut() {
        entry.texts = texts.items;
    }
    entries
}

/// Collects text items under `record`, depth first. A record that overruns its
/// parent ends the walk of that parent; what was found so far is kept.
fn walk(data: &[u8], record: Header, depth: u32, budget: &mut u32, into: &mut TextCollector) {
    if depth > MAX_DEPTH {
        return;
    }
    for child in children(data, record) {
        if *budget == 0 {
            return;
        }
        *budget -= 1;
        if !into.take(data, child) && child.is_container {
            walk(data, child, depth + 1, budget, into);
        }
    }
}

/// The paragraphs of one text item. `budget` is what this slide may still emit.
fn lines(data: &[u8], text: &TextItem, budget: &mut usize) -> Vec<String> {
    let units = units(data, text);
    let mut out = Vec::new();
    for paragraph in units.split(|&u| u == u16::from(b'\r') || u == u16::from(b'\n')) {
        let clean = clean(paragraph);
        let clean = clean.trim();
        if clean.is_empty() {
            continue;
        }
        if clean.len() > *budget {
            return out;
        }
        *budget -= clean.len();
        out.push(clean.to_owned());
    }
    out
}

#[cfg(test)]
pub(crate) mod testing {
    //! Writes minimal `.ppt` streams for fixtures.
    use super::*;
    use crate::cfb::testing::cfb;
    use crate::legacy_ppt_draw::{RT_TEXT_BYTES, RT_TEXT_CHARS, RT_TEXT_HEADER};

    const RT_NOTES: u16 = 0x03F0;

    pub fn atom(kind: u16, instance: u16, body: &[u8]) -> Vec<u8> {
        let mut out = (instance << 4).to_le_bytes().to_vec();
        out.extend_from_slice(&kind.to_le_bytes());
        out.extend_from_slice(&(body.len() as u32).to_le_bytes());
        out.extend_from_slice(body);
        out
    }

    pub fn container(kind: u16, instance: u16, children: &[Vec<u8>]) -> Vec<u8> {
        let body = children.concat();
        let mut out = ((instance << 4) | 0x0F).to_le_bytes().to_vec();
        out.extend_from_slice(&kind.to_le_bytes());
        out.extend_from_slice(&(body.len() as u32).to_le_bytes());
        out.extend_from_slice(&body);
        out
    }

    pub fn wide(text: &str) -> Vec<u8> {
        atom(RT_TEXT_CHARS, 0, &text.encode_utf16().flat_map(u16::to_le_bytes).collect::<Vec<_>>())
    }

    pub fn narrow(text: &[u8]) -> Vec<u8> {
        atom(RT_TEXT_BYTES, 0, text)
    }

    pub fn text_header(text_type: u32) -> Vec<u8> {
        atom(RT_TEXT_HEADER, 0, &text_type.to_le_bytes())
    }

    pub fn slide_persist(id: u32) -> Vec<u8> {
        slide_persist_with_id(id, 0)
    }

    /// A slide list entry: the record's persist id and the id other records name it by.
    pub fn slide_persist_with_id(persist: u32, id: u32) -> Vec<u8> {
        let mut body = persist.to_le_bytes().to_vec();
        body.extend_from_slice(&[0; 8]);
        body.extend_from_slice(&id.to_le_bytes());
        body.extend_from_slice(&[0; 4]);
        atom(RT_SLIDE_PERSIST, 0, &body)
    }

    /// A shape's text, nested in a drawing as PowerPoint stores it.
    pub fn textbox(children: &[Vec<u8>]) -> Vec<u8> {
        let shape = container(0xF004, 0, &[container(0xF00D, 0, children)]);
        drawing(&[shape])
    }

    /// A drawing of `shapes`, under the patriarch group shape.
    pub fn drawing(shapes: &[Vec<u8>]) -> Vec<u8> {
        let patriarch = container(0xF004, 0, &[fsp(0, 0x5)]);
        let mut group = vec![patriarch];
        group.extend_from_slice(shapes);
        drawing_with(container(0xF003, 0, &group), None)
    }

    pub fn drawing_with(group: Vec<u8>, background: Option<Vec<u8>>) -> Vec<u8> {
        let mut parts = vec![atom(0xF008, 0, &[0; 8]), group];
        parts.extend(background);
        container(RT_DRAWING, 0, &[container(0xF002, 0, &parts)])
    }

    pub fn fsp(kind: u16, flags: u32) -> Vec<u8> {
        let mut body = 1024u32.to_le_bytes().to_vec();
        body.extend_from_slice(&flags.to_le_bytes());
        atom(0xF00A, kind, &body)
    }

    pub fn fopt(props: &[(u16, u32)]) -> Vec<u8> {
        let body: Vec<u8> = props
            .iter()
            .flat_map(|(id, value)| [id.to_le_bytes().to_vec(), value.to_le_bytes().to_vec()].concat())
            .collect();
        atom(0xF00B, props.len() as u16, &body)
    }

    /// The short anchor, in master units (576 per inch).
    pub fn anchor(top: i16, left: i16, right: i16, bottom: i16) -> Vec<u8> {
        atom(0xF010, 0, &[top, left, right, bottom].iter().flat_map(|v| v.to_le_bytes()).collect::<Vec<_>>())
    }

    pub fn rect(kind: u16, left: i32, top: i32, right: i32, bottom: i32) -> Vec<u8> {
        atom(kind, 0, &[left, top, right, bottom].iter().flat_map(|v| v.to_le_bytes()).collect::<Vec<_>>())
    }

    pub fn placeholder(kind: u8) -> Vec<u8> {
        container(0xF011, 0, &[atom(0x0BC3, 0, &[0, 0, 0, 0, kind, 0, 0, 0])])
    }

    pub fn client_text(children: &[Vec<u8>]) -> Vec<u8> {
        container(0xF00D, 0, children)
    }

    pub fn outline_ref(index: u32) -> Vec<u8> {
        atom(0x0F9E, 0, &index.to_le_bytes())
    }

    pub fn shape(children: &[Vec<u8>]) -> Vec<u8> {
        container(0xF004, 0, children)
    }

    /// A `StyleTextPropAtom` from `(count, level, paragraph exception)` and
    /// `(count, character exception)` runs, exceptions already encoded.
    pub fn style_text(paras: &[(u32, u16, Vec<u8>)], chars: &[(u32, Vec<u8>)]) -> Vec<u8> {
        let mut body = Vec::new();
        for (count, level, pf) in paras {
            body.extend_from_slice(&count.to_le_bytes());
            body.extend_from_slice(&level.to_le_bytes());
            body.extend_from_slice(pf);
        }
        for (count, cf) in chars {
            body.extend_from_slice(&count.to_le_bytes());
            body.extend_from_slice(cf);
        }
        atom(0x0FA1, 0, &body)
    }

    /// A paragraph exception with only an alignment, or nothing.
    pub fn pf(align: Option<u16>) -> Vec<u8> {
        match align {
            Some(a) => [0x800u32.to_le_bytes().to_vec(), a.to_le_bytes().to_vec()].concat(),
            None => 0u32.to_le_bytes().to_vec(),
        }
    }

    /// A paragraph exception with a bullet.
    pub fn pf_bullet(code: u16) -> Vec<u8> {
        [0x81u32.to_le_bytes().to_vec(), 1u16.to_le_bytes().to_vec(), code.to_le_bytes().to_vec()].concat()
    }

    /// A character exception: style bits (bold 1, italic 2, underline 4), size, colour.
    pub fn cf(style: Option<u16>, size: Option<u16>, color: Option<u32>) -> Vec<u8> {
        let mut masks = 0u32;
        let mut body = Vec::new();
        if let Some(style) = style {
            masks |= 0x7;
            body.extend_from_slice(&style.to_le_bytes());
        }
        if let Some(size) = size {
            masks |= 0x2_0000;
            body.extend_from_slice(&size.to_le_bytes());
        }
        if let Some(color) = color {
            masks |= 0x4_0000;
            body.extend_from_slice(&color.to_le_bytes());
        }
        [masks.to_le_bytes().to_vec(), body].concat()
    }

    pub fn document_atom(width: i32, height: i32) -> Vec<u8> {
        let mut body = width.to_le_bytes().to_vec();
        body.extend_from_slice(&height.to_le_bytes());
        body.extend_from_slice(&[0; 32]);
        atom(RT_DOCUMENT_ATOM, 0, &body)
    }

    pub fn color_scheme(colors: [u32; 8]) -> Vec<u8> {
        let body: Vec<u8> = colors.iter().flat_map(|c| [(c >> 16) as u8, (c >> 8) as u8, *c as u8, 0]).collect();
        atom(0x07F0, 1, &body)
    }

    /// One level of a master text style.
    pub fn master_style(text_type: u16, pf: Vec<u8>, cf: Vec<u8>) -> Vec<u8> {
        let mut body = 1u16.to_le_bytes().to_vec();
        if text_type >= 5 {
            body.extend_from_slice(&0u16.to_le_bytes());
        }
        body.extend(pf);
        body.extend(cf);
        atom(0x0FA3, text_type, &body)
    }

    pub fn slide_atom(notes: u32) -> Vec<u8> {
        slide_atom_full(0, notes, FOLLOW_OBJECTS | FOLLOW_SCHEME | FOLLOW_BACKGROUND)
    }

    pub fn slide_atom_full(master: u32, notes: u32, flags: u16) -> Vec<u8> {
        let mut body = vec![0u8; 12];
        body.extend_from_slice(&master.to_le_bytes());
        body.extend_from_slice(&notes.to_le_bytes());
        body.extend_from_slice(&flags.to_le_bytes());
        body.extend_from_slice(&[0; 2]);
        atom(RT_SLIDE_ATOM, 0, &body)
    }

    pub fn slide(notes: u32, children: &[Vec<u8>]) -> Vec<u8> {
        let mut all = vec![slide_atom(notes)];
        all.extend_from_slice(children);
        container(RT_SLIDE, 0, &all)
    }

    pub fn notes_page(children: &[Vec<u8>]) -> Vec<u8> {
        container(RT_NOTES, 0, children)
    }

    pub fn current_user(offset: u32, token: u32) -> Vec<u8> {
        let mut out = atom(0x0FF6, 0, &[0; 20]);
        out[12..16].copy_from_slice(&token.to_le_bytes());
        out[16..20].copy_from_slice(&offset.to_le_bytes());
        out
    }

    /// Lays out records under persist ids 1.., then one edit that maps them.
    /// Record 1 must be the document. Returns the stream and the edit's offset.
    pub fn stream(records: &[Vec<u8>]) -> (Vec<u8>, u32) {
        let mut data = Vec::new();
        let mut directory = ((records.len() as u32) << 20 | 1).to_le_bytes().to_vec();
        for record in records {
            directory.extend_from_slice(&(data.len() as u32).to_le_bytes());
            data.extend_from_slice(record);
        }
        let directory_at = data.len() as u32;
        data.extend(atom(RT_PERSIST_DIRECTORY, 0, &directory));
        let edit_at = data.len() as u32;
        data.extend(user_edit(0, directory_at, 1));
        (data, edit_at)
    }

    pub fn user_edit(previous: u32, directory: u32, document: u32) -> Vec<u8> {
        let mut body = vec![0u8; 8];
        body.extend_from_slice(&previous.to_le_bytes());
        body.extend_from_slice(&directory.to_le_bytes());
        body.extend_from_slice(&document.to_le_bytes());
        body.extend_from_slice(&[0; 8]);
        atom(RT_USER_EDIT, 0, &body)
    }

    pub fn ppt_file(records: &[Vec<u8>]) -> Vec<u8> {
        let (data, edit) = stream(records);
        cfb(&[("Current User", &current_user(edit, 0xE391_C05F)), ("PowerPoint Document", &data)])
    }

    pub fn simple_ppt() -> Vec<u8> {
        let list = container(RT_SLIDE_LIST, 0, &[slide_persist(2), text_header(0), wide("Hello slide")]);
        ppt_file(&[container(RT_DOCUMENT, 0, &[list]), slide(0, &[])])
    }

    /// A designed deck: a master with a scheme, a title style, a background,
    /// a logo bar and a title placeholder; one slide with a title, an ellipse
    /// with styled text, and a group holding a text box.
    pub fn designed_records() -> Vec<Vec<u8>> {
        const MASTER_ID: u32 = 0x8000_0001;
        let title_style = style_text(&[(6, 0, pf(Some(1)))], &[(6, cf(Some(1), Some(40), Some(0xFE00_00FF)))]);
        let slides = container(
            RT_SLIDE_LIST,
            SLIDE_LIST_SLIDES,
            &[slide_persist_with_id(2, 256), text_header(0), wide("Title"), title_style],
        );
        let masters = container(RT_SLIDE_LIST, SLIDE_LIST_MASTERS, &[slide_persist_with_id(3, MASTER_ID)]);
        let document = container(RT_DOCUMENT, 0, &[document_atom(5760, 4320), slides, masters]);

        let title =
            shape(&[fsp(1, 0xA00), anchor(576, 576, 2880, 1152), placeholder(13), client_text(&[outline_ref(0)])]);
        let ellipse_style = style_text(
            &[(3, 0, pf(None)), (6, 1, pf(Some(2)))],
            &[(3, cf(Some(2), None, None)), (6, cf(None, Some(24), Some(0x0500_0000)))],
        );
        let ellipse = shape(&[
            fsp(3, 0xA00),
            fopt(&[(0x181, 0x0000_FF00), (0x1C0, 0x0800_0001), (0x1CB, 25_400)]),
            anchor(1440, 1440, 2880, 2880),
            client_text(&[text_header(4), wide("Hi\rthere"), ellipse_style]),
        ]);
        let group = container(
            0xF003,
            0,
            &[
                shape(&[fsp(0, 0x201), rect(0xF009, 0, 0, 100, 100), anchor(0, 0, 800, 800)]),
                shape(&[fsp(202, 0xA02), rect(0xF00F, 10, 20, 30, 60), client_text(&[text_header(4), wide("g")])]),
            ],
        );
        let slide = container(
            RT_SLIDE,
            0,
            &[
                slide_atom_full(MASTER_ID, 0, FOLLOW_OBJECTS | FOLLOW_SCHEME | FOLLOW_BACKGROUND),
                drawing(&[title, ellipse, group]),
            ],
        );

        let scheme = [0xFFFFFF, 0x111111, 0x808080, 0x222222, 0xEEEEEE, 0x3366CC, 0x009999, 0x99CC00];
        let logo_bar = shape(&[fsp(1, 0xA00), fopt(&[(0x181, 0x0800_0005)]), anchor(4000, 0, 5760, 4320)]);
        let master_title = shape(&[
            fsp(1, 0xA00),
            fopt(&[(0x087, 1), (0x1BF, 0x0010_0000), (0x1FF, 0x0008_0000)]),
            anchor(0, 0, 5760, 1000),
            placeholder(1),
        ]);
        let patriarch = shape(&[fsp(0, 0x5)]);
        let back = shape(&[fsp(1, 0xC00), fopt(&[(0x181, 0x0040_3020)])]);
        let master = container(
            RT_MAIN_MASTER,
            0,
            &[
                slide_atom_full(0, 0, 0),
                color_scheme(scheme),
                master_style(0, pf(None), cf(None, Some(44), Some(0x0300_0000))),
                drawing_with(container(0xF003, 0, &[patriarch, logo_bar, master_title]), Some(back)),
            ],
        );
        vec![document, slide, master]
    }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use document_core::{Geometry, Limits, Shape};

    use super::testing::*;
    use super::*;
    use crate::cfb::testing::cfb;
    use crate::legacy_ppt_draw::{RT_TEXT_CHARS, TEXT_OTHER};
    use crate::testing::{open_zip, open_zip_err, texts};

    fn deck(records: &[Vec<u8>]) -> Vec<String> {
        let (format, blocks) = open_zip(ppt_file(records));
        assert_eq!(format, document_core::FormatId::Ppt);
        texts(&blocks)
    }

    fn shapes(blocks: &[Block]) -> Vec<&Shape> {
        blocks
            .iter()
            .filter_map(|b| match b {
                Block::Shape(shape) => Some(shape.as_ref()),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn draws_slides_as_designed() {
        let (_, blocks) = open_zip(ppt_file(&designed_records()));
        assert_eq!(
            texts(&blocks),
            ["COUNT 1", "S Slide 1", "SLIDE 720x540 -", "SH ", "SH Title", "SH Hi / there", "SH g"]
        );
        let Some(Block::Slide { background, .. }) = blocks.get(2) else { panic!("no canvas") };
        assert_eq!(*background, Some(0xFF20_3040));

        let shapes = shapes(&blocks);
        // The master's bar, in the master's scheme accent.
        let bar = shapes[0];
        assert_eq!((bar.x, bar.y, bar.width, bar.height), (0.0, 500.0, 720.0, 40.0));
        assert_eq!(bar.fill, Some(0xFF33_66CC));
        assert_eq!(bar.line, Some(0xFF11_1111));

        // The title: its frame from the slide, centred vertically like the
        // master's placeholder, styled by its runs over the master style.
        let title = shapes[1];
        assert_eq!((title.x, title.y, title.width, title.height), (72.0, 72.0, 288.0, 72.0));
        assert_eq!((title.anchor, title.fill, title.line), (1, None, None));
        let p = &title.paragraphs[0];
        assert_eq!((p.size, p.bold, p.italic, p.align, p.color), (40.0, true, false, 1, Some(0xFFFF_0000)));
        assert!(p.bullet.is_none());

        let ellipse = shapes[2];
        assert_eq!(ellipse.geometry, Geometry::Ellipse);
        assert_eq!((ellipse.x, ellipse.y, ellipse.width, ellipse.height), (180.0, 180.0, 180.0, 180.0));
        assert_eq!((ellipse.fill, ellipse.line, ellipse.line_width), (Some(0xFF00_FF00), Some(0xFF11_1111), 2.0));
        let [first, second] = &ellipse.paragraphs[..] else { panic!("two paragraphs") };
        assert_eq!((first.italic, first.size, first.color, first.align), (true, 18.0, Some(0xFF11_1111), 0));
        assert_eq!((second.size, second.color, second.align, second.level), (24.0, Some(0xFF33_66CC), 2, 1));

        // A text box in a group, placed through the group's coordinates.
        let boxed = shapes[3];
        assert_eq!((boxed.x, boxed.y, boxed.width, boxed.height), (10.0, 20.0, 20.0, 40.0));
        assert_eq!((boxed.fill, boxed.line), (None, None));
        assert_eq!(boxed.insets, [7.2, 3.6, 7.2, 3.6]);
    }

    #[test]
    fn a_slide_can_leave_out_its_masters_graphics_and_use_its_own_scheme() {
        let mut records = designed_records();
        let own = color_scheme([0x000000, 0xFFFFFF, 0, 0, 0, 0xFF8800, 0, 0]);
        let back = shape(&[fsp(1, 0xC00), fopt(&[(0x181, 0x0800_0000)])]);
        let patriarch = shape(&[fsp(0, 0x5)]);
        let bullets = style_text(&[(4, 0, pf_bullet(0x2013))], &[(4, cf(None, None, Some(0x0500_0000)))]);
        let body =
            shape(&[fsp(1, 0xA00), anchor(0, 0, 800, 800), client_text(&[text_header(1), wide("one"), bullets])]);
        records[1] = container(
            RT_SLIDE,
            0,
            &[
                slide_atom_full(0x8000_0001, 0, 0),
                own,
                drawing_with(container(0xF003, 0, &[patriarch, body]), Some(back)),
            ],
        );
        let (_, blocks) = open_zip(ppt_file(&records));
        assert_eq!(texts(&blocks), ["COUNT 1", "S Slide 1", "SLIDE 720x540 -", "SH – one"]);
        let Some(Block::Slide { background, .. }) = blocks.get(2) else { panic!("no canvas") };
        assert_eq!(*background, Some(0xFF00_0000));
        let shape = shapes(&blocks)[0];
        assert_eq!(shape.paragraphs[0].color, Some(0xFFFF_8800));
        // An ordinary shape has a fill and outline by default, from the scheme.
        assert_eq!((shape.fill, shape.line), (Some(0xFFFF_FFFF), Some(0xFFFF_FFFF)));
    }

    #[test]
    fn pictures_come_from_the_pictures_stream() {
        let mut fbse = vec![6u8, 6];
        fbse.extend_from_slice(&[0; 18]);
        fbse.extend_from_slice(&29u32.to_le_bytes()); // size
        fbse.extend_from_slice(&1u32.to_le_bytes());
        fbse.extend_from_slice(&3u32.to_le_bytes()); // offset in Pictures
        fbse.extend_from_slice(&[0; 4]);
        let store = container(0xF001, 1, &[atom(0xF007, 6, &fbse), atom(0xF007, 2, &[3; 36])]);
        let group = container(RT_DRAWING_GROUP, 0, &[container(0xF000, 0, &[store])]);
        let list = container(RT_SLIDE_LIST, 0, &[slide_persist(2)]);
        let document = container(RT_DOCUMENT, 0, &[group, list]);
        let picture = shape(&[fsp(75, 0xA00), fopt(&[(0x104, 1)]), anchor(0, 0, 80, 80)]);
        let metafile = shape(&[
            fsp(75, 0xA00),
            fopt(&[(0x104, 2), (0x1BF, 0x0010_0000), (0x1FF, 0x0008_0000)]),
            anchor(0, 0, 8, 8),
        ]);
        let slide = slide(0, &[drawing(&[picture, metafile])]);
        let (data, edit) = stream(&[document, slide]);
        let mut pictures = vec![0xAA; 3];
        let mut blip = atom(0xF01E, 0x6E0, &[[0u8; 17].to_vec(), b"PNGDATA".to_vec()].concat());
        pictures.append(&mut blip);
        let file = cfb(&[
            ("Current User", &current_user(edit, 0xE391_C05F)),
            ("PowerPoint Document", &data),
            ("Pictures", &pictures),
        ]);
        let mut session = crate::engine(Limits::default()).open(Cursor::new(file)).expect("opens");
        let mut blocks = Vec::new();
        while let Some(batch) = session.next_batch().expect("reads") {
            blocks.extend(batch.blocks);
        }
        assert_eq!(texts(&blocks), ["COUNT 1", "S Slide 1", "SLIDE 720x540 -", "IMG pictures/1.png"]);
        assert_eq!(session.resource("pictures/1.png").expect("picture"), b"PNGDATA");
        for name in ["pictures/2.png", "pictures/9.png", "pictures/0.png", "other", "pictures/x.png"] {
            assert!(session.resource(name).is_err(), "{name}");
        }
    }

    #[test]
    fn damaged_drawings_never_panic() {
        let (data, edit) = stream(&designed_records());
        let user = current_user(edit, 0xE391_C05F);
        for at in 0..data.len() {
            for value in [0x00, 0xFF, 0x7F] {
                let mut broken = data.clone();
                broken[at] = value;
                let file = cfb(&[("Current User", &user), ("PowerPoint Document", &broken)]);
                if let Ok(mut session) = crate::engine(Limits::default()).open(Cursor::new(file)) {
                    while let Ok(Some(_)) = session.next_batch() {}
                }
            }
        }
        // Cut short anywhere, the stream fails cleanly or shows what it has.
        for len in 0..data.len() {
            let file = cfb(&[("Current User", &user), ("PowerPoint Document", &data[..len])]);
            if let Ok(mut session) = crate::engine(Limits::default()).open(Cursor::new(file)) {
                while let Ok(Some(_)) = session.next_batch() {}
            }
        }
    }

    #[test]
    fn reads_slides_in_list_order_with_titles_body_and_shapes() {
        // Slides without a drawing show their text: from the slide list, then shapes.
        let list = container(
            RT_SLIDE_LIST,
            SLIDE_LIST_SLIDES,
            &[
                slide_persist(3),
                text_header(TEXT_TITLE),
                wide("Second record first"),
                text_header(1),
                narrow(b"caf\xE9\rtwo\x0Bbroken\r\r"),
                slide_persist(2),
                text_header(TEXT_CENTER_TITLE),
                wide("日本語 😀"),
            ],
        );
        let document =
            container(RT_DOCUMENT, 0, &[container(0x0FF0, 1, &[slide_persist(9), wide("master text")]), list]);
        let lines = deck(&[document, slide(0, &[]), slide(0, &[])]);
        assert_eq!(
            lines,
            ["COUNT 2", "S Slide 1", "H2 Second record first", "P café", "P two\nbroken", "S Slide 2", "H2 日本語 😀"]
        );
    }

    #[test]
    fn notes_become_quotes() {
        let slides = container(RT_SLIDE_LIST, SLIDE_LIST_SLIDES, &[slide_persist(2), wide("Body")]);
        let notes = container(RT_SLIDE_LIST, SLIDE_LIST_NOTES, &[slide_persist_with_id(3, 300)]);
        let lines = deck(&[
            container(RT_DOCUMENT, 0, &[slides, notes]),
            slide(300, &[]),
            notes_page(&[
                textbox(&[text_header(TEXT_NOTES), wide("Say this")]),
                textbox(&[text_header(TEXT_OTHER), wide("*")]),
            ]),
        ]);
        assert_eq!(lines, ["COUNT 1", "S Slide 1", "P Body", "Q Say this"]);
    }

    #[test]
    fn a_fast_save_keeps_only_the_newest_slide() {
        let list = container(RT_SLIDE_LIST, 0, &[slide_persist(2)]);
        let document = container(RT_DOCUMENT, 0, &[list]);
        let text = |t: &str| shape(&[fsp(202, 0xA00), anchor(0, 0, 80, 80), client_text(&[wide(t)])]);
        let old = slide(0, &[drawing(&[text("old")])]);
        let (mut data, first_edit) = stream(&[document, old]);
        let new_at = data.len() as u32;
        data.extend(slide(0, &[drawing(&[text("new")])]));
        let directory_at = data.len() as u32;
        let mut directory = (1u32 << 20 | 2).to_le_bytes().to_vec();
        directory.extend_from_slice(&new_at.to_le_bytes());
        data.extend(atom(RT_PERSIST_DIRECTORY, 0, &directory));
        let edit_at = data.len() as u32;
        data.extend(user_edit(first_edit, directory_at, 1));
        let file = cfb(&[("Current User", &current_user(edit_at, 0xE391_C05F)), ("PowerPoint Document", &data)]);
        let (_, blocks) = open_zip(file);
        assert_eq!(texts(&blocks), ["COUNT 1", "S Slide 1", "SLIDE 720x540 -", "SH new"]);
    }

    #[test]
    fn encrypted_presentations_get_a_typed_error() {
        let by_token = cfb(&[("Current User", &current_user(0, ENCRYPTED_TOKEN)), ("PowerPoint Document", b"x")]);
        assert_eq!(open_zip_err(by_token).kind, ErrorKind::UnsupportedEncryption);
        let by_stream = cfb(&[
            ("Current User", &current_user(0, 0xE391_C05F)),
            ("PowerPoint Document", b"x"),
            ("EncryptedSummary", b"x"),
        ]);
        assert_eq!(open_zip_err(by_stream).kind, ErrorKind::UnsupportedEncryption);
    }

    #[test]
    fn hostile_presentations_fail_cleanly() {
        let malformed = |user: &[u8], data: &[u8]| {
            let err = open_zip_err(cfb(&[("Current User", user), ("PowerPoint Document", data)]));
            assert_eq!(err.kind, ErrorKind::Malformed, "{err}");
        };
        let token = 0xE391_C05F;
        let good = container(RT_DOCUMENT, 0, &[]);
        let (data, edit) = stream(std::slice::from_ref(&good));

        // Cut-off current user; an edit outside the stream, or not at an edit.
        malformed(&[0; 10], &data);
        malformed(&current_user(0xFFFF_FFF0, token), &data);
        malformed(&current_user(0, token), &data);
        // Missing streams.
        let err = open_zip_err(cfb(&[("PowerPoint Document", &data)]));
        assert_eq!(err.kind, ErrorKind::Malformed);

        // An edit whose previous link points at itself.
        let mut looping = data.clone();
        let at = edit as usize + 8 + 8;
        looping[at..at + 4].copy_from_slice(&edit.to_le_bytes());
        malformed(&current_user(edit, token), &looping);

        // A persist directory that promises more entries than it holds.
        let mut cut = Vec::new();
        cut.extend(atom(RT_PERSIST_DIRECTORY, 0, &(4095u32 << 20 | 1).to_le_bytes()));
        let edit_at = cut.len() as u32;
        cut.extend(user_edit(0, 0, 1));
        malformed(&current_user(edit_at, token), &cut);

        // A document id with no record, or pointing at the wrong kind of record.
        let mut no_doc = Vec::new();
        no_doc.extend(atom(RT_PERSIST_DIRECTORY, 0, &[]));
        let edit_at = no_doc.len() as u32;
        no_doc.extend(user_edit(0, 0, 1));
        malformed(&current_user(edit_at, token), &no_doc);
        let (wrong, edit_at) = stream(&[atom(0x1234, 0, &[1, 2])]);
        malformed(&current_user(edit_at, token), &wrong);
    }

    #[test]
    fn hostile_records_stay_bounded() {
        // A text atom overrunning its parent ends that walk; the rest survives.
        let mut overrun = atom(RT_TEXT_CHARS, 0, &[]);
        overrun[4..8].copy_from_slice(&u32::MAX.to_le_bytes());
        let list = container(RT_SLIDE_LIST, 0, &[slide_persist(2), wide("kept"), overrun, wide("after")]);
        let lines = deck(&[container(RT_DOCUMENT, 0, &[list]), slide(0, &[])]);
        assert_eq!(lines, ["COUNT 1", "S Slide 1", "P kept"]);

        // A slide whose id has no record, and control characters in text.
        let list = container(RT_SLIDE_LIST, 0, &[slide_persist(77), narrow(b"\x01a\x02")]);
        assert_eq!(deck(&[container(RT_DOCUMENT, 0, &[list])]), ["COUNT 1", "S Slide 1", "P a"]);

        // Nesting past the depth cap is ignored rather than recursed into.
        let mut nested = wide("deep");
        for _ in 0..100 {
            nested = container(0x1000, 0, &[nested]);
        }
        let list = container(RT_SLIDE_LIST, 0, &[slide_persist(2)]);
        assert_eq!(deck(&[container(RT_DOCUMENT, 0, &[list]), slide(0, &[nested])]), ["COUNT 1", "S Slide 1"]);

        // Groups nested past the cap, and shapes without a frame, are dropped.
        let mut group = container(0xF003, 0, &[shape(&[fsp(1, 0xA00), anchor(0, 0, 8, 8)])]);
        for _ in 0..40 {
            group = container(0xF003, 0, &[shape(&[fsp(0, 0x201)]), group]);
        }
        let unframed = shape(&[fsp(1, 0xA00)]);
        let list = container(RT_SLIDE_LIST, 0, &[slide_persist(2)]);
        let slide_with = slide(0, &[drawing(&[group, unframed])]);
        assert_eq!(
            deck(&[container(RT_DOCUMENT, 0, &[list]), slide_with]),
            ["COUNT 1", "S Slide 1", "SLIDE 720x540 -"]
        );

        // One slide listed many times does not read past the session budget.
        let mut children = Vec::new();
        for _ in 0..2000 {
            children.push(slide_persist(2));
        }
        let list = container(RT_SLIDE_LIST, 0, &children);
        let text = shape(&[fsp(202, 0xA00), anchor(0, 0, 80, 80), client_text(&[wide("x")])]);
        let lines = deck(&[container(RT_DOCUMENT, 0, &[list]), slide(0, &[drawing(&[text])])]);
        assert_eq!(lines.len(), 1 + 3 * 2000);

        // An oversized slide is capped.
        let huge = "y".repeat(MAX_SLIDE_TEXT_BYTES / 2 + 10);
        let list = container(RT_SLIDE_LIST, 0, &[slide_persist(2), wide(&huge), wide(&huge), wide(&huge)]);
        let (_, blocks) = open_zip(ppt_file(&[container(RT_DOCUMENT, 0, &[list]), slide(0, &[])]));
        let total: usize = texts(&blocks).iter().map(String::len).sum();
        assert!(total <= MAX_SLIDE_TEXT_BYTES + 1024, "{total}");
    }

    #[test]
    fn a_document_with_no_slides_is_empty() {
        assert_eq!(deck(&[container(RT_DOCUMENT, 0, &[])]), ["COUNT 0"]);
    }

    #[test]
    fn simple_fixture_opens() {
        let (format, blocks) = open_zip(simple_ppt());
        assert_eq!(format, document_core::FormatId::Ppt);
        assert_eq!(texts(&blocks), ["COUNT 1", "S Slide 1", "H2 Hello slide"]);
    }
}
