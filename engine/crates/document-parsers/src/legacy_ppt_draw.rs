//! Slide drawings of PowerPoint 97-2003 (`.ppt`) as positioned shapes.
//!
//! A slide's `PPDrawing` holds an OfficeArt drawing: nested shape groups, each
//! shape with an anchor, a property table (fill, line, text insets, picture)
//! and its text, either inline or as a reference into the slide list. Text
//! runs are styled by a `StyleTextPropAtom` over the master's text styles for
//! the text's type, and colours may name an entry of the slide's colour
//! scheme. Everything here reads records found by [`crate::legacy_ppt`];
//! nothing is trusted, every walk is bounded, and a damaged record drops only
//! the part it describes.

use document_core::style::{BOLD, ITALIC, UNDERLINE};
use document_core::{Geometry, Shape, ShapeText};

use crate::cfb::{le16, le32};
use crate::legacy_ppt::{Header, header};
use crate::pptx::blank_shape;
use crate::rich::{RichBuilder, drop_color};

pub(crate) const RT_TEXT_HEADER: u16 = 0x0F9F;
pub(crate) const RT_TEXT_CHARS: u16 = 0x0FA0;
pub(crate) const RT_TEXT_BYTES: u16 = 0x0FA8;
const RT_STYLE_TEXT_PROP: u16 = 0x0FA1;
const RT_OUTLINE_TEXT_REF: u16 = 0x0F9E;
const RT_TEXT_MASTER_STYLE: u16 = 0x0FA3;
const RT_PLACEHOLDER: u16 = 0x0BC3;
const RT_COLOR_SCHEME: u16 = 0x07F0;
const RT_FONT_ENTITY: u16 = 0x0FB7;

const DGG_CONTAINER: u16 = 0xF000;
const BSTORE: u16 = 0xF001;
const DG_CONTAINER: u16 = 0xF002;
const SPGR_CONTAINER: u16 = 0xF003;
const SP_CONTAINER: u16 = 0xF004;
const FBSE: u16 = 0xF007;
const FSPGR: u16 = 0xF009;
const FSP: u16 = 0xF00A;
const FOPT: u16 = 0xF00B;
const CLIENT_TEXTBOX: u16 = 0xF00D;
const CHILD_ANCHOR: u16 = 0xF00F;
const CLIENT_ANCHOR: u16 = 0xF010;
const CLIENT_DATA: u16 = 0xF011;
const SECONDARY_FOPT: u16 = 0xF121;
const TERTIARY_FOPT: u16 = 0xF122;

pub(crate) const TEXT_TITLE: u32 = 0;
pub(crate) const TEXT_NOTES: u32 = 2;
pub(crate) const TEXT_OTHER: u32 = 4;
pub(crate) const TEXT_CENTER_TITLE: u32 = 6;

// FSP flags.
const SHAPE_GROUP: u32 = 0x1;
const SHAPE_PATRIARCH: u32 = 0x4;
const SHAPE_DELETED: u32 = 0x8;
const SHAPE_BACKGROUND: u32 = 0x400;

// Property ids.
const PROP_ROTATION: u16 = 0x0004;
const PROP_TEXT_LEFT: u16 = 0x0081;
const PROP_ANCHOR_TEXT: u16 = 0x0087;
const PROP_PICTURE: u16 = 0x0104;
const PROP_FILL_TYPE: u16 = 0x0180;
const PROP_FILL_COLOR: u16 = 0x0181;
const PROP_FILL_OPACITY: u16 = 0x0182;
const PROP_FILL_PICTURE: u16 = 0x0186;
const PROP_FILL_FLAGS: u16 = 0x01BF;
const PROP_LINE_COLOR: u16 = 0x01C0;
const PROP_LINE_WIDTH: u16 = 0x01CB;
const PROP_LINE_FLAGS: u16 = 0x01FF;
const PROP_GROUP_FLAGS: u16 = 0x03BF;

/// PowerPoint positions shapes in master units, 576 to the inch.
const MASTER_UNITS_PER_POINT: f32 = 8.0;
const EMU_PER_POINT: f32 = 12_700.0;
const LEVELS: usize = 5;
const MAX_PROPS: usize = 512;
const MAX_RUNS: usize = 65_536;
const MAX_PARAGRAPHS: usize = 4096;
/// Shapes kept per drawing; the rest of a hostile drawing is dropped.
pub(crate) const MAX_SHAPES: usize = 4096;
const MAX_GROUP_DEPTH: u32 = 16;
const MAX_BLIPS: usize = 65_536;
const MAX_FONTS: usize = 4096;

/// PowerPoint 2003's default colour scheme: background, text and lines,
/// shadows, title text, fills, accent, hyperlink, followed hyperlink.
pub(crate) const DEFAULT_SCHEME: [u32; 8] =
    [0xFFFF_FFFF, 0xFF00_0000, 0xFF80_8080, 0xFF00_0000, 0xFFBB_E0E3, 0xFF33_3399, 0xFF00_9999, 0xFF99_CC00];

/// The children of a container record, stopping at the first that overruns it.
pub(crate) fn children(data: &[u8], parent: Header) -> impl Iterator<Item = Header> + '_ {
    let mut at = parent.body;
    std::iter::from_fn(move || {
        let child = header(data, at, parent.end)?;
        at = child.end;
        Some(child)
    })
}

pub(crate) fn child(data: &[u8], parent: Header, kind: u16) -> Option<Header> {
    children(data, parent).find(|c| c.kind == kind)
}

/// Reads fields inside one record body.
struct Cursor<'a> {
    data: &'a [u8],
    at: usize,
    end: usize,
}

impl<'a> Cursor<'a> {
    fn new(data: &'a [u8], at: usize, end: usize) -> Self {
        Self { data, at, end: end.min(data.len()) }
    }

    fn skip(&mut self, n: usize) -> Option<()> {
        let next = self.at.checked_add(n).filter(|&next| next <= self.end)?;
        self.at = next;
        Some(())
    }

