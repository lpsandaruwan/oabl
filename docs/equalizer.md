# OABL audio equalizer

Current build: open the dock **Equalizer** shortcut, **Launcher Settings → Audio equalizer**, or a compatible player's Android audio-effect control-panel action.

**Launcher 0.1.125 (14 November 2026): the exciter adds only what lands at 10 kHz and above.**
- **Change:** the output high-pass moved from 1.5 times the "from" frequency (3.75 to 10.5 kHz) to a fixed 10 kHz, 4th order (-3 dB at 10 kHz, about -12 dB at 8 kHz). The "from" setting still chooses the band the harmonics are made from. From 5 kHz up its 2nd harmonic lands at or above 10 kHz; lower starts also feed in the sums of their upper notes (6 + 7 kHz gives 13 kHz). Difference tones below the band are still dropped.
- **Headroom:** less is added than before at every setting, so the old peak table stays a safe bound. Measured on the loud Switchfoot section at 100 %, peak growth per unit went 0.162 to 0.138 (2.5 kHz), 0.097 to 0.063 (3.5), 0.069 to 0.055 (5) and 0.023 to 0.018 (7). The worst-of-39-tracks table (0.62, 0.50, 0.45, 0.25) was not re-measured; it can be tightened when the box is reachable.
- **What to expect:** a subtler, airier effect with no added presence in the 4 to 9 kHz range, which is where a clean guitar or cymbals sit. Car speakers and road noise may hide a lot of content above 10 kHz, so it may need a higher amount than before.

**Launcher 0.1.124 (13 November 2026): Even out quiet parts (OABL Music).**
- **Why:** "Meant to Live" opens 8.4 dB below its body (intro -18.1 dB, body -9.7 dB), and the 1.3-2.5 dB mid cut in the saved curve took the intro to -20.2 dB. Nothing in the effects was masking it (left and right of the intro are independent, correlation 0; the effects pass it at -18.1 dB in and out), so this is the master plus road noise.
- **What it does:** a slow leveler ahead of the exciter and bass. It reads the song's average level (150 ms to rise, 400 ms to fall, both channels together so the stereo image stays put), pulls it toward -13 dBFS, lifting quiet parts by the whole distance and easing loud ones by half of it, never more than 8 dB times the strength, and fading out below -42 dB so fades and gaps aren't lifted into hiss. Peaks still go through the soft limiter.
- **Controls:** a slider on the Effects tab (EVEN OUT · OABL MUSIC) and an EVEN chip on the player that steps Off, Light (50 %), Medium (100 %). Off by default. Like Headroom it is not part of a preset.
- **Measured on that file, 2 s into the intro and 2 s into the body:** Off -18.1 / -9.2 dB; Light -16.6 / -10.7; Medium -15.0 / -12.1. The gap goes from 8.9 dB to 5.9 and 2.9. The whole song gets a little quieter at Medium, so the volume goes up a touch.
- **Not for YouTube and other apps:** it lives in OABL Music's own chain, like Bass and the exciter.

**Launcher 0.1.123 (12 November 2026): the equalizer also plays on YouTube, Chrome and other apps on the box.**
- **The gap:** the equalizer attaches to audio sessions that a player announces through Android's audio-effect contract. OABL Music does, YouTube and browsers don't, so they played flat.
- **The fix, on the box:** `oabl-box-tune` (root) reads the audio service every 2 s while the equalizer is on, takes the playing media sessions of other apps (usage media or game, not voice, calls, navigation or system sounds, not OABL Music) and announces each to the launcher's session receiver as a cooperating player would, then closes it when the player goes. The launcher is unchanged apart from a line in the Audio source dialog.
- **Checked on the box:** YouTube's session got its DynamicsProcessing chain within about 2 s of the first sound, was closed when YouTube was stopped, and a new video got a new session. Playing with it on: audioserver about 10 % of a core, no mixer underruns.
- **Off:** `setprop persist.oabl.eqapps.off 1`. Bass and the exciter still only work in OABL Music.

