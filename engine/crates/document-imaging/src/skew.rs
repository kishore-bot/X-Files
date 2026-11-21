//! The angle of a page's text lines, by projection profiles: the ink is
//! summed along lines at each candidate angle, and the angle at which those
//! sums switch most sharply between lines of text and the gaps between them
//! is the angle of the text. A coarse sweep, then a fine one around the best.

use crate::Gray;
use crate::sauvola::{K, binarize, window_for};

/// Steeper than this is a photo held sideways, not a tilt to straighten.
pub const MAX_DEGREES: f32 = 6.0;
/// Smaller tilts are invisible; leaving them avoids softening the page.
pub const MIN_DEGREES: f32 = 0.3;
const COARSE_STEP: f32 = 0.5;
const FINE_STEP: f32 = 0.1;
/// The best angle must beat level by this much to be believed.
const MIN_GAIN: f64 = 1.08;
/// A page of text has some ink, but not mostly ink (that is a photo).
const MIN_INK_SHARE: f32 = 0.003;
const MAX_INK_SHARE: f32 = 0.35;
/// Ink points used at most: enough for a clear profile, bounded time.
const MAX_POINTS: usize = 60_000;
/// Work on a reduced picture.
const MAX_SIDE: usize = 900;

/// Degrees the text slopes down to the right (clockwise on screen), or `None`
/// when there is no clear text to measure or the slope is under [`MIN_DEGREES`].
pub fn text_angle(picture: Gray<'_>) -> Option<f32> {
    let (small, w, h) = reduce(picture);
    let small = Gray { pixels: &small, width: w, height: h };
    let ink = binarize(small, window_for(w, h), K);
    let total = ink.iter().filter(|&&v| v == 0).count();
    let share = total as f32 / (w * h) as f32;
    if !(MIN_INK_SHARE..=MAX_INK_SHARE).contains(&share) {
        return None;
    }
    let step = total.div_ceil(MAX_POINTS).max(1);
    let points: Vec<(f32, f32)> = ink
        .iter()
        .enumerate()
        .filter(|&(_, &v)| v == 0)
        .step_by(step)
        .map(|(i, _)| ((i % w) as f32, (i / w) as f32))
        .collect();
    let level = profile_score(&points, 0.0, w, h);
    let mut best = (0.0f32, level);
    let mut a = -MAX_DEGREES;
    while a <= MAX_DEGREES + 1e-3 {
        let s = profile_score(&points, a, w, h);
        if s > best.1 {
            best = (a, s);
        }
        a += COARSE_STEP;
    }
    let centre = best.0;
    let mut a = centre - COARSE_STEP;
    while a <= centre + COARSE_STEP + 1e-3 {
        let s = profile_score(&points, a, w, h);
        if s > best.1 {
            best = (a, s);
        }
        a += FINE_STEP;
    }
    let (angle, score) = best;
    (angle.abs() >= MIN_DEGREES && score >= level * MIN_GAIN).then_some(angle)
}

/// Sum of squared differences between neighbouring bins of the ink profile
/// along lines sloping at `degrees`: large when lines and gaps are crisp.
fn profile_score(points: &[(f32, f32)], degrees: f32, w: usize, h: usize) -> f64 {
    let (sin, cos) = degrees.to_radians().sin_cos();
    let offset = w as f32 * sin.abs() + 1.0;
    let bins = (h as f32 + 2.0 * offset) as usize + 2;
    let mut profile = vec![0u32; bins];
    for &(x, y) in points {
        let along = y * cos - x * sin + offset;
        if along >= 0.0 {
            let b = along as usize;
            if b < bins {
                profile[b] += 1;
            }
        }
    }
    profile.windows(2).map(|p| f64::from(p[1].abs_diff(p[0])).powi(2)).sum()
}

/// Nearest-neighbour reduction to at most [`MAX_SIDE`]: enough for line angles.
fn reduce(p: Gray<'_>) -> (Vec<u8>, usize, usize) {
    let longer = p.width.max(p.height);
    if longer <= MAX_SIDE {
        return (p.pixels.to_vec(), p.width, p.height);
    }
    let w = (p.width * MAX_SIDE / longer).max(1);
    let h = (p.height * MAX_SIDE / longer).max(1);
    let mut out = Vec::with_capacity(w * h);
    for y in 0..h {
        let sy = y * p.height / h;
        for x in 0..w {
            out.push(p.at(x * p.width / w, sy));
        }
    }
    (out, w, h)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_pictures::text_page;

    fn angle(deg: f32) -> Option<f32> {
        let (w, h) = (600, 800);
        let page = text_page(w, h, deg);
        text_angle(Gray::new(&page, w, h).unwrap())
    }

    #[test]
    fn finds_the_tilt_of_text_lines() {
        for tilt in [2.0f32, -3.0, 4.5] {
            let found = angle(tilt).unwrap_or(0.0);
            assert!((found - tilt).abs() <= 0.25, "tilt {tilt}: found {found}");
        }
    }

    #[test]
    fn level_text_is_left_alone() {
        assert_eq!(angle(0.0), None);
    }

    #[test]
    fn blank_or_dark_pictures_have_no_angle() {
        let blank = vec![240u8; 300 * 400];
        assert_eq!(text_angle(Gray::new(&blank, 300, 400).unwrap()), None);
        let dark = vec![10u8; 300 * 400];
        assert_eq!(text_angle(Gray::new(&dark, 300, 400).unwrap()), None);
    }
}
