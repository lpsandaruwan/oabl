package com.softiva.oabl;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google Maps' picture-in-picture tile, which Android keeps above every app. An ordinary app can't
 * touch another app's tile, so the box's root helper does it (scripts/device-init/oabl-pip). Without
 * the helper, on another device, every call does nothing and the tile behaves as Android leaves it.
 */
final class MapsTile {
    static final String MAPS = "com.google.android.apps.maps";
    /** Seconds the arrival screen stays before the tile closes; also rides out a notification blip. */
    static final long CLOSE_AFTER_END_MS = 8_000;
    /** Lets Maps finish opening guidance before Home sends it to its tile. */
    static final long HOME_AFTER_START_MS = 1_500;

    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "oabl-maps-tile");
        thread.setDaemon(true);
        return thread;
    });

    private MapsTile() {}

    /** Full-screen panels open over Home; touched on the main thread only. */
    private static int panels;

    /** Off the screen, with the route still running. */
    static void hide() { send("hide"); }
    /** Back on Home, unless a full-screen panel is open over it. */
    static void show() { if (panels == 0) send("show"); }

    /** Settings and the other panels are dialogs inside Home, so Home never stops for them. */
    static void hideWhileOpen(android.app.Dialog panel) { hideWhileOpen(panel, null); }

    static void hideWhileOpen(android.app.Dialog panel, Runnable onClose) {
        panel.setOnShowListener(d -> { panels++; hide(); });
        panel.setOnDismissListener(d -> {
            if (onClose != null) onClose.run();
            panels = Math.max(0, panels - 1);
            show();
        });
    }

    /** Home going away takes any panels with it, whether or not they said so. */
    static void forgetPanels() { panels = 0; }

    /** Ends what's left of Maps' guidance screen too, so this is only for after a trip. */
    static void close() { send("close"); }

    private static void send(String command) {
        SENDER.execute(() -> {
            try (LocalSocket socket = new LocalSocket()) {
                socket.connect(new LocalSocketAddress("oabl-pip", LocalSocketAddress.Namespace.ABSTRACT));
                socket.getOutputStream().write((command + " " + MAPS + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException noHelper) {
                // Not this box, or the helper is off: the tile stays as Android leaves it.
            }
        });
    }

    /** Spots a route starting or ending from the listener's snapshots. */
    static final class Watch {
        enum Change { NONE, STARTED, ENDED }

        private boolean seen;
        private String guiding;

        /** The next snapshot only sets the baseline: a route already running hasn't just started. */
        void reset() { seen = false; guiding = null; }

        Change update(boolean active, String packageName) {
            String now = active ? packageName : null;
            if (!seen) { seen = true; guiding = now; return Change.NONE; }
            String before = guiding;
            guiding = now;
            if (before == null && MAPS.equals(now)) return Change.STARTED;
            if (MAPS.equals(before) && now == null) return Change.ENDED;
            return Change.NONE;
        }
    }
}
