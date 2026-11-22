//! The PCM encodings the bridge outputs, matching Java's AudioFormat.ENCODING_PCM_16BIT and ENCODING_PCM_FLOAT.
//! Pure, so it is unit tested on the host.

/// `AV_SAMPLE_FMT_S16`. The FFI module asserts that the bindings agree.
pub const AV_SAMPLE_FMT_S16: i32 = 1;
/// `AV_SAMPLE_FMT_FLT`.
pub const AV_SAMPLE_FMT_FLT: i32 = 3;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum OutputEncoding {
    Pcm16Bit,
    PcmFloat,
}

impl OutputEncoding {
    pub const fn from_output_float(output_float: bool) -> Self {
        if output_float { Self::PcmFloat } else { Self::Pcm16Bit }
    }

    /// Any format other than float is output as 16-bit, as the decoder only ever requests one of the two.
    pub const fn from_sample_format(sample_format: i32) -> Self {
        if sample_format == AV_SAMPLE_FMT_FLT { Self::PcmFloat } else { Self::Pcm16Bit }
    }

    pub const fn sample_format(self) -> i32 {
        match self {
            Self::Pcm16Bit => AV_SAMPLE_FMT_S16,
            Self::PcmFloat => AV_SAMPLE_FMT_FLT,
        }
    }

    pub const fn bytes_per_sample(self) -> usize {
        match self {
            Self::Pcm16Bit => 2,
            Self::PcmFloat => 4,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sample_format_round_trips() {
        for encoding in [OutputEncoding::Pcm16Bit, OutputEncoding::PcmFloat] {
            assert_eq!(OutputEncoding::from_sample_format(encoding.sample_format()), encoding);
        }
    }

    #[test]
    fn output_float_selects_float_format() {
        assert_eq!(OutputEncoding::from_output_float(true), OutputEncoding::PcmFloat);
        assert_eq!(OutputEncoding::from_output_float(false), OutputEncoding::Pcm16Bit);
    }

    #[test]
    fn bytes_per_sample_matches_ffmpeg_sample_formats() {
        assert_eq!(OutputEncoding::Pcm16Bit.bytes_per_sample(), 2);
        assert_eq!(OutputEncoding::PcmFloat.bytes_per_sample(), 4);
    }

    #[test]
    fn unknown_sample_formats_fall_back_to_16_bit() {
        assert_eq!(OutputEncoding::from_sample_format(AV_SAMPLE_FMT_S16), OutputEncoding::Pcm16Bit);
        assert_eq!(OutputEncoding::from_sample_format(4), OutputEncoding::Pcm16Bit);
    }
}
