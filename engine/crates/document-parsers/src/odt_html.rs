//! OpenDocument text (ODT, OTT) as print-layout pages: the document rendered
//! to one HTML page that a web view shows like Writer's Print Layout, with
//! the same page script as Word's pages ([`crate::docx_html`]).
//!
//! The engine resolves what Writer resolves: default styles, common styles
//! along their `parent-style-name` chains, automatic styles, font-face
//! declarations, list styles and the outline (heading numbering) style. Each
//! master page in use becomes a section with its page size, margins,
//! columns, background, headers and footers (also left-page and first-page
//! ones); a paragraph or table whose style names a master page starts it.
//!
//! Covered: fonts (mapped to the metric-compatible ones the app ships), sizes
//! (also relative ones), bold, italic, underline and strike-through styles,
//! colours, highlight, super- and subscript, small caps, case, letter
//! spacing, hidden text; alignment, indents, first-line and hanging indents,
//! spacing above and below (added together, as Writer does), line spacing
//! (proportional, fixed, at least, leading), paragraph borders, padding and
//! background, page breaks before and after, keep with next, right tab stops
//! with leaders (tables of contents); numbered and bulleted lists with their
//! formats, prefixes, suffixes, shown levels, start values, restarts and
//! continuations, numbered headings; tables with column widths, alignment,
//! merged cells, borders, padding, backgrounds, row heights, heading rows,
//! nested tables; pictures in frames (inline or beside the text), text boxes
//! and simple shapes with their text; links; bookmarks; footnotes and
//! endnotes; page number and page count fields; fields' saved text; indexes
//! (tables of contents) as their saved text; tracked changes as accepted.
//!
//! Not covered: section columns, contextual spacing, tab stops other than
//! right ones (left stops use the default tab distance), charts and embedded
//! objects without a picture replacement (a labelled box), drawing shapes'
//! geometry beyond boxes and ellipses, frames positioned relative to the page
//! (they show where they are anchored), right-to-left text, flat XML (.fodt).
//!
//! Text from the document is always escaped, links must be web or mail
//! links, and pictures come only from inside the package, so the only script
//! in the page is the engine's own, which the viewer allows by its nonce.

use std::collections::HashMap;
use std::fmt::Write as _;
use std::rc::Rc;

use document_core::{CancelToken, EngineError, ErrorKind, Result};

use crate::docx_html::{
    BASE_CSS, FONT_FACES, PAGINATE_JS, SCRIPT_NONCE, esc, esc_attr, font_stack, format_number, resource_url,
    single_line, symbol_char,
};
use crate::dom::{self, Node};
use crate::odf::length_points;
use crate::ooxml::{is_safe_link, resolve_inside};
use crate::stream::{Parts, is_raster_picture};
use crate::xml::{Xml, XmlReader};

const MAX_STYLE_CHAIN: usize = 16;
/// Nesting of blocks and spans followed; deeper content is left out.
const MAX_DEPTH: u32 = 64;
const MAX_TABLE_DEPTH: u32 = 12;
const MAX_HTML_BYTES: usize = 64 * 1024 * 1024;
/// Repeated rows and cells are written at most this many times.
const MAX_REPEAT: u32 = 1000;
const MAX_ROW_CELLS: usize = 1024;
const MAX_SPACES: usize = 1000;
const LEVELS: usize = 10;
/// Writer's defaults where a document sets nothing.
const DEFAULT_FONT: &str = "Liberation Serif";
const DEFAULT_SIZE: f32 = 12.0;
const DEFAULT_TAB: f32 = 1.25 / 2.54 * 96.0;

/// Measures each section's header and footer once fonts have loaded: in ODF
/// the page margins reach to the header, and the body starts below it.
const MEASURE_JS: &str = r#"for(const s of document.querySelectorAll('.ez-section[data-odf]')){const d=s.dataset;
const m=(kind,min,gap)=>{const t=s.querySelector('template[data-kind="'+kind+'"][data-type="default"]');if(!t)return 0;const e=document.createElement('div');e.style.cssText='position:absolute;visibility:hidden;left:0;top:0;width:'+(d.w-d.ml-d.mr)+'px';e.innerHTML=t.innerHTML;document.body.appendChild(e);const h=e.offsetHeight;e.remove();return Math.max(h,+min)+(+gap)};
d.mt=+d.mt+m('header',d.hmin||0,d.hgap||0);d.mb=+d.mb+m('footer',d.fmin||0,d.fgap||0)}
"#;

// ------------------------------------------------------------ values

/// An ODF length as CSS pixels at 96 per inch.
fn px(value: &str) -> Option<f32> {
    let v = value.trim();
    if v == "0" {
        return Some(0.0);
    }
    length_points(v).map(|p| p * 4.0 / 3.0).filter(|p| p.is_finite() && p.abs() < 100_000.0)
}

fn points(value: &str) -> Option<f32> {
    length_points(value.trim()).filter(|p| p.is_finite() && *p > 0.0 && *p < 2000.0)
}

/// `50%` as 0.5.
fn percent(value: &str) -> Option<f32> {
    let v: f32 = value.trim().strip_suffix('%')?.trim().parse().ok()?;
    (v.is_finite() && (0.0..=100_000.0).contains(&v.abs())).then_some(v / 100.0)
}

/// A `#rrggbb` colour, lowercased; anything else is refused.
fn hex(value: &str) -> Option<String> {
    let h = value.trim().strip_prefix('#')?;
    (h.len() == 6 && h.bytes().all(|b| b.is_ascii_hexdigit())).then(|| format!("#{}", h.to_ascii_lowercase()))
}

/// An ODF border (`0.5pt solid #000000`) as CSS, or `None` for no border.
fn border(value: &str) -> Option<String> {
    let mut width = 1.0f32;
    let mut style = "solid";
    let mut color = "#000000".to_owned();
    for token in value.split_whitespace() {
        if let Some(w) = px(token) {
            width = w;
        } else if let Some(c) = hex(token) {
            color = c;
        } else {
            match token {
                "none" | "hidden" => return None,
                "solid" | "double" | "dotted" | "dashed" | "groove" | "ridge" | "inset" | "outset" => style = token,
                "dash" | "long-dash" | "dot-dash" | "dot-dot-dash" => style = "dashed",
                "dot" => style = "dotted",
                _ => {}
            }
        }
    }
    if width <= 0.0 {
        return None;
    }
    let width = if style == "double" { width.max(3.0) } else { width.max(0.75) };
    Some(format!("{width:.2}px {style} {color}"))
}

/// Text with Writer's white space rule: runs of spaces, tabs and line ends
/// are one space, and a paragraph starts without one.
fn push_text(out: &mut String, text: &str, last_space: &mut bool) {
    let mut clean = String::with_capacity(text.len());
    for c in text.chars() {
        if matches!(c, ' ' | '\t' | '\n' | '\r') {
            if !*last_space {
                clean.push(' ');
                *last_space = true;
            }
        } else {
            clean.push(c);
            *last_space = false;
        }
    }
    esc(out, &clean);
}

/// An attribute value: escaped, and without tabs, which mark tab positions in content.
fn attr(text: &str) -> String {
    esc_attr(text).replace('\t', " ")
}

// ------------------------------------------------------------ styles

/// Formatting properties, keyed by the kind of properties element (`p`
/// paragraph, `t` text, `T` table, `c` column, `r` row, `C` cell, `g`
/// graphic) and the attribute's local name, such as `t:font-size`.
type Props = HashMap<String, String>;

fn get<'a>(props: &'a Props, key: &str) -> Option<&'a str> {
    props.get(key).map(String::as_str)
}

fn get_px(props: &Props, key: &str) -> Option<f32> {
    get(props, key).and_then(px)
}

fn props_kind(element: &str) -> Option<char> {
    Some(match element {
        "paragraph-properties" => 'p',
        "text-properties" => 't',
        "table-properties" => 'T',
        "table-column-properties" => 'c',
        "table-row-properties" => 'r',
        "table-cell-properties" => 'C',
        "graphic-properties" => 'g',
        _ => return None,
    })
}

/// Shorthands are spread to their sides, so an override of one side merges.
fn insert_prop(props: &mut Props, kind: char, name: &str, value: &str) {
    let sides = |props: &mut Props, base: &str| {
        for side in ["top", "bottom", "left", "right"] {
            props.insert(format!("{kind}:{base}-{side}"), value.to_owned());
        }
    };
    match name {
        "margin" => sides(props, "margin"),
        "padding" => sides(props, "padding"),
        "border" => sides(props, "border"),
        _ => {
            props.insert(format!("{kind}:{name}"), value.to_owned());
        }
    }
}

#[derive(Clone, Debug)]
struct TabStop {
    /// Pixels from the paragraph's indent.
    pos: f32,
    kind: String,
    leader: bool,
}

fn read_tabs(node: &Node) -> Vec<TabStop> {
    node.children_named("tab-stop")
        .filter_map(|t| {
            let leader = t.attr("leader-style").is_some_and(|s| s != "none")
                || t.attr("leader-text").is_some_and(|s| !s.trim().is_empty());
            Some(TabStop { pos: px(t.attr("position")?)?, kind: t.attr("type").unwrap_or("left").to_owned(), leader })
        })
        .collect()
}

#[derive(Default)]
struct Style {
    parent: Option<String>,
    props: Props,
    tabs: Option<Vec<TabStop>>,
    list_style: Option<String>,
    master: Option<String>,
}

fn read_style(node: &Node) -> Style {
    let mut st = Style {
        parent: node.attr("parent-style-name").map(str::to_owned),
        list_style: node.attr("list-style-name").filter(|s| !s.is_empty()).map(str::to_owned),
        master: node.attr("master-page-name").map(str::to_owned),
        ..Style::default()
    };
    for child in &node.children {
        let Some(kind) = props_kind(child.name()) else { continue };
        for (name, value) in child.tag_attrs() {
            insert_prop(&mut st.props, kind, name, value);
        }
        if kind == 'p'
            && let Some(tabs) = child.child("tab-stops")
        {
            st.tabs = Some(read_tabs(tabs));
        }
    }
    st
}

/// `own` over `base`. A relative font size becomes absolute against the
/// base, and a font named one way replaces one named the other.
fn merge(base: &Props, own: &Props) -> Props {
    let mut out = base.clone();
    for (k, v) in own {
        match k.as_str() {
            "t:font-size" => {
                if let Some(rel) = percent(v) {
                    let base_size = get(base, "t:font-size").and_then(points).unwrap_or(DEFAULT_SIZE);
                    out.insert(k.clone(), format!("{:.2}pt", (base_size * rel).clamp(1.0, 1600.0)));
                    continue;
                }
            }
            "t:font-name" => {
                out.remove("t:font-family");
            }
            "t:font-family" => {
                out.remove("t:font-name");
            }
            _ => {}
        }
        out.insert(k.clone(), v.clone());
    }
    out
}

