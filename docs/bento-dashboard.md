# Bento dashboard prototype

The launcher uses a full-height month calendar on the left, large GPS speed and local trip metrics in the centre, and stacked media/navigation on the right. The duplicate top trip summary and agenda card were removed after user feedback. The dock retains four customizable shortcuts, Split pair (long-press editor), Apps and Settings, and adds a permanent Equalizer action. Command icons now use matching 42dp circular plates with native vector glyphs.

## Design rationale

The layout adapts Google's [Design for Driving](https://developers.google.com/cars/design/design-foundations) principles: glanceable primary information, consistent accents, separate day/night palettes, and large primary controls. Media controls are 76dp square at the head-unit configuration. Compact layouts use smaller controls and are not claimed to meet automotive touch-target guidance. This is a standard Android launcher, not an AAOS-certified application.

Dark charcoal/metallic and light pearl/slate cards use translucent rounded fills without decorative borders. Existing wallpaper selection, top anchoring, optional wallpaper blur, and Performance/Visuals settings remain available. Card translucency is not a true per-card backdrop blur. Auto theme now uses the requested IST schedule: Light 8:30 AM–5 PM, Dark overnight, independent of system theme/timezone and not a vehicle headlight signal. Dashboard, EQ and navigation overlays update when active and on resume.

## Real bindings and deliberate limitations

- GPS speed, accuracy-filtered position, reverse-geocoded road (coordinates if unavailable), compass course and GNSS used/visible satellite counts. Stale GPS is labelled standby, never mistaken for a confirmed stopped vehicle.
- Local distance, moving time, average and peak speed persist between launches. Stationary jitter, inaccurate fixes, duplicate observations, large jumps and long gaps are excluded. Tracking runs while the dashboard or user-started navigation overlay is active; it does not continuously track arbitrary apps in the background.
- Long-press the speed gauge for an optional **manual** speed reminder. The warning arc is static red above that threshold. This is not a map-derived legal speed limit.
- Long-press trip metrics to reset, with confirmation.
- Calendar shows the month only; no event ticker or calendar-read permission. Tap to open Calendar.
- Settings → Metallic finish offers Titanium Silver, Midnight Blue, Amethyst Metallic, Burgundy Red, Champagne Gold, Racing Green and Copper Bronze. These are car-paint-inspired finishes, not claims of official Dongfeng paint codes. Gradient swatches suggest metallic highlights; Light/Dark tones preserve readability. Selection updates headings, gauge, calendar highlight, primary actions, dock controls and equalizer colors immediately.
- Settings → Vehicle maximum speed changes the gauge's full-scale value (40–400 km/h), default 140 for this user's EV. It is independent of the optional manual speed reminder. Nothing controls or limits the vehicle.
- Wi-Fi bars and name, RSSI, link speed, band and IPv4 address where Android exposes them; cellular bars/data type, Bluetooth name and call status retain existing permission-gated behavior. Full details remain in the travel-status dialog.
- The header also shows Android's device battery percentage and charging/full status, labelled **Device**. Batteryless boxes show external power/no battery, and missing or invalid readings show unavailable. This needs no additional permission and does not represent EV battery state.
- Media uses real Android media sessions. Guidance uses provider notification text/icon and clears to an idle navigation card when the route ends. ETA and remaining distance appear only if provided by the navigation notification; no directions are inferred from GPS.
- No vehicle battery/CAN or automatic road speed-limit source is connected, so those fictional sample values are deliberately absent. The user's car is a 2026 Dongfeng Nammi 1 / Box E3, with no OBD adapter. No compatible battery-data interface has been verified. Settings explains supported vehicle API, vendor CAN, or vehicle-compatible OBD requirements; Android's device battery is never displayed as EV state of charge. Android Automotive's [energy properties](https://developer.android.com/reference/android/car/VehiclePropertyIds#EV_BATTERY_LEVEL) require an actual supported vehicle interface and permission; the ordinary tablet emulator does not provide one.
- Weather is off by default. Enabling it shares a rounded 0.1-degree GPS area with Open-Meteo; requests are cached/throttled and run off the UI thread. This prototype uses Open-Meteo's non-commercial endpoint. Before commercial distribution, configure a licensed provider/customer endpoint and satisfy [Open-Meteo's pricing and attribution terms](https://open-meteo.com/en/pricing). Do not distribute the prototype endpoint as a commercial production integration.

## Simulator verification

Existing `Medium_Tablet` Android emulator, display override **1920 × 970, density 238**, matching the observed head unit. No physical box display settings changed. Emulator Android version is newer than the Android 13 head unit, so hardware/API-33 deployment still needs a real-device pass.

`NavigationHintsTestRunner` covers Light/Dark column bounds, unclipped card/weather/agenda/control bounds, all eight dock items, opening the actual equalizer activity, provider filtering and idle cleanup, and synthetic GPS trip aggregation/filtering. Test guidance appears only in the instrumentation APK and is labelled TEST GUIDANCE in screenshots.

Build: `./gradlew assembleDebug assembleDebugAndroidTest lintDebug`

Runner: `adb -s emulator-5554 shell am instrument -w com.softiva.oabl.test/com.softiva.oabl.NavigationHintsTestRunner`

Verified 2026-10-13 after feedback: 93 dashboard assertions passed, including full Wi-Fi line metrics, hidden agenda/removed duplicate summary, seven live accent changes, persisted maximum-speed configuration, correct gauge scaling/clamping, and real settings dialog input/Save validation. The earlier separate EqualizerTestRunner passed 62 DSP/UI checks (silent emulator session, not an audible car test). Debug build and lint completed successfully.

Screenshots: `docs/screenshots/bento-dashboard-dark.png` and `bento-dashboard-light.png`. Emulator Wi-Fi/cellular/GNSS are simulated hardware values, not readings from the AI box.

Device battery addition: 101 assertions passed, including charging/discharging percentage, alternate reporting scales, missing/invalid readings, batteryless external-power handling, and unclipped battery text in both themes. Debug build and lint passed.

Metallic-finish update: 102 dashboard checks passed, including all seven live palette changes and opening/dismissing the new gradient-swatch selector. Preview: `screenshots/metallic-finish-selector.png`. The 31-band equalizer separately passed 117 native DSP/UI/migration checks; see `equalizer.md` for audio route limitations.
