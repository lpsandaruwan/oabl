package com.softiva.oabl;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.media.MediaBrowserService;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

final class MediaControllerClient {
    static final class Snapshot {
        final String title;
        final String subtitle;
        final Bitmap artwork;
        final String packageName;
        final boolean playing;
        final String source;
        long position;
        long duration;
        long updatedAt;
        float rate;
        /** The last track of a player whose session has closed (VLC after it stops); no live controller. */
        boolean remembered;

        Snapshot(String title, String subtitle, Bitmap artwork,
                 String packageName, boolean playing) {
            this(title, subtitle, artwork, packageName, playing, null);
        }

        Snapshot(String title, String subtitle, Bitmap artwork,
                 String packageName, boolean playing, String source) {
            this.title = title;
            this.subtitle = subtitle;
            this.artwork = artwork;
            this.packageName = packageName;
            this.playing = playing;
            this.source = source;
        }
    }

    private final MediaSessionManager sessionManager;
    private final ComponentName listenerComponent;
    private final PackageManager packageManager;
    private final Context context;
    private final SharedPreferences card;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String lastPackage;
    private String lastAppLabel;
    private MediaController activeController;
    private String rememberedArtKey;
    private Bitmap rememberedArt;
    private long positionSavedAt;
    /** The position last written while paused; a paused player is saved once, not every refresh. */
    private long pausedPositionSaved = -1;
    private long fullReadAt;
    private MediaBrowser browser;

    // Sessions are tracked through callbacks rather than re-listed on every refresh: listing them
    // builds new controllers and each metadata read copies the album art across processes again.
    private List<MediaController> sessions = Collections.emptyList();
    private final java.util.Map<MediaSession.Token, MediaController.Callback> watched = new java.util.HashMap<>();
    private MediaSession.Token localToken;
    private MediaController localController;
    private boolean listening;
    private long listenTriedAt;
    private boolean dirty = true;
    /** Run on the main thread whenever a player reports a change, so the card can redraw at once. */
    private volatile Runnable changeListener;
    private Snapshot cached;
    private PlaybackState cachedState;
    private String cachedTitle;
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = controllers -> {
        setSessions(controllers == null ? Collections.<MediaController>emptyList() : controllers);
    };

    MediaControllerClient(Context context) {
        this.context = context.getApplicationContext();
        sessionManager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        listenerComponent = new ComponentName(context, MediaAccessService.class);
        packageManager = context.getPackageManager();
        card = this.context.getSharedPreferences("media_card", Context.MODE_PRIVATE);
    }

    void setChangeListener(Runnable listener) { changeListener = listener; }

    private void changed() {
        dirty = true;
        Runnable listener = changeListener;
        if (listener != null) listener.run();
    }

