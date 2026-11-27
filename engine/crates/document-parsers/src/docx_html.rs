//! Word (DOCX) as print-layout pages: the document rendered to one HTML page
//! that a web view shows like Word's Print Layout.
//!
//! The engine resolves what Word resolves: document defaults, paragraph,
//! character and table styles along their `basedOn` chains, theme fonts and
//! colours, list numbering, and direct formatting. It writes each section's
//! content in reading order with its page size, margins, columns, headers
//! and footers, and a small script in the page lays that content out into
//! pages: it breaks where the document breaks, splits paragraphs between
//! lines and tables between rows (repeating heading rows), puts footnotes at
//! the foot of the page that refers to them, and fills in page numbers.
//!
//! Covered: fonts (mapped to metric-compatible ones the app ships, so lines
//! break where Word breaks them), sizes, colours, highlight, bold, italic,
//! underline styles, strikethrough, caps, small caps, superscript and
//! subscript, letter spacing, hidden text; alignment, indents, first-line and
//! hanging indents, spacing before and after, line spacing, contextual
//! spacing, paragraph borders and shading, tab stops with leaders; numbered
//! and bulleted lists with their own formats; tables with grid widths, merged
//! cells across and down, borders, shading, cell margins, row heights,
//! alignment and table-style conditional formatting (heading row, banded
//! rows and columns, first and last column); pictures inline and floating,
//! text boxes, shapes, VML pictures; hyperlinks and bookmarks; fields' saved
//! results with live page numbers; footnotes and endnotes; content controls;
//! tracked changes shown as accepted; equations as linear text; document
//! background colour; multiple sections with their own page setup.
//!
//! Not covered: charts and SmartArt (a labelled box), WordArt effects, page
//! borders, line numbering, drop caps, text wrapping around tight outlines,
//! positioning of floating objects relative to the page.
//!
//! Text from the document is always escaped, so the only script in the page
//! is the engine's own, which the viewer allows by its nonce.

use std::collections::{HashMap, HashSet};
use std::fmt::Write as _;
use std::rc::Rc;

use document_core::{CancelToken, EngineError, Result};

use crate::dom::{self, Node};
use crate::ooxml::{Relationship, is_safe_link, relationships, target_of};
use crate::pptx::theme_colors;
use crate::stream::{Parts, is_raster_picture};

/// The resource name the viewer asks for.
pub(crate) const PAGES_RESOURCE: &str = "ezfiles:pages.html";
/// What the page's script must carry to run; the viewer's policy names it.
pub(crate) const SCRIPT_NONCE: &str = "ezpages";

const MAX_TABLE_DEPTH: u32 = 12;
const MAX_STYLE_CHAIN: usize = 16;
const MAX_HTML_BYTES: usize = 64 * 1024 * 1024;

/// Twips (1/20 pt) to CSS pixels at 96 per inch.
fn twips(v: f32) -> f32 {
    v / 15.0
}

fn emu(v: f32) -> f32 {
    v / 9525.0
}

fn num(node: &Node, attr: &str) -> Option<f32> {
    node.attr(attr).and_then(|v| v.trim().parse::<f32>().ok()).filter(|v| v.is_finite())
}

fn on(node: &Node) -> bool {
    !matches!(node.attr("val"), Some("0" | "false" | "off" | "none"))
}

pub(crate) fn esc(out: &mut String, text: &str) {
    for c in text.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&#39;"),
            // Controls other than tab and newline have no place in a page; U+E000 marks page breaks.
            c if (c.is_control() && c != '\t' && c != '\n') || c == '\u{E000}' => {}
            c => out.push(c),
        }
    }
}

pub(crate) fn esc_attr(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    esc(&mut out, text);
    out
}

/// A resource URL for a picture inside the package.
pub(crate) fn resource_url(path: &str) -> String {
    let mut out = String::from("res/");
    for b in path.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'/' | b'.' | b'-' | b'_') {
            out.push(b as char);
        } else {
            let _ = write!(out, "%{b:02X}");
        }
    }
    out
}

// ------------------------------------------------------------ properties

#[derive(Clone, Default, Debug)]
struct RunProps {
    bold: Option<bool>,
    italic: Option<bool>,
    underline: Option<String>,
    underline_color: Option<String>,
    strike: Option<bool>,
    dstrike: Option<bool>,
    color: Option<String>,
    size: Option<f32>,
    font: Option<String>,
    highlight: Option<String>,
    shading: Option<String>,
    vert: Option<String>,
    caps: Option<bool>,
    small_caps: Option<bool>,
    hidden: Option<bool>,
    spacing: Option<f32>,
    style: Option<String>,
}

impl RunProps {
    fn over(&self, base: &RunProps) -> RunProps {
        RunProps {
            bold: self.bold.or(base.bold),
            italic: self.italic.or(base.italic),
            underline: self.underline.clone().or_else(|| base.underline.clone()),
            underline_color: self.underline_color.clone().or_else(|| base.underline_color.clone()),
            strike: self.strike.or(base.strike),
            dstrike: self.dstrike.or(base.dstrike),
            color: self.color.clone().or_else(|| base.color.clone()),
            size: self.size.or(base.size),
            font: self.font.clone().or_else(|| base.font.clone()),
            highlight: self.highlight.clone().or_else(|| base.highlight.clone()),
            shading: self.shading.clone().or_else(|| base.shading.clone()),
            vert: self.vert.clone().or_else(|| base.vert.clone()),
            caps: self.caps.or(base.caps),
            small_caps: self.small_caps.or(base.small_caps),
            hidden: self.hidden.or(base.hidden),
            spacing: self.spacing.or(base.spacing),
            style: None,
        }
    }
}

#[derive(Clone, Debug)]
struct Border {
    style: String,
    /// Pixels.
    width: f32,
    color: String,
    /// Pixels of space between the border and the text.
    space: f32,
}

#[derive(Clone, Debug)]
struct TabStop {
    kind: String,
    /// Pixels from the paragraph's left edge (the margin).
    pos: f32,
    leader: Option<String>,
}

#[derive(Clone, Default, Debug)]
struct ParaProps {
    style: Option<String>,
    align: Option<String>,
    left: Option<f32>,
    right: Option<f32>,
    /// Positive: first-line indent; negative: hanging.
    first: Option<f32>,
    before: Option<f32>,
    after: Option<f32>,
    before_auto: Option<bool>,
    after_auto: Option<bool>,
    line: Option<(f32, String)>,
    shading: Option<String>,
    borders: [Option<Option<Border>>; 5],
    num: Option<(String, u8)>,
    page_break_before: Option<bool>,
    contextual: Option<bool>,
    tabs: Option<Vec<TabStop>>,
    keep_next: Option<bool>,
    mark: RunProps,
}

impl ParaProps {
    fn over(&self, base: &ParaProps) -> ParaProps {
        let mut borders = base.borders.clone();
        for (i, b) in self.borders.iter().enumerate() {
            if b.is_some() {
                borders[i] = b.clone();
            }
        }
        let tabs = match (&self.tabs, &base.tabs) {
            (Some(own), Some(base)) => {
                // Own stops add to inherited ones; a "clear" removes one.
                let mut all: Vec<TabStop> = base
                    .iter()
                    .filter(|b| !own.iter().any(|o| o.kind == "clear" && (o.pos - b.pos).abs() < 1.0))
                    .cloned()
                    .collect();
                all.extend(own.iter().filter(|o| o.kind != "clear").cloned());
                Some(all)
            }
            (Some(own), None) => Some(own.iter().filter(|o| o.kind != "clear").cloned().collect()),
            (None, b) => b.clone(),
        };
        ParaProps {
            style: None,
            align: self.align.clone().or_else(|| base.align.clone()),
            left: self.left.or(base.left),
            right: self.right.or(base.right),
            first: self.first.or(base.first),
            before: self.before.or(base.before),
            after: self.after.or(base.after),
            before_auto: self.before_auto.or(base.before_auto),
            after_auto: self.after_auto.or(base.after_auto),
            line: self.line.clone().or_else(|| base.line.clone()),
            shading: self.shading.clone().or_else(|| base.shading.clone()),
            borders,
            num: self.num.clone().or_else(|| base.num.clone()),
            page_break_before: self.page_break_before.or(base.page_break_before),
            contextual: self.contextual.or(base.contextual),
            tabs,
            keep_next: self.keep_next.or(base.keep_next),
            mark: self.mark.over(&base.mark),
        }
    }
}

/// Cell-level settings a table or its style gives.
#[derive(Clone, Default, Debug)]
struct CellProps {
    shading: Option<String>,
    /// top, left, bottom, right, insideH, insideV.
    borders: [Option<Option<Border>>; 6],
    valign: Option<String>,
    margins: [Option<f32>; 4],
}

impl CellProps {
    fn over(&self, base: &CellProps) -> CellProps {
        let mut borders = base.borders.clone();
        for (i, b) in self.borders.iter().enumerate() {
            if b.is_some() {
                borders[i] = b.clone();
            }
        }
        let mut margins = base.margins;
        for (i, m) in self.margins.iter().enumerate() {
            if m.is_some() {
                margins[i] = *m;
            }
        }
        CellProps {
            shading: self.shading.clone().or_else(|| base.shading.clone()),
            borders,
            valign: self.valign.clone().or_else(|| base.valign.clone()),
            margins,
        }
    }
}

#[derive(Clone, Default, Debug)]
struct Conditional {
    para: ParaProps,
    run: RunProps,
    cell: CellProps,
}

impl Conditional {
    fn over(&self, base: &Conditional) -> Conditional {
        Conditional {
            para: self.para.over(&base.para),
            run: self.run.over(&base.run),
            cell: self.cell.over(&base.cell),
        }
    }
}

#[derive(Default, Debug)]
struct Style {
    based_on: Option<String>,
    para: ParaProps,
    run: RunProps,
    table: Conditional,
    conditional: HashMap<String, Conditional>,
    band_rows: Option<u32>,
    band_cols: Option<u32>,
}

// ------------------------------------------------------------ theme

#[derive(Default)]
struct Theme {
    colors: HashMap<String, u32>,
    major: Option<String>,
    minor: Option<String>,
}

impl Theme {
    fn read(node: &Node) -> Theme {
        let font = |kind: &str| {
            node.find(kind).and_then(|f| f.child("latin")).and_then(|l| l.attr("typeface")).map(str::to_owned)
        };
        Theme { colors: theme_colors(node), major: font("majorFont"), minor: font("minorFont") }
    }

    fn color(&self, name: &str) -> Option<u32> {
        let slot = match name {
            "text1" | "dark1" => "dk1",
            "background1" | "light1" => "lt1",
            "text2" | "dark2" => "dk2",
            "background2" | "light2" => "lt2",
            "hyperlink" => "hlink",
            "followedHyperlink" => "folHlink",
            other => other,
        };
        self.colors.get(slot).copied()
    }
}

fn hex6(value: &str) -> Option<String> {
    let v = value.trim();
    (v.len() == 6 && v.bytes().all(|b| b.is_ascii_hexdigit())).then(|| format!("#{}", v.to_ascii_lowercase()))
}

/// A colour attribute set (`val`, `themeColor`, `themeShade`, `themeTint`) as CSS.
fn color_of(node: &Node, attr: &str, theme: &Theme) -> Option<String> {
    // Word saves the theme colour it resolved; that is exactly what it shows.
    if let Some(saved) = node.attr(attr).and_then(hex6) {
        return Some(saved);
    }
    let theme_attr = if attr == "val" { "themeColor" } else { "themeFill" };
    if let Some(argb) = node.attr(theme_attr).and_then(|t| theme.color(t)) {
        let mut rgb = [(argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF].map(|c| c as f32);
        let (shade_attr, tint_attr) =
            if attr == "val" { ("themeShade", "themeTint") } else { ("themeFillShade", "themeFillTint") };
        if let Some(shade) = node.attr(shade_attr).and_then(|s| u8::from_str_radix(s, 16).ok()) {
            rgb = rgb.map(|c| c * f32::from(shade) / 255.0);
        }
        if let Some(tint) = node.attr(tint_attr).and_then(|s| u8::from_str_radix(s, 16).ok()) {
            rgb = rgb.map(|c| 255.0 - (255.0 - c) * f32::from(tint) / 255.0);
        }
        let [r, g, b] = rgb.map(|c| c.round().clamp(0.0, 255.0) as u32);
        return Some(format!("#{r:02x}{g:02x}{b:02x}"));
    }
    match node.attr(attr) {
        Some("auto") | None => None,
        Some(v) => hex6(v),
    }
}

// ------------------------------------------------------------ parsing properties

fn border_of(node: &Node, theme: &Theme) -> Option<Border> {
    let style = node.attr("val").unwrap_or("single");
    if matches!(style, "nil" | "none") {
        return None;
    }
    let width = num(node, "sz").map_or(1.0, |eighths| (eighths / 8.0 * 4.0 / 3.0).max(1.0));
    let css = match style {
        "double" | "triple" | "thinThickSmallGap" | "thickThinSmallGap" | "thinThickMediumGap"
        | "thickThinMediumGap" => "double",
        "dotted" => "dotted",
        "dashed" | "dashSmallGap" | "dotDash" | "dotDotDash" => "dashed",
        _ => "solid",
    };
    let width = if css == "double" { width.max(3.0) } else { width };
    Some(Border {
        style: css.to_owned(),
        width,
        color: color_of(node, "color", theme).unwrap_or_else(|| "#000000".to_owned()),
        space: num(node, "space").map_or(0.0, |pt| pt * 4.0 / 3.0),
    })
}

fn fill_of(node: &Node, theme: &Theme) -> Option<String> {
    // A pattern over a fill shows mostly the fill; "clear" is the fill alone.
    let fill = color_of(node, "fill", theme);
    match node.attr("val") {
        Some("solid") => color_of(node, "color", theme).or(fill),
        _ => fill,
    }
}

fn parse_rpr(node: &Node, theme: &Theme) -> RunProps {
    let mut p = RunProps::default();
    for c in &node.children {
        match c.name() {
            "b" => p.bold = Some(on(c)),
            "i" => p.italic = Some(on(c)),
            "u" => {
                p.underline = Some(c.attr("val").unwrap_or("single").to_owned());
                p.underline_color = color_of(c, "color", theme);
            }
            "strike" => p.strike = Some(on(c)),
            "dstrike" => p.dstrike = Some(on(c)),
            "color" => {
                p.color =
                    color_of(c, "val", theme).or_else(|| (c.attr("val") == Some("auto")).then(|| "auto".to_owned()))
            }
            "sz" => p.size = num(c, "val").map(|v| v / 2.0).filter(|v| *v > 0.0 && *v < 1700.0),
            "rFonts" => {
                let theme_font = c
                    .attr("asciiTheme")
                    .or(c.attr("hAnsiTheme"))
                    .and_then(|t| if t.starts_with("major") { theme.major.clone() } else { theme.minor.clone() });
                p.font = theme_font
                    .or_else(|| c.attr("ascii").or(c.attr("hAnsi")).or(c.attr("eastAsia")).map(str::to_owned));
            }
            "highlight" => p.highlight = c.attr("val").and_then(highlight).map(str::to_owned),
            "shd" => p.shading = fill_of(c, theme),
            "vertAlign" => p.vert = c.attr("val").map(str::to_owned),
            "caps" => p.caps = Some(on(c)),
            "smallCaps" => p.small_caps = Some(on(c)),
            "vanish" | "webHidden" => p.hidden = Some(on(c)),
            "spacing" => p.spacing = num(c, "val").map(twips),
            "rStyle" => p.style = c.attr("val").map(str::to_owned),
            _ => {}
        }
    }
    p
}

fn highlight(name: &str) -> Option<&'static str> {
    Some(match name {
        "yellow" => "#ffff00",
        "green" => "#00ff00",
        "cyan" => "#00ffff",
        "magenta" => "#ff00ff",
        "blue" => "#0000ff",
        "red" => "#ff0000",
        "darkBlue" => "#000080",
        "darkCyan" => "#008080",
        "darkGreen" => "#008000",
        "darkMagenta" => "#800080",
        "darkRed" => "#800000",
        "darkYellow" => "#808000",
        "darkGray" => "#808080",
        "lightGray" => "#c0c0c0",
        "black" => "#000000",
        "white" => "#ffffff",
        _ => return None,
    })
}

