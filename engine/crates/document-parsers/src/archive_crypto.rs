//! Decryption for ZIP entries: traditional PKWARE encryption ("ZipCrypto") and
//! WinZip AES (AE-1 and AE-2, 128/192/256-bit).
//!
//! Layers, innermost first, each owning the one below:
//! [`Limited`] (the entry's raw bytes) -> cipher ([`Cipher`]) -> optional DEFLATE
//! decoder -> [`PlainEntry`], which enforces the declared size, the CRC and the
//! compression-ratio guard. Ownership runs one way, so the raw archive reader
//! can be handed back at any point through [`PlainEntry::into_reader`].
//!
//! Checks happen before any plaintext is produced: ZipCrypto compares the
//! header's check byte, and WinZip AES compares its 2-byte verifier after
//! PBKDF2. WinZip AES also authenticates the ciphertext with HMAC-SHA1
//! (10-byte tag at the end), verified as the stream ends.
//!
//! Wrong passwords are reported when the header or verifier does not match.
//! ZipCrypto's check byte has a 1-in-256 false pass; the CRC then catches it.

use std::io::{self, BufReader, Read};

use aes::cipher::generic_array::GenericArray;
use aes::cipher::{BlockEncrypt, KeyInit};
use aes::{Aes128, Aes192, Aes256};
use document_core::{CancelToken, EngineError, ErrorKind, Limits, ReadSeek};
use flate2::read::DeflateDecoder;
use sha1::{Digest, Sha1};
use zeroize::Zeroize;

use crate::zip::{ZipEntry, engine_io};

const AUTH_LEN: usize = 10;
const WINZIP_ITERATIONS: u32 = 1000;
const RATIO_GRACE_BYTES: u64 = 1024 * 1024;

/// Returned with a cipher error so the caller can hand the raw reader back.
pub(crate) type OpenError = (EngineError, Box<dyn ReadSeek>);

/// The first `len` bytes of the underlying reader, and nothing past them.
pub(crate) struct Limited {
    inner: Box<dyn ReadSeek>,
    remaining: u64,
}

impl Limited {
    fn new(inner: Box<dyn ReadSeek>, len: u64) -> Self {
        Self { inner, remaining: len }
    }

    fn into_inner(self) -> Box<dyn ReadSeek> {
        self.inner
    }
}

impl Read for Limited {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if self.remaining == 0 || buf.is_empty() {
            return Ok(0);
        }
        let max = buf.len().min(self.remaining as usize);
        let n = self.inner.read(&mut buf[..max])?;
        self.remaining -= n as u64;
        Ok(n)
    }
}

/// Keeps `read_exact` results typed: a short header is malformed, not an I/O failure.
fn header_error(name: &str) -> EngineError {
    EngineError::malformed(format!("{name} is truncated"))
}

// ---------------------------------------------------------------- ZipCrypto

const fn crc_table() -> [u32; 256] {
    let mut table = [0u32; 256];
    let mut i = 0;
    while i < 256 {
        let mut c = i as u32;
        let mut k = 0;
        while k < 8 {
            c = if c & 1 != 0 { 0xEDB8_8320 ^ (c >> 1) } else { c >> 1 };
            k += 1;
        }
        table[i] = c;
        i += 1;
    }
    table
}

const CRC_TABLE: [u32; 256] = crc_table();

/// The three ZipCrypto key registers (APPNOTE 6.1.7).
struct ZipCryptoKeys {
    x: u32,
    y: u32,
    z: u32,
}

impl ZipCryptoKeys {
    fn new(password: &[u8]) -> Self {
        let mut keys = Self { x: 0x1234_5678, y: 0x2345_6789, z: 0x3456_7890 };
        for &byte in password {
            keys.update(byte);
        }
        keys
    }

    fn crc(c: u32, byte: u8) -> u32 {
        CRC_TABLE[((c ^ u32::from(byte)) & 0xff) as usize] ^ (c >> 8)
    }

    fn update(&mut self, plain: u8) {
        self.x = Self::crc(self.x, plain);
        self.y = self.y.wrapping_add(self.x & 0xff).wrapping_mul(134_775_813).wrapping_add(1);
        self.z = Self::crc(self.z, (self.y >> 24) as u8);
    }

    fn stream_byte(&self) -> u8 {
        let t = (self.z | 2) & 0xffff;
        ((t * (t ^ 1)) >> 8) as u8
    }

    fn decrypt(&mut self, cipher: u8) -> u8 {
        let plain = cipher ^ self.stream_byte();
        self.update(plain);
        plain
    }
}

