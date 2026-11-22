//! Maps FFmpeg error numbers to the error codes Java's FfmpegAudioDecoder expects. Pure, so it is unit tested on the
//! host.

/// Java's `AUDIO_DECODER_ERROR_INVALID_DATA`. Non-fatal: the decoder skips the packet.
pub const DECODER_ERROR_INVALID_DATA: i32 = -1;
/// Java's `AUDIO_DECODER_ERROR_OTHER`. Fatal for the decoder.
pub const DECODER_ERROR_OTHER: i32 = -2;

/// `AVERROR(EAGAIN)`: FFmpeg wants more input before it can produce a frame.
pub const AVERROR_EAGAIN: i32 = -libc::EAGAIN;
/// `AVERROR_INVALIDDATA` = `FFERRTAG('I','N','D','A')`, that is the negated little-endian tag.
pub const AVERROR_INVALID_DATA: i32 = averror_tag(*b"INDA");
/// `AVERROR_EOF` = `FFERRTAG('E','O','F',' ')`.
pub const AVERROR_EOF: i32 = averror_tag(*b"EOF ");

const fn averror_tag(tag: [u8; 4]) -> i32 {
    -i32::from_le_bytes(tag)
}

/// Converts an FFmpeg error number into a decoder error code.
pub const fn transform(av_error: i32) -> i32 {
    if av_error == AVERROR_INVALID_DATA {
        DECODER_ERROR_INVALID_DATA
    } else {
        DECODER_ERROR_OTHER
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn error_tags_match_ffmpeg_values() {
        assert_eq!(AVERROR_INVALID_DATA, -1_094_995_529);
        assert_eq!(AVERROR_EOF, -541_478_725);
    }

    #[test]
    fn invalid_data_is_non_fatal_and_everything_else_is_fatal() {
        assert_eq!(transform(AVERROR_INVALID_DATA), DECODER_ERROR_INVALID_DATA);
        assert_eq!(transform(AVERROR_EOF), DECODER_ERROR_OTHER);
        assert_eq!(transform(AVERROR_EAGAIN), DECODER_ERROR_OTHER);
    }
}
