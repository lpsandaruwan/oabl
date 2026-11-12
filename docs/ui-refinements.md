# UI rework refinements

## Scope

Refinements to the existing reworked UI, not a replacement dashboard. No change to the
automatic day/night policy, CPU/thermal settings, media decoder, DSP engine, or saved tuning.
No general touch-target enlargement.

- The external-app Home tab is managed by application activity lifecycle callbacks. It stays
  off all OABL screens, including Music/EQ in separate tasks, and returns over external apps.
- Basic EQ keeps presets, curve, linked-band editing, undo/redo/reset, selected-band controls,
  comparison, bypass and master gain. Advanced disclosure retains speaker calibration,
  layers, routing, 31 bands, slider view, vocal/de-harsh/balance/speed lift, ReplayGain and
  peak protection. New profiles still default to 15 bands; existing 31-band settings are not
  silently converted. The EQ header/Back control remains pinned while content scrolls.
- Home adapts to window width and height. Wide layouts are measured to the exact stage bounds;
  narrow windows stack the same cards and let the dock scroll. Weather and telemetry details
  remain accessible inside their cards. No cards/data sources or dock shortcuts were removed.
- While driving with active guidance on a wide screen, current weather becomes compact,
  guidance grows beneath it, the speed/trip panel stays central and media gets the right column.
  Forecast and sunrise/sunset remain available from the climate summary beside the clock. The ordinary layout
  returns when guidance/driving mode ends. Narrow layouts retain guidance beside media in the
  stacked right-column group rather than trying to squeeze three columns into a split pane.
- Music shows a star on every song and displays the active sort. In short windows, artwork and
  metadata can scroll while playback and playlist/queue controls remain fixed. Compact browser
  filters/actions scroll horizontally instead of competing with the collection title.
- Controls uses an opaque frosted-colour surface (no real-time blur), with scroll fallbacks for
  constrained windows.

## Speed-limit provenance

The gauge caption distinguishes `MAPPED` (OSM maxspeed), `ESTIMATED` (road-class fallback),
`MANUAL` (the user's reminder) and `RAIN ADVISORY` (the existing weather-based advisory).
This is provenance, not a guarantee that mapped data matches a physical road sign.
New cache entries preserve provenance; legacy entries without it are treated as estimated.
No changes were made to the existing road-class/advisory numerical rules.

## Regression verification

Verified on the Android 13 ARM64 emulator on 2026-10-16: debug build and Android Lint
passed; the full refinement runner passed **365 checks**. The native 1920×970 display and
saved preferences were restored after the run. This APK was installed on the emulator,
not on the offline physical AI box.

`UiRefinementTestRunner` is emulator-only. It exercises 1920×970, 1920×720 and 960×970 in
light and dark modes, retained Home/EQ/music controls, visible transport/trip/navigation
content, Settings columns, opaque Controls, favourites, unchanged saved EQ configuration,
cache provenance, animated guidance transitions and external-app Home-tab behaviour.
It restores preferences, the navigation fixture and native display size in `finally`.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug \
  -PoablTestRunner=com.softiva.oabl.UiRefinementTestRunner
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w \
  com.softiva.oabl.test/com.softiva.oabl.UiRefinementTestRunner
```

Screenshots are written to the test device's app-specific external `files/ui-refinements`
directory. Navigation screenshots use a deterministic notification-text fixture, not a
claim of a live route. Half-width checks simulate the available pane size; they do not
assert that a particular AI-box firmware can launch a native split pair.
