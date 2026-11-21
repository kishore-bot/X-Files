//! Opens ZIP and compound-file containers and decides what they hold.
//!
//! Classification looks inside the container, never at the file name.
//! ZIP: the ODF/EPUB `mimetype` entry, then the OOXML package relationships,
//! then well-known part names; anything else is listed as a plain archive.
//! Compound file: an encrypted package is unlocked and handed to the ZIP path;
//! otherwise the main stream's name picks the legacy format.

use std::io::Cursor;

use document_core::{
    DocumentInfo, DocumentKind, EngineError, ErrorKind, FormatId, Input, OpenContext, OpenDocument, Parser, Result,
};

use crate::cfb::{CfbFile, MAX_STREAM_BYTES};
use crate::docx::DocxSource;
use crate::encrypted::{self, ENCRYPTED_PACKAGE, ENCRYPTION_INFO};
use crate::epub::EpubSource;
use crate::legacy_doc::DocSource;
use crate::legacy_ppt::PptSource;
use crate::legacy_xls::XlsSource;
use crate::listing::ListingSource;
use crate::odf::OdfSource;
use crate::ooxml::main_part;
use crate::pptx::PptxSource;
use crate::stream::{Parts, Streamed};
use crate::xlsx::XlsxSource;
use crate::zip::ZipArchive;

pub struct ZipParser;

impl Parser for ZipParser {
    fn name(&self) -> &'static str {
        "zip"
    }

    fn handles(&self, format: FormatId) -> bool {
        format == FormatId::Zip
    }

    fn open(&self, input: Input, ctx: OpenContext) -> Result<Box<dyn OpenDocument>> {
        let mut archive = ZipArchive::open(input.reader, input.byte_size, ctx.limits, ctx.cancel.clone())?;
        let (format, main) = classify(&mut archive)?;
        let kind = match format {
            FormatId::Xlsx | FormatId::Ods => DocumentKind::Spreadsheet,
            FormatId::Pptx | FormatId::Odp => DocumentKind::Presentation,
            FormatId::Zip => DocumentKind::Archive,
            _ => DocumentKind::Text,
        };
        let info = DocumentInfo { format, kind, byte_size: input.byte_size, encoding: None };
        if format == FormatId::Zip {
            return Ok(Box::new(Streamed::new(info, ListingSource::new(archive))));
        }
        let parts = Parts::new(archive, ctx.cancel);
        let required = || main.clone().ok_or_else(|| EngineError::malformed("the document's main part is missing"));
        Ok(match format {
            FormatId::Docx => Box::new(Streamed::new(info, DocxSource::open(parts, required()?)?)),
            FormatId::Xlsx => Box::new(Streamed::new(info, XlsxSource::open(parts, required()?)?)),
            FormatId::Pptx => Box::new(Streamed::new(info, PptxSource::open(parts, required()?)?)),
            FormatId::Odt | FormatId::Ods | FormatId::Odp => {
                Box::new(Streamed::new(info, OdfSource::new(parts, format)))
            }
            FormatId::Epub => Box::new(Streamed::new(info, EpubSource::open(parts)?)),
            other => unreachable!("classify never returns {other:?}"),
        })
    }
}

pub struct CompoundParser;

