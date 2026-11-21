//! Text parser: UTF-8 (with or without BOM) and BOM-marked UTF-16.
//!
//! Reads the input incrementally in fixed-size chunks, so memory stays
//! bounded regardless of file size. Malformed sequences decode to U+FFFD.
//!
//! Content detected as text is presented according to the file name (see
//! `OpenContext::name_hint`): Markdown, CSV/TSV, JSON and source code get
//! their own modules; HTML goes to the platform as raw bytes; everything else
//! is one paragraph per line.

use std::io::{ErrorKind as IoErrorKind, Read, SeekFrom};

use document_core::{
    Block, CancelToken, DocumentInfo, DocumentKind, EngineError, Extracted, FormatId, Input, OpenContext, OpenDocument,
    Parser, ReadSeek, Result,
};

use crate::readahead::Chunks;
use crate::stream::Streamed;

const READ_CHUNK: usize = 64 * 1024;

/// Markdown and JSON are parsed whole; larger files fall back to line views.
const MAX_WHOLE_TEXT_BYTES: u64 = 8 * 1024 * 1024;

/// The extension that asks for an HTML file's code rather than its rendering:
/// the name hint is the engine's only input on presentation.
const HTML_SOURCE_EXTENSION: &str = "html-source";
/// Asks for a Markdown file's own text, line-numbered, instead of its rendering.
const MARKDOWN_SOURCE_EXTENSION: &str = "md-source";
/// Plain text as line-numbered source, keeping the ordinary reading view separate.
const TEXT_SOURCE_EXTENSION: &str = "txt-source";

/// How a text file is presented, chosen from its name.
enum Dialect {
    Plain,
    Markdown,
    Json,
    /// Rendered by the platform, up to [`MAX_WHOLE_TEXT_BYTES`]; larger files show as code.
    Html,
    /// `None` sniffs the delimiter from the first line.
    Csv(Option<char>),
    Code(String),
}

impl Dialect {
    fn from_name(name: Option<&str>) -> Self {
        let Some(name) = name else { return Self::Plain };
        let lower = name.to_ascii_lowercase();
        if matches!(lower.as_str(), "dockerfile" | "makefile" | "jenkinsfile" | "gemfile" | "rakefile") {
            return Self::Code(lower);
        }
        let Some((_, ext)) = lower.rsplit_once('.') else { return Self::Plain };
        match ext {
            "md" | "markdown" | "mdown" | "mkd" | "mdx" => Self::Markdown,
            "json" | "geojson" | "jsonc" | "webmanifest" => Self::Json,
            "html" | "htm" | "xhtml" => Self::Html,
            HTML_SOURCE_EXTENSION => Self::Code("html".to_owned()),
            MARKDOWN_SOURCE_EXTENSION => Self::Code("markdown".to_owned()),
            TEXT_SOURCE_EXTENSION => Self::Code("text".to_owned()),
            "csv" | "dsv" | "dat" => Self::Csv(None),
            "tsv" | "tab" => Self::Csv(Some('\t')),
            "psv" => Self::Csv(Some('|')),
            "kt" | "kts" | "java" | "js" | "mjs" | "cjs" | "ts" | "tsx" | "jsx" | "py" | "rb" | "go" | "rs" | "c"
            | "h" | "cpp" | "cc" | "cxx" | "hpp" | "cs" | "swift" | "m" | "mm" | "php" | "sh" | "bash" | "zsh"
            | "fish" | "ps1" | "bat" | "cmd" | "sql" | "yaml" | "yml" | "toml" | "ini" | "cfg" | "conf" | "gradle"
            | "xml" | "xsd" | "svg" | "plist" | "css" | "scss" | "sass" | "less" | "dart" | "lua" | "r" | "scala"
            | "pl" | "vue" | "svelte" | "properties" | "env" | "proto" | "graphql" | "gql" | "tf" | "hs" | "ex"
            | "exs" | "erl" | "clj" | "jl" | "zig" | "nim" | "v" | "asm" | "s" | "diff" | "patch" | "tex" | "jsonl"
            | "ndjson" => Self::Code(ext.to_owned()),
            _ => Self::Plain,
        }
    }
}