pub(crate) struct ZipCryptoReader {
    body: Limited,
    keys: ZipCryptoKeys,
}

impl Read for ZipCryptoReader {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let n = self.body.read(buf)?;
        for byte in &mut buf[..n] {
            *byte = self.keys.decrypt(*byte);
        }
        Ok(n)
    }
}

fn open_zip_crypto(raw: Box<dyn ReadSeek>, entry: &ZipEntry, password: &[u8]) -> std::result::Result<Cipher, OpenError> {
    let mut body = Limited::new(raw, entry.compressed_size);
    let mut keys = ZipCryptoKeys::new(password);
    let mut header = [0u8; 12];
    if body.read_exact(&mut header).is_err() {
        let error = header_error(&entry.name);
        return Err((error, body.into_inner()));
    }
    for byte in &mut header {
        *byte = keys.decrypt(*byte);
    }
    // The check byte is the CRC's high byte, or the DOS time's high byte when a data descriptor follows.
    let expected = if entry.flags & 8 != 0 { (entry.modified_dos >> 8) as u8 } else { (entry.crc32 >> 24) as u8 };
    if header[11] != expected {
        return Err((wrong_password(), body.into_inner()));
    }
    Ok(Cipher::ZipCrypto(ZipCryptoReader { body, keys }))
}

// ---------------------------------------------------------------- WinZip AES

/// HMAC-SHA1 over the RustCrypto `sha1` crate, which is all this format needs.
#[derive(Clone)]
struct HmacSha1 {
    inner: Sha1,
    outer_pad: [u8; 64],
}

impl HmacSha1 {
    fn new(key: &[u8]) -> Self {
        let mut block = [0u8; 64];
        if key.len() > 64 {
            let digest: [u8; 20] = Sha1::digest(key).into();
            block[..20].copy_from_slice(&digest);
        } else {
            block[..key.len()].copy_from_slice(key);
        }
        let mut inner_pad = [0x36u8; 64];
        let mut outer_pad = [0x5cu8; 64];
        for i in 0..64 {
            inner_pad[i] ^= block[i];
            outer_pad[i] ^= block[i];
        }
        let mut inner = Sha1::new();
        inner.update(inner_pad);
        block.zeroize();
        Self { inner, outer_pad }
    }

    fn update(&mut self, data: &[u8]) {
        self.inner.update(data);
    }

    fn finalize(self) -> [u8; 20] {
        let inner: [u8; 20] = self.inner.finalize().into();
        let mut outer = Sha1::new();
        outer.update(self.outer_pad);
        outer.update(inner);
        outer.finalize().into()
    }
}

/// PBKDF2-HMAC-SHA1 as WinZip uses it (1000 iterations, no per-entry options).
fn pbkdf2_sha1(password: &[u8], salt: &[u8], iterations: u32, out: &mut [u8]) {
    for (index, chunk) in out.chunks_mut(20).enumerate() {
        let block_index = (index as u32 + 1).to_be_bytes();
        let mut first = HmacSha1::new(password);
        first.update(salt);
        first.update(&block_index);
        let mut t: [u8; 20] = first.finalize();
        let mut acc = t;
        for _ in 1..iterations {
            let mut next = HmacSha1::new(password);
            next.update(&t);
            t = next.finalize();
            for (a, b) in acc.iter_mut().zip(t.iter()) {
                *a ^= b;
            }
        }
        chunk.copy_from_slice(&acc[..chunk.len()]);
    }
}

enum AesKey {
    A128(Aes128),
    A192(Aes192),
    A256(Aes256),
}

impl AesKey {
    fn new(key: &[u8]) -> Option<Self> {
        match key.len() {
            16 => Aes128::new_from_slice(key).ok().map(Self::A128),
            24 => Aes192::new_from_slice(key).ok().map(Self::A192),
            32 => Aes256::new_from_slice(key).ok().map(Self::A256),
            _ => None,
        }
    }

    fn encrypt(&self, block: &mut [u8; 16]) {
        let b = GenericArray::from_mut_slice(&mut block[..]);
        match self {
            Self::A128(c) => c.encrypt_block(b),
            Self::A192(c) => c.encrypt_block(b),
            Self::A256(c) => c.encrypt_block(b),
        }
    }
}

pub(crate) struct AesReader {
    body: Limited,
    key: AesKey,
    mac: HmacSha1,
    counter: u64,
    keystream: [u8; 16],
    used: usize,
    /// Ciphertext held back because it may be the trailing authentication code.
    tail: Vec<u8>,
    out: Vec<u8>,
    out_pos: usize,
    finished: bool,
}

