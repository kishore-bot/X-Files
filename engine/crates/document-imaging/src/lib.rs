//! Classical image algorithms for scanning pages, over 8-bit grey pictures
//! (one byte per pixel, row by row). No models, no dependencies, and memory
//! that grows with a row, not with the picture, wherever that is possible.
//!
//! - [`sharpness`]: is this picture blurry? Compares detail at two scales.
//! - [`sauvola`]: black and white with a threshold that follows the page.
//! - [`skew`]: the angle of the text lines, by projection profiles.
//! - [`similarity`]: how alike two pictures look (SSIM), for picking the
//!   smallest JPEG that still looks the same.

#![forbid(unsafe_code)]

pub mod sauvola;
pub mod sharpness;
pub mod similarity;
pub mod skew;

/// A grey picture borrowed from the caller.
#[derive(Debug, Clone, Copy)]
pub struct Gray<'a> {
    pub pixels: &'a [u8],
    pub width: usize,
    pub height: usize,
}

impl<'a> Gray<'a> {
    /// `None` when the sizes don't match the bytes.
    pub fn new(pixels: &'a [u8], width: usize, height: usize) -> Option<Self> {
        (width > 0 && height > 0 && width.checked_mul(height) == Some(pixels.len())).then_some(Self { pixels, width, height })
    }

    #[inline]
    pub(crate) fn at(&self, x: usize, y: usize) -> u8 {
        self.pixels[y * self.width + x]
    }
}

#[cfg(test)]
pub(crate) mod test_pictures {
    /// A white page with lines of black "words": strokes 2 px wide, gaps between.
    pub fn text_page(width: usize, height: usize, angle_degrees: f32) -> Vec<u8> {
        let mut p = vec![235u8; width * height];
        let (sin, cos) = angle_degrees.to_radians().sin_cos();
        let (cx, cy) = (width as f32 / 2.0, height as f32 / 2.0);
        for y in 0..height {
            for x in 0..width {
                // Undo the rotation to find where this pixel sits on the upright page.
                let (dx, dy) = (x as f32 - cx, y as f32 - cy);
                let ux = dx * cos + dy * sin + cx;
                let uy = -dx * sin + dy * cos + cy;
                if ux < 20.0 || ux > width as f32 - 20.0 || uy < 20.0 || uy > height as f32 - 20.0 {
                    continue;
                }
                let line = (uy as i32) % 24;
                let column = (ux as i32) % 13;
                let word = ((ux as i32) / 60 + (uy as i32) / 24) % 5 != 0;
                if line < 12 && word && (column < 2 || line < 2 || line == 10 || line == 11) {
                    p[y * width + x] = 20;
                }
            }
        }
        p
    }

    /// Box blur, `passes` times: each pass widens the blur by about a pixel.
    pub fn blur(p: &[u8], width: usize, height: usize, passes: usize) -> Vec<u8> {
        let mut cur = p.to_vec();
        for _ in 0..passes {
            let mut next = cur.clone();
            for y in 1..height - 1 {
                for x in 1..width - 1 {
                    let mut s = 0u32;
                    for dy in 0..3 {
                        for dx in 0..3 {
                            s += u32::from(cur[(y + dy - 1) * width + x + dx - 1]);
                        }
                    }
                    next[y * width + x] = (s / 9) as u8;
                }
            }
            cur = next;
        }
        cur
    }
}
