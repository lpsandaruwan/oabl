package com.softiva.oabl;

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The launcher's handle on {@link MusicService}, which runs in the ":music" process. Connects
 * through a Media3 controller, queues commands until the connection is up, and reads the saved
 * queue and position while it isn't. Main thread only.
 */
final class MusicClient implements Player.Listener {
    private static MusicClient instance;
    static synchronized MusicClient get(Context context) {
        if (instance == null) instance = new MusicClient(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Runnable> observers = new HashSet<>();
    private final List<Runnable> pending = new ArrayList<>();
    private ListenableFuture<MediaController> connecting;
    private MediaController controller;

    private MusicClient(Context context) { this.context = context; }

    // ---- Connection ----------------------------------------------------------------------------

    private void connect() {
        if (controller != null || connecting != null) return;
        SessionToken token = new SessionToken(context, new ComponentName(context, MusicService.class));
        ListenableFuture<MediaController> future = new MediaController.Builder(context, token)
                .setListener(new MediaController.Listener() {
                    @Override public void onExtrasChanged(MediaController c, Bundle extras) { notifyObservers(); }
                    @Override public void onDisconnected(MediaController c) {
                        controller = null;
                        notifyObservers();
                    }
                }).buildAsync();
        connecting = future;
        future.addListener(() -> {
            connecting = null;
            try {
                controller = future.get();
                controller.addListener(this);
                List<Runnable> queued = new ArrayList<>(pending);
                pending.clear();
                for (Runnable r : queued) r.run();
            } catch (Exception failed) {
                controller = null;
                pending.clear();
            }
            notifyObservers();
        }, main::post);
    }

    /** Runs now when connected, or once connected (starting the music process if needed). */
    private void whenConnected(Runnable action) {
        if (controller != null && controller.isConnected()) { action.run(); return; }
        controller = null;
        pending.add(action);
        connect();
    }

    void observe(Runnable observer) { observers.add(observer); connect(); }
    void unobserve(Runnable observer) { observers.remove(observer); }
    private void notifyObservers() { for (Runnable r : new ArrayList<>(observers)) r.run(); }
    @Override public void onEvents(Player player, Player.Events events) { notifyObservers(); }

    private boolean connected() { return controller != null && controller.isConnected(); }
    /** True while connected to a running music process. */
    boolean isConnected() { return connected(); }

    /** Written only in the launcher's process (Home and the music screen), read there too. */
    static final String VIEW = "music_view";

    /**
     * The name of the list the queue about to play came from (a playlist, Favourites, an artist,
     * album or folder, or Songs) and its length, so the music screen can title the playing list.
     */
    static void rememberSource(Context context, String name, int count) {
        context.getSharedPreferences(VIEW, Context.MODE_PRIVATE).edit()
                .putString("name", name).putInt("count", count).apply();
    }

    private String effectsSent;
    /**
     * OABL Music runs the exciter and bass in its own process. Tell it when what it should
     * run has changed: an edit, a preset, or switching the equalizer off (which turns them off).
     */
    void effectsChanged() {
        EqualizerProfile profile = new EqualizerProfile(context);
        String effects = profile.activeEffects() + "|" + profile.comparing();
        if (!connected() || effects.equals(effectsSent)) return;
        effectsSent = effects;
        command("refresh");
    }
    private Bundle extras() { return connected() ? controller.getSessionExtras() : Bundle.EMPTY; }
    private android.content.SharedPreferences saved() {
        return context.getSharedPreferences(MusicService.STATE, Context.MODE_MULTI_PROCESS);
    }

    // ---- State ---------------------------------------------------------------------------------

    /** The current song, or the last one played when the music process isn't running. */
    long currentId() {
        long live = extras().getLong(MusicService.EXTRA_CURRENT, -1);
        return live >= 0 ? live : saved().getLong("current", -1);
    }
    boolean isPlaying() {
        if (!connected()) return false;
        int state = controller.getPlaybackState();
        return controller.getPlayWhenReady() && state != Player.STATE_IDLE && state != Player.STATE_ENDED;
    }
    String error() { return extras().getString(MusicService.EXTRA_ERROR); }
    long position() { return connected() && controller.getMediaItemCount() > 0 ? Math.max(0, controller.getCurrentPosition()) : saved().getLong("position", 0); }
    /** Duration of the current song, or 0 when unknown (callers fall back to the library's value). */
    long duration() {
        if (!connected()) return 0;
        long length = controller.getDuration();
        return length > 0 ? length : 0;
    }
    long upNextId() { return extras().getLong(MusicService.EXTRA_UP_NEXT, -1); }
    int audioSessionId() { return extras().getInt(MusicService.EXTRA_AUDIO_SESSION, -1); }
    String repeat() {
        String live = extras().getString(MusicService.EXTRA_REPEAT);
        return live != null ? live : saved().getString("repeat", "Off");
    }
    List<Long> queue() { return MusicService.savedQueue(context); }
    /** A platform controller for OABL's session, for the media card's session list. */
    android.media.session.MediaController platformController() {
        android.media.session.MediaSession.Token token = extras().getParcelable(MusicService.EXTRA_TOKEN);
        return token == null ? null : new android.media.session.MediaController(context, token);
    }

    // ---- Commands ------------------------------------------------------------------------------

    void play(List<MusicLibrary.Track> tracks, long currentId) {
        long[] ids = new long[tracks.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = tracks.get(i).id;
        Bundle args = new Bundle();
        args.putLongArray(MusicService.ARG_IDS, ids);
        args.putLong(MusicService.ARG_CURRENT, currentId);
        custom(MusicService.COMMAND_QUEUE, args);
    }
    void command(String action) {
        switch (action) {
            case "toggle": whenConnected(() -> { if (isPlaying()) controller.pause(); else controller.play(); }); break;
            case "play": whenConnected(() -> controller.play()); break;
            case "pause": whenConnected(() -> controller.pause()); break;
            case "next": whenConnected(() -> controller.seekToNext()); break;
            case "previous": whenConnected(() -> controller.seekToPrevious()); break;
            case "refresh": custom(MusicService.COMMAND_REFRESH, Bundle.EMPTY); break;
            default: break;
        }
    }
    void seek(long position) { whenConnected(() -> controller.seekTo(position)); }
    void shuffle() { custom(MusicService.COMMAND_SHUFFLE, Bundle.EMPTY); }
    void setRepeat(String mode) {
        Bundle args = new Bundle();
        args.putString(MusicService.ARG_REPEAT, mode);
        custom(MusicService.COMMAND_REPEAT, args);
    }
    private void custom(String action, Bundle args) {
        whenConnected(() -> controller.sendCustomCommand(new SessionCommand(action, Bundle.EMPTY), args));
    }
}
