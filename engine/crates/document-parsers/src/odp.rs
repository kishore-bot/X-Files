//! OpenDocument presentations (ODP) laid out as slides, as PPTX ones are.
//!
//! Each `draw:page` becomes a [`Block::Section`] ("Slide N"), a
//! [`Block::Slide`] with the page size and background, and one
//! [`Block::Shape`] per element in drawing order: text frames, custom shapes,
//! rectangles, ellipses and lines (with their fill, outline and text styles),
//! tables, pictures, and embedded audio and video (`draw:plugin`, played from
//! the shape, with the frame's picture as its poster). Groups are flattened.
//! Speaker notes follow as [`Block::Quote`]s.
//!
//! Styles come from `styles.xml` (common and automatic styles, the default
//! graphic style, page layouts and master pages) and from the automatic styles
//! of `content.xml`, following `style:parent-style-name` chains. Shapes on a
//! master page, other than its placeholders, are drawn behind every slide that
//! uses it.
//!
//! Each page is read as one small tree while `content.xml` streams, so a long
//! deck never sits in memory whole.

use std::collections::{HashMap, VecDeque};

use document_core::style::{BOLD, ITALIC, STRIKE, UNDERLINE};
use document_core::{Block, Geometry, Rich, Shape, ShapeText};

use crate::dom::Node;
use crate::odf::length_points;
use crate::ooxml::{is_safe_link, resolve_inside};
use crate::pptx::blank_shape;
use crate::rich::{RichBuilder, drop_color};
use crate::stream::Parts;

/// LibreOffice's default 16:9 page, 28 × 15.75 cm.
const DEFAULT_PAGE: (f32, f32) = (793.7, 446.5);
const MAX_STYLE_CHAIN: usize = 12;
/// More is not a real slide; the rest are dropped.
const MAX_SHAPES: usize = 2000;
const MAX_TABLE_ROWS: usize = 200;
const MAX_TABLE_COLUMNS: usize = 64;
/// Kept per style table; real decks have hundreds.
const MAX_STYLES: usize = 16_384;
const MAX_LIST_LEVELS: usize = 10;
const DEFAULT_FONT_SIZE: f32 = 18.0;

/// What one style sets; `None` inherits.
#[derive(Default, Clone)]
struct Props {
    parent: Option<String>,
    /// A `presentation` family style, as opposed to a `graphic` one.
    presentation: bool,
    /// `Some(None)` is an explicit "no fill".
    fill: Option<Option<u32>>,
    stroke: Option<Option<u32>>,
    stroke_width: Option<f32>,
    size: Option<f32>,
    color: Option<u32>,
    /// Style flags switched on and off.
    on: u8,
    off: u8,
    align: Option<u8>,
    anchor: Option<u8>,
}

impl Props {
    /// `self`, with what it doesn't set taken from `base`.
    fn over(&self, base: &Props) -> Props {
        Props {
            parent: None,
            presentation: false,
            fill: self.fill.or(base.fill),
            stroke: self.stroke.or(base.stroke),
            stroke_width: self.stroke_width.or(base.stroke_width),
            size: self.size.or(base.size),
            color: self.color.or(base.color),
            on: self.on | (base.on & !self.off),
            off: self.off | (base.off & !self.on),
            align: self.align.or(base.align),
            anchor: self.anchor.or(base.anchor),
        }
    }

    fn flags(&self) -> u8 {
        self.on & !self.off
    }
}

/// A list level's label: a bullet character, or a number format and suffix.
#[derive(Clone)]
enum ListLevel {
    Bullet(String),
    Number { format: String, prefix: String, suffix: String },
}

struct Master {
    size: (f32, f32),
    background: Option<u32>,
    shapes: Vec<Shape>,
}

#[derive(Default)]
pub(crate) struct OdpDeck {
    styles: HashMap<String, Props>,
    graphic_default: Props,
    lists: HashMap<String, Vec<Option<ListLevel>>>,
    layouts: HashMap<String, (f32, f32)>,
    masters: HashMap<String, Master>,
    first_master: Option<String>,
    page: u32,
}

impl OdpDeck {
    /// Reads `styles.xml`: styles, page layouts, then master pages, which use both.
    pub fn read_styles(&mut self, root: &Node, parts: &Parts) {
        for section in &root.children {
            if matches!(section.name(), "styles" | "automatic-styles") {
                self.add_styles(section);
            }
        }
        let Some(masters) = root.child("master-styles") else { return };
        for master in masters.children_named("master-page") {
            let Some(name) = master.attr("name") else { continue };
            let size =
                master.attr("page-layout-name").and_then(|l| self.layouts.get(l)).copied().unwrap_or(DEFAULT_PAGE);
            let background = master.attr("style-name").and_then(|s| self.resolve(Some(s)).fill).flatten();
            let mut shapes = Vec::new();
            for child in &master.children {
                // Placeholders hold prompts ("Click to add Title"), not content.
                if child.attr("class").is_none() {
                    self.shape(child, parts, (0.0, 0.0), &mut shapes);
                }
            }
            self.first_master.get_or_insert_with(|| name.to_owned());
            self.masters.insert(name.to_owned(), Master { size, background, shapes });
        }
    }

