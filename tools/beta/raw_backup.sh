#!/usr/bin/env bash
# BETA 1/8 - raw, app-independent copy of ALL JARVIS app-private data to this PC (bash / Git Bash / WSL / macOS).
# Read-only on the phone: it only reads through `adb run-as` (works because the debug build is debuggable).
#   ./raw_backup.sh [package]      default package: com.simone.jarvismobile.debug
set -eu
PKG="${1:-com.simone.jarvismobile.debug}"
OUT="jarvis-raw-$(date +%Y%m%d-%H%M).tar"
adb get-state >/dev/null
adb shell "run-as $PKG ls files databases" >/dev/null || { echo "run-as failed: wrong package id, or the app is not a debug build"; exit 1; }
# Fold the write-ahead log into jarvis.db first is NOT done here on purpose (no write on the phone);
# the -wal / -shm files are copied too, and SQLite reads them together.
adb exec-out "run-as $PKG tar -cf - databases files shared_prefs 2>/dev/null" > "$OUT"
tar -tf "$OUT" >/dev/null && echo "tar readable: $OUT ($(wc -c < "$OUT") bytes)"
sha256sum "$OUT" 2>/dev/null || shasum -a 256 "$OUT"
echo "Now run: python verify_raw_backup.py $OUT"
