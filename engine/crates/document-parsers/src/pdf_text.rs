//! Text extraction for PDF pages.
//!
//! Each page's content stream, and the content of the Form XObjects it draws, is walked with the
//! current transformation matrix and the text matrices. Glyphs are decoded through the font's
//! ToUnicode CMap, or through a simple font's encoding (WinAnsi, MacRoman or Standard, with
//! /Differences by glyph name). Glyph positions decide line breaks and spaces. A glyph that maps to
//! no character is skipped, so the result is best effort and never invents text.

use std::collections::HashMap;
use std::io::{Read, Seek};
use std::rc::Rc;

use document_core::{CancelToken, Result};

use crate::pdf_file::PdfFile;
use crate::pdf_syntax::{Dict, Obj};

/// Bounds for one extraction.
#[derive(Debug, Clone, Copy)]
pub struct TextLimits {
    /// Pages read from the start of the document.
    pub max_pages: usize,
    /// Characters returned across all pages. Beyond this the text is cut and `truncated` is set.
    pub max_chars: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PageText {
    /// 1-based page number.
    pub page: u32,
    pub text: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DocumentText {
    pub pages: Vec<PageText>,
    pub page_count: usize,
    /// Some pages or characters were left out because a limit was reached.
    pub truncated: bool,
}

type Matrix = [f64; 6];
const IDENTITY: Matrix = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0];
const MAX_FORM_DEPTH: usize = 8;
const MAX_OPERATORS_PER_PAGE: usize = 4_000_000;
const MAX_PAGE_BYTES: usize = 8 * 1024 * 1024;
/// A TJ adjustment (thousandths of an em) more negative than this is a visible gap: a space.
const KERNING_SPACE: f64 = 200.0;
/// Horizontal gaps wider than this (in em) are word breaks.
const WORD_GAP_EM: f64 = 0.2;
/// Vertical moves larger than this (in em) start a new line.
const LINE_GAP_EM: f64 = 0.5;
const MAX_CMAP_ENTRIES: usize = 300_000;
/// UTF-16 units decoded across one CMap's entries. Repeated ranges overwrite the same codes, so the entry cap alone
/// does not bound the work.
const MAX_CMAP_WORK: usize = 4_000_000;
/// Units kept from one destination string; real ToUnicode targets are a few characters (ligatures, combining marks).
const MAX_CMAP_DEST_UNITS: usize = 64;
const MAX_CMAP_TOKENS: usize = 2_000_000;
const MAX_ARRAY_ITEMS: usize = 65_536;
const MAX_ARRAY_NESTING: usize = 16;

/// Extracts the text of every page, in page-tree order, up to `limits`.
pub fn extract_text<R: Read + Seek>(
    source: R,
    password: Option<&str>,
    limits: TextLimits,
    cancel: &CancelToken,
) -> Result<DocumentText> {
    let mut file = PdfFile::open(source)?;
    file.unlock(password)?;
    let nodes = file.page_nodes()?;
    let page_count = nodes.len();
    let mut pages = Vec::new();
    let mut total = 0usize;
    let mut truncated = false;

    for (index, node) in nodes.iter().enumerate() {
        cancel.check()?;
        if index >= limits.max_pages || total >= limits.max_chars {
            truncated = true;
            break;
        }
        let page = match file.object(node.page.object)? {
            Obj::Dict(page) => page,
            _ => Dict::default(),
        };
        let resources_value = page.get("Resources").or_else(|| node.inherited.get("Resources")).cloned();
        let resources = match resources_value {
            Some(value) => resolve_dict(&mut file, &value)?.unwrap_or_default(),
            None => Dict::default(),
        };
        let content = page_content(&mut file, &page)?;
        let mut runner = Runner::new(cancel);
        runner.run(&mut file, &content, &resources, 0)?;
        if runner.truncated {
            truncated = true;
        }
        let mut text = runner.finish();
        let room = limits.max_chars - total;
        if text.chars().count() > room {
            text = text.chars().take(room).collect();
            truncated = true;
        }
        total += text.chars().count();
        pages.push(PageText { page: index as u32 + 1, text });
        if truncated {
            break;
        }
    }
    Ok(DocumentText { pages, page_count, truncated })
}

// ---------------------------------------------------------------------------------------------
// Objects and streams

fn page_content<R: Read + Seek>(file: &mut PdfFile<R>, page: &Dict) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    let Some(contents) = page.get("Contents").cloned() else { return Ok(out) };
    let parts = match contents {
        Obj::Array(items) => items,
        other => vec![other],
    };
    for part in parts {
        let Obj::Ref(number, _) = part else { continue };
        if let Some(bytes) = stream_data(file, number)? {
            out.extend_from_slice(&bytes);
            out.push(b'\n');
        }
    }
    Ok(out)
}

/// Decoded stream data, or `None` when the object is absent or cannot be decoded. Text is best
/// effort, so one undecodable stream is skipped rather than failing the page.
fn stream_data<R: Read + Seek>(file: &mut PdfFile<R>, number: u32) -> Result<Option<Vec<u8>>> {
    if !file.has_object(number) {
        return Ok(None);
    }
    Ok(file.decoded_stream(number).unwrap_or(None))
}

fn resolve_dict<R: Read + Seek>(file: &mut PdfFile<R>, value: &Obj) -> Result<Option<Dict>> {
    Ok(match file.resolve(value)? {
        Obj::Dict(dict) => Some(dict),
        _ => None,
    })
}

/// `resources[category][name]`, unresolved.
fn lookup<R: Read + Seek>(file: &mut PdfFile<R>, resources: &Dict, category: &str, name: &[u8]) -> Result<Option<Obj>> {
    let Some(value) = resources.get(category).cloned() else { return Ok(None) };
    let Some(dict) = resolve_dict(file, &value)? else { return Ok(None) };
    Ok(dict.0.into_iter().find(|(key, _)| key.as_slice() == name).map(|(_, value)| value))
}

// ---------------------------------------------------------------------------------------------
// Matrices

fn multiply(m: Matrix, c: Matrix) -> Matrix {
    [
        m[0] * c[0] + m[1] * c[2],
        m[0] * c[1] + m[1] * c[3],
        m[2] * c[0] + m[3] * c[2],
        m[2] * c[1] + m[3] * c[3],
        m[4] * c[0] + m[5] * c[2] + c[4],
        m[4] * c[1] + m[5] * c[3] + c[5],
    ]
}

fn matrix_of(nums: &[f64]) -> Option<Matrix> {
    (nums.len() >= 6).then(|| [nums[0], nums[1], nums[2], nums[3], nums[4], nums[5]])
}

fn matrix_of_obj(value: Option<&Obj>) -> Matrix {
    let numbers: Option<Vec<f64>> = match value {
        Some(Obj::Array(items)) => items.iter().map(Obj::as_f64).collect(),
        _ => None,
    };
    numbers.as_deref().and_then(matrix_of).unwrap_or(IDENTITY)
}

