//! Password-protected OOXML (MS-OFFCRYPTO): an `EncryptedPackage` stream in a
//! compound file, whose plaintext is an ordinary DOCX, XLSX or PPTX.
//!
//! Supports Agile encryption (AES-CBC with SHA-1 or the SHA-2 family) and
//! Standard encryption (AES-ECB with SHA-1). A password is checked with the
//! format's own verifier, so a wrong one is a typed error and never produces
//! garbage. The file's HMAC is not checked: it guards against tampering, which
//! changes nothing here because the plaintext is parsed as untrusted input
//! anyway and ZIP checksums still catch corruption.
//!
//! Only this path handles the password: it is encoded once, never logged or
//! stored, and secrets derived from it are wiped on drop, as is the plaintext.
//! RC4, certificate-only and rights-managed files get a typed "unsupported"
//! error. The primitives come from RustCrypto; this file only arranges them as
//! the format specifies.

use aes::cipher::block_padding::NoPadding;
use aes::cipher::{Block, BlockCipherDecrypt, BlockModeDecrypt, KeyInit, KeyIvInit, consts::U16};
use aes::{Aes128, Aes192, Aes256};
use document_core::{CancelToken, EngineError, ErrorKind, Result};
use sha1::Sha1;
use sha2::{Digest, Sha256, Sha384, Sha512};
use zeroize::{Zeroize, Zeroizing};

use crate::cfb::{CfbFile, MAX_STREAM_BYTES, le16, le32};
use crate::xml::{Xml, XmlReader};

pub(crate) const ENCRYPTION_INFO: &str = "EncryptionInfo";
pub(crate) const ENCRYPTED_PACKAGE: &str = "EncryptedPackage";

const MAX_INFO_BYTES: u64 = 1024 * 1024;
const BLOCK: usize = 16;
const AGILE_SEGMENT: usize = 4096;
const PACKAGE_HEADER: usize = 8;
/// The most the format allows; each try at this count takes seconds on a phone.
const MAX_SPIN_COUNT: u32 = 10_000_000;
const STANDARD_SPIN_COUNT: u32 = 50_000;
const MAX_ENCODED_VALUE: usize = 4096;

const BLOCK_VERIFIER_INPUT: [u8; 8] = [0xFE, 0xA7, 0xD2, 0x76, 0x3B, 0x4B, 0x9E, 0x79];
const BLOCK_VERIFIER_VALUE: [u8; 8] = [0xD7, 0xAA, 0x0F, 0x6D, 0x30, 0x61, 0x34, 0x4E];
const BLOCK_KEY_VALUE: [u8; 8] = [0x14, 0x6E, 0x0B, 0xE7, 0xAB, 0xAC, 0xD0, 0xD6];

const FLAG_CRYPTOAPI: u32 = 0x04;
const FLAG_EXTERNAL: u32 = 0x10;
const FLAG_AES: u32 = 0x20;
const ALG_RC4: u32 = 0x6801;
const ALG_AES128: u32 = 0x660E;
const ALG_AES192: u32 = 0x660F;
const ALG_AES256: u32 = 0x6610;
const ALG_SHA1: u32 = 0x8004;

type Secret = Zeroizing<Vec<u8>>;

fn unsupported(what: &str) -> EngineError {
    EngineError::new(ErrorKind::UnsupportedEncryption, format!("This file uses {what}, which isn't supported."))
}

fn damaged(detail: &str) -> EngineError {
    EngineError::malformed(format!("the file's encryption data is damaged: {detail}"))
}

fn wrong_password() -> EngineError {
    EngineError::new(ErrorKind::WrongPassword, "That password didn't work.")
}

/// Decrypts the package in `cfb` into memory, at most [`MAX_STREAM_BYTES`].
/// The encryption is examined first, so an unsupported file says so without
/// first asking for a password that could not help.
pub(crate) fn decrypt(cfb: &mut CfbFile, password: Option<&str>, cancel: &CancelToken) -> Result<Secret> {
    let info = cfb.read_stream(ENCRYPTION_INFO, MAX_INFO_BYTES)?;
    let scheme = parse_info(&info, cancel)?;
    let password = password.ok_or_else(|| {
        EngineError::new(ErrorKind::PasswordRequired, "This file is protected. Enter its password to open it.")
    })?;
    let utf16: Secret = Zeroizing::new(password.encode_utf16().flat_map(u16::to_le_bytes).collect());
    let key = match &scheme {
        Scheme::Standard(standard) => standard.unlock(&utf16, cancel)?,
        Scheme::Agile(agile) => agile.unlock(&utf16, cancel)?,
    };
    let mut package: Secret =
        Zeroizing::new(cfb.read_stream(ENCRYPTED_PACKAGE, MAX_STREAM_BYTES + PACKAGE_HEADER as u64)?);
    match &scheme {
        Scheme::Standard(_) => decrypt_standard(&key, &mut package, cancel)?,
        Scheme::Agile(agile) => agile.decrypt_package(&key, &mut package, cancel)?,
    }
    Ok(package)
}

enum Scheme {
    Standard(Standard),
    Agile(Agile),
}