fn parse_ppr(node: &Node, theme: &Theme) -> ParaProps {
    let mut p = ParaProps::default();
    for c in &node.children {
        match c.name() {
            "pStyle" => p.style = c.attr("val").map(str::to_owned),
            "jc" => p.align = c.attr("val").map(str::to_owned),
            "ind" => {
                p.left = num(c, "left").or_else(|| num(c, "start")).map(twips).or(p.left);
                p.right = num(c, "right").or_else(|| num(c, "end")).map(twips).or(p.right);
                if let Some(h) = num(c, "hanging") {
                    p.first = Some(-twips(h));
                } else if let Some(f) = num(c, "firstLine") {
                    p.first = Some(twips(f));
                }
            }
            "spacing" => {
                p.before = num(c, "before").map(twips).or(p.before);
                p.after = num(c, "after").map(twips).or(p.after);
                if let Some(a) = c.attr("beforeAutospacing") {
                    p.before_auto = Some(a == "1" || a == "true" || a == "on");
                }
                if let Some(a) = c.attr("afterAutospacing") {
                    p.after_auto = Some(a == "1" || a == "true" || a == "on");
                }
                if let Some(line) = num(c, "line") {
                    p.line = Some((line, c.attr("lineRule").unwrap_or("auto").to_owned()));
                }
            }
            "shd" => p.shading = fill_of(c, theme),
            "pBdr" => {
                for (i, side) in ["top", "left", "bottom", "right", "between"].iter().enumerate() {
                    if let Some(b) = c.child(side) {
                        p.borders[i] = Some(border_of(b, theme));
                    }
                }
            }
            "numPr" => {
                let level = c.child("ilvl").and_then(|l| num(l, "val")).unwrap_or(0.0) as u8;
                let id = c.child("numId").and_then(|n| n.attr("val")).unwrap_or("").to_owned();
                p.num = Some((id, level.min(8)));
            }
            "pageBreakBefore" => p.page_break_before = Some(on(c)),
            "contextualSpacing" => p.contextual = Some(on(c)),
            "keepNext" => p.keep_next = Some(on(c)),
            "tabs" => {
                p.tabs = Some(
                    c.children_named("tab")
                        .filter_map(|t| {
                            Some(TabStop {
                                kind: t.attr("val").unwrap_or("left").to_owned(),
                                pos: twips(num(t, "pos")?),
                                leader: t.attr("leader").filter(|l| *l != "none").map(str::to_owned),
                            })
                        })
                        .collect(),
                )
            }
            "rPr" => p.mark = parse_rpr(c, theme),
            _ => {}
        }
    }
    p
}

fn parse_cell_props(node: &Node, theme: &Theme) -> CellProps {
    let mut p = CellProps::default();
    for c in &node.children {
        match c.name() {
            "shd" => p.shading = fill_of(c, theme),
            "tcBorders" | "tblBorders" => {
                for (i, side) in ["top", "left", "bottom", "right", "insideH", "insideV"].iter().enumerate() {
                    let found = c.child(side).or_else(|| match *side {
                        "left" => c.child("start"),
                        "right" => c.child("end"),
                        _ => None,
                    });
                    if let Some(b) = found {
                        p.borders[i] = Some(border_of(b, theme));
                    }
                }
            }
            "vAlign" => p.valign = c.attr("val").map(str::to_owned),
            "tcMar" | "tblCellMar" => {
                for (i, side) in ["top", "left", "bottom", "right"].iter().enumerate() {
                    let found = c.child(side).or_else(|| match *side {
                        "left" => c.child("start"),
                        "right" => c.child("end"),
                        _ => None,
                    });
                    if let Some(m) =
                        found.filter(|m| m.attr("type").is_none_or(|t| t == "dxa")).and_then(|m| num(m, "w"))
                    {
                        p.margins[i] = Some(twips(m));
                    }
                }
            }
            _ => {}
        }
    }
    p
}

// ------------------------------------------------------------ styles

#[derive(Default)]
struct Styles {
    styles: HashMap<String, Style>,
    default_para: Option<String>,
    default_char: Option<String>,
    default_table: Option<String>,
    doc_run: RunProps,
    doc_para: ParaProps,
}

impl Styles {
    fn read(root: &Node, theme: &Theme) -> Styles {
        let mut s = Styles::default();
        if let Some(defaults) = root.child("docDefaults") {
            if let Some(r) = defaults.path(&["rPrDefault", "rPr"]) {
                s.doc_run = parse_rpr(r, theme);
            }
            if let Some(p) = defaults.path(&["pPrDefault", "pPr"]) {
                s.doc_para = parse_ppr(p, theme);
            }
        }
        for style in root.children_named("style") {
            let Some(id) = style.attr("styleId") else { continue };
            let kind = style.attr("type").unwrap_or("paragraph");
            if style.attr("default").is_some_and(|d| d == "1" || d == "true") {
                match kind {
                    "paragraph" => s.default_para = Some(id.to_owned()),
                    "character" => s.default_char = Some(id.to_owned()),
                    "table" => s.default_table = Some(id.to_owned()),
                    _ => {}
                }
            }
            let mut st = Style {
                based_on: style.child("basedOn").and_then(|b| b.attr("val")).map(str::to_owned),
                ..Style::default()
            };
            if let Some(p) = style.child("pPr") {
                st.para = parse_ppr(p, theme);
            }
            if let Some(r) = style.child("rPr") {
                st.run = parse_rpr(r, theme);
            }
            if let Some(t) = style.child("tblPr") {
                st.table.cell = parse_cell_props(t, theme);
                st.band_rows = t.child("tblStyleRowBandSize").and_then(|b| num(b, "val")).map(|v| v.max(1.0) as u32);
                st.band_cols = t.child("tblStyleColBandSize").and_then(|b| num(b, "val")).map(|v| v.max(1.0) as u32);
            }
            if let Some(t) = style.child("tcPr") {
                st.table.cell = parse_cell_props(t, theme).over(&st.table.cell);
            }
            st.table.para = st.para.clone();
            st.table.run = st.run.clone();
            for cond in style.children_named("tblStylePr") {
                let Some(kind) = cond.attr("type") else { continue };
                let mut c = Conditional::default();
                if let Some(p) = cond.child("pPr") {
                    c.para = parse_ppr(p, theme);
                }
                if let Some(r) = cond.child("rPr") {
                    c.run = parse_rpr(r, theme);
                }
                if let Some(t) = cond.child("tblPr") {
                    c.cell = parse_cell_props(t, theme);
                }
                if let Some(t) = cond.child("tcPr") {
                    c.cell = parse_cell_props(t, theme).over(&c.cell);
                }
                st.conditional.insert(kind.to_owned(), c);
            }
            s.styles.insert(id.to_owned(), st);
        }
        s
    }

    /// The style and its ancestors, nearest first.
    fn chain(&self, id: Option<&str>) -> Vec<&Style> {
        let mut out = Vec::new();
        let mut id = id.map(str::to_owned);
        let mut seen = HashSet::new();
        while let Some(current) = id {
            if out.len() >= MAX_STYLE_CHAIN || !seen.insert(current.clone()) {
                break;
            }
            let Some(style) = self.styles.get(&current) else { break };
            out.push(style);
            id = style.based_on.clone();
        }
        out
    }

    fn para_style(&self, id: Option<&str>) -> (ParaProps, RunProps) {
        let id = id.or(self.default_para.as_deref());
        let mut para = ParaProps::default();
        let mut run = RunProps::default();
        for style in self.chain(id).into_iter().rev() {
            para = style.para.over(&para);
            run = style.run.over(&run);
        }
        (para, run)
    }

    fn char_style(&self, id: Option<&str>) -> RunProps {
        let mut run = RunProps::default();
        for style in self.chain(id).into_iter().rev() {
            run = style.run.over(&run);
        }
        run
    }

    /// A table style flattened: whole-table settings and each conditional part.
    fn table_style(&self, id: Option<&str>) -> (Conditional, HashMap<String, Conditional>, u32, u32) {
        let id = id.or(self.default_table.as_deref());
        let mut whole = Conditional::default();
        let mut parts: HashMap<String, Conditional> = HashMap::new();
        let (mut rows, mut cols) = (1, 1);
        for style in self.chain(id).into_iter().rev() {
            whole = style.table.over(&whole);
            for (k, c) in &style.conditional {
                let merged = match parts.get(k) {
                    Some(base) => c.over(base),
                    None => c.clone(),
                };
                parts.insert(k.clone(), merged);
            }
            rows = style.band_rows.unwrap_or(rows);
            cols = style.band_cols.unwrap_or(cols);
        }
        (whole, parts, rows, cols)
    }
}

// ------------------------------------------------------------ numbering

#[derive(Clone, Default)]
struct Level {
    format: String,
    text: String,
    start: u32,
    para: ParaProps,
    run: RunProps,
    suffix: String,
    legal: bool,
}

/// A list instance's changes to its levels: a new start, or a whole new level.
type Overrides = HashMap<u8, (Option<u32>, Option<Level>)>;

#[derive(Default)]
struct Numbering {
    abstracts: HashMap<String, Vec<Level>>,
    /// numId → (abstractNumId, level overrides).
    nums: HashMap<String, (String, Overrides)>,
    counters: HashMap<String, [u32; 9]>,
    started: HashSet<String>,
}

impl Numbering {
    fn read(root: &Node, theme: &Theme) -> Numbering {
        let level = |l: &Node| Level {
            format: l.child("numFmt").and_then(|f| f.attr("val")).unwrap_or("decimal").to_owned(),
            text: l.child("lvlText").and_then(|t| t.attr("val")).unwrap_or("").to_owned(),
            start: l.child("start").and_then(|s| num(s, "val")).map_or(1, |v| v.max(0.0) as u32),
            para: l.child("pPr").map(|p| parse_ppr(p, theme)).unwrap_or_default(),
            run: l.child("rPr").map(|r| parse_rpr(r, theme)).unwrap_or_default(),
            suffix: l.child("suff").and_then(|s| s.attr("val")).unwrap_or("tab").to_owned(),
            legal: l.child("isLgl").is_some_and(on),
        };
        let mut n = Numbering::default();
        for a in root.children_named("abstractNum") {
            let Some(id) = a.attr("abstractNumId") else { continue };
            let mut levels = vec![Level::default(); 9];
            for l in a.children_named("lvl") {
                let i = l.attr("ilvl").and_then(|v| v.parse::<usize>().ok()).unwrap_or(0);
                if i < 9 {
                    levels[i] = level(l);
                }
            }
            n.abstracts.insert(id.to_owned(), levels);
        }
        for m in root.children_named("num") {
            let Some(id) = m.attr("numId") else { continue };
            let Some(abs) = m.child("abstractNumId").and_then(|a| a.attr("val")) else { continue };
            let mut overrides = HashMap::new();
            for o in m.children_named("lvlOverride") {
                let i = o.attr("ilvl").and_then(|v| v.parse::<u8>().ok()).unwrap_or(0);
                let start = o.child("startOverride").and_then(|s| num(s, "val")).map(|v| v.max(0.0) as u32);
                overrides.insert(i, (start, o.child("lvl").map(level)));
            }
            n.nums.insert(id.to_owned(), (abs.to_owned(), overrides));
        }
        n
    }

    fn level(&self, num_id: &str, level: u8) -> Option<Level> {
        let (abs, overrides) = self.nums.get(num_id)?;
        if let Some((_, Some(l))) = overrides.get(&level) {
            return Some(l.clone());
        }
        self.abstracts.get(abs)?.get(usize::from(level)).cloned()
    }

    /// The label of the next item, advancing the list's counters.
    fn next(&mut self, num_id: &str, level: u8) -> Option<(String, Level)> {
        let lvl = self.level(num_id, level)?;
        let (abs, overrides) = self.nums.get(num_id)?.clone();
        let li = usize::from(level);
        let mut starts = [1u32; 9];
        for (i, s) in starts.iter_mut().enumerate() {
            *s = self.level(num_id, i as u8).map_or(1, |l| l.start);
        }
        let counters = self.counters.entry(abs.clone()).or_insert([0; 9]);
        // A list instance with a start override restarts where it first appears.
        if self.started.insert(format!("{num_id}:{level}")) {
            if let Some((Some(start), _)) = overrides.get(&level) {
                counters[li] = start.saturating_sub(1);
            }
        }
        if counters[li] == 0 {
            counters[li] = starts[li].saturating_sub(1);
        }
        counters[li] += 1;
        for deeper in &mut counters[li + 1..] {
            *deeper = 0;
        }
        let counters = *counters;
        if lvl.format == "none" {
            return Some((String::new(), lvl));
        }
        let mut text = String::new();
        let mut chars = lvl.text.chars().peekable();
        while let Some(c) = chars.next() {
            if c == '%' && chars.peek().is_some_and(|d| d.is_ascii_digit()) {
                let d = chars.next().and_then(|d| d.to_digit(10)).unwrap_or(1) as usize;
                if (1..=9).contains(&d) {
                    let i = d - 1;
                    let value = counters[i].max(if i == li { 1 } else { starts[i] });
                    let format = if lvl.legal && i != li {
                        "decimal".to_owned()
                    } else {
                        self.level(num_id, i as u8).map_or_else(|| "decimal".to_owned(), |l| l.format)
                    };
                    text.push_str(&format_number(value, &format));
                }
            } else {
                text.push(symbol_char(c, lvl.run.font.as_deref()));
            }
        }
        Some((text, lvl))
    }
}

