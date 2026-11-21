//! The PDF standard security handler: reading revisions 2, 3, 4 and 6, and
//! writing revision 6 (AES-256, ISO 32000-2, 7.6.4).
//!
//! Only a password the user supplies unlocks anything. Each password is checked
//! the way the handler specifies, and what it unlocked (user or owner) is kept,
//! because only the owner password lifts the permission limits.

use aes::cipher::block_padding::NoPadding;
use aes::cipher::{BlockDecryptMut, BlockEncryptMut, KeyIvInit, StreamCipher, consts};
use aes::{Aes128, Aes256};
use document_core::{EngineError, ErrorKind, Result};
use md5::Md5;
use rc4::{KeyInit, Rc4};
use sha2::{Digest, Sha256, Sha384, Sha512};
use zeroize::{Zeroize, Zeroizing};

use crate::pdf_syntax::{Dict, Obj};

const AES_BLOCK: usize = 16;
/// Revision 6 passwords are cut to this many bytes of UTF-8.
pub(crate) const MAX_R6_PASSWORD: usize = 127;
/// The padding string of Algorithm 2.
const PAD: [u8; 32] = [
    0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08, 0x2E, 0x2E, 0x00,
    0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A,
];

const PRINT: u32 = 1 << 2;
const MODIFY: u32 = 1 << 3;
const COPY: u32 = 1 << 4;
const ANNOTATE: u32 = 1 << 5;
const FILL_FORMS: u32 = 1 << 8;
const EXTRACT: u32 = 1 << 9;
const ASSEMBLE: u32 = 1 << 10;
const PRINT_HIGH_QUALITY: u32 = 1 << 11;
/// Everything allowed, the reserved bits set as the specification asks.
const ALL_ALLOWED: u32 = 0xFFFF_FFFC;

fn unsupported(what: &str) -> EngineError {
    EngineError::new(ErrorKind::UnsupportedEncryption, format!("this PDF uses {what}, which isn't supported"))
}

fn damaged() -> EngineError {
    EngineError::malformed("the PDF's encryption data is damaged")
}

fn internal() -> EngineError {
    EngineError::new(ErrorKind::Internal, "encryption failed")
}

/// Random bytes from the operating system's secure source.
pub(crate) fn secure_random(out: &mut [u8]) -> Result<()> {
    getrandom::fill(out).map_err(|_| EngineError::new(ErrorKind::Internal, "the system's random source failed"))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Method {
    Identity,
    Rc4,
    AesV2,
    AesV3,
}

impl Method {
    fn from_cfm(name: Option<&[u8]>) -> Result<Self> {
        match name {
            None | Some(b"None") => Ok(Self::Identity),
            Some(b"V2") => Ok(Self::Rc4),
            Some(b"AESV2") => Ok(Self::AesV2),
            Some(b"AESV3") => Ok(Self::AesV3),
            Some(_) => Err(unsupported("an unknown crypt filter")),
        }
    }
}

/// The standard security handler algorithms this module can read and write.
/// `Rc4*` are weak and are written only when a file already uses them and the
/// user chose "same as original".
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CryptAlgorithm {
    /// RC4, 40-bit key (V 1, R 2).
    Rc4Bits40,
    /// RC4, 128-bit key (V 2, R 3).
    Rc4Bits128,
    /// AESV2: AES-128 CBC per object (V 4, R 4).
    AesBits128,
    /// AESV3: AES-256 CBC with the file key (V 5, R 6). New protection uses this.
    AesBits256,
}

impl CryptAlgorithm {
    pub fn is_weak(self) -> bool {
        matches!(self, Self::Rc4Bits40 | Self::Rc4Bits128)
    }

    /// The lowest header version that can carry this algorithm.
    pub fn min_version(self) -> (u8, u8) {
        match self {
            Self::Rc4Bits40 | Self::Rc4Bits128 => (1, 4),
            Self::AesBits128 => (1, 6),
            Self::AesBits256 => (2, 0),
        }
    }

    /// Length of the file key in bytes.
    fn key_len(self) -> usize {
        match self {
            Self::Rc4Bits40 => 5,
            Self::Rc4Bits128 | Self::AesBits128 => 16,
            Self::AesBits256 => 32,
        }
    }
}

/// The document's limits for someone without the owner password.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Permissions {
    bits: u32,
    revision: i64,
}

impl Permissions {
    /// The `/P` bits exactly as stored.
    pub(crate) fn bits(self) -> u32 {
        self.bits
    }

    /// Page assembly: bit 11 on revision 3 and later, and always under bit 4.
    pub(crate) fn may_assemble(self) -> bool {
        self.bits & MODIFY != 0 || (self.revision >= 3 && self.bits & ASSEMBLE != 0)
    }

    pub(crate) fn may_modify(self) -> bool {
        self.bits & MODIFY != 0
    }

    /// Printing at all (bit 3). A watermarked PDF often allows it while forbidding changes.
    pub(crate) fn may_print(self) -> bool {
        self.bits & PRINT != 0
    }

    /// What the file forbids, in words, for a message.
    pub(crate) fn denied(self) -> Vec<&'static str> {
        let mut denied = Vec::new();
        let mut check = |bit: u32, label: &'static str| {
            if self.bits & bit == 0 {
                denied.push(label);
            }
        };
        check(PRINT, "printing");
        check(MODIFY, "changing the content");
        check(COPY, "copying");
        check(ANNOTATE, "adding comments");
        // These bits don't exist before revision 3.
        if self.revision >= 3 {
            check(FILL_FORMS, "filling forms");
            check(EXTRACT, "extracting content for accessibility");
            check(ASSEMBLE, "rearranging pages");
            check(PRINT_HIGH_QUALITY, "printing in high quality");
        }
        denied
    }
}