**Launcher 0.1.113 (7 November 2026): the exciter, checked against how the classics work and cleaned up.**
- **The reference design** is the Aphex Aural Exciter: the dry signal passes untouched, and a side chain (a tunable high-pass, then a harmonic generator) is mixed back in at a low level. Aphex's own manuals describe the added content as low in level yet heard as a clear lift. Later units make the generator respond more to transients. BBE's Sonic Maximizer is a different idea (phase shift plus dynamic treble EQ, no new harmonics), and users report the Aphex type can turn fizzy on bright material. Aliasing from waveshaping is a known problem, normally fixed by oversampling or by band-limiting what reaches the shaper.
- **Two faults found in ours and fixed, each with a test:**
  - **Aliasing:** squaring content above a quarter of the sample rate folded its 2nd harmonic back down as off-pitch fizz. A 15 kHz tone came back at 14.1 kHz at about half the dry level. The shaper's input is now low-passed at 0.2 of the sample rate (4th order), and the fold-back is about 1 %.
  - **Difference tones:** squaring 3 and 4 kHz also gives 1 kHz. About 7 % of the dry level crept into the mids and the 2.5–5 kHz harsh zone. The output now passes a 4th-order high-pass at 1.5× the start frequency, so only new harmonics above the band are added (under 2 %).
- **The level follower** is now a 10 ms average instead of an instant peak hold, which added its own distortion. A transient arrives before the average catches up, so it briefly gets more harmonics (u is held within ±2), as later Aphex units do.
- **Headroom halved:** without those artefacts the exciter grows the worst track's peaks about half as much. The per-unit growth from 2.5, 3.5, 5 and 7 kHz is 0.62, 0.50, 0.45 and 0.25, down from 1.15, 0.87, 0.76 and 0.60. At 30 % from 2.5 kHz the music plays 1.1 dB louder than in 0.1.112.
- **What it really does to the sound, measured on Faint, One Step Closer and Psychosocial at 30 % from 2.5 kHz:** the band energy above 5 kHz rises 0.3–0.5 dB relative to the rest, and the headroom lowered everything by 2.6 dB in 0.1.112 (1.5 dB now). The added harmonics are low in energy by design; what the ear picks up is mostly their edge on transients. Part of the "less harsh at 2.5 kHz" impression from 0.1.112 was probably the 2.6 dB lower level.

**Launcher 0.1.112 (7 November 2026): the exciter is the one effect.**
- **Heard in the car on 0.1.111 and removed:** virtual bass ("bad"), deep bass ("just increases the entire volume") and boom control ("useless"). The exciter was kept.
- **Peak protection was removed too.** At the usual 0 dB master gain it changed nothing, and DynamicsProcessing's limiter reacts to the average level over 21 ms blocks, not to peaks. The engine now runs only the 31-band EQ stage: no multiband compressor and no limiter. A positive master gain can clip loud tracks, as it could with protection off.
- **Effects tab:** Exciter (OABL Music) and Exciter from. **Output tab:** master gain and even loudness. Presets set the exciter: Car 15 % from 5 kHz, Nu Metal 10 % from 5 kHz, Vocals 20 % from 3.5 kHz, Flat, Heavy and Smooth off. Custom presets saved by 0.1.111 keep their exciter values.
- **A settings wipe fixed.** Three processes read `equalizer_profile` (launcher, equalizer service, OABL Music). Android reads a preference file caught halfway through being replaced as empty, and the reader then ran every migration from scratch and saved it. On 7 November that replaced the owner's settings, including a custom preset, with a fresh profile. Now only the launcher's own process migrates, and never on an empty read of a file that isn't really empty.