pub(crate) fn format_number(n: u32, format: &str) -> String {
    match format {
        "lowerLetter" => letters(n).to_lowercase(),
        "upperLetter" => letters(n),
        "lowerRoman" => roman(n).to_lowercase(),
        "upperRoman" => roman(n),
        "decimalZero" => format!("{n:02}"),
        "ordinal" => {
            let suffix = match (n % 10, n % 100) {
                (1, x) if x != 11 => "st",
                (2, x) if x != 12 => "nd",
                (3, x) if x != 13 => "rd",
                _ => "th",
            };
            format!("{n}{suffix}")
        }
        "bullet" => "•".to_owned(),
        _ => n.to_string(),
    }
}

fn letters(n: u32) -> String {
    // Word repeats the letter: a … z, aa … zz.
    if n == 0 {
        return String::new();
    }
    let c = (b'A' + ((n - 1) % 26) as u8) as char;
    c.to_string().repeat(((n - 1) / 26 + 1) as usize)
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

/// Symbol and Wingdings characters (private-use or plain codes in those
/// fonts) as the Unicode ones they draw.
pub(crate) fn symbol_char(c: char, font: Option<&str>) -> char {
    let code = c as u32;
    let code = if (0xF000..=0xF0FF).contains(&code) { code - 0xF000 } else { code };
    let font = font.unwrap_or("").to_ascii_lowercase();
    if font.starts_with("wingdings") {
        return match code {
            0x6C => '●',
            0x6E => '■',
            0x71 | 0xA7 => '▪',
            0x76 => '❖',
            0xA8 => '◆',
            0xD8 => '➢',
            0xFC => '✓',
            0xFB => '✗',
            0xFE => '☑',
            0x6F => '□',
            0xE0 | 0xE8 => '➔',
            _ => '•',
        };
    }
    if font == "symbol" || (0xF000..=0xF0FF).contains(&(c as u32)) {
        return match code {
            0xB7 => '•',
            0xA8 => '♦',
            0xD8 => '¬',
            0x2D => '−',
            0xB0 => '°',
            0xB4 => '×',
            0xB8 => '÷',
            _ => char::from_u32(code).filter(|c| !c.is_control()).unwrap_or('•'),
        };
    }
    c
}

// ------------------------------------------------------------ fonts

/// The CSS stack for a Word font: itself if installed, then the bundled font
/// with the same metrics, then a generic family.
pub(crate) fn font_stack(name: &str) -> String {
    let lower = name.to_ascii_lowercase();
    let (fallback, generic) = match lower.as_str() {
        "calibri" | "calibri light" | "carlito" | "segoe ui" | "tahoma" | "verdana" => ("EzCarlito", "sans-serif"),
        "cambria" | "cambria math" | "caladea" | "georgia" | "constantia" | "book antiqua" | "palatino linotype"
        | "garamond" => ("EzCaladea", "serif"),
        "arial"
        | "helvetica"
        | "liberation sans"
        | "arimo"
        | "aptos"
        | "aptos display"
        | "arial nova"
        | "helvetica neue"
        | "century gothic"
        | "trebuchet ms"
        | "franklin gothic book" => ("EzArimo", "sans-serif"),
        "times new roman" | "times" | "liberation serif" | "tinos" | "century schoolbook" | "bookman old style" => {
            ("EzTinos", "serif")
        }
        "courier new" | "courier" | "liberation mono" | "cousine" | "consolas" | "lucida console" => {
            ("EzCousine", "monospace")
        }
        _ if lower.contains("mono") || lower.contains("code") => ("EzCousine", "monospace"),
        _ if lower.contains("serif") && !lower.contains("sans") => ("EzTinos", "serif"),
        _ => ("EzCarlito", "sans-serif"),
    };
    let clean: String =
        // `&` and `#` too: inside a style attribute `&#59;` decodes to `;` before CSS sees it.
        name.chars()
            .filter(|c| !c.is_control() && !matches!(c, '"' | '\'' | '\\' | ';' | '<' | '>' | '{' | '}' | '&' | '#'))
            .collect();
    format!("\"{clean}\",{fallback},{generic}")
}

/// Line height multiplier of "single" spacing for a font, from its metrics.
pub(crate) fn single_line(font: &str) -> f32 {
    match font.to_ascii_lowercase().as_str() {
        "calibri" | "calibri light" | "carlito" => 1.22,
        "cambria" | "caladea" => 1.172,
        "times new roman" | "times" | "tinos" | "liberation serif" => 1.15,
        "arial" | "helvetica" | "arimo" | "liberation sans" => 1.149,
        "courier new" | "courier" | "cousine" => 1.133,
        "aptos" | "aptos display" => 1.2,
        _ => 1.2,
    }
}

pub(crate) const FONT_FACES: &str = "\
@font-face{font-family:EzCarlito;src:url(ezfonts/Carlito-Regular.ttf)}\
@font-face{font-family:EzCarlito;font-weight:bold;src:url(ezfonts/Carlito-Bold.ttf)}\
@font-face{font-family:EzCarlito;font-style:italic;src:url(ezfonts/Carlito-Italic.ttf)}\
@font-face{font-family:EzCarlito;font-weight:bold;font-style:italic;src:url(ezfonts/Carlito-BoldItalic.ttf)}\
@font-face{font-family:EzCaladea;src:url(ezfonts/Caladea-Regular.ttf)}\
@font-face{font-family:EzCaladea;font-weight:bold;src:url(ezfonts/Caladea-Bold.ttf)}\
@font-face{font-family:EzCaladea;font-style:italic;src:url(ezfonts/Caladea-Italic.ttf)}\
@font-face{font-family:EzCaladea;font-weight:bold;font-style:italic;src:url(ezfonts/Caladea-BoldItalic.ttf)}\
@font-face{font-family:EzArimo;font-weight:100 900;src:url(ezfonts/Arimo-Variable.ttf)}\
@font-face{font-family:EzArimo;font-weight:100 900;font-style:italic;src:url(ezfonts/Arimo-Italic-Variable.ttf)}\
@font-face{font-family:EzTinos;src:url(ezfonts/Tinos-Regular.ttf)}\
@font-face{font-family:EzTinos;font-weight:bold;src:url(ezfonts/Tinos-Bold.ttf)}\
@font-face{font-family:EzTinos;font-style:italic;src:url(ezfonts/Tinos-Italic.ttf)}\
@font-face{font-family:EzTinos;font-weight:bold;font-style:italic;src:url(ezfonts/Tinos-BoldItalic.ttf)}\
@font-face{font-family:EzCousine;src:url(ezfonts/Cousine-Regular.ttf)}\
@font-face{font-family:EzCousine;font-weight:bold;src:url(ezfonts/Cousine-Bold.ttf)}";

pub(crate) const BASE_CSS: &str = "\
html,body{margin:0;padding:0;background:#e8e8ec;-webkit-text-size-adjust:none;text-size-adjust:none}\
#ez-doc{padding:16px 0}\
.ez-section{display:none}\
.ez-page{position:relative;margin:0 auto 16px;box-shadow:0 1px 3px rgba(0,0,0,.3);overflow:hidden;box-sizing:border-box}\
.ez-body,.ez-head,.ez-foot,.ez-notes{position:absolute}\
.ez-p{margin:0;white-space:pre-wrap;overflow-wrap:break-word;position:relative}\
.ez-keep{break-after:avoid-column}\
.ez-mk{display:inline-block;white-space:pre;text-indent:0}\
.ez-tabs{display:flex;align-items:baseline;white-space:pre}\
.ez-tabs>.ez-fill{flex:1;overflow:hidden;white-space:nowrap;margin:0 2px}\
table.ez-t{border-collapse:collapse;border-spacing:0}\
table.ez-t td{vertical-align:top;overflow-wrap:break-word;box-sizing:border-box}\
.ez-img{display:inline-block;vertical-align:baseline;object-fit:fill}\
.ez-box{display:inline-block;vertical-align:top;box-sizing:border-box;overflow:hidden}\
.ez-ph{display:inline-flex;align-items:center;justify-content:center;border:1px dashed #94a3b8;color:#64748b;font:12px sans-serif;background:#f8fafc;vertical-align:top;box-sizing:border-box}\
.ez-fnsep{width:33%;border-top:1px solid #000;margin:6px 0 4px}\
.ez-fnbody{display:none}\
.ez-notes .ez-fnbody,.ez-endnotes .ez-fnbody{display:block}\
.ez-frac{display:inline-flex;flex-direction:column;vertical-align:middle;text-align:center;font-size:90%}\
.ez-frac>span:first-child{border-bottom:1px solid currentColor}\
.ez-math{font-family:EzCaladea,serif;font-style:italic}\
a{color:inherit;text-decoration:inherit}\
sup,sub{line-height:0}";

/// Lays the sections out into pages; see the module notes.
pub(crate) const PAGINATE_JS: &str = r#"(function(){
const doc=document.getElementById('ez-doc');const secs=[...doc.querySelectorAll('.ez-section')];
const all=[];let pageNo=0;
function el(cls){const e=document.createElement('div');e.className=cls;return e}
function tpl(sec,kind,type){const t=sec.querySelector('template[data-kind="'+kind+'"][data-type="'+type+'"]');return t?t.innerHTML:null}
for(const sec of secs){
 const d=sec.dataset;const W=+d.w,H=+d.h,mt=+d.mt,mb=+d.mb,ml=+d.ml,mr=+d.mr,hd=+d.hd,fd=+d.fd,cols=+(d.cols||1),gap=+(d.gap||48);
 if(d.start)pageNo=+d.start-1;
 const limit=H-mt-mb;const flow=sec.querySelector('.ez-flow');const items=[...flow.childNodes].filter(n=>n.nodeType===1);
 let inSec=0,page=null,body=null,notes=null,continued=d.continuous&&all.length>0;
 function newPage(){
  pageNo++;inSec++;page=el('ez-page');page.style.width=W+'px';page.style.height=H+'px';page.style.background=d.bg||'#fff';page.dataset.n=pageNo;
  const type=(inSec===1&&d.titlepg)?'first':((d.evenodd&&pageNo%2===0)?'even':'default');
  const h=tpl(sec,'header',type),f=tpl(sec,'footer',type);
  if(h){const e=el('ez-head');e.innerHTML=h;e.style.cssText='left:'+ml+'px;right:'+mr+'px;top:'+hd+'px';page.appendChild(e)}
  if(f){const e=el('ez-foot');e.innerHTML=f;e.style.cssText='left:'+ml+'px;right:'+mr+'px;bottom:'+fd+'px';page.appendChild(e)}
  body=el('ez-body');body.style.cssText='left:'+ml+'px;top:'+mt+'px;width:'+(W-ml-mr)+'px;'+(cols>1?'height:'+limit+'px;column-count:'+cols+';column-gap:'+gap+'px;column-fill:auto;':'');
  notes=el('ez-notes');notes.style.cssText='left:'+ml+'px;width:'+(W-ml-mr)+'px;bottom:'+mb+'px';
  page.appendChild(body);page.appendChild(notes);doc.insertBefore(page,sec);all.push(page)
 }
 function syncNotes(){
  const refs=body.querySelectorAll('.ez-fnref');notes.innerHTML='';
  if(!refs.length)return;notes.appendChild(el('ez-fnsep'));
  for(const r of refs){const b=document.getElementById('ez-fn-'+r.dataset.fn);if(b)notes.appendChild(b.cloneNode(true))}
 }
 function fits(){syncNotes();
  if(cols>1)return body.scrollWidth<=body.clientWidth+2;
  return body.scrollHeight+(notes.childElementCount?notes.offsetHeight:0)<=limit+2}
 function bottomOf(range){const r=range.getBoundingClientRect();return r.bottom}
 function splitPara(p){
  const tw=document.createTreeWalker(p,NodeFilter.SHOW_TEXT);const nodes=[];let n;while(n=tw.nextNode())nodes.push(n);
  if(!nodes.length)return false;
  const max=body.getBoundingClientRect().top+limit-(notes.childElementCount?notes.offsetHeight:0);
  const pos=[];for(const t of nodes)for(let i=0;i<t.length;i++)pos.push([t,i]);
  const r=document.createRange();
  const over=k=>{r.setStart(pos[k][0],pos[k][1]);r.setEnd(pos[k][0],pos[k][1]+1);const rects=r.getClientRects();return rects.length&&rects[rects.length-1].bottom>max+1};
  let lo=0,hi=pos.length-1;if(!over(hi))return false;
  while(lo<hi){const m=(lo+hi)>>1;if(over(m))hi=m;else lo=m+1}
  if(lo===0)return false;
  // Keep two lines together at each side where possible (widow and orphan control).
  r.setStart(pos[0][0],0);r.setEnd(pos[lo][0],pos[lo][1]);if(r.getClientRects().length===0)return false;
  const rest=p.cloneNode(false);const cut=document.createRange();cut.setStart(pos[lo][0],pos[lo][1]);cut.setEndAfter(p.lastChild);
  rest.appendChild(cut.extractContents());rest.style.textIndent='0';rest.style.marginTop='0';rest.style.paddingTop='0';rest.style.borderTop='none';
  p.style.marginBottom='0';p.style.paddingBottom='0';p.style.borderBottom='none';
  if(!fits()){p.parentNode.removeChild(p);while(rest.firstChild)p.appendChild(rest.firstChild);return false}
  newPage();place(rest);return true
 }
 function splitTable(t){
  const body0=t.tBodies[0];if(!body0)return false;const rows=[...body0.rows];const heads=rows.filter(r=>r.classList.contains('ez-hdr'));
  const movable=rows.filter(r=>!r.classList.contains('ez-hdr'));if(movable.length<2)return false;
  const t2=t.cloneNode(false);const cg=t.querySelector('colgroup');if(cg)t2.appendChild(cg.cloneNode(true));const b2=document.createElement('tbody');t2.appendChild(b2);
  for(const h of heads)b2.appendChild(h.cloneNode(true));const at=b2.childNodes.length;
  let i=movable.length-1;
  while(!fits()&&i>0){b2.insertBefore(movable[i],b2.childNodes[at]||null);i--}
  if(!fits())return false;
  newPage();place(t2);return true
 }
 function place(e){
  if(!page)newPage();body.appendChild(e);if(fits())return;
  const tag=e.tagName;
  if(tag==='TABLE'&&splitTable(e))return;
  if(e.classList.contains('ez-p')&&splitPara(e))return;
  if(body.childElementCount===1)return;
  body.removeChild(e);syncNotes();newPage();place(e)
 }
 if(!continued||!all.length)newPage();else{page=all[all.length-1];body=page.querySelector('.ez-body');notes=page.querySelector('.ez-notes');inSec=1}
 let prev=null;
 for(const e of items){
  if(e.classList.contains('ez-pb')){if(body.childElementCount)newPage();prev=null;continue}
  place(e);
  // Keep with next: a heading left at the foot of a page goes over with what follows it.
  if(prev&&prev.classList.contains('ez-keep')&&cols<2&&e.parentNode&&prev.parentNode&&e.parentNode!==prev.parentNode&&prev.parentNode.childElementCount>1&&e.parentNode.firstElementChild===e){
   e.parentNode.insertBefore(prev,e);
  }
  prev=e
 }
 sec.remove()
}
const total=all.length;
for(const p of all){for(const s of p.querySelectorAll('.ez-fld-page'))s.textContent=p.dataset.n;for(const s of p.querySelectorAll('.ez-fld-numpages'))s.textContent=total}
document.body.dataset.pages=total;
})();"#;

