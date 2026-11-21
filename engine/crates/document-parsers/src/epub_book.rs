//! EPUB as a book: the reader page that shows the book's own chapters in
//! pages, like an e-reader, and the description it needs.
//!
//! [`MANIFEST_RESOURCE`] is JSON: title, author, reading direction, whether
//! the book is fixed-layout, the chapters in reading order with their sizes
//! (which weigh progress through the book), and the table of contents from
//! the EPUB 3 navigation document or, failing that, the EPUB 2 NCX.
//!
//! [`READER_RESOURCE`] is the reader page. It loads each chapter, as the
//! book wrote it, into a sandboxed frame that runs no scripts, and lays it
//! out in columns one screen wide, so each column is a page. Only the page's
//! own script runs (by nonce). It leaves two placeholders for the app, both
//! before the book's own JSON: `__EZTOKEN__`, a secret its messages carry,
//! and `__EZSTATE__`, the position and settings to open with, as JSON. Chapter files are asked for under `/ezbook/`, the reader's
//! fonts under `/ezfonts/`, and messages go to `/ezmsg`.

use std::collections::HashMap;

use document_core::{EngineError, Result};

use crate::dom::{self, Node};
use crate::ooxml::resolve;
use crate::stream::Parts;

pub(crate) const MANIFEST_RESOURCE: &str = "ezfiles:book.json";
pub(crate) const READER_RESOURCE: &str = "ezfiles:book.html";
/// Chapter files and everything they use: `ezfiles:book/<path in the archive>`.
pub(crate) const FILE_PREFIX: &str = "ezfiles:book/";

/// Entries kept from the table of contents; real books have hundreds at most.
const MAX_TOC: usize = 5000;
const MAX_TOC_DEPTH: usize = 6;
const MAX_TITLE_CHARS: usize = 300;

#[derive(Debug, Default, PartialEq)]
pub(crate) struct Book {
    pub title: String,
    pub author: String,
    pub language: String,
    pub rtl: bool,
    pub fixed: bool,
    /// Archive path and uncompressed size of each chapter, in reading order.
    pub chapters: Vec<(String, u64)>,
    /// Title, archive path with an optional `#fragment`, and depth from 0.
    pub toc: Vec<(String, String, usize)>,
}

/// Reads the package file named by `package`, and its table of contents.
pub(crate) fn read_book(parts: &mut Parts, package: &str) -> Result<Book> {
    let cancel = parts.cancel();
    let bytes = parts.file(package)?;
    let root = dom::parse(&bytes, &cancel)?.ok_or_else(|| EngineError::malformed("the e-book's package is empty"))?;
    let base = package.rsplit_once('/').map_or("", |(dir, _)| dir);
    let mut book = Book::default();

    if let Some(meta) = root.child("metadata") {
        book.title = clip(&text_of(meta.child("title")));
        book.author = clip(&text_of(meta.child("creator")));
        book.language = clip(&text_of(meta.child("language")));
        for m in meta.children_named("meta") {
            if m.attr("property") == Some("rendition:layout") && text_of(Some(m)).trim() == "pre-paginated" {
                book.fixed = true;
            }
            // EPUB 2 spelling, from Apple's books.
            if m.attr("name") == Some("fixed-layout") && m.attr("content") == Some("true") {
                book.fixed = true;
            }
        }
    }

    // id -> (path, media type, properties)
    let mut manifest: HashMap<&str, (String, &str, &str)> = HashMap::new();
    if let Some(items) = root.child("manifest") {
        for item in items.children_named("item") {
            if let (Some(id), Some(href)) = (item.attr("id"), item.attr("href")) {
                let path = resolve(base, href.split('#').next().unwrap_or(href));
                manifest
                    .insert(id, (path, item.attr("media-type").unwrap_or(""), item.attr("properties").unwrap_or("")));
            }
        }
    }

    let spine = root.child("spine");
    book.rtl = spine.and_then(|s| s.attr("page-progression-direction")) == Some("rtl");
    if let Some(spine) = spine {
        for itemref in spine.children_named("itemref") {
            if itemref.attr("linear") == Some("no") {
                continue;
            }
            let Some((path, kind, _)) = itemref.attr("idref").and_then(|id| manifest.get(id)) else { continue };
            if !matches!(*kind, "application/xhtml+xml" | "text/html") && !path.ends_with("html") {
                continue;
            }
            let size = parts.archive().find(path).map(|e| e.size);
            if let Some(size) = size
                && !book.chapters.iter().any(|(p, _)| p == path)
            {
                book.chapters.push((path.clone(), size));
            }
        }
    }
    if book.chapters.is_empty() {
        return Err(EngineError::malformed("the e-book has no readable chapters"));
    }

    // EPUB 3's navigation document, else EPUB 2's NCX.
    let nav = manifest.values().find(|(_, _, props)| props.split_whitespace().any(|p| p == "nav"));
    if let Some((path, _, _)) = nav
        && let Ok(bytes) = parts.file(path)
        && let Ok(Some(doc)) = dom::parse(&bytes, &cancel)
    {
        let dir = path.rsplit_once('/').map_or("", |(dir, _)| dir);
        if let Some(toc) = find_toc_nav(&doc) {
            if let Some(list) = toc.child("ol") {
                nav_list(list, dir, 0, &mut book.toc);
            }
        }
    }
    if book.toc.is_empty() {
        let ncx = spine
            .and_then(|s| s.attr("toc"))
            .and_then(|id| manifest.get(id))
            .or_else(|| manifest.values().find(|(_, kind, _)| *kind == "application/x-dtbncx+xml"));
        if let Some((path, _, _)) = ncx
            && let Ok(bytes) = parts.file(path)
            && let Ok(Some(doc)) = dom::parse(&bytes, &cancel)
        {
            let dir = path.rsplit_once('/').map_or("", |(dir, _)| dir);
            if let Some(map) = doc.child("navMap") {
                ncx_points(map, dir, 0, &mut book.toc);
            }
        }
    }
    // Entries pointing outside the book's chapters can't be opened.
    book.toc.retain(|(_, href, _)| {
        let path = href.split('#').next().unwrap_or(href);
        book.chapters.iter().any(|(p, _)| p == path)
    });
    Ok(book)
}