impl AesReader {
    fn refill(&mut self) -> io::Result<()> {
        self.out.clear();
        self.out_pos = 0;
        let mut chunk = [0u8; 8192];
        let n = self.body.read(&mut chunk)?;
        if n == 0 {
            self.finished = true;
            if self.tail.len() != AUTH_LEN {
                return Err(engine_io(EngineError::malformed("the encrypted entry is truncated")));
            }
            let expected = self.mac.clone().finalize();
            if expected[..AUTH_LEN] != self.tail[..] {
                return Err(engine_io(EngineError::new(
                    ErrorKind::Malformed,
                    "the encrypted entry failed authentication; it is corrupt",
                )));
            }
            self.tail.clear();
            return Ok(());
        }
        let mut data = std::mem::take(&mut self.tail);
        data.extend_from_slice(&chunk[..n]);
        let emit = data.len().saturating_sub(AUTH_LEN);
        self.tail = data.split_off(emit);
        self.mac.update(&data);
        for byte in &mut data {
            if self.used == 16 {
                self.counter += 1;
                let mut block = [0u8; 16];
                block[..8].copy_from_slice(&self.counter.to_le_bytes());
                self.key.encrypt(&mut block);
                self.keystream = block;
                self.used = 0;
            }
            *byte ^= self.keystream[self.used];
            self.used += 1;
        }
        self.out = data;
        Ok(())
    }
}

impl Read for AesReader {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        loop {
            if self.out_pos < self.out.len() {
                let n = buf.len().min(self.out.len() - self.out_pos);
                buf[..n].copy_from_slice(&self.out[self.out_pos..self.out_pos + n]);
                self.out_pos += n;
                return Ok(n);
            }
            if self.finished {
                return Ok(0);
            }
            self.refill()?;
        }
    }
}

fn open_aes(raw: Box<dyn ReadSeek>, entry: &ZipEntry, password: &[u8]) -> std::result::Result<Cipher, OpenError> {
    let (key_len, salt_len) = match entry.aes_strength {
        1 => (16usize, 8usize),
        2 => (24, 12),
        3 => (32, 16),
        _ => return Err((EngineError::unsupported("unknown WinZip AES strength"), raw)),
    };
    if entry.compressed_size < (salt_len + 2 + AUTH_LEN) as u64 {
        return Err((header_error(&entry.name), raw));
    }
    let mut body = Limited::new(raw, entry.compressed_size);
    let mut salt_and_verifier = vec![0u8; salt_len + 2];
    if body.read_exact(&mut salt_and_verifier).is_err() {
        let error = header_error(&entry.name);
        return Err((error, body.into_inner()));
    }
    let mut derived = vec![0u8; 2 * key_len + 2];
    pbkdf2_sha1(password, &salt_and_verifier[..salt_len], WINZIP_ITERATIONS, &mut derived);
    if derived[2 * key_len..] != salt_and_verifier[salt_len..] {
        derived.zeroize();
        return Err((wrong_password(), body.into_inner()));
    }
    let key = AesKey::new(&derived[..key_len]);
    let mac = HmacSha1::new(&derived[key_len..2 * key_len]);
    derived.zeroize();
    let Some(key) = key else {
        return Err((EngineError::unsupported("unsupported WinZip AES key size"), body.into_inner()));
    };
    Ok(Cipher::Aes(AesReader {
        body,
        key,
        mac,
        counter: 0,
        keystream: [0; 16],
        used: 16,
        tail: Vec::with_capacity(AUTH_LEN),
        out: Vec::new(),
        out_pos: 0,
        finished: false,
    }))
}

// ---------------------------------------------------------------- shared

fn wrong_password() -> EngineError {
    EngineError::new(ErrorKind::WrongPassword, "the password does not open this entry")
}

/// The decrypting layer: ZipCrypto or WinZip AES, behind one `Read`.
pub(crate) enum Cipher {
    ZipCrypto(ZipCryptoReader),
    Aes(AesReader),
}

impl Read for Cipher {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        match self {
            Self::ZipCrypto(r) => r.read(buf),
            Self::Aes(r) => r.read(buf),
        }
    }
}

impl Cipher {
    fn into_inner(self) -> Box<dyn ReadSeek> {
        match self {
            Self::ZipCrypto(r) => r.body.into_inner(),
            Self::Aes(r) => r.body.into_inner(),
        }
    }
}

enum Decoded {
    Stored(Cipher),
    Deflate(Box<DeflateDecoder<BufReader<Cipher>>>),
}

