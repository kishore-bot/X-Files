# Media playback design

## High-level design

Android keeps Media3 and the existing Compose player and ViewModel. The optional FFmpeg extension handles audio formats that need software decoding. iOS keeps AVPlayer and the existing MPVKit compatibility path. Neither platform selects a decoder from a chip-brand allowlist. Device, firmware, track, display, and output route determine what the application can report and enable.

## Low-level design

| Boundary | Behavior |
| --- | --- |
| Android decoder recovery | Begin with MediaCodec preferred and FFmpeg available as an extension. An eligible fatal audio renderer or AudioTrack error can recreate the player once with FFmpeg preferred. Carry the media item, position, selected track overrides, and play intent. The playback sheet can change the preference manually. |
| Android output monitor | The player session owns analytics, route and spatializer listeners, the optional effect, and USB mixer preference. It reads decoder names and AudioTrack format from Media3. Android's route API reports the output that would be used for the media attributes. The UI identifies this as the system route. |
| Android color | Use SurfaceView for a signaled PQ, HLG, Dolby Vision or HDR10+ stream. File Details shows signaled color, any HDR10+ decoder metadata, and the current display's advertised HDR modes. Actual HDR output remains unverified. The optional SDR adjustment uses a small color filter on TextureView and is unavailable for HDR, unknown color, and Picture-in-Picture. |
| Android audio effects | A session-scoped LoudnessEnhancer applies an opt-in +3 dB target on PCM where supported. It is released with the session and bypassed during passthrough, offload, or experimental USB direct output. The OS owns spatial processing. Xfiles reports availability and head-tracking capability. |
| Automatic Android USB lossless | Prefer Android 14's USB bit-perfect mixer automatically when the active USB route supports the source's mono or stereo PCM, and the sample rate, channels and precision match the player output. This includes FLAC or ALAC with known PCM precision. Require unit speed and pitch, unity player volume, and audio processing off. Clear the request when the route or settings change. The player shows a USB Lossless chip once Android reports the matching mixer preference. End-to-end output quality remains unverified. |
| iOS native and compatibility paths | Inspect AVAsset tracks, codecs, channel counts, and available source format information. Fall back for a native format failure or an explicitly unplayable audio track. Use the system's spatial playback configuration and route reporting on AVPlayer. Both paths show the route and session rate, and the compatibility path does not claim spatial processing. AVPlayer requests the known lossless source sample rate on a single USB route by default. The player reports when the session rate matches and restores the previous preference when leaving that route or playback. |
| Distribution | Dev Debug and release CI both validate the four FFmpeg libraries by ABI, native ELF machine, dynamic dependencies, load-segment alignment and APK zip alignment. The app also reports whether the FFmpeg library loads at runtime. |

## Device validation

1. On the Nothing Phone (3a) Pro, confirm the installed artifact's version and inspect the multilingual MKV with FFprobe. Check every language on the speaker and earbuds, and record the decoder, AudioTrack format and route shown by File Details. Use public, redistributable samples for CI.
2. Cover Snapdragon midrange and flagship, Dimensity 7300 and flagship, Exynos Galaxy, Tensor Pixel, an entry-level UNISOC device, and an x86_64 emulator. Cover older supported A-series, A17 Pro and a newer A-series device, plus an M-series iPad. Record exact model and firmware.
3. Verify H.264 and HEVC, AV1 where available, plus HDR10, HDR10+, HLG and Dolby Vision profiles. Check an SDR display, fullscreen, zoom, captions, orientation, and Picture-in-Picture. Record first-frame time, frame drops, A/V sync and sustained playback.
4. Verify AC‑3, E‑AC‑3, DTS, TrueHD, AAC, FLAC, ALAC and PCM. Compare source metadata to the actual platform route. Exercise wired, wireless, HDMI where applicable, and USB DAC. Verify 5.1 and 7.1 with known channel identifiers. Measure digital output against a known PCM fixture before promoting the USB option or adding a bit-perfect badge.
5. Check audio boost for clipping, transient behavior and route changes. Confirm that wireless quality remains unverified where negotiated lossless operation cannot be established. iOS boost controls require a validated gain or SDR effects path and are not part of this implementation.

## Primary references

- [Media3 audio formats](https://developer.android.com/media/media3/exoplayer/supported-formats) and [FFmpeg extension](https://github.com/androidx/media/tree/1.11.1/libraries/decoder_ffmpeg)
- [Media3 surface behavior](https://developer.android.com/media/media3/ui/surface) and [Android Spatializer](https://developer.android.com/reference/android/media/Spatializer)
- [Android USB mixer attributes](https://source.android.com/docs/core/audio/preferred-mixer-attr) and [16 KiB compatibility](https://developer.android.com/guide/practices/page-sizes)
- [AVPlayer HDR modes](https://developer.apple.com/documentation/avfoundation/avplayer/availablehdrmodes) and [AVPlayer audio spatialization](https://developer.apple.com/documentation/avfoundation/avplayeritem/allowedaudiospatializationformats)
