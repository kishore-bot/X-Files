//! Compress: a structural rewrite. At the Lossless level it keeps every page and
//! every visible byte; the Balanced and Strong levels additionally re-encode large
//! images (see `pdf_tools_images`). Nothing else is re-encoded lossily. The writer:
//! 1. walks the object graph from the trailer (`Root`, `Info`) and keeps only
//!    what is reachable, so objects left behind by earlier edits are dropped.
//!    This is a breadth-first traversal with a visited set, so cycles terminate;
//! 2. hashes each stream (SHA-256 over its stored bytes, read in chunks) and
//!    writes a stream whose dictionary and bytes match an earlier one only once.
//!    Every reference is pointed at the first copy;
//! 3. re-deflates plain `FlateDecode` streams (no predictor) at the best level,
//!    keeping the new bytes only when they are smaller.
//!
//! Object numbers are assigned densely in traversal order, so the output is
//! deterministic. No object streams are written, so the cross-reference is a
//! classic table. Memory: streams are copied in 64 KiB pieces. Only the streams
//! that are recompressed are held whole, and only up to `MAX_RECOMPRESS_BYTES`.

use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};
use std::io::{Read, Seek, Write};

use document_core::{EngineError, Result};
use flate2::Compression;
use flate2::read::ZlibDecoder;
use flate2::write::ZlibEncoder;
use sha2::{Digest, Sha256};

use crate::pdf_file::{LoadedObject, PdfFile, XrefEntry};
use crate::pdf_syntax::{Dict, Obj, write_dict, write_obj};
use crate::pdf_tools::PdfToolObserver;
use crate::pdf_tools_images::{
    ImagePlan, PdfImageQuality, Replacement, plan_image, plan_soft_mask, recompress_batch, replacement_dict,
    scan_placements, soft_mask_of, worker_count,
};

/// Objects one compress may keep. Each one is held in memory until written.
const MAX_OBJECTS: usize = 500_000;
const MAX_OUTPUT_BYTES: u64 = 1024 * 1024 * 1024;
/// The largest stream that is inflated and deflated again, held whole.
const MAX_RECOMPRESS_BYTES: u64 = 32 * 1024 * 1024;
/// How many objects pass between looks at the cancel flag.
const CANCEL_EVERY: usize = 64;

/// What a compress did. Sizes are the input and output file lengths.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PdfCompressReport {
    pub page_count: u32,
    pub before: u64,
    pub after: u64,
    /// Live objects the input's cross-reference listed.
    pub objects_before: u32,
    pub objects_after: u32,
    /// Streams identical to an earlier one, written once.
    pub duplicates_merged: u32,
    /// Flate streams that came out smaller at the best level.
    pub streams_recompressed: u32,
    /// Images re-encoded at a lossy quality and written smaller.
    pub images_recompressed: u32,
}

/// A reachable object. For a stream, `stream` holds where its stored bytes start
/// and how many there are; the dictionary (`value`) still has the old `/Length`.
struct Entry {
    old: u32,
    value: Obj,
    stream: Option<(u64, u64)>,
}

/// Stream identity: the digest of its stored bytes and its dictionary without `/Length`.
type StreamKey = ([u8; 32], Vec<u8>);

/// Writes a compacted copy of `source` to `out`, structure only. Refuses protected
/// files: the output carries no encryption, which would silently lift the owner's limits.
pub fn compress_pdf<R: Read + Seek, W: Write>(
    source: R,
    out: &mut W,
    observer: &mut dyn PdfToolObserver,
) -> Result<PdfCompressReport> {
    compress_pdf_with_quality(source, out, observer, PdfImageQuality::Lossless)
}

