//! Reading the next chunk of a sequential input while the current one is parsed.
//!
//! A worker thread owns the reader and fills a fixed set of buffers. Filled
//! ones go to the parser through a bounded channel, in order, and come back
//! empty for reuse, so at most `DEPTH + 1` chunks exist at a time and nothing
//! is allocated after the start. Order never changes: there is one reader and
//! one channel.
//!
//! Only for inputs read front to back. Formats that seek (ZIP, CFB, PDF) read
//! the reader directly.

use std::io::ErrorKind as IoErrorKind;
use std::sync::mpsc::{Receiver, SyncSender, sync_channel};
use std::thread;

use document_core::{CancelToken, EngineError, ErrorKind, ReadSeek, Result};

/// Chunks read ahead of the parser.
const DEPTH: usize = 2;

/// Smaller inputs are read on the caller's thread: a few reads don't pay for a thread.
const READ_AHEAD_MIN_BYTES: u64 = 1024 * 1024;

/// A sequential input in chunks, read ahead on a worker thread when it is large.
pub(crate) enum Chunks {
    Direct { reader: Box<dyn ReadSeek>, buffer: Vec<u8> },
    Ahead(ReadAhead),
}

impl Chunks {
    pub fn new(reader: Box<dyn ReadSeek>, chunk: usize, byte_size: u64, cancel: &CancelToken) -> Self {
        if byte_size < READ_AHEAD_MIN_BYTES {
            return Self::Direct { reader, buffer: vec![0; chunk] };
        }
        match ReadAhead::spawn(reader, chunk, cancel.clone()) {
            Ok(ahead) => Self::Ahead(ahead),
            Err(reader) => Self::Direct { reader, buffer: vec![0; chunk] },
        }
    }

    /// Appends the next chunk to `out`; the number of bytes, 0 at the end.
    pub fn append_to(&mut self, out: &mut Vec<u8>) -> Result<usize> {
        match self {
            Self::Direct { reader, buffer } => {
                let n = fill(&mut **reader, buffer)?;
                out.extend_from_slice(&buffer[..n]);
                Ok(n)
            }
            Self::Ahead(ahead) => {
                let chunk = ahead.next()?;
                out.extend_from_slice(&chunk);
                let n = chunk.len();
                ahead.recycle(chunk);
                Ok(n)
            }
        }
    }
}

pub(crate) struct ReadAhead {
    filled: Receiver<std::io::Result<Vec<u8>>>,
    empty: SyncSender<Vec<u8>>,
    cancel: CancelToken,
    done: bool,
}

impl ReadAhead {
    /// Starts the worker. On failure to spawn a thread the reader is handed back.
    pub fn spawn(
        reader: Box<dyn ReadSeek>,
        chunk: usize,
        cancel: CancelToken,
    ) -> std::result::Result<Self, Box<dyn ReadSeek>> {
        let (filled_tx, filled) = sync_channel(DEPTH);
        let (empty, empty_rx) = sync_channel::<Vec<u8>>(DEPTH + 1);
        for _ in 0..=DEPTH {
            empty.send(vec![0; chunk]).expect("the receiver is alive");
        }
        let worker_cancel = cancel.clone();
        // The worker needs the reader, but a failed spawn must give it back:
        // it travels through a channel that is only read once the thread runs.
        let (reader_tx, reader_rx) = sync_channel::<Box<dyn ReadSeek>>(1);
        let spawned = thread::Builder::new().name("ezfiles-read".into()).spawn(move || {
            let Ok(mut reader) = reader_rx.recv() else { return };
            // Ends when the parser drops its side: either channel then fails.
            while let Ok(mut buffer) = empty_rx.recv() {
                if worker_cancel.is_cancelled() {
                    return;
                }
                buffer.resize(chunk, 0);
                let result = fill(&mut *reader, &mut buffer).map(|n| {
                    buffer.truncate(n);
                    buffer
                });
                let last = result.as_ref().map_or(true, Vec::is_empty);
                if filled_tx.send(result).is_err() || last {
                    return;
                }
            }
        });
        match spawned {
            Ok(_) => {
                reader_tx.send(reader).expect("the worker is waiting for it");
                Ok(Self { filled, empty, cancel, done: false })
            }
            Err(_) => Err(reader),
        }
    }

    /// The next chunk in input order; empty at the end. Hand it back with [`Self::recycle`].
    pub fn next(&mut self) -> Result<Vec<u8>> {
        if self.done {
            return Ok(Vec::new());
        }
        match self.filled.recv() {
            Ok(Ok(buffer)) => {
                self.done = buffer.is_empty();
                Ok(buffer)
            }
            Ok(Err(e)) => {
                self.done = true;
                Err(e.into())
            }
            // The worker only leaves early when cancelled, or if it panicked.
            Err(_) => {
                self.done = true;
                self.cancel.check()?;
                Err(EngineError::new(ErrorKind::Io, "the file could not be read to the end"))
            }
        }
    }