/// One decrypted, decompressed entry with the same guards as the plain reader.
pub(crate) struct PlainEntry {
    source: Decoded,
    name: String,
    size: u64,
    compressed_size: u64,
    expected_crc: u32,
    check_crc: bool,
    zip_crypto: bool,
    produced: u64,
    crc: crc32fast::Hasher,
    cancel: CancelToken,
    ratio: u64,
}

impl PlainEntry {
    /// Opens an encrypted entry. Fails before reading any data when the password is wrong.
    pub(crate) fn open(
        raw: Box<dyn ReadSeek>,
        entry: &ZipEntry,
        password: &[u8],
        cancel: CancelToken,
    ) -> std::result::Result<Self, OpenError> {
        let cipher = if entry.aes_strength != 0 {
            open_aes(raw, entry, password)?
        } else {
            open_zip_crypto(raw, entry, password)?
        };
        let source = match entry.method {
            0 => Decoded::Stored(cipher),
            8 => Decoded::Deflate(Box::new(DeflateDecoder::new(BufReader::new(cipher)))),
            method => {
                let raw = cipher.into_inner();
                return Err((EngineError::unsupported(format!("ZIP compression method {method} is not supported")), raw));
            }
        };
        // WinZip AE-2 entries store a zero CRC; their HMAC is the integrity check instead.
        let check_crc = entry.aes_strength == 0 || entry.crc32 != 0;
        Ok(Self {
            source,
            name: entry.name.clone(),
            size: entry.size,
            compressed_size: entry.compressed_size,
            expected_crc: entry.crc32,
            check_crc,
            zip_crypto: entry.aes_strength == 0,
            produced: 0,
            crc: crc32fast::Hasher::new(),
            cancel,
            ratio: Limits::default().max_compression_ratio,
        })
    }

    /// Gives the raw archive reader back, wherever reading stopped.
    pub(crate) fn into_reader(self) -> Box<dyn ReadSeek> {
        match self.source {
            Decoded::Stored(cipher) => cipher.into_inner(),
            Decoded::Deflate(decoder) => decoder.into_inner().into_inner().into_inner(),
        }
    }
}

impl Read for PlainEntry {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.cancel.check().map_err(engine_io)?;
        let n = match &mut self.source {
            Decoded::Stored(r) => r.read(buf)?,
            Decoded::Deflate(r) => r.read(buf)?,
        };
        if n == 0 {
            let crc_bad = self.check_crc && self.crc.clone().finalize() != self.expected_crc;
            if !buf.is_empty() && (self.produced != self.size || crc_bad) {
                // ZipCrypto's header check passes one wrong password in 256. Garbage output
                // then fails here, and that is a password problem, not a damaged file.
                if self.zip_crypto {
                    return Err(engine_io(wrong_password()));
                }
                return Err(engine_io(EngineError::malformed(format!(
                    "{} is corrupt (size or checksum mismatch)",
                    self.name
                ))));
            }
            return Ok(0);
        }
        self.produced += n as u64;
        self.crc.update(&buf[..n]);
        if self.produced > self.size {
            return Err(engine_io(EngineError::malformed(format!("{} is larger than its header declares", self.name))));
        }
        let ratio_limit = self.compressed_size.max(1).saturating_mul(self.ratio);
        if self.produced > RATIO_GRACE_BYTES && self.produced > ratio_limit {
            return Err(engine_io(EngineError::limit(format!(
                "{} is compressed suspiciously well; refusing to expand it",
                self.name
            ))));
        }
        Ok(n)
    }
}

/// Encrypt-side fixtures. They use the same key schedule and KDF as the decrypt
/// path, so they prove the round trip, not interoperability with other tools.
#[cfg(test)]
pub(crate) mod test_support {
    use super::*;

    pub(crate) fn zipcrypto_seal(password: &[u8], header_check: u8, plain: &[u8]) -> Vec<u8> {
        let mut keys = ZipCryptoKeys::new(password);
        let mut out = Vec::new();
        let mut header = [0x5au8; 12];
        header[11] = header_check;
        for byte in header.iter().chain(plain.iter()) {
            let stream = keys.stream_byte();
            out.push(byte ^ stream);
            keys.update(*byte);
        }
        out
    }

