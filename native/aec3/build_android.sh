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