/// Decoded text read incrementally from the input, for the dialect modules.
pub(crate) struct TextStream {
    chunks: Chunks,
    decoder: Decoder,
    cancel: CancelToken,
    raw: Vec<u8>,
    text: String,
    pos: usize,
    eof: bool,
}

impl TextStream {
    fn new(reader: Box<dyn ReadSeek>, byte_size: u64, decoder: Decoder, cancel: CancelToken) -> Self {
        Self {
            chunks: Chunks::new(reader, READ_CHUNK, byte_size, &cancel),
            decoder,
            cancel,
            raw: Vec::new(),
            text: String::new(),
            pos: 0,
            eof: false,
        }
    }

    fn fill(&mut self) -> Result<()> {
        self.cancel.check()?;
        self.eof = self.chunks.append_to(&mut self.raw)? == 0;
        if self.pos > 0 {
            self.text.drain(..self.pos);
            self.pos = 0;
        }
        self.decoder.decode(&mut self.raw, &mut self.text, self.eof);
        Ok(())
    }

    /// Next line without its terminator, or `None` at the end. Lines longer
    /// than `max_len` bytes are returned in pieces.
    pub fn read_line(&mut self, max_len: usize) -> Result<Option<String>> {
        loop {
            let pending = &self.text[self.pos..];
            if let Some(nl) = pending.find('\n').filter(|&nl| nl <= max_len) {
                let line = pending[..nl].strip_suffix('\r').unwrap_or(&pending[..nl]).to_owned();
                self.pos += nl + 1;
                return Ok(Some(line));
            }
            if pending.len() >= max_len {
                let cut = floor_char_boundary(pending, max_len).max(pending.chars().next().map_or(0, char::len_utf8));
                let piece = pending[..cut].to_owned();
                self.pos += cut;
                return Ok(Some(piece));
            }
            if self.eof {
                if pending.is_empty() {
                    return Ok(None);
                }
                let line = pending.to_owned();
                self.pos = self.text.len();
                return Ok(Some(line));
            }
            self.fill()?;
        }
    }

    /// The whole remaining text, refusing more than `max_len` bytes.
    pub fn read_to_string(mut self, max_len: u64) -> Result<String> {
        while !self.eof {
            self.fill()?;
            if self.text.len() as u64 > max_len {
                return Err(document_core::EngineError::limit("the file is too large to format"));
            }
        }
        Ok(self.text.split_off(self.pos))
    }
}

pub struct TextParser;

impl Parser for TextParser {
    fn name(&self) -> &'static str {
        "text"
    }

    fn handles(&self, format: FormatId) -> bool {
        format == FormatId::PlainText
    }

    fn open(&self, mut input: Input, ctx: OpenContext) -> Result<Box<dyn OpenDocument>> {
        let mut bom = [0u8; 3];
        let read = read_up_to(&mut input.reader, &mut bom)?;
        let (decoder, bom_len) = Decoder::from_bom(&bom[..read]);
        input.reader.seek(SeekFrom::Start(bom_len as u64))?;

        let byte_size = input.byte_size;
        let info = |format, kind| DocumentInfo { format, kind, byte_size, encoding: Some(decoder.name()) };
        let whole_fits = byte_size <= MAX_WHOLE_TEXT_BYTES;
        let dialect = Dialect::from_name(ctx.name_hint.as_deref());
        let dialect = match dialect {
            Dialect::Markdown if !whole_fits => Dialect::Plain,
            other => other,
        };
        if matches!(dialect, Dialect::Html) && whole_fits {
            let info = info(FormatId::Html, DocumentKind::Text);
            return Ok(Box::new(HtmlDocument { info, reader: input.reader, cancel: ctx.cancel }));
        }
        if !matches!(dialect, Dialect::Plain) {
            let stream = TextStream::new(input.reader, byte_size, decoder, ctx.cancel.clone());
            return Ok(match dialect {
                Dialect::Markdown => {
                    let text = stream.read_to_string(MAX_WHOLE_TEXT_BYTES)?;
                    Box::new(Streamed::new(
                        info(FormatId::Markdown, DocumentKind::Text),
                        crate::markdown::MarkdownSource::new(&text, &ctx.cancel)?,
                    ))
                }
                Dialect::Json if whole_fits => {
                    let text = stream.read_to_string(MAX_WHOLE_TEXT_BYTES)?;
                    Box::new(Streamed::new(
                        info(FormatId::Json, DocumentKind::Text),
                        crate::code::CodeSource::from_text(Some("json".into()), crate::code::pretty_json(&text)),
                    ))
                }
                Dialect::Json => Box::new(Streamed::new(
                    info(FormatId::Json, DocumentKind::Text),
                    crate::code::CodeSource::streaming(Some("json".into()), stream),
                )),
                Dialect::Csv(delimiter) => Box::new(Streamed::new(
                    info(FormatId::Csv, DocumentKind::Spreadsheet),
                    crate::csv::CsvSource::new(stream, delimiter),
                )),
                Dialect::Code(language) => Box::new(Streamed::new(
                    info(FormatId::SourceCode, DocumentKind::Text),
                    crate::code::CodeSource::streaming(Some(language), stream),
                )),
                // Too large to render: the code view streams it, and the format still says HTML.
                Dialect::Html => Box::new(Streamed::new(
                    info(FormatId::Html, DocumentKind::Text),
                    crate::code::CodeSource::streaming(Some("html".into()), stream),
                )),
                Dialect::Plain => unreachable!("handled below"),
            });
        }

        let info = info(FormatId::PlainText, DocumentKind::Text);
        Ok(Box::new(TextDocument {
            info,
            chunks: Chunks::new(input.reader, READ_CHUNK, byte_size, &ctx.cancel),
            decoder,
            cancel: ctx.cancel,
            raw: Vec::new(),
            text: String::new(),
            pos: 0,
            eof: false,
        }))
    }
}

