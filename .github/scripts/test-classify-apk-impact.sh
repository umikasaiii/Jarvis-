#!/usr/bin/env bash
# Fixture test for classify-apk-impact.sh (builds a throwaway git repo).
set -u
S="$(cd "$(dirname "$0")" && pwd)/classify-apk-impact.sh"
T="$(mktemp -d)"; cd "$T"; git init -q -b main; git config user.email t@t; git config user.name t
fail=0
mk() { for p in "$@"; do mkdir -p "$(dirname "$p")"; echo "$RANDOM$p" >> "$p"; done; git add -A; git commit -qm c; git rev-parse HEAD; }
chk() { # name expected event before head
  out="$(bash "$S" "$3" "$4" "$5")"; got="$(echo "$out" | sed -n 's/^apk_impacting=//p')"
  if [ "$got" = "$2" ]; then echo "PASS $1 ($got)"; else echo "FAIL $1 expected $2 got $got :: $out"; fail=1; fi
}
base="$(mk app/src/main/A.kt docs/a.md)"
h="$(mk docs/b.md)";                       chk "1 docs-only" false push "$base" "$h"
b="$h"; h="$(mk CLAUDE.md)";               chk "2 CLAUDE.md-only" false push "$b" "$h"
b="$h"; h="$(mk .github/workflows/ci.yml)"; chk "3 workflow-only" false push "$b" "$h"
b="$h"; h="$(mk app/src/main/B.kt)";       chk "4 Kotlin app" true push "$b" "$h"
b="$h"; h="$(mk core/src/main/C.kt)";      chk "5 core Kotlin" true push "$b" "$h"
b="$h"; h="$(mk app/build.gradle.kts)";    chk "6 gradle" true push "$b" "$h"
b="$h"; h="$(mk app/src/main/res/x.xml)";  chk "7 resource" true push "$b" "$h"
b="$h"; h="$(mk docs/c.md app/src/main/D.kt)"; chk "8 mixed docs+Kotlin" true push "$b" "$h"
b="$h"; mk docs/d.md >/dev/null; mk docs/e.md >/dev/null; h="$(mk CLAUDE.md)"; chk "9 multi-commit all docs" false push "$b" "$h"
b="$h"; mk docs/f.md >/dev/null; mk app/src/main/E.kt >/dev/null; h="$(mk docs/g.md)"; chk "10 multi-commit docs->Kotlin->docs" true push "$b" "$h"
chk "11 zero before" true push 0000000000000000000000000000000000000000 "$h"
chk "11b unknown before" true push deadbeefdeadbeefdeadbeefdeadbeefdeadbeef "$h"
chk "12 workflow_dispatch" true workflow_dispatch "" "$h"
b="$h"; h="$(mk tools/unknown.py)";        chk "13 unknown path default" true push "$b" "$h"
cd /; rm -rf "$T"; exit $fail
