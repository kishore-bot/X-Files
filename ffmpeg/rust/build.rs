//! Builds the bridge against one Android ABI's FFmpeg: generates bindings from its headers and links its shared
//! libraries dynamically, so they stay separate, replaceable LGPL files. Nothing happens off Android, which keeps the
//! pure logic testable on the host.

use std::env;
use std::path::{Path, PathBuf};

const FFMPEG_LIBS: [&str; 3] = ["swresample", "avcodec", "avutil"];

const WRAPPER: &str = "\
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
";

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-env-changed=FFMPEG_DIR");
    println!("cargo:rerun-if-env-changed=ANDROID_NDK_HOME");
    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("android") {
        return;
    }

    let arch = env::var("CARGO_CFG_TARGET_ARCH").expect("cargo sets CARGO_CFG_TARGET_ARCH");
    let abi = match arch.as_str() {
        "aarch64" => "arm64-v8a",
        "x86_64" => "x86_64",
        other => panic!("unsupported Android architecture {other}"),
    };
    let ffmpeg = ffmpeg_root().join(abi);
    for lib in FFMPEG_LIBS {
        let so = ffmpeg.join("lib").join(format!("lib{lib}.so"));
        assert!(so.exists(), "missing {}: run scripts/build-ffmpeg.sh first", so.display());
    }

    println!("cargo:rustc-link-search=native={}", ffmpeg.join("lib").display());
    for lib in FFMPEG_LIBS {
        println!("cargo:rustc-link-lib=dylib={lib}");
    }
    // 16 KB pages for Android 15+ devices. -Bsymbolic on arm64 matches the bridge this replaces.
    println!("cargo:rustc-cdylib-link-arg=-Wl,-z,max-page-size=16384");
    if arch == "aarch64" {
        println!("cargo:rustc-cdylib-link-arg=-Wl,-Bsymbolic");
    }

    let target = env::var("TARGET").expect("cargo sets TARGET");
    let prebuilt = ndk_prebuilt();
    let bindings = bindgen::Builder::default()
        .header_contents("wrapper.h", WRAPPER)
        .clang_args([
            format!("--target={target}"),
            format!("--sysroot={}", prebuilt.join("sysroot").display()),
            format!("-isystem{}", clang_builtin_include(&prebuilt).display()),
            format!("-I{}", ffmpeg.join("include").display()),
        ])
        .rust_edition(bindgen::RustEdition::Edition2024)
        .default_enum_style(bindgen::EnumVariation::Consts)
        .prepend_enum_name(false)
        .size_t_is_usize(true)
        .layout_tests(false)
        .generate_comments(false)
        .allowlist_function("avcodec_alloc_context3")
        .allowlist_function("avcodec_free_context")
        .allowlist_function("avcodec_open2")
        .allowlist_function("avcodec_find_decoder")
        .allowlist_function("avcodec_find_decoder_by_name")
        .allowlist_function("avcodec_send_packet")
        .allowlist_function("avcodec_receive_frame")
        .allowlist_function("avcodec_flush_buffers")
        .allowlist_function("av_packet_alloc")
        .allowlist_function("av_packet_free")
        .allowlist_function("av_frame_alloc")
        .allowlist_function("av_frame_free")
        .allowlist_function("av_strerror")
        .allowlist_function("av_get_bytes_per_sample")
        .allowlist_function("av_channel_layout_default")
        .allowlist_function("av_malloc")
        .allowlist_function("avcodec_version")
        .allowlist_function("swr_alloc_set_opts2")
        .allowlist_function("swr_init")
        .allowlist_function("swr_free")
        .allowlist_function("swr_convert")
        .allowlist_function("swr_get_out_samples")
        .allowlist_type("AVCodecContext")
        .allowlist_type("AVCodec")
        .allowlist_type("AVPacket")
        .allowlist_type("AVFrame")
        .allowlist_type("AVChannelLayout")
        .allowlist_type("SwrContext")
        .allowlist_type("AVCodecID")
        .allowlist_type("AVSampleFormat")
        .allowlist_var("AV_INPUT_BUFFER_PADDING_SIZE")
        .allowlist_var("AV_EF_IGNORE_ERR")
        .generate()
        .expect("bindgen failed on the FFmpeg headers");

    let out = PathBuf::from(env::var("OUT_DIR").expect("cargo sets OUT_DIR"));
    bindings
        .write_to_file(out.join("ffmpeg_bindings.rs"))
        .expect("writing FFmpeg bindings");
}

/// Directory holding `<abi>/include` and `<abi>/lib`. CI and the Gradle build pass it explicitly.
fn ffmpeg_root() -> PathBuf {
    match env::var_os("FFMPEG_DIR") {
        Some(dir) => PathBuf::from(dir),
        None => Path::new(&env::var("CARGO_MANIFEST_DIR").expect("cargo sets CARGO_MANIFEST_DIR"))
            .join("../../.cache/ffmpeg-7.1.2"),
    }
}

/// The NDK's LLVM prebuilt directory for this host, holding the bionic sysroot and clang's builtin headers.
fn ndk_prebuilt() -> PathBuf {
    let ndk = env::var_os("ANDROID_NDK_HOME").expect("ANDROID_NDK_HOME must point at the NDK");
    let root = Path::new(&ndk).join("toolchains/llvm/prebuilt");
    std::fs::read_dir(&root)
        .unwrap_or_else(|e| panic!("reading {}: {e}", root.display()))
        .flatten()
        .map(|entry| entry.path())
        .find(|dir| dir.join("sysroot").is_dir())
        .unwrap_or_else(|| panic!("no LLVM sysroot under {}", root.display()))
}

/// clang's resource headers (stddef.h and friends), which the NDK ships under lib/clang/<version>/include.
fn clang_builtin_include(prebuilt: &Path) -> PathBuf {
    let clang = prebuilt.join("lib/clang");
    std::fs::read_dir(&clang)
        .unwrap_or_else(|e| panic!("reading {}: {e}", clang.display()))
        .flatten()
        .map(|entry| entry.path().join("include"))
        .find(|dir| dir.join("stddef.h").is_file())
        .unwrap_or_else(|| panic!("no clang builtin headers under {}", clang.display()))
}