    Snapshot read() {
        listen();
        MediaController local = localController();
        if (!listening) {
            // Without notification access there are no session callbacks: list them each time.
            try {
                setSessions(sessionManager == null ? Collections.<MediaController>emptyList()
                        : sessionManager.getActiveSessions(listenerComponent));
            } catch (SecurityException error) {
                setSessions(Collections.<MediaController>emptyList());
            }
            dirty = true;
        }
        // Safety nets for a missed callback. A full read copies the artwork across processes, so it
        // is not done on every refresh; a cheap look at the playback state is.
        if (SystemClock.elapsedRealtime() - fullReadAt > 5_000) dirty = true;
        if (!dirty && activeController != null && stateMoved(activeController)) dirty = true;
        if (!dirty) {
            // Nothing changed since the last read; the position is extrapolated from the cached state.
            if (cached != null && !cached.remembered) remember(cached, cachedState, cachedTitle);
            return cached != null && cached.remembered ? rememberedSnapshot() : cached;
        }
        dirty = false;
        fullReadAt = SystemClock.elapsedRealtime();
        List<MediaController> candidates = sessions;
        if (local != null) {
            boolean found = false;
            for (MediaController controller : candidates) if (controller.getSessionToken().equals(local.getSessionToken())) found = true;
            if (!found) { candidates = new java.util.ArrayList<>(candidates); candidates.add(local); }
        }
        activeController = choose(candidates);
        if (activeController == null) {
            cachedState = null; cachedTitle = null;
            return cached = rememberedSnapshot();
        }
        MediaMetadata metadata = activeController.getMetadata();
        PlaybackState state = activeController.getPlaybackState();
        String title = metadata == null ? null
                : text(metadata, MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String artist = metadata == null ? null
                : placeholderToNull(text(metadata, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
        Bitmap art = metadata == null ? null
                : bitmap(metadata, MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_ART);
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        String source = appLabel(activeController.getPackageName());
        Snapshot snapshot = new Snapshot(emptyFallback(title, "Media from " + source),
                emptyFallback(artist, title == null || title.trim().isEmpty()
                        ? "Track details unavailable" : source),
                art, activeController.getPackageName(), playing, source);
        if (metadata != null) {
            snapshot.duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }
        if (state != null) {
            snapshot.position = state.getPosition();
            snapshot.updatedAt = state.getLastPositionUpdateTime();
            snapshot.rate = state.getPlaybackSpeed();
        }
        remember(snapshot, state, title);
        cached = snapshot; cachedState = state; cachedTitle = title;
        return snapshot;
    }

    /**
     * True when the player has published a new playback state since the cached read: a play/pause,
     * a skip or a seek all rebuild it with a new timestamp, which a steady playing state never does.
     */
    private boolean stateMoved(MediaController controller) {
        try {
            PlaybackState now = controller.getPlaybackState();
            if (now == null || cachedState == null) return now != cachedState;
            return now.getState() != cachedState.getState()
                    || now.getLastPositionUpdateTime() != cachedState.getLastPositionUpdateTime()
                    || now.getActiveQueueItemId() != cachedState.getActiveQueueItemId();
        } catch (RuntimeException gone) {
            return true;
        }
    }

    /** Registers for session list changes once notification access allows it; retried every 10 s. */
    private void listen() {
        if (listening || sessionManager == null) return;
        long now = SystemClock.elapsedRealtime();
        if (listenTriedAt != 0 && now - listenTriedAt < 10_000) return;
        listenTriedAt = now;
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent, main);
            listening = true;
            setSessions(sessionManager.getActiveSessions(listenerComponent));
        } catch (SecurityException noAccess) {
            listening = false;
        }
    }

    /** OABL Music's own session, rebuilt only when its token changes. */
    private MediaController localController() {
        MediaController fresh = MusicClient.get(context).platformController();
        MediaSession.Token token = fresh == null ? null : fresh.getSessionToken();
        if (token == null ? localToken == null : token.equals(localToken)) return localController;
        if (localController != null) unwatch(localController);
        localToken = token; localController = fresh;
        if (fresh != null) watch(fresh);
        dirty = true;
        return fresh;
    }

    private void setSessions(List<MediaController> next) {
        java.util.Set<MediaSession.Token> keep = new java.util.HashSet<>();
        for (MediaController controller : next) keep.add(controller.getSessionToken());
        if (localToken != null) keep.add(localToken);
        for (MediaController old : sessions) if (!keep.contains(old.getSessionToken())) unwatch(old);
        sessions = next;
        for (MediaController controller : next) watch(controller);
        dirty = true;
    }

    private void watch(MediaController controller) {
        MediaSession.Token token = controller.getSessionToken();
        if (watched.containsKey(token)) return;
        MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) { changed(); }
            @Override public void onMetadataChanged(MediaMetadata metadata) { changed(); }
            @Override public void onSessionDestroyed() { changed(); }
        };
        try { controller.registerCallback(callback, main); watched.put(token, callback); }
        catch (RuntimeException gone) { dirty = true; }
    }

    private void unwatch(MediaController controller) {
        MediaController.Callback callback = watched.remove(controller.getSessionToken());
        if (callback != null) try { controller.unregisterCallback(callback); } catch (RuntimeException ignored) { }
    }

    /** Drops every callback; the owner calls this when it is destroyed. */
    void release() {
        if (listening && sessionManager != null) {
            try { sessionManager.removeOnActiveSessionsChangedListener(sessionsChanged); } catch (RuntimeException ignored) { }
        }
        listening = false;
        for (MediaController controller : sessions) unwatch(controller);
        if (localController != null) unwatch(localController);
        sessions = Collections.emptyList(); localController = null; localToken = null;
        watched.clear(); cached = null; dirty = true;
    }

    /**
     * Keeps the card on the player you used last. A playing session marks its app as the last
     * player; another app's track is also saved, so the card can still show it (and start it
     * again) after that app closes its session, as VLC does when it stops.
     */
    private void remember(Snapshot snapshot, PlaybackState state, String title) {
        int playback = state == null ? PlaybackState.STATE_NONE : state.getState();
        SharedPreferences.Editor edit = null;
        if (playback == PlaybackState.STATE_PLAYING && !snapshot.packageName.equals(card.getString("last_player", null))) {
            edit = card.edit().putString("last_player", snapshot.packageName);
        }
        boolean live = playback == PlaybackState.STATE_PLAYING || playback == PlaybackState.STATE_PAUSED;
        if (!context.getPackageName().equals(snapshot.packageName) && title != null && !title.trim().isEmpty() && live) {
            String key = snapshot.packageName + "|" + snapshot.title + "|" + snapshot.subtitle;
            long now = SystemClock.elapsedRealtime();
            boolean changed = !key.equals(card.getString("key", null));
            long position = snapshot.position;
            if (playback == PlaybackState.STATE_PLAYING && snapshot.updatedAt > 0) {
                position += (long) ((now - snapshot.updatedAt) * snapshot.rate);
            }
            boolean pausedMoved = playback == PlaybackState.STATE_PAUSED && position != pausedPositionSaved;
            if (changed || pausedMoved || (playback == PlaybackState.STATE_PLAYING && now - positionSavedAt > 5000)) {
                pausedPositionSaved = playback == PlaybackState.STATE_PAUSED ? position : -1;
                if (edit == null) edit = card.edit();
                edit.putString("key", key).putString("package", snapshot.packageName)
                        .putString("title", snapshot.title).putString("subtitle", snapshot.subtitle)
                        .putLong("duration", snapshot.duration).putLong("position", Math.max(0, position));
                positionSavedAt = now;
            }
            if (changed) saveArt(key, snapshot.artwork);
        }
        if (edit != null) edit.apply();
    }

    private File artFile() { return new File(context.getFilesDir(), "media_card_art.png"); }

    private void saveArt(String key, Bitmap art) {
        rememberedArtKey = key;
        rememberedArt = art == null ? null : scaled(art);
        Bitmap toSave = rememberedArt;
        File file = artFile();
        MusicLibrary.WORK.execute(() -> {
            if (toSave == null) { file.delete(); return; }
            try (FileOutputStream out = new FileOutputStream(file)) {
                toSave.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (IOException ignored) {
                file.delete();
            }
        });
    }

    private static Bitmap scaled(Bitmap art) {
        int longest = Math.max(art.getWidth(), art.getHeight());
        if (longest <= 256) return art;
        float scale = 256f / longest;
        return Bitmap.createScaledBitmap(art, Math.round(art.getWidth() * scale), Math.round(art.getHeight() * scale), true);
    }

    /** The saved track of the last player, when that is another app that is still installed. */
    private Snapshot rememberedSnapshot() {
        String pkg = card.getString("package", null);
        if (pkg == null || !pkg.equals(card.getString("last_player", null))
                || packageManager.getLaunchIntentForPackage(pkg) == null) return null;
        String key = card.getString("key", null);
        if (key != null && !key.equals(rememberedArtKey)) {
            rememberedArtKey = key;
            rememberedArt = artFile().exists() ? BitmapFactory.decodeFile(artFile().getPath()) : null;
        }
        Snapshot snapshot = new Snapshot(card.getString("title", ""), card.getString("subtitle", ""),
                rememberedArt, pkg, false, appLabel(pkg));
        snapshot.duration = card.getLong("duration", 0);
        snapshot.position = card.getLong("position", 0);
        snapshot.updatedAt = SystemClock.elapsedRealtime();
        snapshot.remembered = true;
        return snapshot;
    }

    /** Players often send "<unknown>" style placeholders; treat them as missing. */
    private static String placeholderToNull(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.isEmpty() || normalized.equals("<unknown>") || normalized.equals("unknown")
                || normalized.equals("unknown artist") ? null : value;
    }

    private String appLabel(String packageName) {
        if (context.getPackageName().equals(packageName)) return "OABL Music";
        if (packageName.equals(lastPackage)) {
            return lastAppLabel;
        }
        lastPackage = packageName;
        try {
            lastAppLabel = packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException error) {
            lastAppLabel = "player";
        }
        return lastAppLabel;
    }

    void previous() {
        if (activeController != null) {
            activeController.getTransportControls().skipToPrevious();
        } else {
            controlRemembered(COMMAND_PREVIOUS);
        }
    }

    void playPause() {
        if (activeController == null) {
            controlRemembered(COMMAND_PLAY);
            return;
        }
        PlaybackState state = activeController.getPlaybackState();
        if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
            activeController.getTransportControls().pause();
        } else {
            activeController.getTransportControls().play();
        }
    }

    void next() {
        if (activeController != null) {
            activeController.getTransportControls().skipToNext();
        } else {
            controlRemembered(COMMAND_NEXT);
        }
    }

    /**
     * A playing session wins. Otherwise the player you used last: its paused session, or, when
     * another app has closed its session, null so the card shows that app's remembered track.
     * Then OABL Music, then the first paused player that still describes a track. Leftover
     * sessions with no track (an idle "Media from Google") are skipped so they cannot take over
     * the card and swallow the buttons.
     */
    private MediaController choose(List<MediaController> sessions) {
        String lastPlayer = card.getString("last_player", null);
        MediaController own = null, described = null, last = null;
        for (MediaController controller : sessions) {
            PlaybackState state = controller.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                return controller;
            }
            String title = controller.getMetadata() == null ? null : text(controller.getMetadata(),
                    MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            boolean track = title != null && !title.trim().isEmpty();
            if (last == null && track && controller.getPackageName().equals(lastPlayer)) last = controller;
            if (context.getPackageName().equals(controller.getPackageName())) {
                if (own == null) own = controller;
            } else if (described == null && track) {
                described = controller;
            }
        }
        if (last != null) return last;
        if (rememberedSnapshot() != null) return null;
        return own != null ? own : described;
    }

    /**
     * Sends a command to a remembered player whose session has closed. Its media browser service
     * (the one Android Auto uses) wakes it and hands over a fresh session; if the app has none,
     * refuses the connection, or does not start playing, the app is opened instead.
     */
    private void controlRemembered(int command) {
        Snapshot memory = rememberedSnapshot();
        if (memory == null) return;
        String pkg = memory.packageName;
        List<ResolveInfo> services = packageManager.queryIntentServices(
                new Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0);
        if (services.isEmpty()) { open(pkg); return; }
        ServiceInfo service = services.get(0).serviceInfo;
        if (browser != null) browser.disconnect();
        final MediaBrowser[] connection = new MediaBrowser[1];
        connection[0] = new MediaBrowser(context, new ComponentName(service.packageName, service.name),
                new MediaBrowser.ConnectionCallback() {
                    @Override public void onConnected() {
                        try {
                            MediaController.TransportControls controls =
                                    new MediaController(context, connection[0].getSessionToken()).getTransportControls();
                            if (command == COMMAND_NEXT) controls.skipToNext();
                            else if (command == COMMAND_PREVIOUS) controls.skipToPrevious();
                            else controls.play();
                        } catch (RuntimeException error) {
                            Log.w("OablMedia", "Could not control " + pkg + ": " + error);
                            open(pkg);
                        }
                    }
                    @Override public void onConnectionFailed() {
                        Log.w("OablMedia", "Media browser of " + pkg + " refused the connection");
                        open(pkg);
                    }
                }, null);
        browser = connection[0];
        browser.connect();
        main.postDelayed(() -> {
            if (command == COMMAND_PLAY && !playing(pkg)) open(pkg);
            // A player that started keeps going after the browser lets go: it is a foreground service by then.
            connection[0].disconnect();
            if (browser == connection[0]) browser = null;
        }, 3000);
    }

    private static final int COMMAND_PLAY = 0, COMMAND_NEXT = 1, COMMAND_PREVIOUS = 2;

    private boolean playing(String pkg) {
        try {
            for (MediaController controller : sessionManager.getActiveSessions(listenerComponent)) {
                PlaybackState state = controller.getPlaybackState();
                if (pkg.equals(controller.getPackageName()) && state != null
                        && state.getState() == PlaybackState.STATE_PLAYING) return true;
            }
        } catch (SecurityException ignored) { }
        return false;
    }

    private void open(String pkg) {
        Intent launch = packageManager.getLaunchIntentForPackage(pkg);
        if (launch == null) return;
        try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException error) {
            Log.w("OablMedia", "Could not open " + pkg + ": " + error);
        }
    }

    private static String text(MediaMetadata metadata, String first, String second) {
        CharSequence value = metadata.getText(first);
        if (value == null || value.length() == 0) {
            value = metadata.getText(second);
        }
        return value == null ? null : value.toString();
    }

    private static Bitmap bitmap(MediaMetadata metadata, String first, String second) {
        Bitmap value = metadata.getBitmap(first);
        return value == null ? metadata.getBitmap(second) : value;
    }

    private static String emptyFallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
