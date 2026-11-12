# Open AI Box Launcher (OABL)

Open AI Box Launcher is a landscape-first Android HOME app for in-car AI boxes. Its quiet glance screen combines car battery/range, speed, large media controls, a clock-side climate summary, status, shortcuts, and a complete app drawer. The real Google Maps app runs beside it in Android's native split view.

This repository contains a native Android launcher and local music player. It supports Android 7.0+ and targets Android 16/API 36. The music player runs AndroidX Media3 (ExoPlayer) in its own process and decodes with the device's own codecs (FLAC, MP3, AAC, Ogg Vorbis, Opus, WAV); the interface uses native Android views rather than a web runtime.


This is a personal project for one car (a Dongfeng Nammi 01) and one AI box, published as-is. Release notes, captures and device dumps are kept out of this repository; the `docs/` folder holds the design notes and findings.

## What works in the MVP

- A real Android `HOME` activity that can replace the stock launcher.
- A clean two-column glance screen with a large digital clock and climate summary, car battery/range, GPS or car speed, and media controls.
- A quiet, phone-style travel-status pill with level-aware Wi-Fi and cellular bars, Wi-Fi name, carrier/data badge, connected Bluetooth device, and active-call information. Cellular status appears only when a SIM is present. Tap it for details and settings.
- A polished Visuals mode with staged entrance motion, tactile tile feedback, media crossfades, app-drawer reveal, and navigation-overlay transitions.
- A one-tap Performance mode that removes nonessential animation without removing features.
- Dark, Light, and Auto appearance modes. Auto follows Android's current day/night setting.
- Gradient or soft-blurred treatment for a user-selected background image.
- Active media as a compact glass widget with title, artist, artwork, and controls.
- A local music library with recursive folder indexing, search, album artwork, Favorite Songs, saved playlists, and background playback. See [music player design](docs/music-player-design.md).
- Persistent native split view with the complete Google Maps app: search, directions, live navigation, traffic, and all normal Maps controls.
- Android owns the divider, so the Maps width is freely draggable instead of limited to fixed presets.
- Four customizable shortcuts. Long-press a shortcut to replace it.
- A full app drawer with app icons and labels.
- User-selected wallpaper with a persistent document permission.
- A saved two-app pair that launches directly into Android split-screen on Android 12L+. Long-press the tile to choose the two apps, swap their sides, or exit the active split view.
- Quick access to Wi-Fi, media access, accessibility, and Android settings.

## Important platform boundary

An ordinary third-party launcher cannot safely place an arbitrary app such as Google Maps or YouTube inside one of its own views, and it cannot replace Android's secure keyguard. OABL therefore uses supported Android behaviors:

- The Home screen provides the requested lock-screen aesthetic, but it is a launcher glance screen rather than a security boundary.
- Google Maps remains a separate Android task. OABL restores it beside the dashboard instead of attempting to embed or imitate it.
- Speed is calculated locally from GPS while Home or navigation mode is visible.
- Media is supplied through Android `MediaSession`; OABL does not capture audio or video content.
- The split-pair button asks Android to run two independent apps side by side. Android owns the divider, so it remains adjustable.
- Android 12L+ uses the supported adjacent-launch flow without accessibility access. Android 7–12 uses an optional accessibility fallback only when that system action is available. Its configuration explicitly disables window-content retrieval.

Multi-window behavior is ultimately controlled by the AI box firmware. A vendor can disable it, and individual apps can be incompatible on Android 11 and older.

## First run

1. Install the APK.
2. Press Home and select **Open AI Box Launcher**.
3. Open **Settings** in the dock.
4. Choose a wallpaper and background treatment.
5. Enable **Google Maps beside OABL** and the split-screen assistant when prompted.
6. Enable **Media access** if you want now-playing information and controls.
7. Long-press any of the first four dock tiles to customize it.

If the box has a SIM, tap the travel-status pill and then the carrier row once to enable live cellular bars and the data-type badge. Android labels this standard read-only status permission as Phone access; OABL does not place calls or read call history.

## Build

The project uses Android Gradle Plugin 9.0.1, Gradle 9.1, and Java 11 source compatibility. No Google Maps API key is required because OABL launches the installed Google Maps app in native split view.

```bash
./gradlew :app:assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

For distribution, configure a release signing key outside the repository before producing an AAB or signed APK. Do not distribute the debug build.

## Device target

The first compatibility target is the previously inspected Dongfeng/LY head unit:

- Android 10 / API 29
- arm64
- landscape display
- custom vendor Bluetooth and package-management stack

The launcher itself avoids vendor APIs so it can be distributed to other Android AI boxes. Device-specific integrations, such as direct vehicle controls or vendor Bluetooth recovery, should live in optional adapters rather than the launcher core.

## Privacy

OABL has no analytics, account, or OABL backend. Preferences stay on the box. Google Maps handles its own location, search, and route data in its separate app. OABL uses network access for weather, artwork and shared-place resolution. Read-only phone state is used only for SIM presence, carrier, cellular strength, and the displayed data type; OABL does not place calls or read call history. Notification access is used for active media sessions and, when present, the currently active call notification; notification content is never stored. Bluetooth access is used only to show the name of a connected audio/headset device. The split-screen accessibility service does not request or inspect window contents.

## Next milestones

- Test and tune the layout on the physical 1920×970 Android 10 unit.
- Add a safe first-run onboarding flow and default-launcher recovery instructions.
- Add optional theme packs and independent widget-layout choices.
- Build a compatibility report for common AI box firmware families.
- Add signed release builds, update metadata, license, and a public privacy policy.

## License

[MIT](LICENSE). Third-party libraries keep their own licences.
