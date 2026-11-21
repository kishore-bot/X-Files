//! Scanning helpers, exported over UniFFI. The algorithms live in
//! `document-imaging`; pictures cross as one grey byte per pixel. A buffer
//! whose size doesn't match its dimensions gets a neutral answer, never a panic.

use document_imaging::{Gray, sauvola, sharpness, similarity, skew};

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct NativeSharpness {
    pub score: f32,
    /// `false` when the picture has too little detail to say.
    pub judged: bool,
    pub blurry: bool,
}

/// Whether a picture is blurry.
#[uniffi::export]
pub fn imaging_sharpness(luma: Vec<u8>, width: u32, height: u32) -> NativeSharpness {
    match Gray::new(&luma, width as usize, height as usize) {
        Some(picture) => {
            let s = sharpness::measure(picture);
            NativeSharpness { score: s.score, judged: s.judged, blurry: s.blurry() }
        }
        None => NativeSharpness { score: 0.0, judged: false, blurry: false },
    }
}

/// Sauvola black and white: 0 for ink, 255 for paper.
#[uniffi::export]
pub fn imaging_binarize(luma: Vec<u8>, width: u32, height: u32) -> Vec<u8> {
    let (w, h) = (width as usize, height as usize);
    match Gray::new(&luma, w, h) {
        Some(picture) => sauvola::binarize(picture, sauvola::window_for(w, h), sauvola::K),
        None => luma,
    }
}

/// Degrees the text slopes down to the right, or `None` when there's nothing to straighten.
#[uniffi::export]
pub fn imaging_text_angle(luma: Vec<u8>, width: u32, height: u32) -> Option<f32> {
    Gray::new(&luma, width as usize, height as usize).and_then(skew::text_angle)
}

/// SSIM of two same-sized grey pictures, or `None` if they differ in size.
#[uniffi::export]
pub fn imaging_similarity(a: Vec<u8>, b: Vec<u8>, width: u32, height: u32) -> Option<f32> {
    let (w, h) = (width as usize, height as usize);
    similarity::ssim(Gray::new(&a, w, h)?, Gray::new(&b, w, h)?)
}
