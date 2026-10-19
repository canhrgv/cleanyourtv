# Android 5 and 6 counterpart of theme.ps1 that uses the platform-tools adb instead of tools\tv.py: push a
# theme folder (config.json, and optionally wallpaper.jpg and a shelf\ folder of top shelf pictures) to the
# launcher on the TV and restart Home. See themes/README.md for the keys.
#
# Usage: tools\theme-android5.ps1 -TvHost 192.168.1.11 themes\mitv3-vn
#        tools\theme-android5.ps1 -TvHost 192.168.1.11 -Reset
param(
    [Parameter(Position = 0)][string]$Theme,
    [switch]$Reset,
    [string]$TvHost = $env:TV_HOST
)
$ErrorActionPreference = 'Stop'
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
if ($Theme) { $Theme = (Resolve-Path $Theme).Path }

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
$pkg = 'com.gathin.tvhome'
$dir = "/sdcard/Android/data/$pkg/files"

function Restart-Home {
    Tv "am force-stop $pkg" | Out-Null
    Tv "am start -n $pkg/.MainActivity" | Out-Null
}

if ($Reset) {
    Tv "rm -f $dir/config.json $dir/wallpaper.jpg" | Out-Null
    Tv "rm -r $dir/shelf" | Out-Null
    Restart-Home
    Write-Host 'Theme removed; the launcher uses its built-in config.xml again.'
    exit 0
}
if (-not $Theme) { throw 'usage: theme-android5.ps1 <theme folder> | -Reset' }
$config = Join-Path $Theme 'config.json'
if (-not (Test-Path $config)) { throw "no config.json in $Theme" }
try { Get-Content $config -Raw | ConvertFrom-Json | Out-Null } catch { throw "$config is not valid JSON" }

Tv "mkdir -p $dir" | Out-Null
Invoke-Adb @('push', $config, "$dir/config.json") | Out-Null
$wall = Join-Path $Theme 'wallpaper.jpg'
if (Test-Path $wall) { Invoke-Adb @('push', $wall, "$dir/wallpaper.jpg") | Out-Null } else { Tv "rm -f $dir/wallpaper.jpg" | Out-Null }
# The shelf folder is replaced as a whole, so pictures dropped from the theme go too.
Tv "rm -r $dir/shelf" | Out-Null
$shelf = Join-Path $Theme 'shelf'
$pictures = @(Get-ChildItem $shelf -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -match '^\.(jpe?g|png|webp)$' })
if ($pictures.Count -gt 0) {
    Tv "mkdir -p $dir/shelf" | Out-Null
    foreach ($p in $pictures) { Invoke-Adb @('push', $p.FullName, "$dir/shelf/$($p.Name)") | Out-Null }
}
Restart-Home
Write-Host ("Theme {0} is on the TV ({1} top shelf pictures). Undo with: tools\theme-android5.ps1 -TvHost {2} -Reset" -f (Split-Path $Theme -Leaf), $pictures.Count, $TvHost)