/// The `<nav epub:type="toc">`, or the first `<nav>` if none says so.
fn find_toc_nav(node: &Node) -> Option<&Node> {
    let mut first = None;
    let mut stack = vec![node];
    while let Some(n) = stack.pop() {
        if n.name() == "nav" {
            if n.attr("type").is_some_and(|t| t.split_whitespace().any(|t| t == "toc")) {
                return Some(n);
            }
            first.get_or_insert(n);
        }
        stack.extend(n.children.iter().rev());
    }
    first
}

fn nav_list(list: &Node, dir: &str, depth: usize, out: &mut Vec<(String, String, usize)>) {
    for li in list.children_named("li") {
        if out.len() >= MAX_TOC {
            return;
        }
        let link = li.children.iter().find(|c| matches!(c.name(), "a" | "span"));
        if let Some(a) = link {
            let title = clip(&text_of(Some(a)));
            if let Some(href) = a.attr("href").filter(|h| !h.contains(':'))
                && !title.is_empty()
            {
                out.push((title, resolve_href(dir, href), depth));
            }
        }
        if depth + 1 < MAX_TOC_DEPTH
            && let Some(sub) = li.child("ol")
        {
            nav_list(sub, dir, depth + 1, out);
        }
    }
}

fn ncx_points(parent: &Node, dir: &str, depth: usize, out: &mut Vec<(String, String, usize)>) {
    for point in parent.children_named("navPoint") {
        if out.len() >= MAX_TOC {
            return;
        }
        let title = clip(&text_of(point.child("navLabel").and_then(|l| l.child("text"))));
        if let Some(src) = point.child("content").and_then(|c| c.attr("src")).filter(|h| !h.contains(':'))
            && !title.is_empty()
        {
            out.push((title, resolve_href(dir, src), depth));
        }
        if depth + 1 < MAX_TOC_DEPTH {
            ncx_points(point, dir, depth + 1, out);
        }
    }
}

/// An archive path with its fragment kept as written.
fn resolve_href(dir: &str, href: &str) -> String {
    match href.split_once('#') {
        Some(("", fragment)) => format!("#{fragment}"),
        Some((path, fragment)) => format!("{}#{fragment}", resolve(dir, path)),
        None => resolve(dir, href),
    }
}

/// All text inside `node`, whitespace collapsed.
fn text_of(node: Option<&Node>) -> String {
    fn walk(node: &Node, out: &mut String) {
        out.push_str(&node.text);
        for child in &node.children {
            walk(child, out);
            out.push_str(&child.tail);
        }
    }
    let mut raw = String::new();
    if let Some(node) = node {
        walk(node, &mut raw);
    }
    raw.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn clip(text: &str) -> String {
    text.chars().take(MAX_TITLE_CHARS).collect()
}

/// The book as JSON for the app and the reader page.
pub(crate) fn manifest_json(book: &Book) -> String {
    let mut out = String::with_capacity(256 + book.chapters.len() * 64 + book.toc.len() * 96);
    out.push_str("{\"title\":");
    json_string(&book.title, &mut out);
    out.push_str(",\"author\":");
    json_string(&book.author, &mut out);
    out.push_str(",\"language\":");
    json_string(&book.language, &mut out);
    out.push_str(&format!(",\"rtl\":{},\"fixed\":{},\"chapters\":[", book.rtl, book.fixed));
    for (i, (path, size)) in book.chapters.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push_str("{\"href\":");
        json_string(path, &mut out);
        out.push_str(&format!(",\"size\":{size}}}"));
    }
    out.push_str("],\"toc\":[");
    for (i, (title, href, depth)) in book.toc.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push_str("{\"title\":");
        json_string(title, &mut out);
        out.push_str(",\"href\":");
        json_string(href, &mut out);
        out.push_str(&format!(",\"depth\":{depth}}}"));
    }
    out.push_str("]}");
    out
}

