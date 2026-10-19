# Themes

A theme is a folder with a `config.json` and, optionally, a `wallpaper.jpg`. Pushing one changes the
launcher's dock, layout and look on the TV without rebuilding the APK: the launcher reads
`config.json` as an overlay over its built-in `config.xml` the next time it starts.

```bash
TV_HOST=192.168.1.50 tools/theme.sh themes/aurora          # push a theme and restart Home
TV_HOST=192.168.1.50 tools/theme.sh --reset                # back to the built-in config.xml
powershell -ExecutionPolicy Bypass -File tools\theme.ps1 -TvHost 192.168.1.50 themes\aurora
```

| Theme | What it does |
|---|---|
| `default/` | The built-in look written out as JSON, as a starting point for your own |
| `aurora/` | A dark aurora wallpaper (teal and violet on navy, darker at the bottom) with a matching background colour |
| `six-columns/` | Six smaller tiles per row and one grid row visible under the dock |
| `mitv3-vn/` | Navy-black background and abstract top shelf pictures in each brand's colours for YouTube, Netflix, FPT Play and VTV Go; made on a Xiaomi Mi TV 3 (push with `tools\theme-android5.ps1`) |

## Keys

Every key is optional; a missing key keeps the value from `config.xml`, an unknown or wrongly typed key
is ignored, and a file that doesn't parse is ignored as a whole, so a theme can never break Home.

| Key | Type | Meaning |
|---|---|---|
| `dock_apps` | list of package names | The dock row, in order; missing apps are skipped |
| `hidden_apps` | list of package names | Never shown as tiles |
| `live_tv_package`, `live_tv_action` | string | The Live TV app and, if it has no leanback entry, the action to start it with |
| `source_panel_service`, `source_panel_action` | string | The TV's own source panel, for sets with no TvInputManager |
| `input_action` | string | Action sent with an HDMI/AV row's channel URI when nothing handles the standard `VIEW` (XGIMI: `com.xgimi.action.hdmiPlayer`) |
| `columns` | 1 to 8 | Tiles per row; tiles shrink to fit from 5 up |
| `rows_below_dock` | 0 to 3 | Grid rows left visible under the dock |
| `shamsi_date` | bool | Show the Solar Hijri date above the Gregorian one |
| `module_cards` | bool | Look for module cards and ask about them (`true`); `false` turns the feature off |
| `vpn_app` | string | App the network pill opens when a VPN is configured; also shows a red "VPN off" badge while no VPN is up |
| `show_home_settings_shortcut` | bool | The "Default home" tile, where the TV has a home picker |
| `background_color` | `#RRGGBB` | Colour behind everything; black by default |
| `wallpaper` | bool | Show `wallpaper.jpg` from the theme folder (`true`), or ignore it (`false`) |
| `include_phone_apps` | bool | Also tile installed apps that only have a phone launcher entry (TVs that are not Android TV) |
| `source_intent_action`, `source_intent_extra`, `source_list` | string, string, list of `"label\|id"` | Inputs menu rows for TVs without TvInputManager: each sends the action with the id as an int extra (Xiaomi MIUI TV: `com.xiaomi.mitv.tvplayer.EXTSRC_PLAY`, `input`, `"HDMI 1\|23"`) |
| `tv_settings_action` | string | The TV maker's settings, as the last row of the Inputs menu |
| `cast_component` | string | The TV's screen mirroring app as `package/.Activity`, a row in the Inputs menu |
| `weather_location` | string | `"latitude,longitude"` for the weather next to the clock; `""` turns it off |

## Top shelf pictures

A `shelf/` folder in the theme, with one picture per dock app named after its package
(`shelf/com.netflix.ninja.jpg`; `.png` and `.webp` work too), fills the screen behind the app's name
while its dock tile is focused, fading into `background_color` towards the dock. Apps without a picture
keep the coloured glow. Keep them 1920×1080, dark at the bottom and under about 150 KB; the launcher
decodes them at half size, about 1 MB of memory each, for the dock apps only.

## Wallpapers

`wallpaper.jpg` is drawn full screen behind the launcher, centre-cropped. Keep it dark, 1920×1080 and
under about 300 KB: the text, the hero name and the dock band are designed for black, and a 1 GB TV
decodes the picture into memory on every Home start. Pictures larger than the screen are downsampled.

## Adding a theme

Open a PR with a new folder here, a one-line row in the table above and a screenshot from a real TV
in the PR description. Keep the dock list generic (the big streaming apps) unless the theme is for a
specific TV, and say which TV you tried it on.
