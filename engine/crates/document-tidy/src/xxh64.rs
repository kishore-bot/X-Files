//! xxHash64, streaming.
//!
//! Chosen for duplicate detection because it is fast on 32- and 64-bit ARM
//! without SIMD (several GB/s, far above flash read speed), has a stable,
//! documented specification with published test vectors, and is about a
//! hundred lines. It is not cryptographic: two files are only reported as
//! identical when their sizes match and both a partial and a full 64-bit hash
//! agree, so an accidental collision is out of reach (about 2^-64 per pair),
//! and nothing here is ever deleted without the user looking first.
//!
//! Reference: <https://github.com/Cyan4973/xxHash/blob/dev/doc/xxhash_spec.md>.

const P1: u64 = 0x9E37_79B1_85EB_CA87;
const P2: u64 = 0xC2B2_AE3D_27D4_EB4F;
const P3: u64 = 0x1656_67B1_9E37_79F9;
const P4: u64 = 0x85EB_CA77_C2B2_AE63;
const P5: u64 = 0x27D4_EB2F_1656_67C5;

#[inline]
fn round(acc: u64, lane: u64) -> u64 {
    acc.wrapping_add(lane.wrapping_mul(P2)).rotate_left(31).wrapping_mul(P1)
}

#[inline]
fn merge(acc: u64, value: u64) -> u64 {
    (acc ^ round(0, value)).wrapping_mul(P1).wrapping_add(P4)
}

#[inline]
fn read_u64(bytes: &[u8]) -> u64 {
    let mut lane = [0u8; 8];
    lane.copy_from_slice(&bytes[..8]);
    u64::from_le_bytes(lane)
}

#[inline]
fn read_u32(bytes: &[u8]) -> u32 {
    let mut lane = [0u8; 4];
    lane.copy_from_slice(&bytes[..4]);
    u32::from_le_bytes(lane)
}

/// Incremental xxHash64: feed any number of slices, then [`Xxh64::finish`].
#[derive(Debug, Clone)]
pub struct Xxh64 {
    seed: u64,
    acc: [u64; 4],
    buffer: [u8; 32],
    buffered: usize,
    total: u64,
}

impl Xxh64 {
    pub fn new(seed: u64) -> Self {
        Self {
            seed,
            acc: [
                seed.wrapping_add(P1).wrapping_add(P2),
                seed.wrapping_add(P2),
                seed,
                seed.wrapping_sub(P1),
            ],
            buffer: [0; 32],
            buffered: 0,
            total: 0,
        }
    }

    pub fn update(&mut self, mut data: &[u8]) {
        self.total = self.total.wrapping_add(data.len() as u64);
        if self.buffered > 0 {
            let take = (32 - self.buffered).min(data.len());
            self.buffer[self.buffered..self.buffered + take].copy_from_slice(&data[..take]);
            self.buffered += take;
            data = &data[take..];
            if self.buffered < 32 {
                return;
            }
            let block = self.buffer;
            self.stripe(&block);
            self.buffered = 0;
        }
        let mut stripes = data.chunks_exact(32);
        for block in &mut stripes {
            self.stripe(block);
        }
        let rest = stripes.remainder();
        self.buffer[..rest.len()].copy_from_slice(rest);
        self.buffered = rest.len();
    }

    #[inline]
    fn stripe(&mut self, block: &[u8]) {
        for (i, acc) in self.acc.iter_mut().enumerate() {
            *acc = round(*acc, read_u64(&block[i * 8..]));
        }
    }

    pub fn finish(&self) -> u64 {
        let mut hash = if self.total >= 32 {
            let [a, b, c, d] = self.acc;
            let mut h = a.rotate_left(1).wrapping_add(b.rotate_left(7)).wrapping_add(c.rotate_left(12)).wrapping_add(d.rotate_left(18));
            for lane in self.acc {
                h = merge(h, lane);
            }
            h
        } else {
            self.seed.wrapping_add(P5)
        };
        hash = hash.wrapping_add(self.total);

        let mut tail = &self.buffer[..self.buffered];
        while tail.len() >= 8 {
            hash = (hash ^ round(0, read_u64(tail))).rotate_left(27).wrapping_mul(P1).wrapping_add(P4);
            tail = &tail[8..];
        }
        if tail.len() >= 4 {
            hash = (hash ^ u64::from(read_u32(tail)).wrapping_mul(P1)).rotate_left(23).wrapping_mul(P2).wrapping_add(P3);
            tail = &tail[4..];
        }
        for &byte in tail {
            hash = (hash ^ u64::from(byte).wrapping_mul(P5)).rotate_left(11).wrapping_mul(P1);
        }

        hash ^= hash >> 33;
        hash = hash.wrapping_mul(P2);
        hash ^= hash >> 29;
        hash = hash.wrapping_mul(P3);
        hash ^ (hash >> 32)
    }
}

/// One-shot xxHash64 of `data`.
pub fn xxh64(data: &[u8], seed: u64) -> u64 {
    let mut hasher = Xxh64::new(seed);
    hasher.update(data);
    hasher.finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_reference_vectors() {
        assert_eq!(xxh64(b"", 0), 0xEF46_DB37_51D8_E999);
        assert_eq!(xxh64(b"a", 0), 0xD24E_C4F1_A98C_6E5B);
        assert_eq!(xxh64(b"abc", 0), 0x44BC_2CF5_AD77_0999);
        assert_eq!(xxh64(b"Nobody inspects the spammish repetition", 0), 0xFBCE_A83C_8A37_8BF1);
    }

    #[test]
    fn streaming_matches_one_shot_at_every_split() {
        let data: Vec<u8> = (0..300u32).map(|i| (i.wrapping_mul(7919) >> 3) as u8).collect();
        let whole = xxh64(&data, 42);
        for split in 0..data.len() {
            let mut hasher = Xxh64::new(42);
            hasher.update(&data[..split]);
            hasher.update(&data[split..]);
            assert_eq!(hasher.finish(), whole, "split at {split}");
        }
        let mut bytewise = Xxh64::new(42);
        for byte in &data {
            bytewise.update(std::slice::from_ref(byte));
        }
        assert_eq!(bytewise.finish(), whole);
    }

    #[test]
    fn seed_changes_the_hash() {
        assert_ne!(xxh64(b"same", 0), xxh64(b"same", 1));
    }
}