fn parse_info(info: &[u8], cancel: &CancelToken) -> Result<Scheme> {
    let (major, minor) = le16(info, 0).zip(le16(info, 2)).ok_or_else(|| damaged("the version is cut off"))?;
    match (major, minor) {
        (4, 4) => Agile::parse(info.get(8..).unwrap_or_default(), cancel).map(Scheme::Agile),
        (2..=4, 2) => Standard::parse(info).map(Scheme::Standard),
        (3 | 4, 3) => Err(unsupported("an extensible encryption scheme")),
        (1, 1) => Err(unsupported("older RC4 encryption")),
        _ => Err(unsupported("an unknown kind of encryption")),
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum HashKind {
    Sha1,
    Sha256,
    Sha384,
    Sha512,
}

impl HashKind {
    fn from_name(name: &str) -> Option<Self> {
        Some(match name.replace('-', "").to_ascii_uppercase().as_str() {
            "SHA1" => Self::Sha1,
            "SHA256" => Self::Sha256,
            "SHA384" => Self::Sha384,
            "SHA512" => Self::Sha512,
            _ => return None,
        })
    }

    fn hash(self, parts: &[&[u8]]) -> Secret {
        match self {
            Self::Sha1 => hash_parts::<Sha1>(parts),
            Self::Sha256 => hash_parts::<Sha256>(parts),
            Self::Sha384 => hash_parts::<Sha384>(parts),
            Self::Sha512 => hash_parts::<Sha512>(parts),
        }
    }

    /// `H(salt + password)`, then `count` rounds of `H(round + previous)`.
    fn iterate(self, salt: &[u8], password: &[u8], count: u32, cancel: &CancelToken) -> Result<Secret> {
        match self {
            Self::Sha1 => iterate::<Sha1>(salt, password, count, cancel),
            Self::Sha256 => iterate::<Sha256>(salt, password, count, cancel),
            Self::Sha384 => iterate::<Sha384>(salt, password, count, cancel),
            Self::Sha512 => iterate::<Sha512>(salt, password, count, cancel),
        }
    }
}

fn hash_parts<D: Digest>(parts: &[&[u8]]) -> Secret {
    let mut hasher = D::new();
    for part in parts {
        hasher.update(part);
    }
    Zeroizing::new(hasher.finalize().to_vec())
}

fn iterate<D: Digest>(salt: &[u8], password: &[u8], count: u32, cancel: &CancelToken) -> Result<Secret> {
    let mut hash = D::new().chain_update(salt).chain_update(password).finalize();
    for round in 0..count {
        if round & 0xFFFF == 0 {
            cancel.check()?;
        }
        hash = D::new().chain_update(round.to_le_bytes()).chain_update(&hash).finalize();
    }
    let result = Zeroizing::new(hash.to_vec());
    hash.as_mut_slice().zeroize();
    Ok(result)
}

/// Cuts or pads to `len`, padding with 0x36 as the format specifies.
fn fit(bytes: &[u8], len: usize) -> Secret {
    let mut out = Zeroizing::new(bytes[..bytes.len().min(len)].to_vec());
    out.resize(len, 0x36);
    out
}

fn ecb<C: BlockCipherDecrypt<BlockSize = U16> + KeyInit>(
    key: &[u8],
    data: &mut [u8],
    cancel: &CancelToken,
) -> Result<()> {
    let cipher = C::new_from_slice(key).map_err(|_| damaged("the key size is not valid"))?;
    for chunk in data.chunks_mut(1024 * 1024) {
        cancel.check()?;
        let (blocks, remainder) = chunk.as_chunks_mut::<BLOCK>();
        if !remainder.is_empty() {
            return Err(damaged("the data is not whole blocks"));
        }
        cipher.decrypt_blocks(Block::<C>::cast_slice_from_core_mut(blocks));
    }
    Ok(())
}

fn ecb_decrypt(key: &[u8], data: &mut [u8], cancel: &CancelToken) -> Result<()> {
    match key.len() {
        16 => ecb::<Aes128>(key, data, cancel),
        24 => ecb::<Aes192>(key, data, cancel),
        32 => ecb::<Aes256>(key, data, cancel),
        _ => Err(damaged("the key size is not valid")),
    }
}

fn cbc<C: BlockCipherDecrypt<BlockSize = U16> + KeyInit>(
    key: &[u8],
    iv: &[u8],
    data: &mut [u8],
) -> Result<()> {
    let decryptor = cbc::Decryptor::<C>::new_from_slices(key, iv).map_err(|_| damaged("the key size is not valid"))?;
    decryptor.decrypt_padded::<NoPadding>(data).map_err(|_| damaged("the data is not whole blocks"))?;
    Ok(())
}

fn cbc_decrypt(key: &[u8], iv: &[u8], data: &mut [u8]) -> Result<()> {
    match key.len() {
        16 => cbc::<Aes128>(key, iv, data),
        24 => cbc::<Aes192>(key, iv, data),
        32 => cbc::<Aes256>(key, iv, data),
        _ => Err(damaged("the key size is not valid")),
    }
}

/// Moves the plaintext over the 8-byte size prefix and cuts it to that size.
fn finish_package(package: &mut Vec<u8>, declared: u64, decrypted: usize) -> Result<()> {
    if declared > decrypted as u64 {
        return Err(damaged("the package is shorter than its declared size"));
    }
    let size = declared as usize;
    package.copy_within(PACKAGE_HEADER..PACKAGE_HEADER + size, 0);
    // Whatever lies past the end is wiped when the buffer is, on drop; truncate keeps the capacity.
    package.truncate(size);
    Ok(())
}

fn package_size(package: &[u8]) -> Result<u64> {
    let head: [u8; PACKAGE_HEADER] = package
        .get(..PACKAGE_HEADER)
        .and_then(|b| b.try_into().ok())
        .ok_or_else(|| damaged("the package is cut off"))?;
    Ok(u64::from_le_bytes(head))
}

// --- Standard encryption ---

struct Standard {
    key_bytes: usize,
    salt: [u8; 16],
    encrypted_verifier: [u8; 16],
    encrypted_hash: [u8; 32],
}

impl Standard {
    fn parse(info: &[u8]) -> Result<Self> {
        let cut = || damaged("the header is cut off");
        let flags = le32(info, 4).ok_or_else(cut)?;
        let header_size = le32(info, 8).ok_or_else(cut)? as usize;
        let header = 12;
        let algorithm = le32(info, header + 8).ok_or_else(cut)?;
        let hash = le32(info, header + 12).ok_or_else(cut)?;
        let key_bits = le32(info, header + 16).ok_or_else(cut)?;

        if flags & FLAG_EXTERNAL != 0 {
            return Err(unsupported("externally managed encryption"));
        }
        if algorithm == ALG_RC4 || flags & FLAG_AES == 0 {
            return Err(unsupported("RC4 encryption"));
        }
        if flags & FLAG_CRYPTOAPI == 0 || !matches!(hash, ALG_SHA1 | 0) {
            return Err(unsupported("an unknown kind of encryption"));
        }
        let key_bits = match (algorithm, key_bits) {
            (ALG_AES128, 0 | 128) | (0, 128) => 128,
            (ALG_AES192, 0 | 192) | (0, 192) => 192,
            (ALG_AES256, 0 | 256) | (0, 256) => 256,
            _ => return Err(unsupported("an unknown kind of encryption")),
        };

        let verifier = header.checked_add(header_size).ok_or_else(cut)?;
        if le32(info, verifier) != Some(16) {
            return Err(damaged("the salt size is not valid"));
        }
        let array = |at: usize, len: usize| info.get(verifier + at..verifier + at + len).ok_or_else(cut);
        let mut result =
            Self { key_bytes: key_bits / 8, salt: [0; 16], encrypted_verifier: [0; 16], encrypted_hash: [0; 32] };
        result.salt.copy_from_slice(array(4, 16)?);
        result.encrypted_verifier.copy_from_slice(array(20, 16)?);
        if le32(info, verifier + 36) != Some(20) {
            return Err(damaged("the verifier size is not valid"));
        }
        result.encrypted_hash.copy_from_slice(array(40, 32)?);
        Ok(result)
    }

    /// Derives the key and checks it against the verifier.
    fn unlock(&self, password: &[u8], cancel: &CancelToken) -> Result<Secret> {
        let key = standard_key(&self.salt, password, self.key_bytes, cancel)?;
        let mut verifier: Secret = Zeroizing::new(self.encrypted_verifier.to_vec());
        let mut stored: Secret = Zeroizing::new(self.encrypted_hash.to_vec());
        ecb_decrypt(&key, &mut verifier, cancel)?;
        ecb_decrypt(&key, &mut stored, cancel)?;
        let actual = HashKind::Sha1.hash(&[&verifier]);
        if actual[..] != stored[..actual.len()] {
            return Err(wrong_password());
        }
        Ok(key)
    }
}

/// MS-OFFCRYPTO 2.3.4.7.
fn standard_key(salt: &[u8], password: &[u8], key_bytes: usize, cancel: &CancelToken) -> Result<Secret> {
    let hash = HashKind::Sha1.iterate(salt, password, STANDARD_SPIN_COUNT, cancel)?;
    let last = HashKind::Sha1.hash(&[&hash, &0u32.to_le_bytes()]);
    let derive = |pad: u8| {
        let mut buffer = Zeroizing::new([pad; 64]);
        for (slot, byte) in buffer.iter_mut().zip(last.iter()) {
            *slot ^= byte;
        }
        HashKind::Sha1.hash(&[&buffer[..]])
    };
    let (first, second) = (derive(0x36), derive(0x5C));
    let mut key: Secret = Zeroizing::new(Vec::with_capacity(40));
    key.extend_from_slice(&first);
    key.extend_from_slice(&second);
    key.truncate(key_bytes);
    Ok(key)
}

fn decrypt_standard(key: &[u8], package: &mut Vec<u8>, cancel: &CancelToken) -> Result<()> {
    let declared = package_size(package)?;
    let whole = (package.len() - PACKAGE_HEADER) / BLOCK * BLOCK;
    ecb_decrypt(key, &mut package[PACKAGE_HEADER..PACKAGE_HEADER + whole], cancel)?;
    finish_package(package, declared, whole)
}

// --- Agile encryption ---

#[derive(Default)]
struct AgileParams {
    hash: Option<HashKind>,
    key_bits: usize,
    salt: Vec<u8>,
}

struct Agile {
    data: AgileParams,
    password: AgileParams,
    spin_count: u32,
    salt_size: usize,
    encrypted_input: Vec<u8>,
    encrypted_value: Vec<u8>,
    encrypted_key: Vec<u8>,
}

fn attr<'a>(tag: &'a crate::xml::Tag, name: &str) -> Result<&'a str> {
    tag.attr(name).ok_or_else(|| damaged("an attribute is missing"))
}