    pub(crate) fn winzip_aes_seal(password: &[u8], strength: u8, plain: &[u8]) -> Vec<u8> {
        let (key_len, salt_len) = match strength {
            1 => (16usize, 8usize),
            2 => (24, 12),
            _ => (32, 16),
        };
        let salt = vec![7u8; salt_len];
        let mut derived = vec![0u8; 2 * key_len + 2];
        pbkdf2_sha1(password, &salt, WINZIP_ITERATIONS, &mut derived);
        let key = AesKey::new(&derived[..key_len]).unwrap();
        let mut mac = HmacSha1::new(&derived[key_len..2 * key_len]);
        let mut cipher = Vec::with_capacity(plain.len());
        for (i, chunk) in plain.chunks(16).enumerate() {
            let mut block = [0u8; 16];
            block[..8].copy_from_slice(&((i as u64) + 1).to_le_bytes());
            key.encrypt(&mut block);
            for (j, byte) in chunk.iter().enumerate() {
                cipher.push(byte ^ block[j]);
            }
        }
        mac.update(&cipher);
        let tag = mac.finalize();
        let mut out = salt;
        out.extend_from_slice(&derived[2 * key_len..]);
        out.extend_from_slice(&cipher);
        out.extend_from_slice(&tag[..AUTH_LEN]);
        out
    }

}

#[cfg(test)]
mod tests {
    use super::test_support::{winzip_aes_seal, zipcrypto_seal};
    use super::*;

    #[test]
    fn hmac_sha1_matches_rfc2202_vector() {
        // RFC 2202 test case 1: key = 0x0b * 20, data = "Hi There".
        let mut mac = HmacSha1::new(&[0x0b; 20]);
        mac.update(b"Hi There");
        let hex: String = mac.finalize().iter().map(|b| format!("{b:02x}")).collect();
        assert_eq!(hex, "b617318655057264e28bc0b6fb378c8ef146be00");
    }

    #[test]
    fn zip_crypto_round_trips_and_checks_the_header() {
        let plain = b"hello, encrypted world";
        // A real writer stores the plaintext CRC32 and uses its high byte as the check byte.
        let crc = crc32fast::hash(plain);
        let bytes = zipcrypto_seal(b"secret", (crc >> 24) as u8, plain);
        let entry = fake_entry(bytes.len() as u64, plain.len() as u64, 0, crc);
        let raw: Box<dyn ReadSeek> = Box::new(io::Cursor::new(bytes.clone()));
        let mut plain_entry = PlainEntry::open(raw, &entry, b"secret", CancelToken::default()).ok().unwrap();
        let mut out = Vec::new();
        plain_entry.read_to_end(&mut out).unwrap();
        assert_eq!(out, plain);

        let raw: Box<dyn ReadSeek> = Box::new(io::Cursor::new(bytes));
        let error = PlainEntry::open(raw, &entry, b"wrong", CancelToken::default()).err().unwrap().0;
        assert!(matches!(error.kind, ErrorKind::WrongPassword));
    }

    #[test]
    fn winzip_aes_round_trips_for_every_strength_and_rejects_tampering() {
        let plain = b"0123456789abcdefghij-aes-payload";
        for strength in [1u8, 2, 3] {
            let mut bytes = winzip_aes_seal(b"pw", strength, plain);
            let entry = fake_aes_entry(bytes.len() as u64, plain.len() as u64, strength);
            let raw: Box<dyn ReadSeek> = Box::new(io::Cursor::new(bytes.clone()));
            let mut ok = PlainEntry::open(raw, &entry, b"pw", CancelToken::default()).ok().unwrap();
            let mut out = Vec::new();
            ok.read_to_end(&mut out).unwrap();
            assert_eq!(out, plain, "strength {strength}");

            let last = bytes.len() - 1;
            bytes[last] ^= 1;
            let raw: Box<dyn ReadSeek> = Box::new(io::Cursor::new(bytes));
            let mut tampered = PlainEntry::open(raw, &entry, b"pw", CancelToken::default()).ok().unwrap();
            assert!(tampered.read_to_end(&mut Vec::new()).is_err(), "tampered tag must fail, strength {strength}");
        }
    }

    fn fake_entry(compressed: u64, size: u64, method: u16, crc_word: u32) -> ZipEntry {
        ZipEntry {
            name: "t.bin".into(),
            is_dir: false,
            size,
            compressed_size: compressed,
            method,
            encrypted: true,
            crc32: crc_word,
            flags: 0,
            aes_strength: 0,
            header_offset: 0,
            modified_dos: 0,
        }
    }

    fn fake_aes_entry(compressed: u64, size: u64, strength: u8) -> ZipEntry {
        ZipEntry { aes_strength: strength, crc32: 0, ..fake_entry(compressed, size, 0, 0) }
    }
}
