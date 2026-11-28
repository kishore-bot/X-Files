//! Office Open XML package conventions shared by DOCX, XLSX and PPTX:
//! relationship files and target resolution.

use std::collections::{HashMap, HashSet};
use std::io::{Cursor, Write};

use document_core::{CancelToken, EngineError, Result};
use flate2::write::DeflateEncoder;
use flate2::Compression;

use crate::xml::{Xml, XmlReader};
use crate::zip::ZipArchive;

/// Changes applied while copying an OPC package into a new ZIP.
#[derive(Default)]
pub(crate) struct PackageEdit {
    /// Replacement contents for existing parts, keyed by archive path.
    pub replace: HashMap<String, Vec<u8>>,
    /// Existing parts to drop from the output.
    pub remove: HashSet<String>,
    /// New parts appended after the existing ones, in order.
    pub add: Vec<(String, Vec<u8>)>,
}

/// Rewrites `original` with `edit` applied. Kept parts are read, deflated and
/// written one at a time, so only the output buffer and one part are resident.
/// Parts keep their archive order; added parts follow them.
pub(crate) fn rewrite_package(original: &[u8], edit: &PackageEdit) -> std::result::Result<Vec<u8>, EngineError> {
    let mut archive = ZipArchive::open(
        Box::new(Cursor::new(original.to_vec())),
        original.len() as u64,
        document_core::Limits { max_part_bytes: u64::MAX, ..document_core::Limits::default() },
        CancelToken::default(),
    )?;
    let mut names: Vec<String> = archive.entries().iter().map(|e| e.name.clone()).collect();
    // OpenDocument packages (ODS sheet edits come through here) need `mimetype` first and stored.
    names.sort_by_key(|name| name != "mimetype");
    let mut out = Cursor::new(Vec::new());
    let mut central = Vec::new();
    let mut count: u16 = 0;

    let write_part = |name: &str, data: &[u8], out: &mut Cursor<Vec<u8>>, central: &mut Vec<u8>| -> std::result::Result<(), EngineError> {
        let offset = out.position() as u32;
        let crc = crc32fast::hash(data);
        let (method, compressed): (u16, Vec<u8>) = if name == "mimetype" {
            (0, data.to_vec())
        } else {
            let mut enc = DeflateEncoder::new(Vec::new(), Compression::default());
            enc.write_all(data).map_err(EngineError::from)?;
            (8, enc.finish().map_err(EngineError::from)?)
        };
        let mut local = Vec::with_capacity(30 + name.len());
        local.extend(0x0403_4b50u32.to_le_bytes());
        local.extend([20, 0, 0, 0]);
        local.extend(method.to_le_bytes());
        local.extend([0; 4]);
        local.extend(crc.to_le_bytes());
        local.extend((compressed.len() as u32).to_le_bytes());
        local.extend((data.len() as u32).to_le_bytes());
        local.extend((name.len() as u16).to_le_bytes());
        local.extend(0u16.to_le_bytes());
        local.extend(name.as_bytes());
        out.write_all(&local).map_err(EngineError::from)?;
        out.write_all(&compressed).map_err(EngineError::from)?;
        central.extend(0x0201_4b50u32.to_le_bytes());
        central.extend([20, 0, 20, 0, 0, 0]);
        central.extend(method.to_le_bytes());
        central.extend([0; 4]);
        central.extend(crc.to_le_bytes());
        central.extend((compressed.len() as u32).to_le_bytes());
        central.extend((data.len() as u32).to_le_bytes());
        central.extend((name.len() as u16).to_le_bytes());
        central.extend([0; 12]);
        central.extend(offset.to_le_bytes());
        central.extend(name.as_bytes());
        Ok(())
    };

    for name in &names {
        if edit.remove.contains(name) {
            continue;
        }
        match edit.replace.get(name) {
            Some(new) => write_part(name.as_str(), new.as_slice(), &mut out, &mut central)?,
            None => {
                let data = archive.read_to_vec(name.as_str())?;
                write_part(name.as_str(), data.as_slice(), &mut out, &mut central)?;
            }
        }
        count = count.wrapping_add(1);
    }
    for (name, data) in &edit.add {
        write_part(name.as_str(), data.as_slice(), &mut out, &mut central)?;
        count = count.wrapping_add(1);
    }

    let cd_offset = out.position() as u32;
    out.write_all(&central).map_err(EngineError::from)?;
    let mut eocd = Vec::with_capacity(22);
    eocd.extend(0x0605_4b50u32.to_le_bytes());
    eocd.extend([0; 4]);
    eocd.extend(count.to_le_bytes());
    eocd.extend(count.to_le_bytes());
    eocd.extend((central.len() as u32).to_le_bytes());
    eocd.extend(cd_offset.to_le_bytes());
    eocd.extend(0u16.to_le_bytes());
    out.write_all(&eocd).map_err(EngineError::from)?;
    Ok(out.into_inner())
}

pub(crate) struct Relationship {
    pub id: String,
    pub kind: String,
    /// Archive path for internal targets; the raw URL for external ones.
    pub target: String,
    pub external: bool,
    /// Archive path of an internal target that stays inside the package
    /// (see [`resolve_inside`]).
    pub inside: Option<String>,
}