/// A style flattened along its parents, over its family's default.
struct Resolved {
    props: Props,
    tabs: Vec<TabStop>,
    list_style: Option<String>,
    master: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
enum Label {
    Number { format: String, prefix: String, suffix: String, levels: usize },
    Bullet(String),
    None,
}

#[derive(Clone, Debug)]
struct ListLevel {
    label: Label,
    start: u32,
    text_style: Option<String>,
    props: Props,
    /// `label-alignment` (Writer's own since 2008) rather than the older
    /// `label-width-and-position`.
    alignment: bool,
    margin_left: f32,
    text_indent: f32,
    tab_pos: Option<f32>,
    follow: String,
    space_before: f32,
    min_width: f32,
    min_distance: f32,
}

#[derive(Default)]
struct ListStyle {
    levels: Vec<Option<ListLevel>>,
}

fn read_list_style(node: &Node) -> ListStyle {
    let mut levels: Vec<Option<ListLevel>> = vec![None; LEVELS];
    for lvl in &node.children {
        let label = match lvl.name() {
            "list-level-style-number" | "outline-level-style" => {
                let format = lvl.attr("num-format").unwrap_or("").to_owned();
                let prefix = lvl.attr("num-prefix").unwrap_or("").to_owned();
                let suffix = lvl.attr("num-suffix").unwrap_or("").to_owned();
                let levels = lvl.attr("display-levels").and_then(|d| d.parse().ok()).unwrap_or(1usize).clamp(1, LEVELS);
                if format.is_empty() && prefix.is_empty() && suffix.is_empty() {
                    Label::None
                } else {
                    Label::Number { format, prefix, suffix, levels }
                }
            }
            "list-level-style-bullet" => Label::Bullet(lvl.attr("bullet-char").unwrap_or("•").to_owned()),
            "list-level-style-image" => Label::Bullet("•".to_owned()),
            _ => continue,
        };
        let Some(level) = lvl.attr("level").and_then(|l| l.parse::<usize>().ok()).filter(|l| (1..=LEVELS).contains(l))
        else {
            continue;
        };
        let mut props = Props::new();
        if let Some(t) = lvl.child("text-properties") {
            for (name, value) in t.tag_attrs() {
                insert_prop(&mut props, 't', name, value);
            }
        }
        let geometry = lvl.child("list-level-properties");
        let g = |name: &str| geometry.and_then(|g| g.attr(name)).and_then(px).unwrap_or(0.0);
        let align = geometry.and_then(|g| g.child("list-level-label-alignment"));
        let a = |name: &str| align.and_then(|a| a.attr(name)).and_then(px);
        levels[level - 1] = Some(ListLevel {
            label,
            start: lvl.attr("start-value").and_then(|s| s.parse().ok()).unwrap_or(1),
            text_style: lvl.attr("style-name").map(str::to_owned),
            props,
            alignment: geometry.and_then(|g| g.attr("list-level-position-and-space-mode")) == Some("label-alignment"),
            margin_left: a("margin-left").unwrap_or(0.0),
            text_indent: a("text-indent").unwrap_or(0.0),
            tab_pos: a("list-tab-stop-position"),
            follow: align.and_then(|a| a.attr("label-followed-by")).unwrap_or("listtab").to_owned(),
            space_before: g("space-before"),
            min_width: g("min-label-width"),
            min_distance: g("min-label-distance"),
        });
    }
    ListStyle { levels }
}

/// The outline style's lists live under this name, which no document can use.
const OUTLINE: &str = "\u{0}outline";

#[derive(Default)]
struct Styles {
    defaults: HashMap<String, Props>,
    default_tabs: Vec<TabStop>,
    /// `family/name` of `office:styles`.
    common: HashMap<String, Style>,
    /// `family/name` of the automatic styles of the part being written.
    auto: HashMap<String, Style>,
    fonts: HashMap<String, String>,
    lists: HashMap<String, Rc<ListStyle>>,
    auto_lists: HashMap<String, Rc<ListStyle>>,
    cache: HashMap<String, Rc<Resolved>>,
    tab_distance: f32,
}

fn style_key(family: &str, name: &str) -> String {
    format!("{family}/{name}")
}

impl Styles {
    fn read_fonts(&mut self, root: &Node) {
        let Some(decls) = root.child("font-face-decls") else { return };
        for face in decls.children_named("font-face") {
            if let (Some(name), Some(family)) = (face.attr("name"), face.attr("font-family")) {
                self.fonts.insert(name.to_owned(), first_family(family));
            }
        }
    }

    /// The styles of `office:styles` or of an automatic-styles element.
    fn read_into(node: &Node, styles: &mut HashMap<String, Style>, lists: &mut HashMap<String, Rc<ListStyle>>) {
        for child in &node.children {
            match child.name() {
                "style" => {
                    let (Some(name), Some(family)) = (child.attr("name"), child.attr("family")) else { continue };
                    styles.insert(style_key(family, name), read_style(child));
                }
                "list-style" => {
                    if let Some(name) = child.attr("name") {
                        lists.insert(name.to_owned(), Rc::new(read_list_style(child)));
                    }
                }
                _ => {}
            }
        }
    }

    fn read_common(&mut self, node: &Node) {
        Self::read_into(node, &mut self.common, &mut self.lists);
        for child in &node.children {
            match child.name() {
                "default-style" => {
                    let Some(family) = child.attr("family") else { continue };
                    let st = read_style(child);
                    if family == "paragraph" {
                        if let Some(d) = node_props_attr(child, "paragraph-properties", "tab-stop-distance") {
                            self.tab_distance = d;
                        }
                        self.default_tabs = st.tabs.clone().unwrap_or_default();
                    }
                    self.defaults.insert(family.to_owned(), st.props);
                }
                "outline-style" => {
                    self.lists.insert(OUTLINE.to_owned(), Rc::new(read_list_style(child)));
                }
                _ => {}
            }
        }
    }

    fn set_auto(&mut self, auto: HashMap<String, Style>, lists: HashMap<String, Rc<ListStyle>>) {
        self.auto = auto;
        self.auto_lists = lists;
        self.cache.clear();
    }

    fn lookup(&self, key: &str, first: bool) -> Option<&Style> {
        // An automatic style's parent is a common one; a name may be in both.
        if first {
            self.auto.get(key).or_else(|| self.common.get(key))
        } else {
            self.common.get(key).or_else(|| self.auto.get(key))
        }
    }

    fn resolve(&mut self, family: &str, name: Option<&str>) -> Rc<Resolved> {
        let cache_key = style_key(family, name.unwrap_or(""));
        if let Some(r) = self.cache.get(&cache_key) {
            return r.clone();
        }
        let mut chain: Vec<&Style> = Vec::new();
        let mut next = name.map(str::to_owned);
        while let Some(current) = next {
            if chain.len() >= MAX_STYLE_CHAIN {
                break;
            }
            let Some(style) = self.lookup(&style_key(family, &current), chain.is_empty()) else { break };
            chain.push(style);
            next = style.parent.clone().filter(|p| *p != current);
        }
        // Text styles apply over the paragraph's text, so they start empty.
        let mut props =
            if family == "text" { Props::new() } else { self.defaults.get(family).cloned().unwrap_or_default() };
        let mut tabs = if family == "paragraph" { self.default_tabs.clone() } else { Vec::new() };
        let mut list_style = None;
        for style in chain.iter().rev() {
            props = merge(&props, &style.props);
            if let Some(t) = &style.tabs {
                tabs = t.clone();
            }
            if style.list_style.is_some() {
                list_style = style.list_style.clone();
            }
        }
        let master = chain.first().and_then(|s| s.master.clone());
        let resolved = Rc::new(Resolved { props, tabs, list_style, master });
        self.cache.insert(cache_key, resolved.clone());
        resolved
    }

    /// Whether the named style itself (not a parent) sets this property.
    fn own_sets(&self, family: &str, name: Option<&str>, key: &str) -> bool {
        name.and_then(|n| self.lookup(&style_key(family, n), true)).is_some_and(|s| s.props.contains_key(key))
    }

    fn list(&self, name: &str) -> Option<Rc<ListStyle>> {
        self.auto_lists.get(name).or_else(|| self.lists.get(name)).cloned()
    }

    fn level(&self, name: &str, level: usize) -> Option<ListLevel> {
        self.list(name)?.levels.get(level)?.clone()
    }

    fn font_of(&self, props: &Props) -> Option<String> {
        get(props, "t:font-name")
            .map(|n| self.fonts.get(n).cloned().unwrap_or_else(|| n.to_owned()))
            .or_else(|| get(props, "t:font-family").map(first_family))
            .filter(|f| !f.is_empty())
    }
}

fn node_props_attr(node: &Node, element: &str, name: &str) -> Option<f32> {
    node.child(element).and_then(|p| p.attr(name)).and_then(px).filter(|d| *d > 1.0)
}

/// `'Liberation Serif', serif` as `Liberation Serif`.
fn first_family(list: &str) -> String {
    list.split(',').next().unwrap_or("").trim().trim_matches(|c| c == '\'' || c == '"').trim().to_owned()
}

// ------------------------------------------------------------ pages

#[derive(Clone)]
struct PageLayout {
    width: f32,
    height: f32,
    /// top, right, bottom, left.
    margins: [f32; 4],
    background: Option<String>,
    cols: u32,
    gap: f32,
    /// Header and footer: min height and the space between it and the body.
    header: (f32, f32),
    footer: (f32, f32),
}

impl Default for PageLayout {
    fn default() -> Self {
        // A4 with 2 cm margins: Writer's default.
        let cm = 96.0 / 2.54;
        PageLayout {
            width: 21.0 * cm,
            height: 29.7 * cm,
            margins: [2.0 * cm; 4],
            background: None,
            cols: 1,
            gap: 0.0,
            header: (0.0, 0.0),
            footer: (0.0, 0.0),
        }
    }
}

fn read_page_layout(node: &Node) -> PageLayout {
    let mut l = PageLayout::default();
    if let Some(p) = node.child("page-layout-properties") {
        let mut props = Props::new();
        for (name, value) in p.tag_attrs() {
            insert_prop(&mut props, 'L', name, value);
        }
        l.width = get_px(&props, "L:page-width").unwrap_or(l.width).clamp(96.0, 4000.0);
        l.height = get_px(&props, "L:page-height").unwrap_or(l.height).clamp(96.0, 6000.0);
        let m = |side: &str, d: f32| get_px(&props, &format!("L:margin-{side}")).map_or(d, |v| v.clamp(0.0, 2000.0));
        l.margins =
            [m("top", l.margins[0]), m("right", l.margins[1]), m("bottom", l.margins[2]), m("left", l.margins[3])];
        l.background = get(&props, "L:background-color").and_then(hex);
        if let Some(c) = p.child("columns") {
            l.cols = c.attr("column-count").and_then(|n| n.parse().ok()).unwrap_or(1u32).clamp(1, 6);
            l.gap = c.attr("column-gap").and_then(px).unwrap_or(0.0).max(0.0);
        }
    }
    let part = |kind: &str| {
        node.child(kind).and_then(|h| h.child("header-footer-properties")).map_or((0.0, 0.0), |p| {
            let min = p.attr("min-height").or(p.attr("height")).and_then(px).unwrap_or(0.0).clamp(0.0, 2000.0);
            let gap = p.attr(if kind == "header-style" { "margin-bottom" } else { "margin-top" }).and_then(px);
            (min, gap.unwrap_or(0.0).clamp(0.0, 2000.0))
        })
    };
    l.header = part("header-style");
    l.footer = part("footer-style");
    l
}

struct Master<'a> {
    layout: String,
    /// (`header` / `footer`, `default` / `even` / `first`, the part's element).
    parts: Vec<(&'static str, &'static str, &'a Node)>,
}

// ------------------------------------------------------------ the renderer

#[derive(Default)]
struct Counter {
    counts: [u32; LEVELS],
    set: [bool; LEVELS],
}

/// The list a block sits in.
#[derive(Clone)]
struct ListContext {
    style: String,
    level: usize,
}

#[derive(Clone, Default)]
struct Context {
    cell: bool,
    list: Option<ListContext>,
}

struct Renderer {
    styles: Styles,
    counters: HashMap<String, Counter>,
    cancel: CancelToken,
    steps: u32,
    depth: u32,
    table_depth: u32,
    notes: String,
    endnotes: String,
    note_count: u32,
    in_note: bool,
    /// The label for the first paragraph of the list item being written.
    pending_label: Option<String>,
    /// Written at the start of the next paragraph (a note's citation).
    pending_prefix: Option<String>,
    last_space: bool,
}

impl Renderer {
    fn check(&mut self) -> Result<()> {
        self.steps += 1;
        if self.steps % 512 == 0 {
            self.cancel.check()?;
        }
        Ok(())
    }