**Launcher 0.1.111 (7 November 2026): effects instead of sound shaping.** Vocal focus, de-harsh, seat balance, speed-aware lift and the Crystalizer are gone; the owner asked for the useful parts of an old favourite, 4Front's OSS/3D Winamp plugin, with dry and wet levels. Master gain, even loudness and peak protection stay under **Output**. The **Effects** tab has:
- **Virtual bass** (OABL Music only): the sub band below 60, 80, 100 or 120 Hz (mid of both channels) is turned into its 2nd and 3rd harmonics, which door speakers can play, so the ear still hears the low note. **Harmonics** is the wet level (up to 1.5× the sub band); **Deep bass** is the dry level of the original sub band, blended with a high-passed copy below 100 %.
- **Exciter** (OABL Music only): each channel's top end above 2.5, 3.5, 5 or 7 kHz gets new even harmonics (up to 1× that band), replacing the Crystalizer's plain treble lift.
- **Boom control** (every music app): the 120–350 Hz band of DynamicsProcessing's multiband compressor, ratio 1–4 and threshold −14 to −30 dB, 25 ms attack and 250 ms release, so it only holds the zone down while boom builds up.
- **How the harmonics are made** (`SoundEffects`): the band is divided by its own peak envelope (u, within ±1), shaped by u² and u³ − ¾u, and multiplied by the envelope again, so they follow the band's level. Chebyshev T2/T3 were tried first and failed on real music: T3 is mostly −3u when the band sits below its peak, an inverted copy that cancelled the treble. A 4th-order high-pass after the bass harmonics keeps the sub notes themselves out.
- **Headroom:** these masters are limited so hard that any phase change near the bass lifts their peaks; an 80 Hz high-pass alone took "Psychosocial" from +1.1 to +5.6 dBFS. So the input is lowered by 1 / (1 + the growth of each effect added up), with the growth per frequency measured as the worst of the owner's 39 tracks. With that, all five presets stayed within full scale on all 39 tracks, and a soft limiter from 0.9 of full scale catches the rest. Car, for example, plays about 3 dB quieter than with no effects.
- **Presets set the effects too:** Car 35 % bass at 80 Hz, 15 % exciter from 5 kHz, 30 % boom control; Nu Metal 45 %, 10 %, 40 %; Heavy 40 % bass, no exciter (Slipknot masters already run hot up top), 50 % boom; Vocals 15 % at 100 Hz, 20 % from 3.5 kHz, 30 %; Smooth 30 % at 60 Hz, no exciter, 20 %. Not judged by ear. Custom presets save their effects; ones saved before 0.1.111 leave the effects alone.
- **CPU:** a dozen biquads per sample frame, far less than the equalizer's FFT.

**Since format 6 (launcher 0.1.110, 7 November 2026): 31 bands.**
- **Curve:** the 31 ISO third-octave bands from 20 Hz to 20 kHz, still one curve, still ±6 dB in 0.5 dB steps, still one `DynamicsProcessing` stage. The CPU cost is about the same as 14 bands: DynamicsProcessing applies one gain per FFT bin whatever the band count.
- **What the bands can really do.** DynamicsProcessing uses 1024-point FFT blocks at 48 kHz, so its bins are 46.875 Hz apart, and each bin takes the gain of the band whose cutoff range holds it. Set as they are, the 31.5, 40, 50, 80 and 100 Hz bands would own no bin and do nothing, and 63 Hz would act at 94 Hz. So `EqualizerProfile.bandGains` sets each DynamicsProcessing band to the curve's **average over the frequencies its bins cover**. Every fader from 25 Hz up now counts where its label says. Below about 70 Hz, though, the 25, 31.5, 40, 50 and 63 Hz faders share the one bin at 47 Hz: moving one of them alone by 6 dB moves that bin by about 1 dB, and all five together move it fully. From 80 Hz up a single fader moves its bins by 2.4 dB or more per 6 dB. The 20 Hz fader only reaches the DC bin.
- **Presets** were refitted onto the 31 bands so they play as they did on 14: bin by bin within 0.9 dB, 0.25 dB on average, and within 0.62 dB as steady sine waves through a copy of the AOSP code (`scripts/eq-presets/thirty_one.py`). Peak boosts went up by up to 0.5 dB on the faders (Car 4, Nu Metal 4.5, Heavy 4), so the always-on headroom lowers those presets by up to 0.5 dB more.
- **Migration (format 5 → 6):** an untouched built-in preset takes its refitted curve. Anything else, and every 14-band custom preset when it's read, goes through `fromFourteen`: what DynamicsProcessing played for the 14 bands, read at each of the 31 frequencies. That stays within about 0.9 dB.