/// As [`compress_pdf`], and at a lossy `quality` also re-encodes large images
/// (see `pdf_tools_images`). Lossless output is byte-for-byte what `compress_pdf` writes.
pub fn compress_pdf_with_quality<R: Read + Seek, W: Write>(
    source: R,
    out: &mut W,
    observer: &mut dyn PdfToolObserver,
    quality: PdfImageQuality,
) -> Result<PdfCompressReport> {
    let mut file = PdfFile::open(source)?;
    if file.trailer.get("Encrypt").is_some() {
        return Err(EngineError::unsupported("Remove the password before compressing this PDF."));
    }
    let page_count = file.pages()?.len() as u32;
    let version = file.header_version()?;
    let root = trailer_ref(&file, "Root").ok_or_else(|| EngineError::malformed("This PDF has no catalog."))?;
    let info = trailer_ref(&file, "Info");
    let objects_before = file
        .xref
        .iter()
        .filter(|(number, entry)| !matches!(entry, XrefEntry::Free) && file.has_object(**number))
        .count() as u32;
    let before = file.len;

    let mut roots = vec![root];
    roots.extend(info);
    let entries = reachable(&mut file, &roots, observer)?;
    let (new_of, kept, duplicates_merged) = number_objects(&mut file, &entries, observer)?;
    let plans = if quality == PdfImageQuality::Lossless {
        Vec::new()
    } else {
        image_plans(&mut file, &entries, &kept, quality, observer)?
    };

    let mut sink = Sink { out, pos: 0, offsets: vec![0; kept.len() + 1] };
    sink.put(format!("%PDF-{}.{}\n", version.0, version.1).as_bytes())?;
    // High bytes in a comment mark the file as binary for tools that guess.
    sink.put(b"%\xE2\xE3\xCF\xD3\n")?;
    let total = kept.len() as u32;
    observer.on_progress(0, total);
    let mut streams_recompressed = 0u32;
    let mut images_recompressed = 0u32;
    // Planned images are re-encoded a batch at a time, just ahead of the writes that need them.
    let workers = worker_count();
    let mut next_plan = 0usize;
    let mut ready: HashMap<usize, Replacement> = HashMap::new();
    for (index, &entry_index) in kept.iter().enumerate() {
        if index % CANCEL_EVERY == 0 {
            if observer.is_cancelled() {
                return Err(EngineError::cancelled());
            }
            observer.on_progress(index as u32, total);
        }
        let entry = &entries[entry_index];
        let number = index as u32 + 1;
        if next_plan < plans.len() && plans[next_plan].0 == index {
            if observer.is_cancelled() {
                return Err(EngineError::cancelled());
            }
            let end = (next_plan + workers).min(plans.len());
            ready = recompress_batch(&mut file, &plans[next_plan..end])?;
            next_plan = end;
        }
        sink.begin(number)?;
        if let Some(replacement) = ready.remove(&index) {
            let mut dict = stream_dict(entry);
            dict.remove("Length");
            let dict = replacement_dict(renumber_dict(dict, &new_of), &replacement);
            write_encoded_stream(&mut sink, dict, &replacement.bytes)?;
            images_recompressed += 1;
            continue;
        }
        match entry.stream {
            None => {
                let mut bytes = Vec::new();
                write_obj(&mut bytes, &renumber(entry.value.clone(), &new_of));
                sink.put(&bytes)?;
                sink.put(b"\nendobj\n")?;
            }
            Some((at, length)) => {
                if write_stream(&mut file, &mut sink, entry, at, length, &new_of)? {
                    streams_recompressed += 1;
                }
            }
        }
    }
    observer.on_progress(total, total);

    let root_number = *new_of.get(&root).ok_or_else(|| EngineError::malformed("This PDF has no catalog."))?;
    let info_number = info.and_then(|number| new_of.get(&number).copied());
    let after = sink.finish(root_number, info_number)?;
    Ok(PdfCompressReport {
        page_count,
        before,
        after,
        objects_before,
        objects_after: total,
        duplicates_merged,
        streams_recompressed,
        images_recompressed,
    })
}

