# Stage the built backend and the QNN runtime into the app.
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

# ⚠⚠ ALL SIX HTP arches, ~150 MB raw.
#
# It was V79 only, which meant the APK ran on exactly one phone. `libQnnHtp.so`
# dispatches on the arch of the DEVICE, not of the context binary, so an 8 Gen 2
# needs a V73 Skel present to initialise the NPU AT ALL -- even though every
# model we ship is a context it could otherwise load. A missing Skel is not a
# slow path, it is `Failed to create context from binary` with nothing in the
# message pointing at the cause.
#
# ⚠ The APK carries all six; the APP unpacks only the one it needs at runtime
# (DeviceProbe.runtimeLibs), so the 150 MB is a download cost, not a per-start
# copy. Upstream local-dream and DreamUI both ship all six for this reason.
#
# ⚠ Keep this list in step with DeviceProbe.STAGED_ARCHES -- a staged arch with
# no entry there is dead weight, and an entry with no staged arch is a phone
# that fails NPU init.
$arches = @("V68", "V69", "V73", "V75", "V79", "V81")

New-Item -ItemType Directory -Force -Path $jni, $assets | Out-Null

$exe = Join-Path $built "bin\arm64-v8a\libstable_diffusion_core.so"
if (-not (Test-Path $exe)) { throw "backend not built: $exe" }
Copy-Item $exe (Join-Path $jni "libstable_diffusion_core.so") -Force
Write-Output ("exe    {0,10:N0} bytes" -f (Get-Item $exe).Length)

# libQnnHtp.so and libQnnSystem.so are arch-independent; the rest are the
# per-arch trio (accelerator, stub, skel).
$want = @("libQnnHtp.so", "libQnnSystem.so")
foreach ($a in $arches) {
    $want += @("libQnnHtp$a.so", "libQnnHtp${a}Stub.so", "libQnnHtp${a}Skel.so")
}
# ⚠⚠ The RUNTIME is QAIRT 2.50, while the SDK the backend is BUILT against is
# 2.49 (backend-src/CMakeLists.txt). A QNN runtime loads context binaries from
# its own version and OLDER, never newer -- and npuforge's SDXL exports are
# 2.50 builds, which 2.49 refuses with "Using newer context binary on old SDK"
# (2026-09-16). Measured the same day: the backend built on 2.49 headers runs
# on these libraries and renders a 2.28 SD 1.5 and a 2.28 SDXL checkpoint
# BIT-IDENTICAL to 2.49.
# ⚠ Only the SDK installer needs an account, so this set was taken from an
# upstream APK's assets/qnnlibs. ⚠⚠ It is local-dream **3.0**'s set, NOT
# 2.8.1's as this comment used to say: libQnnSystem.so here is byte-identical
# to v3.0.0-alpha.1's (md5 f050e5d0…), while 2.8.1 ships a different, 2.4 MB
# build (3f09fefd…) — checked 2026-09-19. Set $env:NM_QNN_RUNTIME to stage a
# different set.
$runtime = if ($env:NM_QNN_RUNTIME) { $env:NM_QNN_RUNTIME } else {
    Join-Path (Split-Path -Parent $root) "LocalDream\qairt-runtime\2.50.0.260828221209"
}
if (-not (Test-Path $runtime)) { throw "QNN runtime not found: $runtime" }
$total = 0
foreach ($n in $want) {
    $src = Join-Path $runtime $n
    if (-not (Test-Path $src)) { throw "missing QNN lib: $src" }
    Copy-Item $src (Join-Path $assets $n) -Force
    $total += (Get-Item $src).Length
}
Write-Output ("qnnlibs {0,9:N0} bytes across {1} files ({2} arches)" -f $total, $want.Count, $arches.Count)

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
$dit = if ($env:NM_DIT_ENGINE) { $env:NM_DIT_ENGINE } else {
    Join-Path (Split-Path -Parent $root) "LocalDream\ld3-apk-a2\extracted"
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
