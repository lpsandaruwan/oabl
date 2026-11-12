# Controlled PCM diagnostic — 2026-10-16 (Asia/Colombo)

Physical Bengal box at `<device-ip>`. User authorized a temporary internal-storage WAV test with EQ/spectrum bypassed and subsequent restoration.

- Generated a quiet, continuous 75-second WAV: 48 kHz, stereo, 16-bit PCM, approximately −30.5 dBFS maximum peak, smooth start/end fades. Placed it under a unique `/data/local/tmp/oabl-audio-ab-ofUgTv` directory, not on the memory card.
- Paused OABL using its existing service action and enabled its **transient** EQ bypass. No saved curves, speaker corrections, normalisation or spectrum preferences were edited.
- AudioFlinger confirmed all five OABL Dynamics Processing effects disabled and the Visualizer released during the valid test.
- An initial platform MediaPlayer probe exited with signal 9/status 137 before producing a READY record. **It is not a valid playback test and is excluded from conclusions.**
- The valid probe read the uncompressed WAV into RAM and streamed its PCM through a new Android AudioTrack for **20 seconds**. The buffer was 48,000 frames (one second), prefilled with 192,000 bytes; this is larger than OABL's observed 5,772-frame buffer (~120 ms).
- The active diagnostic track was AudioFlinger track 66, client PID 29297, session 10601. It used the **same 48 kHz AudioOut_15 deep-buffer output** as OABL. A live dump showed it advancing with nonzero queued frames.
- Native client result: `RESULT track clientUnderruns=0`, followed by `PROBE complete mode=track`.
- Hardware-output FIFO underrun counter remained **2497 → 2497**. No new reported underruns during the valid run. Previous music observations had shown that counter increasing repeatedly.
- The probe released its AudioTrack after the 20 seconds, but app_process retained framework threads after main returned. Its identity-verified PID was terminated afterward to let the surrounding shell finish. No application/system audio process was killed. The reusable probe source now explicitly exits after successful cleanup.

## Interpretation

This proves a continuously fed, generously buffered PCM source can traverse the shared Android output path without **reported buffer starvation** during this short test. It does not prove the car speakers were gap-free: no external recording or user listening confirmation was captured. It also does not isolate storage vs LibVLC feeding/buffering vs effects because these changed together. The larger test buffer is an important confound, not proof of a missing codec or a definitive CarPlay-driver diagnosis.

## Restoration

The surrounding shell restored transient EQ bypass to false and resumed OABL. Live effect-state dumps confirmed all saved EQ effects enabled again and the Visualizer reattached. OABL's paused source had no active AudioTrack even before the test; its music service was subsequently stopped/started (not force-stopped, uninstalled or data-cleared) to reopen normal playback without replacing its queue.

Before/after hashes were identical:

- `equalizer_profile.xml`: `815c2560c0a5012961791c635916429baf6acc3c426a3ed1c2629078a7e0172d`
- `launcher_preferences.xml`: `44b76de3df5e4a6aadd25ed450dd61afeaa5f16f187bc368cc0c5b36a31fa851`

Only the generated WAV and probe JAR were removed from the box after the test. Diagnostic text logs remain in the unique temporary directory. Local backups/logs: `/tmp/oabl-audio-ab.ofUgTv`. No governor, affinity policy, CarPlay FPS, system volume, thermal setting, app installation, library contents or playlists were changed by this test.