// ---------------------------------------------------------------------------------------------
// Content stream tokens

enum Operand {
    Num(f64),
    Name(Vec<u8>),
    Str(Vec<u8>),
    Array(Vec<Operand>),
    Other,
}

enum Token {
    Operand(Operand),
    Op(Vec<u8>),
    ArrayEnd,
}

fn is_space(byte: u8) -> bool {
    matches!(byte, b' ' | b'\t' | b'\n' | b'\r' | 0x0C | 0)
}

fn is_delimiter(byte: u8) -> bool {
    matches!(byte, b'(' | b')' | b'<' | b'>' | b'[' | b']' | b'{' | b'}' | b'/' | b'%')
}

struct Content<'a> {
    data: &'a [u8],
    at: usize,
    nesting: usize,
}

impl<'a> Content<'a> {
    fn new(data: &'a [u8]) -> Self {
        Self { data, at: 0, nesting: 0 }
    }

    fn next_token(&mut self) -> Option<Token> {
        loop {
            self.skip_space();
            let byte = *self.data.get(self.at)?;
            match byte {
                b'/' => {
                    self.at += 1;
                    return Some(Token::Operand(Operand::Name(self.regular())));
                }
                b'(' => return Some(Token::Operand(Operand::Str(self.literal()))),
                b'<' if self.data.get(self.at + 1) == Some(&b'<') => {
                    self.skip_dict();
                    return Some(Token::Operand(Operand::Other));
                }
                b'<' => return Some(Token::Operand(Operand::Str(self.hex()))),
                b'[' => {
                    self.at += 1;
                    return Some(Token::Operand(self.array()));
                }
                b']' => {
                    self.at += 1;
                    return Some(Token::ArrayEnd);
                }
                b'{' | b'}' | b')' | b'>' => self.at += 1,
                _ => {
                    let word = self.regular();
                    if word.is_empty() {
                        self.at += 1;
                        continue;
                    }
                    return Some(classify(&word));
                }
            }
        }
    }

    fn skip_space(&mut self) {
        while self.at < self.data.len() {
            let byte = self.data[self.at];
            if is_space(byte) {
                self.at += 1;
            } else if byte == b'%' {
                while self.at < self.data.len() && !matches!(self.data[self.at], b'\n' | b'\r') {
                    self.at += 1;
                }
            } else {
                break;
            }
        }
    }

    fn regular(&mut self) -> Vec<u8> {
        let start = self.at;
        while self.at < self.data.len() && !is_space(self.data[self.at]) && !is_delimiter(self.data[self.at]) {
            self.at += 1;
        }
        self.data[start..self.at].to_vec()
    }

    /// A literal string starting at `(`, with escapes and nested parentheses resolved.
    fn literal(&mut self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut depth = 0usize;
        while let Some(&byte) = self.data.get(self.at) {
            self.at += 1;
            match byte {
                b'(' => {
                    depth += 1;
                    if depth > 1 {
                        out.push(byte);
                    }
                }
                b')' => {
                    depth = depth.saturating_sub(1);
                    if depth == 0 {
                        break;
                    }
                    out.push(byte);
                }
                b'\\' => {
                    let Some(&escaped) = self.data.get(self.at) else { break };
                    self.at += 1;
                    match escaped {
                        b'n' => out.push(b'\n'),
                        b'r' => out.push(b'\r'),
                        b't' => out.push(b'\t'),
                        b'b' => out.push(8),
                        b'f' => out.push(12),
                        b'0'..=b'7' => {
                            let mut value = u32::from(escaped - b'0');
                            for _ in 0..2 {
                                match self.data.get(self.at) {
                                    Some(&digit @ b'0'..=b'7') => {
                                        value = value * 8 + u32::from(digit - b'0');
                                        self.at += 1;
                                    }
                                    _ => break,
                                }
                            }
                            out.push(value as u8);
                        }
                        b'\r' => {
                            if self.data.get(self.at) == Some(&b'\n') {
                                self.at += 1;
                            }
                        }
                        b'\n' => {}
                        other => out.push(other),
                    }
                }
                _ => out.push(byte),
            }
        }
        out
    }

    /// A hex string starting at `<`.
    fn hex(&mut self) -> Vec<u8> {
        self.at += 1;
        let start = self.at;
        let end = self.data[start..].iter().position(|b| *b == b'>').map_or(self.data.len(), |p| start + p);
        self.at = (end + 1).min(self.data.len());
        hex_bytes(&self.data[start..end])
    }

    /// An array after its `[`. Nested arrays are kept up to a depth limit, beyond which they are
    /// skipped bytewise so that hostile nesting cannot exhaust the stack.
    fn array(&mut self) -> Operand {
        if self.nesting >= MAX_ARRAY_NESTING {
            let mut depth = 1usize;
            while self.at < self.data.len() && depth > 0 {
                match self.data[self.at] {
                    b'[' => depth += 1,
                    b']' => depth -= 1,
                    _ => {}
                }
                self.at += 1;
            }
            return Operand::Other;
        }
        self.nesting += 1;
        let mut items = Vec::new();
        loop {
            match self.next_token() {
                None | Some(Token::ArrayEnd) => break,
                Some(Token::Operand(operand)) => {
                    if items.len() < MAX_ARRAY_ITEMS {
                        items.push(operand);
                    }
                }
                Some(Token::Op(_)) => {}
            }
        }
        self.nesting -= 1;
        Operand::Array(items)
    }

    fn skip_dict(&mut self) {
        self.at += 2;
        let mut depth = 1usize;
        while self.at < self.data.len() && depth > 0 {
            if self.data[self.at..].starts_with(b"<<") {
                depth += 1;
                self.at += 2;
            } else if self.data[self.at..].starts_with(b">>") {
                depth -= 1;
                self.at += 2;
            } else {
                self.at += 1;
            }
        }
    }

    /// Skips inline image data after `ID`, up to a whitespace-delimited `EI`.
    fn skip_inline_image(&mut self) {
        let data = self.data;
        let mut at = self.at;
        while at + 1 < data.len() {
            let before_ok = at == 0 || is_space(data[at - 1]);
            let after_ok = data.get(at + 2).is_none_or(|b| is_space(*b) || is_delimiter(*b));
            if data[at] == b'E' && data[at + 1] == b'I' && before_ok && after_ok {
                self.at = at + 2;
                return;
            }
            at += 1;
        }
        self.at = data.len();
    }
}

fn classify(word: &[u8]) -> Token {
    let first = word[0];
    if first.is_ascii_digit() || matches!(first, b'+' | b'-' | b'.') {
        let number = std::str::from_utf8(word).ok().and_then(|text| text.parse::<f64>().ok());
        return match number {
            Some(value) if value.is_finite() => Token::Operand(Operand::Num(value)),
            _ => Token::Operand(Operand::Other),
        };
    }
    match word {
        b"true" | b"false" | b"null" => Token::Operand(Operand::Other),
        _ => Token::Op(word.to_vec()),
    }
}

