//! PowerPoint (PPTX): slides as they were designed.
//!
//! Each slide is emitted as `Section` ("Slide N"), a `Slide` canvas (size,
//! background, transition) and positioned `Shape`s, followed by speaker notes
//! as `Quote`s. Positions, sizes and text formatting are resolved through the
//! inheritance chain PowerPoint uses: theme → slide master (colour map, text
//! styles, placeholders, background) → slide layout → slide. Entrance
//! animations become click "builds"; pictures are fetched on demand through
//! [`BlockSource::resource`].
//!
//! Embedded video and audio are reported on their picture shape
//! (`Shape::media`, with the picture as the poster) and served through
//! [`BlockSource::resource`] like pictures.
//!
//! Not rendered: charts and SmartArt (shown as labelled boxes), custom
//! geometry (drawn as rectangles), gradients (first stop colour) and effects.
//! Each slide part is read whole, bounded by `max_part_bytes`.

use std::collections::{HashMap, VecDeque};
use std::sync::Arc;

use document_core::style::{BOLD, ITALIC, STRIKE, UNDERLINE};
use document_core::{Block, Build, CancelToken, EngineError, Geometry, Result, Rich, Shape, ShapeText};

use crate::dom::{self, Node};
use crate::ooxml::{Relationship, hyperlinks, relationships, target_of};
use crate::rich::{RichBuilder, drop_color};
use crate::stream::{BlockSource, Parts, is_media, is_raster_picture};

const EMU_PER_POINT: f32 = 12700.0;
const DEFAULT_FONT_SIZE: f32 = 18.0;

pub(crate) struct PptxSource {
    parts: Parts,
    cancel: CancelToken,
    slides: VecDeque<String>,
    slide_number: u32,
    /// The slide count goes out before the first slide.
    counted: bool,
    slide_size: (f32, f32),
    layouts: HashMap<String, Arc<Layout>>,
    masters: HashMap<String, Arc<Master>>,
}

impl PptxSource {
    pub fn open(mut parts: Parts, presentation: String) -> Result<Self> {
        let cancel = parts.cancel();
        let rels = relationships(parts.archive(), &presentation)?;
        let targets: HashMap<&str, &str> =
            rels.iter().filter(|r| !r.external).map(|r| (r.id.as_str(), r.target.as_str())).collect();
        let bytes = parts.archive().read_to_vec(&presentation)?;
        let root = dom::parse(&bytes, &cancel)?.ok_or_else(|| EngineError::malformed("the presentation is empty"))?;
        let slides = root
            .child("sldIdLst")
            .map(|list| {
                list.children_named("sldId")
                    .filter_map(|s| s.prefixed_attr("id").and_then(|id| targets.get(id)).map(|t| t.to_string()))
                    .collect()
            })
            .unwrap_or_default();
        let slide_size = root
            .child("sldSz")
            .and_then(|s| Some((s.attr_f32("cx")? / EMU_PER_POINT, s.attr_f32("cy")? / EMU_PER_POINT)))
            .unwrap_or((960.0, 540.0));
        Ok(Self {
            parts,
            cancel,
            slides,
            slide_number: 0,
            counted: false,
            slide_size,
            layouts: HashMap::new(),
            masters: HashMap::new(),
        })
    }

    fn read_dom(&mut self, path: &str) -> Result<Option<Node>> {
        if self.parts.archive().find(path).is_none() {
            return Ok(None);
        }
        let bytes = self.parts.archive().read_to_vec(path)?;
        dom::parse(&bytes, &self.cancel)
    }

    fn master(&mut self, path: &str) -> Result<Arc<Master>> {
        if let Some(master) = self.masters.get(path) {
            return Ok(master.clone());
        }
        let rels = relationships(self.parts.archive(), path)?;
        let theme = match target_of(&rels, "theme") {
            Some(theme) => self.read_dom(&theme)?.map(|t| theme_colors(&t)).unwrap_or_default(),
            None => HashMap::new(),
        };
        let root = self.read_dom(path)?;
        let mut colors = Colors { theme, map: HashMap::new() };
        if let Some(map) = root.as_ref().and_then(|r| r.child("clrMap")) {
            colors.map = map.tag_attrs().map(|(k, v)| (k.to_owned(), v.to_owned())).collect();
        }
        let master = match &root {
            Some(root) => {
                let styles = root.child("txStyles");
                let levels = |name: &str| parse_levels(styles.and_then(|s| s.child(name)), &colors);
                Master {
                    placeholders: placeholders(root, &colors),
                    title: levels("titleStyle"),
                    body: levels("bodyStyle"),
                    other: levels("otherStyle"),
                    background: background(root, &colors, &rels, dir_of(path)),
                    colors,
                }
            }
            None => Master::empty(colors),
        };
        let master = Arc::new(master);
        self.masters.insert(path.to_owned(), master.clone());
        Ok(master)
    }

    fn layout(&mut self, path: &str) -> Result<Arc<Layout>> {
        if let Some(layout) = self.layouts.get(path) {
            return Ok(layout.clone());
        }
        let rels = relationships(self.parts.archive(), path)?;
        let master = match target_of(&rels, "slideMaster") {
            Some(master) => self.master(&master)?,
            None => Arc::new(Master::empty(Colors::default())),
        };
        let root = self.read_dom(path)?;
        let layout = Layout {
            placeholders: root.as_ref().map(|r| placeholders(r, &master.colors)).unwrap_or_default(),
            background: root.as_ref().map(|r| background(r, &master.colors, &rels, dir_of(path))).unwrap_or_default(),
            master,
        };
        let layout = Arc::new(layout);
        self.layouts.insert(path.to_owned(), layout.clone());
        Ok(layout)
    }