/// Relationships of `part`, read from its `_rels/<name>.rels` sibling.
/// A missing relationships part is treated as empty.
pub(crate) fn relationships(archive: &mut ZipArchive, part: &str) -> Result<Vec<Relationship>> {
    let (dir, file) = part.rsplit_once('/').unwrap_or(("", part));
    let rels_path = if dir.is_empty() { format!("_rels/{file}.rels") } else { format!("{dir}/_rels/{file}.rels") };
    if archive.find(&rels_path).is_none() {
        return Ok(Vec::new());
    }
    let bytes = archive.read_to_vec(&rels_path)?;
    let mut xml = XmlReader::new(bytes.as_slice(), CancelToken::default());
    let mut rels = Vec::new();
    loop {
        match xml.next()? {
            Xml::Start(tag) | Xml::Empty(tag) if tag.name == "Relationship" => {
                let external = tag.attr("TargetMode") == Some("External");
                if let (Some(id), Some(kind), Some(target)) = (tag.attr("Id"), tag.attr("Type"), tag.attr("Target")) {
                    let inside = if external { None } else { resolve_inside(dir, target) };
                    let target = if external { target.to_owned() } else { resolve(dir, target) };
                    rels.push(Relationship { id: id.into(), kind: kind.into(), target, external, inside });
                }
            }
            Xml::Eof => return Ok(rels),
            _ => {}
        }
    }
}

/// Target of the first relationship whose type ends with `/suffix`.
pub(crate) fn target_of(rels: &[Relationship], suffix: &str) -> Option<String> {
    rels.iter().find(|r| !r.external && r.kind.rsplit('/').next() == Some(suffix)).map(|r| r.target.clone())
}

/// External hyperlink targets by relationship id. Only web and mail links
/// are kept; anything else (file:, javascript:, app schemes) is dropped.
pub(crate) fn hyperlinks(rels: &[Relationship]) -> std::collections::HashMap<String, String> {
    rels.iter()
        .filter(|r| r.external && r.kind.ends_with("/hyperlink") && is_safe_link(&r.target))
        .map(|r| (r.id.clone(), r.target.clone()))
        .collect()
}

pub(crate) fn is_safe_link(url: &str) -> bool {
    let lower = url.trim().to_ascii_lowercase();
    lower.starts_with("https://") || lower.starts_with("http://") || lower.starts_with("mailto:")
}

/// The package's main part (`word/document.xml`, `xl/workbook.xml`, ...).
pub(crate) fn main_part(archive: &mut ZipArchive) -> Result<Option<String>> {
    Ok(target_of(&relationships(archive, "")?, "officeDocument"))
}

/// Resolves a relationship target relative to the directory of its source part.
pub(crate) fn resolve(base_dir: &str, target: &str) -> String {
    let target = percent_decode(target);
    let mut segments: Vec<&str> =
        if target.starts_with('/') { Vec::new() } else { base_dir.split('/').filter(|s| !s.is_empty()).collect() };
    for segment in target.split('/') {
        match segment {
            "" | "." => {}
            ".." => {
                segments.pop();
            }
            s => segments.push(s),
        }
    }
    segments.join("/")
}

/// Like [`resolve`], for targets a document controls that select what gets
/// read: `None` for anything that is not a plain path inside the container
/// (a URL or other scheme, a network or Windows path, or `..` climbing above
/// the root, also when percent-encoded). A leading `/` means the container
/// root; nothing here can reach the file system.
pub(crate) fn resolve_inside(base_dir: &str, target: &str) -> Option<String> {
    let target = percent_decode(target);
    let scheme = target.split('/').next().is_some_and(|first| first.contains(':'));
    if scheme || target.starts_with("//") || target.contains(|c: char| c == '\\' || c.is_control()) {
        return None;
    }
    let mut segments: Vec<&str> =
        if target.starts_with('/') { Vec::new() } else { base_dir.split('/').filter(|s| !s.is_empty()).collect() };
    for segment in target.split('/') {
        match segment {
            "" | "." => {}
            ".." => {
                segments.pop()?;
            }
            s => segments.push(s),
        }
    }
    Some(segments.join("/"))
}

pub(crate) fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%'
            && let Some(byte) = s.get(i + 1..i + 3).and_then(|h| u8::from_str_radix(h, 16).ok())
        {
            out.push(byte);
            i += 3;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolves_relative_and_absolute_targets() {
        assert_eq!(resolve("xl", "worksheets/sheet1.xml"), "xl/worksheets/sheet1.xml");
        assert_eq!(resolve("ppt/slides", "../media/a%20b.png"), "ppt/media/a b.png");
        assert_eq!(resolve("word", "/word/document.xml"), "word/document.xml");
        assert_eq!(resolve("", "./word/document.xml"), "word/document.xml");
    }

    #[test]
    fn resolve_inside_refuses_what_leaves_the_container() {
        let inside = |base, target: &str| resolve_inside(base, target);
        assert_eq!(inside("word", "media/a%20b.png").as_deref(), Some("word/media/a b.png"));
        assert_eq!(inside("OEBPS/text", "../images/a.png").as_deref(), Some("OEBPS/images/a.png"));
        assert_eq!(inside("word", "/word/media/a.png").as_deref(), Some("word/media/a.png"));
        for hostile in [
            "../../etc/passwd",
            "..%2F..%2Fetc%2Fpasswd",
            "%2e%2e/%2e%2e/a.png",
            "http://evil.example/a.png",
            "HTTPS://evil.example/a.png",
            "http%3A//evil.example/a.png",
            "file:///etc/a.png",
            "data:image/png;base64,AAAA",
            "//evil.example/a.png",
            "C:/Windows/a.png",
            "..\\..\\a.png",
            "a\0.png",
        ] {
            assert_eq!(inside("word", hostile), None, "{hostile}");
        }
    }
}
