# Music player — October 15, 2026

The player is native Android UI over AndroidX Media3 1.11.1 (ExoPlayer), which replaced libVLC 3.7.7 on 18 October 2026 (see [Playback engine](#playback-engine)). JamesDSP was evaluated but is not integrated. Existing equalizer processing is separate from this visual redesign.

## Design direction

Apple Music's documented language and interaction structure informed the redesign, not its branding or assets:

- **Library / Songs / Favorite Songs / Playlists / Playing Next** replace the earlier technical labels and promotional heading.
- Favorite is a separate star button at the far right of every song row and beside the title in Now Playing. Tapping it does not select the song or interrupt playback.
- Song menus expose **Add to Playlist**, **Favorite**, and **Song Info**. New playlists can be created from the library or while adding a playing song.
- Full-width displays use a song library beside an artwork-led Now Playing panel. Narrow panes keep the library visible above a MiniPlayer; tapping that bar expands Now Playing. Back returns to the library without stopping playback.
- The redundant Maps + music action is removed. The launcher's existing split-pair function remains the way to arrange apps.
- Icons share a small native Canvas drawing system. Controls have at least 48dp touch targets; text truncates inside measured space instead of pushing into neighboring controls.
- Theme, metallic accent and the launcher's fixed IST day/night schedule are inherited. Search and progress tracks have explicit light-theme contrast.
- Artwork decoding is off the UI thread, bounded by an 8MiB cache, with cached missing-art results. No spectrum visualizer, animated wallpaper or per-frame blur is used.

## Artist and folder browsing

The Library now exposes **Songs / Artists / Folders / Favorite Songs / Playlists / Playing Next**. Artist browsing is separate from sorting the song list:

- **Artists** groups embedded artist tags already read during indexing, with song/album counts and representative local artwork. Tapping an artist opens only that artist's songs. Case variants share one group; missing tags remain **Unknown artist**. No filename guessing or online identification is performed.
- **Folders** lists indexed audio-containing directories with their relative paths and selected storage root. Identically named folders on separate roots remain separate; selecting a folder matches its exact path, not similarly named descendants.
- Inside an artist or folder, **Play / Shuffle**, search, stars, and song/playlist menus operate on the scoped songs. **Sort** retains Artist, Folder, Title, Filename, Random, and Most played. The Playing Next view preserves queue order.
- Back returns from songs to the parent artist/folder browser and restores its search. Switching Library sections clears the old section's search. Artist/folder selection survives activity recreation, including theme changes.
- Existing metadata is reused immediately; no rescan is necessary to enable these browsers. A rescan refreshes relative paths when the same files are selected through a different subtree without duplicating tracks or discarding stars/playlists.

Artist artwork is representative album/file artwork, not an online artist portrait. Folder browsing is a flat directory catalogue of indexed music, not a general-purpose file manager.

Sources consulted:

- [Apple: favorites and Favorite Songs](https://support.apple.com/en-au/111118)
- [Apple: Playing Next and queue actions](https://support.apple.com/en-il/109336)
- [Apple: creating and adding to playlists](https://support.apple.com/en-au/118289)

These are UX references; OABL does not provide Apple Music streaming, recommendations, AutoPlay, or account integration.

## Sample desktop wallpaper

Launcher settings → **Sample wallpaper** selects the bundled day/night road landscapes while retaining the custom wallpaper URI. Selecting a custom image turns sample mode off. The existing soft-blur treatment also applies to the sample.

If a custom image becomes unavailable, wallpaper loading falls back to the bundled landscape rather than leaving an empty background. Custom files are not deleted.

## Playback and access

Folder selection uses Android's read-only Storage Access Framework with a persistent grant. Metadata, stars, playlists, play counts, and queue state are stored privately; original music files are never edited. Indexing has a visible stoppable notification and can run while Maps is in front. Playback owns a Media3 session, handles audio focus, and continues independently of the library activity and of the launcher's own process.

## Playback engine

libVLC 3.7.7 was replaced because, on the AI box, it went silent when OABL's equalizer already sat on its audio session (its output adds its own DynamicsProcessing and fails), dropped or padded audio when the box's timestamps jittered, and played from a ~120 ms AudioTrack with ~300 ms of read-ahead, too little to cover the memory card's ~100 ms stalls.

```
launcher process: MainActivity, MusicActivity ── MusicClient (Media3 MediaController)
                                                     │ Media3 session (IPC)
":music" process: MusicService (MediaSessionService) ── ExoPlayer ×1–2 ── AudioTrack 0.5–0.75 s
                     playback thread "OablPlayback": urgent-audio priority, pinned to the fast cores
```

- **Own process.** `MusicService` runs in `com.softiva.oabl:music`, so launcher layout, artwork decoding and garbage collection can't delay the playback thread, and the launcher can be killed without stopping music. The launcher talks to it only through `MusicClient`. The queue, current song, position and repeat mode are saved by the music process alone (`music_session` preferences) and restored when it restarts; the settings it reads (crossfade, art lookup, normalization) are re-read across processes.
- **Formats.** Decoding uses the box's platform codecs: FLAC (16/24-bit), MP3, AAC (`.m4a`/`.aac`), Ogg Vorbis, Opus, WAV and Matroska audio. APE, AIFF, ALAC, WMA, WavPack, MP2 and DSD are no longer indexed, and the database upgrade removes them and their playlist entries. A file that still fails (for example an ALAC `.m4a`) shows "Can't play this file" and is skipped; a queue where nothing plays stops instead of looping.
- **Buffers.** ExoPlayer reads up to ~50 s ahead on its loader thread; the AudioTrack holds 0.5–0.75 s of PCM.
- **Fast cores.** The playback thread starts at `THREAD_PRIORITY_URGENT_AUDIO` and pins itself (JNI `sched_setaffinity`, `liboablaudio`) to the CPUs with the highest `cpu_capacity` (cores 4–7 on the Bengal box). Android resets affinity when it moves the process between cgroups, so it re-pins on every play and every 30 s while playing. Moving other apps to the efficient cores stays with the root scripts (`scripts/box-tune.sh`).
- **Gapless and crossfade.** Songs from the same album follow each other gaplessly in one player. Otherwise a second player fades in over the crossfade time while the first fades out, and the session moves to it; skips and pause/resume use short fades. Each player has its own audio session, handed to the equalizer service; ExoPlayer adds no effects of its own.
- **ReplayGain.** An audio processor applies track or album gain from Vorbis comments, ID3 `TXXX` or MP4 tags, with peak protection, following the equalizer's normalization setting.
- **Lighter Home while music plays.** The dock badge and the Now Playing glyph are drawn once per state change instead of animating, because on these boxes every Home frame is captured and encoded for CarPlay.

Media3 is Apache-2.0; its notice and source link are under Music Folders → Decoder & open-source licenses. Individual corrupt files, removable-card behavior, firmware audio routing and audible sound quality still require real-device testing.

## Verification

- MusicTestRunner: 48 checks passed with libVLC, including real WAV, APE, FLAC, MP3 and M4A playback, seeking, queue resume, retained playlist membership and stars across rescans. It now drives the `:music` process through `MusicClient`, checks that APE and WMA are dropped, restarts the music process to test queue restore, and plays FLAC, MP3 and M4A; it has not yet been rerun on Media3.
- MusicDesignTestRunner: 254 checks passed, including native control bounds, non-overlapping sibling controls, long titles, independent/synchronized favorite taps, and wide versus half-width layouts in both themes. Screenshots use test audio and test artwork, not a claim of a car listening test.
- MusicBrowseTestRunner: 59 checks passed for indexed tags, case-insensitive artist grouping, literal searches, exact artist/folder filters, separate storage roots, scoped playback queues, system Back, search restoration, activity recreation, cached subtree rescans, and wide/light/narrow browser previews. Temporary test metadata and display changes are restored before instrumentation exits.
- The half-width preview uses a temporary emulator-only 960×970 viewport at density 238; tests restore the native 1920×970 display. It tests responsive layout, not the physical box's firmware splitter.
- No physical AI-box installation was possible while its ADB connection was offline.
- Emulator-only Android fullscreen education was acknowledged and the floating accessibility shortcut was hidden for clean previews; the accessibility service remains enabled. No matching physical-device settings were changed.

Run the design tests on the seeded emulator with:

```sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug -PoablTestRunner=com.softiva.oabl.MusicDesignTestRunner
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r com.softiva.oabl.test/com.softiva.oabl.MusicDesignTestRunner
```

For artist/folder regression, replace `MusicDesignTestRunner` with `MusicBrowseTestRunner` in the build and instrumentation commands. It expects the audio fixtures seeded by `MusicTestRunner`.

Artist-browser previews: [dark](screenshots/music-artists-dark.png), [light](screenshots/music-artists-light.png), [narrow light](screenshots/music-artists-half-light.png).

## Opening on the playing list (launcher 0.1.112)

The music screen used to open on Songs every time. Now it shows the playing queue in playing order, scrolled to the playing song, in two cases:
- when it opens;
- when it comes back after a new queue started (from the screen, from Home's "Play a playlist" or anywhere else).

It's the same list as Playing Next. The title is the list the queue came from, such as a playlist's name, Favourites or an artist. Playing from the screen or from Home records that name (`MusicClient.rememberSource`, in the launcher's own `music_view` preferences), and it's used only while the queue still has that many songs. Otherwise the title is "Now Playing". Each queue is followed once, so browsing elsewhere afterwards isn't overridden.

A first version restored the source list itself, but only for queues started after the update, so it did nothing for the queue that was already playing.
