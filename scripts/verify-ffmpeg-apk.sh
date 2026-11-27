#!/usr/bin/env bash
set -euo pipefail

apk=${1:?Usage: verify-ffmpeg-apk.sh APK}
entries=$(unzip -Z1 "$apk")
for abi in arm64-v8a x86_64; do
  for library in libavcodec.so libavutil.so libswresample.so libffmpegJNI.so; do
    if ! grep -Fxq "lib/$abi/$library" <<< "$entries"; then
      printf 'Missing lib/%s/%s in %s\n' "$abi" "$library" "$apk" >&2
      exit 1
    fi
  done
done