/// An unlocked document's decryption state. Holds the file key: wiped on drop.
pub(crate) struct Decryptor {
    key: Zeroizing<Vec<u8>>,
    streams: Method,
    strings: Method,
    embedded_files: Method,
    named: Vec<(Vec<u8>, Method)>,
    encrypt_metadata: bool,
    /// The encryption dictionary itself is never encrypted.
    encrypt_object: Option<u32>,
    algorithm: CryptAlgorithm,
    pub(crate) is_owner: bool,
    pub(crate) permissions: Permissions,
}

fn bytes_entry<'a>(dict: &'a Dict, key: &str) -> Option<&'a [u8]> {
    match dict.get(key) {
        Some(Obj::Str(bytes)) => Some(bytes),
        _ => None,
    }
}

/// Which candidate byte forms a password has before revision 6: as typed in
/// UTF-8, and in the single-byte encoding those readers expect where it fits.
fn legacy_candidates(password: &str) -> Vec<Zeroizing<Vec<u8>>> {
    let mut candidates = vec![Zeroizing::new(password.as_bytes().to_vec())];
    if !password.is_ascii() {
        let latin: Option<Vec<u8>> = password.chars().map(|c| u8::try_from(u32::from(c)).ok()).collect();
        if let Some(latin) = latin {
            candidates.push(Zeroizing::new(latin));
        }
    }
    candidates
}

impl Decryptor {
    /// Checks `password` against `dict` (all values direct). `None` tries only
    /// the empty user password, which many restricted files use: it never counts
    /// as the owner's. Otherwise the password is tried as the owner's first, so
    /// a file with one password for both roles grants full rights.
    pub(crate) fn unlock(dict: &Dict, id0: &[u8], encrypt_object: Option<u32>, password: Option<&str>) -> Result<Self> {
        if dict.get("Filter").and_then(Obj::as_name) != Some(b"Standard") {
            return Err(unsupported("a certificate or custom security handler"));
        }
        let version = dict.get("V").and_then(Obj::as_i64).unwrap_or(0);
        let revision = dict.get("R").and_then(Obj::as_i64).ok_or_else(damaged)?;
        let (streams, strings, embedded_files, named) = methods(dict, version)?;
        let encrypt_metadata = !matches!(dict.get("EncryptMetadata"), Some(Obj::Bool(false)));
        let bits = dict.get("P").and_then(Obj::as_i64).ok_or_else(damaged)? as u32;
        let permissions = Permissions { bits, revision };
        let password = password.filter(|p| !p.is_empty());

        let mut algorithm = CryptAlgorithm::AesBits256;
        let unlocked = match (version, revision) {
            (1, 2) | (2, 3) | (4, 4) => {
                let length = legacy_key_length(dict, version)?;
                algorithm = if version == 4 && streams == Method::AesV2 {
                    CryptAlgorithm::AesBits128
                } else if length == 5 {
                    CryptAlgorithm::Rc4Bits40
                } else {
                    CryptAlgorithm::Rc4Bits128
                };
                let header = LegacyHeader {
                    o: bytes_entry(dict, "O").filter(|o| o.len() >= 32).ok_or_else(damaged)?,
                    u: bytes_entry(dict, "U")
                        .filter(|u| u.len() >= if revision == 2 { 32 } else { 16 })
                        .ok_or_else(damaged)?,
                    bits,
                    id0,
                    revision,
                    length,
                    encrypt_metadata,
                };
                unlock_legacy(&header, password)
            }
            (5, 6) => unlock_r6(dict, bits, encrypt_metadata, password)?,
            (5, 5) => return Err(unsupported("an early AES-256 revision")),
            _ => return Err(unsupported("an unknown encryption version")),
        };
        let Some((key, is_owner)) = unlocked else {
            return Err(match password {
                None => EngineError::new(ErrorKind::PasswordRequired, "this PDF needs a password"),
                Some(_) => EngineError::new(ErrorKind::WrongPassword, "that password doesn't open this PDF"),
            });
        };
        Ok(Self {
            key,
            streams,
            strings,
            embedded_files,
            named,
            encrypt_metadata,
            encrypt_object,
            algorithm,
            is_owner,
            permissions,
        })
    }

    /// The algorithm the file was written with; a writer keeps it when asked to.
    pub(crate) fn algorithm(&self) -> CryptAlgorithm {
        self.algorithm
    }

    pub(crate) fn metadata_encrypted(&self) -> bool {
        self.encrypt_metadata
    }

    fn decrypt(&self, method: Method, number: u32, generation: u16, data: &[u8]) -> Result<Vec<u8>> {
        match method {
            Method::Identity => Ok(data.to_vec()),
            Method::Rc4 => {
                let mut out = data.to_vec();
                rc4(&object_key(&self.key, method, number, generation), &mut out)?;
                Ok(out)
            }
            Method::AesV2 | Method::AesV3 => {
                aes_decrypt_data(&object_key(&self.key, method, number, generation), data)
            }
        }
    }

    /// Decrypts every string in an object read from outside any object stream.
    pub(crate) fn decrypt_strings(&self, number: u32, generation: u16, value: &mut Obj) -> Result<()> {
        if self.encrypt_object == Some(number) || self.strings == Method::Identity {
            return Ok(());
        }
        match value {
            Obj::Str(bytes) => *bytes = self.decrypt(self.strings, number, generation, bytes)?,
            Obj::Array(items) => {
                for item in items {
                    self.decrypt_strings(number, generation, item)?;
                }
            }
            Obj::Dict(dict) => {
                for (_, item) in &mut dict.0 {
                    self.decrypt_strings(number, generation, item)?;
                }
            }
            _ => {}
        }
        Ok(())
    }

    fn stream_method(&self, dict: &Dict) -> Method {
        if let Some(name) = crypt_filter_name(dict) {
            return self.named.iter().find(|(n, _)| *n == name).map_or(Method::Identity, |(_, method)| *method);
        }
        match dict.get("Type").and_then(Obj::as_name) {
            Some(b"XRef") => Method::Identity,
            Some(b"Metadata") if !self.encrypt_metadata => Method::Identity,
            Some(b"EmbeddedFile") => self.embedded_files,
            _ => self.streams,
        }
    }

