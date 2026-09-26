#!/usr/bin/env bash
# Build Foxlet (prompt translation, docs/TRANSLATE.md) from source into
# app/libs/foxlet-release.aar. One-time per Foxlet version, or after a fresh
# clone. Run from Git Bash: Foxlet ships no gradlew.bat.
#
#   tools/build_foxlet.sh            # clone beside this repo if missing, build, copy
#
# ⚠ Built FROM SOURCE on purpose: a young one-person project's prebuilt native
# binary was judged not worth shipping unseen.
#
# ⚠ tools/foxlet-overrides.patch holds the four local overrides this toolchain
# needs (NDK/CMake versions + CMake 4 policy floor, compileSdk 35, Kotlin 2.1
# metadata for our 2.0 compiler, `python` instead of `python3`). Applied once;
# a clone that already carries them is left alone.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${FOXLET_DIR:-$HERE/../foxlet-translate}"
REPO=https://github.com/yinvoke/foxlet-translate
COMMIT=3e6b7b0   # v0.6.0 -- bump deliberately, and re-measure (docs/TRANSLATE.md)

if [ ! -d "$SRC/.git" ]; then
    git clone "$REPO" "$SRC"
    git -C "$SRC" checkout "$COMMIT"
fi
head="$(git -C "$SRC" rev-parse --short=7 HEAD)"
[ "$head" = "$COMMIT" ] || echo "!! $SRC is at $head, not $COMMIT -- building it anyway" >&2

if git -C "$SRC" apply --check "$HERE/tools/foxlet-overrides.patch" 2>/dev/null; then
    git -C "$SRC" apply "$HERE/tools/foxlet-overrides.patch"
    echo "applied foxlet-overrides.patch"
elif git -C "$SRC" apply --reverse --check "$HERE/tools/foxlet-overrides.patch" 2>/dev/null; then
    echo "overrides already applied"
else
    echo "!! foxlet-overrides.patch neither applies nor is applied -- fix it by hand" >&2
    exit 1
fi

# ⚠ Forward slashes: a backslash path in local.properties is read as escapes.
if [ ! -f "$SRC/local.properties" ]; then
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-}/Android/Sdk}}"
    echo "sdk.dir=$(echo "$sdk" | tr '\\' '/')" > "$SRC/local.properties"
fi

# ⚠ PYTHONUTF8: Windows' cp1252 default chokes on the licence files the AAR
# packages (package_notices.py).
(cd "$SRC" && PYTHONUTF8=1 ./gradlew :foxlet:assembleRelease)

mkdir -p "$HERE/app/libs"
cp "$SRC/foxlet/build/outputs/aar/foxlet-release.aar" "$HERE/app/libs/"
ls -l "$HERE/app/libs/foxlet-release.aar"