    /// Styles, list styles and page layouts of an `office:styles` or
    /// `office:automatic-styles` element.
    pub fn add_styles(&mut self, section: &Node) {
        for style in &section.children {
            match style.name() {
                "style" | "default-style" => {
                    let props = style_props(style);
                    if style.name() == "default-style" {
                        if style.attr("family") == Some("graphic") {
                            self.graphic_default = props;
                        }
                    } else if let Some(name) = style.attr("name")
                        && self.styles.len() < MAX_STYLES
                    {
                        self.styles.insert(name.to_owned(), props);
                    }
                }
                "list-style" => {
                    if let Some(name) = style.attr("name")
                        && self.lists.len() < MAX_STYLES
                    {
                        self.lists.insert(name.to_owned(), list_levels(style));
                    }
                }
                "page-layout" => {
                    let props = style.child("page-layout-properties");
                    let size = props.and_then(|p| {
                        Some((length_points(p.attr("page-width")?)?, length_points(p.attr("page-height")?)?))
                    });
                    if let (Some(name), Some(size)) = (style.attr("name"), size.filter(|(w, h)| *w > 0.0 && *h > 0.0)) {
                        self.layouts.insert(name.to_owned(), size);
                    }
                }
                _ => {}
            }
        }
    }

    /// A style with its parents' settings filled in.
    fn resolve(&self, name: Option<&str>) -> Props {
        let mut props = Props::default();
        let mut name = name.map(str::to_owned);
        for _ in 0..MAX_STYLE_CHAIN {
            let Some(style) = name.as_deref().and_then(|n| self.styles.get(n)) else { break };
            props = props.over(style);
            name = style.parent.clone();
        }
        props
    }

    /// The blocks of one `draw:page`.
    pub fn page(&mut self, page: &Node, parts: &Parts, out: &mut VecDeque<Block>) {
        self.page += 1;
        out.push_back(Block::Section { title: format!("Slide {}", self.page) });
        let master = page
            .attr("master-page-name")
            .and_then(|m| self.masters.get(m))
            .or_else(|| self.first_master.as_deref().and_then(|m| self.masters.get(m)));
        let size = master.map_or(DEFAULT_PAGE, |m| m.size);
        let own = page.attr("style-name").map(|s| self.resolve(Some(s)).fill);
        let background = match own.flatten() {
            Some(fill) => fill,
            None => master.and_then(|m| m.background),
        };
        out.push_back(Block::Slide {
            width: size.0,
            height: size.1,
            background: background.or(Some(0xFFFF_FFFF)),
            background_image: None,
            transition: None,
        });
        let mut shapes: Vec<Shape> = master.map(|m| m.shapes.clone()).unwrap_or_default();
        let mut notes = Vec::new();
        let mut unsized_body = 0;
        for child in &page.children {
            if child.name() == "notes" {
                notes_text(child, &mut notes);
            } else if child.name() == "frame" && child.attr("width").is_none() && child.attr("height").is_none() {
                // A frame without a size (hand-written files) takes its place in the usual
                // layout, as LibreOffice shows it, instead of vanishing at zero size.
                if shapes.len() < MAX_SHAPES
                    && let Some(mut shape) = self.frame(child, parts, (0.0, 0.0))
                {
                    let (x, y, w, h) = default_area(child.attr("class"), &mut unsized_body);
                    (shape.x, shape.y, shape.width, shape.height) = (x * size.0, y * size.1, w * size.0, h * size.1);
                    shapes.push(shape);
                }
            } else {
                self.shape(child, parts, (0.0, 0.0), &mut shapes);
            }
        }
        out.extend(shapes.into_iter().map(|s| Block::Shape(Box::new(s))));
        out.extend(notes.into_iter().map(|text| Block::Quote { text }));
    }

    /// Adds the shapes of a drawing element; `offset` moves a group's members.
    fn shape(&self, node: &Node, parts: &Parts, offset: (f32, f32), shapes: &mut Vec<Shape>) {
        if shapes.len() >= MAX_SHAPES {
            return;
        }
        match node.name() {
            "g" => {
                for child in &node.children {
                    self.shape(child, parts, offset, shapes);
                }
            }
            "frame" => {
                if let Some(shape) = self.frame(node, parts, offset) {
                    shapes.push(shape);
                }
            }
            "custom-shape" | "rect" | "ellipse" | "circle" | "line" | "connector" => {
                if let Some(shape) = self.drawn(node, offset) {
                    shapes.push(shape);
                }
            }
            _ => {}
        }
    }

    /// The shape's style: its graphic style over its presentation style.
    /// Both attributes are named `style-name`, so the style's family tells
    /// which is which.
    fn shape_props(&self, node: &Node, base: &Props) -> Props {
        let (mut graphic, mut presentation) = (Props::default(), Props::default());
        for (key, value) in node.tag_attrs() {
            if key == "style-name" {
                if self.styles.get(value).is_some_and(|s| s.presentation) {
                    presentation = self.resolve(Some(value));
                } else {
                    graphic = self.resolve(Some(value));
                }
            }
        }
        graphic.over(&presentation).over(base)
    }