    /// True if this stream's bytes are stored as they are.
    pub(crate) fn stream_is_plain(&self, dict: &Dict) -> bool {
        self.stream_method(dict) == Method::Identity
    }

    pub(crate) fn decrypt_stream(&self, number: u32, generation: u16, dict: &Dict, raw: &[u8]) -> Result<Vec<u8>> {
        self.decrypt(self.stream_method(dict), number, generation, raw)
    }
}

/// The name of a stream's own `/Crypt` filter (`Identity` if unnamed).
fn crypt_filter_name(dict: &Dict) -> Option<Vec<u8>> {
    let parms = dict.get("DecodeParms").or_else(|| dict.get("DP"));
    let name_in = |parms: Option<&Obj>| match parms {
        Some(Obj::Dict(parms)) => parms.get("Name").and_then(Obj::as_name).map(<[u8]>::to_vec),
        _ => None,
    };
    let identity = || b"Identity".to_vec();
    match dict.get("Filter") {
        Some(Obj::Name(name)) if name == b"Crypt" => Some(name_in(parms).unwrap_or_else(identity)),
        Some(Obj::Array(filters)) => {
            let at = filters.iter().position(|f| f.as_name() == Some(b"Crypt"))?;
            let parms = match parms {
                Some(Obj::Array(all)) => all.get(at),
                _ => None,
            };
            Some(name_in(parms).unwrap_or_else(identity))
        }
        _ => None,
    }
}

type Methods = (Method, Method, Method, Vec<(Vec<u8>, Method)>);

fn methods(dict: &Dict, version: i64) -> Result<Methods> {
    if version < 4 {
        return Ok((Method::Rc4, Method::Rc4, Method::Rc4, vec![(b"Identity".to_vec(), Method::Identity)]));
    }
    let mut named = vec![(b"Identity".to_vec(), Method::Identity)];
    if let Some(Obj::Dict(filters)) = dict.get("CF") {
        for (name, filter) in &filters.0 {
            let Obj::Dict(filter) = filter else { return Err(damaged()) };
            named.push((name.clone(), Method::from_cfm(filter.get("CFM").and_then(Obj::as_name))?));
        }
    }
    let pick = |key: &str| -> Result<Method> {
        let name = dict.get(key).and_then(Obj::as_name).unwrap_or(b"Identity");
        named.iter().rev().find(|(n, _)| n == name).map(|(_, method)| *method).ok_or_else(damaged)
    };
    let streams = pick("StmF")?;
    let strings = pick("StrF")?;
    let embedded = if dict.get("EFF").is_some() { pick("EFF")? } else { streams };
    Ok((streams, strings, embedded, named))
}

/// The file key's length in bytes for revisions before 6.
fn legacy_key_length(dict: &Dict, version: i64) -> Result<usize> {
    let declared = dict.get("Length").and_then(Obj::as_i64);
    let bits = match version {
        1 => 40,
        2 => declared.unwrap_or(40),
        _ => {
            // Writers put this in the dictionary or the filter, in bits or bytes.
            let in_filter = || {
                let Some(Obj::Dict(filters)) = dict.get("CF") else { return None };
                let Some(Obj::Dict(filter)) = filters.get("StdCF") else { return None };
                filter.get("Length").and_then(Obj::as_i64)
            };
            let length = declared.or_else(in_filter).unwrap_or(128);
            if length < 40 { length * 8 } else { length }
        }
    };
    if !(40..=128).contains(&bits) || bits % 8 != 0 {
        return Err(damaged());
    }
    Ok(bits as usize / 8)
}

struct LegacyHeader<'a> {
    o: &'a [u8],
    u: &'a [u8],
    bits: u32,
    id0: &'a [u8],
    revision: i64,
    length: usize,
    encrypt_metadata: bool,
}

fn pad_password(password: &[u8]) -> Zeroizing<[u8; 32]> {
    let mut padded = Zeroizing::new(PAD);
    let used = password.len().min(32);
    padded[..used].copy_from_slice(&password[..used]);
    padded[used..].copy_from_slice(&PAD[..32 - used]);
    padded
}

/// Algorithm 2: the file key from a padded user password.
fn legacy_file_key(header: &LegacyHeader<'_>, padded: &[u8; 32]) -> Zeroizing<Vec<u8>> {
    let mut hash = Md5::new();
    hash.update(padded);
    hash.update(&header.o[..32]);
    hash.update(header.bits.to_le_bytes());
    hash.update(header.id0);
    if header.revision >= 4 && !header.encrypt_metadata {
        hash.update([0xFF; 4]);
    }
    let mut digest: [u8; 16] = hash.finalize().into();
    if header.revision >= 3 {
        for _ in 0..50 {
            digest = Md5::digest(&digest[..header.length]).into();
        }
    }
    let key = Zeroizing::new(digest[..header.length].to_vec());
    digest.zeroize();
    key
}

/// Algorithms 4 to 6: does this key produce the stored `/U`?
fn legacy_user_matches(header: &LegacyHeader<'_>, key: &[u8]) -> Result<bool> {
    if header.revision == 2 {
        let mut check = PAD;
        rc4(key, &mut check)?;
        return Ok(check[..] == header.u[..32]);
    }
    let mut hash = Md5::new();
    hash.update(PAD);
    hash.update(header.id0);
    let mut check: [u8; 16] = hash.finalize().into();
    rc4(key, &mut check)?;
    for round in 1..=19u8 {
        let round_key: Zeroizing<Vec<u8>> = Zeroizing::new(key.iter().map(|b| b ^ round).collect());
        rc4(&round_key, &mut check)?;
    }
    Ok(check[..] == header.u[..16])
}