    fn blocks(&mut self, parent: &Node, ctx: &Context, out: &mut String) -> Result<()> {
        if self.depth >= MAX_DEPTH {
            return Ok(());
        }
        self.depth += 1;
        let result = parent.children.iter().try_for_each(|child| {
            self.block(child, ctx, out)?;
            if out.len() > MAX_HTML_BYTES {
                return Err(EngineError::limit("the document is too large to lay out as pages"));
            }
            Ok(())
        });
        self.depth -= 1;
        result
    }

    fn block(&mut self, node: &Node, ctx: &Context, out: &mut String) -> Result<()> {
        self.check()?;
        match node.name() {
            "p" | "h" => self.paragraph(node, ctx, out),
            "list" => self.list(node, ctx, out),
            "table" => self.table(node, ctx, out),
            "section" | "index-body" | "index-title" | "numbered-paragraph" | "table-of-content"
            | "illustration-index" | "alphabetical-index" | "object-index" | "table-index" | "user-index"
            | "bibliography" => self.blocks(node, ctx, out),
            "frame" | "custom-shape" | "rect" | "ellipse" | "circle" => {
                // Anchored to the page or paragraph outside any paragraph: placed where it is.
                out.push_str("<div class=\"ez-p\">");
                let base = self.styles.resolve("paragraph", None).props.clone();
                self.drawing(node, &base, out)?;
                out.push_str("</div>");
                Ok(())
            }
            "bookmark" | "bookmark-start" => {
                if let Some(name) = node.attr("name") {
                    let _ = write!(out, "<a id=\"bm-{}\"></a>", attr(name));
                }
                Ok(())
            }
            // Index source settings, tracked changes, declarations, forms, comments.
            _ => Ok(()),
        }
    }

    // ---- lists

    fn list(&mut self, node: &Node, ctx: &Context, out: &mut String) -> Result<()> {
        let parent = ctx.list.as_ref();
        let style = node
            .attr("style-name")
            .map(str::to_owned)
            .or_else(|| parent.map(|p| p.style.clone()))
            .or_else(|| {
                // A list with no style of its own takes its paragraphs' list style.
                let item = node.children.iter().find(|c| c.name() == "list-item")?;
                let para = item.children.iter().find(|c| matches!(c.name(), "p" | "h"))?;
                self.styles.resolve("paragraph", para.attr("style-name")).list_style.clone()
            })
            .unwrap_or_default();
        let level = parent.map_or(0, |p| (p.level + 1).min(LEVELS - 1));
        let continues = node.attr("continue-numbering") == Some("true") || node.attr("continue-list").is_some();
        if parent.is_none() && !continues {
            self.counters.remove(&style);
        }
        // A list inside an item takes the item's label only through its own items.
        self.pending_label = None;
        let inner = Context { cell: ctx.cell, list: Some(ListContext { style: style.clone(), level }) };
        for item in &node.children {
            let header = match item.name() {
                "list-item" => false,
                "list-header" => true,
                _ => continue,
            };
            self.check()?;
            let start = item.attr("start-value").and_then(|s| s.parse().ok());
            self.pending_label = if header { None } else { Some(self.next_label(&style, level, start)) };
            self.blocks(item, &inner, out)?;
            self.pending_label = None;
        }
        Ok(())
    }

    /// Counts an item at `level` of the list style and returns its label.
    fn next_label(&mut self, style: &str, level: usize, start: Option<u32>) -> String {
        let list = self.styles.list(style);
        let level_of = |l: usize| list.as_ref().and_then(|s| s.levels.get(l).cloned().flatten());
        let counter = self.counters.entry(style.to_owned()).or_default();
        let own_start = level_of(level).map_or(1, |l| l.start);
        if let Some(start) = start {
            counter.counts[level] = start;
        } else if counter.set[level] {
            counter.counts[level] = counter.counts[level].saturating_add(1);
        } else {
            counter.counts[level] = own_start;
        }
        counter.set[level] = true;
        for deeper in level + 1..LEVELS {
            counter.set[deeper] = false;
        }
        let counts = counter.counts;
        let set = counter.set;
        match level_of(level).map(|l| l.label) {
            Some(Label::Number { prefix, suffix, levels, .. }) => {
                let first = (level + 1).saturating_sub(levels);
                let parts: Vec<String> = (first..=level)
                    .map(|l| {
                        let lvl = level_of(l);
                        let n = if set[l] { counts[l] } else { lvl.as_ref().map_or(1, |l| l.start) };
                        match lvl.map(|l| l.label) {
                            Some(Label::Number { format, .. }) => number_text(n, &format),
                            _ => n.to_string(),
                        }
                    })
                    .filter(|p| !p.is_empty())
                    .collect();
                format!("{prefix}{}{suffix}", parts.join("."))
            }
            Some(Label::Bullet(ch)) => ch,
            _ => String::new(),
        }
    }

    // ---- paragraphs

    fn paragraph(&mut self, node: &Node, ctx: &Context, out: &mut String) -> Result<()> {
        let style_name = node.attr("style-name");
        let style = self.styles.resolve("paragraph", style_name);
        let props = &style.props;

        // List geometry: the list's level, or for a numbered heading the outline's.
        let mut label = None;
        let mut level_def = None;
        if let Some(list) = &ctx.list {
            label = self.pending_label.take();
            level_def = self.styles.level(&list.style, list.level);
        } else if node.name() == "h" && node.attr("is-list-header") != Some("true") {
            let level =
                node.attr("outline-level").and_then(|l| l.parse::<usize>().ok()).unwrap_or(1).clamp(1, LEVELS) - 1;
            if let Some(lvl) = self.styles.level(OUTLINE, level).filter(|l| l.label != Label::None) {
                let start = node
                    .attr("restart-numbering")
                    .filter(|r| *r == "true")
                    .map(|_| node.attr("start-value").and_then(|s| s.parse().ok()).unwrap_or(lvl.start));
                label = Some(self.next_label(OUTLINE, level, start));
                level_def = Some(lvl);
            }
        }

        let mut left = get_px(props, "p:margin-left").unwrap_or(0.0);
        let mut first = get_px(props, "p:text-indent").unwrap_or(0.0);
        let mut label_width = 0.0;
        if let Some(lvl) = &level_def {
            let own_left = self.styles.own_sets("paragraph", style_name, "p:margin-left");
            if lvl.alignment {
                if !own_left {
                    left = lvl.margin_left;
                }
                let indent = if self.styles.own_sets("paragraph", style_name, "p:text-indent") {
                    first
                } else {
                    lvl.text_indent
                };
                let label_start = left + indent;
                label_width = match lvl.follow.as_str() {
                    "listtab" => lvl
                        .tab_pos
                        .filter(|t| *t > label_start + 1.0)
                        .map_or_else(|| if indent < -1.0 { -indent } else { self.tab_distance() }, |t| t - label_start),
                    _ => 0.0,
                };
                first = if label.is_some() { indent } else { 0.0 };
            } else {
                left += lvl.space_before + lvl.min_width;
                label_width = lvl.min_width;
                first = if label.is_some() { -lvl.min_width } else { 0.0 };
            }
        }

        if get(props, "p:break-before") == Some("page") && !ctx.cell {
            out.push_str("<div class=\"ez-pb\"></div>");
        }
        let keep = get(props, "p:keep-with-next") == Some("always") && !ctx.cell;
        out.push_str(if keep { "<div class=\"ez-p ez-keep\" style=\"" } else { "<div class=\"ez-p\" style=\"" });
        self.paragraph_css(out, props, left, first);
        out.push_str("\">");

        if let Some(prefix) = self.pending_prefix.take() {
            out.push_str(&prefix);
        }
        if let (Some(text), Some(lvl)) = (&label, &level_def)
            && (!text.is_empty() || lvl.follow == "listtab")
        {
            let mut label_props = props.clone();
            if let Some(name) = &lvl.text_style {
                label_props = merge(&label_props, &self.styles.resolve("text", Some(name)).props);
            }
            label_props = merge(&label_props, &lvl.props);
            let font = self.styles.font_of(&label_props);
            let shown: String = text.chars().map(|c| bullet_char(c, font.as_deref())).collect();
            out.push_str("<span class=\"ez-mk\" style=\"");
            self.text_css(out, &label_props, true);
            if lvl.alignment && lvl.follow == "listtab" {
                let _ = write!(out, "min-width:{label_width:.1}px;");
            } else if !lvl.alignment {
                let _ = write!(out, "min-width:{label_width:.1}px;padding-right:{:.1}px;", lvl.min_distance);
            }
            out.push_str("\">");
            esc(out, &shown);
            if lvl.alignment && lvl.follow == "space" {
                out.push(' ');
            }
            out.push_str("</span>");
        }

        let mut content = String::new();
        self.last_space = true;
        let decorated = has_inline_only(props);
        if decorated {
            content.push_str("<span style=\"");
            self.text_css(&mut content, props, true);
            content.push_str("\">");
        }
        self.inline(node, props, &mut content)?;
        if decorated {
            content.push_str("</span>");
        }
        let content = lay_out_tabs(&style.tabs, content);
        let empty = content.is_empty() || content == "<span style=\"\"></span>";
        out.push_str(&content);
        if empty || content.ends_with("<br>") || label.as_deref().is_some_and(|_| content.is_empty()) {
            // An empty line keeps the height of its paragraph.
            out.push_str("<br>");
        }
        out.push_str("</div>");
        if get(props, "p:break-after") == Some("page") && !ctx.cell {
            out.push_str("<div class=\"ez-pb\"></div>");
        }
        Ok(())
    }

    fn tab_distance(&self) -> f32 {
        if self.styles.tab_distance > 1.0 { self.styles.tab_distance } else { DEFAULT_TAB }
    }