**Format 5 (October 2026 to 0.1.109), one equalizer for music playback only:**
- **Curve:** 14 fixed bands, two-thirds of an octave apart: 40, 63, 100, 160, 250, 400, 630 Hz; 1, 1.6, 2.5, 4, 6.3, 10, 16 kHz. Range ±6 dB in 0.5 dB steps.
- **One processing chain per music player session.** Each player that announces its audio session gets one `DynamicsProcessing` chain: input gain (master, headroom, seat balance) → EQ (curve + speed lift) → multiband compressor (vocal focus, de-harsh) → limiter. There is no post-EQ stage.
- **Removed:**
  - The speaker layer, which had its own 31-band post-EQ stage.
  - Speaker calibration (source kept in `docs/retired/`).
  - The "All apps" and "Split" sources, which processed the system output mix (session 0).
  - Split ran two FFT chains on every music sample, one on the player and one on the mix, in the same audioserver mixer thread. That doubled the CPU cost and the effect latency.
- **Migration:**
  - The old curve as it played becomes the new curve: the 15- or 10-band curve, plus any 31-band detail, plus the speaker profile when it was on. It is scaled to fit ±6 dB, keeping its shape.
  - The original is saved as the preset "My curve · before 14 bands". Older custom presets saved on 31 bands are read onto the 14.
  - The speaker and old band keys stay in the preferences as a backup.

**Since 0.1.108 (November 2026):**
- **Presets:** Flat, Car, Nu Metal, Heavy, Vocals and Smooth. They were fitted on the owner's Linkin Park and Slipknot files and set the Crystalizer too (all Off).
- **0.1.109:** the boxy 400 Hz–1.3 kHz zone comes down and presence comes up, after vocals and guitars sounded "tubed" in the car. Beyond that first listen, the presets were not judged by ear.
- **Headroom is always taken.** The input is lowered by the largest boost, counting bands from 4 kHz up 2 dB higher, plus 0.5 dB. The built-in limiter works on 21 ms block energy and can't catch peaks.
- **Peak protection** now only stops the master gain going above 0 dB.
- **Crystalizer:** its input is lowered by 1 / (1 + 2k), and it is off while the equalizer is off.

The research, measurements and sources are in [metal-equalizer.md](metal-equalizer.md).

See [metal-equalizer.md](metal-equalizer.md) for the curve UI, presets and persistence behavior. All controls are saved; the original audio files are never edited.

The processing details and verification records below describe earlier versions and are retained as history.

The tuner now has 31 one-third-octave-style frequency controls from 20 Hz to 20 kHz. Swipe horizontally to reach all bands; labels retain 31.5 Hz and 1.25 kHz precision. Existing 15-band profiles and presets are interpolated on a logarithmic frequency axis, with matching frequencies preserved exactly and edge values extended. Enabled state, scope and preamp remain unchanged. New gains use frequency-keyed storage, with a versioned atomic migration; legacy keys remain available for recovery. Boost/cut range, headroom and limiter behavior are unchanged.

## Signal processing

