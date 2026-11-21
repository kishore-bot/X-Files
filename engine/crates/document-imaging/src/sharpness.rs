//! Is a picture blurry? The variance of the Laplacian measures fine detail,
//! but how much detail a page has depends on what is printed on it. So this
//! compares the detail at full size with the detail at half size: a sharp
//! picture loses about as much as it keeps when halved, a blurred one had
//! little fine detail to begin with. The ratio hardly depends on the page.

use crate::Gray;

/// Below this ratio of fine to coarse detail the picture is blurry. Crisp
/// print scores about 0.4, slightly soft about 0.2, clearly blurred about
/// 0.1: only the last is worth a warning.
pub const BLURRY_BELOW: f32 = 0.11;
/// Coarse detail needed before judging at all: a blank wall or an empty
/// page has nothing to be sharp about.
const MIN_COARSE_DETAIL: f64 = 40.0;
/// Bounded work: larger pictures are halved first.
const MAX_SIDE: usize = 1600;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Sharpness {
    /// Fine detail over coarse detail; around 1 when sharp, near 0 when blurred.
    pub score: f32,
    /// `false` when the picture has too little detail to say.
    pub judged: bool,
}

impl Sharpness {
    pub fn blurry(&self) -> bool {
        self.judged && self.score < BLURRY_BELOW
    }
}

pub fn measure(picture: Gray<'_>) -> Sharpness {
    if picture.width.max(picture.height) <= MAX_SIDE {
        return judge(picture);
    }
    let mut reduced = halve(picture);
    while reduced.1.max(reduced.2) > MAX_SIDE && reduced.1 >= 4 && reduced.2 >= 4 {
        reduced = halve(Gray { pixels: &reduced.0, width: reduced.1, height: reduced.2 });
    }
    judge(Gray { pixels: &reduced.0, width: reduced.1, height: reduced.2 })
}

fn judge(picture: Gray<'_>) -> Sharpness {
    if picture.width < 8 || picture.height < 8 {
        return Sharpness { score: 0.0, judged: false };
    }
    let fine = laplacian_variance(picture);
    let half = halve(picture);
    let coarse = laplacian_variance(Gray { pixels: &half.0, width: half.1, height: half.2 });
    if coarse < MIN_COARSE_DETAIL {
        return Sharpness { score: 0.0, judged: false };
    }
    Sharpness { score: (fine / coarse) as f32, judged: true }
}

/// Variance of the 4-neighbour Laplacian over the inside of the picture.
fn laplacian_variance(p: Gray<'_>) -> f64 {
    let (mut sum, mut sq, mut n) = (0f64, 0f64, 0f64);
    for y in 1..p.height - 1 {
        for x in 1..p.width - 1 {
            let l = 4 * i32::from(p.at(x, y)) - i32::from(p.at(x - 1, y)) - i32::from(p.at(x + 1, y)) - i32::from(p.at(x, y - 1)) - i32::from(p.at(x, y + 1));
            let l = f64::from(l);
            sum += l;
            sq += l * l;
            n += 1.0;
        }
    }
    if n == 0.0 {
        return 0.0;
    }
    let mean = sum / n;
    sq / n - mean * mean
}

/// Averages each 2 x 2 block.
fn halve(p: Gray<'_>) -> (Vec<u8>, usize, usize) {
    let (w, h) = (p.width / 2, p.height / 2);
    let mut out = Vec::with_capacity(w * h);
    for y in 0..h {
        for x in 0..w {
            let s = u32::from(p.at(2 * x, 2 * y)) + u32::from(p.at(2 * x + 1, 2 * y)) + u32::from(p.at(2 * x, 2 * y + 1)) + u32::from(p.at(2 * x + 1, 2 * y + 1));
            out.push(((s + 2) / 4) as u8);
        }
    }
    (out, w, h)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_pictures::{blur, text_page};

    fn sharpness(p: &[u8], w: usize, h: usize) -> Sharpness {
        measure(Gray::new(p, w, h).unwrap())
    }

    #[test]
    fn sharp_text_is_sharp_and_blurred_text_is_blurry() {
        let (w, h) = (400, 300);
        let page = text_page(w, h, 0.0);
        let sharp = sharpness(&page, w, h);
        let soft = sharpness(&blur(&page, w, h, 1), w, h);
        let blurred = sharpness(&blur(&page, w, h, 4), w, h);
        assert!(sharp.judged && !sharp.blurry() && !soft.blurry(), "{sharp:?} {soft:?}");
        assert!(soft.score < sharp.score && blurred.score < soft.score, "{sharp:?} {soft:?} {blurred:?}");
        assert!(blurred.blurry(), "{blurred:?}");
    }

    #[test]
    fn a_blank_page_is_not_judged() {
        let page = vec![200u8; 300 * 200];
        assert!(!sharpness(&page, 300, 200).judged);
        assert!(!sharpness(&page, 300, 200).blurry());
    }

    #[test]
    fn big_pictures_are_reduced() {
        let (w, h) = (3400, 200);
        let page = text_page(w, h, 0.0);
        assert!(sharpness(&page, w, h).judged);
    }
}
