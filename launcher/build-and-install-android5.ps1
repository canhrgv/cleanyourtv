# Android 5 and 6 version of build-and-install.ps1, for TVs whose shell has no `cmd` (Xiaomi Mi TV 3,
# Android 5.1). Builds the launcher, backs up the TV's package list and settings, installs the APK next to
# the stock launcher, starts it, then disables the stock launcher (or hides it where the shell may not
# disable packages) so Home lands here. Uses the platform-tools adb rather than tools\tv.py, so no Python.
#
# Usage: launcher\build-and-install-android5.ps1 -TvHost 192.168.1.11 [-StockLauncher <package>|none] [-NoBuild]
#        The stock launcher is read from the TV's home task when -StockLauncher is not given.
# Undo:  tools\restore-android5.ps1 -TvHost 192.168.1.11
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$StockLauncher = '',
    [switch]$NoBuild
)
$ErrorActionPreference = 'Stop'
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
Set-Location $PSScriptRoot
$self = 'com.gathin.tvhome'

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $sdk 'platform-tools\adb.exe' }
if (-not (Test-Path $adb)) { throw 'adb not found: put platform-tools on PATH or set ANDROID_HOME' }
$device = if ($TvHost -match ':') { $TvHost } else { "${TvHost}:5555" }

# adb against the TV. It reports push progress on stderr, which must not trip ErrorActionPreference; old
# adbd also sends CRLF and blank lines, so this returns trimmed non-empty lines.
function Invoke-Adb([string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    @(& $adb -s $device @Arguments 2>&1 | ForEach-Object { "$_".TrimEnd() } | Where-Object { $_ })
}

# One shell command on the TV.
function Tv([string]$Command) { Invoke-Adb @('shell', $Command) }

# Where HOME resolves now. `am start` prints "Activity: pkg/cls" when it may start it, and a Permission
# Denial naming the component when the stock launcher guards it (Xiaomi: HOME_STATE); both name the target.
function Get-HomeTarget {
    $out = (Tv 'am start -W -a android.intent.action.MAIN -c android.intent.category.HOME') -join "`n"
    if ($out -match 'Activity: (\S+)') { return $Matches[1] }
    if ($out -match 'cmp=(\S+?)[ }]') { return $Matches[1] }
    return $out
}

if (-not $NoBuild) {
    if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        $jbr = Get-ChildItem 'C:\Program Files\Android' -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName 'jbr' } | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
        if (-not $jbr) { throw 'Set JAVA_HOME to a JDK 17+ (Android Studio ships one in its jbr folder)' }
        $env:JAVA_HOME = $jbr
    }
    if (-not (Test-Path local.properties)) {
        "sdk.dir=$($sdk -replace '\\', '\\')" | Out-File -Encoding ascii local.properties
    }
    .\gradlew.bat assembleRelease -q --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
}
$apk = 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { throw "no APK at $apk; run without -NoBuild" }

$connect = & { $ErrorActionPreference = 'Continue'; "$(& $adb connect $device 2>&1)" }
if ($connect -notmatch 'connected') { throw "adb could not reach ${device}: $connect" }
$api = [int]((Tv 'getprop ro.build.version.sdk') | Select-Object -First 1)
if ($api -ge 24) { throw "This TV runs API $api; use build-and-install.ps1, which sets Home with cmd package." }
$model = ((Tv 'getprop ro.product.model') | Select-Object -First 1) -replace '[^\w.-]', '_'
Write-Host "TV: $model, API $api"

# Backup first: package lists, properties and settings, kept out of git (backup/ is ignored).
$backup = Join-Path (Split-Path -Parent $PSScriptRoot) ("backup\{0}-{1}" -f $model, (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $backup | Out-Null
Tv 'pm list packages -f' | Set-Content -Encoding UTF8 "$backup\packages.txt"
Tv 'pm list packages -d' | Set-Content -Encoding UTF8 "$backup\disabled.txt"
Tv 'getprop' | Set-Content -Encoding UTF8 "$backup\getprop.txt"
# `settings list` arrived in Android 6; the settings provider answers content queries on every version.
foreach ($ns in 'system', 'secure', 'global') {
    Tv "content query --uri content://settings/$ns" | Set-Content -Encoding UTF8 "$backup\settings-$ns.txt"
}
Write-Host "Backup: $backup"

if (-not $StockLauncher) {
    $homeTask = (Tv 'dumpsys activity activities') | Where-Object { $_ -match 'cat=\[android\.intent\.category\.HOME\]' } |
        ForEach-Object { if ($_ -match 'cmp=([\w.]+)/') { $Matches[1] } } | Where-Object { $_ -ne $self } | Select-Object -First 1
    if (-not $homeTask) {
        throw 'could not read the stock launcher from the home task; press Home on the TV once, or pass -StockLauncher <package>|none'
    }
    $StockLauncher = $homeTask
}
Write-Host "Stock launcher: $StockLauncher"
$StockLauncher | Set-Content -Encoding ASCII "$backup\stock-launcher.txt"

# Push and install with pm: a streamed `adb install` can hang after Success on Android 5.1.
Invoke-Adb @('push', $apk, '/data/local/tmp/tvhome.apk') | Out-Null
$out = (Tv 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk') -join ' '
Write-Host $out
if ($out -notmatch 'Success') { throw 'pm install failed; the stock launcher was left untouched' }

# Start the new launcher before the stock one goes, so Home always has somewhere to land.
$out = (Tv "am start -W -n $self/.MainActivity") -join ' '
if ($out -notmatch 'Status: ok') { throw "the new launcher did not start ($out); the stock launcher was left untouched" }

if ($StockLauncher -ne 'none') {
    # disable-user as in build-and-install.ps1. Some firmware strips that right from the shell for every
    # package (Xiaomi Mi TV 3), so fall back to hiding the package for user 0, which `pm unhide` undoes.
    $out = (Tv "pm disable-user --user 0 $StockLauncher") -join ' '
    if ($out -notmatch 'new state') {
        $out = (Tv "pm hide $StockLauncher") -join ' '
        if ($out -notmatch 'hidden state: true') { throw "could not disable or hide ${StockLauncher}: $out" }
    }
    Write-Host $out
}

$target = Get-HomeTarget
if ($target -like "$self/*") {
    Write-Host "Home now resolves to $target"
} elseif ($target -match 'ResolverActivity') {
    # Another app still claims HOME at the same priority: Android asks once, then remembers.
    Write-Host 'Home opens Android''s chooser: on the TV pick "Home" (this launcher), then Always.'
} elseif ($target -match '^[\w.]+/' -and $target -notlike "$StockLauncher/*") {
    # Another installed launcher is the saved default (Always in the chooser, or Settings > Home, where
    # pressing OK on a row already switches). Home works, so keep it and let the owner pick this one there.
    Tv 'am start -a android.settings.HOME_SETTINGS' | Out-Null
    Write-Host "Home is set to $target. Settings > Home is open on the TV: pick Home (this launcher)."
} else {
    if ($StockLauncher -ne 'none') {
        Tv "pm unhide $StockLauncher" | Out-Null
        Tv "pm enable $StockLauncher" | Out-Null
    }
    throw "Home resolves to '$target', not to the new launcher; $StockLauncher was re-enabled."
}
Write-Host 'Installed. Press Home on the remote to see it.'
