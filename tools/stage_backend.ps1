# Stage the built backend (and the DiT skels) into the app. The QNN runtime is NOT staged any more.
#
# Both destinations are GITIGNORED and must stay that way: the executable is
# built from backend-src/ (CC BY-NC lineage) and the QNN libs come from the
# QAIRT SDK (its own redistribution restrictions). This script is how they get
# into a build; nothing else should copy them.
#
#   pwsh tools/stage_backend.ps1
#
# Run it after every backend rebuild. The app silently keeps running an OLD
# backend otherwise, and an old backend that answers /health looks exactly like
# a working one.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$built = Join-Path $root "backend-src\build\android"
$jni = Join-Path $root "app\src\main\jniLibs\arm64-v8a"
$assets = Join-Path $root "app\src\main\assets\qnnlibs"

# ⭐⭐ The QNN runtime is DOWNLOADED by the app for the phone's own NPU (QnnPack.kt, the user 2026-11-01):
# QAIRT 2.51's libQnnHtp.so + libQnnSystem.so and one arch trio, ~28 MB once, from HF
# AbrahamPJ/nightmare-qnn-251 (unchanged copies of ../LocalDream/qairt-runtime/2.51.0.260929). Until 1.6.272 this
# script copied all six 2.50 trios into assets/qnnlibs: 52 MB of an 85 MB APK, one trio used per phone.
# ⚠ A leftover assets/qnnlibs would ship again, unused: it is deleted here.
if (Test-Path $assets) { Remove-Item -Recurse -Force $assets; Write-Output "removed $assets (the runtime is downloaded)" }

New-Item -ItemType Directory -Force -Path $jni | Out-Null

$exe = Join-Path $built "bin\arm64-v8a\libstable_diffusion_core.so"
if (-not (Test-Path $exe)) { throw "backend not built: $exe" }
Copy-Item $exe (Join-Path $jni "libstable_diffusion_core.so") -Force
Write-Output ("exe    {0,10:N0} bytes" -f (Get-Item $exe).Length)

# ⭐⭐ The DiT engine (FLUX.2 Klein / Z-Image). Taken from local-dream
# v3.0.0-alpha.2's APK, where upstream builds it from stable-diffusion.cpp with
# the Hexagon SDK (not installed here).
#
# ⚠⚠ alpha.2 is an ABI BUMP: DIT_ENGINE_ABI_VERSION went 1 -> 3, and the
# Hexagon skels changed with it (upstream a7dd738, "correct Hexagon ops that
# broke DiT edits and non-256 sizes"). The core refuses a mismatched engine by
# version, so the engine, the skels and backend-src/src/DitEngine.h move
# TOGETHER or DiT stops working entirely.
#
# ⚠⚠ Since 1.5.502 libdit_engine.so does NOT go into the APK. It is 55.7 MB on
# disk, 21.9 MB deflated, and dead weight on every phone that never renders a
# DiT model - so it is packed into a release asset here and DOWNLOADED by
# DitEngine.kt into the runtime dir at first use. notes/HANDOFF.md §7.
# ⚠ Its Hexagon skels (assets/ditlibs, 1.8 MB) DO stay in the APK: FastRPC
# hands them to the DSP by bare name and they are too small to pay for a
# second moving part.
#
# ⚠⚠⚠ Publishing the zip is a MANUAL step, and the app cannot install the
# engine until it is done: upload build/release-assets/ to the tag named in
# DitEngine.URL. If the engine itself ever changes, change that URL (a new
# filename at a new tag) and BYTES, FILE_BYTES and SHA256 with it. Re-uploading
# the SAME engine under a new tag makes every user re-download 22 MB for
# nothing.
#
# Set $env:NM_DIT_ENGINE to a dir holding lib/arm64-v8a/libdit_engine.so and
# assets/ditlibs/*.so.
#
# ⚠⚠⚠ The DEFAULT is the engine DitEngine.URL names, and must move with it.
# It was left pointing at alpha.2 after the ABI 105 engine shipped (1.6.046),
# and a plain run on 2026-09-28 put alpha.2 skels beside the ABI 105 engine:
# FLUX.2 Klein 9B's first DiT graph died `dsp-rsp INVAL-PARAMS` + SIGABRT.
# ../LocalDream/dit-engine-abi110-h3enc (CI 40950650774: abi110 + dit/079, H3's one-frame encode, ABI 110, wrapper only; skels are abi110's) is the CI build's layout: the
# engine, its release zip and the v73-v81 skels built with it. ⚠ Until 1.6.202 this was dit-engine-abi105-a4, whose
# skels were patched by hand as engines moved; dit/031 changes the skel side, so the folder moves with each engine now.
$dit = if ($env:NM_DIT_ENGINE) { $env:NM_DIT_ENGINE } else {
    Join-Path (Split-Path -Parent $root) "LocalDream\dit-engine-abi110-h3enc"
}
$ditAssets = Join-Path $root "app\src\main\assets\ditlibs"
$ditSo = Join-Path $dit "lib\arm64-v8a\libdit_engine.so"
# ⚠⚠ A copy left by a pre-1.5.502 staging run would be packaged silently and
# quietly undo the whole change, so it is REMOVED rather than merely not
# written.
$staleEngine = Join-Path $jni "libdit_engine.so"
if (Test-Path $staleEngine) {
    Remove-Item $staleEngine -Force
    Write-Output "removed stale libdit_engine.so from jniLibs (it is a download now)"
}
if (Test-Path $ditSo) {
    New-Item -ItemType Directory -Force $ditAssets | Out-Null
    Copy-Item (Join-Path $dit "assets\ditlibs\*.so") $ditAssets -Force
    # ⚠⚠⚠ STAMP THEM, or Gradle ships the PREVIOUS skels. They come out of
    # an APK with a 1981 timestamp, and a rebuilt skel keeps its page-aligned
    # SIZE -- so after Copy-Item the new file has the same size AND the same
    # mtime as the old one, and the asset-merge task treats it as unchanged
    # and reuses its cache. Measured 2026-09-20: the alpha.2 engine shipped
    # against alpha.1 skels and every FLUX.2 edit rendered as pure noise,
    # which is upstream's own bug (their a7dd738 hit it with a size check)
    # one layer up in the build.
    Get-ChildItem $ditAssets -Filter *.so | ForEach-Object { $_.LastWriteTime = Get-Date }
    $relDir = Join-Path $root "build\release-assets"
    New-Item -ItemType Directory -Force $relDir | Out-Null
    $zip = Join-Path $relDir "dit-engine-ld3.0.0a2.zip"
    if (Test-Path $zip) { Remove-Item $zip -Force }
    Compress-Archive -Path $ditSo -DestinationPath $zip -CompressionLevel Optimal
    $h = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
    Write-Output "dit skels staged from $dit"
    Write-Output ("dit engine -> {0}" -f $zip)
    Write-Output ("  FILE_BYTES {0,12:N0}" -f (Get-Item $ditSo).Length)
    Write-Output ("  BYTES      {0,12:N0}" -f (Get-Item $zip).Length)
    Write-Output ("  SHA256     {0}" -f $h)
    Write-Output "  ^ these three must match the constants in DitEngine.kt"
} else {
    Write-Output "dit engine NOT staged (none at $dit) - DiT models cannot be installed"
}

# ⚠ A stale copy is the failure this guards against, so print what landed.
Write-Output "--- staged ---"
Get-ChildItem $jni, $assets, $ditAssets -ErrorAction SilentlyContinue | ForEach-Object { "  {0,-28} {1,10:N0}" -f $_.Name, $_.Length }
