# Stage the video path's APK assets from ../Neodragon.
#
# ⚠ GITIGNORED, like `assets/qnnlibs` and for a related reason: these are build
# OUTPUT of another tree's conversion pipeline, not source. `tools/` is how they
# get into a build; nothing else should copy them.
#
#   powershell -File tools/stage_neodragon.ps1
#
# ⚠⚠ **Only what CANNOT be a download lives here.** `docs/ROADMAP.md` §3c
# measured the alternative: shipping `mmdit_temb.ndw` (33 MB) and
# `ssd1b_addembed.ndw` (21 MB) as assets roughly DOUBLES a 58 MB APK,
# permanently, for everyone, whether or not they ever make a video. They are
# data, so they join the model download instead.
#
# The canary is the one thing that genuinely cannot: it is what decides whether
# to START the download, so it has to be in the APK. 58 KB.

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$src = $env:NEODRAGON_SRC
if (-not $src) { $src = Join-Path (Split-Path -Parent $root) "Neodragon\work\android\app\src\main\assets" }

if (-not (Test-Path $src)) {
    throw @"
Neodragon assets not found at $src
Set NEODRAGON_SRC, or put the project beside this repo at ../Neodragon/
"@
}

$assets = Join-Path $root "app\src\main\assets\npu"
New-Item -ItemType Directory -Force -Path $assets | Out-Null

# ⚠ The canary trio is ONE artefact in three files and they must come from the
# same conversion run: the reference was computed on the host for that exact
# graph, so a mixed set fails the SNR check and reads as "this chip is broken".
$want = @("canary_v79.bin", "canary_in.raw", "canary_ref.raw")

$total = 0
foreach ($n in $want) {
    $f = Join-Path $src $n
    if (-not (Test-Path $f)) { throw "missing canary file: $f" }
    Copy-Item $f (Join-Path $assets $n) -Force
    $total += (Get-Item $f).Length
}

Write-Output ("canary {0,8:N0} bytes across {1} files -> {2}" -f $total, $want.Count, $assets)
