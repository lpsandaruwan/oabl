package com.softiva.oabl;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.view.MotionEvent;
import android.widget.ImageView;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;


/**
 * Optional, user-enabled bridge to Android's native split-screen controller, and the Back and Home rail
 * kept on the screen edge over every screen that is not an OABL screen (the accessibility overlay is allowed over anything,
 * and its Back and Home are the real system actions).
 */
public final class SplitScreenService extends AccessibilityService {
    // Runs in the ":services" process. The launcher sends commands as broadcasts and reads
    // "connected" / "pair active" from the shared state file.
    private static final String ACTION = "com.softiva.oabl.SPLIT_COMMAND";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean pairActive;
    private WindowManager windowManager;
    private View appControls;
    private android.content.BroadcastReceiver commands;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        commands = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context context, Intent intent) { handle(intent); }
        };
        ServicesState.register(this, commands, new android.content.IntentFilter(ACTION));
        ServicesState.prefs(this).edit().putBoolean("split_connected", true).putBoolean("split_pair", false).commit();
        // Reconnecting while another app is in front: no window event will come until the next change.
        if (!ServicesState.prefs(this).getBoolean("ours_visible", true)) showNavIfAllowed();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        setPair(false);
        hideAppControlsInternal();
        ServicesState.prefs(this).edit().putBoolean("split_connected", false).commit();
        if (commands != null) { try { unregisterReceiver(commands); } catch (RuntimeException ignored) { } commands = null; }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        hideAppControlsInternal();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Window contents are deliberately not inspected: only which app's window came forward.
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        NavTabPolicy.Action action = NavTabPolicy.decide(text(event.getPackageName()), text(event.getClassName()), getPackageName(), ignoredPackages());
        // The launcher knows exactly when one of its own screens is in front. A late window event from the app
        // that just left, or a Maps tile over Home, must not bring the rail back over the dashboard.
        if (action == NavTabPolicy.Action.SHOW) { if (!ServicesState.prefs(this).getBoolean("ours_visible", false)) showNavIfAllowed(); }
        else if (action == NavTabPolicy.Action.HIDE) hideAppControlsInternal();
    }

    private static String text(CharSequence value) { return value == null ? null : value.toString(); }

    private java.util.Set<String> ignored;

    /** The status bar and shade, and every installed keyboard. */
    private java.util.Set<String> ignoredPackages() {
        if (ignored != null) return ignored;
        java.util.Set<String> set = new java.util.HashSet<>();
        set.add("com.android.systemui");
        try {
            android.view.inputmethod.InputMethodManager manager = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) for (android.view.inputmethod.InputMethodInfo method : manager.getInputMethodList()) set.add(method.getPackageName());
        } catch (RuntimeException ignoredFailure) { /* the list is only an optimisation */ }
        ignored = set;
        return set;
    }

    /** Off when the owner switched it off, or while the navigation overlay (which has its own Home and Back) is up. */
    private void showNavIfAllowed() {
        android.content.SharedPreferences state = ServicesState.prefs(this);
        if ("off".equals(state.getString("nav_side", "right")) || state.getBoolean("drive_overlay", false)) return;
        showAppControlsInternal();
    }

    @Override
    public void onInterrupt() {
        // No continuous accessibility operation is performed.
    }

    private void setPair(boolean active) {
        pairActive = active;
        ServicesState.prefs(this).edit().putBoolean("split_pair", active).apply();
    }

    // ---- launcher-side API (any process) ----

    static boolean isConnected(android.content.Context context) {
        // The flag can be stale after a crash or force-stop (onUnbind never ran), so also require
        // the service to still be switched on in Android's accessibility settings.
        if (!ServicesState.prefs(context).getBoolean("split_connected", false)) return false;
        String enabled = android.provider.Settings.Secure.getString(context.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(context.getPackageName() + "/");
    }

    private static boolean send(android.content.Context context, Intent command) {
        if (!isConnected(context)) return false;
        context.sendBroadcast(command.setAction(ACTION).setPackage(context.getPackageName()));
        return true;
    }

    static boolean showAppControls(android.content.Context context) {
        // "Home button in other apps: Off" covers this rail too (e.g. when the firmware has its own).
        if (DriveOverlayService.isActive() || "off".equals(new LauncherPreferences(context).homeTabSide())) return false;
        return send(context, new Intent().putExtra("cmd", "show"));
    }

    static void hideAppControls(android.content.Context context) {
        send(context, new Intent().putExtra("cmd", "hide"));
    }

    static boolean navigationBack(android.content.Context context) {
        return send(context, new Intent().putExtra("cmd", "back"));
    }

    static boolean launchPair(android.content.Context context, ComponentName primary, ComponentName secondary) {
        if (primary == null || secondary == null) return false;
        return send(context, new Intent().putExtra("cmd", "launch").putExtra("primary", primary).putExtra("secondary", secondary));
    }

    static boolean exitPair(android.content.Context context, ComponentName primary) {
        if (primary == null || !ServicesState.prefs(context).getBoolean("split_pair", false)) return false;
        return send(context, new Intent().putExtra("cmd", "exit").putExtra("primary", primary));
    }

    /** Service side: one command from the launcher. */
    private void handle(Intent intent) {
        String command = intent.getStringExtra("cmd");
        if ("show".equals(command)) showAppControlsInternal();
        else if ("hide".equals(command)) hideAppControlsInternal();
        else if ("back".equals(command)) performGlobalAction(GLOBAL_ACTION_BACK);
        else if ("launch".equals(command)) startPair(intent.getParcelableExtra("primary"), intent.getParcelableExtra("secondary"));
        else if ("exit".equals(command)) endPair(intent.getParcelableExtra("primary"));
    }

    private void startPair(final ComponentName primary, final ComponentName secondary) {
        if (primary == null || secondary == null) return;
        launch(primary, false);
        handler.postDelayed(() -> {
            boolean entered = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
            setPair(entered);
            if (!entered) Toast.makeText(this, "This firmware did not enter split screen", Toast.LENGTH_SHORT).show();
        }, 1600L);
        handler.postDelayed(() -> launch(secondary, true), 2500L);
    }

    private void endPair(final ComponentName primary) {
        if (!pairActive || primary == null) return;
        // Bring the existing pair forward before toggling native split mode off.
        launch(primary, false);
        handler.postDelayed(() -> {
            boolean exited = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
            setPair(false);
            if (!exited) Toast.makeText(this, "This firmware did not close split screen", Toast.LENGTH_SHORT).show();
        }, 900L);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                performGlobalAction(GLOBAL_ACTION_HOME);
            }
        }, 1600L);
    }

    private void launch(ComponentName component, boolean adjacent) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (adjacent) {
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        }
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            Toast.makeText(this, "Unable to open " + component.getPackageName(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private static final int NAV_INK = 0xffe9edf1, NAV_ACCENT = 0xffc7b18c, NAV_PANEL = 0xe0141a21;

    /** The Back and Home rail stays on the screen edge for as long as another app is in front. */
    private void showAppControlsInternal() {
        if (windowManager == null || appControls != null) return;
        showRail();
    }

    private boolean onLeft() { return "left".equals(ServicesState.prefs(this).getString("nav_side", "right")); }

    private void showRail() {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setColor(NAV_PANEL);
        background.setStroke(dp(1), 0x40c7b18c);
        background.setCornerRadius(dp(24));
        rail.setBackground(background);
        rail.setPadding(dp(4), dp(4), dp(4), dp(4));
        rail.addView(railButton(R.drawable.ic_bar_back, "Back", NAV_INK, view -> {
            if (!performGlobalAction(GLOBAL_ACTION_BACK)) Toast.makeText(this, "Back is unavailable", Toast.LENGTH_SHORT).show();
        }), new LinearLayout.LayoutParams(dp(80), dp(80)));
        View divider = new View(this);
        divider.setBackgroundColor(0x33ffffff);
        rail.addView(divider, new LinearLayout.LayoutParams(dp(52), dp(1)));
        rail.addView(railButton(R.drawable.ic_bar_home, "Home", NAV_ACCENT, view -> {
            performGlobalAction(GLOBAL_ACTION_HOME);
            hideAppControlsInternal();
        }), new LinearLayout.LayoutParams(dp(80), dp(80)));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(dp(92), dp(171), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = (onLeft() ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL;
        params.x = dp(6);
        try {
            windowManager.addView(rail, params);
            appControls = rail;
        } catch (RuntimeException error) {
            appControls = null;
        }
    }

    private View railButton(int icon, String label, int color, View.OnClickListener click) {
        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER);
        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setImageTintList(ColorStateList.valueOf(color));
        button.addView(image, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(color);
        text.setTextSize(13);
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, dp(3), 0, 0);
        button.addView(text);
        StateListDrawable pressed = new StateListDrawable();
        GradientDrawable hot = new GradientDrawable();
        hot.setColor(0x40c7b18c);
        hot.setCornerRadius(dp(20));
        pressed.addState(new int[]{android.R.attr.state_pressed}, hot);
        button.setBackground(pressed);
        button.setContentDescription(label);
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(click);
        return button;
    }

    private void hideAppControlsInternal() {
        if (appControls == null || windowManager == null) {
            appControls = null;
            return;
        }
        try {
            windowManager.removeView(appControls);
        } catch (RuntimeException ignoredFailure) {
            // The system can remove accessibility overlays during a service restart.
        }
        appControls = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
