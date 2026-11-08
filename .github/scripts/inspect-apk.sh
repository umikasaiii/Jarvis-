#!/usr/bin/env bash
# BETA 1/8 — read-only identity report of an APK: provenance inputs, SHA-256, application id,
# versions, SDK levels, ABIs, debuggable flag, permissions and signing certificate.
#   inspect-apk.sh <new.apk>                      report
#   inspect-apk.sh --compare <old.apk> <new.apk>  in-place-update compatibility vs a published APK
# Exit 1 only when --compare finds a hard in-place-update blocker (package/cert mismatch, versionCode regression).
set -u
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
AAPT2="${BT}aapt2"; APKSIGNER="${BT}apksigner"
[ -x "$AAPT2" ] && [ -x "$APKSIGNER" ] || { echo "build-tools not found under $SDK"; exit 2; }

field() { sed -n "s/.* $2='\([^']*\)'.*/\1/p" <<<"$1" | head -1; }
facts() { # apk -> key=value lines
  local apk="$1" b; b="$("$AAPT2" dump badging "$apk" 2>/dev/null)"
  echo "file=$(basename "$apk")"
  echo "bytes=$(stat -c %s "$apk")"
  echo "sha256=$(sha256sum "$apk" | cut -d' ' -f1)"
  local pk; pk="$(grep -m1 '^package:' <<<"$b")"
  echo "package=$(field "$pk" name)"
  echo "versionCode=$(field "$pk" versionCode)"
  echo "versionName=$(field "$pk" versionName)"
  echo "compileSdk=$(field "$pk" compileSdkVersion)"
  echo "minSdk=$(sed -n "s/^\(min\)\{0,1\}[sS]dkVersion:'\([0-9]*\)'.*/\2/p" <<<"$b" | head -1)"
  echo "targetSdk=$(sed -n "s/^targetSdkVersion:'\([0-9]*\)'.*/\1/p" <<<"$b")"
  echo "debuggable=$(grep -q "application-debuggable" <<<"$b" && echo yes || echo no)"
  echo "native-code=$(sed -n "s/^native-code: //p" <<<"$b" | tr -d "'")"
  echo "abis-in-zip=$(unzip -l "$apk" | awk '{print $4}' | grep -E '^lib/[^/]+/' | cut -d/ -f2 | sort -u | tr '\n' ' ')"
  local sg; sg="$("$APKSIGNER" verify --verbose --print-certs "$apk" 2>&1)"
  grep -q '^Verifies$' <<<"$sg" && echo "verifies=yes" || echo "verifies=NO"
  sed -n 's/^Verified using v\([0-9.]*\) .*scheme.*: \(true\|false\)/scheme-v\1=\2/p' <<<"$sg"
  echo "cert-sha256=$(grep -i 'certificate SHA-256 digest' <<<"$sg" | head -1 | sed 's/.*: *//')"
  echo "cert-dn=$(grep -i 'certificate DN' <<<"$sg" | head -1 | sed 's/.*: *//')"
  [ -n "${SHOW_RAW:-}" ] && { echo "apksigner-raw-begin"; head -15 <<<"$sg"; echo "apksigner-raw-end"; }
}
perms() { "$AAPT2" dump permissions "$1" 2>/dev/null | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" | sort -u; }

if [ "${1:-}" = "--compare" ]; then
  OLD="$2"; NEW="$3"; rc=0
  facts "$OLD" > /tmp/old.facts; facts "$NEW" > /tmp/new.facts
  g() { sed -n "s/^$2=//p" "$1" | head -1; }
  echo "== PUBLISHED (latest-debug) =="; cat /tmp/old.facts; echo; echo "== CANDIDATE =="; cat /tmp/new.facts; echo
  [ "$(g /tmp/old.facts package)" = "$(g /tmp/new.facts package)" ] && echo "OK   same application id: $(g /tmp/new.facts package)" || { echo "FAIL application id differs"; rc=1; }
  [ -n "$(g /tmp/new.facts cert-sha256)" ] && [ "$(g /tmp/old.facts cert-sha256)" = "$(g /tmp/new.facts cert-sha256)" ] && echo "OK   same signing certificate (SHA-256 $(g /tmp/new.facts cert-sha256))" || { echo "FAIL signing certificate differs or could not be read: in-place update NOT confirmed"; rc=1; }
  [ "$(g /tmp/new.facts versionCode)" -ge "$(g /tmp/old.facts versionCode)" ] && echo "OK   versionCode $(g /tmp/old.facts versionCode) -> $(g /tmp/new.facts versionCode) (not a downgrade)" || { echo "FAIL versionCode regression"; rc=1; }
  [ "$(g /tmp/new.facts debuggable)" = "$(g /tmp/old.facts debuggable)" ] && echo "OK   debuggable unchanged ($(g /tmp/new.facts debuggable))" || echo "WARN debuggable changed"
  perms "$OLD" > /tmp/old.perms; perms "$NEW" > /tmp/new.perms
  echo; echo "== PERMISSION DIFF (published -> candidate) =="
  echo "added:";   comm -13 /tmp/old.perms /tmp/new.perms | sed 's/^/  + /'
  echo "removed:"; comm -23 /tmp/old.perms /tmp/new.perms | sed 's/^/  - /'
  echo "unchanged count: $(comm -12 /tmp/old.perms /tmp/new.perms | wc -l)"
  exit $rc
fi
SHOW_RAW=1 facts "$1"; echo; echo "permissions ($(perms "$1" | wc -l)):"; perms "$1" | sed 's/^/  /'
