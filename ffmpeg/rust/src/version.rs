//! The libavcodec version string Java shows, equal to FFmpeg's `LIBAVCODEC_IDENT`. Pure, so it is unit tested on the
//! host.

/// Formats a packed `avcodec_version()` value (major << 16 | minor << 8 | micro) as `LIBAVCODEC_IDENT` does.
pub fn libavcodec_ident(packed_version: u32) -> String {
    format!(
        "Lavc{}.{}.{}",
        packed_version >> 16,
        (packed_version >> 8) & 0xff,
        packed_version & 0xff
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn formats_packed_version_like_the_ffmpeg_ident_macro() {
        // libavcodec 61.19.101
        assert_eq!(libavcodec_ident((61 << 16) | (19 << 8) | 101), "Lavc61.19.101");
    }
}
