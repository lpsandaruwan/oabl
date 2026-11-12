import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.system.Os;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Private development box only: root helper for the launcher's control of Maps' picture-in-picture
 * tile, which an ordinary app can't touch. It listens on the abstract socket "oabl-pip" for one line
 * from the launcher: "hide PACKAGE", "show PACKAGE" or "close PACKAGE". It acts only on a pinned task
 * of that package, so another app's tile is left alone.
 *
 * hide uses WindowContainerTransaction.setHidden, which keeps the task and the route. Don't use
 * `am stack move-task` for this: moving the pinned task crashed SystemUI ("Unknown task").
 * close uses removeTask, as `am stack remove` does, which ends Maps' guidance, so it's only for after a trip.
 */
public final class OablPip {
    private static final String TAG = "OablPip";
    private static final String SOCKET = "oabl-pip", LAUNCHER_DATA = "/data/data/com.softiva.oabl";
    private static final int PINNED = 2, ANY_TYPE = 0;
    private static final long WATCH_MS = 400;

    private static Object hiddenToken;
    private static int hiddenTask = -1;

    public static void main(String[] args) throws Exception {
        if (args.length == 2) { act(args[0], args[1]); return; }
        Thread watcher = new Thread(OablPip::watch, "oabl-pip-watch");
        watcher.setDaemon(true);
        watcher.start();
        LocalServerSocket server = new LocalServerSocket(SOCKET);
        Log.i(TAG, "listening");
        while (true) {
            try (LocalSocket client = server.accept()) {
                client.setSoTimeout(2000);
                Credentials peer = client.getPeerCredentials();
                if (peer.getUid() != Os.stat(LAUNCHER_DATA).st_uid) { Log.w(TAG, "refused uid " + peer.getUid()); continue; }
                String line = new BufferedReader(new InputStreamReader(client.getInputStream())).readLine();
                String[] parts = line == null ? new String[0] : line.trim().split(" ");
                if (parts.length == 2) act(parts[0], parts[1]);
            } catch (Exception failed) {
                Log.w(TAG, "request failed", failed);
            }
        }
    }

    private static synchronized void act(String command, String pkg) throws Exception {
        Object atm = taskManager();
        Object pinned = method(atm, "getRootTaskInfo").invoke(atm, PINNED, ANY_TYPE);
        if (pinned == null || !pkg.equals(packageOf(pinned))) return;
        int task = field(pinned, "taskId").getInt(pinned);
        Object token = field(pinned, "token").get(pinned);
        switch (command) {
            case "hide":
                setHidden(atm, token, true);
                hiddenToken = token;
                hiddenTask = task;
                break;
            case "show":
                setHidden(atm, token, false);
                clearWatch();
                break;
            case "close":
                setHidden(atm, token, false);
                clearWatch();
                method(atm, "removeTask").invoke(atm, task);
                break;
            default:
                return;
        }
        Log.i(TAG, command + " task " + task);
    }

    /**
     * Opening Maps while its tile is hidden turns the tile into a full-screen task that would stay
     * hidden, so the hidden flag comes off as soon as the task stops being the pinned one.
     */
    private static void watch() {
        while (true) {
            try {
                Thread.sleep(WATCH_MS);
                synchronized (OablPip.class) {
                    if (hiddenToken == null) continue;
                    Object atm = taskManager();
                    Object pinned = method(atm, "getRootTaskInfo").invoke(atm, PINNED, ANY_TYPE);
                    if (pinned != null && field(pinned, "taskId").getInt(pinned) == hiddenTask) continue;
                    Log.i(TAG, "task " + hiddenTask + " left the tile; unhiding");
                    Object token = hiddenToken;
                    clearWatch();
                    setHidden(atm, token, false);
                }
            } catch (Exception failed) {
                Log.w(TAG, "watch failed", failed);
                synchronized (OablPip.class) { clearWatch(); }
            }
        }
    }

    private static void clearWatch() { hiddenToken = null; hiddenTask = -1; }

    private static void setHidden(Object atm, Object token, boolean hidden) throws Exception {
        Class<?> type = Class.forName("android.window.WindowContainerTransaction");
        Object transaction = type.getDeclaredConstructor().newInstance();
        for (Method m : type.getMethods()) if (m.getName().equals("setHidden")) m.invoke(transaction, token, hidden);
        Object organizer = method(atm, "getWindowOrganizerController").invoke(atm);
        for (Method m : organizer.getClass().getMethods()) {
            if (m.getName().equals("applyTransaction") && m.getParameterTypes().length == 1) { m.invoke(organizer, transaction); return; }
        }
        throw new NoSuchMethodException("applyTransaction");
    }

    private static String packageOf(Object info) throws Exception {
        for (String name : new String[]{"topActivity", "baseActivity"}) {
            Object component = field(info, name).get(info);
            if (component instanceof android.content.ComponentName) return ((android.content.ComponentName) component).getPackageName();
        }
        return null;
    }

    private static Object taskManager() throws Exception {
        return Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    }

    private static Method method(Object target, String name) throws Exception {
        for (Method m : target.getClass().getMethods()) if (m.getName().equals(name)) return m;
        throw new NoSuchMethodException(name);
    }

    private static Field field(Object target, String name) throws Exception {
        return Class.forName("android.app.TaskInfo").getField(name);
    }
}
