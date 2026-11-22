//! The decoder: RAII owners for the FFmpeg objects it holds, and the packet-to-PCM loop that runs on them.

use std::ffi::{CStr, c_char, c_int};
use std::ptr;

use crate::android_log;
use crate::error::{self, DECODER_ERROR_INVALID_DATA, DECODER_ERROR_OTHER};
use crate::ffi;
use crate::format::OutputEncoding;

/// Where decoded PCM goes. `grow` is called when the next write would not fit. It returns the new base address,
/// which must hold the bytes already written, or null on failure.
pub struct Sink<'a> {
    pub base: *mut u8,
    pub capacity: usize,
    pub grow: &'a mut dyn FnMut(usize) -> *mut u8,
}

/// Owns an `AVPacket`. Its payload is borrowed from the caller and is never freed here.
struct Packet(*mut ffi::AVPacket);

impl Packet {
    fn new() -> Option<Self> {
        // SAFETY: av_packet_alloc takes no arguments and returns null on allocation failure.
        let raw = unsafe { ffi::av_packet_alloc() };
        (!raw.is_null()).then_some(Self(raw))
    }

    fn set_payload(&mut self, data: *mut u8, size: c_int) {
        // SAFETY: self.0 is a live packet that only this wrapper touches.
        unsafe {
            (*self.0).data = data;
            (*self.0).size = size;
        }
    }

    fn as_ptr(&self) -> *const ffi::AVPacket {
        self.0
    }
}

impl Drop for Packet {
    fn drop(&mut self) {
        // SAFETY: self.0 came from av_packet_alloc and is freed once. The payload has no buffer reference, so
        // av_packet_free leaves it alone.
        unsafe { ffi::av_packet_free(&mut self.0) };
    }
}

/// Owns an `AVFrame`.
struct Frame(*mut ffi::AVFrame);

impl Frame {
    fn new() -> Option<Self> {
        // SAFETY: av_frame_alloc takes no arguments and returns null on allocation failure.
        let raw = unsafe { ffi::av_frame_alloc() };
        (!raw.is_null()).then_some(Self(raw))
    }

    fn samples(&self) -> c_int {
        // SAFETY: self.0 is a live frame owned by self.
        unsafe { (*self.0).nb_samples }
    }

    fn planes(&self) -> *const *const u8 {
        // SAFETY: self.0 is a live frame owned by self. Reading the plane array does not dereference it.
        unsafe { (*self.0).data.as_ptr().cast() }
    }

    fn as_mut_ptr(&mut self) -> *mut ffi::AVFrame {
        self.0
    }
}

impl Drop for Frame {
    fn drop(&mut self) {
        // SAFETY: self.0 came from av_frame_alloc and is freed once.
        unsafe { ffi::av_frame_free(&mut self.0) };
    }
}

/// Owns an opened `AVCodecContext`.
struct CodecContext(*mut ffi::AVCodecContext);

impl CodecContext {
    fn alloc(codec: *const ffi::AVCodec) -> Option<Self> {
        // SAFETY: codec is a static decoder description from libavcodec.
        let raw = unsafe { ffi::avcodec_alloc_context3(codec) };
        if raw.is_null() {
            android_log::error("Failed to allocate context.");
            return None;
        }
        Some(Self(raw))
    }

    /// Copies codec setup data into FFmpeg-owned memory, zero-padded as FFmpeg requires.
    fn set_extradata(&mut self, bytes: &[u8]) -> Result<(), ()> {
        let padding = ffi::AV_INPUT_BUFFER_PADDING_SIZE as usize;
        let size = c_int::try_from(bytes.len()).map_err(|_| ())?;
        // SAFETY: av_malloc returns a block of at least size + padding bytes, or null.
        let buffer = unsafe { ffi::av_malloc(bytes.len() + padding) }.cast::<u8>();
        if buffer.is_null() {
            android_log::error("Failed to allocate extradata.");
            return Err(());
        }
        // SAFETY: the block just allocated holds size + padding bytes, and nothing else refers to it yet.
        let block = unsafe { std::slice::from_raw_parts_mut(buffer, bytes.len() + padding) };
        block[..bytes.len()].copy_from_slice(bytes);
        block[bytes.len()..].fill(0);
        // FFmpeg frees extradata with av_free when the context is freed, which matches av_malloc.
        let context = self.raw_mut();
        context.extradata = buffer;
        context.extradata_size = size;
        Ok(())
    }

    fn raw(&self) -> &ffi::AVCodecContext {
        // SAFETY: self.0 is a live context owned by self.
        unsafe { &*self.0 }
    }

    fn raw_mut(&mut self) -> &mut ffi::AVCodecContext {
        // SAFETY: self.0 is a live context owned by self, and &mut self makes this the only borrow.
        unsafe { &mut *self.0 }
    }