    /// Returns a consumed chunk to the worker for its next read.
    pub fn recycle(&self, buffer: Vec<u8>) {
        if !self.done {
            let _ = self.empty.try_send(buffer);
        }
    }
}

/// Fills `buf` unless the input ends first; the byte count read.
pub(crate) fn fill(reader: &mut dyn ReadSeek, buf: &mut [u8]) -> std::io::Result<usize> {
    let mut filled = 0;
    while filled < buf.len() {
        match reader.read(&mut buf[filled..]) {
            Ok(0) => break,
            Ok(n) => filled += n,
            Err(e) if e.kind() == IoErrorKind::Interrupted => {}
            Err(e) => return Err(e),
        }
    }
    Ok(filled)
}

#[cfg(test)]
mod tests {
    use std::io::{Cursor, Read, Seek, SeekFrom};

    use super::*;

    fn read_all(ahead: &mut ReadAhead) -> Result<Vec<u8>> {
        let mut out = Vec::new();
        loop {
            let chunk = ahead.next()?;
            if chunk.is_empty() {
                return Ok(out);
            }
            out.extend_from_slice(&chunk);
            ahead.recycle(chunk);
        }
    }

    #[test]
    fn chunks_arrive_whole_and_in_order() {
        let data: Vec<u8> = (0..1_000_003u32).map(|i| (i % 251) as u8).collect();
        let mut ahead = ReadAhead::spawn(Box::new(Cursor::new(data.clone())), 4096, CancelToken::default())
            .unwrap_or_else(|_| panic!("spawn"));
        assert_eq!(read_all(&mut ahead).unwrap(), data);
        // The end stays the end.
        assert!(ahead.next().unwrap().is_empty());
    }

    #[test]
    fn both_ways_of_reading_give_the_same_bytes() {
        let data: Vec<u8> = (0..3_000_000u32).map(|i| (i % 253) as u8).collect();
        for size_hint in [10, 2 * READ_AHEAD_MIN_BYTES] {
            let mut chunks =
                Chunks::new(Box::new(Cursor::new(data.clone())), 65_536, size_hint, &CancelToken::default());
            assert_eq!(matches!(chunks, Chunks::Ahead(_)), size_hint >= READ_AHEAD_MIN_BYTES);
            let mut out = Vec::new();
            while chunks.append_to(&mut out).unwrap() > 0 {}
            assert_eq!(out, data);
        }
    }

    #[test]
    fn an_empty_input_ends_at_once() {
        let mut ahead = ReadAhead::spawn(Box::new(Cursor::new(Vec::new())), 4096, CancelToken::default())
            .unwrap_or_else(|_| panic!("spawn"));
        assert!(ahead.next().unwrap().is_empty());
    }

    struct Failing(usize);

    impl Read for Failing {
        fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            if self.0 == 0 {
                return Err(std::io::Error::other("disk gone"));
            }
            let n = buf.len().min(self.0);
            self.0 -= n;
            buf[..n].fill(7);
            Ok(n)
        }
    }

    impl Seek for Failing {
        fn seek(&mut self, _: SeekFrom) -> std::io::Result<u64> {
            Ok(0)
        }
    }

    #[test]
    fn a_read_error_reaches_the_parser_after_the_good_chunks() {
        let mut ahead = ReadAhead::spawn(Box::new(Failing(10_000)), 4096, CancelToken::default())
            .unwrap_or_else(|_| panic!("spawn"));
        assert_eq!(ahead.next().unwrap().len(), 4096);
        assert_eq!(ahead.next().unwrap().len(), 4096);
        assert!(ahead.next().is_err());
        assert!(ahead.next().unwrap().is_empty());
    }

    #[test]
    fn cancelling_stops_the_worker_and_reports_cancellation() {
        let cancel = CancelToken::default();
        let mut ahead = ReadAhead::spawn(Box::new(Cursor::new(vec![1u8; 1 << 20])), 4096, cancel.clone())
            .unwrap_or_else(|_| panic!("spawn"));
        cancel.cancel();
        let outcome = loop {
            match ahead.next() {
                Ok(chunk) if chunk.is_empty() => break Ok(()),
                Ok(chunk) => ahead.recycle(chunk),
                Err(e) => break Err(e),
            }
        };
        assert!(matches!(&outcome, Err(e) if e.kind == ErrorKind::Cancelled), "{outcome:?}");
    }
}