    fn u16(&mut self) -> Option<u16> {
        let value = le16(self.data, self.at)?;
        self.skip(2)?;
        Some(value)
    }

    fn u32(&mut self) -> Option<u32> {
        let value = le32(self.data, self.at)?;
        self.skip(4)?;
        Some(value)
    }
}

fn i32_at(data: &[u8], at: usize) -> Option<i32> {
    le32(data, at).map(|v| v as i32)
}

fn argb(r: u8, g: u8, b: u8) -> u32 {
    0xFF00_0000 | u32::from(r) << 16 | u32::from(g) << 8 | u32::from(b)
}

// ---------------------------------------------------------------- colours

#[derive(Debug, Clone, Copy, PartialEq)]
enum Color {
    Rgb(u32),
    /// An entry of the slide's colour scheme.
    Scheme(u8),
}

impl Color {
    fn resolve(self, scheme: &[u32; 8]) -> u32 {
        match self {
            Color::Rgb(c) => c,
            Color::Scheme(i) => scheme[usize::from(i) & 7],
        }
    }
}

/// A text colour (`ColorIndexStruct`): RGB, a scheme entry, or undefined.
fn color_index(value: u32) -> Option<Color> {
    let [r, g, b, index] = value.to_le_bytes();
    match index {
        0xFE => Some(Color::Rgb(argb(r, g, b))),
        0..=7 => Some(Color::Scheme(index)),
        _ => None,
    }
}

/// A shape colour (`OfficeArtCOLORREF`). System and palette colours are left
/// to the default.
fn color_ref(value: u32) -> Option<Color> {
    let [r, g, b, flags] = value.to_le_bytes();
    if flags & 0x08 != 0 {
        return (r < 8).then_some(Color::Scheme(r));
    }
    if flags & 0x11 != 0 {
        return None;
    }
    Some(Color::Rgb(argb(r, g, b)))
}

/// The eight colours of a `ColorSchemeAtom`, if `record` holds one with `instance`.
pub(crate) fn read_scheme(data: &[u8], record: Header, instance: u16) -> Option<[u32; 8]> {
    let atom = children(data, record).find(|c| c.kind == RT_COLOR_SCHEME && c.instance == instance)?;
    let bytes = data.get(atom.body..atom.body.checked_add(32)?).filter(|_| atom.end - atom.body >= 32)?;
    let mut scheme = [0; 8];
    for (slot, rgb) in scheme.iter_mut().zip(bytes.chunks_exact(4)) {
        *slot = argb(rgb[0], rgb[1], rgb[2]);
    }
    Some(scheme)
}

/// Font names of a `FontCollection`, in the order text refers to them.
pub(crate) fn read_fonts(data: &[u8], collection: Header) -> Vec<String> {
    children(data, collection)
        .filter(|c| c.kind == RT_FONT_ENTITY)
        .take(MAX_FONTS)
        .map(|font| {
            let units = data[font.body..font.end.min(font.body + 64)]
                .chunks_exact(2)
                .map(|b| u16::from_le_bytes([b[0], b[1]]))
                .take_while(|&u| u != 0);
            char::decode_utf16(units).map(|c| c.unwrap_or(char::REPLACEMENT_CHARACTER)).collect()
        })
        .collect()
}

// ---------------------------------------------------------------- text styles

#[derive(Debug, Clone, Copy, Default)]
struct Chars {
    bold: Option<bool>,
    italic: Option<bool>,
    underline: Option<bool>,
    size: Option<f32>,
    color: Option<Color>,
}

impl Chars {
    fn over(self, base: Chars) -> Chars {
        Chars {
            bold: self.bold.or(base.bold),
            italic: self.italic.or(base.italic),
            underline: self.underline.or(base.underline),
            size: self.size.or(base.size),
            color: self.color.or(base.color),
        }
    }
}

#[derive(Debug, Clone, Copy, Default)]
struct Para {
    bullet: Option<bool>,
    bullet_char: Option<u16>,
    bullet_font: Option<u16>,
    /// 0 left, 1 centre, 2 right, 3 justified.
    align: Option<u8>,
}

impl Para {
    fn over(self, base: Para) -> Para {
        Para {
            bullet: self.bullet.or(base.bullet),
            bullet_char: self.bullet_char.or(base.bullet_char),
            bullet_font: self.bullet_font.or(base.bullet_font),
            align: self.align.or(base.align),
        }
    }
}

#[derive(Debug, Clone, Copy, Default)]
struct Level {
    para: Para,
    chars: Chars,
}

impl Level {
    fn over(self, base: Level) -> Level {
        Level { para: self.para.over(base.para), chars: self.chars.over(base.chars) }
    }
}

/// A `TextPFException`: paragraph formatting, each field present when its mask bit is.
fn read_para(cur: &mut Cursor<'_>) -> Option<Para> {
    let masks = cur.u32()?;
    let mut para = Para::default();
    if masks & 0x0F != 0 {
        let flags = cur.u16()?;
        if masks & 0x01 != 0 {
            para.bullet = Some(flags & 0x01 != 0);
        }
    }
    if masks & 0x80 != 0 {
        para.bullet_char = Some(cur.u16()?);
    }
    if masks & 0x10 != 0 {
        para.bullet_font = Some(cur.u16()?);
    }
    if masks & 0x40 != 0 {
        cur.skip(2)?; // bullet size
    }
    if masks & 0x20 != 0 {
        cur.skip(4)?; // bullet colour
    }
    if masks & 0x800 != 0 {
        para.align = Some(match cur.u16()? {
            1 => 1,
            2 => 2,
            3..=6 => 3,
            _ => 0,
        });
    }
    // Line spacing, space before and after, left margin, indent, tab size.
    for bit in [0x1000, 0x2000, 0x4000, 0x100, 0x400, 0x8000] {
        if masks & bit != 0 {
            cur.skip(2)?;
        }
    }
    if masks & 0x10_0000 != 0 {
        let stops = cur.u16()?;
        cur.skip(usize::from(stops) * 4)?;
    }
    if masks & 0x1_0000 != 0 {
        cur.skip(2)?; // font alignment
    }
    if masks & 0xE_0000 != 0 {
        cur.skip(2)?; // wrapping
    }
    if masks & 0x20_0000 != 0 {
        cur.skip(2)?; // direction
    }
    Some(para)
}