fn numbers(operands: &[Operand]) -> Vec<f64> {
    operands
        .iter()
        .filter_map(|operand| match operand {
            Operand::Num(value) => Some(*value),
            _ => None,
        })
        .collect()
}

fn name_of(operands: &[Operand]) -> Option<&[u8]> {
    operands.iter().find_map(|operand| match operand {
        Operand::Name(name) => Some(name.as_slice()),
        _ => None,
    })
}

fn string_of(operands: &[Operand]) -> Option<&[u8]> {
    operands.iter().find_map(|operand| match operand {
        Operand::Str(bytes) => Some(bytes.as_slice()),
        _ => None,
    })
}

fn array_of(operands: &[Operand]) -> Option<&[Operand]> {
    operands.iter().find_map(|operand| match operand {
        Operand::Array(items) => Some(items.as_slice()),
        _ => None,
    })
}

// ---------------------------------------------------------------------------------------------
// Page walk

#[derive(Clone)]
struct Graphics {
    ctm: Matrix,
    font: Option<Rc<Font>>,
    size: f64,
    char_space: f64,
    word_space: f64,
    scale: f64,
    leading: f64,
}

struct Runner<'c> {
    cancel: &'c CancelToken,
    gs: Graphics,
    saved: Vec<Graphics>,
    tm: Matrix,
    tlm: Matrix,
    fonts: HashMap<u32, Rc<Font>>,
    out: String,
    pending_space: bool,
    last_end: Option<(f64, f64)>,
    operators: usize,
    truncated: bool,
}

impl<'c> Runner<'c> {
    fn new(cancel: &'c CancelToken) -> Self {
        Self {
            cancel,
            gs: Graphics {
                ctm: IDENTITY,
                font: None,
                size: 0.0,
                char_space: 0.0,
                word_space: 0.0,
                scale: 1.0,
                leading: 0.0,
            },
            saved: Vec::new(),
            tm: IDENTITY,
            tlm: IDENTITY,
            fonts: HashMap::new(),
            out: String::new(),
            pending_space: false,
            last_end: None,
            operators: 0,
            truncated: false,
        }
    }

    fn finish(self) -> String {
        self.out
    }

    fn run<R: Read + Seek>(&mut self, file: &mut PdfFile<R>, data: &[u8], resources: &Dict, depth: usize) -> Result<()> {
        let mut lexer = Content::new(data);
        let mut operands: Vec<Operand> = Vec::new();
        while let Some(token) = lexer.next_token() {
            match token {
                Token::Operand(operand) => {
                    if operands.len() < 64 {
                        operands.push(operand);
                    }
                }
                Token::ArrayEnd => {}
                Token::Op(op) => {
                    self.operators += 1;
                    if self.operators > MAX_OPERATORS_PER_PAGE {
                        self.truncated = true;
                        return Ok(());
                    }
                    if self.operators % 1024 == 0 {
                        self.cancel.check()?;
                    }
                    if op.as_slice() == b"ID" {
                        lexer.skip_inline_image();
                    } else {
                        self.exec(file, &op, &operands, resources, depth)?;
                    }
                    operands.clear();
                }
            }
        }
        Ok(())
    }

    fn exec<R: Read + Seek>(
        &mut self,
        file: &mut PdfFile<R>,
        op: &[u8],
        operands: &[Operand],
        resources: &Dict,
        depth: usize,
    ) -> Result<()> {
        let nums = numbers(operands);
        match op {
            b"BT" => {
                self.tm = IDENTITY;
                self.tlm = IDENTITY;
            }
            b"q" => {
                if self.saved.len() < 256 {
                    self.saved.push(self.gs.clone());
                }
            }
            b"Q" => {
                if let Some(gs) = self.saved.pop() {
                    self.gs = gs;
                }
            }
            b"cm" => {
                if let Some(m) = matrix_of(&nums) {
                    self.gs.ctm = multiply(m, self.gs.ctm);
                }
            }
            b"Tf" => {
                if let Some(name) = name_of(operands) {
                    let font = self.font_named(file, resources, name)?;
                    self.gs.font = font;
                }
                if let Some(size) = nums.first() {
                    self.gs.size = *size;
                }
            }
            b"Tc" => {
                if let Some(value) = nums.first() {
                    self.gs.char_space = *value;
                }
            }
            b"Tw" => {
                if let Some(value) = nums.first() {
                    self.gs.word_space = *value;
                }
            }
            b"Tz" => {
                if let Some(value) = nums.first() {
                    self.gs.scale = *value / 100.0;
                }
            }
            b"TL" => {
                if let Some(value) = nums.first() {
                    self.gs.leading = *value;
                }
            }
            b"Td" => {
                if nums.len() >= 2 {
                    self.move_line(nums[0], nums[1]);
                }
            }
            b"TD" => {
                if nums.len() >= 2 {
                    self.gs.leading = -nums[1];
                    self.move_line(nums[0], nums[1]);
                }
            }
            b"Tm" => {
                if let Some(m) = matrix_of(&nums) {
                    self.tm = m;
                    self.tlm = m;
                }
            }
            b"T*" => {
                let leading = self.gs.leading;
                self.move_line(0.0, -leading);
            }
            b"Tj" => {
                if let Some(bytes) = string_of(operands) {
                    self.show(bytes);
                }
            }
            b"TJ" => {
                if let Some(items) = array_of(operands) {
                    for item in items {
                        match item {
                            Operand::Str(bytes) => self.show(bytes),
                            Operand::Num(adjust) => self.adjust(*adjust),
                            _ => {}
                        }
                    }
                }
            }
            b"'" => {
                let leading = self.gs.leading;
                self.move_line(0.0, -leading);
                if let Some(bytes) = string_of(operands) {
                    self.show(bytes);
                }
            }
            b"\"" => {
                if nums.len() >= 2 {
                    self.gs.word_space = nums[0];
                    self.gs.char_space = nums[1];
                }
                let leading = self.gs.leading;
                self.move_line(0.0, -leading);
                if let Some(bytes) = string_of(operands) {
                    self.show(bytes);
                }
            }
            b"Do" => {
                if let Some(name) = name_of(operands) {
                    self.draw_form(file, name, resources, depth)?;
                }
            }
            _ => {}
        }
        Ok(())
    }

    fn move_line(&mut self, tx: f64, ty: f64) {
        self.tlm = multiply([1.0, 0.0, 0.0, 1.0, tx, ty], self.tlm);
        self.tm = self.tlm;
    }

    /// A TJ number: moves the text position, and a large negative one is a visible space.
    fn adjust(&mut self, amount: f64) {
        let tx = -amount / 1000.0 * self.gs.size * self.gs.scale;
        self.tm = multiply([1.0, 0.0, 0.0, 1.0, tx, 0.0], self.tm);
        if amount < -KERNING_SPACE {
            self.pending_space = true;
        }
    }

