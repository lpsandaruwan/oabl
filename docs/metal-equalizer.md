# Listening EQ presets

## Current: presets of 0.1.108, mids revised in 0.1.109 (7 November 2026)

**These values were not judged by ear.** They come from measurements of the owner's own files, a copy of the playback chain run on those files, and general facts about small-car factory audio. The owner's listening test in the car decides; change or drop any preset that doesn't sound right there.

**On 31 bands since 0.1.110.** The presets were designed on the 14 bands below and refitted onto the 31 third-octave bands so they play the same: bin by bin within 0.9 dB, 0.25 dB on average (see [equalizer.md](equalizer.md) and `scripts/eq-presets/thirty_one.py`). The stored 31-band values, in dB:

```
Hz        20  25 31.5 40  50  63  80 100 125 160 200 250 315 400 500 630 800 1k 1.25k 1.6k 2k 2.5k 3.15k 4k 5k 6.3k 8k 10k 12.5k 16k 20k
Car        0   0   0  .5 1.5  3   4 3.5 1.5  .5  0  -1  -1 -1.5 -1.5 -2 -2 -2  -1.5 -.5  0  .5   1   1.5 1.5  1   1 1.5  1.5  1.5 1.5
Nu Metal  .5  .5  .5   1   2 3.5 4.5  4   1   0 -.5 -1.5 -1.5 -2 -2 -2.5 -2.5 -2 -1.5 -.5  0  .5   1    1   1   1 1.5 1.5  1.5  1.5 1.5
Heavy     -1  -1 -.5   0   1 2.5  4 3.5  .5 -.5  -1  -2  -2 -2.5 -2.5 -2.5 -2.5 -2 -1.5 -1 -1 -.5  -.5   -1  -1  -1 -.5   0    0   .5  .5
Vocals    -1  -1 -.5 -.5 -.5   0  .5  .5   0  .5   0  -1  -1 -1.5 -1.5 -2 -2 -1.5  -1   0  .5 1.5  1.5  1.5   1  .5   0   0    0    0   0
Smooth     0   0  .5   1 1.5   2 2.5 2.5 1.5  .5  .5   0   0  -1  -1  -1  -1  -1   -1 -.5 -.5 -1  -1.5 -1.5 -1.5 -2 -2.5 -3   -3   -3  -3
```

**Since 0.1.111 each preset sets the curve and the effects** (virtual bass, exciter, boom control; see [equalizer.md](equalizer.md)), and the Crystalizer is gone. Until then: the presets are **Flat, Car, Nu Metal, Heavy, Vocals and Smooth**. Each one sets the curve *and* the Crystalizer level, and all six set the Crystalizer to **Off** (reason below). Choosing a preset replaces the curve and the Crystalizer level. On first start, 0.1.108 saves whatever was playing before (curve plus Crystalizer) once as the custom preset **"My sound · before 0.1.108 presets"**. Custom presets now keep their Crystalizer level too; older ones leave it alone. A profile still naming a retired preset (Warm, Clarity, Punch, Relaxed, Metal Tight, Metal Smooth, Metal Vocals Forward) shows as Custom.

### Band gains on the 14 bands (dB, as designed in 0.1.108–0.1.109)

| Preset | 40 | 63 | 100 | 160 | 250 | 400 | 630 | 1k | 1.6k | 2.5k | 4k | 6.3k | 10k | 16k | Headroom |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Flat | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 2.5 |
| Car | +1 | +3.5 | +1 | +0.5 | −1 | −1.5 | −2 | −2 | −0.5 | +0.5 | +1.5 | +1 | +1.5 | +1.5 | 4.0 |
| Nu Metal | +1.5 | +4 | +0.5 | 0 | −1.5 | −2 | −2.5 | −2 | −0.5 | +0.5 | +1 | +1 | +1.5 | +1.5 | 4.5 |
| Heavy | +0.5 | +3.5 | 0 | −0.5 | −2 | −2.5 | −2.5 | −2 | −1 | −0.5 | −1 | −1 | 0 | +0.5 | 4.0 |
| Vocals | −0.5 | +0.5 | 0 | +0.5 | −1 | −1.5 | −2 | −1.5 | 0 | +1.5 | +1.5 | +0.5 | 0 | 0 | 4.0 |
| Smooth | +1 | +2.5 | +1 | +0.5 | 0 | −1 | −1 | −1 | −0.5 | −1 | −1.5 | −2 | −3 | −3 | 3.0 |

