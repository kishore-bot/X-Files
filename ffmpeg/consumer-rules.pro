# DefaultRenderersFactory creates the FFmpeg renderer by class name.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(...); }
# Called from the Rust JNI bridge (ffmpeg/rust/src/bridge.rs).
-keepclasseswithmembernames class androidx.media3.decoder.ffmpeg.** { native <methods>; }
