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
$total = 0
foreach ($n in $want) {
    $src = Join-Path $built "qnnlibs\$n"
    if (-not (Test-Path $src)) { throw "missing QNN lib: $src" }
    Copy-Item $src (Join-Path $assets $n) -Force
    $total += (Get-Item $src).Length
}
Write-Output ("qnnlibs {0,9:N0} bytes across {1} files ({2} arches)" -f $total, $want.Count, $arches.Count)

# ⚠ A stale copy is the failure this guards against, so print what landed.
Write-Output "--- staged ---"
Get-ChildItem $jni, $assets | ForEach-Object { "  {0,-28} {1,10:N0}" -f $_.Name, $_.Length }