impl Parser for CompoundParser {
    fn name(&self) -> &'static str {
        "compound"
    }

    fn handles(&self, format: FormatId) -> bool {
        format == FormatId::OleCompound
    }

    fn open(&self, input: Input, ctx: OpenContext) -> Result<Box<dyn OpenDocument>> {
        let byte_size = input.byte_size;
        let mut cfb = CfbFile::open(input.reader, byte_size, ctx.limits, ctx.cancel.clone())?;
        if cfb.contains(ENCRYPTION_INFO) && cfb.contains(ENCRYPTED_PACKAGE) {
            return open_encrypted(&mut cfb, ctx);
        }
        let info = |format, kind| DocumentInfo { format, kind, byte_size, encoding: None };
        if cfb.contains("WordDocument") {
            let source = DocSource::open(&mut cfb, ctx.cancel)?;
            return Ok(Box::new(Streamed::new(info(FormatId::Doc, DocumentKind::Text), source)));
        }
        if cfb.contains("Workbook") {
            let data = cfb.read_stream("Workbook", MAX_STREAM_BYTES)?;
            let source = XlsSource::open(data, ctx.limits, ctx.cancel)?;
            return Ok(Box::new(Streamed::new(info(FormatId::Xls, DocumentKind::Spreadsheet), source)));
        }
        if cfb.contains("PowerPoint Document") {
            let source = PptSource::open(cfb, ctx.cancel)?;
            return Ok(Box::new(Streamed::new(info(FormatId::Ppt, DocumentKind::Presentation), source)));
        }
        if cfb.contains("Book") {
            return Err(EngineError::unsupported("Excel 95 and older workbooks are not supported"));
        }
        // Rights-managed OOXML keeps its transforms here.
        if cfb.contains("\u{6}DataSpaces") {
            return Err(EngineError::new(
                ErrorKind::UnsupportedEncryption,
                "This file uses rights management, which isn't supported.",
            ));
        }
        Err(EngineError::unsupported("This compound file is not a Word, Excel or PowerPoint document"))
    }
}

/// Unlocks the package and reads the plaintext as the OOXML file it is. It stays
/// in memory for the session, within [`MAX_STREAM_BYTES`], and is wiped on close.
fn open_encrypted(cfb: &mut CfbFile, ctx: OpenContext) -> Result<Box<dyn OpenDocument>> {
    let plain = encrypted::decrypt(cfb, ctx.password, &ctx.cancel)?;
    // Detection normally vouches for the signature; here nothing has yet.
    if !plain.starts_with(b"PK\x03\x04") && !plain.starts_with(b"PK\x05\x06") {
        return Err(EngineError::malformed("the protected file's contents are not an Office document"));
    }
    let byte_size = plain.len() as u64;
    let inner = Input { reader: Box::new(Cursor::new(plain)), byte_size };
    ZipParser.open(inner, OpenContext { password: None, ..ctx })
}

/// The container's format and, for OOXML, its main part.
fn classify(archive: &mut ZipArchive) -> Result<(FormatId, Option<String>)> {
    if let Some(entry) = archive.find("mimetype").filter(|e| e.size <= 256) {
        let name = entry.name.clone();
        let mime = String::from_utf8_lossy(&archive.read_to_vec(&name)?).trim().to_owned();
        let format = match mime.trim_end_matches("-template") {
            "application/epub+zip" => Some(FormatId::Epub),
            "application/vnd.oasis.opendocument.text" => Some(FormatId::Odt),
            "application/vnd.oasis.opendocument.spreadsheet" => Some(FormatId::Ods),
            "application/vnd.oasis.opendocument.presentation" => Some(FormatId::Odp),
            _ => None,
        };
        if let Some(format) = format {
            return Ok((format, None));
        }
    }
    if archive.find("[Content_Types].xml").is_some() || archive.find("_rels/.rels").is_some() {
        let main = main_part(archive)?.or_else(|| {
            ["word/document.xml", "xl/workbook.xml", "ppt/presentation.xml"]
                .into_iter()
                .find(|p| archive.find(p).is_some())
                .map(str::to_owned)
        });
        let format = match main.as_deref().and_then(|m| m.split('/').next()) {
            Some("word") => Some(FormatId::Docx),
            Some("xl") => Some(FormatId::Xlsx),
            Some("ppt") => Some(FormatId::Pptx),
            _ => None,
        };
        if let Some(format) = format {
            return Ok((format, main));
        }
    }
    if archive.find("META-INF/container.xml").is_some() {
        return Ok((FormatId::Epub, None));
    }
    Ok((FormatId::Zip, None))
}

#[cfg(test)]
mod compound_tests {
    use std::io::Cursor;

    use document_core::Limits;