    fn show(&mut self, bytes: &[u8]) {
        let Some(font) = self.gs.font.clone() else { return };
        if self.out.len() > MAX_PAGE_BYTES {
            self.truncated = true;
            return;
        }
        let width = font.code_bytes;
        let mut index = 0usize;
        while index < bytes.len() {
            let end = (index + width).min(bytes.len());
            let code = bytes[index..end].iter().fold(0u32, |acc, byte| (acc << 8) | u32::from(*byte));
            index = end;

            let before = multiply(self.tm, self.gs.ctm);
            let origin = (before[4], before[5]);
            let size = self.device_size(&before);
            let mut advance = font.width(code) * self.gs.size + self.gs.char_space;
            if font.code_bytes == 1 && code == 32 {
                advance += self.gs.word_space;
            }
            advance *= self.gs.scale;
            self.tm = multiply([1.0, 0.0, 0.0, 1.0, advance, 0.0], self.tm);
            let after = multiply(self.tm, self.gs.ctm);
            self.place(origin, size, font.text(code), (after[4], after[5]));
        }
    }

    /// The glyph size on the page, from the text-to-device matrix.
    fn device_size(&self, matrix: &Matrix) -> f64 {
        let scale = (matrix[0] * matrix[3] - matrix[1] * matrix[2]).abs().sqrt();
        (self.gs.size.abs() * scale).max(1e-6)
    }

    fn place(&mut self, origin: (f64, f64), size: f64, glyph: Option<String>, end: (f64, f64)) {
        if let Some((prev_x, prev_y)) = self.last_end {
            if (origin.1 - prev_y).abs() > LINE_GAP_EM * size {
                self.newline();
            } else if origin.0 - prev_x > WORD_GAP_EM * size {
                self.pending_space = true;
            }
        }
        self.last_end = Some(end);
        if let Some(text) = glyph {
            if !text.is_empty() {
                if self.pending_space && !self.out.is_empty() && !self.out.ends_with(char::is_whitespace) {
                    self.out.push(' ');
                }
                self.pending_space = false;
                self.out.push_str(&text);
            }
        }
    }

    fn newline(&mut self) {
        if !self.out.is_empty() && !self.out.ends_with('\n') {
            self.out.push('\n');
        }
        self.pending_space = false;
    }

    fn font_named<R: Read + Seek>(
        &mut self,
        file: &mut PdfFile<R>,
        resources: &Dict,
        name: &[u8],
    ) -> Result<Option<Rc<Font>>> {
        let Some(value) = lookup(file, resources, "Font", name)? else { return Ok(None) };
        if let Obj::Ref(number, _) = &value {
            let number = *number;
            if let Some(font) = self.fonts.get(&number) {
                return Ok(Some(Rc::clone(font)));
            }
            let font = Rc::new(load_font(file, &value)?);
            self.fonts.insert(number, Rc::clone(&font));
            return Ok(Some(font));
        }
        Ok(Some(Rc::new(load_font(file, &value)?)))
    }

    /// Draws a Form XObject: its content runs with the form matrix, its own resources (or the
    /// caller's), and the graphics and text state restored afterwards. Images are ignored.
    fn draw_form<R: Read + Seek>(
        &mut self,
        file: &mut PdfFile<R>,
        name: &[u8],
        resources: &Dict,
        depth: usize,
    ) -> Result<()> {
        if depth >= MAX_FORM_DEPTH {
            return Ok(());
        }
        let Some(Obj::Ref(number, _)) = lookup(file, resources, "XObject", name)? else { return Ok(()) };
        let loaded = file.load(number)?;
        let Obj::Dict(dict) = loaded.value else { return Ok(()) };
        if dict.get("Subtype").and_then(Obj::as_name) != Some(&b"Form"[..]) {
            return Ok(());
        }
        let Some(data) = stream_data(file, number)? else { return Ok(()) };
        let form_resources = match dict.get("Resources").cloned() {
            Some(value) => resolve_dict(file, &value)?.unwrap_or_else(|| resources.clone()),
            None => resources.clone(),
        };
        let matrix = matrix_of_obj(dict.get("Matrix"));

        let saved_gs = self.gs.clone();
        let saved_text = (self.tm, self.tlm);
        self.gs.ctm = multiply(matrix, self.gs.ctm);
        self.run(file, &data, &form_resources, depth + 1)?;
        self.gs = saved_gs;
        self.tm = saved_text.0;
        self.tlm = saved_text.1;
        Ok(())
    }
}

// ---------------------------------------------------------------------------------------------
// Fonts

#[derive(Clone, Copy)]
enum Base {
    WinAnsi,
    MacRoman,
    Standard,
}

impl Base {
    fn char_of(self, code: u8) -> Option<char> {
        match code {
            0x20..=0x7E => Some(match (self, code) {
                (Base::Standard, 0x27) => '\u{2019}',
                (Base::Standard, 0x60) => '\u{2018}',
                _ => char::from(code),
            }),
            0x80..=0xFF => match self {
                Base::WinAnsi => winansi_high(code),
                Base::MacRoman => mac_roman_high(code),
                Base::Standard => None,
            },
            _ => None,
        }
    }
}

/// Windows-1252 for 0x80 to 0x9F (0 where undefined); Latin-1 above.
const CP1252: [u16; 32] = [
    0x20AC, 0, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0, 0x017D, 0,
    0, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0, 0x017E,
    0x0178,
];

fn winansi_high(code: u8) -> Option<char> {
    let unit = if code < 0xA0 { CP1252[usize::from(code - 0x80)] } else { u16::from(code) };
    if unit == 0 { None } else { char::from_u32(u32::from(unit)) }
}

/// MacRomanEncoding for 0x80 to 0xFF (0 where undefined).
const MAC_ROMAN_HIGH: [u16; 128] = [
    0x00C4, 0x00C5, 0x00C7, 0x00C9, 0x00D1, 0x00D6, 0x00DC, 0x00E1, 0x00E0, 0x00E2, 0x00E4, 0x00E3, 0x00E5, 0x00E7,
    0x00E9, 0x00E8, 0x00EA, 0x00EB, 0x00ED, 0x00EC, 0x00EE, 0x00EF, 0x00F1, 0x00F3, 0x00F2, 0x00F4, 0x00F6, 0x00F5,
    0x00FA, 0x00F9, 0x00FB, 0x00FC, 0x2020, 0x00B0, 0x00A2, 0x00A3, 0x00A7, 0x2022, 0x00B6, 0x00DF, 0x00AE, 0x00A9,
    0x2122, 0x00B4, 0x00A8, 0x2260, 0x00C6, 0x00D8, 0x221E, 0x00B1, 0x2264, 0x2265, 0x00A5, 0x00B5, 0x2202, 0x2211,
    0x220F, 0x03C0, 0x222B, 0x00AA, 0x00BA, 0x03A9, 0x00E6, 0x00F8, 0x00BF, 0x00A1, 0x00AC, 0x221A, 0x0192, 0x2248,
    0x2206, 0x00AB, 0x00BB, 0x2026, 0x00A0, 0x00C0, 0x00C3, 0x00D5, 0x0152, 0x0153, 0x2013, 0x2014, 0x201C, 0x201D,
    0x2018, 0x2019, 0x00F7, 0x25CA, 0x00FF, 0x0178, 0x2044, 0x20AC, 0x2039, 0x203A, 0xFB01, 0xFB02, 0x2021, 0x00B7,
    0x201A, 0x201E, 0x2030, 0x00C2, 0x00CA, 0x00C1, 0x00CB, 0x00C8, 0x00CD, 0x00CE, 0x00CF, 0x00CC, 0x00D3, 0x00D4,
    0, 0x00D2, 0x00DA, 0x00DB, 0x00D9, 0x0131, 0x02C6, 0x02DC, 0x00AF, 0x02D8, 0x02D9, 0x02DA, 0x00B8, 0x02DD,
    0x02DB, 0x02C7,
];