// ------------------------------------------------------------ sections

#[derive(Clone)]
struct Section {
    width: f32,
    height: f32,
    margins: [f32; 4],
    header: f32,
    footer: f32,
    cols: u32,
    gap: f32,
    title_page: bool,
    continuous: bool,
    start: Option<u32>,
    headers: HashMap<String, String>,
    footers: HashMap<String, String>,
}

impl Default for Section {
    fn default() -> Self {
        Section {
            width: 816.0,
            height: 1056.0,
            margins: [96.0, 96.0, 96.0, 96.0],
            header: 48.0,
            footer: 48.0,
            cols: 1,
            gap: 48.0,
            title_page: false,
            continuous: false,
            start: None,
            headers: HashMap::new(),
            footers: HashMap::new(),
        }
    }
}

fn parse_section(node: &Node, previous: &Section) -> Section {
    let mut s = Section { headers: previous.headers.clone(), footers: previous.footers.clone(), ..Section::default() };
    if let Some(sz) = node.child("pgSz") {
        s.width = num(sz, "w").map_or(s.width, twips);
        s.height = num(sz, "h").map_or(s.height, twips);
    }
    if let Some(m) = node.child("pgMar") {
        let get = |a: &str, d: f32| num(m, a).map_or(d, |v| twips(v.abs()));
        s.margins = [get("top", 96.0), get("right", 96.0), get("bottom", 96.0), get("left", 96.0) + get("gutter", 0.0)];
        s.header = get("header", 48.0);
        s.footer = get("footer", 48.0);
    }
    if let Some(c) = node.child("cols") {
        s.cols = num(c, "num").map_or(1, |n| n.clamp(1.0, 6.0) as u32);
        s.gap = num(c, "space").map_or(48.0, twips);
    }
    s.title_page = node.child("titlePg").is_some_and(on);
    s.continuous = node.child("type").and_then(|t| t.attr("val")) == Some("continuous");
    s.start = node.child("pgNumType").and_then(|p| num(p, "start")).map(|v| v.max(0.0) as u32);
    for (kind, map) in [("headerReference", &mut s.headers), ("footerReference", &mut s.footers)] {
        for r in node.children_named(kind) {
            if let (Some(t), Some(id)) = (r.attr("type"), r.prefixed_attr("id")) {
                map.insert(t.to_owned(), id.to_owned());
            }
        }
    }
    s.width = s.width.clamp(96.0, 4000.0);
    s.height = s.height.clamp(96.0, 6000.0);
    s
}

// ------------------------------------------------------------ the renderer

struct Field {
    instr: String,
    /// Showing the saved result (after `separate`).
    result: bool,
    /// The result is replaced by a live value, so its saved text is hidden.
    replaced: bool,
    link_open: bool,
}

struct Renderer {
    styles: Styles,
    numbering: Numbering,
    theme: Theme,
    rels: Rc<HashMap<String, Relationship>>,
    footnotes: HashMap<String, Rc<Node>>,
    endnotes: HashMap<String, Rc<Node>>,
    note_bodies: String,
    endnote_bodies: String,
    note_count: u32,
    in_note: Option<u32>,
    fields: Vec<Field>,
    tab_stop: f32,
    cancel: CancelToken,
    steps: u32,
    table_depth: u32,
    /// The style of the last paragraph written, for contextual spacing.
    last_style: Option<String>,
    last_contextual: bool,
    /// Where the last paragraph ended: its buffer and length then.
    last_end: Option<(usize, usize)>,
}

/// Table context handed to the paragraphs of a cell.
#[derive(Clone, Default)]
struct CellContext {
    para: ParaProps,
    run: RunProps,
}

impl Renderer {
    fn check(&mut self) -> Result<()> {
        self.steps += 1;
        if self.steps % 512 == 0 {
            self.cancel.check()?;
        }
        Ok(())
    }

    fn blocks(&mut self, parent: &Node, cell: Option<&CellContext>, out: &mut String) -> Result<()> {
        for child in &parent.children {
            self.block(child, cell, out)?;
            if out.len() > MAX_HTML_BYTES {
                return Err(EngineError::limit("the document is too large to lay out as pages"));
            }
        }
        Ok(())
    }

    fn block(&mut self, node: &Node, cell: Option<&CellContext>, out: &mut String) -> Result<()> {
        self.check()?;
        match node.name() {
            "p" => self.paragraph(node, cell, out),
            "tbl" => self.table(node, out),
            "sdt" => match node.child("sdtContent") {
                Some(content) => self.blocks(content, cell, out),
                None => Ok(()),
            },
            "customXml" | "ins" | "moveTo" | "smartTag" => self.blocks(node, cell, out),
            "bookmarkStart" => {
                if let Some(name) = node.attr("name") {
                    let _ = write!(out, "<a id=\"bm-{}\"></a>", esc_attr(name));
                }
                Ok(())
            }
            "oMathPara" => {
                out.push_str("<p class=\"ez-p\" style=\"text-align:center\">");
                self.math(node, out);
                out.push_str("</p>");
                Ok(())
            }
            _ => Ok(()),
        }
    }

    // ---- paragraphs

    fn paragraph(&mut self, node: &Node, cell: Option<&CellContext>, out: &mut String) -> Result<()> {
        let own = node.child("pPr").map(|p| parse_ppr(p, &self.theme)).unwrap_or_default();
        let (style_para, style_run) = self.styles.para_style(own.style.as_deref());
        let base_para = match cell {
            Some(c) => style_para.over(&c.para.over(&self.styles.doc_para)),
            None => style_para.over(&self.styles.doc_para),
        };
        let mut para = own.over(&base_para);
        let run_base = match cell {
            Some(c) => style_run.over(&c.run.over(&self.styles.doc_run)),
            None => style_run.over(&self.styles.doc_run),
        };

        // List label, with the level's indents under the paragraph's own.
        let mut marker = None;
        if let Some((id, level)) = para.num.clone().filter(|(id, _)| !id.is_empty() && id != "0") {
            if let Some((text, lvl)) = self.numbering.next(&id, level) {
                para = own.over(&lvl.para.over(&base_para));
                para.num = Some((id, level));
                let mark_run = lvl.run.over(&para.mark.over(&run_base));
                marker = Some((text, lvl.suffix.clone(), mark_run));
            }
        }

        if para.page_break_before == Some(true) && cell.is_none() {
            out.push_str("<div class=\"ez-pb\"></div>");
        }

        let style_id = own.style.clone().or_else(|| self.styles.default_para.clone());
        let contextual = para.contextual == Some(true);
        let same_as_last = contextual && self.last_contextual && self.last_style == style_id;
        self.last_style = style_id;
        self.last_contextual = contextual;

        let mark_run = para.mark.over(&run_base);
        // Contextual spacing: no space between paragraphs of one style, so the
        // one just written loses its space below too.
        if same_as_last
            && out.ends_with("</p>")
            && self.last_end == Some((out.as_ptr() as usize, out.len()))
            && let Some(start) = out.rfind("<p class=\"ez-p")
            && let Some(at) = out[start..].find("margin-bottom:").map(|i| start + i)
            && let Some(end) = out[at..].find(';').map(|i| at + i + 1)
        {
            out.replace_range(at..end, "margin-bottom:0.0px;");
        }
        let keep = para.keep_next == Some(true) && cell.is_none();
        let open_tag = |out: &mut String, para: &ParaProps, first_part: bool| {
            out.push_str(if keep { "<p class=\"ez-p ez-keep\" style=\"" } else { "<p class=\"ez-p\" style=\"" });
            paragraph_css(out, para, &mark_run, same_as_last && first_part, contextual);
            out.push_str("\">");
        };
        let mut html = String::new();
        open_tag(&mut html, &para, true);
        if let Some((text, suffix, run)) = &marker {
            if !text.is_empty() || suffix == "tab" {
                let hanging = (-para.first.unwrap_or(0.0)).max(0.0);
                html.push_str("<span class=\"ez-mk\" style=\"");
                run_css(&mut html, run);
                if suffix == "tab" {
                    let tab = if hanging > 1.0 { hanging } else { self.tab_stop };
                    let _ = write!(html, "min-width:{tab:.1}px;");
                }
                html.push_str("\">");
                esc(&mut html, text);
                if suffix == "space" {
                    html.push(' ');
                }
                html.push_str("</span>");
            }
        }
        let mut content = String::new();
        self.inline_children(node, &run_base, &mut content)?;
        // Close any hyperlink field still open at the paragraph's end.
        for f in &mut self.fields {
            if f.link_open {
                content.push_str("</a>");
                f.link_open = false;
            }
        }
        let content = self.lay_out_tabs(&para, content);
        // Page breaks inside the paragraph split it.
        let mut parts = content.split("\u{E000}").peekable();
        let mut first = true;
        while let Some(part) = parts.next() {
            if !first {
                html.push_str("<div class=\"ez-pb\"></div>");
                let mut rest = para.clone();
                rest.first = Some(0.0);
                open_tag(&mut html, &rest, false);
            }
            html.push_str(part);
            let empty = part.is_empty() && (marker.is_none() || !first);
            if empty || part.ends_with("<br>") {
                // An empty line keeps the height of its paragraph mark.
                html.push_str("<br>");
            }
            html.push_str("</p>");
            first = false;
            let _ = parts.peek();
        }
        out.push_str(&html);
        self.last_end = Some((out.as_ptr() as usize, out.len()));
        Ok(())
    }

    /// Positioned and leader tabs, where the paragraph sets tab stops.
    fn lay_out_tabs(&self, para: &ParaProps, content: String) -> String {
        let stops: Vec<&TabStop> = para.tabs.as_ref().map(|t| t.iter().collect()).unwrap_or_default();
        if !content.contains('\t') || stops.is_empty() {
            return content;
        }
        let segments: Vec<&str> = content.split('\t').collect();
        let right_or_center = stops.iter().any(|s| matches!(s.kind.as_str(), "right" | "end" | "center" | "decimal"));
        if !right_or_center {
            return content;
        }
        // One tab to a right stop (a table of contents line, a header): text, leader, text.
        let left = para.left.unwrap_or(0.0);
        let mut out = String::new();
        if segments.len() == 2 {
            let stop = stops.iter().rev().find(|s| matches!(s.kind.as_str(), "right" | "end")).copied();
            if let Some(stop) = stop {
                let leader = match stop.leader.as_deref() {
                    Some("dot") => "border-bottom:1.5px dotted currentColor;margin-bottom:.3em",
                    Some("hyphen") => "border-bottom:1px dashed currentColor;margin-bottom:.3em",
                    Some("underscore" | "heavy") => "border-bottom:1px solid currentColor",
                    Some("middleDot") => "border-bottom:2px dotted currentColor;margin-bottom:.45em",
                    _ => "",
                };
                let _ = write!(
                    out,
                    "<span class=\"ez-tabs\" style=\"display:inline-flex;width:{:.1}px;max-width:100%\"><span>{}</span><span class=\"ez-fill\" style=\"{leader}\"></span><span>{}</span></span>",
                    (stop.pos - left).max(0.0),
                    segments[0],
                    segments[1]
                );
                return out;
            }
        }
        // Several stops: each piece placed at its stop, aligned as the stop says.
        let mut sorted: Vec<&TabStop> = stops.clone();
        sorted.sort_by(|a, b| a.pos.total_cmp(&b.pos));
        out.push_str("<span style=\"display:inline-block;position:relative;width:100%;min-height:1.2em\">");
        out.push_str("<span>");
        out.push_str(segments[0]);
        out.push_str("</span>");
        for (i, seg) in segments.iter().enumerate().skip(1) {
            let (pos, kind) = sorted
                .get(i - 1)
                .map_or_else(|| (self.tab_stop * i as f32, "left"), |s| (s.pos - left, s.kind.as_str()));
            let shift = match kind {
                "center" => "translateX(-50%)",
                "right" | "end" | "decimal" => "translateX(-100%)",
                _ => "none",
            };
            let _ = write!(
                out,
                "<span style=\"position:absolute;left:{pos:.1}px;top:0;transform:{shift};white-space:pre\">{seg}</span>"
            );
        }
        out.push_str("</span>");
        out
    }

    fn inline_children(&mut self, parent: &Node, base: &RunProps, out: &mut String) -> Result<()> {
        for child in &parent.children {
            self.check()?;
            match child.name() {
                "r" => self.run(child, base, out)?,
                "hyperlink" => {
                    let href = child
                        .prefixed_attr("id")
                        .and_then(|id| self.rels.get(id))
                        .filter(|r| r.external && is_safe_link(&r.target))
                        .map(|r| r.target.clone())
                        .or_else(|| child.attr("anchor").map(|a| format!("#bm-{a}")));
                    match href {
                        Some(h) => {
                            let _ = write!(out, "<a href=\"{}\">", esc_attr(&h));
                            self.inline_children(child, base, out)?;
                            out.push_str("</a>");
                        }
                        None => self.inline_children(child, base, out)?,
                    }
                }
                "ins" | "moveTo" | "smartTag" | "customXml" | "dir" | "bdo" => {
                    self.inline_children(child, base, out)?
                }
                "sdt" => {
                    if let Some(c) = child.child("sdtContent") {
                        self.inline_children(c, base, out)?;
                    }
                }
                "fldSimple" => {
                    let instr = child.attr("instr").unwrap_or("").trim().to_ascii_uppercase();
                    match instr.split_whitespace().next() {
                        Some("PAGE") => out.push_str("<span class=\"ez-fld-page\"></span>"),
                        Some("NUMPAGES" | "SECTIONPAGES") => out.push_str("<span class=\"ez-fld-numpages\"></span>"),
                        _ => self.inline_children(child, base, out)?,
                    }
                }
                "bookmarkStart" => {
                    if let Some(name) = child.attr("name") {
                        let _ = write!(out, "<a id=\"bm-{}\"></a>", esc_attr(name));
                    }
                }
                "oMath" | "oMathPara" => self.math(child, out),
                _ => {}
            }
        }
        Ok(())
    }

