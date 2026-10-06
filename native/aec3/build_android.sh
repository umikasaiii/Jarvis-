#!/usr/bin/env bash
# Build standalone WebRTC AudioProcessing (arm64-v8a, android-31) from the pinned source.
# Usage: build_android.sh <work_dir> [out_jniLibs_dir]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${1:?work dir}"
OUT_JNILIBS="${2:-}"
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
echo "=== NEEDED"; "$TC/llvm-readelf" -d "$OUT_SO" | grep NEEDED | tee "$WORK/needed.txt"
echo "=== exported dynamic symbols"; "$TC/llvm-nm" -D --defined-only "$OUT_SO" | tee "$WORK/exports.txt"
# Fail closed on the contract: only system libs, no libc++_shared, exactly the 4 JNI symbols.
if grep -q "libc++_shared" "$WORK/needed.txt"; then echo "FAIL: libc++_shared introduced" >&2; exit 1; fi
if [ "$(wc -l < "$WORK/exports.txt")" != "4" ]; then echo "FAIL: unexpected exported symbols" >&2; exit 1; fi
SO_SHA=$(sha256sum "$OUT_SO" | cut -d' ' -f1)
cat > "$WORK/BUILD_RESULT.json" <<EOT
{
  "libraryName": "libjarvis_aec3.so",
  "sha256": "$SO_SHA",
  "sizeBytes": $(stat -c %s "$OUT_SO"),
  "ndkRevision": "$(grep Pkg.Revision "$NDK/source.properties" | cut -d= -f2 | tr -d ' ')",
  "meson": "$(meson --version)",
  "ninja": "$(ninja --version)",
  "androidApi": $API,
  "abi": "arm64-v8a",
  "buildType": "release",
  "defaultLibrary": "static",
  "sourceIdentity": "$(tr '\n' ' ' < "$WORK/fetch/source-identity.txt")",
  "abseilWrap": "abseil-cpp 20240722.0 (meson wrapdb patch 3, hash-pinned by its wrap file)",
  "linkFlags": "-static-libstdc++ -llog -Wl,--version-script,--gc-sections,--exclude-libs,ALL,--build-id=none,-z,max-page-size=16384"
}
EOT
cat "$WORK/BUILD_RESULT.json"
if [ -n "$OUT_JNILIBS" ]; then mkdir -p "$OUT_JNILIBS/arm64-v8a"; cp "$OUT_SO" "$OUT_JNILIBS/arm64-v8a/libjarvis_aec3.so"; fi