- Android 9+ `DynamicsProcessing` pre-EQ with 31 native bands per channel. Centers: 20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800 Hz; 1, 1.25, 1.6, 2, 2.5, 3.15, 4, 5, 6.3, 8, 10, 12.5, 16, 20 kHz. Native [configuration supports a supplied band count](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing.Config.Builder); actual route support still depends on firmware/player behavior.
- These labels are band centers. DSP cutoff boundaries use the geometric mean of adjacent centers; the last band's upper boundary is 20 kHz. No 5-band interpolation or decorative extra sliders.
- Gains: −12 to +12 dB in 0.5 dB steps. Manual preamp: −12 to 0 dB. Input headroom is the quieter of manual attenuation or negative maximum boost minus 1 dB; a linked-channel limiter at −1 dBFS (20:1, 1 ms attack, 80 ms release) further reduces peaks.
- Presets: Flat, Warm, Clarity, Punch, Relaxed, or Custom after editing a band. Presets are starting points; they are not measurements/calibration of the user's speakers or a surround-sound decoder.
- Edits are debounced at 100 ms; unchanged settings are not reapplied by the health poll. Failed session attachments are not retried repeatedly until settings or a source change.

## Sources and persistence

Default **Compatible players** uses Android's `OPEN_AUDIO_EFFECT_CONTROL_SESSION` and `CLOSE_AUDIO_EFFECT_CONTROL_SESSION` broadcasts. Dynamic registration in the running service also supports modern Android's background-broadcast restrictions. Voice-marked sessions are ignored. Players that do not expose sessions must restart playback after enabling, or remain unsupported by this mode. The engine uses normal effect priority and does not forcibly displace another equalizer.

**System mix · experimental** explicitly attempts session 0. Android deprecates this attachment and some firmware/audio paths reject or bypass it; creating a handle does not prove that every output is processed. The UI states that audible verification is needed and that system mix can also affect guidance/calls. Root is not requested, audio configuration files are not patched, and protected/offloaded/proprietary phone-projection paths are not claimed to work.

The foreground media-playback service owns enabled effects outside the tuner. Disabling releases all effects. Band levels, preset, preamp, scope, and enabled preference are saved in `equalizer_profile`; audio session IDs are kept only in memory and removed on close. An enabled profile resumes when OABL becomes visible after boot, rather than attempting a prohibited media-playback FGS launch directly from BOOT_COMPLETED on Android 15+. Newly installed profiles are Flat and disabled.

No microphone, audio recording, playback capture, network upload, or bundled driver is used. New permissions are normal `MODIFY_AUDIO_SETTINGS` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`; the existing notification permission governs visibility of the service notification.

## Verification

31-band update (2026-10-13): 117 emulator checks passed, including all 31 native gain/cutoff readbacks, 15-to-31 migration and one-time preservation, precise labels, first-fader touch isolation, scrolling to the 20 kHz control, headroom/bypass, service ownership, ignored voice sessions and profile reload. Screenshot: `screenshots/equalizer-31bands-test.png` (test gains and silent audio session, not an audible vehicle verification). Debug build and lint passed. Original profile preferences were restored after the tests. More bands provide finer controls, not a guarantee of better sound or compatibility with every player/output.

Historical 15-band implementation: the native backend was exercised using a silent 48 kHz stereo AudioTrack session. All 62 checks passed. The old `screenshots/equalizer-15bands-test.png` is retained as a historical emulator preview, not the current UI or a car listening test.

Build and run the dedicated instrumentation suite:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -PoablTestRunner=com.softiva.oabl.EqualizerTestRunner
adb -s DEVICE install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE shell am instrument -w com.softiva.oabl.test/com.softiva.oabl.EqualizerTestRunner
```

The Android 13 AI box is currently offline. Installation, actual source compatibility, CarPlay/Android Auto routing, audible A/B checks, and reboot verification are outstanding. The same APK also includes the pending enlarged GPS speed and home navigation-hints feature.

Primary references: [DynamicsProcessing](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing), [EqBand](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing.EqBand), and [AudioEffect session contract](https://developer.android.com/reference/android/media/audiofx/AudioEffect).