    fn read_slide(&mut self, path: &str, out: &mut VecDeque<Block>) -> Result<()> {
        let rels = relationships(self.parts.archive(), path)?;
        let layout = match target_of(&rels, "slideLayout") {
            Some(layout) => self.layout(&layout)?,
            None => Arc::new(Layout {
                master: Arc::new(Master::empty(Colors::default())),
                placeholders: Vec::new(),
                background: Background::default(),
            }),
        };
        let Some(root) = self.read_dom(path)? else { return Ok(()) };
        let colors = &layout.master.colors;
        let own = background(&root, colors, &rels, dir_of(path));
        let bg = own.or(&layout.background).or(&layout.master.background);
        out.push_back(Block::Slide {
            width: self.slide_size.0,
            height: self.slide_size.1,
            background: bg.color.or(Some(colors.scheme("bg1").unwrap_or(0xFFFF_FFFF))),
            background_image: bg.image.clone(),
            transition: transition(&root),
        });
        let ctx = SlideCtx {
            layout: &layout,
            rels: &rels,
            links: hyperlinks(&rels),
            dir: dir_of(path),
            size: self.slide_size,
        };
        let mut shapes = Vec::new();
        if let Some(tree) = root.path(&["cSld", "spTree"]) {
            collect_shapes(tree, &ctx, Transform::IDENTITY, &mut shapes);
        }
        let builds = root.child("timing").map(builds).unwrap_or_default();
        for (id, mut shape) in shapes {
            shape.build = id.and_then(|id| builds.get(&id).copied());
            out.push_back(Block::Shape(Box::new(shape)));
        }
        if let Some(notes) = target_of(&rels, "notesSlide")
            && let Some(notes) = self.read_dom(&notes)?
        {
            out.extend(speaker_notes(&notes));
        }
        Ok(())
    }
}

impl BlockSource for PptxSource {
    fn pump(&mut self, out: &mut VecDeque<Block>) -> Result<bool> {
        if !self.counted {
            self.counted = true;
            out.push_back(Block::Count { total: self.slides.len() as u32 });
        }
        let Some(path) = self.slides.pop_front() else { return Ok(true) };
        self.slide_number += 1;
        out.push_back(Block::Section { title: format!("Slide {}", self.slide_number) });
        if self.parts.archive().find(&path).is_some() {
            self.read_slide(&path, out)?;
        }
        Ok(false)
    }

    /// Pictures referenced by shapes and backgrounds.
    fn resource(&mut self, name: &str) -> Result<Vec<u8>> {
        self.parts.picture(name)
    }
}

// ---------------------------------------------------------------- colours

#[derive(Clone, Default)]
struct Colors {
    /// Theme colour scheme: dk1, lt1, accent1, …
    theme: HashMap<String, u32>,
    /// The master's colour map: bg1 → lt1, tx1 → dk1, …
    map: HashMap<String, String>,
}

impl Colors {
    fn scheme(&self, name: &str) -> Option<u32> {
        let key = self.map.get(name).map(String::as_str).unwrap_or(name);
        self.theme.get(key).copied()
    }

    /// The colour of the first colour element inside `parent`.
    fn color_in(&self, parent: &Node) -> Option<u32> {
        parent.children.iter().find_map(|c| self.color(c))
    }

    fn color(&self, node: &Node) -> Option<u32> {
        let base = match node.name() {
            "srgbClr" => hex(node.attr("val")?),
            "schemeClr" => self.scheme(node.attr("val")?),
            "sysClr" => node.attr("lastClr").and_then(hex).or(match node.attr("val") {
                Some("window") => Some(0xFFFF_FFFF),
                _ => Some(0xFF00_0000),
            }),
            "prstClr" => preset_color(node.attr("val")?),
            _ => return None,
        }?;
        Some(apply_modifiers(base, node))
    }
}

pub(crate) fn theme_colors(theme: &Node) -> HashMap<String, u32> {
    let Some(scheme) = theme.find("clrScheme") else { return HashMap::new() };
    scheme
        .children
        .iter()
        .filter_map(|slot| {
            let color = slot.children.iter().find_map(|c| match c.name() {
                "srgbClr" => c.attr("val").and_then(hex),
                "sysClr" => c.attr("lastClr").and_then(hex),
                _ => None,
            })?;
            Some((slot.name().to_owned(), color))
        })
        .collect()
}

pub(crate) fn hex(value: &str) -> Option<u32> {
    (value.len() == 6).then(|| u32::from_str_radix(value, 16).ok()).flatten().map(|rgb| 0xFF00_0000 | rgb)
}

fn preset_color(name: &str) -> Option<u32> {
    Some(match name {
        "black" => 0xFF00_0000,
        "white" => 0xFFFF_FFFF,
        "red" => 0xFFFF_0000,
        "green" => 0xFF00_8000,
        "blue" => 0xFF00_00FF,
        "yellow" => 0xFFFF_FF00,
        "gray" | "grey" => 0xFF80_8080,
        "orange" => 0xFFFF_A500,
        _ => return None,
    })
}

/// Applies DrawingML colour transforms (lumMod/lumOff, tint, shade, alpha).
fn apply_modifiers(argb: u32, node: &Node) -> u32 {
    let mut a = ((argb >> 24) & 0xFF) as f32 / 255.0;
    let mut rgb =
        [((argb >> 16) & 0xFF) as f32 / 255.0, ((argb >> 8) & 0xFF) as f32 / 255.0, (argb & 0xFF) as f32 / 255.0];
    for m in &node.children {
        let Some(v) = m.attr_f32("val").map(|v| v / 100_000.0) else { continue };
        match m.name() {
            "lumMod" | "lumOff" => {
                let (h, s, mut l) = to_hsl(rgb);
                l = if m.name() == "lumMod" { l * v } else { l + v };
                rgb = from_hsl(h, s, l.clamp(0.0, 1.0));
            }
            "tint" => rgb = rgb.map(|c| c + (1.0 - c) * (1.0 - v)),
            "shade" => rgb = rgb.map(|c| c * v),
            "alpha" => a = v.clamp(0.0, 1.0),
            _ => {}
        }
    }
    let byte = |c: f32| (c.clamp(0.0, 1.0) * 255.0).round() as u32;
    (byte(a) << 24) | (byte(rgb[0]) << 16) | (byte(rgb[1]) << 8) | byte(rgb[2])
}

pub(crate) fn to_hsl([r, g, b]: [f32; 3]) -> (f32, f32, f32) {
    let max = r.max(g).max(b);
    let min = r.min(g).min(b);
    let l = (max + min) / 2.0;
    if (max - min).abs() < f32::EPSILON {
        return (0.0, 0.0, l);
    }
    let d = max - min;
    let s = if l > 0.5 { d / (2.0 - max - min) } else { d / (max + min) };
    let h = if max == r {
        (g - b) / d + if g < b { 6.0 } else { 0.0 }
    } else if max == g {
        (b - r) / d + 2.0
    } else {
        (r - g) / d + 4.0
    } / 6.0;
    (h, s, l)
}