/// A `TextCFException`: character formatting.
fn read_chars(cur: &mut Cursor<'_>) -> Option<Chars> {
    let masks = cur.u32()?;
    let mut chars = Chars::default();
    if masks & 0x3EB7 != 0 {
        let style = cur.u16()?;
        let flag = |bit: u32| (masks & bit != 0).then_some(u32::from(style) & bit != 0);
        chars.bold = flag(0x1);
        chars.italic = flag(0x2);
        chars.underline = flag(0x4);
    }
    // Typeface, old East Asian, ANSI and symbol fonts.
    for bit in [0x1_0000, 0x20_0000, 0x40_0000, 0x80_0000] {
        if masks & bit != 0 {
            cur.skip(2)?;
        }
    }
    if masks & 0x2_0000 != 0 {
        chars.size = Some(f32::from(cur.u16()?)).filter(|s| (1.0..=4000.0).contains(s));
    }
    if masks & 0x4_0000 != 0 {
        chars.color = color_index(cur.u32()?);
    }
    if masks & 0x8_0000 != 0 {
        cur.skip(2)?; // superscript or subscript
    }
    if masks & 0x10_0000 != 0 {
        cur.skip(4)?;
    }
    if masks & 0x100_0000 != 0 {
        cur.skip(2)?;
    }
    if masks & 0x200_0000 != 0 {
        cur.skip(2)?;
    }
    if masks & 0x400_0000 != 0 {
        cur.skip(4)?;
    }
    Some(chars)
}

/// A master's text styles, per text type (title, body, notes, other, …) and indent level.
#[derive(Debug, Clone, Default)]
pub(crate) struct TextStyles([Option<[Level; LEVELS]>; 9]);

impl TextStyles {
    /// The `TextMasterStyleAtom`s directly inside `record`.
    pub fn read(data: &[u8], record: Header) -> Self {
        let mut styles = TextStyles::default();
        for atom in children(data, record).filter(|c| c.kind == RT_TEXT_MASTER_STYLE) {
            let Some(slot) = styles.0.get_mut(usize::from(atom.instance)) else { continue };
            let mut cur = Cursor::new(data, atom.body, atom.end);
            let mut levels = [Level::default(); LEVELS];
            let count = usize::from(cur.u16().unwrap_or(0)).min(LEVELS);
            for level in levels.iter_mut().take(count) {
                // Types from centre body on name each level's indent first.
                if atom.instance >= 5 && cur.skip(2).is_none() {
                    break;
                }
                let Some(para) = read_para(&mut cur) else { break };
                let Some(chars) = read_chars(&mut cur) else { break };
                *level = Level { para, chars };
            }
            *slot = Some(levels);
        }
        styles
    }

    pub fn is_empty(&self) -> bool {
        self.0.iter().all(Option::is_none)
    }

    /// These styles, with types they lack taken from `base`.
    pub fn or(mut self, base: &TextStyles) -> TextStyles {
        for (own, base) in self.0.iter_mut().zip(&base.0) {
            if own.is_none() {
                *own = *base;
            }
        }
        self
    }

    /// The style of `text_type` at `level`: each type inherits from a more
    /// general one, and every one from "other".
    fn level(&self, text_type: u32, level: usize) -> Level {
        let chain: &[u32] = match text_type {
            0 => &[4, 0],
            6 => &[4, 0, 6],
            1 => &[4, 1],
            5 | 7 | 8 => &[4, 1, text_type],
            2 => &[4, 2],
            _ => &[4],
        };
        chain.iter().fold(Level::default(), |base, &t| match self.0[t as usize] {
            Some(levels) => levels[level.min(LEVELS - 1)].over(base),
            None => base,
        })
    }
}

/// Runs of a `StyleTextPropAtom`, each ending (exclusive) at a character index.
#[derive(Default)]
struct Runs {
    paras: Vec<(u64, u16, Para)>,
    chars: Vec<(u64, Chars)>,
}

impl Runs {
    /// The runs over text of `len` characters; they cover one more, the final paragraph mark.
    fn read(data: &[u8], (start, end): (usize, usize), len: usize) -> Runs {
        let total = len as u64 + 1;
        let mut cur = Cursor::new(data, start, end);
        let mut runs = Runs::default();
        let mut covered = 0u64;
        while covered < total && runs.paras.len() < MAX_RUNS {
            let Some(count) = cur.u32() else { break };
            let Some(level) = cur.u16() else { break };
            let Some(para) = read_para(&mut cur) else { break };
            covered += u64::from(count);
            runs.paras.push((covered, level, para));
        }
        covered = 0;
        while covered < total && runs.chars.len() < MAX_RUNS {
            let Some(count) = cur.u32() else { break };
            let Some(chars) = read_chars(&mut cur) else { break };
            covered += u64::from(count);
            runs.chars.push((covered, chars));
        }
        runs
    }

    fn para_at(&self, index: usize) -> (u16, Para) {
        let i = self.paras.partition_point(|r| r.0 <= index as u64);
        self.paras.get(i).map_or((0, Para::default()), |r| (r.1, r.2))
    }

    /// The character run at `index` and where it ends.
    fn chars_at(&self, index: usize) -> (u64, Chars) {
        let i = self.chars.partition_point(|r| r.0 <= index as u64);
        self.chars.get(i).copied().unwrap_or((u64::MAX, Chars::default()))
    }
}

/// One piece of text: its type from the `TextHeaderAtom`, its characters and their styles.
#[derive(Debug, Clone, Copy)]
pub(crate) struct TextItem {
    pub text_type: u32,
    /// Record kind (`TextCharsAtom` or `TextBytesAtom`) and body.
    pub chars: Option<(u16, usize, usize)>,
    pub style: Option<(usize, usize)>,
}

