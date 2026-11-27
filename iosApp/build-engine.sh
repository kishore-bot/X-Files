#!/usr/bin/env bash
set -euo pipefail

# Xcode supplies the destination, deployment target, and requested architectures.
export PATH="$HOME/.cargo/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"
command -v cargo >/dev/null || { echo "error: Install rustup and the Rust targets listed in the root README.md under Build from source." >&2; exit 1; }

engine="$(cd "$SRCROOT/../engine" && pwd)"
derived="${DERIVED_FILE_DIR:?Xcode derived-files directory is required}"
target_dir="$derived/rust"
output="$derived/libXfilesEngine.a"
mkdir -p "$derived"

libraries=()
for arch in ${ARCHS:?Xcode architectures are required}; do
    case "$PLATFORM_NAME:$arch" in
        iphoneos:arm64) target=aarch64-apple-ios ;;
        iphonesimulator:arm64) target=aarch64-apple-ios-sim ;;
        iphonesimulator:x86_64) target=x86_64-apple-ios ;;
        *) echo "error: Unsupported Rust destination $PLATFORM_NAME/$arch." >&2; exit 1 ;;
    esac
    (cd "$engine" && cargo rustc --locked --release -p document-ffi --lib --crate-type staticlib --target "$target" --target-dir "$target_dir")
    libraries+=("$target_dir/$target/release/libdocument_ffi.a")
done

xcrun lipo -create "${libraries[@]}" -output "$output"
