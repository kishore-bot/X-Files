#!/usr/bin/env bash
set -euo pipefail

apk=${1:?Usage: verify-ffmpeg-apk.sh APK}
entries=$(unzip -Z1 "$apk")
temp=$(mktemp -d)
trap 'rm -rf "$temp"' EXIT

export LC_ALL=C
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
zipalign=${ZIPALIGN:-}
if [[ -z "$zipalign" && -d "$sdk/build-tools" ]]; then
  zipalign=$(find "$sdk/build-tools" -type f -name zipalign | sort -V | tail -1)
fi
if [[ -z "$zipalign" ]]; then
  echo "Set ZIPALIGN or install Android SDK Build Tools." >&2
  exit 1
fi
"$zipalign" -c -P 16 4 "$apk"

for abi in arm64-v8a x86_64; do
  case "$abi" in
    arm64-v8a) machine=AArch64 ;;
    x86_64) machine='Advanced Micro Devices X86-64' ;;
  esac
  for library in libavcodec.so libavutil.so libswresample.so libffmpegJNI.so; do
    entry="lib/$abi/$library"
    if ! grep -Fxq "$entry" <<< "$entries"; then
      printf 'Missing %s in %s\n' "$entry" "$apk" >&2
      exit 1
    fi

    binary="$temp/$library"
    unzip -p "$apk" "$entry" > "$binary"
    if ! readelf -hW "$binary" | grep -Eq "Machine:[[:space:]]+$machine$"; then
      printf 'Wrong ELF architecture for %s\n' "$entry" >&2
      exit 1
    fi
    mapfile -t alignments < <(readelf -lW "$binary" | awk '$1 == "LOAD" {print $NF}')
    if (( ${#alignments[@]} == 0 )); then
      printf 'No ELF load segments found in %s\n' "$entry" >&2
      exit 1
    fi
    for alignment in "${alignments[@]}"; do
      if (( alignment < 0x4000 )); then
        printf 'Load segment below 16 KiB alignment in %s\n' "$entry" >&2
        exit 1
      fi
    done
    dynamic=$(readelf -dW "$binary")
    if [[ "$library" == "libffmpegJNI.so" ]]; then
      for required in libavcodec.so libavutil.so libswresample.so; do
        if ! grep -Fq "[$required]" <<< "$dynamic"; then
          printf 'Missing native link to %s in %s\n' "$required" "$entry" >&2
          exit 1
        fi
      done
    fi
    while IFS= read -r dependency; do
      case "$dependency" in
        libavcodec.so|libavutil.so|libswresample.so)
          if ! grep -Fxq "lib/$abi/$dependency" <<< "$entries"; then
            printf 'Missing ELF dependency %s for %s\n' "$dependency" "$entry" >&2
            exit 1
          fi ;;
        libavcodec.so.*|libavutil.so.*|libswresample.so.*)
          printf 'Unexpected versioned ELF dependency %s for %s\n' "$dependency" "$entry" >&2
          exit 1 ;;
      esac
    done < <(sed -n 's/.*(NEEDED).*\[\(.*\)\].*/\1/p' <<< "$dynamic")
  done
done