/// Decides, per kept stream, whether it's an image worth re-encoding. Returns
/// `(position in kept, plan)` in position order. Placement comes from the pages.
/// A planned image's soft mask is planned with it, to the same size, unless the
/// mask is already planned or can't be carried.
fn image_plans<R: Read + Seek>(
    file: &mut PdfFile<R>,
    entries: &[Entry],
    kept: &[usize],
    quality: PdfImageQuality,
    observer: &mut dyn PdfToolObserver,
) -> Result<Vec<(usize, ImagePlan)>> {
    let placements = scan_placements(file, observer)?;
    // Kept position by the object number the pages use (duplicates answer with their first copy).
    let position_of: HashMap<u32, usize> =
        kept.iter().enumerate().map(|(position, &entry_index)| (entries[entry_index].old, position)).collect();
    let mut plans: BTreeMap<usize, ImagePlan> = BTreeMap::new();
    for (position, &entry_index) in kept.iter().enumerate() {
        let entry = &entries[entry_index];
        let (Some((at, length)), Obj::Dict(dict)) = (entry.stream, &entry.value) else { continue };
        let Some(&placed) = placements.get(&entry.old) else { continue };
        if plans.contains_key(&position) {
            continue;
        }
        let Some(plan) = plan_image(file, dict, at, length, placed, quality)? else { continue };
        if let Some(mask) = soft_mask_of(dict) {
            if let Some(&mask_position) = position_of.get(&mask) {
                if !plans.contains_key(&mask_position) {
                    let mask_entry = &entries[kept[mask_position]];
                    if let (Some((mask_at, mask_length)), Obj::Dict(mask_dict)) = (mask_entry.stream, &mask_entry.value)
                    {
                        let target = (plan.dst_width, plan.dst_height);
                        if let Some(mask_plan) = plan_soft_mask(file, mask_dict, mask_at, mask_length, target)? {
                            plans.insert(mask_position, mask_plan);
                        }
                    }
                }
            }
        }
        plans.insert(position, plan);
    }
    Ok(plans.into_iter().collect())
}

/// Writes an image stream whose data is already encoded (JPEG or Flate, per its dictionary).
fn write_encoded_stream<W: Write>(sink: &mut Sink<'_, W>, mut dict: Dict, bytes: &[u8]) -> Result<()> {
    dict.set("Length", Obj::int(bytes.len() as u64));
    sink.put_dict(&dict)?;
    sink.put(b"\nstream\n")?;
    sink.put(bytes)?;
    sink.put(b"\nendstream\nendobj\n")
}

fn trailer_ref<R: Read + Seek>(file: &PdfFile<R>, key: &str) -> Option<u32> {
    match file.trailer.get(key) {
        Some(Obj::Ref(number, _)) => Some(*number),
        _ => None,
    }
}

/// Breadth-first walk from `roots`. Missing objects are skipped, so references
/// to them become null in the output. Cross-reference and object-stream
/// containers are file structure, and are left out: this writer emits plain objects.
fn reachable<R: Read + Seek>(
    file: &mut PdfFile<R>,
    roots: &[u32],
    observer: &mut dyn PdfToolObserver,
) -> Result<Vec<Entry>> {
    let mut seen: HashSet<u32> = HashSet::new();
    let mut queue: VecDeque<u32> = roots.iter().copied().collect();
    let mut entries = Vec::new();
    let mut targets = Vec::new();
    while let Some(number) = queue.pop_front() {
        if !seen.insert(number) || !file.has_object(number) {
            continue;
        }
        if seen.len() > MAX_OBJECTS {
            return Err(EngineError::limit("the PDF has too many objects to compress"));
        }
        if seen.len() % CANCEL_EVERY == 0 && observer.is_cancelled() {
            return Err(EngineError::cancelled());
        }
        let LoadedObject { value, stream_at, .. } = file.load(number)?;
        let dict_type = match &value {
            Obj::Dict(dict) => dict.get("Type").and_then(Obj::as_name).map(<[u8]>::to_vec),
            _ => None,
        };
        let structural = matches!(dict_type.as_deref(), Some(b"ObjStm" | b"XRef"));
        if structural && stream_at.is_some() {
            continue;
        }
        let stream = match stream_at {
            Some(at) => {
                let declared = match &value {
                    Obj::Dict(dict) => dict.get("Length").cloned(),
                    _ => None,
                };
                let length = match declared {
                    Some(length) => file.resolve(&length)?.as_i64().unwrap_or(0).max(0) as u64,
                    None => 0,
                };
                Some((at, file.stream_extent(at, length)?))
            }
            None => None,
        };
        // A stream's /Length is rebuilt on output, so it isn't followed.
        match (&value, stream.is_some()) {
            (Obj::Dict(dict), true) => {
                let mut dict = dict.clone();
                dict.remove("Length");
                refs(&Obj::Dict(dict), &mut targets);
            }
            _ => refs(&value, &mut targets),
        }
        queue.extend(targets.drain(..));
        entries.push(Entry { old: number, value, stream });
    }
    Ok(entries)
}