/// Groups text records into items as they are met: a header starts one.
#[derive(Default)]
pub(crate) struct TextCollector {
    pub items: Vec<TextItem>,
}

impl TextCollector {
    /// Takes `record` if it is a text record; returns whether it was.
    pub fn take(&mut self, data: &[u8], record: Header) -> bool {
        match record.kind {
            RT_TEXT_HEADER => {
                let text_type = le32(data, record.body).unwrap_or(TEXT_OTHER);
                self.items.push(TextItem { text_type, chars: None, style: None });
            }
            RT_TEXT_CHARS | RT_TEXT_BYTES => {
                let chars = Some((record.kind, record.body, record.end));
                match self.items.last_mut() {
                    Some(item) if item.chars.is_none() => item.chars = chars,
                    last => {
                        let text_type = last.map_or(TEXT_OTHER, |l| l.text_type);
                        self.items.push(TextItem { text_type, chars, style: None });
                    }
                }
            }
            RT_STYLE_TEXT_PROP => {
                if let Some(item) = self.items.last_mut().filter(|i| i.style.is_none()) {
                    item.style = Some((record.body, record.end));
                }
            }
            _ => return false,
        }
        true
    }
}

/// The characters of a text item as UTF-16 units.
pub(crate) fn units(data: &[u8], item: &TextItem) -> Vec<u16> {
    let Some((kind, start, end)) = item.chars else { return Vec::new() };
    let bytes = &data[start..end];
    if kind == RT_TEXT_CHARS {
        bytes.chunks_exact(2).map(|b| u16::from_le_bytes([b[0], b[1]])).collect()
    } else {
        // Each byte is the low byte of a UTF-16 unit, so Latin-1 and not a code page.
        bytes.iter().map(|&b| u16::from(b)).collect()
    }
}

/// Readable text from units: vertical tabs are line breaks, other controls go.
pub(crate) fn clean(units: &[u16]) -> String {
    char::decode_utf16(units.iter().copied())
        .map(|c| c.unwrap_or(char::REPLACEMENT_CHARACTER))
        .map(|c| if c == '\u{b}' { '\n' } else { c })
        .filter(|&c| c >= ' ' || c == '\t' || c == '\n')
        .collect()
}

/// What paragraphs of a slide need beyond the text itself.
pub(crate) struct TextContext<'a> {
    pub data: &'a [u8],
    pub styles: &'a TextStyles,
    pub scheme: &'a [u32; 8],
    pub fonts: &'a [String],
}

fn is_title(text_type: u32) -> bool {
    matches!(text_type, TEXT_TITLE | TEXT_CENTER_TITLE)
}

/// The shape paragraphs of one text item. `budget` is what the slide may still emit.
pub(crate) fn paragraphs(ctx: &TextContext<'_>, item: &TextItem, budget: &mut usize) -> Vec<ShapeText> {
    let units = units(ctx.data, item);
    let runs = item.style.map(|r| Runs::read(ctx.data, r, units.len())).unwrap_or_default();
    let title = is_title(item.text_type);
    let default_color = ctx.scheme[if title { 3 } else { 1 }];
    let mut out = Vec::new();
    let mut start = 0;
    while out.len() < MAX_PARAGRAPHS {
        let end = units[start..].iter().position(|&u| u == 0x0D).map_or(units.len(), |p| start + p);
        let (level, own) = runs.para_at(start);
        let level = usize::from(level).min(LEVELS - 1);
        let base = ctx.styles.level(item.text_type, level);
        let para = own.over(base.para);
        let mut text = RichBuilder::default();
        let (mut size, mut color) = (None, None);
        let (mut all_bold, mut all_italic, mut any) = (true, true, false);
        let mut at = start;
        while at < end {
            let (run_end, own) = runs.chars_at(at);
            let stop = (run_end.min(end as u64) as usize).max(at + 1);
            let chars = own.over(base.chars);
            let piece = clean(&units[at..stop]);
            if piece.len() > *budget {
                return out;
            }
            *budget -= piece.len();
            let run_color = chars.color.map(|c| c.resolve(ctx.scheme));
            if !piece.trim().is_empty() {
                any = true;
                size = size.or(chars.size);
                color = color.or(Some(run_color));
                all_bold &= chars.bold == Some(true);
                all_italic &= chars.italic == Some(true);
            }
            let mut style = 0;
            for (on, flag) in [(chars.bold, BOLD), (chars.italic, ITALIC), (chars.underline, UNDERLINE)] {
                if on == Some(true) {
                    style |= flag;
                }
            }
            text.push_colored(&piece, style, None, run_color);
            at = stop;
        }
        // An empty paragraph still takes the height of its mark's size.
        let size = size.or_else(|| runs.chars_at(start).1.over(base.chars).size);
        let shown = color.flatten().or(base.chars.color.map(|c| c.resolve(ctx.scheme))).unwrap_or(default_color);
        let mut rich = text.take();
        drop_color(&mut rich, Some(shown));
        let bullet = (para.bullet == Some(true) && !title && !rich.text.trim().is_empty())
            .then(|| bullet_text(para.bullet_char, para.bullet_font.and_then(|f| ctx.fonts.get(usize::from(f)))));
        out.push(ShapeText {
            text: rich,
            size: size.unwrap_or(if title { 44.0 } else { 18.0 }),
            color: Some(shown),
            bold: any && all_bold,
            italic: any && all_italic,
            align: para.align.unwrap_or(0),
            bullet,
            level: level as u8,
        });
        if end >= units.len() {
            break;
        }
        start = end + 1;
    }
    out
}

