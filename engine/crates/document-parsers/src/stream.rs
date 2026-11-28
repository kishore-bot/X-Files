//! Batching shared by every structured parser.
//!
//! A format implements [`BlockSource`], producing blocks in document order as
//! it walks its XML. [`Streamed`] turns that into budget-respecting batches:
//! blocks that do not fit wait for the next batch, and blocks larger than a
//! whole batch are split with [`Block::split_to_fit`].

use std::collections::VecDeque;
use std::io::BufReader;

use document_core::{Block, CancelToken, DocumentInfo, EngineError, Extracted, Limits, OpenDocument, Result};

use crate::xml::XmlReader;
use crate::zip::{EntryReader, ZipArchive};

/// Paragraphs longer than this are emitted in pieces while parsing, so a
/// pathological single paragraph cannot grow without bound in memory.
pub(crate) const MAX_PENDING_TEXT: usize = 256 * 1024;

/// High-resolution photos and scans are often well over 16 MB.
const MAX_PICTURE_BYTES: u64 = 64 * 1024 * 1024;
const PICTURE_EXTENSIONS: [&str; 6] = ["png", "jpg", "jpeg", "gif", "bmp", "webp"];

/// Embedded audio and video are larger than pictures; the app plays them from a temporary file.
const MAX_MEDIA_BYTES: u64 = 128 * 1024 * 1024;
const MEDIA_EXTENSIONS: [&str; 16] =
    ["mp4", "m4v", "mov", "3gp", "webm", "mkv", "mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr", "mid"];

/// Whether `name` has the extension of an audio or video format the platforms play.
pub(crate) fn is_media(name: &str) -> bool {
    name.rsplit_once('.').is_some_and(|(_, ext)| MEDIA_EXTENSIONS.iter().any(|m| ext.eq_ignore_ascii_case(m)))
}

/// Whether `name` has the extension of a raster format the platforms decode.
/// Vector and metafile formats (SVG, EMF, WMF) are never served.
pub(crate) fn is_raster_picture(name: &str) -> bool {
    name.rsplit_once('.').is_some_and(|(_, ext)| PICTURE_EXTENSIONS.iter().any(|p| ext.eq_ignore_ascii_case(p)))
}

pub(crate) trait BlockSource: Send {
    /// Appends at least one block to `out`, or returns `true` when the
    /// document is exhausted.
    fn pump(&mut self, out: &mut VecDeque<Block>) -> Result<bool>;

    fn resource(&mut self, _name: &str) -> Result<Vec<u8>> {
        Err(EngineError::unsupported("this document has no embedded resources"))
    }
}

pub(crate) struct Streamed<S> {
    info: DocumentInfo,
    source: S,
    queue: VecDeque<Block>,
    exhausted: bool,
}

impl<S: BlockSource> Streamed<S> {
    pub fn new(info: DocumentInfo, source: S) -> Self {
        Self { info, source, queue: VecDeque::new(), exhausted: false }
    }
}

impl<S: BlockSource> OpenDocument for Streamed<S> {
    fn info(&self) -> &DocumentInfo {
        &self.info
    }

    fn resource(&mut self, name: &str) -> Result<Vec<u8>> {
        self.source.resource(name)
    }

    fn next_blocks(&mut self, max_bytes: usize) -> Result<Extracted> {
        let mut blocks = Vec::new();
        let mut used = 0;
        loop {
            let Some(block) = self.queue.pop_front() else {
                if self.exhausted {
                    return Ok(Extracted { blocks, finished: true });
                }
                self.exhausted = self.source.pump(&mut self.queue)?;
                continue;
            };
            let room = max_bytes - used;
            let cost = block.cost();
            if cost <= room {
                used += cost;
                blocks.push(block);
                continue;
            }
            // Keep a block whole when it fits the next batch, or when there is
            // too little room left to place any part of it.
            if (cost <= max_bytes && !blocks.is_empty()) || room < Limits::MIN_BATCH_BYTES {
                self.queue.push_front(block);
                return Ok(Extracted { blocks, finished: false });
            }
            let (head, rest) = block.split_to_fit(room);
            used += head.cost();
            blocks.push(head);
            if let Some(rest) = rest {
                self.queue.push_front(rest);
            }
        }
    }
}

pub(crate) type PartXml = XmlReader<BufReader<EntryReader>>;

/// Streams XML parts of a ZIP container one at a time.
pub(crate) struct Parts {
    archive: ZipArchive,
    current: Option<PartXml>,
    cancel: CancelToken,
}