pub(crate) fn from_hsl(h: f32, s: f32, l: f32) -> [f32; 3] {
    if s == 0.0 {
        return [l, l, l];
    }
    let q = if l < 0.5 { l * (1.0 + s) } else { l + s - l * s };
    let p = 2.0 * l - q;
    let hue = |mut t: f32| {
        if t < 0.0 {
            t += 1.0;
        }
        if t > 1.0 {
            t -= 1.0;
        }
        if t < 1.0 / 6.0 {
            p + (q - p) * 6.0 * t
        } else if t < 0.5 {
            q
        } else if t < 2.0 / 3.0 {
            p + (q - p) * (2.0 / 3.0 - t) * 6.0
        } else {
            p
        }
    };
    [hue(h + 1.0 / 3.0), hue(h), hue(h - 1.0 / 3.0)]
}

// ---------------------------------------------------------------- styles

#[derive(Clone, Default)]
struct Level {
    size: Option<f32>,
    color: Option<u32>,
    bold: Option<bool>,
    italic: Option<bool>,
    align: Option<u8>,
    bullet: Option<Bullet>,
}

#[derive(Clone)]
enum Bullet {
    None,
    Char(String),
    Number,
}

impl Level {
    fn over(&self, base: &Level) -> Level {
        Level {
            size: self.size.or(base.size),
            color: self.color.or(base.color),
            bold: self.bold.or(base.bold),
            italic: self.italic.or(base.italic),
            align: self.align.or(base.align),
            bullet: self.bullet.clone().or_else(|| base.bullet.clone()),
        }
    }
}

type Levels = Vec<Level>;

fn parse_levels(node: Option<&Node>, colors: &Colors) -> Levels {
    (1..=9)
        .map(|i| node.and_then(|n| n.child(&format!("lvl{i}pPr"))).map(|p| level_of(p, colors)).unwrap_or_default())
        .collect()
}

/// Paragraph properties (`a:pPr` or `a:lvlNpPr`) as a style level.
fn level_of(ppr: &Node, colors: &Colors) -> Level {
    let mut level = ppr.child("defRPr").map(|r| run_level(r, colors)).unwrap_or_default();
    level.align = ppr.attr("algn").map(|a| match a {
        "ctr" => 1,
        "r" => 2,
        "just" | "dist" => 3,
        _ => 0,
    });
    level.bullet = ppr.children.iter().find_map(|c| match c.name() {
        "buNone" => Some(Bullet::None),
        "buChar" => Some(Bullet::Char(c.attr("char").unwrap_or("•").to_owned())),
        "buAutoNum" => Some(Bullet::Number),
        _ => None,
    });
    level
}

fn run_level(rpr: &Node, colors: &Colors) -> Level {
    Level {
        size: rpr.attr_f32("sz").map(|s| s / 100.0),
        color: rpr.child("solidFill").and_then(|f| colors.color_in(f)),
        bold: rpr.attr("b").map(|b| b == "1" || b == "true"),
        italic: rpr.attr("i").map(|i| i == "1" || i == "true"),
        align: None,
        bullet: None,
    }
}

fn merged(stack: &[&Levels]) -> Levels {
    (0..9)
        .map(|i| stack.iter().fold(Level::default(), |acc, levels| levels.get(i).map_or(acc.clone(), |l| l.over(&acc))))
        .collect()
}

// ---------------------------------------------------------------- masters and layouts

#[derive(Clone, Copy)]
struct Rect {
    x: f32,
    y: f32,
    w: f32,
    h: f32,
    rotation: f32,
}

/// Where the default Office master puts a placeholder, for decks that ship
/// without layouts or masters. Fractions of the slide; the result is in EMU.
fn default_rect(kind: &str, (width, height): (f32, f32)) -> Option<Rect> {
    let (x, y, w, h) = match kind {
        "title" => (0.069, 0.053, 0.862, 0.193),
        "ctrTitle" => (0.125, 0.164, 0.75, 0.348),
        "subTitle" => (0.125, 0.525, 0.75, 0.241),
        "body" | "obj" | "tbl" | "chart" | "pic" | "media" | "dgm" | "clipArt" => (0.069, 0.266, 0.862, 0.635),
        _ => return None,
    };
    let (w_emu, h_emu) = (width * EMU_PER_POINT, height * EMU_PER_POINT);
    Some(Rect { x: x * w_emu, y: y * h_emu, w: w * w_emu, h: h * h_emu, rotation: 0.0 })
}

fn rect_of(xfrm: &Node) -> Option<Rect> {
    let off = xfrm.child("off")?;
    let ext = xfrm.child("ext")?;
    Some(Rect {
        x: off.attr_f32("x")?,
        y: off.attr_f32("y")?,
        w: ext.attr_f32("cx")?,
        h: ext.attr_f32("cy")?,
        rotation: xfrm.attr_f32("rot").map_or(0.0, |r| r / 60_000.0),
    })
}

#[derive(Clone, Default)]
struct BodyProps {
    anchor: Option<u8>,
    insets: [Option<f32>; 4],
}

impl BodyProps {
    fn of(body_pr: Option<&Node>) -> Self {
        let Some(b) = body_pr else { return Self::default() };
        let inset = |name| b.attr_f32(name).map(|v| v / EMU_PER_POINT);
        BodyProps {
            anchor: b.attr("anchor").map(|a| match a {
                "ctr" => 1,
                "b" => 2,
                _ => 0,
            }),
            insets: [inset("lIns"), inset("tIns"), inset("rIns"), inset("bIns")],
        }
    }

    fn over(&self, base: &BodyProps) -> BodyProps {
        BodyProps {
            anchor: self.anchor.or(base.anchor),
            insets: std::array::from_fn(|i| self.insets[i].or(base.insets[i])),
        }
    }
}

struct Placeholder {
    kind: String,
    idx: Option<String>,
    rect: Option<Rect>,
    levels: Levels,
    body: BodyProps,
}

#[derive(Clone, Default)]
struct Background {
    color: Option<u32>,
    image: Option<String>,
}

impl Background {
    fn or<'a>(&'a self, other: &'a Background) -> &'a Background {
        if self.color.is_some() || self.image.is_some() { self } else { other }
    }
}

struct Master {
    colors: Colors,
    placeholders: Vec<Placeholder>,
    title: Levels,
    body: Levels,
    other: Levels,
    background: Background,
}

impl Master {
    fn empty(colors: Colors) -> Self {
        Self {
            colors,
            placeholders: Vec::new(),
            // The default Office master's text styles.
            title: vec![Level { size: Some(44.0), ..Level::default() }],
            body: [28.0, 24.0, 20.0, 18.0, 18.0]
                .map(|size| Level { size: Some(size), bullet: Some(Bullet::Char("•".into())), ..Level::default() })
                .into(),
            other: Levels::new(),
            background: Background::default(),
        }
    }