/// A bullet as text. Symbol fonts put their glyphs at private-use or ASCII
/// codes, so the common ones are mapped to the shapes they draw.
fn bullet_text(code: Option<u16>, font: Option<&String>) -> String {
    let code = code.filter(|&c| c >= 0x20).unwrap_or(0x2022);
    let symbol = font.is_some_and(|f| {
        let f = f.to_ascii_lowercase();
        ["wingdings", "webdings", "symbol", "sorts", "marlett"].iter().any(|s| f.contains(s))
    });
    if symbol || (0xF000..=0xF0FF).contains(&code) {
        let glyph = match code & 0xFF {
            0x6C => '●',
            0x6E => '■',
            0x71 => '❑',
            0x75 => '◆',
            0x76 => '❖',
            0xA7 => '▪',
            0xD8 => '➢',
            0xFC => '✓',
            0xA8 => '◻',
            0x2D => '–',
            _ => '•',
        };
        return glyph.to_string();
    }
    char::from_u32(u32::from(code)).filter(|c| !c.is_control()).unwrap_or('•').to_string()
}

// ---------------------------------------------------------------- shapes

/// An OfficeArt property table: `(property id, value)`.
#[derive(Debug, Clone, Default)]
pub(crate) struct Props(Vec<(u16, u32)>);

impl Props {
    fn get(&self, id: u16) -> Option<u32> {
        self.0.iter().rev().find(|(p, _)| *p == id).map(|(_, v)| *v)
    }

    /// Adds the simple properties of an `OfficeArtFOPT`; complex data is not needed.
    fn read(&mut self, data: &[u8], record: Header) {
        let mut cur = Cursor::new(data, record.body, record.end);
        for _ in 0..record.instance {
            if self.0.len() >= MAX_PROPS {
                return;
            }
            let (Some(id), Some(value)) = (cur.u16(), cur.u32()) else { return };
            self.0.push((id & 0x3FFF, value));
        }
    }

    /// The `on` bit of a boolean property set, if its `use` bit (16 higher) says it is set.
    fn flag(&self, id: u16, on: u32) -> Option<bool> {
        self.get(id).filter(|v| v & (on << 16) != 0).map(|v| v & on != 0)
    }
}

/// The document's default shape properties, from its drawing group.
pub(crate) fn read_defaults(data: &[u8], group: Header) -> Props {
    let mut props = Props::default();
    if let Some(dgg) = child(data, group, DGG_CONTAINER) {
        for record in children(data, dgg).filter(|c| matches!(c.kind, FOPT | SECONDARY_FOPT | TERTIARY_FOPT)) {
            props.read(data, record);
        }
    }
    props
}

/// A picture in the document's store: its type and where the `Pictures` stream holds it.
#[derive(Debug, Clone, Copy)]
pub(crate) struct Blip {
    pub kind: u8,
    pub offset: u32,
}

impl Blip {
    /// The extension the platform decodes it by; metafiles and TIFF have none.
    pub fn extension(&self) -> Option<&'static str> {
        match self.kind {
            5 | 0x12 => Some("jpg"),
            6 => Some("png"),
            7 => Some("bmp"),
            _ => None,
        }
    }
}

pub(crate) fn read_blips(data: &[u8], group: Header) -> Vec<Blip> {
    let Some(store) = child(data, group, DGG_CONTAINER).and_then(|dgg| child(data, dgg, BSTORE)) else {
        return Vec::new();
    };
    children(data, store)
        .take(MAX_BLIPS)
        .map(|entry| {
            let field = |at| le32(data, entry.body + at).filter(|_| entry.body + at + 4 <= entry.end).unwrap_or(0);
            let kind = if entry.kind == FBSE && entry.end - entry.body >= 36 { data[entry.body] } else { 0 };
            Blip { kind, offset: field(28) }
        })
        .collect()
}

/// Where a shape's text is.
#[derive(Debug, Clone, Copy)]
pub(crate) enum TextSource {
    Inline(TextItem),
    /// An index into the slide's text in the slide list.
    Outline(usize),
}

/// A shape as the drawing stores it, before inheritance and colours.
#[derive(Debug, Clone, Default)]
pub(crate) struct RawShape {
    kind: u16,
    flags: u32,
    /// x, y, width, height in points.
    rect: Option<[f32; 4]>,
    props: Props,
    pub placeholder: Option<u8>,
    pub text: Option<TextSource>,
}

/// Maps a group's child coordinates to points.
#[derive(Debug, Clone, Copy)]
struct Transform {
    sx: f32,
    sy: f32,
    dx: f32,
    dy: f32,
}

impl Transform {
    const TOP: Transform =
        Transform { sx: 1.0 / MASTER_UNITS_PER_POINT, sy: 1.0 / MASTER_UNITS_PER_POINT, dx: 0.0, dy: 0.0 };

    fn apply(&self, [left, top, right, bottom]: [i32; 4]) -> [f32; 4] {
        let (l, t) = (left.min(right) as f32, top.min(bottom) as f32);
        let (w, h) = ((right as f32 - left as f32).abs(), (bottom as f32 - top as f32).abs());
        [l * self.sx + self.dx, t * self.sy + self.dy, w * self.sx, h * self.sy]
    }
}

#[derive(Debug, Default)]
pub(crate) struct Drawing {
    pub shapes: Vec<RawShape>,
    pub background: Option<RawShape>,
}

/// The drawing inside a slide, master or notes record, if it has one.
pub(crate) fn read_drawing(data: &[u8], record: Header, ppdrawing: u16) -> Option<Drawing> {
    let dg = child(data, child(data, record, ppdrawing)?, DG_CONTAINER)?;
    let mut drawing = Drawing::default();
    let mut budget = 1u32 << 20;
    for part in children(data, dg) {
        match part.kind {
            SPGR_CONTAINER => walk_group(data, part, Transform::TOP, 0, &mut budget, &mut drawing.shapes),
            SP_CONTAINER => {
                let (shape, _) = read_shape(data, part, Transform::TOP);
                if shape.flags & SHAPE_BACKGROUND != 0 {
                    drawing.background = Some(shape);
                }
            }
            _ => {}
        }
    }
    Some(drawing)
}

