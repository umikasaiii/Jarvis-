#!/usr/bin/env bash
# STEP 2 — recover REAL Room schema JSONs for jarvis.db versions that were shipped before
# `exportSchema = true`. For each version N the script finds the last commit whose
# JarvisDatabase.kt declared `version = N`, checks that exact tree out in a throw-away worktree,
# turns schema export on, and lets Room/KSP generate N.json from THAT code. Nothing is written by
# hand. Needs the Android SDK (run it in CI: .github/workflows/room-historical-schemas.yml).
#
# usage: tools/db/export_historical_schemas.sh [out_dir=app/schemas] [versions...]
set -euo pipefail
REPO="$(git rev-parse --show-toplevel)"
OUT="${1:-$REPO/app/schemas}"; shift || true
DBFILE=app/src/main/java/com/simone/jarvismobile/background/JarvisDatabase.kt
DBDIR=com.simone.jarvismobile.background.JarvisDatabase
CUR=$(grep -oE 'version = [0-9]+' "$REPO/$DBFILE" | head -1 | grep -oE '[0-9]+')
VERSIONS=("$@"); [ ${#VERSIONS[@]} -gt 0 ] || VERSIONS=($(seq 1 $((CUR-1))))
mkdir -p "$OUT/$DBDIR"
INIT="$(mktemp --suffix=.gradle)"
cat > "$INIT" <<'GRADLE'
// Groovy init script: point KSP's Room processor at SCHEMA_OUT in whatever tree is being built.
allprojects {
  plugins.withId("com.google.devtools.ksp") {
    project.extensions.getByName("ksp").arg("room.schemaLocation", System.getenv("SCHEMA_OUT"))
  }
}
GRADLE
for v in "${VERSIONS[@]}"; do
  if [ -f "$OUT/$DBDIR/$v.json" ]; then echo "v$v: already present, skipping"; continue; fi
  commit=""
  for c in $(git -C "$REPO" log --format=%H -- "$DBFILE"); do
    if git -C "$REPO" show "$c:$DBFILE" 2>/dev/null | grep -qE "version = $v,"; then commit=$c; break; fi
  done
  [ -n "$commit" ] || { echo "v$v: no commit found" >&2; echo "$v" >> "$OUT/MISSING.txt"; continue; }
  echo "v$v: using $commit"
  wt="$(mktemp -d)"; git -C "$REPO" worktree add --detach "$wt" "$commit" >/dev/null
  ( cd "$wt"
    sed -i 's/exportSchema = false/exportSchema = true/' "$DBFILE"
    mkdir -p native/aec3/out/jniLibs
    SCHEMA_OUT="$wt/_schemas" ./gradlew --no-daemon -I "$INIT" :app:kspDebugKotlin -q ) \
    && cp "$wt/_schemas/$DBDIR/$v.json" "$OUT/$DBDIR/$v.json" \
    && echo "v$v: exported from $commit" \
    || { echo "v$v: EXPORT FAILED at $commit" >&2; echo "$v $commit" >> "$OUT/MISSING.txt"; }
  git -C "$REPO" worktree remove --force "$wt"
done
rm -f "$INIT"