    fn paragraph_css(&self, out: &mut String, p: &Props, left: f32, first: f32) {
        self.text_css(out, p, false);
        let before = get_px(p, "p:margin-top").unwrap_or(0.0).max(0.0);
        let after = get_px(p, "p:margin-bottom").unwrap_or(0.0).max(0.0);
        let background = get(p, "p:background-color").and_then(hex);
        let borders: Vec<(&str, String)> = ["top", "left", "bottom", "right"]
            .iter()
            .filter_map(|side| get(p, &format!("p:border-{side}")).and_then(border).map(|b| (*side, b)))
            .collect();
        if background.is_some() || !borders.is_empty() {
            // Space outside the paragraph's box: as margins, which can collapse.
            let _ = write!(out, "margin-top:{before:.1}px;margin-bottom:{after:.1}px;");
            for side in ["top", "left", "bottom", "right"] {
                if let Some(pad) = get_px(p, &format!("p:padding-{side}")).filter(|v| *v > 0.0) {
                    let _ = write!(out, "padding-{side}:{pad:.1}px;");
                }
            }
        } else {
            // Writer adds the space below one paragraph to the space above the next.
            let _ = write!(out, "padding-top:{before:.1}px;padding-bottom:{after:.1}px;");
        }
        if left.abs() > 0.01 {
            let _ = write!(out, "margin-left:{left:.1}px;");
        }
        if let Some(r) = get_px(p, "p:margin-right").filter(|r| r.abs() > 0.01) {
            let _ = write!(out, "margin-right:{r:.1}px;");
        }
        if first.abs() > 0.01 {
            let _ = write!(out, "text-indent:{first:.1}px;");
        }
        out.push_str(match get(p, "p:text-align") {
            Some("center") => "text-align:center;",
            Some("end" | "right") => "text-align:right;",
            Some("justify") => "text-align:justify;",
            _ => "text-align:left;",
        });
        let font = self.styles.font_of(p).unwrap_or_else(|| DEFAULT_FONT.to_owned());
        let single = single_line(&font);
        let size_px = get(p, "t:font-size").and_then(points).unwrap_or(DEFAULT_SIZE) * 4.0 / 3.0;
        let line = get(p, "p:line-height");
        if let Some(rel) = line.and_then(percent) {
            let _ = write!(out, "line-height:{:.3};", single * rel.clamp(0.5, 10.0));
        } else if let Some(fixed) = line.and_then(px).filter(|v| *v > 0.0) {
            let _ = write!(out, "line-height:{fixed:.1}px;");
        } else if let Some(least) = get_px(p, "p:line-height-at-least").filter(|v| *v > 0.0) {
            let _ = write!(out, "line-height:{:.1}px;", least.max(size_px * single));
        } else if let Some(leading) = get_px(p, "p:line-spacing").filter(|v| v.abs() > 0.01) {
            let _ = write!(out, "line-height:{:.1}px;", (size_px * single + leading).max(1.0));
        } else {
            let _ = write!(out, "line-height:{single:.3};");
        }
        let _ = write!(out, "tab-size:{:.1}px;", self.tab_distance());
        if let Some(bg) = background {
            let _ = write!(out, "background:{bg};");
        }
        for (side, b) in borders {
            let _ = write!(out, "border-{side}:{b};");
        }
    }

    /// Text formatting as CSS. Decoration, highlight and position belong to
    /// text, not to a paragraph's box, so only `inline` writes them.
    fn text_css(&self, out: &mut String, p: &Props, inline: bool) {
        if let Some(font) = self.styles.font_of(p) {
            let _ = write!(out, "font-family:{};", font_stack(&font).replace('"', "&quot;"));
        } else if !inline {
            let _ = write!(out, "font-family:{};", font_stack(DEFAULT_FONT).replace('"', "&quot;"));
        }
        let position = get(p, "t:text-position").and_then(text_position).filter(|_| inline);
        let size = get(p, "t:font-size").and_then(points).or((!inline).then_some(DEFAULT_SIZE));
        if let Some(size) = size {
            let size = position.map_or(size, |(_, scale)| size * scale);
            let _ = write!(out, "font-size:{size:.1}pt;");
        }
        match get(p, "t:font-weight") {
            Some("bold" | "bolder") => out.push_str("font-weight:bold;"),
            Some(w) if w.parse::<u32>().is_ok_and(|w| w >= 600) => out.push_str("font-weight:bold;"),
            Some(_) => out.push_str("font-weight:normal;"),
            None => {}
        }
        match get(p, "t:font-style") {
            Some("italic" | "oblique") => out.push_str("font-style:italic;"),
            Some(_) => out.push_str("font-style:normal;"),
            None => {}
        }
        if get(p, "t:use-window-font-color") == Some("true") {
            out.push_str("color:#000;");
        } else if let Some(c) = get(p, "t:color").and_then(hex) {
            let _ = write!(out, "color:{c};");
        } else if !inline {
            out.push_str("color:#000;");
        }
        match get(p, "t:font-variant") {
            Some("small-caps") => out.push_str("font-variant:small-caps;"),
            Some("normal") => out.push_str("font-variant:normal;"),
            _ => {}
        }
        if let Some(t @ ("uppercase" | "lowercase" | "capitalize" | "none")) = get(p, "t:text-transform") {
            let _ = write!(out, "text-transform:{t};");
        }
        if let Some(s) = get_px(p, "t:letter-spacing").filter(|s| s.abs() > 0.01) {
            let _ = write!(out, "letter-spacing:{s:.2}px;");
        }
        if !inline {
            return;
        }
        let line_style = |style: Option<&str>, double: bool| -> Option<&'static str> {
            let style = style?;
            Some(match style {
                "none" => return None,
                _ if double => "double",
                "dotted" => "dotted",
                "dash" | "long-dash" | "dot-dash" | "dot-dot-dash" => "dashed",
                "wave" => "wavy",
                _ => "solid",
            })
        };
        let under = line_style(get(p, "t:text-underline-style"), get(p, "t:text-underline-type") == Some("double"));
        let strike =
            line_style(get(p, "t:text-line-through-style"), get(p, "t:text-line-through-type") == Some("double"));
        let lines: Vec<&str> =
            [under.map(|_| "underline"), strike.map(|_| "line-through")].into_iter().flatten().collect();
        if !lines.is_empty() {
            let _ = write!(out, "text-decoration:{} {};", lines.join(" "), under.or(strike).unwrap_or("solid"));
            if let Some(c) = get(p, "t:text-underline-color").and_then(hex).filter(|_| under.is_some()) {
                let _ = write!(out, "text-decoration-color:{c};");
            }
        }
        if let Some(bg) = get(p, "t:background-color").and_then(hex) {
            let _ = write!(out, "background:{bg};");
        }
        if let Some((shift, _)) = position {
            out.push_str(if shift > 0.0 { "vertical-align:super;" } else { "vertical-align:sub;" });
        }
        if get(p, "t:display") == Some("none") {
            out.push_str("display:none;");
        }
    }

    // ---- inline content

    fn inline(&mut self, node: &Node, base: &Props, out: &mut String) -> Result<()> {
        push_text(out, &node.text, &mut self.last_space);
        if self.depth >= MAX_DEPTH {
            return Ok(());
        }
        self.depth += 1;
        let result = node.children.iter().try_for_each(|child| {
            self.check()?;
            self.inline_child(child, base, out)?;
            push_text(out, &child.tail, &mut self.last_space);
            Ok(())
        });
        self.depth -= 1;
        result
    }

    fn styled(&mut self, node: &Node, base: &Props, out: &mut String) -> Result<()> {
        let own = self.styles.resolve("text", node.attr("style-name"));
        if own.props.is_empty() {
            return self.inline(node, base, out);
        }
        let props = merge(base, &own.props);
        out.push_str("<span style=\"");
        self.text_css(out, &props, true);
        out.push_str("\">");
        self.inline(node, &props, out)?;
        out.push_str("</span>");
        Ok(())
    }

    fn inline_child(&mut self, node: &Node, base: &Props, out: &mut String) -> Result<()> {
        match node.name() {
            "span" => self.styled(node, base, out)?,
            "a" => {
                let href = node.attr("href").map(str::trim).filter(|h| is_safe_link(h));
                if let Some(href) = href {
                    let _ = write!(out, "<a href=\"{}\">", attr(href));
                }
                self.styled(node, base, out)?;
                if href.is_some() {
                    out.push_str("</a>");
                }
            }
            "s" => {
                let n = node.attr("c").and_then(|c| c.parse::<usize>().ok()).unwrap_or(1).clamp(1, MAX_SPACES);
                out.extend(std::iter::repeat_n(' ', n));
                self.last_space = false;
            }
            "tab" => {
                out.push('\t');
                self.last_space = false;
            }
            "line-break" => {
                out.push_str("<br>");
                self.last_space = true;
            }
            "note" => self.note(node, out)?,
            "page-number" | "page-count" => {
                let class = if node.name() == "page-number" { "ez-fld-page" } else { "ez-fld-numpages" };
                let _ = write!(out, "<span class=\"{class}\">");
                self.inline(node, base, out)?;
                out.push_str("</span>");
            }
            "frame" | "custom-shape" | "rect" | "ellipse" | "circle" => self.drawing(node, base, out)?,
            "bookmark" | "bookmark-start" => {
                if let Some(name) = node.attr("name") {
                    let _ = write!(out, "<a id=\"bm-{}\"></a>", attr(name));
                }
            }
            "ruby" => {
                if let Some(b) = node.child("ruby-base") {
                    self.inline(b, base, out)?;
                }
            }
            "hidden-text" if node.attr("is-hidden") != Some("false") => {}
            // Comments, change marks, soft page breaks and declarations show nothing.
            "annotation"
            | "annotation-end"
            | "soft-page-break"
            | "bookmark-end"
            | "change"
            | "change-start"
            | "change-end"
            | "reference-mark-start"
            | "reference-mark-end"
            | "binary-data"
            | "sequence-decls"
            | "alphabetical-index-mark"
            | "toc-mark"
            | "user-index-mark" => {}
            // Fields and the like: their saved text.
            _ => self.inline(node, base, out)?,
        }
        Ok(())
    }

    fn note(&mut self, node: &Node, out: &mut String) -> Result<()> {
        let citation = node.child("note-citation").map(|c| c.text.trim().to_owned()).unwrap_or_default();
        let end = node.attr("note-class") == Some("endnote");
        self.note_count += 1;
        let n = self.note_count;
        let shown = if citation.is_empty() { n.to_string() } else { citation };
        let _ = write!(out, "<sup class=\"{}\" data-fn=\"{n}\">", if end { "ez-enref" } else { "ez-fnref" });
        esc(out, &shown);
        out.push_str("</sup>");
        let Some(body) = node.child("note-body").filter(|_| !self.in_note) else { return Ok(()) };
        let saved = (self.pending_label.take(), self.last_space);
        self.in_note = true;
        let mut html = format!("<div class=\"ez-fnbody\" id=\"ez-fn-{n}\">");
        let mut prefix = String::from("<sup>");
        esc(&mut prefix, &shown);
        prefix.push_str("</sup> ");
        self.pending_prefix = Some(prefix);
        let result = self.blocks(body, &Context::default(), &mut html);
        self.pending_prefix = None;
        self.in_note = false;
        (self.pending_label, self.last_space) = saved;
        result?;
        html.push_str("</div>");
        if end {
            self.endnotes.push_str(&html);
        } else {
            self.notes.push_str(&html);
        }
        Ok(())
    }