/// The parameters both `keyData` and `encryptedKey` carry. An algorithm this
/// code lacks is "unsupported"; values that make no sense are "damaged".
fn cipher_params(tag: &crate::xml::Tag) -> Result<AgileParams> {
    if !attr(tag, "cipherAlgorithm")?.eq_ignore_ascii_case("AES") {
        return Err(unsupported("a cipher other than AES"));
    }
    if !attr(tag, "cipherChaining")?.eq_ignore_ascii_case("ChainingModeCBC") {
        return Err(unsupported("a cipher mode other than CBC"));
    }
    let hash =
        HashKind::from_name(attr(tag, "hashAlgorithm")?).ok_or_else(|| unsupported("an unknown hash algorithm"))?;
    if attr(tag, "blockSize")?.parse() != Ok(BLOCK) {
        return Err(damaged("the block size is not valid"));
    }
    let key_bits: usize = attr(tag, "keyBits")?.parse().map_err(|_| damaged("the key size is not valid"))?;
    if !matches!(key_bits, 128 | 192 | 256) {
        return Err(unsupported("an unknown key size"));
    }
    Ok(AgileParams { hash: Some(hash), key_bits, salt: base64(attr(tag, "saltValue")?)? })
}

impl Agile {
    fn parse(xml: &[u8], cancel: &CancelToken) -> Result<Self> {
        let mut reader = XmlReader::new(xml, cancel.clone());
        let mut data = None;
        let mut found = None;
        let mut has_certificate = false;
        let mut uri_is_password = false;
        loop {
            match reader.next()? {
                Xml::Start(tag) | Xml::Empty(tag) => match tag.name.as_str() {
                    "keyData" => data = Some(cipher_params(&tag)?),
                    "keyEncryptor" => {
                        uri_is_password = tag.attr("uri").is_some_and(|u| u.ends_with("/password"));
                        has_certificate |= !uri_is_password;
                    }
                    "encryptedKey" if uri_is_password && found.is_none() => {
                        let spin_count: u32 =
                            attr(&tag, "spinCount")?.parse().map_err(|_| damaged("the spin count is not valid"))?;
                        if spin_count > MAX_SPIN_COUNT {
                            return Err(damaged("the spin count is above the format's limit"));
                        }
                        let salt_size =
                            attr(&tag, "saltSize")?.parse().map_err(|_| damaged("the salt size is not valid"))?;
                        found = Some(Self {
                            data: AgileParams::default(),
                            password: cipher_params(&tag)?,
                            spin_count,
                            salt_size,
                            encrypted_input: base64(attr(&tag, "encryptedVerifierHashInput")?)?,
                            encrypted_value: base64(attr(&tag, "encryptedVerifierHashValue")?)?,
                            encrypted_key: base64(attr(&tag, "encryptedKeyValue")?)?,
                        });
                    }
                    _ => {}
                },
                Xml::Eof => break,
                _ => {}
            }
        }
        let Some(mut agile) = found else {
            return Err(if has_certificate {
                unsupported("certificate-based encryption")
            } else {
                damaged("the password entry is missing")
            });
        };
        agile.data = data.ok_or_else(|| damaged("the key data is missing"))?;
        Ok(agile)
    }