fn mac_roman_high(code: u8) -> Option<char> {
    let unit = MAC_ROMAN_HIGH[usize::from(code - 0x80)];
    if unit == 0 { None } else { char::from_u32(u32::from(unit)) }
}

/// Glyph names that are not single letters or digits, with their code points.
const GLYPH_NAMES: &[(&str, u32)] = &[
    ("space", 0x20), ("exclam", 0x21), ("quotedbl", 0x22), ("numbersign", 0x23), ("dollar", 0x24),
    ("percent", 0x25), ("ampersand", 0x26), ("quotesingle", 0x27), ("parenleft", 0x28), ("parenright", 0x29),
    ("asterisk", 0x2A), ("plus", 0x2B), ("comma", 0x2C), ("hyphen", 0x2D), ("period", 0x2E), ("slash", 0x2F),
    ("zero", 0x30), ("one", 0x31), ("two", 0x32), ("three", 0x33), ("four", 0x34), ("five", 0x35), ("six", 0x36),
    ("seven", 0x37), ("eight", 0x38), ("nine", 0x39), ("colon", 0x3A), ("semicolon", 0x3B), ("less", 0x3C),
    ("equal", 0x3D), ("greater", 0x3E), ("question", 0x3F), ("at", 0x40), ("bracketleft", 0x5B),
    ("backslash", 0x5C), ("bracketright", 0x5D), ("asciicircum", 0x5E), ("underscore", 0x5F), ("grave", 0x60),
    ("braceleft", 0x7B), ("bar", 0x7C), ("braceright", 0x7D), ("asciitilde", 0x7E), ("nbspace", 0xA0),
    ("exclamdown", 0xA1), ("cent", 0xA2), ("sterling", 0xA3), ("currency", 0xA4), ("yen", 0xA5), ("brokenbar", 0xA6),
    ("section", 0xA7), ("dieresis", 0xA8), ("copyright", 0xA9), ("ordfeminine", 0xAA), ("guillemotleft", 0xAB),
    ("logicalnot", 0xAC), ("registered", 0xAE), ("macron", 0xAF), ("degree", 0xB0), ("plusminus", 0xB1),
    ("twosuperior", 0xB2), ("threesuperior", 0xB3), ("acute", 0xB4), ("mu", 0xB5), ("paragraph", 0xB6),
    ("periodcentered", 0xB7), ("cedilla", 0xB8), ("onesuperior", 0xB9), ("ordmasculine", 0xBA),
    ("guillemotright", 0xBB), ("onequarter", 0xBC), ("onehalf", 0xBD), ("threequarters", 0xBE),
    ("questiondown", 0xBF), ("multiply", 0xD7), ("divide", 0xF7), ("florin", 0x192), ("circumflex", 0x2C6),
    ("caron", 0x2C7), ("breve", 0x2D8), ("dotaccent", 0x2D9), ("ring", 0x2DA), ("ogonek", 0x2DB),
    ("tilde", 0x2DC), ("hungarumlaut", 0x2DD), ("endash", 0x2013), ("emdash", 0x2014), ("quoteleft", 0x2018),
    ("quoteright", 0x2019), ("quotesinglbase", 0x201A), ("quotedblleft", 0x201C), ("quotedblright", 0x201D),
    ("quotedblbase", 0x201E), ("dagger", 0x2020), ("daggerdbl", 0x2021), ("bullet", 0x2022), ("ellipsis", 0x2026),
    ("perthousand", 0x2030), ("guilsinglleft", 0x2039), ("guilsinglright", 0x203A), ("fraction", 0x2044),
    ("Euro", 0x20AC), ("euro", 0x20AC), ("trademark", 0x2122), ("OE", 0x152), ("oe", 0x153), ("Scaron", 0x160),
    ("scaron", 0x161), ("Zcaron", 0x17D), ("zcaron", 0x17E), ("Ydieresis", 0x178), ("fi", 0xFB01), ("fl", 0xFB02),
    ("minus", 0x2212),
];

/// Latin-1 letters from code 0xC0 upward, by their standard names (0xD7 and 0xF7 are in the
/// table above, so the names here are the letters only, in code order, with the two signs).
const LATIN1_NAMES: [&str; 64] = [
    "Agrave", "Aacute", "Acircumflex", "Atilde", "Adieresis", "Aring", "AE", "Ccedilla", "Egrave", "Eacute",
    "Ecircumflex", "Edieresis", "Igrave", "Iacute", "Icircumflex", "Idieresis", "Eth", "Ntilde", "Ograve", "Oacute",
    "Ocircumflex", "Otilde", "Odieresis", "multiply", "Oslash", "Ugrave", "Uacute", "Ucircumflex", "Udieresis",
    "Yacute", "Thorn", "germandbls", "agrave", "aacute", "acircumflex", "atilde", "adieresis", "aring", "ae",
    "ccedilla", "egrave", "eacute", "ecircumflex", "edieresis", "igrave", "iacute", "icircumflex", "idieresis",
    "eth", "ntilde", "ograve", "oacute", "ocircumflex", "otilde", "odieresis", "divide", "oslash", "ugrave",
    "uacute", "ucircumflex", "udieresis", "yacute", "thorn", "ydieresis",
];

/// The character a glyph name stands for: a letter or digit, `uniXXXX`/`uXXXX`, or a known name.
fn glyph_char(name: &[u8]) -> Option<char> {
    if let [single] = name {
        if single.is_ascii_alphanumeric() {
            return Some(char::from(*single));
        }
    }
    let text = std::str::from_utf8(name).ok()?;
    let hex = text
        .strip_prefix("uni")
        .filter(|hex| hex.len() == 4)
        .or_else(|| text.strip_prefix('u').filter(|hex| (4..=6).contains(&hex.len())));
    if let Some(ch) = hex.and_then(|hex| u32::from_str_radix(hex, 16).ok()).and_then(char::from_u32) {
        return Some(ch);
    }
    if let Some(index) = LATIN1_NAMES.iter().position(|candidate| *candidate == text) {
        return char::from_u32(0xC0 + index as u32);
    }
    GLYPH_NAMES.iter().find(|(candidate, _)| *candidate == text).and_then(|(_, code)| char::from_u32(*code))
}