    // ---- frames, pictures and shapes

    fn drawing(&mut self, node: &Node, base: &Props, out: &mut String) -> Result<()> {
        let graphic = self.styles.resolve("graphic", node.attr("style-name"));
        let g = &graphic.props;
        let text_box = node.child("text-box");
        let w = node.attr("width").and_then(px).or_else(|| text_box.and_then(|t| t.attr("min-width")).and_then(px));
        let h = node.attr("height").and_then(px);
        let min_h = text_box.and_then(|t| t.attr("min-height")).and_then(px);
        let mut css = String::new();
        if let Some(w) = w.filter(|w| *w > 0.0) {
            let _ = write!(css, "width:{:.1}px;", w.min(4000.0));
        }
        match (h.filter(|h| *h > 0.0), min_h) {
            (Some(h), _) if text_box.is_none() => {
                let _ = write!(css, "height:{:.1}px;", h.min(6000.0));
            }
            (h, min) => {
                if let Some(m) = min.or(h).filter(|m| *m > 0.0) {
                    let _ = write!(css, "min-height:{:.1}px;", m.min(6000.0));
                }
            }
        }
        css.push_str(&placement_css(node, g));

        if let Some(image) = node.children_named("image").find(|i| picture_path(i).is_some()) {
            let src = picture_path(image).map(|p| resource_url(&p)).unwrap_or_default();
            let alt = node.child("desc").or(node.child("title")).map(|d| d.text.trim().to_owned()).unwrap_or_default();
            let _ = write!(
                out,
                "<img class=\"ez-img\" loading=\"lazy\" decoding=\"async\" src=\"{}\" alt=\"{}\" style=\"{css}\">",
                attr(&src),
                attr(&alt)
            );
            return Ok(());
        }
        let boxed = text_box.or_else(|| (node.name() != "frame").then_some(node));
        let Some(content) = boxed else {
            let label =
                if node.child("object").is_some() || node.child("object-ole").is_some() { "Object" } else { "Picture" };
            let _ = write!(out, "<span class=\"ez-ph\" style=\"{css}\">{label}</span>");
            return Ok(());
        };
        let _ = write!(css, "{}", box_css(g, node.name() == "ellipse" || node.name() == "circle"));
        let _ = write!(out, "<span class=\"ez-box\" style=\"{css}text-indent:0\">");
        let saved = (self.pending_label.take(), self.last_space);
        self.table_depth += 1;
        let ctx = Context { cell: true, list: None };
        let result = if self.table_depth <= MAX_TABLE_DEPTH { self.blocks(content, &ctx, out) } else { Ok(()) };
        self.table_depth -= 1;
        (self.pending_label, self.last_space) = saved;
        result?;
        let _ = base;
        out.push_str("</span>");
        Ok(())
    }

    // ---- tables

    fn table(&mut self, node: &Node, ctx: &Context, out: &mut String) -> Result<()> {
        if self.table_depth >= MAX_TABLE_DEPTH {
            return Ok(());
        }
        let style = self.styles.resolve("table", node.attr("style-name"));
        let t = style.props.clone();
        if get(&t, "T:break-before") == Some("page") && !ctx.cell {
            out.push_str("<div class=\"ez-pb\"></div>");
        }

        let mut columns: Vec<(f32, Option<String>)> = Vec::new();
        self.columns(node, &mut columns);
        let total: f32 = columns.iter().map(|c| c.0).sum();

        let mut css = String::from("table-layout:fixed;");
        let ml = get_px(&t, "T:margin-left").unwrap_or(0.0);
        let mr = get_px(&t, "T:margin-right").unwrap_or(0.0);
        match get(&t, "T:align").unwrap_or("margins") {
            "center" => css.push_str("margin-left:auto;margin-right:auto;"),
            "right" => {
                let _ = write!(css, "margin-left:auto;margin-right:{mr:.1}px;");
            }
            "left" => {
                let _ = write!(css, "margin-left:{ml:.1}px;");
            }
            _ => {
                let _ = write!(css, "margin-left:{ml:.1}px;width:calc(100% - {:.1}px);", ml + mr);
            }
        }
        if get(&t, "T:align").is_some_and(|a| a != "margins") {
            if let Some(rel) = get(&t, "T:rel-width").and_then(percent) {
                let _ = write!(css, "width:{:.2}%;", rel * 100.0);
            } else if let Some(w) = get_px(&t, "T:width").filter(|w| *w > 0.0) {
                let _ = write!(css, "width:{w:.1}px;");
            }
        }
        if let Some(bg) = get(&t, "T:background-color").and_then(hex) {
            let _ = write!(css, "background:{bg};");
        }
        let _ = write!(
            css,
            "margin-top:{:.1}px;margin-bottom:{:.1}px;",
            get_px(&t, "T:margin-top").unwrap_or(0.0).max(0.0),
            get_px(&t, "T:margin-bottom").unwrap_or(0.0).max(0.0)
        );
        let _ = write!(out, "<table class=\"ez-t\" style=\"{css}\">");
        if total > 0.0 {
            out.push_str("<colgroup>");
            for (w, _) in &columns {
                let _ = write!(out, "<col style=\"width:{:.2}%\">", w / total * 100.0);
            }
            out.push_str("</colgroup>");
        }
        out.push_str("<tbody>");
        self.table_depth += 1;
        let result = self.rows(node, &columns, false, out);
        self.table_depth -= 1;
        result?;
        out.push_str("</tbody></table>");
        if get(&t, "T:break-after") == Some("page") && !ctx.cell {
            out.push_str("<div class=\"ez-pb\"></div>");
        }
        Ok(())
    }

    /// Column widths (absolute or relative; only their ratio matters) and default cell styles.
    fn columns(&mut self, node: &Node, out: &mut Vec<(f32, Option<String>)>) {
        for child in &node.children {
            match child.name() {
                "table-column" => {
                    let style = self.styles.resolve("table-column", child.attr("style-name"));
                    let rel = get(&style.props, "c:rel-column-width")
                        .and_then(|r| r.trim().trim_end_matches('*').parse::<f32>().ok());
                    let width = rel.or_else(|| get_px(&style.props, "c:column-width")).unwrap_or(0.0).max(0.0);
                    let width = if width.is_finite() { width } else { 0.0 };
                    let repeat = child.attr("number-columns-repeated").and_then(|r| r.parse().ok()).unwrap_or(1u32);
                    let cell = child.attr("default-cell-style-name").map(str::to_owned);
                    for _ in 0..repeat.clamp(1, MAX_REPEAT) {
                        if out.len() >= MAX_ROW_CELLS {
                            return;
                        }
                        out.push((width, cell.clone()));
                    }
                }
                "table-columns" | "table-header-columns" | "table-column-group" => self.columns(child, out),
                _ => {}
            }
        }
    }

    fn rows(&mut self, node: &Node, columns: &[(f32, Option<String>)], header: bool, out: &mut String) -> Result<()> {
        for child in &node.children {
            match child.name() {
                "table-header-rows" => self.rows(child, columns, true, out)?,
                "table-rows" | "table-row-group" => self.rows(child, columns, header, out)?,
                "table-row" => {
                    let repeat = child.attr("number-rows-repeated").and_then(|r| r.parse().ok()).unwrap_or(1u32);
                    let mut html = String::new();
                    self.row(child, columns, header, &mut html)?;
                    for _ in 0..repeat.clamp(1, MAX_REPEAT) {
                        out.push_str(&html);
                        if out.len() > MAX_HTML_BYTES {
                            return Err(EngineError::limit("the document is too large to lay out as pages"));
                        }
                    }
                }
                _ => {}
            }
        }
        Ok(())
    }

    fn row(&mut self, row: &Node, columns: &[(f32, Option<String>)], header: bool, out: &mut String) -> Result<()> {
        self.check()?;
        let style = self.styles.resolve("table-row", row.attr("style-name"));
        let r = &style.props;
        out.push_str(if header { "<tr class=\"ez-hdr\"" } else { "<tr" });
        let height = get_px(r, "r:row-height").or_else(|| get_px(r, "r:min-row-height")).filter(|h| *h > 0.0);
        let bg = get(r, "r:background-color").and_then(hex);
        if height.is_some() || bg.is_some() {
            out.push_str(" style=\"");
            if let Some(h) = height {
                let _ = write!(out, "height:{:.1}px;", h.min(6000.0));
            }
            if let Some(bg) = bg {
                let _ = write!(out, "background:{bg};");
            }
            out.push('"');
        }
        out.push('>');
        let mut col = 0usize;
        for cell in &row.children {
            let covered = match cell.name() {
                "table-cell" => false,
                "covered-table-cell" => true,
                _ => continue,
            };
            let repeat = cell.attr("number-columns-repeated").and_then(|r| r.parse().ok()).unwrap_or(1u32);
            let repeat = repeat.clamp(1, MAX_REPEAT) as usize;
            if covered {
                col += repeat;
                continue;
            }
            let mut html = String::new();
            let span = |name: &str| cell.attr(name).and_then(|s| s.parse::<u32>().ok()).unwrap_or(1).clamp(1, 1000);
            let (colspan, rowspan) = (span("number-columns-spanned"), span("number-rows-spanned"));
            let fallback = columns.get(col).and_then(|c| c.1.clone());
            let style_name = cell.attr("style-name").map(str::to_owned).or(fallback);
            let style = self.styles.resolve("table-cell", style_name.as_deref());
            html.push_str("<td");
            if colspan > 1 {
                let _ = write!(html, " colspan=\"{colspan}\"");
            }
            if rowspan > 1 {
                let _ = write!(html, " rowspan=\"{rowspan}\"");
            }
            html.push_str(" style=\"");
            cell_css(&mut html, &style.props);
            html.push_str("\">");
            self.blocks(cell, &Context { cell: true, list: None }, &mut html)?;
            html.push_str("</td>");
            for _ in 0..repeat {
                if col >= MAX_ROW_CELLS {
                    break;
                }
                out.push_str(&html);
                col += colspan as usize;
            }
        }
        out.push_str("</tr>");
        Ok(())
    }
}

