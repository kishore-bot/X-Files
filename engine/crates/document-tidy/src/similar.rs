//! Grouping 64-bit fingerprints by Hamming distance without comparing every pair.
//!
//! Banded lookup: split the 64 bits into `d + 1` bands. If two fingerprints
//! differ in at most `d` bits, those bits fall in at most `d` bands, so at
//! least one band is identical (pigeonhole). Fingerprints are bucketed by
//! `(band, value)`, only fingerprints sharing a bucket are compared, and pairs
//! within `d` are joined with union-find. Equal fingerprints are collapsed
//! first, so a thousand copies of one photo cost one comparison, not a
//! million.
//!
//! A bucket that is still crowded (many different fingerprints agreeing on
//! one band) is compared against a sliding window of its sorted neighbours,
//! which keeps the worst case linear in the number of fingerprints.

use std::collections::HashMap;

use crate::{Cancelled, Progress};

/// The largest distance supported; more bands than this make buckets too
/// coarse to prune anything.
pub const MAX_DISTANCE: u32 = 10;

/// Buckets larger than this are compared within a window, not all pairs.
const FULL_COMPARE_BUCKET: usize = 192;
const WINDOW: usize = 48;

pub fn hamming(a: u64, b: u64) -> u32 {
    (a ^ b).count_ones()
}

/// Groups of indices into `fingerprints` whose members are linked by chains
/// of fingerprints at most `max_distance` apart. Groups of one are dropped.
/// Largest groups first; indices ascending within a group.
pub fn group_similar<P: Progress + ?Sized>(
    fingerprints: &[u64],
    max_distance: u32,
    progress: &P,
) -> Result<Vec<Vec<usize>>, Cancelled> {
    let max_distance = max_distance.min(MAX_DISTANCE);

    // Collapse exact matches: one representative per distinct fingerprint.
    let mut distinct: Vec<u64> = Vec::new();
    let mut members: HashMap<u64, Vec<usize>> = HashMap::new();
    for (index, &fp) in fingerprints.iter().enumerate() {
        let list = members.entry(fp).or_default();
        if list.is_empty() {
            distinct.push(fp);
        }
        list.push(index);
    }

    let mut sets = DisjointSets::new(distinct.len());
    let bands = band_ranges(max_distance + 1);
    for (band, &(shift, width)) in bands.iter().enumerate() {
        progress.check()?;
        let mask = if width >= 64 { u64::MAX } else { (1u64 << width) - 1 };
        let mut buckets: HashMap<u64, Vec<usize>> = HashMap::new();
        for (slot, &fp) in distinct.iter().enumerate() {
            buckets.entry((fp >> shift) & mask).or_default().push(slot);
        }
        for bucket in buckets.values_mut() {
            if bucket.len() < 2 {
                continue;
            }
            if bucket.len() <= FULL_COMPARE_BUCKET {
                for i in 0..bucket.len() {
                    for j in i + 1..bucket.len() {
                        let (a, b) = (bucket[i], bucket[j]);
                        if hamming(distinct[a], distinct[b]) <= max_distance {
                            sets.union(a, b);
                        }
                    }
                }
            } else {
                // Sorted by the bits outside this band, near fingerprints sit close together.
                let outside = !(mask << shift);
                bucket.sort_unstable_by_key(|&slot| (distinct[slot] & outside).rotate_left(band as u32 * 7));
                for i in 0..bucket.len() {
                    for j in i + 1..(i + 1 + WINDOW).min(bucket.len()) {
                        let (a, b) = (bucket[i], bucket[j]);
                        if hamming(distinct[a], distinct[b]) <= max_distance {
                            sets.union(a, b);
                        }
                    }
                }
            }
        }
    }

    let mut grouped: HashMap<usize, Vec<usize>> = HashMap::new();
    for (slot, fp) in distinct.iter().enumerate() {
        let root = sets.find(slot);
        grouped.entry(root).or_default().extend_from_slice(&members[fp]);
    }
    let mut groups: Vec<Vec<usize>> = grouped.into_values().filter(|g| g.len() > 1).collect();
    for group in &mut groups {
        group.sort_unstable();
    }
    groups.sort_by(|a, b| b.len().cmp(&a.len()).then_with(|| a[0].cmp(&b[0])));
    Ok(groups)
}

/// `(shift, width)` of `count` bands covering all 64 bits as evenly as possible.
fn band_ranges(count: u32) -> Vec<(u32, u32)> {
    let count = count.clamp(1, 64);
    let base = 64 / count;
    let extra = 64 % count;
    let mut shift = 0;
    (0..count)
        .map(|i| {
            let width = base + u32::from(i < extra);
            let band = (shift, width);
            shift += width;
            band
        })
        .collect()
}