    fn text_style(&self, kind: &str) -> &Levels {
        match family(kind) {
            "title" => &self.title,
            "body" => &self.body,
            _ => &self.other,
        }
    }
}

struct Layout {
    master: Arc<Master>,
    placeholders: Vec<Placeholder>,
    background: Background,
}

/// `title`/`ctrTitle` share title styles; `body`/`subTitle`/`obj` body styles.
fn family(kind: &str) -> &str {
    match kind {
        "title" | "ctrTitle" => "title",
        "body" | "subTitle" | "obj" => "body",
        other => other,
    }
}

fn placeholders(root: &Node, colors: &Colors) -> Vec<Placeholder> {
    let Some(tree) = root.path(&["cSld", "spTree"]) else { return Vec::new() };
    tree.children_named("sp")
        .filter_map(|sp| {
            let ph = sp.path(&["nvSpPr", "nvPr", "ph"])?;
            let body = sp.child("txBody");
            Some(Placeholder {
                kind: ph.attr("type").unwrap_or("body").to_owned(),
                idx: ph.attr("idx").map(str::to_owned),
                rect: sp.path(&["spPr", "xfrm"]).and_then(rect_of),
                levels: parse_levels(body.and_then(|b| b.child("lstStyle")), colors),
                body: BodyProps::of(body.and_then(|b| b.child("bodyPr"))),
            })
        })
        .collect()
}

fn find_placeholder<'a>(list: &'a [Placeholder], kind: &str, idx: Option<&str>) -> Option<&'a Placeholder> {
    idx.and_then(|i| list.iter().find(|p| p.idx.as_deref() == Some(i)))
        .or_else(|| list.iter().find(|p| p.kind == kind))
        .or_else(|| list.iter().find(|p| family(&p.kind) == family(kind)))
}

fn background(root: &Node, colors: &Colors, rels: &[Relationship], dir: &str) -> Background {
    let Some(bg) = root.path(&["cSld", "bg"]) else { return Background::default() };
    if let Some(pr) = bg.child("bgPr") {
        return match fill_of(pr, colors, rels, dir) {
            Fill::Color(c) => Background { color: Some(c), image: None },
            Fill::Image(path) => Background { color: None, image: Some(path) },
            _ => Background::default(),
        };
    }
    Background { color: bg.child("bgRef").and_then(|r| colors.color_in(r)), image: None }
}

fn dir_of(path: &str) -> &str {
    path.rsplit_once('/').map_or("", |(d, _)| d)
}

// ---------------------------------------------------------------- fills

enum Fill {
    Unset,
    None,
    Color(u32),
    Image(String),
}

fn fill_of(props: &Node, colors: &Colors, rels: &[Relationship], dir: &str) -> Fill {
    for c in &props.children {
        match c.name() {
            "noFill" => return Fill::None,
            "solidFill" => return colors.color_in(c).map_or(Fill::None, Fill::Color),
            "gradFill" => {
                let stop = c.path(&["gsLst", "gs"]).and_then(|gs| colors.color_in(gs));
                return stop.map_or(Fill::None, Fill::Color);
            }
            "pattFill" => return c.child("fgClr").and_then(|f| colors.color_in(f)).map_or(Fill::None, Fill::Color),
            "blipFill" => return image_of(c, rels, dir).map_or(Fill::None, Fill::Image),
            _ => {}
        }
    }
    Fill::Unset
}

/// The package path of a `blipFill`'s raster picture, if it has one.
fn image_of(blip_fill: &Node, rels: &[Relationship], _dir: &str) -> Option<String> {
    let id = blip_fill.child("blip")?.prefixed_attr("embed")?;
    let target = rels.iter().find(|r| r.id == id && !r.external)?.target.clone();
    is_raster_picture(&target).then_some(target)
}

// ---------------------------------------------------------------- shapes

#[derive(Clone, Copy)]
struct Transform {
    sx: f32,
    sy: f32,
    tx: f32,
    ty: f32,
}

impl Transform {
    const IDENTITY: Transform = Transform { sx: 1.0, sy: 1.0, tx: 0.0, ty: 0.0 };

    /// Points on the slide for a rectangle in this group's coordinates.
    fn apply(&self, r: Rect) -> (f32, f32, f32, f32) {
        (
            (r.x * self.sx + self.tx) / EMU_PER_POINT,
            (r.y * self.sy + self.ty) / EMU_PER_POINT,
            r.w * self.sx / EMU_PER_POINT,
            r.h * self.sy / EMU_PER_POINT,
        )
    }

    fn group(&self, xfrm: &Node) -> Transform {
        let ch_off = xfrm.child("chOff");
        let ch_ext = xfrm.child("chExt");
        let (Some(r), Some(ch_off), Some(ch_ext)) = (rect_of(xfrm), ch_off, ch_ext) else { return *self };
        let (cx, cy) = (ch_off.attr_f32("x").unwrap_or(0.0), ch_off.attr_f32("y").unwrap_or(0.0));
        let (cw, ch) = (ch_ext.attr_f32("cx").unwrap_or(r.w).max(1.0), ch_ext.attr_f32("cy").unwrap_or(r.h).max(1.0));
        let (gx, gy) = (r.w / cw, r.h / ch);
        Transform {
            sx: self.sx * gx,
            sy: self.sy * gy,
            tx: self.sx * (r.x - cx * gx) + self.tx,
            ty: self.sy * (r.y - cy * gy) + self.ty,
        }
    }
}

struct SlideCtx<'a> {
    layout: &'a Layout,
    rels: &'a [Relationship],
    links: HashMap<String, String>,
    dir: &'a str,
    /// Slide size in points.
    size: (f32, f32),
}

fn collect_shapes(tree: &Node, ctx: &SlideCtx<'_>, xf: Transform, out: &mut Vec<(Option<u32>, Shape)>) {
    for node in &tree.children {
        match node.name() {
            "sp" | "cxnSp" => {
                if let Some(shape) = shape(node, ctx, xf) {
                    out.push(shape);
                }
            }
            "pic" => {
                if let Some(shape) = picture(node, ctx, xf) {
                    out.push(shape);
                }
            }
            "graphicFrame" => {
                if let Some(shape) = frame(node, ctx, xf) {
                    out.push(shape);
                }
            }
            "grpSp" => {
                let inner = node.path(&["grpSpPr", "xfrm"]).map_or(xf, |x| xf.group(x));
                collect_shapes(node, ctx, inner, out);
            }
            // Prefer the plain-DrawingML fallback over newer extensions.
            "AlternateContent" => {
                if let Some(branch) = node.child("Fallback").or_else(|| node.child("Choice")) {
                    collect_shapes(branch, ctx, xf, out);
                }
            }
            _ => {}
        }
    }
}

