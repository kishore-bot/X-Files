//! Preloaded text cues for a signed offset against the media clock.
use document_core::{EngineError, Result};

pub const MAX_BYTES: usize = 4 * 1024 * 1024;
const MAX_CUES: usize = 50_000;

pub struct Cue {
    pub start_ms: u64,
    pub end_ms: u64,
    pub text: String,
}

/// Parse SRT or WebVTT sidecars. Cue positioning is owned by each platform's subtitle overlay.
pub fn parse(input: &str) -> Result<Vec<Cue>> {
    if input.len() > MAX_BYTES {
        return Err(EngineError::limit("Subtitle files may be at most 4 MiB"));
    }
    let normalized = input.trim_start_matches('\u{feff}').replace("\r\n", "\n").replace('\r', "\n");
    let mut cues = Vec::new();
    for block in normalized.split("\n\n") {
        let mut lines = block.lines().map(str::trim).filter(|line| !line.is_empty());
        let Some(first) = lines.next() else { continue };
        if first.starts_with("WEBVTT")
            || first == "NOTE"
            || first.starts_with("NOTE ")
            || matches!(first, "STYLE" | "REGION")
        {
            continue;
        }
        let timing = if first.contains("-->") { first } else { lines.next().unwrap_or("") };
        let Some((start, end)) = timing.split_once("-->") else { continue };
        let Some(start_ms) = timestamp(start.trim()) else { continue };
        let Some(end_ms) = timestamp(end.split_whitespace().next().unwrap_or("")) else { continue };
        if end_ms <= start_ms {
            continue;
        }
        let text = plain_text(&lines.collect::<Vec<_>>().join("\n"));
        if !text.is_empty() {
            if cues.len() == MAX_CUES {
                return Err(EngineError::limit("Subtitle file has too many cues"));
            }
            cues.push(Cue { start_ms, end_ms, text });
        }
    }
    if cues.is_empty() {
        return Err(EngineError::malformed("No timed subtitles were found"));
    }
    cues.sort_by_key(|cue| cue.start_ms);
    Ok(cues)
}

fn timestamp(value: &str) -> Option<u64> {
    let (seconds, fraction) = value.split_once(['.', ','])?;
    if fraction.len() != 3 || !fraction.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let mut components = seconds.rsplit(':');
    let secs = components.next()?.parse::<u64>().ok()?;
    let minutes = components.next()?.parse::<u64>().ok()?;
    let hours = match components.next() {
        Some(hours) => hours.parse::<u64>().ok()?,
        None => 0,
    };
    if components.next().is_some() || minutes >= 60 || secs >= 60 || hours >= 1000 {
        return None;
    }
    Some(((hours * 60 + minutes) * 60 + secs) * 1000 + fraction.parse::<u64>().ok()?)
}

fn plain_text(input: &str) -> String {
    let mut text = String::new();
    let mut rest = input;
    while let Some((before, tag)) = rest.split_once('<') {
        text.push_str(before);
        let Some((tag, after)) = tag.split_once('>') else {
            text.push('<');
            rest = tag;
            break;
        };
        if tag.eq_ignore_ascii_case("br") || tag.eq_ignore_ascii_case("br/") {
            text.push('\n');
        }
        rest = after;
    }
    text.push_str(rest);
    text.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", "\u{a0}")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

#[cfg(test)]
mod tests {
    use super::parse;

    #[test]
    fn reads_srt_and_webvtt_timestamps_and_text() {
        let srt = parse("1\r\n00:00:10,000 --> 00:00:12,250\r\n<i>Hello</i>\r\nSecond line").unwrap();
        assert_eq!((srt[0].start_ms, srt[0].end_ms), (10_000, 12_250));
        assert_eq!(srt[0].text, "Hello\nSecond line");

        let vtt =
            parse("WEBVTT\n\nNOTE comment\nignored\n\nname\n00:01.100 --> 00:03.000 align:start\nHello &amp; goodbye")
                .unwrap();
        assert_eq!((vtt[0].start_ms, vtt[0].end_ms), (1_100, 3_000));
        assert_eq!(vtt[0].text, "Hello & goodbye");
    }
}