/// Where a frame sits: in the line, or beside or between the text.
fn placement_css(node: &Node, g: &Props) -> String {
    let mut css = String::new();
    let margin = |side: &str| get_px(g, &format!("g:margin-{side}")).unwrap_or(0.0).max(0.0);
    if node.attr("anchor-type") == Some("as-char") {
        css.push_str(match get(g, "g:vertical-pos") {
            Some("middle" | "center") => "vertical-align:middle;",
            Some("bottom") if get(g, "g:vertical-rel") != Some("baseline") => "vertical-align:bottom;",
            _ => "vertical-align:baseline;",
        });
        return css;
    }
    let h_pos = get(g, "g:horizontal-pos").unwrap_or("center");
    let side = match h_pos {
        "right" | "outside" => "right",
        "from-left" | "left" | "inside" => "left",
        _ => "center",
    };
    let wraps = matches!(get(g, "g:wrap"), Some("left" | "right" | "parallel" | "dynamic" | "biggest"));
    if wraps && side != "center" {
        let _ = write!(
            css,
            "float:{side};margin:{:.1}px {:.1}px {:.1}px {:.1}px;",
            margin("top"),
            margin("right"),
            margin("bottom"),
            margin("left")
        );
        if h_pos == "from-left"
            && let Some(x) = node.attr("x").and_then(px).filter(|x| *x > 0.0)
        {
            let _ = write!(css, "margin-left:{x:.1}px;");
        }
    } else {
        let (l, r) = match side {
            "left" => {
                let x = if h_pos == "from-left" { node.attr("x").and_then(px).unwrap_or(0.0).max(0.0) } else { 0.0 };
                (format!("{:.1}px", x.max(margin("left"))), "auto".to_owned())
            }
            "right" => ("auto".to_owned(), format!("{:.1}px", margin("right"))),
            _ => ("auto".to_owned(), "auto".to_owned()),
        };
        let _ =
            write!(css, "display:block;margin:{:.1}px {r} {:.1}px {l};text-indent:0;", margin("top"), margin("bottom"));
        if node.attr("anchor-type") == Some("page") {
            css.push_str("clear:both;");
        }
    }
    css
}

/// A text box's or shape's border, fill and padding.
fn box_css(g: &Props, round: bool) -> String {
    let mut css = String::new();
    let fill = match get(g, "g:fill") {
        Some("none") => None,
        Some("solid") => get(g, "g:fill-color").and_then(hex),
        _ => get(g, "g:background-color").and_then(hex),
    };
    if let Some(f) = fill {
        let _ = write!(css, "background:{f};");
    }
    if get(g, "g:stroke").is_some_and(|s| s != "none") {
        let width = get_px(g, "g:stroke-width").unwrap_or(1.0).max(0.75);
        let color = get(g, "g:stroke-color").and_then(hex).unwrap_or_else(|| "#000000".to_owned());
        let style = if get(g, "g:stroke") == Some("dash") { "dashed" } else { "solid" };
        let _ = write!(css, "border:{width:.2}px {style} {color};");
    }
    for side in ["top", "left", "bottom", "right"] {
        if let Some(b) = get(g, &format!("g:border-{side}")).and_then(border) {
            let _ = write!(css, "border-{side}:{b};");
        }
        if let Some(p) = get_px(g, &format!("g:padding-{side}")).filter(|p| *p > 0.0) {
            let _ = write!(css, "padding-{side}:{p:.1}px;");
        }
    }
    if round {
        css.push_str("border-radius:50%;display:inline-flex;flex-direction:column;justify-content:center;");
    }
    css
}

fn cell_css(out: &mut String, c: &Props) {
    if let Some(bg) = get(c, "C:background-color").and_then(hex) {
        let _ = write!(out, "background:{bg};");
    }
    for side in ["top", "left", "bottom", "right"] {
        if let Some(b) = get(c, &format!("C:border-{side}")).and_then(border) {
            let _ = write!(out, "border-{side}:{b};");
        }
        if let Some(p) = get_px(c, &format!("C:padding-{side}")).filter(|p| *p > 0.0) {
            let _ = write!(out, "padding-{side}:{p:.1}px;");
        }
    }
    match get(c, "C:vertical-align") {
        Some("middle") => out.push_str("vertical-align:middle;"),
        Some("bottom") => out.push_str("vertical-align:bottom;"),
        _ => {}
    }
}

/// A picture inside the package that can be shown.
fn picture_path(image: &Node) -> Option<String> {
    let href = image.attr("href")?;
    resolve_inside("", href).filter(|p| is_raster_picture(p))
}

/// Text properties that draw on the text rather than set the font.
fn has_inline_only(p: &Props) -> bool {
    let line = |k: &str| get(p, k).is_some_and(|v| v != "none");
    line("t:text-underline-style")
        || line("t:text-line-through-style")
        || get(p, "t:background-color").and_then(hex).is_some()
        || get(p, "t:text-position").and_then(text_position).is_some()
        || get(p, "t:display") == Some("none")
}

/// `super 58%`, `sub 58%` or `33% 58%` as (raise, size scale); `None` if not raised.
fn text_position(value: &str) -> Option<(f32, f32)> {
    let mut parts = value.split_whitespace();
    let shift = match parts.next()? {
        "super" => 0.33,
        "sub" => -0.33,
        other => percent(other)?,
    };
    if shift.abs() < 0.001 {
        return None;
    }
    let scale = parts.next().and_then(percent).unwrap_or(0.58).clamp(0.1, 1.0);
    Some((shift, scale))
}

/// ODF number formats (`1`, `a`, `A`, `i`, `I`) as Word's names for the shared formatter.
fn number_text(n: u32, format: &str) -> String {
    match format {
        "" => String::new(),
        "a" => format_number(n, "lowerLetter"),
        "A" => format_number(n, "upperLetter"),
        "i" => format_number(n, "lowerRoman"),
        "I" => format_number(n, "upperRoman"),
        _ => n.to_string(),
    }
}

/// Bullets in Symbol-encoded fonts, or private-use characters of
/// OpenSymbol, as the Unicode characters they draw.
fn bullet_char(c: char, font: Option<&str>) -> char {
    let code = c as u32;
    if (0xF000..=0xF0FF).contains(&code)
        || font.is_some_and(|f| {
            let f = f.to_ascii_lowercase();
            f == "symbol" || f.starts_with("wingdings")
        })
    {
        return symbol_char(c, font);
    }
    if (0xE000..=0xF8FF).contains(&code) {
        return match code {
            0xE00A => '■',
            0xE011 => '➢',
            0xE012 => '◆',
            0xE01F => '◉',
            _ => '•',
        };
    }
    c
}

/// Right and centre tab stops (a table of contents line, a header) laid out;
/// other tabs keep the default tab distance.
fn lay_out_tabs(stops: &[TabStop], content: String) -> String {
    if !content.contains('\t') || !stops.iter().any(|s| matches!(s.kind.as_str(), "right" | "center" | "char")) {
        return content;
    }
    let segments: Vec<&str> = content.split('\t').collect();
    let mut out = String::new();
    if segments.len() == 2
        && let Some(stop) = stops.iter().rev().find(|s| s.kind == "right")
    {
        let leader = if stop.leader { "border-bottom:1.5px dotted currentColor;margin-bottom:.3em" } else { "" };
        let _ = write!(
            out,
            "<span class=\"ez-tabs\" style=\"display:inline-flex;width:{:.1}px;max-width:100%\"><span>{}</span><span class=\"ez-fill\" style=\"{leader}\"></span><span>{}</span></span>",
            stop.pos.max(0.0),
            segments[0],
            segments[1]
        );
        return out;
    }
    let mut sorted: Vec<&TabStop> = stops.iter().collect();
    sorted.sort_by(|a, b| a.pos.total_cmp(&b.pos));
    out.push_str("<span style=\"display:inline-block;position:relative;width:100%;min-height:1.2em\"><span>");
    out.push_str(segments[0]);
    out.push_str("</span>");
    for (i, seg) in segments.iter().enumerate().skip(1) {
        let (pos, kind) = sorted.get(i - 1).map_or((DEFAULT_TAB * i as f32, "left"), |s| (s.pos, s.kind.as_str()));
        let shift = match kind {
            "center" => "translateX(-50%)",
            "right" | "char" => "translateX(-100%)",
            _ => "none",
        };
        let _ = write!(
            out,
            "<span style=\"position:absolute;left:{:.1}px;top:0;transform:{shift};white-space:pre\">{seg}</span>",
            pos.max(0.0)
        );
    }
    out.push_str("</span>");
    out
}

// ------------------------------------------------------------ entry point

/// A part as a tree that keeps text and elements in order (ODF paragraphs
/// are mixed content), read aside so the text stream carries on.
fn read_tree(parts: &mut Parts, name: &str) -> Result<Option<Node>> {
    let bytes = parts.aside(|archive| {
        let limit = archive.limits().max_part_bytes;
        match archive.find(name) {
            Some(e) if !e.is_dir && e.size <= limit => archive.read_to_vec(name).map(Some),
            Some(e) if !e.is_dir => Err(EngineError::limit(format!("{name} is too large to lay out as pages"))),
            _ => Ok(None),
        }
    })?;
    let Some(bytes) = bytes else { return Ok(None) };
    let mut xml = XmlReader::new(&bytes[..], parts.cancel());
    loop {
        match xml.next()? {
            Xml::Start(tag) => return dom::subtree(&mut xml, tag).map(Some),
            Xml::Eof => return Ok(None),
            _ => {}
        }
    }
}