fn shape_id(node: &Node, nv: &str) -> Option<u32> {
    node.path(&[nv, "cNvPr"]).and_then(|c| c.attr("id")).and_then(|id| id.parse().ok())
}

pub(crate) fn blank_shape(x: f32, y: f32, w: f32, h: f32, rotation: f32) -> Shape {
    Shape {
        x,
        y,
        width: w,
        height: h,
        rotation,
        geometry: Geometry::Rect,
        fill: None,
        line: None,
        line_width: 0.0,
        image: None,
        media: None,
        paragraphs: Vec::new(),
        anchor: 0,
        insets: [7.2, 3.6, 7.2, 3.6],
        table: Vec::new(),
        table_header: false,
        build: None,
    }
}

fn geometry_of(sp_pr: Option<&Node>, connector: bool) -> Geometry {
    if connector {
        return Geometry::Line;
    }
    match sp_pr.and_then(|p| p.child("prstGeom")).and_then(|g| g.attr("prst")) {
        Some("roundRect" | "snipRoundRect" | "round1Rect" | "round2SameRect") => Geometry::RoundRect,
        Some("ellipse" | "flowChartConnector") => Geometry::Ellipse,
        Some("line" | "straightConnector1" | "bentConnector3" | "curvedConnector3") => Geometry::Line,
        _ => Geometry::Rect,
    }
}

fn shape(node: &Node, ctx: &SlideCtx<'_>, xf: Transform) -> Option<(Option<u32>, Shape)> {
    let connector = node.name() == "cxnSp";
    let nv = if connector { "nvCxnSpPr" } else { "nvSpPr" };
    let colors = &ctx.layout.master.colors;
    let ph = node.path(&[nv, "nvPr", "ph"]);
    let kind = ph.map(|p| p.attr("type").unwrap_or("body").to_owned());
    let idx = ph.and_then(|p| p.attr("idx"));
    let layout_ph = kind.as_deref().and_then(|k| find_placeholder(&ctx.layout.placeholders, k, idx));
    let master_ph = kind.as_deref().and_then(|k| find_placeholder(&ctx.layout.master.placeholders, k, None));

    let sp_pr = node.child("spPr");
    let rect = sp_pr
        .and_then(|p| p.child("xfrm"))
        .and_then(rect_of)
        .or_else(|| layout_ph.and_then(|p| p.rect))
        .or_else(|| master_ph.and_then(|p| p.rect))
        .or_else(|| default_rect(kind.as_deref()?, ctx.size))?;
    let (x, y, w, h) = xf.apply(rect);
    let mut out = blank_shape(x, y, w, h, rect.rotation);
    out.geometry = geometry_of(sp_pr, connector);

    let style = node.child("style");
    let style_color = |name: &str| {
        style
            .and_then(|s| s.child(name))
            .filter(|r| r.attr("idx").is_some_and(|i| i != "0"))
            .and_then(|r| colors.color_in(r))
    };
    let fill = sp_pr.map_or(Fill::Unset, |p| fill_of(p, colors, ctx.rels, ctx.dir));
    match fill {
        Fill::Color(c) => out.fill = Some(c),
        Fill::Image(path) => out.image = Some(path),
        Fill::None => {}
        Fill::Unset if !connector => out.fill = style_color("fillRef"),
        Fill::Unset => {}
    }
    let line = sp_pr.and_then(|p| p.child("ln"));
    out.line = match line.map(|l| fill_of(l, colors, ctx.rels, ctx.dir)) {
        Some(Fill::Color(c)) => Some(c),
        Some(Fill::None) => None,
        _ => style_color("lnRef"),
    };
    out.line_width = line.and_then(|l| l.attr_f32("w")).map_or(0.75, |w| w / EMU_PER_POINT);

    if let Some(body) = node.child("txBody") {
        let kind = kind.as_deref().unwrap_or("other");
        let master = &ctx.layout.master;
        let empty = Levels::new();
        let shape_levels = parse_levels(body.child("lstStyle"), colors);
        let levels = merged(&[
            if ph.is_some() { master.text_style(kind) } else { &master.other },
            master_ph.map_or(&empty, |p| &p.levels),
            layout_ph.map_or(&empty, |p| &p.levels),
            &shape_levels,
        ]);
        let props = BodyProps::of(body.child("bodyPr"))
            .over(&layout_ph.map(|p| p.body.clone()).unwrap_or_default())
            .over(&master_ph.map(|p| p.body.clone()).unwrap_or_default());
        out.anchor = props.anchor.unwrap_or(if family(kind) == "title" { 1 } else { 0 });
        out.insets = [
            props.insets[0].unwrap_or(7.2),
            props.insets[1].unwrap_or(3.6),
            props.insets[2].unwrap_or(7.2),
            props.insets[3].unwrap_or(3.6),
        ];
        let scale =
            body.path(&["bodyPr", "normAutofit"]).and_then(|a| a.attr_f32("fontScale")).map_or(1.0, |s| s / 100_000.0);
        let default_color =
            style.and_then(|s| s.child("fontRef")).and_then(|r| colors.color_in(r)).or_else(|| colors.scheme("tx1"));
        out.paragraphs = paragraphs(body, &levels, scale, default_color, family(kind) == "title", ctx);
    }
    let visible = out.fill.is_some() || out.line.is_some() || out.image.is_some();
    if !visible && out.paragraphs.iter().all(|p| p.text.text.trim().is_empty()) {
        return None;
    }
    Some((shape_id(node, nv), out))
}