    fn frame(&self, node: &Node, parts: &Parts, offset: (f32, f32)) -> Option<Shape> {
        let mut shape = placed(node, offset)?;
        let props = self.shape_props(node, &Props::default());
        apply_graphic(&mut shape, &props);
        let class = node.attr("class");
        let base = Props {
            size: Some(match class {
                Some("title") => 40.0,
                Some("subtitle") => 28.0,
                _ => DEFAULT_FONT_SIZE,
            }),
            ..Props::default()
        }
        .over(&Props::default());
        let text_props = props.over(&base);
        let mut poster = None;
        let mut has_content = false;
        for child in &node.children {
            match child.name() {
                "text-box" => {
                    has_content = true;
                    self.paragraphs(child, &text_props, &mut shape.paragraphs);
                }
                // A frame may offer several versions of its picture; the first that can be served wins.
                "image" => {
                    has_content = true;
                    if poster.is_none() {
                        poster = href(child).and_then(|p| parts.picture_path(&p));
                    }
                    // A picture can carry a caption.
                    self.paragraphs(child, &text_props, &mut shape.paragraphs);
                }
                "plugin" => {
                    has_content = true;
                    shape.media = shape.media.take().or_else(|| href(child).and_then(|p| parts.media_path(&p)));
                }
                "table" => {
                    has_content = true;
                    self.table(child, &mut shape);
                }
                _ => {}
            }
        }
        shape.image = poster;
        // An empty placeholder shows a prompt in the editor only.
        let empty = shape.paragraphs.iter().all(|p| p.text.text.trim().is_empty());
        let invisible = !has_content || node.attr("placeholder") == Some("true") || shape.fill.is_none();
        if shape.image.is_none() && shape.media.is_none() && shape.table.is_empty() && empty && invisible {
            return None;
        }
        if shape.media.is_some() && shape.image.is_none() && shape.fill.is_none() {
            // Something to see before it plays.
            shape.fill = Some(0xFF11_1827);
        }
        shape.anchor = props.anchor.unwrap_or(0);
        Some(shape)
    }

    /// Custom shapes, rectangles, ellipses and lines, with text of their own.
    fn drawn(&self, node: &Node, offset: (f32, f32)) -> Option<Shape> {
        let mut shape = if matches!(node.name(), "line" | "connector") {
            let point = |name| node.attr(name).and_then(length_points);
            let (x1, y1, x2, y2) = (point("x1")?, point("y1")?, point("x2")?, point("y2")?);
            let mut shape =
                blank_shape(x1.min(x2) + offset.0, y1.min(y2) + offset.1, (x2 - x1).abs(), (y2 - y1).abs(), 0.0);
            shape.geometry = Geometry::Line;
            shape
        } else {
            placed(node, offset)?
        };
        let props = self.shape_props(node, &self.graphic_default);
        apply_graphic(&mut shape, &props);
        if shape.geometry != Geometry::Line {
            shape.geometry = match node.name() {
                "ellipse" | "circle" => Geometry::Ellipse,
                "rect" if node.attr("corner-radius").and_then(length_points).is_some_and(|r| r > 0.0) => {
                    Geometry::RoundRect
                }
                "custom-shape" => match node.child("enhanced-geometry").and_then(|g| g.attr("type")) {
                    Some("ellipse" | "circle") => Geometry::Ellipse,
                    Some("round-rectangle" | "round-quadrat") => Geometry::RoundRect,
                    _ => Geometry::Rect,
                },
                _ => Geometry::Rect,
            };
        }
        // Drawn shapes centre their text unless told otherwise.
        let base = Props { size: Some(DEFAULT_FONT_SIZE), align: Some(1), ..Props::default() };
        self.paragraphs(node, &props.over(&base), &mut shape.paragraphs);
        shape.anchor = props.anchor.unwrap_or(1);
        Some(shape)
    }

    fn table(&self, node: &Node, shape: &mut Shape) {
        let mut rows = Vec::new();
        let mut header_rows = 0;
        let collect = |row: &Node, rows: &mut Vec<Vec<String>>| {
            if rows.len() >= MAX_TABLE_ROWS {
                return;
            }
            let cells = row
                .children
                .iter()
                .filter(|c| matches!(c.name(), "table-cell" | "covered-table-cell"))
                .take(MAX_TABLE_COLUMNS)
                .map(|cell| {
                    let mut lines = Vec::new();
                    notes_text(cell, &mut lines);
                    lines.into_iter().map(|r| r.text).collect::<Vec<_>>().join("\n")
                })
                .collect();
            rows.push(cells);
        };
        for child in &node.children {
            match child.name() {
                "table-header-rows" => {
                    for row in child.children_named("table-row") {
                        collect(row, &mut rows);
                        header_rows += 1;
                    }
                }
                "table-row" => collect(child, &mut rows),
                "table-rows" => {
                    for row in child.children_named("table-row") {
                        collect(row, &mut rows);
                    }
                }
                _ => {}
            }
        }
        // LibreOffice marks a heading row through the table's template.
        shape.table_header = header_rows > 0 || node.attr("use-first-row-styles") == Some("true");
        shape.table = rows;
    }

    /// The paragraphs of a text container, with list bullets and numbers.
    fn paragraphs(&self, container: &Node, base: &Props, out: &mut Vec<ShapeText>) {
        for child in &container.children {
            match child.name() {
                "p" | "h" => out.push(self.paragraph(child, base, None, 0)),
                "list" => {
                    let style = child.attr("style-name");
                    self.list(child, base, style, 0, out);
                }
                _ => {}
            }
        }
    }

