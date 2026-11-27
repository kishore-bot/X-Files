use crate::ReadError;
use document_parsers::subtitle_timeline;

#[derive(uniffi::Record)]
pub struct NativeSubtitleCue {
    pub start_ms: u64,
    pub end_ms: u64,
    pub text: String,
}

#[uniffi::export]
pub fn parse_subtitle_timeline(text: String) -> Result<Vec<NativeSubtitleCue>, ReadError> {
    Ok(subtitle_timeline::parse(&text)?
        .into_iter()
        .map(|cue| NativeSubtitleCue { start_ms: cue.start_ms, end_ms: cue.end_ms, text: cue.text })
        .collect())
}
