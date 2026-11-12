package com.softiva.oabl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * User-started navigation overlay. It leaves the map app in control of the
 * screen and adds only compact clock, speed, and media surfaces.
 */
public final class DriveOverlayService extends Service {
    private static final String CHANNEL_ID = "navigation_overlay";
    private static final int NOTIFICATION_ID = 71;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View clockCard;
    private View speedCard;
    private View homeCard;
    private View mediaCard;
    private View navigationCard;
    private ImageView navigationIcon;
    private TextView navigationDistance;
    private TextView navigationInstruction;
    private TextView navigationTrip;
    private PendingIntent navigationOpen;
    private TextView speedValue;
    private TextView speedStatus;
    private ImageView mediaArtwork;
    private TextView mediaTitle;
    private TextView mediaSubtitle;
    private TextView playPause;
    private MediaControllerClient mediaClient;
    private SpeedTracker speedTracker;
    private int gpsKph;
    private boolean gpsReliable;
    private long gpsSpeedAt;
    private long foregroundCheckedAt;
    private final Runnable carChanged = () -> {
        renderSpeed();
        // The link reports every sample; its foreground type changes only when the link is turned on or off.
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - foregroundCheckedAt >= UiRefreshPolicy.FOREGROUND_RECHECK_MS) {
            foregroundCheckedAt = now;
            refreshForegroundOwnership();
        }
    };
    private LauncherTheme.Palette palette;
    private boolean visualEffects;
    private LinearLayout overlayRoot;
    private LinearLayout panel;
    private WindowManager.LayoutParams overlayParams;
    private boolean expanded;
    private boolean mediaAvailable;
    private boolean navigationAvailable;
    private boolean navigationReceiverRegistered;
    private boolean rightEdge = true;
    private float verticalFraction = .45f;
    private AutoThemeWatcher themeWatcher;
    private int foregroundTypes = -1;
    private static java.lang.ref.WeakReference<DriveOverlayService> current = new java.lang.ref.WeakReference<>(null);
    static boolean isActive() { return current.get() != null; }
    private final Runnable collapse = () -> setExpanded(false);
    private long lastMediaSafetyAt;
    private final Runnable mediaChanged = () -> handler.post(() -> {
        renderMedia();
        renderNavigation();
    });

    private final Runnable mediaRefresh = new Runnable() {
        @Override
        public void run() {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastMediaSafetyAt >= UiRefreshPolicy.MEDIA_SAFETY_MS) {
                lastMediaSafetyAt = now;
                renderMedia();
                renderNavigation();
            }
            renderSpeed();
            if (BoxThermal.update(DriveOverlayService.this)) refreshOverlayTheme();
            handler.postDelayed(this, UiRefreshPolicy.overlayDelay(BoxThermal.throttled));
        }
    };

    private final BroadcastReceiver navigationChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            MediaAccessService.accept(intent);
            renderNavigation();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // The navigation panel has its own Home control; never show both.
        HomeTab.hide(this);
        createNotificationChannel();
        if (!refreshForegroundOwnership()) { stopSelf(); return; }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        LauncherPreferences preferences = new LauncherPreferences(this);
        palette = LauncherTheme.resolve(this, preferences);
        visualEffects = preferences.visualEffects();
        mediaClient = new MediaControllerClient(this);
        mediaClient.setChangeListener(mediaChanged);
        speedTracker = new SpeedTracker(this, new SpeedTracker.Listener() {
            @Override public void onPosition(android.location.Location location, boolean reliable) {
                DashboardData.get(DriveOverlayService.this).position(location, reliable);
            }
            @Override public void onSatellites(int used, int seen) {
                DashboardData.get(DriveOverlayService.this).satellitesUsed = used;
                DashboardData.get(DriveOverlayService.this).satellitesSeen = seen;
            }
            @Override
            public void onSpeed(int kilometersPerHour, boolean reliable) {
                gpsKph = kilometersPerHour; gpsReliable = reliable;
                gpsSpeedAt = android.os.SystemClock.elapsedRealtime();
                if (!EnergyClient.link(DriveOverlayService.this).expectsCarBattery())
                    EnergyJournal.get(DriveOverlayService.this).observeSpeed(kilometersPerHour, reliable);
                renderSpeed();
            }
        });
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        current = new java.lang.ref.WeakReference<>(this);
        ServicesState.prefs(this).edit().putBoolean("drive_overlay", true).apply();
        SplitScreenService.hideAppControls(DriveOverlayService.this);
        clockCard = buildClockCard();
        speedCard = buildSpeedCard();
        homeCard = buildHomeCard();
        navigationCard = buildNavigationCard();
        mediaCard = buildMediaCard();
        addOverlayViews();
        themeWatcher = new AutoThemeWatcher(this, this::refreshOverlayTheme);
        themeWatcher.start();
        if (hasLocationPermission()) speedTracker.start();
        EnergyClient.link(this).observe(carChanged);
        ServicesState.register(this, navigationChanged,
                new IntentFilter(MediaAccessService.ACTION_MEDIA_CHANGED));
        navigationReceiverRegistered = true;
        MediaAccessService.requestUpdate(this);
        renderSpeed();
        handler.post(mediaRefresh);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        refreshForegroundOwnership();
        return START_NOT_STICKY;
    }

    /**
     * Refreshes the claim without dropping a resource still in use. The overlay always owns
     * location when GPS permission is available and additionally owns connected-device while the
     * OABL car link is enabled. This mirrors actual ownership on the Android 13 AI box.
     */
    private synchronized boolean refreshForegroundOwnership() {
        boolean location = hasLocationPermission();
        boolean connected = false;
        try { connected = EnergyClient.link(this).expectsCarBattery(); }
        catch (RuntimeException energyRestarting) { /* retain the last accepted connected claim */
            connected = foregroundTypes >= 0
                    && (foregroundTypes & ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) != 0;
        }
        int types = OverlayForegroundPolicy.types(location, connected);
        if (types == foregroundTypes) return true;
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 29 && types != 0) startForeground(NOTIFICATION_ID, notification, types);
            else startForeground(NOTIFICATION_ID, notification);
            foregroundTypes = types;
            return true;
        } catch (RuntimeException refused) {
            android.util.Log.w("OABL", "Driving overlay foreground ownership refused", refused);
            return false;
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onDestroy() {
        EnergyClient.link(this).unobserve(carChanged);
        if (navigationReceiverRegistered) {
            try { unregisterReceiver(navigationChanged); } catch (RuntimeException ignored) { }
            navigationReceiverRegistered = false;
        }
        if (themeWatcher != null) themeWatcher.stop();
        handler.removeCallbacksAndMessages(null);
        carFetch.shutdownNow();
        if (mediaClient != null) { mediaClient.setChangeListener(null); mediaClient.release(); }
        if (speedTracker != null) {
            speedTracker.stop();
        }
        removeView(clockCard);
        removeView(speedCard);
        removeView(homeCard);
        removeView(navigationCard);
        removeView(mediaCard);
        removeView(overlayRoot);
        if (current.get() == this) { current.clear(); ServicesState.prefs(this).edit().putBoolean("drive_overlay", false).apply(); }
        super.onDestroy();
    }

    // The car values come from :energy over IPC, so they are fetched on this worker and the
    // overlay draws from the last answer; it never waits on the other process.
    private final java.util.concurrent.ExecutorService carFetch = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean carFetching = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean carExpected;
    private volatile double carKph = Double.NaN;
    private volatile long carFetchedAt;

    private void refreshCar() {
        if (!carFetching.compareAndSet(false, true)) return;
        carFetch.execute(() -> {
            try {
                CarLinkController link = EnergyClient.link(this);
                boolean expected = link.expectsCarBattery();
                // The speedometer's figure when Link has one, else the real car speed.
                double kph = expected ? link.liveSpeed() : Double.NaN;
                carExpected = expected; carKph = kph; carFetchedAt = android.os.SystemClock.elapsedRealtime();
            } catch (RuntimeException unavailable) { /* :energy restarting; the next change retries */ }
            finally { carFetching.set(false); }
            handler.post(this::renderSpeedNow);
        });
    }

    private void renderSpeed() {
        refreshCar();
        renderSpeedNow();
    }

    private void renderSpeedNow() {
        if (speedValue == null || speedStatus == null) return;
        // A car answer older than the car-speed freshness window counts as no reading.
        double car = android.os.SystemClock.elapsedRealtime() - carFetchedAt <= CarLinkController.SPEED_FRESH_MS ? carKph : Double.NaN;
        SpeedReading speed = SpeedReading.select(carExpected, car, gpsKph,
                gpsReliable && android.os.SystemClock.elapsedRealtime() - gpsSpeedAt <= 3000
                        && DashboardData.get(this).freshFix());
        speedValue.setText(speed.known() ? Integer.toString(speed.kph) : "—");
        speedStatus.setText(speed.fromCar ? (speed.known() ? "km/h · CAR" : "car · waiting")
                : speed.known() ? "km/h · GPS" : "GPS · waiting");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private View buildClockCard() {
        LinearLayout clockBlock = new LinearLayout(this);
        clockBlock.setOrientation(LinearLayout.VERTICAL);
        clockBlock.setGravity(Gravity.CENTER_VERTICAL);
        clockBlock.setPadding(dp(18), dp(5), dp(18), dp(5));
        clockBlock.setBackground(LauncherTheme.overlayGlass(this, palette));
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(palette.ink);
        clock.setTextSize(38);
        clock.setGravity(Gravity.CENTER_VERTICAL);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEE, d MMM");
        date.setFormat24Hour("EEE, d MMM");
        date.setTextColor(palette.muted);
        date.setTextSize(12);
        clockBlock.addView(clock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f));
        clockBlock.addView(date);
        return clockBlock;
    }

    private View buildSpeedCard() {
        LinearLayout speed = new LinearLayout(this);
        speed.setOrientation(LinearLayout.VERTICAL);
        speed.setGravity(Gravity.CENTER);
        speed.setPadding(dp(8), dp(4), dp(8), dp(4));
        speed.setBackground(LauncherTheme.overlayGlass(this, palette));
        speedValue = new TextView(this);
        speedValue.setText("—");
        speedValue.setTextColor(palette.ink);
        speedValue.setTextSize(30);
        speedValue.setGravity(Gravity.CENTER);
        speedStatus = new TextView(this);
        speedStatus.setText("km/h • GPS standby");
        speedStatus.setTextColor(palette.accent);
        speedStatus.setTextSize(11);
        speedStatus.setGravity(Gravity.CENTER);
        speed.addView(speedValue);
        speed.addView(speedStatus);
        return speed;
    }

    private View buildHomeCard() {
        TextView home = actionButton("HOME");
        home.setBackground(LauncherTheme.overlayGlass(this, palette));
        home.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopSelf();
                Intent homeIntent = new Intent(DriveOverlayService.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(homeIntent);
            }
        });
        return home;
    }

    /** Turn-by-turn details already collected from the navigation app's ongoing notification. */
    private View buildNavigationCard() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(LauncherTheme.overlayGlass(this, palette));

        navigationIcon = new ImageView(this);
        navigationIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        navigationIcon.setImageResource(R.drawable.ic_nav_turn);
        row.addView(navigationIcon, new LinearLayout.LayoutParams(dp(50), dp(50)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(10), 0, 0, 0);
        navigationDistance = overlayText(19, palette.accent, 1);
        navigationInstruction = overlayText(14, palette.ink, 2);
        navigationTrip = overlayText(11, palette.muted, 1);
        words.addView(navigationDistance);
        words.addView(navigationInstruction);
        words.addView(navigationTrip);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            if (navigationOpen == null) return;
            try { navigationOpen.send(); } catch (PendingIntent.CanceledException ignored) { }
            setExpanded(false);
        });
        return row;
    }

    private TextView overlayText(float size, int color, int lines) {
        TextView text = new TextView(this);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setMaxLines(lines);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return text;
    }

    private View buildMediaCard() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(LauncherTheme.overlayGlass(this, palette));

        mediaArtwork = new ImageView(this);
        mediaArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mediaArtwork.setBackground(LauncherTheme.tile(this, palette));
        row.addView(mediaArtwork, new LinearLayout.LayoutParams(dp(62), dp(62)));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setGravity(Gravity.CENTER_VERTICAL);
        details.setPadding(dp(12), 0, 0, 0);
        mediaTitle = new TextView(this);
        mediaTitle.setText("Nothing playing");
        mediaTitle.setTextColor(palette.ink);
        mediaTitle.setTextSize(15);
        mediaTitle.setMaxLines(1);
        mediaTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mediaSubtitle = new TextView(this);
        mediaSubtitle.setText("Media controls");
        mediaSubtitle.setTextColor(palette.muted);
        mediaSubtitle.setTextSize(12);
        mediaSubtitle.setMaxLines(1);
        mediaSubtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(mediaTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        details.addView(mediaSubtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(0, dp(6), 0, 0);
        TextView previous = roundButton("|◀");
        playPause = roundButton("▶");
        TextView next = roundButton("▶|");
        controls.addView(previous);
        controls.addView(playPause);
        controls.addView(next);
        details.addView(controls);
        row.addView(details, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        previous.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { mediaClient.previous(); }
        });
        playPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { mediaClient.playPause(); }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { mediaClient.next(); }
        });
        return row;
    }

    private TextView roundButton(String text) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(palette.ink);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setBackground(LauncherTheme.circle(this, palette));
        installMotionFeedback(button);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(38), dp(38));
        params.rightMargin = dp(7);
        button.setLayoutParams(params);
        return button;
    }

    private TextView actionButton(String text) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(palette.ink);
        button.setTextSize(12);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackground(LauncherTheme.tile(this, palette));
        installMotionFeedback(button);
        return button;
    }

    private void installMotionFeedback(final View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View target, MotionEvent event) {
                if (!visualEffects) {
                    return false;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    target.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90L).start();
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    target.animate().scaleX(1f).scaleY(1f).setDuration(170L)
                            .setInterpolator(new DecelerateInterpolator()).start();
                }
                return false;
            }
        });
    }

    private void animateOverlayViews() {
        if (!visualEffects) {
            return;
        }
        View[] views = {clockCard, speedCard, homeCard, mediaCard};
        for (int i = 0; i < views.length; i++) {
            View view = views[i];
            view.setAlpha(0f);
            view.setTranslationY(dp(i == 3 ? 18 : -12));
            view.setScaleX(0.96f);
            view.setScaleY(0.96f);
            view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(i * 70L).setDuration(360L)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private void addOverlayViews() {
        if (windowManager == null) {
            stopSelf();
            return;
        }
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        android.content.SharedPreferences placement = getSharedPreferences("drive_overlay", 0);
        rightEdge = placement.getBoolean("right", true);
        verticalFraction = Math.max(0, Math.min(1, placement.getFloat("vertical_fraction", .45f)));
        overlayRoot = new LinearLayout(this);
        overlayRoot.setOrientation(LinearLayout.HORIZONTAL);
        overlayRoot.setGravity(Gravity.CENTER_VERTICAL);
        overlayRoot.setContentDescription("Navigation floating controls");
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));
        panel.setBackground(LauncherTheme.overlayGlass(this, palette));
        clockCard.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        ((TextClock)((LinearLayout)clockCard).getChildAt(0)).setTextSize(24);
        panel.addView(clockCard, new LinearLayout.LayoutParams(-1, dp(58)));
        navigationCard.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        panel.addView(navigationCard, new LinearLayout.LayoutParams(-1, dp(94)));
        LinearLayout actions = new LinearLayout(this);
        TextView back = actionButton("BACK");
        back.setContentDescription("Back in navigation app");
        back.setOnClickListener(v -> {
            if (!SplitScreenService.navigationBack(DriveOverlayService.this)) android.widget.Toast.makeText(this,
                    "Enable App controls in launcher settings for Back; Android's system Back still works", android.widget.Toast.LENGTH_LONG).show();
            setExpanded(false);
        });
        TextView close = actionButton("HIDE");
        close.setContentDescription("Collapse navigation controls");
        close.setOnClickListener(v -> setExpanded(false));
        actions.addView(back, new LinearLayout.LayoutParams(0, dp(76), 1));
        actions.addView(homeCard, new LinearLayout.LayoutParams(0, dp(76), 1));
        actions.addView(close, new LinearLayout.LayoutParams(0, dp(76), 1));
        panel.addView(actions);
        mediaArtwork.setVisibility(View.GONE);
        LinearLayout mediaDetails = (LinearLayout)((LinearLayout)mediaCard).getChildAt(1);
        LinearLayout controls = (LinearLayout)mediaDetails.getChildAt(2);
        for (int i = 0; i < controls.getChildCount(); i++) {
            View button = controls.getChildAt(i);
            button.getLayoutParams().width = dp(76);
            button.getLayoutParams().height = dp(76);
        }
        mediaCard.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        panel.addView(mediaCard, new LinearLayout.LayoutParams(-1, dp(144)));
        TextClock miniClock = new TextClock(this);
        miniClock.setFormat12Hour("h:mm"); miniClock.setFormat24Hour("HH:mm");
        miniClock.setTextSize(12); miniClock.setTextColor(palette.muted); miniClock.setGravity(Gravity.CENTER);
        ((LinearLayout)speedCard).addView(miniClock, 0);
        speedValue.setTextSize(24); speedStatus.setTextSize(9);
        speedStatus.setText("km/h · wait");
        TextView grip = new TextView(this);
        grip.setText("•••"); grip.setGravity(Gravity.CENTER); grip.setTextColor(palette.accent);
        ((LinearLayout)speedCard).addView(grip);
        speedCard.setContentDescription("Speed and clock. Tap for controls. Drag to move.");
        installHandleDrag();
        overlayParams = new WindowManager.LayoutParams(dp(76), dp(108), type,
                flags | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.LEFT;
        overlayRoot.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) setExpanded(false);
            return false;
        });
        rebuildOverlayChildren();
        positionOverlay();
        windowManager.addView(overlayRoot, overlayParams);
    }

    private void rebuildOverlayChildren() {
        overlayRoot.removeAllViews();
        panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (rightEdge) overlayRoot.addView(panel, new LinearLayout.LayoutParams(dp(300), -1));
        overlayRoot.addView(speedCard, new LinearLayout.LayoutParams(dp(76), dp(108)));
        if (!rightEdge) overlayRoot.addView(panel, new LinearLayout.LayoutParams(dp(300), -1));
    }
    private void refreshOverlayTheme() {
        LauncherPreferences preferences = new LauncherPreferences(this);
        visualEffects = preferences.visualEffects();
        LauncherTheme.Palette next = LauncherTheme.resolve(this, preferences);
        if (palette.light == next.light && palette.accent == next.accent) return;
        LauncherTheme.Palette previous = palette;
        palette = next;
        recolorOverlay(overlayRoot, previous);
        speedCard.setBackground(LauncherTheme.overlayGlass(this, palette));
        panel.setBackground(LauncherTheme.overlayGlass(this, palette));
        homeCard.setBackground(LauncherTheme.overlayGlass(this, palette));
        mediaArtwork.setBackground(LauncherTheme.tile(this, palette));
    }
    private void recolorOverlay(View view, LauncherTheme.Palette previous) {
        if (view instanceof TextView) {
            TextView text = (TextView)view;
            int color = text.getCurrentTextColor();
            if (color == previous.ink) text.setTextColor(palette.ink);
            else if (color == previous.muted) text.setTextColor(palette.muted);
            else if (color == previous.accent) text.setTextColor(palette.accent);
            if (view.isClickable()) view.setBackground(LauncherTheme.dockItem(this, palette));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) recolorOverlay(group.getChildAt(i), previous);
        }
    }

    private void positionOverlay() {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        overlayParams.width = Math.min(dp(expanded ? 376 : 76), width - dp(16));
        int expandedHeight = 166 + (navigationAvailable ? 94 : 0) + (mediaAvailable ? 144 : 0);
        overlayParams.height = dp(expanded ? expandedHeight : 108);
        overlayParams.x = rightEdge ? width-overlayParams.width-dp(8) : dp(8);
        int margin = dp(32);
        overlayParams.y = margin + Math.round(verticalFraction * Math.max(0, height-overlayParams.height-2*margin));
    }

    private void setExpanded(boolean value) {
        if (overlayRoot == null || overlayParams == null) return;
        expanded = value;
        handler.removeCallbacks(collapse);
        if ((overlayRoot.getChildAt(0) == panel) != rightEdge) rebuildOverlayChildren();
        else panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        positionOverlay();
        try { windowManager.updateViewLayout(overlayRoot, overlayParams); }
        catch (RuntimeException ignored) { stopSelf(); }
        if (value) handler.postDelayed(collapse, 8000);
    }

    private void installHandleDrag() {
        speedCard.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int startX, startY; boolean dragged;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX(); downY = event.getRawY(); startX = overlayParams.x; startY = overlayParams.y; dragged = false;
                    handler.removeCallbacks(collapse); return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX()-downX, dy = event.getRawY()-downY;
                    if (Math.hypot(dx, dy) > android.view.ViewConfiguration.get(DriveOverlayService.this).getScaledTouchSlop()) dragged = true;
                    if (dragged) {
                        overlayParams.x = Math.max(0, Math.min(getResources().getDisplayMetrics().widthPixels-overlayParams.width, startX+Math.round(dx)));
                        overlayParams.y = Math.max(dp(32), Math.min(getResources().getDisplayMetrics().heightPixels-overlayParams.height-dp(32), startY+Math.round(dy)));
                        windowManager.updateViewLayout(overlayRoot, overlayParams);
                    }
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    if (dragged) {
                        dragged = false;
                        rightEdge = overlayParams.x + overlayParams.width/2 > getResources().getDisplayMetrics().widthPixels/2;
                        int available = getResources().getDisplayMetrics().heightPixels-overlayParams.height-dp(64);
                        verticalFraction = available > 0 ? Math.max(0, Math.min(1, (overlayParams.y-dp(32))/(float)available)) : 0;
                        getSharedPreferences("drive_overlay", 0).edit().putBoolean("right", rightEdge).putFloat("vertical_fraction", verticalFraction).apply();
                        setExpanded(false);
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
                    else if (expanded) handler.postDelayed(collapse, 8000);
                    return true;
                }
                return false;
            }
        });
        speedCard.setOnClickListener(v -> setExpanded(!expanded));
    }

    private void renderMedia() {
        if (mediaClient == null) {
            return;
        }
        MediaControllerClient.Snapshot snapshot = mediaClient.read();
        boolean available = snapshot != null;
        mediaCard.setVisibility(available ? View.VISIBLE : View.GONE);
        if (mediaAvailable != available) { mediaAvailable = available; setExpanded(expanded); }
        if (snapshot == null) {
            mediaTitle.setText("Nothing playing");
            mediaSubtitle.setText("Start audio to show controls here");
            mediaArtwork.setImageResource(R.drawable.ic_stat_drive);
            playPause.setText("▶");
            return;
        }
        mediaTitle.setText(snapshot.title);
        mediaSubtitle.setText(snapshot.subtitle);
        playPause.setText(snapshot.playing ? "❚❚" : "▶");
        if (snapshot.artwork != null) {
            mediaArtwork.setImageBitmap(snapshot.artwork);
        } else {
            try {
                Drawable icon = getPackageManager().getApplicationIcon(snapshot.packageName);
                mediaArtwork.setImageDrawable(icon);
            } catch (Exception error) {
                mediaArtwork.setImageResource(R.drawable.ic_stat_drive);
            }
        }
    }

    private void renderNavigation() {
        if (navigationCard == null) return;
        MediaAccessService.NavigationSnapshot snapshot = MediaAccessService.navigationSnapshot();
        boolean available = snapshot != null && snapshot.active && snapshot.text != null;
        navigationCard.setVisibility(available ? View.VISIBLE : View.GONE);
        if (navigationAvailable != available) {
            navigationAvailable = available;
            setExpanded(expanded);
        }
        if (!available) {
            navigationOpen = null;
            return;
        }

        NavigationGuidance guidance = NavigationGuidance.read(snapshot.text);
        navigationDistance.setText(guidance.distance == null ? snapshot.text.title : guidance.distance);
        String instruction = guidance.distance == null ? snapshot.text.detail : guidance.instruction;
        navigationInstruction.setText(instruction == null ? snapshot.appLabel : instruction);
        String trip = guidance.eta == null ? guidance.remaining
                : guidance.remaining == null ? "ETA " + guidance.eta
                : guidance.remaining + " · ETA " + guidance.eta;
        if (guidance.trafficDelay != null && (trip == null || !trip.toLowerCase(java.util.Locale.ROOT).contains("delay")))
            trip = trip == null ? guidance.trafficDelay : trip + " · " + guidance.trafficDelay;
        navigationTrip.setText(trip == null ? snapshot.appLabel : trip);
        navigationOpen = snapshot.open;
        try {
            Drawable icon = guidance.maneuver == NavigationGuidance.Maneuver.UNKNOWN
                    ? (snapshot.icon == null ? null : snapshot.icon.loadDrawable(this))
                    : new NavigationManeuverDrawable(guidance.maneuver, guidance.roundaboutExit, palette.accent);
            if (icon != null) navigationIcon.setImageDrawable(icon);
            else navigationIcon.setImageResource(guidance.arriving ? R.drawable.ic_nav_arrived : R.drawable.ic_nav_turn);
        } catch (RuntimeException malformedIcon) {
            navigationIcon.setImageResource(guidance.arriving ? R.drawable.ic_nav_arrived : R.drawable.ic_nav_turn);
        }
        navigationCard.setContentDescription("Navigation: " + navigationDistance.getText()
                + (instruction == null ? "" : ". " + instruction)
                + (guidance.eta == null ? "" : ". Arriving " + guidance.eta));
    }

    private void removeView(View view) {
        if (windowManager != null && view != null) {
            try {
                windowManager.removeView(view);
            } catch (RuntimeException ignored) {
                // The display or permission may have disappeared first.
            }
        }
    }

    private Notification buildNotification() {
        Intent home = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingHome = PendingIntent.getActivity(this, 0, home,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_stat_drive)
                .setContentTitle("Navigation display is active")
                .setContentText("Tap the floating handle for controls; drag it to a clear edge")
                .setContentIntent(pendingHome)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE);
        if (Build.VERSION.SDK_INT >= 28) {
            builder.setCategory(Notification.CATEGORY_NAVIGATION);
        }
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Navigation display", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the navigation clock, speed, and media overlay active");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
