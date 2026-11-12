# Home music spectrum

The unused top-center header gap between the clock and connectivity cluster now contains a real music-reactive spectrum. The media, calendar, GPS and navigation cards keep their previous layout. All 20 bars inherit the launcher's metallic accent and day/night palette.

## Behavior and consent

- Bars appear only while Home is in the foreground and a MediaSession reports playing music. They disappear on pause, leaving Home, choosing Off, or switching to Performance mode. The local player sends immediate play/pause state changes; other sessions also use the launcher's existing status polling.
- The first playing track exposes a quiet **Enable music spectrum** action in that header space. Tapping opens the consent/options menu; permission dialogs never open automatically. The same options are under **Launcher settings → Home music spectrum**. Once enabled, tap the bars for options or long-press to open the equalizer.
- **Local player** attaches only to OABL Music's audio session. **All players** explicitly opts into Android output-mix analysis for external player sessions; these also need the launcher's existing Media & navigation notification access to report playback state. Projection, Bluetooth, protected or offloaded paths can bypass visualization. A quiet/unavailable route stays flat with an explanatory status, never randomly animated fake levels.
- Android's Visualizer API requires `RECORD_AUDIO`; microphone hardware is explicitly optional. This implementation does not open a microphone, retain audio, write captures, or upload audio. Output-mix mode can include other device audio, including guidance; capture pauses when Android reports a non-normal audio mode. This is not a guarantee that every vendor phone-projection call is detectable.

The spectrum measures normalized spectral activity, not calibrated loudness, speaker response, EQ-band gain, or clipping. Its analysis-buffer normalization keeps quiet listening visible and does not alter playback volume, ReplayGain, or EQ settings.

## Performance and lifecycle

`PlaybackSpectrum` lazily creates one worker thread and one native Visualizer, reads a reusable 1024-byte (or supported smaller) FFT buffer at up to 20 Hz, and maps it into logarithmic display bands. At most one display frame is in flight. `HomeSpectrumView` reuses its paint and gradient; it has no perpetual animation timer. Pausing/hiding/bypass removes capture callbacks and releases the native effect. Unsupported attachment is not retried every status poll; source changes, foreground return or explicit options can retry.

No audio-driver changes, root, new third-party library, session-0 EQ changes, or saved equalizer edits are introduced. Actual CPU usage and proprietary CarPlay/Android Auto routes still need physical AI-box verification.

## Verification

The emulator passed 73 checks using actual local WAV/FLAC playback and native FFT capture, not pre-filled bar heights. It covers silence and FFT layout, logarithmic mapping, real nonzero PCM energy, bounded capture rate, all seven metallic palettes in both themes, header geometry at native 1920 × 970 and temporary ultra-wide 1920 × 720, pause/resume, leaving/returning Home, permission denial, explicit output-mix gating, Performance mode and retained EQ settings. The ultra-wide check validates the header only; older compact dashboard content below it still has clipping at that reduced height and is not claimed fixed by this feature. Temporary profile/music preferences and display overrides are restored before instrumentation exits. Debug build and lint passed.

The emulator alone was granted audio permission for native verification. The physical AI box is offline and has not received this update or permission changes.

```sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug -PoablTestRunner=com.softiva.oabl.SpectrumTestRunner
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell pm grant com.softiva.oabl android.permission.RECORD_AUDIO
adb -s emulator-5554 shell am instrument -w -r com.softiva.oabl.test/com.softiva.oabl.SpectrumTestRunner
```

Previews: [night](screenshots/home-spectrum-dark.png), [day](screenshots/home-spectrum-light.png), [paused](screenshots/home-spectrum-paused.png), [ultra-wide](screenshots/home-spectrum-ultrawide.png). These use the bundled sample wallpaper and seeded test music, not a car listening test.

Primary API reference: [Android Visualizer](https://developer.android.com/reference/android/media/audiofx/Visualizer), including its permission requirements, session/output-mix distinction, signed complex FFT layout and scaling modes.
