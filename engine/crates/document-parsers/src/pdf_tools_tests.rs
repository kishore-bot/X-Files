//! The PDF tools on generated files. Every output is parsed again by the
//! engine's own reader; `testdata/pdf_crypt` holds files written by qpdf, so
//! the handlers are also tested against an outside writer.

use std::cell::Cell;
use std::io::{Cursor, Read, Write};

use document_core::ErrorKind;
use flate2::Compression;
use flate2::read::ZlibDecoder;
use flate2::write::ZlibEncoder;
use zeroize::Zeroizing;

use super::*;

const USER: &str = "userpw";
const OWNER: &str = "ownerpw";

#[derive(Default)]
struct Watcher {
    progress: Vec<(u32, u32)>,
    cancel_after_checks: Option<u32>,
    checks: Cell<u32>,
}

impl PdfToolObserver for Watcher {
    fn on_progress(&mut self, done: u32, total: u32) {
        self.progress.push((done, total));
    }

    fn is_cancelled(&self) -> bool {
        self.checks.set(self.checks.get() + 1);
        self.cancel_after_checks.is_some_and(|limit| self.checks.get() > limit)
    }
}

fn source(bytes: &[u8], password: Option<&str>) -> PdfToolSource<Cursor<Vec<u8>>> {
    PdfToolSource { reader: Cursor::new(bytes.to_vec()), password: password.map(|p| Zeroizing::new(p.to_owned())) }
}

fn run_with(
    inputs: &[(&[u8], Option<&str>)],
    op: &PdfToolOp,
    watcher: &mut Watcher,
) -> Result<(Vec<u8>, PdfToolReport)> {
    let sources = inputs.iter().map(|(bytes, password)| source(bytes, *password)).collect();
    let mut out = Vec::new();
    let report = run_pdf_tool(sources, op, &mut out, watcher)?;
    Ok((out, report))
}

fn run(inputs: &[(&[u8], Option<&str>)], op: &PdfToolOp) -> Result<(Vec<u8>, PdfToolReport)> {
    run_with(inputs, op, &mut Watcher::default())
}

/// Runs and applies the checks every output has to pass.
fn run_ok(inputs: &[(&[u8], Option<&str>)], op: &PdfToolOp) -> (Vec<u8>, PdfToolReport) {
    let (bytes, report) = run(inputs, op).unwrap();
    let password = match op {
        PdfToolOp::AddPassword { password } => Some(password.as_str()),
        _ => None,
    };
    verify_pdf_tool_output(Cursor::new(bytes.clone()), password, report.page_count).unwrap();
    assert_eq!(report.bytes as usize, bytes.len());
    (bytes, report)
}

fn open(bytes: &[u8], password: Option<&str>) -> PdfFile<Cursor<Vec<u8>>> {
    let mut file = PdfFile::open(Cursor::new(bytes.to_vec())).unwrap();
    file.unlock(password).unwrap();
    assert!(dangling_references(&mut file).unwrap().is_empty());
    file
}

fn assemble(bodies: &[String], trailer_extra: &str) -> Vec<u8> {
    let mut out = b"%PDF-1.7\n%\xE2\xE3\xCF\xD3\n".to_vec();
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
        format!("trailer\n<</Size {} /Root 1 0 R {trailer_extra}>>\nstartxref\n{at}\n%%EOF\n", bodies.len() + 1)
            .as_bytes(),
    );
    out
}

fn stream(body: &str) -> String {
    format!("<</Length {}>>\nstream\n{body}\nendstream", body.len())
}

/// `pages` pages sharing an inherited media box and font; each has text naming itself.
fn doc(marker: &str, pages: usize) -> Vec<u8> {
    let kids: Vec<String> = (0..pages).map(|i| format!("{} 0 R", 5 + i * 2)).collect();
    let mut bodies = vec![
        "<</Type /Catalog /Pages 2 0 R>>".to_owned(),
        format!(
            "<</Type /Pages /Kids [{}] /Count {pages} /MediaBox [0 0 300 400] /Resources <</Font <</F1 3 0 R>>>>>>",
            kids.join(" ")
        ),
        format!("<</Type /Font /Subtype /Type1 /BaseFont /{marker}>>"),
        format!("<</Title ({marker} title)>>"),
    ];
    for i in 0..pages {
        bodies.push(format!("<</Type /Page /Parent 2 0 R /Contents {} 0 R>>", 6 + i * 2));
        bodies.push(stream(&format!("BT /F1 12 Tf ({marker} page {i}) Tj ET")));
    }
    assemble(&bodies, "/Info 4 0 R")
}

fn inflate_if_needed(dict: &Dict, data: Vec<u8>) -> Vec<u8> {
    if dict.get("Filter").and_then(Obj::as_name) == Some(b"FlateDecode") {
        let mut out = Vec::new();
        ZlibDecoder::new(&data[..]).read_to_end(&mut out).unwrap();
        out
    } else {
        data
    }
}

/// A stream object's content, decrypted and inflated.
fn stream_data<R: Read + Seek>(file: &mut PdfFile<R>, number: u32) -> Vec<u8> {
    let loaded = file.load(number).unwrap();
    let Obj::Dict(dict) = loaded.value else { panic!("not a stream dictionary") };
    let length = file.resolve(dict.get("Length").unwrap()).unwrap().as_i64().unwrap() as u64;
    let raw = file.stream_raw(loaded.stream_at.expect("object has no stream"), length).unwrap();
    let plain = match &file.crypt {
        Some(crypt) => crypt.decrypt_stream(number, loaded.generation, &dict, &raw).unwrap(),
        None => raw,
    };
    inflate_if_needed(&dict, plain)
}

fn page_dict<R: Read + Seek>(file: &mut PdfFile<R>, index: usize) -> Dict {
    let object = file.pages().unwrap()[index].object;
    match file.object(object).unwrap() {
        Obj::Dict(dict) => dict,
        _ => panic!("page is not a dictionary"),
    }
}

/// All of a page's content streams, joined.
fn page_text<R: Read + Seek>(file: &mut PdfFile<R>, index: usize) -> String {
    let page = page_dict(file, index);
    let contents = file.resolve(page.get("Contents").unwrap()).unwrap();
    let numbers: Vec<u32> = match (&contents, page.get("Contents").unwrap()) {
        (Obj::Array(items), _) => items
            .iter()
            .map(|item| match item {
                Obj::Ref(number, _) => *number,
                _ => panic!("content item is not a reference"),
            })
            .collect(),
        (_, Obj::Ref(number, _)) => vec![*number],
        _ => panic!("no contents"),
    };
    let mut text = Vec::new();
    for number in numbers {
        text.extend(stream_data(file, number));
        text.push(b'\n');
    }
    String::from_utf8_lossy(&text).into_owned()
}

fn font_name<R: Read + Seek>(file: &mut PdfFile<R>, index: usize) -> Vec<u8> {
    let page = page_dict(file, index);
    let Obj::Dict(resources) = file.resolve(page.get("Resources").unwrap()).unwrap() else { panic!("no resources") };
    let Obj::Dict(fonts) = file.resolve(resources.get("Font").unwrap()).unwrap() else { panic!("no fonts") };
    let Obj::Dict(font) = file.resolve(fonts.get("F1").unwrap()).unwrap() else { panic!("no font") };
    font.get("BaseFont").unwrap().as_name().unwrap().to_vec()
}