/// The byte-to-character table of a simple font: base encoding, then /Differences.
fn simple_encoding<R: Read + Seek>(file: &mut PdfFile<R>, dict: &Dict, truetype: bool) -> Result<Vec<Option<char>>> {
    let encoding = match dict.get("Encoding").cloned() {
        Some(value) => file.resolve(&value)?,
        None => Obj::Null,
    };
    let (base_name, differences) = match &encoding {
        Obj::Name(name) => (Some(name.clone()), None),
        Obj::Dict(entries) => (
            entries.get("BaseEncoding").and_then(Obj::as_name).map(<[u8]>::to_vec),
            entries.get("Differences").cloned(),
        ),
        _ => (None, None),
    };
    let base = match base_name.as_deref() {
        Some(b"WinAnsiEncoding") => Base::WinAnsi,
        Some(b"MacRomanEncoding") => Base::MacRoman,
        Some(b"StandardEncoding") => Base::Standard,
        _ if truetype => Base::WinAnsi,
        _ => Base::Standard,
    };
    let mut table: Vec<Option<char>> = (0..=255u8).map(|code| base.char_of(code)).collect();
    if let Some(value) = differences {
        if let Obj::Array(items) = file.resolve(&value)? {
            let mut cursor = 0usize;
            for item in &items {
                match item {
                    Obj::Num(_) => cursor = item.as_i64().unwrap_or(0).clamp(0, 255) as usize,
                    Obj::Name(name) => {
                        if let Some(slot) = table.get_mut(cursor) {
                            *slot = glyph_char(name);
                        }
                        cursor += 1;
                    }
                    _ => {}
                }
            }
        }
    }
    Ok(table)
}

struct Font {
    composite: bool,
    code_bytes: usize,
    to_unicode: Option<CMap>,
    encoding: Vec<Option<char>>,
    widths: HashMap<u32, f64>,
    default_width: f64,
}

impl Font {
    /// Advance width in text-space units (1 is one em).
    fn width(&self, code: u32) -> f64 {
        self.widths.get(&code).copied().unwrap_or(self.default_width)
    }

    /// The text a code shows, if it maps to any character.
    fn text(&self, code: u32) -> Option<String> {
        if let Some(text) = self.to_unicode.as_ref().and_then(|map| map.get(code)) {
            return Some(text.to_owned());
        }
        if self.composite {
            return None;
        }
        self.encoding.get(code as usize).copied().flatten().map(|ch| ch.to_string())
    }
}

fn load_font<R: Read + Seek>(file: &mut PdfFile<R>, value: &Obj) -> Result<Font> {
    let dict = match file.resolve(value)? {
        Obj::Dict(dict) => dict,
        _ => Dict::default(),
    };
    let subtype: Vec<u8> = dict.get("Subtype").and_then(Obj::as_name).map(<[u8]>::to_vec).unwrap_or_default();
    let to_unicode = match dict.get("ToUnicode").cloned() {
        Some(Obj::Ref(number, _)) => stream_data(file, number)?.map(|data| CMap::parse(&data)),
        _ => None,
    };

    if subtype.as_slice() == b"Type0" {
        let descendant = match dict.get("DescendantFonts").cloned() {
            Some(value) => match file.resolve(&value)? {
                Obj::Array(items) => items.into_iter().next(),
                _ => None,
            },
            None => None,
        };
        let cid = match descendant {
            Some(value) => resolve_dict(file, &value)?.unwrap_or_default(),
            None => Dict::default(),
        };
        let widths = match cid.get("W").cloned() {
            Some(value) => parse_w(&file.resolve(&value)?),
            None => HashMap::new(),
        };
        let default_width = cid.get("DW").and_then(Obj::as_f64).unwrap_or(1000.0) / 1000.0;
        let code_bytes = to_unicode.as_ref().and_then(|cmap| cmap.width).unwrap_or(2);
        return Ok(Font { composite: true, code_bytes, to_unicode, encoding: Vec::new(), widths, default_width });
    }

    let encoding = simple_encoding(file, &dict, subtype.as_slice() == b"TrueType")?;
    let mut widths = HashMap::new();
    if let (Some(first), Some(list)) = (dict.get("FirstChar").and_then(Obj::as_i64), dict.get("Widths").cloned()) {
        if let Obj::Array(items) = file.resolve(&list)? {
            for (offset, item) in items.iter().enumerate() {
                if let Some(width) = item.as_f64() {
                    let code = (first.max(0) as u32).saturating_add(offset as u32);
                    widths.insert(code, width / 1000.0);
                }
            }
        }
    }
    Ok(Font { composite: false, code_bytes: 1, to_unicode, encoding, widths, default_width: 0.5 })
}

/// A CID `/W` array: `c [w1 w2 ...]` or `cfirst clast w`.
fn parse_w(value: &Obj) -> HashMap<u32, f64> {
    let mut widths = HashMap::new();
    let Obj::Array(items) = value else { return widths };
    let mut i = 0usize;
    while i < items.len() {
        let Some(first) = items[i].as_i64() else {
            i += 1;
            continue;
        };
        let first = first.max(0) as u32;
        if let Some(Obj::Array(list)) = items.get(i + 1) {
            for (offset, width) in list.iter().enumerate() {
                if let Some(width) = width.as_f64() {
                    widths.insert(first.saturating_add(offset as u32), width / 1000.0);
                }
            }
            i += 2;
        } else {
            let last = items.get(i + 1).and_then(Obj::as_i64);
            let width = items.get(i + 2).and_then(Obj::as_f64);
            if let (Some(last), Some(width)) = (last, width) {
                let last = last.max(0) as u32;
                if last >= first && last - first <= 65_535 {
                    for code in first..=last {
                        widths.insert(code, width / 1000.0);
                    }
                }
            }
            i += 3;
        }
    }
    widths
}

// ---------------------------------------------------------------------------------------------
// ToUnicode CMaps

/// Code-to-text map from a ToUnicode stream.
#[derive(Default)]
struct CMap {
    map: HashMap<u32, String>,
    /// Byte length of the codes, from the codespace ranges.
    width: Option<usize>,
    /// Units decoded so far, against [MAX_CMAP_WORK].
    work: usize,
}

enum CTok {
    Hex(Vec<u8>),
    Word(String),
    Open,
    Close,
}

impl CTok {
    fn word(&self) -> Option<&str> {
        match self {
            CTok::Word(word) => Some(word),
            _ => None,
        }
    }
}

impl CMap {
    fn get(&self, code: u32) -> Option<&str> {
        self.map.get(&code).map(String::as_str)
    }

