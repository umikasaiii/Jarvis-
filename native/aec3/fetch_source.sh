#!/usr/bin/env bash
# Fetch + verify the pinned webrtc-audio-processing source. FAIL CLOSED.
# Usage: fetch_source.sh <dest_dir>
set -euo pipefail
DEST="${1:?dest dir}"
mkdir -p "$DEST"
EXPECT_SHA=35e86b986d02ea15f3d04741a1a5a735ba399bc0fac0ee089c39480e35fc3253
EXPECT_SIZE=814872
COMMIT=846fe90a289f58b7c9303a635142aa2c7caa93e5
PRIMARY=https://freedesktop.org/software/pulseaudio/webrtc-audio-processing/webrtc-audio-processing-2.1.tar.gz
FALLBACK=https://gitlab.freedesktop.org/pulseaudio/webrtc-audio-processing/-/archive/$COMMIT/webrtc-audio-processing-$COMMIT.tar.gz
OUT="$DEST/source.tar.gz"
KIND=""
if curl -fsSL --retry 3 --max-time 120 -o "$OUT" "$PRIMARY"; then
  KIND=release-tarball
  GOT=$(sha256sum "$OUT" | cut -d' ' -f1); SIZE=$(stat -c %s "$OUT")
  # A reachable-but-different primary artifact is NEVER silently replaced by the fallback.
  if [ "$GOT" != "$EXPECT_SHA" ] || [ "$SIZE" != "$EXPECT_SIZE" ]; then
    echo "FAIL CLOSED: release tarball identity mismatch sha=$GOT size=$SIZE" >&2; exit 1
  fi
else
  echo "primary release tarball unreachable; trying immutable official GitLab archive for exact commit" >&2
  curl -fsSL --retry 3 --max-time 120 -o "$OUT" "$FALLBACK" || { echo "FAIL CLOSED: no official Freedesktop source reachable" >&2; exit 1; }
  KIND=gitlab-commit-archive
  GOT=$(sha256sum "$OUT" | cut -d' ' -f1); SIZE=$(stat -c %s "$OUT")
  echo "NOTE: fallback archive is NOT the release tarball; its own sha256 is recorded, not compared."
fi
echo "sourceKind=$KIND" | tee "$DEST/source-identity.txt"
echo "sha256=$GOT" | tee -a "$DEST/source-identity.txt"
echo "size=$SIZE" | tee -a "$DEST/source-identity.txt"
mkdir -p "$DEST/src"
tar -xzf "$OUT" -C "$DEST/src" --strip-components=1
ls "$DEST/src" | head -50