struct DisjointSets {
    parent: Vec<usize>,
    rank: Vec<u8>,
}

impl DisjointSets {
    fn new(len: usize) -> Self {
        Self { parent: (0..len).collect(), rank: vec![0; len] }
    }

    fn find(&mut self, mut x: usize) -> usize {
        while self.parent[x] != x {
            self.parent[x] = self.parent[self.parent[x]];
            x = self.parent[x];
        }
        x
    }

    fn union(&mut self, a: usize, b: usize) {
        let (ra, rb) = (self.find(a), self.find(b));
        if ra == rb {
            return;
        }
        match self.rank[ra].cmp(&self.rank[rb]) {
            std::cmp::Ordering::Less => self.parent[ra] = rb,
            std::cmp::Ordering::Greater => self.parent[rb] = ra,
            std::cmp::Ordering::Equal => {
                self.parent[rb] = ra;
                self.rank[ra] = self.rank[ra].saturating_add(1);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::NeverCancelled;

    /// A deterministic pseudo-random sequence (SplitMix64).
    fn random(seed: &mut u64) -> u64 {
        *seed = seed.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = *seed;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    /// Brute force, for comparison.
    fn all_pairs(fps: &[u64], d: u32) -> Vec<Vec<usize>> {
        let mut sets = DisjointSets::new(fps.len());
        for i in 0..fps.len() {
            for j in i + 1..fps.len() {
                if hamming(fps[i], fps[j]) <= d {
                    sets.union(i, j);
                }
            }
        }
        let mut grouped: HashMap<usize, Vec<usize>> = HashMap::new();
        for i in 0..fps.len() {
            let root = sets.find(i);
            grouped.entry(root).or_default().push(i);
        }
        let mut groups: Vec<Vec<usize>> = grouped.into_values().filter(|g| g.len() > 1).collect();
        groups.sort_by(|a, b| b.len().cmp(&a.len()).then_with(|| a[0].cmp(&b[0])));
        groups
    }

    #[test]
    fn bands_cover_all_bits() {
        for count in 1..=11 {
            let bands = band_ranges(count);
            assert_eq!(bands.len(), count as usize);
            assert_eq!(bands.iter().map(|b| b.1).sum::<u32>(), 64);
            assert_eq!(bands.last().map(|b| b.0 + b.1), Some(64));
        }
    }

    #[test]
    fn finds_every_pair_brute_force_finds() {
        let mut seed = 7;
        for d in [0, 3, 6] {
            let mut fps = Vec::new();
            for _ in 0..150 {
                let base = random(&mut seed);
                fps.push(base);
                // A neighbour at exactly d bits, and one well beyond.
                let mut near = base;
                let mut flipped = 0;
                while flipped < d {
                    let bit = 1u64 << (random(&mut seed) % 64);
                    if near & bit == base & bit {
                        near ^= bit;
                        flipped += 1;
                    }
                }
                fps.push(near);
            }
            assert_eq!(group_similar(&fps, d, &NeverCancelled).unwrap(), all_pairs(&fps, d), "d = {d}");
        }
    }

    #[test]
    fn far_fingerprints_stay_apart() {
        let fps = [0u64, 0b1111, u64::MAX];
        assert!(group_similar(&fps, 3, &NeverCancelled).unwrap().is_empty());
        assert_eq!(group_similar(&fps, 4, &NeverCancelled).unwrap(), vec![vec![0, 1]]);
    }

    #[test]
    fn many_equal_fingerprints_collapse_cheaply() {
        let fps = vec![42u64; 50_000];
        let groups = group_similar(&fps, 3, &NeverCancelled).unwrap();
        assert_eq!(groups.len(), 1);
        assert_eq!(groups[0].len(), 50_000);
    }

    #[test]
    fn a_crowded_bucket_stays_bounded() {
        // 20 000 fingerprints that share their low 16 bits: one crowded bucket per band 0.
        let mut seed = 11;
        let fps: Vec<u64> = (0..20_000).map(|_| (random(&mut seed) << 16) | 0xBEEF).collect();
        let start = std::time::Instant::now();
        let groups = group_similar(&fps, 3, &NeverCancelled).unwrap();
        assert!(groups.is_empty());
        assert!(start.elapsed().as_secs() < 10);
    }

    #[test]
    fn honours_cancellation() {
        struct Cancelledness;
        impl Progress for Cancelledness {
            fn is_cancelled(&self) -> bool {
                true
            }
        }
        assert_eq!(group_similar(&[1, 2], 3, &Cancelledness), Err(Cancelled));
    }
}
