//! Digital signatures in a PDF: who signed, when, why, and how much of the
//! file each signature covers.
//!
//! This reports what the file declares. It does not check the cryptography:
//! the signer name comes from the signature dictionary or, failing that, the
//! first certificate's subject, and is labelled as declared, not trusted.

use std::io::{Read, Seek};

use document_core::Result;

use crate::pdf_annot::read_text_string;
use crate::pdf_file::PdfFile;
use crate::pdf_syntax::{Dict, Obj};

/// Form fields visited at most, so a crafted tree cannot run forever.
const MAX_FIELDS: usize = 4096;
const MAX_DEPTH: usize = 32;
/// Signature `/Contents` read at most when looking for a certificate name.
const MAX_CONTENTS: usize = 256 * 1024;

/// One signature field that holds a signature.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PdfSignatureInfo {
    /// The field's full name, such as `Signature1`.
    pub field: String,
    /// `/Name`, else the first certificate's subject common name.
    pub signer: Option<String>,
    /// `/M` as `YYYY-MM-DD HH:MM` plus the offset when given.
    pub signed_at: Option<String>,
    pub reason: Option<String>,
    pub location: Option<String>,
    pub contact: Option<String>,
    /// `/SubFilter`, such as `adbe.pkcs7.detached` or `ETSI.CAdES.detached`.
    pub format: Option<String>,
    /// The byte range is well formed: two spans around the signature value.
    pub byte_range_ok: bool,
    /// The signed spans reach the end of the file: nothing was added after.
    pub covers_whole_file: bool,
}

/// Lists every signed signature field. An unsigned file gives an empty list.
pub fn read_signatures<R: Read + Seek>(source: R) -> Result<Vec<PdfSignatureInfo>> {
    let mut file = PdfFile::open(source)?;
    // An encrypted file with a user password hides its strings; report nothing then.
    if file.unlock(None).is_err() {
        return Ok(Vec::new());
    }
    let len = file.len;
    let catalog = file.catalog()?;
    let Some(form) = catalog.get("AcroForm") else { return Ok(Vec::new()) };
    let Obj::Dict(form) = file.resolve(form)? else { return Ok(Vec::new()) };
    let Some(fields) = form.get("Fields") else { return Ok(Vec::new()) };
    let Obj::Array(fields) = file.resolve(fields)? else { return Ok(Vec::new()) };

    let mut out = Vec::new();
    let mut seen = 0usize;
    // (field, parent name, inherited field type, depth)
    let mut stack: Vec<(Obj, String, Option<Vec<u8>>, usize)> =
        fields.into_iter().rev().map(|f| (f, String::new(), None, 0)).collect();
    while let Some((field, parent, inherited, depth)) = stack.pop() {
        seen += 1;
        if seen > MAX_FIELDS || depth > MAX_DEPTH {
            break;
        }
        let Obj::Dict(dict) = file.resolve(&field)? else { continue };
        let name = match dict.get("T").map(|t| file.resolve(t)).transpose()? {
            Some(Obj::Str(bytes)) => {
                let part = read_text_string(&bytes);
                if parent.is_empty() { part } else { format!("{parent}.{part}") }
            }
            _ => parent.clone(),
        };
        let kind = match dict.get("FT") {
            Some(Obj::Name(n)) => Some(n.clone()),
            _ => inherited,
        };
        if let Some(Obj::Array(kids)) = dict.get("Kids").map(|k| file.resolve(k)).transpose()? {
            for kid in kids.into_iter().rev() {
                stack.push((kid, name.clone(), kind.clone(), depth + 1));
            }
        }
        if kind.as_deref() != Some(b"Sig".as_slice()) {
            continue;
        }
        let Some(value) = dict.get("V") else { continue };
        let Obj::Dict(value) = file.resolve(value)? else { continue };
        out.push(describe(&mut file, &value, name, len)?);
    }
    Ok(out)
}