fn paragraphs(
    body: &Node,
    levels: &Levels,
    scale: f32,
    default_color: Option<u32>,
    title: bool,
    ctx: &SlideCtx<'_>,
) -> Vec<ShapeText> {
    let colors = &ctx.layout.master.colors;
    let mut counters = [0u32; 9];
    let mut out = Vec::new();
    for p in body.children_named("p") {
        let ppr = p.child("pPr");
        let level_index = ppr.and_then(|p| p.attr("lvl")).and_then(|l| l.parse::<usize>().ok()).unwrap_or(0).min(8);
        let base = levels.get(level_index).cloned().unwrap_or_default();
        let level = ppr.map_or(base.clone(), |p| level_of(p, colors).over(&base));
        let mut text = RichBuilder::default();
        let mut size = None;
        // The paragraph is drawn in its first run's colour; other runs keep their own.
        let mut color: Option<Option<u32>> = None;
        for run in &p.children {
            match run.name() {
                "r" | "fld" => {
                    let rpr = run.child("rPr");
                    let props = rpr.map(|r| run_level(r, colors)).unwrap_or_default().over(&level);
                    size = size.or(props.size);
                    let t = run.child("t").map_or("", |t| t.text.as_str());
                    let run_color = props.color.or(default_color);
                    if color.is_none() && !t.trim().is_empty() {
                        color = Some(run_color);
                    }
                    let mut style = 0;
                    if props.bold == Some(true) {
                        style |= BOLD;
                    }
                    if props.italic == Some(true) {
                        style |= ITALIC;
                    }
                    if rpr.and_then(|r| r.attr("u")).is_some_and(|u| u != "none") {
                        style |= UNDERLINE;
                    }
                    if rpr.and_then(|r| r.attr("strike")).is_some_and(|s| s != "noStrike") {
                        style |= STRIKE;
                    }
                    let link = rpr
                        .and_then(|r| r.child("hlinkClick"))
                        .and_then(|h| h.prefixed_attr("id"))
                        .and_then(|id| ctx.links.get(id))
                        .map(String::as_str);
                    text.push_colored(t, style, link, run_color);
                }
                "br" => text.push_plain("\n"),
                _ => {}
            }
        }
        let mut rich: Rich = text.take();
        let shown = color.flatten().or(level.color).or(default_color);
        drop_color(&mut rich, shown);
        let size = size
            .or_else(|| p.child("endParaRPr").and_then(|e| e.attr_f32("sz")).map(|s| s / 100.0))
            .or(level.size)
            .unwrap_or(DEFAULT_FONT_SIZE)
            * scale;
        let bullet = if rich.text.is_empty() || title {
            None
        } else {
            match level.bullet {
                Some(Bullet::Char(c)) => Some(c),
                Some(Bullet::Number) => {
                    counters[level_index] += 1;
                    counters[level_index + 1..].fill(0);
                    Some(format!("{}.", counters[level_index]))
                }
                _ => None,
            }
        };
        out.push(ShapeText {
            text: rich,
            size,
            color: shown,
            bold: level.bold == Some(true),
            italic: level.italic == Some(true),
            align: level.align.unwrap_or(0),
            bullet,
            level: level_index as u8,
        });
    }
    out
}

fn picture(node: &Node, ctx: &SlideCtx<'_>, xf: Transform) -> Option<(Option<u32>, Shape)> {
    let image = node.child("blipFill").and_then(|b| image_of(b, ctx.rels, ctx.dir));
    let media = media_of(node, ctx.rels);
    if image.is_none() && media.is_none() {
        return None;
    }
    let ph = node.path(&["nvPicPr", "nvPr", "ph"]);
    let rect = node.path(&["spPr", "xfrm"]).and_then(rect_of).or_else(|| {
        let kind = ph?.attr("type").unwrap_or("pic");
        find_placeholder(&ctx.layout.placeholders, kind, ph?.attr("idx"))
            .and_then(|p| p.rect)
            .or_else(|| default_rect(kind, ctx.size))
    })?;
    let (x, y, w, h) = xf.apply(rect);
    let mut out = blank_shape(x, y, w, h, rect.rotation);
    out.geometry = geometry_of(node.child("spPr"), false);
    out.image = image;
    out.media = media;
    Some((shape_id(node, "nvPicPr"), out))
}

/// The package path of the audio or video a picture plays: an `a:videoFile` or
/// `a:audioFile` link, or PowerPoint 2010's `p14:media` embed. Files outside
/// the package are never followed.
fn media_of(pic: &Node, rels: &[Relationship]) -> Option<String> {
    let nv = pic.path(&["nvPicPr", "nvPr"])?;
    let linked = nv
        .children
        .iter()
        .filter(|c| matches!(c.name(), "videoFile" | "audioFile"))
        .filter_map(|c| c.prefixed_attr("link"));
    let embedded = nv
        .child("extLst")
        .into_iter()
        .flat_map(|l| l.children_named("ext"))
        .filter_map(|e| e.child("media")?.prefixed_attr("embed"));
    let ids: Vec<&str> = embedded.chain(linked).collect();
    ids.iter().find_map(|id| {
        rels.iter().find(|r| r.id == *id && !r.external && is_media(&r.target)).map(|r| r.target.clone())
    })
}

fn frame(node: &Node, ctx: &SlideCtx<'_>, xf: Transform) -> Option<(Option<u32>, Shape)> {
    let rect = node.child("xfrm").and_then(rect_of)?;
    let (x, y, w, h) = xf.apply(rect);
    let mut out = blank_shape(x, y, w, h, 0.0);
    let data = node.path(&["graphic", "graphicData"])?;
    if let Some(table) = data.child("tbl") {
        out.table_header =
            table.child("tblPr").and_then(|p| p.attr("firstRow")).is_some_and(|v| v == "1" || v == "true");
        out.table = table.children_named("tr").map(|tr| tr.children_named("tc").map(cell_text).collect()).collect();
        out.line = ctx.layout.master.colors.scheme("tx1").map(|c| (c & 0x00FF_FFFF) | 0x4000_0000);
    } else {
        let uri = data.attr("uri").unwrap_or_default();
        let label = if uri.contains("chart") {
            "Chart"
        } else if uri.contains("diagram") {
            "SmartArt"
        } else {
            return None;
        };
        out.fill = Some(0xFFF4_F1F4);
        out.anchor = 1;
        out.paragraphs = vec![ShapeText {
            text: Rich::plain(label),
            size: 16.0,
            color: Some(0xFF8E_8496),
            bold: false,
            italic: true,
            align: 1,
            bullet: None,
            level: 0,
        }];
    }
    Some((shape_id(node, "nvGraphicFramePr"), out))
}

fn cell_text(tc: &Node) -> String {
    let Some(body) = tc.child("txBody") else { return String::new() };
    body.children_named("p")
        .map(|p| {
            p.children
                .iter()
                .filter(|r| r.name() == "r" || r.name() == "fld")
                .filter_map(|r| r.child("t").map(|t| t.text.as_str()))
                .collect::<String>()
        })
        .collect::<Vec<_>>()
        .join("\n")
}