/// A web page for the platform to render. There are no blocks: the viewer
/// asks for the raw bytes, which it decodes by the page's own declaration.
struct HtmlDocument {
    info: DocumentInfo,
    reader: Box<dyn ReadSeek>,
    cancel: CancelToken,
}

impl OpenDocument for HtmlDocument {
    fn info(&self) -> &DocumentInfo {
        &self.info
    }

    fn next_blocks(&mut self, _max_bytes: usize) -> Result<Extracted> {
        Ok(Extracted { blocks: Vec::new(), finished: true })
    }

    /// The whole file; the name is ignored because there is one.
    fn resource(&mut self, _name: &str) -> Result<Vec<u8>> {
        self.cancel.check()?;
        self.reader.seek(SeekFrom::Start(0))?;
        let mut bytes = Vec::with_capacity(self.info.byte_size as usize);
        // The input can grow after open, so the cap is enforced on the read too.
        (&mut self.reader).take(MAX_WHOLE_TEXT_BYTES + 1).read_to_end(&mut bytes)?;
        if bytes.len() as u64 > MAX_WHOLE_TEXT_BYTES {
            return Err(EngineError::limit("the page is larger than 8 MiB"));
        }
        Ok(bytes)
    }
}

struct TextDocument {
    info: DocumentInfo,
    chunks: Chunks,
    decoder: Decoder,
    cancel: CancelToken,
    /// Undecoded bytes: an incomplete sequence left over from the last chunk.
    raw: Vec<u8>,
    /// Decoded text not yet emitted starts at `pos`.
    text: String,
    pos: usize,
    eof: bool,
}

impl OpenDocument for TextDocument {
    fn info(&self) -> &DocumentInfo {
        &self.info
    }

