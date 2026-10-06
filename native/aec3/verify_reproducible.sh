#!/usr/bin/env bash
# Builds the AEC3 library twice from scratch at the SAME work path and requires identical SHA-256.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${1:?work dir}"
rm -rf "$WORK"; bash "$HERE/build_android.sh" "$WORK" >/dev/null
A=$(sha256sum "$WORK/libjarvis_aec3.so" | cut -d' ' -f1)
rm -rf "$WORK"; bash "$HERE/build_android.sh" "$WORK" >/dev/null
B=$(sha256sum "$WORK/libjarvis_aec3.so" | cut -d' ' -f1)
echo "build1=$A"; echo "build2=$B"
[ "$A" = "$B" ] && echo "REPRODUCIBLE" || { echo "NOT REPRODUCIBLE" >&2; exit 1; }
