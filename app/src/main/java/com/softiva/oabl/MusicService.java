package com.softiva.oabl;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionError;
import androidx.media3.session.SessionResult;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.json.JSONArray;

/**
 * Local music playback in its own ":music" process: Media3 ExoPlayer behind a Media3 session.
 * The launcher UI controls it through {@link MusicClient}; cars, notifications and media buttons
 * through the same session, so every path gets OABL's fades and queue.
 *
 * Why this layout (AI box findings, 2026-10-17): the launcher's UI work can no longer stall audio;
 * ExoPlayer reads ~50 s ahead so memory-card pauses don't matter; its AudioTrack holds 0.5–0.75 s
 * instead of libVLC's 120 ms; the playback thread runs at audio priority on the performance cores;
 * and ExoPlayer adds no effects of its own, so OABL's equalizer can't collide with it.
 */
@OptIn(markerClass = UnstableApi.class)
public final class MusicService extends MediaSessionService {
    private static final String TAG = "OablMusic";
    /** Crossfade settings, written by the UI in the "music" preferences. */
    static final String CROSSFADE = "crossfade_seconds", CROSSFADE_ALBUMS = "crossfade_albums",
            CROSSFADE_SKIPS = "crossfade_skips", FADE_PAUSE = "fade_pause";
    static final int CROSSFADE_DEFAULT = 6;
    /** Commands only OABL's own UI may send. */
    static final String COMMAND_QUEUE = "oabl.queue", COMMAND_SHUFFLE = "oabl.shuffle",
            COMMAND_REFRESH = "oabl.refresh", COMMAND_REPEAT = "oabl.repeat";
    static final String ARG_IDS = "ids", ARG_CURRENT = "current", ARG_REPEAT = "repeat";
    /** Session extras the UI reads. */
    static final String EXTRA_AUDIO_SESSION = "audioSession", EXTRA_ERROR = "error", EXTRA_UP_NEXT = "upNext",
            EXTRA_CURRENT = "current", EXTRA_REPEAT = "repeat", EXTRA_TOKEN = "platformToken";
    /** Queue, current song, position and repeat: written only by this process, read by the UI. */
    static final String STATE = "music_session";
    private static final int FADE_IN_MS = 400, FADE_OUT_MS = 250;
    private static final AudioAttributes MUSIC = new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private HandlerThread playbackThread;
    private Handler playbackHandler;
    private AudioManager audio;
    private MediaSession session;
    /** The deck being heard; during a crossfade the previous song keeps playing on outgoing. */
    private Deck active, outgoing;
    private final ArrayList<Long> queue = new ArrayList<>();
    private int index = -1;
    private MusicLibrary.Track current;
    /** The following song already queued on the active deck, so ExoPlayer moves on without a gap. */
    private long queuedNextId = -1;
    private String error;
    /** Songs that failed in a row; reset once one plays. */
    private int failures;
    private final PlaybackStallWatch stallWatch = new PlaybackStallWatch();
    private boolean counted;
    private long heardMs, tickAt, lastSave, lastPin, pinnedMask = Long.MIN_VALUE;
    private long lastThermalAt;
    private Bundle publishedExtras;

    /** One volume move with an equal-power shape, so a crossfade keeps a steady loudness. */
    private static final class Ramp {
        float from, to; long start; int ms; boolean active; Runnable done;
        void begin(float from, float to, int ms, Runnable done) {
            this.from = from; this.to = to; this.ms = Math.max(1, ms); this.done = done;
            start = SystemClock.elapsedRealtime(); active = true;
        }
        float value(long now) {
            float t = Math.min(1f, (now - start) / (float) ms);
            double shape = to >= from ? Math.sin(t * Math.PI / 2) : 1 - Math.cos(t * Math.PI / 2);
            return (float) (from + (to - from) * shape);
        }
        boolean finished(long now) { return now - start >= ms; }
    }