    fn hidden_by_field(&self) -> bool {
        self.fields.iter().any(|f| !f.result || f.replaced)
    }

    fn run(&mut self, run: &Node, base: &RunProps, out: &mut String) -> Result<()> {
        let own = run.child("rPr").map(|r| parse_rpr(r, &self.theme)).unwrap_or_default();
        let char_style = self.styles.char_style(own.style.as_deref().or(self.styles.default_char.as_deref()));
        let props = own.over(&char_style.over(base));
        let mut text = String::new();
        let flush = |text: &mut String, out: &mut String, props: &RunProps| {
            if text.is_empty() {
                return;
            }
            out.push_str("<span style=\"");
            run_css(out, props);
            out.push_str("\">");
            out.push_str(text);
            out.push_str("</span>");
            text.clear();
        };
        for c in &run.children {
            match c.name() {
                "fldChar" => {
                    flush(&mut text, out, &props);
                    match c.attr("fldCharType") {
                        Some("begin") => self.fields.push(Field {
                            instr: String::new(),
                            result: false,
                            replaced: false,
                            link_open: false,
                        }),
                        Some("separate") => {
                            if let Some(f) = self.fields.last_mut() {
                                f.result = true;
                                let instr = f.instr.trim().to_owned();
                                let word = instr.split_whitespace().next().unwrap_or("").to_ascii_uppercase();
                                match word.as_str() {
                                    "PAGE" => {
                                        f.replaced = true;
                                        out.push_str("<span style=\"");
                                        run_css(out, &props);
                                        out.push_str("\" class=\"ez-fld-page\"></span>");
                                    }
                                    "NUMPAGES" | "SECTIONPAGES" => {
                                        f.replaced = true;
                                        out.push_str("<span style=\"");
                                        run_css(out, &props);
                                        out.push_str("\" class=\"ez-fld-numpages\"></span>");
                                    }
                                    "HYPERLINK" => {
                                        if let Some(href) = hyperlink_target(&instr) {
                                            let _ = write!(out, "<a href=\"{}\">", esc_attr(&href));
                                            f.link_open = true;
                                        }
                                    }
                                    _ => {}
                                }
                            }
                        }
                        Some("end") => {
                            if let Some(f) = self.fields.pop()
                                && f.link_open
                            {
                                out.push_str("</a>");
                            }
                        }
                        _ => {}
                    }
                }
                "instrText" => {
                    if let Some(f) = self.fields.last_mut()
                        && !f.result
                        && f.instr.len() < 2048
                    {
                        f.instr.push_str(&c.text);
                    }
                }
                _ if self.hidden_by_field() => {}
                _ if props.hidden == Some(true) => {}
                "t" => esc(&mut text, &c.text),
                // Outside the run's span, so the pieces between tabs stay whole for tab stops.
                "tab" | "ptab" => {
                    flush(&mut text, out, &props);
                    out.push('\t');
                }
                "br" => match c.attr("type") {
                    Some("page") if self.table_depth == 0 && self.in_note.is_none() => text.push('\u{E000}'),
                    Some("column") => text.push_str("<br>"),
                    _ => text.push_str("<br>"),
                },
                "cr" => text.push_str("<br>"),
                "noBreakHyphen" => text.push('\u{2011}'),
                "softHyphen" => text.push('\u{AD}'),
                "sym" => {
                    let code = c.attr("char").and_then(|h| u32::from_str_radix(h, 16).ok()).and_then(char::from_u32);
                    if let Some(ch) = code {
                        text.push(symbol_char(ch, c.attr("font")));
                    }
                }
                "footnoteReference" | "endnoteReference" => {
                    flush(&mut text, out, &props);
                    self.note_reference(c, out)?;
                }
                "footnoteRef" | "endnoteRef" => {
                    if let Some(n) = self.in_note {
                        let _ = write!(text, "<sup>{n}</sup>");
                    }
                }
                "drawing" => {
                    flush(&mut text, out, &props);
                    self.drawing(c, out)?;
                }
                "pict" | "object" => {
                    flush(&mut text, out, &props);
                    self.vml(c, out)?;
                }
                "AlternateContent" => {
                    flush(&mut text, out, &props);
                    let chosen = c.children.iter().find(|x| x.name() == "Choice").or_else(|| c.child("Fallback"));
                    if let Some(ch) = chosen {
                        for inner in &ch.children {
                            match inner.name() {
                                "drawing" => self.drawing(inner, out)?,
                                "pict" => self.vml(inner, out)?,
                                _ => {}
                            }
                        }
                    }
                }
                _ => {}
            }
        }
        flush(&mut text, out, &props);
        Ok(())
    }

    fn note_reference(&mut self, node: &Node, out: &mut String) -> Result<()> {
        let end = node.name() == "endnoteReference";
        let Some(id) = node.attr("id") else { return Ok(()) };
        let body = if end { self.endnotes.get(id).cloned() } else { self.footnotes.get(id).cloned() };
        self.note_count += 1;
        let n = self.note_count;
        let _ = write!(out, "<sup class=\"{}\" data-fn=\"{n}\">{n}</sup>", if end { "ez-enref" } else { "ez-fnref" });
        if let Some(body) = body
            && self.in_note.is_none()
        {
            let saved = (self.last_style.take(), self.last_contextual);
            self.in_note = Some(n);
            let mut html = String::new();
            let _ = write!(html, "<div class=\"ez-fnbody\" id=\"ez-fn-{n}\">");
            self.blocks(&body, None, &mut html)?;
            html.push_str("</div>");
            self.in_note = None;
            (self.last_style, self.last_contextual) = saved;
            if end {
                self.endnote_bodies.push_str(&html);
            } else {
                self.note_bodies.push_str(&html);
            }
        }
        Ok(())
    }

    // ---- pictures and shapes

    fn picture_src(&self, id: Option<&str>) -> Option<String> {
        let rel = self.rels.get(id?)?;
        let path = rel.inside.as_ref().filter(|_| !rel.external)?;
        is_raster_picture(path).then(|| resource_url(path))
    }

    fn drawing(&mut self, drawing: &Node, out: &mut String) -> Result<()> {
        for item in &drawing.children {
            let anchored = item.name() == "anchor";
            if !anchored && item.name() != "inline" {
                continue;
            }
            let (w, h) = item
                .child("extent")
                .map_or((96.0, 96.0), |e| (num(e, "cx").map_or(96.0, emu), num(e, "cy").map_or(96.0, emu)));
            let alt = item.child("docPr").and_then(|d| d.attr("descr").or(d.attr("title"))).unwrap_or("").to_owned();
            let mut style = format!("width:{w:.1}px;height:{h:.1}px;");
            if anchored {
                style.push_str(&anchor_css(item));
            }
            let Some(data) = item.find("graphicData") else { continue };
            self.graphic(data, &style, w, h, &alt, out)?;
        }
        Ok(())
    }

    fn graphic(&mut self, data: &Node, style: &str, w: f32, h: f32, alt: &str, out: &mut String) -> Result<()> {
        if let Some(pic) = data.child("pic") {
            let embed = pic.find("blip").and_then(|b| b.prefixed_attr("embed"));
            match self.picture_src(embed) {
                Some(src) => {
                    let _ = write!(
                        out,
                        "<img class=\"ez-img\" loading=\"lazy\" decoding=\"async\" src=\"{}\" alt=\"{}\" style=\"{style}\">",
                        esc_attr(&src),
                        esc_attr(alt)
                    );
                }
                None => {
                    let _ = write!(
                        out,
                        "<span class=\"ez-ph\" style=\"{style}\">{}</span>",
                        esc_attr(if alt.is_empty() { "Picture" } else { alt })
                    );
                }
            }
            return Ok(());
        }
        if let Some(shape) = data.child("wsp") {
            return self.shape(shape, style, out);
        }
        if let Some(group) = data.child("wgp") {
            let _ = write!(out, "<span class=\"ez-box\" style=\"{style}position:relative\">");
            self.group(group, w, h, out)?;
            out.push_str("</span>");
            return Ok(());
        }
        let label = if data.child("chart").is_some() {
            "Chart"
        } else if data.find("relIds").is_some() {
            "SmartArt graphic"
        } else {
            "Object"
        };
        let _ = write!(out, "<span class=\"ez-ph\" style=\"{style}\">{label}</span>");
        Ok(())
    }

    fn group(&mut self, group: &Node, w: f32, h: f32, out: &mut String) -> Result<()> {
        let xfrm = group.child("grpSpPr").and_then(|g| g.child("xfrm"));
        let ch_off = xfrm
            .and_then(|x| x.child("chOff"))
            .map_or((0.0, 0.0), |o| (num(o, "x").unwrap_or(0.0), num(o, "y").unwrap_or(0.0)));
        let ch_ext = xfrm
            .and_then(|x| x.child("chExt"))
            .map_or((1.0, 1.0), |e| (num(e, "cx").unwrap_or(1.0).max(1.0), num(e, "cy").unwrap_or(1.0).max(1.0)));
        let (sx, sy) = (w / emu(ch_ext.0), h / emu(ch_ext.1));
        for child in &group.children {
            let sp_pr = child.child("spPr").or_else(|| child.child("grpSpPr"));
            let Some(x) = sp_pr.and_then(|s| s.child("xfrm")) else { continue };
            let off = x.child("off").map_or((0.0, 0.0), |o| (num(o, "x").unwrap_or(0.0), num(o, "y").unwrap_or(0.0)));
            let ext = x.child("ext").map_or((0.0, 0.0), |e| (num(e, "cx").unwrap_or(0.0), num(e, "cy").unwrap_or(0.0)));
            let (cw, ch) = (emu(ext.0) * sx, emu(ext.1) * sy);
            let style = format!(
                "position:absolute;left:{:.1}px;top:{:.1}px;width:{cw:.1}px;height:{ch:.1}px;",
                emu(off.0 - ch_off.0) * sx,
                emu(off.1 - ch_off.1) * sy
            );
            match child.name() {
                "pic" => {
                    let embed = child.find("blip").and_then(|b| b.prefixed_attr("embed"));
                    if let Some(src) = self.picture_src(embed) {
                        let _ = write!(
                            out,
                            "<img class=\"ez-img\" loading=\"lazy\" decoding=\"async\" src=\"{}\" style=\"{style}\">",
                            esc_attr(&src)
                        );
                    }
                }
                "wsp" => self.shape(child, &style, out)?,
                "grpSp" => {
                    let _ = write!(out, "<span class=\"ez-box\" style=\"{style}\">");
                    self.group(child, cw, ch, out)?;
                    out.push_str("</span>");
                }
                _ => {}
            }
        }
        Ok(())
    }

    /// A DrawingML shape: its fill and outline, and its text box if any.
    fn shape(&mut self, shape: &Node, style: &str, out: &mut String) -> Result<()> {
        let sp_pr = shape.child("spPr");
        let mut css = String::from(style);
        if let Some(fill) = sp_pr.and_then(|s| s.child("solidFill")).and_then(|f| self.drawing_color(f)) {
            let _ = write!(css, "background:{fill};");
        }
        if let Some(ln) = sp_pr.and_then(|s| s.child("ln"))
            && ln.child("noFill").is_none()
        {
            let width = num(ln, "w").map_or(1.0, |w| emu(w).max(0.75));
            let color = ln.child("solidFill").and_then(|f| self.drawing_color(f)).unwrap_or_else(|| "#000".to_owned());
            let _ = write!(css, "border:{width:.1}px solid {color};");
        }
        match sp_pr.and_then(|s| s.child("prstGeom")).and_then(|g| g.attr("prst")) {
            Some("ellipse") => css.push_str("border-radius:50%;"),
            Some("roundRect") => css.push_str("border-radius:12px;"),
            _ => {}
        }
        if let Some(body) = shape.child("bodyPr") {
            let pad = |a: &str, d: f32| num(body, a).map_or(d, emu);
            let _ = write!(
                css,
                "padding:{:.1}px {:.1}px {:.1}px {:.1}px;",
                pad("tIns", 4.8),
                pad("rIns", 9.6),
                pad("bIns", 4.8),
                pad("lIns", 9.6)
            );
            match body.attr("anchor") {
                Some("ctr") => css.push_str("display:inline-flex;flex-direction:column;justify-content:center;"),
                Some("b") => css.push_str("display:inline-flex;flex-direction:column;justify-content:flex-end;"),
                _ => {}
            }
        }
        let _ = write!(out, "<span class=\"ez-box\" style=\"{css}\">");
        if let Some(content) = shape.child("txbx").and_then(|t| t.child("txbxContent")) {
            let saved = (self.last_style.take(), self.last_contextual);
            self.table_depth += 1;
            self.blocks(content, None, out)?;
            self.table_depth -= 1;
            (self.last_style, self.last_contextual) = saved;
        }
        out.push_str("</span>");
        Ok(())
    }

    fn drawing_color(&self, fill: &Node) -> Option<String> {
        let c = fill.children.first()?;
        let base = match c.name() {
            "srgbClr" => c.attr("val").and_then(hex6),
            "schemeClr" => {
                let name = match c.attr("val")? {
                    "tx1" => "dk1",
                    "bg1" => "lt1",
                    "tx2" => "dk2",
                    "bg2" => "lt2",
                    other => other,
                };
                self.theme.colors.get(name).map(|argb| format!("#{:06x}", argb & 0xFF_FFFF))
            }
            _ => None,
        }?;
        // Lighter and darker variants, as Word's theme colours use.
        let mut rgb = [1, 3, 5].map(|i| u8::from_str_radix(&base[i..i + 2], 16).unwrap_or(0) as f32);
        for m in &c.children {
            let v = num(m, "val").unwrap_or(100_000.0) / 100_000.0;
            match m.name() {
                "lumMod" | "shade" => rgb = rgb.map(|x| x * v),
                "lumOff" => rgb = rgb.map(|x| x + 255.0 * v),
                "tint" => rgb = rgb.map(|x| 255.0 - (255.0 - x) * v),
                _ => {}
            }
        }
        let [r, g, b] = rgb.map(|x| x.round().clamp(0.0, 255.0) as u32);
        Some(format!("#{r:02x}{g:02x}{b:02x}"))
    }