    fn as_mut_ptr(&mut self) -> *mut ffi::AVCodecContext {
        self.0
    }
}

impl Drop for CodecContext {
    fn drop(&mut self) {
        // SAFETY: self.0 came from avcodec_alloc_context3 and is freed once. This also frees extradata.
        unsafe { ffi::avcodec_free_context(&mut self.0) };
    }
}

/// Owns an `SwrContext` that converts decoded frames to the requested output encoding.
struct Resampler(*mut ffi::SwrContext);

impl Resampler {
    /// Input and output share the decoder's channel layout and sample rate. Only the sample format changes.
    fn open(
        layout: &ffi::AVChannelLayout,
        encoding: OutputEncoding,
        input_format: c_int,
        sample_rate: c_int,
    ) -> Result<Self, c_int> {
        let mut raw = ptr::null_mut();
        // SAFETY: layout is live for the call, and swresample copies it.
        let ret = unsafe {
            ffi::swr_alloc_set_opts2(
                &mut raw,
                layout,
                encoding.sample_format(),
                sample_rate,
                layout,
                input_format,
                sample_rate,
                0,
                ptr::null_mut(),
            )
        };
        // From here on the wrapper owns `raw`, so every early return below frees it.
        let resampler = Self(raw);
        if ret < 0 {
            log_av_error("swr_alloc_set_opts2", ret);
            return Err(ret);
        }
        // SAFETY: resampler.0 was just allocated and configured.
        let ret = unsafe { ffi::swr_init(resampler.0) };
        if ret < 0 {
            log_av_error("swr_init", ret);
            return Err(ret);
        }
        Ok(resampler)
    }

    /// Upper bound on the output samples for `input_samples`, or a negative error.
    fn out_samples(&mut self, input_samples: c_int) -> c_int {
        // SAFETY: self.0 is configured and live.
        unsafe { ffi::swr_get_out_samples(self.0, input_samples) }
    }

    /// Returns the samples per channel written, or a negative error.
    fn convert(&mut self, out: *mut u8, out_samples: c_int, input: *const *const u8, input_samples: c_int) -> c_int {
        let mut out_plane = out;
        // SAFETY: out has room for out_samples per channel, as the caller sized it from out_samples(). input holds the
        // planes of a frame that is live for the call.
        unsafe {
            ffi::swr_convert(
                self.0,
                &mut out_plane as *mut *mut u8 as *const *mut u8,
                out_samples,
                input,
                input_samples,
            )
        }
    }

    /// Samples still buffered inside the resampler. Must be zero after every frame.
    fn pending_samples(&mut self) -> c_int {
        self.out_samples(0)
    }
}

impl Drop for Resampler {
    fn drop(&mut self) {
        // SAFETY: self.0 came from swr_alloc_set_opts2 or is null, and is freed once.
        unsafe { ffi::swr_free(&mut self.0) };
    }
}

/// A decoder instance. Java holds it as a `Box<Decoder>` pointer; `bridge` owns that box and frees it once.
pub struct Decoder {
    // Declared before `codec` so it is freed first, as the former bridge's release did.
    resampler: Option<Resampler>,
    codec: CodecContext,
}

impl Decoder {
    /// Opens a decoder for `codec`. Raw rate and channel count only apply to the PCM mu-law and A-law codecs.
    pub fn open(
        codec: *const ffi::AVCodec,
        extradata: Option<&[u8]>,
        encoding: OutputEncoding,
        raw_sample_rate: c_int,
        raw_channel_count: c_int,
    ) -> Option<Self> {
        let mut codec_context = CodecContext::alloc(codec)?;
        codec_context.raw_mut().request_sample_fmt = encoding.sample_format();
        if let Some(extradata) = extradata {
            codec_context.set_extradata(extradata).ok()?;
        }
        let codec_id = codec_context.raw().codec_id;
        if codec_id == ffi::AV_CODEC_ID_PCM_MULAW || codec_id == ffi::AV_CODEC_ID_PCM_ALAW {
            let context = codec_context.raw_mut();
            context.sample_rate = raw_sample_rate;
            // SAFETY: the layout belongs to the live context; av_channel_layout_default overwrites it.
            unsafe { ffi::av_channel_layout_default(&mut context.ch_layout, raw_channel_count) };
        }
        codec_context.raw_mut().err_recognition = ffi::AV_EF_IGNORE_ERR as c_int;
        // SAFETY: the context is allocated for this codec and not yet open.
        let ret = unsafe { ffi::avcodec_open2(codec_context.as_mut_ptr(), codec, ptr::null_mut()) };
        if ret < 0 {
            log_av_error("avcodec_open2", ret);
            return None;
        }
        Some(Self { resampler: None, codec: codec_context })
    }