/// The whole document as one HTML page of print-layout pages.
pub(crate) fn render(parts: &mut Parts) -> Result<Vec<u8>> {
    let cancel = parts.cancel();
    let styles_root = match read_tree(parts, "styles.xml") {
        Err(e) if e.kind == ErrorKind::Cancelled => return Err(e),
        other => other.unwrap_or(None),
    };
    let Some(content) = read_tree(parts, "content.xml")? else {
        return Err(EngineError::malformed("the document has no content"));
    };

    let mut styles = Styles::default();
    let mut layouts: HashMap<String, PageLayout> = HashMap::new();
    let mut masters: Vec<(String, Master)> = Vec::new();
    let mut part_autos = (HashMap::new(), HashMap::new());
    if let Some(root) = &styles_root {
        styles.read_fonts(root);
        if let Some(common) = root.child("styles") {
            styles.read_common(common);
        }
        if let Some(auto) = root.child("automatic-styles") {
            Styles::read_into(auto, &mut part_autos.0, &mut part_autos.1);
            for layout in auto.children_named("page-layout") {
                if let Some(name) = layout.attr("name") {
                    layouts.insert(name.to_owned(), read_page_layout(layout));
                }
            }
        }
        if let Some(master_styles) = root.child("master-styles") {
            for m in master_styles.children_named("master-page") {
                let Some(name) = m.attr("name") else { continue };
                let mut master =
                    Master { layout: m.attr("page-layout-name").unwrap_or("").to_owned(), parts: Vec::new() };
                for child in &m.children {
                    let (kind, ty) = match child.name() {
                        "header" => ("header", "default"),
                        "footer" => ("footer", "default"),
                        "header-left" => ("header", "even"),
                        "footer-left" => ("footer", "even"),
                        "header-first" => ("header", "first"),
                        "footer-first" => ("footer", "first"),
                        _ => continue,
                    };
                    if child.attr("display") == Some("false") {
                        continue;
                    }
                    master.parts.push((kind, ty, child));
                }
                masters.push((name.to_owned(), master));
            }
        }
    }
    styles.read_fonts(&content);
    let (mut auto, mut auto_lists) = (HashMap::new(), HashMap::new());
    if let Some(a) = content.child("automatic-styles") {
        Styles::read_into(a, &mut auto, &mut auto_lists);
    }
    styles.set_auto(auto, auto_lists);

    let body =
        content.path(&["body", "text"]).ok_or_else(|| EngineError::malformed("the document has no text body"))?;

    let mut r = Renderer {
        styles,
        counters: HashMap::new(),
        cancel: cancel.clone(),
        steps: 0,
        depth: 0,
        table_depth: 0,
        notes: String::new(),
        endnotes: String::new(),
        note_count: 0,
        in_note: false,
        pending_label: None,
        pending_prefix: None,
        last_space: true,
    };

    // Each master page in use is a section; a block whose style names one starts it.
    let default_master = masters
        .iter()
        .find(|(n, _)| n == "Standard" || n == "Default")
        .or(masters.first())
        .map(|(n, _)| n.clone())
        .unwrap_or_default();
    let mut sections: Vec<(String, String)> = Vec::new();
    let mut current = default_master;
    let mut flow = String::new();
    for child in &body.children {
        let family = match child.name() {
            "p" | "h" => Some("paragraph"),
            "table" => Some("table"),
            _ => None,
        };
        if let Some(family) = family
            && let Some(master) = r.styles.resolve(family, child.attr("style-name")).master.clone()
            && !master.is_empty()
            && masters.iter().any(|(n, _)| *n == master)
        {
            if flow.contains("class=\"ez-p") || flow.contains("<table") {
                sections.push((std::mem::replace(&mut current, master), std::mem::take(&mut flow)));
            } else {
                current = master;
            }
        }
        r.block(child, &Context::default(), &mut flow)?;
        if flow.len() > MAX_HTML_BYTES {
            return Err(EngineError::limit("the document is too large to lay out as pages"));
        }
    }
    if !r.endnotes.is_empty() {
        let _ = write!(flow, "<div class=\"ez-endnotes\"><div class=\"ez-fnsep\"></div>{}</div>", r.endnotes);
    }
    sections.push((current, flow));

    // Headers and footers use the automatic styles of styles.xml.
    r.styles.set_auto(part_autos.0, part_autos.1);
    let mut part_html: HashMap<String, Vec<(&'static str, &'static str, String)>> = HashMap::new();
    for (name, _) in &sections {
        if part_html.contains_key(name) {
            continue;
        }
        let Some((_, master)) = masters.iter().find(|(n, _)| n == name) else { continue };
        let mut rendered = Vec::new();
        for (kind, ty, node) in &master.parts {
            let mut html = String::new();
            r.blocks(node, &Context::default(), &mut html)?;
            rendered.push((*kind, *ty, html));
        }
        part_html.insert(name.clone(), rendered);
    }

    let layout_of = |name: &str| -> PageLayout {
        masters.iter().find(|(n, _)| n == name).and_then(|(_, m)| layouts.get(&m.layout)).cloned().unwrap_or_default()
    };
    let widest = sections.iter().map(|(m, _)| layout_of(m).width).fold(96.0f32, f32::max);
    let mut out = String::with_capacity(sections.iter().map(|s| s.1.len()).sum::<usize>() + 32 * 1024);
    let _ = write!(
        out,
        "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width={:.0}\"><style>{FONT_FACES}{BASE_CSS}</style></head><body><div id=\"ez-doc\">",
        widest + 32.0
    );
    for (master, flow) in &sections {
        let l = layout_of(master);
        let [mt, mr, mb, ml] = l.margins;
        let _ = write!(
            out,
            "<div class=\"ez-section\" data-odf=\"1\" data-w=\"{:.1}\" data-h=\"{:.1}\" data-mt=\"{mt:.1}\" data-mr=\"{mr:.1}\" data-mb=\"{mb:.1}\" data-ml=\"{ml:.1}\" data-hd=\"{mt:.1}\" data-fd=\"{mb:.1}\" data-cols=\"{}\" data-gap=\"{:.1}\" data-hmin=\"{:.1}\" data-hgap=\"{:.1}\" data-fmin=\"{:.1}\" data-fgap=\"{:.1}\"",
            l.width, l.height, l.cols, l.gap, l.header.0, l.header.1, l.footer.0, l.footer.1
        );
        let rendered = part_html.get(master);
        let has = |ty: &str| rendered.is_some_and(|parts| parts.iter().any(|p| p.1 == ty));
        if has("first") {
            out.push_str(" data-titlepg=\"1\"");
        }
        if has("even") {
            out.push_str(" data-evenodd=\"1\"");
        }
        if let Some(bg) = &l.background {
            let _ = write!(out, " data-bg=\"{bg}\"");
        }
        out.push('>');
        for (kind, ty, html) in rendered.into_iter().flatten() {
            let _ = write!(out, "<template data-kind=\"{kind}\" data-type=\"{ty}\">{html}</template>");
        }
        // Without its own left or first page, those pages show the default.
        for (kind, ty, html) in rendered.into_iter().flatten() {
            if *ty == "default" {
                for other in ["even", "first"] {
                    if has(other) && !rendered.is_some_and(|p| p.iter().any(|p| p.0 == *kind && p.1 == other)) {
                        let _ = write!(out, "<template data-kind=\"{kind}\" data-type=\"{other}\">{html}</template>");
                    }
                }
            }
        }
        out.push_str("<div class=\"ez-flow\">");
        out.push_str(flow);
        out.push_str("</div></div>");
    }
    out.push_str("<div id=\"ez-footnotes\" style=\"display:none\">");
    out.push_str(&r.notes);
    out.push_str("</div></div>");
    let _ = write!(
        out,
        "<script nonce=\"{SCRIPT_NONCE}\">(function(){{const html=document.body.innerHTML;const faces=[...document.fonts].filter(f=>html.indexOf(f.family.replace(/[\"']/g,''))>=0);Promise.all(faces.map(f=>f.load().catch(()=>null))).then(()=>document.fonts.ready).then(function(){{{MEASURE_JS}{PAGINATE_JS}}});}})();</script></body></html>"
    );
    cancel.check()?;
    Ok(out.into_bytes())
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use document_core::Limits;

    use crate::zip::testing::zip;

    const NS: &str = r##"xmlns:office="o" xmlns:style="s" xmlns:text="t" xmlns:table="tb" xmlns:fo="fo" xmlns:draw="d" xmlns:svg="svg" xmlns:xlink="xl""##;

    const STYLES: &str = r##"<office:font-face-decls><style:font-face style:name="Liberation Serif" svg:font-family="'Liberation Serif'"/><style:font-face style:name="Arial1" svg:font-family="Arial"/></office:font-face-decls>
<office:styles>
<style:default-style style:family="paragraph"><style:paragraph-properties style:tab-stop-distance="1.251cm"/><style:text-properties style:font-name="Liberation Serif" fo:font-size="12pt"/></style:default-style>
<style:style style:name="Standard" style:family="paragraph"/>
<style:style style:name="Heading" style:family="paragraph" style:parent-style-name="Standard"><style:paragraph-properties fo:margin-top="0.423cm" fo:margin-bottom="0.212cm" fo:keep-with-next="always"/><style:text-properties style:font-name="Arial1" fo:font-size="14pt"/></style:style>
<style:style style:name="Heading_20_1" style:family="paragraph" style:parent-style-name="Heading"><style:text-properties fo:font-size="130%" fo:font-weight="bold" fo:color="#1F3864"/></style:style>
<style:style style:name="Strong" style:family="text"><style:text-properties fo:font-weight="bold"/></style:style>
<text:outline-style style:name="Outline"><text:outline-level-style text:level="1" style:num-format="1" style:num-suffix="."/></text:outline-style>
<text:list-style style:name="Numbering_20_123"><text:list-level-style-number text:level="1" style:num-suffix="." style:num-format="1"><style:list-level-properties text:list-level-position-and-space-mode="label-alignment"><style:list-level-label-alignment text:label-followed-by="listtab" text:list-tab-stop-position="1.27cm" fo:text-indent="-0.635cm" fo:margin-left="1.27cm"/></style:list-level-properties></text:list-level-style-number><text:list-level-style-number text:level="2" style:num-suffix=")" style:num-format="a" text:display-levels="2"/></text:list-style>
</office:styles>
<office:automatic-styles><style:page-layout style:name="pm1"><style:page-layout-properties fo:page-width="21.001cm" fo:page-height="29.7cm" fo:margin-top="2cm" fo:margin-bottom="2cm" fo:margin-left="2.5cm" fo:margin-right="2cm" fo:background-color="#fffff0"/><style:header-style><style:header-footer-properties fo:min-height="0cm" fo:margin-bottom="0.5cm"/></style:header-style></style:page-layout>
<style:page-layout style:name="pm2"><style:page-layout-properties fo:page-width="29.7cm" fo:page-height="21cm" fo:margin="1cm"/></style:page-layout>
<style:style style:name="MP1" style:family="paragraph"><style:paragraph-properties fo:text-align="end"/></style:style></office:automatic-styles>
<office:master-styles><style:master-page style:name="Standard" style:page-layout-name="pm1"><style:header><text:p text:style-name="MP1">Page <text:page-number text:select-page="current">1</text:page-number> of <text:page-count>2</text:page-count></text:p></style:header><style:header-first><text:p>Cover</text:p></style:header-first></style:master-page><style:master-page style:name="Landscape" style:page-layout-name="pm2"/></office:master-styles>"##;

    fn odt(automatic: &str, body: &str) -> Vec<u8> {
        let content = format!(
            r##"<?xml version="1.0"?><office:document-content {NS}><office:automatic-styles>{automatic}</office:automatic-styles><office:body><office:text>{body}</office:text></office:body></office:document-content>"##
        );
        let styles = format!(r##"<office:document-styles {NS}>{STYLES}</office:document-styles>"##);
        zip(&[
            ("mimetype", b"application/vnd.oasis.opendocument.text"),
            ("content.xml", content.as_bytes()),
            ("styles.xml", styles.as_bytes()),
            ("Pictures/a.png", b"\x89PNG"),
        ])
    }

    fn page(automatic: &str, body: &str) -> String {
        let mut session = crate::engine(Limits::default()).open(Cursor::new(odt(automatic, body))).unwrap();
        String::from_utf8(session.resource(crate::docx_html::PAGES_RESOURCE).unwrap()).unwrap()
    }

    /// The label texts of list items, in order.
    fn labels(html: &str) -> Vec<&str> {
        html.match_indices("class=\"ez-mk\"")
            .map(|(i, _)| {
                let rest = &html[i..];
                let start = rest.find('>').unwrap() + 1;
                &rest[start..start + rest[start..].find('<').unwrap()]
            })
            .collect()
    }

    #[test]
    fn styles_inherit_from_parents_and_defaults() {
        let auto = r##"<style:style style:name="P1" style:family="paragraph" style:parent-style-name="Standard"><style:paragraph-properties fo:text-align="center" fo:margin-left="1in" fo:text-indent="-0.5in" fo:line-height="150%" fo:margin-bottom="0.25cm"/></style:style>
<style:style style:name="T1" style:family="text"><style:text-properties fo:font-style="italic" style:text-underline-style="solid" style:text-underline-type="double" style:text-underline-color="#FF0000" fo:background-color="#ffff00"/></style:style>
<style:style style:name="T2" style:family="text"><style:text-properties style:text-line-through-style="solid" style:text-position="super 58%" fo:color="#00AA00" fo:font-size="50%"/></style:style>"##;
        let html = page(
            auto,
            r##"<text:h text:style-name="Heading_20_1" text:outline-level="1">Title &lt;script&gt;</text:h>
<text:p text:style-name="P1">Plain <text:span text:style-name="T1">marked</text:span> <text:span text:style-name="T2">up</text:span> <text:span text:style-name="Strong">strong</text:span></text:p>"##,
        );
        assert!(
            html.contains("font-family:&quot;Arial&quot;,EzArimo,sans-serif"),
            "the heading's font through its parent"
        );
        assert!(html.contains("font-size:18.2pt;"), "130% of the parent's 14pt");
        assert!(html.contains("font-weight:bold;") && html.contains("color:#1f3864;"));
        assert!(html.contains("padding-top:16.0px;padding-bottom:8.0px;"), "the parent's spacing");
        assert!(html.contains("ez-keep"), "keep with next from the parent");
        assert_eq!(labels(&html), ["1."], "the outline numbers the heading");
        assert!(html.contains("Title &lt;script&gt;"), "text is escaped");
        assert!(html.contains("text-align:center;") && html.contains("margin-left:96.0px;"));
        assert!(html.contains("text-indent:-48.0px;"));
        assert!(html.contains("line-height:1.725;"), "150% of Liberation Serif's single line");
        assert!(html.contains("font-family:&quot;Liberation Serif&quot;,EzTinos,serif"), "the default style's font");
        assert!(html.contains(
            "font-style:italic;text-decoration:underline double;text-decoration-color:#ff0000;background:#ffff00;"
        ));
        assert!(
            html.contains("font-size:3.5pt;") && html.contains("vertical-align:super;"),
            "half size, raised and 58%"
        );
        assert!(html.contains("line-through") && html.contains("color:#00aa00;"));
        assert_eq!(html.matches("<script").count(), 1, "only the engine's script");
    }

    #[test]
    fn lists_number_by_their_style() {
        let item = |text: &str| format!("<text:list-item><text:p>{text}</text:p></text:list-item>");
        let body = format!(
            r##"<text:list text:style-name="Numbering_20_123">{}<text:list-item><text:p>b</text:p><text:list>{}{}</text:list></text:list-item></text:list>
<text:p>between</text:p>
<text:list text:style-name="Numbering_20_123" text:continue-numbering="true">{}</text:list>
<text:list text:style-name="Numbering_20_123"><text:list-item text:start-value="7"><text:p>g</text:p></text:list-item></text:list>
<text:list text:style-name="L1">{}<text:list-header><text:p>no label</text:p></text:list-header></text:list>"##,
            item("a"),
            item("b1"),
            item("b2"),
            item("c"),
            item("bullet"),
        );
        let auto = r##"<text:list-style style:name="L1"><text:list-level-style-bullet text:level="1" text:bullet-char="&#xE001;"><style:list-level-properties text:space-before="0.5cm" text:min-label-width="0.5cm"/><style:text-properties style:font-name="OpenSymbol"/></text:list-level-style-bullet></text:list-style>"##;
        let html = page(auto, &body);
        assert_eq!(labels(&html), ["1.", "2.", "2.a)", "2.b)", "3.", "7.", "•"]);
        assert!(html.contains("margin-left:48.0px;text-indent:-24.0px;"), "label alignment");
        assert!(html.contains("min-width:24.0px;"), "the label reaches the list tab");
        assert!(html.contains("margin-left:37.8px;text-indent:-18.9px;"), "the older label width and position");
        assert!(html.contains(">no label<"));
    }

    #[test]
    fn tables_keep_widths_spans_and_cell_styles() {
        let auto = r##"<style:style style:name="Tbl" style:family="table"><style:table-properties style:width="16cm" table:align="margins"/></style:style>
<style:style style:name="Tbl.A" style:family="table-column"><style:table-column-properties style:column-width="4cm" style:rel-column-width="1000*"/></style:style>
<style:style style:name="Tbl.B" style:family="table-column"><style:table-column-properties style:column-width="12cm" style:rel-column-width="3000*"/></style:style>
<style:style style:name="Tbl.A1" style:family="table-cell"><style:table-cell-properties fo:background-color="#4472C4" fo:padding="0.1cm" fo:border="0.5pt solid #000000" style:vertical-align="middle"/></style:style>"##;
        let body = r##"<table:table table:style-name="Tbl"><table:table-column table:style-name="Tbl.A"/><table:table-column table:style-name="Tbl.B" table:number-columns-repeated="2"/>
<table:table-header-rows><table:table-row><table:table-cell table:style-name="Tbl.A1" table:number-columns-spanned="2"><text:p>Head</text:p></table:table-cell><table:covered-table-cell/><table:table-cell><text:p>H3</text:p></table:table-cell></table:table-row></table:table-header-rows>
<table:table-row><table:table-cell table:number-rows-spanned="2"><text:p>Tall</text:p></table:table-cell><table:table-cell><table:table><table:table-row><table:table-cell><text:p>nested</text:p></table:table-cell></table:table-row></table:table></table:table-cell><table:table-cell/></table:table-row>
<table:table-row><table:covered-table-cell/><table:table-cell><text:p>z</text:p></table:table-cell><table:table-cell><text:p>w</text:p></table:table-cell></table:table-row></table:table>"##;
        let html = page(auto, body);
        assert!(html.contains("<col style=\"width:14.29%\"><col style=\"width:42.86%\"><col style=\"width:42.86%\">"));
        assert!(html.contains("width:calc(100% - 0.0px);"), "aligned to the margins");
        assert!(html.contains("colspan=\"2\"") && html.contains("rowspan=\"2\""));
        assert!(html.contains("<tr class=\"ez-hdr\">"));
        assert!(html.contains(
            "background:#4472c4;border-top:0.75px solid #000000;padding-top:3.8px;border-left:0.75px solid #000000;"
        ));
        assert!(html.contains("vertical-align:middle;"));
        assert!(html.contains(">nested<"));
        assert_eq!(html.matches("<td").count(), 8, "covered cells are skipped");
    }

    #[test]
    fn pictures_frames_links_and_notes() {
        let auto = r##"<style:style style:name="fr1" style:family="graphic"><style:graphic-properties style:wrap="right" style:horizontal-pos="left" fo:margin-right="0.2cm"/></style:style>
<style:style style:name="fr2" style:family="graphic"><style:graphic-properties draw:fill="solid" draw:fill-color="#EEEEEE" draw:stroke="solid" svg:stroke-color="#333333"/></style:style>"##;
        let body = r##"<text:p>A<draw:frame draw:style-name="fr1" text:anchor-type="char" svg:width="2.54cm" svg:height="1.27cm"><draw:image xlink:href="Pictures/a.png"/><svg:desc>Logo</svg:desc></draw:frame><draw:frame text:anchor-type="as-char" svg:width="1cm" svg:height="1cm"><draw:image xlink:href="http://evil.example/x.png"/></draw:frame></text:p>
<text:p><draw:frame draw:style-name="fr2" text:anchor-type="paragraph" svg:width="5cm"><draw:text-box fo:min-height="2cm"><text:p>In a box</text:p></draw:text-box></draw:frame></text:p>
<text:p><text:a xlink:href="https://example.com/?a=1&amp;b=2">web</text:a> <text:a xlink:href="javascript:alert(1)">bad</text:a><text:note text:note-class="footnote"><text:note-citation>1</text:note-citation><text:note-body><text:p>A note</text:p></text:note-body></text:note></text:p>"##;
        let html = page(auto, body);
        assert!(
            html.contains(r##"src="res/Pictures/a.png" alt="Logo" style="width:96.0px;height:48.0px;float:left;"##)
        );
        assert!(!html.contains("evil.example") && html.contains(">Picture</span>"), "outside pictures don't load");
        assert!(
            html.contains("class=\"ez-box\"")
                && html.contains("min-height:75.6px;")
                && html.contains("background:#eeeeee;")
        );
        assert!(html.contains(">In a box<"));
        assert!(html.contains(r##"<a href="https://example.com/?a=1&amp;b=2">"##));
        assert!(!html.contains("javascript:") && html.contains("bad"));
        assert!(html.contains("class=\"ez-fnref\" data-fn=\"1\">1</sup>") && html.contains("id=\"ez-fn-1\""));
        assert!(html.contains("<sup>1</sup> A note"));
    }

    #[test]
    fn pages_headers_breaks_and_master_pages() {
        let auto = r##"<style:style style:name="P1" style:family="paragraph" style:parent-style-name="Standard"><style:paragraph-properties fo:break-before="page"/></style:style>
<style:style style:name="P2" style:family="paragraph" style:parent-style-name="Standard" style:master-page-name="Landscape"/>"##;
        let html = page(
            auto,
            r##"<text:p>One</text:p><text:p text:style-name="P1">Two</text:p><text:p text:style-name="P2">Wide</text:p>"##,
        );
        assert!(html.contains(
            "data-w=\"793.7\" data-h=\"1122.5\" data-mt=\"75.6\" data-mr=\"75.6\" data-mb=\"75.6\" data-ml=\"94.5\""
        ));
        assert!(html.contains("data-hgap=\"18.9\"") && html.contains("data-bg=\"#fffff0\""));
        assert!(html.contains("<template data-kind=\"header\" data-type=\"default\">"));
        assert!(html.contains("<span class=\"ez-fld-page\">1</span>") && html.contains("ez-fld-numpages"));
        assert!(html.contains("text-align:right;"), "the header's own automatic style");
        assert!(html.contains("data-titlepg=\"1\"") && html.contains(">Cover<"));
        assert!(html.contains("<div class=\"ez-pb\"></div>"));
        assert_eq!(html.matches("class=\"ez-section\"").count(), 2, "the landscape master starts a section");
        assert!(html.contains("data-w=\"1122.5\" data-h=\"793.7\""));
        assert!(html.contains("<meta name=\"viewport\" content=\"width=1155\">"));
    }

    #[test]
    fn white_space_follows_writer() {
        let html =
            page("", "<text:p>\n  a   b<text:s text:c=\"3\"/>c<text:tab/>d<text:line-break/>e</text:p><text:p/>");
        assert!(html.contains(">a b   c\td<br>e</div>"));
        assert!(html.contains("\"><br></div>"), "an empty paragraph keeps its line");
    }

    #[test]
    fn the_text_stream_carries_on_after_the_pages() {
        let body: String = (0..4000).map(|i| format!("<text:p>Paragraph number {i} with some text</text:p>")).collect();
        let limits = Limits { max_batch_bytes: 1024, ..Limits::default() };
        let mut session = crate::engine(limits).open(Cursor::new(odt("", &body))).unwrap();
        let first = session.next_batch().unwrap().unwrap();
        assert!(!first.is_last);
        let html = String::from_utf8(session.resource(crate::docx_html::PAGES_RESOURCE).unwrap()).unwrap();
        assert!(html.contains("Paragraph number 3999"));
        let mut count = first.blocks.len();
        while let Some(batch) = session.next_batch().unwrap() {
            count += batch.blocks.len();
        }
        assert_eq!(count, 4000);
    }
}
