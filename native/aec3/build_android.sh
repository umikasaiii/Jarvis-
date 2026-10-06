#!/usr/bin/env bash
# Build standalone WebRTC AudioProcessing (arm64-v8a, android-31) from the pinned source.
# Usage: build_android.sh <work_dir>
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${1:?work dir}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:?no NDK}}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=31
"$HERE/fetch_source.sh" "$WORK/fetch"
SRC="$WORK/fetch/src"
cat > "$WORK/cross.ini" <<EOT
[binaries]
c = '$TC/aarch64-linux-android$API-clang'
cpp = '$TC/aarch64-linux-android$API-clang++'
ar = '$TC/llvm-ar'
strip = '$TC/llvm-strip'
ranlib = '$TC/llvm-ranlib'
pkg-config = 'pkg-config'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'

[built-in options]
c_args = ['-fPIC']
cpp_args = ['-fPIC']
cpp_link_args = ['-static-libstdc++']
EOT
meson setup "$WORK/build" "$SRC" --cross-file "$WORK/cross.ini" \
  --prefix "$WORK/prefix" --buildtype release -Ddefault_library=static 2>&1 | tee "$WORK/meson-setup.log"
ninja -C "$WORK/build" 2>&1 | tail -30
meson install -C "$WORK/build" 2>&1 | tail -30
echo "=== installed tree"; find "$WORK/prefix" -type f | sort | head -300
echo "=== pc files"; find "$WORK/prefix" -name '*.pc' -exec sh -c 'echo "--- $1"; cat "$1"' _ {} \;

echo "=== header API excerpts"
H="$WORK/prefix/include/webrtc-audio-processing-2/api/audio/audio_processing.h"
grep -n -E "class .*AudioProcessing|AudioProcessingBuilder|Create\(|Build\(|ProcessStream|ProcessReverseStream|set_stream_delay|namespace|struct EchoCanceller|mobile_mode|high_pass_filter|noise_suppression|gain_controller|scoped_refptr|ApplyConfig|GetStatistics|StreamConfig" "$H" | head -80
echo "=== absl in main .a?"; "$TC/llvm-nm" "$WORK/prefix/lib/libwebrtc-audio-processing-2.a" 2>/dev/null | grep -c -i absl || true
find "$WORK/build/subprojects" -name 'libabsl_*.a' | head -30
# ---- JNI wrapper
PREFIX="$WORK/prefix"
ABSL_LIBS=$(find "$WORK/build/subprojects" -name 'libabsl_*.a' | sort | tr '\n' ' ')
OUT_SO="$WORK/libjarvis_aec3.so"
"$TC/aarch64-linux-android$API-clang++" -std=c++20 -O2 -fPIC -shared -fvisibility=hidden -ffile-prefix-map="$WORK"=. \
  -I"$PREFIX/include/webrtc-audio-processing-2" -I"$PREFIX/include" -DWEBRTC_POSIX -DWEBRTC_ANDROID \
  "$HERE/jni/jarvis_aec3.cpp" -o "$OUT_SO" \
  -Wl,--start-group "$PREFIX/lib/libwebrtc-audio-processing-2.a" $ABSL_LIBS -Wl,--end-group \
  -static-libstdc++ -llog -Wl,--version-script="$HERE/jni/exports.map" -Wl,--gc-sections -Wl,--exclude-libs,ALL \
  -Wl,--build-id=none -Wl,-z,max-page-size=16384 2>&1 | tail -60
"$TC/llvm-strip" --strip-unneeded "$OUT_SO"
ls -l "$OUT_SO"; sha256sum "$OUT_SO"
echo "=== NEEDED"; "$TC/llvm-readelf" -d "$OUT_SO" | grep NEEDED
echo "=== exported dynamic symbols"; "$TC/llvm-nm" -D --defined-only "$OUT_SO"