    fn vml(&mut self, node: &Node, out: &mut String) -> Result<()> {
        for shape in node.children.iter() {
            if matches!(shape.name(), "shapetype") {
                continue;
            }
            if shape.attr("hr").is_some_and(|v| v == "t" || v == "true") {
                out.push_str("<hr style=\"border:none;border-top:1px solid #a0a0a0;margin:4px 0\">");
                continue;
            }
            let (w, h) = vml_size(shape.attr("style").unwrap_or(""));
            let style = format!("width:{w:.1}px;height:{h:.1}px;");
            if let Some(src) = self.picture_src(shape.child("imagedata").and_then(|i| i.prefixed_attr("id"))) {
                let _ = write!(
                    out,
                    "<img class=\"ez-img\" loading=\"lazy\" decoding=\"async\" src=\"{}\" style=\"{style}\">",
                    esc_attr(&src)
                );
            } else if let Some(content) = shape.child("textbox").and_then(|t| t.child("txbxContent")) {
                let mut css = style.clone();
                if let Some(fill) = shape.attr("fillcolor").and_then(|f| hex6(f.trim_start_matches('#'))) {
                    let _ = write!(css, "background:{fill};");
                }
                if shape.attr("stroked") != Some("f") {
                    css.push_str("border:1px solid #000;");
                }
                let _ = write!(out, "<span class=\"ez-box\" style=\"{css}padding:4px 8px\">");
                self.table_depth += 1;
                self.blocks(content, None, out)?;
                self.table_depth -= 1;
                out.push_str("</span>");
            } else if shape.attr("fillcolor").is_some() {
                let fill = shape
                    .attr("fillcolor")
                    .and_then(|f| hex6(f.trim_start_matches('#')))
                    .unwrap_or_else(|| "#fff".to_owned());
                let radius = if shape.name() == "oval" { "border-radius:50%;" } else { "" };
                let _ = write!(out, "<span class=\"ez-box\" style=\"{style}background:{fill};{radius}\"></span>");
            }
        }
        Ok(())
    }

    // ---- tables

    fn table(&mut self, tbl: &Node, out: &mut String) -> Result<()> {
        if self.table_depth >= MAX_TABLE_DEPTH {
            return Ok(());
        }
        self.table_depth += 1;
        let result = self.table_inner(tbl, out);
        self.table_depth -= 1;
        self.last_style = None;
        self.last_contextual = false;
        result
    }

    fn table_inner(&mut self, tbl: &Node, out: &mut String) -> Result<()> {
        let tbl_pr = tbl.child("tblPr");
        let style_id = tbl_pr.and_then(|p| p.child("tblStyle")).and_then(|s| s.attr("val")).map(str::to_owned);
        let (whole, conditional, band_rows, band_cols) = self.styles.table_style(style_id.as_deref());
        let own_cell = tbl_pr.map(|p| parse_cell_props(p, &self.theme)).unwrap_or_default();
        let table_cell = own_cell.over(&whole.cell);
        let look = tbl_pr.and_then(|p| p.child("tblLook"));
        let look_flag = |attr: &str, bit: u32, default: bool| -> bool {
            match look {
                Some(l) => match l.attr(attr) {
                    Some(v) => v == "1" || v == "true",
                    None => {
                        l.attr("val").and_then(|v| u32::from_str_radix(v, 16).ok()).map_or(default, |m| m & bit != 0)
                    }
                },
                None => default,
            }
        };
        let first_row = look_flag("firstRow", 0x20, true);
        let last_row = look_flag("lastRow", 0x40, false);
        let first_col = look_flag("firstColumn", 0x80, true);
        let last_col = look_flag("lastColumn", 0x100, false);
        let no_hband = look_flag("noHBand", 0x200, false);
        let no_vband = look_flag("noVBand", 0x400, true);

        let grid: Vec<f32> = tbl
            .child("tblGrid")
            .map(|g| g.children_named("gridCol").map(|c| num(c, "w").map_or(0.0, twips)).collect())
            .unwrap_or_default();
        let rows: Vec<&Node> = tbl.children_named("tr").collect();

        // Cells by grid position, for spans and vertical merges.
        struct CellAt<'a> {
            node: &'a Node,
            col: usize,
            span: usize,
            merge: Option<bool>,
        }
        let mut layout: Vec<Vec<CellAt>> = Vec::new();
        for row in &rows {
            let mut col = 0;
            let mut cells = Vec::new();
            if let Some(before) = row.child("trPr").and_then(|p| p.child("gridBefore")).and_then(|g| num(g, "val")) {
                col += before as usize;
            }
            for tc in row.children.iter().filter(|c| matches!(c.name(), "tc" | "sdt" | "customXml")) {
                let tc = if tc.name() == "tc" { tc } else { tc.find("tc").unwrap_or(tc) };
                let pr = tc.child("tcPr");
                let span = pr
                    .and_then(|p| p.child("gridSpan"))
                    .and_then(|g| num(g, "val"))
                    .map_or(1, |v| v.clamp(1.0, 64.0) as usize);
                let merge = pr.and_then(|p| p.child("vMerge")).map(|m| m.attr("val") == Some("restart"));
                cells.push(CellAt { node: tc, col, span, merge });
                col += span;
            }
            layout.push(cells);
        }
        let columns = layout.iter().map(|r| r.last().map_or(0, |c| c.col + c.span)).max().unwrap_or(0).max(grid.len());

        // Table width and placement.
        let mut css = String::from("table-layout:fixed;");
        let total: f32 = grid.iter().sum();
        let width = tbl_pr.and_then(|p| p.child("tblW")).and_then(|w| match w.attr("type") {
            Some("pct") => num(w, "w")
                .map(|v| format!("{:.2}%", if w.attr("w").is_some_and(|s| s.ends_with('%')) { v } else { v / 50.0 })),
            Some("dxa") | None => num(w, "w").filter(|v| *v > 0.0).map(|v| format!("{:.1}px", twips(v))),
            _ => None,
        });
        match width {
            Some(w) => {
                let _ = write!(css, "width:{w};");
            }
            None if total > 0.0 => {
                let _ = write!(css, "width:{total:.1}px;");
            }
            None => css.push_str("width:100%;"),
        }
        match tbl_pr.and_then(|p| p.child("jc")).and_then(|j| j.attr("val")) {
            Some("center") => css.push_str("margin-left:auto;margin-right:auto;"),
            Some("right" | "end") => css.push_str("margin-left:auto;"),
            _ => {
                if let Some(ind) = tbl_pr.and_then(|p| p.child("tblInd")).and_then(|i| num(i, "w")) {
                    let _ = write!(css, "margin-left:{:.1}px;", twips(ind));
                }
            }
        }
        if let Some(fill) = &table_cell.shading {
            let _ = write!(css, "background:{fill};");
        }
        let _ = write!(out, "<table class=\"ez-t\" style=\"{css}\">");
        if !grid.is_empty() {
            out.push_str("<colgroup>");
            for w in &grid {
                let _ = write!(out, "<col style=\"width:{w:.1}px\">");
            }
            out.push_str("</colgroup>");
        }
        out.push_str("<tbody>");
        let row_count = rows.len();
        // Banding counts rows below the heading row.
        let mut band_index = 0u32;
        for (ri, row) in rows.iter().enumerate() {
            let tr_pr = row.child("trPr");
            let header = tr_pr.and_then(|p| p.child("tblHeader")).is_some_and(on);
            let height = tr_pr.and_then(|p| p.child("trHeight")).and_then(|h| num(h, "val")).map(twips);
            let is_first = ri == 0 && first_row;
            let is_last = ri + 1 == row_count && last_row && row_count > 1;
            let _ = write!(
                out,
                "<tr{}",
                if header || (ri == 0 && first_row && tr_pr.is_some_and(|p| p.child("tblHeader").is_some())) {
                    " class=\"ez-hdr\""
                } else {
                    ""
                }
            );
            if let Some(h) = height {
                let _ = write!(out, " style=\"height:{h:.1}px\"");
            }
            out.push('>');
            let row_band = if is_first || is_last { None } else { Some((band_index / band_rows) % 2 == 0) };
            if !is_first && !is_last {
                band_index += 1;
            }
            for cell in &layout[ri] {
                if cell.merge == Some(false) {
                    continue;
                }
                let mut rowspan = 1;
                if cell.merge == Some(true) {
                    for next in layout.iter().skip(ri + 1) {
                        if next.iter().any(|c| c.col == cell.col && c.merge == Some(false)) {
                            rowspan += 1;
                        } else {
                            break;
                        }
                    }
                }
                // Conditional parts in rising precedence.
                let mut cond = whole.clone();
                let apply = |name: &str, cond: &mut Conditional| {
                    if let Some(c) = conditional.get(name) {
                        *cond = c.over(cond);
                    }
                };
                if !no_hband && let Some(odd) = row_band {
                    apply(if odd { "band1Horz" } else { "band2Horz" }, &mut cond);
                }
                let is_first_col = cell.col == 0 && first_col;
                let is_last_col = cell.col + cell.span >= columns && last_col;
                if !no_vband && !is_first_col && !is_last_col {
                    let band = (cell.col as u32 / band_cols) % 2 == 0;
                    apply(if band { "band1Vert" } else { "band2Vert" }, &mut cond);
                }
                if is_first_col {
                    apply("firstCol", &mut cond);
                }
                if is_last_col {
                    apply("lastCol", &mut cond);
                }
                if is_first {
                    apply("firstRow", &mut cond);
                }
                if is_last {
                    apply("lastRow", &mut cond);
                }
                let own = cell.node.child("tcPr").map(|p| parse_cell_props(p, &self.theme)).unwrap_or_default();
                let props = own.over(&cond.cell.over(&table_cell));
                let mut td = String::new();
                let edge = |i: usize| -> Option<Border> {
                    // Own and conditional edges first, then the table's outer or inside lines.
                    let outer = match i {
                        0 if ri == 0 => 0,
                        0 => 4,
                        2 if ri + rowspan >= row_count => 2,
                        2 => 4,
                        1 if cell.col == 0 => 1,
                        1 => 5,
                        3 if cell.col + cell.span >= columns => 3,
                        _ => 5,
                    };
                    own.borders[i]
                        .clone()
                        .or_else(|| cond.cell.borders[i].clone())
                        .or_else(|| table_cell.borders[outer].clone())
                        .flatten()
                };
                for (i, side) in ["top", "left", "bottom", "right"].iter().enumerate() {
                    if let Some(b) = edge(i) {
                        let _ = write!(td, "border-{side}:{:.1}px {} {};", b.width, b.style, b.color);
                    }
                }
                let m = props.margins;
                let _ = write!(
                    td,
                    "padding:{:.1}px {:.1}px {:.1}px {:.1}px;",
                    m[0].unwrap_or(0.0),
                    m[3].unwrap_or(7.2),
                    m[2].unwrap_or(0.0),
                    m[1].unwrap_or(7.2)
                );
                if let Some(fill) = &props.shading {
                    let _ = write!(td, "background:{fill};");
                }
                match props.valign.as_deref() {
                    Some("center") => td.push_str("vertical-align:middle;"),
                    Some("bottom") => td.push_str("vertical-align:bottom;"),
                    _ => {}
                }
                let _ = write!(out, "<td style=\"{td}\"");
                if cell.span > 1 {
                    let _ = write!(out, " colspan=\"{}\"", cell.span);
                }
                if rowspan > 1 {
                    let _ = write!(out, " rowspan=\"{rowspan}\"");
                }
                out.push('>');
                let ctx = CellContext { para: cond.para.clone(), run: cond.run.clone() };
                self.last_style = None;
                self.last_contextual = false;
                self.blocks(cell.node, Some(&ctx), out)?;
                out.push_str("</td>");
            }
            out.push_str("</tr>");
        }
        out.push_str("</tbody></table>");
        Ok(())
    }

    // ---- maths

    fn math(&self, node: &Node, out: &mut String) {
        out.push_str("<span class=\"ez-math\">");
        self.math_inner(node, out);
        out.push_str("</span>");
    }

    fn math_inner(&self, node: &Node, out: &mut String) {
        for c in &node.children {
            match c.name() {
                "t" => esc(out, &c.text),
                "f" => {
                    out.push_str("<span class=\"ez-frac\"><span>");
                    if let Some(n) = c.child("num") {
                        self.math_inner(n, out);
                    }
                    out.push_str("</span><span>");
                    if let Some(d) = c.child("den") {
                        self.math_inner(d, out);
                    }
                    out.push_str("</span></span>");
                }
                "sSup" | "sSub" | "sSubSup" => {
                    if let Some(e) = c.child("e") {
                        self.math_inner(e, out);
                    }
                    if let Some(s) = c.child("sub") {
                        out.push_str("<sub>");
                        self.math_inner(s, out);
                        out.push_str("</sub>");
                    }
                    if let Some(s) = c.child("sup") {
                        out.push_str("<sup>");
                        self.math_inner(s, out);
                        out.push_str("</sup>");
                    }
                }
                "rad" => {
                    out.push('√');
                    out.push('(');
                    if let Some(e) = c.child("e") {
                        self.math_inner(e, out);
                    }
                    out.push(')');
                }
                "d" => {
                    let pr = c.child("dPr");
                    let open = pr.and_then(|p| p.child("begChr")).and_then(|b| b.attr("val")).unwrap_or("(");
                    let close = pr.and_then(|p| p.child("endChr")).and_then(|b| b.attr("val")).unwrap_or(")");
                    esc(out, open);
                    for (i, e) in c.children_named("e").enumerate() {
                        if i > 0 {
                            out.push(',');
                        }
                        self.math_inner(e, out);
                    }
                    esc(out, close);
                }
                "nary" => {
                    let chr = c.child("naryPr").and_then(|p| p.child("chr")).and_then(|b| b.attr("val")).unwrap_or("∫");
                    esc(out, chr);
                    for part in ["sub", "sup"] {
                        if let Some(s) = c.child(part) {
                            let _ = write!(out, "<{part}>");
                            self.math_inner(s, out);
                            let _ = write!(out, "</{part}>");
                        }
                    }
                    if let Some(e) = c.child("e") {
                        self.math_inner(e, out);
                    }
                }
                "rPr" | "ctrlPr" | "fPr" | "radPr" | "dPr" | "naryPr" | "sSupPr" | "sSubPr" | "oMathParaPr" => {}
                _ => self.math_inner(c, out),
            }
        }
    }
}

fn hyperlink_target(instr: &str) -> Option<String> {
    let rest = instr.trim_start().get("HYPERLINK".len()..)?.trim();
    let anchor = rest.contains("\\l");
    let quoted = rest
        .split('"')
        .nth(1)
        .map(str::to_owned)
        .or_else(|| rest.split_whitespace().find(|w| !w.starts_with('\\')).map(str::to_owned))?;
    if anchor {
        return Some(format!("#bm-{quoted}"));
    }
    is_safe_link(&quoted).then_some(quoted)
}

