//! 64-bit difference hash (dHash) of a small grayscale picture.
//!
//! The picture is averaged down to 9x8 cells; each bit says whether a cell is
//! brighter than its right-hand neighbour. That survives rescaling,
//! recompression, mild colour and brightness changes, which is what makes two
//! saves of one photo "similar". The platform decodes the image (downscaled
//! while decoding, so a 50-megapixel photo costs a few kilobytes) and passes
//! the luminance here.

/// Cells across; one more than the bits per row.
const COLS: usize = 9;
const ROWS: usize = 8;

/// A picture whose cells span fewer grey levels than this is close to a flat
/// colour (a blank page, a black frame); every such picture would look
/// "similar" to every other, so it gets no hash.
const MIN_CONTRAST: u32 = 6;

/// dHash of a `width` x `height` grayscale buffer, row-major, one byte per
/// pixel. `None` if the buffer is too small, the wrong length, or flat.
pub fn dhash(width: u32, height: u32, gray: &[u8]) -> Option<u64> {
    let (w, h) = (width as usize, height as usize);
    if w < COLS || h < ROWS || w.checked_mul(h)? != gray.len() {
        return None;
    }

    let mut cells = [0u32; COLS * ROWS];
    for row in 0..ROWS {
        let (y0, y1) = span(row, ROWS, h);
        for col in 0..COLS {
            let (x0, x1) = span(col, COLS, w);
            let mut sum = 0u64;
            for y in y0..y1 {
                sum += gray[y * w + x0..y * w + x1].iter().map(|&p| u64::from(p)).sum::<u64>();
            }
            // Scaled by 16 so averaging keeps some precision.
            cells[row * COLS + col] = (sum * 16 / ((y1 - y0) * (x1 - x0)) as u64) as u32;
        }
    }

    let (min, max) = cells.iter().fold((u32::MAX, 0), |(lo, hi), &c| (lo.min(c), hi.max(c)));
    if max - min < MIN_CONTRAST * 16 {
        return None;
    }

    let mut hash = 0u64;
    for row in 0..ROWS {
        for col in 0..COLS - 1 {
            hash <<= 1;
            if cells[row * COLS + col] > cells[row * COLS + col + 1] {
                hash |= 1;
            }
        }
    }
    Some(hash)
}

/// Source range `[start, end)` covered by cell `i` of `cells` over `len` pixels; never empty.
fn span(i: usize, cells: usize, len: usize) -> (usize, usize) {
    let start = i * len / cells;
    let end = ((i + 1) * len / cells).max(start + 1).min(len);
    (start, end)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::similar::hamming;

    /// A synthetic "photo": soft blobs and a gradient, at any size.
    fn scene(w: u32, h: u32, variant: u32) -> Vec<u8> {
        let mut out = Vec::with_capacity((w * h) as usize);
        for y in 0..h {
            for x in 0..w {
                let (fx, fy) = (x as f32 / w as f32, y as f32 / h as f32);
                let v = match variant {
                    0 => 120.0 + 80.0 * (fx * 6.0).sin() * (fy * 4.0).cos() + 40.0 * fy,
                    1 => 128.0 + 100.0 * ((fx - 0.3).powi(2) + (fy - 0.6).powi(2)).sqrt().cos() * (fx * 11.0).sin(),
                    _ => 60.0 + 150.0 * fx * fy + 30.0 * (fy * 9.0).sin(),
                };
                out.push(v.clamp(0.0, 255.0) as u8);
            }
        }
        out
    }

    fn noisy(pixels: &[u8], amplitude: i32, seed: u32) -> Vec<u8> {
        let mut state = seed;
        pixels
            .iter()
            .map(|&p| {
                state = state.wrapping_mul(1_103_515_245).wrapping_add(12345);
                let n = (state >> 16) as i32 % (2 * amplitude + 1) - amplitude;
                (i32::from(p) + n).clamp(0, 255) as u8
            })
            .collect()
    }

    #[test]
    fn brightness_noise_and_size_changes_stay_within_the_threshold() {
        let base = dhash(32, 32, &scene(32, 32, 0)).unwrap();
        let brighter: Vec<u8> = scene(32, 32, 0).iter().map(|&p| p.saturating_add(20)).collect();
        let max = crate::IMAGE_MAX_DISTANCE;
        assert!(hamming(base, dhash(32, 32, &brighter).unwrap()) <= max);
        assert!(hamming(base, dhash(32, 32, &noisy(&scene(32, 32, 0), 8, 1)).unwrap()) <= max);
        assert!(hamming(base, dhash(64, 48, &scene(64, 48, 0)).unwrap()) <= max);
    }

    #[test]
    fn different_pictures_are_far_apart() {
        let a = dhash(32, 32, &scene(32, 32, 0)).unwrap();
        let b = dhash(32, 32, &scene(32, 32, 1)).unwrap();
        let c = dhash(32, 32, &scene(32, 32, 2)).unwrap();
        let max = crate::IMAGE_MAX_DISTANCE;
        assert!(hamming(a, b) > max * 2, "{}", hamming(a, b));
        assert!(hamming(a, c) > max * 2, "{}", hamming(a, c));
        assert!(hamming(b, c) > max * 2, "{}", hamming(b, c));
    }

    #[test]
    fn flat_or_malformed_buffers_have_no_hash() {
        assert_eq!(dhash(32, 32, &[200; 1024]), None);
        assert_eq!(dhash(32, 32, &noisy(&[128; 1024], 2, 5)), None);
        assert_eq!(dhash(32, 32, &[0; 10]), None);
        assert_eq!(dhash(4, 4, &[0; 16]), None);
        assert_eq!(dhash(u32::MAX, u32::MAX, &[]), None);
    }
}