fn describe<R: Read + Seek>(file: &mut PdfFile<R>, v: &Dict, field: String, len: u64) -> Result<PdfSignatureInfo> {
    let mut text = |key: &str| -> Result<Option<String>> {
        Ok(match v.get(key).map(|o| file.resolve(o)).transpose()? {
            Some(Obj::Str(bytes)) => Some(read_text_string(&bytes)).filter(|s| !s.trim().is_empty()),
            _ => None,
        })
    };
    let name = text("Name")?;
    let when = text("M")?.and_then(|m| pdf_date(&m));
    let reason = text("Reason")?;
    let location = text("Location")?;
    let contact = text("ContactInfo")?;
    let format = match v.get("SubFilter") {
        Some(Obj::Name(n)) => Some(String::from_utf8_lossy(n).into_owned()),
        _ => None,
    };
    let range: Vec<u64> = match v.get("ByteRange").map(|o| file.resolve(o)).transpose()? {
        Some(Obj::Array(items)) => items.iter().filter_map(|o| o.as_i64()).filter_map(|n| u64::try_from(n).ok()).collect(),
        _ => Vec::new(),
    };
    let byte_range_ok = range.len() == 4 && range[0] == 0 && range[1] <= range[2] && range[2].saturating_add(range[3]) <= len;
    let covers_whole_file = byte_range_ok && range[2] + range[3] == len;
    let signer = match name {
        Some(n) => Some(n),
        None => match v.get("Contents").map(|o| file.resolve(o)).transpose()? {
            Some(Obj::Str(der)) => subject_common_name(&der[..der.len().min(MAX_CONTENTS)]),
            _ => None,
        },
    };
    Ok(PdfSignatureInfo {
        field,
        signer,
        signed_at: when,
        reason,
        location,
        contact,
        format,
        byte_range_ok,
        covers_whole_file,
    })
}

/// `D:YYYYMMDDHHmmSSOHH'mm'` to `YYYY-MM-DD HH:MM (+HH:MM)`; partial dates keep what they have.
pub(crate) fn pdf_date(raw: &str) -> Option<String> {
    let s = raw.trim().strip_prefix("D:").unwrap_or(raw.trim());
    let digits = |from: usize, to: usize| s.get(from..to).filter(|p| p.bytes().all(|b| b.is_ascii_digit()));
    let year = digits(0, 4)?;
    let month = digits(4, 6).unwrap_or("01");
    let day = digits(6, 8).unwrap_or("01");
    let mut out = format!("{year}-{month}-{day}");
    if let (Some(h), Some(m)) = (digits(8, 10), digits(10, 12)) {
        out.push_str(&format!(" {h}:{m}"));
        match s.as_bytes().get(14) {
            Some(b'Z') => out.push_str(" UTC"),
            Some(&sign @ (b'+' | b'-')) => {
                if let Some(oh) = digits(15, 17) {
                    let om = digits(18, 20).unwrap_or("00");
                    out.push_str(&format!(" ({}{oh}:{om})", sign as char));
                }
            }
            _ => {}
        }
    }
    Some(out)
}

/// The common names (OID 2.5.4.3) in a DER blob, in order. In a PKCS#7
/// signature the first certificate's issuer comes first, then its subject.
fn common_names(der: &[u8]) -> Vec<String> {
    const OID_CN: [u8; 5] = [0x06, 0x03, 0x55, 0x04, 0x03];
    let mut names = Vec::new();
    let mut i = 0;
    while i + OID_CN.len() + 2 <= der.len() {
        if der[i..i + OID_CN.len()] == OID_CN {
            let tag = der[i + 5];
            let n = der[i + 6] as usize;
            let start = i + 7;
            if n < 0x80 && start + n <= der.len() {
                let bytes = &der[start..start + n];
                let name = match tag {
                    0x1E => {
                        let units: Vec<u16> = bytes.chunks_exact(2).map(|c| u16::from_be_bytes([c[0], c[1]])).collect();
                        String::from_utf16_lossy(&units)
                    }
                    0x0C | 0x13 | 0x16 | 0x14 => String::from_utf8_lossy(bytes).into_owned(),
                    _ => String::new(),
                };
                if !name.trim().is_empty() {
                    names.push(name.trim().to_owned());
                }
                i = start + n;
                continue;
            }
        }
        i += 1;
    }
    names
}