// ---------------------------------------------------------------- animation, transitions, notes

/// Entrance animations by shape id. Each click effect starts a new step;
/// "with/after previous" effects join the current step. Step 0 plays as
/// the slide opens.
fn builds(timing: &Node) -> HashMap<u32, Build> {
    fn walk(node: &Node, effect: Option<u16>, step: &mut u16, out: &mut HashMap<u32, Build>) {
        let mut effect = effect;
        if node.name() == "cTn" && node.attr("presetClass") == Some("entr") {
            if node.attr("nodeType") == Some("clickEffect") {
                *step = step.saturating_add(1);
            }
            effect = Some(node.attr("presetID").and_then(|v| v.parse().ok()).unwrap_or(10));
        }
        if node.name() == "spTgt"
            && let (Some(effect), Some(id)) = (effect, node.attr("spid").and_then(|v| v.parse().ok()))
        {
            out.entry(id).or_insert(Build { step: *step, effect });
        }
        for child in &node.children {
            walk(child, effect, step, out);
        }
    }
    let mut out = HashMap::new();
    walk(timing, None, &mut 0, &mut out);
    out
}

/// The slide transition's effect name (`fade`, `push`, `wipe`, …).
fn transition(slide: &Node) -> Option<String> {
    let node = slide.child("transition").or_else(|| {
        slide.child("AlternateContent").and_then(|a| a.child("Choice")).and_then(|c| c.child("transition"))
    })?;
    node.children.first().map(|c| c.name().to_owned())
}

