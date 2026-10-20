#!/usr/bin/env bash
# ART's verifier on every class of an APK, the check a phone runs at startup.
#
# Usage (Git Bash, an emulator running, see HANDOFF §3b):
#   tools/verify_dex.sh app/build/outputs/apk/release/app-release.apk [serial]
#
# ⚠⚠ Why: 1.6.207 shipped a class R8 had compiled into code ART rejects ("Verifier rejected class ...
# copy-cat1 ... type=Conflict"), so the app crashed on open for testers. The S25's ART (Android 17) accepts it
# and a dev build has no R8, so neither the phone nor the suite saw it. An Android 14 emulator's ART rejects it:
# this script found 1 error in 1.6.207 and 0 in 1.6.203 and the fix.
# ⭐ Exit 0 = no verification errors. It needs root (an AOSP "default" image) and runs no app code.
set -euo pipefail
APK=${1:?usage: verify_dex.sh <apk> [serial]}
SERIAL=${2:-emulator-5580}
ADB=${ADB:-adb}
export MSYS_NO_PATHCONV=1
"$ADB" -s "$SERIAL" root >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" wait-for-device
"$ADB" -s "$SERIAL" push "$APK" /data/local/tmp/verify.apk >/dev/null
"$ADB" -s "$SERIAL" logcat -c
"$ADB" -s "$SERIAL" shell "cd /data/local/tmp && rm -f verify.odex verify.vdex && \
  /apex/com.android.art/bin/dex2oat64 --dex-file=verify.apk --oat-file=verify.odex \
  --compiler-filter=verify --instruction-set=x86_64 -j4 >/dev/null 2>&1; echo dex2oat exit \$?"
# ⚠ dex2oat writes verifier failures to logcat, not to its stderr.
errors=$("$ADB" -s "$SERIAL" logcat -d -s dex2oat64 | grep 'Verification error' || true)
"$ADB" -s "$SERIAL" shell rm -f /data/local/tmp/verify.apk /data/local/tmp/verify.odex /data/local/tmp/verify.vdex
if [ -n "$errors" ]; then
  echo "$errors" | cut -c1-240
  echo "FAIL: $(echo "$errors" | wc -l) verification error(s)"
  exit 1
fi
echo "OK: no verification errors"
