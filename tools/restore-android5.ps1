# Android 5 and 6 version of restore.ps1, for TVs whose shell has no `cmd` (Xiaomi Mi TV 3, Android 5.1):
# unhide and re-enable the stock launcher that launcher\build-and-install-android5.ps1 put away, so Home
# goes back to it, plus every package in a debloat list if you pass one. Uses the platform-tools adb, no Python.
#
# Usage: tools\restore-android5.ps1 -TvHost 192.168.1.11 [-StockLauncher <package>|none] [-List <debloat.txt>] [-RemoveLauncher]
#        The stock launcher defaults to the one the install script saved in the newest backup\*\stock-launcher.txt;
#        `none` with -List undoes tools\hide-android5.ps1 only and keeps this launcher as Home.
#        -RemoveLauncher also uninstalls this launcher; by default it stays installed, as with restore.ps1.
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$StockLauncher = '',
    [string]$List = '',
    [switch]$RemoveLauncher
)
$ErrorActionPreference = 'Stop'
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$repo = Split-Path -Parent $PSScriptRoot
$self = 'com.gathin.tvhome'

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $sdk 'platform-tools\adb.exe' }
if (-not (Test-Path $adb)) { throw 'adb not found: put platform-tools on PATH or set ANDROID_HOME' }
$device = if ($TvHost -match ':') { $TvHost } else { "${TvHost}:5555" }

# adb against the TV; see build-and-install-android5.ps1 for why stderr is folded in.
function Invoke-Adb([string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    @(& $adb -s $device @Arguments 2>&1 | ForEach-Object { "$_".TrimEnd() } | Where-Object { $_ })
}

function Tv([string]$Command) { Invoke-Adb @('shell', $Command) }

# Where HOME resolves: "Activity: pkg/cls", or the component named in a Permission Denial (Xiaomi guards
# its launcher with HOME_STATE, so the shell may not start it, but the answer is in the message).
function Get-HomeTarget {
    $out = (Tv 'am start -W -a android.intent.action.MAIN -c android.intent.category.HOME') -join "`n"
    if ($out -match 'Activity: (\S+)') { return $Matches[1] }
    if ($out -match 'cmp=(\S+?)[ }]') { return $Matches[1] }
    return $out
}

if (-not $StockLauncher) {
    $saved = Get-ChildItem (Join-Path $repo 'backup') -Recurse -Filter 'stock-launcher.txt' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($saved) { $StockLauncher = "$(Get-Content $saved.FullName | Select-Object -First 1)".Trim() }
    if (-not $StockLauncher -or $StockLauncher -eq 'none') {
        throw 'no saved stock launcher: pass -StockLauncher <package> (Xiaomi Mi TV 3: com.mitv.tvhome), or none to restore only -List'
    }
}

$connect = & { $ErrorActionPreference = 'Continue'; "$(& $adb connect $device 2>&1)" }
if ($connect -notmatch 'connected') { throw "adb could not reach ${device}: $connect" }

# -StockLauncher none: bring back only the debloat list and leave this launcher as Home.
$pkgs = @()
if ($StockLauncher -ne 'none') { $pkgs += $StockLauncher }
if ($List) {
    $pkgs += Get-Content (Resolve-Path $List).Path | ForEach-Object { ($_ -replace '#.*$', '').Trim() } | Where-Object { $_ }
}
# The install script hides the stock launcher where the shell may not disable it, so undo both; on such
# firmware `pm enable` reports a Permission Denial for a package that was never disabled, which is harmless.
foreach ($p in $pkgs) {
    # One pm per shell call: chained pm commands can stop after the first (docs/TROUBLESHOOTING.md).
    Write-Host ((Tv "pm unhide $p") -join ' ')
    Write-Host ((Tv "pm enable $p") -join ' ')
}
if ($RemoveLauncher) { Write-Host ((Tv "pm uninstall $self") -join ' ') }

$target = Get-HomeTarget
if ($StockLauncher -eq 'none') {
    Write-Host "Home resolves to $target."
} elseif ($target -like "$StockLauncher/*") {
    Write-Host "Home resolves to $target again. Press Home on the remote."
} elseif ($target -match 'ResolverActivity') {
    Write-Host "Home opens Android's chooser: on the TV pick the stock launcher, then Always."
} else {
    Write-Warning "Home resolves to '$target'. Check Settings > Home on the TV."
}
Write-Host 'Disabled packages left:'
Tv 'pm list packages -d' | ForEach-Object { Write-Host "  $_" }
