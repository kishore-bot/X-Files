//! UniFFI surface for XLSX cell editing: read a cell's stored value and the
//! pane freeze, and save an edited copy that is re-opened before it is returned.
//!
//! Sheets are named by their tab name. Rows and columns are 0-based. Formula
//! cells are refused by the parser and shown read-only by the app.

use std::fs::File;

use document_core::CancelToken;
use document_parsers::office_edit;
use document_parsers::sheet_edit::{self, CellEdit, CellKind, CellValue};

use crate::ReadError;

/// A value to write into one cell.
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum NativeCellValue {
    Text { text: String },
    Number { value: f64 },
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NativeCellEdit {
    pub sheet: String,
    pub row: u32,
    pub column: u32,
    pub value: NativeCellValue,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum NativeCellKind {
    Empty,
    Text,
    Number,
    Bool,
    Error,
    Formula,
}

/// Stored value of a cell. `raw` is the text for text cells, the number as
/// written for numbers, and the formula text for formulas.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NativeCell {
    pub kind: NativeCellKind,
    pub raw: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct NativeFreeze {
    pub rows: u32,
    pub columns: u32,
}

/// Frozen rows and columns from the sheet's pane (zero when not frozen).
#[uniffi::export]
pub fn xlsx_read_freeze(path: String, sheet: String) -> Result<NativeFreeze, ReadError> {
    let data = read_input(&path)?;
    let freeze = sheet_edit::read_freeze(&data, &sheet)?;
    Ok(NativeFreeze { rows: freeze.rows, columns: freeze.columns })
}

/// Stored value of one cell, for the edit dialog.
#[uniffi::export]
pub fn xlsx_read_cell(path: String, sheet: String, row: u32, column: u32) -> Result<NativeCell, ReadError> {
    let data = read_input(&path)?;
    let cell = sheet_edit::read_cell(&data, &sheet, row, column)?;
    Ok(NativeCell { kind: kind_to_native(cell.kind), raw: cell.raw })
}

/// Writes `edits` into a copy at `target_path`, then re-opens the copy through
/// the engine. A failed write or re-open removes the copy. Returns the number of
/// edits applied.
#[uniffi::export]
pub fn xlsx_apply_cell_edits(path: String, target_path: String, edits: Vec<NativeCellEdit>) -> Result<u32, ReadError> {
    let data = read_input(&path)?;
    let edits: Vec<CellEdit> = edits.into_iter().map(to_edit).collect();
    let count = edits.len() as u32;
    let output = sheet_edit::apply_cell_edits(&data, &edits)?;
    let result = (|| -> Result<(), ReadError> {
        std::fs::write(&target_path, &output)
            .map_err(|e| ReadError::Io { detail: format!("cannot write file: {e}") })?;
        let written = File::open(&target_path).map_err(|e| ReadError::Io { detail: format!("cannot read file: {e}") })?;
        office_edit::verify_reopens(written, &CancelToken::default())?;
        Ok(())
    })();
    if result.is_err() {
        std::fs::remove_file(&target_path).ok();
    }
    result?;
    Ok(count)
}

fn read_input(path: &str) -> Result<Vec<u8>, ReadError> {
    std::fs::read(path).map_err(|e| ReadError::Io { detail: format!("cannot read file: {e}") })
}

fn to_edit(edit: NativeCellEdit) -> CellEdit {
    let value = match edit.value {
        NativeCellValue::Text { text } => CellValue::Text(text),
        NativeCellValue::Number { value } => CellValue::Number(value),
    };
    CellEdit { sheet: edit.sheet, row: edit.row, column: edit.column, value }
}

fn kind_to_native(kind: CellKind) -> NativeCellKind {
    match kind {
        CellKind::Empty => NativeCellKind::Empty,
        CellKind::Text => NativeCellKind::Text,
        CellKind::Number => NativeCellKind::Number,
        CellKind::Bool => NativeCellKind::Bool,
        CellKind::Error => NativeCellKind::Error,
        CellKind::Formula => NativeCellKind::Formula,
    }
}
