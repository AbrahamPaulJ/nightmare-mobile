# Copy the QNN headers the in-process NPU runner is built against.
#
# ⚠ NOT committed, for the same reason `app/src/main/assets/qnnlibs/` is not:
# they are QAIRT SDK material with its own redistribution terms. What IS
# committed is `nmqnn.cpp` and the CMakeLists beside it -- the parts that are
# ours. Same shape as `tools/fetch_quickjs.ps1`.
#
#   pwsh tools/fetch_qnn_headers.ps1
#
# ⚠ Headers only, and only at BUILD time. The runtime `.so` files are dlopen'd
# from `filesDir/qnnruntime` (`BackendProcess.prepareRuntime`), so nothing here
# links against a QNN library and the same `libnmqnn.so` serves every HTP arch.
#
# ⚠⚠ The SDK version must match the one the backend and the context binaries
# were built with -- 2.49 today. A header set from a different SDK compiles
# cleanly and can still disagree about a versioned struct, which is the kind of
# failure that shows up as garbage output rather than as an error.

$ErrorActionPreference = "Stop"

# ⚠ The same SDK the backend is built from. Kept out of the repo (it is a
# `C:\Users\...` path on this machine and this repo is public), so it is read
# from the environment first and only then falls back to the sibling checkout.
$Version = "2.49.0.260730"
$sdk = $env:QAIRT_SDK
if (-not $sdk) { $sdk = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) "LocalDream\qairt\$Version" }

$src = Join-Path $sdk "include\QNN"
if (-not (Test-Path $src)) {
    throw @"
QNN headers not found at $src
Set QAIRT_SDK to a QAIRT $Version installation, or put one beside this repo at
  ../LocalDream/qairt/$Version/
"@
}

$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root "app\src\main\cpp\include\QNN"

if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
New-Item -ItemType Directory -Force -Path $dest | Out-Null

# ⚠ The whole tree, not a hand-picked list. `nmqnn.cpp` includes nine headers;
# those nine include their siblings unqualified and transitively reach most of
# the set. A curated list here would break on an SDK bump in a way that looks
# like a compiler error in vendor code.
Copy-Item -Recurse -Force (Join-Path $src "*") $dest

$n = (Get-ChildItem -Recurse -File $dest).Count
$mb = [math]::Round(((Get-ChildItem -Recurse -File $dest | Measure-Object Length -Sum).Sum / 1MB), 1)
Write-Output "QNN headers $Version -> $dest ($n files, $mb MB)"