    /// Derives the package key and checks the password against the verifier.
    fn unlock(&self, password: &[u8], cancel: &CancelToken) -> Result<Secret> {
        let params = &self.password;
        let hash = params.hash.ok_or_else(|| damaged("the hash is missing"))?;
        let spun = hash.iterate(&params.salt, password, self.spin_count, cancel)?;
        let key_bytes = params.key_bits / 8;
        let iv = fit(&params.salt, BLOCK);
        let open = |block: &[u8; 8], encrypted: &[u8]| -> Result<Secret> {
            let key = fit(&hash.hash(&[&spun, block]), key_bytes);
            let mut plain: Secret = Zeroizing::new(encrypted.to_vec());
            cbc_decrypt(&key, &iv, &mut plain)?;
            Ok(plain)
        };

        let input = open(&BLOCK_VERIFIER_INPUT, &self.encrypted_input)?;
        let value = open(&BLOCK_VERIFIER_VALUE, &self.encrypted_value)?;
        let actual = hash.hash(&[&input[..self.salt_size.min(input.len())]]);
        if value.len() < actual.len() {
            return Err(damaged("the verifier is too short"));
        }
        if actual[..] != value[..actual.len()] {
            return Err(wrong_password());
        }

        let mut key = open(&BLOCK_KEY_VALUE, &self.encrypted_key)?;
        let wanted = self.data.key_bits / 8;
        if key.len() < wanted {
            return Err(damaged("the key is too short"));
        }
        key.truncate(wanted);
        Ok(key)
    }

    /// Each 4096-byte segment is CBC on its own, with an IV derived from its number.
    fn decrypt_package(&self, key: &[u8], package: &mut Vec<u8>, cancel: &CancelToken) -> Result<()> {
        let declared = package_size(package)?;
        let hash = self.data.hash.ok_or_else(|| damaged("the hash is missing"))?;
        let whole = (package.len() - PACKAGE_HEADER) / BLOCK * BLOCK;
        let body = &mut package[PACKAGE_HEADER..PACKAGE_HEADER + whole];
        for (index, segment) in body.chunks_mut(AGILE_SEGMENT).enumerate() {
            if index % 256 == 0 {
                cancel.check()?;
            }
            let iv = fit(&hash.hash(&[&self.data.salt, &(index as u32).to_le_bytes()]), BLOCK);
            cbc_decrypt(key, &iv, segment)?;
        }
        finish_package(package, declared, whole)
    }
}