fn walk_group(data: &[u8], group: Header, xf: Transform, depth: u32, budget: &mut u32, out: &mut Vec<RawShape>) {
    if depth > MAX_GROUP_DEPTH {
        return;
    }
    // The first shape describes the group itself: where it sits and its own coordinates.
    let mut inner = xf;
    for (i, part) in children(data, group).enumerate() {
        if *budget == 0 || out.len() >= MAX_SHAPES {
            return;
        }
        *budget -= 1;
        match part.kind {
            SP_CONTAINER if i == 0 => {
                let (shape, frame) = read_shape(data, part, xf);
                if shape.flags & SHAPE_PATRIARCH == 0
                    && let (Some([x, y, w, h]), Some([l, t, r, b])) = (shape.rect, frame)
                {
                    let (cw, ch) = (r as f32 - l as f32, b as f32 - t as f32);
                    let sx = if cw != 0.0 { w / cw } else { xf.sx };
                    let sy = if ch != 0.0 { h / ch } else { xf.sy };
                    inner = Transform { sx, sy, dx: x - l as f32 * sx, dy: y - t as f32 * sy };
                }
            }
            SP_CONTAINER => {
                let (shape, _) = read_shape(data, part, inner);
                if shape.flags & (SHAPE_GROUP | SHAPE_DELETED) == 0 {
                    out.push(shape);
                }
            }
            SPGR_CONTAINER => walk_group(data, part, inner, depth + 1, budget, out),
            _ => {}
        }
    }
}

fn rect_of(data: &[u8], record: Header) -> Option<[i32; 4]> {
    (record.end - record.body >= 16).then_some(())?;
    Some([
        i32_at(data, record.body)?,
        i32_at(data, record.body + 4)?,
        i32_at(data, record.body + 8)?,
        i32_at(data, record.body + 12)?,
    ])
}

/// One shape container, and a group's own coordinate frame if it has one.
fn read_shape(data: &[u8], container: Header, xf: Transform) -> (RawShape, Option<[i32; 4]>) {
    let mut shape = RawShape::default();
    let mut frame = None;
    let (mut child_anchor, mut client_anchor) = (None, None);
    for part in children(data, container) {
        match part.kind {
            FSP => {
                shape.kind = part.instance;
                shape.flags = le32(data, part.body + 4).filter(|_| part.end - part.body >= 8).unwrap_or(0);
            }
            FSPGR => frame = rect_of(data, part),
            FOPT | SECONDARY_FOPT | TERTIARY_FOPT => shape.props.read(data, part),
            CHILD_ANCHOR => child_anchor = rect_of(data, part),
            CLIENT_ANCHOR => {
                // Top, left, right, bottom: 16-bit in the short form.
                let field = |i: usize| le16(data, part.body + i * 2).map(|v| i32::from(v as i16));
                client_anchor = match part.end - part.body {
                    8 => (|| Some([field(1)?, field(0)?, field(2)?, field(3)?]))(),
                    n if n >= 16 => rect_of(data, part).map(|[t, l, r, b]| [l, t, r, b]),
                    _ => None,
                };
            }
            CLIENT_DATA => {
                if let Some(atom) = child(data, part, RT_PLACEHOLDER) {
                    shape.placeholder =
                        data.get(atom.body + 4).copied().filter(|&p| p != 0 && atom.end > atom.body + 4);
                }
            }
            CLIENT_TEXTBOX => {
                let mut texts = TextCollector::default();
                for record in children(data, part) {
                    if record.kind == RT_OUTLINE_TEXT_REF {
                        if let Some(index) = le32(data, record.body).filter(|_| record.end - record.body >= 4) {
                            shape.text = Some(TextSource::Outline(index as usize));
                        }
                    } else {
                        texts.take(data, record);
                    }
                }
                if let Some(item) = texts.items.into_iter().find(|i| i.chars.is_some()) {
                    shape.text = Some(TextSource::Inline(item));
                }
            }
            _ => {}
        }
    }
    shape.rect = match (child_anchor, client_anchor) {
        (Some(anchor), _) => Some(xf.apply(anchor)),
        (None, Some(anchor)) => Some(Transform::TOP.apply(anchor)),
        (None, None) => None,
    };
    (shape, frame)
}

/// What turning raw shapes into [`Shape`]s needs from the slide.
pub(crate) struct ShapeContext<'a> {
    pub text: TextContext<'a>,
    pub defaults: &'a Props,
    pub blips: &'a [Blip],
    /// The slide's text in the slide list, for placeholders.
    pub outline: &'a [TextItem],
    /// The master's placeholders, which slide placeholders inherit from.
    pub placeholders: &'a [RawShape],
    pub slide_number: u32,
}

/// Master placeholders a slide placeholder of `kind` takes its frame and look from.
fn master_kinds(kind: u8) -> &'static [u8] {
    match kind {
        15 => &[3, 1],
        16 => &[4, 2],
        1 | 13 | 17 => &[1, 3],
        3 => &[3, 1],
        4 => &[4, 2],
        7 => &[7],
        8 => &[8],
        9 => &[9],
        10 => &[10],
        0 | 5 | 6 | 11 | 12 => &[],
        _ => &[2, 4],
    }
}

/// The path a shape or background shows a store picture by, if the platform can decode it.
fn picture_path(blips: &[Blip], index: Option<u32>) -> Option<String> {
    let index = index.filter(|&i| i > 0)?;
    let blip = blips.get(index as usize - 1)?;
    Some(format!("pictures/{index}.{}", blip.extension()?))
}

fn fill_color(own: impl Fn(u16) -> Option<u32>, defaults: &Props, scheme: &[u32; 8]) -> u32 {
    let color = own(PROP_FILL_COLOR).or_else(|| defaults.get(PROP_FILL_COLOR)).and_then(color_ref);
    let base = color.map_or(0xFFFF_FFFF, |c| c.resolve(scheme));
    match own(PROP_FILL_OPACITY) {
        Some(opacity) => {
            let alpha = (u64::from(opacity.min(0x1_0000)) * 255 / 0x1_0000) as u32;
            (base & 0x00FF_FFFF) | alpha << 24
        }
        None => base,
    }
}