fn contains(haystack: &[u8], needle: &[u8]) -> bool {
    haystack.windows(needle.len()).any(|w| w == needle)
}

fn pick(page: u32) -> PdfPagePick {
    PdfPagePick { page, rotation: 0 }
}

#[test]
fn merge_keeps_every_page_in_order_and_each_font_resolves() {
    let (a, b) = (doc("Aaa", 2), doc("Bbb", 3));
    let (out, report) = run_ok(&[(&a, None), (&b, None)], &PdfToolOp::Merge);
    assert_eq!(report.page_count, 5);
    let mut file = open(&out, None);
    let expected = ["Aaa page 0", "Aaa page 1", "Bbb page 0", "Bbb page 1", "Bbb page 2"];
    for (index, text) in expected.iter().enumerate() {
        assert!(page_text(&mut file, index).contains(text), "page {index}");
        assert_eq!(font_name(&mut file, index), &text.as_bytes()[..3], "page {index}");
    }
    // Inherited attributes moved onto the pages.
    let page = page_dict(&mut file, 4);
    assert!(page.get("MediaBox").is_some() && page.get("Resources").is_some());
    assert_eq!(file.pages().unwrap()[4].geometry.x1, 300.0);
}

#[test]
fn merging_a_file_with_itself_duplicates_it() {
    let a = doc("Aaa", 2);
    let (out, report) = run_ok(&[(&a, None), (&a, None)], &PdfToolOp::Merge);
    assert_eq!(report.page_count, 4);
    assert!(page_text(&mut open(&out, None), 3).contains("Aaa page 1"));
}

#[test]
fn rebuild_reorders_deletes_and_duplicates() {
    let a = doc("Aaa", 4);
    let pages = vec![pick(2), pick(0), pick(2)];
    let (out, report) = run_ok(&[(&a, None)], &PdfToolOp::Rebuild { pages });
    assert_eq!(report.page_count, 3);
    let mut file = open(&out, None);
    assert!(page_text(&mut file, 0).contains("page 2"));
    assert!(page_text(&mut file, 1).contains("page 0"));
    assert!(page_text(&mut file, 2).contains("page 2"));
    assert!(!contains(&out, b"Aaa page 1") && !contains(&out, b"Aaa page 3"), "deleted pages must leave the file");
}

#[test]
fn rotation_adds_to_the_page_own_and_inherited_rotate() {
    let bodies = vec![
        "<</Type /Catalog /Pages 2 0 R>>".to_owned(),
        "<</Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /Rotate 90 /MediaBox [0 0 100 200]>>".to_owned(),
        "<</Type /Page /Parent 2 0 R>>".to_owned(),
        "<</Type /Page /Parent 2 0 R /Rotate 180>>".to_owned(),
    ];
    let a = assemble(&bodies, "");
    let pages = vec![PdfPagePick { page: 0, rotation: 90 }, PdfPagePick { page: 1, rotation: -270 }];
    let (out, _) = run_ok(&[(&a, None)], &PdfToolOp::Rebuild { pages });
    let mut file = open(&out, None);
    let rotations: Vec<u16> = file.pages().unwrap().iter().map(|page| page.geometry.rotate).collect();
    assert_eq!(rotations, [180, 270]);
    let err =
        run(&[(&a, None)], &PdfToolOp::Rebuild { pages: vec![PdfPagePick { page: 0, rotation: 45 }] }).unwrap_err();
    assert_eq!(err.kind, ErrorKind::Malformed);
}

#[test]
fn rebuild_refuses_nonsense_pages() {
    let a = doc("Aaa", 2);
    for pages in [vec![], vec![pick(2)]] {
        let err = run(&[(&a, None)], &PdfToolOp::Rebuild { pages }).unwrap_err();
        assert_eq!(err.kind, ErrorKind::Malformed);
    }
}

#[test]
fn insert_places_pages_at_the_requested_position() {
    let (a, pictures) = (doc("Aaa", 2), doc("Pic", 2));
    for (at, expected) in [
        (0, ["Pic page 0", "Pic page 1", "Aaa page 0", "Aaa page 1"]),
        (1, ["Aaa page 0", "Pic page 0", "Pic page 1", "Aaa page 1"]),
        (2, ["Aaa page 0", "Aaa page 1", "Pic page 0", "Pic page 1"]),
    ] {
        let (out, _) = run_ok(&[(&a, None), (&pictures, None)], &PdfToolOp::InsertPages { at_page: at });
        let mut file = open(&out, None);
        for (index, text) in expected.iter().enumerate() {
            assert!(page_text(&mut file, index).contains(text), "at {at}, page {index}");
        }
    }
    assert!(run(&[(&a, None), (&pictures, None)], &PdfToolOp::InsertPages { at_page: 3 }).is_err());
}

/// Three pages, with a kept and a lost link of each kind, an outline and a form.
fn linked_doc() -> Vec<u8> {
    let bodies = vec![
        "<</Type /Catalog /Pages 2 0 R /Outlines 12 0 R /AcroForm <</Fields [11 0 R]>> /StructTreeRoot 15 0 R /PageLabels <</Nums [0 <</S /D>>]>> /Names <</Dests 13 0 R>>>>".to_owned(),
        "<</Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 /MediaBox [0 0 300 400]>>".to_owned(),
        "<</Type /Page /Parent 2 0 R /Annots [6 0 R 7 0 R 8 0 R 9 0 R 10 0 R 11 0 R] /Contents 14 0 R>>".to_owned(),
        "<</Type /Page /Parent 2 0 R /Contents 16 0 R>>".to_owned(),
        "<</Type /Page /Parent 2 0 R /Contents 17 0 R>>".to_owned(),
        // To a kept page, explicitly.
        "<</Type /Annot /Subtype /Link /Rect [1 1 2 2] /Dest [5 0 R /Fit]>>".to_owned(),
        // To a page that goes, explicitly.
        "<</Type /Annot /Subtype /Link /Rect [1 1 2 2] /Dest [4 0 R /Fit]>>".to_owned(),
        // By name, to a kept page, through an action.
        "<</Type /Annot /Subtype /Link /Rect [1 1 2 2] /A <</S /GoTo /D (keep)>>>>".to_owned(),
        // By name, to a page that goes.
        "<</Type /Annot /Subtype /Link /Rect [1 1 2 2] /Dest (lose)>>".to_owned(),
        // To the web.
        "<</Type /Annot /Subtype /Link /Rect [1 1 2 2] /A <</S /URI /URI (https://example.org/)>>>>".to_owned(),
        "<</Type /Annot /Subtype /Widget /FT /Tx /T (field) /V (typed) /Rect [1 1 2 2] /P 3 0 R /Parent 18 0 R /AP <</N 19 0 R>>>>".to_owned(),
        "<</Type /Outlines /First 20 0 R /Last 20 0 R /Count 1>>".to_owned(),
        "<</Names [(keep) [5 0 R /Fit] (lose) <</D [4 0 R /Fit]>>]>>".to_owned(),
        stream("(first) Tj"),
        "<</Type /StructTreeRoot>>".to_owned(),
        stream("(second gone) Tj"),
        stream("(third) Tj"),
        "<</T (parent) /Kids [11 0 R 21 0 R]>>".to_owned(),
        "<</Type /XObject /Subtype /Form /BBox [0 0 1 1] /Length 0>>\nstream\n\nendstream".to_owned(),
        "<</Title (First) /Parent 12 0 R /Dest [4 0 R /Fit]>>".to_owned(),
        "<</Subtype /Widget /P 4 0 R /Parent 18 0 R /AP <</N 22 0 R>>>>".to_owned(),
        stream("(other widget appearance) Tj"),
    ];
    assemble(&bodies, "")
}