    pub fn codec_id(&self) -> ffi::AVCodecID {
        self.codec.raw().codec_id
    }

    pub fn encoding(&self) -> OutputEncoding {
        OutputEncoding::from_sample_format(self.codec.raw().request_sample_fmt)
    }

    pub fn channel_count(&self) -> c_int {
        self.codec.raw().ch_layout.nb_channels
    }

    pub fn sample_rate(&self) -> c_int {
        self.codec.raw().sample_rate
    }

    pub fn flush(&mut self) {
        // SAFETY: the context is open and live.
        unsafe { ffi::avcodec_flush_buffers(self.codec.as_mut_ptr()) };
    }

    /// Decodes one packet into `sink` and returns the bytes written, or a negative decoder error code.
    /// `input` may be null only when `input_size` is zero, which tells FFmpeg to flush.
    pub fn decode(&mut self, input: *mut u8, input_size: c_int, sink: &mut Sink<'_>) -> c_int {
        let Some(mut packet) = Packet::new() else {
            android_log::error("Failed to allocate packet.");
            return DECODER_ERROR_INVALID_DATA;
        };
        packet.set_payload(input, input_size);

        // SAFETY: the context is open, and the packet's payload stays valid until this call returns.
        let ret = unsafe { ffi::avcodec_send_packet(self.codec.as_mut_ptr(), packet.as_ptr()) };
        if ret != 0 {
            log_av_error("avcodec_send_packet", ret);
            return error::transform(ret);
        }

        let mut written = 0usize;
        loop {
            let Some(mut frame) = Frame::new() else {
                android_log::error("Failed to allocate output frame.");
                return DECODER_ERROR_INVALID_DATA;
            };
            // SAFETY: the context is open and the frame is freshly allocated.
            let ret = unsafe { ffi::avcodec_receive_frame(self.codec.as_mut_ptr(), frame.as_mut_ptr()) };
            if ret != 0 {
                if ret == error::AVERROR_EAGAIN {
                    break;
                }
                log_av_error("avcodec_receive_frame", ret);
                return error::transform(ret);
            }

            let (channels, sample_rate, input_format, encoding) = {
                let context = self.codec.raw();
                (
                    context.ch_layout.nb_channels,
                    context.sample_rate,
                    context.sample_fmt,
                    OutputEncoding::from_sample_format(context.request_sample_fmt),
                )
            };
            if self.resampler.is_none() {
                let layout = &self.codec.raw().ch_layout;
                match Resampler::open(layout, encoding, input_format, sample_rate) {
                    Ok(resampler) => self.resampler = Some(resampler),
                    Err(code) => return error::transform(code),
                }
            }
            let Some(resampler) = self.resampler.as_mut() else {
                return DECODER_ERROR_INVALID_DATA;
            };

            let input_samples = frame.samples();
            let out_samples = resampler.out_samples(input_samples);
            if out_samples < 0 {
                log_av_error("swr_get_out_samples", out_samples);
                return DECODER_ERROR_INVALID_DATA;
            }
            let out_bytes = encoding.bytes_per_sample() * channels.max(0) as usize * out_samples as usize;
            let required = written + out_bytes;
            if required > sink.capacity {
                android_log::debug(&format!(
                    "Output buffer size ({}) too small for output data ({required}), reallocating buffer.",
                    sink.capacity
                ));
                let grown = (sink.grow)(required);
                if grown.is_null() {
                    android_log::error("Failed to reallocate output buffer.");
                    return DECODER_ERROR_OTHER;
                }
                sink.base = grown;
                sink.capacity = required;
            }

            // SAFETY: base + written lies inside the capacity checked above.
            let destination = unsafe { sink.base.add(written) };
            let converted = resampler.convert(destination, out_samples, frame.planes(), input_samples);
            if converted < 0 {
                log_av_error("swr_convert", converted);
                return DECODER_ERROR_INVALID_DATA;
            }
            let pending = resampler.pending_samples();
            if pending != 0 {
                android_log::error(&format!("Expected no samples remaining after resampling, but found {pending}."));
                return DECODER_ERROR_INVALID_DATA;
            }
            written = required;
        }
        c_int::try_from(written).unwrap_or(DECODER_ERROR_OTHER)
    }
}

/// Logs an FFmpeg error with its description, as the former bridge's logError did.
fn log_av_error(function: &str, code: c_int) {
    let mut buffer = [0 as c_char; 256];
    // SAFETY: the buffer is writable for its full length, and av_strerror NUL-terminates within it.
    unsafe { ffi::av_strerror(code, buffer.as_mut_ptr(), buffer.len()) };
    // SAFETY: av_strerror NUL-terminated the buffer above.
    let description = unsafe { CStr::from_ptr(buffer.as_ptr()) }.to_string_lossy();
    android_log::error(&format!("Error in {function}: {description}"));
}