fn base64(encoded: &str) -> Result<Vec<u8>> {
    if encoded.len() > MAX_ENCODED_VALUE {
        return Err(damaged("a value is too long"));
    }
    let mut out = Vec::with_capacity(encoded.len() / 4 * 3);
    let (mut bits, mut pending) = (0u32, 0u32);
    for byte in encoded.bytes() {
        let value = match byte {
            b'A'..=b'Z' => byte - b'A',
            b'a'..=b'z' => byte - b'a' + 26,
            b'0'..=b'9' => byte - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            b'=' | b' ' | b'\t' | b'\r' | b'\n' => continue,
            _ => return Err(damaged("a value is not valid base64")),
        };
        bits = (bits << 6) | u32::from(value);
        pending += 6;
        if pending >= 8 {
            pending -= 8;
            out.push((bits >> pending) as u8);
        }
    }
    Ok(out)
}

#[cfg(test)]
pub(crate) mod testing {
    //! Encrypts fixtures with the same primitives. This shows the two halves
    //! agree with each other and with the spec as read; it is not a file saved
    //! by Office.
    use aes::cipher::{BlockCipherEncrypt, BlockModeEncrypt};

    use super::*;
    use crate::cfb::testing::cfb;

    pub fn encode(bytes: &[u8]) -> String {
        const TABLE: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        let mut out = String::new();
        for chunk in bytes.chunks(3) {
            let word = u32::from(chunk[0]) << 16
                | u32::from(chunk.get(1).copied().unwrap_or(0)) << 8
                | u32::from(chunk.get(2).copied().unwrap_or(0));
            for i in 0..4 {
                if i <= chunk.len() {
                    out.push(TABLE[(word >> (18 - i * 6)) as usize & 63] as char);
                } else {
                    out.push('=');
                }
            }
        }
        out
    }

    fn ecb_encrypt(key: &[u8], data: &mut [u8]) {
        fn run<C: BlockCipherEncrypt<BlockSize = U16> + KeyInit>(key: &[u8], data: &mut [u8]) {
            let (blocks, remainder) = data.as_chunks_mut::<BLOCK>();
            assert!(remainder.is_empty());
            C::new_from_slice(key).unwrap().encrypt_blocks(Block::<C>::cast_slice_from_core_mut(blocks));
        }
        match key.len() {
            16 => run::<Aes128>(key, data),
            24 => run::<Aes192>(key, data),
            _ => run::<Aes256>(key, data),
        }
    }

    fn cbc_encrypt(key: &[u8], iv: &[u8], data: &mut [u8]) {
        fn run<C: BlockCipherEncrypt<BlockSize = U16> + KeyInit>(key: &[u8], iv: &[u8], data: &mut [u8]) {
            let len = data.len();
            cbc::Encryptor::<C>::new_from_slices(key, iv).unwrap().encrypt_padded::<NoPadding>(data, len).unwrap();
        }
        match key.len() {
            16 => run::<Aes128>(key, iv, data),
            24 => run::<Aes192>(key, iv, data),
            _ => run::<Aes256>(key, iv, data),
        }
    }

    fn pad_blocks(mut data: Vec<u8>) -> Vec<u8> {
        data.resize(data.len().next_multiple_of(BLOCK), 0);
        data
    }

    fn utf16(password: &str) -> Vec<u8> {
        password.encode_utf16().flat_map(u16::to_le_bytes).collect()
    }

    fn package_with_size(plain: &[u8]) -> Vec<u8> {
        (plain.len() as u64).to_le_bytes().to_vec()
    }

    pub fn standard_info(password: &str, key_bits: u32, algorithm: u32) -> Vec<u8> {
        let salt = [7u8; 16];
        let key = standard_key(&salt, &utf16(password), key_bits as usize / 8, &CancelToken::default()).unwrap();
        let mut verifier = [9u8; 16];
        let mut hash = pad_blocks(HashKind::Sha1.hash(&[&verifier]).to_vec());
        ecb_encrypt(&key, &mut verifier);
        ecb_encrypt(&key, &mut hash);
        standard_info_raw(FLAG_CRYPTOAPI | FLAG_AES, algorithm, key_bits, &salt, &verifier, &hash)
    }

    pub fn standard_info_raw(
        flags: u32,
        algorithm: u32,
        key_bits: u32,
        salt: &[u8],
        verifier: &[u8],
        hash: &[u8],
    ) -> Vec<u8> {
        let mut header = Vec::new();
        header.extend_from_slice(&flags.to_le_bytes());
        header.extend_from_slice(&0u32.to_le_bytes());
        header.extend_from_slice(&algorithm.to_le_bytes());
        header.extend_from_slice(&ALG_SHA1.to_le_bytes());
        header.extend_from_slice(&key_bits.to_le_bytes());
        header.extend_from_slice(&0x18u32.to_le_bytes());
        header.extend_from_slice(&[0; 8]);
        header.extend("Provider\0".encode_utf16().flat_map(u16::to_le_bytes));

        let mut info = 4u16.to_le_bytes().to_vec();
        info.extend_from_slice(&2u16.to_le_bytes());
        info.extend_from_slice(&flags.to_le_bytes());
        info.extend_from_slice(&(header.len() as u32).to_le_bytes());
        info.extend_from_slice(&header);
        info.extend_from_slice(&16u32.to_le_bytes());
        info.extend_from_slice(salt);
        info.extend_from_slice(verifier);
        info.extend_from_slice(&20u32.to_le_bytes());
        info.extend_from_slice(hash);
        info
    }

    /// A Standard-encrypted compound file holding `plain`.
    pub fn standard_file(plain: &[u8], password: &str, key_bits: u32) -> Vec<u8> {
        let algorithm = match key_bits {
            128 => ALG_AES128,
            192 => ALG_AES192,
            _ => ALG_AES256,
        };
        let info = standard_info(password, key_bits, algorithm);
        let key = standard_key(&[7u8; 16], &utf16(password), key_bits as usize / 8, &CancelToken::default()).unwrap();
        let mut body = pad_blocks(plain.to_vec());
        ecb_encrypt(&key, &mut body);
        let mut package = package_with_size(plain);
        package.extend_from_slice(&body);
        cfb(&[(ENCRYPTION_INFO, &info), (ENCRYPTED_PACKAGE, &package)])
    }