    fn list(&self, list: &Node, base: &Props, style: Option<&str>, level: usize, out: &mut Vec<ShapeText>) {
        let style = list.attr("style-name").or(style);
        let label = style
            .and_then(|s| self.lists.get(s))
            .and_then(|levels| levels.get(level.min(MAX_LIST_LEVELS - 1)).cloned())
            .unwrap_or_else(|| Some(ListLevel::Bullet("•".to_owned())));
        let mut number = list.attr("start-value").and_then(|v| v.parse::<u32>().ok()).unwrap_or(1);
        for item in list.children.iter().filter(|c| matches!(c.name(), "list-item" | "list-header")) {
            let mut first = item.name() == "list-item";
            for child in &item.children {
                match child.name() {
                    "p" | "h" => {
                        // Only an item's first paragraph shows the label.
                        let bullet = if first {
                            first = false;
                            match &label {
                                Some(ListLevel::Bullet(c)) => Some(c.clone()),
                                Some(ListLevel::Number { format, prefix, suffix }) => {
                                    let n = number;
                                    number += 1;
                                    Some(format!("{prefix}{}{suffix}", format_number(n, format)))
                                }
                                None => None,
                            }
                        } else {
                            None
                        };
                        out.push(self.paragraph(child, base, bullet, level as u8));
                    }
                    "list" if level + 1 < MAX_LIST_LEVELS => self.list(child, base, style, level + 1, out),
                    _ => {}
                }
            }
        }
    }

    fn paragraph(&self, node: &Node, base: &Props, bullet: Option<String>, level: u8) -> ShapeText {
        let props = self.resolve(node.attr("style-name")).over(base);
        let mut text = RichBuilder::default();
        let mut first_run: Option<Props> = None;
        self.runs(node, &props, None, true, &mut text, &mut first_run);
        // The paragraph's size and colour are those of its first run, where the text style sets them.
        let shown = first_run.unwrap_or_else(|| props.clone());
        let mut text = text.take_trimmed();
        drop_color(&mut text, shown.color);
        ShapeText {
            text,
            size: shown.size.unwrap_or(DEFAULT_FONT_SIZE),
            color: shown.color,
            bold: props.flags() & BOLD != 0,
            italic: props.flags() & ITALIC != 0,
            align: props.align.unwrap_or(0),
            bullet,
            level,
        }
    }

    /// Mixed text and inline elements, in order.
    fn runs(
        &self,
        node: &Node,
        props: &Props,
        link: Option<&str>,
        root: bool,
        out: &mut RichBuilder,
        first: &mut Option<Props>,
    ) {
        let flags = if root { 0 } else { props.flags() };
        if !node.text.is_empty() {
            if !node.text.trim().is_empty() {
                first.get_or_insert_with(|| props.clone());
            }
            out.push_collapsed_colored(&node.text, flags, link, props.color);
        }
        for child in &node.children {
            match child.name() {
                "span" => {
                    let inner = self.resolve(child.attr("style-name")).over(props);
                    self.runs(child, &inner, link, false, out, first);
                }
                "a" => {
                    let target = child.attr("href").filter(|h| is_safe_link(h));
                    self.runs(child, props, target.or(link), root, out, first);
                }
                "s" => {
                    let count = child.attr("c").and_then(|c| c.parse::<usize>().ok()).unwrap_or(1).min(64);
                    out.push_colored(&" ".repeat(count), flags, link, props.color);
                }
                "tab" => out.push("\t", flags, link),
                "line-break" => out.push("\n", flags, link),
                // Comments and their kin are not slide text.
                "annotation" | "annotation-end" | "note" | "bookmark" | "bookmark-start" | "bookmark-end" => {}
                // Fields (page number, date) show their saved text.
                _ => self.runs(child, props, link, root, out, first),
            }
            if !child.tail.is_empty() {
                out.push_collapsed_colored(&child.tail, flags, link, props.color);
            }
        }
    }
}

/// The text paragraphs anywhere inside `node`, for notes and table cells.
fn notes_text(node: &Node, out: &mut Vec<Rich>) {
    for child in &node.children {
        if matches!(child.name(), "p" | "h") {
            let mut text = RichBuilder::default();
            plain_runs(child, &mut text);
            let text = text.take_trimmed();
            if !text.text.is_empty() {
                out.push(text);
            }
        } else {
            notes_text(child, out);
        }
    }
}

fn plain_runs(node: &Node, out: &mut RichBuilder) {
    out.push_collapsed(&node.text, 0, None);
    for child in &node.children {
        match child.name() {
            "s" => out.push_plain(" "),
            "tab" => out.push_plain("\t"),
            "line-break" => out.push_plain("\n"),
            "annotation" | "note" => {}
            _ => plain_runs(child, out),
        }
        out.push_collapsed(&child.tail, 0, None);
    }
}