/// `width:120pt;height:40pt` and the like, in pixels.
fn vml_size(style: &str) -> (f32, f32) {
    let mut w = 96.0;
    let mut h = 96.0;
    for decl in style.split(';') {
        let Some((k, v)) = decl.split_once(':') else { continue };
        let v = v.trim();
        let value = v
            .strip_suffix("pt")
            .and_then(|n| n.parse::<f32>().ok())
            .map(|p| p * 4.0 / 3.0)
            .or_else(|| v.strip_suffix("in").and_then(|n| n.parse::<f32>().ok()).map(|i| i * 96.0))
            .or_else(|| v.strip_suffix("px").and_then(|n| n.parse::<f32>().ok()));
        match (k.trim(), value) {
            ("width", Some(x)) => w = x,
            ("height", Some(x)) => h = x,
            _ => {}
        }
    }
    (w.clamp(1.0, 4000.0), h.clamp(1.0, 6000.0))
}

/// Floating objects: around the text by side, or on lines of their own.
fn anchor_css(anchor: &Node) -> String {
    let wrap = anchor.children.iter().map(|c| c.name()).find(|n| n.starts_with("wrap")).unwrap_or("wrapSquare");
    let h = anchor.child("positionH");
    let align = h.and_then(|p| p.child("align")).map(|a| a.text.trim().to_owned());
    let offset = h.and_then(|p| p.child("posOffset")).and_then(|o| o.text.trim().parse::<f32>().ok()).map_or(0.0, emu);
    let dist = |a: &str| num(anchor, a).map_or(0.0, emu);
    let side = match align.as_deref() {
        Some("right" | "outside") => "right",
        Some("center") => "center",
        Some(_) => "left",
        None if offset > 300.0 => "right",
        None => "left",
    };
    match wrap {
        "wrapTopAndBottom" => match side {
            "center" => "display:block;margin:6px auto;".to_owned(),
            "right" => "display:block;margin:6px 0 6px auto;".to_owned(),
            _ => format!("display:block;margin:6px 0 6px {:.1}px;", offset.max(0.0)),
        },
        "wrapNone" => {
            let behind = anchor.attr("behindDoc").is_some_and(|v| v == "1" || v == "true");
            let v = anchor.child("positionV");
            let top =
                v.and_then(|p| p.child("posOffset")).and_then(|o| o.text.trim().parse::<f32>().ok()).map_or(0.0, emu);
            let v_para = v.and_then(|p| p.attr("relativeFrom")).is_none_or(|r| matches!(r, "paragraph" | "line"));
            format!(
                "position:absolute;left:{offset:.1}px;top:{:.1}px;z-index:{};",
                if v_para { top } else { 0.0 },
                if behind { 0 } else { 2 }
            )
        }
        _ => match side {
            "right" => format!(
                "float:right;margin:{:.1}px 0 {:.1}px {:.1}px;",
                dist("distT"),
                dist("distB"),
                dist("distL").max(6.0)
            ),
            "center" => "display:block;margin:6px auto;".to_owned(),
            _ => format!(
                "float:left;margin:{:.1}px {:.1}px {:.1}px 0;",
                dist("distT"),
                dist("distR").max(6.0),
                dist("distB")
            ),
        },
    }
}

fn run_css(out: &mut String, p: &RunProps) {
    if let Some(font) = &p.font {
        let _ = write!(out, "font-family:{};", font_stack(font).replace('"', "&quot;"));
    }
    if let Some(size) = p.size {
        let size = if matches!(p.vert.as_deref(), Some("superscript" | "subscript")) { size * 0.65 } else { size };
        let _ = write!(out, "font-size:{size:.1}pt;");
    }
    if p.bold == Some(true) {
        out.push_str("font-weight:bold;");
    } else if p.bold == Some(false) {
        out.push_str("font-weight:normal;");
    }
    if p.italic == Some(true) {
        out.push_str("font-style:italic;");
    } else if p.italic == Some(false) {
        out.push_str("font-style:normal;");
    }
    match p.color.as_deref() {
        Some("auto") => out.push_str("color:#000;"),
        Some(c) => {
            let _ = write!(out, "color:{c};");
        }
        None => {}
    }
    let mut lines = Vec::new();
    let mut line_style = "solid";
    if let Some(u) = p.underline.as_deref().filter(|u| *u != "none") {
        lines.push("underline");
        line_style = match u {
            "double" => "double",
            "dotted" | "dottedHeavy" => "dotted",
            "dash" | "dashLong" | "dashedHeavy" | "dotDash" | "dotDotDash" => "dashed",
            "wave" | "wavyHeavy" | "wavyDouble" => "wavy",
            _ => "solid",
        };
    }
    if p.strike == Some(true) || p.dstrike == Some(true) {
        lines.push("line-through");
        if p.dstrike == Some(true) && lines.len() == 1 {
            line_style = "double";
        }
    }
    if !lines.is_empty() {
        let _ = write!(out, "text-decoration:{} {line_style};", lines.join(" "));
        if let Some(c) = &p.underline_color {
            let _ = write!(out, "text-decoration-color:{c};");
        }
    }
    if let Some(bg) = p.highlight.as_deref().or(p.shading.as_deref()) {
        let _ = write!(out, "background:{bg};");
    }
    match p.vert.as_deref() {
        Some("superscript") => out.push_str("vertical-align:super;"),
        Some("subscript") => out.push_str("vertical-align:sub;"),
        _ => {}
    }
    if p.caps == Some(true) {
        out.push_str("text-transform:uppercase;");
    }
    if p.small_caps == Some(true) {
        out.push_str("font-variant:small-caps;");
    }
    if let Some(s) = p.spacing.filter(|s| s.abs() > 0.01) {
        let _ = write!(out, "letter-spacing:{s:.2}px;");
    }
}

fn paragraph_css(out: &mut String, p: &ParaProps, mark: &RunProps, no_space_before: bool, contextual: bool) {
    // The paragraph mark's font sets the size of an empty line and the line height base.
    run_css(
        out,
        &RunProps {
            underline: None,
            strike: None,
            dstrike: None,
            highlight: None,
            shading: None,
            vert: None,
            ..mark.clone()
        },
    );
    let before = if no_space_before {
        0.0
    } else if p.before_auto == Some(true) {
        18.67
    } else {
        p.before.unwrap_or(0.0)
    };
    let _ = contextual;
    let after = if p.after_auto == Some(true) { 18.67 } else { p.after.unwrap_or(0.0) };
    let _ = write!(out, "margin-top:{before:.1}px;margin-bottom:{after:.1}px;");
    if let Some(l) = p.left.filter(|l| l.abs() > 0.01) {
        let _ = write!(out, "margin-left:{l:.1}px;");
    }
    if let Some(r) = p.right.filter(|r| r.abs() > 0.01) {
        let _ = write!(out, "margin-right:{r:.1}px;");
    }
    if let Some(f) = p.first.filter(|f| f.abs() > 0.01) {
        let _ = write!(out, "text-indent:{f:.1}px;");
    }
    match p.align.as_deref() {
        Some("center") => out.push_str("text-align:center;"),
        Some("right" | "end") => out.push_str("text-align:right;"),
        Some("both" | "distribute" | "lowKashida" | "mediumKashida" | "highKashida" | "thaiDistribute") => {
            out.push_str("text-align:justify;")
        }
        _ => out.push_str("text-align:left;"),
    }
    let font = mark.font.as_deref().unwrap_or("Times New Roman");
    let size_px = mark.size.unwrap_or(10.0) * 4.0 / 3.0;
    match &p.line {
        Some((line, rule)) if rule == "exact" => {
            let _ = write!(out, "line-height:{:.1}px;", twips(*line));
        }
        Some((line, rule)) if rule == "atLeast" => {
            let at_least = twips(*line);
            let _ = write!(out, "line-height:{:.1}px;", at_least.max(size_px * single_line(font)));
        }
        Some((line, _)) => {
            let _ = write!(out, "line-height:{:.3};", single_line(font) * (line / 240.0).clamp(0.5, 10.0));
        }
        None => {
            let _ = write!(out, "line-height:{:.3};", single_line(font));
        }
    }
    if let Some(bg) = &p.shading {
        let _ = write!(out, "background:{bg};");
    }
    for (i, side) in ["top", "left", "bottom", "right"].iter().enumerate() {
        if let Some(Some(b)) = &p.borders[i] {
            let _ =
                write!(out, "border-{side}:{:.1}px {} {};padding-{side}:{:.1}px;", b.width, b.style, b.color, b.space);
        }
    }
}

// ------------------------------------------------------------ entry point

/// Reads a part aside, so the text stream search pulls from carries on.
fn read_dom(parts: &mut Parts, path: &str) -> Result<Option<Node>> {
    let bytes = parts.aside(|archive| {
        let limit = archive.limits().max_part_bytes;
        match archive.find(path) {
            Some(e) if !e.is_dir && e.size <= limit => archive.read_to_vec(path).map(Some),
            _ => Ok(None),
        }
    })?;
    let Some(bytes) = bytes else { return Ok(None) };
    dom::parse(&bytes, &parts.cancel())
}

/// Reads an optional part; anything but cancellation leaves it out.
fn optional_dom(parts: &mut Parts, path: Option<String>) -> Result<Option<Node>> {
    let Some(path) = path else { return Ok(None) };
    match read_dom(parts, &path) {
        Err(e) if e.kind == document_core::ErrorKind::Cancelled => Err(e),
        Err(_) => Ok(None),
        Ok(n) => Ok(n),
    }
}

fn rel_map(rels: Vec<Relationship>) -> HashMap<String, Relationship> {
    rels.into_iter().map(|r| (r.id.clone(), r)).collect()
}

fn notes_of(root: Option<Node>, item: &str) -> HashMap<String, Rc<Node>> {
    let Some(root) = root else { return HashMap::new() };
    root.children
        .into_iter()
        .filter(|n| n.name() == item && matches!(n.attr("type"), None | Some("normal")))
        .filter_map(|n| Some((n.attr("id")?.to_owned(), Rc::new(n))))
        .collect()
}

