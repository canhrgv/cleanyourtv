# Installs an APK from this computer on an Android 5 or 6 TV with the platform-tools adb, after checking
# that it can run there: its minimum Android version against the TV's, and its native code against the
# TV's CPU (the Xiaomi Mi TV 3 is 32-bit ARM, so arm64-only APKs are refused). Pushes the file and runs
# `pm install -r`, since a streamed `adb install` can hang after Success on Android 5.1.
#
# Usage: tools\install-apk-android5.ps1 -TvHost 192.168.1.11 path\to\app.apk [-Force]
#        -Force skips the compatibility checks. Look for builds marked "minapi-21" or "Android 5.0+".
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Apk,
    [string]$TvHost = $env:TV_HOST,
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$Apk = (Resolve-Path $Apk).Path

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $sdk 'platform-tools\adb.exe' }
if (-not (Test-Path $adb)) { throw 'adb not found: put platform-tools on PATH or set ANDROID_HOME' }
$device = if ($TvHost -match ':') { $TvHost } else { "${TvHost}:5555" }

# adb against the TV; see launcher\build-and-install-android5.ps1 for why stderr is folded in.
function Invoke-Adb([string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    @(& $adb -s $device @Arguments 2>&1 | ForEach-Object { "$_".TrimEnd() } | Where-Object { $_ })
}

function Tv([string]$Command) { Invoke-Adb @('shell', $Command) }

$connect = & { $ErrorActionPreference = 'Continue'; "$(& $adb connect $device 2>&1)" }
if ($connect -notmatch 'connected') { throw "adb could not reach ${device}: $connect" }
$api = [int]((Tv 'getprop ro.build.version.sdk') | Select-Object -First 1)
$abis = ((Tv 'getprop ro.product.cpu.abilist') | Select-Object -First 1) -split ','

# What the APK needs, from aapt in the newest build-tools (skipped when there is none).
$aapt = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
    Sort-Object { [version]($_.Name -replace '[^\d.]', '') } -Descending |
    ForEach-Object { Join-Path $_.FullName 'aapt.exe' } | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($aapt) {
    $badging = & { $ErrorActionPreference = 'Continue'; & $aapt dump badging $Apk 2>$null }
    # \s before versionName: the same line also carries platformBuildVersionName.
    $name = ($badging | Select-String "^package: name='([^']+)'.*?\sversionName='([^']*)'" | Select-Object -First 1).Matches
    $label = ($badging | Select-String "^application-label:'([^']*)'" | Select-Object -First 1).Matches
    $minSdk = ($badging | Select-String "^sdkVersion:'(\d+)'" | Select-Object -First 1).Matches
    $native = ($badging | Select-String "^native-code: (.*)$" | Select-Object -First 1).Matches
    if ($name) { Write-Host ("APK: {0} {1} ({2})" -f $(if ($label) { $label[0].Groups[1].Value } else { '' }), $name[0].Groups[2].Value, $name[0].Groups[1].Value) }
    $problems = @()
    if ($minSdk -and [int]$minSdk[0].Groups[1].Value -gt $api) {
        $problems += "needs API $($minSdk[0].Groups[1].Value), the TV has API $api"
    }
    if ($native) {
        $apkAbis = [regex]::Matches($native[0].Groups[1].Value, "'([^']+)'") | ForEach-Object { $_.Groups[1].Value }
        if (-not ($apkAbis | Where-Object { $abis -contains $_ })) {
            $problems += "native code for $($apkAbis -join ', ') only, the TV runs $($abis -join ', ')"
        }
    }
    if ($problems -and -not $Force) { throw ("This APK will not run on the TV: " + ($problems -join '; ') + ". Look for an older build, or pass -Force.") }
    foreach ($p in $problems) { Write-Warning $p }
} else {
    Write-Warning 'aapt not found in build-tools; installing without the compatibility check.'
}

Invoke-Adb @('push', $Apk, '/data/local/tmp/install.apk') | Out-Null
Write-Host 'Installing. If the TV shows "Install Confirmation", press OK on the remote.'
$out = (Tv 'pm install -r /data/local/tmp/install.apk') -join ' '
Tv 'rm -f /data/local/tmp/install.apk' | Out-Null
Write-Host $out
if ($out -notmatch 'Success') { throw "pm install failed: $out" }
