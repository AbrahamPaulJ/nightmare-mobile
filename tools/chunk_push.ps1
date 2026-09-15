# Chunked adb push, for a link that drops mid-transfer.
#
# adb push reports the full byte count even when the connection dies partway, so
# every chunk is verified by md5 ON THE DEVICE and only the failures are resent.
# A drop then costs one chunk, not the whole APK.
#
#   .\tools\chunk_push.ps1 -Adb <path\to\adb.exe> -Device <serial-or-host:port> `
#                          -Apk <path\to\app-release.apk> -Dest /sdcard/Download/nightmare.apk
#
# Nothing here is defaulted to a machine: pass the three paths. The staging area
# is the device's own /data/local/tmp, cleaned up on success.

param(
    [Parameter(Mandatory = $true)][string] $Adb,
    [Parameter(Mandatory = $true)][string] $Device,
    [Parameter(Mandatory = $true)][string] $Apk,
    [string] $Dest = "/sdcard/Download/nightmare.apk",
    [int]    $ChunkBytes = 6MB,
    [int]    $Tries = 4
)

$ErrorActionPreference = "Continue"
$work   = Join-Path $env:TEMP "nmchunks"
$remote = "/data/local/tmp/nmparts"

function Reconnect {
    & $Adb disconnect $Device | Out-Null
    Start-Sleep -Seconds 3
    & $Adb connect $Device | Out-Null
    Start-Sleep -Seconds 1
}

# --- split locally -------------------------------------------------------
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Path $work | Out-Null

$fs = [System.IO.File]::OpenRead($Apk)
$buf = New-Object byte[] $ChunkBytes
$i = 0
$parts = @()
while (($read = $fs.Read($buf, 0, $buf.Length)) -gt 0) {
    $name = "part{0:d3}" -f $i
    $path = Join-Path $work $name
    $out = [System.IO.File]::Create($path)
    $out.Write($buf, 0, $read)
    $out.Close()
    $parts += [pscustomobject]@{
        Name = $name
        Path = $path
        Md5  = (Get-FileHash $path -Algorithm MD5).Hash.ToLower()
    }
    $i++
}
$fs.Close()
"split into $($parts.Count) chunks of up to $ChunkBytes bytes"

# --- push each, verifying on the device ----------------------------------
& $Adb -s $Device shell "rm -rf $remote; mkdir -p $remote" | Out-Null
foreach ($p in $parts) {
    $ok = $false
    for ($t = 1; $t -le $Tries -and -not $ok; $t++) {
        & $Adb -s $Device push $p.Path "$remote/$($p.Name)" | Out-Null
        $there = (& $Adb -s $Device shell md5sum "$remote/$($p.Name)" 2>$null)
        if ($there) { $there = $there.Split(" ")[0] }
        if ($there -eq $p.Md5) { $ok = $true } else { "  $($p.Name) attempt $t mismatch"; Reconnect }
    }
    if (-not $ok) { throw "$($p.Name) would not transfer after $Tries attempts" }
}

# --- reassemble on the device and verify the whole file ------------------
& $Adb -s $Device shell "cat $remote/part* > $Dest" | Out-Null
$whole  = (Get-FileHash $Apk -Algorithm MD5).Hash.ToLower()
$onPhone = (& $Adb -s $Device shell md5sum $Dest).Split(" ")[0]
if ($whole -ne $onPhone) { throw "reassembled file does not match: $whole vs $onPhone" }

& $Adb -s $Device shell "rm -rf $remote" | Out-Null
Remove-Item -Recurse -Force $work
"pushed and verified: $Dest  ($whole)"