    /// Charges one entry of `units` units; false once the budget is spent.
    fn spend(&mut self, units: usize) -> bool {
        self.work = self.work.saturating_add(units + 1);
        self.work <= MAX_CMAP_WORK
    }

    fn put(&mut self, code: u32, units: &[u16]) {
        let units = &units[..units.len().min(MAX_CMAP_DEST_UNITS)];
        if self.map.len() < MAX_CMAP_ENTRIES && self.spend(units.len()) {
            self.map.insert(code, units_text(units));
        }
    }

    fn add_range(&mut self, lo: u32, hi: u32, units: &[u16]) {
        if hi < lo || hi - lo > 65_535 {
            return;
        }
        let mut shifted = units[..units.len().min(MAX_CMAP_DEST_UNITS)].to_vec();
        let base = shifted.last().copied();
        for code in lo..=hi {
            if self.work > MAX_CMAP_WORK {
                return;
            }
            if let (Some(last), Some(base)) = (shifted.last_mut(), base) {
                *last = base.wrapping_add((code - lo) as u16);
            }
            self.put(code, &shifted);
        }
    }

    fn parse(data: &[u8]) -> CMap {
        let tokens = cmap_tokens(data);
        let mut cmap = CMap::default();
        let mut i = 0usize;
        while i < tokens.len() {
            match tokens[i].word() {
                Some("begincodespacerange") => {
                    i += 1;
                    while i < tokens.len() && tokens[i].word() != Some("endcodespacerange") {
                        if let CTok::Hex(bytes) = &tokens[i] {
                            cmap.width.get_or_insert(bytes.len());
                        }
                        i += 1;
                    }
                }
                Some("beginbfchar") => {
                    i += 1;
                    while i < tokens.len() && tokens[i].word() != Some("endbfchar") {
                        if let (CTok::Hex(src), Some(CTok::Hex(dst))) = (&tokens[i], tokens.get(i + 1)) {
                            cmap.put(hex_value(src), &utf16_units(dst));
                        }
                        i += 2;
                    }
                }
                Some("beginbfrange") => {
                    i += 1;
                    while i < tokens.len() && tokens[i].word() != Some("endbfrange") {
                        let (Some(CTok::Hex(lo)), Some(CTok::Hex(hi))) = (tokens.get(i), tokens.get(i + 1)) else {
                            i += 1;
                            continue;
                        };
                        let (lo, hi) = (hex_value(lo), hex_value(hi));
                        match tokens.get(i + 2) {
                            Some(CTok::Hex(dst)) => {
                                cmap.add_range(lo, hi, &utf16_units(dst));
                                i += 3;
                            }
                            Some(CTok::Open) => {
                                let mut j = i + 3;
                                let mut code = lo;
                                while let Some(CTok::Hex(dst)) = tokens.get(j) {
                                    cmap.put(code, &utf16_units(dst));
                                    code = code.saturating_add(1);
                                    j += 1;
                                }
                                while j < tokens.len() && !matches!(tokens[j], CTok::Close) {
                                    j += 1;
                                }
                                i = j + 1;
                            }
                            _ => i += 3,
                        }
                    }
                }
                _ => i += 1,
            }
        }
        cmap
    }
}

fn cmap_tokens(data: &[u8]) -> Vec<CTok> {
    let mut tokens = Vec::new();
    let mut at = 0usize;
    while at < data.len() && tokens.len() < MAX_CMAP_TOKENS {
        let byte = data[at];
        if is_space(byte) {
            at += 1;
        } else if byte == b'%' {
            while at < data.len() && !matches!(data[at], b'\n' | b'\r') {
                at += 1;
            }
        } else if byte == b'<' && data.get(at + 1) == Some(&b'<') {
            tokens.push(CTok::Word("<<".to_owned()));
            at += 2;
        } else if byte == b'>' && data.get(at + 1) == Some(&b'>') {
            at += 2;
        } else if byte == b'<' {
            let start = at + 1;
            let end = data[start..].iter().position(|b| *b == b'>').map_or(data.len(), |p| start + p);
            tokens.push(CTok::Hex(hex_bytes(&data[start..end])));
            at = end + 1;
        } else if byte == b'[' {
            tokens.push(CTok::Open);
            at += 1;
        } else if byte == b']' {
            tokens.push(CTok::Close);
            at += 1;
        } else if byte == b'(' {
            let mut depth = 0usize;
            while at < data.len() {
                match data[at] {
                    b'\\' => at += 1,
                    b'(' => depth += 1,
                    b')' => {
                        depth = depth.saturating_sub(1);
                        if depth == 0 {
                            at += 1;
                            break;
                        }
                    }
                    _ => {}
                }
                at += 1;
            }
        } else {
            let start = at;
            if byte == b'/' {
                at += 1;
            }
            while at < data.len() && !is_space(data[at]) && !is_delimiter(data[at]) {
                at += 1;
            }
            if at == start {
                at += 1;
            } else {
                tokens.push(CTok::Word(String::from_utf8_lossy(&data[start..at]).into_owned()));
            }
        }
    }
    tokens
}

/// Hex digits to bytes; an odd last digit is padded with 0.
fn hex_bytes(text: &[u8]) -> Vec<u8> {
    let digits: Vec<u8> = text.iter().filter_map(|b| (*b as char).to_digit(16).map(|d| d as u8)).collect();
    digits.chunks(2).map(|pair| (pair[0] << 4) | pair.get(1).copied().unwrap_or(0)).collect()
}

fn hex_value(bytes: &[u8]) -> u32 {
    bytes.iter().take(4).fold(0u32, |acc, byte| (acc << 8) | u32::from(*byte))
}

fn utf16_units(bytes: &[u8]) -> Vec<u16> {
    if bytes.len() == 1 {
        return vec![u16::from(bytes[0])];
    }
    bytes.chunks_exact(2).map(|pair| u16::from_be_bytes([pair[0], pair[1]])).collect()
}

