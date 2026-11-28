# DefaultRenderersFactory creates the FFmpeg renderer by class name.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(...); }
# Called from the Rust JNI bridge (ffmpeg/rust/src/bridge.rs).
-keepclasseswithmembernames class androidx.media3.decoder.ffmpeg.** { native <methods>; }
# The bridge calls this Java method back by name and signature. JNI_OnLoad looks it up first and refuses to load if
# it is missing, so without this rule R8 drops it as unused and FFmpeg audio is silently off in release builds only.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder {
    private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}
# Named in that JNI signature, so it must keep its name.
-keepnames class androidx.media3.decoder.SimpleDecoderOutputBuffer