/// Algorithm 7: the owner password decrypts `/O` to the padded user password.
fn legacy_user_from_owner(header: &LegacyHeader<'_>, owner: &[u8]) -> Result<Zeroizing<[u8; 32]>> {
    let mut digest: [u8; 16] = Md5::digest(*pad_password(owner)).into();
    if header.revision >= 3 {
        // Algorithm 3 hashes the first `n` bytes each time, not the whole digest.
        for _ in 0..50 {
            digest = Md5::digest(&digest[..header.length]).into();
        }
    }
    let key = Zeroizing::new(digest[..header.length].to_vec());
    digest.zeroize();
    let mut user = Zeroizing::new([0u8; 32]);
    user.copy_from_slice(&header.o[..32]);
    if header.revision == 2 {
        rc4(&key, &mut *user)?;
    } else {
        for round in (0..=19u8).rev() {
            let round_key: Zeroizing<Vec<u8>> = Zeroizing::new(key.iter().map(|b| b ^ round).collect());
            rc4(&round_key, &mut *user)?;
        }
    }
    Ok(user)
}

fn unlock_legacy(header: &LegacyHeader<'_>, password: Option<&str>) -> Option<(Zeroizing<Vec<u8>>, bool)> {
    let try_user = |padded: &[u8; 32]| -> Option<Zeroizing<Vec<u8>>> {
        let key = legacy_file_key(header, padded);
        legacy_user_matches(header, &key).ok()?.then_some(key)
    };
    let Some(password) = password else {
        return try_user(&pad_password(b"")).map(|key| (key, false));
    };
    let candidates = legacy_candidates(password);
    for candidate in &candidates {
        if let Ok(user) = legacy_user_from_owner(header, candidate) {
            if let Some(key) = try_user(&user) {
                return Some((key, true));
            }
        }
    }
    candidates.iter().find_map(|candidate| try_user(&pad_password(candidate)).map(|key| (key, false)))
}

/// Algorithm 2.B, the revision 6 hash.
fn hash_r6(password: &[u8], salt: &[u8], extra: &[u8]) -> Result<Zeroizing<[u8; 32]>> {
    let mut first = Sha256::new();
    first.update(password);
    first.update(salt);
    first.update(extra);
    let mut k: Zeroizing<Vec<u8>> = Zeroizing::new(first.finalize().to_vec());
    let mut round = 0u32;
    loop {
        let mut unit = Zeroizing::new(Vec::with_capacity(password.len() + k.len() + extra.len()));
        unit.extend_from_slice(password);
        unit.extend_from_slice(&k);
        unit.extend_from_slice(extra);
        let mut e = Zeroizing::new(Vec::with_capacity(unit.len() * 64));
        for _ in 0..64 {
            e.extend_from_slice(&unit);
        }
        let encrypted_len = e.len();
        cbc::Encryptor::<Aes128>::new_from_slices(&k[..16], &k[16..32])
            .map_err(|_| internal())?
            .encrypt_padded_mut::<NoPadding>(&mut e, encrypted_len)
            .map_err(|_| internal())?;
        let remainder = e[..16].iter().map(|b| u32::from(*b)).sum::<u32>() % 3;
        k = Zeroizing::new(match remainder {
            0 => Sha256::digest(&*e).to_vec(),
            1 => Sha384::digest(&*e).to_vec(),
            _ => Sha512::digest(&*e).to_vec(),
        });
        round += 1;
        if round >= 64 && u32::from(*e.last().ok_or_else(internal)?) <= round - 32 {
            break;
        }
    }
    let mut out = Zeroizing::new([0u8; 32]);
    out.copy_from_slice(&k[..32]);
    Ok(out)
}

fn aes256_cbc_no_iv_decrypt(key: &[u8], data: &mut [u8]) -> Result<()> {
    cbc::Decryptor::<Aes256>::new_from_slices(key, &[0u8; AES_BLOCK])
        .map_err(|_| internal())?
        .decrypt_padded_mut::<NoPadding>(data)
        .map_err(|_| damaged())?;
    Ok(())
}

fn aes256_cbc_no_iv_encrypt(key: &[u8], data: &mut [u8]) -> Result<()> {
    let len = data.len();
    cbc::Encryptor::<Aes256>::new_from_slices(key, &[0u8; AES_BLOCK])
        .map_err(|_| internal())?
        .encrypt_padded_mut::<NoPadding>(data, len)
        .map_err(|_| internal())?;
    Ok(())
}

fn fixed<'a>(dict: &'a Dict, key: &str, len: usize) -> Result<&'a [u8]> {
    // Some writers pad these strings; only the first `len` bytes count.
    bytes_entry(dict, key).and_then(|bytes| bytes.get(..len)).ok_or_else(damaged)
}

fn unlock_r6(
    dict: &Dict,
    bits: u32,
    encrypt_metadata: bool,
    password: Option<&str>,
) -> Result<Option<(Zeroizing<Vec<u8>>, bool)>> {
    let (o, u) = (fixed(dict, "O", 48)?, fixed(dict, "U", 48)?);
    let (oe, ue) = (fixed(dict, "OE", 32)?, fixed(dict, "UE", 32)?);
    let perms = fixed(dict, "Perms", AES_BLOCK)?;
    let raw = password.unwrap_or("").as_bytes();
    let cut = Zeroizing::new(raw[..raw.len().min(MAX_R6_PASSWORD)].to_vec());

    let mut unlocked = None;
    if password.is_some() && *hash_r6(&cut, &o[32..40], u)? == o[..32] {
        let mut key = Zeroizing::new(oe.to_vec());
        aes256_cbc_no_iv_decrypt(&*hash_r6(&cut, &o[40..48], u)?, &mut key)?;
        unlocked = Some((key, true));
    }
    if unlocked.is_none() && *hash_r6(&cut, &u[32..40], b"")? == u[..32] {
        let mut key = Zeroizing::new(ue.to_vec());
        aes256_cbc_no_iv_decrypt(&*hash_r6(&cut, &u[40..48], b"")?, &mut key)?;
        unlocked = Some((key, false));
    }
    let Some((key, is_owner)) = unlocked else { return Ok(None) };

    // `/P` isn't bound into the key here: `/Perms` is what proves it wasn't edited.
    let mut check = [0u8; AES_BLOCK];
    check.copy_from_slice(perms);
    aes256_cbc_no_iv_decrypt(&key, &mut check)?;
    let sealed_bits = u32::from_le_bytes([check[0], check[1], check[2], check[3]]);
    let sealed_metadata = check[8] == b'T';
    if &check[9..12] != b"adb" || sealed_bits != bits || sealed_metadata != encrypt_metadata {
        return Err(EngineError::malformed("the PDF's permissions have been tampered with"));
    }
    Ok(Some((key, is_owner)))
}

