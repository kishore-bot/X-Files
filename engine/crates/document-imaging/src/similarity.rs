//! How alike two pictures of the same size look, by the structural
//! similarity index (SSIM; Wang et al., 2004) over 8 x 8 blocks: 1.0 when
//! identical, lower as compression smears or blocks the detail.

use crate::Gray;

const BLOCK: usize = 8;
const C1: f64 = (0.01 * 255.0) * (0.01 * 255.0);
const C2: f64 = (0.03 * 255.0) * (0.03 * 255.0);

/// Mean SSIM; `None` when the sizes differ or the pictures are under one block.
pub fn ssim(a: Gray<'_>, b: Gray<'_>) -> Option<f32> {
    if a.width != b.width || a.height != b.height || a.width < BLOCK || a.height < BLOCK {
        return None;
    }
    let (mut total, mut blocks) = (0f64, 0u32);
    for by in (0..=a.height - BLOCK).step_by(BLOCK) {
        for bx in (0..=a.width - BLOCK).step_by(BLOCK) {
            let (mut sa, mut sb, mut saa, mut sbb, mut sab) = (0f64, 0f64, 0f64, 0f64, 0f64);
            for y in by..by + BLOCK {
                for x in bx..bx + BLOCK {
                    let (va, vb) = (f64::from(a.at(x, y)), f64::from(b.at(x, y)));
                    sa += va;
                    sb += vb;
                    saa += va * va;
                    sbb += vb * vb;
                    sab += va * vb;
                }
            }
            let n = (BLOCK * BLOCK) as f64;
            let (ma, mb) = (sa / n, sb / n);
            let (va, vb, cov) = (saa / n - ma * ma, sbb / n - mb * mb, sab / n - ma * mb);
            total += ((2.0 * ma * mb + C1) * (2.0 * cov + C2)) / ((ma * ma + mb * mb + C1) * (va + vb + C2));
            blocks += 1;
        }
    }
    Some((total / f64::from(blocks)) as f32)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_pictures::{blur, text_page};

    #[test]
    fn identical_is_one_and_blur_is_less() {
        let (w, h) = (160, 120);
        let page = text_page(w, h, 0.0);
        let same = ssim(Gray::new(&page, w, h).unwrap(), Gray::new(&page, w, h).unwrap()).unwrap();
        assert!((same - 1.0).abs() < 1e-6);
        let soft = blur(&page, w, h, 2);
        let s = ssim(Gray::new(&page, w, h).unwrap(), Gray::new(&soft, w, h).unwrap()).unwrap();
        assert!(s < 0.9, "{s}");
    }

    #[test]
    fn different_sizes_have_no_score() {
        let a = vec![0u8; 64];
        let b = vec![0u8; 128];
        assert_eq!(ssim(Gray::new(&a, 8, 8).unwrap(), Gray::new(&b, 16, 8).unwrap()), None);
    }
}