    fn next_blocks(&mut self, max_bytes: usize) -> Result<Extracted> {
        let mut blocks = Vec::new();
        let mut used = 0;
        let finished = loop {
            let room = max_bytes - used;
            if room == 0 {
                break false;
            }
            let pending = &self.text[self.pos..];
            // (line length without terminator, bytes consumed including it,
            //  whether the line is complete)
            let (content, consumed, complete) = match pending.find('\n') {
                Some(nl) => (nl, nl + 1, true),
                None if self.eof && pending.is_empty() => break true,
                None if self.eof => (pending.len(), pending.len(), true),
                None if pending.len() < max_bytes => {
                    self.fill()?;
                    continue;
                }
                // An unterminated line already longer than a batch: split it.
                None => (pending.len(), pending.len(), false),
            };

            let line = &pending[..content];
            // A trailing '\r' of an incomplete line may be half of a "\r\n".
            let line = if complete { line.strip_suffix('\r').unwrap_or(line) } else { line };
            if complete && line.len() < room {
                blocks.push(Block::paragraph(line));
                used += line.len() + 1;
                self.pos += consumed;
                continue;
            }
            if !blocks.is_empty() && line.len() < max_bytes {
                // Fits whole in the next batch; don't split it needlessly.
                break false;
            }
            let cut = floor_char_boundary(line, room - 1);
            if cut == 0 {
                // Not even one character fits; with an empty batch this cannot
                // happen because max_bytes >= Limits::MIN_BATCH_BYTES.
                break false;
            }
            blocks.push(Block::paragraph(&line[..cut]));
            used += cut + 1;
            self.pos += cut;
        };
        self.compact();
        Ok(Extracted { blocks, finished })
    }
}

impl TextDocument {
    fn fill(&mut self) -> Result<()> {
        self.cancel.check()?;
        self.eof = self.chunks.append_to(&mut self.raw)? == 0;
        self.decoder.decode(&mut self.raw, &mut self.text, self.eof);
        Ok(())
    }

    fn compact(&mut self) {
        self.text.drain(..self.pos);
        self.pos = 0;
    }
}

#[derive(Clone, Copy)]
pub(crate) enum Decoder {
    Utf8,
    Utf16 { big_endian: bool },
}

impl Decoder {
    fn from_bom(head: &[u8]) -> (Self, usize) {
        match head {
            [0xEF, 0xBB, 0xBF, ..] => (Self::Utf8, 3),
            [0xFF, 0xFE, ..] => (Self::Utf16 { big_endian: false }, 2),
            [0xFE, 0xFF, ..] => (Self::Utf16 { big_endian: true }, 2),
            _ => (Self::Utf8, 0),
        }
    }

    fn name(self) -> &'static str {
        match self {
            Self::Utf8 => "utf-8",
            Self::Utf16 { big_endian: false } => "utf-16le",
            Self::Utf16 { big_endian: true } => "utf-16be",
        }
    }

    /// Decodes as much of `raw` as possible into `out`, leaving an incomplete
    /// trailing sequence in `raw` unless `eof`.
    fn decode(self, raw: &mut Vec<u8>, out: &mut String, eof: bool) {
        let consumed = match self {
            Self::Utf8 => decode_utf8(raw, out, eof),
            Self::Utf16 { big_endian } => decode_utf16(raw, out, eof, big_endian),
        };
        raw.drain(..consumed);
    }
}

fn decode_utf8(raw: &[u8], out: &mut String, eof: bool) -> usize {
    let mut start = 0;
    loop {
        match std::str::from_utf8(&raw[start..]) {
            Ok(text) => {
                out.push_str(text);
                return raw.len();
            }
            Err(e) => {
                let valid = start + e.valid_up_to();
                out.push_str(std::str::from_utf8(&raw[start..valid]).unwrap_or_default());
                match e.error_len() {
                    Some(bad) => {
                        out.push(char::REPLACEMENT_CHARACTER);
                        start = valid + bad;
                    }
                    None if eof => {
                        out.push(char::REPLACEMENT_CHARACTER);
                        return raw.len();
                    }
                    None => return valid,
                }
            }
        }
    }
}

fn decode_utf16(raw: &[u8], out: &mut String, eof: bool, big_endian: bool) -> usize {
    let unit = |pair: &[u8]| {
        let bytes = [pair[0], pair[1]];
        if big_endian { u16::from_be_bytes(bytes) } else { u16::from_le_bytes(bytes) }
    };
    let mut units: Vec<u16> = raw.chunks_exact(2).map(unit).collect();
    let mut consumed = units.len() * 2;
    // Hold back a high surrogate whose partner is in the next chunk.
    if !eof && units.last().is_some_and(|u| (0xD800..0xDC00).contains(u)) {
        units.pop();
        consumed -= 2;
    }
    out.extend(char::decode_utf16(units).map(|c| c.unwrap_or(char::REPLACEMENT_CHARACTER)));
    if eof && consumed < raw.len() {
        out.push(char::REPLACEMENT_CHARACTER);
        consumed = raw.len();
    }
    consumed
}

