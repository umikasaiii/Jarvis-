#!/usr/bin/env bash
# STEP 2 — proves the latest-debug protection holds AND that the guard itself can fail
# (mutation tests on temp copies of the real workflows). Run by CI before anything else.
set -u
cd "$(git rev-parse --show-toplevel)"
G=.github/scripts/ci_publish_guard.py
fail=0
python3 "$G" .github/workflows || { echo "FAIL: real workflows violate the guard"; exit 1; }

mutate() { # name, python-expression-on-text(t), file
  tmp="$(mktemp -d)"; cp .github/workflows/*.yml "$tmp/"
  python3 - "$tmp/$3" "$2" <<'PY'
import sys; p,expr=sys.argv[1],sys.argv[2]; t=open(p).read(); n=eval(expr)
assert n!=t, "mutation did not change the file"; open(p,'w').write(n)
PY
  if python3 "$G" "$tmp" >/dev/null; then echo "FAIL: guard did NOT catch mutation: $1"; fail=1; else echo "PASS: caught: $1"; fi
  rm -rf "$tmp"
}
mutate "branch gate removed from publish" "t.replace(\"github.ref == 'refs/heads/claude/jarvis-mobile-automazioni-dashboard-b4xa7e' &&\",'')" ci.yml
mutate "gate loosened to claude/**" "t.replace(\"github.ref == 'refs/heads/claude/jarvis-mobile-automazioni-dashboard-b4xa7e'\",\"startsWith(github.ref, 'refs/heads/claude/')\")" ci.yml
mutate "release delete added to build job" "t.replace('      - name: Verify release-publish guard','      - run: gh release delete latest-debug --yes\n      - name: Verify release-publish guard')" ci.yml
mutate "contents: write on build job" "t.replace('      contents: read\n    outputs','      contents: write\n    outputs')" ci.yml
mutate "release mutation in test workflow" "t.replace('      - uses: actions/checkout@v4','      - run: gh release create x\n      - uses: actions/checkout@v4',1)" db-migration-tests.yml
mutate "pull_request_target added" "t.replace('on:\n','on:\n  pull_request_target: {}\n',1)" db-migration-tests.yml
mutate "publish no longer needs build" "t.replace('    needs: build\n','')" ci.yml
exit $fail
