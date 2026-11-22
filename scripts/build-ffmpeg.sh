#!/usr/bin/env bash
# Builds FFmpeg's audio decoders for the video player (Media3 FFmpeg extension), as LGPL shared
# libraries: libavutil, libswresample, libavcodec. No GPL or non-free parts are enabled, and the
# libraries stay separate .so files so they can be replaced, as the LGPL requires.
#
#   scripts/build-ffmpeg.sh            # builds into .cache/ffmpeg-<version>/<abi>/{include,lib}
#
# Needs: bash (WSL, Linux, macOS or Git Bash), curl, tar with xz, and the Android NDK (ANDROID_HOME or local.properties).
set -euo pipefail

VERSION=7.1.2
SHA256=089bc60fb59d6aecc5d994ff530fd0dcb3ee39aa55867849a2bbc4e555f9c304
ABIS=(arm64-v8a x86_64)
API=30
DECODERS=(aac mp3 mp2 ac3 eac3 truehd mlp dca vorbis opus amrnb amrwb flac alac pcm_mulaw pcm_alaw)

# C:/style paths on Windows: the NDK's make.exe can't read /c/... paths.
ROOT="$(cd "$(dirname "$0")/.." && (pwd -W 2>/dev/null || pwd))"
OUT="$ROOT/.cache/ffmpeg-$VERSION"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$SDK" ]] || SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" | sed 's/\\:/:/g; s/\\\\/\//g')
NDK_VERSION=$(sed -n 's/^ndk = "\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml")
NDK="$SDK/ndk/$NDK_VERSION"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64; EXE=.exe ;;
  Darwin) HOST=darwin-x86_64; EXE= ;;
  *) HOST=linux-x86_64; EXE= ;;
esac
TOOLS="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
[[ -d "$TOOLS" ]] || { echo "NDK toolchain not found: $TOOLS" >&2; exit 1; }
MAKE="$NDK/prebuilt/$HOST/bin/make$EXE"
[[ -x "$MAKE" ]] || MAKE=make

mkdir -p "$OUT/tmp"
# FFmpeg's configure needs a POSIX temp path (Windows TEMP has backslashes).
export TMPDIR="$OUT/tmp"
SRC="$OUT/src"
if [[ ! -f "$SRC/configure" ]]; then
  ARCHIVE="$OUT/ffmpeg-$VERSION.tar.xz"
  [[ -f "$ARCHIVE" ]] || curl -fsSL -o "$ARCHIVE" "https://ffmpeg.org/releases/ffmpeg-$VERSION.tar.xz"
  echo "$SHA256  $ARCHIVE" | sha256sum -c -
  mkdir -p "$SRC"
  tar -xJf "$ARCHIVE" -C "$SRC" --strip-components=1
fi

# What "make install" would do, by plain copies: its install(1) calls fail under the NDK's
# Windows make. Public headers (plus the two generated ones) and the three shared libraries.
install_abi() {
  local abi=$1 build="$OUT/build-$1" dest="$OUT/$1"
  for lib in libavutil libavcodec libswresample; do
    mkdir -p "$dest/include/$lib" "$dest/lib"
    cp "$SRC/$lib"/*.h "$dest/include/$lib/"
    cp "$build/$lib/$lib.so" "$dest/lib/"
  done
  cp "$build/libavutil/avconfig.h" "$build/libavutil/ffversion.h" "$dest/include/libavutil/"
}

for ABI in "${ABIS[@]}"; do
  if [[ -f "$OUT/$ABI/lib/libavcodec.so" ]]; then echo "$ABI: already built"; continue; fi
  case "$ABI" in
    arm64-v8a) ARCH=aarch64; TRIPLE=aarch64-linux-android; EXTRA=(--enable-neon) ;;
    x86_64) ARCH=x86_64; TRIPLE=x86_64-linux-android; EXTRA=(--disable-x86asm) ;;
  esac
  BUILD="$OUT/build-$ABI"
  rm -rf "$BUILD" && mkdir -p "$BUILD" && cd "$BUILD"
  CONFIGURE=(
    --prefix="$OUT/$ABI" --target-os=android --arch="$ARCH" --enable-cross-compile
    --cc="$TOOLS/clang$EXE" --cxx="$TOOLS/clang++$EXE" --ld="$TOOLS/clang$EXE"
    # No host program runs with these options (no hardcoded tables), so the NDK clang can stand in.
    --host-cc="$TOOLS/clang$EXE" --host-cflags="--target=$TRIPLE$API" --host-ld="$TOOLS/clang$EXE" --host-ldflags="--target=$TRIPLE$API"
    --ar="$TOOLS/llvm-ar$EXE" --nm="$TOOLS/llvm-nm$EXE" --ranlib="$TOOLS/llvm-ranlib$EXE" --strip="$TOOLS/llvm-strip$EXE"
    --extra-cflags="--target=$TRIPLE$API -O3 -fPIC" --extra-ldflags="--target=$TRIPLE$API -Wl,-z,max-page-size=16384"
    --enable-shared --disable-static --disable-doc --disable-programs --disable-everything
    --disable-avdevice --disable-avformat --disable-swscale --disable-postproc --disable-avfilter
    --disable-network --disable-symver --disable-v4l2-m2m --disable-vulkan --disable-debug
    --enable-swresample --enable-pic
  )
  for d in "${DECODERS[@]}"; do CONFIGURE+=(--enable-decoder="$d"); done
  "$SRC/configure" "${CONFIGURE[@]}" "${EXTRA[@]}" > configure.log 2>&1 || { tail -40 configure.log; tail -60 ffbuild/config.log; exit 1; }
  # configure records its source folder as /c/...; the NDK's make.exe needs C:/... (Windows only).
  POSIX_ROOT="$(cd "$ROOT" && pwd)"
  if [[ "$POSIX_ROOT" != "$ROOT" ]]; then sed -i "s#$POSIX_ROOT#$ROOT#g" Makefile ffbuild/config.mak; fi
  # The licence must stay LGPL: configure prints it.
  grep -q "License: LGPL" configure.log || { echo "Not an LGPL configuration" >&2; grep -i license configure.log; exit 1; }
  "$MAKE" -j"$(nproc 2>/dev/null || echo 4)" > make.log 2>&1 || { tail -40 make.log; exit 1; }
  install_abi "$ABI"
  echo "$ABI: built $(ls "$OUT/$ABI/lib"/*.so | xargs -n1 basename | tr '\n' ' ')"
done
cp "$SRC/COPYING.LGPLv2.1" "$OUT/COPYING.LGPLv2.1"