#[test]
fn extract_keeps_working_links_and_says_what_it_dropped() {
    let a = linked_doc();
    let (out, report) = run_ok(&[(&a, None)], &PdfToolOp::Rebuild { pages: vec![pick(0), pick(2)] });
    assert_eq!(
        report.dropped,
        PdfDropped {
            outlines: true,
            forms: true,
            named_destinations: true,
            structure_tags: true,
            page_labels: true,
            attachments: false,
            links: 2
        }
    );
    assert!(!contains(&out, b"second gone"), "the deleted page must not come along");
    assert!(!contains(&out, b"other widget appearance"), "the rest of the form must not come along");

    let mut file = open(&out, None);
    let kept = file.pages().unwrap();
    let catalog = file.catalog().unwrap();
    for gone in ["Outlines", "AcroForm", "Names", "StructTreeRoot", "PageLabels"] {
        assert!(catalog.get(gone).is_none(), "{gone}");
    }
    let page = page_dict(&mut file, 0);
    let Some(Obj::Array(annots)) = page.get("Annots") else { panic!("no annotations") };
    assert_eq!(annots.len(), 4, "explicit link, named link, web link and the field's look");
    let mut annotation = |index: usize| -> Dict {
        match file.resolve(&annots[index]).unwrap() {
            Obj::Dict(dict) => dict,
            _ => panic!("annotation is not a dictionary"),
        }
    };
    for index in [0, 1] {
        let dict = annotation(index);
        let Some(Obj::Array(dest)) = dict.get("Dest") else { panic!("link {index} has no destination") };
        assert_eq!(dest[0], Obj::Ref(kept[1].object, 0), "link {index} leads to the kept third page");
    }
    assert!(annotation(2).get("A").is_some(), "web links stay");
    let widget = annotation(3);
    assert!(widget.get("AP").is_some());
    for gone in ["Parent", "V", "T", "FT", "P"] {
        assert!(widget.get(gone).is_none(), "{gone}");
    }
}

#[test]
fn keeping_every_page_keeps_the_catalog_even_when_reordered() {
    let a = linked_doc();
    let pages = vec![pick(2), pick(1), pick(0)];
    let (out, report) = run_ok(&[(&a, None)], &PdfToolOp::Rebuild { pages });
    assert_eq!(report.dropped, PdfDropped::default());
    let mut file = open(&out, None);
    let catalog = file.catalog().unwrap();
    for kept in ["Outlines", "AcroForm", "Names", "StructTreeRoot", "PageLabels"] {
        assert!(catalog.get(kept).is_some(), "{kept}");
    }
    let Obj::Dict(outline) = file.resolve(catalog.get("Outlines").unwrap()).unwrap() else { panic!("no outline") };
    let Obj::Dict(first) = file.resolve(outline.get("First").unwrap()).unwrap() else { panic!("no entry") };
    let Some(Obj::Array(dest)) = first.get("Dest") else { panic!("no destination") };
    let second_source_page = file.pages().unwrap()[1].object;
    assert_eq!(dest[0], Obj::Ref(second_source_page, 0), "the outline follows the page to its new place");
}

#[test]
fn layers_that_were_hidden_stay_hidden_after_an_extract() {
    let bodies = vec![
        "<</Type /Catalog /Pages 2 0 R /OCProperties <</OCGs [5 0 R 6 0 R] /D <</OFF [6 0 R]>>>>>>".to_owned(),
        "<</Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 100 100]>>".to_owned(),
        "<</Type /Page /Parent 2 0 R>>".to_owned(),
        "<</Type /Page /Parent 2 0 R>>".to_owned(),
        "<</Type /OCG /Name (shown)>>".to_owned(),
        "<</Type /OCG /Name (hidden)>>".to_owned(),
    ];
    let a = assemble(&bodies, "");
    let (out, _) = run_ok(&[(&a, None)], &PdfToolOp::Rebuild { pages: vec![pick(0)] });
    let mut file = open(&out, None);
    let catalog = file.catalog().unwrap();
    let Some(Obj::Dict(layers)) = catalog.get("OCProperties") else { panic!("no layers") };
    let Some(Obj::Dict(default)) = layers.get("D") else { panic!("no default state") };
    let Some(Obj::Array(off)) = default.get("OFF") else { panic!("no hidden list") };
    let Obj::Dict(group) = file.resolve(&off[0]).unwrap() else { panic!("no group") };
    assert_eq!(group.get("Name"), Some(&Obj::Str(b"hidden".to_vec())));
}

#[test]
fn information_is_kept_and_a_pdfa_claim_is_not() {
    let a = doc("Aaa", 1);
    let (out, _) = run_ok(&[(&a, None)], &PdfToolOp::RemovePassword);
    let mut file = open(&out, None);
    let info = file.trailer.get("Info").cloned().unwrap();
    let Obj::Dict(info) = file.resolve(&info).unwrap() else { panic!("no info") };
    assert_eq!(info.get("Title"), Some(&Obj::Str(b"Aaa title".to_vec())));

    let claim = |xmp: &str| {
        assemble(
            &[
                "<</Type /Catalog /Pages 2 0 R /Metadata 4 0 R /OutputIntents []>>".to_owned(),
                "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 9 9]>>".to_owned(),
                "<</Type /Page /Parent 2 0 R>>".to_owned(),
                format!("<</Type /Metadata /Subtype /XML /Length {}>>\nstream\n{xmp}\nendstream", xmp.len()),
            ],
            "",
        )
    };
    let archived = claim("<rdf pdfaid:part='1'/>");
    assert!(inspect_for_tools(Cursor::new(archived.clone()), None).unwrap().archival);
    let (out, _) = run_ok(&[(&archived, None)], &PdfToolOp::RemovePassword);
    let catalog = open(&out, None).catalog().unwrap();
    assert!(catalog.get("Metadata").is_none() && catalog.get("OutputIntents").is_none());
    let plain = claim("<rdf title='x'/>");
    let (out, _) = run_ok(&[(&plain, None)], &PdfToolOp::RemovePassword);
    assert!(open(&out, None).catalog().unwrap().get("Metadata").is_some());
}

/// Rotated 90, cropped, with an annotation and a second content stream.
fn sized_doc() -> Vec<u8> {
    let bodies = vec![
        "<</Type /Catalog /Pages 2 0 R>>".to_owned(),
        "<</Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 200 100] /CropBox [10 20 110 70]>>".to_owned(),
        "<</Type /Page /Parent 2 0 R /Rotate 90 /Contents [5 0 R 6 0 R] /Annots [7 0 R]>>".to_owned(),
        "<</Type /Page /Parent 2 0 R /CropBox [0 0 200 100] /TrimBox [1 1 2 2] /UserUnit 2 /Contents 5 0 R>>"
            .to_owned(),
        stream("(one) Tj"),
        stream("(two) Tj"),
        "<</Type /Annot /Subtype /Ink /Rect [10 20 110 70] /InkList [[10 20 110 70]] /QuadPoints 8 0 R>>".to_owned(),
        "[10 20 110 70]".to_owned(),
    ];
    assemble(&bodies, "")
}

