//! Argument checks shared by the tools. Every message names the field it is about.

use document_parsers::PdfPageRange;

use crate::error::{Result, ToolError};

pub fn bounded(value: u64, min: u64, max: u64, field: &str) -> Result<u64> {
    if (min..=max).contains(&value) {
        Ok(value)
    } else {
        Err(ToolError::invalid(format!("{field} must be between {min} and {max}")))
    }
}

/// A required text argument: not blank, and at most `max_chars` characters.
pub fn required_text<'a>(value: &'a str, field: &str, max_chars: usize) -> Result<&'a str> {
    if value.trim().is_empty() {
        Err(ToolError::invalid(format!("{field} is required")))
    } else if value.chars().count() > max_chars {
        Err(ToolError::invalid(format!("{field} is longer than {max_chars} characters")))
    } else {
        Ok(value)
    }
}

/// A 1-based page number, returned zero-based.
pub fn page(value: u32, field: &str) -> Result<u32> {
    if value == 0 {
        Err(ToolError::invalid(format!("{field} is 1-based; the first page is 1")))
    } else {
        Ok(value - 1)
    }
}

pub fn float_in(value: f32, min: f32, max: f32, field: &str) -> Result<f32> {
    if value.is_finite() && (min..=max).contains(&value) {
        Ok(value)
    } else {
        Err(ToolError::invalid(format!("{field} must be between {min} and {max}")))
    }
}

/// Clockwise rotation in degrees: a multiple of 90, normalised to 0, 90, 180 or 270.
pub fn rotation(degrees: i32) -> Result<i32> {
    if degrees % 90 != 0 {
        return Err(ToolError::invalid(format!("rotation {degrees} must be a multiple of 90 degrees")));
    }
    Ok(degrees.rem_euclid(360))
}

/// `#RRGGBB` or `RRGGBB`, as 0xRRGGBB.
pub fn color(value: &str) -> Result<u32> {
    let hex = value.strip_prefix('#').unwrap_or(value);
    if hex.len() != 6 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(ToolError::invalid(format!("color {value:?} must look like #RRGGBB")));
    }
    u32::from_str_radix(hex, 16).map_err(|_| ToolError::invalid(format!("color {value:?} is not valid")))
}

/// A plain file name for an output prefix: no folders, no reserved characters.
pub fn file_stem<'a>(value: &'a str, field: &str) -> Result<&'a str> {
    let reserved = value.chars().any(|c| matches!(c, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|') || c.is_control());
    if value.is_empty() || value.len() > 100 || value == "." || value == ".." || reserved {
        Err(ToolError::invalid(format!("{field} must be a plain name without folders or special characters")))
    } else {
        Ok(value)
    }
}

/// Inclusive 1-based page ranges such as `1-3,5,8-10`, returned zero-based.
pub fn page_ranges(spec: &str) -> Result<Vec<PdfPageRange>> {
    let mut ranges = Vec::new();
    for part in spec.split(',').map(str::trim).filter(|part| !part.is_empty()) {
        let (first, last) = match part.split_once('-') {
            Some((from, to)) => (parse_page_number(from.trim())?, parse_page_number(to.trim())?),
            None => {
                let page = parse_page_number(part)?;
                (page, page)
            }
        };
        if first > last {
            return Err(ToolError::invalid(format!("the range {part} ends before it starts")));
        }
        ranges.push(PdfPageRange { start: first - 1, end: last - 1 });
    }
    if ranges.is_empty() {
        return Err(ToolError::invalid("ranges must name at least one page, such as 1-3,5"));
    }
    Ok(ranges)
}

fn parse_page_number(text: &str) -> Result<u32> {
    let number: u32 = text
        .parse()
        .map_err(|_| ToolError::invalid(format!("{text:?} is not a page number")))?;
    if number == 0 {
        Err(ToolError::invalid("page numbers start at 1"))
    } else {
        Ok(number)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bounded_checks_both_ends() {
        assert_eq!(bounded(5, 1, 10, "n").unwrap(), 5);
        assert!(bounded(0, 1, 10, "n").is_err());
        assert!(bounded(11, 1, 10, "n").is_err());
    }

    #[test]
    fn required_text_rejects_blank_and_long_values() {
        assert!(required_text("  ", "find", 10).is_err());
        assert!(required_text("abcdefghijk", "find", 10).is_err());
        assert_eq!(required_text("ok", "find", 10).unwrap(), "ok");
    }

    #[test]
    fn pages_are_one_based() {
        assert_eq!(page(1, "p").unwrap(), 0);
        assert!(page(0, "p").is_err());
    }

    #[test]
    fn float_bounds_reject_nan_and_out_of_range() {
        assert!(float_in(0.5, 0.0, 1.0, "opacity").is_ok());
        assert!(float_in(1.5, 0.0, 1.0, "opacity").is_err());
        assert!(float_in(f32::NAN, 0.0, 1.0, "opacity").is_err());
    }

    #[test]
    fn rotation_must_be_quarter_turns() {
        assert_eq!(rotation(90).unwrap(), 90);
        assert_eq!(rotation(-90).unwrap(), 270);
        assert_eq!(rotation(360).unwrap(), 0);
        assert!(rotation(45).is_err());
    }

    #[test]
    fn color_parses_hex_with_or_without_hash() {
        assert_eq!(color("#808080").unwrap(), 0x808080);
        assert_eq!(color("FF0000").unwrap(), 0xFF0000);
        assert!(color("#12345").is_err());
        assert!(color("#GG0000").is_err());
    }

    #[test]
    fn file_stem_rejects_paths_and_reserved_characters() {
        assert_eq!(file_stem("part", "prefix").unwrap(), "part");
        assert!(file_stem("../x", "prefix").is_err());
        assert!(file_stem("a:b", "prefix").is_err());
        assert!(file_stem("..", "prefix").is_err());
        assert!(file_stem("", "prefix").is_err());
    }

    #[test]
    fn page_ranges_parse_and_convert_to_zero_based() {
        let ranges = page_ranges("1-3, 5").unwrap();
        assert_eq!(ranges.len(), 2);
        assert_eq!((ranges[0].start, ranges[0].end), (0, 2));
        assert_eq!((ranges[1].start, ranges[1].end), (4, 4));
    }

    #[test]
    fn page_ranges_reject_bad_input() {
        assert!(page_ranges("").is_err());
        assert!(page_ranges("0-2").is_err());
        assert!(page_ranges("5-2").is_err());
        assert!(page_ranges("a-b").is_err());
    }
}