/// What a new encryption dictionary is written with.
pub(crate) struct EncryptSpec<'a> {
    pub(crate) algorithm: CryptAlgorithm,
    /// Opens the file. Empty opens it for anyone (only the owner limits apply).
    pub(crate) user: &'a [u8],
    /// Lifts the owner limits. Empty means the user password.
    pub(crate) owner: &'a [u8],
    /// The `/P` bits, as stored.
    pub(crate) permissions: u32,
    pub(crate) encrypt_metadata: bool,
    /// The trailer's first `/ID` string. Revisions 2 to 4 derive the key from it.
    pub(crate) file_id: &'a [u8],
}

/// Writes a new encryption dictionary and the key it is sealed with. Every object
/// written through it is encrypted per the algorithm: AES-256 with the file key,
/// or AES-128 and RC4 with the per-object key (Algorithm 1).
pub(crate) struct Encryptor {
    key: Zeroizing<Vec<u8>>,
    method: Method,
    encrypt_metadata: bool,
    algorithm: CryptAlgorithm,
    dict: Dict,
}

/// `/P` as the signed decimal the dictionary stores.
fn permission_text(bits: u32) -> String {
    (bits as i32).to_string()
}

impl Encryptor {
    /// AES-256, one password for both roles, with nothing restricted.
    pub(crate) fn new(password: &str) -> Result<Self> {
        Self::with_spec(&EncryptSpec {
            algorithm: CryptAlgorithm::AesBits256,
            user: password.as_bytes(),
            owner: password.as_bytes(),
            permissions: ALL_ALLOWED,
            encrypt_metadata: true,
            file_id: &[],
        })
    }

    pub(crate) fn with_spec(spec: &EncryptSpec<'_>) -> Result<Self> {
        let owner = if spec.owner.is_empty() { spec.user } else { spec.owner };
        match spec.algorithm {
            CryptAlgorithm::AesBits256 => Self::new_r6(spec, owner),
            _ => Self::new_legacy(spec, owner),
        }
    }

    /// Revision 6: a random file key, wrapped under the user and owner passwords (Algorithms 8 and 9).
    fn new_r6(spec: &EncryptSpec<'_>, owner: &[u8]) -> Result<Self> {
        let mut key = Zeroizing::new(vec![0u8; 32]);
        secure_random(&mut key[..])?;

        let mut salts = [0u8; 32];
        secure_random(&mut salts)?;
        let (u, ue) = seal_r6(spec.user, &salts[0..8], &salts[8..16], b"", &key)?;
        let (o, oe) = seal_r6(owner, &salts[16..24], &salts[24..32], &u, &key)?;

        let mut perms = [0u8; AES_BLOCK];
        perms[..4].copy_from_slice(&spec.permissions.to_le_bytes());
        perms[4..8].copy_from_slice(&[0xFF; 4]);
        perms[8] = if spec.encrypt_metadata { b'T' } else { b'F' };
        perms[9..12].copy_from_slice(b"adb");
        secure_random(&mut perms[12..16])?;
        aes256_cbc_no_iv_encrypt(&key, &mut perms)?;

        let filter =
            Dict::of(vec![("CFM", Obj::name("AESV3")), ("AuthEvent", Obj::name("DocOpen")), ("Length", Obj::int(32))]);
        let dict = Dict::of(vec![
            ("Filter", Obj::name("Standard")),
            ("V", Obj::int(5)),
            ("R", Obj::int(6)),
            ("Length", Obj::int(256)),
            ("P", Obj::Num(permission_text(spec.permissions))),
            ("O", Obj::Str(o)),
            ("U", Obj::Str(u)),
            ("OE", Obj::Str(oe)),
            ("UE", Obj::Str(ue)),
            ("Perms", Obj::Str(perms.to_vec())),
            ("EncryptMetadata", Obj::Bool(spec.encrypt_metadata)),
            ("CF", Obj::Dict(Dict::of(vec![("StdCF", Obj::Dict(filter))]))),
            ("StmF", Obj::name("StdCF")),
            ("StrF", Obj::name("StdCF")),
        ]);
        Ok(Self {
            key,
            method: Method::AesV3,
            encrypt_metadata: spec.encrypt_metadata,
            algorithm: CryptAlgorithm::AesBits256,
            dict,
        })
    }