/// The whole document as one HTML page of print-layout pages.
pub(crate) fn render(parts: &mut Parts, main: &str) -> Result<Vec<u8>> {
    let cancel = parts.cancel();
    let rels = parts.aside(|archive| relationships(archive, main))?;
    let theme_path = target_of(&rels, "theme");
    let theme = optional_dom(parts, theme_path)?.map(|t| Theme::read(&t)).unwrap_or_default();
    let styles_path = target_of(&rels, "styles");
    let styles = optional_dom(parts, styles_path)?.map(|s| Styles::read(&s, &theme)).unwrap_or_default();
    let numbering_path = target_of(&rels, "numbering");
    let numbering = optional_dom(parts, numbering_path)?.map(|n| Numbering::read(&n, &theme)).unwrap_or_default();
    let settings_path = target_of(&rels, "settings");
    let settings = optional_dom(parts, settings_path)?;
    let even_odd = settings.as_ref().and_then(|s| s.child("evenAndOddHeaders")).is_some_and(on);
    let tab_stop = settings
        .as_ref()
        .and_then(|s| s.child("defaultTabStop"))
        .and_then(|t| num(t, "val"))
        .map_or(48.0, twips)
        .max(4.0);
    let footnotes_path = target_of(&rels, "footnotes");
    let footnotes = notes_of(optional_dom(parts, footnotes_path)?, "footnote");
    let endnotes_path = target_of(&rels, "endnotes");
    let endnotes = notes_of(optional_dom(parts, endnotes_path)?, "endnote");

    let Some(document) = read_dom(parts, main)? else {
        return Err(EngineError::malformed("the document part is missing"));
    };
    let Some(body) = document.child("body") else {
        return Err(EngineError::malformed("the document has no body"));
    };
    let background = document.child("background").and_then(|b| color_of(b, "color", &theme));

    let rel_by_id = rel_map(rels);
    let mut r = Renderer {
        styles,
        numbering,
        theme,
        rels: Rc::new(rel_by_id),
        footnotes,
        endnotes,
        note_bodies: String::new(),
        endnote_bodies: String::new(),
        note_count: 0,
        in_note: None,
        fields: Vec::new(),
        tab_stop,
        cancel: cancel.clone(),
        steps: 0,
        table_depth: 0,
        last_style: None,
        last_contextual: false,
        last_end: None,
    };

    // Split the body into sections: a paragraph carrying `sectPr` ends one.
    let mut sections: Vec<(Section, String)> = Vec::new();
    let mut flow = String::new();
    let mut previous = Section::default();
    for child in &body.children {
        if child.name() == "sectPr" {
            continue;
        }
        r.block(child, None, &mut flow)?;
        if let Some(sect) = child.child("pPr").and_then(|p| p.child("sectPr")) {
            let section = parse_section(sect, &previous);
            previous = section.clone();
            sections.push((section, std::mem::take(&mut flow)));
        }
    }
    let last = body.child("sectPr").map_or_else(|| previous.clone(), |s| parse_section(s, &previous));
    sections.push((last, flow));
    if !r.endnote_bodies.is_empty()
        && let Some((_, flow)) = sections.last_mut()
    {
        let _ = write!(flow, "<div class=\"ez-endnotes\"><div class=\"ez-fnsep\"></div>{}</div>", r.endnote_bodies);
    }

    // Headers and footers, each rendered once with its own relationships.
    let mut part_html: HashMap<String, String> = HashMap::new();
    let main_rels = r.rels.clone();
    for (section, _) in &sections {
        for id in section.headers.values().chain(section.footers.values()) {
            let Some(path) = main_rels.get(id).and_then(|rel| rel.inside.clone()) else { continue };
            if part_html.contains_key(&path) {
                continue;
            }
            let Some(root) = optional_dom(parts, Some(path.clone()))? else { continue };
            let part_rels = match parts.aside(|archive| relationships(archive, &path)) {
                Ok(rels) => rel_map(rels),
                Err(e) if e.kind == document_core::ErrorKind::Cancelled => return Err(e),
                Err(_) => HashMap::new(),
            };
            r.rels = Rc::new(part_rels);
            r.last_style = None;
            let mut html = String::new();
            r.blocks(&root, None, &mut html)?;
            part_html.insert(path, html);
        }
    }
    r.rels = main_rels.clone();

    // The widest page, so every page centres in the view (a landscape section is wider).
    let first_width = sections.iter().map(|s| s.0.width).fold(0.0f32, f32::max).max(96.0);
    let mut out = String::with_capacity(sections.iter().map(|s| s.1.len()).sum::<usize>() + 32 * 1024);
    let _ = write!(
        out,
        "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width={:.0}\"><style>{FONT_FACES}{BASE_CSS}</style></head><body><div id=\"ez-doc\">",
        first_width + 32.0
    );
    for (section, flow) in &sections {
        let [mt, mr, mb, ml] = section.margins;
        let _ = write!(
            out,
            "<div class=\"ez-section\" data-w=\"{:.1}\" data-h=\"{:.1}\" data-mt=\"{mt:.1}\" data-mr=\"{mr:.1}\" data-mb=\"{mb:.1}\" data-ml=\"{ml:.1}\" data-hd=\"{:.1}\" data-fd=\"{:.1}\" data-cols=\"{}\" data-gap=\"{:.1}\"",
            section.width, section.height, section.header, section.footer, section.cols, section.gap
        );
        if section.title_page {
            out.push_str(" data-titlepg=\"1\"");
        }
        if even_odd {
            out.push_str(" data-evenodd=\"1\"");
        }
        if section.continuous {
            out.push_str(" data-continuous=\"1\"");
        }
        if let Some(start) = section.start {
            let _ = write!(out, " data-start=\"{start}\"");
        }
        if let Some(bg) = &background {
            let _ = write!(out, " data-bg=\"{bg}\"");
        }
        out.push('>');
        for (kind, map) in [("header", &section.headers), ("footer", &section.footers)] {
            for (ty, id) in map.iter() {
                let html = main_rels.get(id).and_then(|rel| rel.inside.as_ref()).and_then(|p| part_html.get(p));
                if let Some(html) = html {
                    let _ =
                        write!(out, "<template data-kind=\"{kind}\" data-type=\"{}\">{html}</template>", esc_attr(ty));
                }
            }
        }
        out.push_str("<div class=\"ez-flow\">");
        out.push_str(flow);
        out.push_str("</div></div>");
    }
    out.push_str("<div id=\"ez-footnotes\" style=\"display:none\">");
    out.push_str(&r.note_bodies);
    out.push_str("</div></div>");
    // Faces load only once text uses them, and the content is hidden until laid
    // out, so the faces the document names load first: lines must break in them.
    let _ = write!(
        out,
        "<script nonce=\"{SCRIPT_NONCE}\">(function(){{const html=document.body.innerHTML;const faces=[...document.fonts].filter(f=>html.indexOf(f.family.replace(/[\"']/g,''))>=0);Promise.all(faces.map(f=>f.load().catch(()=>null))).then(()=>document.fonts.ready).then(function(){{{PAGINATE_JS}}});}})();</script></body></html>"
    );
    cancel.check()?;
    Ok(out.into_bytes())
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use document_core::Limits;

    use crate::zip::testing::zip;

    const RELS: &[u8] = br#"<Relationships><Relationship Id="rId1" Type="http://x/officeDocument" Target="word/document.xml"/></Relationships>"#;

    fn page(body: &str, parts: &[(&str, &str)], rels: &str) -> String {
        let document = format!(r#"<w:document xmlns:w="w" xmlns:r="r"><w:body>{body}</w:body></w:document>"#);
        let doc_rels = format!("<Relationships>{rels}</Relationships>");
        let mut entries: Vec<(&str, &[u8])> = vec![
            ("[Content_Types].xml", b"<Types/>"),
            ("_rels/.rels", RELS),
            ("word/document.xml", document.as_bytes()),
            ("word/_rels/document.xml.rels", doc_rels.as_bytes()),
            ("word/media/a.png", b"\x89PNG"),
        ];
        entries.extend(parts.iter().map(|(n, t)| (*n, t.as_bytes())));
        let mut session = crate::engine(Limits::default()).open(Cursor::new(zip(&entries))).unwrap();
        String::from_utf8(session.resource(super::PAGES_RESOURCE).unwrap()).unwrap()
    }

    const STYLES: &str = r#"<w:styles>
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:asciiTheme="minorHAnsi" w:hAnsiTheme="minorHAnsi"/><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="0"/></w:pPr><w:rPr><w:rFonts w:asciiTheme="majorHAnsi"/><w:color w:val="2F5496" w:themeColor="accent1" w:themeShade="BF"/><w:sz w:val="32"/></w:rPr></w:style>
<w:style w:type="character" w:styleId="Strong"><w:rPr><w:b/></w:rPr></w:style>
<w:style w:type="table" w:styleId="Grid"><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4" w:color="000000"/><w:insideH w:val="single" w:sz="4" w:color="000000"/></w:tblBorders></w:tblPr><w:tblStylePr w:type="firstRow"><w:rPr><w:b/></w:rPr><w:tcPr><w:shd w:val="clear" w:fill="4472C4"/></w:tcPr></w:tblStylePr></w:style>
</w:styles>"#;

    const THEME: &str = r#"<a:theme xmlns:a="a"><a:themeElements><a:clrScheme name="x"><a:dk1><a:srgbClr val="000000"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:accent1><a:srgbClr val="4472C4"/></a:accent1></a:clrScheme><a:fontScheme name="f"><a:majorFont><a:latin typeface="Calibri Light"/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/></a:minorFont></a:fontScheme></a:themeElements></a:theme>"#;

    const NUMBERING: &str = r#"<w:numbering><w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl><w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="%1.%2)"/></w:lvl></w:abstractNum><w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="&#xF0B7;"/><w:rPr><w:rFonts w:ascii="Symbol" w:hAnsi="Symbol"/></w:rPr></w:lvl></w:abstractNum><w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num><w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num><w:num w:numId="3"><w:abstractNumId w:val="0"/><w:lvlOverride w:ilvl="0"><w:startOverride w:val="5"/></w:lvlOverride></w:num></w:numbering>"#;

    fn rels_for(extra: &str) -> String {
        format!(
            r#"<Relationship Id="s" Type="http://x/styles" Target="styles.xml"/><Relationship Id="t" Type="http://x/theme" Target="theme/theme1.xml"/><Relationship Id="n" Type="http://x/numbering" Target="numbering.xml"/><Relationship Id="img" Type="http://x/image" Target="media/a.png"/><Relationship Id="web" Type="http://x/hyperlink" Target="https://example.com/?a=1&amp;b=2" TargetMode="External"/><Relationship Id="bad" Type="http://x/hyperlink" Target="javascript:alert(1)" TargetMode="External"/>{extra}"#
        )
    }

    fn standard(body: &str) -> String {
        page(
            body,
            &[("word/styles.xml", STYLES), ("word/theme/theme1.xml", THEME), ("word/numbering.xml", NUMBERING)],
            &rels_for(""),
        )
    }

    #[test]
    fn styles_theme_fonts_and_colours_resolve() {
        let html = standard(
            r#"<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Title &lt;script&gt;</w:t></w:r></w:p>
<w:p><w:r><w:rPr><w:rStyle w:val="Strong"/><w:highlight w:val="yellow"/><w:u w:val="double"/></w:rPr><w:t xml:space="preserve">bold </w:t></w:r><w:r><w:rPr><w:vertAlign w:val="superscript"/></w:rPr><w:t>2</w:t></w:r></w:p>"#,
        );
        assert!(html.contains("font-family:&quot;Calibri Light&quot;,EzCarlito,sans-serif"), "theme major font");
        assert!(html.contains("color:#2f5496;"), "the colour Word saved for accent1 shaded");
        assert!(html.contains("font-size:16.0pt"));
        assert!(html.contains("margin-top:16.0px;margin-bottom:0.0px;"), "heading spacing over the defaults");
        assert!(html.contains("Title &lt;script&gt;"), "text is escaped");
        assert!(
            html.contains("font-weight:bold;")
                && html.contains("background:#ffff00;")
                && html.contains("underline double")
        );
        assert!(html.contains("vertical-align:super;"));
        assert!(html.contains("line-height:1.317;"), "259/240 of Calibri's single line");
        assert_eq!(html.matches("<script").count(), 1, "only the engine's script");
    }

    #[test]
    fn lists_count_like_word() {
        let item = |num: u32, lvl: u32, text: &str| {
            format!(
                r#"<w:p><w:pPr><w:numPr><w:ilvl w:val="{lvl}"/><w:numId w:val="{num}"/></w:numPr></w:pPr><w:r><w:t>{text}</w:t></w:r></w:p>"#
            )
        };
        let body =
            [item(1, 0, "a"), item(1, 1, "b"), item(1, 1, "c"), item(1, 0, "d"), item(2, 0, "e"), item(3, 0, "f")]
                .concat();
        let html = standard(&body);
        let markers: Vec<&str> = html
            .match_indices("class=\"ez-mk\"")
            .map(|(i, _)| {
                let rest = &html[i..];
                let start = rest.find('>').unwrap() + 1;
                &rest[start..start + rest[start..].find('<').unwrap()]
            })
            .collect();
        assert_eq!(markers, ["1.", "1.a)", "1.b)", "2.", "•", "5."]);
        assert!(html.contains("margin-left:48.0px;") && html.contains("text-indent:-24.0px;"));
    }

    #[test]
    fn tables_merge_cells_and_apply_their_style() {
        let cell = |extra: &str, text: &str| {
            format!(r#"<w:tc><w:tcPr>{extra}</w:tcPr><w:p><w:r><w:t>{text}</w:t></w:r></w:p></w:tc>"#)
        };
        let body = format!(
            r#"<w:tbl><w:tblPr><w:tblStyle w:val="Grid"/><w:tblW w:w="5000" w:type="pct"/></w:tblPr><w:tblGrid><w:gridCol w:w="1500"/><w:gridCol w:w="1500"/><w:gridCol w:w="1500"/></w:tblGrid>
<w:tr><w:trPr><w:tblHeader/></w:trPr>{}{}</w:tr><w:tr>{}{}{}</w:tr><w:tr>{}{}{}</w:tr></w:tbl>"#,
            cell(r#"<w:gridSpan w:val="2"/>"#, "Head"),
            cell("", "H3"),
            cell(r#"<w:vMerge w:val="restart"/>"#, "Tall"),
            cell(r#"<w:shd w:val="clear" w:fill="FF0000"/>"#, "x"),
            cell("", "y"),
            cell("<w:vMerge/>", ""),
            cell("", "z"),
            cell("", "w"),
        );
        let html = standard(&body);
        assert!(html.contains("width:100.00%"));
        assert!(html.contains("colspan=\"2\"") && html.contains("rowspan=\"2\""));
        assert!(html.contains("class=\"ez-hdr\""));
        assert!(html.contains("background:#4472c4;"), "first row shading from the table style");
        assert!(html.contains("background:#ff0000;"));
        assert!(html.contains("<col style=\"width:100.0px\">"));
        assert_eq!(html.matches("<td").count(), 7, "the merged-away cell is skipped");
    }

    #[test]
    fn pictures_links_breaks_fields_and_notes() {
        let footnotes = r#"<w:footnotes><w:footnote w:type="separator" w:id="-1"><w:p/></w:footnote><w:footnote w:id="1"><w:p><w:r><w:footnoteRef/></w:r><w:r><w:t>A note</w:t></w:r></w:p></w:footnote></w:footnotes>"#;
        let body = r#"<w:p><w:r><w:drawing><wp:inline><wp:extent cx="952500" cy="476250"/><wp:docPr id="1" descr="Logo"/><a:graphic><a:graphicData><pic:pic><pic:blipFill><a:blip r:embed="img"/></pic:blipFill></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>
<w:p><w:hyperlink r:id="web"><w:r><w:t>web</w:t></w:r></w:hyperlink><w:hyperlink r:id="bad"><w:r><w:t>bad</w:t></w:r></w:hyperlink><w:r><w:br w:type="page"/><w:t>next</w:t></w:r></w:p>
<w:p><w:r><w:t>Page </w:t></w:r><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>9</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r><w:r><w:footnoteReference w:id="1"/></w:r></w:p>
<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708"/><w:cols w:num="2" w:space="720"/></w:sectPr>"#;
        let html = page(
            body,
            &[("word/footnotes.xml", footnotes)],
            &rels_for(r#"<Relationship Id="f" Type="http://x/footnotes" Target="footnotes.xml"/>"#),
        );
        assert!(html.contains(
            r#"<img class="ez-img" loading="lazy" decoding="async" src="res/word/media/a.png" alt="Logo" style="width:100.0px;height:50.0px;">"#
        ));
        assert!(html.contains(r#"<a href="https://example.com/?a=1&amp;b=2">"#));
        assert!(!html.contains("javascript:"));
        assert!(html.contains("<div class=\"ez-pb\"></div>"));
        assert!(
            html.contains("class=\"ez-fld-page\"") && !html.contains(">9<"),
            "live page number replaces the saved one"
        );
        assert!(html.contains("data-fn=\"1\"") && html.contains("id=\"ez-fn-1\"") && html.contains("A note"));
        assert!(html.contains("data-w=\"793.7\"") && html.contains("data-cols=\"2\""), "A4 in two columns");
        assert!(html.contains("<meta name=\"viewport\" content=\"width=826\">"));
    }

    #[test]
    fn headers_and_footers_get_their_own_pictures() {
        let header = r#"<w:hdr><w:p><w:r><w:drawing><wp:inline><wp:extent cx="9525" cy="9525"/><a:graphic><a:graphicData><pic:pic><a:blip r:embed="h1"/></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing><w:t>Header</w:t></w:r></w:p></w:hdr>"#;
        let body = r#"<w:p><w:r><w:t>Body</w:t></w:r></w:p><w:sectPr><w:headerReference w:type="default" r:id="hd"/><w:titlePg/></w:sectPr>"#;
        let html = page(
            body,
            &[
                ("word/header1.xml", header),
                (
                    "word/_rels/header1.xml.rels",
                    r#"<Relationships><Relationship Id="h1" Type="http://x/image" Target="media/a.png"/></Relationships>"#,
                ),
            ],
            &rels_for(r#"<Relationship Id="hd" Type="http://x/header" Target="header1.xml"/>"#),
        );
        assert!(html.contains("<template data-kind=\"header\" data-type=\"default\">"));
        assert!(html.contains("res/word/media/a.png") && html.contains("data-titlepg=\"1\""));
    }

    #[test]
    fn symbols_and_number_formats() {
        assert_eq!(super::symbol_char('\u{F0B7}', Some("Symbol")), '•');
        assert_eq!(super::symbol_char('§', Some("Wingdings")), '▪');
        assert_eq!(super::format_number(27, "upperLetter"), "AA");
        assert_eq!(super::format_number(14, "lowerRoman"), "xiv");
        assert_eq!(super::format_number(3, "ordinal"), "3rd");
        assert_eq!(super::font_stack("Times New Roman"), "\"Times New Roman\",EzTinos,serif");
    }
}