fn subject_common_name(der: &[u8]) -> Option<String> {
    let names = common_names(der);
    names.get(1).or_else(|| names.first()).cloned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dates() {
        assert_eq!(pdf_date("D:20240131094500+05'30'").as_deref(), Some("2024-01-31 09:45 (+05:30)"));
        assert_eq!(pdf_date("D:20240131094500Z").as_deref(), Some("2024-01-31 09:45 UTC"));
        assert_eq!(pdf_date("D:2024").as_deref(), Some("2024-01-01"));
        assert_eq!(pdf_date("junk"), None);
    }

    #[test]
    fn common_name_prefers_subject() {
        let cn = |s: &str| {
            let mut v = vec![0x06, 0x03, 0x55, 0x04, 0x03, 0x0C, s.len() as u8];
            v.extend_from_slice(s.as_bytes());
            v
        };
        let mut der = cn("Issuer CA");
        der.extend(cn("Jane Signer"));
        assert_eq!(subject_common_name(&der).as_deref(), Some("Jane Signer"));
        assert_eq!(subject_common_name(&cn("Only")).as_deref(), Some("Only"));
    }

    fn assemble(bodies: &[&str]) -> Vec<u8> {
        let mut out = b"%PDF-1.7\n".to_vec();
        let mut offsets = Vec::new();
        for (index, body) in bodies.iter().enumerate() {
            offsets.push(out.len());
            out.extend_from_slice(format!("{} 0 obj\n{body}\nendobj\n", index + 1).as_bytes());
        }
        let at = out.len();
        out.extend_from_slice(format!("xref\n0 {}\n0000000000 65535 f \n", bodies.len() + 1).as_bytes());
        for offset in offsets {
            out.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
        }
        out.extend_from_slice(
            format!("trailer\n<</Size {} /Root 1 0 R>>\nstartxref\n{at}\n%%EOF\n", bodies.len() + 1).as_bytes(),
        );
        out
    }

    #[test]
    fn reads_signature_field() {
        let pdf = assemble(&[
            "<</Type /Catalog /Pages 2 0 R /AcroForm <</Fields [4 0 R] /SigFlags 3>>>>",
            "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 9 9]>>",
            "<</Type /Page /Parent 2 0 R>>",
            "<</FT /Sig /T (Signature1) /V 5 0 R>>",
            "<</Type /Sig /SubFilter /adbe.pkcs7.detached /Name (Jane Signer) /Reason (Approved) \
             /M (D:20240131094500Z) /ByteRange [0 10 20 5] /Contents <00>>>",
            "<</FT /Tx /T (Plain)>>",
        ]);
        let sigs = read_signatures(std::io::Cursor::new(pdf)).unwrap();
        assert_eq!(sigs.len(), 1);
        let s = &sigs[0];
        assert_eq!(s.field, "Signature1");
        assert_eq!(s.signer.as_deref(), Some("Jane Signer"));
        assert_eq!(s.reason.as_deref(), Some("Approved"));
        assert_eq!(s.format.as_deref(), Some("adbe.pkcs7.detached"));
        assert_eq!(s.signed_at.as_deref(), Some("2024-01-31 09:45 UTC"));
        assert!(s.byte_range_ok);
        assert!(!s.covers_whole_file, "bytes follow the signed range");
    }

    #[test]
    fn unsigned_file_is_empty() {
        let pdf = assemble(&[
            "<</Type /Catalog /Pages 2 0 R>>",
            "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 9 9]>>",
            "<</Type /Page /Parent 2 0 R>>",
        ]);
        assert!(read_signatures(std::io::Cursor::new(pdf)).unwrap().is_empty());
    }
}