/// UTF-16 units to text, dropping unpaired surrogates.
fn units_text(units: &[u16]) -> String {
    char::decode_utf16(units.iter().copied()).filter_map(|decoded| decoded.ok()).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn stream(body: &str) -> String {
        format!("<</Length {}>>\nstream\n{body}\nendstream", body.len())
    }

    /// A small PDF with one page. Object 1 is the catalog, 2 the page tree, 3 the page, 4 the
    /// font, 5 the page content, and `more` holds objects 6 onward.
    fn document(content: &str, font: &str, more: &[String]) -> Vec<u8> {
        let mut bodies = vec![
            "<</Type /Catalog /Pages 2 0 R>>".to_owned(),
            "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 300 400] /Resources <</Font <</F1 4 0 R>> /XObject <</X1 6 0 R>> >> >>"
                .to_owned(),
            "<</Type /Page /Parent 2 0 R /Contents 5 0 R>>".to_owned(),
            font.to_owned(),
            stream(content),
        ];
        bodies.extend(more.iter().cloned());

        let mut out = b"%PDF-1.7\n".to_vec();
        let mut offsets = Vec::new();
        for (index, body) in bodies.iter().enumerate() {
            offsets.push(out.len());
            out.extend_from_slice(format!("{} 0 obj\n{body}\nendobj\n", index + 1).as_bytes());
        }
        let xref_at = out.len();
        out.extend_from_slice(format!("xref\n0 {}\n0000000000 65535 f \n", bodies.len() + 1).as_bytes());
        for offset in offsets {
            out.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
        }
        out.extend_from_slice(
            format!("trailer\n<</Size {} /Root 1 0 R>>\nstartxref\n{xref_at}\n%%EOF\n", bodies.len() + 1).as_bytes(),
        );
        out
    }

    const HELVETICA: &str = "<</Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding>>";

    fn extract(bytes: Vec<u8>) -> DocumentText {
        let limits = TextLimits { max_pages: 100, max_chars: 100_000 };
        extract_text(Cursor::new(bytes), None, limits, &CancelToken::default()).expect("extraction succeeds")
    }

    fn page_text(bytes: Vec<u8>) -> String {
        let doc = extract(bytes);
        assert_eq!(doc.pages.len(), 1);
        doc.pages[0].text.clone()
    }

    #[test]
    fn simple_winansi_text_is_decoded() {
        let content = "BT /F1 12 Tf 72 700 Td (Hello World) Tj ET";
        assert_eq!(page_text(document(content, HELVETICA, &[])), "Hello World");
    }

    #[test]
    fn winansi_high_characters_and_escapes_are_decoded() {
        // \251 is the octal escape for 0xA9, the copyright sign; 0x80 is the euro sign in WinAnsi.
        let content = "BT /F1 12 Tf 72 700 Td (\\251 2026 \\200) Tj ET";
        assert_eq!(page_text(document(content, HELVETICA, &[])), "\u{a9} 2026 \u{20ac}");
    }

    #[test]
    fn tj_kerning_gaps_become_spaces_and_small_kerns_do_not() {
        let content = "BT /F1 12 Tf 72 700 Td [(Hel) -20 (lo) -300 (World)] TJ ET";
        assert_eq!(page_text(document(content, HELVETICA, &[])), "Hello World");
    }

    #[test]
    fn line_moves_break_lines_and_text_matrix_moves_add_spaces() {
        let content = "BT /F1 12 Tf 14 TL 72 700 Td (Line one) Tj 0 -14 Td (Line two) Tj T* (Line three) Tj ET";
        assert_eq!(page_text(document(content, HELVETICA, &[])), "Line one\nLine two\nLine three");
    }

    #[test]
    fn to_unicode_type0_identity_h_text_is_decoded() {
        let font = "<</Type /Font /Subtype /Type0 /BaseFont /Demo /Encoding /Identity-H \
                    /DescendantFonts [<</Type /Font /Subtype /CIDFontType2 /BaseFont /Demo /W [1 [600]] /DW 1000>>] \
                    /ToUnicode 6 0 R>>";
        let cmap = "/CIDInit /ProcSet findresource begin\n1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n\
                    2 beginbfchar\n<0001> <0048>\n<0002> <0069>\nendbfchar\n\
                    1 beginbfrange\n<0003> <0004> <D83DDE00>\nendbfrange\nendcmap";
        // Codes 1 and 2 come from bfchar; 3 and 4 from a bfrange whose last unit steps by one.
        let content = "BT /F1 12 Tf 72 700 Td <00010002 00030004> Tj ET";
        let more = [stream(cmap)];
        assert_eq!(page_text(document(content, font, &more)), "Hi\u{1F600}\u{1F601}");
    }

    #[test]
    fn differences_map_glyph_names_and_skip_unknown_glyphs() {
        let font = "<</Type /Font /Subtype /Type1 /BaseFont /Helvetica \
                    /Encoding <</Differences [65 /Euro /uni00E9 /glyph_nobody 68 /Aacute]>>>>";
        // 65 Euro, 66 uni00E9 (e acute), 67 unknown name (skipped), 68 Aacute.
        let content = "BT /F1 12 Tf 72 700 Td (ABCD) Tj ET";
        assert_eq!(page_text(document(content, font, &[])), "\u{20ac}\u{e9}\u{c1}");
    }

    #[test]
    fn form_xobject_text_is_included_in_order() {
        let form = format!(
            "<</Type /XObject /Subtype /Form /BBox [0 0 300 400] /Length {}>>\nstream\n(Form text) Tj\nendstream",
            "(Form text) Tj".len()
        );
        let content = "BT /F1 12 Tf 72 700 Td (Page text) Tj ET q /X1 Do Q";
        let text = page_text(document(content, HELVETICA, &[form]));
        assert!(text.contains("Page text"), "{text}");
        assert!(text.contains("Form text"), "{text}");
    }

    #[test]
    fn page_limit_marks_the_result_truncated() {
        let mut bytes = document("BT /F1 12 Tf (one) Tj ET", HELVETICA, &[]);
        bytes.shrink_to_fit();
        let doc = extract_text(
            Cursor::new(bytes),
            None,
            TextLimits { max_pages: 0, max_chars: 100 },
            &CancelToken::default(),
        )
        .unwrap();
        assert!(doc.truncated);
        assert!(doc.pages.is_empty());
        assert_eq!(doc.page_count, 1);
    }

    #[test]
    fn character_limit_cuts_the_text() {
        let content = "BT /F1 12 Tf (abcdefghij) Tj ET";
        let doc = extract_text(
            Cursor::new(document(content, HELVETICA, &[])),
            None,
            TextLimits { max_pages: 10, max_chars: 4 },
            &CancelToken::default(),
        )
        .unwrap();
        assert!(doc.truncated);
        assert_eq!(doc.pages[0].text, "abcd");
    }

    #[test]
    fn cancellation_stops_extraction() {
        let cancel = CancelToken::default();
        cancel.cancel();
        let bytes = document("BT /F1 12 Tf (x) Tj ET", HELVETICA, &[]);
        let result = extract_text(Cursor::new(bytes), None, TextLimits { max_pages: 10, max_chars: 100 }, &cancel);
        assert!(result.is_err());
    }

    #[test]
    fn glyph_names_and_hex_helpers() {
        assert_eq!(glyph_char(b"a"), Some('a'));
        assert_eq!(glyph_char(b"uni20AC"), Some('\u{20ac}'));
        assert_eq!(glyph_char(b"uacute"), Some('\u{fa}'));
        assert_eq!(glyph_char(b"Aacute"), Some('\u{c1}'));
        assert_eq!(glyph_char(b"nosuchglyph"), None);
        assert_eq!(hex_bytes(b"00 4 1"), vec![0x00, 0x41]);
        assert_eq!(units_text(&[0xD83D, 0xDE00]), "\u{1F600}");
    }
}