    pub struct AgileSpec {
        pub hash: &'static str,
        pub key_bits: usize,
        pub spin: u32,
        pub cipher: &'static str,
        pub chaining: &'static str,
    }

    impl Default for AgileSpec {
        fn default() -> Self {
            Self { hash: "SHA512", key_bits: 256, spin: 1000, cipher: "AES", chaining: "ChainingModeCBC" }
        }
    }

    fn agile_info_and_key(password: &str, spec: &AgileSpec) -> (Vec<u8>, Vec<u8>, Vec<u8>, HashKind) {
        let hash = HashKind::from_name(spec.hash).unwrap_or(HashKind::Sha512);
        let (key_salt, data_salt) = ([0x11u8; 16], [0x22u8; 16]);
        let key_bytes = spec.key_bits / 8;
        let package_key: Vec<u8> = (0..key_bytes as u8).map(|b| b.wrapping_mul(3)).collect();
        let spun = hash.iterate(&key_salt, &utf16(password), spec.spin, &CancelToken::default()).unwrap();
        let iv = fit(&key_salt, BLOCK);
        let seal = |block: &[u8; 8], plain: &[u8]| {
            // At least a real AES size, so a fixture can declare a bogus `keyBits`.
            let key = fit(&hash.hash(&[&spun, block]), key_bytes.max(16));
            let mut data = pad_blocks(plain.to_vec());
            cbc_encrypt(&key, &iv, &mut data);
            data
        };
        let verifier = [0x5Au8; 16];
        let encrypted_input = seal(&BLOCK_VERIFIER_INPUT, &verifier);
        let encrypted_value = seal(&BLOCK_VERIFIER_VALUE, &hash.hash(&[&verifier]));
        let encrypted_key = seal(&BLOCK_KEY_VALUE, &package_key);

        let shared = format!(
            r#"blockSize="16" keyBits="{}" hashSize="{}" cipherAlgorithm="{}" cipherChaining="{}" hashAlgorithm="{}""#,
            spec.key_bits,
            hash.hash(&[]).len(),
            spec.cipher,
            spec.chaining,
            spec.hash
        );
        let xml = format!(
            r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<encryption xmlns="http://schemas.microsoft.com/office/2006/encryption" xmlns:p="http://schemas.microsoft.com/office/2006/keyEncryptor/password">
<keyData saltSize="16" {shared} saltValue="{}"/>
<dataIntegrity encryptedHmacKey="AAAA" encryptedHmacValue="AAAA"/>
<keyEncryptors><keyEncryptor uri="http://schemas.microsoft.com/office/2006/keyEncryptor/password"><p:encryptedKey spinCount="{}" saltSize="16" {shared} saltValue="{}" encryptedVerifierHashInput="{}" encryptedVerifierHashValue="{}" encryptedKeyValue="{}"/></keyEncryptor></keyEncryptors>
</encryption>"#,
            encode(&data_salt),
            spec.spin,
            encode(&key_salt),
            encode(&encrypted_input),
            encode(&encrypted_value),
            encode(&encrypted_key),
        );
        let mut info = 4u16.to_le_bytes().to_vec();
        info.extend_from_slice(&4u16.to_le_bytes());
        info.extend_from_slice(&0x40u32.to_le_bytes());
        info.extend_from_slice(xml.as_bytes());
        (info, package_key, data_salt.to_vec(), hash)
    }

    /// An Agile-encrypted compound file holding `plain`.
    pub fn agile_file(plain: &[u8], password: &str, spec: &AgileSpec) -> Vec<u8> {
        let (info, key, salt, hash) = agile_info_and_key(password, spec);
        let mut body = pad_blocks(plain.to_vec());
        for (index, segment) in body.chunks_mut(AGILE_SEGMENT).enumerate() {
            let iv = fit(&hash.hash(&[&salt, &(index as u32).to_le_bytes()]), BLOCK);
            cbc_encrypt(&key, &iv, segment);
        }
        let mut package = package_with_size(plain);
        package.extend_from_slice(&body);
        cfb(&[(ENCRYPTION_INFO, &info), (ENCRYPTED_PACKAGE, &package)])
    }

    pub fn agile_info(password: &str, spec: &AgileSpec) -> Vec<u8> {
        agile_info_and_key(password, spec).0
    }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use document_core::{Block, FormatId, Limits};

    use super::testing::*;
    use super::*;
    use crate::cfb::testing::cfb;
    use crate::testing::texts;
    use crate::zip::testing::zip;

    const PASSWORD: &str = "pässwörd 🔒";

    fn docx() -> Vec<u8> {
        zip(&[
            (
                "_rels/.rels",
                br#"<Relationships><Relationship Id="r1" Type="http://x/officeDocument" Target="word/document.xml"/></Relationships>"#,
            ),
            (
                "word/document.xml",
                br#"<w:document xmlns:w="w"><w:body><w:p><w:r><w:t>Unlocked text</w:t></w:r></w:p></w:body></w:document>"#,
            ),
        ])
    }

