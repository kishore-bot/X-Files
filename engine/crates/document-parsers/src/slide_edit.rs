//! PPTX slide management: reorder and delete slides.
//!
//! Operates on `ppt/presentation.xml` to change the slide order. The actual
//! slide XML files stay in the ZIP untouched; only the slide-id list that
//! controls presentation order is rewritten.

use std::io::Cursor;

use document_core::{CancelToken, EngineError, Limits};

use crate::zip::ZipArchive;

pub struct SlideInfo {
    pub slide_count: u32,
}

pub fn pptx_slide_count(data: &[u8]) -> Result<SlideInfo, EngineError> {
    let mut archive = open(data)?;
    let xml_bytes = archive
        .read_to_vec("ppt/presentation.xml")
        .map_err(|_| EngineError::malformed("missing ppt/presentation.xml"))?;
    let xml = String::from_utf8_lossy(&xml_bytes);
    let entries = parse_slide_id_entries(&xml);
    Ok(SlideInfo { slide_count: entries.len() as u32 })
}

pub fn reorder_pptx_slides(data: &[u8], keep_indices: &[u32]) -> Result<Vec<u8>, EngineError> {
    let mut archive = open(data)?;
    let xml_bytes = archive
        .read_to_vec("ppt/presentation.xml")
        .map_err(|_| EngineError::malformed("missing ppt/presentation.xml"))?;
    let xml = String::from_utf8_lossy(&xml_bytes);

    let entries = parse_slide_id_entries(&xml);
    if entries.is_empty() {
        return Err(EngineError::malformed("no slides found in presentation"));
    }
    for &idx in keep_indices {
        if idx as usize >= entries.len() {
            return Err(EngineError::malformed("slide index out of range"));
        }
    }
    if keep_indices.is_empty() {
        return Err(EngineError::malformed("must keep at least one slide"));
    }

    let new_entries: Vec<&str> = keep_indices
        .iter()
        .map(|&i| entries[i as usize])
        .collect();

    let new_xml = rebuild_slide_id_list(&xml, &new_entries);
    let modified = vec![("ppt/presentation.xml".to_owned(), new_xml.into_bytes())];

    drop(archive);
    super::text_replace::rebuild_zip_pub(data, &modified)
}

fn parse_slide_id_entries(xml: &str) -> Vec<&str> {
    let start_tag = "<p:sldIdLst";
    let end_tag = "</p:sldIdLst>";
    let entry_tag = "<p:sldId";

    let list_start = match xml.find(start_tag) {
        Some(pos) => pos,
        None => return Vec::new(),
    };
    let list_body_start = match xml[list_start..].find('>') {
        Some(pos) => list_start + pos + 1,
        None => return Vec::new(),
    };
    let list_end = match xml[list_body_start..].find(end_tag) {
        Some(pos) => list_body_start + pos,
        None => return Vec::new(),
    };

    let body = &xml[list_body_start..list_end];
    let mut entries = Vec::new();
    let mut pos = 0;
    while let Some(start) = body[pos..].find(entry_tag) {
        let abs_start = pos + start;
        // Find the end of this element: either /> or </p:sldId>
        if let Some(self_close) = body[abs_start..].find("/>") {
            let entry_end = abs_start + self_close + 2;
            entries.push(&body[abs_start..entry_end]);
            pos = entry_end;
        } else if let Some(close) = body[abs_start..].find("</p:sldId>") {
            let entry_end = abs_start + close + "</p:sldId>".len();
            entries.push(&body[abs_start..entry_end]);
            pos = entry_end;
        } else {
            break;
        }
    }
    entries
}

fn rebuild_slide_id_list(xml: &str, entries: &[&str]) -> String {
    let start_tag = "<p:sldIdLst";
    let end_tag = "</p:sldIdLst>";

    let list_start = match xml.find(start_tag) {
        Some(pos) => pos,
        None => return xml.to_owned(),
    };
    let list_body_start = match xml[list_start..].find('>') {
        Some(pos) => list_start + pos + 1,
        None => return xml.to_owned(),
    };
    let list_end = match xml[list_body_start..].find(end_tag) {
        Some(pos) => list_body_start + pos + end_tag.len(),
        None => return xml.to_owned(),
    };

    let mut result = String::with_capacity(xml.len());
    result.push_str(&xml[..list_body_start]);
    for entry in entries {
        result.push_str(entry);
    }
    result.push_str(&xml[list_end - end_tag.len()..]);
    result
}

fn open(data: &[u8]) -> Result<ZipArchive, EngineError> {
    let len = data.len() as u64;
    ZipArchive::open(
        Box::new(Cursor::new(data.to_vec())),
        len,
        Limits { max_part_bytes: u64::MAX, ..Limits::default() },
        CancelToken::default(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::zip::testing::zip;

    fn sample_pptx() -> Vec<u8> {
        let pres = br#"<?xml version="1.0"?><p:presentation><p:sldIdLst><p:sldId id="256" r:id="rId2"/><p:sldId id="257" r:id="rId3"/><p:sldId id="258" r:id="rId4"/></p:sldIdLst></p:presentation>"#;
        let slide1 = b"<p:sld>slide1</p:sld>";
        let slide2 = b"<p:sld>slide2</p:sld>";
        let slide3 = b"<p:sld>slide3</p:sld>";
        zip(&[
            ("ppt/presentation.xml", pres),
            ("ppt/slides/slide1.xml", slide1),
            ("ppt/slides/slide2.xml", slide2),
            ("ppt/slides/slide3.xml", slide3),
        ])
    }

    #[test]
    fn count_slides() {
        let data = sample_pptx();
        let info = pptx_slide_count(&data).unwrap();
        assert_eq!(info.slide_count, 3);
    }

    #[test]
    fn reorder_reverse() {
        let data = sample_pptx();
        let out = reorder_pptx_slides(&data, &[2, 1, 0]).unwrap();
        let info = pptx_slide_count(&out).unwrap();
        assert_eq!(info.slide_count, 3);
        let mut archive = open(&out).unwrap();
        let xml = String::from_utf8(archive.read_to_vec("ppt/presentation.xml").unwrap()).unwrap();
        let first_258 = xml.find("rId4").unwrap();
        let first_257 = xml.find("rId3").unwrap();
        let first_256 = xml.find("rId2").unwrap();
        assert!(first_258 < first_257);
        assert!(first_257 < first_256);
    }

    #[test]
    fn delete_middle() {
        let data = sample_pptx();
        let out = reorder_pptx_slides(&data, &[0, 2]).unwrap();
        let info = pptx_slide_count(&out).unwrap();
        assert_eq!(info.slide_count, 2);
        let mut archive = open(&out).unwrap();
        let xml = String::from_utf8(archive.read_to_vec("ppt/presentation.xml").unwrap()).unwrap();
        assert!(xml.contains("rId2"));
        assert!(!xml.contains("rId3"));
        assert!(xml.contains("rId4"));
    }

    #[test]
    fn empty_keep_rejected() {
        let data = sample_pptx();
        assert!(reorder_pptx_slides(&data, &[]).is_err());
    }

    #[test]
    fn out_of_range_rejected() {
        let data = sample_pptx();
        assert!(reorder_pptx_slides(&data, &[5]).is_err());
    }
}