fn refs(value: &Obj, out: &mut Vec<u32>) {
    match value {
        Obj::Ref(number, _) => out.push(*number),
        Obj::Array(items) => items.iter().for_each(|item| refs(item, out)),
        Obj::Dict(dict) => dict.0.iter().for_each(|(_, item)| refs(item, out)),
        _ => {}
    }
}

/// Assigns new numbers in traversal order. Returns the map from old to new
/// numbers (duplicates map to their first copy), the indices into `entries` that
/// are written, in output order, and how many streams were merged.
fn number_objects<R: Read + Seek>(
    file: &mut PdfFile<R>,
    entries: &[Entry],
    observer: &mut dyn PdfToolObserver,
) -> Result<(HashMap<u32, u32>, Vec<usize>, u32)> {
    let mut new_of = HashMap::with_capacity(entries.len());
    let mut kept = Vec::with_capacity(entries.len());
    let mut first_copy: HashMap<StreamKey, u32> = HashMap::new();
    let mut duplicates = 0u32;
    for (index, entry) in entries.iter().enumerate() {
        if index % CANCEL_EVERY == 0 && observer.is_cancelled() {
            return Err(EngineError::cancelled());
        }
        if let Some((at, length)) = entry.stream {
            let key = stream_key(file, entry, at, length, observer)?;
            if let Some(&number) = first_copy.get(&key) {
                new_of.insert(entry.old, number);
                duplicates += 1;
                continue;
            }
            first_copy.insert(key, kept.len() as u32 + 1);
        }
        kept.push(index);
        new_of.insert(entry.old, kept.len() as u32);
    }
    Ok((new_of, kept, duplicates))
}

/// Digest of the stored bytes plus the dictionary, read in chunks so the stream
/// is never held whole.
fn stream_key<R: Read + Seek>(
    file: &mut PdfFile<R>,
    entry: &Entry,
    at: u64,
    length: u64,
    observer: &mut dyn PdfToolObserver,
) -> Result<StreamKey> {
    let mut hasher = Sha256::new();
    file.read_chunks(at, length, |chunk| {
        if observer.is_cancelled() {
            return Err(EngineError::cancelled());
        }
        hasher.update(chunk);
        Ok(())
    })?;
    let mut dict = stream_dict(entry);
    dict.remove("Length");
    let mut bytes = Vec::new();
    write_dict(&mut bytes, &dict);
    Ok((hasher.finalize().into(), bytes))
}

fn stream_dict(entry: &Entry) -> Dict {
    match &entry.value {
        Obj::Dict(dict) => dict.clone(),
        _ => Dict::default(),
    }
}

/// Rewrites every reference through `new_of`. A reference to something that
/// wasn't reached (a dangling one) becomes null, as it was never a live object.
fn renumber(value: Obj, new_of: &HashMap<u32, u32>) -> Obj {
    match value {
        Obj::Ref(number, _) => new_of.get(&number).map_or(Obj::Null, |new| Obj::Ref(*new, 0)),
        Obj::Array(items) => Obj::Array(items.into_iter().map(|item| renumber(item, new_of)).collect()),
        Obj::Dict(dict) => Obj::Dict(renumber_dict(dict, new_of)),
        other => other,
    }
}

