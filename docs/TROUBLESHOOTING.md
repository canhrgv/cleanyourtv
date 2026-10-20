# Troubleshooting

Things that bit us or contributors, so they don't bite you. Commands use `tools/tv.py`; see the
[README](../README.md#doing-it-by-hand) for setting it up.

## Connecting

- **macOS: `adb connect` says "No route to host"** although the port is open. Recent macOS treats
  the Google-signed `adb` as its own app under Local Network privacy and silently blocks it.
  `tools/tv.py` avoids the binary entirely.
- **No "wireless debugging" toggle** on some TVs, Xiaomi among them. USB debugging alone opens
  network ADB on port 5555.
- **USB debugging never opens port 5555** on some Google TV sets (a Skyworth-built Philips among
  them). Pair once through Wireless debugging with the stock `adb` (`adb pair <ip>:<port> <code>`,
  then `adb connect`), run `adb tcpip 5555`, and `tools/tv.py` works from then on.
- **USB devices go dead while USB debugging is on**, seen on the Xiaomi Mi Box S: its firmware
  switches the USB port to ADB device mode, which cuts the power to a USB speaker or drive. Switch
  USB debugging off when you're done; the launcher doesn't need it.
- **Windows PowerShell 5.1 adds a byte-order mark** to text piped into a program, which turned the
  first batch command into gibberish. `tv.py` now strips it, and the `.ps1` scripts pipe clean UTF-8.
- **"Allow USB debugging?" comes back** although `adb devices` already works: `tv.py` used its own
  key next to the one platform-tools had registered. It now reuses `~/.android/adbkey` when that
  exists. Every attempt with an unaccepted key queues one more prompt on the TV, so dismiss them all
  (Back) before testing with key presses.
- **Network ADB is off again after every reboot** on the Xiaomi Mi TV 3 (Android 5.1): port 5555
  stays closed until you switch Settings → Account & security → ADB debugging on again. With this
  launcher that page is under Inputs → TV settings.
- **`adb install` hangs after printing `Success`** on Android 5.1 with platform-tools 37. Push the
  APK and run `pm install -r` on the TV instead; `build-and-install-android5.ps1` does.
- **Git Bash rewrites paths** like `/data/local/tmp` into Windows paths. The scripts set
  `MSYS_NO_PATHCONV=1` for you. Set `JAVA_HOME` and `ANDROID_HOME` before building there.

## Working on the TV

- **Chained `pm` commands** (`pm disable a; pm disable b`) may silently stop after the first. Run
  one per shell call; `--batch` does exactly that.
- **Large pushes hang** through the Python client. Use `tools/b64push.py` for APKs.
- **`pm disable-user` fails with "Permission Denial: attempt to change component state"** for every
  package on the Xiaomi Mi TV 3: the firmware withholds that right from the shell. `pm hide <package>`
  still works and `pm unhide` undoes it; the Android 5 scripts fall back to it for the stock launcher
  (`com.mitv.tvhome`, which runs as the system user).
- **Home asks "Select a Home app"** after the stock launcher is put away: a leftover third-party
  launcher also claims Home (FiredTV on a used Mi TV 3). Pick Home, then Always; Android keeps that
  until the set of Home apps changes. Android 5's Settings → Home switches the default as soon as you
  press OK on a row, so browsing that page can hand Home to the other launcher; pick Home there again.
- **`pm install` waits forever** on the Xiaomi Mi TV 3 after ADB debugging was switched on again: the
  TV shows "Install Confirmation: Do you agree to install tvhome.apk?". Press OK on the remote; leave
  "Apply to all" unticked unless you want every ADB install allowed without asking.
- **Xiaomi's My Apps stops listing the launcher** (`删除不合规应用` in the log): China firmware
  only lists apps on its own whitelist there. The launcher stays installed; once it is Home that
  list no longer matters.
- **A photo slideshow covers Home after a few idle minutes** on the Xiaomi Mi TV 3: Xiaomi's own
  screensaver (`com.xiaomi.tv.gallery`, a window called `my_test`) downloads pictures from Xiaomi and
  runs on its own timer, before the Clock screensaver's. Keep whichever you prefer: `pm hide
  com.xiaomi.tv.gallery` leaves the Clock, `pm unhide` brings the photos back.
- **The time zone can't be set over ADB** on Android 5.1 (`setTimeZone` needs SET_TIME_ZONE, which the
  shell lacks). Pick it on the TV in Settings → Date & time; turning off the automatic zone first
  (`settings put global auto_time_zone 0`) keeps the choice.
- **The weather stays empty** on Android before 7.1.1 if it is fetched over HTTPS: those sets lack the
  ISRG root behind Open-Meteo's Let's Encrypt certificate. The launcher asks over plain HTTP there.
- **Nothing happens at power-on although `CHECK_POWER_ON_INPUT` works over adb**: Xiaomi's firmware marks
  every third-party app stopped at boot (`PackageManager: stop: <package>` in the log) and delivers
  BOOT_COMPLETED only to its own apps (`unmatched pkg name=`). The launcher therefore treats Home's first
  start after a boot as the power-on (once per kernel boot id) and keeps BOOT_COMPLETED for other TVs.
- **An HDMI port counts as connected with its device switched off** on the Xiaomi Mi TV 3:
  `isSourceConnected` sees the cable, not a picture, and the TV only measures the signal of the input on
  screen. `power_on_inputs` therefore opens each connected port in turn; the switch reports "unlocked"
  about 6.5 s later and, when there is a picture, "locked" 0.5 to 3 s after that. The first
  `isSourceConnected` answer after the TV service is reached is empty. So the launcher looks for ports
  every 2 s for 20 s, waits up to 20 s per port and 8 s after an "unlocked". The firmware drops app logs
  at info level during boot, so every scan also writes its steps with seconds since boot to
  `/sdcard/Android/data/com.gathin.tvhome/files/power-on.log`.
- **Xiaomi's HTTP API on port 6095** (`/controller?action=changesource`) raises a Mi Link "allow this
  device" prompt on the TV instead of switching inputs. The Inputs menu sends the player's own
  intent instead (`config.xml`, `source_list`).
- **`adb exec-out screencap -p` gives a broken PNG** on some MediaTek Xiaomi sets: the binary prints
  `Init wrapper sys mutex successful` on stdout first. Save on the TV and pull the file instead:
  `screencap -p /data/local/tmp/s.png`.
- **Play Protect blocked the launcher** ("unsafe app blocked, built for an older version of Android")
  and `pm install` hung, then failed with `INSTALL_FAILED_VERIFICATION_FAILURE`, while it targeted
  SDK 29. It targets 34 now; if you built an old checkout, rebuild.
- **Screenshots come out empty** while Live TV, an HDMI input or Netflix is on screen, seen on TCL
  and a [Sony KDL-49W800G](https://github.com/Gathin23/cleanyourtv/issues/92). Press Home before
  `screencap`. An empty capture means nothing about the ADB connection or playback; check those
  separately.
- **Disable the stock launcher only after the new one is installed**, or the Home button lands on
  a factory test app. Launch the replacement by hand first. `set-home-activity` can report
  `Success` while HOME still resolves to the stock launcher (priority 2) until that one is disabled.
  And check which packages actually handle HOME before disabling anything by name: on the Sony
  above, `com.google.android.leanbacklauncher` has a boot receiver but no HOME activity.
- **The new launcher still doesn't own Home** after the stock one is disabled, seen on Google TV
  (Xiaomi MiTV-MOOR2). Another system app answers HOME: `com.google.android.tungsten.setupwraith`,
  with `.MainActivity` at intent-filter priority 4 and `.RecoveryActivity` at priority 1. You
  cannot outrank it, and the obvious workarounds don't help: Android ignores `android:priority` on
  HOME filters for apps that aren't on the system image, `cmd package set-home-activity` prints
  `Success` without changing anything (not even after a reboot), and `pm disable-user` on one of
  setupwraith's components is refused with a SecurityException. Disabling the whole
  `com.google.android.tungsten.setupwraith` package is what frees the slot. It is the first-run
  setup wizard, so everyday use is unaffected, but re-enable it before a factory reset - list it in
  your `debloat.txt` and `tools/restore.sh` does that for you.
- **The TV drops off Wi-Fi every minute right after the install**, seen on Google TV 14 (Xiaomi
  MiTV-MZTU0): the same setupwraith `.RecoveryActivity` took Home and system_server crash-looped
  on it. The install script now starts the new launcher before disabling the stock one, sets the
  home preference last (Android drops it whenever the set of HOME apps changes), then checks where
  Home resolves and re-enables the stock launcher if it is not the new one. Disable setupwraith as
  above. To confirm it is this bug: `logcat -b crash` shows `FATAL EXCEPTION IN SYSTEM PROCESS:
  android.display`, and the main log shows `setupwraith/.RecoveryActivity` being started every
  200 ms or so. If you're already in the loop:
  `tools/tv.py 'pm enable --user 0 com.google.android.apps.tv.launcherx'`.
- **No on-screen keyboard** after switching launchers, seen on Xiaomi: the default input method
  pointed at a Baidu keyboard that isn't installed. Fix:
  `tools/tv.py 'ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'`
- **Third-party launchers** often charge for basics like a custom wallpaper. That's why this repo
  has its own.
- **Wrong time zone, clock off by 30 or 60 minutes**, seen on Xiaomi in Iran: the TV picked a
  neighbouring zone, and older firmware ships time zone data from before Iran dropped daylight
  saving in 2022 (on Android 10 and newer, check with
  `tools/tv.py 'cat /apex/com.android.tzdata/etc/tz/tz_version'`). Set the zone yourself:
  `tools/tv.py 'settings put global auto_time_zone 0; cmd alarm set-timezone Asia/Tehran'`.
  If the clock then jumps an hour ahead in spring, a fixed offset keeps it right:
  `tools/tv.py 'cmd alarm set-timezone GMT+03:30'`. The same applies to any country whose rules
  changed after the firmware was built.
- **The network pill says "Wi-Fi" instead of the network name** after `pm clear com.gathin.tvhome`
  or a reinstall from scratch: clearing the launcher's data also drops the location permission
  Android 10 needs to read the SSID. `pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION`
  (the install script does this).
- **Home is "Select a Home app"** after `tools/restore.sh` followed by re-applying a list: disabling a
  package that also claims HOME (PatchWall on Xiaomi) makes Android drop the default. The install
  script now picks the known stock launcher among the candidates by itself; otherwise choose one on
  the TV or pass `STOCK_LAUNCHER=<package>`.