fn read_up_to(reader: &mut dyn ReadSeek, buf: &mut [u8]) -> Result<usize> {
    let mut filled = 0;
    while filled < buf.len() {
        match reader.read(&mut buf[filled..]) {
            Ok(0) => break,
            Ok(n) => filled += n,
            Err(e) if e.kind() == IoErrorKind::Interrupted => {}
            Err(e) => return Err(e.into()),
        }
    }
    Ok(filled)
}

fn floor_char_boundary(s: &str, mut index: usize) -> usize {
    index = index.min(s.len());
    while !s.is_char_boundary(index) {
        index -= 1;
    }
    index
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use document_core::{Block, ContentBatch, DocumentKind, ErrorKind, FormatId, Limits, Session};

    use crate::engine;

    fn read_all(bytes: &[u8], limits: Limits) -> Vec<ContentBatch> {
        let mut session = engine(limits).open(Cursor::new(bytes.to_vec())).unwrap();
        std::iter::from_fn(|| session.next_batch().unwrap()).collect()
    }

    fn lines(batches: &[ContentBatch]) -> Vec<String> {
        batches
            .iter()
            .flat_map(|b| &b.blocks)
            .map(|block| match block {
                document_core::Block::Paragraph { text } => text.text.clone(),
                other => panic!("plain text produced {other:?}"),
            })
            .collect()
    }

    #[test]
    fn splits_lines_and_strips_terminators() {
        let batches = read_all(b"one\r\ntwo\n\nthree", Limits::default());
        assert_eq!(lines(&batches), ["one", "two", "", "three"]);
        assert!(batches.last().unwrap().is_last);
    }

    #[test]
    fn empty_file_yields_one_empty_final_batch() {
        let batches = read_all(b"", Limits::default());
        assert_eq!(batches.len(), 1);
        assert!(batches[0].blocks.is_empty() && batches[0].is_last);
    }

    #[test]
    fn decodes_bom_variants() {
        assert_eq!(lines(&read_all(b"\xEF\xBB\xBFhi", Limits::default())), ["hi"]);
        let le: Vec<u8> =
            [0xFF, 0xFE].into_iter().chain("h\u{e9}\n\u{1F600}".encode_utf16().flat_map(u16::to_le_bytes)).collect();
        assert_eq!(lines(&read_all(&le, Limits::default())), ["h\u{e9}", "\u{1F600}"]);
        let be: Vec<u8> = [0xFE, 0xFF].into_iter().chain("ok".encode_utf16().flat_map(u16::to_be_bytes)).collect();
        assert_eq!(lines(&read_all(&be, Limits::default())), ["ok"]);
    }

    #[test]
    fn batches_respect_the_byte_budget() {
        let text = "αβγδε\n".repeat(1_000);
        let limits = Limits { max_batch_bytes: 100, ..Limits::default() };
        let batches = read_all(text.as_bytes(), limits);
        assert!(batches.len() > 1);
        for batch in &batches {
            let cost: usize = batch.blocks.iter().map(|b| b.cost()).sum();
            assert!(cost <= 100, "batch {} cost {cost}", batch.sequence);
        }
        assert_eq!(lines(&batches).len(), 1_000);
    }

    #[test]
    fn overlong_lines_split_on_char_boundaries_without_loss() {
        let line = "é".repeat(50_000); // 100 KB, longer than one read chunk
        let limits = Limits { max_batch_bytes: 1_001, ..Limits::default() };
        let batches = read_all(line.as_bytes(), limits);
        assert_eq!(lines(&batches).concat(), line);
    }

    #[test]
    fn large_files_are_read_ahead_and_arrive_whole_and_in_order() {
        // Over a megabyte, so a worker thread reads; multibyte characters straddle chunks.
        let text: String = (0..120_000)
            .map(|i| {
                format!(
                    "{i} ünïcode €
"
                )
            })
            .collect();
        let got = lines(&read_all(text.as_bytes(), Limits::default()));
        assert_eq!(got.len(), 120_000);
        assert!(got.iter().enumerate().all(|(i, line)| *line == format!("{i} ünïcode €")));
    }

    #[test]
    fn multibyte_sequences_spanning_read_chunks_decode_intact() {
        let mut text = "a".repeat(super::READ_CHUNK - 1);
        text.push('€'); // straddles the first chunk boundary
        assert_eq!(lines(&read_all(text.as_bytes(), Limits::default())).concat(), text);
    }

    #[test]
    fn invalid_sequences_after_the_sniff_window_become_replacement_chars() {
        let mut bytes = b"ok ".repeat(4_000);
        bytes.extend_from_slice(b"\xFFend");
        let out = lines(&read_all(&bytes, Limits::default())).concat();
        assert!(out.ends_with("\u{FFFD}end"));
    }

    #[test]
    fn total_output_budget_truncates() {
        let text = "line\n".repeat(10_000);
        let limits = Limits { max_batch_bytes: 1_000, max_total_output_bytes: 5_000, ..Limits::default() };
        let batches = read_all(text.as_bytes(), limits);
        assert!(batches.last().unwrap().truncated);
        assert!(lines(&batches).len() <= 1_000);
    }

    #[test]
    fn binary_content_is_rejected() {
        let err = engine(Limits::default()).open(Cursor::new(vec![0u8, 1, 2, 3])).err().unwrap();
        assert_eq!(err.kind, ErrorKind::UnsupportedFormat);
    }

    fn open_named(bytes: &[u8], name: &str) -> Session {
        engine(Limits::default()).open_named(Cursor::new(bytes.to_vec()), Some(name.to_owned())).unwrap()
    }

    #[test]
    fn html_names_hand_the_raw_bytes_to_the_platform() {
        for name in ["page.html", "PAGE.HTM", "page.xhtml"] {
            let bytes = b"\xEF\xBB\xBF<!doctype html><p>hi</p>";
            let mut session = open_named(bytes, name);
            assert_eq!((session.info().format, session.info().kind), (FormatId::Html, DocumentKind::Text), "{name}");
            let batch = session.next_batch().unwrap().unwrap();
            assert!(batch.blocks.is_empty() && batch.is_last);
            // Twice: a read must not depend on where the last one left the reader.
            assert_eq!(session.resource("html").unwrap(), bytes);
            assert_eq!(session.resource("html").unwrap(), bytes);
        }
    }

    #[test]
    fn html_in_a_legacy_encoding_keeps_its_bytes() {
        let bytes = b"<meta charset=latin1><p>caf\xE9</p>";
        let mut session = open_named(bytes, "page.html");
        assert_eq!(session.info().format, FormatId::Html);
        assert_eq!(session.resource("html").unwrap(), bytes);
    }

    #[test]
    fn html_is_not_decided_by_the_name_alone() {
        let err = engine(Limits::default())
            .open_named(Cursor::new(b"\x00\x01\x02".to_vec()), Some("page.html".to_owned()))
            .err()
            .unwrap();
        assert_eq!(err.kind, ErrorKind::UnsupportedFormat);
        assert_ne!(open_named(b"plain\n", "page.txt").info().format, FormatId::Html);
    }

    #[test]
    fn html_source_is_numbered_code_and_svg_stays_code() {
        for (name, language) in [("page.html-source", "html"), ("logo.svg", "svg")] {
            let mut session = open_named(b"<p>one</p>\n<p>two</p>\n", name);
            assert_eq!(session.info().format, FormatId::SourceCode, "{name}");
            let batch = session.next_batch().unwrap().unwrap();
            match &batch.blocks[0] {
                Block::Code { language: shown, first_line, .. } => {
                    assert_eq!((shown.as_deref(), *first_line), (Some(language), Some(1)));
                }
                other => panic!("{other:?}"),
            }
        }
    }

    #[test]
    fn html_too_large_to_render_shows_as_code_and_serves_no_bytes() {
        let bytes = vec![b'a'; super::MAX_WHOLE_TEXT_BYTES as usize + 1];
        let mut session = open_named(&bytes, "big.html");
        assert_eq!(session.info().format, FormatId::Html);
        assert!(matches!(session.next_batch().unwrap().unwrap().blocks[0], Block::Code { .. }));
        assert_eq!(session.resource("html").err().unwrap().kind, ErrorKind::UnsupportedFormat);
    }
}