    /// Revisions 2 to 4: Algorithms 2 to 5 of ISO 32000-1, with the file key
    /// derived from the user password, the owner entry, `/P` and the file ID.
    fn new_legacy(spec: &EncryptSpec<'_>, owner: &[u8]) -> Result<Self> {
        let (version, revision, method) = match spec.algorithm {
            CryptAlgorithm::Rc4Bits40 => (1i64, 2i64, Method::Rc4),
            CryptAlgorithm::Rc4Bits128 => (2, 3, Method::Rc4),
            CryptAlgorithm::AesBits128 => (4, 4, Method::AesV2),
            CryptAlgorithm::AesBits256 => return Err(internal()),
        };
        let length = spec.algorithm.key_len();
        // Only revision 4 has the metadata flag; earlier files always encrypt it.
        let encrypt_metadata = version != 4 || spec.encrypt_metadata;

        // Algorithm 3: the owner entry, the padded user password under the owner's key.
        let mut digest: [u8; 16] = Md5::digest(*pad_password(owner)).into();
        if revision >= 3 {
            for _ in 0..50 {
                digest = Md5::digest(&digest[..length]).into();
            }
        }
        let owner_key = digest[..length].to_vec();
        digest.zeroize();
        let mut o = *pad_password(spec.user);
        rc4_rounds(&owner_key, &mut o, revision)?;

        // Algorithm 2: the file key.
        let header = LegacyHeader {
            o: &o[..],
            u: &[],
            bits: spec.permissions,
            id0: spec.file_id,
            revision,
            length,
            encrypt_metadata,
        };
        let key = legacy_file_key(&header, &pad_password(spec.user));

        // Algorithms 4 and 5: the user entry. Its last 16 bytes are arbitrary padding, left zero.
        let mut u = [0u8; 32];
        if revision == 2 {
            u.copy_from_slice(&PAD);
            rc4(&key, &mut u)?;
        } else {
            let mut hash = Md5::new();
            hash.update(PAD);
            hash.update(spec.file_id);
            let mut check: [u8; 16] = hash.finalize().into();
            rc4_rounds(&key, &mut check, revision)?;
            u[..16].copy_from_slice(&check);
        }

        let mut entries: Vec<(&str, Obj)> = vec![
            ("Filter", Obj::name("Standard")),
            ("V", Obj::int(version as u64)),
            ("R", Obj::int(revision as u64)),
            ("Length", Obj::int(length as u64 * 8)),
            ("P", Obj::Num(permission_text(spec.permissions))),
            ("O", Obj::Str(o.to_vec())),
            ("U", Obj::Str(u.to_vec())),
        ];
        if version == 4 {
            let filter =
                Dict::of(vec![("CFM", Obj::name("AESV2")), ("AuthEvent", Obj::name("DocOpen")), ("Length", Obj::int(16))]);
            entries.push(("EncryptMetadata", Obj::Bool(encrypt_metadata)));
            entries.push(("CF", Obj::Dict(Dict::of(vec![("StdCF", Obj::Dict(filter))]))));
            entries.push(("StmF", Obj::name("StdCF")));
            entries.push(("StrF", Obj::name("StdCF")));
        }
        Ok(Self {
            key,
            method,
            encrypt_metadata,
            algorithm: spec.algorithm,
            dict: Dict::of(entries),
        })
    }

    pub(crate) fn dict(&self) -> &Dict {
        &self.dict
    }

    pub(crate) fn algorithm(&self) -> CryptAlgorithm {
        self.algorithm
    }

    /// Whether a stream with this dictionary is encrypted. XMP metadata is left
    /// in clear when the file says so (`EncryptMetadata false`).
    pub(crate) fn encrypts_stream(&self, dict: &Dict) -> bool {
        self.encrypt_metadata || dict.get("Type").and_then(Obj::as_name) != Some(b"Metadata")
    }

    /// The bytes of object `number` as stored: encrypted with its key, or with the file key for AES-256.
    pub(crate) fn encrypt(&self, number: u32, generation: u16, plain: &[u8]) -> Result<Vec<u8>> {
        match self.method {
            Method::Identity => Ok(plain.to_vec()),
            Method::Rc4 => {
                let mut out = plain.to_vec();
                rc4(&object_key(&self.key, Method::Rc4, number, generation), &mut out)?;
                Ok(out)
            }
            Method::AesV2 | Method::AesV3 => {
                aes_encrypt_data(&object_key(&self.key, self.method, number, generation), plain)
            }
        }
    }

    /// Encrypts every string in object `number` before it is written.
    pub(crate) fn encrypt_strings(&self, number: u32, generation: u16, value: &mut Obj) -> Result<()> {
        match value {
            Obj::Str(bytes) => *bytes = self.encrypt(number, generation, bytes)?,
            Obj::Array(items) => {
                for item in items {
                    self.encrypt_strings(number, generation, item)?;
                }
            }
            Obj::Dict(dict) => {
                for (_, item) in &mut dict.0 {
                    self.encrypt_strings(number, generation, item)?;
                }
            }
            _ => {}
        }
        Ok(())
    }
}

/// Algorithms 8 and 9: the 48-byte check value and the wrapped file key.
fn seal_r6(
    password: &[u8],
    validation_salt: &[u8],
    key_salt: &[u8],
    extra: &[u8],
    key: &[u8],
) -> Result<(Vec<u8>, Vec<u8>)> {
    let mut check = hash_r6(password, validation_salt, extra)?.to_vec();
    check.extend_from_slice(validation_salt);
    check.extend_from_slice(key_salt);
    let mut wrapped = key.to_vec();
    aes256_cbc_no_iv_encrypt(&*hash_r6(password, key_salt, extra)?, &mut wrapped)?;
    Ok((check, wrapped))
}

/// A random IV, then `plain` in CBC with PKCS#7 padding. The key is 16 bytes (AES-128) or 32 (AES-256).
fn aes_encrypt_data(key: &[u8], plain: &[u8]) -> Result<Vec<u8>> {
    let padding = AES_BLOCK - plain.len() % AES_BLOCK;
    let mut out = vec![0u8; AES_BLOCK + plain.len() + padding];
    let (iv, body) = out.split_at_mut(AES_BLOCK);
    secure_random(iv)?;
    body[..plain.len()].copy_from_slice(plain);
    body[plain.len()..].fill(padding as u8);
    let len = body.len();
    match key.len() {
        16 => {
            cbc::Encryptor::<Aes128>::new_from_slices(key, iv)
                .map_err(|_| internal())?
                .encrypt_padded_mut::<NoPadding>(body, len)
                .map_err(|_| internal())?;
        }
        32 => {
            cbc::Encryptor::<Aes256>::new_from_slices(key, iv)
                .map_err(|_| internal())?
                .encrypt_padded_mut::<NoPadding>(body, len)
                .map_err(|_| internal())?;
        }
        _ => return Err(internal()),
    }
    Ok(out)
}

