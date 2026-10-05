#!/usr/bin/env bash
# Classifies a push as APK-impacting or not. Conservative by construction:
# only an explicit allowlist of paths is "non-impacting"; anything unknown,
# any git failure, an unavailable/zero BEFORE sha or an empty diff => impacting.
# Usage: classify-apk-impact.sh <event_name> <before_sha> <head_sha>
# Prints "apk_impacting=true|false" and "reason=..." on stdout.
set -u
event="${1:-}"; before="${2:-}"; head="${3:-HEAD}"

emit() { echo "apk_impacting=$1"; echo "reason=$2"; exit 0; }

is_non_impacting() {
  case "$1" in
    docs/*|CLAUDE.md|README.md|LICENSE|\
    .github/workflows/ci.yml|.github/scripts/classify-apk-impact.sh|.github/scripts/test-classify-apk-impact.sh) return 0 ;;
    *) return 1 ;;
  esac
}

[ "$event" = "workflow_dispatch" ] && emit true "workflow_dispatch: manual publish always allowed"
[ "$event" = "push" ] || emit true "event '$event' is not a push: conservative"
case "$before" in ""|0000000000000000000000000000000000000000) emit true "no usable before sha (new branch/force push)";; esac
git cat-file -e "${before}^{commit}" 2>/dev/null || emit true "before sha not available locally"
git cat-file -e "${head}^{commit}" 2>/dev/null || emit true "head sha not available"

files="$(git diff --name-only --no-renames "$before" "$head" 2>/dev/null)" || emit true "git diff failed"
[ -n "$files" ] || emit true "empty diff: conservative"

while IFS= read -r f; do
  [ -z "$f" ] && continue
  if ! is_non_impacting "$f"; then emit true "impacting path: $f"; fi
done <<< "$files"
emit false "all changed paths are allowlisted non-impacting"