    fn open(file: Vec<u8>, password: Option<&str>) -> Result<(FormatId, Vec<Block>)> {
        let engine = crate::engine(Limits::default());
        let mut session = engine.open_protected(Cursor::new(file), None, password)?;
        let format = session.info().format;
        let mut blocks = Vec::new();
        while let Some(batch) = session.next_batch()? {
            blocks.extend(batch.blocks);
        }
        Ok((format, blocks))
    }

    fn assert_unlocks(file: Vec<u8>) {
        let (format, blocks) = open(file, Some(PASSWORD)).unwrap();
        assert_eq!(format, FormatId::Docx);
        assert_eq!(texts(&blocks), ["P Unlocked text"]);
    }

    fn kind_of(file: Vec<u8>, password: Option<&str>) -> ErrorKind {
        open(file, password).expect_err("should fail").kind
    }

    #[test]
    fn agile_unlocks_with_each_supported_hash_and_key_size() {
        for (hash, key_bits) in
            [("SHA512", 256), ("SHA1", 128), ("SHA-1", 128), ("SHA256", 256), ("SHA384", 192), ("SHA512", 128)]
        {
            assert_unlocks(agile_file(&docx(), PASSWORD, &AgileSpec { hash, key_bits, ..AgileSpec::default() }));
        }
    }

    #[test]
    fn agile_handles_packages_longer_than_one_segment() {
        // Stored entries, so the package is long and random access must work.
        let padding = vec![b'z'; 3 * AGILE_SEGMENT + 123];
        let plain = zip(&[
            (
                "_rels/.rels",
                br#"<Relationships><Relationship Id="r1" Type="http://x/officeDocument" Target="word/document.xml"/></Relationships>"#,
            ),
            (
                "word/document.xml",
                br#"<w:document xmlns:w="w"><w:body><w:p><w:r><w:t>Unlocked text</w:t></w:r></w:p></w:body></w:document>"#,
            ),
            ("padding.bin", &padding),
        ]);
        assert_unlocks(agile_file(&plain, PASSWORD, &AgileSpec::default()));
    }

    #[test]
    fn standard_unlocks_with_each_key_size() {
        for key_bits in [128, 192, 256] {
            assert_unlocks(standard_file(&docx(), PASSWORD, key_bits));
        }
    }

    #[test]
    fn a_missing_password_is_typed_before_anything_is_decrypted() {
        assert_eq!(kind_of(agile_file(&docx(), PASSWORD, &AgileSpec::default()), None), ErrorKind::PasswordRequired);
        assert_eq!(kind_of(standard_file(&docx(), PASSWORD, 128), None), ErrorKind::PasswordRequired);
    }

    #[test]
    fn a_wrong_password_is_typed() {
        for wrong in ["wrong", "", "PÄSSWÖRD 🔒"] {
            let agile = agile_file(&docx(), PASSWORD, &AgileSpec::default());
            assert_eq!(kind_of(agile, Some(wrong)), ErrorKind::WrongPassword);
            assert_eq!(kind_of(standard_file(&docx(), PASSWORD, 128), Some(wrong)), ErrorKind::WrongPassword);
        }
    }

    #[test]
    fn the_password_never_reaches_an_error_message() {
        let secret = "correct horse battery staple";
        let agile = agile_file(&docx(), PASSWORD, &AgileSpec::default());
        let message = open(agile, Some(secret)).err().unwrap().to_string();
        assert!(!message.contains(secret), "{message}");
    }

    #[test]
    fn rc4_and_other_schemes_are_unsupported_not_cracked() {
        let package = [0u8; 8 + 16];
        let file = |info: &[u8]| cfb(&[(ENCRYPTION_INFO, info), (ENCRYPTED_PACKAGE, &package)]);
        let version = |major: u16, minor: u16| [major.to_le_bytes(), minor.to_le_bytes()].concat();
        let (salt, verifier, hash) = ([0u8; 16], [0u8; 16], [0u8; 32]);

        for info in [
            // CryptoAPI RC4, by algorithm and by a missing AES flag.
            standard_info_raw(FLAG_CRYPTOAPI, ALG_RC4, 128, &salt, &verifier, &hash),
            standard_info_raw(FLAG_CRYPTOAPI | FLAG_AES, ALG_RC4, 128, &salt, &verifier, &hash),
            // Externally managed keys.
            standard_info_raw(FLAG_EXTERNAL | FLAG_CRYPTOAPI | FLAG_AES, ALG_AES128, 128, &salt, &verifier, &hash),
            // An AES key size that does not exist.
            standard_info_raw(FLAG_CRYPTOAPI | FLAG_AES, ALG_AES128, 64, &salt, &verifier, &hash),
            version(1, 1),
            version(3, 3),
            version(4, 3),
            version(9, 9),
        ] {
            // Unsupported even with no password: asking for one could not help.
            assert_eq!(kind_of(file(&info), None), ErrorKind::UnsupportedEncryption);
        }

        for spec in [
            AgileSpec { cipher: "RC2", ..AgileSpec::default() },
            AgileSpec { chaining: "ChainingModeCFB", ..AgileSpec::default() },
            AgileSpec { hash: "MD5", ..AgileSpec::default() },
            AgileSpec { key_bits: 64, ..AgileSpec::default() },
        ] {
            assert_eq!(kind_of(file(&agile_info(PASSWORD, &spec)), None), ErrorKind::UnsupportedEncryption);
        }

        // Certificate-only Agile.
        let mut info = version(4, 4);
        info.extend_from_slice(&[0x40, 0, 0, 0]);
        info.extend_from_slice(
            br#"<encryption><keyEncryptors><keyEncryptor uri="http://schemas.microsoft.com/office/2006/keyEncryptor/certificate"/></keyEncryptors></encryption>"#,
        );
        assert_eq!(kind_of(file(&info), None), ErrorKind::UnsupportedEncryption);

        // Rights management, with no EncryptedPackage at all.
        let rights = cfb(&[("\u{6}DataSpaces", b"x")]);
        assert_eq!(kind_of(rights, None), ErrorKind::UnsupportedEncryption);
    }