fn numbers(value: &Obj) -> Vec<f64> {
    match value {
        Obj::Array(items) => items.iter().filter_map(Obj::as_f64).collect(),
        _ => panic!("not an array"),
    }
}

#[test]
fn resize_scales_real_content_and_honours_rotation() {
    let a = sized_doc();
    let (out, _) = run_ok(&[(&a, None)], &PdfToolOp::Resize { width: 595.0, height: 842.0 });
    let mut file = open(&out, None);

    // Page 1 displays turned: its box is sideways, so it displays as portrait A4.
    let page = page_dict(&mut file, 0);
    assert_eq!(numbers(page.get("MediaBox").unwrap()), [0.0, 0.0, 842.0, 595.0]);
    assert_eq!(numbers(page.get("CropBox").unwrap()), [0.0, 0.0, 842.0, 595.0]);
    assert_eq!(page.get("Rotate").and_then(Obj::as_i64), Some(90));
    let geometry = file.pages().unwrap()[0].geometry;
    assert_eq!((geometry.display_width(), geometry.height().max(geometry.width())), (595.0, 842.0));

    // The old box is 100 by 50 at (10, 20): the scale is min(8.42, 11.9), centred.
    let text = page_text(&mut file, 0);
    let scale: f64 = 8.42;
    let dx = (842.0 - scale * 100.0) / 2.0 - scale * 10.0;
    let dy = (595.0 - scale * 50.0) / 2.0 - scale * 20.0;
    let expected =
        format!("q {scale:.6} 0 0 {scale:.6} {dx:.6} {dy:.6} cm 10.000000 20.000000 100.000000 50.000000 re W n");
    assert!(text.starts_with(&expected), "{text}");
    let order = ["cm", "(one) Tj", "(two) Tj", "Q"];
    let places: Vec<usize> = order.iter().map(|part| text.find(part).unwrap()).collect();
    assert!(places.windows(2).all(|w| w[0] < w[1]), "{text}");

    // The annotation moved with the content, however it stored its points.
    let Some(Obj::Array(annots)) = page.get("Annots") else { panic!("no annotations") };
    let Obj::Dict(annot) = file.resolve(&annots[0]).unwrap() else { panic!("no annotation") };
    let moved = numbers(annot.get("Rect").unwrap());
    let want = [10.0 * scale + dx, 20.0 * scale + dy, 110.0 * scale + dx, 70.0 * scale + dy];
    for (got, want) in moved.iter().zip(want) {
        assert!((got - want).abs() < 0.002, "{moved:?} vs {want}");
    }
    let Some(Obj::Array(ink)) = annot.get("InkList") else { panic!("no ink") };
    assert_eq!(numbers(&ink[0]), moved);
    assert_eq!(numbers(annot.get("QuadPoints").unwrap()), moved);

    // Page 2: stale boxes and the unit are gone, and it stays centred on its own scale.
    let page = page_dict(&mut file, 1);
    assert_eq!(numbers(page.get("MediaBox").unwrap()), [0.0, 0.0, 595.0, 842.0]);
    for gone in ["TrimBox", "UserUnit"] {
        assert!(page.get(gone).is_none(), "{gone}");
    }
    let text = page_text(&mut file, 1);
    let dy = (842.0 - 2.975 * 100.0) / 2.0;
    assert!(text.starts_with(&format!("q 2.975000 0 0 2.975000 0.000000 {dy:.6} cm")), "{text}");
}

#[test]
fn resize_refuses_a_size_that_means_nothing() {
    let a = doc("Aaa", 1);
    for (width, height) in [(0.0, 842.0), (595.0, -1.0), (f64::NAN, 1.0), (20_000.0, 842.0)] {
        let err = run(&[(&a, None)], &PdfToolOp::Resize { width, height }).unwrap_err();
        assert_eq!(err.kind, ErrorKind::Malformed);
    }
}

/// Catalog, pages tree, page and font in a compressed object stream, found
/// through a cross-reference stream, as modern writers do it.
fn compressed_doc() -> Vec<u8> {
    let inner = [
        (1u32, "<</Type /Catalog /Pages 2 0 R>>"),
        (2, "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 300 400]>>"),
        (3, "<</Type /Page /Parent 2 0 R /Contents 6 0 R /Resources <</Font <</F1 4 0 R>>>>>>"),
        (4, "<</Type /Font /Subtype /Type1 /BaseFont /Packed>>"),
    ];
    let mut head = String::new();
    let mut body = String::new();
    for (number, text) in inner {
        head.push_str(&format!("{number} {} ", body.len()));
        body.push_str(text);
        body.push(' ');
    }
    let plain = format!("{head}{body}");
    let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
    encoder.write_all(plain.as_bytes()).unwrap();
    let packed = encoder.finish().unwrap();

    let mut out = b"%PDF-1.5\n".to_vec();
    let objstm_at = out.len();
    out.extend_from_slice(
        format!(
            "5 0 obj\n<</Type /ObjStm /N {} /First {} /Filter /FlateDecode /Length {}>>\nstream\n",
            inner.len(),
            head.len(),
            packed.len()
        )
        .as_bytes(),
    );
    out.extend_from_slice(&packed);
    out.extend_from_slice(b"\nendstream\nendobj\n");
    let content_at = out.len();
    let content = stream("(packed page) Tj");
    out.extend_from_slice(format!("6 0 obj\n{content}\nendobj\n").as_bytes());
    let xref_at = out.len();
    let entry = |kind: u8, second: usize, third: u16| {
        let mut row = vec![kind];
        row.extend_from_slice(&(second as u32).to_be_bytes());
        row.extend_from_slice(&third.to_be_bytes());
        row
    };
    let mut rows = entry(0, 0, 65535);
    for index in 0..inner.len() {
        rows.extend(entry(2, 5, index as u16));
    }
    rows.extend(entry(1, objstm_at, 0));
    rows.extend(entry(1, content_at, 0));
    rows.extend(entry(1, xref_at, 0));
    let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
    encoder.write_all(&rows).unwrap();
    let rows = encoder.finish().unwrap();
    out.extend_from_slice(
        format!(
            "7 0 obj\n<</Type /XRef /Size 8 /W [1 4 2] /Root 1 0 R /Filter /FlateDecode /Length {}>>\nstream\n",
            rows.len()
        )
        .as_bytes(),
    );
    out.extend_from_slice(&rows);
    out.extend_from_slice(format!("\nendstream\nendobj\nstartxref\n{xref_at}\n%%EOF\n").as_bytes());
    out
}

#[test]
fn object_and_cross_reference_streams_are_read_and_flattened() {
    let a = compressed_doc();
    let (out, _) = run_ok(&[(&a, None)], &PdfToolOp::Merge);
    assert!(!contains(&out, b"/ObjStm") && !contains(&out, b"/XRef"));
    let mut file = open(&out, None);
    assert_eq!(font_name(&mut file, 0), b"Packed");
    assert!(page_text(&mut file, 0).contains("packed page"));
}