/// IV first; damaged padding or a stray partial block is kept rather than
/// refused, as the bytes after decryption are untrusted in any case.
fn aes_decrypt_data(key: &[u8], data: &[u8]) -> Result<Vec<u8>> {
    if data.len() < AES_BLOCK {
        return Ok(Vec::new());
    }
    let (iv, body) = data.split_at(AES_BLOCK);
    let mut out = body[..body.len() / AES_BLOCK * AES_BLOCK].to_vec();
    if out.is_empty() {
        return Ok(out);
    }
    match key.len() {
        16 => cbc::Decryptor::<Aes128>::new_from_slices(key, iv)
            .map_err(|_| damaged())?
            .decrypt_padded_mut::<NoPadding>(&mut out)
            .map(|_| ())
            .map_err(|_| damaged())?,
        32 => cbc::Decryptor::<Aes256>::new_from_slices(key, iv)
            .map_err(|_| damaged())?
            .decrypt_padded_mut::<NoPadding>(&mut out)
            .map(|_| ())
            .map_err(|_| damaged())?,
        _ => return Err(damaged()),
    }
    if let Some(&padding) = out.last() {
        let padding = usize::from(padding);
        if (1..=AES_BLOCK).contains(&padding)
            && out.len() >= padding
            && out[out.len() - padding..].iter().all(|b| usize::from(*b) == padding)
        {
            out.truncate(out.len() - padding);
        }
    }
    Ok(out)
}

/// The per-object key of Algorithm 1 (AES-256 uses the file key directly).
/// Shared by the reader and the writer.
fn object_key(key: &[u8], method: Method, number: u32, generation: u16) -> Zeroizing<Vec<u8>> {
    if method == Method::AesV3 {
        return Zeroizing::new(key.to_vec());
    }
    let mut hash = Md5::new();
    hash.update(key);
    hash.update(&number.to_le_bytes()[..3]);
    hash.update(generation.to_le_bytes());
    if method == Method::AesV2 {
        hash.update(b"sAlT");
    }
    let digest = hash.finalize();
    Zeroizing::new(digest[..(key.len() + 5).min(16)].to_vec())
}

/// RC4 with `key`, then for revision 3 and later 19 more passes with the key
/// XORed with 1..=19 (Algorithms 3 and 5).
fn rc4_rounds(key: &[u8], data: &mut [u8], revision: i64) -> Result<()> {
    rc4(key, data)?;
    if revision >= 3 {
        for round in 1..=19u8 {
            let round_key: Zeroizing<Vec<u8>> = Zeroizing::new(key.iter().map(|b| b ^ round).collect());
            rc4(&round_key, data)?;
        }
    }
    Ok(())
}