impl Parts {
    pub fn new(archive: ZipArchive, cancel: CancelToken) -> Self {
        Self { archive, current: None, cancel }
    }

    pub fn cancel(&self) -> CancelToken {
        self.cancel.clone()
    }

    pub fn archive(&mut self) -> &mut ZipArchive {
        self.close();
        &mut self.archive
    }

    /// Starts streaming `name`, closing any part still open.
    pub fn open(&mut self, name: &str) -> Result<&mut PartXml> {
        self.close();
        let entry = self.archive.stream(name)?;
        Ok(self.current.insert(XmlReader::new(BufReader::with_capacity(64 * 1024, entry), self.cancel.clone())))
    }

    pub fn xml(&mut self) -> &mut PartXml {
        self.current.as_mut().expect("no part is open")
    }

    /// The name of the container entry at `path`, if it is a raster picture.
    /// Unlike [`Parts::archive`], this leaves the open part streaming.
    pub fn picture_path(&self, path: &str) -> Option<String> {
        if !is_raster_picture(path) {
            return None;
        }
        self.archive.find(path).filter(|e| !e.is_dir).map(|e| e.name.clone())
    }

    /// Like [`Self::picture_path`], for embedded audio and video.
    pub fn media_path(&self, path: &str) -> Option<String> {
        if !is_media(path) {
            return None;
        }
        self.archive.find(path).filter(|e| !e.is_dir).map(|e| e.name.clone())
    }

    /// Bytes of an embedded picture, for [`BlockSource::resource`]. Only
    /// raster images inside the container can be read, within a size limit.
    /// The open part, if any, keeps streaming from where it was: a picture
    /// can be requested at any point between two batches.
    pub fn picture(&mut self, name: &str) -> Result<Vec<u8>> {
        let limit = if is_raster_picture(name) {
            MAX_PICTURE_BYTES
        } else if is_media(name) {
            MAX_MEDIA_BYTES
        } else {
            return Err(EngineError::unsupported("only pictures, audio and video can be read from a document"));
        };
        if name.split('/').any(|segment| segment == "..") {
            return Err(EngineError::unsupported("only pictures, audio and video can be read from a document"));
        }
        let entry = self
            .archive
            .find(name)
            .filter(|e| !e.is_dir)
            .ok_or_else(|| EngineError::malformed(format!("{name} is missing")))?;
        if entry.size > limit {
            return Err(EngineError::limit("the embedded file is too large"));
        }
        let active = self.current.as_mut().map(|xml| xml.get_mut().get_mut());
        self.archive.read_aside(active, name)
    }

    /// Bytes of any file inside the container, for a reader that shows the
    /// document's own pages (an e-book's chapters, styles and fonts). The
    /// container's own bookkeeping (`META-INF/`, `mimetype`) is not served.
    /// Like [`Self::picture`], the open part keeps streaming.
    pub fn file(&mut self, name: &str) -> Result<Vec<u8>> {
        let refused = || EngineError::unsupported("this file can't be read from the document");
        let name = name.trim_start_matches('/');
        if name.is_empty()
            || name.split('/').any(|segment| segment == ".." || segment == ".")
            || name.starts_with("META-INF/")
            || name == "mimetype"
        {
            return Err(refused());
        }
        let entry = self
            .archive
            .find(name)
            .filter(|e| !e.is_dir)
            .ok_or_else(|| EngineError::malformed(format!("{name} is missing")))?;
        let limit = if is_media(name) { MAX_MEDIA_BYTES } else { MAX_PICTURE_BYTES * 2 };
        if entry.size > limit {
            return Err(EngineError::limit("the embedded file is too large"));
        }
        let name = entry.name.clone();
        let active = self.current.as_mut().map(|xml| xml.get_mut().get_mut());
        self.archive.read_aside(active, &name)
    }

    /// Reads from the archive by `read`, leaving the open part streaming (see
    /// [`ZipArchive::aside`]): a document rendered whole between two batches
    /// must not end the text stream that search still pulls from.
    pub fn aside<T>(&mut self, read: impl FnOnce(&mut ZipArchive) -> Result<T>) -> Result<T> {
        let active = self.current.as_mut().map(|xml| xml.get_mut().get_mut());
        self.archive.aside(active, read)
    }

    pub fn close(&mut self) {
        if let Some(xml) = self.current.take() {
            self.archive.finish(xml.into_inner().into_inner());
        }
    }
}