fn renumber_dict(mut dict: Dict, new_of: &HashMap<u32, u32>) -> Dict {
    for (_, item) in dict.0.iter_mut() {
        *item = renumber(std::mem::replace(item, Obj::Null), new_of);
    }
    dict
}

/// Writes one stream object (its header is already begun). Returns whether the
/// data was recompressed.
fn write_stream<R: Read + Seek, W: Write>(
    file: &mut PdfFile<R>,
    sink: &mut Sink<'_, W>,
    entry: &Entry,
    at: u64,
    length: u64,
    new_of: &HashMap<u32, u32>,
) -> Result<bool> {
    let mut dict = stream_dict(entry);
    dict.remove("Length");
    let mut dict = renumber_dict(dict, new_of);
    if is_plain_flate(&dict) && length <= MAX_RECOMPRESS_BYTES {
        let raw = file.stream_raw(at, length)?;
        if let Some(packed) = deflate_if_smaller(&raw)? {
            dict.set("Length", Obj::int(packed.len() as u64));
            sink.put_dict(&dict)?;
            sink.put(b"\nstream\n")?;
            sink.put(&packed)?;
            sink.put(b"\nendstream\nendobj\n")?;
            return Ok(true);
        }
    }
    dict.set("Length", Obj::int(length));
    sink.put_dict(&dict)?;
    sink.put(b"\nstream\n")?;
    file.read_chunks(at, length, |chunk| sink.put(chunk))?;
    sink.put(b"\nendstream\nendobj\n")?;
    Ok(false)
}

/// A single FlateDecode filter with no predictor, which is safe to re-deflate
/// without knowing the image layout.
fn is_plain_flate(dict: &Dict) -> bool {
    let filter_ok = match dict.get("Filter") {
        Some(Obj::Name(name)) => name.as_slice() == b"FlateDecode",
        Some(Obj::Array(items)) => {
            items.len() == 1 && matches!(&items[0], Obj::Name(name) if name.as_slice() == b"FlateDecode")
        }
        _ => false,
    };
    filter_ok && matches!(dict.get("DecodeParms"), None | Some(Obj::Null))
}

/// The stream re-deflated at the best level, if that's smaller. A stream that
/// won't inflate, or inflates past the bound, is kept as stored.
fn deflate_if_smaller(raw: &[u8]) -> Result<Option<Vec<u8>>> {
    let mut inflated = Vec::new();
    let read = ZlibDecoder::new(raw).take(MAX_RECOMPRESS_BYTES + 1).read_to_end(&mut inflated);
    if read.is_err() || inflated.len() as u64 > MAX_RECOMPRESS_BYTES {
        return Ok(None);
    }
    let mut encoder = ZlibEncoder::new(Vec::with_capacity(raw.len()), Compression::best());
    encoder.write_all(&inflated)?;
    let packed = encoder.finish()?;
    Ok((packed.len() < raw.len()).then_some(packed))
}

/// Output position and the offsets the cross-reference needs.
struct Sink<'a, W> {
    out: &'a mut W,
    pos: u64,
    /// Indexed by new object number. Zero is the free-list head.
    offsets: Vec<u64>,
}