#[test]
fn compressed_streams_are_copied_byte_for_byte() {
    let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
    encoder.write_all(b"(compressed) Tj").unwrap();
    let packed = encoder.finish().unwrap();
    let head = b"%PDF-1.7\n".to_vec();
    let mut out = head;
    let mut offsets = Vec::new();
    for (number, body) in [
        (1, "<</Type /Catalog /Pages 2 0 R>>".to_owned()),
        (2, "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 9 9]>>".to_owned()),
        (3, "<</Type /Page /Parent 2 0 R /Contents 4 0 R>>".to_owned()),
    ] {
        offsets.push(out.len());
        out.extend_from_slice(format!("{number} 0 obj\n{body}\nendobj\n").as_bytes());
    }
    offsets.push(out.len());
    out.extend_from_slice(b"4 0 obj\n<</Filter /FlateDecode /Length 5 0 R>>\nstream\n");
    out.extend_from_slice(&packed);
    out.extend_from_slice(b"\nendstream\nendobj\n");
    offsets.push(out.len());
    out.extend_from_slice(format!("5 0 obj\n{}\nendobj\n", packed.len()).as_bytes());
    let at = out.len();
    out.extend_from_slice(b"xref\n0 6\n0000000000 65535 f \n");
    for offset in offsets {
        out.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    out.extend_from_slice(format!("trailer\n<</Size 6 /Root 1 0 R>>\nstartxref\n{at}\n%%EOF\n").as_bytes());

    let (rewritten, _) = run_ok(&[(&out, None)], &PdfToolOp::Merge);
    assert!(contains(&rewritten, &packed));
    assert!(contains(&rewritten, format!("/Length {}", packed.len()).as_bytes()), "the length is now direct");
    assert!(page_text(&mut open(&rewritten, None), 0).contains("(compressed) Tj"));
}

#[test]
fn stream_keyword_on_a_read_boundary_is_not_lost() {
    for pad in 16_300..16_400 {
        let filler = "x".repeat(pad);
        let bodies = vec![
            "<</Type /Catalog /Pages 2 0 R>>".to_owned(),
            "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 9 9]>>".to_owned(),
            "<</Type /Page /Parent 2 0 R /Contents 4 0 R>>".to_owned(),
            format!("<</Filler ({filler}) /Length 8>>\nstream\n(far) Tj\nendstream"),
        ];
        let a = assemble(&bodies, "");
        let (out, _) = run_ok(&[(&a, None)], &PdfToolOp::Merge);
        assert!(page_text(&mut open(&out, None), 0).contains("(far) Tj"), "pad {pad}");
    }
}

#[test]
fn add_password_round_trips_to_identical_content() {
    let a = doc("Aaa", 3);
    let op = PdfToolOp::AddPassword { password: Zeroizing::new("correct horse".to_owned()) };
    let (out, _) = run_ok(&[(&a, None)], &op);
    assert!(out.starts_with(b"%PDF-2.0"));
    for plaintext in [&b"Aaa page"[..], b"Aaa title"] {
        assert!(!contains(&out, plaintext), "plaintext left in the file");
    }

    // Wrong and missing passwords are told apart, for reading and for inspecting.
    let wrong = PdfFile::open(Cursor::new(out.clone())).unwrap().unlock(Some("nope")).unwrap_err();
    assert_eq!(wrong.kind, ErrorKind::WrongPassword);
    let missing = PdfFile::open(Cursor::new(out.clone())).unwrap().unlock(None).unwrap_err();
    assert_eq!(missing.kind, ErrorKind::PasswordRequired);
    let shut = inspect_for_tools(Cursor::new(out.clone()), None).unwrap_err();
    assert_eq!(shut.kind, ErrorKind::PasswordRequired);
    let err = inspect_for_tools(Cursor::new(out.clone()), Some("nope")).unwrap_err();
    assert_eq!(err.kind, ErrorKind::WrongPassword);
    let open_info = inspect_for_tools(Cursor::new(out.clone()), Some("correct horse")).unwrap();
    assert_eq!((open_info.encrypted, open_info.page_count), (true, 3));

    // The right password gives back the original bytes of everything.
    let mut file = open(&out, Some("correct horse"));
    for index in 0..3 {
        assert_eq!(page_text(&mut file, index).trim_end(), format!("BT /F1 12 Tf (Aaa page {index}) Tj ET"));
        assert_eq!(font_name(&mut file, index), b"Aaa");
    }
    let info = file.trailer.get("Info").cloned().unwrap();
    let Obj::Dict(info) = file.resolve(&info).unwrap() else { panic!("no info") };
    assert_eq!(info.get("Title"), Some(&Obj::Str(b"Aaa title".to_vec())));

    // The dictionary itself reads without a key, with the sizes the standard sets.
    let mut raw = PdfFile::open(Cursor::new(out.clone())).unwrap();
    let encrypt = raw.trailer.get("Encrypt").cloned().unwrap();
    let Obj::Dict(encrypt) = raw.resolve(&encrypt).unwrap() else { panic!("no dictionary") };
    for (key, len) in [("U", 48), ("O", 48), ("UE", 32), ("OE", 32), ("Perms", 16)] {
        let Some(Obj::Str(value)) = encrypt.get(key) else { panic!("{key}") };
        assert_eq!(value.len(), len, "{key}");
    }
    assert_eq!(encrypt.get("R").and_then(Obj::as_i64), Some(6));
}

#[test]
fn add_password_then_remove_gives_a_plain_file() {
    let a = doc("Aaa", 2);
    let password = "s3cret";
    let (locked, _) = run_ok(&[(&a, None)], &PdfToolOp::AddPassword { password: Zeroizing::new(password.to_owned()) });
    assert_eq!(run(&[(&locked, None)], &PdfToolOp::RemovePassword).unwrap_err().kind, ErrorKind::PasswordRequired);
    assert_eq!(run(&[(&locked, Some("x"))], &PdfToolOp::RemovePassword).unwrap_err().kind, ErrorKind::WrongPassword);
    let (plain, _) = run_ok(&[(&locked, Some(password))], &PdfToolOp::RemovePassword);
    assert!(contains(&plain, b"Aaa page 1") && contains(&plain, b"Aaa title"));
    assert!(PdfFile::open(Cursor::new(plain.clone())).unwrap().trailer.get("Encrypt").is_none());
    assert!(!inspect_for_tools(Cursor::new(plain), None).unwrap().encrypted);
}

#[test]
fn add_password_can_replace_an_earlier_one() {
    let a = doc("Aaa", 1);
    let lock = |password: &str| PdfToolOp::AddPassword { password: Zeroizing::new(password.to_owned()) };
    let (first, _) = run_ok(&[(&a, None)], &lock("one"));
    let (second, _) = run_ok(&[(&first, Some("one"))], &lock("two"));
    assert!(page_text(&mut open(&second, Some("two")), 0).contains("Aaa page 0"));
    assert_eq!(
        PdfFile::open(Cursor::new(second)).unwrap().unlock(Some("one")).unwrap_err().kind,
        ErrorKind::WrongPassword
    );
}

#[test]
fn unusable_new_passwords_are_refused() {
    let a = doc("Aaa", 1);
    for bad in ["".to_owned(), "x".repeat(128), "tab\there".to_owned()] {
        let op = PdfToolOp::AddPassword { password: Zeroizing::new(bad) };
        assert!(run(&[(&a, None)], &op).is_err());
    }
    let longest = "x".repeat(127);
    let op = PdfToolOp::AddPassword { password: Zeroizing::new(longest.clone()) };
    let (out, _) = run_ok(&[(&a, None)], &op);
    open(&out, Some(&longest));
}

#[test]
fn a_non_ascii_password_round_trips() {
    let a = doc("Aaa", 1);
    let password = "pässwörd-密码";
    let op = PdfToolOp::AddPassword { password: Zeroizing::new(password.to_owned()) };
    let (out, _) = run_ok(&[(&a, None)], &op);
    assert!(page_text(&mut open(&out, Some(password)), 0).contains("Aaa page 0"));
}

const R2: &[u8] = include_bytes!("../testdata/pdf_crypt/r2_rc4_40.pdf");
const R3: &[u8] = include_bytes!("../testdata/pdf_crypt/r3_rc4_128.pdf");
const R4_RC4: &[u8] = include_bytes!("../testdata/pdf_crypt/r4_rc4_128.pdf");
const R4_AES: &[u8] = include_bytes!("../testdata/pdf_crypt/r4_aes_128.pdf");
const R4_CLEAR_METADATA: &[u8] = include_bytes!("../testdata/pdf_crypt/r4_aes_clear_metadata.pdf");
const R6: &[u8] = include_bytes!("../testdata/pdf_crypt/r6_aes_256.pdf");
const NO_ASSEMBLE: &[u8] = include_bytes!("../testdata/pdf_crypt/r4_empty_user_no_assemble.pdf");
const NO_MODIFY: &[u8] = include_bytes!("../testdata/pdf_crypt/r6_empty_user_no_modify.pdf");
const NO_PRINT: &[u8] = include_bytes!("../testdata/pdf_crypt/r6_empty_user_no_print.pdf");
const ALL_ALLOWED: &[u8] = include_bytes!("../testdata/pdf_crypt/r6_empty_user_all_allowed.pdf");
const USER_NO_COPY: &[u8] = include_bytes!("../testdata/pdf_crypt/r6_user_no_copy.pdf");

const LOCKED: [(&str, &[u8]); 6] = [
    ("R2", R2),
    ("R3", R3),
    ("R4 RC4", R4_RC4),
    ("R4 AES", R4_AES),
    ("R4 clear metadata", R4_CLEAR_METADATA),
    ("R6", R6),
];

#[test]
fn every_outside_handler_opens_with_either_password_and_decrypts() {
    for (name, bytes) in LOCKED {
        for (role, password) in [("user", USER), ("owner", OWNER)] {
            let mut file = open(bytes, Some(password));
            assert_eq!(file.pages().unwrap().len(), 2, "{name}");
            assert!(page_text(&mut file, 1).contains("Fixture page 2"), "{name} with {role} password");
            assert_eq!(font_name(&mut file, 0), b"Helvetica", "{name}");
            let info = file.trailer.get("Info").cloned().unwrap();
            let Obj::Dict(info) = file.resolve(&info).unwrap() else { panic!("{name}: no info") };
            assert_eq!(info.get("Title"), Some(&Obj::Str(b"Fixture title".to_vec())), "{name}");
            let catalog = file.catalog().unwrap();
            let Some(Obj::Ref(metadata, _)) = catalog.get("Metadata") else { panic!("{name}: no metadata") };
            assert!(contains(&stream_data(&mut file, *metadata), b"fixture metadata"), "{name}");
            assert_eq!(file.crypt.as_ref().unwrap().is_owner, password == OWNER, "{name}");
        }
        let wrong = PdfFile::open(Cursor::new(bytes.to_vec())).unwrap().unlock(Some("nope")).unwrap_err();
        assert_eq!(wrong.kind, ErrorKind::WrongPassword, "{name}");
        let none = PdfFile::open(Cursor::new(bytes.to_vec())).unwrap().unlock(None).unwrap_err();
        assert_eq!(none.kind, ErrorKind::PasswordRequired, "{name}");
    }
}

#[test]
fn remove_password_works_on_every_outside_handler() {
    for (name, bytes) in LOCKED {
        let err = run(&[(bytes, None)], &PdfToolOp::RemovePassword).unwrap_err();
        assert_eq!(err.kind, ErrorKind::PasswordRequired, "{name}");
        let err = run(&[(bytes, Some("nope"))], &PdfToolOp::RemovePassword).unwrap_err();
        assert_eq!(err.kind, ErrorKind::WrongPassword, "{name}");
        let (plain, _) = run_ok(&[(bytes, Some(USER))], &PdfToolOp::RemovePassword);
        let mut file = open(&plain, None);
        assert!(file.trailer.get("Encrypt").is_none(), "{name}");
        assert!(page_text(&mut file, 0).contains("Fixture page 1"), "{name}");
        let (again, _) = run_ok(&[(bytes, Some(OWNER))], &PdfToolOp::RemovePassword);
        assert!(page_text(&mut open(&again, None), 1).contains("Fixture page 2"), "{name}");
    }
}

#[test]
fn encrypted_input_can_be_merged_with_a_plain_one() {
    let plain = doc("Aaa", 1);
    let (out, report) = run_ok(&[(&plain, None), (R4_AES, Some(USER)), (R3, Some(OWNER))], &PdfToolOp::Merge);
    assert_eq!(report.page_count, 5);
    let mut file = open(&out, None);
    assert!(page_text(&mut file, 0).contains("Aaa page 0"));
    assert!(page_text(&mut file, 2).contains("Fixture page 2"));
    assert!(page_text(&mut file, 3).contains("Fixture page 1"));
}

fn all_write_ops() -> Vec<(&'static str, PdfToolOp)> {
    vec![
        ("merge", PdfToolOp::Merge),
        ("rebuild", PdfToolOp::Rebuild { pages: vec![pick(0)] }),
        ("resize", PdfToolOp::Resize { width: 595.0, height: 842.0 }),
        ("add", PdfToolOp::AddPassword { password: Zeroizing::new("new".to_owned()) }),
        ("remove", PdfToolOp::RemovePassword),
    ]
}

#[test]
fn owner_restrictions_are_honoured_until_the_owner_password_is_given() {
    for (file_name, bytes, user_password) in [
        ("no assemble", NO_ASSEMBLE, None),
        ("no modify", NO_MODIFY, None),
        ("no print", NO_PRINT, None),
        ("user, no copy", USER_NO_COPY, Some(USER)),
    ] {
        for (op_name, op) in all_write_ops() {
            let err = run(&[(bytes, user_password)], &op).unwrap_err();
            assert_eq!(err.kind, ErrorKind::PasswordRequired, "{file_name}, {op_name}");
            assert!(err.message.contains("owner password"), "{file_name}, {op_name}: {}", err.message);
            let err = run(&[(bytes, Some("guess"))], &op).unwrap_err();
            assert_eq!(err.kind, ErrorKind::WrongPassword, "{file_name}, {op_name}");
            let (out, _) = run(&[(bytes, Some(OWNER))], &op).unwrap();
            assert!(!out.is_empty(), "{file_name}, {op_name}");
        }
        let info = inspect_for_tools(Cursor::new(bytes.to_vec()), user_password).unwrap();
        assert_eq!((info.encrypted, info.page_count), (true, 2), "{file_name}");
    }
}

/// A watermarked PDF opens for anyone and forbids changes but allows printing: printing it must
/// not ask for the owner password (it used to, because print went through Rebuild).
#[test]
fn a_print_copy_needs_only_the_print_permission() {
    for (file_name, bytes) in [("no assemble", NO_ASSEMBLE), ("no modify", NO_MODIFY), ("all allowed", ALL_ALLOWED)] {
        let info = inspect_for_tools(Cursor::new(bytes.to_vec()), None).unwrap();
        assert!(info.printable, "{file_name}");
        let (out, report) = run(&[(bytes, None)], &PdfToolOp::PrintCopy).unwrap();
        assert!(!out.is_empty(), "{file_name}");
        assert_eq!(report.page_count, info.page_count, "{file_name}");
        // The copy is plain: it opens without any password.
        let copy = inspect_for_tools(Cursor::new(out), None).unwrap();
        assert!(!copy.encrypted, "{file_name}");
    }
    assert!(!inspect_for_tools(Cursor::new(NO_PRINT.to_vec()), None).unwrap().printable);
    let err = run(&[(NO_PRINT, None)], &PdfToolOp::PrintCopy).unwrap_err();
    assert_eq!(err.kind, ErrorKind::PasswordRequired);
    assert!(err.message.contains("doesn't allow printing"), "{}", err.message);
    run(&[(NO_PRINT, Some(OWNER))], &PdfToolOp::PrintCopy).unwrap();
}

#[test]
fn restriction_messages_name_what_is_limited() {
    let err = run(&[(NO_PRINT, None)], &PdfToolOp::RemovePassword).unwrap_err();
    assert!(err.message.contains("printing"), "{}", err.message);
    let err = run(&[(NO_MODIFY, None)], &PdfToolOp::Resize { width: 595.0, height: 842.0 }).unwrap_err();
    assert!(err.message.contains("changing its content"), "{}", err.message);
    let err = run(&[(NO_ASSEMBLE, None)], &PdfToolOp::Merge).unwrap_err();
    assert!(err.message.contains("rearranging"), "{}", err.message);
}

#[test]
fn a_file_open_to_all_with_no_limits_needs_no_password() {
    for (_, op) in all_write_ops() {
        run(&[(ALL_ALLOWED, None)], &op).unwrap();
    }
}

#[test]
fn certificate_encryption_is_typed() {
    let a = assemble(
        &[
            "<</Type /Catalog /Pages 2 0 R>>".to_owned(),
            "<</Type /Pages /Kids [3 0 R] /Count 1>>".to_owned(),
            "<</Type /Page /Parent 2 0 R>>".to_owned(),
            "<</Filter /Adobe.PubSec /V 4 /R 4>>".to_owned(),
        ],
        "/Encrypt 4 0 R /ID [<00><00>]",
    );
    let err = run(&[(&a, None)], &PdfToolOp::RemovePassword).unwrap_err();
    assert_eq!(err.kind, ErrorKind::UnsupportedEncryption);
    assert_eq!(inspect_for_tools(Cursor::new(a.clone()), None).unwrap_err().kind, ErrorKind::UnsupportedEncryption);
    assert_eq!(inspect_for_tools(Cursor::new(a), Some("x")).unwrap_err().kind, ErrorKind::UnsupportedEncryption);
}

#[test]
fn inspect_reports_signatures_and_archival_claims() {
    let body = |extra: &str| {
        assemble(
            &[
                format!("<</Type /Catalog /Pages 2 0 R {extra}>>"),
                "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 9 9]>>".to_owned(),
                "<</Type /Page /Parent 2 0 R>>".to_owned(),
            ],
            "",
        )
    };
    let info = |bytes: Vec<u8>| inspect_for_tools(Cursor::new(bytes), None).unwrap();
    assert!(!info(body("")).signed);
    assert!(info(body("/X <</ByteRange [0 1 2 3]>>")).signed);
    assert!(info(body("/X <</Type /Sig>>")).signed);
    assert!(!info(body("/AcroForm <</SigFlags 0>>")).signed, "/SigFlags alone isn't a signature");
    assert!(info(body("/AcroForm <</SigFlags 1>>")).signed);
    assert_eq!(info(body("")).page_count, 1);
    assert!(!info(body("")).encrypted);
}

#[test]
fn progress_counts_pages_and_cancelling_stops_it() {
    let a = doc("Aaa", 3);
    let mut watcher = Watcher::default();
    run_with(&[(&a, None)], &PdfToolOp::Merge, &mut watcher).unwrap();
    assert_eq!(watcher.progress, [(0, 3), (1, 3), (2, 3), (3, 3)]);

    let mut stop_at_once = Watcher { cancel_after_checks: Some(0), ..Watcher::default() };
    let err = run_with(&[(&a, None)], &PdfToolOp::Merge, &mut stop_at_once).unwrap_err();
    assert_eq!(err.kind, ErrorKind::Cancelled);

    // Between objects too: this page reaches far more than one check's worth.
    let mut bodies = vec![
        "<</Type /Catalog /Pages 2 0 R>>".to_owned(),
        "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 9 9]>>".to_owned(),
    ];
    let targets: Vec<String> = (0..500).map(|i| format!("{} 0 R", 4 + i)).collect();
    bodies.push(format!("<</Type /Page /Parent 2 0 R /Things [{}]>>", targets.join(" ")));
    bodies.extend((0..500).map(|i| format!("<</N {i}>>")));
    let large = assemble(&bodies, "");
    let mut stop_later = Watcher { cancel_after_checks: Some(3), ..Watcher::default() };
    let err = run_with(&[(&large, None)], &PdfToolOp::Merge, &mut stop_later).unwrap_err();
    assert_eq!(err.kind, ErrorKind::Cancelled);
    assert_eq!(stop_later.progress.last(), Some(&(0, 1)), "it stopped inside the page");
}

#[test]
fn output_bounds_are_enforced() {
    let mut sink = Vec::new();
    let mut watcher = Watcher::default();
    let mut output = Output {
        out: &mut sink,
        pos: MAX_OUTPUT_BYTES - 1,
        offsets: vec![0; MAX_OUTPUT_OBJECTS - 1],
        encryptor: None,
        observer: &mut watcher,
        objects_since_check: 0,
        file_id: [0; 16],
    };
    assert_eq!(output.write(b"xx").unwrap_err().kind, ErrorKind::LimitExceeded);
    assert!(output.allocate().is_ok());
    assert_eq!(output.allocate().unwrap_err().kind, ErrorKind::LimitExceeded);
}

#[test]
fn too_many_sources_is_typed() {
    let a = doc("Aaa", 1);
    let inputs: Vec<(&[u8], Option<&str>)> = (0..=MAX_SOURCES).map(|_| (a.as_slice(), None)).collect();
    assert_eq!(run(&inputs, &PdfToolOp::Merge).unwrap_err().kind, ErrorKind::LimitExceeded);
    assert_eq!(run(&[], &PdfToolOp::Merge).unwrap_err().kind, ErrorKind::Malformed);
}

#[test]
fn stray_references_never_drag_the_old_document_along_or_dangle() {
    let bodies = vec![
        "<</Type /Catalog /Pages 2 0 R>>".to_owned(),
        "<</Type /Pages /Kids [3 0 R 8 0 R] /Count 2 /MediaBox [0 0 9 9]>>".to_owned(),
        "<</Type /Page /Parent 2 0 R /Resources 4 0 R /Contents [5 0 R 99 0 R]>>".to_owned(),
        // Cycles, the catalog, the tree, a page that goes, a missing object, a stream's own /Length.
        "<</Self 4 0 R /Root 1 0 R /Up 2 0 R /Gone 8 0 R /Missing 98 0 R /List [98 0 R 6 0 R] /Cycle 6 0 R>>"
            .to_owned(),
        "<</Length 7 0 R>>\nstream\n(here) Tj\nendstream".to_owned(),
        "<</Back 4 0 R /Next 7 0 R>>".to_owned(),
        "9".to_owned(),
        "<</Type /Page /Parent 2 0 R /Contents 9 0 R>>".to_owned(),
        stream("(page that goes) Tj"),
    ];
    let a = assemble(&bodies, "");
    let (out, _) = run_ok(&[(&a, None)], &PdfToolOp::Rebuild { pages: vec![pick(0)] });
    assert!(!contains(&out, b"page that goes"));
    let mut file = open(&out, None);
    let page = page_dict(&mut file, 0);
    let Obj::Dict(resources) = file.resolve(page.get("Resources").unwrap()).unwrap() else { panic!("no resources") };
    assert!(resources.get("Gone").is_none() && resources.get("Missing").is_none());
    for structural in ["Root", "Up"] {
        assert_eq!(file.resolve(resources.get(structural).unwrap()).unwrap(), Obj::Null, "{structural}");
    }
    let Some(Obj::Array(list)) = resources.get("List") else { panic!("no list") };
    assert_eq!(list[0], Obj::Null);
    assert_eq!(file.trailer.get("Size").and_then(Obj::as_i64), Some(file.xref.len() as i64));
}

#[test]
fn hostile_structures_get_typed_errors() {
    let tree = |pages: &str, page: &str| {
        assemble(&["<</Type /Catalog /Pages 2 0 R>>".to_owned(), pages.to_owned(), page.to_owned()], "")
    };
    let cases: Vec<(&str, Vec<u8>)> = vec![
        ("empty", Vec::new()),
        ("not a pdf", b"hello world, this is not a PDF".to_vec()),
        ("loop in the tree", tree("<</Type /Pages /Kids [2 0 R] /Count 1>>", "null")),
        ("no pages", tree("<</Type /Pages /Kids [] /Count 0>>", "null")),
        ("kid isn't a reference", tree("<</Type /Pages /Kids [7] /Count 1>>", "null")),
        ("kid isn't a dictionary", tree("<</Type /Pages /Kids [3 0 R] /Count 1>>", "12")),
        ("deep nesting", tree("<</Type /Pages /Kids [3 0 R] /Count 1>>", &"[".repeat(5000))),
    ];
    for (name, bytes) in cases {
        let err = run(&[(&bytes, None)], &PdfToolOp::Merge).unwrap_err();
        assert!(matches!(err.kind, ErrorKind::Malformed | ErrorKind::LimitExceeded), "{name}: {err}");
    }
}

#[test]
fn a_stream_with_no_end_is_typed_and_a_wrong_length_is_survived() {
    let doc_with = |stream_object: &str| {
        assemble(
            &[
                "<</Type /Catalog /Pages 2 0 R>>".to_owned(),
                "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 9 9]>>".to_owned(),
                "<</Type /Page /Parent 2 0 R /Contents 4 0 R>>".to_owned(),
                stream_object.to_owned(),
            ],
            "",
        )
    };
    // A wild /Length is corrected from where the stream really ends.
    for length in ["1", "99999999999999", "-5"] {
        let a = doc_with(&format!("<</Length {length}>>\nstream\n(real) Tj\nendstream"));
        let (out, _) = run_ok(&[(&a, None)], &PdfToolOp::Merge);
        assert!(page_text(&mut open(&out, None), 0).contains("(real) Tj"), "length {length}");
    }
    let no_end = doc_with("<</Length 9>>\nstream\nno terminator");
    let err = run(&[(&no_end, None)], &PdfToolOp::Merge).unwrap_err();
    assert_eq!(err.kind, ErrorKind::Malformed, "{err}");
}

#[test]
fn a_decompression_bomb_in_an_object_stream_is_stopped() {
    let mut encoder = ZlibEncoder::new(Vec::new(), Compression::fast());
    let zeros = vec![b' '; 1024 * 1024];
    for _ in 0..70 {
        encoder.write_all(&zeros).unwrap();
    }
    let packed = encoder.finish().unwrap();
    let mut out = b"%PDF-1.5\n".to_vec();
    let at = out.len();
    out.extend_from_slice(
        format!("1 0 obj\n<</Type /ObjStm /N 1 /First 4 /Filter /FlateDecode /Length {}>>\nstream\n", packed.len())
            .as_bytes(),
    );
    out.extend_from_slice(&packed);
    out.extend_from_slice(b"\nendstream\nendobj\n");
    let xref_at = out.len();
    let mut rows = vec![0, 0, 0, 0, 0, 0xFF, 0xFF];
    rows.extend_from_slice(&[1]);
    rows.extend_from_slice(&(at as u32).to_be_bytes());
    rows.extend_from_slice(&[0, 0]);
    rows.extend_from_slice(&[2, 0, 0, 0, 1, 0, 0]);
    rows.extend_from_slice(&[1]);
    rows.extend_from_slice(&(xref_at as u32).to_be_bytes());
    rows.extend_from_slice(&[0, 0]);
    out.extend_from_slice(
        format!(
            "3 0 obj\n<</Type /XRef /Size 4 /W [1 4 2] /Index [0 4] /Root 2 0 R /Length {}>>\nstream\n",
            rows.len()
        )
        .as_bytes(),
    );
    out.extend_from_slice(&rows);
    out.extend_from_slice(format!("\nendstream\nendobj\nstartxref\n{xref_at}\n%%EOF\n").as_bytes());
    let err = run(&[(&out, None)], &PdfToolOp::Merge).unwrap_err();
    assert_eq!(err.kind, ErrorKind::LimitExceeded, "{err}");
}

/// A reproducible stream of small numbers, for damaging files in many ways.
struct Dice(u64);

impl Dice {
    fn next(&mut self, below: usize) -> usize {
        self.0 = self.0.wrapping_mul(0x5851_F42D_4C95_7F2D).wrapping_add(0x1405_7B7E_F767_814F);
        ((self.0 >> 33) as usize) % below.max(1)
    }
}

#[test]
fn damaged_files_never_panic_and_what_comes_out_is_sound() {
    let mut dice = Dice(0x00C0_FFEE);
    let seeds: Vec<Vec<u8>> =
        vec![doc("Aaa", 3), linked_doc(), compressed_doc(), sized_doc(), R4_AES.to_vec(), R6.to_vec()];
    let ops = all_write_ops();
    for round in 0..360 {
        let mut bytes = seeds[round % seeds.len()].clone();
        match dice.next(3) {
            0 => {
                let keep = dice.next(bytes.len());
                bytes.truncate(keep);
            }
            1 => {
                for _ in 0..1 + dice.next(16) {
                    let at = dice.next(bytes.len());
                    bytes[at] = dice.next(256) as u8;
                }
            }
            _ => {
                // Digits are the offsets, lengths and numbers: the interesting damage.
                for _ in 0..1 + dice.next(8) {
                    let at = dice.next(bytes.len());
                    if bytes[at].is_ascii_digit() {
                        bytes[at] = b'0' + dice.next(10) as u8;
                    }
                }
            }
        }
        let (_, op) = &ops[round % ops.len()];
        let password = (round % seeds.len() >= 4).then_some(USER);
        let info = inspect_for_tools(Cursor::new(bytes.clone()), password);
        drop(info);
        if let Ok((out, report)) = run(&[(&bytes, password)], op) {
            let password = match op {
                PdfToolOp::AddPassword { password } => Some(password.as_str()),
                _ => None,
            };
            verify_pdf_tool_output(Cursor::new(out), password, report.page_count).unwrap();
        }
    }
}
