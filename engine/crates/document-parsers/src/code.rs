//! Source code and JSON: line-numbered [`Block::Code`] chunks.
//!
//! Files stream line by line in chunks of [`LINES_PER_BLOCK`], so even very
//! large logs or generated sources stay within memory budgets. JSON that fits
//! in memory is pretty-printed first by a small, lenient formatter: invalid
//! JSON is still shown, just less neatly.

use std::collections::VecDeque;

use document_core::{Block, Result};

use crate::stream::BlockSource;
use crate::text::TextStream;

const LINES_PER_BLOCK: usize = 120;
const MAX_LINE_BYTES: usize = 16 * 1024;
const MAX_JSON_DEPTH: usize = 512;

pub(crate) struct CodeSource {
    language: Option<String>,
    next_line: u32,
    lines: Lines,
}

enum Lines {
    Stream(TextStream),
    Formatted(VecDeque<String>),
}

impl CodeSource {
    pub fn streaming(language: Option<String>, stream: TextStream) -> Self {
        Self { language, next_line: 1, lines: Lines::Stream(stream) }
    }

    pub fn from_text(language: Option<String>, text: String) -> Self {
        let lines = text.lines().map(str::to_owned).collect();
        Self { language, next_line: 1, lines: Lines::Formatted(lines) }
    }
}

impl BlockSource for CodeSource {
    fn pump(&mut self, out: &mut VecDeque<Block>) -> Result<bool> {
        let mut text = String::new();
        let mut count = 0;
        while count < LINES_PER_BLOCK {
            let line = match &mut self.lines {
                Lines::Stream(stream) => stream.read_line(MAX_LINE_BYTES)?,
                Lines::Formatted(lines) => lines.pop_front(),
            };
            let Some(line) = line else { break };
            if count > 0 {
                text.push('\n');
            }
            text.push_str(&line);
            count += 1;
        }
        if count == 0 {
            return Ok(true);
        }
        out.push_back(Block::Code { language: self.language.clone(), first_line: Some(self.next_line), text });
        self.next_line += count as u32;
        Ok(false)
    }
}

/// Re-indents JSON with two spaces per level. Tolerates invalid input: it
/// only tracks strings and brackets, so the output is always shown.
pub(crate) fn pretty_json(input: &str) -> String {
    let mut out = String::with_capacity(input.len() + input.len() / 2);
    let mut depth = 0usize;
    let mut chars = input.chars().peekable();
    let newline = |out: &mut String, depth: usize| {
        out.push('\n');
        out.extend(std::iter::repeat_n("  ", depth.min(MAX_JSON_DEPTH)));
    };
    while let Some(c) = chars.next() {
        match c {
            '"' => {
                out.push('"');
                let mut escaped = false;
                for c in chars.by_ref() {
                    out.push(c);
                    if escaped {
                        escaped = false;
                    } else if c == '\\' {
                        escaped = true;
                    } else if c == '"' {
                        break;
                    }
                }
            }
            '{' | '[' => {
                out.push(c);
                while chars.peek().is_some_and(|c| c.is_whitespace()) {
                    chars.next();
                }
                let close = if c == '{' { '}' } else { ']' };
                if chars.peek() == Some(&close) {
                    out.push(close);
                    chars.next();
                } else {
                    depth += 1;
                    newline(&mut out, depth);
                }
            }
            '}' | ']' => {
                depth = depth.saturating_sub(1);
                newline(&mut out, depth);
                out.push(c);
            }
            ',' => {
                out.push(',');
                newline(&mut out, depth);
            }
            ':' => out.push_str(": "),
            c if c.is_whitespace() => {}
            c => out.push(c),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use document_core::{Block, DocumentKind, FormatId, Limits};

    use super::pretty_json;

    #[test]
    fn pretty_prints_json() {
        assert_eq!(
            pretty_json(r#"{"a":[1,2,{}],"b":{"c":"x, \"y\""},"e":[]}"#),
            "{\n  \"a\": [\n    1,\n    2,\n    {}\n  ],\n  \"b\": {\n    \"c\": \"x, \\\"y\\\"\"\n  },\n  \"e\": []\n}"
        );
        assert_eq!(pretty_json("[1, 2"), "[\n  1,\n  2");
    }

    #[test]
    fn source_files_are_numbered_code_blocks() {
        let source = (1..=300).map(|i| format!("let x{i} = {i};\n")).collect::<String>();
        let mut session = crate::engine(Limits::default())
            .open_named(Cursor::new(source.into_bytes()), Some("main.kt".into()))
            .unwrap();
        assert_eq!((session.info().format, session.info().kind), (FormatId::SourceCode, DocumentKind::Text));
        let blocks: Vec<Block> = std::iter::from_fn(|| session.next_batch().unwrap()).flat_map(|b| b.blocks).collect();
        let starts: Vec<_> = blocks
            .iter()
            .map(|b| match b {
                Block::Code { language, first_line, .. } => (language.clone().unwrap(), first_line.unwrap()),
                other => panic!("{other:?}"),
            })
            .collect();
        assert_eq!(starts, [("kt".to_owned(), 1), ("kt".to_owned(), 121), ("kt".to_owned(), 241)]);
    }
}
