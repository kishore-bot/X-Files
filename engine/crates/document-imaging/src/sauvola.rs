//! Sauvola's adaptive threshold (Sauvola and Pietikäinen, 2000): each pixel
//! is compared with a threshold from the mean and spread of the pixels around
//! it, so faint pencil next to bold print, or text in a shadow, both come out.
//!
//! The window sums slide down the picture: memory is a few rows of sums, not
//! an integral image the size of the picture.

use crate::Gray;

/// Weight of the local spread; 0.2 suits text on paper after lighting is evened.
pub const K: f32 = 0.2;
/// Dynamic range of the standard deviation for 8-bit pictures.
const R: f32 = 128.0;
/// The window is about this fraction of the longer side, so it spans a few letters.
const WINDOW_FRACTION: usize = 40;
const MIN_WINDOW: usize = 15;

/// The window side used for a picture of this size (odd).
pub fn window_for(width: usize, height: usize) -> usize {
    (width.max(height) / WINDOW_FRACTION).max(MIN_WINDOW) | 1
}

/// 0 for ink and 255 for paper, for every pixel.
pub fn binarize(picture: Gray<'_>, window: usize, k: f32) -> Vec<u8> {
    let (w, h) = (picture.width, picture.height);
    let r = window / 2;
    let mut out = vec![255u8; w * h];
    // Per column: the sum and sum of squares over the rows in the window.
    let mut col_sum = vec![0u64; w];
    let mut col_sq = vec![0u64; w];
    let add_row = |y: usize, sign: i64, col_sum: &mut [u64], col_sq: &mut [u64]| {
        for x in 0..w {
            let v = u64::from(picture.at(x, y));
            if sign > 0 {
                col_sum[x] += v;
                col_sq[x] += v * v;
            } else {
                col_sum[x] -= v;
                col_sq[x] -= v * v;
            }
        }
    };
    for y in 0..=r.min(h - 1) {
        add_row(y, 1, &mut col_sum, &mut col_sq);
    }
    let mut prefix_sum = vec![0u64; w + 1];
    let mut prefix_sq = vec![0u64; w + 1];
    for y in 0..h {
        let top = y.saturating_sub(r);
        let bottom = (y + r).min(h - 1);
        let rows = (bottom - top + 1) as u64;
        for x in 0..w {
            prefix_sum[x + 1] = prefix_sum[x] + col_sum[x];
            prefix_sq[x + 1] = prefix_sq[x] + col_sq[x];
        }
        for x in 0..w {
            let left = x.saturating_sub(r);
            let right = (x + r).min(w - 1);
            let n = rows * (right - left + 1) as u64;
            let sum = (prefix_sum[right + 1] - prefix_sum[left]) as f64;
            let sq = (prefix_sq[right + 1] - prefix_sq[left]) as f64;
            let mean = sum / n as f64;
            let deviation = (sq / n as f64 - mean * mean).max(0.0).sqrt() as f32;
            let threshold = mean as f32 * (1.0 + k * (deviation / R - 1.0));
            if f32::from(picture.at(x, y)) < threshold {
                out[y * w + x] = 0;
            }
        }
        // Slide the window down one row.
        if y + r + 1 < h {
            add_row(y + r + 1, 1, &mut col_sum, &mut col_sq);
        }
        if y >= r {
            add_row(y - r, -1, &mut col_sum, &mut col_sq);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ink_in_a_shadow_and_faint_ink_both_come_out() {
        let (w, h) = (200, 60);
        let mut p = vec![0u8; w * h];
        for y in 0..h {
            for x in 0..w {
                // Paper darkens from 240 to 120 across the page.
                let paper = 240 - (x * 120 / w) as u8;
                let ink = (x % 20 < 3) && (20..40).contains(&y);
                // Bold ink on the left, faint ink on the right.
                p[y * w + x] = if ink { if x < w / 2 { 30 } else { paper - 50 } } else { paper };
            }
        }
        let out = binarize(Gray::new(&p, w, h).unwrap(), window_for(w, h), K);
        assert_eq!(out[30 * w], 0, "bold ink");
        assert_eq!(out[30 * w + 180], 0, "faint ink in the shadow");
        assert_eq!(out[5 * w + 10], 255, "light paper");
        assert_eq!(out[5 * w + 190], 255, "shadowed paper");
    }

    #[test]
    fn blank_paper_stays_white() {
        let p = vec![250u8; 50 * 40];
        assert!(binarize(Gray::new(&p, 50, 40).unwrap(), 15, K).iter().all(|&v| v == 255));
    }

    #[test]
    fn tiny_pictures() {
        assert_eq!(binarize(Gray::new(&[10], 1, 1).unwrap(), 15, K).len(), 1);
        assert_eq!(binarize(Gray::new(&[10; 7], 7, 1).unwrap(), 15, K).len(), 7);
        assert_eq!(binarize(Gray::new(&[10; 7], 1, 7).unwrap(), 15, K).len(), 7);
    }
}