**Revised in 0.1.109 after the first listen.** In the car, vocals and electric guitars sounded "tubed", like cheap midrange speakers in a box. Bass and air were good. The 0.1.108 curves had cut 2.5 to 4 kHz to tame harshness and left 630 Hz to 1.6 kHz flat, which leaves a hump in the middle: the honky, talking-through-a-tube sound. Small door speakers heard off-axis also tend to lose the 2 to 5 kHz range (an inference, not measured in this car), which makes that hump stand out more. Vocals and guitars can't be moved up in pitch without changing the notes, and they can't be separated out of a finished mix. What makes them crisp is the balance between their boxy fundamentals and their harmonics, so the boxy zone (400 Hz to 1.3 kHz) now comes down by 1.5 to 2.5 dB, and the presence zone (2.5 to 6.3 kHz) comes up relative to it. The bass and the top are unchanged. The new curves passed the same whole-chain check on all 39 tracks: none went over full scale, and the worst peak was −0.45 dBFS. If a preset is still boxy or turns harsh, the **Find your sound** tool's "Boxiness" (around 700 Hz) and "Bite" (around 3.15 kHz) steps can set those two zones by ear in the car.

- **Car** is the everyday preset. It lifts the kick and bass between 60 and 110 Hz, where small door speakers still play, and takes the boxy mids down 1.5 to 2 dB. Presence goes up 1 to 1.5 dB at 4 to 6.3 kHz, plus a small top lift.
- **Nu Metal** is aimed at Linkin Park: a bit more kick, the drop-D mud and the 630 Hz to 1 kHz honk down. Presence goes up a little less than in Car, because those masters already run +2 dB at 1.6 to 4 kHz.
- **Heavy** is aimed at Slipknot. Those masters carry 2 to 5 dB more than pop at 1.6 to 6.3 kHz, so presence stays slightly down. The mids come down more (−2 to −2.5 dB), so it still sounds less boxy than before.
- **Vocals** takes the boxy zone down and lifts 2.5 to 4 kHz by 1.5 dB, so words cut through at speed.
- **Smooth** is a gentle downward tilt for long drives, with a small mid dip.