/// Where a frame with no size goes, as fractions of the page: the title band at
/// the top, a subtitle under it, and other text stacked in the body.
fn default_area(class: Option<&str>, body: &mut u32) -> (f32, f32, f32, f32) {
    match class {
        Some("title") => (0.06, 0.06, 0.88, 0.18),
        Some("subtitle") => (0.06, 0.26, 0.88, 0.12),
        _ => {
            let row = (*body).min(5) as f32;
            *body += 1;
            (0.06, 0.28 + row * 0.12, 0.88, 0.12)
        }
    }
}

/// A shape at its `svg:x`/`svg:y` with its size, or the translation of a
/// `draw:transform`, which also carries rotation.
fn placed(node: &Node, offset: (f32, f32)) -> Option<Shape> {
    let length = |name| node.attr(name).and_then(length_points);
    let width = length("width").unwrap_or(0.0);
    let height = length("height").unwrap_or(0.0);
    let (mut x, mut y, mut rotation) = (length("x"), length("y"), 0.0);
    if let Some(transform) = node.attr("transform") {
        let (angle, translate) = parse_transform(transform);
        rotation = angle;
        if let Some((tx, ty)) = translate {
            x = Some(tx);
            y = Some(ty);
        }
    }
    let (x, y) = (x.unwrap_or(0.0), y.unwrap_or(0.0));
    if !(x.is_finite() && y.is_finite() && width.is_finite() && height.is_finite()) || width < 0.0 || height < 0.0 {
        return None;
    }
    Some(blank_shape(x + offset.0, y + offset.1, width, height, rotation))
}

/// `rotate (0.52) translate (5cm 3cm)`: degrees clockwise, and the position.
/// ODF turns counter-clockwise in radians, about the shape's top-left corner;
/// the slide turns about the centre, which this approximates.
fn parse_transform(transform: &str) -> (f32, Option<(f32, f32)>) {
    let mut rotation = 0.0;
    let mut translate = None;
    let mut rest = transform;
    while let Some(open) = rest.find('(') {
        let name = rest[..open].trim();
        let Some(close) = rest[open..].find(')') else { break };
        let args: Vec<&str> = rest[open + 1..open + close].split([' ', ',']).filter(|a| !a.is_empty()).collect();
        match name {
            "rotate" => {
                if let Some(radians) = args.first().and_then(|a| a.parse::<f32>().ok()).filter(|r| r.is_finite()) {
                    rotation = (-radians.to_degrees()).rem_euclid(360.0);
                }
            }
            "translate" => {
                translate = args.first().and_then(|a| length_points(a)).zip(args.get(1).and_then(|a| length_points(a)));
            }
            _ => {}
        }
        rest = &rest[open + close + 1..];
    }
    (rotation, translate)
}

fn apply_graphic(shape: &mut Shape, props: &Props) {
    shape.fill = props.fill.flatten();
    shape.line = props.stroke.flatten();
    shape.line_width = if shape.line.is_some() { props.stroke_width.unwrap_or(0.75).max(0.5) } else { 0.0 };
}

/// A container path from `xlink:href`; LibreOffice may name the package explicitly.
fn href(node: &Node) -> Option<String> {
    let target = node.attr("href")?;
    let target = target.strip_prefix("vnd.sun.star.Package:").unwrap_or(target);
    resolve_inside("", target)
}

fn style_props(style: &Node) -> Props {
    let mut props = Props {
        parent: style.attr("parent-style-name").map(str::to_owned),
        presentation: style.attr("family") == Some("presentation"),
        ..Props::default()
    };
    for part in &style.children {
        match part.name() {
            "graphic-properties" | "drawing-page-properties" => {
                match part.attr("fill") {
                    Some("none") => props.fill = Some(None),
                    // Gradients and pictures show their main colour.
                    Some(_) => props.fill = part.attr("fill-color").and_then(color).map(Some).or(props.fill),
                    None => {}
                }
                match part.attr("stroke") {
                    Some("none") => props.stroke = Some(None),
                    Some(_) => {
                        props.stroke = Some(Some(part.attr("stroke-color").and_then(color).unwrap_or(0xFF00_0000)))
                    }
                    None => {
                        if let Some(c) = part.attr("stroke-color").and_then(color) {
                            props.stroke = Some(Some(c));
                        }
                    }
                }
                props.stroke_width = part.attr("stroke-width").and_then(length_points).or(props.stroke_width);
                props.anchor = match part.attr("textarea-vertical-align") {
                    Some("top") => Some(0),
                    Some("middle") => Some(1),
                    Some("bottom") => Some(2),
                    _ => props.anchor,
                };
            }
            "text-properties" => {
                props.size = part.attr("font-size").and_then(length_points).filter(|s| *s > 0.0 && *s < 1000.0);
                props.color = part.attr("color").and_then(color);
                let mut set = |on: bool, flag: u8| {
                    if on {
                        props.on |= flag;
                    } else {
                        props.off |= flag;
                    }
                };
                if let Some(weight) = part.attr("font-weight") {
                    set(weight == "bold" || weight.parse::<u32>().is_ok_and(|n| n >= 600), BOLD);
                }
                if let Some(style) = part.attr("font-style") {
                    set(matches!(style, "italic" | "oblique"), ITALIC);
                }
                if let Some(underline) = part.attr("text-underline-style") {
                    set(underline != "none", UNDERLINE);
                }
                if let Some(strike) = part.attr("text-line-through-style") {
                    set(strike != "none", STRIKE);
                }
            }
            "paragraph-properties" => {
                props.align = match part.attr("text-align") {
                    Some("start" | "left") => Some(0),
                    Some("center") => Some(1),
                    Some("end" | "right") => Some(2),
                    Some("justify") => Some(3),
                    _ => props.align,
                };
            }
            _ => {}
        }
    }
    props
}

