# Fetch the QuickJS source the plugin runtime is built from.
#
# ⚠ The source is NOT committed, for the same reason `backend-src/` is not: a
# vendored third-party tree bloats every clone and every diff, and this one is
# reproducible from a pinned tag plus a hash. What IS committed is our
# CMakeLists and our JNI bridge -- the parts that are ours.
#
# ⚠ Pinned by TAG and verified by SHA256. A moving `main` would change the
# engine under a plugin ecosystem whose whole promise is that a workflow keeps
# working; and a tag alone is not immutable on GitHub, so the hash is the actual
# check.
#
# QuickJS-ng is MIT (Bellard / Gordon / Noordhuis). ⭐ That matters here beyond
# hygiene: the backend's CC BY-NC lineage is already an open licensing question
# (notes/PROGRESS.md), and the plugin engine at least adds nothing to it.

$ErrorActionPreference = "Stop"

$Tag    = "v0.16.2"
$Sha256 = "97c80625b26775a4c7ca618c004d4ea24cf99cbf867e4eba78bd927a8b23d106"
$Url    = "https://github.com/quickjs-ng/quickjs/archive/refs/tags/$Tag.tar.gz"

$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root "app\src\main\cpp\third_party\quickjs"
$tmp  = Join-Path $env:TEMP "quickjs-$Tag.tar.gz"

# The four sources our CMakeLists compiles, plus the headers they need. If a
# future bump changes this list the build fails loudly at compile time, which is
# the right place for it to fail.
$needed = @("quickjs.c", "quickjs.h", "libregexp.c", "libunicode.c", "dtoa.c")

if (Test-Path (Join-Path $dest "quickjs.c")) {
    $have = Get-Content (Join-Path $dest "VERSION") -ErrorAction SilentlyContinue
    if ($have -eq $Tag) {
        Write-Host "quickjs $Tag already present at $dest"
        exit 0
    }
    Write-Host "replacing quickjs $have with $Tag"
    Remove-Item -Recurse -Force $dest
}

if (-not (Test-Path $tmp)) {
    Write-Host "downloading $Url"
    Invoke-WebRequest -Uri $Url -OutFile $tmp
}

$got = (Get-FileHash -Algorithm SHA256 $tmp).Hash.ToLower()
if ($got -ne $Sha256) {
    Remove-Item -Force $tmp
    throw "sha256 mismatch for $Tag`n  expected $Sha256`n  got      $got"
}

New-Item -ItemType Directory -Force -Path $dest | Out-Null
# ⚠ --strip-components=1: the tarball's top directory carries the version, and
# a path that changes with every bump would break the CMakeLists.
tar -xzf $tmp -C $dest --strip-components=1
Set-Content -Path (Join-Path $dest "VERSION") -Value $Tag -Encoding ascii

foreach ($f in $needed) {
    if (-not (Test-Path (Join-Path $dest $f))) {
        throw "quickjs $Tag is missing $f -- the source layout changed, fix app/src/main/cpp/CMakeLists.txt"
    }
}

Write-Host "quickjs $Tag extracted to $dest"
