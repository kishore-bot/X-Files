//! Rust JNI bridge for Media3's FFmpeg audio decoder (Java side: androidx/media, Apache-2.0). It replaces the former
//! C++ ffmpeg_jni.cc with the same JNI surface and error codes. FFmpeg is linked dynamically from its own shared
//! libraries.

// The crate is named after the library Java loads (libffmpegJNI.so), which is not snake case.
#![allow(non_snake_case)]

pub mod error;
pub mod format;
pub mod version;

#[cfg(target_os = "android")]
mod android_log;
#[cfg(target_os = "android")]
mod bridge;
#[cfg(target_os = "android")]
mod decoder;
#[cfg(target_os = "android")]
#[allow(non_upper_case_globals, non_camel_case_types, non_snake_case, dead_code)]
mod ffi {
    include!(concat!(env!("OUT_DIR"), "/ffmpeg_bindings.rs"));

    // The pure modules hardcode these values, so a header change fails the build instead of silently mismatching.
    const _: () = assert!(AV_SAMPLE_FMT_S16 == crate::format::AV_SAMPLE_FMT_S16);
    const _: () = assert!(AV_SAMPLE_FMT_FLT == crate::format::AV_SAMPLE_FMT_FLT);
}