fn list_levels(style: &Node) -> Vec<Option<ListLevel>> {
    let mut levels = vec![None; MAX_LIST_LEVELS];
    let mut known = vec![false; MAX_LIST_LEVELS];
    for level in &style.children {
        let Some(index) =
            level.attr("level").and_then(|l| l.parse::<usize>().ok()).filter(|l| (1..=MAX_LIST_LEVELS).contains(l))
        else {
            continue;
        };
        let label = match level.name() {
            "list-level-style-bullet" => {
                let bullet = level.attr("bullet-char").unwrap_or("•").chars().take(4).collect::<String>();
                (!bullet.is_empty()).then_some(ListLevel::Bullet(bullet))
            }
            "list-level-style-number" => match level.attr("num-format") {
                Some("") | None => None,
                Some(format) => Some(ListLevel::Number {
                    format: format.chars().take(1).collect(),
                    prefix: level.attr("num-prefix").unwrap_or("").chars().take(8).collect(),
                    suffix: level.attr("num-suffix").unwrap_or("").chars().take(8).collect(),
                }),
            },
            _ => continue,
        };
        levels[index - 1] = label;
        known[index - 1] = true;
    }
    // Levels the style doesn't describe keep the usual bullet.
    for (level, known) in levels.iter_mut().zip(known) {
        if !known {
            *level = Some(ListLevel::Bullet("•".to_owned()));
        }
    }
    levels
}