/// A slide background's colour and picture.
pub(crate) fn background(shape: &RawShape, ctx: &ShapeContext<'_>) -> (Option<u32>, Option<String>) {
    let props = &shape.props;
    let image = matches!(props.get(PROP_FILL_TYPE), Some(2 | 3))
        .then(|| picture_path(ctx.blips, props.get(PROP_FILL_PICTURE)))
        .flatten();
    let color = props.get(PROP_FILL_COLOR).and_then(color_ref).map(|c| c.resolve(ctx.text.scheme));
    (color, image)
}

/// The shape as the slide shows it, or `None` if nothing of it would show.
pub(crate) fn to_shape(raw: &RawShape, ctx: &ShapeContext<'_>, budget: &mut usize) -> Option<Shape> {
    let master = raw.placeholder.and_then(|kind| {
        master_kinds(kind).iter().find_map(|k| ctx.placeholders.iter().find(|p| p.placeholder == Some(*k)))
    });
    let own = |id| raw.props.get(id).or_else(|| master.and_then(|m| m.props.get(id)));
    let flag = |id, bit| raw.props.flag(id, bit).or_else(|| master.and_then(|m| m.props.flag(id, bit)));
    if flag(PROP_GROUP_FLAGS, 0x2) == Some(true) {
        return None; // hidden
    }
    let [x, y, mut w, mut h] = raw.rect.or_else(|| master?.rect)?;
    if ![x, y, w, h].iter().all(|v| v.is_finite()) {
        return None;
    }
    let rotation = own(PROP_ROTATION).map_or(0.0, |r| (r as i32) as f32 / 65_536.0).rem_euclid(360.0);
    let (mut x, mut y) = (x, y);
    // Near-vertical rotations store the frame turned a quarter.
    if (45.0..135.0).contains(&rotation) || (225.0..315.0).contains(&rotation) {
        (x, y) = (x + (w - h) / 2.0, y + (h - w) / 2.0);
        (w, h) = (h, w);
    }
    let mut shape = blank_shape(x, y, w, h, rotation);
    shape.geometry = match raw.kind {
        2 => Geometry::RoundRect,
        3 => Geometry::Ellipse,
        20 | 32..=40 => Geometry::Line,
        _ => Geometry::Rect,
    };
    // Text boxes, pictures and placeholders draw no box unless they say so; other shapes do.
    let boxed = raw.placeholder.is_none() && !matches!(raw.kind, 75 | 202);
    let scheme = ctx.text.scheme;
    let filled =
        flag(PROP_FILL_FLAGS, 0x10).or_else(|| boxed.then(|| ctx.defaults.flag(PROP_FILL_FLAGS, 0x10)).flatten());
    let fill_type = own(PROP_FILL_TYPE).unwrap_or(0);
    if filled.unwrap_or(boxed) {
        if matches!(fill_type, 2 | 3) {
            shape.image = picture_path(ctx.blips, own(PROP_FILL_PICTURE));
        }
        shape.fill = Some(fill_color(own, ctx.defaults, scheme));
    }
    let lined = flag(PROP_LINE_FLAGS, 0x8).or_else(|| boxed.then(|| ctx.defaults.flag(PROP_LINE_FLAGS, 0x8)).flatten());
    if lined.unwrap_or(boxed) || shape.geometry == Geometry::Line && lined != Some(false) {
        let color = own(PROP_LINE_COLOR).or_else(|| ctx.defaults.get(PROP_LINE_COLOR)).and_then(color_ref);
        shape.line = Some(color.map_or(scheme[1], |c| c.resolve(scheme)));
        let width = own(PROP_LINE_WIDTH).or_else(|| ctx.defaults.get(PROP_LINE_WIDTH)).unwrap_or(9525);
        shape.line_width = (width.min(20_000_000) as f32 / EMU_PER_POINT).max(0.25);
    }
    if let Some(picture) = picture_path(ctx.blips, own(PROP_PICTURE)) {
        shape.image = Some(picture);
    }
    let defaults = [91_440, 45_720, 91_440, 45_720];
    for (i, inset) in shape.insets.iter_mut().enumerate() {
        let emu = own(PROP_TEXT_LEFT + i as u16).unwrap_or(defaults[i]);
        *inset = (emu as i32).max(0) as f32 / EMU_PER_POINT;
    }
    shape.anchor = match own(PROP_ANCHOR_TEXT) {
        Some(1 | 4) => 1,
        Some(2 | 5 | 7 | 9) => 2,
        _ => 0,
    };
    let item = match raw.text {
        Some(TextSource::Inline(item)) => Some(item),
        Some(TextSource::Outline(index)) => ctx.outline.get(index).copied(),
        None => None,
    };
    if let Some(item) = item {
        shape.paragraphs = paragraphs(&ctx.text, &item, budget);
        // The slide number field is stored as an asterisk.
        if raw.placeholder == Some(8) {
            for p in &mut shape.paragraphs {
                if p.text.text == "*" {
                    p.text.text = ctx.slide_number.to_string();
                    p.text.spans.clear();
                }
            }
        }
        if shape.paragraphs.iter().all(|p| p.text.text.trim().is_empty()) {
            shape.paragraphs.clear();
        }
    }
    let visible = shape.fill.is_some() || shape.line.is_some() || shape.image.is_some() || !shape.paragraphs.is_empty();
    visible.then_some(shape)
}

