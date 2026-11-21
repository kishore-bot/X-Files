//! PPTX slide management: reorder and delete slides.
//!
//! Operates on `ppt/presentation.xml` to change the slide order. The actual
//! slide XML files stay in the ZIP untouched; only the slide-id list that
//! controls presentation order is rewritten.

use std::collections::HashSet;
use std::io::Cursor;

use document_core::{CancelToken, EngineError, Limits};

use crate::ooxml::{PackageEdit, relationships, rewrite_package};
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

const PRES_PART: &str = "ppt/presentation.xml";
const PRES_RELS_PART: &str = "ppt/_rels/presentation.xml.rels";
const CONTENT_TYPES_PART: &str = "[Content_Types].xml";
const SLIDE_CONTENT_TYPE: &str = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml";

/// Deletes the slide at `index` (0-based). The slide's part, its relationships,
/// its notes page, their content-type overrides, the presentation relationship
/// and the section-list references are all dropped, so no orphaned part remains.
/// Media that only the deleted slide used is left in place (harmless, bounded).
pub fn delete_pptx_slide(data: &[u8], index: u32) -> Result<Vec<u8>, EngineError> {
    let mut archive = open(data)?;
    let pres = read_text(&mut archive, PRES_PART)?;
    let pres_rels = read_text(&mut archive, PRES_RELS_PART)?;
    let content_types = read_text(&mut archive, CONTENT_TYPES_PART)?;
    let rels = relationships(&mut archive, PRES_PART)?;

    let entries = parse_slide_id_entries(&pres);
    if entries.len() < 2 {
        return Err(EngineError::malformed("a deck must keep at least one slide"));
    }
    let idx = index as usize;
    if idx >= entries.len() {
        return Err(EngineError::malformed("slide index out of range"));
    }
    let removed_entry = entries[idx];
    let removed_id = attr(removed_entry, "id")
        .ok_or_else(|| EngineError::malformed("slide without an id"))?
        .to_owned();
    let rid = attr(removed_entry, "r:id")
        .ok_or_else(|| EngineError::malformed("slide without a relationship"))?
        .to_owned();
    let part = rels
        .iter()
        .find(|r| r.id == rid && !r.external)
        .map(|r| r.target.clone())
        .ok_or_else(|| EngineError::malformed("slide relationship missing"))?;

    // The slide part plus its notes page (notes are owned by exactly one slide).
    let mut dropped = vec![part.clone()];
    for r in relationships(&mut archive, &part)? {
        if !r.external && r.kind.ends_with("/notesSlide") {
            dropped.push(r.target);
        }
    }
    let mut edit = PackageEdit::default();
    for name in &dropped {
        edit.remove.insert(name.clone());
        edit.remove.insert(rels_path_for(name));
    }
    let overrides: Vec<String> = dropped.iter().map(|name| format!("/{name}")).collect();
    let content_types = remove_elements(&content_types, "Override", |tag| {
        attr(tag, "PartName").is_some_and(|p| overrides.iter().any(|o| o == p))
    });

    // Media (and any other part) that only the deleted slide references goes too. A target survives
    // when some remaining part's relationships still point at it.
    let mut candidates: Vec<String> = Vec::new();
    for name in &dropped {
        for r in relationships(&mut archive, name)? {
            if !r.external && !dropped.contains(&r.target) {
                candidates.push(r.target);
            }
        }
    }
    if !candidates.is_empty() {
        let names: Vec<String> = archive.entries().iter().map(|e| e.name.clone()).collect();
        let mut referenced: HashSet<String> = HashSet::new();
        for rels_name in names.iter().filter(|n| n.ends_with(".rels")) {
            let Some(source) = part_of_rels(rels_name) else { continue };
            if dropped.contains(&source) || edit.remove.contains(rels_name) {
                continue;
            }
            for r in relationships(&mut archive, &source)? {
                if !r.external {
                    referenced.insert(r.target);
                }
            }
        }
        for target in candidates {
            if referenced.contains(&target) || archive.find(&target).is_none() {
                continue;
            }
            edit.remove.insert(target.clone());
            edit.remove.insert(rels_path_for(&target));
        }
    }

    let pres_rels = remove_elements(&pres_rels, "Relationship", |tag| attr(tag, "Id") == Some(rid.as_str()));
    let kept: Vec<&str> = entries.iter().enumerate().filter(|(i, _)| *i != idx).map(|(_, e)| *e).collect();
    let pres = rebuild_slide_id_list(&pres, &kept);
    let pres = remove_elements(&pres, "p14:sldId", |tag| attr(tag, "id") == Some(removed_id.as_str()));

    edit.replace.insert(PRES_PART.to_owned(), pres.into_bytes());
    edit.replace.insert(PRES_RELS_PART.to_owned(), pres_rels.into_bytes());
    edit.replace.insert(CONTENT_TYPES_PART.to_owned(), content_types.into_bytes());
    drop(archive);
    rewrite_package(data, &edit)
}

