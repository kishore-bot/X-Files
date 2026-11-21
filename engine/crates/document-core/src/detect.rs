use crate::FormatId;

/// Number of leading bytes the coordinator reads for detection.
pub const SNIFF_LEN: usize = 8 * 1024;

const PDF: &[u8] = b"%PDF-";
const ZIP_LOCAL: &[u8] = b"PK\x03\x04";
const ZIP_EMPTY: &[u8] = b"PK\x05\x06";
const OLE: &[u8] = &[0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1];
const UTF8_BOM: &[u8] = &[0xEF, 0xBB, 0xBF];
const UTF16LE_BOM: &[u8] = &[0xFF, 0xFE];
const UTF16BE_BOM: &[u8] = &[0xFE, 0xFF];

/// Identifies a format from the first bytes of the input.
///
/// `head` holds at most [`SNIFF_LEN`] bytes; `is_complete` says whether it is
/// the entire input, which decides how a UTF-8 sequence cut off at the end of
/// `head` is judged. Binary signatures are checked before the text heuristic so
/// a binary file is never misread as text.
pub fn detect(head: &[u8], is_complete: bool) -> FormatId {
    if head.starts_with(PDF) {
        return FormatId::Pdf;
    }
    if head.starts_with(ZIP_LOCAL) || head.starts_with(ZIP_EMPTY) {
        return FormatId::Zip;
    }
    if head.starts_with(OLE) {
        return FormatId::OleCompound;
    }
    if is_image(head) {
        return FormatId::Image;
    }
    if head.starts_with(UTF8_BOM) || head.starts_with(UTF16LE_BOM) || head.starts_with(UTF16BE_BOM) {
        return FormatId::PlainText;
    }
    if looks_like_utf8_text(head, is_complete) || looks_like_legacy_html(head) {
        return FormatId::PlainText;
    }
    FormatId::Unknown
}

fn is_image(head: &[u8]) -> bool {
    let at = |range: std::ops::Range<usize>| head.get(range).unwrap_or_default();
    head.starts_with(b"\x89PNG\r\n\x1a\n")
        || head.starts_with(b"\xFF\xD8\xFF")
        || head.starts_with(b"GIF87a")
        || head.starts_with(b"GIF89a")
        || (head.starts_with(b"RIFF") && at(8..12) == b"WEBP")
        // ISO media files: HEIF and AVIF are told apart from video by the brand.
        || (at(4..8) == b"ftyp"
            && matches!(at(8..12), b"heic" | b"heix" | b"hevc" | b"heim" | b"heis" | b"mif1" | b"msf1" | b"avif" | b"avis"))
        // "BM" alone also starts ordinary text: require zero reserved bytes and a known DIB header size.
        || (head.starts_with(b"BM")
            && at(6..10) == [0, 0, 0, 0]
            && matches!(at(14..18), [12 | 40 | 52 | 56 | 64 | 108 | 124, 0, 0, 0]))
}

fn looks_like_utf8_text(head: &[u8], is_complete: bool) -> bool {
    let valid = match std::str::from_utf8(head) {
        Ok(text) => text,
        // A multi-byte sequence cut off by the sniff window is still text.
        Err(e) if e.error_len().is_none() && !is_complete => {
            // valid_up_to() is always a char boundary, so this cannot fail.
            std::str::from_utf8(&head[..e.valid_up_to()]).unwrap_or_default()
        }
        Err(_) => return false,
    };
    // NUL and most C0 controls do not occur in text files.
    !valid.bytes().any(|b| b < 0x20 && !matches!(b, b'\t' | b'\n' | b'\r' | 0x0C | 0x1B))
}

/// A web page in a legacy encoding is not valid UTF-8 but is still text: its
/// own markup says so, and the viewer decodes it by its `<meta charset>`.
fn looks_like_legacy_html(head: &[u8]) -> bool {
    if head.iter().any(|&b| b < 0x20 && !matches!(b, b'\t' | b'\n' | b'\r' | 0x0C | 0x1B)) {
        return false;
    }
    let lower = head.to_ascii_lowercase();
    let has = |needle: &[u8]| lower.windows(needle.len()).any(|window| window == needle);
    has(b"<!doctype html") || has(b"<html") || (has(b"<meta") && has(b"charset"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn binary_signatures_win_over_text() {
        assert_eq!(detect(b"%PDF-1.7\n", true), FormatId::Pdf);
        assert_eq!(detect(b"PK\x03\x04rest", true), FormatId::Zip);
        assert_eq!(detect(OLE, true), FormatId::OleCompound);
    }

    #[test]
    fn images_by_signature() {
        for head in [
            &b"\x89PNG\r\n\x1a\n...."[..],
            b"\xFF\xD8\xFF\xE0",
            b"GIF89a....",
            b"RIFF\x10\0\0\0WEBPVP8 ",
            b"\0\0\0\x18ftypheic\0\0\0\0",
            b"\0\0\0\x1cftypavif\0\0\0\0",
            b"BM\x46\0\0\0\0\0\0\0\x36\0\0\0\x28\0\0\0",
        ] {
            assert_eq!(detect(head, false), FormatId::Image, "{head:?}");
        }
        // Text that merely starts like a bitmap, and MP4 video, are not images.
        assert_eq!(detect(b"BMW prices for this year", true), FormatId::PlainText);
        assert_eq!(detect(b"\0\0\0\x18ftypmp42\0\0\0\0", true), FormatId::Unknown);
    }

    #[test]
    fn text_detection() {
        assert_eq!(detect(b"hello\r\nworld\t!", true), FormatId::PlainText);
        assert_eq!(detect("na\u{ef}ve caf\u{e9}".as_bytes(), true), FormatId::PlainText);
        assert_eq!(detect(b"", true), FormatId::PlainText);
        assert_eq!(detect(&[0xFF, 0xFE, b'h', 0], true), FormatId::PlainText);
    }

    #[test]
    fn utf8_cut_by_sniff_window_is_text_only_when_more_follows() {
        let cut = &"é".as_bytes()[..1];
        assert_eq!(detect(cut, false), FormatId::PlainText);
        assert_eq!(detect(cut, true), FormatId::Unknown);
    }

    #[test]
    fn binary_and_legacy_encodings_are_unknown() {
        assert_eq!(detect(b"ELF\x00\x01\x02", true), FormatId::Unknown);
        // Windows-1252 "café" is not valid UTF-8.
        assert_eq!(detect(b"caf\xE9", true), FormatId::Unknown);
        assert_eq!(detect(b"<p>caf\xE9</p>", true), FormatId::Unknown);
    }

    #[test]
    fn web_pages_in_legacy_encodings_are_text() {
        assert_eq!(detect(b"<!DOCTYPE HTML><p>caf\xE9</p>", true), FormatId::PlainText);
        assert_eq!(detect(b"<html><p>caf\xE9</p>", true), FormatId::PlainText);
        assert_eq!(detect(b"<META CHARSET=latin1>caf\xE9", true), FormatId::PlainText);
        // Markup does not make binary content text.
        assert_eq!(detect(b"<html>\x00\x01\xE9", true), FormatId::Unknown);
    }
}