/// Bytes of a picture's record in the `Pictures` stream, made into a file the
/// platform decodes. `record` is the blip record whole, header included.
pub(crate) fn picture_file(record: &[u8]) -> Option<Vec<u8>> {
    let options = le16(record, 0)?;
    let kind = le16(record, 2)?;
    let instance = options >> 4;
    // One 16-byte id, or two for odd instances, then a tag byte.
    let skip = 8 + if instance & 1 != 0 { 33 } else { 17 };
    let body = record.get(skip..)?;
    match kind {
        0xF01D | 0xF01E | 0xF02A => Some(body.to_vec()),
        0xF01F => bitmap_file(body),
        _ => None,
    }
}

/// A BMP file around a device-independent bitmap, which lacks the file header.
fn bitmap_file(dib: &[u8]) -> Option<Vec<u8>> {
    let header_len = le32(dib, 0)?;
    if !(12..=1024).contains(&header_len) || dib.len() < header_len as usize {
        return None;
    }
    let (bits, compression, used) = if header_len == 12 {
        (u32::from(le16(dib, 10)?), 0, 0)
    } else {
        (u32::from(le16(dib, 14)?), le32(dib, 16)?, le32(dib, 32)?)
    };
    let entry = if header_len == 12 { 3 } else { 4 };
    let colors = if used != 0 {
        used.min(256)
    } else if bits <= 8 {
        1 << bits
    } else {
        0
    };
    let masks = if compression == 3 && header_len == 40 { 12 } else { 0 };
    let offset = 14 + header_len + colors * entry + masks;
    let mut file = Vec::with_capacity(dib.len() + 14);
    file.extend_from_slice(b"BM");
    file.extend_from_slice(&((dib.len() + 14) as u32).to_le_bytes());
    file.extend_from_slice(&[0; 4]);
    file.extend_from_slice(&offset.to_le_bytes());
    file.extend_from_slice(dib);
    Some(file)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn record(kind: u16, instance: u16, body: &[u8]) -> Vec<u8> {
        let mut out = (instance << 4).to_le_bytes().to_vec();
        out.extend_from_slice(&kind.to_le_bytes());
        out.extend_from_slice(&(body.len() as u32).to_le_bytes());
        out.extend_from_slice(body);
        out
    }

    #[test]
    fn colours_name_rgb_or_a_scheme_entry() {
        let scheme = DEFAULT_SCHEME;
        assert_eq!(color_ref(0x0011_2233).map(|c| c.resolve(&scheme)), Some(0xFF33_2211));
        assert_eq!(color_ref(0x0800_0004).map(|c| c.resolve(&scheme)), Some(scheme[4]));
        assert_eq!(color_ref(0x0800_0009), None);
        assert_eq!(color_ref(0x1000_00F0), None);
        assert_eq!(color_index(0xFE00_00FF).map(|c| c.resolve(&scheme)), Some(0xFFFF_0000));
        assert_eq!(color_index(0x0300_0000), Some(Color::Scheme(3)));
        assert_eq!(color_index(0xFF00_0000), None);
    }

    #[test]
    fn truncated_formatting_keeps_what_was_read() {
        // A paragraph exception whose tab stops run past the record.
        let body = [0x00, 0x00, 0x10, 0x00, 0xFF, 0xFF];
        assert!(read_para(&mut Cursor::new(&body, 0, body.len())).is_none());
        // A character exception cut inside its colour.
        let body = [0x00, 0x00, 0x04, 0x00, 1, 2];
        assert!(read_chars(&mut Cursor::new(&body, 0, body.len())).is_none());
        // Style runs cut half-way leave the first run.
        let mut runs = 3u32.to_le_bytes().to_vec();
        runs.extend_from_slice(&[0, 0, 0, 0, 0, 0]);
        runs.extend_from_slice(&[9, 9]);
        let data = record(RT_STYLE_TEXT_PROP, 0, &runs);
        let parsed = Runs::read(&data, (8, data.len()), 10);
        assert_eq!(parsed.paras.len(), 1);
        assert!(parsed.chars.is_empty());
    }

    #[test]
    fn bullets_from_symbol_fonts_are_mapped() {
        let wingdings = "Wingdings".to_owned();
        assert_eq!(bullet_text(Some(0x6E), Some(&wingdings)), "■");
        assert_eq!(bullet_text(Some(0xF0A7), None), "▪");
        assert_eq!(bullet_text(Some(0x2013), Some(&"Arial".to_owned())), "–");
        assert_eq!(bullet_text(None, None), "•");
        assert_eq!(bullet_text(Some(0x07), None), "•");
    }

    #[test]
    fn pictures_get_their_file_form() {
        let mut png = (0x6E0u16 << 4).to_le_bytes().to_vec();
        png.extend_from_slice(&0xF01Eu16.to_le_bytes());
        png.extend_from_slice(&[0; 4]);
        png.extend_from_slice(&[7; 17]);
        png.extend_from_slice(b"\x89PNG");
        assert_eq!(picture_file(&png).as_deref(), Some(&b"\x89PNG"[..]));

        let mut dib = 40u32.to_le_bytes().to_vec();
        dib.extend_from_slice(&[0; 10]);
        dib.extend_from_slice(&24u16.to_le_bytes());
        dib.extend_from_slice(&[0; 24]);
        dib.extend_from_slice(&[1, 2, 3, 0]);
        let mut bmp = (0x7A8u16 << 4).to_le_bytes().to_vec();
        bmp.extend_from_slice(&0xF01Fu16.to_le_bytes());
        bmp.extend_from_slice(&[0; 4]);
        bmp.extend_from_slice(&[0; 17]);
        bmp.extend_from_slice(&dib);
        let file = picture_file(&bmp).expect("a bitmap");
        assert_eq!(&file[..2], b"BM");
        assert_eq!(le32(&file, 10), Some(54));
        assert_eq!(file.len(), 14 + dib.len());

        // Metafiles, cut records and broken bitmap headers give nothing.
        let mut wmf = bmp.clone();
        wmf[2..4].copy_from_slice(&0xF01Bu16.to_le_bytes());
        assert!(picture_file(&wmf).is_none());
        assert!(picture_file(&bmp[..20]).is_none());
        assert!(bitmap_file(&[0xFF; 8]).is_none());
    }
}