/// RC4, for reading old files only. The crate fixes the key size in a type.
fn rc4(key: &[u8], data: &mut [u8]) -> Result<()> {
    macro_rules! with_size {
        ($($len:literal => $size:ty),* $(,)?) => {
            match key.len() {
                $($len => Rc4::<$size>::new_from_slice(key).map_err(|_| damaged())?.apply_keystream(data),)*
                _ => return Err(damaged()),
            }
        };
    }
    with_size!(
        5 => consts::U5, 6 => consts::U6, 7 => consts::U7, 8 => consts::U8,
        9 => consts::U9, 10 => consts::U10, 11 => consts::U11, 12 => consts::U12,
        13 => consts::U13, 14 => consts::U14, 15 => consts::U15, 16 => consts::U16,
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(text: &str) -> Vec<u8> {
        (0..text.len()).step_by(2).map(|i| u8::from_str_radix(&text[i..i + 2], 16).unwrap()).collect()
    }

    #[test]
    fn rc4_matches_a_published_vector() {
        // RFC 6229, 40-bit key, first output block.
        let mut data = [0u8; 16];
        rc4(&hex("0102030405"), &mut data).unwrap();
        assert_eq!(data.to_vec(), hex("b2396305f03dc027ccc3524a0a1118a8"));
    }

    #[test]
    fn r6_seal_unlocks_for_the_password_and_nothing_else() {
        let encryptor = Encryptor::new("s3cret").unwrap();
        let dict = encryptor.dict();
        let unlocked = Decryptor::unlock(dict, b"", Some(9), Some("s3cret")).unwrap();
        assert!(unlocked.is_owner);
        assert_eq!(&*unlocked.key, &encryptor.key[..]);
        let wrong = Decryptor::unlock(dict, b"", Some(9), Some("nope")).err().unwrap();
        assert_eq!(wrong.kind, ErrorKind::WrongPassword);
        let none = Decryptor::unlock(dict, b"", Some(9), None).err().unwrap();
        assert_eq!(none.kind, ErrorKind::PasswordRequired);
    }

    #[test]
    fn r6_refuses_edited_permissions() {
        let encryptor = Encryptor::new("s3cret").unwrap();
        let mut dict = encryptor.dict().clone();
        dict.set("P", Obj::Num("-44".into()));
        let err = Decryptor::unlock(&dict, b"", None, Some("s3cret")).err().unwrap();
        assert_eq!(err.kind, ErrorKind::Malformed);
    }

    #[test]
    fn aes_output_differs_each_time_and_round_trips() {
        let encryptor = Encryptor::new("pw").unwrap();
        let first = encryptor.encrypt(1, 0, b"hello world").unwrap();
        let second = encryptor.encrypt(1, 0, b"hello world").unwrap();
        assert_ne!(first, second);
        assert_eq!(first.len(), 32);
        assert_eq!(aes_decrypt_data(&encryptor.key, &first).unwrap(), b"hello world");
        // A whole block of plaintext still gets a padding block.
        assert_eq!(encryptor.encrypt(1, 0, &[7u8; 16]).unwrap().len(), 48);
    }

    /// Writes with `algorithm`, then reads back as the user and as the owner.
    fn round_trip(algorithm: CryptAlgorithm, user: &str, owner: &str, bits: u32) {
        let file_id = [0x5Au8; 16];
        let writer = Encryptor::with_spec(&EncryptSpec {
            algorithm,
            user: user.as_bytes(),
            owner: owner.as_bytes(),
            permissions: bits,
            encrypt_metadata: true,
            file_id: &file_id,
        })
        .unwrap();
        let dict = writer.dict().clone();
        assert!(writer.algorithm() == algorithm);

        let as_user = Decryptor::unlock(&dict, &file_id, Some(9), Some(user)).unwrap();
        assert!(!as_user.is_owner, "{algorithm:?} opened as owner by user password");
        assert_eq!(as_user.algorithm(), algorithm);
        assert_eq!(as_user.permissions.bits(), bits);

        let as_owner = Decryptor::unlock(&dict, &file_id, Some(9), Some(owner)).unwrap();
        assert!(as_owner.is_owner, "{algorithm:?} owner password not recognised");

        let mut text = Obj::Str(b"Title (x)".to_vec());
        writer.encrypt_strings(4, 0, &mut text).unwrap();
        as_user.decrypt_strings(4, 0, &mut text).unwrap();
        assert_eq!(text, Obj::Str(b"Title (x)".to_vec()));

        let plain = b"BT /F1 12 Tf (hello) Tj ET".repeat(20);
        let stored = writer.encrypt(7, 0, &plain).unwrap();
        assert_eq!(as_owner.decrypt(writer.method, 7, 0, &stored).unwrap(), plain);
    }

    #[test]
    fn every_writable_algorithm_round_trips_for_user_and_owner() {
        let print_only = PRINT | COPY | ANNOTATE;
        round_trip(CryptAlgorithm::Rc4Bits40, "", "owner-x", print_only);
        round_trip(CryptAlgorithm::Rc4Bits128, "user1", "owner2", print_only);
        round_trip(CryptAlgorithm::AesBits128, "user1", "owner2", ALL_ALLOWED);
        round_trip(CryptAlgorithm::AesBits256, "user1", "owner2", print_only);
    }

    #[test]
    fn legacy_writer_without_user_password_opens_for_anyone() {
        let file_id = [1u8; 16];
        let writer = Encryptor::with_spec(&EncryptSpec {
            algorithm: CryptAlgorithm::AesBits128,
            user: b"",
            owner: b"random-owner-key",
            permissions: PRINT,
            encrypt_metadata: false,
            file_id: &file_id,
        })
        .unwrap();
        let dict = writer.dict().clone();
        let opened = Decryptor::unlock(&dict, &file_id, Some(9), None).unwrap();
        assert!(!opened.is_owner);
        assert!(!opened.permissions.may_modify());
        // Metadata is left in clear when the file says so.
        let metadata = Dict::of(vec![("Type", Obj::name("Metadata"))]);
        assert!(!writer.encrypts_stream(&metadata));
        assert!(writer.encrypts_stream(&Dict::default()));
    }

    #[test]
    fn owner_entry_rc4_128_uses_the_first_n_bytes_each_round() {
        // A 128-bit key uses the whole digest, so the owner path must agree with the writer.
        let file_id = [3u8; 16];
        let writer = Encryptor::with_spec(&EncryptSpec {
            algorithm: CryptAlgorithm::Rc4Bits128,
            user: b"u",
            owner: b"o",
            permissions: ALL_ALLOWED,
            encrypt_metadata: true,
            file_id: &file_id,
        })
        .unwrap();
        let dict = writer.dict().clone();
        assert!(Decryptor::unlock(&dict, &file_id, Some(9), Some("o")).unwrap().is_owner);
    }

    #[test]
    fn algorithm_versions_and_weakness_are_reported() {
        assert_eq!(CryptAlgorithm::Rc4Bits40.min_version(), (1, 4));
        assert_eq!(CryptAlgorithm::AesBits128.min_version(), (1, 6));
        assert_eq!(CryptAlgorithm::AesBits256.min_version(), (2, 0));
        assert!(CryptAlgorithm::Rc4Bits40.is_weak() && !CryptAlgorithm::AesBits128.is_weak());
    }

    #[test]
    fn unknown_handlers_are_typed() {
        let pubsec = Dict::of(vec![("Filter", Obj::name("Adobe.PubSec")), ("V", Obj::int(4)), ("R", Obj::int(4))]);
        let err = Decryptor::unlock(&pubsec, b"", None, None).err().unwrap();
        assert_eq!(err.kind, ErrorKind::UnsupportedEncryption);
        let odd = Dict::of(vec![
            ("Filter", Obj::name("Standard")),
            ("V", Obj::int(9)),
            ("R", Obj::int(9)),
            ("P", Obj::int(0)),
        ]);
        let err = Decryptor::unlock(&odd, b"", None, None).err().unwrap();
        assert_eq!(err.kind, ErrorKind::UnsupportedEncryption);
    }

    #[test]
    fn permission_bits_read_as_the_specification_numbers_them() {
        let all = Permissions { bits: ALL_ALLOWED, revision: 4 };
        assert!(all.may_assemble() && all.may_modify() && all.denied().is_empty());
        let assemble_only = Permissions { bits: ASSEMBLE | PRINT | COPY | ANNOTATE, revision: 4 };
        assert!(assemble_only.may_assemble() && !assemble_only.may_modify());
        // Revision 2 has no assemble bit.
        let old = Permissions { bits: ASSEMBLE, revision: 2 };
        assert!(!old.may_assemble());
    }
}
