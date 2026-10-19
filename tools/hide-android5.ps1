# Android 5 and 6 counterpart of disable.ps1, for firmware whose shell may not disable packages
# (Xiaomi Mi TV 3): hide every package listed in a debloat file for user 0 with `pm hide` (one package
# per line, # comments allowed). Nothing is uninstalled. Uses the platform-tools adb, no Python.
#
# Usage: tools\hide-android5.ps1 -TvHost 192.168.1.11 examples\xiaomi-mitv3-inception\debloat.txt
# Undo:  tools\restore-android5.ps1 -TvHost 192.168.1.11 -StockLauncher none -List <same file>
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$List,
    [string]$TvHost = $env:TV_HOST
)
$ErrorActionPreference = 'Stop'
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$List = (Resolve-Path $List).Path

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

$pkgs = Get-Content $List | ForEach-Object { ($_ -replace '#.*$', '').Trim() } | Where-Object { $_ }
$failed = 0
foreach ($p in $pkgs) {
    # One pm per shell call: chained pm commands can stop after the first (docs/TROUBLESHOOTING.md).
    $out = (Tv "pm hide $p") -join ' '
    if ($out -notmatch 'hidden state: true') { $failed++ }
    Write-Host "$p -> $out"
}
Write-Host ("{0} of {1} packages hidden." -f ($pkgs.Count - $failed), $pkgs.Count)