/// Inserts a copy of the slide at `index` directly after it. The copy gets a
/// new part, relationships without the notes link (so notes are not shared),
/// a fresh relationship id and a fresh slide id, plus its content-type override.
pub fn duplicate_pptx_slide(data: &[u8], index: u32) -> Result<Vec<u8>, EngineError> {
    let mut archive = open(data)?;
    let pres = read_text(&mut archive, PRES_PART)?;
    let pres_rels = read_text(&mut archive, PRES_RELS_PART)?;
    let content_types = read_text(&mut archive, CONTENT_TYPES_PART)?;
    let rels = relationships(&mut archive, PRES_PART)?;

    let entries = parse_slide_id_entries(&pres);
    let idx = index as usize;
    if idx >= entries.len() {
        return Err(EngineError::malformed("slide index out of range"));
    }
    let src_entry = entries[idx];
    let src_rid = attr(src_entry, "r:id").ok_or_else(|| EngineError::malformed("slide without a relationship"))?;
    let src_rel = rels
        .iter()
        .find(|r| r.id == src_rid && !r.external)
        .ok_or_else(|| EngineError::malformed("slide relationship missing"))?;
    let src_part = src_rel.target.clone();
    let slide_kind = src_rel.kind.clone();
    let slide_bytes = archive
        .read_to_vec(&src_part)
        .map_err(|_| EngineError::malformed("slide part missing"))?;
    let slide_type = override_type(&content_types, &format!("/{src_part}")).unwrap_or(SLIDE_CONTENT_TYPE.to_owned());

    let max_part = archive.entries().iter().filter_map(|e| slide_number(&e.name)).max().unwrap_or(0);
    let new_num = max_part + 1;
    let new_part = format!("ppt/slides/slide{new_num}.xml");

    let mut edit = PackageEdit::default();
    let src_rels_path = rels_path_for(&src_part);
    if archive.find(&src_rels_path).is_some() {
        let src_rels_xml = read_text(&mut archive, &src_rels_path)?;
        let copied = remove_elements(&src_rels_xml, "Relationship", |tag| {
            attr(tag, "Type").is_some_and(|t| t.ends_with("/notesSlide"))
        });
        edit.add.push((rels_path_for(&new_part), copied.into_bytes()));
    }
    edit.add.push((new_part.clone(), slide_bytes));

    let new_rid = next_rid(&pres_rels);
    let rel_xml = format!(
        r#"<Relationship Id="{new_rid}" Type="{slide_kind}" Target="slides/slide{new_num}.xml"/>"#
    );
    let pres_rels = insert_before(&pres_rels, "</Relationships>", &rel_xml)?;

    let new_id = entries
        .iter()
        .filter_map(|e| attr(e, "id")?.parse::<u32>().ok())
        .max()
        .unwrap_or(255)
        .max(255)
        + 1;
    let new_entry = format!(r#"<p:sldId id="{new_id}" r:id="{new_rid}"/>"#);
    let mut list: Vec<&str> = entries[..=idx].to_vec();
    list.push(new_entry.as_str());
    list.extend_from_slice(&entries[idx + 1..]);
    let pres = rebuild_slide_id_list(&pres, &list);

    let override_xml = format!(r#"<Override PartName="/{new_part}" ContentType="{slide_type}"/>"#);
    let content_types = insert_before(&content_types, "</Types>", &override_xml)?;

    edit.replace.insert(PRES_PART.to_owned(), pres.into_bytes());
    edit.replace.insert(PRES_RELS_PART.to_owned(), pres_rels.into_bytes());
    edit.replace.insert(CONTENT_TYPES_PART.to_owned(), content_types.into_bytes());
    drop(archive);
    rewrite_package(data, &edit)
}

/// Source part of a relationships part, e.g. `ppt/slides/_rels/slide1.xml.rels` gives `ppt/slides/slide1.xml`.
/// The package-level `_rels/.rels` has no source part and gives None.
fn part_of_rels(rels_name: &str) -> Option<String> {
    let stem = rels_name.strip_suffix(".rels")?;
    let (dir, file) = stem.rsplit_once("/_rels/")?;
    Some(if dir.is_empty() { file.to_owned() } else { format!("{dir}/{file}") })
}

fn read_text(archive: &mut ZipArchive, name: &str) -> Result<String, EngineError> {
    let bytes = archive.read_to_vec(name).map_err(|_| EngineError::malformed("missing package part"))?;
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

/// Relationship part of `part`, e.g. `ppt/slides/_rels/slide1.xml.rels`.
fn rels_path_for(part: &str) -> String {
    let (dir, file) = part.rsplit_once('/').unwrap_or(("", part));
    if dir.is_empty() { format!("_rels/{file}.rels") } else { format!("{dir}/_rels/{file}.rels") }
}

fn slide_number(name: &str) -> Option<u32> {
    name.strip_prefix("ppt/slides/slide")?.strip_suffix(".xml")?.parse().ok()
}

/// Value of attribute `name` in a start tag (double-quoted, as OOXML writes them).
fn attr<'a>(tag: &'a str, name: &str) -> Option<&'a str> {
    let needle = format!(" {name}=\"");
    let start = tag.find(&needle)? + needle.len();
    let end = tag[start..].find('"')? + start;
    Some(&tag[start..end])
}

fn override_type(content_types: &str, part_name: &str) -> Option<String> {
    let mut pos = 0;
    while let Some(rel) = content_types[pos..].find("<Override ") {
        let start = pos + rel;
        let end = start + content_types[start..].find('>')?;
        let tag = &content_types[start..=end];
        if attr(tag, "PartName") == Some(part_name) {
            return attr(tag, "ContentType").map(str::to_owned);
        }
        pos = end + 1;
    }
    None
}

/// Next free `rIdN` in a relationships part.
fn next_rid(rels_xml: &str) -> String {
    let mut max = 0u32;
    let mut pos = 0;
    while let Some(rel) = rels_xml[pos..].find("Id=\"rId") {
        let start = pos + rel + "Id=\"rId".len();
        let digits: String = rels_xml[start..].chars().take_while(char::is_ascii_digit).collect();
        if let Ok(n) = digits.parse::<u32>() {
            max = max.max(n);
        }
        pos = start;
    }
    format!("rId{}", max + 1)
}

fn insert_before(xml: &str, anchor: &str, fragment: &str) -> Result<String, EngineError> {
    let pos = xml.rfind(anchor).ok_or_else(|| EngineError::malformed("package part lacks its closing tag"))?;
    let mut out = String::with_capacity(xml.len() + fragment.len());
    out.push_str(&xml[..pos]);
    out.push_str(fragment);
    out.push_str(&xml[pos..]);
    Ok(out)
}

/// Removes every `<tag .../>` (or `<tag ...>...</tag>`) whose start tag matches
/// `drop`. Elements that do not match are copied through byte for byte.
fn remove_elements(xml: &str, tag: &str, drop: impl Fn(&str) -> bool) -> String {
    let open = format!("<{tag}");
    let close = format!("</{tag}>");
    let bytes = xml.as_bytes();
    let mut out = String::with_capacity(xml.len());
    let mut pos = 0;
    while let Some(rel) = xml[pos..].find(&open) {
        let start = pos + rel;
        let after = start + open.len();
        if !matches!(bytes.get(after), Some(b' ' | b'>' | b'/' | b'\t' | b'\r' | b'\n')) {
            out.push_str(&xml[pos..after]);
            pos = after;
            continue;
        }
        let Some(gt_rel) = xml[start..].find('>') else { break };
        let gt = start + gt_rel;
        let end = if bytes[gt - 1] == b'/' {
            gt + 1
        } else {
            match xml[gt..].find(&close) {
                Some(c) => gt + c + close.len(),
                None => break,
            }
        };
        out.push_str(&xml[pos..start]);
        if !drop(&xml[start..=gt]) {
            out.push_str(&xml[start..end]);
        }
        pos = end;
    }
    out.push_str(&xml[pos..]);
    out
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

    /// A deck with package relationships, content types, a notes page on slide 2
    /// and a section list that names every slide.
    fn linked_pptx() -> Vec<u8> {
        let pres = br#"<?xml version="1.0"?><p:presentation><p:sldIdLst><p:sldId id="256" r:id="rId2"/><p:sldId id="257" r:id="rId3"/></p:sldIdLst><p:extLst><p14:sectionLst><p14:section><p14:sldIdLst><p14:sldId id="256"/><p14:sldId id="257"/></p14:sldIdLst></p14:section></p14:sectionLst></p:extLst></p:presentation>"#;
        let pres_rels = br#"<?xml version="1.0"?><Relationships><Relationship Id="rId1" Type="http://x/slideMaster" Target="slideMasters/slideMaster1.xml"/><Relationship Id="rId2" Type="http://x/slide" Target="slides/slide1.xml"/><Relationship Id="rId3" Type="http://x/slide" Target="slides/slide2.xml"/></Relationships>"#;
        let slide2_rels = br#"<?xml version="1.0"?><Relationships><Relationship Id="rId1" Type="http://x/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="http://x/notesSlide" Target="../notesSlides/notesSlide1.xml"/></Relationships>"#;
        let types = br#"<?xml version="1.0"?><Types><Default Extension="xml" ContentType="application/xml"/><Override PartName="/ppt/presentation.xml" ContentType="pres"/><Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/><Override PartName="/ppt/slides/slide2.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/><Override PartName="/ppt/notesSlides/notesSlide1.xml" ContentType="notes"/></Types>"#;
        zip(&[
            ("[Content_Types].xml", types),
            ("ppt/presentation.xml", pres),
            ("ppt/_rels/presentation.xml.rels", pres_rels),
            ("ppt/slides/slide1.xml", b"<p:sld>one</p:sld>"),
            ("ppt/slides/slide2.xml", b"<p:sld>two</p:sld>"),
            ("ppt/slides/_rels/slide2.xml.rels", slide2_rels),
            ("ppt/notesSlides/notesSlide1.xml", b"<p:notes>n</p:notes>"),
        ])
    }

    fn part_text(data: &[u8], name: &str) -> Option<String> {
        let mut archive = open(data).unwrap();
        archive.find(name)?;
        Some(String::from_utf8(archive.read_to_vec(name).unwrap()).unwrap())
    }

    #[test]
    fn delete_removes_slide_notes_and_references() {
        let out = delete_pptx_slide(&linked_pptx(), 1).unwrap();
        assert!(part_text(&out, "ppt/slides/slide2.xml").is_none());
        assert!(part_text(&out, "ppt/slides/_rels/slide2.xml.rels").is_none());
        assert!(part_text(&out, "ppt/notesSlides/notesSlide1.xml").is_none());
        let types = part_text(&out, "[Content_Types].xml").unwrap();
        assert!(!types.contains("/ppt/slides/slide2.xml"));
        assert!(!types.contains("notesSlide1"));
        assert!(types.contains("/ppt/slides/slide1.xml"));
        let rels = part_text(&out, "ppt/_rels/presentation.xml.rels").unwrap();
        assert!(!rels.contains("rId3"));
        assert!(rels.contains("rId2"));
        let pres = part_text(&out, "ppt/presentation.xml").unwrap();
        assert!(!pres.contains("257"));
        assert!(pres.contains("256"));
        assert_eq!(pptx_slide_count(&out).unwrap().slide_count, 1);
    }

    #[test]
    fn delete_drops_media_only_the_slide_used() {
        let base = linked_pptx();
        // Add a picture used only by slide 2 and one shared by slides 1 and 2.
        let mut edit = PackageEdit::default();
        edit.replace.insert(
            "ppt/slides/_rels/slide2.xml.rels".to_owned(),
            br#"<Relationships><Relationship Id="rId1" Type="http://x/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="http://x/notesSlide" Target="../notesSlides/notesSlide1.xml"/><Relationship Id="rId3" Type="http://x/image" Target="../media/only2.png"/><Relationship Id="rId4" Type="http://x/image" Target="../media/both.png"/></Relationships>"#.to_vec(),
        );
        edit.replace.insert(
            "ppt/slides/_rels/slide1.xml.rels".to_owned(),
            br#"<Relationships><Relationship Id="rId1" Type="http://x/image" Target="../media/both.png"/></Relationships>"#.to_vec(),
        );
        edit.add.push(("ppt/media/only2.png".to_owned(), b"png2".to_vec()));
        edit.add.push(("ppt/media/both.png".to_owned(), b"png1".to_vec()));
        let with_media = rewrite_package(&base, &edit).unwrap();
        let out = delete_pptx_slide(&with_media, 1).unwrap();
        assert!(part_text(&out, "ppt/media/only2.png").is_none());
        assert!(part_text(&out, "ppt/media/both.png").is_some());
    }

    #[test]
    fn delete_refuses_last_slide() {
        let once = delete_pptx_slide(&linked_pptx(), 1).unwrap();
        assert!(delete_pptx_slide(&once, 0).is_err());
    }

    #[test]
    fn duplicate_adds_part_without_notes_and_keeps_order() {
        let out = duplicate_pptx_slide(&linked_pptx(), 0).unwrap();
        assert_eq!(part_text(&out, "ppt/slides/slide3.xml").as_deref(), Some("<p:sld>one</p:sld>"));
        let types = part_text(&out, "[Content_Types].xml").unwrap();
        assert!(types.contains("/ppt/slides/slide3.xml"));
        let rels = part_text(&out, "ppt/_rels/presentation.xml.rels").unwrap();
        assert!(rels.contains(r#"Id="rId4""#));
        assert!(rels.contains("slides/slide3.xml"));
        let pres = part_text(&out, "ppt/presentation.xml").unwrap();
        let first = pres.find(r#"id="256""#).unwrap();
        let copy = pres.find(r#"id="258" r:id="rId4""#).unwrap();
        let second = pres.find(r#"id="257""#).unwrap();
        assert!(first < copy && copy < second);
        assert_eq!(pptx_slide_count(&out).unwrap().slide_count, 3);
    }

    #[test]
    fn duplicate_copy_has_no_notes_relationship() {
        let out = duplicate_pptx_slide(&linked_pptx(), 1).unwrap();
        let copied = part_text(&out, "ppt/slides/_rels/slide3.xml.rels").unwrap();
        assert!(copied.contains("slideLayout1"));
        assert!(!copied.contains("notesSlide"));
    }

    #[test]
    fn remove_elements_keeps_unmatched_bytes() {
        let xml = r#"<a><Relationship Id="x"/><Relationship Id="y">body</Relationship><Relationships/></a>"#;
        let out = remove_elements(xml, "Relationship", |t| attr(t, "Id") == Some("x"));
        assert_eq!(out, r#"<a><Relationship Id="y">body</Relationship><Relationships/></a>"#);
    }
}
