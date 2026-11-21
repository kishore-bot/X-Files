//! CSV and TSV: streamed into numbered spreadsheet rows.
//!
//! RFC 4180 quoting (doubled quotes, delimiters and newlines inside quotes).
//! For `.csv` the delimiter is sniffed from the first line (`,` `;` or tab,
//! since many locales export `;`). The first record is marked as the header.

use std::collections::VecDeque;

use document_core::{Block, Result};

use crate::stream::BlockSource;
use crate::text::TextStream;
use crate::xlsx::MAX_COLUMNS;

const ROWS_PER_PUMP: usize = 256;
const MAX_CELL_BYTES: usize = 32 * 1024;
const MAX_LINE_BYTES: usize = 1024 * 1024;

pub(crate) struct CsvSource {
    stream: TextStream,
    delimiter: Option<char>,
    record: u32,
    /// The line read while sniffing, not yet parsed.
    pending: Option<String>,
}

impl CsvSource {
    pub fn new(stream: TextStream, delimiter: Option<char>) -> Self {
        Self { stream, delimiter, record: 0, pending: None }
    }

    fn next_line(&mut self) -> Result<Option<String>> {
        match self.pending.take() {
            Some(line) => Ok(Some(line)),
            None => self.stream.read_line(MAX_LINE_BYTES),
        }
    }

    /// One record, which may span several physical lines.
    fn next_record(&mut self, delimiter: char) -> Result<Option<Vec<String>>> {
        let Some(mut line) = self.next_line()? else { return Ok(None) };
        let mut cells = Vec::new();
        let mut cell = String::new();
        let mut quoted = false;
        loop {
            let mut chars = line.chars().peekable();
            while let Some(c) = chars.next() {
                match c {
                    '"' if quoted && chars.peek() == Some(&'"') => {
                        chars.next();
                        push_capped(&mut cell, '"');
                    }
                    '"' if quoted => quoted = false,
                    '"' if cell.is_empty() => quoted = true,
                    c if c == delimiter && !quoted => {
                        if cells.len() < MAX_COLUMNS {
                            cells.push(std::mem::take(&mut cell));
                        } else {
                            cell.clear();
                        }
                    }
                    c => push_capped(&mut cell, c),
                }
            }
            if !quoted {
                break;
            }
            // A quoted field continues on the next line.
            match self.stream.read_line(MAX_LINE_BYTES)? {
                Some(next) => {
                    push_capped(&mut cell, '\n');
                    line = next;
                }
                None => break,
            }
        }
        if cells.len() < MAX_COLUMNS {
            cells.push(cell);
        }
        Ok(Some(cells))
    }
}

impl BlockSource for CsvSource {
    fn pump(&mut self, out: &mut VecDeque<Block>) -> Result<bool> {
        let delimiter = match self.delimiter {
            Some(d) => d,
            None => {
                let first = self.stream.read_line(MAX_LINE_BYTES)?;
                let d = first.as_deref().map_or(',', sniff_delimiter);
                self.pending = first;
                self.delimiter = Some(d);
                d
            }
        };
        let mut produced = 0;
        while produced < ROWS_PER_PUMP {
            let Some(cells) = self.next_record(delimiter)? else { break };
            self.record += 1;
            if cells.len() == 1 && cells[0].is_empty() {
                continue; // blank line
            }
            out.push_back(Block::TableRow {
                row: Some(self.record),
                header: self.record == 1,
                cells,
                styles: Vec::new(),
            });
            produced += 1;
        }
        Ok(produced == 0)
    }
}

fn push_capped(cell: &mut String, c: char) {
    if cell.len() < MAX_CELL_BYTES {
        cell.push(c);
    }
}

/// The candidate occurring most often outside quotes on the first line.
fn sniff_delimiter(line: &str) -> char {
    let mut counts = [(',', 0usize), (';', 0), ('\t', 0), ('|', 0)];
    let mut quoted = false;
    for c in line.chars() {
        if c == '"' {
            quoted = !quoted;
        } else if !quoted && let Some(entry) = counts.iter_mut().find(|(d, _)| *d == c) {
            entry.1 += 1;
        }
    }
    counts.iter().max_by_key(|(_, n)| *n).filter(|(_, n)| *n > 0).map_or(',', |(d, _)| *d)
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use document_core::{DocumentKind, FormatId, Limits};

    use crate::testing::texts;

    fn read(name: &str, data: &str) -> Vec<String> {
        let mut session = crate::engine(Limits::default())
            .open_named(Cursor::new(data.as_bytes().to_vec()), Some(name.into()))
            .unwrap();
        assert_eq!((session.info().format, session.info().kind), (FormatId::Csv, DocumentKind::Spreadsheet));
        texts(&std::iter::from_fn(|| session.next_batch().unwrap()).flat_map(|b| b.blocks).collect::<Vec<_>>())
    }

    #[test]
    fn parses_quotes_and_multiline_fields() {
        let data = "name,note,qty\r\n\"Smith, J\",\"said \"\"hi\"\"\",3\n\n\"multi\nline\",x,4\n";
        assert_eq!(read("people.csv", data), ["T1 name|note|qty", "R2 Smith, J|said \"hi\"|3", "R4 multi\nline|x|4"]);
    }

    #[test]
    fn sniffs_semicolons_and_reads_tsv() {
        assert_eq!(read("eu.csv", "a;b;c\n1;2,5;3\n"), ["T1 a|b|c", "R2 1|2,5|3"]);
        assert_eq!(read("data.tsv", "a\tb\n1\t2\n"), ["T1 a|b", "R2 1|2"]);
    }
}