    private static boolean debugLogging() {
        try { return "1".equals(Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, "debug.oabl.musiclog")); }
        catch (Exception unavailable) { return false; }
    }

    /** One ExoPlayer with its own audio session (for the equalizer), ReplayGain and volume ramp. */
    private final class Deck implements Player.Listener {
        final ReplayGainProcessor gain = new ReplayGainProcessor();
        final SoundEffectsProcessor effects = new SoundEffectsProcessor();
        final Ramp ramp = new Ramp();
        final ExoPlayer player;
        final int audioSession;
        float volume = 1f;
        boolean equalizer;
        Tracks tracks = Tracks.EMPTY;

        Deck() {
            DefaultRenderersFactory renderers = new DefaultRenderersFactory(MusicService.this) {
                @Override protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams) {
                    // 0.5–0.75 s in the AudioTrack: enough to ride out a late playback thread on the box.
                    return new DefaultAudioSink.Builder(context)
                            .setAudioProcessors(new AudioProcessor[]{gain, effects})
                            .setAudioTrackBufferSizeProvider(new DefaultAudioTrackBufferSizeProvider.Builder()
                                    .setMinPcmBufferDurationUs(500_000).setMaxPcmBufferDurationUs(750_000).build())
                            .build();
                }
            };
            player = new ExoPlayer.Builder(MusicService.this, renderers)
                    .setPlaybackLooper(playbackThread.getLooper())
                    .setAudioAttributes(MUSIC, true)
                    .setHandleAudioBecomingNoisy(true)
                    .setWakeMode(C.WAKE_MODE_LOCAL)
                    .build();
            EqualizerProfile profile = new EqualizerProfile(MusicService.this);
            effects.setSettings(profile.activeEffects());
            effects.setOriginal(profile.comparing());
            audioSession = audio.generateAudioSessionId();
            player.setAudioSessionId(audioSession);
            player.addListener(this);
            // Diagnostics, off by default: `setprop debug.oabl.musiclog 1` before the song starts logs every player event.
            if (debugLogging()) player.addAnalyticsListener(new androidx.media3.exoplayer.util.EventLogger("OablPlayer"));
        }
        void setVolume(float value) { volume = value; player.setVolume(value); }
        void release() {
            ramp.active = false;
            player.removeListener(this);
            player.release();
            if (equalizer) SoundEqualizerService.forgetSession(MusicService.this, audioSession);
        }

        @Override public void onIsPlayingChanged(boolean isPlaying) {
            if (this != active) return;
            if (isPlaying) {
                failures = 0;
                tickAt = SystemClock.elapsedRealtime();
                if (!equalizer) {
                    equalizer = true;
                    SoundEqualizerService.rememberSession(MusicService.this, audioSession, getPackageName());
                    SoundEqualizerService.start(MusicService.this);
                }
                playbackHandler.post(MusicService.this::pinPlaybackThread);
            } else {
                savePosition();
            }
            publish();
        }
        @Override public void onPlaybackStateChanged(int state) {
            if (this == active && state == Player.STATE_ENDED) main.post(() -> step(1, true));
        }
        @Override public void onMediaItemTransition(MediaItem item, int reason) {
            if (this == active && item != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) movedOn(item);
        }
        @Override public void onTracksChanged(Tracks value) {
            tracks = value;
            gain.setGain(ReplayGainProcessor.gainFor(audioFormat(value), normalizationMode()));
        }
        @Override public void onPlayerError(PlaybackException failure) {
            if (this == active) failed(failure);
        }
    }

    /** Routes transport controls from cars, notifications and the UI through OABL's fades and queue. */
    private final class SessionPlayer extends ForwardingPlayer {
        SessionPlayer(Player player) { super(player); }
        @Override public void play() { resume(); }
        @Override public void pause() { MusicService.this.pause(true); }
        @Override public void setPlayWhenReady(boolean play) { if (play) resume(); else MusicService.this.pause(true); }
        @Override public void seekToNext() { step(1, false); }
        @Override public void seekToNextMediaItem() { step(1, false); }
        @Override public void seekToPrevious() { step(-1, false); }
        @Override public void seekToPreviousMediaItem() { step(-1, false); }
        @Override public void seekTo(long position) { seek(position); }
        @Override public boolean hasNextMediaItem() { return !queue.isEmpty(); }
        @Override public boolean hasPreviousMediaItem() { return !queue.isEmpty(); }
        @Override public Player.Commands getAvailableCommands() {
            return super.getAvailableCommands().buildUpon().addAll(COMMAND_PLAY_PAUSE, COMMAND_SEEK_TO_NEXT,
                    COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build();
        }
        @Override public boolean isCommandAvailable(int command) { return getAvailableCommands().contains(command); }
    }

    private final class Callback implements MediaSession.Callback {
        @Override public MediaSession.ConnectionResult onConnect(MediaSession session, MediaSession.ControllerInfo controller) {
            if (!getPackageName().equals(controller.getPackageName())) return MediaSession.Callback.super.onConnect(session, controller);
            SessionCommands commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(new SessionCommand(COMMAND_QUEUE, Bundle.EMPTY)).add(new SessionCommand(COMMAND_SHUFFLE, Bundle.EMPTY))
                    .add(new SessionCommand(COMMAND_REFRESH, Bundle.EMPTY)).add(new SessionCommand(COMMAND_REPEAT, Bundle.EMPTY)).build();
            return new MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build();
        }
        @Override public ListenableFuture<SessionResult> onCustomCommand(MediaSession session, MediaSession.ControllerInfo controller,
                                                                         SessionCommand command, Bundle args) {
            switch (command.customAction) {
                case COMMAND_QUEUE: setQueue(args.getLongArray(ARG_IDS), args.getLong(ARG_CURRENT, -1)); break;
                case COMMAND_SHUFFLE: shuffle(); break;
                case COMMAND_REFRESH: refreshGain(); break;
                case COMMAND_REPEAT: setRepeat(args.getString(ARG_REPEAT, "Off")); break;
                default: return Futures.immediateFuture(new SessionResult(SessionError.ERROR_NOT_SUPPORTED));
            }
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
        }
        /** A media button or the car asks to play after this process was restarted. */
        @Override public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onPlaybackResumption(
                MediaSession session, MediaSession.ControllerInfo controller) {
            if (current == null) return Futures.immediateFailedFuture(new IllegalStateException("Nothing to resume"));
            return Futures.immediateFuture(new MediaSession.MediaItemsWithStartPosition(
                    Collections.singletonList(item(current)), 0, state().getLong("position", 0)));
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        audio = getSystemService(AudioManager.class);
        playbackThread = new HandlerThread("OablPlayback", Process.THREAD_PRIORITY_URGENT_AUDIO);
        playbackThread.start();
        playbackHandler = new Handler(playbackThread.getLooper());
        playbackHandler.post(this::pinPlaybackThread);
        // A compare left on by a crashed equalizer screen would keep the music on the original.
        new EqualizerProfile(this).setComparing(false);
        active = new Deck();
        DefaultMediaNotificationProvider notifications = new DefaultMediaNotificationProvider.Builder(this).build();
        notifications.setSmallIcon(R.drawable.ic_stat_drive);
        setMediaNotificationProvider(notifications);
        session = new MediaSession.Builder(this, new SessionPlayer(active.player))
                .setCallback(new Callback())
                .setSessionActivity(PendingIntent.getActivity(this, 90, new Intent(this, MusicActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build();
        // Sessions from an earlier music process (killed, updated, box switched off) are dead.
        SoundEqualizerService.forgetSessionsFrom(this, getPackageName());
        restoreState();
        if (current != null) {
            // Show the last song (Home card, notification-less session, car) before Play. The item
            // isn't prepared, so nothing is read until resume() loads it from this position.
            active.player.setMediaItem(item(current), state().getLong("position", 0));
            loadArtwork(current);
        }
        publish();
        main.post(tick);
        main.post(watch);
    }

    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controller) { return session; }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        savePosition();
        session.release();
        releaseOutgoing();
        active.release();
        playbackThread.quitSafely();
        super.onDestroy();
    }

    // ---- Settings and saved state ---------------------------------------------------------------

    /** Owned by the UI process; re-read from disk because it changes there. */
    private SharedPreferences settings() { return getSharedPreferences("music", Context.MODE_MULTI_PROCESS); }
    private SharedPreferences state() { return getSharedPreferences(STATE, Context.MODE_MULTI_PROCESS); }
    private String normalizationMode() { return new EqualizerProfile(this).normalization(); }
    private String repeat() { return state().getString("repeat", "Off"); }
    private int crossfadeMs() { return Math.max(0, Math.min(12, settings().getInt(CROSSFADE, CROSSFADE_DEFAULT))) * 1000; }

    private void restoreState() {
        SharedPreferences state = state();
        if (!state.contains("current")) {
            // First start after the move to this process: take over the old shared values once.
            SharedPreferences old = settings();
            state.edit().putString("queue", old.getString("queue", "[]")).putLong("current", old.getLong("current", -1))
                    .putLong("position", old.getLong("position", 0)).putString("repeat", old.getString("repeat", "Off")).commit();
        }
        queue.clear();
        try {
            JSONArray ids = new JSONArray(state.getString("queue", "[]"));
            for (int i = 0; i < ids.length(); i++) queue.add(ids.getLong(i));
        } catch (Exception broken) { queue.clear(); }
        long id = state.getLong("current", -1);
        current = id < 0 ? null : MusicLibrary.get(this).track(id);
        index = current == null ? -1 : queue.indexOf(id);
    }
    private void saveState() {
        JSONArray ids = new JSONArray();
        for (long id : queue) ids.put(id);
        state().edit().putString("queue", ids.toString()).putLong("current", current == null ? -1 : current.id)
                .putLong("position", positionMs()).apply();
    }
    private void savePosition() {
        if (current != null) state().edit().putLong("current", current.id).putLong("position", positionMs()).apply();
    }

    // ---- Playback ------------------------------------------------------------------------------

    private static MediaItem item(MusicLibrary.Track track) {
        Bundle extras = new Bundle();
        extras.putLong("id", track.id);
        MediaMetadata.Builder meta = new MediaMetadata.Builder().setTitle(track.title).setArtist(track.byline())
                .setAlbumTitle(track.album).setExtras(extras);
        if (track.duration > 0) meta.setDurationMs(track.duration);
        return new MediaItem.Builder().setMediaId(Long.toString(track.id)).setUri(Uri.parse(track.uri))
                .setMediaMetadata(meta.build()).build();
    }
    private static long idOf(MediaItem item) {
        try { return Long.parseLong(item.mediaId); } catch (NumberFormatException none) { return -1; }
    }
    private static Format audioFormat(Tracks tracks) {
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) if (group.isTrackSelected(i)) return group.getTrackFormat(i);
        }
        return null;
    }
    private long positionMs() { return Math.max(0, active.player.getCurrentPosition()); }
    private long durationMs() {
        long length = active.player.getDuration();
        return length != C.TIME_UNSET && length > 0 ? length : current == null ? 0 : current.duration;
    }

    /**
     * Starts a song. With {@code fadeMs} > 0 while music plays, the song playing now keeps going on
     * the outgoing deck and fades out while the new one fades in.
     */
    private void load(long id, long position, int fadeMs) {
        MusicLibrary.Track track = MusicLibrary.get(this).track(id);
        if (track == null) { error = "Choose a song from your library"; publish(); return; }
        int at = queue.indexOf(id);
        if (at < 0) { queue.clear(); queue.add(id); at = 0; }
        index = at; current = track; error = null; counted = false; heardMs = 0; queuedNextId = -1;
        if (fadeMs > 0 && active.player.isPlaying()) {
            releaseOutgoing();
            outgoing = active;
            // The new deck takes audio focus; the old one must not pause when it loses it.
            outgoing.player.setAudioAttributes(MUSIC, false);
            ExoPlayer old = outgoing.player;
            int playing = old.getCurrentMediaItemIndex();
            if (old.getMediaItemCount() > playing + 1) old.removeMediaItems(playing + 1, old.getMediaItemCount());
            active = new Deck();
            active.setVolume(0f);
            fadeTo(outgoing, 0f, fadeMs, this::releaseOutgoing);
            fadeTo(active, 1f, fadeMs, null);
            session.setPlayer(new SessionPlayer(active.player));
        } else {
            releaseOutgoing();
            active.ramp.active = false;
            active.setVolume(1f);
        }
        ExoPlayer player = active.player;
        player.setMediaItem(item(track), Math.max(0, position));
        player.prepare();
        player.play();
        saveState();
        loadArtwork(track);
        queueNext();
        publish();
    }

    /** ExoPlayer moved to the queued song on its own (gapless). */
    private void movedOn(MediaItem item) {
        MusicLibrary.Track track = MusicLibrary.get(this).track(idOf(item));
        if (track == null) return;
        int at = queue.indexOf(track.id);
        if (at >= 0) index = at;
        current = track; error = null; counted = false; heardMs = 0; queuedNextId = -1;
        ExoPlayer player = active.player;
        int playing = player.getCurrentMediaItemIndex();
        if (playing > 0) player.removeMediaItems(0, playing);
        saveState();
        loadArtwork(track);
        queueNext();
        publish();
    }

    void resume() {
        if (current == null) {
            long id = state().getLong("current", -1);
            if (id < 0) { error = "Choose a song from your library"; publish(); return; }
            load(id, state().getLong("position", 0), 0);
            return;
        }
        ExoPlayer player = active.player;
        int state = player.getPlaybackState();
        if (error != null || player.getMediaItemCount() == 0 || state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
            long position = player.getMediaItemCount() == 0 ? state().getLong("position", 0) : positionMs();
            load(current.id, state == Player.STATE_ENDED ? 0 : position, 0);
            return;
        }
        if (settings().getBoolean(FADE_PAUSE, true)) {
            // Soft start: rise from silence, or from wherever a pause fade had got to.
            if (!(active.ramp.active && active.ramp.to == 0f)) active.setVolume(0f);
            fadeTo(active, 1f, FADE_IN_MS, null);
        } else {
            active.ramp.active = false;
            active.setVolume(1f);
        }
        player.play();
    }

    /** {@code soft}: a short fade before pausing (user pause); a car or a call pauses at once. */
    void pause(boolean soft) {
        releaseOutgoing();
        savePosition();
        Deck deck = active;
        if (soft && deck.player.isPlaying() && settings().getBoolean(FADE_PAUSE, true)) {
            fadeTo(deck, 0f, FADE_OUT_MS, () -> { if (deck == active) deck.player.pause(); });
        } else {
            deck.ramp.active = false;
            deck.player.pause();
        }
        publish();
    }

    void seek(long position) {
        long length = durationMs();
        active.player.seekTo(Math.max(0, length > 0 ? Math.min(length, position) : position));
        publish();
    }

    void step(int direction, boolean ended) {
        if (current == null || queue.isEmpty()) { resume(); return; }
        if (direction < 0 && positionMs() > 3000) { seek(0); return; }
        String repeat = repeat();
        if (ended && "One".equals(repeat)) { load(current.id, 0, 0); return; }
        int next = index + direction;
        if (next >= queue.size()) {
            if ("All".equals(repeat)) next = 0;
            else { active.ramp.active = false; active.player.pause(); seek(0); return; }
        }
        if (next < 0) next = "All".equals(repeat) ? queue.size() - 1 : 0;
        load(queue.get(next), 0, ended ? 0 : skipFade());
    }

    /** Quick fade used when the song changes by hand, or 0 when that is turned off. */
    private int skipFade() {
        return active.player.isPlaying() && settings().getBoolean(CROSSFADE_SKIPS, true) ? Math.min(1500, crossfadeMs()) : 0;
    }

    private void setQueue(long[] ids, long currentId) {
        if (ids == null || ids.length == 0) return;
        queue.clear();
        for (long id : ids) queue.add(id);
        load(currentId >= 0 ? currentId : ids[0], 0, skipFade());
    }

    private void shuffle() {
        if (current == null) return;
        ArrayList<Long> rest = new ArrayList<>(queue);
        rest.remove(current.id);
        Collections.shuffle(rest, random);
        queue.clear(); queue.add(current.id); queue.addAll(rest); index = 0;
        saveState();
        queueNext();
        publish();
    }

    private void setRepeat(String mode) {
        state().edit().putString("repeat", "All".equals(mode) || "One".equals(mode) ? mode : "Off").apply();
        queueNext();
        publish();
    }

    /** The normalisation mode, the effects, a preset or the equalizer's on/off changed. */
    private void refreshGain() {
        String mode = normalizationMode();
        EqualizerProfile profile = new EqualizerProfile(this);
        SoundEffects.Settings settings = profile.activeEffects();
        boolean original = profile.comparing();
        active.effects.setSettings(settings);
        active.effects.setOriginal(original);
        if (outgoing != null) {
            outgoing.effects.setSettings(settings);
            outgoing.effects.setOriginal(original);
        }
        active.gain.setGain(ReplayGainProcessor.gainFor(audioFormat(active.tracks), mode));
        if (outgoing != null) outgoing.gain.setGain(ReplayGainProcessor.gainFor(audioFormat(outgoing.tracks), mode));
    }

    private void failed(PlaybackException failure) {
        Log.w(TAG, "Cannot play " + (current == null ? "?" : current.name) + ": " + failure.getErrorCodeName());
        failures++;
        boolean skip = queue.size() > 1 && failures < queue.size();
        error = skip ? "Can't play this file · skipping" : "Can't play this file";
        publish();
        // Skip to the next song even with Repeat One; stop once every song has failed.
        if (skip) main.postDelayed(() -> { if (error != null) load(queue.get((index + 1) % queue.size()), 0, 0); }, 1500);
    }

    private static boolean sameAlbum(MusicLibrary.Track a, MusicLibrary.Track b) {
        return a.album != null && !a.album.trim().isEmpty() && a.album.equals(b.album) && a.folder != null && a.folder.equals(b.folder);
    }

    /** The song that plays after this one ends on its own (repeat aware), or null. */
    private MusicLibrary.Track nextTrack() {
        if (queue.isEmpty() || current == null) return null;
        String repeat = repeat();
        if ("One".equals(repeat)) return current;
        int next = index + 1;
        if (next >= queue.size()) { if (!"All".equals(repeat)) return null; next = 0; }
        return MusicLibrary.get(this).track(queue.get(next));
    }

    /** Crossfade length into the next song, or 0 when it follows without a gap. */
    private int crossfadeInto(MusicLibrary.Track next) {
        if (next == null || current == null) return 0;
        // A crossfade owns two decoders and AudioTracks. Keep gapless single-deck playback while
        // the Android 13 box is thermally constrained, then resume the saved choice after cooling.
        if (BoxThermal.throttled) return 0;
        if (sameAlbum(current, next) && settings().getBoolean(CROSSFADE_ALBUMS, true)) return 0;
        long length = durationMs();
        return (int) Math.min(crossfadeMs(), length > 0 ? length / 4 : crossfadeMs());
    }

    /** Keeps the following song queued on the active deck when it should follow gaplessly. */
    private void queueNext() {
        ExoPlayer player = active.player;
        if (current == null || player.getMediaItemCount() == 0) return;
        MusicLibrary.Track next = nextTrack();
        long wanted = next != null && crossfadeInto(next) == 0 ? next.id : -1;
        if (wanted == queuedNextId) return;
        int playing = player.getCurrentMediaItemIndex();
        if (player.getMediaItemCount() > playing + 1) player.removeMediaItems(playing + 1, player.getMediaItemCount());
        if (wanted >= 0) player.addMediaItem(item(next));
        queuedNextId = wanted;
    }

    /** The song after the current one in Playing Next, or null at the end of the queue. */
    private MusicLibrary.Track upNext() {
        if (current == null || index < 0 || index + 1 >= queue.size()) return null;
        return MusicLibrary.get(this).track(queue.get(index + 1));
    }

    private void loadArtwork(MusicLibrary.Track track) {
        MusicArt.load(this, track, bitmap -> {
            if (bitmap == null || track != current) return;
            ExoPlayer player = active.player;
            int at = player.getCurrentMediaItemIndex();
            MediaItem playing = player.getCurrentMediaItem();
            if (playing == null || idOf(playing) != track.id) return;
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, jpeg);
            MediaMetadata meta = playing.mediaMetadata.buildUpon()
                    .setArtworkData(jpeg.toByteArray(), MediaMetadata.PICTURE_TYPE_FRONT_COVER).build();
            player.replaceMediaItem(at, playing.buildUpon().setMediaMetadata(meta).build());
        });
    }

    // ---- Fades ---------------------------------------------------------------------------------

    private void fadeTo(Deck deck, float to, int ms, Runnable done) {
        deck.ramp.begin(deck.volume, to, ms, done);
        main.removeCallbacks(fader);
        main.post(fader);
    }

    /** Moves both decks' volumes 25 times a second; only runs while something is fading. */
    private final Runnable fader = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            boolean more = false;
            for (Deck deck : new Deck[]{active, outgoing}) {
                if (deck == null || !deck.ramp.active) continue;
                deck.setVolume(deck.ramp.value(now));
                if (deck.ramp.finished(now)) {
                    deck.ramp.active = false;
                    Runnable done = deck.ramp.done;
                    deck.ramp.done = null;
                    if (done != null) done.run();
                } else more = true;
            }
            if (more) main.postDelayed(this, 40);
        }
    };

    private void releaseOutgoing() {
        if (outgoing == null) return;
        Deck old = outgoing;
        outgoing = null;
        old.release();
    }

    // ---- Housekeeping --------------------------------------------------------------------------

    /** Starts the crossfade into the next song just before this one ends. */
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            long delay = active.player.isPlaying() && !BoxThermal.throttled ? 500 : 2000;
            ExoPlayer player = active.player;
            if (current != null && outgoing == null && player.isPlaying()) {
                MusicLibrary.Track next = nextTrack();
                int fade = crossfadeInto(next);
                long length = durationMs(), remaining = length - positionMs();
                if (next != null && fade > 0 && length > 0 && remaining > 0) {
                    long lead = fade + 150;
                    if (remaining <= lead) load(next.id, 0, (int) Math.max(1000, remaining));
                    else delay = remaining - lead > 2000 ? 500 : 50;
                }
            }
            main.postDelayed(this, delay);
        }
    };

    /** Play counts, saved position, settings changes and core placement, once a second. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            if (now - lastThermalAt >= 10_000) {
                lastThermalAt = now;
                if (BoxThermal.update(MusicService.this)) queueNext();
            }
            // A damaged file can leave the player stuck at one spot with no error: skip it instead of looping forever.
            if (current != null && stallWatch.stalled(now, positionMs(), active.player.getPlayWhenReady() && error == null
                    && active.player.getPlaybackState() != Player.STATE_ENDED && active.player.getPlaybackState() != Player.STATE_IDLE)) {
                stallWatch.reset();
                failed(new PlaybackException("Stuck at " + positionMs() + " ms", null, PlaybackException.ERROR_CODE_DECODING_FAILED));
            }
            if (current != null && active.player.isPlaying()) {
                heardMs += Math.min(2000, now - tickAt);
                tickAt = now;
                if (!counted && heardMs >= Math.min(30000, Math.max(5000, durationMs() / 2))) {
                    counted = true;
                    long id = current.id;
                    MusicLibrary.WORK.execute(() -> MusicLibrary.get(MusicService.this).played(id));
                }
                if (now - lastSave >= 5000) { savePosition(); lastSave = now; }
                if (now - lastPin >= 30000) { lastPin = now; playbackHandler.post(MusicService.this::pinPlaybackThread); }
                queueNext();
            } else {
                tickAt = now;
            }
            main.postDelayed(this, 1000);
        }
    };

    /** Runs on the playback thread: keep it on the performance cores. */
    private boolean pinRecorded;

    private void pinPlaybackThread() {
        long mask = CoreAffinity.pinCurrentThread();
        if (mask != pinnedMask || !pinRecorded) {
            pinnedMask = mask;
            pinRecorded = true;
            AudioThreads.recordRequest(this, mask);
            Log.i(TAG, mask > 0 ? "Playback thread on cores 0x" + Long.toHexString(mask) : "Playback thread not pinned (" + mask + ")");
        }
    }

    /** Shares what the UI shows but the player can't say: audio session, error, up next, repeat. */
    private void publish() {
        Bundle extras = new Bundle();
        extras.putInt(EXTRA_AUDIO_SESSION, active.audioSession);
        extras.putString(EXTRA_ERROR, error);
        MusicLibrary.Track next = upNext();
        extras.putLong(EXTRA_UP_NEXT, next == null ? -1 : next.id);
        extras.putLong(EXTRA_CURRENT, current == null ? -1 : current.id);
        extras.putString(EXTRA_REPEAT, repeat());
        extras.putParcelable(EXTRA_TOKEN, session.getPlatformToken());
        if (same(extras, publishedExtras)) return;
        publishedExtras = extras;
        session.setSessionExtras(extras);
    }
    private static boolean same(Bundle a, Bundle b) {
        if (b == null) return false;
        for (String key : new String[]{EXTRA_AUDIO_SESSION, EXTRA_ERROR, EXTRA_UP_NEXT, EXTRA_CURRENT, EXTRA_REPEAT}) {
            if (!java.util.Objects.equals(a.get(key), b.get(key))) return false;
        }
        return true;
    }

    /** Ids of the Playing Next queue, as last saved by the music process (for the UI). */
    static List<Long> savedQueue(Context context) {
        ArrayList<Long> ids = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(context.getSharedPreferences(STATE, Context.MODE_MULTI_PROCESS).getString("queue", "[]"));
            for (int i = 0; i < saved.length(); i++) ids.add(saved.getLong(i));
        } catch (Exception broken) { ids.clear(); }
        return ids;
    }
}