fn speaker_notes(notes: &Node) -> Vec<Block> {
    let Some(tree) = notes.path(&["cSld", "spTree"]) else { return Vec::new() };
    tree.children_named("sp")
        .filter(|sp| sp.path(&["nvSpPr", "nvPr", "ph"]).and_then(|p| p.attr("type")) == Some("body"))
        .filter_map(|sp| sp.child("txBody"))
        .flat_map(|body| body.children_named("p"))
        .filter_map(|p| {
            let text: String = p
                .children
                .iter()
                .filter(|r| r.name() == "r")
                .filter_map(|r| r.child("t"))
                .map(|t| t.text.as_str())
                .collect();
            (!text.trim().is_empty()).then(|| Block::Quote { text: Rich::plain(text) })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use document_core::Block;

    use crate::testing::{open_zip, texts};
    use crate::zip::testing::zip;

    const NS: &str = r#"xmlns:p="p" xmlns:a="a" xmlns:r="r""#;

    #[test]
    fn slides_with_inherited_layout_theme_and_builds() {
        let rels = br#"<Relationships><Relationship Id="r1" Type="http://x/officeDocument" Target="ppt/presentation.xml"/></Relationships>"#;
        let presentation = format!(
            r#"<p:presentation {NS}><p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/></p:presentation>"#
        );
        let pres_rels = br#"<Relationships><Relationship Id="rId2" Type="http://x/slide" Target="slides/slide1.xml"/></Relationships>"#;
        let theme = br#"<a:theme xmlns:a="a"><a:themeElements><a:clrScheme name="x"><a:dk1><a:srgbClr val="111111"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:accent1><a:srgbClr val="E60023"/></a:accent1></a:clrScheme></a:themeElements></a:theme>"#;
        let master = format!(
            r#"<p:sldMaster {NS}><p:cSld><p:bg><p:bgPr><a:solidFill><a:schemeClr val="bg1"/></a:solidFill></p:bgPr></p:bg><p:spTree>
<p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="838200" y="365125"/><a:ext cx="10515600" cy="1325563"/></a:xfrm></p:spPr></p:sp>
<p:sp><p:nvSpPr><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="838200" y="1825625"/><a:ext cx="10515600" cy="4351338"/></a:xfrm></p:spPr></p:sp>
</p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1"/>
<p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz="4400"/></a:lvl1pPr></p:titleStyle><p:bodyStyle><a:lvl1pPr><a:buChar char="•"/><a:defRPr sz="2800"/></a:lvl1pPr></p:bodyStyle></p:txStyles></p:sldMaster>"#
        );
        let master_rels = br#"<Relationships><Relationship Id="rId1" Type="http://x/theme" Target="../theme/theme1.xml"/></Relationships>"#;
        let layout = format!(r#"<p:sldLayout {NS}><p:cSld><p:spTree/></p:cSld></p:sldLayout>"#);
        let layout_rels = br#"<Relationships><Relationship Id="rId1" Type="http://x/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>"#;
        let slide = format!(
            r#"<p:sld {NS}><p:cSld><p:spTree>
<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr><a:normAutofit fontScale="50000"/></a:bodyPr><a:p><a:r><a:rPr b="1"/><a:t>Hello</a:t></a:r></a:p></p:txBody></p:sp>
<p:sp><p:nvSpPr><p:cNvPr id="3" name="Body"/><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:p><a:r><a:t>Point </a:t></a:r><a:r><a:rPr><a:solidFill><a:srgbClr val="C00000"/></a:solidFill></a:rPr><a:t>red</a:t></a:r></a:p></p:txBody></p:sp>
<p:sp><p:nvSpPr><p:cNvPr id="4" name="Box"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="1270000" cy="635000"/></a:xfrm><a:prstGeom prst="roundRect"/><a:solidFill><a:schemeClr val="accent1"><a:lumMod val="50000"/></a:schemeClr></a:solidFill></p:spPr></p:sp>
<p:pic><p:nvPicPr><p:cNvPr id="5" name="Pic"/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rId3"/></p:blipFill><p:spPr><a:xfrm><a:off x="127000" y="127000"/><a:ext cx="254000" cy="254000"/></a:xfrm></p:spPr></p:pic>
<p:pic><p:nvPicPr><p:cNvPr id="6" name="Movie"/><p:nvPr><a:videoFile r:link="rId4"/><p:extLst><p:ext uri="{{DAA4B4D4-6D71-4841-9C94-3DE7FCFB9230}}"><p14:media r:embed="rId5"/></p:ext></p:extLst></p:nvPr></p:nvPicPr><p:blipFill><a:blip r:embed="rId3"/></p:blipFill><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="254000" cy="254000"/></a:xfrm></p:spPr></p:pic>
<p:pic><p:nvPicPr><p:cNvPr id="7" name="Remote"/><p:nvPr><a:videoFile r:link="rId6"/></p:nvPr></p:nvPicPr><p:blipFill/><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="254000" cy="254000"/></a:xfrm></p:spPr></p:pic>
</p:spTree></p:cSld>
<p:transition><p:push dir="l"/></p:transition>
<p:timing><p:tnLst><p:par><p:cTn id="1" nodeType="tmRoot"><p:childTnLst><p:seq><p:cTn id="2" nodeType="mainSeq"><p:childTnLst><p:par><p:cTn id="3"><p:childTnLst><p:par><p:cTn id="4" presetID="10" presetClass="entr" nodeType="clickEffect"><p:childTnLst><p:set><p:cBhvr><p:tgtEl><p:spTgt spid="3"/></p:tgtEl></p:cBhvr></p:set></p:childTnLst></p:cTn></p:par></p:childTnLst></p:cTn></p:par></p:childTnLst></p:cTn></p:seq></p:childTnLst></p:cTn></p:par></p:tnLst></p:timing>
</p:sld>"#
        );
        let slide_rels = br#"<Relationships><Relationship Id="rId1" Type="http://x/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId3" Type="http://x/image" Target="../media/image1.png"/><Relationship Id="rId4" Type="http://x/video" Target="../media/media1.mp4"/><Relationship Id="rId5" Type="http://x/media" Target="../media/media1.mp4"/><Relationship Id="rId6" Type="http://x/video" Target="https://example.com/a.mp4" TargetMode="External"/></Relationships>"#;
        let deck = zip(&[
            ("_rels/.rels", rels),
            ("ppt/presentation.xml", presentation.as_bytes()),
            ("ppt/_rels/presentation.xml.rels", pres_rels),
            ("ppt/theme/theme1.xml", theme),
            ("ppt/slideMasters/slideMaster1.xml", master.as_bytes()),
            ("ppt/slideMasters/_rels/slideMaster1.xml.rels", master_rels),
            ("ppt/slideLayouts/slideLayout1.xml", layout.as_bytes()),
            ("ppt/slideLayouts/_rels/slideLayout1.xml.rels", layout_rels),
            ("ppt/slides/slide1.xml", slide.as_bytes()),
            ("ppt/slides/_rels/slide1.xml.rels", slide_rels),
            ("ppt/media/image1.png", b"\x89PNG fake"),
            ("ppt/media/media1.mp4", b"fake movie"),
        ]);
        let (format, blocks) = open_zip(deck);
        assert_eq!(format, document_core::FormatId::Pptx);
        assert_eq!(
            texts(&blocks),
            [
                "COUNT 1",
                "S Slide 1",
                "SLIDE 960x540 push",
                "SH Hello",
                "SH • Point red",
                "SH ",
                "IMG ppt/media/image1.png",
                "IMG ppt/media/image1.png"
            ]
        );
        // The movie keeps its picture as the poster; a video outside the package is never followed.
        let media: Vec<_> = blocks
            .iter()
            .filter_map(|b| if let Block::Shape(s) = b { Some(s.media.as_deref()) } else { None })
            .collect();
        assert_eq!(media, [None, None, None, None, Some("ppt/media/media1.mp4")]);

        let shapes: Vec<_> =
            blocks.iter().filter_map(|b| if let Block::Shape(s) = b { Some(s) } else { None }).collect();
        // Title: position from the master, 44pt halved by autofit, theme text colour, bold run.
        assert_eq!((shapes[0].x, shapes[0].y.round()), (66.0, 29.0));
        assert_eq!(shapes[0].paragraphs[0].size, 22.0);
        assert_eq!(shapes[0].paragraphs[0].color, Some(0xFF11_1111));
        // Body matched by idx, bullet from the master body style, click build.
        assert_eq!(shapes[1].paragraphs[0].size, 28.0);
        // A colour on one word stays on that word; the paragraph keeps the theme's text colour.
        let point = &shapes[1].paragraphs[0];
        assert_eq!(point.color, Some(0xFF11_1111));
        let spans: Vec<_> = point.text.spans.iter().map(|s| (s.start, s.end, s.color)).collect();
        assert_eq!(spans, [(6, 9, Some(0xFFC0_0000))]);
        assert_eq!(shapes[1].build, Some(document_core::Build { step: 1, effect: 10 }));
        // Box: accent1 darkened by lumMod 50%.
        assert_eq!(shapes[2].fill, Some(0xFF73_0011));
        assert_eq!(shapes[2].geometry, document_core::Geometry::RoundRect);
        let Some(Block::Slide { background, .. }) = blocks.get(2) else { panic!() };
        assert_eq!(*background, Some(0xFFFF_FFFF));
    }

    #[test]
    fn placeholders_without_layout_get_default_frames() {
        let rels = br#"<Relationships><Relationship Id="r1" Type="http://x/officeDocument" Target="ppt/presentation.xml"/></Relationships>"#;
        let presentation = format!(
            r#"<p:presentation {NS}><p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst></p:presentation>"#
        );
        let pres_rels = br#"<Relationships><Relationship Id="rId2" Type="http://x/slide" Target="slides/slide1.xml"/></Relationships>"#;
        let slide = format!(
            r#"<p:sld {NS}><p:cSld><p:spTree>
<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>Hello</a:t></a:r></a:p></p:txBody></p:sp>
<p:sp><p:nvSpPr><p:cNvPr id="3" name="Body"/><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>Point</a:t></a:r></a:p></p:txBody></p:sp>
</p:spTree></p:cSld></p:sld>"#
        );
        let deck = zip(&[
            ("_rels/.rels", rels),
            ("ppt/presentation.xml", presentation.as_bytes()),
            ("ppt/_rels/presentation.xml.rels", pres_rels),
            ("ppt/slides/slide1.xml", slide.as_bytes()),
        ]);
        let (_, blocks) = open_zip(deck);
        assert_eq!(texts(&blocks), ["COUNT 1", "S Slide 1", "SLIDE 960x540 -", "SH Hello", "SH • Point"]);
        let shapes: Vec<_> =
            blocks.iter().filter_map(|b| if let Block::Shape(s) = b { Some(s) } else { None }).collect();
        assert!(shapes[0].width > 800.0 && shapes[0].y < 40.0);
        assert_eq!(shapes[0].paragraphs[0].size, 44.0);
        assert!(shapes[1].y > shapes[0].y + shapes[0].height - 1.0);
        assert_eq!(shapes[1].paragraphs[0].size, 28.0);
    }
}