fn format_number(n: u32, format: &str) -> String {
    match format {
        "a" => letters(n, b'a'),
        "A" => letters(n, b'A'),
        "i" => roman(n).to_lowercase(),
        "I" => roman(n),
        _ => n.to_string(),
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

/// `#RRGGBB` as opaque ARGB.
fn color(value: &str) -> Option<u32> {
    let hex = value.strip_prefix('#')?;
    (hex.len() == 6).then(|| u32::from_str_radix(hex, 16).ok()).flatten().map(|rgb| 0xFF00_0000 | rgb)
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use document_core::{Block, Geometry, Limits, Shape};

    use crate::testing::{open_zip, texts};
    use crate::zip::testing::zip_with;

    const MIME: &str = "application/vnd.oasis.opendocument.presentation";

    const STYLES: &str = r##"<office:document-styles><office:styles>
<style:default-style style:family="graphic"><style:graphic-properties draw:fill="solid" draw:fill-color="#729fcf" svg:stroke-color="#3465a4"/></style:default-style>
<style:style style:name="Default-title" style:family="presentation"><style:text-properties fo:font-size="44pt" fo:color="#1f4e79"/></style:style>
</office:styles><office:automatic-styles>
<style:page-layout style:name="PM1"><style:page-layout-properties fo:page-width="25.4cm" fo:page-height="19.05cm"/></style:page-layout>
<style:style style:name="Mdp1" style:family="drawing-page"><style:drawing-page-properties draw:fill="solid" draw:fill-color="#fdf6e3"/></style:style>
<style:style style:name="Mgr1" style:family="graphic"><style:graphic-properties draw:fill="solid" draw:fill-color="#003366" draw:stroke="none"/></style:style>
</office:automatic-styles><office:master-styles>
<style:master-page style:name="Brand" style:page-layout-name="PM1" draw:style-name="Mdp1">
<draw:custom-shape draw:style-name="Mgr1" svg:x="0cm" svg:y="18cm" svg:width="25.4cm" svg:height="1.05cm"><draw:enhanced-geometry draw:type="rectangle"/></draw:custom-shape>
<draw:frame presentation:class="title" presentation:placeholder="true" svg:x="1cm" svg:y="1cm" svg:width="20cm" svg:height="3cm"><draw:text-box/></draw:frame>
</style:master-page></office:master-styles></office:document-styles>"##;

    const CONTENT: &str = r##"<office:document-content><office:automatic-styles>
<style:style style:name="pr1" style:family="presentation" style:parent-style-name="Default-title"><style:graphic-properties draw:fill="none"/></style:style>
<style:style style:name="gr1" style:family="graphic"><style:graphic-properties draw:fill="solid" draw:fill-color="#ffcc00" draw:stroke="solid" svg:stroke-color="#000000" svg:stroke-width="0.1cm" draw:textarea-vertical-align="bottom"/></style:style>
<style:style style:name="gr2" style:family="graphic"><style:graphic-properties draw:stroke="solid" svg:stroke-color="#ff0000"/></style:style>
<style:style style:name="dp2" style:family="drawing-page"><style:drawing-page-properties draw:fill="solid" draw:fill-color="#000000"/></style:style>
<style:style style:name="P1" style:family="paragraph"><style:paragraph-properties fo:text-align="center"/></style:style>
<style:style style:name="T1" style:family="text"><style:text-properties fo:font-weight="bold" fo:font-size="20pt" fo:color="#c00000"/></style:style>
<text:list-style style:name="L1"><text:list-level-style-number text:level="1" style:num-format="1" style:num-suffix="."/><text:list-level-style-bullet text:level="2" text:bullet-char="–"/></text:list-style>
</office:automatic-styles><office:body><office:presentation>
<draw:page draw:name="One" draw:master-page-name="Brand">
<draw:frame presentation:style-name="pr1" draw:text-style-name="P1" presentation:class="title" svg:x="1cm" svg:y="1cm" svg:width="20cm" svg:height="3cm"><draw:text-box><text:p text:style-name="P1">Big <text:span text:style-name="T1">news</text:span><text:s text:c="2"/>today</text:p></draw:text-box></draw:frame>
<draw:frame presentation:class="outline" svg:x="1cm" svg:y="5cm" svg:width="20cm" svg:height="8cm"><draw:text-box>
<text:list text:style-name="L1"><text:list-item><text:p>First</text:p><text:list><text:list-item><text:p>Detail</text:p></text:list-item></text:list></text:list-item><text:list-item><text:p>Second</text:p><text:p>More of second</text:p></text:list-item></text:list>
<text:list><text:list-item><text:p>Plain bullet</text:p></text:list-item></text:list></draw:text-box></draw:frame>
<draw:custom-shape draw:style-name="gr1" draw:transform="rotate (1.5707963) translate (2cm 3cm)" svg:width="4cm" svg:height="2cm"><text:p>Oval <text:a xlink:href="https://example.com">link</text:a><text:a xlink:href="javascript:x">bad</text:a></text:p><draw:enhanced-geometry draw:type="ellipse"/></draw:custom-shape>
<draw:g><draw:line draw:style-name="gr2" svg:x1="5cm" svg:y1="5cm" svg:x2="1cm" svg:y2="6cm"/><draw:rect svg:x="1cm" svg:y="1cm" svg:width="1cm" svg:height="1cm"/></draw:g>
<draw:frame svg:x="2cm" svg:y="2cm" svg:width="8cm" svg:height="4.5cm"><draw:plugin xlink:href="Media/clip.mp4" draw:mime-type="video/mp4"><draw:param draw:name="Loop" draw:value="false"/></draw:plugin><draw:image xlink:href="Pictures/poster.png"/></draw:frame>
<draw:frame svg:x="12cm" svg:y="2cm" svg:width="8cm" svg:height="4.5cm"><draw:plugin xlink:href="vnd.sun.star.Package:Media/song.wav"/></draw:frame>
<draw:frame svg:x="1cm" svg:y="14cm" svg:width="10cm" svg:height="3cm"><table:table table:use-first-row-styles="true"><table:table-row><table:table-cell><text:p>Q</text:p></table:table-cell><table:table-cell><text:p>Sales</text:p></table:table-cell></table:table-row><table:table-row><table:table-cell><text:p>Q1</text:p></table:table-cell><table:table-cell><text:p>1</text:p><text:p>2</text:p></table:table-cell></table:table-row></table:table></draw:frame>
<draw:frame presentation:class="subtitle" presentation:placeholder="true" svg:x="1cm" svg:y="1cm" svg:width="1cm" svg:height="1cm"><draw:text-box/></draw:frame>
<draw:frame svg:x="1cm" svg:y="1cm" svg:width="1cm" svg:height="1cm"><draw:plugin xlink:href="https://evil.example/x.mp4"/></draw:frame>
<presentation:notes><draw:page-thumbnail/><draw:frame presentation:class="notes"><draw:text-box><text:p>Say hello</text:p><text:p/></draw:text-box></draw:frame></presentation:notes>
</draw:page>
<draw:page draw:style-name="dp2" draw:master-page-name="Missing"/>
</office:presentation></office:body></office:document-content>"##;

    fn deck() -> Vec<u8> {
        zip_with(
            &[
                ("mimetype", MIME.as_bytes()),
                ("styles.xml", STYLES.as_bytes()),
                ("content.xml", CONTENT.as_bytes()),
                ("Media/clip.mp4", b"mp4"),
                ("Media/song.wav", b"wav"),
                ("Pictures/poster.png", b"\x89PNG"),
            ],
            false,
        )
    }

    fn shapes(blocks: &[Block]) -> Vec<&Shape> {
        blocks.iter().filter_map(|b| if let Block::Shape(s) = b { Some(&**s) } else { None }).collect()
    }

    fn near(a: f32, b: f32) -> bool {
        (a - b).abs() < 0.5
    }

    #[test]
    fn pages_become_slides_with_their_masters_styles_and_media() {
        let (_, blocks) = open_zip(deck());
        let Block::Slide { width, height, background, .. } = &blocks[1] else { panic!("{:?}", blocks[1]) };
        assert!(near(*width, 720.0) && near(*height, 540.0), "the master's page layout");
        assert_eq!(*background, Some(0xFFFD_F6E3), "the master's background");

        let notes_at = blocks.iter().position(|b| matches!(b, Block::Quote { .. })).unwrap();
        let shapes = shapes(&blocks[..notes_at]);
        // The master's band comes first, its title placeholder not at all.
        assert_eq!(shapes[0].fill, Some(0xFF00_3366));
        assert_eq!(shapes[0].line, None);
        assert!(near(shapes[0].y, 510.2));

        let title = shapes[1];
        assert_eq!(title.fill, None);
        let p = &title.paragraphs[0];
        assert_eq!(p.text.text, "Big news  today");
        assert_eq!((p.size, p.color, p.align), (44.0, Some(0xFF1F_4E79), 1), "the presentation style's font");
        assert_eq!(p.text.spans.len(), 1);
        let span = &p.text.spans[0];
        assert_eq!((span.start, span.end, span.style), (4, 8, document_core::style::BOLD));
        assert_eq!(span.color, Some(0xFFC0_0000), "a colour on one word stays on that word");

        let outline: Vec<_> =
            shapes[2].paragraphs.iter().map(|p| (p.bullet.clone(), p.level, p.text.text.as_str())).collect();
        assert_eq!(
            outline,
            [
                (Some("1.".into()), 0, "First"),
                (Some("–".into()), 1, "Detail"),
                (Some("2.".into()), 0, "Second"),
                (None, 0, "More of second"),
                (Some("•".into()), 0, "Plain bullet"),
            ]
        );

        let oval = shapes[3];
        assert_eq!(oval.geometry, Geometry::Ellipse);
        assert_eq!((oval.fill, oval.line, oval.anchor), (Some(0xFFFF_CC00), Some(0xFF00_0000), 2));
        assert!(near(oval.x, 56.7) && near(oval.y, 85.0) && near(oval.rotation, 270.0), "{oval:?}");
        assert_eq!(oval.paragraphs[0].text.text, "Oval linkbad");
        assert_eq!(oval.paragraphs[0].text.spans[0].link.as_deref(), Some("https://example.com"));
        assert_eq!(oval.paragraphs[0].align, 1, "drawn shapes centre their text");

        let line = shapes[4];
        assert_eq!(line.geometry, Geometry::Line);
        assert_eq!(line.line, Some(0xFFFF_0000));
        assert!(near(line.x, 28.3) && near(line.width, 113.4) && near(line.height, 28.3));
        // A plain rectangle takes the default graphic style.
        assert_eq!((shapes[5].fill, shapes[5].line), (Some(0xFF72_9FCF), Some(0xFF34_65A4)));

        assert_eq!(
            (shapes[6].media.as_deref(), shapes[6].image.as_deref()),
            (Some("Media/clip.mp4"), Some("Pictures/poster.png"))
        );
        assert_eq!(shapes[7].media.as_deref(), Some("Media/song.wav"));
        assert!(shapes[7].fill.is_some(), "media without a poster still shows");

        assert_eq!(shapes[8].table, [vec!["Q", "Sales"], vec!["Q1", "1\n2"]]);
        assert!(shapes[8].table_header);
        assert_eq!(shapes.len(), 9, "the empty placeholder and the remote video are left out");

        // An unknown master falls back to the first one.
        assert_eq!(texts(&blocks[notes_at..]), ["Q Say hello", "S Slide 2", "SLIDE 720x540 -", "SH "]);
        let Some(Block::Slide { background, .. }) = blocks.iter().rev().find(|b| matches!(b, Block::Slide { .. }))
        else {
            panic!()
        };
        assert_eq!(*background, Some(0xFF00_0000), "the page's own background");
    }

    #[test]
    fn a_frame_without_a_size_takes_its_place_in_the_layout() {
        let content = r#"<office:document-content><office:body><office:presentation><draw:page><draw:frame presentation:class="title"><draw:text-box><text:p>Talk</text:p></draw:text-box></draw:frame><draw:frame><draw:text-box><text:p>Body</text:p></draw:text-box></draw:frame></draw:page></office:presentation></office:body></office:document-content>"#;
        let file = zip_with(&[("mimetype", MIME.as_bytes()), ("content.xml", content.as_bytes())], false);
        let blocks = open_zip(file).1;
        let shapes = shapes(&blocks);
        assert_eq!(shapes.len(), 2);
        assert!(shapes.iter().all(|s| s.width > 100.0 && s.height > 20.0), "{shapes:?}");
        assert!(shapes[0].y < shapes[1].y, "the title above the body");
    }

    #[test]
    fn media_can_be_read_and_a_deck_without_styles_still_opens() {
        let mut session = crate::engine(Limits::default()).open(Cursor::new(deck())).unwrap();
        while session.next_batch().unwrap().is_some() {}
        assert_eq!(session.resource("Media/clip.mp4").unwrap(), b"mp4");
        assert!(session.resource("content.xml").is_err());

        let content = r#"<office:document-content><office:body><office:presentation><draw:page><draw:frame svg:width="1cm" svg:height="1cm" svg:x="bad"><draw:text-box><text:p>Hi</text:p></draw:text-box></draw:frame></draw:page></office:presentation></office:body></office:document-content>"#;
        let bare = zip_with(
            &[
                ("mimetype", MIME.as_bytes()),
                ("styles.xml", b"<office:document-styles><broken"),
                ("content.xml", content.as_bytes()),
            ],
            false,
        );
        assert_eq!(texts(&open_zip(bare).1), ["S Slide 1", "SLIDE 793x446 -", "SH Hi"]);
    }
}