/// A JSON string that is also safe inside an HTML `<script>`.
fn json_string(text: &str, out: &mut String) {
    out.push('"');
    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '<' => out.push_str("\\u003c"),
            '>' => out.push_str("\\u003e"),
            '&' => out.push_str("\\u0026"),
            '\u{2028}' => out.push_str("\\u2028"),
            '\u{2029}' => out.push_str("\\u2029"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
}

/// The reader page, with the book filled in.
pub(crate) fn reader_page(book: &Book) -> String {
    let lang =
        if book.language.chars().all(|c| c.is_ascii_alphanumeric() || c == '-') { book.language.as_str() } else { "" };
    READER_HTML.replace("__EZLANG__", lang).replace("__EZBOOK__", &manifest_json(book))
}

const READER_HTML: &str = r#"<!doctype html>
<html lang="__EZLANG__"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<style>
html,body{margin:0;padding:0;height:100%;overflow:hidden;background:#fff}
#f{border:0;display:block;position:fixed;left:0;top:0;width:100vw;height:100vh;background:transparent;z-index:2}
#curlPreview{border:0;position:fixed;inset:0;width:100vw;height:100vh;pointer-events:none;visibility:hidden;z-index:1}
#shade{position:fixed;top:0;height:100%;pointer-events:none;opacity:0;z-index:3;background:linear-gradient(90deg,#0000,#0005 48%,#0000)}
#fold{position:fixed;top:-2%;height:104%;pointer-events:none;display:none;z-index:4;backface-visibility:hidden}
</style></head>
<body><iframe id="f" sandbox="allow-same-origin" title="Book"></iframe><div id="shade" aria-hidden="true"></div><div id="fold" aria-hidden="true"></div>
<script nonce="ezbook">
(() => {
'use strict';
// The app fills in the first two before the book, so nothing in the book can stand in for them.
const TOKEN = '__EZTOKEN__';
const START = __EZSTATE__;
const BOOK = __EZBOOK__;
const NS = 'http://www.w3.org/1999/xhtml';
const frame = document.getElementById('f');
const n = BOOK.chapters.length;
const sizes = BOOK.chapters.map(c => Math.max(1, c.size));
const before = [];
let total = 0;
for (const s of sizes) { before.push(total); total += s; }
const THEMES = {
  white: { bg: '#ffffff', fg: '#1b1b1b', link: '#1a56db', force: false },
  sepia: { bg: '#f4ecd8', fg: '#4a3b2a', link: '#8a4b0f', force: true },
  green: { bg: '#cfe5cb', fg: '#1d2b1d', link: '#1e5631', force: true },
  black: { bg: '#000000', fg: '#d2d2d2', link: '#8ab4f8', force: true },
};
const FONTS = {
  serif: '"Noto Serif", serif',
  book: 'EzCaladea, "Noto Serif", serif',
  sans: 'Roboto, "Noto Sans", sans-serif',
};
const MARKS = {
  yellow: 'rgba(255,205,0,.42)',
  green: 'rgba(52,199,89,.34)',
  blue: 'rgba(10,132,255,.30)',
  pink: 'rgba(255,55,135,.30)',
};
const RTL_LANGS = ['ar', 'he', 'iw', 'fa', 'ur', 'yi', 'ps', 'sd', 'ug', 'ckb', 'dv', 'syr'];
const MARGINS = [14, 26, 44];
const PAD_V = 14;
let S = (START && START.settings) || {};
let HL = (START && START.highlights) || [];
let BM = (START && START.bookmarks) || [];
let ch = -1, page = 0, pages = 1, ready = false, pending = null;
// Per chapter: vertical writing (pages run down), right-to-left text (pages run left),
// and whether the next page is to the left, for taps and swipes.
let V = false, R = false, BACK = BOOK.rtl;
let lastSel = null;
const shade = document.getElementById('shade');
const fold = document.getElementById('fold');
const motionQuery = matchMedia('(prefers-reduced-motion: reduce)');
let reducedMotion = !!(START && START.reducedMotion);
let turnFrame = 0, turning = false, dragging = false;
let curlPreview = null, curlReady = false, curlTried = false, curlTarget = 0;
let curlRenderedTarget = null, curlVisible = false, curlTheme = null, curlRight = null;
let curlProgress = 0, fingerY = 0.5, committedOffset = null;
function noMotion() { return reducedMotion || motionQuery.matches; }
function releaseCurl() {
  if (curlPreview) curlPreview.remove();
  curlPreview = null; curlReady = false; curlTried = false;
  curlRenderedTarget = null; curlVisible = false; curlTheme = null; curlRight = null;
  frame.style.clipPath = '';
  fold.style.display = 'none';
  shade.style.opacity = '0';
}
function prepareCurl() {
  if (curlTried || noMotion()) return;
  curlTried = true;
  const d = doc();
  // One transient chapter preview, never a whole-book copy or per-frame capture.
  // Check source/DOM/image budgets before creating another browsing context.
  if (!d || BOOK.fixed || (BOOK.chapters[ch].size || 0) > 262144 ||
      d.getElementsByTagName('*').length > 2000 ||
      d.querySelector('audio,video,iframe,object,embed,canvas')) return;
  let pixels = 0;
  if (d.images.length > 16) return;
  for (const im of d.images) {
    if (!im.complete) return;
    pixels += im.naturalWidth * im.naturalHeight;
    if (pixels > 2000000) return;
  }
  const owner = ch, ownerPage = page;
  const preview = document.createElement('iframe');
  preview.id = 'curlPreview';
  preview.setAttribute('sandbox', 'allow-same-origin');
  preview.setAttribute('aria-hidden', 'true');
  preview.setAttribute('tabindex', '-1');
  preview.setAttribute('inert', '');
  curlPreview = preview;
  preview.addEventListener('load', () => {
    if (curlPreview !== preview || ch !== owner || page !== ownerPage || !ready) return;
    let other;
    try { other = preview.contentDocument; } catch (_) { return; }
    if (!other || !other.body || other.getElementsByTagName('parsererror').length) return;
    // Same locked chapter URL/base/CSP as the active iframe; no scripts or media playback.
    for (const script of Array.from(other.querySelectorAll('script'))) script.remove();
    other.documentElement.setAttribute('aria-hidden', 'true');
    other.documentElement.setAttribute('inert', '');
    prepare(other, false);
    const st = other.createElementNS(NS, 'style');
    st.textContent = '*{animation:none!important;transition:none!important;pointer-events:none!important}';
    (other.head || other.documentElement).appendChild(st);
    const fonts = other.fonts ? other.fonts.ready : Promise.resolve();
    fonts.then(() => {
      if (curlPreview !== preview || ch !== owner || page !== ownerPage || !ready) return;
      scrollTo(other, curlTarget * step());
      curlRenderedTarget = curlTarget;
      curlReady = true;
      if (dragging || turning) drawTurn(curlProgress);
    }, () => {});
  });
  // Loading through the existing resource path preserves relative CSS/fonts/images and policy.
  preview.src = frame.src;
  document.body.insertBefore(preview, frame);
}
function stopTurn() {
  cancelAnimationFrame(turnFrame);
  turnFrame = 0; turning = false; dragging = false;
  frame.style.transform = '';
  releaseCurl();
  const d = doc();
  if (d && ready && !BOOK.fixed) scrollTo(d, page * step());
}
function animateTurn(from, to, draw, done) {
  turning = true;
  const start = performance.now();
  function tick(now) {
    const t = noMotion() ? 1 : Math.min(1, (now - start) / 220);
    draw(from + (to - from) * (1 - Math.pow(1 - t, 3)));
    if (t < 1) turnFrame = requestAnimationFrame(tick);
    else { turnFrame = 0; turning = false; done(); }
  }
  turnFrame = requestAnimationFrame(tick);
}
function drawTurn(progress, withPreview = true) {
  const d = doc();
  if (!d || noMotion()) return;
  curlProgress = progress;
  const amount = Math.min(1, Math.abs(progress));
  if (amount < 0.001) { frame.style.clipPath = ''; fold.style.display = 'none'; shade.style.opacity = '0'; return; }
  const destination = page + (progress > 0 ? 1 : -1);
  if (withPreview && destination >= 0 && destination < pages) {
    curlTarget = destination;
    prepareCurl();
    if (curlReady && curlPreview) {
      if (curlRenderedTarget !== destination) {
        scrollTo(curlPreview.contentDocument, destination * step());
        curlRenderedTarget = destination;
      }
      if (!curlVisible) { curlPreview.style.visibility = 'visible'; curlVisible = true; }
    }
  } else if (curlPreview && curlVisible) {
    curlPreview.style.visibility = 'hidden'; curlVisible = false;
  }
  // The outgoing text stays fixed on the paper, while its edge folds across the next page.
  // Large/media/fixed chapters use the same paper fold over the theme rather than a second DOM.
  const right = (progress > 0) !== BACK;
  const paperTheme = theme();
  if (curlTheme !== paperTheme || curlRight !== right) {
    curlTheme = paperTheme; curlRight = right;
    fold.style.transformOrigin = right ? 'right center' : 'left center';
    fold.style.borderRadius = right ? '90% 0 0 90% / 4% 0 0 4%' : '0 90% 90% 0 / 0 4% 4% 0';
    fold.style.background = `linear-gradient(${right ? 90 : 270}deg,${paperTheme.bg} 0%,${paperTheme.bg} 58%,${paperTheme.fg}28 88%,${paperTheme.bg} 100%)`;
  }
  const width = innerWidth;
  const edge = width * (right ? 1 - amount : amount);
  const bend = Math.sin(Math.PI * amount);
  const band = Math.min(width * 0.24, 10 + width * 0.22 * bend);
  const slant = (fingerY - 0.5) * 20 * bend;
  frame.style.clipPath = right
    ? `polygon(0 0,${edge + slant}px 0,${edge - slant}px 100%,0 100%)`
    : `polygon(${edge + slant}px 0,100% 0,100% 100%,${edge - slant}px 100%)`;
  fold.style.display = amount < 0.995 ? 'block' : 'none';
  fold.style.width = `${band}px`;
  fold.style.left = `${right ? edge - band : edge}px`;
  fold.style.transform = `perspective(900px) rotateY(${(right ? -1 : 1) * (12 + 28 * bend)}deg) skewY(${(right ? 1 : -1) * slant / 8}deg)`;
  shade.style.left = `${edge - 24}px`;
  shade.style.width = '48px';
  shade.style.opacity = String(bend * 0.45);
}

function send(m) {
  m.t = TOKEN;
  fetch('/ezmsg?m=' + encodeURIComponent(JSON.stringify(m)), { cache: 'no-store' }).catch(() => {});
}
function theme() { return THEMES[S.theme] || THEMES.white; }
function margin() { return MARGINS[S.margin == null ? 1 : S.margin] || 26; }
function doc() { try { return frame.contentDocument; } catch (_) { return null; } }
function scroller(d) { return d.scrollingElement || d.documentElement; }

// Pages are one screen apart along one axis: across for horizontal text, down for vertical.
function step() { return V ? innerHeight : innerWidth; }
function scrolled(d) { const se = scroller(d); return V ? Math.abs(se.scrollTop) : Math.abs(se.scrollLeft); }
function scrollTo(d, at) { const se = scroller(d); if (V) se.scrollTop = at; else se.scrollLeft = R ? -at : at; }
/** Where a box starts along the page axis, from the chapter's start. */
function along(d, r) {
  if (V) return r.top + scrolled(d);
  if (R) return (innerWidth - r.right) + scrolled(d);
  return r.left + scrolled(d);
}
function pageAt(d, r) { return Math.max(0, Math.min(pages - 1, Math.floor((along(d, r) + 1) / step()))); }

function css() {
  const t = theme();
  const m = margin();
  const w = innerWidth, h = innerHeight;
  let s = '@font-face{font-family:EzCaladea;src:url(/ezfonts/Caladea-Regular.ttf)}' +
    '@font-face{font-family:EzCaladea;font-weight:bold;src:url(/ezfonts/Caladea-Bold.ttf)}' +
    '@font-face{font-family:EzCaladea;font-style:italic;src:url(/ezfonts/Caladea-Italic.ttf)}' +
    '@font-face{font-family:EzCaladea;font-weight:bold;font-style:italic;src:url(/ezfonts/Caladea-BoldItalic.ttf)}';
  s += `html{background:${t.bg}!important;overflow:hidden!important}`;
  s += `#eznote{position:fixed;left:12px;right:12px;bottom:12px;max-height:45%;overflow:auto;z-index:2147483647;` +
    `background:${t.bg};color:${t.fg};border:1px solid ${t.fg}33;border-radius:12px;box-shadow:0 6px 24px #0005;` +
    `padding:14px 16px;font-size:0.92em;line-height:1.45;column-count:1!important;text-align:start;writing-mode:horizontal-tb}`;
  for (const [c, v] of Object.entries(MARKS)) s += `body .ezhl[data-c="${c}"]{background-color:${v}!important;border-radius:2px}`;
  s += `body .ezhl[data-n]{text-decoration:underline dotted ${t.fg}99!important;text-underline-offset:3px}`;
  if (BOOK.fixed) return s + 'html,body{margin:0!important;padding:0!important}';
  s += `html{margin:0!important;padding:0!important;height:${h}px!important;width:${w}px!important}`;
  if (V) {
    // Vertical text: lines run down, the block runs across; columns of one screen's height stack down the page.
    s += `body{margin:0!important;box-sizing:border-box!important;width:${w}px!important;height:auto!important;min-width:0!important;` +
      `max-height:none!important;padding:${PAD_V}px ${m}px!important;column-width:${h - 2 * PAD_V}px!important;column-gap:${2 * PAD_V}px!important;` +
      `column-fill:auto!important;column-count:auto!important;overflow:visible!important;background:transparent!important;color:${t.fg}}`;
  } else {
    s += `body{margin:0!important;box-sizing:border-box!important;height:${h}px!important;min-height:0!important;width:auto!important;` +
      `max-width:none!important;padding:${PAD_V}px ${m}px!important;column-width:${w - 2 * m}px!important;column-gap:${2 * m}px!important;` +
      `column-fill:auto!important;column-count:auto!important;overflow:visible!important;background:transparent!important;color:${t.fg}}`;
  }
  s += `img,svg,video,canvas{max-width:${w - 2 * m}px!important;max-height:${h - 2 * PAD_V}px!important;object-fit:contain;break-inside:avoid}`;
  s += 'img{height:auto}figure,table,pre{break-inside:avoid}h1,h2,h3,h4,h5,h6{break-after:avoid}';
  // Starts one more column after the chapter, so its last page can scroll fully into place.
  s += '#ezend{break-before:column!important;display:block!important;height:1px!important;width:1px!important;margin:0!important;padding:0!important;border:0!important}';
  if (S.spacing) {
    s += `body{line-height:${S.spacing}!important}p,li,div,span,blockquote,dd,dt,td,th,a,em,i,b,strong,small,cite{line-height:inherit!important}`;
  }
  if (S.justify) s += 'p:not([data-ezc]){text-align:justify!important;-webkit-hyphens:auto;hyphens:auto}';
  const f = FONTS[S.font];
  if (f) {
    s += `body,p,div,span,li,blockquote,td,th,dd,dt,a,em,i,b,strong,h1,h2,h3,h4,h5,h6,small,sup,sub,cite,section,article{font-family:${f}!important}`;
  }
  if (t.force) {
    s += `body *{color:${t.fg}!important;background-color:transparent!important;border-color:${t.fg}55!important}` +
      `a,a *{color:${t.link}!important}`;
  }
  return s;
}

function langOf(d) {
  const de = d.documentElement;
  return (de.getAttribute('lang') || de.getAttributeNS('http://www.w3.org/XML/1998/namespace', 'lang') ||
    (d.body && d.body.getAttribute('lang')) || BOOK.language || '').toLowerCase().split('-')[0];
}

/** Reads how the chapter writes before the reader's style goes in. */
function direction(d) {
  const view = d.defaultView;
  const de = d.documentElement;
  // Arabic, Hebrew and the like read right to left even when a book forgets to say so.
  if (!de.hasAttribute('dir') && !(d.body && d.body.hasAttribute('dir')) && RTL_LANGS.includes(langOf(d)) &&
      view.getComputedStyle(d.body || de).direction !== 'rtl') {
    de.setAttribute('dir', 'rtl');
  }
  const cs = view.getComputedStyle(d.body || de);
  const mode = cs.writingMode || '';
  V = mode.startsWith('vertical') || mode.startsWith('sideways');
  R = !V && cs.direction === 'rtl';
  BACK = BOOK.rtl || R || mode === 'vertical-rl' || mode === 'sideways-rl';
}

function prepare(d, interactive = true) {
  const view = d.defaultView;
  // Centred or right-aligned paragraphs keep their alignment when text is justified.
  for (const p of d.getElementsByTagName('p')) {
    const a = view.getComputedStyle(p).textAlign;
    if (a === 'center' || a === 'right' || a === 'end') p.setAttribute('data-ezc', '');
  }
  direction(d);
  let st = d.getElementById('ezstyle');
  if (!st) {
    st = d.createElementNS(NS, 'style');
    st.id = 'ezstyle';
    (d.head || d.documentElement).appendChild(st);
  }
  st.textContent = css();
  if (!BOOK.fixed && d.body && !d.getElementById('ezend')) {
    const end = d.createElementNS(NS, 'div');
    end.id = 'ezend';
    d.body.appendChild(end);
  }
  paintMarks(d);
  if (interactive) bind(d);
}

function fit(d) {
  const de = d.documentElement;
  de.style.transform = '';
  let w = 0, h = 0;
  const vp = d.querySelector('meta[name=viewport]');
  if (vp) {
    const c = vp.getAttribute('content') || '';
    w = +((c.match(/width\s*=\s*(\d+)/) || [])[1] || 0);
    h = +((c.match(/height\s*=\s*(\d+)/) || [])[1] || 0);
  }
  if (!w || !h) { w = de.scrollWidth || innerWidth; h = de.scrollHeight || innerHeight; }
  const s = Math.min(innerWidth / w, innerHeight / h);
  de.style.width = w + 'px';
  de.style.height = h + 'px';
  de.style.transformOrigin = '0 0';
  de.style.transform = `translate(${(innerWidth - w * s) / 2}px,${(innerHeight - h * s) / 2}px) scale(${s})`;
}

// Text positions: characters from the start of the chapter's body. They stay put when the
// text size, font or screen changes, so highlights and bookmarks keep their place.
function textOffset(d, node, off) {
  const r = d.createRange();
  r.setStart(d.body, 0);
  try { r.setEnd(node, off); } catch (_) { return 0; }
  return r.toString().length;
}
function textNodes(d) {
  const out = [];
  const w = d.createTreeWalker(d.body, NodeFilter.SHOW_TEXT);
  let t;
  while ((t = w.nextNode())) out.push(t);
  return out;
}
function nodeAt(d, offset) {
  let at = 0, last = null;
  for (const t of textNodes(d)) {
    const len = t.data.length;
    if (offset < at + len || (offset === at + len && len > 0)) return [t, offset - at];
    at += len;
    last = t;
  }
  return last ? [last, last.data.length] : null;
}
function pageOfOffset(d, offset) {
  const hit = nodeAt(d, offset);
  if (!hit) return 0;
  const [t, o] = hit;
  const r = d.createRange();
  r.setStart(t, Math.min(o, t.data.length));
  r.setEnd(t, Math.min(o + 1, t.data.length));
  const rects = r.getClientRects();
  const box = rects.length ? rects[0] : (t.parentElement ? t.parentElement.getBoundingClientRect() : null);
  return box ? pageAt(d, box) : 0;
}
/** The first character shown on this page, found by probing near the page's starting edge. */
function pageStartOffset(d) {
  const m = margin();
  const tries = [];
  if (V) {
    for (let x = innerWidth - m - 6; x > m; x -= 12) tries.push([x, PAD_V + 8]);
  } else {
    const x0 = R ? innerWidth - m - 6 : m + 6;
    for (let y = PAD_V + 6; y < innerHeight - PAD_V; y += 12) tries.push([x0, y], [innerWidth / 2, y]);
  }
  for (const [x, y] of tries) {
    const r = d.caretRangeFromPoint ? d.caretRangeFromPoint(x, y) : null;
    if (r && r.startContainer.nodeType === 3 && d.body.contains(r.startContainer)) {
      const off = textOffset(d, r.startContainer, r.startOffset);
      if (pageOfOffset(d, off) === page) return off;
    }
  }
  const len = (d.body.textContent || '').length;
  return Math.round(len * page / Math.max(1, pages));
}

function place() {
  const d = doc();
  if (!d || !d.documentElement) return;
  const p = pending || {};
  pending = null;
  if (BOOK.fixed) {
    fit(d);
    pages = 1;
    page = 0;
  } else {
    // The end marker starts the column after the last page.
    const end = d.getElementById('ezend');
    const extent = V ? scroller(d).scrollHeight : scroller(d).scrollWidth;
    pages = Math.max(1, end ? Math.floor((along(d, end.getBoundingClientRect()) + step() / 2) / step()) : Math.round(extent / step()));
    if (p.end) page = pages - 1;
    else if (p.anchor) {
      const el = d.getElementById(p.anchor) || d.getElementsByName(p.anchor)[0];
      page = el ? pageAt(d, el.getBoundingClientRect()) : 0;
    } else if (p.offset != null) page = pageOfOffset(d, p.offset);
    else if (p.fraction != null) page = Math.min(pages - 1, Math.max(0, Math.round(p.fraction * pages)));
    else page = Math.min(pages - 1, p.page || 0);
  }
  ready = true;
  frame.style.visibility = '';
  show();
  if (p.turn) {
    send({ type: 'turn' });
    animateTurn(p.turn, 0, x => drawTurn(x, false), releaseCurl);
  }
}

let snapping = false;
function show() {
  const d = doc();
  if (!d) return;
  if (!BOOK.fixed) {
    snapping = true;
    scrollTo(d, page * step());
    snapping = false;
  }
  if (!dragging && !turning) committedOffset = BOOK.fixed ? 0 : pageStartOffset(d);
  const pct = (before[ch] + ((page + 1) / pages) * sizes[ch]) / total;
  const kept = BM.find(b => b.ch === ch && (BOOK.fixed || pageOfOffset(d, b.offset) === page));
  send({ type: 'pos', ch, page, pages, fraction: pages > 0 ? page / pages : 0, percent: pct, bookmark: kept ? kept.id : null });
}

function load(i, target) {
  stopTurn();
  i = Math.max(0, Math.min(n - 1, i | 0));
  pending = target || { page: 0 };
  const d = doc();
  if (i === ch && ready && d && d.readyState === 'complete') { place(); return; }
  ch = i;
  committedOffset = null;
  ready = false;
  lastSel = null;
  if (pending.turn) frame.style.visibility = 'hidden';
  frame.src = '/ezbook/' + BOOK.chapters[i].href.split('/').map(encodeURIComponent).join('/');
}

frame.addEventListener('load', () => {
  const d = doc();
  if (!d || !d.documentElement) { send({ type: 'error' }); return; }
  // A chapter that isn't well-formed XHTML is read again as HTML, as browsers read web pages.
  if (d.getElementsByTagName('parsererror').length && !frame.src.includes('/ezbook-html/')) {
    const keep = pending;
    frame.src = frame.src.replace('/ezbook/', '/ezbook-html/');
    pending = keep;
    return;
  }
  if (!d.body) { send({ type: 'error' }); return; }
  prepare(d);
  for (const im of d.images) if (!im.complete) im.addEventListener('load', relayoutSoon, { once: true });
  const fonts = d.fonts ? d.fonts.ready : Promise.resolve();
  fonts.then(place, place);
});

function relayout() {
  const d = doc();
  if (!d || !ready) return;
  stopTurn();
  // The first character on the page stays on the page shown.
  pending = BOOK.fixed ? { page: 0 } : { offset: committedOffset == null ? pageStartOffset(d) : committedOffset };
  const st = d.getElementById('ezstyle');
  if (st) st.textContent = css();
  place();
}
let timer = 0;
function relayoutSoon() { clearTimeout(timer); timer = setTimeout(relayout, 120); }
addEventListener('resize', relayoutSoon);
addEventListener('pagehide', stopTurn);
document.addEventListener('visibilitychange', () => { if (document.hidden) stopTurn(); });

function turn(direction, from = 0) {
  if (!ready || turning) return;
  cancelAnimationFrame(turnFrame);
  turnFrame = 0;
  dragging = false;
  const target = page + direction;
  if (target >= 0 && target < pages) {
    animateTurn(from, direction, drawTurn, () => {
      page = target;
      frame.style.transform = '';
      releaseCurl();
      show();
      send({ type: 'turn' });
    });
  } else if (ch + direction >= 0 && ch + direction < n) {
    animateTurn(from, direction, drawTurn, () => {
      load(ch + direction, { page: 0, end: direction < 0, turn: direction });
    });
  } else {
    settleTurn(from);
    if (direction > 0) send({ type: 'end' });
  }
}
function settleTurn(from) {
  animateTurn(from, 0, drawTurn, () => { stopTurn(); });
}
function next() { turn(1); }
function prev() { turn(-1); }

// Highlights are spans around the text they cover; the text itself, and so every position, is unchanged.
function clearMarks(d) {
  for (const m of Array.from(d.querySelectorAll('span.ezhl'))) {
    const parent = m.parentNode;
    while (m.firstChild) parent.insertBefore(m.firstChild, m);
    parent.removeChild(m);
    parent.normalize();
  }
}
function mark(d, h) {
  const a = nodeAt(d, h.start), b = nodeAt(d, h.end);
  if (!a || !b) return;
  const nodes = textNodes(d);
  const from = nodes.indexOf(a[0]), to = nodes.indexOf(b[0]);
  if (from < 0 || to < from) return;
  for (let i = from; i <= to; i++) {
    const t = nodes[i];
    const s = i === from ? a[1] : 0, e = i === to ? b[1] : t.data.length;
    if (e <= s || !t.data.slice(s, e).trim()) continue;
    const parent = t.parentNode;
    if (!parent || ['style', 'script', 'title'].includes(parent.localName)) continue;
    let x = s > 0 ? t.splitText(s) : t;
    if (e - s < x.data.length) x.splitText(e - s);
    const span = d.createElementNS(NS, 'span');
    span.setAttribute('class', 'ezhl');
    span.setAttribute('data-id', h.id);
    span.setAttribute('data-c', MARKS[h.color] ? h.color : 'yellow');
    if (h.note) span.setAttribute('data-n', '');
    x.parentNode.insertBefore(span, x);
    span.appendChild(x);
  }
}
function paintMarks(d) {
  clearMarks(d);
  for (const h of HL) if (h.ch === ch) mark(d, h);
}

function closeNote(d) {
  const box = d.getElementById('eznote');
  if (box) { box.remove(); return true; }
  return false;
}
function showNote(el) {
  const d = doc();
  if (!d) return;
  closeNote(d);
  let target = el;
  if (['a', 'span', 'sup'].includes(el.localName) || el.textContent.trim().length < 3) {
    target = el.closest('aside,li,p,div,section,dd') || el;
  }
  const box = d.createElementNS(NS, 'div');
  box.id = 'eznote';
  box.appendChild(d.importNode(target, true));
  d.documentElement.appendChild(box);
}
function note(path, id) {
  const i = BOOK.chapters.findIndex(c => c.href === path);
  if (i === ch) {
    const el = doc().getElementById(id);
    if (!el) return false;
    showNote(el);
    return true;
  }
  if (i < 0) return false;
  fetch('/ezbook/' + path.split('/').map(encodeURIComponent).join('/'))
    .then(r => r.text())
    .then(t => {
      const other = new DOMParser().parseFromString(t, 'application/xhtml+xml');
      const el = other.getElementById(id);
      if (el) showNote(el); else load(i, { anchor: id });
    })
    .catch(() => load(i, { anchor: id }));
  return true;
}

function follow(a, d) {
  let url;
  try { url = new URL(a.getAttribute('href'), d.baseURI); } catch (_) { return; }
  const inBook = url.origin === location.origin && /^\/ezbook(-html)?\//.test(url.pathname);
  if (inBook) {
    const path = decodeURIComponent(url.pathname.replace(/^\/ezbook(-html)?\//, ''));
    const anchor = url.hash ? decodeURIComponent(url.hash.slice(1)) : null;
    const kind = (a.getAttribute('epub:type') || '') + ' ' + (a.getAttribute('role') || '');
    if (anchor && /noteref/.test(kind) && note(path, anchor)) return;
    const i = BOOK.chapters.findIndex(c => c.href === path);
    if (i >= 0) load(i, anchor ? { anchor } : { page: 0 });
  } else if (/^(https?|mailto):/i.test(url.href)) {
    send({ type: 'link', url: url.href });
  }
}

function selected(d) {
  const s = d.getSelection();
  return s && !s.isCollapsed;
}

function bind(d) {
  if (d.ezBound) return;
  d.ezBound = true;
  let sx = 0, sy = 0, swiped = false, active = false, horizontal = false, progress = 0;
  d.addEventListener('touchstart', e => {
    active = false;
    if (e.touches.length !== 1) { if (dragging) stopTurn(); swiped = true; return; }
    if (turning || !ready) return;
    sx = e.touches[0].clientX;
    sy = e.touches[0].clientY;
    fingerY = Math.max(0, Math.min(1, sy / Math.max(1, innerHeight)));
    swiped = false; active = true; horizontal = false; progress = 0;
  }, { passive: true });
  d.addEventListener('touchmove', e => {
    if (!active) return;
    if (e.touches.length !== 1 || selected(d)) { active = false; stopTurn(); return; }
    const t = e.touches[0];
    fingerY = Math.max(0, Math.min(1, t.clientY / Math.max(1, innerHeight)));
    const dx = t.clientX - sx, dy = t.clientY - sy;
    if (!horizontal && Math.abs(dy) > 8 && Math.abs(dy) >= Math.abs(dx)) { active = false; return; }
    if (!horizontal && Math.abs(dx) > 8 && Math.abs(dx) > Math.abs(dy) * 1.2) horizontal = true;
    if (!horizontal) return;
    e.preventDefault();
    dragging = true;
    progress = Math.max(-0.95, Math.min(0.95, (BACK ? dx : -dx) / Math.max(1, innerWidth)));
    cancelAnimationFrame(turnFrame);
    turnFrame = requestAnimationFrame(() => { turnFrame = 0; if (dragging) drawTurn(progress); });
  }, { passive: false });
  d.addEventListener('touchend', e => {
    if (!active) return;
    active = false;
    const t = e.changedTouches[0];
    const dx = t.clientX - sx, dy = t.clientY - sy;
    if (selected(d)) { stopTurn(); return; }
    if (Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy) * 1.2) {
      swiped = true;
      closeNote(d);
      turn((dx < 0) !== BACK ? 1 : -1, progress);
    } else if (horizontal) { swiped = true; dragging = false; settleTurn(progress); }
  });
  d.addEventListener('touchcancel', () => { active = false; swiped = true; stopTurn(); });
  // The text chosen last, kept for the Highlight and Note actions, which run after the selection menu closes.
  d.addEventListener('selectionchange', () => {
    const s = d.getSelection();
    if (!s || s.isCollapsed || !s.rangeCount) return;
    if (dragging || turning) stopTurn();
    const r = s.getRangeAt(0);
    if (!d.body.contains(r.commonAncestorContainer)) return;
    const start = textOffset(d, r.startContainer, r.startOffset);
    const end = textOffset(d, r.endContainer, r.endOffset);
    if (end > start) lastSel = { start, end, text: s.toString().slice(0, 2000) };
  });
  // The app shows its highlight bar while text is chosen, on the side away from it.
  let chosen = false, chosenTimer = 0;
  d.addEventListener('selectionchange', () => {
    clearTimeout(chosenTimer);
    chosenTimer = setTimeout(() => {
      const s = d.getSelection();
      const active = !!(s && !s.isCollapsed && s.rangeCount && d.body.contains(s.getRangeAt(0).commonAncestorContainer));
      const low = active && s.getRangeAt(0).getBoundingClientRect().bottom > innerHeight * 0.55;
      if (active !== chosen || active) send({ type: 'selection', active, low });
      chosen = active;
    }, 180);
  });
  d.addEventListener('click', e => {
    if (swiped) return;
    const box = d.getElementById('eznote');
    const a = e.target.closest ? e.target.closest('a[href]') : null;
    if (a) { e.preventDefault(); follow(a, d); return; }
    if (box && box.contains(e.target)) return;
    if (closeNote(d)) return;
    if (selected(d)) return;
    const hl = e.target.closest ? e.target.closest('span.ezhl') : null;
    if (hl) { send({ type: 'tapHighlight', id: hl.getAttribute('data-id') }); return; }
    const x = e.clientX, w = innerWidth;
    if (x < w * 0.3) { if (BACK) next(); else prev(); }
    else if (x > w * 0.7) { if (BACK) prev(); else next(); }
    else send({ type: 'menu' });
  });
  d.addEventListener('keydown', e => {
    if (e.key === 'ArrowRight' || e.key === 'PageDown' || e.key === ' ') { e.preventDefault(); if (BACK) prev(); else next(); }
    if (e.key === 'ArrowLeft' || e.key === 'PageUp') { e.preventDefault(); if (BACK) next(); else prev(); }
  });
  // Find-in-page scrolls to its match; the page holding it becomes the page shown.
  d.defaultView.addEventListener('scroll', () => {
    if (snapping || turning || dragging || !ready || BOOK.fixed) return;
    const at = scrolled(d);
    const p = Math.max(0, Math.min(pages - 1, Math.floor((at + step() / 2) / step())));
    if (at !== p * step()) { page = p; show(); }
  });
}

// Sized from the window, not CSS viewport units: a web view laid out before it had a size can keep them at 0.
function size() {
  frame.style.width = innerWidth + 'px';
  frame.style.height = innerHeight + 'px';
}
size();
addEventListener('resize', size);

function paint() {
  const t = theme();
  document.documentElement.style.background = t.bg;
  document.body.style.background = t.bg;
}

/** About [max] characters of text from [offset], with a space where one block ends and the next begins. */
function snippetFrom(d, offset, max) {
  const hit = nodeAt(d, offset);
  if (!hit) return '';
  const nodes = textNodes(d);
  const first = nodes.indexOf(hit[0]);
  const blockOf = t => (t.parentElement && t.parentElement.closest('p,h1,h2,h3,h4,h5,h6,li,dd,dt,div,blockquote,figcaption,td,th,pre')) || null;
  let out = '', block = null;
  for (let i = first; i >= 0 && i < nodes.length && out.length < max; i++) {
    const t = nodes[i];
    const parent = t.parentElement;
    if (parent && ['style', 'script', 'title'].includes(parent.localName)) continue;
    const b = blockOf(t);
    if (out && b !== block) out += ' ';
    block = b;
    out += i === first ? t.data.slice(hit[1]) : t.data;
  }
  return out.replace(/\s+/g, ' ').trim().slice(0, max);
}

function newId() {
  const b = new Uint8Array(9);
  crypto.getRandomValues(b);
  return Array.from(b, x => x.toString(16).padStart(2, '0')).join('');
}

window.ez = {
  next, prev,
  setReducedMotion(value) { reducedMotion = !!value; if (reducedMotion) stopTurn(); },
  apply(settings) { S = settings || {}; paint(); relayout(); setTimeout(relayout, 300); },
  goHref(href) {
    const [path, anchor] = String(href).split('#');
    const i = path ? BOOK.chapters.findIndex(c => c.href === path) : ch;
    if (i >= 0) load(i, anchor ? { anchor } : { page: 0 });
  },
  goPercent(p) {
    const at = Math.max(0, Math.min(1, +p || 0)) * total;
    let i = 0;
    while (i < n - 1 && before[i + 1] <= at) i++;
    load(i, { fraction: Math.min(0.999, (at - before[i]) / sizes[i]) });
  },
  goOffset(i, offset) { load(i, { offset: Math.max(0, offset | 0) }); },
  /** Highlights the text chosen last; with a note, the app asks for the note next. */
  highlight(withNote, color) {
    const d = doc();
    if (!d || !ready || !lastSel) return;
    const h = { id: newId(), ch, start: lastSel.start, end: lastSel.end, color: MARKS[color] ? color : 'yellow', note: false };
    const text = lastSel.text;
    lastSel = null;
    HL.push(h);
    mark(d, h);
    try { d.getSelection().removeAllRanges(); } catch (_) { /* nothing chosen */ }
    send({ type: 'highlight', id: h.id, ch, start: h.start, end: h.end, text, color: h.color, note: !!withNote });
  },
  setHighlights(list) {
    HL = list || [];
    const d = doc();
    if (d && ready) paintMarks(d);
  },
  setBookmarks(list) {
    BM = list || [];
    if (ready) show();
  },
  /** Bookmarks this page, or takes its bookmark away. */
  toggleBookmark() {
    const d = doc();
    if (!d || !ready) return;
    const kept = BM.find(b => b.ch === ch && (BOOK.fixed || pageOfOffset(d, b.offset) === page));
    if (kept) {
      BM = BM.filter(b => b !== kept);
      send({ type: 'unbookmark', id: kept.id });
    } else {
      const offset = BOOK.fixed ? 0 : pageStartOffset(d);
      const text = snippetFrom(d, offset, 140);
      const b = { id: newId(), ch, offset };
      BM.push(b);
      send({ type: 'bookmark', id: b.id, ch, offset, text });
    }
    show();
  },
};

paint();
const start = START || {};
load(start.ch || 0, { fraction: start.fraction || 0 });
})();
</script></body></html>
"#;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_is_safe_inside_a_script() {
        let mut out = String::new();
        json_string("a\"b\\c</script>\u{2028}\n&", &mut out);
        assert_eq!(out, r#""a\"b\\c\u003c/script\u003e\u2028\u000a\u0026""#);
    }

    #[test]
    fn hrefs_keep_their_fragment() {
        assert_eq!(resolve_href("OEBPS/text", "ch1.xhtml#s2"), "OEBPS/text/ch1.xhtml#s2");
        assert_eq!(resolve_href("OEBPS", "../ch1.xhtml"), "ch1.xhtml");
        assert_eq!(resolve_href("OEBPS", "ch%201.xhtml"), "OEBPS/ch 1.xhtml");
    }
}