    #[test]
    fn hostile_encryption_data_fails_cleanly() {
        let package = [0u8; 8 + 16];
        let file = |info: &[u8]| cfb(&[(ENCRYPTION_INFO, info), (ENCRYPTED_PACKAGE, &package)]);
        let agile_xml = |body: &str| {
            let mut info = vec![4, 0, 4, 0, 0x40, 0, 0, 0];
            info.extend_from_slice(body.as_bytes());
            info
        };
        let valid = String::from_utf8_lossy(&agile_info(PASSWORD, &AgileSpec::default())[8..]).into_owned();
        let malformed: Vec<Vec<u8>> = vec![
            // Too short for a version; a Standard header cut off.
            vec![4],
            vec![4, 0, 2, 0, 0x24, 0, 0, 0],
            // Empty and broken XML, and a missing piece.
            agile_xml(""),
            agile_xml("<encryption"),
            agile_xml(&valid.replace("<keyData ", "<other ")),
            agile_xml(&valid.replace("saltValue=", "pepperValue=")),
            // A spin count over the format's limit would burn the CPU.
            agile_xml(&valid.replace("spinCount=\"1000\"", "spinCount=\"4000000000\"")),
            agile_xml(&valid.replace("spinCount=\"1000\"", "spinCount=\"nope\"")),
            agile_xml(&valid.replace("blockSize=\"16\"", "blockSize=\"8\"")),
            agile_xml(&valid.replace("encryptedKeyValue=\"", "encryptedKeyValue=\"!!")),
        ];
        for info in malformed {
            assert_eq!(
                kind_of(file(&info), Some(PASSWORD)),
                ErrorKind::Malformed,
                "{}",
                String::from_utf8_lossy(&info)
            );
        }

        // Verifiers and keys of the wrong size fail on block size, not on a panic.
        let info = agile_xml(&valid.replace("encryptedVerifierHashInput=\"", "encryptedVerifierHashInput=\"QUJD"));
        assert_eq!(kind_of(file(&info), Some(PASSWORD)), ErrorKind::Malformed);
    }

    #[test]
    fn a_package_shorter_than_declared_is_damaged() {
        let spec = AgileSpec::default();
        let info = agile_info(PASSWORD, &spec);
        // No size prefix at all, then a size far above the content.
        for package in [vec![0u8; 4], [u64::MAX.to_le_bytes().to_vec(), vec![0; 32]].concat()] {
            let file = cfb(&[(ENCRYPTION_INFO, &info), (ENCRYPTED_PACKAGE, &package)]);
            assert_eq!(kind_of(file, Some(PASSWORD)), ErrorKind::Malformed);
        }
        let standard = standard_info(PASSWORD, 128, ALG_AES128);
        let package = [1000u64.to_le_bytes().to_vec(), vec![0; 32]].concat();
        let file = cfb(&[(ENCRYPTION_INFO, &standard), (ENCRYPTED_PACKAGE, &package)]);
        assert_eq!(kind_of(file, Some(PASSWORD)), ErrorKind::Malformed);
    }

    #[test]
    fn plaintext_that_is_not_a_package_is_damaged() {
        let file = agile_file(b"this decrypts fine but is no zip", PASSWORD, &AgileSpec::default());
        assert_eq!(kind_of(file, Some(PASSWORD)), ErrorKind::Malformed);
    }

    #[test]
    fn base64_decodes_and_rejects() {
        assert_eq!(base64("aGVsbG8=").unwrap(), b"hello");
        assert_eq!(base64(&encode(&[0, 255, 1, 2])).unwrap(), [0, 255, 1, 2]);
        assert_eq!(base64("a G V s\nbG8=").unwrap(), b"hello");
        assert!(base64("a$b").is_err());
        assert!(base64(&"A".repeat(MAX_ENCODED_VALUE + 1)).is_err());
    }

    /// Fixed vectors for the password derivation: computed once by hand from the
    /// spec's steps with Python's `hashlib`, independently of this crate.
    #[test]
    fn key_derivation_matches_an_independent_computation() {
        let cancel = CancelToken::default();
        let password: Vec<u8> = "Password1234_".encode_utf16().flat_map(u16::to_le_bytes).collect();
        let salt: Vec<u8> = (0u8..16).collect();
        let hex = |bytes: &[u8]| bytes.iter().map(|b| format!("{b:02x}")).collect::<String>();
        assert_eq!(hex(&standard_key(&salt, &password, 16, &cancel).unwrap()), STANDARD_KEY_VECTOR);
        let spun = HashKind::Sha512.iterate(&salt, &password, 100_000, &cancel).unwrap();
        let block_key = HashKind::Sha512.hash(&[&spun, &BLOCK_KEY_VALUE]);
        assert_eq!(hex(&fit(&block_key, 32)), AGILE_KEY_VECTOR);
    }

    const STANDARD_KEY_VECTOR: &str = "340fc93a8e2154db8304d055df81cebc";
    const AGILE_KEY_VECTOR: &str = "7a8b2091cd76dd40577bbc7b165de0985a9de0e0aded58ce94fc4b35294c0d0e";
}
