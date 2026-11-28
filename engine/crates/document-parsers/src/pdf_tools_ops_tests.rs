//! End-to-end tests for Reorder, Split, Compress, Watermark and Sign. Each builds
//! a small uncompressed PDF, runs the op, re-opens the output and checks it.

use std::io::Cursor;

use document_core::Result;

use crate::pdf_file::PdfFile;
use crate::pdf_syntax::Obj;
use crate::pdf_tools::{
    PdfPagePick, PdfToolObserver, PdfToolOp, PdfToolReport, PdfToolSource, run_pdf_tool, verify_pdf_tool_output,
};
use crate::pdf_tools_compress::{compress_pdf, compress_pdf_with_quality};
use crate::pdf_tools_images::PdfImageQuality;
use zeroize::Zeroizing;
use crate::pdf_tools_overlay::{
    PdfOverlay, PdfSignature, PdfWatermark, PdfWatermarkPosition, apply_overlay, verify_overlay,
};
use crate::pdf_tools_split::{PdfPageRange, plan_split};

struct Quiet;

impl PdfToolObserver for Quiet {
    fn on_progress(&mut self, _done: u32, _total: u32) {}

    fn is_cancelled(&self) -> bool {
        false
    }
}

fn stream(body: &str) -> String {
    format!("<</Length {}>>\nstream\n{body}\nendstream", body.len())
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

/// One page per entry of `contents`, each showing its text, with an optional
/// unreferenced object at the end (numbered after the last page's content).
fn doc(contents: &[String], orphan: bool) -> Vec<u8> {
    let count = contents.len();
    let kids: Vec<String> = (0..count).map(|i| format!("{} 0 R", 4 + 2 * i)).collect();
    let mut bodies = vec![
        "<</Type /Catalog /Pages 2 0 R>>".to_owned(),
        format!(
            "<</Type /Pages /Kids [{}] /Count {count} /MediaBox [0 0 300 400] /Resources <</Font <</F1 3 0 R>>>>>>",
            kids.join(" ")
        ),
        "<</Type /Font /Subtype /Type1 /BaseFont /Helvetica>>".to_owned(),
    ];
    for (index, text) in contents.iter().enumerate() {
        bodies.push(format!("<</Type /Page /Parent 2 0 R /Contents {} 0 R>>", 5 + 2 * index));
        bodies.push(stream(&format!("BT /F1 12 Tf ({text}) Tj ET")));
    }
    if orphan {
        bodies.push(stream("BT /F1 12 Tf (orphan) Tj ET"));
    }
    assemble(&bodies, "")
}

fn texts(count: usize) -> Vec<String> {
    (0..count).map(|i| format!("p{i}")).collect()
}

fn pick(page: u32) -> PdfPagePick {
    PdfPagePick { page, rotation: 0 }
}

fn run_op(bytes: &[u8], op: &PdfToolOp) -> Result<(Vec<u8>, PdfToolReport)> {
    let mut out = Vec::new();
    let mut quiet = Quiet;
    let source = PdfToolSource { reader: Cursor::new(bytes.to_vec()), password: None };
    let report = run_pdf_tool(vec![source], op, &mut out, &mut quiet)?;
    Ok((out, report))
}

fn open(bytes: &[u8]) -> PdfFile<Cursor<Vec<u8>>> {
    let mut file = PdfFile::open(Cursor::new(bytes.to_vec())).unwrap();
    file.unlock(None).unwrap();
    file
}

/// The page's content stream as text. Works for a page whose `/Contents` is one reference.
fn content_of(file: &mut PdfFile<Cursor<Vec<u8>>>, index: usize) -> String {
    let pages = file.pages().unwrap();
    let Obj::Dict(page) = file.object(pages[index].object).unwrap() else { panic!("page dict expected") };
    let Some(Obj::Ref(number, _)) = page.get("Contents") else { panic!("single content expected") };
    let loaded = file.load(*number).unwrap();
    let Obj::Dict(dict) = loaded.value else { panic!("stream dict expected") };
    let length = match dict.get("Length") {
        Some(Obj::Num(text)) => text.parse::<u64>().unwrap(),
        _ => panic!("direct length expected"),
    };
    let data = file.stream_raw(loaded.stream_at.unwrap(), length).unwrap();
    String::from_utf8_lossy(&data).into_owned()
}

fn page_contents_len(file: &mut PdfFile<Cursor<Vec<u8>>>, index: usize) -> usize {
    let pages = file.pages().unwrap();
    let Obj::Dict(page) = file.object(pages[index].object).unwrap() else { panic!("page dict expected") };
    match page.get("Contents") {
        Some(Obj::Array(items)) => items.len(),
        _ => 1,
    }
}

#[test]
fn reorder_applies_a_full_permutation_across_a_word_boundary() {
    let count = 70;
    let bytes = doc(&texts(count), false);
    let order: Vec<PdfPagePick> = (0..count as u32).rev().map(pick).collect();
    let (out, report) = run_op(&bytes, &PdfToolOp::Reorder { pages: order }).unwrap();
    assert_eq!(report.page_count, count as u32);
    verify_pdf_tool_output(Cursor::new(out.clone()), None, count as u32).unwrap();
    let mut file = open(&out);
    assert!(content_of(&mut file, 0).contains("(p69)"));
    assert!(content_of(&mut file, count - 1).contains("(p0)"));
}

#[test]
fn reorder_refuses_repeats_gaps_and_strangers() {
    let bytes = doc(&texts(3), false);
    let repeat = PdfToolOp::Reorder { pages: vec![pick(0), pick(0), pick(1)] };
    assert!(run_op(&bytes, &repeat).is_err());
    let short = PdfToolOp::Reorder { pages: vec![pick(0), pick(1)] };
    assert!(run_op(&bytes, &short).is_err());
    let stranger = PdfToolOp::Reorder { pages: vec![pick(0), pick(1), pick(3)] };
    assert!(run_op(&bytes, &stranger).is_err());
}

#[test]
fn each_split_part_holds_only_its_own_pages() {
    let bytes = doc(&texts(6), false);
    let plan = plan_split(&[PdfPageRange { start: 0, end: 1 }, PdfPageRange { start: 4, end: 5 }], 6).unwrap();
    let counts: Vec<u32> = plan
        .into_iter()
        .map(|part| run_op(&bytes, &PdfToolOp::Rebuild { pages: part }).unwrap().1.page_count)
        .collect();
    assert_eq!(counts, vec![2, 2]);
}

#[test]
fn compress_merges_identical_streams_and_drops_unreachable_objects() {
    let same = "BT /F1 12 Tf (same) Tj ET".to_owned();
    let bytes = doc(&[same.clone(), same, "BT /F1 12 Tf (other) Tj ET".to_owned()], true);
    let mut out = Vec::new();
    let mut quiet = Quiet;
    let report = compress_pdf(Cursor::new(bytes), &mut out, &mut quiet).unwrap();
    assert_eq!(report.page_count, 3);
    assert_eq!(report.duplicates_merged, 1);
    // Catalog, pages, font, three pages and two distinct contents; the orphan is gone.
    assert_eq!(report.objects_after, 8);
    assert!(report.objects_after < report.objects_before);
    assert_eq!(report.after, out.len() as u64);
    verify_pdf_tool_output(Cursor::new(out.clone()), None, 3).unwrap();
    let mut file = open(&out);
    assert_eq!(content_of(&mut file, 0), content_of(&mut file, 1));
    assert!(!String::from_utf8_lossy(&out).contains("orphan"));
}

#[test]
fn compress_refuses_protected_files() {
    let bytes = assemble(
        &[
            "<</Type /Catalog /Pages 2 0 R>>".to_owned(),
            "<</Type /Pages /Kids [] /Count 0>>".to_owned(),
        ],
        "/Encrypt 9 0 R",
    );
    let mut out = Vec::new();
    let mut quiet = Quiet;
    assert!(compress_pdf(Cursor::new(bytes), &mut out, &mut quiet).is_err());
}

#[test]
fn watermark_adds_a_font_an_opacity_and_escaped_text_to_every_page() {
    let bytes = doc(&texts(2), false);
    let mark = PdfOverlay::Watermark(PdfWatermark {
        text: "DRAFT (x)".to_owned(),
        font_size: 40.0,
        opacity: 0.25,
        angle_degrees: 45.0,
        color: 0x80_80_80,
        position: PdfWatermarkPosition::Center,
        pages: None,
    });
    let mut out = Vec::new();
    let mut quiet = Quiet;
    let report = apply_overlay(Cursor::new(bytes.clone()), &mut out, &mark, &mut quiet).unwrap();
    assert_eq!(report.pages_changed, 2);
    assert_eq!(report.original_len, bytes.len() as u64);
    verify_overlay(Cursor::new(out.clone()), 2).unwrap();
    let text = String::from_utf8_lossy(&out);
    assert!(text.contains("/Helvetica"));
    assert!(text.contains("/ExtGState"));
    assert!(text.contains("DRAFT \\(x\\)"));
    let mut file = open(&out);
    // Prefix, original content and overlay.
    assert_eq!(page_contents_len(&mut file, 0), 3);
}

#[test]
fn signature_strokes_land_on_the_chosen_page_only() {
    let bytes = doc(&texts(2), false);
    let sign = PdfOverlay::Sign(PdfSignature {
        page: 1,
        rect: [0.1, 0.8, 0.3, 0.1],
        strokes: vec![vec![(0.0, 0.5), (0.5, 0.0), (1.0, 0.5)]],
        color: 0x00_00_80,
        line_width: 1.5,
    });
    let mut out = Vec::new();
    let mut quiet = Quiet;
    let report = apply_overlay(Cursor::new(bytes), &mut out, &sign, &mut quiet).unwrap();
    assert_eq!(report.pages_changed, 1);
    verify_overlay(Cursor::new(out.clone()), 2).unwrap();
    let text = String::from_utf8_lossy(&out);
    assert!(text.contains(" RG\n"));
    assert!(text.contains(" m\n"));
    assert!(text.contains(" l\n"));
    assert!(text.contains("S\n"));
    let mut file = open(&out);
    assert_eq!(page_contents_len(&mut file, 0), 1);
    assert_eq!(page_contents_len(&mut file, 1), 3);
}

#[test]
fn overlays_refuse_pages_that_are_not_in_the_document() {
    let bytes = doc(&texts(1), false);
    let mark = PdfOverlay::Watermark(PdfWatermark {
        text: "X".to_owned(),
        font_size: 20.0,
        opacity: 0.5,
        angle_degrees: 0.0,
        color: 0,
        position: PdfWatermarkPosition::Tiled,
        pages: Some(vec![4]),
    });
    let mut out = Vec::new();
    let mut quiet = Quiet;
    assert!(apply_overlay(Cursor::new(bytes), &mut out, &mark, &mut quiet).is_err());
}

fn lossy_image_pdf() -> Vec<u8> {
    // One 200x200 gray image drawn 72 points wide: 200 dpi, so Balanced (150 dpi) scales it down.
    let pixels = "A".repeat(200 * 200);
    let image = format!(
        "<</Type /XObject /Subtype /Image /Width 200 /Height 200 /ColorSpace /DeviceGray /BitsPerComponent 8 /Length {}>>\nstream\n{pixels}\nendstream",
        pixels.len()
    );
    let bodies = vec![
        "<</Type /Catalog /Pages 2 0 R>>".to_owned(),
        "<</Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 300 400] /Resources <</XObject <</Im1 5 0 R>>>>>>".to_owned(),
        "<</Type /Page /Parent 2 0 R /Contents 4 0 R>>".to_owned(),
        stream("q 72 0 0 72 0 0 cm /Im1 Do Q"),
        image,
    ];
    assemble(&bodies, "")
}

#[test]
fn lossless_level_is_the_structural_compress() {
    let bytes = doc(&texts(2), true);
    let mut plain = Vec::new();
    let mut lossless = Vec::new();
    compress_pdf(Cursor::new(bytes.clone()), &mut plain, &mut Quiet).unwrap();
    let report =
        compress_pdf_with_quality(Cursor::new(bytes), &mut lossless, &mut Quiet, PdfImageQuality::Lossless).unwrap();
    assert_eq!(plain, lossless);
    assert_eq!(report.images_recompressed, 0);
}

#[test]
fn balanced_level_re_encodes_a_large_gray_image_and_keeps_the_page() {
    let bytes = lossy_image_pdf();
    let mut out = Vec::new();
    let report =
        compress_pdf_with_quality(Cursor::new(bytes.clone()), &mut out, &mut Quiet, PdfImageQuality::Balanced).unwrap();
    assert_eq!(report.page_count, 1);
    assert_eq!(report.images_recompressed, 1);
    assert!(report.after < report.before);
    verify_pdf_tool_output(Cursor::new(out), None, 1).unwrap();
}

#[test]
fn protected_round_trip_unlocks_compresses_and_protects_again() {
    let plain = doc(&texts(2), false);
    let secret = || Zeroizing::new("secret".to_owned());
    let (protected, _) = run_op(&plain, &PdfToolOp::AddPassword { password: secret() }).unwrap();

    // Phase 1: the Unlock path turns the protected file into a plain copy.
    let unlock = PdfToolSource { reader: Cursor::new(protected), password: Some(secret()) };
    let mut unlocked = Vec::new();
    let unlocked_report =
        run_pdf_tool(vec![unlock], &PdfToolOp::RemovePassword, &mut unlocked, &mut Quiet).unwrap();
    verify_pdf_tool_output(Cursor::new(unlocked.clone()), None, unlocked_report.page_count).unwrap();

    // Phase 2: the operation runs on the plain copy.
    let mut compressed = Vec::new();
    let report = compress_pdf_with_quality(
        Cursor::new(unlocked),
        &mut compressed,
        &mut Quiet,
        PdfImageQuality::Strong,
    )
    .unwrap();

    // Phase 3: protected again with the same password, and verified with it.
    let protect = PdfToolSource { reader: Cursor::new(compressed), password: None };
    let mut again = Vec::new();
    run_pdf_tool(vec![protect], &PdfToolOp::AddPassword { password: secret() }, &mut again, &mut Quiet).unwrap();
    verify_pdf_tool_output(Cursor::new(again.clone()), Some("secret"), report.page_count).unwrap();
    assert!(verify_pdf_tool_output(Cursor::new(again), Some("wrong"), report.page_count).is_err());
}