On the 14 bands, the labels were where the controls sat, not always where they acted (since 0.1.110 each band is set to the curve's average over the bins it covers, so the 31 labels are right as far as 47 Hz bins allow). DynamicsProcessing runs a 1024-point FFT at 48 kHz, so each bin is 47 Hz wide, and the bass bands land on the nearest bins. Measured with sine waves through a copy of the AOSP code, the band labelled **63 Hz acts at about 94 Hz**, 100 Hz at about 141 Hz, 160 Hz at about 188 Hz, and 250 Hz covers 220 to 300 Hz. From 400 Hz up the labels are right. The presets were fitted to targets in true frequency, so the real response stays within 0.9 dB of each target. For example, Car's kick lift peaks at +3.1 dB around 94 Hz.

### Headroom: why these can't clip loud masters

Measured on the owner's files on the box: 13 Linkin Park FLACs from every studio album, 20 Slipknot tracks (the 2018 "Greatest Songs" MP3s at 320 kbps), and 6 pop, rock and rap tracks for comparison.

| | Linkin Park | Slipknot | Others |
| --- | --- | --- | --- |
| Integrated loudness (median) | −6.7 LUFS (−5.5 to −8.7) | −6.2 LUFS (−4.6 to −10.3) | −9.5 LUFS |
| True peak (highest) | +1.9 dBTP | +2.1 dBTP | +2.1 dBTP |
| Peak to RMS (median) | 10.1 dB | 9.4 dB | 10.2 dB |
| Flat-topped runs at the peak | Meteora 1,600 to 2,600 a minute | up to 2,070 a minute | varies |

These masters sit at full scale. Meteora is clipped: thousands of flat tops a minute. The true peaks go over full scale between samples. Then three facts from the box and the Android 13 source decide what the headroom has to do:

1. **The mixer runs at 48 kHz in float and writes 16-bit PCM to the HAL**, so anything over full scale is hard-clipped there. Resampling 44.1 kHz files to 48 kHz rebuilds the inter-sample peaks: with a **flat curve the output reached +1.9 dB over full scale**.
2. **The media volume is at its maximum (15 of 15)** and DynamicsProcessing applies the volume *after* its EQ (`EFFECT_FLAG_VOLUME_CTRL`, applied as output gain), so the volume gives no room.
3. **The built-in "limiter" can't catch peaks.** In `DPFrequency.cpp` it reacts to the energy of each 1024-sample block (about the RMS of 21 ms) against a −1 dBFS threshold. These masters run about −6 to −9 dB RMS, so it almost never acts.

So the input is now **always lowered** by `EqualizerProfile.headroom`, with or without Peak protection. The amount is the largest boost, counting bands from 4 kHz up 2 dB higher for the inter-sample peaks, plus 0.5 dB of safety, plus any vocal-focus makeup. With every preset, run through a copy of the chain (16-bit decode, Crystalizer, resampling to 48 kHz, the AOSP EQ) on all 39 tracks, **no track went over full scale; the highest peak was −0.45 dBFS** with the 0.1.109 curves (−0.47 dBFS with 0.1.108's). This is a measurement on these files, not a proof; the 0.5 dB safety is there for louder tracks. Upper-mid boosts are the risky kind: the old rule (largest boost only) let Vocals go 1 dB over on 30 of the 39 tracks.

All six presets play 2.5 to 4.1 dB quieter than the equalizer switched off (median over the 39 tracks: Flat −2.5, Smooth −2.6, Car −3.3, Nu Metal −3.7, Heavy −3.9, Vocals −4.1), within about 1.6 dB of each other, so comparing presets is fair. Comparing a preset against Off is not: Off is louder and clips these masters. Raise the car's volume to match before judging.

**Peak protection** now keeps the master gain at or below 0 dB. Switched off, a positive master gain is allowed and will clip loud tracks. Its limiter stays on with it, but as point 3 says, that limiter does little.

### Crystalizer (removed in 0.1.111)

The Crystalizer computes `out = in + (in − previous) × k`. That's a fixed first-order treble lift, not a transient effect. At 44.1 kHz, Light (k = 0.25) adds +0.9 dB at 6.3 kHz, +1.9 dB at 10 kHz and +3.1 dB at 16 kHz. On these masters it raised peaks by up to **3.1 dB at Light, 5.4 dB at Medium and 8.7 dB at Strong**. Strong pushed up to 3.7 % of samples into its soft limiter (knee at 0.9), so on the earlier builds it was bending, that is distorting, those samples.

Its input is now lowered by 1 / (1 + 2k): **−3.5 dB at Light, −6 dB at Medium, −9.5 dB at Strong**. This guarantees the output stays within full scale for any input, and on the measured tracks almost nothing reaches the knee (0.1 samples in a million at most). The level dialog says so.

**Why every preset sets it Off:** the same lift from the curve's 10 and 16 kHz bands costs no extra headroom when the bass band is already the largest boost, while the Crystalizer needs its own 3.5 dB on top. Car with Light plus a matching curve would play about 3 dB quieter than Car as it is, with the same tonal balance. As a differentiator it also emphasises the edges of clipped flat tops. It stays available as a manual choice, and it only runs in OABL Music. It now switches off when the equalizer is off, so "Off" means untouched. It doesn't follow the hold-to-compare button: OABL Music has 0.5 s of audio buffered after it, so a switch would arrive late.

**The owner's own curve** before 0.1.108 was a custom curve with Strong. Under these rules it plays about **12 dB quieter** than the equalizer off: 9.5 dB for the Crystalizer and 4 dB for the curve's +3 dB bass and +1.5 dB top. Its old loudness came partly from soft-limited and clipped peaks. It's saved as "My sound · before 0.1.108 presets".

### The car: what is known and what is inference

- **Specific to the Dongfeng Nammi 01** (spec sheets, not measurements):
  - Trims list 2, 4 or 6 speakers, and the Cross lists 6 or 8 ([EV视界 configuration table](https://www.evlook.com/nami01/peizhi), [PCauto launch report](https://price.pcauto.com.cn/sg30120/config.html), [PCauto trim guide](https://www.pcauto.com.cn/note/849480151637955785.html), [Nammi 01 Cross configuration](https://www.yipai.com.cn/configdetailsnami01new)).
  - Export reviews say the base car has 4 speakers and higher grades 6 ([First Look Review](https://electricvehicleweb.com/dongfeng-box/), [EVKX](https://evkx.net/models/dongfeng/nammi_box/nammi_box_42.3kwh/)).
  - Some tables name Edifier (漫步者) as the speaker brand. No sizes, positions, amplifier power or frequency response were found for this car, and no review describes how it sounds.
- **General inference** (not measured in this car):
  - **Cabin gain.** A small cabin lifts the bass below about 60 to 80 Hz, often quoted at 6 to 12 dB per octave below that point ([diyAudio on cabin gain](https://www.diyaudio.com/community/threads/cabin-gain-in-a-ford-explorer.56719/post-635657), [caraudio.com](https://www.caraudio.com/threads/cabin-gain.505910/page-2)).
  - **Door speakers.** Factory door speakers in an unsealed, flexing door lose bass to rear-wave leakage and add panel resonance ([Fanatic Audio](https://www.fanaticaudio.com/tech-talk-mid-bass-madness-getting-the-most-out-of-your-door-speakers), [Dynamat](https://dynamat.com/blogs/news/does-sound-deadening-improve-car-audio)).
  - What that means here: little point boosting below 50 Hz, where these speakers can't follow and only distort. The bass lift centres on 60 to 110 Hz, with a small cut in the 250 to 400 Hz boom region.
  - **Road noise.** In an EV, road and tyre noise masks the low end most, up to about 300 Hz ([SAE, BEV road noise](https://saemobilus.sae.org/papers/assessment-low-frequency-structure-borne-road-noise-battery-electric-vehicle-prediction-improvement-2026-26-0320), [IEEE Spectrum on Harman road-noise cancelling](https://spectrum.ieee.org/harman-cancels-road-noise)). The existing speed-aware lift handles that while driving.
  - **Listener preference.** Listeners in Harman's research preferred a response with more bass and treble than flat ([Olive, Welti and McMullin, AES 2013](https://aes2.org/publications/elibrary-page/?id=17042)), hence the small bass and top lifts in Car.
  - **The head unit.** Its own processing is unknown and stays in the chain.

### The bands' records: what is known

- **Credits.**
  - *Hybrid Theory* and *Meteora*: produced by Don Gilmore with the band, mixed by Andy Wallace, mastered by Brian "Big Bass" Gardner ([Hybrid Theory](https://en.wikipedia.org/wiki/Hybrid_Theory), [Meteora](https://en.wikipedia.org/wiki/Meteora_(album)), [making of Meteora](https://riffology.co/posts/the-making-of-meteora-by-linkin-park/)). Delson's guitars are mostly in drop D.
  - *Iowa*: produced by Ross Robinson with the band, mixed by Andy Wallace, mastered by George Marino at Sterling Sound ([Discogs](https://www.discogs.com/release/3301203-Slipknot-Iowa)).
- **Published loudness.**
  - Hybrid Theory studio masters: about −7.1 LUFS, DR 6 ([ProStudioMasters](https://www.prostudiomasters.com/album/page/44497)).
  - Iowa 24/96: DR 6 with peaks around −0.2 dB ([DR database](https://dr.loudness-war.info/album/view/77396)). All Hope Is Gone hi-res: DR 5 ([ProStudioMasters](https://www.prostudiomasters.com/album/page/33416)). We Are Not Your Kind: DR 6, tracks DR 4 to 9 ([DR database](https://dr.loudness-war.info/album/view/165729)).
  - Vinyl cuts are more open: Meteora DR 9 ([DR database](https://dr.loudness-war.info/album/view/108980)), .5: The Gray Chapter DR 10 ([DR database](https://dr.loudness-war.info/album/view/118304)). These are different masters from the files on the box.
- **Mixing and mastering guidance.** The song decides, and in dense material small whole-mix changes matter ([iZotope on mixing metal](https://www.izotope.com/community/blog/mixing-metal-sessions), [iZotope on mastering metal](https://www.izotope.com/community/blog/how-to-master-metal)). EQ after a limiter can raise inter-sample peaks ([FabFilter on true-peak limiting](https://www.fabfilter.com/help/pro-l/using/truepeaklimiting)), which the measurements above confirm.
- **The owner's files**, measured against the 6 other tracks:
  - Both bands carry 5 to 8 dB less below 50 Hz.
  - Linkin Park runs about +2 dB at 800 Hz and at 1.6 to 4 kHz.
  - Slipknot runs +2.5 to +4.8 dB at 1.6 to 6.3 kHz and +1 to +2 dB at 200 to 500 Hz.

The reasoning for tuning on these two bands is the owner's: they are among the loudest, densest and harshest masters in the library, so a curve that keeps them clean and not tiring should suit gentler music. Pop and hip-hop carry much more below 50 Hz, which is another reason the presets leave 40 Hz small.

### Reproducing the numbers

`scripts/eq-presets/` holds the copy of the chain (`chain.py`), the per-track measurements (`measure.py`) and the fit plus whole-chain simulation (`design.py`). They need Python with numpy and scipy, plus ffmpeg. Pull a few tracks into a folder, then run `python measure.py <folder> out.json` and `python design.py <folder> out.json`. The tracks themselves stay on the box.

### Tests

- Unit tests: `EqualizerPresetsTest` (preset shape and effects, headroom rule, Peak protection); `SoundEffectsTest` (harmonics from pure tones, no treble cancellation, deep bass, a full-scale square wave). `CrystalizerTest` and `CrystalizerProcessorTest` went with the Crystalizer in 0.1.111.
- Instrumentation runners were updated for the new presets and the headroom, but were not run for this change.

## Before 0.1.108

> **Format 4 (25 October 2026, launcher 0.1.23):** the style curve is **10 fixed octave bands: 30, 60, 125, 250, 500 Hz, 1, 2, 4, 8, 16 kHz** (Depth, Kick, Bass, Body, Mud, Riffs, Vocals, Bite, Cymbals, Air), with a **±6 dB** range in 0.5 dB steps. This was the owner's choice: ±12 was too much. The speaker layer keeps its 31 measured bands at ±12 dB, and the engine still processes 31 third-octave bands with the style curve interpolated between the 10 points.
>
> **Migration (format 4).** It runs once. The curve as it played (15 bands plus any 31-band detail) is saved as the custom preset **"My curve · before 10 bands"**. It's then carried onto the 10 bands; a curve past ±6 dB is scaled down to keep its shape rather than clipped. The old 15-band keys are kept as a backup, and a one-time notice explains the change. The metal presets are defined on the old 15 frequencies and interpolated onto the 10.
>
> **Tests.** The emulator passed EqualizerTestRunner (124 checks), EqualizerDesignTestRunner (566) and MusicTestRunner (50). These were silent sessions, not a car listening test. The tests caught a bug, since fixed: `flush()` still wrote format 3, which would have re-run the migration after every save.
>
> The section below describes the earlier 15-band design and is kept as history.


## Research and design choice

This is playback EQ for an already mixed song, not an instrument-mixing console. [iZotope's EQ guide](https://www.izotope.com/community/blog/eq-cheat-sheet) identifies kick weight around 60–80 Hz, snare/body around 150–250 Hz, muddy/boxy regions roughly 200–500 Hz, guitar aggression/harshness around 2–5 kHz, and cymbal harshness/air in higher bands. [Its metal-mixing guidance](https://www.izotope.com/community/blog/mixing-metal-sessions) stresses that the song dictates the sound; metal is not one fixed bass-heavy response. [Ian Shepherd's metal-mastering discussion](https://www.izotope.com/community/blog/how-to-master-metal) recommends gentle whole-mix adjustments and notes that tiny changes matter in dense material.

The following centers are our design selection covering those regions, not a universally optimal or source-prescribed 15-band standard:

| Zone | Centers | Listening purpose |
| --- | --- | --- |
| Low end | 40, 63, 80, 125, 200 Hz | Depth, kick/bass punch, warmth and body |
| Definition | 315, 500, 800 Hz, 1.25, 2 kHz | Low-mid buildup, riff and vocal definition |
| Bite & air | 3.15, 4, 6.3, 10, 16 kHz | Attack, possible harshness, cymbals and upper detail |

Labels such as Kick and Voice describe common overlapping regions; they do not isolate instruments. Speaker origin is not an acoustic measurement. The factory speakers and cabin need a parked listening check; there is no claim of Dongfeng speaker calibration.

Metal Tight uses at most +1 dB, a modest low-mid reduction and restrained treble. Metal Smooth uses at most +0.5 dB and gentle upper-mid/cymbal cuts. Both are optional starting points. Existing settings are retained instead of automatically selecting a preset.

## Simpler studio UI

The October 15 redesign has **one editable logarithmic curve**, not a graph plus a duplicate bank of faders. All fifteen band points fit the 1920 × 970 / density-238 tablet. Frequency labels share one baseline; narrow panes scroll horizontally rather than staggering the labels. The selected band shows its frequency, listening role and gain, with 0.5 dB minus/plus controls and a frequency picker for precision. Dragging a point uses the selected independent/linked mode; dragging empty space horizontally pans the graph in a narrow pane without changing gains. The curve illustrates saved band gains, not a measured acoustic response.

Presets, band linking and source scope use consistent two-line selectors. Output controls sit in separate cards beneath the editor; they stack on narrow displays. Text is measured/ellipsized inside its space, switches do not share labels' bounds, and controls have at least 48dp touch targets. Reset asks for confirmation and only resets band gains, keeping output options unchanged. Dark/light mode and the metallic accent are inherited from the launcher. The selected frequency and scroll positions survive activity recreation.

Native Android controls and a small gain-curve Canvas are used. Paint/Path and gradient shaders are reused; gains and point geometry are cached outside drawing. There is no live audio spectrum, animated background, per-panel blur or continuous rendering loop. DSP edits remain debounced; persistence flushes at drag completion, preset/toggle changes and when leaving the tuner. Keyboard and accessibility gain adjustment are supported. No measured CPU percentage improvement is claimed.

## User-requested processing and persistence

The native engine still uses 15 pre-EQ bands. Later user-requested controls add master gain (−18 to +12 dB), optional peak protection (EQ headroom plus a linked-channel limiter), and Off/Track/Album ReplayGain for the built-in local player. Untagged files keep their original ReplayGain level; other apps/Bluetooth input are not normalized by this option. New profiles use 0 dB master gain with protection and normalization off. Large boosts can distort loud material. Peak protection cannot repair pre-existing distortion or guarantee protection from speaker/amplifier overload; tune while parked. A third-party player's own processing is outside this launcher's control.

Frequency-keyed saved gains from the 31-band format retain the exact selected center values. Removed-frequency keys remain as a backup, but a fifteen-band curve cannot reproduce every bend of a thirty-one-band curve. Legacy index-based 15-band settings are log-frequency interpolated onto the new centers. Enabled state, preset and audio scope persist; old preamp preferences are retained as historical data but are no longer applied.

The live DSP still depends on participating player sessions or the explicitly selected experimental system-mix route. Saved enabled state does not guarantee that an unsupported projection/offload source is processed. The same saved profile is loaded when the launcher/tuner next starts; session IDs are never persisted.

## Verification

2026-10-15: the updated native suite passed 78 checks, including all fifteen native gain/boundary readbacks, optional limiter/input-gain readback, migration, durable settings, single-curve touch isolation and 16 kHz point reachability. Native audio verification uses a silent emulator session, not an audible car test. The audio engine was not changed by the visual redesign.

The dedicated `EqualizerDesignTestRunner` passed 265 checks: exactly one curve and one master-gain slider, measured/non-overlapping 48dp controls, menus and reset confirmation, actual linked curve dragging, master-gain touch, output setting persistence, recreation, and dark/light wide/narrow previews. Horizontal exploration of the narrow graph leaves gains unchanged. The temporary 960 × 970 emulator viewport and original preferences are restored before the suite exits. Narrow-view testing checks responsive layout, not the physical box's native splitter.

Previews: [dark](screenshots/equalizer-ui-dark.png), [light](screenshots/equalizer-ui-light.png), [narrow dark](screenshots/equalizer-ui-narrow-dark.png), [narrow output controls](screenshots/equalizer-ui-narrow-controls.png). These show temporary test settings, not a recommended speaker calibration. The APK is installed on the emulator; the physical AI box remains offline.

```sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug -PoablTestRunner=com.softiva.oabl.EqualizerDesignTestRunner
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r com.softiva.oabl.test/com.softiva.oabl.EqualizerDesignTestRunner
```

Historical: the October 13 fader-based UI passed 74 simulator checks. Its older screenshots are retained as history. Actual car playback and speaker response remain unverified.