impl<W: Write> Sink<'_, W> {
    fn put(&mut self, bytes: &[u8]) -> Result<()> {
        self.pos += bytes.len() as u64;
        if self.pos > MAX_OUTPUT_BYTES {
            return Err(EngineError::limit("the compressed PDF would be too large"));
        }
        self.out.write_all(bytes)?;
        Ok(())
    }

    fn begin(&mut self, number: u32) -> Result<()> {
        self.offsets[number as usize] = self.pos;
        self.put(format!("{number} 0 obj\n").as_bytes())
    }

    fn put_dict(&mut self, dict: &Dict) -> Result<()> {
        let mut bytes = Vec::new();
        write_dict(&mut bytes, dict);
        self.put(&bytes)
    }

    /// The cross-reference table and trailer. Returns the file's size.
    fn finish(&mut self, root: u32, info: Option<u32>) -> Result<u64> {
        let xref_at = self.pos;
        let size = self.offsets.len();
        let mut section = format!("xref\n0 {size}\n0000000000 65535 f \n");
        for offset in &self.offsets[1..] {
            // Exactly 20 bytes per entry, as the format demands.
            section.push_str(&format!("{offset:010} 00000 n \n"));
        }
        let mut trailer = Dict::of(vec![("Size", Obj::int(size as u64)), ("Root", Obj::Ref(root, 0))]);
        if let Some(info) = info {
            trailer.set("Info", Obj::Ref(info, 0));
        }
        let mut bytes = section.into_bytes();
        bytes.extend_from_slice(b"trailer\n");
        write_dict(&mut bytes, &trailer);
        bytes.extend_from_slice(format!("\nstartxref\n{xref_at}\n%%EOF\n").as_bytes());
        self.put(&bytes)?;
        Ok(self.pos)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn renumbering_maps_refs_and_nulls_out_unknown_ones() {
        let map: HashMap<u32, u32> = [(7, 1), (9, 2)].into_iter().collect();
        let value = Obj::Array(vec![Obj::Ref(7, 0), Obj::Ref(8, 0), Obj::Dict(Dict::of(vec![("K", Obj::Ref(9, 0))]))]);
        let out = renumber(value, &map);
        let Obj::Array(items) = out else { panic!("array expected") };
        assert_eq!(items[0], Obj::Ref(1, 0));
        assert_eq!(items[1], Obj::Null);
        let Obj::Dict(dict) = &items[2] else { panic!("dict expected") };
        assert_eq!(dict.get("K"), Some(&Obj::Ref(2, 0)));
    }

    #[test]
    fn only_a_single_plain_flate_filter_is_recompressed() {
        let plain = Dict::of(vec![("Filter", Obj::name("FlateDecode"))]);
        assert!(is_plain_flate(&plain));
        let array = Dict::of(vec![("Filter", Obj::Array(vec![Obj::name("FlateDecode")]))]);
        assert!(is_plain_flate(&array));
        let predicted = Dict::of(vec![
            ("Filter", Obj::name("FlateDecode")),
            ("DecodeParms", Obj::Dict(Dict::of(vec![("Predictor", Obj::int(12))]))),
        ]);
        assert!(!is_plain_flate(&predicted));
        let chained = Dict::of(vec![("Filter", Obj::Array(vec![Obj::name("ASCIIHexDecode"), Obj::name("FlateDecode")]))]);
        assert!(!is_plain_flate(&chained));
        assert!(!is_plain_flate(&Dict::default()));
    }

    #[test]
    fn deflate_keeps_the_smaller_form_and_nothing_else() {
        let mut repetitive = Vec::new();
        for _ in 0..200 {
            repetitive.extend_from_slice(b"BT /F1 12 Tf (hello) Tj ET\n");
        }
        // Store at the lowest level so there is something to gain.
        let mut weak = ZlibEncoder::new(Vec::new(), Compression::none());
        weak.write_all(&repetitive).unwrap();
        let stored = weak.finish().unwrap();
        let packed = deflate_if_smaller(&stored).unwrap().expect("should shrink");
        assert!(packed.len() < stored.len());
        let mut back = Vec::new();
        ZlibDecoder::new(packed.as_slice()).read_to_end(&mut back).unwrap();
        assert_eq!(back, repetitive);

        // Already-best data and garbage are left alone.
        assert!(deflate_if_smaller(b"not zlib at all").unwrap().is_none());
    }

    #[test]
    fn identical_stream_keys_match_and_different_dictionaries_do_not() {
        let digest = [1u8; 32];
        let a: StreamKey = (digest, b"<< /Type /XObject >>".to_vec());
        let b: StreamKey = (digest, b"<< /Type /XObject >>".to_vec());
        let c: StreamKey = (digest, b"<< /Type /Image >>".to_vec());
        let mut map = HashMap::new();
        map.insert(a, 1u32);
        assert_eq!(map.get(&b), Some(&1));
        assert_eq!(map.get(&c), None);
    }
}