    use crate::cfb::testing::cfb;
    use crate::encrypted::testing::{AgileSpec, agile_file, standard_file};
    use crate::legacy_doc::testing::simple_doc;
    use crate::legacy_ppt::testing::simple_ppt;
    use crate::legacy_xls::testing::simple_xls;
    use crate::testing::{open_zip_err, texts};
    use crate::zip::testing::zip;

    use super::*;

    #[test]
    fn unknown_compound_files_are_unsupported() {
        assert_eq!(open_zip_err(cfb(&[("Thumbs", b"x")])).kind, ErrorKind::UnsupportedFormat);
        assert_eq!(open_zip_err(cfb(&[])).kind, ErrorKind::UnsupportedFormat);
        // Half of an encrypted file is not one.
        assert_eq!(open_zip_err(cfb(&[(ENCRYPTED_PACKAGE, b"x")])).kind, ErrorKind::UnsupportedFormat);
    }

    #[test]
    fn a_password_on_a_plain_file_is_ignored() {
        let mut session =
            crate::engine(Limits::default()).open_protected(Cursor::new(simple_doc()), None, Some("unused")).unwrap();
        assert_eq!(session.info().format, FormatId::Doc);
        assert_eq!(texts(&session.next_batch().unwrap().unwrap().blocks), ["P Hello from Word"]);
    }

    #[test]
    fn a_workbook_inside_a_protected_file_reads_as_a_workbook() {
        let rels = br#"<Relationships><Relationship Id="r1" Type="http://x/officeDocument" Target="xl/workbook.xml"/></Relationships>"#;
        let workbook =
            br#"<workbook xmlns:r="r"><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>"#;
        let workbook_rels = br#"<Relationships><Relationship Id="rId1" Type="http://x/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"#;
        let sheet = br#"<worksheet><sheetData><row r="1"><c r="A1"><v>7</v></c></row></sheetData></worksheet>"#;
        let plain = zip(&[
            ("_rels/.rels", rels),
            ("xl/workbook.xml", workbook),
            ("xl/_rels/workbook.xml.rels", workbook_rels),
            ("xl/worksheets/sheet1.xml", sheet),
        ]);
        let file = standard_file(&plain, "pw", 128);
        let mut session = crate::engine(Limits::default()).open_protected(Cursor::new(file), None, Some("pw")).unwrap();
        assert_eq!(session.info().format, FormatId::Xlsx);
        assert_eq!(session.info().kind, DocumentKind::Spreadsheet);
        let mut blocks = Vec::new();
        while let Some(batch) = session.next_batch().unwrap() {
            blocks.extend(batch.blocks);
        }
        assert_eq!(texts(&blocks), ["S S", "R1 7"]);
    }

    /// Corrupts valid files many ways. Only panics and hangs can fail this: any
    /// result, typed error included, is fine.
    #[test]
    fn corrupted_files_never_panic() {
        let spec = AgileSpec { spin: 1, ..AgileSpec::default() };
        let seeds = [
            cfb(&[("Workbook", &simple_xls())]),
            simple_doc(),
            simple_ppt(),
            agile_file(&zip(&[("a", b"b")]), "pw", &spec),
        ];
        let mut state = 0x2545_F491_4F6C_DD1Du64;
        let mut next = move || {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state
        };
        for seed in &seeds {
            for _ in 0..1500 {
                let mut bytes = seed.clone();
                for _ in 0..1 + next() % 8 {
                    let at = (next() as usize) % bytes.len();
                    bytes[at] = match next() % 4 {
                        0 => 0,
                        1 => 0xFF,
                        2 => bytes[at] ^ (1 << (next() % 8)),
                        _ => next() as u8,
                    };
                }
                if next() % 16 == 0 {
                    bytes.truncate((next() as usize) % bytes.len());
                }
                let Ok(mut session) =
                    crate::engine(Limits::default()).open_protected(Cursor::new(bytes), None, Some("pw"))
                else {
                    continue;
                };
                while let Ok(Some(_)) = session.next_batch() {}
            }
        }
    }
}
