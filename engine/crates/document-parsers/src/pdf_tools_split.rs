//! Split: each requested page range becomes its own PDF.
//!
//! The ranges are checked before any output is written. Each must be in bounds
//! and ordered, and no two may share a page. Sorting by start and comparing each
//! range with the one before it finds any overlap in O(k log k) for k ranges,
//! since after sorting an overlap always shows up between neighbours. Adjacent
//! ranges (`1-3`, `4-8`) are fine: they don't share a page.
//!
//! Each part is then written by `run_pdf_tool` as a plain page list, so it keeps
//! the same structure guarantees as every other tool. The caller decides where
//! each part goes; this module only plans them.

use document_core::{EngineError, Result};

use crate::pdf_tools::PdfPagePick;

/// Parts one split may produce. Bounds the number of files, not pages.
pub const MAX_SPLIT_PARTS: usize = 256;

/// One part, as zero-based inclusive page indices.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PdfPageRange {
    pub start: u32,
    pub end: u32,
}

/// Checks `ranges` against a document of `page_count` pages and returns the
/// pages of each part, in the order the ranges were given.
pub fn plan_split(ranges: &[PdfPageRange], page_count: u32) -> Result<Vec<Vec<PdfPagePick>>> {
    if ranges.is_empty() {
        return Err(EngineError::malformed("Add at least one page range to split by."));
    }
    if ranges.len() > MAX_SPLIT_PARTS {
        return Err(EngineError::limit("too many parts to split into at once"));
    }
    for range in ranges {
        if range.start > range.end {
            return Err(EngineError::malformed("A page range starts after it ends."));
        }
        if range.end >= page_count {
            return Err(EngineError::malformed("A page range goes past the last page."));
        }
    }
    ensure_disjoint(ranges)?;
    Ok(ranges
        .iter()
        .map(|range| (range.start..=range.end).map(|page| PdfPagePick { page, rotation: 0 }).collect())
        .collect())
}

/// Interval check: after sorting by start, a range that starts at or before the
/// previous range's end shares a page with it.
fn ensure_disjoint(ranges: &[PdfPageRange]) -> Result<()> {
    let mut order = ranges.to_vec();
    order.sort_unstable_by_key(|range| (range.start, range.end));
    if order.windows(2).any(|pair| pair[1].start <= pair[0].end) {
        return Err(EngineError::malformed("Two page ranges share a page. Each page can go in one part."));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn range(start: u32, end: u32) -> PdfPageRange {
        PdfPageRange { start, end }
    }

    fn pages(part: &[PdfPagePick]) -> Vec<u32> {
        part.iter().map(|pick| pick.page).collect()
    }

    #[test]
    fn parts_keep_the_order_the_ranges_were_given() {
        let plan = plan_split(&[range(3, 4), range(0, 1)], 5).unwrap();
        assert_eq!(plan.len(), 2);
        assert_eq!(pages(&plan[0]), vec![3, 4]);
        assert_eq!(pages(&plan[1]), vec![0, 1]);
    }

    #[test]
    fn adjacent_ranges_are_allowed() {
        let plan = plan_split(&[range(0, 2), range(3, 7), range(8, 8)], 9).unwrap();
        assert_eq!(plan.iter().map(Vec::len).sum::<usize>(), 9);
    }

    #[test]
    fn overlap_is_found_even_when_the_ranges_arrive_unsorted() {
        assert!(plan_split(&[range(5, 9), range(0, 5)], 10).is_err());
        assert!(plan_split(&[range(0, 10), range(2, 3)], 11).is_err());
        assert!(plan_split(&[range(2, 2), range(2, 2)], 3).is_err());
    }

    #[test]
    fn out_of_bounds_and_reversed_ranges_are_refused() {
        assert!(plan_split(&[range(0, 5)], 5).is_err());
        assert!(plan_split(&[range(3, 2)], 5).is_err());
        assert!(plan_split(&[], 5).is_err());
    }

    #[test]
    fn too_many_parts_is_refused() {
        let ranges: Vec<PdfPageRange> = (0..=MAX_SPLIT_PARTS as u32).map(|i| range(i, i)).collect();
        assert!(plan_split(&ranges, MAX_SPLIT_PARTS as u32 + 1).is_err());
    }
}
