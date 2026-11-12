package com.softiva.oabl;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.role.RoleManager;
import android.appwidget.AppWidgetHost;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ScrollView;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    static final String EXTRA_RELOAD_WALLPAPER =
            "com.softiva.oabl.extra.RELOAD_WALLPAPER";
    private static final int APP_WIDGET_HOST_ID = 4107;
    private static final int REQUEST_WALLPAPER = 200;
    private static final int REQUEST_SCREENSAVER_IMAGE = 201;
    private static final int REQUEST_BACKUP = 202;
    private static final int REQUEST_RESTORE = 203;
    // Was 201, the same as REQUEST_SCREENSAVER_IMAGE: a picked screensaver photo was taken for this.
    private static final int REQUEST_HOME_ROLE = 204;
    private static final int REQUEST_PLACE = 206;
    private static final int REQUEST_LOCATION = 500;
    private static final int REQUEST_BLUETOOTH = 501;
    private static final int REQUEST_PHONE = 502;
    private static final int REQUEST_NOTIFICATIONS = 503;
    private static final int LEGACY_WIDGET_SLOTS = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private LauncherPreferences preferences;
    private InstalledAppsRepository appRepository;
    private List<AppEntry> apps = new ArrayList<>();
    private MediaControllerClient mediaClient;
    private SpeedTracker speedTracker;
    private TravelStatusMonitor travelStatusMonitor;
    private LauncherTheme.Palette palette;
    private AutoThemeWatcher themeWatcher;

    private ImageView wallpaper;
    private ImageView mediaArtwork;
    private TextView networkStatus;
    private TextView mediaTitle;
    private TextView mediaSubtitle;
    private ImageView mediaPlayPause;
    private MediaProgressView mediaProgress;
    private TextView mediaSource;
    private String renderedArtKey;
    private String renderedSourcePackage;
    private boolean mediaPlaying;
    private android.graphics.Bitmap mediaArt;
    private int renderedPlayIcon;
    private boolean navigationGuiding;
    private boolean compactLayout;
    private HomeLayout homeLayout;
    private boolean dashboardLinkConnected;
    private TextView speedValue;
    private TextView speedStatus;
    private WeatherIconView weatherIcon;
    private ScreensaverView screensaver;
    private boolean drivingMode;
    private long lastFastAt;
    private View startupNotice;
    private long lastInteraction;
    private View navigationHintCard;
    private ImageView navigationHintIcon;
    private TextView navigationHintTitle;
    private TextView navigationHintDetail;
    private TextView navigationHintTrip;
    private MediaAccessService.NavigationSnapshot activeNavigation =
            MediaAccessService.NavigationSnapshot.NONE;
    private String renderedNavigationKey;
    private NavigationGuidance lastGuidance;
    private String turnKey;
    private double turnStartMeters;
    private boolean turnNear;
    private android.animation.ValueAnimator turnPulse;
    private boolean arrivalChargeNeeded;
    private long arrivalUntil;
    private String arrivalTitle;
    private String arrivalDetail;
    private double routeStartKilometers;
    private double routeStartMinutes;
    private String renderedChipsKey;
    private String pendingDestination;
    private LinearLayout dock;
    private String activeMediaPackage;
    private boolean mediaReceiverRegistered;
    private boolean foreground;
    private boolean lastOwnPlayback;
    private final Runnable ownPlaybackChanged=()->{
        boolean playing=MusicClient.get(this).isPlaying();
        if(playing!=lastOwnPlayback){lastOwnPlayback=playing;if(foreground)renderMedia();}
    };
    private boolean pendingNavigation;
    /** When the last speed reading arrived; the gauge drops to 0 after 3 s without one. */
    private long lastSpeedAt;
    private boolean entranceAnimated;
    private String lastMediaSignature;
    private String renderedCalendarKey;
    private CarEnergyRepository.Item pendingChargeReview;
    private long lastMediaSafetyAt;
    private long lastStatusRefreshAt;
    private long lastWeatherRefreshAt;

    /** A player reported a change: redraw the card now instead of at the next 1.5 s refresh. */
    private final Runnable mediaChangedSoon = () -> { if (foreground) renderMedia(); };
    private final Runnable mediaChangeListener = () -> {
        // Metadata and state usually arrive together; one redraw covers the pair.
        handler.removeCallbacks(mediaChangedSoon);
        handler.postDelayed(mediaChangedSoon, 80L);
    };

    private final Runnable mediaRefresh = new Runnable() {
        @Override
        public void run() {
            long now = android.os.SystemClock.elapsedRealtime();
            // Media and navigation publish change callbacks. These bounded checks cover a missed
            // vendor callback without repainting every surface on every dashboard tick.
            if (now - lastMediaSafetyAt >= UiRefreshPolicy.MEDIA_SAFETY_MS) {
                lastMediaSafetyAt = now;
                renderMedia();
            }
            renderNavigationHints();
            renderDashboardData();
            checkScreensaver();
            if (now - lastStatusRefreshAt >= UiRefreshPolicy.STATUS_MS) {
                lastStatusRefreshAt = now;
                renderTravelStatus();
                if (BoxThermal.update(MainActivity.this)) {
                    if (BoxThermal.throttled) cancelAnimations(findViewById(R.id.root));
                    applyAppearance(false);
                }
            }
            if (now - lastWeatherRefreshAt >= UiRefreshPolicy.WEATHER_MS) {
                lastWeatherRefreshAt = now;
                refreshWeatherSurfaces();
            }
            // Driving mode ends after 10 s below the threshold (or without fresh speed).
            if (drivingMode && now - lastFastAt > 10_000) setDrivingMode(false);
            if (foreground) {
                handler.postDelayed(this, UiRefreshPolicy.homeDelay(drivingMode, activeNavigation.active));
            }
        }
    };

    private final BroadcastReceiver mediaChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            MediaAccessService.accept(intent);
            renderMedia();
            renderNavigationHints();
        }
    };

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        try {
            getLayoutInflater().setFactory2(new android.view.LayoutInflater.Factory2() {
                @Override public View onCreateView(View parent, String name, Context context, android.util.AttributeSet attrs) {
                    return onCreateView(name, context, attrs);
                }
                @Override public View onCreateView(String name, Context context, android.util.AttributeSet attrs) {
                    return "TextView".equals(name) ? new StableTextView(context, attrs) : null;
                }
            });
        } catch (IllegalStateException alreadySet) { /* plain TextViews; only redraw cost differs */ }
        setContentView(R.layout.activity_main);
        ImmersiveWindow.apply(getWindow(), findViewById(android.R.id.content));
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new OnBackInvokedCallback() {
                        @Override
                        public void onBackInvoked() {
                            // A HOME app remains home when there is no overlay to dismiss.
                        }
                    });
        }

        preferences = new LauncherPreferences(this);
        homePeriod = HomePeriod.fromIndex(preferences.homePeriod());
        LauncherTheme.loadWallpaperColor(this);
        appRepository = new InstalledAppsRepository(this);
        mediaClient = new MediaControllerClient(this);
        travelStatusMonitor = new TravelStatusMonitor(this);

        bindViews();
        removeLegacyWidgets();
        applyAppearance(false);
        themeWatcher = new AutoThemeWatcher(this, () -> applyAppearance(false));
        speedTracker = new SpeedTracker(this, new SpeedTracker.Listener() {
            @Override
            public void onSpeed(int kilometersPerHour, boolean reliable) {
                // Car speed while it is current; otherwise this GPS reading drives the gauge and trip.
                if (home != null && home.car && renderCarSpeed()) return;
                lastSpeedAt = android.os.SystemClock.elapsedRealtime();
                if (kilometersPerHour >= 5) hideScreensaver();
                int threshold = preferences.drivingModeKmh();
                if (reliable && threshold > 0 && kilometersPerHour >= threshold) {
                    lastFastAt = android.os.SystemClock.elapsedRealtime();
                    if (!drivingMode) setDrivingMode(true);
                }
                setSpeedStatus(null);
                SpeedGaugeView gauge = findViewById(R.id.speed_cluster);
                if (reliable) gauge.reading(kilometersPerHour, speedLimit(), palette);
                else gauge.unknown(speedLimit(), palette);
                if (reliable) overspeedChime(kilometersPerHour);
                EnergyJournal.get(MainActivity.this).observeSpeed(kilometersPerHour, reliable);
            }
            @Override public void onPosition(android.location.Location location, boolean reliable) {
                DashboardData.get(MainActivity.this).position(location, reliable);
                if ("auto".equals(preferences.speedLimitMode())) {
                    RoadSpeedLimit.get().position(MainActivity.this, location, reliable && location.hasSpeed() && location.getSpeed() > 2);
                }
            }
            @Override public void onSatellites(int used, int seen) {
                DashboardData.get(MainActivity.this).satellitesUsed = used;
                DashboardData.get(MainActivity.this).satellitesSeen = seen;
            }
        });
        applyCompactLayoutIfNeeded();
        bindActions();
        tidyDockOnce();
        reloadApps();
        loadWallpaper();
        renderWeatherCard();
        renderTravelStatus();

        IntentFilter filter = new IntentFilter(MediaAccessService.ACTION_MEDIA_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mediaChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mediaChangedReceiver, filter);
        }
        mediaReceiverRegistered = true;
        // The listener runs in the services process; ask it for the current state.
        MediaAccessService.requestUpdate(this);
        handler.post(new Runnable() {
            @Override public void run() { runEntranceAnimation(); }
        });
        handler.postDelayed(new Runnable() {
            @Override public void run() { offerHomeRoleOnFirstRun(); }
        }, 900L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_RELOAD_WALLPAPER, false)) {
            // A direct-boot launch can happen before credential storage and the
            // media document provider are ready. USER_UNLOCKED delivers another
            // launch to this singleTask activity; rebuild preferences and reload.
            preferences = new LauncherPreferences(this);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    applyAppearance(false);
                    loadWallpaper();
                }
            }, 300L);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        MapsTile.show();
        HomeTab.hide(this);
        lastInteraction = android.os.SystemClock.elapsedRealtime();
        hideScreensaver();
        maybeShowStartupNotice();
        runStartActions();
        DevicePerformance.apply(this, getWindow());
        SoundEqualizerService.start(this);
        SplitScreenService.hideAppControls(MainActivity.this);
        stopService(new Intent(this, DriveOverlayService.class));
        applyAppearance(false);
        foreground = true;
        // Maps can update while Home is paused or its process is being reclaimed.
        // Reacquire the listener's current notifications whenever Home returns.
        MediaAccessService.requestUpdate(this);
        // A journey of this boot whose GPS service stopped carries on; one from before a reboot waits for the driver.
        EnergyJournal.get(this).ensureRunning();
        EnergyJournal.get(this).observe(energyChanged);
        EnergyClient.link(this).observe(energyChanged);
        EnergyClient.link(this).apply();
        applyDriveModeTheme();
        renderedEnergyKey = null;
        renderEnergyCard();
        MusicClient.get(this).observe(ownPlaybackChanged);
        PlaylistImport.applyPending(this);
        themeWatcher.start();
        reloadApps();
        travelStatusMonitor.start();
        renderTravelStatus();
        lastStatusRefreshAt = android.os.SystemClock.elapsedRealtime();
        lastMediaSafetyAt = 0;
        lastWeatherRefreshAt = 0;
        handler.removeCallbacks(mediaRefresh);
        mediaClient.setChangeListener(mediaChangeListener);
        handler.post(mediaRefresh);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            speedTracker.start();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Maps' tile belongs to Home: over any other page it only covers what's there.
        MapsTile.hide();
        // LauncherApplication handles all OABL tasks, not just departures from Home.
    }

    @Override
    protected void onPause() {
        if (themeWatcher != null) themeWatcher.stop();
        foreground = false;
        EnergyJournal.get(this).unobserve(energyChanged);
        EnergyClient.link(this).unobserve(energyChanged);
        handler.removeCallbacks(queuedHomeRefresh);
        homeRefreshQueued = false;
        MusicClient.get(this).unobserve(ownPlaybackChanged);
        handler.removeCallbacks(mediaRefresh);
        handler.removeCallbacks(mediaChangedSoon);
        if (mediaClient != null) mediaClient.setChangeListener(null);
        speedTracker.stop();
        travelStatusMonitor.stop();
        super.onPause();
    }

    /** Home in the background (an app like YouTube in front) or low memory: give back caches. */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN || level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL) {
            MusicArt.trim();
            if (screensaver != null && screensaver.getVisibility() != View.VISIBLE) screensaver.release();
        }
    }

    @Override
    protected void onDestroy() {
        MapsTile.forgetPanels();
        if (chime != null) { chime.release(); chime = null; }
        if (mediaClient != null) mediaClient.release();
        if (mediaReceiverRegistered) {
            unregisterReceiver(mediaChangedReceiver);
            mediaReceiverRegistered = false;
        }
        if (speedTracker != null) {
            speedTracker.stop();
        }
        if (travelStatusMonitor != null) {
            travelStatusMonitor.stop();
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyCompactLayoutIfNeeded();
        renderedDockKey = null;
        renderDock();
        // Chips, weather and the battery card have window-width variants.
        renderWeatherCard();
        renderNavigationHints();
        renderedEnergyKey = null;
        renderEnergyCard();
        if (preferences != null && preferences.themeMode() == LauncherPreferences.THEME_AUTO) {
            applyAppearance(preferences.visualEffects());
        }
    }

    @Override
    @SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        // A HOME app should stay home. Android's system navigation still works.
    }

    private void bindViews() {
        wallpaper = findViewById(R.id.wallpaper);
        // Static surfaces live in GPU layers, so animated bits (spectrum, badges, progress) only
        // redraw themselves instead of the whole Home screen.
        wallpaper.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        for (int id : new int[]{R.id.calendar_card, R.id.telemetry_card, R.id.navigation_hint_card, R.id.trip_card, R.id.energy_card}) {
            View card = findViewById(id);
            if (card != null) card.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        mediaArtwork = findViewById(R.id.media_artwork);
        networkStatus = findViewById(R.id.network_status);
        mediaTitle = findViewById(R.id.media_title);
        mediaSubtitle = findViewById(R.id.media_subtitle);
        mediaPlayPause = findViewById(R.id.media_play_pause);
        mediaProgress = findViewById(R.id.media_progress);
        mediaSource = findViewById(R.id.media_source);
        speedValue = findViewById(R.id.speed_value);
        speedStatus = findViewById(R.id.launcher_tagline);
        screensaver = new ScreensaverView(this, () -> hideScreensaver());
        screensaver.setVisibility(View.GONE);
        ((FrameLayout) findViewById(R.id.root)).addView(screensaver,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Details moved behind the status icons and onto the weather card.
        int[] retired = {R.id.wifi_detail, R.id.gps_health, R.id.device_battery};
        for (int id : retired) findViewById(id).setVisibility(View.GONE);
        weatherIcon = new WeatherIconView(this);
        ((FrameLayout) findViewById(R.id.weather_icon_host)).addView(weatherIcon,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        navigationHintCard = findViewById(R.id.navigation_hint_card);
        navigationHintIcon = findViewById(R.id.navigation_hint_icon);
        navigationHintTitle = findViewById(R.id.navigation_hint_title);
        navigationHintDetail = findViewById(R.id.navigation_hint_detail);
        navigationHintTrip = findViewById(R.id.navigation_hint_trip);
        dock = findViewById(R.id.dock);
    }

    private void applyCompactLayoutIfNeeded() {
        if (homeLayout == null) homeLayout = new HomeLayout(this);
        boolean driving = drivingMode, guiding = navigationGuiding;
        HomeDashboardMode mode = desiredDashboardMode();
        HomeDashboardMode forced = debugForcedMode();
        if (forced != null) { mode = forced; if (mode == HomeDashboardMode.NAVIGATION) guiding = true; driving = debugForcedDriving(); }
        homeLayout.apply(driving,guiding,mode);
        compactLayout = homeLayout.narrow() || homeLayout.shortWindow();
        updateMediaDensity();
        updateMediaLayoutButton();
        for(int id:new int[]{R.id.trip_distance_label,R.id.trip_time_label,R.id.trip_average_label,R.id.trip_peak_label}) {
            TextView label=findViewById(id);label.setMaxLines(homeLayout.narrow()?2:1);
            label.setText(tripLabel(label.getText().toString()));
        }
    }

    /** Debuggable-only layout preview: `setprop debug.oabl.mode media|normal|nav` forces a mode so
     * every arrangement can be screenshotted without a live car or route. Ignored in release. */
    private HomeDashboardMode debugForcedMode() {
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return null;
        String v = systemProp("debug.oabl.mode");
        if ("media".equals(v)) return HomeDashboardMode.MEDIA;
        if ("normal".equals(v)) return HomeDashboardMode.NORMAL;
        if ("nav".equals(v)) return HomeDashboardMode.NAVIGATION;
        return null;
    }
    private boolean debugForcedDriving() { return "1".equals(systemProp("debug.oabl.driving")); }
    private HomeDashboardMode desiredDashboardMode() {
        if (activeNavigation.active && activeNavigation.text != null) return HomeDashboardMode.NAVIGATION;
        // Home has one layout; the old "large player" pin (dashboard_layout_override) is no longer read.
        return HomeDashboardMode.choose(false, dashboardLinkConnected);
    }

    /** The card's player: OABL Music for OABL's own playback (or nothing playing), else the playing app. */
    private void openMediaPlayer() {
        if (getPackageName().equals(activeMediaPackage) || activeMediaPackage == null) openTool(MusicActivity.class);
        else launchPackage(activeMediaPackage);
    }

    private void updateMediaLayoutButton() {
        ImageView button = findViewById(R.id.media_expand);
        if (button == null || homeLayout == null) return;
        button.setImageResource(R.drawable.ic_expand);
        button.setContentDescription("Open the music player");
        button.setVisibility(homeLayout.mode() == HomeDashboardMode.NAVIGATION ? View.GONE : View.VISIBLE);
        renderHeroTools();
    }
    private static String systemProp(String key) {
        try { return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, key); }
        catch (Exception e) { return ""; }
    }

    private void bindActions() {
        findViewById(R.id.speed_cluster).setOnLongClickListener(v -> { showSpeedLimitSettings(null); return true; });
        RoadSpeedLimit.get().listener = this::renderDashboardData;
        findViewById(R.id.weather_status).setOnClickListener(v -> { if (!preferences.weatherEnabled()) askWeather(); else showWeatherDetails(); });
        findViewById(R.id.energy_card).setOnClickListener(v -> energyCardTapped());
        findViewById(R.id.energy_card).setOnLongClickListener(v -> { openTool(EnergyActivity.class); return true; });
        findViewById(R.id.trip_card).setOnClickListener(v -> {
            if(EnergyClient.link(this).expectsCarBattery())openTool(EnergyActivity.class);else openTool(TripHistoryActivity.class);
        });
        findViewById(R.id.trip_card).setContentDescription("This drive. Tap for trip history, hold to reset.");
        findViewById(R.id.trip_card).setOnLongClickListener(v -> {
            if(EnergyClient.link(this).expectsCarBattery()){openTool(EnergyActivity.class);return true;}
            dialogBuilder().setTitle("Reset trip?").setMessage("Clear distance, moving time, average and peak speed?")
                    .setPositiveButton("Reset", (d,w) -> { TripComputer.get(this).reset(); renderDashboardData(); })
                    .setNegativeButton("Cancel", null).show(); return true;
        });
        findViewById(R.id.stage).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startNavigationMode();
            }
        });
        findViewById(R.id.media_previous).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (ownMusicCard()) MusicClient.get(MainActivity.this).command("previous"); else mediaClient.previous();
            }
        });
        mediaPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (activeMediaPackage != null && !getPackageName().equals(activeMediaPackage)) {
                    mediaClient.playPause();
                    // A remembered player (VLC after it stopped) opens a new session once it starts.
                    handler.postDelayed(MainActivity.this::renderMedia, 1200);
                } else if (ownMusicCard()) {
                    // OABL Music: play/pause through the service, which also restores the last queue after a restart.
                    MusicClient.get(MainActivity.this).command("toggle");
                    handler.postDelayed(MainActivity.this::renderMedia, 800);
                } else {
                    openTool(MusicActivity.class);
                }
            }
        });
        findViewById(R.id.media_next).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (ownMusicCard()) MusicClient.get(MainActivity.this).command("next"); else mediaClient.next();
            }
        });
        findViewById(R.id.media_playlists).setOnClickListener(v -> showPlaylistPicker());
        findViewById(R.id.media_expand).setOnClickListener(v -> openMediaPlayer());
        findViewById(R.id.media_favorite).setOnClickListener(v -> toggleHeroFavourite());
        findViewById(R.id.media_eq).setOnClickListener(v -> openEqualizer());
        findViewById(R.id.media_open).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (getPackageName().equals(activeMediaPackage) || activeMediaPackage == null) {
                    openTool(MusicActivity.class);
                } else {
                    launchPackage(activeMediaPackage);
                }
            }
        });
        findViewById(R.id.media_card).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (getPackageName().equals(activeMediaPackage) || activeMediaPackage == null) {
                    openTool(MusicActivity.class);
                } else {
                    launchPackage(activeMediaPackage);
                }
            }
        });
        installMediaSwipe(findViewById(R.id.media_card));
        findViewById(R.id.calendar_card).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (!preferences.weatherEnabled()) askWeather();
                else showWeatherDetails();
            }
        });
        navigationHintCard.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openNavigationHint(); }
        });
        findViewById(R.id.navigation_idle).addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            if(r-l!=or-ol || b-t!=ob-ot) v.post(this::renderNavigationChips);
        });
        // Holding the live position line sets Home and Work, whatever chips fit on the card.
        // A tap on the road line does what a tap on the card does (and dismisses a trip summary).
        findViewById(R.id.navigation_where).setOnClickListener(v -> openNavigationHint());
        findViewById(R.id.navigation_where).setOnLongClickListener(v -> {
            if (blockedWhileDriving()) return true;
            showSavedPlaces();
            return true;
        });
        findViewById(R.id.navigation_end).setOnClickListener(v -> endNavigation());
        findViewById(R.id.navigation_arrival_soc).setOnClickListener(v -> { if (arrivalChargeNeeded) openChargers(); });
        findViewById(R.id.charge_review_pill).setOnClickListener(v -> reviewPendingCharge());
        networkStatus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showTravelStatus(); }
        });
        findViewById(R.id.speed_cluster).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { requestSpeedAccess(false); }
        });
        int[] animatedControls = {
                R.id.network_status, R.id.speed_cluster,
                R.id.media_previous, R.id.media_play_pause, R.id.media_next, R.id.media_playlists, R.id.media_expand,
                R.id.media_open, R.id.calendar_card
        };
        for (int id : animatedControls) {
            installMotionFeedback(findViewById(id));
        }
        installMediaSwipe();
        installLayoutMotion();
    }

    /** Horizontal swipe across the media card skips tracks; taps still open the player. */
    @SuppressLint("ClickableViewAccessibility")
    private void installMediaSwipe() {
        final View card = findViewById(R.id.media_card);
        final View content = findViewById(R.id.media_content);
        final android.view.GestureDetector detector = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent down, MotionEvent up, float velocityX, float velocityY) {
                        if (down == null || activeMediaPackage == null) return false;
                        float dx = up.getX() - down.getX();
                        if (Math.abs(dx) < dp(56) || Math.abs(velocityX) < Math.abs(velocityY) * 1.5f) {
                            return false;
                        }
                        if (dx < 0) mediaClient.next(); else mediaClient.previous();
                        if (preferences.visualEffects()) {
                            content.animate().cancel();
                            content.setTranslationX(Math.signum(dx) * dp(28));
                            content.setAlpha(0.4f);
                            content.animate().translationX(0f).alpha(1f).setDuration(360L)
                                    .setInterpolator(new DecelerateInterpolator(1.5f)).start();
                        }
                        return true;
                    }
                });
        card.setOnTouchListener((view, event) -> {
            if (detector.onTouchEvent(event)) {
                // Consume the gesture so the card's click does not also fire.
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                view.onTouchEvent(cancel);
                cancel.recycle();
                return true;
            }
            return false;
        });
    }

    /** Lets the navigation card grow and the media card make room smoothly. */
    private void installLayoutMotion() {
        int[] containers = {R.id.right_column, R.id.media_content};
        for (int id : containers) {
            ViewGroup group = findViewById(id);
            if (!preferences.visualEffects()) {
                group.setLayoutTransition(null);
                continue;
            }
            android.animation.LayoutTransition transition = new android.animation.LayoutTransition();
            transition.enableTransitionType(android.animation.LayoutTransition.CHANGING);
            transition.setDuration(320L);
            transition.setInterpolator(android.animation.LayoutTransition.CHANGING, new DecelerateInterpolator(1.4f));
            group.setLayoutTransition(transition);
        }
    }

    private void requestSpeedAccess(boolean forNavigation) {
        if (!forNavigation && EnergyClient.link(this).expectsCarBattery()) {
            CarLinkDialogs.status(this, palette, EnergyClient.link(this));
            return;
        }
        pendingNavigation = forNavigation;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            speedTracker.start();
            if (forNavigation) {
                continueNavigationMode();
            }
            return;
        }
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                REQUEST_LOCATION);
    }

    private void startNavigationMode() {
        pendingDestination = null;
        requestSpeedAccess(true);
    }

    /** Starts turn-by-turn to a typed or saved place in the chosen map app, with the overlay. */
    private void navigateTo(String destination) {
        if (destination == null || destination.trim().isEmpty()) {
            return;
        }
        String place = destination.trim();
        if (!place.equals(preferences.navigationHome()) && !place.equals(preferences.navigationWork())) {
            preferences.addNavigationRecent(place);
            renderedChipsKey = null;
        }
        pendingDestination = place;
        requestSpeedAccess(true);
    }

    private boolean launchDestination(ComponentName map, String destination) {
        String provider = map.getPackageName();
        Uri uri;
        if ("com.waze".equals(provider)) {
            uri = Uri.parse("https://waze.com/ul?q=" + Uri.encode(destination) + "&navigate=yes");
        } else if ("com.google.android.apps.maps".equals(provider)) {
            uri = Uri.parse("google.navigation:q=" + Uri.encode(destination));
        } else {
            uri = Uri.parse("geo:0,0?q=" + Uri.encode(destination));
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                .setPackage(provider)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            SplitScreenService.showAppControls(MainActivity.this);
            startActivity(intent);
            playLaunchTransition();
            return true;
        } catch (ActivityNotFoundException error) {
            SplitScreenService.hideAppControls(MainActivity.this);
            return false;
        }
    }

    private void continueNavigationMode() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
            return;
        }
        launchNavigationMode();
    }

    private void launchNavigationMode() {
        if (!Settings.canDrawOverlays(this)) {
            pendingNavigation = false;
            dialogBuilder()
                    .setTitle("Allow navigation display")
                    .setMessage("Lets Maps use the full screen while the clock, speed and media controls stay on top. "
                            + "Allow Open AI Box Launcher to appear over other apps, then tap Navigation again.")
                    .setNegativeButton("Not now", null)
                    .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        }
                    })
                    .show();
            return;
        }
        ComponentName map = preferences.focus();
        if (map == null) {
            chooseFocusApp();
            return;
        }
        try {
            Intent overlay = new Intent(this, DriveOverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(overlay);
            } else {
                startService(overlay);
            }
            if (pendingDestination == null || !launchDestination(map, pendingDestination)) {
                launch(map);
            }
        } catch (RuntimeException error) {
            Toast.makeText(this, "Navigation display could not start",
                    Toast.LENGTH_LONG).show();
        } finally {
            pendingNavigation = false;
            pendingDestination = null;
        }
    }

    private void chooseFocusApp() {
        pickApp("Choose navigation app", new AppChoice() {
            @Override
            public void selected(AppEntry app) {
                preferences.setFocus(app.component);
                renderMedia();
                renderNavigationHints();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 508) { EnergyClient.link(this).retry(); return; }
        if (requestCode == REQUEST_BLUETOOTH) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                travelStatusMonitor.start();
                renderTravelStatus();
            } else {
                Toast.makeText(this, "Bluetooth details are off",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode == REQUEST_PHONE) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                travelStatusMonitor.start();
                renderTravelStatus();
            } else {
                Toast.makeText(this, "Mobile signal details are off",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (grantResults.length == 0
                    || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this,
                        "Navigation works without status notifications",
                        Toast.LENGTH_SHORT).show();
            }
            launchNavigationMode();
            return;
        }
        if (requestCode != REQUEST_LOCATION) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            speedTracker.start();
            if (pendingNavigation) {
                continueNavigationMode();
            }
        } else if (!renderCarSpeed()) {
            ((SpeedGaugeView) findViewById(R.id.speed_cluster)).unknown(speedLimit(), palette);
            setSpeedStatus("LOCATION OFF");
            Toast.makeText(this, "Allow location to see GPS speed",
                    Toast.LENGTH_LONG).show();
        }
        pendingNavigation = false;
    }

    private final java.util.concurrent.ExecutorService appLoader = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean appsLoading = new java.util.concurrent.atomic.AtomicBoolean();

    private void reloadApps() {
        // Reading every app and icon is costly; only do it when something was installed or changed,
        // and off the UI thread. The dock shows what it has meanwhile; defaults wait for the real list.
        if (!apps.isEmpty() && !appRepository.changedSinceLoad()) { renderDock(); return; }
        renderDock();
        if (!appsLoading.compareAndSet(false, true)) return;
        appLoader.execute(() -> {
            List<AppEntry> loaded = null;
            try { loaded = appRepository.load(); } catch (RuntimeException failed) { android.util.Log.w("OABL", "App list load failed", failed); }
            List<AppEntry> result = loaded;
            handler.post(() -> {
                appsLoading.set(false);
                if (result == null || ActivityState.isGone(this)) return;
                apps = result;
                ensureDefaults();
                renderDock();
            });
        });
    }

    private void ensureDefaults() {
        String[][] preferredPackages = new String[][]{
                {"com.google.android.apps.maps", "com.waze"},
                {"com.spotify.music", "com.google.android.apps.youtube.music",
                        "com.apple.android.music", "com.android.music"},
                {"com.google.android.youtube", "com.google.android.youtube.tv"},
                // The box's own phone/Bluetooth app; otherwise the slot stays free (Controls opens settings).
                {"com.carsyso.bluetooth", "com.ly.bluetooth"}
        };
        for (int i = 0; i < 4; i++) {
            ComponentName current = preferences.shortcut(i);
            if (appRepository.find(apps, current) != null) {
                continue;
            }
            AppEntry candidate = appRepository.findFirstPackage(apps, preferredPackages[i]);
            preferences.setShortcut(i, candidate == null ? null : candidate.component);
        }
        if (appRepository.find(apps, preferences.focus()) == null) {
            AppEntry map = appRepository.findFirstPackage(apps,
                    "com.google.android.apps.maps", "com.waze");
            if (map == null && !apps.isEmpty()) {
                map = apps.get(0);
            }
            preferences.setFocus(map == null ? null : map.component);
        }
        if (appRepository.find(apps, preferences.splitPrimary()) == null
                || appRepository.find(apps, preferences.splitSecondary()) == null) {
            preferences.setSplitPair(preferences.focus(), preferences.shortcut(1));
        }
    }

    /**
     * The command bar (EV cockpit). Back and Home, the four pinned apps, two recent apps with the
     * full Recents list, then Split, EQ, Apps, Controls and Settings. A split window keeps Back,
     * Home, the pinned apps, Recents and Apps and gathers the rest under More. Every tile takes an
     * equal share of the bar, so nothing scrolls. Badges are updated separately on each refresh.
     */
    private String renderedDockKey;

    /**
     * Rebuilds the bar only when what it shows changed. Rebuilding replaces the tiles, and a tap
     * whose click was still pending on the old tile would be lost (e.g. on every return to Home).
     */
    private void renderDock() {
        boolean fewer = homeLayout != null && homeLayout.narrow();
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 4; i++) key.append(preferences.shortcut(i)).append('|');
        key.append(preferences.dockRecents()).append('|').append(drivingMode).append('|').append(compactLayout)
                .append('|').append(fewer)
                .append('|').append(palette == null ? 0 : palette.accent).append(palette != null && palette.light)
                .append('|').append(apps.size()).append('|').append(preferences.hiddenApps());
        if (key.toString().equals(renderedDockKey) && dock.getChildCount() > 0) return;
        renderedDockKey = key.toString();
        java.util.Set<String> before = new java.util.HashSet<>();
        for (int i = 0; i < dock.getChildCount(); i++) {
            Object recent = dock.getChildAt(i).getTag(R.id.dock_package);
            if (recent != null && "recent".equals(dock.getChildAt(i).getTag())) before.add((String) recent);
        }
        dock.removeAllViews();

        // Back to the app used last, and Home (this screen).
        final AppEntry last = lastUsedApp();
        View back = createCommandTile(R.drawable.ic_bar_back, "Back", v -> {
            if (last != null) launch(last.component, (View) v.getTag(R.id.dock_icon));
            else Toast.makeText(this, "No app to go back to yet", Toast.LENGTH_SHORT).show();
        }, last != null, false);
        back.setContentDescription(last == null ? "Back: no recent app" : "Back to " + last.label);
        dock.addView(back, dockParams());
        View home = createCommandTile(R.drawable.ic_bar_home, "Home", v -> hideScreensaver(), true, true);
        home.setContentDescription("Home, shown now");
        dock.addView(home, dockParams());
        dock.addView(dockDivider());

        java.util.Set<String> pinned = new java.util.HashSet<>();
        for (int i = 0; i < 4; i++) {
            final int slot = i;
            final AppEntry app = appRepository.find(apps, preferences.shortcut(i));
            View tile = app == null
                    ? createCommandTile(R.drawable.ic_dock_add, "Add app", v -> chooseShortcut(slot), true, false)
                    : createAppDockTile(app);
            tile.setOnLongClickListener(v -> { chooseShortcut(slot); return true; });
            if (app != null) pinned.add(app.component.flattenToString());
            dock.addView(tile, dockParams());
        }
        dock.addView(dockDivider());

        if (!fewer) {
            for (final AppEntry app : recentDockApps(pinned, 2)) {
                View tile = createAppDockTile(app);
                tile.setTag("recent");
                tile.setOnLongClickListener(v -> { forgetDockRecent(app); return true; });
                dock.addView(tile, dockParams());
                if (foreground && entranceAnimated && preferences.visualEffects()
                        && !before.contains(app.component.getPackageName())) {
                    tile.setAlpha(0f);
                    tile.setScaleX(.6f);
                    tile.setScaleY(.6f);
                    tile.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
                }
            }
        }
        View recents = createCommandTile(R.drawable.ic_bar_recents, "Recents", v -> showRecentApps(), true, false);
        recents.setContentDescription("Recent apps: open, remove or app info");
        dock.addView(recents, dockParams());
        dock.addView(dockDivider());

        if (!fewer) {
            View split = createCommandTile(R.drawable.ic_bar_split, "Split", v -> launchSplitPair(), true, false);
            split.setOnLongClickListener(v -> { showSplitPairEditor(); return true; });
            split.setContentDescription("Split screen pair. Hold to choose the apps");
            dock.addView(split, dockParams());
            dock.addView(createCommandTile(R.drawable.ic_bar_eq, "EQ", v -> openEqualizer(), true, false), dockParams());
        }
        View appsTile = createCommandTile(R.drawable.ic_bar_apps, "Apps", v -> showAppDrawer(), true, false);
        appsTile.setTag("dock_apps");
        dock.addView(appsTile, dockParams());
        if (!fewer) {
            View controls = createCommandTile(R.drawable.ic_bar_controls, "Controls", v -> showControls(), true, false);
            controls.setTag("dock_controls");
            controls.setContentDescription("Quick controls: volume, sound, brightness and connections");
            dock.addView(controls, dockParams());
            dock.addView(createCommandTile(R.drawable.ic_bar_settings, "Settings", v -> showLauncherSettings(), true, false), dockParams());
        } else {
            View more = createCommandTile(R.drawable.ic_bar_more, "More", v -> showMoreCommands(), true, false);
            more.setContentDescription("More: split screen, equalizer, controls and settings");
            dock.addView(more, dockParams());
        }
        updateDockBadges();
        if (foreground && entranceAnimated && preferences.visualEffects() && before.isEmpty() && dock.getAlpha() == 1f) {
            animateDockTiles();
        }
    }

    /** Split window: the commands that don't fit in the bar. */
    private void showMoreCommands() {
        String[] items = {"Split screen · " + appLabel(preferences.splitPrimary()) + " + " + appLabel(preferences.splitSecondary()),
                "Choose the split pair", "Equalizer", "Quick controls", "Settings"};
        dialogBuilder().setTitle("More").setItems(items, (d, which) -> {
            switch (which) {
                case 0: launchSplitPair(); break;
                case 1: showSplitPairEditor(); break;
                case 2: openEqualizer(); break;
                case 3: showControls(); break;
                default: showLauncherSettings(); break;
            }
        }).setNegativeButton("Close", null).show();
    }

    /** Every recent app: tap to open, hold for Remove from recents or App info. */
    private void showRecentApps() {
        final List<AppEntry> entries = new ArrayList<>();
        for (String flattened : preferences.dockRecents()) {
            AppEntry app = appRepository.find(apps, ComponentName.unflattenFromString(flattened));
            if (app != null && !MainActivity.class.getName().equals(app.component.getClassName())) entries.add(app);
        }
        if (entries.isEmpty()) {
            Toast.makeText(this, "No recent apps yet", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[entries.size()];
        for (int i = 0; i < names.length; i++) names[i] = entries.get(i).label;
        final AlertDialog dialog = dialogBuilder().setTitle("Recent apps · hold one for options")
                .setItems(names, (d, which) -> launch(entries.get(which).component))
                .setNegativeButton("Close", null).show();
        dialog.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            forgetDockRecent(entries.get(position));
            return true;
        });
    }

    /** Hidden apps can still be opened from here, or shown in Apps again. */
    private void showHiddenApps(Dialog drawer) {
        java.util.Set<String> hidden = preferences.hiddenApps();
        List<AppEntry> entries = new ArrayList<>();
        for (AppEntry app : apps) if (hidden.contains(app.component.flattenToString())) entries.add(app);
        String[] names = new String[entries.size()];
        for (int i = 0; i < names.length; i++) names[i] = entries.get(i).label;
        dialogBuilder().setTitle("Hidden apps · tap to show again").setItems(names, (d, which) -> {
            preferences.setHidden(entries.get(which).component.flattenToString(), false);
            drawer.dismiss();
            showAppDrawer();
        }).setNegativeButton("Close", null).show();
    }

    /** Once: free a dock slot pinned to Android Settings, which Controls already opens. */
    private void tidyDockOnce() {
        android.content.SharedPreferences flags = getSharedPreferences("launcher_preferences", 0);
        if (flags.getBoolean("dock_tidy_v2", false)) return;
        for (int i = 0; i < 4; i++) {
            ComponentName pinned = preferences.shortcut(i);
            if (pinned != null && utilityApp(pinned.getPackageName())) preferences.setShortcut(i, null);
        }
        flags.edit().putBoolean("dock_tidy_v2", true).apply();
    }

    /** Settings, clocks, file pickers and installers are tools, not something to keep in the dock. */
    private static boolean utilityApp(String packageName) {
        return packageName.equals("com.android.settings") || packageName.contains("deskclock")
                || packageName.endsWith("documentsui") || packageName.contains("packageinstaller");
    }

    private List<AppEntry> recentDockApps(java.util.Set<String> pinned, int limit) {
        List<AppEntry> result = new ArrayList<>();
        for (String flattened : preferences.dockRecents()) {
            AppEntry app = appRepository.find(apps, ComponentName.unflattenFromString(flattened));
            if (app == null || pinned.contains(flattened) || utilityApp(app.component.getPackageName())
                    || MainActivity.class.getName().equals(app.component.getClassName())) continue;
            result.add(app);
            if (result.size() == limit) break;
        }
        return result;
    }

    /** The app Back returns to: the most recently opened one, pinned or not. */
    private AppEntry lastUsedApp() {
        for (String flattened : preferences.dockRecents()) {
            AppEntry app = appRepository.find(apps, ComponentName.unflattenFromString(flattened));
            if (app != null && !MainActivity.class.getName().equals(app.component.getClassName())) return app;
        }
        return null;
    }

    private void forgetDockRecent(AppEntry app) {
        dialogBuilder().setTitle(app.label)
                .setItems(new String[]{"Remove from recents", "App info"}, (d, which) -> {
                    if (which == 0) {
                        preferences.removeDockRecent(app.component.flattenToString());
                        renderDock();
                    } else {
                        openAppDetails(app.component.getPackageName());
                    }
                }).show();
    }

    private View dockDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(palette != null && palette.light ? 0x1f13202a : 0x1fffffff);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(1), dp(compactLayout ? 30 : 36));
        params.setMargins(dp(compactLayout ? 3 : 6), 0, dp(compactLayout ? 3 : 6), 0);
        divider.setLayoutParams(params);
        return divider;
    }

    /** The bar shares the cards' surface, a little more transparent, with no outline. */
    private Drawable commandBarBackground(LauncherTheme.Palette next) {
        int alpha = Math.max(0, (next.surface >>> 24) - 0x18);
        return LauncherTheme.rounded(this, (next.surface & 0x00ffffff) | (alpha << 24), 18, 0, 0);
    }

    /** Running dot, playing-media bars and navigation ETA on the matching app tiles. */
    private void updateDockBadges() {
        if (dock == null || palette == null) return;
        String navigating = activeNavigation.active ? activeNavigation.packageName : null;
        String navText = lastGuidance == null ? null
                : lastGuidance.eta != null ? lastGuidance.eta : lastGuidance.distance;
        for (int i = 0; i < dock.getChildCount(); i++) {
            View tile = dock.getChildAt(i);
            Object tag = tile.getTag(R.id.dock_package);
            if (!(tag instanceof String)) continue;
            String pkg = (String) tag;
            DockBadgeView badge = (DockBadgeView) tile.getTag(R.id.dock_badge);
            View dot = (View) tile.getTag(R.id.dock_dot);
            boolean nav = pkg.equals(navigating);
            boolean media = pkg.equals(activeMediaPackage);
            if (nav && navText != null) badge.text(navText, palette.accent);
            else if (media) badge.media(mediaPlaying, palette.accent);
            else badge.none();
            dot.setVisibility(nav || media ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private View createAppDockTile(final AppEntry app) {
        LinearLayout tile = dockTileBase(false);
        boolean blocked=drivingMode&&videoApp(app.component.getPackageName());
        FrameLayout box = new FrameLayout(this);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(icon, new FrameLayout.LayoutParams(dp(dockIconSize()), dp(dockIconSize()), Gravity.CENTER));
        DockBadgeView badge = new DockBadgeView(this);
        box.addView(badge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        View dot = dockDot();
        box.addView(dot);
        tile.addView(box, new LinearLayout.LayoutParams(dp(dockIconSize() + 10), dp(dockIconSize() + 6)));
        tile.addView(dockLabel(app.label, palette.ink));
        tile.setTag(R.id.dock_package, app.component.getPackageName());
        tile.setTag(R.id.dock_badge, badge);
        tile.setTag(R.id.dock_dot, dot);
        tile.setTag(R.id.dock_icon, box);
        tile.setContentDescription(app.label+(blocked?", video blocked while driving":""));
        if(blocked)tile.setAlpha(.38f);
        tile.setOnClickListener(v -> launch(app.component, box));
        return tile;
    }

    /** A command: an outline glyph over its label; the active one (Home) is raised and accented. */
    private View createCommandTile(int glyph, String label, View.OnClickListener listener,
                                   boolean enabled, boolean active) {
        LinearLayout tile = dockTileBase(active);
        ImageView icon = new ImageView(this);
        icon.setImageResource(glyph);
        int tint = active ? palette.accent : enabled ? palette.ink : palette.muted;
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int glyphSize = Math.round(dockIconSize() * .78f);
        FrameLayout box = new FrameLayout(this);
        box.addView(icon, new FrameLayout.LayoutParams(dp(glyphSize), dp(glyphSize), Gravity.CENTER));
        tile.addView(box, new LinearLayout.LayoutParams(dp(dockIconSize() + 10), dp(dockIconSize() + 6)));
        tile.addView(dockLabel(label, active ? palette.accent : enabled ? palette.ink : palette.muted));
        tile.setTag(R.id.dock_icon, box);
        tile.setContentDescription(label);
        tile.setOnClickListener(listener);
        return tile;
    }

    private View dockDot() {
        View dot = new View(this);
        dot.setBackground(LauncherTheme.rounded(this, palette.accent, 3, 0, 0));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(5), dp(5),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dot.setLayoutParams(params);
        dot.setVisibility(View.INVISIBLE);
        return dot;
    }

    /** Icon over label. Only a press (or the active command) shows a surface: no outline per tile. */
    private LinearLayout dockTileBase(boolean active) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(2), dp(2), dp(2), dp(3));
        tile.setBackground(active ? LauncherTheme.raised(this, palette, 16) : LauncherTheme.dockItem(this, palette));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setClipChildren(false);
        installDockMotion(tile);
        return tile;
    }

    /** The pressed icon lifts with a soft spring; its neighbours lean in slightly. */
    @SuppressLint("ClickableViewAccessibility")
    private void installDockMotion(final LinearLayout tile) {
        tile.setOnTouchListener((view, event) -> {
            if (!preferences.visualEffects()) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                magnify(view, 1.12f, 1.04f);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                magnify(view, 1f, 1f);
            }
            return false;
        });
    }

    private void magnify(View tile, float self, float neighbours) {
        int index = dock.indexOfChild(tile);
        for (int i = index - 1; i <= index + 1; i++) {
            if (i < 0 || i >= dock.getChildCount()) continue;
            View box = (View) dock.getChildAt(i).getTag(R.id.dock_icon);
            if (box == null) continue;
            float scale = i == index ? self : neighbours;
            box.animate().scaleX(scale).scaleY(scale).translationY(scale > 1f ? -dp(i == index ? 4 : 1) : 0)
                    .setDuration(scale > 1f ? 140 : 260)
                    .setInterpolator(scale > 1f ? new DecelerateInterpolator()
                            : new android.view.animation.OvershootInterpolator(2f)).start();
        }
    }

    private TextView dockLabel(String text, int color) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(color);
        label.setTextSize(13);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setIncludeFontPadding(false);
        // Driving mode: the larger icons alone, labels would only be cut to "Yo…".
        if (drivingMode) label.setVisibility(View.GONE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(2);
        label.setLayoutParams(params);
        return label;
    }

    private int dockIconSize() {
        return !compactLayout && drivingMode ? 42 : 30;
    }

    private LinearLayout.LayoutParams dockParams() {
        // Tiles share the bar evenly; dividers keep their fixed size.
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        params.setMargins(dp(1), dp(2), dp(1), dp(2));
        return params;
    }

    // ---- entry points used by the Controls sheet ----

    private void showControls() {
        ControlsSheet.show(this, palette);
    }

    LauncherTheme.Palette currentPalette() { return palette; }
    TravelStatusMonitor.Snapshot travelNow() { return travelStatusMonitor.read(); }
    String splitPairSummary() { return appLabel(preferences.splitPrimary()) + " + " + appLabel(preferences.splitSecondary()); }
    void launchSplitPairFromControls() { launchSplitPair(); }
    void editSplitPairFromControls() { showSplitPairEditor(); }
    void showLauncherSettingsFromControls() { showLauncherSettings(); }
    void openSystemSettingFromControls(String action) { openSystemSettings(action); }
    void openEqualizer() { openTool(EqualizerActivity.class); }
    void openEnergyFromControls() { openTool(EnergyActivity.class); }

    /** Tool screens get their own task, so Home always returns to the dashboard. */
    private void openTool(Class<?> screen) {
        startActivity(new Intent(this, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    void setThemeFromControls(int mode) {
        preferences.setThemeMode(mode);
        applyAppearance(preferences.visualEffects());
    }

    void togglePerformanceFromControls() {
        boolean visuals = !preferences.visualEffectsChoice();
        preferences.setVisualEffects(visuals);
        if (visuals) {
            entranceAnimated = false;
            runEntranceAnimation();
        } else {
            cancelAnimations(findViewById(R.id.root));
        }
        installLayoutMotion();
        renderMedia();
    }

    private void renderMedia() {
        MediaControllerClient.Snapshot snapshot = mediaClient.read();
        mediaPlaying = snapshot != null && snapshot.playing;
        mediaArt = snapshot == null ? null : snapshot.artwork;
        if (snapshot != null) {
            activeMediaPackage = snapshot.packageName;
            mediaTitle.setText(snapshot.title);
            // When a player sends no artist the subtitle falls back to the app name, which
            // the source chip already shows.
            boolean subtitleIsSource = snapshot.subtitle != null && snapshot.subtitle.equals(snapshot.source);
            mediaSubtitle.setText(snapshot.subtitle);
            mediaSubtitle.setVisibility(subtitleIsSource ? View.GONE : View.VISIBLE);
            setPlayIcon(snapshot.playing ? R.drawable.ic_media_pause : R.drawable.ic_media_play);
            if (snapshot.artwork != null) {
                if (!artKey(snapshot).equals(renderedArtKey)) {
                    renderedArtKey = artKey(snapshot);
                    mediaArtwork.setImageBitmap(snapshot.artwork);
                }
                mediaArtwork.setPadding(0, 0, 0, 0);
                mediaArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                renderedArtKey = null;
                setMediaPackageIcon(snapshot.packageName);
            }
            renderMediaSource(snapshot.packageName, snapshot.source);
            updateMediaDensity();
            setMediaButtonsEnabled(true);
            animateMediaChange(snapshot.packageName + "|" + snapshot.title + "|"
                    + snapshot.subtitle + "|" + snapshot.playing);
            updateDockBadges();
            return;
        }

        activeMediaPackage = null;
        MusicLibrary.Track last = lastMusicTrack();
        mediaTitle.setText(last != null ? last.title : "Your music, ready");
        mediaSubtitle.setText(last != null ? last.byline() + " · tap play to resume" : "OABL Music · tap to browse your collection");
        mediaSubtitle.setVisibility(View.VISIBLE);
        setPlayIcon(R.drawable.ic_media_play);
        mediaArtwork.setImageResource(R.drawable.ic_music);
        mediaArtwork.setPadding(dp(8), dp(8), dp(8), dp(8));
        mediaArtwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
        renderMediaSource(null, null);
        renderedArtKey = null;
        updateMediaDensity();
        setMediaButtonsEnabled(false);
        // Play stays available: it resumes the last OABL Music queue or opens the library.
        mediaPlayPause.setEnabled(true);
        mediaPlayPause.setAlpha(1f);
        animateMediaChange("idle|" + mediaTitle.getText() + "|" + mediaSubtitle.getText());
        updateDockBadges();
    }

    /** The limit to show and warn about: from the road (auto), the manual choice, or none. */
    private int speedLimit() {
        String mode = preferences.speedLimitMode();
        if ("off".equals(mode)) return 0;
        if ("manual".equals(mode)) return preferences.manualSpeedLimit();
        RoadSpeedLimit road = RoadSpeedLimit.get();
        int limit = road.limit();
        if (limit > 60 && road.expressway() && rainLimitActive()) return 60;
        return limit;
    }

    private boolean rainLimitActive() {
        DashboardData.Forecast forecast = preferences.weatherEnabled() ? DashboardData.get(this).forecast : null;
        return preferences.rainLimit() && forecast != null && forecast.raining
                && System.currentTimeMillis() - forecast.fetchedAt < 90 * 60_000L;
    }

    private void gaugeLimitNote() {
        SpeedGaugeView gauge = findViewById(R.id.speed_cluster);
        RoadSpeedLimit road = RoadSpeedLimit.get();
        boolean auto = "auto".equals(preferences.speedLimitMode());
        String note = null;
        if ("manual".equals(preferences.speedLimitMode())) note = "MANUAL";
        else if (auto && road.limit() > 0) {
            note = road.expressway() && road.limit() > 60 && rainLimitActive() ? "RAIN ADVISORY"
                    : road.estimated() ? "ESTIMATED" : "MAPPED";
        }
        gauge.limitNote(note);
    }

    private long overSince, chimedAt;
    private android.media.ToneGenerator chime;

    /** Two short beeps after three seconds over the limit, then at most once a minute. */
    private void overspeedChime(int speed) {
        int limit = speedLimit();
        long now = android.os.SystemClock.elapsedRealtime();
        if (limit <= 0 || speed <= limit + 3 || !preferences.speedChime()) { if (speed < limit) overSince = 0; return; }
        if (overSince == 0) { overSince = now; return; }
        if (now - overSince < 3000 || now - chimedAt < 60_000) return;
        chimedAt = now;
        try {
            if (chime == null) chime = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 70);
            chime.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 400);
        } catch (RuntimeException noAudio) {
            chime = null;
        }
    }

    /** Settings › Speed limit, also on long-press of the speedometer. */
    private void showSpeedLimitSettings(SettingsPanel panel) {
        String mode = preferences.speedLimitMode();
        String[] rows = {
                ("auto".equals(mode) ? "● " : "○ ") + "Automatic · from the road (OpenStreetMap)",
                ("manual".equals(mode) ? "● " : "○ ") + "Fixed reminder" + (preferences.manualSpeedLimit() > 0 ? " · " + preferences.manualSpeedLimit() + " km/h" : "…"),
                ("off".equals(mode) ? "● " : "○ ") + "Off",
                (preferences.speedChime() ? "☑ " : "☐ ") + "Chime when over the limit",
                (preferences.rainLimit() ? "☑ " : "☐ ") + "Expressways: 60 km/h in rain"};
        dialogBuilder().setTitle("Speed limit").setItems(rows, (d, which) -> {
            if (which == 0) preferences.setSpeedLimitMode("auto");
            else if (which == 1) {
                final int[] limits = {40, 50, 60, 70, 80, 100, 120};
                dialogBuilder().setTitle("Fixed speed reminder · km/h")
                        .setItems(new String[]{"40", "50", "60", "70", "80", "100", "120"}, (x, i) -> {
                            preferences.setManualSpeedLimit(limits[i]);
                            preferences.setSpeedLimitMode("manual");
                            renderDashboardData();
                            if (panel != null) panel.refresh();
                        }).show();
                return;
            } else if (which == 2) preferences.setSpeedLimitMode("off");
            else if (which == 3) preferences.setSpeedChime(!preferences.speedChime());
            else preferences.setRainLimit(!preferences.rainLimit());
            renderDashboardData();
            if (panel != null) panel.refresh();
            if (which >= 3) showSpeedLimitSettings(panel);
        }).setNegativeButton("Close", null).show();
    }

    /**
     * Swiping sideways across the media card changes song: a much larger target than the small
     * skip buttons while driving. Taps still open the player; the buttons handle their own touches.
     */
    private void installMediaSwipe(View card) {
        android.view.GestureDetector detector = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(android.view.MotionEvent e) { card.performClick(); return true; }
            @Override public boolean onFling(android.view.MotionEvent a, android.view.MotionEvent b, float vx, float vy) {
                if (a == null) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (Math.abs(dx) < dp(70) || Math.abs(dx) < Math.abs(dy) * 1.5f) return false;
                findViewById(dx < 0 ? R.id.media_next : R.id.media_previous).performClick();
                if (preferences.visualEffects()) {
                    mediaArtwork.animate().translationX(dx < 0 ? -dp(28) : dp(28)).setDuration(110).withEndAction(() ->
                            mediaArtwork.animate().translationX(0).setDuration(200).start()).start();
                }
                return true;
            }
        });
        card.setOnTouchListener((v, e) -> detector.onTouchEvent(e));
    }

    /**
     * OABL Music owns the card (its session, or the idle card with a last track). Its buttons
     * then go straight to the music service, which restarts the player when it was closed;
     * a session left behind by a stopped player would ignore them.
     */
    private boolean ownMusicCard() {
        return getPackageName().equals(activeMediaPackage)
                || (activeMediaPackage == null && MusicClient.get(this).currentId() > 0);
    }

    private MusicLibrary.Track lastMusicTrack() {
        long id = MusicClient.get(this).currentId();
        if (id <= 0) return null;
        try { return MusicLibrary.get(this).track(id); } catch (RuntimeException unavailable) { return null; }
    }

    private static String artKey(MediaControllerClient.Snapshot snapshot) {
        return snapshot.packageName + "|" + snapshot.title + "|" + snapshot.subtitle
                + (snapshot.artwork == null ? "" : "|" + snapshot.artwork.getWidth() + "x" + snapshot.artwork.getHeight());
    }

    private void setPlayIcon(int icon) {
        if (renderedPlayIcon != icon) {
            renderedPlayIcon = icon;
            mediaPlayPause.setImageResource(icon);
        }
    }

    private void renderMediaSource(String packageName, String label) {
        if (packageName == null) {
            renderedSourcePackage = null;
            mediaSource.setVisibility(View.GONE);
            return;
        }
        mediaSource.setVisibility(View.VISIBLE);
        mediaSource.setText(label);
        if (packageName.equals(renderedSourcePackage)) {
            return;
        }
        renderedSourcePackage = packageName;
        Drawable icon = null;
        try {
            icon = getPackageManager().getApplicationIcon(packageName);
            icon.setBounds(0, 0, dp(16), dp(16));
        } catch (PackageManager.NameNotFoundException ignored) {
            // The label alone still names the source.
        }
        mediaSource.setCompoundDrawablesRelative(icon, null, null, null);
    }

    /**
     * Fills the media card with a blurred copy of the artwork and an art-tinted scrim. The blur
     * runs once per track on a 48 px thumbnail; nothing is blurred per frame.
     */
    /** Home's playlist picker: a playlist plays in OABL Music, shuffled, from its first song. */
    private void showPlaylistPicker() {
        appLoader.execute(() -> {
            MusicLibrary library = MusicLibrary.get(this);
            List<MusicLibrary.Playlist> lists = library.playlists();
            java.util.Map<Long, Integer> sizes = library.playlistSizes();
            handler.post(() -> {
                if (ActivityState.isGone(this)) return;
                if (lists.isEmpty()) { Toast.makeText(this, "No playlists yet · create them in OABL Music", Toast.LENGTH_LONG).show(); return; }
                String[] names = new String[lists.size()];
                for (int i = 0; i < names.length; i++) {
                    Integer n = sizes.get(lists.get(i).id);
                    names[i] = lists.get(i).name + "  ·  " + (n == null ? 0 : n) + " songs";
                }
                dialogBuilder().setTitle("Play a playlist").setItems(names, (d, which) -> playPlaylist(lists.get(which)))
                        .setNegativeButton("Close", null).show();
            });
        });
    }

    private void playPlaylist(MusicLibrary.Playlist playlist) {
        appLoader.execute(() -> {
            List<MusicLibrary.Track> tracks = new ArrayList<>(MusicLibrary.get(this).playlistTracks(playlist.id));
            handler.post(() -> {
                if (tracks.isEmpty()) { Toast.makeText(this, playlist.name + " has no songs on this box", Toast.LENGTH_SHORT).show(); return; }
                java.util.Collections.shuffle(tracks);
                MusicClient.rememberSource(this, playlist.name, tracks.size());
                MusicClient.get(this).play(tracks, tracks.get(0).id);
                Toast.makeText(this, "Playing " + playlist.name, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void fadeIn(View view) {
        view.animate().cancel();
        if (view.getVisibility() != View.VISIBLE) {
            view.setAlpha(0f);
            view.setVisibility(View.VISIBLE);
        }
        if (preferences.visualEffects()) {
            view.animate().alpha(1f).setDuration(420L).setInterpolator(new DecelerateInterpolator()).start();
        } else {
            view.setAlpha(1f);
        }
    }

    private void fadeOut(final View view) {
        view.animate().cancel();
        if (!preferences.visualEffects()) {
            view.setVisibility(View.GONE);
            return;
        }
        view.animate().alpha(0f).setDuration(320L).withEndAction(() -> view.setVisibility(View.GONE)).start();
    }

    /** The card keeps OABL's own colours whatever the artwork: only the cover thumbnail shows it. */
    private void applyMediaColors() {
        if (palette == null) {
            return;
        }
        int ink = palette.ink;
        int muted = palette.muted;
        mediaTitle.setTextColor(ink);
        mediaSubtitle.setTextColor(muted);
        mediaSource.setTextColor(muted);
        ((TextView) findViewById(R.id.media_section_label)).setTextColor(muted);
        ((TextView) findViewById(R.id.media_open)).setTextColor(ink);
        android.content.res.ColorStateList controls = android.content.res.ColorStateList.valueOf(ink);
        ((ImageView) findViewById(R.id.media_previous)).setImageTintList(controls);
        ((ImageView) findViewById(R.id.media_next)).setImageTintList(controls);
        ((ImageView) findViewById(R.id.media_playlists)).setImageTintList(controls);
        ((ImageView) findViewById(R.id.media_expand)).setImageTintList(controls);
        ((ImageView) findViewById(R.id.media_eq)).setImageTintList(controls);
        ((TextView) findViewById(R.id.media_format)).setTextColor(muted);
        mediaPlayPause.setBackground(ripple(new CentredDisc(palette.accent), 0x33ffffff));
        mediaPlayPause.setImageTintList(android.content.res.ColorStateList.valueOf(
                palette.light ? Color.WHITE : palette.root));
        mediaProgress.colors(ink, palette.light ? 0x22102a2d : 0x2effffff, muted);
    }

    private Drawable ripple(Drawable content, int color) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(color), content, null);
    }

    /** The compact player keeps its transport buttons; detailed playback stays in the player. */
    private void updateMediaDensity() {
        // The "Now playing" header is only for the full player layout; elsewhere the song is one quiet line.
        findViewById(R.id.media_header).setVisibility(homeLayout != null && homeLayout.mode() == HomeDashboardMode.MEDIA ? View.VISIBLE : View.GONE);
        mediaProgress.setVisibility(View.GONE);
        renderHeroTools();
    }

    private long heroTrackId;
    private MusicLibrary.Track heroTrack;

    /**
     * The large player's extras: a star for the song OABL Music is playing, the file type next to the
     * source, and the equalizer for any player. The compact card keeps only the transport.
     */
    private void renderHeroTools() {
        if (palette == null) return;
        boolean hero = homeLayout != null && homeLayout.mode() == HomeDashboardMode.MEDIA;
        boolean own = hero && ownMusicCard();
        findViewById(R.id.media_eq).setVisibility(hero ? View.VISIBLE : View.GONE);
        ImageView star = findViewById(R.id.media_favorite);
        TextView format = findViewById(R.id.media_format);
        long id = own ? MusicClient.get(this).currentId() : 0;
        if (id != heroTrackId) {
            heroTrackId = id;
            try { heroTrack = id > 0 ? MusicLibrary.get(this).track(id) : null; } catch (RuntimeException unavailable) { heroTrack = null; }
        }
        MusicLibrary.Track track = own ? heroTrack : null;
        star.setVisibility(track != null ? View.VISIBLE : View.GONE);
        if (track != null) {
            int ink = palette.ink;
            star.setImageTintList(android.content.res.ColorStateList.valueOf(track.favorite ? palette.accent : (ink & 0x00ffffff) | 0x66000000));
            star.setContentDescription(track.favorite ? "Remove from favourites" : "Add to favourites");
        }
        String name = track == null || track.name == null ? "" : track.name;
        int dot = name.lastIndexOf('.');
        String type = dot >= 0 && name.length() - dot <= 6 ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : "";
        format.setText(type);
        format.setVisibility(type.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void toggleHeroFavourite() {
        MusicLibrary.Track track = heroTrack;
        if (track == null) return;
        try { MusicLibrary.get(this).favorite(track.id, !track.favorite); } catch (RuntimeException unavailable) { return; }
        track.favorite = !track.favorite;
        renderHeroTools();
        Toast.makeText(this, track.favorite ? "Added to favourites" : "Removed from favourites", Toast.LENGTH_SHORT).show();
    }

    private void setMediaPackageIcon(String packageName) {
        if(getPackageName().equals(packageName)){
            mediaArtwork.setImageResource(R.drawable.ic_music);
            mediaArtwork.setPadding(dp(8),dp(8),dp(8),dp(8));
            mediaArtwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
            return;
        }
        try {
            Drawable icon = getPackageManager().getApplicationIcon(packageName);
            mediaArtwork.setImageDrawable(icon);
            mediaArtwork.setPadding(dp(8), dp(8), dp(8), dp(8));
            mediaArtwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
        } catch (PackageManager.NameNotFoundException error) {
            mediaArtwork.setImageDrawable(null);
        }
    }

    /** Under the date once the forecast is in (the Weather tile steps aside): "28° Clear sky · ↑31° ↓24°". */
    private void renderWeatherLine(DashboardData.Forecast forecast) {
        TextView line = findViewById(R.id.weather_status);
        if (forecast == null || palette == null) { line.setVisibility(View.GONE); return; }
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(String.format(Locale.US, "%d° %s · ↑%d° ↓%d°",
                Math.round(forecast.temperature), DashboardData.weatherLabel(forecast.code), Math.round(forecast.high), Math.round(forecast.low)));
        String rain = forecast.rainAlert(System.currentTimeMillis());
        if (rain != null) {
            int at = text.length();
            text.append("  ☂ ").append(rain);
            text.setSpan(new ForegroundColorSpan(palette.accent), at, text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        line.setText(text);
        line.setTextColor(palette.muted);
        line.setMaxLines(1);
        line.setVisibility(View.VISIBLE);
    }


    private void renderDashboardData() {
        refreshHome(); // also notices a link that went quiet, which sends no change notification
        DashboardData data = DashboardData.get(this);
        ((TextView)findViewById(R.id.speed_scale)).setText("0–" + preferences.vehicleMaximumSpeed());
        android.location.Location fix = data.freshFix() ? data.fix : null;
        String roadText = data.roadLabel();
        // The speed card never shows the road: the navigation card leads with it, or with the next turn.
        findViewById(R.id.current_road).setVisibility(View.GONE);
        renderCarConditions();
        renderCarStrip();
        ((HeadingStripView)findViewById(R.id.heading_strip)).heading(fix != null && fix.hasBearing(),
                fix == null ? 0f : fix.getBearing(), preferences.visualEffects());
        ((TextView)findViewById(R.id.gps_health)).setText("GPS · " + data.satellitesUsed + " / " + data.satellitesSeen + " satellites · " + (data.freshFix() ? "Fix ready" : "Waiting for fix"));
        TextView limit = findViewById(R.id.speed_limit);
        int reminder = speedLimit();
        gaugeLimitNote();
        // The gauge draws the limit as a road-sign badge; this text stays for accessibility.
        limit.setVisibility(View.GONE);
        limit.setText(reminder > 0 ? "Speed reminder " + reminder + " km/h" : "");
        SpeedGaugeView gauge = findViewById(R.id.speed_cluster);
        gauge.setContentDescription((gauge.known() ? gauge.speed() + " km/h" : "Speed unknown, no GPS fix") + (reminder > 0 ? ", "
                + (gauge.limitNote() == null ? "speed reminder" : gauge.limitNote().toLowerCase(Locale.ROOT))
                + " " + reminder + " km/h" : ""));
        renderGpsDrive();
        updateHere(data, fix, roadText, gauge);
        renderEnergyCard();
        if (renderCarTrip()) return;
        ((TextView)findViewById(R.id.telemetry_label)).setText(speedLabel(false));
        boolean speedStale = android.os.SystemClock.elapsedRealtime() - lastSpeedAt > 3000;
        if (!data.freshFix() || speedStale) {
            // No fix: the speed is unknown, never shown as 0.
            // The road line already says "Waiting for GPS"; here only what is new: how many satellites so far.
            setSpeedStatus(data.satellitesUsed > 0 ? data.satellitesUsed + (data.satellitesUsed == 1 ? " SATELLITE" : " SATELLITES") : null);
            gauge.unknown(reminder, palette);
        } else {
            gauge.limit(reminder, palette);
        }
    }

    /** Weather/network work is intentionally outside the fast speed/navigation rendering path. */
    private void refreshWeatherSurfaces() {
        DashboardData data = DashboardData.get(this);
        data.refreshWeather();
        boolean weatherUp = HomeLayout.weatherInHeader(this);
        if (weatherUp != weatherInHeaderShown) { weatherInHeaderShown = weatherUp; applyCompactLayoutIfNeeded(); }
        renderWeatherLine(weatherUp ? data.forecast : null);
        renderWeatherCard();
    }

    /**
     * The drive card from the car: it follows the connected car even when its speed is briefly
     * missing, while the gauge alone falls back to GPS. True when the car drew both card and gauge.
     */
    private TripComputer.Trip gpsTrip;
    private long gpsTripAt;
    /** The drive in progress, else the last one logged by GPS (kept 10 s: the log is read from storage). */
    private TripComputer.Trip latestGpsTrip() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (gpsTripAt == 0 || now - gpsTripAt > 10_000) {
            gpsTripAt = now;
            try {
                java.util.List<TripComputer.Trip> trips = TripComputer.get(this).history();
                gpsTrip = trips.isEmpty() ? null : trips.get(0);
            } catch (RuntimeException unavailable) { gpsTrip = null; }
        }
        return gpsTrip;
    }

    /** The drive card without the car: the current GPS drive while moving, otherwise the last one. Never the since-reset trip meter. */
    private void renderGpsDrive() {
        TripComputer.Trip trip = latestGpsTrip();
        ((TextView)findViewById(R.id.trip_label)).setText(trip != null && trip.end == 0 ? "THIS DRIVE" : "LAST DRIVE");
        double moving = trip == null ? 0 : trip.minutes;
        ((TextView)findViewById(R.id.trip_distance)).setText(trip == null ? "—" : String.format(Locale.US, "%.1f", trip.kilometers));
        ((TextView)findViewById(R.id.trip_time)).setText(trip == null ? "—" : moving < 100 ? String.format(Locale.US, "%.0f", moving)
                : String.format(Locale.US, "%d:%02d", (int) (moving / 60), (int) (moving % 60)));
        ((TextView)findViewById(R.id.trip_time_label)).setText(tripLabel(moving < 100 ? "MIN MOVING" : "H MOVING"));
        ((TextView)findViewById(R.id.trip_average)).setText(trip == null || trip.minutes <= 0 ? "—" : String.format(Locale.US, "%.0f", trip.averageKph()));
        ((TextView)findViewById(R.id.trip_peak)).setText(trip == null ? "—" : String.format(Locale.US, "%.0f", trip.peakKph));
    }

    private boolean renderCarTrip() {
        HomeEnergySnapshot car = home;
        boolean carSpeed = renderCarSpeed();
        if (car == null || !car.car) return carSpeed;
        if (car.connected || car.latestDrive != null) {
            ((TextView)findViewById(R.id.trip_note)).setText((car.connected
                    ? (Double.isFinite(car.odometer) ? String.format(Locale.US, "ODO %,.0f km", car.odometer) : "ODO —")
                    : "Car not connected")
                    + (car.unpricedCharges > 0 ? " · " + car.unpricedCharges + (car.unpricedCharges == 1 ? " charge needs" : " charges need") + " a price" : "")
                    + " · Trip history ›");
            ((TextView)findViewById(R.id.trip_since)).setText("CAR");
            ((TextView)findViewById(R.id.trip_label)).setText(car.openCharge != null ? "THIS CHARGE" : car.driveCurrent ? "THIS DRIVE" : "LAST DRIVE");
            if (car.openCharge != null) renderChargeMetrics(car.openCharge);
            else {
                setTripLabels("KM","AVG KM/H","PEAK KM/H");
                CarEnergyTracker.Event carDrive = car.latestDrive;
                ((TextView)findViewById(R.id.trip_distance)).setText(carDrive==null?"—":String.format(Locale.US,"%.1f",carDrive.km));
                ((TextView)findViewById(R.id.trip_time)).setText(carDrive==null?"—":String.format(Locale.US,"%.0f",carDrive.movingSeconds/60));
                ((TextView)findViewById(R.id.trip_time_label)).setText(tripLabel("MIN MOVING"));
                ((TextView)findViewById(R.id.trip_average)).setText(carDrive==null||carDrive.movingSeconds<=0?"—":String.format(Locale.US,"%.0f",carDrive.averageKph()));
                ((TextView)findViewById(R.id.trip_peak)).setText(carDrive==null?"—":String.format(Locale.US,"%.0f",carDrive.peakSpeed));
            }
            return carSpeed;
        }
        if (carSpeed) return true;
        ((TextView)findViewById(R.id.trip_since)).setText("GPS");
        setTripLabels("KM","AVG KM/H","PEAK KM/H");
        renderDrivePerformance(null);
        ((TextView)findViewById(R.id.trip_note)).setText("GPS only · Trip history ›");
        return false;
    }

    private String renderedEnergyKey;
    /** "SPEED · GPS" or "SPEED · CAR": the source is said once, here, and not again under the number. */
    private String speedLabel(boolean car) { return (drivingMode ? "DRIVING" : "SPEED") + " · " + (car ? "CAR" : "GPS"); }

    /** A line under the unit only when there is something to say (no fix, no car speed); empty hides it. */
    private void setSpeedStatus(String text) {
        speedStatus.setText(text == null ? "" : text);
        speedStatus.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private boolean renderCarSpeed() {
        if(palette==null||speedStatus==null)return false;
        HomeEnergySnapshot car=home;if(car==null||!car.car)return false;
        // No current car speed (link down, or older than 3 s): GPS takes over, labelled as GPS.
        double carSpeed = car.currentSpeed(System.currentTimeMillis());
        SpeedReading speed = SpeedReading.select(true, carSpeed, 0, false);
        if(!speed.known())return false;
        SpeedGaugeView gauge=findViewById(R.id.speed_cluster);
        if(speed.known()){
            int kph=speed.kph;gauge.reading(carSpeed,speedLimit(),palette);setSpeedStatus(null);
            overspeedChime(kph);
            gauge.setContentDescription(kph+" km/h from car");
            int threshold=preferences.drivingModeKmh();if(threshold>0&&kph>=threshold){lastFastAt=android.os.SystemClock.elapsedRealtime();if(!drivingMode)setDrivingMode(true);}
            if(kph>=5)hideScreensaver();
        }else{gauge.unknown(speedLimit(),palette);setSpeedStatus("NO CAR SPEED");gauge.setContentDescription("Car speed unavailable");}
        ((TextView)findViewById(R.id.telemetry_label)).setText(speedLabel(true));
        return true;
    }
    private long energyRenderedAt, aliveAt;
    private void renderCarConditions() {
        HomeEnergySnapshot car = home;
        View conditions = findViewById(R.id.car_conditions);
        conditions.setVisibility(car != null && car.car && car.connected ? View.VISIBLE : View.GONE);
        if (car == null || !car.car) return;
        double temperature = car.temperature;
        TextView outside = findViewById(R.id.outside_temperature);
        if (Double.isFinite(temperature)) {
            String value = String.format(Locale.US, temperature == Math.rint(temperature) ? "%.0f°C" : "%.1f°C", temperature);
            outside.setText("OUT " + value);
            outside.setContentDescription("Outside temperature from car: " + value);
        } else {
            outside.setText("OUT —"); outside.setContentDescription("Outside temperature from car unavailable");
        }
        DriveMode mode = car.driveMode;
        TextView label = findViewById(R.id.drive_mode_label);
        label.setText(mode == null ? "—" : mode.label);
        label.setPadding(dp(9),dp(4),dp(9),dp(4));
        int modeColor=mode==null?palette.muted:mode.accent;
        label.setTextColor(modeColor);
        backgroundIfChanged(label, (mode == null) + "|" + palette.light + "|" + modeColor,
                () -> LauncherTheme.rounded(this,mode==null?Color.TRANSPARENT:(palette.light?0x10102a2d:0x18ffffff),14,mode==null?0:1,modeColor));
        label.setContentDescription(mode == null ? "Car drive mode unavailable" : "Car drive mode: " + mode.label);
    }
    /** Each render re-made these chips' drawables, redrawing them every tick; replace one only when it changes. */
    private final java.util.Map<View, String> backgroundKeys = new java.util.WeakHashMap<>();
    private void backgroundIfChanged(View view, String key, java.util.function.Supplier<Drawable> make) {
        if (key.equals(backgroundKeys.get(view))) return;
        backgroundKeys.put(view, key);
        view.setBackground(make.get());
    }
    /**
     * The header strip: a dot for the car link (green online, amber syncing, red offline) with its
     * words (car connected, idle, syncing, car offline), and a 12 V note only when that battery is low.
     */
    /** The header stays quiet about the car link (it's in the status dialog); only a low 12 V battery shows here. */
    private void renderCarStrip() {
        View strip = findViewById(R.id.car_strip);
        HomeEnergySnapshot car = home;
        if (strip == null || palette == null) return;
        boolean lowTwelve = car != null && car.car && car.lowVoltage != null && car.lowVoltage.low;
        strip.setVisibility(lowTwelve ? View.VISIBLE : View.GONE);
        if (!lowTwelve) return;
        TextView note = findViewById(R.id.car_link_note);
        note.setVisibility(View.VISIBLE);
        note.setText(String.format(Locale.US, "12 V %.1f", car.lowVoltage.volts));
        note.setTextColor(batteryLevelColor(10));
        strip.setOnClickListener(v -> showTravelStatus());
    }

    /** What a tap on the battery card does now: 0 Energy page, 1 check-in, 2 end, 3 recovery, 4 starting %. */
    private int energyAction;
    private final Runnable energyChanged = this::refreshHome;

    /** The last car snapshot from :energy; Home draws from it and never calls :energy on the UI thread. */
    private volatile HomeEnergySnapshot home;
    private final java.util.concurrent.ExecutorService homeFetch = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean homeFetching = new java.util.concurrent.atomic.AtomicBoolean();
    private long homeRequestedAt;
    /** One request for everything Home shows from the car, at most every 0.4 s, off the UI thread. */
    private boolean homeRefreshQueued;
    /** A change that arrived during a fetch or inside the 0.4 s gap: fetch once more so the newest speed is drawn. */
    private final Runnable queuedHomeRefresh = () -> { homeRefreshQueued = false; refreshHome(); };
    private void refreshHome() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (homeFetching.get() || now - homeRequestedAt < 400) {
            if (!homeRefreshQueued) {
                homeRefreshQueued = true;
                handler.postDelayed(queuedHomeRefresh, Math.max(50, 400 - (now - homeRequestedAt)));
            }
            return;
        }
        if (!homeFetching.compareAndSet(false, true)) return;
        homeRequestedAt = now;
        homeFetch.execute(() -> {
            HomeEnergySnapshot fetched = null;
            try { fetched = EnergyClient.home(this); } catch (RuntimeException unavailable) { /* :energy restarting; next change retries */ }
            HomeEnergySnapshot result = fetched;
            handler.post(() -> {
                homeFetching.set(false);
                if (homeRefreshQueued) { handler.removeCallbacks(queuedHomeRefresh); homeRefreshQueued = false; refreshHome(); }
                if (result == null || ActivityState.isGone(this)) return;
                home = result; renderedEnergyKey = null;
                pendingChargeReview = result.promptCharge;
                applyDriveModeTheme(); renderEnergyCard(); renderCarSpeed(); renderCarConditions(); renderCarStrip(); renderCarTrip();
                renderChargeReviewPill();
            });
        });
    }
    private Runnable energyChangedCallback() { return () -> { renderedEnergyKey=null;renderEnergyCard(); }; }

    /** A completed charge waits as a small, non-blocking action instead of opening a dialog on park. */
    private void renderChargeReviewPill() {
        View pill = findViewById(R.id.charge_review_pill);
        HomeEnergySnapshot car = home;
        boolean early = pendingChargeReview == null && car != null && Double.isFinite(car.earlyFrom);
        if (pill == null || (pendingChargeReview == null && !early) || moving() || startupNotice != null) {
            if (pill != null) pill.setVisibility(View.GONE);
            return;
        }
        if (early) {
            // Known at once from the battery level; the history brings the times, kWh and the review.
            String text = "Charge detected · " + Math.round(car.earlyFrom) + "% → " + Math.round(car.earlyTo) + "%";
            ((TextView)findViewById(R.id.charge_review_text)).setText(text);
            pill.setContentDescription(text + ". Details are syncing from the car.");
            ((TextView)findViewById(R.id.charge_review_action)).setText("Syncing");
            pill.setVisibility(View.VISIBLE);
            return;
        }
        ((TextView)findViewById(R.id.charge_review_action)).setText("Add price");
        CarEnergyTracker.Event event = pendingChargeReview.event;
        String amount = Double.isFinite(event.gain()) ? " · +" + EnergyModel.percent(event.gain()) + " points" : "";
        ((TextView)findViewById(R.id.charge_review_text)).setText("Charge complete" + amount);
        pill.setContentDescription("Charge complete" + amount + ". Add charging price.");
        pill.setVisibility(View.VISIBLE);
    }

    private void reviewPendingCharge() {
        HomeEnergySnapshot car = home;
        if (pendingChargeReview == null && car != null && Double.isFinite(car.earlyFrom)) {
            Toast.makeText(this, "Charged while the box was off. The details are still syncing from the car; you can add the price in a few minutes.", Toast.LENGTH_LONG).show();
            return;
        }
        if (pendingChargeReview == null || moving()) return;
        CarChargeDialogs.show(this, palette, pendingChargeReview, () -> {
            pendingChargeReview = null;
            renderChargeReviewPill();
            refreshHome();
        });
    }

    /**
     * Repaints the accent to match the car's drive mode when that setting is on and a fresh mode is
     * live (Eco green, Comfort blue, Sport red); clears the override otherwise so the chosen accent
     * returns. Cheap to call often — it only re-applies the appearance when the colour actually moves.
     */
    private void applyDriveModeTheme() {
        HomeEnergySnapshot car = home;
        int want = 0;
        if (car != null && car.matchDriveMode && car.driveMode != null) want = car.driveMode.accent;
        if (want != LauncherTheme.driveModeAccent()) {
            LauncherTheme.setDriveModeAccent(want);
            applyAppearance(true);
        }
    }

    /**
     * Home's battery glance. The last entered % stays the headline with its time; anything
     * projected since is labelled as an estimate. The box's own battery is never shown here.
     */
    private void renderEnergyCard() {
        if(EnergyDataActivity.BUSY.get())return;
        if (palette == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (renderedEnergyKey != null && now - energyRenderedAt < 5_000L) return;
        energyRenderedAt = now;
        EnergyJournal journal = EnergyJournal.get(this);
        if (now - aliveAt > 60_000L) { aliveAt = now; journal.alive(); }
        HomeEnergySnapshot home=this.home;if(home==null){refreshHome();return;}
        updateDashboardConnection(home.connected);if(home.car){renderHomeEnergy(home);return;}
        EnergyJournal.Status s = journal.status();
        EnergyModel.Profile profile = s.profile;
        boolean driven = s.lastReading != null && s.kmSinceReading >= 0.1;
        String label = s.recovery != null ? "BATTERY · UNFINISHED DRIVE" : s.active ? "BATTERY · DRIVING" : "BATTERY";
        EnergyModel.Result shown = s.drive != null ? s.drive : s.allTime.validDrop > 0 ? s.allTime.result(profile) : null;
        String bench = shown != null && shown.verdict != EnergyModel.Verdict.NOT_ENOUGH_DATA
                ? EnergyModel.signed(shown.differencePercent).replace(" ", "") + " VS " + profile.benchmark().label.toUpperCase(Locale.US)
                : "VS " + profile.benchmarkLabel().toUpperCase(Locale.US);
        String soc = s.lastReading == null ? "—" : EnergyModel.percent(s.lastReading.percent) + "%";
        String socNote = s.lastReading == null ? "not entered" : "entered " + EnergyDialogs.when(this, s.lastReading.time);
        // A fresh live reading from the car leads; the entered % stays underneath (never overwritten).
        // Manual mode: the car path returned above, so there is no live car battery here.
        boolean live = false;
        boolean carBattery = false;
        String range, result,rangeNote="Range to reserve · estimated";
        if (carBattery) {
            energyAction = 0;
            if (live) {
                double carRange = home.range;
                range = Double.isNaN(carRange) ? "Live battery from car" : Math.round(carRange) + " km · car range";
                result = "Updates automatically · tap for energy and connection";
            } else {
                label = "BATTERY · WAITING FOR CAR";
                soc = "—";
                socNote = "No fresh car reading";
                range = "Waiting for OABL Link";
                result = "Tap to check the connection or choose manual entry";
            }
        } else if (s.recovery != null) {
            range = String.format(Locale.US, "%.1f km", s.km());rangeNote="Recorded · unfinished drive";
            result = "The box lost power · tap to end, continue or keep as a trip";
            energyAction = 3;
        } else if (s.active && s.needsStartPercent) {
            range = String.format(Locale.US, "%.1f km", s.km());rangeNote="No starting battery reading";
            result = "Driving without a reading · tap to add it or end the drive";
            energyAction = 4;
        } else if (s.lastReading != null) {
            range = "≈ " + Math.round(driven ? s.rangeKm : s.reserveRangeKm) + " km";rangeNote="To "+EnergyModel.percent(profile.reservePercent)+"% · estimated";
            if (s.active && s.askEnd) { result = "Parked? Tap to end the drive and enter the %"; energyAction = 2; }
            else if (s.active) {
                result = driven ? "≈ " + Math.round(s.estimatedPercent) + "% now, estimated · " + String.format(Locale.US, "%.1f km", s.kmSinceReading)
                        : (s.learned ? "Range learned from your drives" : profile.benchmark().label + "-based range until drives are learned");
                energyAction = 0;
            } else if (s.askStart) { result = "Tap before you drive: enter the battery %"; energyAction = 1; }
            else { result = shown != null ? EnergyModel.verdictText(shown.verdict) : "Tap for drives and battery"; energyAction = 0; }
        } else {
            range = "— km";rangeNote="Battery check-in";
            result = s.askStart || !s.active ? "Enter the battery % before you drive" : "Tap for drives and battery";
            energyAction = s.active ? 0 : 1;
        }
        // Near the reserve by estimate: the navigation card puts chargers first.
        double estimate = carBattery ? home.soc : s.lastReading == null ? Double.NaN : driven ? s.estimatedPercent : s.lastReading.percent;
        boolean low = !Double.isNaN(estimate) && (estimate <= profile.reservePercent + 15
                || (!carBattery && !Double.isNaN(s.rangeKm) && driven && s.rangeKm < 40));
        if (low != batteryLow) { batteryLow = low; renderedChipsKey = null; renderNavigationChips(); }
        String vehicle = journal.store().vehicleName().toUpperCase(Locale.getDefault());
        String key = label + bench + soc + socNote + range + result + energyAction + vehicle + palette.accent + palette.light
                + home.referenceText + (s.lastReading == null ? "" : s.estimatedPercent);
        if (key.equals(renderedEnergyKey)) return;
        renderedEnergyKey = key;
        ((TextView) findViewById(R.id.vehicle_name)).setText(vehicle);
        ((TextView) findViewById(R.id.energy_label)).setText(label);
        ((TextView) findViewById(R.id.energy_benchmark)).setText(bench);
        readingText(R.id.energy_soc,soc,"%");
        ((TextView) findViewById(R.id.energy_soc_note)).setText(socNote);
        readingText(R.id.energy_range,range,"km");
        TextView resultView = findViewById(R.id.energy_result);
        resultView.setText(result);
        resultView.setTextColor(energyAction == 0 ? palette.muted : palette.accent);
        ((EnergyBarView) findViewById(R.id.energy_bar)).set(
                carBattery ? (live ? (float) home.soc : -1) : (s.lastReading == null ? -1 : (float) s.lastReading.percent),
                carBattery ? -1 : (driven ? (float) s.estimatedPercent : -1),
                (float) profile.reservePercent, palette.accent, palette.light ? 0x1f13202a : 0x24ffffff, palette.muted);
        if (carBattery) findViewById(R.id.energy_bar).setContentDescription(live ? "Battery " + soc + " from car" : "Waiting for car battery reading");
        findViewById(R.id.energy_card).setContentDescription(label + ". " + soc + " " + socNote + ". " + range + ". " + result);
        ((TextView)findViewById(R.id.energy_reference_row)).setText(home.referenceText);
        findViewById(R.id.energy_reference_row).setVisibility(View.GONE);
        ((TextView)findViewById(R.id.energy_range_note)).setText(rangeNote);
        renderBatteryLevel(s.lastReading==null?Double.NaN:s.lastReading.percent,socNote);
        renderBatteryStats(s.lastReading==null?Double.NaN:s.lastReading.percent,profile.usableKwh,profile.reservePercent,null);
        renderTripEnergyStats(null);
        renderDrivePerformance(null);
    }

    /** When the car last sent a fresh reading; a short gap must not flip Home between layouts. */
    private long carLiveAt;
    private boolean weatherInHeaderShown;
    void renderHomeEnergy(HomeEnergySnapshot data){
        if(palette==null)return;energyAction=0;
        long clock=android.os.SystemClock.elapsedRealtime();
        if(data.live)carLiveAt=clock;
        // A link with no data behind it shows dashes in two big cards; give those cards' space to
        // the player instead, once the data has been missing for 20 s (or never arrived).
        updateDashboardConnection(data.connected&&(data.live||(carLiveAt!=0&&clock-carLiveAt<20_000)));
        boolean live=data.live&&Double.isFinite(data.soc)&&data.soc>=0&&data.soc<=100;
        // Offline, the level the car last left behind is shown, dimmed and marked with when it was seen;
        // with nothing known the whole card steps aside instead of showing dashes.
        boolean stale=!live&&Double.isFinite(data.lastSoc)&&data.lastSoc>=0&&data.lastSoc<=100&&data.lastSocAt>0;
        double shown=live?data.soc:stale?data.lastSoc:Double.NaN;
        boolean haveRange=data.connected&&Double.isFinite(data.range)&&data.range>=0;
        if(homeLayout!=null)homeLayout.energyShown(live||stale);
        String soc=live||stale?EnergyModel.percent(shown)+"%":"—%";
        String range=haveRange?Math.round(data.range)+" km":"— km";
        String note=stale?"Last seen "+seenAt(data.lastSocAt):!live?"No fresh car reading":data.charging?"Charging":data.soc<=15?"Low battery":data.soc<=30?"Moderate":"Good level";
        String action=live?"Updated "+Math.max(0,data.ageSeconds)+" s ago ›":stale?"Car offline · tap for status":"No car data · tap for status";
        ((TextView)findViewById(R.id.vehicle_name)).setText(data.vehicle);
        ((TextView)findViewById(R.id.energy_label)).setText(data.charging?"BATTERY · CHARGING":"BATTERY & RANGE");
        ((TextView)findViewById(R.id.energy_benchmark)).setText(stale?"LAST KNOWN":"FROM CAR");
        readingText(R.id.energy_soc,soc,"%");((TextView)findViewById(R.id.energy_soc_note)).setText(note);
        ((TextView)findViewById(R.id.energy_soc)).setTextColor(stale?palette.muted:palette.ink);
        readingText(R.id.energy_range,range,"km");
        TextView rangeNoteView=findViewById(R.id.energy_range_note);String rangeNote=rangeNote(data,shown);
        if(!rangeNote.contentEquals(rangeNoteView.getText()))rangeNoteView.setText(rangeNote);
        // No range to show while offline: the level takes the whole card instead of a column of dashes.
        findViewById(R.id.energy_range_column).setVisibility(haveRange||live?View.VISIBLE:View.GONE);
        // A low resting 12 V battery is worth a glance before the car is left parked for long.
        boolean lowTwelve=data.lowVoltage!=null&&data.lowVoltage.low;
        String reference=lowTwelve?(data.referenceText.isEmpty()?data.lowVoltage.text():data.referenceText+"\n"+data.lowVoltage.text()):data.referenceText;
        // While charging, the car's own charge detail (power, target, time to full) is the useful line.
        if(!lowTwelve&&data.charging&&data.chargeDetail!=null&&!data.chargeDetail.isEmpty()&&!data.chargeDetail.startsWith("Drive comparison"))
            reference=data.chargeDetail+(reference.isEmpty()?"":"\n"+reference);
        ((TextView)findViewById(R.id.energy_reference_row)).setText(reference);
        // The compact battery card beside the map has no room for the reference line; low 12 V shows in the 12 V stat.
        findViewById(R.id.energy_reference_row).setVisibility(View.GONE);
        ((TextView)findViewById(R.id.energy_reference_row)).setTextColor(lowTwelve?batteryLevelColor(10):palette.muted);
        ((TextView)findViewById(R.id.energy_result)).setText(action);((TextView)findViewById(R.id.energy_result)).setTextColor(palette.muted);
        // The Home gauge: the car's level in its traffic-light colour, and while charging the target.
        EnergyBarView gauge=findViewById(R.id.energy_bar);
        gauge.set(Double.isFinite(shown)?(float)shown:-1,-1,-1,stale?palette.muted:batteryLevelColor(live?data.soc:Double.NaN),palette.light?0x1f13202a:0x24ffffff,palette.muted);
        gauge.setTarget(data.charging&&Double.isFinite(data.chargeTarget)?(float)data.chargeTarget:-1);
        renderBatteryLevel(shown,note,stale?palette.muted:batteryLevelColor(shown));
        renderBatteryStats(shown,data.usableCapacity,data.reservePercent,data.lowVoltage);
        if(data.lowReserve!=batteryLow){batteryLow=data.lowReserve;renderedChipsKey=null;renderNavigationChips();}
        renderTripEnergyStats(data);
        renderDrivePerformance(data.performance);
        if(data.charging){((TextView)findViewById(R.id.trip_verdict)).setText("Charging");((TextView)findViewById(R.id.trip_performance)).setText(data.chargeDetail.isEmpty()?"Drive comparison resumes on the next drive":data.chargeDetail);}
        findViewById(R.id.energy_card).setContentDescription("Battery "+soc+". "+note+". "+range+" from car. "+data.referenceText+". "+action);
        renderedEnergyKey="car:"+data.ageSeconds+":"+data.soc+":"+palette.light;
    }
    /** While the car charges, the drive row shows the charge: energy added, time, power and % gained. */
    private void renderChargeMetrics(CarEnergyTracker.Event charge){
        double kwh=charge.measuredKwh(),minutes=CarChargeEnergy.durationSeconds(charge)/60,power=home==null?Double.NaN:home.power;
        setTripLabels("KWH ADDED","KW NOW","% GAINED");
        ((TextView)findViewById(R.id.trip_distance)).setText(Double.isFinite(kwh)?String.format(Locale.US,"%.2f",kwh):"—");
        ((TextView)findViewById(R.id.trip_time)).setText(minutes<100?String.format(Locale.US,"%.0f",minutes):String.format(Locale.US,"%d:%02d",(int)(minutes/60),(int)(minutes%60)));
        ((TextView)findViewById(R.id.trip_time_label)).setText(tripLabel(minutes<100?"MIN CHARGING":"H CHARGING"));
        ((TextView)findViewById(R.id.trip_average)).setText(Double.isFinite(power)?String.format(Locale.US,"%.1f",power):"—");
        double gain=charge.gain();
        ((TextView)findViewById(R.id.trip_peak)).setText(Double.isFinite(gain)?"+"+EnergyModel.percent(gain):"—");
    }
    private void setTripLabels(String distance,String average,String peak){
        ((TextView)findViewById(R.id.trip_distance_label)).setText(tripLabel(distance));
        ((TextView)findViewById(R.id.trip_average_label)).setText(tripLabel(average));
        ((TextView)findViewById(R.id.trip_peak_label)).setText(tripLabel(peak));
    }
    private String tripLabel(String value) {
        String[][] labels={{"KM","DISTANCE\nKM"},{"AVG KM/H","AVG\nKM/H"},{"PEAK KM/H","PEAK\nKM/H"},
                {"MIN MOVING","MOVING\nMIN"},{"H MOVING","MOVING\nH"},{"KWH ADDED","ADDED\nKWH"},
                {"KW NOW","NOW\nKW"},{"% GAINED","GAINED\n%"},{"MIN CHARGING","CHARGING\nMIN"},{"H CHARGING","CHARGING\nH"}};
        for(String[] pair:labels)if(value.equals(pair[0])||value.equals(pair[1]))return homeLayout!=null&&homeLayout.narrow()?pair[1]:pair[0];
        return value;
    }
    private void updateDashboardConnection(boolean connected){
        if(dashboardLinkConnected==connected)return;
        dashboardLinkConnected=connected;applyCompactLayoutIfNeeded();
    }
    /** "5:04 PM", or "Mon 5:04 PM" when it was not today. */
    private String seenAt(long time){
        int flags=android.text.format.DateUtils.FORMAT_SHOW_TIME;
        if(!android.text.format.DateUtils.isToday(time))flags|=android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY|android.text.format.DateUtils.FORMAT_ABBREV_ALL;
        return android.text.format.DateUtils.formatDateTime(this,time,flags);
    }
    private void readingText(int id,String text,String unit){
        android.text.SpannableString styled=new android.text.SpannableString(text);int at=text.lastIndexOf(unit);
        if(at>=0)styled.setSpan(new android.text.style.RelativeSizeSpan(.34f),at,at+unit.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ((TextView)findViewById(id)).setText(styled);
    }
    private int batteryLevelColor(double value){
        return !Double.isFinite(value)?palette.muted:value<=15?(palette.light?0xffba3d35:0xffff9d91):value<=30?(palette.light?0xff9a7400:0xfff2cf5c):(palette.light?0xff34724f:0xff94d6aa);
    }
    private double batteryIconValue=Double.MIN_VALUE;
    private int batteryIconColor;
    private void renderBatteryLevel(double value,String note){renderBatteryLevel(value,note,batteryLevelColor(value));}
    private void renderBatteryLevel(double value,String note,int noteColor){
        // The gauge is shown whenever there is a level to draw, so an unread battery leaves no empty strip.
        findViewById(R.id.energy_bar).setVisibility(Double.isFinite(value)?View.VISIBLE:View.GONE);
        ImageView icon=findViewById(R.id.energy_battery_icon);int color=batteryLevelColor(value);
        // Home re-renders on every refresh; a new drawable repaints the card and sends another frame to the car.
        if(Double.compare(value,batteryIconValue)!=0||color!=batteryIconColor){
            batteryIconValue=value;batteryIconColor=color;
            icon.setImageDrawable(new BatteryLevelDrawable(value,color));icon.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        }
        icon.setContentDescription(Double.isFinite(value)?"Battery "+EnergyModel.percent(value)+"%, "+note:"Battery level unavailable");
        ((TextView)findViewById(R.id.energy_soc_note)).setTextColor(noteColor);
    }
    private void renderBatteryStats(double soc,double capacity,double reserve,CarBatteryHealth.Status twelveVolt){
        boolean usable=Double.isFinite(soc)&&Double.isFinite(capacity)&&capacity>0;
        setDashboardStat(R.id.energy_usable,usable?String.format(Locale.US,"USABLE LEFT\n%.1f kWh",soc*capacity/100):null,palette.ink);
        setDashboardStat(R.id.energy_reserve,Double.isFinite(reserve)&&Double.isFinite(soc)?"KEEP ABOVE\n"+EnergyModel.percent(reserve)+"%":null,palette.ink);
        setDashboardStat(R.id.energy_12v,twelveVolt!=null&&Double.isFinite(twelveVolt.volts)?String.format(Locale.US,"12 V\n%.1f V",twelveVolt.volts):null,
                twelveVolt!=null&&twelveVolt.low?batteryLevelColor(10):palette.ink);
        boolean any=usable||Double.isFinite(reserve)&&Double.isFinite(soc)||twelveVolt!=null&&Double.isFinite(twelveVolt.volts);
        findViewById(R.id.energy_stats).setVisibility(any?View.VISIBLE:View.GONE);
    }
    private void renderTripEnergyStats(HomeEnergySnapshot data){
        CarEnergyTracker.Event drive=data==null?null:data.latestDrive;
        double capacity=data==null?Double.NaN:data.usableCapacity;
        // Short drives read as noise (0.12 kWh used, 3.9 kWh/100 km), so the strip waits for 5 km.
        boolean longEnough=drive!=null&&drive.km>=MIN_ECONOMY_KM;
        double kwh=longEnough?drive.netKwh(capacity):Double.NaN;
        double per100=longEnough&&Double.isFinite(kwh)?kwh/drive.km*100:Double.NaN;
        double regen=longEnough?drive.regenKwh:Double.NaN;
        HomeCost cost=data==null?null:data.cost;
        // The headline already says the consumption unless it is showing how the drive compares.
        boolean headlineIsComparison=data!=null&&data.performance!=null&&data.performance.eligible;
        setDashboardStat(R.id.trip_consumption,headlineIsComparison&&Double.isFinite(per100)?String.format(Locale.US,"CONSUMPTION\n%.1f kWh/100 km",per100):null,palette.ink);
        setDashboardStat(R.id.trip_energy,Double.isFinite(kwh)?String.format(Locale.US,"USED\n%.2f kWh",kwh):null,palette.ink);
        setDashboardStat(R.id.trip_regen,Double.isFinite(regen)&&regen>.001?String.format(Locale.US,"REGEN\n%.2f kWh",regen):null,batteryLevelColor(68));
        String money=longEnough&&cost!=null&&Double.isFinite(cost.sessionCost)?"COST\n≈ "+HomeCost.money(cost.sessionCost)+(cost.currency.isEmpty()?"":" "+cost.currency):null;
        setDashboardStat(R.id.trip_cost,money,palette.ink);
        boolean any=Double.isFinite(kwh)||Double.isFinite(regen)&&regen>.001||money!=null||headlineIsComparison&&Double.isFinite(per100);
        findViewById(R.id.trip_energy_stats).setVisibility(data!=null&&!data.charging&&any?View.VISIBLE:View.GONE);
        // The battery card's second row, now that the drive card is gone: odometer, and the latest drive's regen and cost.
        double odometer=data==null?Double.NaN:data.odometer;
        String odo=data!=null&&data.connected&&Double.isFinite(odometer)?String.format(Locale.US,"ODOMETER\n%,.0f km",odometer):null;
        String regenText=Double.isFinite(regen)&&regen>.001?String.format(Locale.US,"REGEN\n%.2f kWh",regen):null;
        String economy=Double.isFinite(per100)?String.format(Locale.US,"ECONOMY\n%.1f kWh/100 km",per100):null;
        setDashboardStat(R.id.energy_odometer,odo,palette.ink);
        setDashboardStat(R.id.energy_economy,economy,palette.ink);
        setDashboardStat(R.id.energy_regen,regenText,batteryLevelColor(68));
        setDashboardStat(R.id.energy_cost,money,palette.ink);
        findViewById(R.id.energy_drive_stats).setVisibility(odo!=null||economy!=null||regenText!=null||money!=null?View.VISIBLE:View.GONE);
    }
    private void setDashboardStat(int id,String text,int color){
        TextView view=findViewById(id);view.setText(text==null?"":text);view.setTextColor(color);view.setVisibility(text==null?View.GONE:View.VISIBLE);
    }
    /** Energy per 100 km needs a distance to mean anything: the car's trip counter moves in 0.01 kWh steps. */
    static final double MIN_ECONOMY_KM = HomeCost.MIN_ECONOMY_KM;

    /**
     * The drive card's headline, in plain words: how the drive compares when that is known; else the
     * consumption of a drive of 5 km or more; else only what is true ("Driving", "Short drive", "No drive yet").
     */
    void renderDrivePerformance(CarDrivePerformance.Result result){
        if(palette==null)return;
        TextView title=findViewById(R.id.trip_verdict),detail=findViewById(R.id.trip_performance);
        HomeEnergySnapshot car=home;
        CarEnergyTracker.Event drive=car==null?null:car.latestDrive;
        double used=drive==null?Double.NaN:drive.netKwh(car.usableCapacity);
        double per100=drive!=null&&drive.km>=MIN_ECONOMY_KM&&Double.isFinite(used)?used/drive.km*100:Double.NaN;
        boolean verdict=result!=null&&result.eligible;
        String when=drive==null?"":car.driveCurrent?"So far this drive":"Ended "+android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(drive.closed()?drive.end:drive.last));
        if(verdict){
            title.setText(result.title);
            detail.setText(result.detail);
        }else if(Double.isFinite(per100)){
            title.setText(String.format(Locale.US,"%.1f kWh/100 km",per100));
            detail.setText(when);
        }else if(car!=null&&car.car){
            title.setText(drive==null?"No drive yet":car.driveCurrent?"Driving":"Short drive");
            detail.setText(drive==null?"Drives are recorded automatically":car.driveCurrent?"Energy figures appear after 5 km":when);
        }else{
            title.setText("Drive");
            detail.setText("Connect the car for energy figures");
        }
        int color=verdict?(result.verdict==CarDrivePerformance.Verdict.OVERPERFORMING?batteryLevelColor(68):result.verdict==CarDrivePerformance.Verdict.UNDERPERFORMING?batteryLevelColor(25):palette.ink):palette.ink;
        title.setTextColor(color);detail.setTextColor(palette.muted);
        title.setContentDescription(title.getText()+". "+detail.getText());
    }

    private void energyCardTapped() {
        if (EnergyClient.link(this).expectsCarBattery()) { openTool(BatteryActivity.class); return; }
        Runnable done = energyChanged;
        switch (energyAction) {
            case 1: EnergyDialogs.checkIn(this, palette, done); break;
            case 2: EnergyDialogs.endDrive(this, palette, done); break;
            case 3: EnergyDialogs.recovery(this, palette, done); break;
            case 4: EnergyDialogs.startPercent(this, palette, done); break;
            default: openTool(EnergyActivity.class);
        }
    }

    private void renderNavigationHints() {
        if (navigationHintCard == null) {
            return;
        }
        MediaAccessService.NavigationSnapshot snapshot = hasNotificationAccess()
                ? MediaAccessService.navigationSnapshot() : MediaAccessService.NavigationSnapshot.NONE;
        activeNavigation = snapshot;
        applyDashboardMode();
        navigationHintCard.setVisibility(View.VISIBLE);
        findViewById(R.id.navigation_divider).setVisibility(View.GONE);
        if (snapshot.active && snapshot.text != null) {
            renderGuidance(snapshot);
            updateDockBadges();
            return;
        }
        updateDockBadges();
        if (lastGuidance != null) {
            beginArrival();
        }
        if (android.os.SystemClock.elapsedRealtime() < arrivalUntil) {
            renderArrival();
            return;
        }
        renderIdleNavigation();
    }

    private void renderGuidance(MediaAccessService.NavigationSnapshot snapshot) {
        NavigationGuidance guidance = NavigationGuidance.read(snapshot.text);
        if (lastGuidance == null) {
            TripComputer trip = TripComputer.get(this);
            routeStartKilometers = trip.kilometers();
            routeStartMinutes = trip.movingMinutes();
            arrivalUntil = 0;
            arrivalSummary = null;
            routeStart = RouteStart.now(home);
        }
        lastGuidance = guidance;
        showNavigationState(true);
        String key = snapshot.key + "|" + snapshot.postedAt + "|" + snapshot.text.title
                + "|" + snapshot.text.detail + "|" + snapshot.text.trip + "|"
                + (home==null?"":home.soc+":"+home.learnedKwhPerKm+":"+home.usableCapacity+":"+home.reservePercent)
                // Redraw once the week's prices have loaded, so the chip gains its cost.
                + "|" + (energyWeek==null?"-":String.valueOf(energyWeek.price));
        if (key.equals(renderedNavigationKey)) {
            return;
        }
        renderedNavigationKey = key;
        boolean split = guidance.distance != null;
        // The large slot is only for a distance; a road name needs the full-width line below it.
        navigationHintTitle.setText(split ? guidance.distance : "");
        navigationHintTitle.setVisibility(split ? View.VISIBLE : View.GONE);
        String detail = split ? guidance.instruction
                : snapshot.text.detail == null ? snapshot.text.title
                : snapshot.text.title == null ? snapshot.text.detail
                : snapshot.text.title + " · " + snapshot.text.detail;
        navigationHintDetail.setText(detail);
        navigationHintDetail.setVisibility(detail == null ? View.GONE : View.VISIBLE);
        TextView eta = findViewById(R.id.navigation_eta);
        eta.setText(guidance.eta);
        findViewById(R.id.navigation_eta_block).setVisibility(guidance.eta == null ? View.GONE : View.VISIBLE);
        String trip = guidance.eta != null ? guidance.remaining : snapshot.text.trip;
        if (guidance.trafficDelay != null && (trip == null || !trip.toLowerCase(Locale.ROOT).contains("delay")))
            trip = trip == null ? guidance.trafficDelay : trip + " · " + guidance.trafficDelay;
        navigationHintTrip.setText(trip == null ? snapshot.appLabel : trip);
        renderArrivalBattery(guidance);
        Drawable icon = guidance.maneuver == NavigationGuidance.Maneuver.UNKNOWN ? null
                : new NavigationManeuverDrawable(guidance.maneuver, guidance.roundaboutExit, Color.WHITE);
        if (icon == null) try {
            if (snapshot.icon != null) icon = snapshot.icon.loadDrawable(this);
        } catch (RuntimeException ignored) { /* guidance text and the fallback remain */ }
        setNavigationIcon(icon, R.drawable.ic_nav_turn);
        TurnProgressView progress = findViewById(R.id.navigation_turn_progress);
        if (guidance.meters >= 0) {
            String maneuver = snapshot.key + "|" + guidance.instruction;
            // A new instruction, or the distance growing again, starts a new countdown.
            if (!maneuver.equals(turnKey) || guidance.meters > turnStartMeters * 1.05) {
                turnKey = maneuver;
                turnStartMeters = Math.max(1, guidance.meters);
            }
            progress.setVisibility(View.VISIBLE);
            progress.progress(1f - (float) (guidance.meters / turnStartMeters), preferences.visualEffects());
        } else {
            progress.setVisibility(View.INVISIBLE);
        }
        setTurnPulse(guidance.meters >= 0 && guidance.meters <= 150);
        findViewById(R.id.navigation_end).setVisibility(snapshot.end == null ? View.GONE : View.VISIBLE);
        navigationHintCard.setContentDescription("Navigation: " + navigationHintTitle.getText() + " "
                + (detail == null ? "" : ". " + detail)
                + (guidance.eta == null ? "" : ". Arriving " + guidance.eta));
    }

    private void renderArrivalBattery(NavigationGuidance guidance) {
        TextView chip=findViewById(R.id.navigation_arrival_soc);
        HomeEnergySnapshot car=home;
        ArrivalBattery.Result estimate=car==null?null:ArrivalBattery.estimate(car.soc,guidance.remainingKilometers(),
                car.learnedKwhPerKm,car.usableCapacity,car.reservePercent);
        boolean need=estimate!=null&&estimate.belowReserve;
        if(need!=arrivalChargeNeeded){arrivalChargeNeeded=need;renderedChipsKey=null;renderNavigationChips();}
        // The rest of the route in energy and money: the learned kWh/km (else this week's), at the average paid price.
        loadEnergyWeek();
        EnergyWeek week=energyWeek;
        double perKm=car!=null&&Double.isFinite(car.learnedKwhPerKm)&&car.learnedKwhPerKm>0?car.learnedKwhPerKm
                :week!=null&&Double.isFinite(week.economy)?week.economy/100:Double.NaN;
        double remaining=guidance.remainingKilometers();
        double routeKwh=Double.isFinite(perKm)&&Double.isFinite(remaining)?remaining*perKm:Double.NaN;
        double routeCost=Double.isFinite(routeKwh)&&week!=null&&Double.isFinite(week.price)?routeKwh*week.price:Double.NaN;
        StringBuilder text=new StringBuilder();
        if(estimate!=null)text.append("Arrive ≈ ").append(EnergyModel.percent(Math.round(estimate.percent))).append('%');
        if(Double.isFinite(routeKwh)&&routeKwh>=0.05)text.append(text.length()>0?" · ":"").append(String.format(Locale.US,"≈ %.1f kWh",routeKwh));
        if(Double.isFinite(routeCost))text.append(" · ≈ ").append(HomeCost.money(routeCost)).append(week.currency.isEmpty()?"":" "+week.currency);
        if(need)text.append(" · Chargers");
        if(text.length()==0){chip.setVisibility(View.GONE);return;}
        chip.setText(text);
        int color=need?batteryLevelColor(25):palette.accent;
        chip.setTextColor(color);
        chip.setBackground(ripple(LauncherTheme.rounded(this,need?(palette.light?0x1f9a7400:0x26f2cf5c):(palette.light?0x10102a2d:0x14ffffff),18,1,color),
                palette.light?0x22102a2d:0x33ffffff));
        chip.setClickable(need);
        chip.setContentDescription("Rest of the route: "+text+(need?". Tap to find chargers.":"."));
        chip.setVisibility(View.VISIBLE);
    }

    /** What the car said when a route began, so the arrival summary can show this route alone. */
    static final class RouteStart {
        long at;
        double soc = Double.NaN, capacity = Double.NaN;
        String driveKey;
        double driveKm, driveRegen, driveNet = Double.NaN;

        static RouteStart now(HomeEnergySnapshot car) {
            RouteStart start = new RouteStart();
            start.at = System.currentTimeMillis();
            if (car == null) return start;
            start.capacity = car.usableCapacity;
            if (car.live && Double.isFinite(car.soc)) start.soc = car.soc;
            CarEnergyTracker.Event drive = car.latestDrive;
            if (drive != null && !drive.closed()) {
                start.driveKey = drive.key; start.driveKm = drive.km; start.driveRegen = drive.regenKwh;
                start.driveNet = drive.netKwh(car.usableCapacity);
            }
            return start;
        }
    }

    /** The finished route: the box's GPS distance and time, and the car's energy for the same stretch. */
    static final class ArrivalSummary {
        String title, place;
        long start, end;
        double gpsKm, minutes, carKm = Double.NaN, kwh = Double.NaN, regen = Double.NaN;
        double socStart = Double.NaN, socEnd = Double.NaN, price = Double.NaN;
        String energySource = "", currency = "";
        double km() { return Double.isFinite(carKm) && carKm > 0.1 ? carKm : gpsKm; }
    }
    private RouteStart routeStart;
    private ArrivalSummary arrivalSummary;

    private void beginArrival() {
        TripComputer trip = TripComputer.get(this);
        ArrivalSummary summary = new ArrivalSummary();
        summary.gpsKm = Math.max(0, trip.kilometers() - routeStartKilometers);
        summary.minutes = Math.max(0, trip.movingMinutes() - routeStartMinutes);
        summary.title = lastGuidance.arriving ? "You've arrived" : "Route ended";
        summary.place = hereRoad;
        summary.end = System.currentTimeMillis();
        RouteStart start = routeStart;
        summary.start = start == null ? summary.end : start.at;
        HomeEnergySnapshot car = home;
        if (car != null && start != null) {
            double capacity = Double.isFinite(car.usableCapacity) ? car.usableCapacity : start.capacity;
            CarEnergyTracker.Event drive = car.latestDrive;
            // The same drive as at the start: only what changed since; a drive that began during the route: all of it.
            if (drive != null && drive.last >= start.at) {
                boolean same = drive.key != null && drive.key.equals(start.driveKey);
                summary.carKm = same ? drive.km - start.driveKm : drive.km;
                summary.regen = same ? drive.regenKwh - start.driveRegen : drive.regenKwh;
                double net = drive.netKwh(capacity);
                double used = same ? (Double.isFinite(net) && Double.isFinite(start.driveNet) ? net - start.driveNet : Double.NaN) : net;
                if (Double.isFinite(used) && used >= 0) { summary.kwh = used; summary.energySource = "Car trip computer"; }
            }
            if (Double.isFinite(start.soc) && car.live && Double.isFinite(car.soc)) {
                summary.socStart = start.soc;
                summary.socEnd = car.soc;
                if (!Double.isFinite(summary.kwh) && capacity > 0 && start.soc >= car.soc) {
                    summary.kwh = (start.soc - car.soc) * capacity / 100;
                    summary.energySource = "From the battery level";
                }
            }
        }
        EnergyWeek week = energyWeek;
        if (week != null) { summary.price = week.price; summary.currency = week.currency; }
        arrivalSummary = summary;
        arrivalTitle = summary.title;
        arrivalDetail = String.format(Locale.US, "%.1f km · %.0f min", summary.km(), summary.minutes);
        // The summary stays until it is tapped (or the next route starts); 20 minutes at most.
        arrivalUntil = android.os.SystemClock.elapsedRealtime() + 20 * 60_000L;
        lastGuidance = null;
        turnKey = null;
        renderedNavigationKey = null;
        hereShownKey = null;
        setTurnPulse(false);
    }

    /** The idle card turns into the trip summary: the title line on top, the figures in the cells. */
    private void renderArrival() {
        showNavigationState(false);
        if ("arrival".equals(renderedNavigationKey)) return;
        renderedNavigationKey = "arrival";
        findViewById(R.id.navigation_arrival_soc).setVisibility(View.GONE);
        if (arrivalChargeNeeded) { arrivalChargeNeeded = false; renderedChipsKey = null; }
        setTurnPulse(false);
        hereShownKey = null;
        applyHere();
        navigationHintCard.setContentDescription("Trip summary: " + arrivalTitle + ". " + arrivalDetail + ". Tap to dismiss.");
    }

    /**
     * Maps can keep or re-post its navigation notification for a moment after a route ends, so the
     * summary does not depend on that: only real guidance coming back (renderGuidance) clears it.
     */
    private boolean arrivalShown() {
        return arrivalSummary != null && android.os.SystemClock.elapsedRealtime() < arrivalUntil;
    }

    private DriveStatsView.Cell[] arrivalCells(ArrivalSummary a) {
        boolean energy = Double.isFinite(a.kwh);
        String money = a.currency;
        double km = a.km();
        DriveStatsView.Cell distance = new DriveStatsView.Cell("Distance", km(km), "km")
                .note(Double.isFinite(a.carKm) && a.carKm > 0.1 ? String.format(Locale.US, "Car odometer · GPS %.1f km", a.gpsKm) : "Box GPS");
        DriveStatsView.Cell time = new DriveStatsView.Cell("Driving time", a.minutes >= 1 ? duration(a.minutes) : "<1 min", "")
                .note(timeRange(a.start, a.end));
        DriveStatsView.Cell speed = new DriveStatsView.Cell("Avg speed", a.minutes >= 1 ? String.valueOf(Math.round(km / (a.minutes / 60))) : "—", "km/h")
                .note("While moving");
        DriveStatsView.Cell used = energy
                ? new DriveStatsView.Cell("Energy used", String.format(Locale.US, "%.2f", a.kwh), "kWh").note(a.energySource)
                : new DriveStatsView.Cell("Energy used", "—", "").note("Car not linked on this route");
        DriveStatsView.Cell battery = Double.isFinite(a.socStart) && Double.isFinite(a.socEnd)
                ? new DriveStatsView.Cell("Battery used", (a.socStart - a.socEnd >= 0 ? "−" : "+") + Math.round(Math.abs(a.socStart - a.socEnd)) + "%", "")
                        .note(Math.round(a.socStart) + "% → " + Math.round(a.socEnd) + "%")
                : new DriveStatsView.Cell("Battery used", "—", "").note("Car not linked");
        DriveStatsView.Cell economy = energy && km >= 1
                ? new DriveStatsView.Cell("Economy", String.format(Locale.US, "%.1f", a.kwh / km * 100), "kWh/100 km").note(String.format(Locale.US, "%.2f kWh over %.1f km", a.kwh, km))
                : new DriveStatsView.Cell("Economy", "—", "").note(energy ? "Too short to rate" : "Car not linked");
        DriveStatsView.Cell regen = Double.isFinite(a.regen) && a.regen >= 0.005
                ? new DriveStatsView.Cell("Regen", "≈" + String.format(Locale.US, "%.2f", a.regen), "kWh est.")
                        .note(energy && a.kwh > 0 ? String.format(Locale.US, "≈%.0f%% of the energy used", 100 * a.regen / a.kwh) : "Modelled from the car's power")
                : new DriveStatsView.Cell("Regen", "—", "").note(Double.isFinite(a.regen) ? "None on this route" : "Car not linked");
        DriveStatsView.Cell cost = energy && Double.isFinite(a.price)
                ? new DriveStatsView.Cell("Cost", "≈" + HomeCost.money(a.kwh * a.price), money).note(String.format(Locale.US, "at %s %s/kWh", HomeCost.money(a.price), money))
                : new DriveStatsView.Cell("Cost", "—", "").note(energy ? "Price a charge to see costs" : "Needs the car's energy");
        return new DriveStatsView.Cell[]{distance, time, speed, regen, used, battery, economy, cost};
    }

    private void dismissArrival() {
        arrivalUntil = 0;
        arrivalSummary = null;
        hereShownKey = null;
        renderedNavigationKey = null;
        renderNavigationHints();
    }

    // ---- The idle navigation card's live position: road or area, heading, speed and coordinates ----
    private String hereRoad = "Waiting for GPS", hereDetail;
    private boolean hereStale, hereFull = true;
    private String hereShownKey;

    private void updateHere(DashboardData data, android.location.Location fix, String road, SpeedGaugeView gauge) {
        java.util.List<String> bits = new java.util.ArrayList<>();
        if (fix != null) {
            // A bearing is only meaningful while moving; parked, the receiver's heading wanders.
            if (fix.hasBearing() && fix.hasSpeed() && fix.getSpeed() > 1.5f) {
                // Coordinates would change on every fix, and each repaint is another frame sent to the car.
                bits.add("Heading " + DashboardData.compassName(fix.getBearing()));
            } else {
                double lat = fix.getLatitude(), lon = fix.getLongitude();
                bits.add(String.format(Locale.US, "%.4f° %s  %.4f° %s", Math.abs(lat), lat >= 0 ? "N" : "S", Math.abs(lon), lon >= 0 ? "E" : "W"));
            }
        } else {
            bits.add(data.satellitesUsed > 0 ? "GPS · " + data.satellitesUsed + " of " + data.satellitesSeen + " satellites"
                    : "GPS · searching for satellites");
        }
        hereRoad = road;
        hereStale = fix != null && data.roadLabelStale();
        hereDetail = android.text.TextUtils.join("  ·  ", bits);
        applyHere();
        updateDriveStats(data, fix, gauge);
    }


    /** The trip computer under the live position, on an idle card tall enough to hold it. */
    private void updateDriveStats(DashboardData data, android.location.Location fix, SpeedGaugeView gauge) {
        DriveStatsView stats = findViewById(R.id.navigation_stats);
        statsData = data; statsFix = fix; statsGauge = gauge;
        if (stats != null && !statsListenerSet) { stats.setOnPeriodListener(this::selectHomePeriod); stats.setOnActionListener(this::onStatsAction); statsListenerSet = true; }
        View idle = findViewById(R.id.navigation_idle);
        if (stats == null || idle == null || palette == null) return;
        boolean room = driveStatsRoom();
        if ((stats.getVisibility() == View.VISIBLE) != room) {
            stats.setVisibility(room ? View.VISIBLE : View.GONE);
            // The trip computer takes the whole card: no place shortcuts under it (Maps is one tap away).
            ((View) findViewById(R.id.navigation_chips).getParent()).setVisibility(room ? View.GONE : View.VISIBLE);
            renderedChipsKey = null;
            idle.post(this::renderNavigationChips);
        }
        updateCardTitle();
        if (!room || idle.getVisibility() != View.VISIBLE) return;
        if (arrivalShown()) {
            stats.colors(palette.ink, palette.muted, palette.accent);
            stats.show(arrivalCells(arrivalSummary), null, null, null, "");
            return;
        }
        long now = System.currentTimeMillis();
        loadEnergyWeek();
        EnergyWeek week = energyWeek;
        if (week == null) return;
        boolean car = home != null && home.car;
        boolean moving = gauge.known() && gauge.speed() >= 5;

        HomePeriod period = homePeriod;
        if (period == HomePeriod.TRIP) loadTrip();
        stats.setTrip(tripButtonLabel(now), tripConfirm != 0 ? 2 : manualTrip().active() ? 1 : 0);
        StatsModel model = period == HomePeriod.TRIP ? tripModel(now, week) : periodModel(period, week, car, moving, fix, now);
        stats.colors(palette.ink, palette.muted, palette.accent);
        stats.show(model.cells, model.bars, model.kwh, model.marks, model.note, period.ordinal(), model.names, model.title);
    }

    /** Everything the card (and a report picture of it) shows for one period. */
    static final class StatsModel {
        DriveStatsView.Cell[] cells = new DriveStatsView.Cell[0];
        double[] bars = new double[1];
        double[] kwh;
        boolean[] marks;
        String[] names;
        String title, note = "";
    }

    private StatsModel periodModel(HomePeriod period, EnergyWeek week, boolean car, boolean moving, android.location.Location fix, long now) {
        EnergyWeek.Stats st = week.stats(period);
        String within = period == HomePeriod.DAY ? "today" : "in the last " + period.label;
        DriveStatsView.Cell economy = Double.isFinite(st.economy)
                ? new DriveStatsView.Cell(period.title("Economy"), String.format(Locale.US, "%.1f", st.economy), "kWh/100 km")
                        .note(String.format(Locale.US, "Car log: %.1f kWh over %s km", st.kwh, km(st.energyKm)))
                : new DriveStatsView.Cell(period.title("Economy"), "—", "").note(car ? "Needs " + Math.round(MIN_ECONOMY_KM) + " km of linked driving" + (period == HomePeriod.DAY ? " today" : "") : "Link the car for energy");

        DriveStatsView.Cell drive;
        TripComputer.Trip last = week.lastTrip;
        if (last == null) drive = new DriveStatsView.Cell("Last drive", "—", "").note("Drives are logged by GPS");
        else {
            boolean live = last.end == 0;
            String to = live ? null : placeName(last.endLat, last.endLon);
            String when = live ? "since " + clockTime(last.start) : timeRange(last.start, last.end);
            drive = new DriveStatsView.Cell(live ? "This drive" : "Last drive · " + dayName(last.end), km(last.kilometers), "km · " + duration(last.minutes))
                    .note(when + (to == null || to.isEmpty() ? "" : " · to " + to));
        }

        String monthName = new java.text.SimpleDateFormat("MMMM", Locale.getDefault()).format(new java.util.Date(now));
        int periodDrives = week.drives[period.ordinal()];
        // The period's distance; the calendar month's figure stays in the note so nothing the card showed is lost.
        DriveStatsView.Cell distance = new DriveStatsView.Cell(period.title("Distance"), km(HomePeriod.sumTail(week.km30, period.days)), "km")
                .note(periodDrives + (periodDrives == 1 ? " drive" : " drives") + " · " + monthName.substring(0, Math.min(3, monthName.length())) + " so far " + km(week.monthKm) + " km");
        // Regen is modelled from the car's power and speed, not measured: shown as an estimate.
        DriveStatsView.Cell regen = st.regen >= 0.01
                ? new DriveStatsView.Cell(period.title("Regen"), "≈" + String.format(Locale.US, "%.1f", st.regen), "kWh est.")
                        .note(st.kwh > 0 ? String.format(Locale.US, "≈%.0f%% of the %.1f kWh used", 100 * st.regen / st.kwh, st.kwh) : "Modelled from the car's power")
                : new DriveStatsView.Cell(period.title("Regen"), "—", "").note(car ? "No regen recorded" : "Link the car for energy");

        // Driving cost: the car's energy as grid kWh (charging losses learned from charges) × the average paid price.
        String money = week.currency.isEmpty() ? "" : week.currency;
        DriveStatsView.Cell cost = Double.isFinite(st.cost)
                ? new DriveStatsView.Cell(period.title("Cost"), "≈" + HomeCost.money(st.cost), money)
                        .note(String.format(Locale.US, "%.1f kWh at %s %s/kWh", st.gridKwh, HomeCost.money(week.price), money))
                : new DriveStatsView.Cell(period.title("Cost"), st.paid > 0 ? HomeCost.money(st.paid) : "—", st.paid > 0 ? money : "")
                        .note(st.paid > 0 ? "Paid for charges " + within : Double.isFinite(week.price) ? "No car energy " + within : "Price a charge to see costs");

        // "Idle energy · 30 days" is wider than its cell.
        String idleTitle = period == HomePeriod.MONTH ? "Idle · 30 days" : period.title("Idle energy");
        DriveStatsView.Cell idleCell = st.idleSeconds > 0
                // Modelled from the stopped-load profile (Energy page), not measured: shown as an estimate.
                ? new DriveStatsView.Cell(idleTitle, "≈" + String.format(Locale.US, st.idleKwh >= 10 ? "%.0f" : "%.1f", st.idleKwh), "kWh est.")
                        .note(duration(st.idleSeconds / 60) + " stopped with the car on")
                : new DriveStatsView.Cell(idleTitle, "—", "").note(car ? "No stops with the car on" : "Link the car for energy");

        DriveStatsView.Cell parkedCell;
        if (moving && fix != null && fix.hasBearing()) {
            float bearing = (fix.getBearing() % 360 + 360) % 360;
            parkedCell = new DriveStatsView.Cell("Heading", DashboardData.compassName(bearing), "")
                    .note(Math.round(bearing) + "°" + (hereRoad == null ? "" : " on " + hereRoad));
        } else if (week.parkedSince > 0 && now > week.parkedSince) {
            String at = placeName(week.parkedLat, week.parkedLon);
            boolean here = fix != null && GpsMotion.distance(fix.getLatitude(), fix.getLongitude(), week.parkedLat, week.parkedLon) < 200;
            parkedCell = new DriveStatsView.Cell("Parked", duration((now - week.parkedSince) / 60_000.0), "")
                    .note("Since " + dayTime(week.parkedSince) + (here ? " · here" : at == null || at.isEmpty() ? "" : " · " + at));
        } else parkedCell = new DriveStatsView.Cell("Parked", "—", "").note("After the first drive");

        // Top row: the period's energy and what it cost. Bottom row: the latest charge and drive, where the car is, the period's distance.
        // Bars are the box's GPS distance (per hour today, per day otherwise); the energy under the week's bars is the car's own figure.
        StatsModel model = new StatsModel();
        model.cells = new DriveStatsView.Cell[]{economy, regen, idleCell, cost, lastChargeCell(week), drive, parkedCell, distance};
        model.note = st.kwh >= 0.05 ? String.format(Locale.US, "car %.1f kWh", st.kwh) : "";
        model.bars = period == HomePeriod.DAY ? week.kmHours : HomePeriod.tail(week.km30, period.days);
        model.marks = period == HomePeriod.DAY ? week.chargedHours : HomePeriod.tail(week.charged30, period.days);
        model.kwh = period == HomePeriod.WEEK ? week.kwh : null;
        return model;
    }

    // ---- Trip mode: a trip the owner starts and ends by hand ----
    private ManualTrip manualTripStore;
    private volatile ManualTrip.Summary tripSummary;
    private volatile ManualTrip.Chart tripChart;
    private String tripKey;
    private long tripAt;
    private boolean tripLoading;
    /** 1 while the End button is waiting for its second tap (4 s). */
    private int tripConfirm;

    private ManualTrip manualTrip() {
        if (manualTripStore == null) manualTripStore = new ManualTrip(this);
        return manualTripStore;
    }

    private String tripButtonLabel(long now) {
        ManualTrip trip = manualTrip();
        if (tripConfirm != 0) return "TAP TO END";
        if (!trip.active()) return "START TRIP";
        long minutes = Math.max(0, (now - trip.start()) / 60_000);
        return "END TRIP · " + (minutes < 60 ? minutes + " MIN" : String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60));
    }

    private void onStatsAction(int id) {
        if (id == DriveStatsView.ACTION_TRIP) tripButton();
        else showShareDialog();
    }

    private void showStatsPeriod(HomePeriod period) {
        homePeriod = period;
        if (preferences != null) preferences.setHomePeriod(period.ordinal());
        if (statsData != null) updateDriveStats(statsData, statsFix, statsGauge);
    }

    private void tripButton() {
        ManualTrip trip = manualTrip();
        if (!trip.active()) {
            HomeEnergySnapshot snapshot = home;
            double soc = snapshot != null && snapshot.live && Double.isFinite(snapshot.soc) ? snapshot.soc
                    : snapshot != null && Double.isFinite(snapshot.lastSoc) ? snapshot.lastSoc : Double.NaN;
            android.location.Location fix = statsFix;
            trip.begin(System.currentTimeMillis(), soc, fix == null ? 0 : fix.getLatitude(), fix == null ? 0 : fix.getLongitude());
            tripSummary = null; tripKey = null; tripAt = 0;
            Toast.makeText(this, "Trip started. It runs until you end it.", Toast.LENGTH_LONG).show();
            showStatsPeriod(HomePeriod.TRIP);
            return;
        }
        if (tripConfirm == 0) {
            // A car screen is easy to brush: ending needs a second tap within four seconds.
            tripConfirm = 1;
            if (statsData != null) updateDriveStats(statsData, statsFix, statsGauge);
            handler.postDelayed(() -> {
                if (tripConfirm == 1) { tripConfirm = 0; if (statsData != null) updateDriveStats(statsData, statsFix, statsGauge); }
            }, 4000);
            return;
        }
        endTrip();
    }

    /** The trip's figures and its chart, worked out off the main thread. */
    private Object[] computeTrip(long start, long end, double startSoc, double endSoc, EnergyWeek week) {
        try {
            EnergyStore store = EnergyStore.get(this);
            double capacity = store.profile().usableKwh;
            CarEnergyRepository repository = new CarEnergyRepository(store);
            java.util.List<CarEnergyTracker.Event> events = new java.util.ArrayList<>();
            for (CarEnergyRepository.Item item : repository.events(start, end > 0 ? end : Long.MAX_VALUE, null, 0)) events.add(item.event);
            java.util.List<TripComputer.Trip> gps = TripComputer.get(this).history();
            long now = System.currentTimeMillis();
            ManualTrip.Summary summary = ManualTrip.compute(events, gps, start, end, now, capacity, startSoc, endSoc,
                    week == null ? Double.NaN : week.price, week == null ? Double.NaN : week.gridPerPoint, week == null ? "" : week.currency);
            return new Object[]{summary, ManualTrip.chart(gps, start, end, now, java.util.TimeZone.getDefault())};
        } catch (RuntimeException failure) {
            android.util.Log.w("OABL", "Trip figures unavailable", failure);
            return null;
        }
    }

    private double liveSocNow() {
        HomeEnergySnapshot snapshot = home;
        return snapshot != null && snapshot.live && Double.isFinite(snapshot.soc) ? snapshot.soc : Double.NaN;
    }

    private void endTrip() {
        tripConfirm = 0;
        ManualTrip trip = manualTrip();
        final long start = trip.start();
        final double startSoc = trip.startSoc(), lat = trip.startLat(), lon = trip.startLon(), endSoc = liveSocNow();
        final android.location.Location fix = statsFix;
        final EnergyWeek week = energyWeek;
        energyLoader.execute(() -> {
            Object[] result = computeTrip(start, 0, startSoc, endSoc, week);
            ManualTrip.Summary summary = result == null ? null : (ManualTrip.Summary) result[0];
            long end = System.currentTimeMillis();
            if (summary == null) summary = ManualTrip.compute(new java.util.ArrayList<>(), new java.util.ArrayList<>(), start, 0, end, Double.NaN, startSoc, endSoc, Double.NaN, Double.NaN, "");
            summary.end = end;
            summary.active = false;
            String from = lat != 0 || lon != 0 ? placeName(lat, lon) : null, to = fix == null ? null : placeName(fix.getLatitude(), fix.getLongitude());
            summary.from = from == null ? "" : from;
            summary.to = to == null ? "" : to;
            final ManualTrip.Summary finished = summary;
            trip.end(finished);
            handler.post(() -> {
                tripSummary = null; tripKey = null; tripAt = 0;
                Toast.makeText(this, "Trip saved: " + (Double.isFinite(finished.km) ? km(finished.km) + " km" : "no distance") + " · tap SHARE to send it as an image", Toast.LENGTH_LONG).show();
                showStatsPeriod(HomePeriod.TRIP);
            });
        });
    }

    /** Keeps the trip view's figures fresh: every 10 s while a trip runs, once for a finished one. */
    private void loadTrip() {
        ManualTrip trip = manualTrip();
        boolean active = trip.active();
        ManualTrip.Summary finished = active ? null : trip.last();
        if (!active && finished == null) { tripSummary = null; tripChart = null; return; }
        long tick = android.os.SystemClock.elapsedRealtime();
        String key = active ? "live" : finished.start + ":" + finished.end;
        if (tripLoading || key.equals(tripKey) && (!active || tick - tripAt < 10_000)) return;
        tripLoading = true;
        final long start = active ? trip.start() : finished.start, end = active ? 0 : finished.end;
        final double startSoc = active ? trip.startSoc() : finished.startSoc, endSoc = active ? liveSocNow() : finished.endSoc;
        final EnergyWeek week = energyWeek;
        energyLoader.execute(() -> {
            Object[] result = computeTrip(start, end, startSoc, endSoc, week);
            handler.post(() -> {
                if (result != null) {
                    if (active) tripSummary = (ManualTrip.Summary) result[0];
                    tripChart = (ManualTrip.Chart) result[1];
                }
                tripKey = key;
                tripAt = android.os.SystemClock.elapsedRealtime();
                tripLoading = false;
            });
        });
    }

    private StatsModel tripModel(long now, EnergyWeek week) {
        ManualTrip trip = manualTrip();
        boolean active = trip.active();
        ManualTrip.Summary s = active ? tripSummary : trip.last();
        StatsModel m = new StatsModel();
        m.title = "TRIP";
        if (s == null) {
            String hint = active ? "Reading the trip…" : "Tap START TRIP · it runs until you end it";
            m.cells = new DriveStatsView.Cell[]{new DriveStatsView.Cell("Duration", "—", "").note(hint), new DriveStatsView.Cell("Distance", "—", ""),
                    new DriveStatsView.Cell("Moving", "—", ""), new DriveStatsView.Cell("Speed", "—", ""), new DriveStatsView.Cell("Energy used", "—", ""),
                    new DriveStatsView.Cell("Economy", "—", ""), new DriveStatsView.Cell("Regen", "—", ""), new DriveStatsView.Cell("Cost", "—", "")};
            m.names = new String[]{""};
            return m;
        }
        String money = s.currency.isEmpty() ? "" : s.currency;
        String when = (active ? "since " : "") + dayTime(s.start) + (active ? "" : " → " + clockTime(s.end));
        String places = !s.from.isEmpty() && !s.to.isEmpty() ? " · " + s.from + " → " + s.to : "";
        String socNote = Double.isFinite(s.startSoc) ? Math.round(s.startSoc) + "% → " + (Double.isFinite(s.endSoc) ? Math.round(s.endSoc) + "%" : "…") : "Battery level at the start unknown";
        m.cells = new DriveStatsView.Cell[]{
                new DriveStatsView.Cell(active ? "Trip in progress" : "Trip time", duration(s.elapsedSeconds(now) / 60), "").note(when + places),
                new DriveStatsView.Cell("Distance", Double.isFinite(s.km) ? km(s.km) : "—", "km")
                        .note(s.drives + (s.drives == 1 ? " drive" : " drives") + (s.kmGps > 0 ? " · GPS " + km(s.kmGps) + " km" : "")),
                new DriveStatsView.Cell("Moving", duration(s.movingSeconds / 60), "")
                        .note("Stopped " + duration(s.stoppedSeconds / 60) + (s.idleKwh >= 0.01 ? String.format(Locale.US, " · ≈%.1f kWh idle", s.idleKwh) : "")),
                new DriveStatsView.Cell("Average speed", Double.isFinite(s.averageKph) ? String.valueOf(Math.round(s.averageKph)) : "—", "km/h")
                        .note(Double.isFinite(s.peakKph) ? "Top " + Math.round(s.peakKph) + " km/h" : "Moving time only"),
                new DriveStatsView.Cell("Energy used", Double.isFinite(s.kwh) ? String.format(Locale.US, "%.1f", s.kwh) : "—", "kWh").note(socNote),
                new DriveStatsView.Cell("Economy", Double.isFinite(s.economy) ? String.format(Locale.US, "%.1f", s.economy) : "—", Double.isFinite(s.economy) ? "kWh/100 km" : "")
                        .note(Double.isFinite(s.economy) ? "over " + km(s.energyKm) + " km" : "Needs 2 km of linked driving"),
                new DriveStatsView.Cell("Regen", s.regenKwh >= 0.01 ? "≈" + String.format(Locale.US, "%.1f", s.regenKwh) : "—", s.regenKwh >= 0.01 ? "kWh est." : "")
                        .note(s.regenKwh >= 0.01 && Double.isFinite(s.kwh) && s.kwh > 0 ? String.format(Locale.US, "≈%.0f%% of the %.1f kWh used", 100 * s.regenKwh / s.kwh, s.kwh) : "Modelled from the car's power"),
                Double.isFinite(s.cost)
                        ? new DriveStatsView.Cell("Cost", "≈" + HomeCost.money(s.cost), money).note(s.charges > 0 ? s.charges + (s.charges == 1 ? " charge" : " charges") + String.format(Locale.US, " · +%.1f kWh", s.chargedKwh) : "At the average paid price")
                        : new DriveStatsView.Cell("Charges", String.valueOf(s.charges), s.charges == 1 ? "charge" : "charges")
                                .note(s.charges > 0 ? String.format(Locale.US, "+%.1f kWh · +%.0f%%", s.chargedKwh, s.chargedPoints) : "Price a charge to see costs")};
        ManualTrip.Chart chart = tripChart;
        m.bars = chart != null ? chart.km : new double[1];
        m.names = chart != null ? chart.names : new String[]{""};
        m.title = (active ? "TRIP IN PROGRESS" : "LAST TRIP") + " · KM PER " + (chart == null || chart.hourly ? "HOUR" : "DAY");
        m.note = Double.isFinite(s.kwh) ? String.format(Locale.US, "car %.1f kWh", s.kwh) : "";
        return m;
    }

    // ---- Share as an image: day, week, month, the trip, or a screenshot ----
    private void showShareDialog() {
        ManualTrip trip = manualTrip();
        String tripItem = trip.active() ? "Trip in progress" : trip.last() != null ? "Last trip" : "Trip (none yet)";
        String[] items = {"Today", "This week · 7 days", "This month · 30 days", tripItem, "Screenshot of this screen"};
        dialogBuilder().setTitle("Share as image").setItems(items, (dialog, which) -> shareReport(which)).show();
    }

    private static String reportStamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new java.util.Date());
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    private String reportRange(HomePeriod period, long now) {
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault());
        java.text.SimpleDateFormat shortDay = new java.text.SimpleDateFormat("d MMM", Locale.getDefault());
        java.text.SimpleDateFormat full = new java.text.SimpleDateFormat("d MMM yyyy", Locale.getDefault());
        if (period == HomePeriod.DAY) return day.format(new java.util.Date(now));
        long from = now - (period.days - 1) * 86_400_000L;
        return shortDay.format(new java.util.Date(from)) + " – " + full.format(new java.util.Date(now));
    }

    /** Bar labels for a report: hours every third bar, weekdays for the week, dates every fifth day for the month. */
    private String[] reportNames(HomePeriod period, int count) {
        String[] names = new String[count];
        java.util.Calendar day = java.util.Calendar.getInstance();
        day.add(java.util.Calendar.DAY_OF_YEAR, -(count - 1));
        for (int i = 0; i < count; i++) {
            boolean now = i == count - 1;
            if (period == HomePeriod.DAY) names[i] = i % 3 == 0 ? String.format(Locale.US, "%02d", i) : "";
            else if (period == HomePeriod.WEEK) names[i] = now ? "TODAY" : day.getDisplayName(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.SHORT, Locale.getDefault()).toUpperCase(Locale.ROOT);
            else names[i] = now ? "TODAY" : (count - 1 - i) % 5 == 0 ? String.valueOf(day.get(java.util.Calendar.DAY_OF_MONTH)) : "";
            day.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        return names;
    }

    private void shareReport(int which) {
        if (which == 4) { handler.postDelayed(this::shareScreenshot, 400); return; }
        EnergyWeek week = energyWeek;
        if (week == null) { toast("The figures are still loading. Try again in a moment."); return; }
        long now = System.currentTimeMillis();
        boolean car = home != null && home.car;
        ReportImage.Model report = new ReportImage.Model();
        report.vehicle = home == null ? "" : home.vehicle;
        report.footer = "Generated " + new java.text.SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(new java.util.Date(now)) + " · OABL";
        String subject, name;
        if (which == 3) {
            ManualTrip trip = manualTrip();
            if (!trip.active() && trip.last() == null) { toast("No trip yet. Tap START TRIP first."); return; }
            if (trip.active() && tripSummary == null) { toast("The trip is still being read. Try again in a moment."); return; }
            StatsModel model = tripModel(now, week);
            ManualTrip.Summary s = trip.active() ? tripSummary : trip.last();
            report.title = trip.active() ? "Trip in progress" : "Trip report";
            report.subtitle = new java.text.SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()).format(new java.util.Date(s.start))
                    + (s.end > 0 ? " → " + new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date(s.end)) : " → now")
                    + (!s.from.isEmpty() && !s.to.isEmpty() ? " · " + s.from + " → " + s.to : "");
            report.cells = model.cells; report.bars = model.bars; report.names = model.names == null ? new String[0] : model.names;
            report.chartTitle = model.title; report.chartSum = model.bars.length > 0 && Double.isFinite(s.km) ? "GPS " + km(HomePeriod.sumTail(model.bars, model.bars.length)) + " km" : "";
            subject = "Trip report"; name = "OABL-trip-" + reportStamp();
        } else {
            HomePeriod period = HomePeriod.fromIndex(which);
            StatsModel model = periodModel(period, week, car, false, null, now);
            String[] kinds = {"Daily report", "Weekly report", "Monthly report"};
            report.title = kinds[which];
            report.subtitle = reportRange(period, now);
            report.cells = model.cells; report.bars = model.bars; report.marks = model.marks;
            report.names = reportNames(period, model.bars.length);
            report.chartTitle = new String[]{"Today · km per hour", "Last 7 days · km per day", "Last 30 days · km per day"}[which];
            report.chartSum = HomePeriod.sumTail(model.bars, model.bars.length) >= 0.05 ? "GPS " + km(HomePeriod.sumTail(model.bars, model.bars.length)) + " km" : "";
            subject = kinds[which]; name = "OABL-" + new String[]{"day", "week", "month"}[which] + "-" + reportStamp();
        }
        final String finalSubject = subject, finalName = name;
        energyLoader.execute(() -> finishShare(ReportImage.render(report), finalSubject, finalName));
    }

    private void shareScreenshot() {
        View decor = getWindow().getDecorView();
        int width = decor.getWidth(), height = decor.getHeight();
        if (width <= 0 || height <= 0) return;
        final android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
        final String name = "OABL-screen-" + reportStamp();
        if (Build.VERSION.SDK_INT >= 26) {
            android.view.PixelCopy.request(getWindow(), bitmap, result -> {
                if (result == android.view.PixelCopy.SUCCESS) energyLoader.execute(() -> finishShare(bitmap, "Screenshot", name));
                else toast("Could not capture the screen");
            }, handler);
        } else {
            decor.draw(new android.graphics.Canvas(bitmap));
            energyLoader.execute(() -> finishShare(bitmap, "Screenshot", name));
        }
    }

    /** Saves the picture to Pictures/OABL and opens the share sheet. Runs off the main thread. */
    private void finishShare(android.graphics.Bitmap bitmap, String subject, String name) {
        android.net.Uri uri = ReportShare.save(this, bitmap, name);
        handler.post(() -> {
            if (uri == null) { toast("Could not save the image"); return; }
            toast("Saved to Pictures/OABL/" + name + ".png");
            try { startActivity(ReportShare.chooser(uri, subject)); } catch (RuntimeException noSharing) { /* saved; no app to share with */ }
        });
    }

    // ---- The Drive & Energy card's period: today, the last 7 days or the last 30 days ----
    private HomePeriod homePeriod = HomePeriod.WEEK;
    private boolean statsListenerSet;
    private DashboardData statsData;
    private android.location.Location statsFix;
    private SpeedGaugeView statsGauge;

    private void selectHomePeriod(int index) {
        HomePeriod next = HomePeriod.fromIndex(index);
        if (next == homePeriod) return;
        homePeriod = next;
        if (preferences != null) preferences.setHomePeriod(next.ordinal());
        if (statsData != null) updateDriveStats(statsData, statsFix, statsGauge);
        HomeEnergySnapshot snapshot = home;
        if (snapshot != null) renderHomeEnergy(snapshot);
    }

    /** The range note: the car's figure is the number above it; this line is what the chosen period's economy would give. */
    private String rangeNote(HomeEnergySnapshot data, double shownSoc) {
        EnergyWeek week = energyWeek;
        if (week == null || !data.connected || !Double.isFinite(shownSoc) || !Double.isFinite(data.usableCapacity) || data.usableCapacity <= 0) return "Range";
        HomePeriod basis = homePeriod == HomePeriod.TRIP ? HomePeriod.WEEK : homePeriod;
        EnergyWeek.Stats st = week.stats(basis);
        if (!Double.isFinite(st.economy) || st.economy <= 0) return "Range";
        double reserve = Double.isFinite(data.reservePercent) ? data.reservePercent : 0;
        double kwh = Math.max(0, (shownSoc - reserve) / 100 * data.usableCapacity);
        double km = kwh / (st.economy / 100);
        return "Range · ≈" + Math.round(km) + " km at " + (basis == HomePeriod.DAY ? "today's" : basis.label) + " economy";
    }

    private static String km(double value) {
        return value >= 100 ? String.valueOf(Math.round(value)) : String.format(Locale.US, "%.1f", value);
    }

    /** "2:06–2:25 PM": the AM/PM marker once when both ends share it. */
    private String timeRange(long start, long end) {
        String a = clockTime(start), b = clockTime(end);
        for (String marker : new String[]{" AM", " PM", " am", " pm"})
            if (a.endsWith(marker) && b.endsWith(marker)) { a = a.substring(0, a.length() - marker.length()); break; }
        return a + "–" + b;
    }

    private String clockTime(long time) {
        return android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(time));
    }

    /** "Today" is left out: a time alone means today, else the weekday, else the date. */
    private String dayTime(long time) {
        String day = dayName(time);
        return day.equals("today") ? clockTime(time) : day + " " + clockTime(time);
    }

    private String dayName(long time) { return chargeWhen(time); }

    /** The last 30 days from the car's energy log (the week is its newest seven), read off the main thread every 30 s. */
    static final class EnergyWeek {
        double economy = Double.NaN, idleKwh, idleSeconds, kwhTotal, energyKm, regenKwh;
        double price = Double.NaN, gridKwh = Double.NaN, cost = Double.NaN, paid;
        final double[] kwh = new double[7];
        double[] km = new double[7];
        double monthKm;
        int monthDrives;
        TripComputer.Trip lastTrip;
        long parkedSince;
        double parkedLat, parkedLon;
        final boolean[] charged = new boolean[7];
        EnergyModel.Charge lastCharge;
        double lastChargeMoney = Double.NaN;
        String currency = "";
        // Per day, oldest first, ending today: every period's figures are sums of the newest days of these.
        final double[] kwh30 = new double[HomePeriod.HISTORY_DAYS], energyKm30 = new double[HomePeriod.HISTORY_DAYS],
                regen30 = new double[HomePeriod.HISTORY_DAYS], idleKwh30 = new double[HomePeriod.HISTORY_DAYS],
                idleSeconds30 = new double[HomePeriod.HISTORY_DAYS], paid30 = new double[HomePeriod.HISTORY_DAYS];
        double[] km30 = new double[HomePeriod.HISTORY_DAYS], kmHours = new double[24];
        final boolean[] charged30 = new boolean[HomePeriod.HISTORY_DAYS], chargedHours = new boolean[24];
        /** Drives started in each period (HomePeriod order). */
        final int[] drives = new int[HomePeriod.WINDOWS];
        double gridPerPoint = Double.NaN, capacity = Double.NaN;
        /** Start of today and when the older days were read, so they are only re-read every few minutes. */
        long dayStart, olderAt;

        /** One period's energy figures: the sums of its days, with the economy and cost worked out from them. */
        static final class Stats {
            double kwh, energyKm, regen, idleKwh, idleSeconds, paid, economy = Double.NaN, gridKwh = Double.NaN, cost = Double.NaN;
        }

        Stats stats(HomePeriod period) {
            Stats s = new Stats();
            int n = period.days;
            s.kwh = HomePeriod.sumTail(kwh30, n);
            s.energyKm = HomePeriod.sumTail(energyKm30, n);
            s.regen = HomePeriod.sumTail(regen30, n);
            s.idleKwh = HomePeriod.sumTail(idleKwh30, n);
            s.idleSeconds = HomePeriod.sumTail(idleSeconds30, n);
            s.paid = HomePeriod.sumTail(paid30, n);
            if (s.energyKm >= MIN_ECONOMY_KM && s.kwh > 0) s.economy = s.kwh / s.energyKm * 100;
            if (s.kwh > 0) {
                s.gridKwh = CarTripCost.billedEnergy(s.kwh, gridPerPoint, capacity);
                if (Double.isFinite(price) && price >= 0 && Double.isFinite(s.gridKwh)) s.cost = s.gridKwh * price;
            }
            return s;
        }
    }
    private volatile EnergyWeek energyWeek;
    private long energyWeekAt;
    private boolean energyWeekLoading;
    private final java.util.concurrent.ExecutorService energyLoader = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void loadEnergyWeek() {
        long tick = android.os.SystemClock.elapsedRealtime();
        if (energyWeekLoading || energyWeek != null && tick - energyWeekAt < 30_000) return;
        energyWeekLoading = true;
        energyLoader.execute(() -> {
            final long loadStarted = android.os.SystemClock.elapsedRealtime();
            EnergyWeek week = new EnergyWeek();
            try {
                EnergyStore store = EnergyStore.get(this);
                double capacity = store.profile().usableKwh;
                CarEnergyRepository repository = new CarEnergyRepository(store);
                java.util.Calendar day = java.util.Calendar.getInstance();
                day.set(java.util.Calendar.HOUR_OF_DAY, 0); day.set(java.util.Calendar.MINUTE, 0);
                day.set(java.util.Calendar.SECOND, 0); day.set(java.util.Calendar.MILLISECOND, 0);
                week.dayStart = day.getTimeInMillis();
                int days = HomePeriod.HISTORY_DAYS;
                day.add(java.util.Calendar.DAY_OF_YEAR, -(days - 1));
                long monthStart = day.getTimeInMillis();
                long[] starts = new long[days + 1];
                // Days before yesterday do not change within minutes: reuse them from the last read when it is the same day.
                EnergyWeek before = energyWeek;
                boolean reuse = before != null && before.dayStart == week.dayStart && tick - before.olderAt < 300_000;
                week.olderAt = reuse ? before.olderAt : tick;
                for (int i = 0; i < days; i++) {
                    starts[i] = day.getTimeInMillis();
                    if (reuse && i < days - 2) {
                        week.kwh30[i] = before.kwh30[i]; week.energyKm30[i] = before.energyKm30[i]; week.regen30[i] = before.regen30[i];
                        week.idleKwh30[i] = before.idleKwh30[i]; week.idleSeconds30[i] = before.idleSeconds30[i];
                    } else {
                        // Day buckets are keyed by their midnight, so [midnight, midnight + 1) is that day.
                        CarEnergySummary summary = repository.summary(starts[i], starts[i] + 1);
                        double kwh = summary.usedKwh(capacity);
                        week.kwh30[i] = kwh;
                        week.energyKm30[i] = Double.isFinite(kwh) ? summary.energyKm(capacity) : 0;
                        week.idleKwh30[i] = summary.model;
                        week.regen30[i] = summary.regenKwh;
                        week.idleSeconds30[i] = summary.seconds;
                    }
                    day.add(java.util.Calendar.DAY_OF_YEAR, 1);
                }
                starts[days] = day.getTimeInMillis();
                week.capacity = capacity;
                // The week is the newest seven days of the thirty.
                System.arraycopy(week.kwh30, days - 7, week.kwh, 0, 7);
                week.kwhTotal = HomePeriod.sumTail(week.kwh30, 7);
                double used = week.kwhTotal, energyKm = HomePeriod.sumTail(week.energyKm30, 7);
                week.regenKwh = HomePeriod.sumTail(week.regen30, 7);
                week.idleKwh = HomePeriod.sumTail(week.idleKwh30, 7);
                week.idleSeconds = HomePeriod.sumTail(week.idleSeconds30, 7);
                if (energyKm >= MIN_ECONOMY_KM && used > 0) week.economy = used / energyKm * 100;
                week.energyKm = energyKm;
                java.util.List<EnergyModel.Charge> charges = store.charges();
                double homeRate = store.homeRate();
                week.currency = store.currency();
                for (EnergyModel.Charge charge : charges) {
                    if (charge.time < monthStart) continue;
                    for (int i = 0; i < days; i++) if (charge.time >= starts[i] && charge.time < starts[i + 1]) {
                        week.charged30[i] = true;
                        double paid = charge.money(homeRate);
                        if (Double.isFinite(paid) && paid > 0) week.paid30[i] += paid;
                    }
                    if (charge.time >= week.dayStart && charge.time < week.dayStart + 24 * 3_600_000L)
                        week.chargedHours[(int) ((charge.time - week.dayStart) / 3_600_000L)] = true;
                }
                System.arraycopy(week.charged30, days - 7, week.charged, 0, 7);
                week.paid = HomePeriod.sumTail(week.paid30, 7);
                week.price = CarTripCost.averagePrice(charges, homeRate);
                week.gridPerPoint = CarChargeEnergy.gridKwhPerPoint(charges);
                if (week.kwhTotal > 0) {
                    week.gridKwh = CarTripCost.billedEnergy(week.kwhTotal, week.gridPerPoint, capacity);
                    if (Double.isFinite(week.price) && week.price >= 0 && Double.isFinite(week.gridKwh)) week.cost = week.gridKwh * week.price;
                }
                if (!charges.isEmpty()) {
                    week.lastCharge = charges.get(charges.size() - 1);
                    week.lastChargeMoney = week.lastCharge.money(store.homeRate());
                    week.currency = store.currency();
                }
            } catch (RuntimeException failure) {
                android.util.Log.w("OABL", "Energy week unavailable", failure);
            }
            try {
                // The box's own GPS drive log: this week, this month, the latest drive and where it parked.
                TripComputer trips = TripComputer.get(this);
                java.util.List<TripComputer.Trip> history = trips.history();
                week.km30 = HomePeriod.dailyKm(history, week.dayStart, HomePeriod.HISTORY_DAYS);
                week.kmHours = HomePeriod.hourlyKm(history, week.dayStart, System.currentTimeMillis());
                week.km = HomePeriod.tail(week.km30, 7);
                java.util.Calendar month = java.util.Calendar.getInstance();
                month.set(java.util.Calendar.DAY_OF_MONTH, 1); month.set(java.util.Calendar.HOUR_OF_DAY, 0);
                month.set(java.util.Calendar.MINUTE, 0); month.set(java.util.Calendar.SECOND, 0); month.set(java.util.Calendar.MILLISECOND, 0);
                long[] periodStart = new long[HomePeriod.WINDOWS];
                for (int i = 0; i < HomePeriod.WINDOWS; i++) periodStart[i] = week.dayStart - (HomePeriod.fromIndex(i).days - 1) * 86_400_000L;
                for (TripComputer.Trip trip : history) {
                    if (week.lastTrip == null) week.lastTrip = trip;
                    if (trip.start >= month.getTimeInMillis()) { week.monthKm += trip.kilometers; week.monthDrives++; }
                    for (int i = 0; i < HomePeriod.WINDOWS; i++) if (trip.start >= periodStart[i]) week.drives[i]++;
                }
                double[] parked = trips.parked();
                if (parked != null) { week.parkedLat = parked[0]; week.parkedLon = parked[1]; week.parkedSince = (long) parked[2]; }
                // A drive in progress is not parked.
                if (week.lastTrip != null && week.lastTrip.end == 0) week.parkedSince = 0;
            } catch (RuntimeException failure) {
                android.util.Log.w("OABL", "Drive log unavailable", failure);
            }
            android.util.Log.i("OABL", "Energy figures read in " + (android.os.SystemClock.elapsedRealtime() - loadStarted) + " ms");
            handler.post(() -> {
                energyWeek = week;
                energyWeekAt = android.os.SystemClock.elapsedRealtime();
                energyWeekLoading = false;
            });
        });
    }

    /** The newest charge: points added (or kWh), when, and what it cost when known. */
    private DriveStatsView.Cell lastChargeCell(EnergyWeek week) {
        EnergyModel.Charge charge = week == null ? null : week.lastCharge;
        if (charge == null) return new DriveStatsView.Cell("Last charge", "—", "none yet");
        String when = chargeWhen(charge.time);
        double gain = charge.gain(), kwh = charge.billedKwh();
        String value = Double.isFinite(gain) && gain > 0 ? "+" + Math.round(gain) + "%" : Double.isFinite(kwh) ? String.format(Locale.US, "%.1f", kwh) : "—";
        String unit;
        if (Double.isFinite(week.lastChargeMoney)) unit = HomeCost.money(week.lastChargeMoney) + (week.currency.isEmpty() ? "" : " " + week.currency);
        else if (Double.isFinite(gain) && gain > 0 && Double.isFinite(kwh)) unit = String.format(Locale.US, "%.1f kWh", kwh);
        else unit = Double.isFinite(gain) && gain > 0 ? "" : "kWh";
        StringBuilder note = new StringBuilder();
        if (Double.isFinite(charge.before) && Double.isFinite(charge.after))
            note.append(Math.round(charge.before)).append("→").append(Math.round(charge.after)).append("%");
        if (Double.isFinite(kwh) && !unit.endsWith("kWh")) note.append(note.length() > 0 ? " · " : "").append(String.format(Locale.US, "%.1f kWh", kwh));
        note.append(note.length() > 0 ? " · " : "").append(clockTime(charge.time));
        return new DriveStatsView.Cell("Last charge · " + when, value, unit).note(note.toString());
    }

    private String chargeWhen(long time) {
        java.util.Calendar today = java.util.Calendar.getInstance();
        today.set(java.util.Calendar.HOUR_OF_DAY, 0); today.set(java.util.Calendar.MINUTE, 0);
        today.set(java.util.Calendar.SECOND, 0); today.set(java.util.Calendar.MILLISECOND, 0);
        long midnight = today.getTimeInMillis();
        if (time >= midnight) return "today";
        if (time >= midnight - 6 * 86_400_000L)
            return new java.text.SimpleDateFormat("EEE", Locale.getDefault()).format(new java.util.Date(time));
        return new java.text.SimpleDateFormat("d MMM", Locale.getDefault()).format(new java.util.Date(time));
    }

    private boolean driveStatsRoom() {
        View idle = findViewById(R.id.navigation_idle);
        return idle != null && idle.getHeight() >= dp(300);
    }

    private static String duration(double minutes) {
        long total = Math.round(minutes);
        if (total < 60) return total + " min";
        if (total < 24 * 60) return (total / 60) + "h" + (total % 60 == 0 ? "" : " " + (total % 60) + "m");
        return (total / 1440) + "d" + ((total % 1440) / 60 == 0 ? "" : " " + (total % 1440) / 60 + "h");
    }

    private void applyHere() {
        TextView where = findViewById(R.id.navigation_where);
        if (where == null || palette == null || hereRoad == null) return;
        // After a route: the arrival line instead of the live position, until the summary is tapped away.
        ArrivalSummary arrival = arrivalShown() ? arrivalSummary : null;
        String line = arrival != null ? arrival.title : hereRoad;
        String detail = arrival != null ? (arrival.place == null || arrival.place.startsWith("Waiting") ? "" : "At " + arrival.place + "  ·  ")
                + timeRange(arrival.start, arrival.end) + "  ·  Tap to dismiss" : hereDetail;
        boolean stale = arrival == null && hereStale;
        int room = where.getWidth() - where.getPaddingLeft() - where.getPaddingRight();
        // One line for the road, so a long name never pushes the detail line off the card.
        CharSequence road = room > 0 ? android.text.TextUtils.ellipsize(line, where.getPaint(), room,
                android.text.TextUtils.TruncateAt.END) : line;
        String key = road + "|" + stale + "|" + hereFull + "|" + detail + "|" + palette.muted + "|" + palette.ink;
        if (key.equals(hereShownKey)) return;
        hereShownKey = key;
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(road);
        if (stale) text.setSpan(new android.text.style.ForegroundColorSpan(palette.muted), 0, text.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (hereFull && detail != null && !detail.isEmpty()) {
            int start = text.length() + 1;
            text.append('\n').append(detail);
            text.setSpan(new android.text.style.RelativeSizeSpan(0.5f), start, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new android.text.style.ForegroundColorSpan(palette.muted), start, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        where.setText(text);
        where.setContentDescription((arrival != null ? "Trip summary: " : "You are here: ") + text);
    }

    private void renderIdleNavigation() {
        showNavigationState(false);
        renderedNavigationKey = null;
        findViewById(R.id.navigation_arrival_soc).setVisibility(View.GONE);
        arrivalChargeNeeded=false;
        setTurnPulse(false);
        renderNavigationChips();
        navigationHintCard.setContentDescription("Navigation: where to?");
    }

    private void setNavigationIcon(Drawable provided, int fallback) {
        if (provided != null) {
            navigationHintIcon.setImageDrawable(provided);
            navigationHintIcon.setImageTintList(null);
        } else {
            navigationHintIcon.setImageResource(fallback);
            navigationHintIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                    palette != null && !palette.light ? palette.ink : Color.WHITE));
        }
    }

    /** "Navigation" while a route is guided; the idle card with its trip computer is the "Drive & energy" card. */
    private void updateCardTitle() {
        TextView title = findViewById(R.id.navigation_hint_label);
        View stats = findViewById(R.id.navigation_stats);
        boolean guiding = findViewById(R.id.navigation_guidance).getVisibility() == View.VISIBLE;
        String text = !guiding && arrivalShown() ? "TRIP SUMMARY"
                : !guiding && stats != null && stats.getVisibility() == View.VISIBLE ? "DRIVE & ENERGY" : "NAVIGATION";
        if (!text.contentEquals(title.getText())) title.setText(text);
    }

    private void showNavigationState(boolean guidance) {
        View idle = findViewById(R.id.navigation_idle);
        View guiding = findViewById(R.id.navigation_guidance);
        int idleVisibility = guidance ? View.GONE : View.VISIBLE;
        if (idle.getVisibility() != idleVisibility) {
            idle.setVisibility(idleVisibility);
            guiding.setVisibility(guidance ? View.VISIBLE : View.GONE);
        }
        setNavigationGuiding(guidance);
        updateCardTitle();
    }

    /** A static accent ring marks the last ~150 m without forcing continuous video frames. */
    private void setTurnPulse(boolean near) {
        if (near == turnNear || Build.VERSION.SDK_INT < 23) {
            return;
        }
        turnNear = near;
        if (turnPulse != null) {
            turnPulse.cancel();
            turnPulse = null;
        }
        if (!near || palette == null) {
            navigationHintCard.setForeground(null);
            return;
        }
        final GradientDrawable ring = LauncherTheme.rounded(this, Color.TRANSPARENT, 22, 3, palette.accent);
        navigationHintCard.setForeground(ring);
    }

    private void endNavigation() {
        if (activeNavigation.end == null) {
            return;
        }
        try {
            activeNavigation.end.send();
        } catch (PendingIntent.CanceledException ignored) {
            // The route already ended.
        }
        handler.postDelayed(this::renderNavigationHints, 600L);
    }

    /**
     * Destination chips, fitted to the card's width in priority order so nothing clips or repeats:
     * learned "usually now" places from the trip log, saved Home and Work, back to where this drive
     * started, recent places and chargers nearby. While driving only one-tap destinations are
     * offered; typing waits until parked.
     */
    private void renderNavigationChips() {
        if (palette == null) {
            return;
        }
        final String home = preferences.navigationHome();
        final String work = preferences.navigationWork();
        java.util.List<String> recents = preferences.navigationRecents();
        boolean access = hasNotificationAccess();
        // Split or short window: fewer, tighter chips so the row never clips.
        boolean narrow = homeLayout != null && (homeLayout.narrow() || homeLayout.shortWindow());
        DashboardData data = DashboardData.get(this);
        android.location.Location here = data.freshFix() ? data.fix : null;
        java.util.List<PlaceSuggestions.Place> usual = learnedPlaces(here);
        double[] start = TripComputer.get(this).liveStart();
        double backMeters = start == null || here == null ? Double.NaN
                : GpsMotion.distance(here.getLatitude(), here.getLongitude(), start[0], start[1]);
        StringBuilder key = new StringBuilder().append(home).append('|').append(work).append('|').append(recents)
                .append('|').append(java.util.Arrays.toString(preferences.navigationPoint(true)))
                .append(java.util.Arrays.toString(preferences.navigationPoint(false)))
                .append('|').append(access).append('|').append(palette.accent).append(palette.light).append('|')
                .append(drivingMode).append(narrow).append(batteryLow).append(arrivalChargeNeeded).append('|').append(preferences.focus())
                // Re-lay the chips when the card's size settles or changes, since that decides the rows.
                .append('|').append(navigationChipRows()).append('x').append(((View) findViewById(R.id.navigation_chips).getParent()).getWidth())
                .append('x').append(findViewById(R.id.navigation_idle).getHeight());
        for (PlaceSuggestions.Place place : usual) {
            key.append('|').append(placeName(place.lat, place.lon));
        }
        if (backMeters > 1000) key.append("|back").append(placeName(start[0], start[1]));
        if (key.toString().equals(renderedChipsKey)) {
            return;
        }
        renderedChipsKey = key.toString();
        LinearLayout row = findViewById(R.id.navigation_chips);
        row.removeAllViews();
        View container = (View) row.getParent();
        int width = container.getWidth() > 0 ? container.getWidth() : dp(narrow ? 300 : 460);
        // A second row of destinations when the idle card has the height for it, so the card is
        // full of one-tap places instead of empty below a single row.
        chipOverflow = findViewById(R.id.navigation_chips_2);
        chipOverflow.removeAllViews();
        boolean tiles = navigationChipRows() > 1;
        chipTiles = tiles;
        int base = chipBaseHeight();
        setRowHeight(row, base); setRowHeight(chipOverflow, base);
        if (!tiles) for (LinearLayout r : new LinearLayout[]{row, chipOverflow}) {
            ViewGroup.LayoutParams rp = r.getLayoutParams();
            if (rp.width != ViewGroup.LayoutParams.WRAP_CONTENT) { rp.width = ViewGroup.LayoutParams.WRAP_CONTENT; r.setLayoutParams(rp); }
        }
        findViewById(R.id.navigation_chips_2_host).setVisibility(tiles ? View.VISIBLE : View.GONE);
        // Tiles: two rows' worth of places, laid out as a grid of big buttons once they are all in.
        int[] room = {tiles ? width * 2 - dp(20) : width};
        // A short card keeps its one-tap places and drops the "Where to?" heading instead of clipping
        // them; the card's NAVIGATION label already says what they are.
        View where = findViewById(R.id.navigation_where), idle = findViewById(R.id.navigation_idle);
        if (!tiles) findViewById(R.id.navigation_recents).setVisibility(View.GONE);
        // The live position leads the card: road and a detail line, the road alone when short, none when tiny.
        hereFull = true; applyHere();
        where.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED);
        boolean roomForHeading = idle.getHeight() == 0 || idle.getHeight() >= where.getMeasuredHeight() + base * (tiles ? 2 : 1) + dp(tiles ? 24 : 12);
        if (!roomForHeading) {
            hereFull = false; applyHere();
            where.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED);
            roomForHeading = idle.getHeight() >= where.getMeasuredHeight() + base * (tiles ? 2 : 1) + dp(tiles ? 24 : 12);
        }
        where.setVisibility(roomForHeading ? View.VISIBLE : View.GONE);

        if (tiles) {
            // Four stable targets; recents remain available from Search rather than moving Home/Work.
            addNavigationChip(row, "Search", R.drawable.ic_nav_search, false, v -> askDestination(), null);
            TextView chargers=addNavigationChip(row, "Chargers", R.drawable.ic_bar_energy, false, v -> openChargers(), null);
            if(batteryLow||arrivalChargeNeeded)chargers.setTextColor(palette.accent);
            addNavigationChip(row, home==null?"Set Home":"Home", R.drawable.ic_nav_home, home==null,
                    v -> {if(home==null)editPlace(true);else navigateToSaved(true);},v -> {editPlace(true);return true;});
            addNavigationChip(row, work==null?"Set Work":"Work", R.drawable.ic_nav_work, work==null,
                    v -> {if(work==null)editPlace(false);else navigateToSaved(false);},v -> {editPlace(false);return true;});
            renderRecentsStrip(recents, home, work);
            layoutChipTiles(row, chipOverflow);
            return;
        }
        if (drivingMode && (batteryLow||arrivalChargeNeeded)) chargersChip(row, room);
        int learned = 0;
        for (PlaceSuggestions.Place place : usual) {
            if (learned == (drivingMode ? 2 : 1)) break;
            String name = placeName(place.lat, place.lon);
            String label = name == null || name.isEmpty() ? "Usual place" : name;
            final double lat = place.lat, lon = place.lon;
            fitChip(row, room, addNavigationChip(row, label,
                    R.drawable.ic_nav_recent, false, v -> navigateToPoint(lat, lon), null),
                    "Usually around now: " + label);
            learned++;
        }
        if (home != null) fitChip(row, room, addNavigationChip(row, "Home", R.drawable.ic_nav_home, false,
                v -> navigateToSaved(true), drivingMode ? null : v -> { editPlace(true); return true; }), null);
        if (work != null) fitChip(row, room, addNavigationChip(row, "Work", R.drawable.ic_nav_work, false,
                v -> navigateToSaved(false), drivingMode ? null : v -> { editPlace(false); return true; }), null);
        if (!drivingMode) {
            // Search with its label when it fits, otherwise its icon alone (it always stays).
            if (narrow || !fitChip(row, room, addNavigationChip(row, "Search", R.drawable.ic_nav_search, false, v -> askDestination(), null), "Search")) {
                fitChip(row, room, addNavigationChip(row, "", R.drawable.ic_nav_search, false, v -> askDestination(), null), "Search");
            }
        }
        if (backMeters > 1000) {
            String name = placeName(start[0], start[1]);
            final double lat = start[0], lon = start[1];
            fitChip(row, room, addNavigationChip(row, "Back to " + (name == null || name.isEmpty() ? "start" : name),
                    R.drawable.ic_nav_turn, false, v -> navigateToPoint(lat, lon), null),
                    "Back to where this drive started");
        }
        if (!(drivingMode && (batteryLow||arrivalChargeNeeded))) chargersChip(row, room);
        int shown = 0;
        for (final String place : recents) {
            if (shown == (narrow ? 0 : 2)) break;
            if (place.equals(home) || place.equals(work)) continue;
            fitChip(row, room, addNavigationChip(row, shortPlace(place), R.drawable.ic_nav_recent, false,
                    v -> navigateTo(place),
                    v -> { forgetRecent(place); return true; }), null);
            shown++;
        }
        // Setting up places is lowest priority here; holding "Where to?" always offers it too.
        if (!drivingMode && home == null) fitChip(row, room, addNavigationChip(row, "Set Home", R.drawable.ic_nav_home, true,
                v -> editPlace(true), null), null);
        if (!drivingMode && work == null) fitChip(row, room, addNavigationChip(row, "Set Work", R.drawable.ic_nav_work, true,
                v -> editPlace(false), null), null);
        if (!drivingMode && !access) {
            fitChip(row, room, addNavigationChip(row, "Turn-by-turn on Home", R.drawable.ic_nav_access, true,
                    v -> openSystemSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), null), null);
        }
        if (tiles) layoutChipTiles(row, chipOverflow);
    }

    private boolean chipTiles;

    private int chipBaseHeight() {
        return dp(48);
    }

    private static void setRowHeight(View row, int height) {
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (params.height != height) { params.height = height; row.setLayoutParams(params); }
    }

    /**
     * The idle card has room: split the places across two rows of equal-width tiles as tall as the
     * card allows, so the space below "Where to?" becomes big one-tap destinations.
     */
    private void layoutChipTiles(LinearLayout first, LinearLayout second) {
        int count = first.getChildCount();
        if (count < 2) { findViewById(R.id.navigation_chips_2_host).setVisibility(View.GONE); return; }
        View idle = findViewById(R.id.navigation_idle), where = findViewById(R.id.navigation_where);
        int recentsHeight = findViewById(R.id.navigation_recents).getVisibility() == View.VISIBLE ? dp(44) : 0;
        int heading = where.getVisibility()==View.VISIBLE ? Math.max(where.getHeight(), where.getMeasuredHeight()) : 0;
        int tile = Math.max(chipBaseHeight(), Math.min(dp(150), (idle.getHeight() - heading - dp(20) - recentsHeight) / 2));
        int split = (count + 1) / 2;
        for (int i = count - 1; i >= split; i--) {
            View chip = first.getChildAt(i);
            first.removeViewAt(i);
            second.addView(chip, 0);
        }
        for (LinearLayout row : new LinearLayout[]{first, second}) {
            setRowHeight(row, tile);
            ViewGroup.LayoutParams rp = row.getLayoutParams();
            int available=((View)row.getParent()).getWidth();
            if(available>0 && rp.width!=available) {rp.width=available;row.setLayoutParams(rp);}
            for (int i = 0; i < row.getChildCount(); i++) {
                TextView chip = (TextView) row.getChildAt(i);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                params.setMarginEnd(i == row.getChildCount() - 1 ? 0 : dp(10));
                chip.setLayoutParams(params);
                // A tile: the icon above its label, both centred, like a car's own home screen.
                if (chip.getText().length() == 0 && chip.getContentDescription() != null) chip.setText(chip.getContentDescription());
                chip.setGravity(Gravity.CENTER);
                chip.setMaxLines(2);
                chip.setTextSize(homeLayout!=null&&homeLayout.narrow()?15:chip.getTextSize() / getResources().getDisplayMetrics().scaledDensity * 1.12f);
                Drawable[] glyphs = chip.getCompoundDrawablesRelative();
                Drawable glyph = glyphs[0] != null ? glyphs[0] : glyphs[1];
                // Tall tiles stack the icon over the label; shorter ones keep it beside the label.
                boolean stacked = tile >= dp(88);
                if (glyph != null) {
                    int size = stacked ? dp(tile >= dp(130) ? 44 : 32) : dp(homeLayout!=null&&homeLayout.narrow()?20:24);
                    glyph.setBounds(0, 0, size, size);
                    if (stacked) chip.setCompoundDrawablesRelative(null, glyph, null, null);
                    else chip.setCompoundDrawablesRelative(glyph, null, null, null);
                }
                chip.setCompoundDrawablePadding(dp(stacked ? (tile >= dp(130) ? 12 : 6) : homeLayout!=null&&homeLayout.narrow()?6:10));
                int side=stacked?10:homeLayout!=null&&homeLayout.narrow()?6:20;
                // The icon is drawn at the top padding, so the padding centres icon and label as one group.
                int group = (glyph == null ? 0 : glyph.getBounds().height() + chip.getCompoundDrawablePadding()) + chip.getLineHeight();
                int vertical = stacked ? Math.max(dp(8), (tile - group) / 2) : 0;
                chip.setPadding(dp(side),vertical,dp(side),vertical);
                if (!stacked && glyph != null) {
                    // Every tile lines up the same way: icon at a fixed distance from the left edge, label right
                    // after it, so the four icons and the four labels each sit in a clean column.
                    chip.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                    int start = dp(homeLayout != null && homeLayout.narrow() ? 14 : 28);
                    chip.setPaddingRelative(start, chip.getPaddingTop(), dp(10), chip.getPaddingBottom());
                    chip.setCompoundDrawablePadding(dp(14));
                }
            }
        }
    }

    /** Keeps a chip only if the row still has room for it (chips are never clipped). */
    private boolean fitChip(LinearLayout row, int[] room, TextView chip, String description) {
        if (description != null) chip.setContentDescription(description);
        int height = row.getLayoutParams().height > 0 ? row.getLayoutParams().height : dp(48);
        chip.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        int needed = chip.getMeasuredWidth() + dp(10);
        if (needed <= room[0]) { room[0] -= needed; return true; }
        // The first row is full: continue on the second, keeping the priority order.
        room[0] = 0;
        if (room.length > 1 && chipOverflow != null && needed <= room[1]) {
            ViewGroup.LayoutParams params = chip.getLayoutParams();
            row.removeView(chip);
            chipOverflow.addView(chip, params);
            room[1] -= needed;
            return true;
        }
        row.removeView(chip);
        return false;
    }

    private LinearLayout chipOverflow;

    /** How many chip rows the idle navigation card can hold at its current height. */
    /** Home and Work: set or change them. Also reachable from "Manage places" under the destination tiles. */
    private void showSavedPlaces() {
        String home = preferences.navigationHome(), work = preferences.navigationWork();
        dialogBuilder().setTitle("Saved places").setItems(new String[]{
                (home == null ? "Set Home" : "Change Home · " + shortPlace(home)),
                (work == null ? "Set Work" : "Change Work · " + shortPlace(work))},
                (d, which) -> editPlace(which == 0)).setNegativeButton("Close", null).show();
    }

    /** One line under the tiles: the last places you went (not Home or Work) and a way to manage places. */
    private void renderRecentsStrip(java.util.List<String> recents, String home, String work) {
        LinearLayout strip = findViewById(R.id.navigation_recents);
        strip.removeAllViews();
        View idle = findViewById(R.id.navigation_idle), where = findViewById(R.id.navigation_where);
        int heading = where.getVisibility() == View.VISIBLE ? Math.max(where.getHeight(), where.getMeasuredHeight()) : 0;
        boolean narrowCard = homeLayout != null && homeLayout.narrow();
        boolean room = idle.getHeight() > 0 && idle.getHeight() - heading - dp(20) - dp(44) >= chipBaseHeight() * 2;
        java.util.List<String> shown = new java.util.ArrayList<>();
        for (String place : recents) {
            if (place.equals(home) || place.equals(work)) continue;
            shown.add(place);
            if (shown.size() == (narrowCard ? 1 : 2)) break;
        }
        if (!room || drivingMode || shown.isEmpty()) { strip.setVisibility(View.GONE); return; }
        strip.setVisibility(View.VISIBLE);
        TextView label = new TextView(this);
        label.setText("RECENT");
        label.setTextSize(13);
        label.setTextColor(palette.muted);
        label.setLetterSpacing(.14f);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        label.setPadding(0, 0, dp(10), 0);
        strip.addView(label);
        for (final String place : shown) {
            TextView pill = new TextView(this);
            pill.setText(shortPlace(place));
            pill.setTextSize(14);
            pill.setTextColor(palette.ink);
            pill.setSingleLine(true);
            pill.setEllipsize(android.text.TextUtils.TruncateAt.END);
            pill.setMaxWidth(dp(narrowCard ? 150 : 220));
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(dp(12), 0, dp(12), 0);
            pill.setBackground(ripple(LauncherTheme.rounded(this, palette.light ? 0x10142e38 : 0x14ffffff, 18, 0, 0), palette.light ? 0x22102a2d : 0x33ffffff));
            pill.setOnClickListener(v -> navigateTo(place));
            pill.setOnLongClickListener(v -> { forgetRecent(place); return true; });
            pill.setContentDescription("Recent place " + shortPlace(place) + ". Hold to forget.");
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            size.setMarginEnd(dp(8));
            strip.addView(pill, size);
        }
        View spacer = new View(this);
        strip.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        TextView manage = new TextView(this);
        manage.setText("Places ›");
        manage.setTextSize(14);
        manage.setTextColor(palette.accent);
        manage.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        manage.setGravity(Gravity.CENTER_VERTICAL);
        manage.setPadding(dp(10), 0, dp(4), 0);
        manage.setOnClickListener(v -> { if (!blockedWhileDriving()) showSavedPlaces(); });
        manage.setContentDescription("Manage Home and Work");
        strip.addView(manage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
    }

    private int navigationChipRows() {
        View idle = findViewById(R.id.navigation_idle), where = findViewById(R.id.navigation_where);
        if (idle == null || idle.getHeight() == 0 || drivingMode) return 1;
        // A card with the trip computer keeps its places to one compact row under it.
        if (driveStatsRoom()) return 1;
        // Two rows of tiles only below the live position, never instead of it.
        TextView here = (TextView) where;
        int needed = chipBaseHeight() * 2 + dp(20) + Math.round(here.getLineHeight() * 1.6f) + dp(12);
        return idle.getHeight() >= needed ? 2 : 1;
    }

    /** One tap to the map app's search for EV chargers around the car (accented when the battery runs low). */
    private void chargersChip(LinearLayout row, int[] room) {
        boolean needed=batteryLow||arrivalChargeNeeded;
        TextView chip = addNavigationChip(row, arrivalChargeNeeded?"Chargers · route low":batteryLow?"Chargers · battery low":"Chargers",
                R.drawable.ic_bar_energy, false, v -> openChargers(), null);
        if (needed) chip.setTextColor(palette.accent);
        // Its label when it fits, otherwise the charger icon alone.
        if (!fitChip(row, room, chip, "Find EV chargers nearby")) {
            fitChip(row, room, addNavigationChip(row, "", R.drawable.ic_bar_energy, false, v -> openChargers(), null),
                    "Find EV chargers nearby");
        }
    }

    private void openChargers() {
        DashboardData data = DashboardData.get(this);
        android.location.Location fix = data.freshFix() ? data.fix : null;
        String where = fix == null ? "0,0" : String.format(Locale.US, "%.5f,%.5f", fix.getLatitude(), fix.getLongitude());
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + where + "?q=" + Uri.encode("EV charging station")))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ComponentName map = preferences.focus();
        if (map != null) intent.setPackage(map.getPackageName());
        try {
            SplitScreenService.showAppControls(MainActivity.this);
            startActivity(intent);
        } catch (ActivityNotFoundException withChosenApp) {
            try { startActivity(intent.setPackage(null)); }
            catch (ActivityNotFoundException none) {
                SplitScreenService.hideAppControls(MainActivity.this);
                Toast.makeText(this, "No map app can search for chargers", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Navigates to a learned place or the drive's start (coordinates; not added to recents). */
    private void navigateToPoint(double lat, double lon) {
        pendingDestination = String.format(Locale.US, "%.6f,%.6f", lat, lon);
        requestSpeedAccess(true);
    }

    private java.util.List<PlaceSuggestions.Place> learnedPlacesCache = new ArrayList<>();
    private long learnedAt;
    private double learnedLat, learnedLon;
    /** Set by the battery card: the estimate is near the reserve, so chargers come first. */
    private boolean batteryLow;

    /** Learned destinations from the trip log, recomputed at most once a minute or after moving 500 m. */
    private java.util.List<PlaceSuggestions.Place> learnedPlaces(android.location.Location here) {
        long now = android.os.SystemClock.elapsedRealtime();
        boolean moved = here != null && GpsMotion.distance(learnedLat, learnedLon, here.getLatitude(), here.getLongitude()) > 500;
        if (learnedAt != 0 && now - learnedAt < 60_000 && !moved) return learnedPlacesCache;
        learnedAt = now;
        if (here != null) { learnedLat = here.getLatitude(); learnedLon = here.getLongitude(); }
        java.util.List<PlaceSuggestions.Visit> ends = new ArrayList<>();
        for (TripComputer.Trip trip : TripComputer.get(this).history()) {
            if (trip.end > 0 && (trip.endLat != 0 || trip.endLon != 0)) ends.add(new PlaceSuggestions.Visit(trip.endLat, trip.endLon, trip.end));
        }
        learnedPlacesCache = PlaceSuggestions.suggest(ends, System.currentTimeMillis(), java.util.TimeZone.getDefault(),
                here == null ? 0 : here.getLatitude(), here == null ? 0 : here.getLongitude(), here != null, 2);
        return learnedPlacesCache;
    }

    private static final java.util.concurrent.ExecutorService PLACE_LOOKUP = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.Map<String, String> placeNames = new java.util.HashMap<>();

    /** Street or area for a point (~100 m cells), looked up once off the main thread; null while pending. */
    private String placeName(double lat, double lon) {
        final String key = String.format(Locale.US, "%.3f,%.3f", lat, lon);
        synchronized (placeNames) {
            if (placeNames.containsKey(key)) return placeNames.get(key);
            placeNames.put(key, null);
        }
        PLACE_LOOKUP.execute(() -> {
            String name = "";
            if (android.location.Geocoder.isPresent()) {
                try {
                    java.util.List<android.location.Address> found = new android.location.Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1);
                    if (found != null && !found.isEmpty()) {
                        android.location.Address a = found.get(0);
                        for (String part : new String[]{a.getThoroughfare(), a.getSubLocality(), a.getLocality(), a.getSubAdminArea()}) {
                            if (part != null && !part.trim().isEmpty()) { name = part.trim(); break; }
                        }
                    }
                } catch (Exception offline) {
                    // The chip keeps its generic label.
                }
            }
            final String value = name;
            synchronized (placeNames) { placeNames.put(key, value); }
            handler.post(() -> { renderedChipsKey = null; renderNavigationChips(); });
        });
        return null;
    }

    private TextView addNavigationChip(LinearLayout row, String text, int icon, boolean quiet,
                                   View.OnClickListener click, View.OnLongClickListener longClick) {
        TextView chip = new TextView(this);
        chip.setText(text);
        float scale = Math.max(1f, Math.min(1.3f, getResources().getConfiguration().screenHeightDp / 600f));
        boolean compact = homeLayout != null && (homeLayout.narrow() || homeLayout.shortWindow());
        chip.setTextSize((drivingMode ? 19 : compact ? 15 : 17) * scale);
        chip.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        chip.setTextColor(quiet ? palette.muted : palette.ink);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setMaxLines(1);
        chip.setPadding(dp(16), 0, dp(18), 0);
        chip.setCompoundDrawablePadding(dp(9));
        Drawable glyph = getDrawable(icon).mutate();
        glyph.setTint(palette.accent);
        glyph.setBounds(0, 0, dp(compact ? 20 : 24), dp(compact ? 20 : 24));
        chip.setCompoundDrawablesRelative(glyph, null, null, null);
        chip.setBackground(ripple(LauncherTheme.rounded(this,
                palette.light ? 0x14102a2d : 0x1cffffff, 26, quiet ? 1 : 0,
                palette.light ? 0x33102a2d : 0x33ffffff), palette.light ? 0x22102a2d : 0x33ffffff));
        chip.setOnClickListener(click);
        if (longClick != null) chip.setOnLongClickListener(longClick);
        chip.setContentDescription(text);
        if (text.isEmpty()) { chip.setPadding(dp(18), 0, dp(18), 0); chip.setCompoundDrawablePadding(0); }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        params.setMarginEnd(dp(8));
        row.addView(chip, params);
        installMotionFeedback(chip);
        return chip;
    }

    private static String shortPlace(String place) {
        String first = place.split(",")[0].trim();
        return first.length() > 22 ? first.substring(0, 21) + "…" : first;
    }


    /**
     * Home or Work, set precisely: pick it on a map, take the car's spot now, choose one of the
     * places your drives end most often, or type an address (least exact). Google Maps can also
     * share a place to OABL (PlaceShareActivity).
     */
    private void editPlace(final boolean home) {
        if (blockedWhileDriving()) return;
        String what = home ? "Home" : "Work";
        java.util.List<String> labels = new ArrayList<>();
        java.util.List<Runnable> actions = new ArrayList<>();
        labels.add("Pick on the map");
        actions.add(() -> startActivityForResult(new Intent(this, PlacePickerActivity.class)
                .putExtra(PlacePickerActivity.EXTRA_HOME, home), REQUEST_PLACE));
        DashboardData data = DashboardData.get(this);
        if (data.freshFix()) {
            final android.location.Location here = data.fix;
            String name = placeName(here.getLatitude(), here.getLongitude());
            labels.add("Here · " + (name == null || name.isEmpty() ? "the car's location now" : name));
            actions.add(() -> savePlace(home, here.getLatitude(), here.getLongitude(), name));
        }
        java.util.List<PlaceSuggestions.Visit> ends = new ArrayList<>();
        for (TripComputer.Trip trip : TripComputer.get(this).history()) {
            if (trip.end > 0 && (trip.endLat != 0 || trip.endLon != 0)) ends.add(new PlaceSuggestions.Visit(trip.endLat, trip.endLon, trip.end));
        }
        for (PlaceSuggestions.Place usual : PlaceSuggestions.frequent(ends, 3)) {
            String name = placeName(usual.lat, usual.lon);
            final String label = name == null || name.isEmpty() ? String.format(Locale.US, "%.4f, %.4f", usual.lat, usual.lon) : name;
            labels.add(label + " · ended " + usual.visits + " drives");
            actions.add(() -> savePlace(home, usual.lat, usual.lon, label));
        }
        labels.add("Type an address");
        actions.add(() -> typePlace(home));
        String current = home ? preferences.navigationHome() : preferences.navigationWork();
        if (current != null) {
            labels.add("Clear " + what);
            actions.add(() -> {
                if (home) preferences.setNavigationHome(null); else preferences.setNavigationWork(null);
                renderedChipsKey = null;
                renderNavigationHints();
            });
        }
        dialogBuilder().setTitle(current == null ? "Set " + what : what + " · " + shortPlace(current))
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton("Cancel", null).show();
    }

    private void savePlace(boolean home, double lat, double lon, String label) {
        preferences.setNavigationPoint(home, lat, lon, label);
        renderedChipsKey = null;
        renderNavigationHints();
        Toast.makeText(this, (home ? "Home" : "Work") + " saved" + (label == null || label.isEmpty() ? "" : " · " + label), Toast.LENGTH_SHORT).show();
    }

    /** One tap to Home or Work: the exact point when one was picked, else the typed address. */
    private void navigateToSaved(boolean home) {
        double[] point = preferences.navigationPoint(home);
        if (point != null) navigateToPoint(point[0], point[1]);
        else navigateTo(home ? preferences.navigationHome() : preferences.navigationWork());
    }

    private void typePlace(final boolean home) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint("Address or place name");
        input.setText(home ? preferences.navigationHome() : preferences.navigationWork());
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        dialogBuilder().setTitle(home ? "Home address" : "Work address")
                .setMessage("A typed address is the least exact; picking on the map saves the precise spot.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    if (home) preferences.setNavigationHome(input.getText().toString());
                    else preferences.setNavigationWork(input.getText().toString());
                    renderedChipsKey = null;
                    renderNavigationHints();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void askDestination() {
        if (blockedWhileDriving()) return;
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint("Search a place or address");
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        final AlertDialog dialog = dialogBuilder().setTitle("Where to?")
                .setView(box)
                .setPositiveButton("Go", (d, w) -> navigateTo(input.getText().toString()))
                .setNegativeButton("Cancel", null)
                .create();
        java.util.List<String> recentPlaces=preferences.navigationRecents();
        if(!recentPlaces.isEmpty()) {
            ScrollView recentScroll=new ScrollView(this);LinearLayout recentList=new LinearLayout(this);recentList.setOrientation(LinearLayout.VERTICAL);
            recentScroll.addView(recentList);box.addView(recentScroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(160)));
            for(String place:recentPlaces) {
                TextView recent=new TextView(this);recent.setText(place);recent.setTextSize(18);recent.setTextColor(palette.ink);
                recent.setPadding(dp(8),dp(12),dp(8),dp(12));recent.setMinHeight(dp(48));
                recent.setOnClickListener(v->{dialog.dismiss();navigateTo(place);});recentList.addView(recent);
            }
        }
        input.setOnEditorActionListener((view, action, event) -> {
            dialog.dismiss();
            navigateTo(input.getText().toString());
            return true;
        });
        dialog.show();
        ThemedDialog.match(dialog, palette);
    }

    private void forgetRecent(final String place) {
        dialogBuilder().setTitle("Remove from recents?")
                .setMessage(place)
                .setPositiveButton("Remove", (d, w) -> {
                    preferences.removeNavigationRecent(place);
                    renderedChipsKey = null;
                    renderNavigationHints();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setNavigationGuiding(boolean guiding) {
        if (guiding == navigationGuiding) return;
        navigationGuiding = guiding;
        applyCompactLayoutIfNeeded();
    }
    private void applyDashboardMode(){
        if(homeLayout!=null&&homeLayout.mode()!=desiredDashboardMode())applyCompactLayoutIfNeeded();
    }

    private void openNavigationHint() {
        if (arrivalShown()) {
            dismissArrival();
            return;
        }
        if (!activeNavigation.active) {
            startNavigationMode();
            return;
        }
        if (activeNavigation.open != null) {
            try {
                SplitScreenService.showAppControls(MainActivity.this);
                activeNavigation.open.send();
                playLaunchTransition();
                return;
            } catch (PendingIntent.CanceledException ignored) {
                // The route ended after this card rendered. Open the provider instead.
            } catch (RuntimeException ignored) {
                SplitScreenService.hideAppControls(MainActivity.this);
            }
        }
        launchPackage(activeNavigation.packageName);
    }

    private void setMediaButtonsEnabled(boolean enabled) {
        int[] ids = {R.id.media_previous, R.id.media_play_pause, R.id.media_next};
        for (int id : ids) {
            View view = findViewById(id);
            view.setEnabled(enabled);
            view.setAlpha(enabled ? 1f : 0.35f);
        }
    }

    private boolean hasNotificationAccess() {
        ComponentName listener = new ComponentName(this, MediaAccessService.class);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            return manager != null && manager.isNotificationListenerAccessGranted(listener);
        }
        String enabled = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        return enabled != null && enabled.contains(listener.flattenToString());
    }

    private void chooseShortcut(final int slot) {
        pickApp("Choose shortcut " + (slot + 1), new AppChoice() {
            @Override
            public void selected(AppEntry app) {
                preferences.setShortcut(slot, app.component);
                renderDock();
            }
        });
    }

    private void chooseSplitPair() {
        pickApp("Choose left app", new AppChoice() {
            @Override
            public void selected(final AppEntry primary) {
                pickApp("Choose right app", new AppChoice() {
                    @Override
                    public void selected(AppEntry secondary) {
                        preferences.setSplitPair(primary.component, secondary.component);
                        Toast.makeText(MainActivity.this,
                                primary.label + " + " + secondary.label,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void showSplitPairEditor() {
        final ComponentName left = preferences.splitPrimary();
        final ComponentName right = preferences.splitSecondary();
        String[] actions = {
                "Left window · " + appLabel(left),
                "Right window · " + appLabel(right),
                "Swap left and right",
                "Launch pair"
        };
        dialogBuilder()
                .setTitle("Split pair · choose an action")
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0: chooseSplitSide(true); break;
                            case 1: chooseSplitSide(false); break;
                            case 2:
                                preferences.setSplitPair(right, left);
                                Toast.makeText(MainActivity.this, "Split sides swapped",
                                        Toast.LENGTH_SHORT).show();
                                showSplitPairEditor();
                                break;
                            case 3: launchSplitPair(); break;
                            default: break;
                        }
                    }
                })
                .setNegativeButton("Exit split view", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        exitSplitView();
                    }
                })
                .show();
    }

    /**
     * Remove OABL's pane from the system-managed split pair. Android then
     * promotes the remaining pane to fullscreen. Relaunch OABL after that
     * transition so the driver lands back on the full launcher dashboard.
     */
    private void exitSplitView() {
        if (SplitScreenService.exitPair(MainActivity.this, preferences.splitPrimary())) {
            Toast.makeText(this, "Closing split view", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInMultiWindowMode()) {
            Toast.makeText(this, "Split view is not active", Toast.LENGTH_SHORT).show();
            return;
        }

        final Context application = getApplicationContext();
        finishAndRemoveTask();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Intent launcher = new Intent(application, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                try {
                    application.startActivity(launcher);
                } catch (RuntimeException error) {
                    Toast.makeText(application, "Split view closed", Toast.LENGTH_SHORT).show();
                }
            }
        }, 500L);
    }

    private void chooseSplitSide(final boolean chooseLeft) {
        pickApp(chooseLeft ? "Choose left window" : "Choose right window",
                new AppChoice() {
                    @Override
                    public void selected(AppEntry app) {
                        ComponentName left = chooseLeft
                                ? app.component : preferences.splitPrimary();
                        ComponentName right = chooseLeft
                                ? preferences.splitSecondary() : app.component;
                        preferences.setSplitPair(left, right);
                        showSplitPairEditor();
                    }
                });
    }

    private String appLabel(ComponentName component) {
        AppEntry app = appRepository.find(apps, component);
        return app == null ? "Not selected" : app.label;
    }

    private void launchSplitPair() {
        final ComponentName primary = preferences.splitPrimary();
        final ComponentName secondary = preferences.splitSecondary();
        if (primary == null || secondary == null) {
            chooseSplitPair();
            return;
        }
        if(moving()&&(videoApp(primary.getPackageName())||videoApp(secondary.getPackageName()))){
            Toast.makeText(this,"Video blocked while driving",Toast.LENGTH_SHORT).show();
            return;
        }
        if (SplitScreenService.launchPair(MainActivity.this, primary, secondary)) {
            return;
        }
        if (SplitScreenService.isConnected(MainActivity.this)) {
            showSplitUnavailable();
            return;
        }
        dialogBuilder()
                .setTitle("Turn on split screen")
                .setMessage("Android needs a one-time Accessibility setting to start split screen. "
                        + "Turn on “Open AI Box app controls”, then try again. It does not read your screen.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    }
                })
                .show();
    }

    private Intent launcherIntent(ComponentName component) {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    private void showSplitUnavailable() {
        dialogBuilder()
                .setTitle("Split screen is blocked")
                .setMessage("This box or one of the apps refused split screen. Try another pair. If it keeps failing, "
                        + "turn on Developer options → Force activities to be resizable and restart the box.")
                .setNegativeButton("Close", null)
                .setPositiveButton("Developer options", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openSystemSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                    }
                })
                .show();
    }

    /**
     * Once per box start, after the startup message: show service reminders that are due, then
     * resume music and open the navigation app if those options are on.
     */
    private void runStartActions() {
        int boot = bootCount(this);
        if (startupNotice != null || preferences.startActionsBoot() == boot) return;
        preferences.setStartActionsBoot(boot);
        ServiceReminders reminders = new ServiceReminders(this);
        List<ServiceReminders.Item> due = reminders.due();
        if (!due.isEmpty()) {
            StringBuilder text = new StringBuilder();
            for (ServiceReminders.Item item : due) text.append(item.name).append(" · ").append(reminders.status(item)).append('\n');
            dialogBuilder().setTitle("Service due").setMessage(text.toString().trim())
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Manage", (d, w) -> ServiceReminders.manage(this, palette, () -> { })).show();
        }
        if (preferences.resumeMusicOnStart() && MusicClient.get(this).currentId() > 0) {
            handler.postDelayed(() -> MusicClient.get(this).command("play"), 1200);
        }
        if (preferences.openNavigationOnStart() && preferences.focus() != null) {
            handler.postDelayed(() -> {
                AppEntry map = appRepository.find(apps, preferences.focus());
                if (map != null && foreground) launch(map.component, null);
            }, 2500);
        }
    }

    /** Restarts the whole app so every component reloads the restored settings. */
    private void restartLauncher() {
        android.app.AlarmManager alarms = getSystemService(android.app.AlarmManager.class);
        android.app.PendingIntent again = android.app.PendingIntent.getActivity(this, 77,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_CANCEL_CURRENT);
        if (alarms != null) alarms.set(android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 800, again);
        handler.postDelayed(() -> { finishAffinity(); android.os.Process.killProcess(android.os.Process.myPid()); }, 600);
    }

    private static int bootCount(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException unknown) {
            return 0;
        }
    }

    /** Once per boot, a message that stays on Home until it is acknowledged. */
    private void maybeShowStartupNotice() {
        if (startupNotice != null || !preferences.startupNoticeEnabled()
                || preferences.startupAcknowledgedBoot() == bootCount(this)) return;
        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0xcc05070a);
        scrim.setClickable(true);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(44), dp(36), dp(44), dp(30));
        card.setBackground(LauncherTheme.rounded(this, palette.light ? 0xfff7f9fa : 0xff161d25, 28, 0, 0));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_notice_warning);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(0xffe0a03a));
        card.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = new TextView(this);
        title.setText(preferences.startupTitle());
        title.setTextSize(30);
        title.setTextColor(palette.ink);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams titleSize = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleSize.topMargin = dp(14);
        card.addView(title, titleSize);
        TextView message = new TextView(this);
        message.setText(preferences.startupMessage());
        message.setTextSize(21);
        message.setTextColor(palette.muted);
        message.setGravity(Gravity.CENTER);
        message.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams messageSize = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        messageSize.topMargin = dp(12);
        card.addView(message, messageSize);
        TextView done = new TextView(this);
        done.setText("Done");
        done.setTextSize(20);
        done.setGravity(Gravity.CENTER);
        done.setTextColor(palette.light ? Color.WHITE : palette.root);
        done.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        done.setBackground(LauncherTheme.rounded(this, palette.accent, 22, 0, 0));
        done.setTag("startup_done");
        done.setOnClickListener(v -> {
            preferences.setStartupAcknowledgedBoot(bootCount(this));
            ((ViewGroup) findViewById(R.id.root)).removeView(startupNotice);
            startupNotice = null;
            lastInteraction = android.os.SystemClock.elapsedRealtime();
            runStartActions();
        });
        LinearLayout.LayoutParams doneSize = new LinearLayout.LayoutParams(dp(300), dp(68));
        doneSize.topMargin = dp(28);
        card.addView(done, doneSize);
        scrim.addView(card, new FrameLayout.LayoutParams(dp(760), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        startupNotice = scrim;
        ViewGroup root = findViewById(R.id.root);
        root.addView(scrim, root.indexOfChild(screensaver), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Large, driving-safe controls on Home while moving above the configured speed. */
    /** Driving mode, or a current speed of 5 km/h or more from the car or GPS. */
    private boolean moving() {
        if (drivingMode) return true;
        SpeedGaugeView gauge = findViewById(R.id.speed_cluster);
        return gauge != null && gauge.known() && gauge.speed() >= 5;
    }

    private void setDrivingMode(boolean on) {
        if (drivingMode == on) return;
        drivingMode = on;
        if (on) DrivingDismiss.dismissAll();
        // Guidance can expand while weather, forecast and trip data remain on Home.
        applyCompactLayoutIfNeeded();
        renderedChipsKey = null;
        renderNavigationChips();
        // HomeLayout owns transport sizing in all three modes, including the large media player.
        ((TextView) findViewById(R.id.telemetry_label)).setText(speedLabel(home != null && home.car && Double.isFinite(home.currentSpeed(System.currentTimeMillis()))));
        renderDock();
        renderChargeReviewPill();
    }

    /** Settings and other typing-heavy screens wait until the car is parked. */
    private boolean blockedWhileDriving() {
        if (!drivingMode) return false;
        Toast.makeText(this, "Available when parked", Toast.LENGTH_SHORT).show();
        return true;
    }

    /** With "Match wallpaper", a new wallpaper re-tints the whole launcher. */
    void wallpaperColorChanged() {
        if (preferences.accentIndex() == LauncherTheme.MATCH_WALLPAPER) applyAppearance(preferences.visualEffects());
    }

    private void showLauncherSettings() {
        if (blockedWhileDriving()) return;
        new SettingsPanel(this, palette).show();
    }

    String settingTitle(String id) {
        switch (id) {
            case "appearance": return "Appearance";
            case "weather": return "Weather";
            case "start_actions": return "When the box starts";
            case "speed_limit": return "Speed limit";
            case "service": return "Service reminders";
            case "album_art": return "Album covers online";
            case "backup": return "Back up & restore settings";
            case "accent": return "Accent colour";
            case "wallpaper": return "Wallpaper";
            case "background": return "Background treatment";
            case "experience": return "Animations";
            case "home_role": return "Default launcher";
            case "home_tab": return "Back & Home over other apps";
            case "screensaver": return "Screensaver when parked";
            case "screensaver_image": return "Screensaver background";
            case "car_data": return "Car data";
            case "startup": return "Startup message";
            case "split": return "Split-screen pair";
            case "driving_mode": return "Driving mode";
            case "max_speed": return "Speedometer scale";
            case "nav_app": return "Navigation app";
            case "vehicle": return "Vehicle and energy";
            case "energy": return "Energy";
            case "car_source": return "Vehicle data source";
            case "trips": return "Trip history";
            case "equalizer": return "Equalizer";
            case "crossfade": return "Crossfade";
            case "music": return "Music library";
            case "media_access": return "Media & navigation access";
            case "overlay": return "Navigation display permission";
            case "split_assistant": return "Back & Home assistant";
            case "android_settings": return "Android settings";
            case "performance": return "Performance & diagnostics";
            case "about": return "About";
            default: return id;
        }
    }

    /** One plain line saying what a setting does, shown under its value when the row has room. */
    String settingHint(String id) {
        switch (id) {
            case "appearance": return "Graphite night, Pearl day, or follow the sun";
            case "accent": return "Colour of highlights, buttons and the speed arc";
            case "wallpaper": return "The photo behind the dashboard cards";
            case "background": return "How the wallpaper is dimmed behind the cards";
            case "experience": return "Cuts motion and blur; the car screen is a video stream";
            case "home_role": return "Make OABL the box's Home screen";
            case "home_tab": return "Back and Home buttons kept on the screen edge";
            case "weather": return "Forecast and rain alerts for your approximate area";
            case "screensaver": return "Dims to a clock and weather once the car is parked";
            case "screensaver_image": return "The photo behind the parked screensaver";
            case "startup": return "A message shown once each time the box starts";
            case "start_actions": return "What happens after the startup message";
            case "split": return "Two apps side by side with one tap";
            case "vehicle": return "Battery size, the level to keep above, and ranges";
            case "energy": return "Drives, charges, cost and battery use";
            case "car_source": return "Where battery and speed come from: the car or manual entry";
            case "driving_mode": return "Bigger buttons and no typing at speed";
            case "speed_limit": return "Road limit reminder and over-speed chime";
            case "max_speed": return "The top of the speed scale bar";
            case "nav_app": return "The maps app that Where to? and turn-by-turn use";
            case "trips": return "Logged drives, weekly totals and where you parked";
            case "service": return "Reminders to service by date or distance";
            case "equalizer": return "31-band curve, exciter and loudness matching for music";
            case "crossfade": return "Blend the end of one song into the next";
            case "album_art": return "Look up missing covers online, once per album";
            case "music": return "Folders, playlists and favourites";
            case "backup": return "Save or restore looks, dock, places and equalizer";
            case "media_access": return "Needed for Now playing and turn-by-turn on Home";
            case "overlay": return "Needed for the Home button and the navigation display";
            case "split_assistant": return "Makes Back work in every app, and runs split screen";
            case "car_data": return "What the box receives from the car";
            case "android_settings": return "The system settings of the box";
            case "performance": return "CPU, temperature and device information";
            case "about": return "Version and build of this launcher";
            default: return "";
        }
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException unknown) { return ""; }
    }

    String settingValue(String id) {
        switch (id) {
            case "appearance": return themeModeLabel(preferences.themeMode(), this);
            case "weather": return preferences.weatherEnabled() ? "On" : "Off";
            case "start_actions": {
                boolean music = preferences.resumeMusicOnStart(), nav = preferences.openNavigationOnStart();
                return music && nav ? "Resume music · open navigation" : music ? "Resume music" : nav ? "Open navigation" : "Nothing";
            }
            case "speed_limit": {
                String mode = preferences.speedLimitMode();
                return "off".equals(mode) ? "Off" : "manual".equals(mode) ? "Fixed reminder · " + preferences.manualSpeedLimit() + " km/h"
                        : "Automatic from the road" + (preferences.speedChime() ? " · chime" : "");
            }
            case "service": return new ServiceReminders(this).summary();
            case "album_art": return MusicArt.lookupEnabled(this) ? "On" : "Off";
            case "backup": return "";
            case "accent": return LauncherTheme.ACCENT_NAMES[preferences.accentIndex()];
            case "wallpaper": return preferences.wallpaperUri() != null && !preferences.sampleWallpaper() ? "Your photo" : "Built-in";
            case "background": return backgroundTreatmentLabel();
            case "experience": return preferences.visualEffects() ? "Smooth" : "Performance";
            case "home_role": return isDefaultHome() ? "Active" : "Tap to set up";
            case "home_tab": return capitalize(preferences.homeTabSide()) + (SplitScreenService.isConnected(this) ? "" : "off".equals(preferences.homeTabSide()) ? "" : " · turn on the assistant for Back");
            case "screensaver": return screensaverLabel(preferences.screensaverSeconds());
            case "screensaver_image": {
                String image = preferences.screensaverImage();
                return image == null ? "Plain dark" : "wallpaper".equals(image) ? "Your wallpaper" : "Your photo";
            }
            case "car_data": return "";
            case "startup": return preferences.startupNoticeEnabled() ? preferences.startupTitle() : "Off";
            case "split": return splitPairSummary();
            case "driving_mode": return preferences.drivingModeKmh() == 0 ? "Off" : "Above " + preferences.drivingModeKmh() + " km/h";
            case "max_speed": return "Up to " + preferences.vehicleMaximumSpeed() + " km/h";
            case "nav_app": {
                ComponentName map = preferences.focus();
                AppEntry app = map == null ? null : appRepository.find(apps, map);
                return app == null ? "Choose" : app.label;
            }
            case "vehicle": {
                EnergyModel.Profile profile = EnergyJournal.get(this).store().profile();
                return profile.benchmarkLabel() + " · " + (profile.hasCapacity() ? EnergyModel.percent(profile.usableKwh) + " kWh usable" : "capacity not set");
            }
            case "energy": return "";
            case "car_source": {
                CarLinkController link = EnergyClient.link(this);
                String driveTheme = link.matchDriveMode() ? " · drive-mode colour" : "";
                if (!link.paired()) return "Manual entry · tap to pair with the car";
                if (CarLinkController.MODE_MANUAL.equals(link.mode())) return "Manual only · car paired but off";
                return (CarLinkController.MODE_CAR.equals(link.mode()) ? "Car only · " : "Automatic · ") + link.state() + driveTheme;
            }
            case "trips": return "";
            case "crossfade": return CrossfadeDialog.summary(this);
            case "equalizer": { EqualizerProfile eq = new EqualizerProfile(this); return eq.enabled() ? "On · " + eq.preset() : "Off"; }
            case "music": return "";
            case "media_access": return hasNotificationAccess() ? "On" : "Off";
            case "overlay": return Settings.canDrawOverlays(this) ? "On" : "Off";
            case "split_assistant": return SplitScreenService.isConnected(MainActivity.this) ? "On" : "Off";
            case "android_settings": return "";
            case "performance": return "";
            case "about": return "Version " + versionName();
            default: return "";
        }
    }

    void settingAction(String id, SettingsPanel panel) {
        switch (id) {
            case "appearance": showAppearancePicker(); break;
            case "weather":
                if (preferences.weatherEnabled()) { preferences.setWeatherEnabled(false); renderedCalendarKey = null; renderWeatherCard(); panel.refresh(); }
                else askWeather();
                break;
            case "start_actions": {
                String[] rows = {(preferences.resumeMusicOnStart() ? "☑ " : "☐ ") + "Resume OABL Music",
                        (preferences.openNavigationOnStart() ? "☑ " : "☐ ") + "Open the navigation app"};
                dialogBuilder().setTitle("When the box starts").setItems(rows, (d, i) -> {
                    if (i == 0) preferences.setResumeMusicOnStart(!preferences.resumeMusicOnStart());
                    else preferences.setOpenNavigationOnStart(!preferences.openNavigationOnStart());
                    panel.refresh();
                    settingAction(id, panel);
                }).setNegativeButton("Close", null).show();
                break;
            }
            case "speed_limit": showSpeedLimitSettings(panel); break;
            case "service": ServiceReminders.manage(this, palette, panel::refresh); break;
            case "album_art":
                getSharedPreferences("music", 0).edit().putBoolean("online_art", !MusicArt.lookupEnabled(this)).apply();
                panel.refresh();
                break;
            case "backup":
                dialogBuilder().setTitle("Back up & restore settings")
                        .setMessage("Saves look, dock, places, equalizer curves, music options, trips and reminders to one file. "
                                + "Restore it after a firmware reset. Photos are not inside the file; choose them again if needed.")
                        .setPositiveButton("Back up…", (d, w) -> {
                            Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                    .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "oabl-settings-"
                                            + new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date()) + ".zip");
                            try { startActivityForResult(save, REQUEST_BACKUP); } catch (RuntimeException none) { Toast.makeText(this, "No file picker on this box", Toast.LENGTH_LONG).show(); }
                        })
                        .setNeutralButton("Restore…", (d, w) -> {
                            Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                            try { startActivityForResult(open, REQUEST_RESTORE); } catch (RuntimeException none) { Toast.makeText(this, "No file picker on this box", Toast.LENGTH_LONG).show(); }
                        })
                        .setNegativeButton("Cancel", null).show();
                break;
            case "accent": showAccentPicker(); break;
            case "wallpaper":
                dialogBuilder().setTitle("Wallpaper").setItems(new String[]{"Choose a photo…", "Use the built-in wallpaper"}, (d, i) -> {
                    if (i == 0) chooseWallpaper();
                    else { preferences.setWallpaperUri(null); preferences.setSampleWallpaper(false); loadWallpaper(); panel.refresh(); }
                }).show();
                break;
            case "background": showBackgroundTreatmentPicker(); break;
            case "experience": togglePerformanceFromControls(); panel.refresh(); break;
            case "home_role": configureHomeRole(); break;
            case "home_tab": {
                String[] sides = {"right", "left", "off"};
                dialogBuilder().setTitle("Back & Home over other apps")
                        .setSingleChoiceItems(new String[]{"Right edge", "Left edge", "Off"},
                                java.util.Arrays.asList(sides).indexOf(preferences.homeTabSide()), (d, i) -> {
                                    preferences.setHomeTabSide(sides[i]); d.dismiss(); panel.refresh();
                                    if ("off".equals(sides[i])) { SplitScreenService.hideAppControls(this); return; }
                                    if (!SplitScreenService.isConnected(this)) {
                                        // Back needs the assistant (Android only lets an accessibility service press Back for us).
                                        Toast.makeText(this, "Turn on \u201c" + getString(R.string.split_service_name) + "\u201d so Back works in every app", Toast.LENGTH_LONG).show();
                                        openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                                    } else if (!Settings.canDrawOverlays(this)) {
                                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                                    }
                                }).show();
                break;
            }
            case "screensaver": {
                int[] values = {0, 20, 60, 120, 300};
                String[] names = new String[values.length];
                int current = 1;
                for (int i = 0; i < values.length; i++) {
                    names[i] = screensaverLabel(values[i]);
                    if (values[i] == preferences.screensaverSeconds()) current = i;
                }
                dialogBuilder().setTitle("Screensaver when parked")
                        .setSingleChoiceItems(names, current, (d, i) -> { preferences.setScreensaverSeconds(values[i]); d.dismiss(); panel.refresh(); }).show();
                break;
            }
            case "startup": editStartupNotice(panel); break;
            case "screensaver_image":
                dialogBuilder().setTitle("Screensaver background")
                        .setItems(new String[]{"Choose a photo…", "Use my wallpaper", "Plain dark"}, (d, i) -> {
                            if (i == 0) chooseImage(REQUEST_SCREENSAVER_IMAGE);
                            else preferences.setScreensaverImage(i == 1 ? "wallpaper" : null);
                            panel.refresh();
                        }).show();
                break;
            case "car_data": showVehicleData(); break;
            case "split": chooseSplitPair(); break;
            case "driving_mode": {
                int[] values = {0, 10, 15, 20, 30};
                String[] names = {"Off", "Above 10 km/h", "Above 15 km/h", "Above 20 km/h", "Above 30 km/h"};
                int current = java.util.Arrays.binarySearch(values, preferences.drivingModeKmh());
                dialogBuilder().setTitle("Driving mode · large controls, no typing")
                        .setSingleChoiceItems(names, Math.max(0, current), (d, i) -> { preferences.setDrivingModeKmh(values[i]); d.dismiss(); panel.refresh(); }).show();
                break;
            }
            case "max_speed": showMaximumSpeedPicker(); break;
            case "nav_app": chooseFocusApp(); break;
            case "vehicle": EnergyDialogs.vehicle(this, palette, () -> { panel.refresh(); renderedEnergyKey = null; renderEnergyCard(); applyCompactLayoutIfNeeded(); }); break;
            case "energy": openTool(EnergyActivity.class); break;
            case "car_source": CarLinkDialogs.dataSource(this, palette, () -> { panel.refresh(); renderedEnergyKey = null; renderEnergyCard(); }); break;
            case "trips": openTool(TripHistoryActivity.class); break;
            case "crossfade": CrossfadeDialog.show(this, palette, panel::refresh); break;
            case "equalizer": openTool(EqualizerActivity.class); break;
            case "music": openTool(MusicActivity.class); break;
            case "media_access": openSystemSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS); break;
            case "overlay": startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); break;
            case "split_assistant": openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS); break;
            case "android_settings": openSystemSettings(Settings.ACTION_SETTINGS); break;
            case "performance": showPerformanceSettings(); break;
            case "about": showAbout(); break;
            default: break;
        }
    }

    /** Read-only report of what the box firmware exposes from the car. */
    private void showVehicleData() {
        final String report = VehicleDataProbe.report(this);
        TextView text = new TextView(this);
        text.setText(report);
        text.setTextSize(13);
        text.setTextColor(palette.ink);
        text.setTextIsSelectable(true);
        text.setPadding(dp(24), dp(8), dp(24), dp(8));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(text);
        dialogBuilder().setTitle("Car data (CarPlay)").setView(scroll)
                .setNeutralButton("Copy report", (d, w) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OABL car data", report));
                    Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("Close", null).show();
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String screensaverLabel(int seconds) {
        if (seconds <= 0) return "Off";
        return seconds < 60 ? "After " + seconds + " seconds" : "After " + (seconds / 60) + " minute" + (seconds >= 120 ? "s" : "");
    }

    private void editStartupNotice(SettingsPanel panel) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), 0);
        android.widget.EditText title = new android.widget.EditText(this);
        title.setSingleLine(true);
        title.setHint("Title");
        title.setText(preferences.startupTitle());
        android.widget.EditText message = new android.widget.EditText(this);
        message.setHint("Message");
        message.setMinLines(2);
        message.setText(preferences.startupMessage());
        android.widget.Switch enabled = new android.widget.Switch(this);
        enabled.setText("Show at every startup until acknowledged  ");
        enabled.setChecked(preferences.startupNoticeEnabled());
        form.addView(enabled);
        form.addView(title);
        form.addView(message);
        dialogBuilder().setTitle("Startup message").setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    preferences.setStartupNoticeEnabled(enabled.isChecked());
                    String t = title.getText().toString().trim();
                    String m = message.getText().toString().trim();
                    if (!t.isEmpty() && !m.isEmpty()) preferences.setStartupNotice(t, m);
                    panel.refresh();
                }).show();
    }

    private void offerHomeRoleOnFirstRun() {
        if (isDefaultHome() || preferences.homeRolePrompted() || isFinishing()) {
            return;
        }
        preferences.setHomeRolePrompted();
        dialogBuilder()
                .setTitle("Make OABL your Home app")
                .setMessage("The box will open this dashboard at startup and whenever Home is pressed. Android asks you to confirm.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Set as default", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        requestHomeRole();
                    }
                })
                .show();
    }

    private boolean isDefaultHome() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roles = getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return roles.isRoleHeld(RoleManager.ROLE_HOME);
            }
        }
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo resolved = getPackageManager().resolveActivity(
                home, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && resolved.activityInfo != null
                && getPackageName().equals(resolved.activityInfo.packageName);
    }

    private void configureHomeRole() {
        if (isDefaultHome()) {
            openHomeSettings();
        } else {
            requestHomeRole();
        }
    }

    private void requestHomeRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roles = getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)
                    && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME),
                        REQUEST_HOME_ROLE);
                return;
            }
        }
        openHomeSettings();
    }

    private void openHomeSettings() {
        try {
            SplitScreenService.showAppControls(MainActivity.this);
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            playLaunchTransition();
        } catch (ActivityNotFoundException error) {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                SplitScreenService.showAppControls(MainActivity.this);
                startActivity(home);
            } catch (ActivityNotFoundException unavailable) {
                SplitScreenService.hideAppControls(MainActivity.this);
                Toast.makeText(this, "Default Home settings are unavailable on this device",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showAppearancePicker() {
        final String[] choices = {"Auto · follows sunrise and sunset", "Dark", "Light"};
        dialogBuilder()
                .setTitle("Appearance")
                .setSingleChoiceItems(choices, preferences.themeMode(),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                preferences.setThemeMode(which);
                                dialog.dismiss();
                                applyAppearance(preferences.visualEffects());
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAccentPicker() {
        android.widget.BaseAdapter choices = new android.widget.BaseAdapter() {
            @Override public int getCount() { return LauncherTheme.ACCENT_NAMES.length; }
            @Override public Object getItem(int index) { return LauncherTheme.ACCENT_NAMES[index]; }
            @Override public long getItemId(int index) { return index; }
            @Override public View getView(int index, View recycled, ViewGroup parent) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(20), dp(12), dp(20), dp(12));
                row.setMinimumHeight(dp(64));
                View swatch = new View(MainActivity.this);
                swatch.setBackground(LauncherTheme.metallicFinish(index));
                row.addView(swatch, new LinearLayout.LayoutParams(dp(44), dp(36)));
                TextView label = new TextView(MainActivity.this);
                label.setText(LauncherTheme.ACCENT_NAMES[index]); label.setTextColor(palette.ink); label.setTextSize(17);
                label.setPadding(dp(16), 0, dp(8), 0);
                row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
                TextView selected = new TextView(MainActivity.this);
                selected.setText(index == preferences.accentIndex() ? "✓" : "");
                selected.setTextColor(palette.accent); selected.setTextSize(22);
                row.addView(selected, new LinearLayout.LayoutParams(dp(32), -2));
                row.setContentDescription(LauncherTheme.ACCENT_NAMES[index] + (index == preferences.accentIndex() ? ", selected" : ""));
                return row;
            }
        };
        dialogBuilder().setTitle("Metallic car-paint finish")
                .setSingleChoiceItems(choices, preferences.accentIndex(), (dialog, which) -> {
                    preferences.setAccentIndex(which); dialog.dismiss();
                    applyAppearance(preferences.visualEffects());
                }).setNegativeButton("Cancel", null).show();
    }

    private void showMaximumSpeedPicker() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setTag("vehicle_max_speed_input");
        input.setContentDescription("Vehicle maximum speed in kilometers per hour");
        input.setText(String.valueOf(preferences.vehicleMaximumSpeed()));
        input.selectAll();
        AlertDialog dialog = dialogBuilder().setTitle("Speedometer scale · km/h")
                .setMessage("The full width of the speed bar, from 40 to 400 km/h. The default is 140. It does not limit your speed.")
                .setView(input).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        dialog.show();
        ThemedDialog.match(dialog, palette);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int value = Integer.parseInt(input.getText().toString().trim());
                if (value < 40 || value > 400) throw new NumberFormatException();
                preferences.setVehicleMaximumSpeed(value); renderDashboardData(); dialog.dismiss();
            } catch (NumberFormatException invalid) {
                input.setError("Enter a whole number from 40 to 400");
            }
        });
    }

    private void showPerformanceSettings() {
        showPerformanceSettings(RootAccess::run);
    }
    private AlertDialog showPerformanceSettings(RootAccess.Runner rootRunner) {
        float rate = preferences.displayRefreshPreference();
        AlertDialog.Builder performanceBuilder = dialogBuilder();
        final int menuInk = palette.ink; // Keep rows matched to this dialog even if Auto changes behind it.
        android.widget.ArrayAdapter<String> options = new android.widget.ArrayAdapter<String>(performanceBuilder.getContext(),
                android.R.layout.simple_list_item_1, new ArrayList<>(java.util.Arrays.asList(
                        "Launcher animations · " + (preferences.visualEffects() ? "Visuals" : "Performance"),
                        "Display preference · " + (rate == 0 ? "System" : String.format(java.util.Locale.US, "%.1f Hz", rate)),
                        "CPU governors & thermal diagnostics · read-only",
                        "Checking root access…",
                        "Wireless debugging recovery · " + (WirelessDebugKeeper.get(this).enabled() ? "On" : "Off")))) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView row = (TextView)super.getView(position, recycled, parent);
                row.setTextColor(menuInk);
                row.setMinHeight(dp(48));
                return row;
            }
        };
        final boolean[] rootGranted = {false};
        AlertDialog performance = performanceBuilder.setTitle("Performance & device")
                .setAdapter(options, (dialog, which) -> {
                    if (which == 0) {
                        preferences.setVisualEffects(!preferences.visualEffectsChoice());
                        if (!preferences.visualEffects()) cancelAnimations(findViewById(R.id.root));
                        showPerformanceSettings();
                    } else if (which == 1) showDisplayRefreshPicker();
                    else if (which == 4) showWirelessDebugSettings();
                    else if (which == 3) {
                        if (rootGranted[0]) showCoreSettings();
                        else dialogBuilder().setTitle("Root access not verified")
                                .setMessage("Core settings require superuser permission for OABL, not just a rooted device. Allow OABL in your root manager, then reopen Performance settings to retry. The check has a 15-second timeout.")
                                .setPositiveButton("Done", null).show();
                    } else {
                        AlertDialog information = dialogBuilder().setTitle("Device performance")
                                .setMessage("Reading Android-reported device data…")
                                .setPositiveButton("Done", null).show();
                        new Thread(() -> {
                            String details;
                            try { details = DevicePerformance.diagnostics(getApplicationContext()); }
                            catch (RuntimeException error) { details = "Device diagnostics unavailable. CPU changes need a verified root/vendor interface."; }
                            final String report = details;
                            handler.post(() -> { if (information.isShowing() && !isFinishing()) information.setMessage(report); });
                        }, "oabl-device-diagnostics").start();
                    }
                }).setNegativeButton("Close", null).show();
        new Thread(() -> {
            boolean granted = RootAccess.check(rootRunner);
            handler.post(() -> {
                if (!performance.isShowing() || ActivityState.isGone(this)) return;
                rootGranted[0] = granted;
                options.remove(options.getItem(3));
                options.insert(granted ? "Core settings · root access verified" : "Root access unavailable · Core settings hidden", 3);
            });
        }, "oabl-root-check").start();
        return performance;
    }

    private void showWirelessDebugSettings() {
        WirelessDebugKeeper keeper = WirelessDebugKeeper.get(this);
        dialogBuilder().setTitle("Wireless debugging recovery")
                .setMessage(keeper.status() + "\n\nWhen enabled, OABL restores wireless debugging once Wi-Fi connects, starting when the Home app opens. Requires an existing trusted Wi-Fi network and a one-time ADB permission grant.\n\nTurn recovery off here before manually disabling wireless debugging. Turning recovery off leaves the current debugging session unchanged.")
                .setPositiveButton(keeper.enabled() ? "Turn recovery off" : "Turn recovery on", (dialog, which) -> {
                    if (!keeper.enabled() && !keeper.permitted()) {
                        dialogBuilder().setTitle("One-time ADB setup")
                                .setMessage("Run on your connected computer:\n\nadb -s AI_BOX_SERIAL shell pm grant com.softiva.oabl android.permission.WRITE_SECURE_SETTINGS\n\nThen turn recovery on here.")
                                .setPositiveButton("Done", null).show();
                    } else keeper.setEnabled(!keeper.enabled());
                }).setNegativeButton("Close", null).show();
    }

    private void showCoreSettings() {
        dialogBuilder().setTitle("Core settings · superuser")
                .setItems(new String[]{"System animation speed", "CPU & thermal controls", "CPU policies · root diagnostics", "About root controls"}, (dialog, which) -> {
                    if (which == 0) {
                        runCoreOperation("Current system animations", () -> CoreSettings.readAnimations(RootAccess::run), true);
                    } else if (which == 1) {
                        openTool(CoreTuningActivity.class);
                    } else if (which == 2) {
                        runCoreOperation("CPU policies · read-only", () -> CoreSettings.cpuReport(RootAccess::run), false);
                    } else dialogBuilder().setTitle("Core settings")
                            .setMessage("Superuser access is checked using the actual UID returned by su, not build tags or installed apps. Permission is rechecked before changes.\n\nCPU/thermal controls offer supported governors, frequency limits and conservative passive cooling trips. Originals are saved for restore. Critical/shutdown protection and voltage/overclock controls are locked. CPU/thermal changes are not automatically reapplied at boot. Animation settings affect transitions, not video frame rate.")
                            .setPositiveButton("Done", null).show();
                }).setNegativeButton("Close", null).show();
    }

    private void runCoreOperation(String title, java.util.concurrent.Callable<String> operation, boolean offerAnimations) {
        AlertDialog progress = dialogBuilder().setTitle(title).setMessage("Checking superuser access…")
                .setPositiveButton("Done", null).create();
        if (offerAnimations) progress.setButton(AlertDialog.BUTTON_NEUTRAL, "Change speed", (dialog, which) -> showSystemAnimationPicker());
        progress.show();
        if (offerAnimations) progress.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
        new Thread(() -> {
            String output; boolean access = RootAccess.check();
            try { output = access ? operation.call() : "Root permission unavailable. No settings changed."; }
            catch (Exception error) { output = "Core operation unavailable. Recheck root permission."; }
            final String report = output;
            handler.post(() -> {
                if (!progress.isShowing() || ActivityState.isGone(this)) return;
                progress.setMessage(report);
                if (offerAnimations) progress.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(access);
            });
        }, "oabl-core-operation").start();
    }

    private void showSystemAnimationPicker() {
        dialogBuilder().setTitle("Android-wide animation speed")
                .setItems(new String[]{"Off · no system animation", "0.25× · very fast", "0.5× · fast", "1× · normal"}, (dialog, which) ->
                        dialogBuilder().setTitle("Apply system animation speed?")
                                .setMessage("Sets window, transition and animator duration scales to " + CoreSettings.ANIMATION_VALUES[which]
                                        + "× across Android. This does not change video FPS. You can select 1× to return to normal speed; it does not restore any previous custom values.")
                                .setPositiveButton("Apply", (confirmation, button) -> runCoreOperation("System animation speed",
                                        () -> CoreSettings.setAnimations(which, RootAccess::run), false))
                                .setNegativeButton("Cancel", null).show())
                .setNegativeButton("Cancel", null).show();
    }
    private void showDisplayRefreshPicker() {
        final ArrayList<Float> rates = DevicePerformance.refreshChoices(this);
        String[] labels = new String[rates.size()]; int selected = 0;
        for (int i = 0; i < rates.size(); i++) {
            labels[i] = rates.get(i) == 0 ? "System default" : String.format(java.util.Locale.US, "%.1f Hz · supported display mode", rates.get(i));
            if (Math.abs(rates.get(i)-preferences.displayRefreshPreference()) < .2f) selected = i;
        }
        dialogBuilder().setTitle("Launcher / EQ display hint · not video FPS")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    preferences.setDisplayRefreshPreference(rates.get(which));
                    DevicePerformance.apply(this, getWindow()); dialog.dismiss();
                    Toast.makeText(this, "Display preference saved; Android decides the actual rate", Toast.LENGTH_LONG).show();
                }).setNegativeButton("Cancel", null).show();
    }

    private static String themeModeLabel(int mode, Context context) {
        if (mode == LauncherPreferences.THEME_DARK) {
            return "Dark";
        }
        if (mode == LauncherPreferences.THEME_LIGHT) {
            return "Light";
        }
        // Auto follows the sun where the car is; the fixed schedule is only a fallback without GPS.
        SunTimes sun = SunTimes.today(context, System.currentTimeMillis());
        if (sun == null) return "Auto · sunrise and sunset (after the first GPS fix)";
        java.text.DateFormat clock = android.text.format.DateFormat.getTimeFormat(context);
        return "Auto · light from " + clock.format(new java.util.Date(sun.sunrise)) + ", dark from " + clock.format(new java.util.Date(sun.sunset));
    }

    private void showAbout() {
        dialogBuilder()
                .setTitle("Open AI Box Launcher " + versionName())
                .setMessage("A driving dashboard for landscape Android AI boxes.\n\n"
                        + "Home shows the car's battery and range, your speed, navigation, weather and music. "
                        + "Hold a dock app to replace it, or the map app on the navigation card to change it.\n\n"
                        + "Visuals adds motion; Performance removes it. Auto appearance follows sunrise and sunset.")
                .setPositiveButton("Done", null)
                .show();
    }

    /** All apps as a sheet: search, recently used, A–Z jump and long-press to pin to the dock. */
    private void showAppDrawer() {
        final Dialog dialog = new Dialog(this, R.style.LauncherDialog);
        MapsTile.hideWhileOpen(dialog);
        DrivingDismiss.track(dialog);
        final java.util.Set<String> hidden = preferences.hiddenApps();
        final List<AppEntry> visible = new ArrayList<>();
        final List<AppEntry> shortcuts = new ArrayList<>();
        final boolean[] shortcutTab = {false};
        for (AppEntry app : apps) if (!hidden.contains(app.component.flattenToString())) visible.add(app);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(14), dp(16), dp(14));
        root.setBackground(LauncherTheme.drawerBackground(palette));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("All apps");
        title.setTextColor(palette.ink);
        title.setTextSize(26);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));
        final android.widget.EditText search = new android.widget.EditText(this);
        search.setHint("Search apps");
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setTextColor(palette.ink);
        search.setHintTextColor(palette.muted);
        search.setPadding(dp(18), 0, dp(18), 0);
        search.setBackground(LauncherTheme.rounded(this, palette.light ? 0x10142e38 : 0x14ffffff, 22, 0, 0));
        search.setVisibility(drivingMode ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams searchSize = new LinearLayout.LayoutParams(0, dp(48), 1f);
        searchSize.setMargins(dp(24), 0, dp(16), 0);
        header.addView(search, searchSize);
        if (drivingMode) header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView close = new TextView(this);
        close.setText("Close");
        close.setTextSize(16);
        close.setTextColor(palette.accent);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(16), 0, dp(12), 0);
        if (!hidden.isEmpty()) {
            TextView hiddenButton = new TextView(this);
            hiddenButton.setText("Hidden (" + hidden.size() + ")");
            hiddenButton.setTextSize(15);
            hiddenButton.setTextColor(palette.muted);
            hiddenButton.setGravity(Gravity.CENTER);
            hiddenButton.setPadding(dp(12), 0, dp(12), 0);
            hiddenButton.setOnClickListener(v -> showHiddenApps(dialog));
            header.addView(hiddenButton, header.getChildCount() - 1,
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));
        }
        header.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));
        root.addView(header);

        final LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(0, dp(2), 0, dp(4));
        final TextView appsTab = drawerTab("Apps", true);
        final TextView shortcutsTab = drawerTab("Shortcuts", false);
        tabs.addView(appsTab, new LinearLayout.LayoutParams(dp(132), dp(42)));
        tabs.addView(shortcutsTab, new LinearLayout.LayoutParams(dp(132), dp(42)));
        tabs.setVisibility(View.GONE);
        root.addView(tabs);

        // Recently used, from the dock's launch history.
        List<AppEntry> recent = new ArrayList<>();
        for (String flattened : preferences.dockRecents()) {
            AppEntry app = appRepository.find(apps, ComponentName.unflattenFromString(flattened));
            if (app != null && !MainActivity.class.getName().equals(app.component.getClassName())) recent.add(app);
            if (recent.size() == 6) break;
        }
        final LinearLayout recentRow = new LinearLayout(this);
        recentRow.setGravity(Gravity.CENTER_VERTICAL);
        if (!recent.isEmpty()) {
            TextView label = new TextView(this);
            label.setText("RECENT");
            label.setTextSize(10);
            label.setLetterSpacing(.14f);
            label.setTextColor(palette.muted);
            recentRow.addView(label, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));
            for (final AppEntry app : recent) {
                LinearLayout chip = new LinearLayout(this);
                chip.setGravity(Gravity.CENTER_VERTICAL);
                chip.setPadding(dp(8), 0, dp(14), 0);
                chip.setBackground(LauncherTheme.rounded(this, palette.light ? 0xffffffff : 0x14ffffff, 22, 0, 0));
                ImageView icon = new ImageView(this);
                icon.setImageDrawable(app.icon);
                chip.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
                TextView name = new TextView(this);
                name.setText(app.label);
                name.setTextSize(14);
                name.setTextColor(palette.ink);
                name.setPadding(dp(8), 0, 0, 0);
                chip.addView(name);
                chip.setOnClickListener(v -> { dialog.dismiss(); launch(app.component, icon); });
                LinearLayout.LayoutParams chipSize = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
                chipSize.rightMargin = dp(8);
                recentRow.addView(chip, chipSize);
            }
            LinearLayout.LayoutParams recentSize = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            recentSize.topMargin = dp(6);
            root.addView(recentRow, recentSize);
        }

        LinearLayout body = new LinearLayout(this);
        final List<AppEntry> shown = new ArrayList<>(visible);
        final AppGridAdapter adapter = new AppGridAdapter(shown);
        final GridView grid = new GridView(this);
        final int columns = Math.max(4, Math.min(8, getResources().getDisplayMetrics().widthPixels / dp(160)));
        grid.setNumColumns(columns);
        grid.setHorizontalSpacing(dp(10));
        grid.setVerticalSpacing(dp(10));
        grid.setPadding(0, dp(10), 0, dp(8));
        grid.setClipToPadding(false);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalScrollBarEnabled(false);
        grid.setAdapter(adapter);
        body.addView(grid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        // A–Z index: tap a letter to jump to the first app starting with it.
        LinearLayout index = new LinearLayout(this);
        index.setOrientation(LinearLayout.VERTICAL);
        index.setGravity(Gravity.CENTER);
        java.util.LinkedHashSet<Character> letters = new java.util.LinkedHashSet<>();
        for (AppEntry app : visible) if (!app.label.isEmpty()) letters.add(Character.toUpperCase(app.label.charAt(0)));
        for (final char letter : letters) {
            TextView key = new TextView(this);
            key.setText(String.valueOf(letter));
            key.setTextSize(13);
            key.setGravity(Gravity.CENTER);
            key.setTextColor(palette.accent);
            key.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            key.setOnClickListener(v -> {
                for (int i = 0; i < shown.size(); i++) {
                    if (Character.toUpperCase(shown.get(i).label.charAt(0)) == letter) { grid.setSelection(i); break; }
                }
            });
            index.addView(key, new LinearLayout.LayoutParams(dp(36), 0, 1f));
        }
        body.addView(index, new LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                String term = s.toString().trim().toLowerCase(Locale.getDefault());
                fillDrawerEntries(shown, shortcutTab[0] ? shortcuts : visible, term);
                adapter.notifyDataSetChanged();
                recentRow.setVisibility(!shortcutTab[0] && term.isEmpty() ? View.VISIBLE : View.GONE);
                index.setVisibility(!shortcutTab[0] && term.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override public void afterTextChanged(android.text.Editable e) { }
        });

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
            ImmersiveWindow.apply(window, root);
        }
        close.setOnClickListener(v -> {
            if (!preferences.visualEffects()) { dialog.dismiss(); return; }
            root.animate().translationY(root.getHeight() * 0.6f).alpha(0f).setDuration(220L)
                    .setInterpolator(new DecelerateInterpolator()).withEndAction(dialog::dismiss).start();
        });
        grid.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry app = shown.get(position);
            dialog.dismiss();
            if (app.isShortcut()) {
                if (!appRepository.launchShortcut(app)) {
                    Toast.makeText(this, "That shortcut is no longer available", Toast.LENGTH_SHORT).show();
                    reloadApps();
                }
            } else launch(app.component, view);
        });
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            AppEntry app = shown.get(position);
            if (app.isShortcut()) {
                dialogBuilder().setTitle(app.label).setItems(new String[]{"App info"},
                        (d, which) -> openAppDetails(app.component.getPackageName())).show();
                return true;
            }
            String[] options = {"Pin to dock · slot 1", "Pin to dock · slot 2", "Pin to dock · slot 3", "Pin to dock · slot 4", "Hide from Apps", "App info"};
            dialogBuilder().setTitle(app.label).setItems(options, (d, which) -> {
                if (which < 4) {
                    preferences.setShortcut(which, app.component);
                    renderDock();
                    Toast.makeText(this, app.label + " pinned to the dock", Toast.LENGTH_SHORT).show();
                } else if (which == 4) {
                    preferences.setHidden(app.component.flattenToString(), true);
                    dialog.dismiss();
                    Toast.makeText(this, app.label + " hidden · find it under Hidden in Apps", Toast.LENGTH_LONG).show();
                    showAppDrawer();
                } else {
                    dialog.dismiss();
                    openAppDetails(app.component.getPackageName());
                }
            }).show();
            return true;
        });
        View.OnClickListener chooseTab = selected -> {
            shortcutTab[0] = selected == shortcutsTab;
            drawerTabSelected(appsTab, !shortcutTab[0]);
            drawerTabSelected(shortcutsTab, shortcutTab[0]);
            search.setHint(shortcutTab[0] ? "Search shortcuts" : "Search apps");
            String term = search.getText().toString().trim().toLowerCase(Locale.getDefault());
            fillDrawerEntries(shown, shortcutTab[0] ? shortcuts : visible, term);
            adapter.notifyDataSetChanged();
            recentRow.setVisibility(!shortcutTab[0] && term.isEmpty() ? View.VISIBLE : View.GONE);
            index.setVisibility(!shortcutTab[0] && term.isEmpty() ? View.VISIBLE : View.GONE);
            grid.setSelection(0);
        };
        appsTab.setOnClickListener(chooseTab);
        shortcutsTab.setOnClickListener(chooseTab);
        dialog.show();
        appLoader.execute(() -> {
            List<AppEntry> loaded = appRepository.loadShortcuts(visible);
            handler.post(() -> {
                if (!dialog.isShowing() || ActivityState.isGone(this)) return;
                shortcuts.clear();
                shortcuts.addAll(loaded);
                tabs.setVisibility(shortcuts.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (preferences.visualEffects()) {
                // The drawer rises out of the dock as a sheet.
                root.setTranslationY(getResources().getDisplayMetrics().heightPixels * 0.55f);
                root.setAlpha(0.6f);
                root.animate().translationY(0f).alpha(1f)
                        .setDuration(300L).setInterpolator(new DecelerateInterpolator(1.8f)).start();
            }
        }
    }

    private TextView drawerTab(String label, boolean selected) {
        TextView tab = new TextView(this);
        tab.setText(label);
        tab.setTextSize(14);
        tab.setGravity(Gravity.CENTER);
        drawerTabSelected(tab, selected);
        return tab;
    }

    private void drawerTabSelected(TextView tab, boolean selected) {
        tab.setTextColor(selected ? palette.root : palette.ink);
        tab.setBackground(LauncherTheme.rounded(this,
                selected ? palette.accent : (palette.light ? 0x10142e38 : 0x14ffffff), 20, 0, 0));
    }

    private static void fillDrawerEntries(List<AppEntry> result, List<AppEntry> source, String term) {
        result.clear();
        for (AppEntry entry : source) {
            String owner = entry.appLabel == null ? "" : entry.appLabel;
            if (term.isEmpty() || entry.label.toLowerCase(Locale.getDefault()).contains(term)
                    || owner.toLowerCase(Locale.getDefault()).contains(term)) result.add(entry);
        }
    }

    private void pickApp(String title, final AppChoice callback) {
        if (apps.isEmpty()) {
            Toast.makeText(this, "No launchable apps found", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i).label;
        }
        dialogBuilder()
                .setTitle(title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        callback.selected(apps.get(which));
                    }
                })
                .setNegativeButton("Exit view", null)
                .show();
    }

    private void removeLegacyWidgets() {
        AppWidgetHost legacyHost = null;
        for (int slot = 0; slot < LEGACY_WIDGET_SLOTS; slot++) {
            int widgetId = preferences.widgetId(slot);
            if (widgetId < 0) {
                continue;
            }
            if (legacyHost == null) {
                legacyHost = new AppWidgetHost(this, APP_WIDGET_HOST_ID);
            }
            try {
                legacyHost.deleteAppWidgetId(widgetId);
            } catch (RuntimeException ignored) {
                // The provider may already have disappeared. The old preference is still removed.
            }
            preferences.setWidgetId(slot, -1);
        }
    }

    private void askWeather() {
        dialogBuilder()
                .setTitle("Local weather")
                .setMessage("Show local weather? Your approximate area (about 10 km) is sent to Open-Meteo. Optional, and you can turn it off any time.")
                .setPositiveButton("Show weather", (d, w) -> { preferences.setWeatherEnabled(true); renderWeatherCard(); })
                .setNegativeButton("Turn off", (d, w) -> { preferences.setWeatherEnabled(false); renderWeatherCard(); }).show();
    }

    /** The compact Home tile keeps the hourly forecast and sun times one tap away. */
    private void showWeatherDetails(){
        DashboardData data=DashboardData.get(this);DashboardData.Forecast forecast=data.forecast;
        StringBuilder text=new StringBuilder();
        if(forecast==null)text.append(data.weather);
        else{
            text.append(Math.round(forecast.temperature)).append("° · ").append(DashboardData.weatherLabel(forecast.code));
            text.append("\nHigh ").append(Math.round(forecast.high)).append("° · Low ").append(Math.round(forecast.low)).append("°");
            long minutes=Math.max(0,(System.currentTimeMillis()-forecast.fetchedAt)/60000);
            text.append("\nUpdated ").append(minutes<2?"just now":minutes+" min ago");
            text.append("\n\nHourly forecast");
            for(DashboardData.Hour hour:forecast.hours)text.append("\n").append(hour.label).append(" · ").append(Math.round(hour.temperature)).append("° · ").append(hour.rain).append("% rain · ").append(DashboardData.weatherLabel(hour.code));
        }
        SunTimes times=SunTimes.today(this,System.currentTimeMillis());
        if(times!=null){java.text.DateFormat clock=android.text.format.DateFormat.getTimeFormat(this);text.append("\n\nSunrise ").append(clock.format(new java.util.Date(times.sunrise))).append(" · Sunset ").append(clock.format(new java.util.Date(times.sunset)));}
        dialogBuilder().setTitle("Local weather").setMessage(text.toString()).setPositiveButton("Close",null)
                .setNeutralButton("Refresh",(d,w)->{data.refreshWeather();renderedCalendarKey=null;renderWeatherCard();}).show();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        lastInteraction = android.os.SystemClock.elapsedRealtime();
    }

    /** Starts the screensaver after the configured idle time while parked on Home. */
    private void checkScreensaver() {
        if (screensaver == null) return;
        SpeedGaugeView gauge = findViewById(R.id.speed_cluster);
        boolean parked = gauge.speed() == 0;
        if (screensaver.getVisibility() == View.VISIBLE) {
            if (!parked) { hideScreensaver(); return; }
            DashboardData data = DashboardData.get(this);
            DashboardData.Forecast forecast = preferences.weatherEnabled() ? data.forecast : null;
            ScreensaverView.Info info = new ScreensaverView.Info();
            if (forecast != null) {
                info.weatherCode = forecast.code;
                info.weatherDay = forecast.day;
                info.weather = Math.round(forecast.temperature) + "°  " + DashboardData.weatherLabel(forecast.code);
                String rain = forecast.rainAlert(System.currentTimeMillis());
                info.weatherDetail = rain != null ? "☂ " + rain
                        : "High " + Math.round(forecast.high) + "°  ·  Low " + Math.round(forecast.low) + "°";
            }
            if (activeNavigation.active && lastGuidance != null) {
                info.navTitle = lastGuidance.distance != null
                        ? lastGuidance.distance + "  " + (lastGuidance.instruction == null ? "" : lastGuidance.instruction)
                        : lastGuidance.instruction;
                info.navDetail = lastGuidance.remaining;
                info.navEta = lastGuidance.eta;
            }
            info.location = data.freshFix() ? data.roadLabel() : null;
            double[] stop = TripComputer.get(this).parked();
            if (info.location != null && stop != null && System.currentTimeMillis() - (long) stop[2] < 24 * 3_600_000L
                    && System.currentTimeMillis() - (long) stop[2] > 60_000L) {
                info.location += "  ·  parked since " + android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date((long) stop[2]));
            }
            if (mediaPlaying) {
                info.songTitle = mediaTitle.getText().toString();
                info.songSubtitle = mediaSubtitle.getVisibility() == View.VISIBLE ? mediaSubtitle.getText().toString() : null;
                info.songArt = mediaArt;
            }
            screensaver.update(info);
            return;
        }
        int seconds = preferences.screensaverSeconds();
        long idle = android.os.SystemClock.elapsedRealtime() - lastInteraction;
        if (seconds <= 0 || !foreground || !parked || !hasWindowFocus() || idle < seconds * 1000L
                || startupNotice != null) return;
        String image = preferences.screensaverImage();
        String wallpaperUri = preferences.wallpaperUri();
        screensaver.background(image == null ? null : "wallpaper".equals(image)
                ? (wallpaperUri == null || preferences.sampleWallpaper() ? null : Uri.parse(wallpaperUri)) : Uri.parse(image));
        screensaver.setAlpha(0f);
        screensaver.setVisibility(View.VISIBLE);
        screensaver.update(new ScreensaverView.Info());
        Runnable rest = () -> findViewById(R.id.home_content).setVisibility(View.INVISIBLE);
        if (preferences.visualEffects()) {
            screensaver.animate().alpha(1f).setDuration(500).withEndAction(rest).start();
        } else {
            screensaver.setAlpha(1f);
            rest.run();
        }
        checkScreensaver();
    }

    private void hideScreensaver() {
        lastInteraction = android.os.SystemClock.elapsedRealtime();
        if (screensaver == null || screensaver.getVisibility() != View.VISIBLE) return;
        screensaver.animate().cancel();
        findViewById(R.id.home_content).setVisibility(View.VISIBLE);
        screensaver.setVisibility(View.GONE);
        screensaver.release();
    }

    /** Weather card: now, today's range, sunrise/sunset and the next six hours. */
    private void renderWeatherCard() {
        if (weatherIcon == null || palette == null) {
            return;
        }
        DashboardData data = DashboardData.get(this);
        DashboardData.Forecast forecast = preferences.weatherEnabled() ? data.forecast : null;
        String key = (forecast == null ? data.weather : forecast.fetchedAt + "") + "|" + palette.light + palette.accent
                + "|" + (System.currentTimeMillis() / 60_000) + "|" + (homeLayout != null && (homeLayout.narrow() || homeLayout.shortWindow()));
        if (key.equals(renderedCalendarKey)) {
            return;
        }
        renderedCalendarKey = key;
        TextView temp = findViewById(R.id.weather_temp);
        TextView condition = findViewById(R.id.weather_condition);
        TextView range = findViewById(R.id.weather_range);
        TextView updated = findViewById(R.id.weather_updated);
        TextView sun = findViewById(R.id.weather_sun);
        LinearLayout hours = findViewById(R.id.weather_hours);
        java.text.DateFormat clock = android.text.format.DateFormat.getTimeFormat(this);
        SunTimes times = SunTimes.today(this, System.currentTimeMillis());
        // Split or short window: compact sun times (beside the label) and four hours.
        boolean narrowWeather = homeLayout != null && (homeLayout.narrow() || homeLayout.shortWindow());
        sun.setText(times == null ? (narrowWeather ? "Sun times after GPS" : "Sunrise and sunset after the first GPS fix")
                : narrowWeather ? "↑ " + clock.format(new java.util.Date(times.sunrise)) + "  ↓ " + clock.format(new java.util.Date(times.sunset))
                : "Sunrise " + clock.format(new java.util.Date(times.sunrise)) + "   ·   Sunset " + clock.format(new java.util.Date(times.sunset)));
        sun.setContentDescription(times == null ? null : "Sunrise " + clock.format(new java.util.Date(times.sunrise)) + ", sunset " + clock.format(new java.util.Date(times.sunset)));
        hours.removeAllViews();
        if (forecast == null) {
            temp.setText("--°");
            condition.setText(preferences.weatherEnabled() ? data.weather.replace("Weather · ", "") : "Set up weather");
            range.setText(preferences.weatherEnabled() ? "" : "Approximate area only");
            updated.setText("");
            boolean night = times != null && !times.daylight(System.currentTimeMillis());
            weatherIcon.set(preferences.weatherEnabled() ? 3 : 1, !night, palette.muted);
            return;
        }
        weatherIcon.set(forecast.code, forecast.day, palette.light ? 0xff7d8ea0 : 0xffdfe6ee);
        temp.setText(Math.round(forecast.temperature) + "°");
        String rainSoon = forecast.rainAlert(System.currentTimeMillis());
        if (rainSoon == null) {
            condition.setText(DashboardData.weatherLabel(forecast.code));
        } else {
            // Rain on the way is worth more than "Clear sky" while driving.
            SpannableStringBuilder line = new SpannableStringBuilder("☂ " + rainSoon);
            line.setSpan(new ForegroundColorSpan(palette.accent), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            condition.setText(line);
        }
        range.setText("↑" + Math.round(forecast.high) + "°  ↓" + Math.round(forecast.low) + "°");
        long minutes = (System.currentTimeMillis() - forecast.fetchedAt) / 60_000;
        updated.setText(minutes < 2 ? "Now" : minutes < 60 ? minutes + " min ago" : (minutes / 60) + " h ago");
        if (narrowWeather) updated.setText(minutes < 2 ? "now" : minutes < 60 ? minutes + " min" : (minutes / 60) + " h");
        // Compact hour cells: time (and rain chance) above a small icon beside the temperature.
        int cells = 0;
        for (DashboardData.Hour hour : forecast.hours) {
            if (narrowWeather && ++cells > 4) break;
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER);
            TextView label = new TextView(this);
            SpannableStringBuilder top = new SpannableStringBuilder(hour.label);
            if (hour.rain >= 20) {
                int start = top.length();
                top.append(" · ").append(String.valueOf(hour.rain)).append('%');
                top.setSpan(new ForegroundColorSpan(palette.light ? 0xff1f6fc0 : 0xff6aaef0), start, top.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            label.setText(top);
            label.setTextSize(11);
            label.setMaxLines(1);
            label.setIncludeFontPadding(false);
            label.setTextColor(palette.muted);
            column.addView(label);
            LinearLayout reading = new LinearLayout(this);
            reading.setOrientation(LinearLayout.HORIZONTAL);
            reading.setGravity(Gravity.CENTER_VERTICAL);
            WeatherIconView icon = new WeatherIconView(this);
            icon.set(hour.code, hour.day, palette.light ? 0xff7d8ea0 : 0xffdfe6ee);
            reading.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
            TextView value = new TextView(this);
            value.setText(Math.round(hour.temperature) + "°");
            value.setTextSize(14);
            value.setSingleLine(true);
            value.setIncludeFontPadding(false);
            value.setTextColor(palette.ink);
            value.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gap.setMarginStart(dp(4));
            reading.addView(value, gap);
            LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            below.topMargin = dp(3);
            column.addView(reading, below);
            hours.addView(column, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PLACE) {
            if (resultCode == RESULT_OK && data != null) {
                savePlace(data.getBooleanExtra(PlacePickerActivity.EXTRA_HOME, true),
                        data.getDoubleExtra(PlacePickerActivity.EXTRA_LAT, 0), data.getDoubleExtra(PlacePickerActivity.EXTRA_LON, 0),
                        data.getStringExtra(PlacePickerActivity.EXTRA_LABEL));
            }
            return;
        }
        if (requestCode == REQUEST_HOME_ROLE) {
            Toast.makeText(this,
                    isDefaultHome() ? "OABL is now your default Home app"
                            : "Default launcher was not changed",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if ((requestCode == REQUEST_BACKUP || requestCode == REQUEST_RESTORE) && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri file = data.getData();
            if (requestCode == REQUEST_BACKUP) {
                TripComputer.get(this).save();
                new Thread(()->{
                    try { SettingsBackup.export(this, file);runOnUiThread(()->Toast.makeText(this,"Settings saved",Toast.LENGTH_LONG).show()); }
                    catch(Exception failed){runOnUiThread(()->Toast.makeText(this,"Could not save · "+failed.getMessage(),Toast.LENGTH_LONG).show());}
                },"settings-backup").start();
            } else {
                dialogBuilder().setTitle("Restore these settings?")
                        .setMessage("Current settings are replaced and Home restarts.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Restore", (d, w) -> {
                            new Thread(()->{
                                try{int count=SettingsBackup.restore(this,file);runOnUiThread(()->{Toast.makeText(this,count+" settings restored · restarting",Toast.LENGTH_LONG).show();restartLauncher();});}
                                catch(Exception failed){runOnUiThread(()->Toast.makeText(this,"Could not restore · "+failed.getMessage(),Toast.LENGTH_LONG).show());}
                            },"settings-restore").start();
                        }).show();
            }
            return;
        }
        if (requestCode == REQUEST_SCREENSAVER_IMAGE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // Some vendor pickers grant a durable read without exposing this API.
                }
                preferences.setScreensaverImage(data.getData().toString());
            }
            return;
        }
        if (requestCode == REQUEST_WALLPAPER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // Some vendor file pickers grant a durable read without exposing this API.
                }
                preferences.setWallpaperUri(uri.toString());
                preferences.setSampleWallpaper(false);
                loadWallpaper();
            }
            return;
        }

    }

    private void chooseWallpaper() {
        chooseImage(REQUEST_WALLPAPER);
    }

    private void chooseImage(int request) {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(pick, request);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "No image picker is installed", Toast.LENGTH_LONG).show();
        }
    }

    private void loadWallpaper() {
        updateScrim();
        if (preferences.auroraBackground()) {
            wallpaper.setTag(null);
            wallpaper.setScaleType(ImageView.ScaleType.FIT_XY);
            int modeAccent = LauncherTheme.driveModeAccent();
            wallpaper.setImageDrawable(new AuroraDrawable(palette != null ? palette
                    : LauncherTheme.resolve(this, preferences),
                    modeAccent == DriveMode.COMFORT.accent || modeAccent == DriveMode.SPORT.accent));
            return;
        }
        String saved = preferences.wallpaperUri();
        if (saved == null || preferences.sampleWallpaper()) {
            loadDefaultWallpaper();
            return;
        }
        try {
            wallpaper.setBackgroundColor(palette == null ? 0xff0d1116 : palette.root);
            WallpaperRenderer.load(this, wallpaper, Uri.parse(saved),
                    preferences.blurredBackground(), this::loadDefaultWallpaper);
        } catch (RuntimeException error) {
            preferences.setWallpaperUri(null);
            wallpaper.setImageDrawable(null);
            loadWallpaper();
        }
    }

    private void updateScrim() {
        boolean defaultWallpaper = preferences.wallpaperUri() == null || preferences.sampleWallpaper();
        findViewById(R.id.wallpaper_scrim).setBackground(preferences.auroraBackground() || palette == null
                ? null : LauncherTheme.scrim(palette, defaultWallpaper));
    }

    private String backgroundTreatmentLabel() {
        if (preferences.auroraBackground()) return "Aurora";
        return preferences.blurredBackground() ? "Soft blur" : "Photo";
    }

    private void showBackgroundTreatmentPicker() {
        String[] choices = {"Photo · wallpaper as chosen", "Soft blur · frosted wallpaper",
                "Aurora · generated accent gradient"};
        int current = preferences.auroraBackground() ? 2 : (preferences.blurredBackground() ? 1 : 0);
        dialogBuilder().setTitle("Background treatment")
                .setSingleChoiceItems(choices, current, (dialog, which) -> {
                    preferences.setAuroraBackground(which == 2);
                    if (which != 2) preferences.setBlurredBackground(which == 1);
                    loadWallpaper();
                    dialog.dismiss();
                }).show();
    }

    private void loadDefaultWallpaper() {
        boolean light = palette != null && palette.light;
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaper.setImageMatrix(new android.graphics.Matrix());
        int resource = light ? R.drawable.light_wallpaper : R.drawable.default_wallpaper;
        wallpaper.setImageResource(resource);
        android.graphics.BitmapFactory.Options small = new android.graphics.BitmapFactory.Options();
        small.inSampleSize = 16;
        if (LauncherTheme.rememberWallpaper(this, android.graphics.BitmapFactory.decodeResource(getResources(), resource, small))) {
            handler.post(this::wallpaperColorChanged);
        }
        wallpaper.setTag(null);
        wallpaper.setBackgroundColor(palette == null ? 0xff0d1116 : palette.root);
        if(preferences.blurredBackground()){
            Uri sample=Uri.parse("android.resource://"+getPackageName()+"/"+(light?R.drawable.light_wallpaper:R.drawable.default_wallpaper));
            WallpaperRenderer.load(this,wallpaper,sample,true);
        }
    }

    // Wi-Fi, cellular, Bluetooth, call and battery state take about a dozen system calls to read,
    // so they are read on this worker and the status pill is only rebuilt when something changed.
    private final java.util.concurrent.ExecutorService statusReader = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean statusReading = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile String deviceBattery = "";
    private String renderedStatusKey;
    private static final java.util.regex.Pattern BATTERY_PERCENT = java.util.regex.Pattern.compile("(\\d+)%");

    private void renderTravelStatus() {
        if (travelStatusMonitor == null || networkStatus == null) {
            return;
        }
        if (!statusReading.compareAndSet(false, true)) return;
        TravelStatusMonitor monitor = travelStatusMonitor;
        statusReader.execute(() -> {
            TravelStatusMonitor.Snapshot read = null;
            String battery = deviceBattery;
            try { read = monitor.read(); battery = DeviceBattery.read(this); }
            catch (RuntimeException unavailable) { android.util.Log.w("OABL", "Status read failed", unavailable); }
            TravelStatusMonitor.Snapshot snapshot = read;
            String batteryText = battery;
            handler.post(() -> {
                statusReading.set(false);
                if (snapshot == null || ActivityState.isGone(this)) return;
                deviceBattery = batteryText;
                applyTravelStatus(snapshot, batteryText);
            });
        });
    }

    private void applyTravelStatus(TravelStatusMonitor.Snapshot snapshot, String battery) {
        float celsius = BoxThermal.celsius();
        String key = snapshot.wifiConnected + "|" + snapshot.wifiLevel + "|" + snapshot.wifi + "|" + snapshot.cellularAvailable
                + "|" + snapshot.cellularLevel + "|" + snapshot.dataType + "|" + snapshot.cellular + "|" + snapshot.bluetooth
                + "|" + snapshot.call + "|" + battery + "|" + (Float.isNaN(celsius) ? "-" : Math.round(celsius))
                + "|" + BoxThermal.throttled + "|" + DashboardData.get(this).freshFix()
                + "|" + getResources().getDisplayMetrics().widthPixels
                + "|" + (palette == null ? "" : palette.light + "," + palette.accent);
        if (key.equals(renderedStatusKey)) return;
        renderedStatusKey = key;
        ((TextView)findViewById(R.id.device_battery)).setText(battery);
        float density = getResources().getDisplayMetrics().density;
        int widthDp = Math.round(getResources().getDisplayMetrics().widthPixels / density);
        networkStatus.setMaxWidth(dp(widthDp < 1100 ? 330 : 560));
        networkStatus.setText(buildTravelStatus(snapshot, battery));
        ((TextView)findViewById(R.id.wifi_detail)).setText(snapshot.wifi);
        String cellularDescription = snapshot.cellularAvailable
                ? ". " + snapshot.cellular : "";
        networkStatus.setContentDescription("Travel status. " + snapshot.wifi
                + cellularDescription + ". " + snapshot.bluetooth + ". " + snapshot.call);
        networkStatus.setTextColor(getColorCompat("No active call".equals(snapshot.call)
                ? R.color.ink : R.color.accent));
    }

    /** One quiet row of status icons; details live behind a tap. */
    private SpannableStringBuilder buildTravelStatus(TravelStatusMonitor.Snapshot snapshot, String battery) {
        SpannableStringBuilder status = new SpannableStringBuilder();
        if (!"No active call".equals(snapshot.call)) {
            int start = status.length();
            status.append("● ").append(shortLabel(snapshot.call, 20)).append("     ");
            status.setSpan(new ForegroundColorSpan(getColorCompat(R.color.accent)), start, status.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (snapshot.wifiConnected) appendStatusIcon(status, StatusIconSpan.WIFI, snapshot.wifiLevel);
        else appendGlyph(status, R.drawable.ic_ctl_wifi, false);
        if (snapshot.cellularAvailable) {
            status.append("  ");
            appendStatusIcon(status, StatusIconSpan.CELLULAR, snapshot.cellularLevel);
            if (snapshot.dataType != null) {
                int start = status.length();
                status.append(snapshot.dataType);
                status.setSpan(new android.text.style.RelativeSizeSpan(.8f), start, status.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                status.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, status.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        status.append("    ");
        boolean bluetooth = snapshot.bluetooth != null && !snapshot.bluetooth.startsWith("Bluetooth off")
                && !snapshot.bluetooth.contains("unavailable");
        appendGlyph(status, R.drawable.ic_ctl_bluetooth, bluetooth);
        status.append("    ");
        appendGlyph(status, R.drawable.ic_status_gps, DashboardData.get(this).freshFix());
        status.append("    ");
        // A box on car power gains nothing from a battery percentage: show its temperature
        // instead, which matters in a car parked in the sun.
        float celsius = BoxThermal.celsius();
        boolean onCarPower = !battery.contains("%") || battery.contains("charging") || battery.contains("full")
                || battery.contains("100%") || battery.contains("external");
        if (!Float.isNaN(celsius) && onCarPower) {
            appendGlyph(status, R.drawable.ic_status_temp, true);
            int start = status.length();
            status.append(Math.round(celsius) + "°");
            if (BoxThermal.throttled) {
                status.setSpan(new ForegroundColorSpan(0xffe5484d), start, status.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                status.append("  hot · effects reduced");
            }
        } else {
            java.util.regex.Matcher percent = BATTERY_PERCENT.matcher(battery);
            appendGlyph(status, R.drawable.ic_status_battery, true);
            status.append(percent.find() ? percent.group(1) + "%" : "—");
            if (BoxThermal.throttled) status.append("   hot · effects reduced");
        }
        return status;
    }

    private void appendGlyph(SpannableStringBuilder text, int icon, boolean active) {
        Drawable glyph = getDrawable(icon).mutate();
        int size = dp(20);
        glyph.setBounds(0, 0, size, size);
        int muted = getColorCompat(R.color.muted);
        glyph.setTint(active ? getColorCompat(R.color.ink) : Color.argb(110, Color.red(muted), Color.green(muted), Color.blue(muted)));
        int start = text.length();
        text.append('\ufffc');
        text.setSpan(new android.text.style.ImageSpan(glyph, android.text.style.DynamicDrawableSpan.ALIGN_CENTER),
                start, text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append(' ');
    }

    private void appendStatusIcon(SpannableStringBuilder text, int kind, int level) {
        int start = text.length();
        text.append('\ufffc');
        int muted = getColorCompat(R.color.muted);
        int inactive = Color.argb(72, Color.red(muted), Color.green(muted), Color.blue(muted));
        text.setSpan(new StatusIconSpan(this, kind, level,
                        getColorCompat(R.color.ink), inactive), start, text.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append(' ');
    }

    private static void appendSeparator(SpannableStringBuilder text) {
        if (text.length() > 0) {
            text.append("   ·   ");
        }
    }

    private static String shortLabel(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(1, limit - 1)) + "…";
    }

    private void showTravelStatus() {
        new HardwareStatusPanel(this, palette, this::hardwareRows).show();
    }

    /** One row per thing the box depends on, each with the screen that changes it. */
    private java.util.List<HardwareStatusPanel.Row> hardwareRows() {
        final TravelStatusMonitor.Snapshot s = travelStatusMonitor.read();
        final java.util.List<HardwareStatusPanel.Row> rows = new ArrayList<>();
        HomeEnergySnapshot car = home;
        CarLinkController link = EnergyClient.link(this);
        if (car != null && car.car) {
            int status = car.linkStatus();
            String word = status == HomeEnergySnapshot.LINK_ONLINE ? "Online" : "Offline";
            String detail = car.linkState == null || car.linkState.isEmpty() ? "Bluetooth" : car.linkState;
            if (car.live) detail += " · data " + Math.max(0, car.ageSeconds) + " s old";
            rows.add(new HardwareStatusPanel.Row("Car link", word + " · " + detail,
                    status == HomeEnergySnapshot.LINK_ONLINE ? HardwareStatusPanel.GOOD : HardwareStatusPanel.BAD,
                    "Connection", () -> CarLinkDialogs.status(this, palette, link)));
            if (car.lowVoltage != null && Double.isFinite(car.lowVoltage.volts)) {
                rows.add(new HardwareStatusPanel.Row("Car 12 V battery",
                        String.format(Locale.US, "%.1f V · %s", car.lowVoltage.volts, car.lowVoltage.low ? "low, check before parking long" : "normal"),
                        car.lowVoltage.low ? HardwareStatusPanel.BAD : HardwareStatusPanel.GOOD, "Energy", () -> openTool(EnergyActivity.class)));
            }
        } else {
            rows.add(new HardwareStatusPanel.Row("Car link", "Manual entry · the car is not connected", HardwareStatusPanel.OFF, "Connect",
                    () -> CarLinkDialogs.dataSource(this, palette, () -> { }) ));
        }
        rows.add(new HardwareStatusPanel.Row("Wi-Fi", s.wifi, s.wifiConnected ? HardwareStatusPanel.GOOD : HardwareStatusPanel.OFF, "Wi-Fi settings", () -> {
            if (s.wifiConnected && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) requestSpeedAccess(false);
            else openSystemSettings(Settings.ACTION_WIFI_SETTINGS);
        }));
        if (s.cellularAvailable) {
            rows.add(new HardwareStatusPanel.Row("Mobile data", s.cellular, s.cellularLevel > 0 ? HardwareStatusPanel.GOOD : HardwareStatusPanel.WARN, "Mobile settings", () -> {
                if (s.phonePermissionNeeded) requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, REQUEST_PHONE);
                else openSystemSettings(Settings.ACTION_NETWORK_OPERATOR_SETTINGS);
            }));
        }
        boolean bluetoothOn = s.bluetooth != null && !s.bluetooth.startsWith("Bluetooth off") && !s.bluetooth.contains("unavailable");
        String bluetoothText = s.bluetooth != null && s.bluetooth.startsWith("BT ") && !s.bluetooth.startsWith("BT details")
                ? "Connected · " + s.bluetooth.substring(3) : s.bluetooth;
        if (s.bluetoothPermissionNeeded) bluetoothText = "Allow Bluetooth access to see the connected device";
        rows.add(new HardwareStatusPanel.Row("Bluetooth", bluetoothText, s.bluetoothPermissionNeeded ? HardwareStatusPanel.WARN : bluetoothOn ? HardwareStatusPanel.GOOD : HardwareStatusPanel.OFF, "Bluetooth settings", () -> {
            if (s.bluetoothPermissionNeeded && Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH);
            else openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS);
        }));
        boolean inCall = !"No active call".equals(s.call);
        rows.add(new HardwareStatusPanel.Row("Calls and media", s.call + (hasNotificationAccess() ? "" : " · access is off, now playing and turn-by-turn need it"),
                hasNotificationAccess() ? (inCall ? HardwareStatusPanel.GOOD : HardwareStatusPanel.OFF) : HardwareStatusPanel.WARN, "Access",
                () -> openSystemSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        DashboardData data = DashboardData.get(this);
        rows.add(new HardwareStatusPanel.Row("GPS", data.satellitesUsed + " of " + data.satellitesSeen + " satellites · " + (data.freshFix() ? "fix ready" : "waiting for a fix"),
                data.freshFix() ? HardwareStatusPanel.GOOD : HardwareStatusPanel.WARN, "Location",
                () -> openSystemSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        float celsius = BoxThermal.celsius();
        String temperature = Float.isNaN(celsius) ? "temperature unknown" : Math.round(celsius) + "°C" + (BoxThermal.throttled ? " · hot, effects reduced" : " · normal");
        rows.add(new HardwareStatusPanel.Row("AI box", temperature + " · " + DeviceBattery.read(this),
                BoxThermal.throttled ? HardwareStatusPanel.BAD : HardwareStatusPanel.GOOD, "Performance", () -> showPerformanceSettings()));
        return rows;
    }

    private void launch(ComponentName component) {
        launch(component, null);
    }

    /** Opens an app, growing it out of the tapped icon when visual effects are on. */
    private void launch(ComponentName component, View source) {
        if (component == null) {
            Toast.makeText(this, "Choose an app first", Toast.LENGTH_SHORT).show();
            return;
        }
        if(moving()&&videoApp(component.getPackageName())){
            Toast.makeText(this,"Video blocked while driving",Toast.LENGTH_SHORT).show();
            return;
        }
        preferences.addDockRecent(component.flattenToString());
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            SplitScreenService.showAppControls(MainActivity.this);
            if (source != null && preferences.visualEffects() && source.getWidth() > 0) {
                startActivity(intent, android.app.ActivityOptions.makeScaleUpAnimation(
                        source, 0, 0, source.getWidth(), source.getHeight()).toBundle());
            } else {
                startActivity(intent);
                playLaunchTransition();
            }
        } catch (ActivityNotFoundException error) {
            SplitScreenService.hideAppControls(MainActivity.this);
            Toast.makeText(this, "That app is no longer available", Toast.LENGTH_SHORT).show();
            apps = new ArrayList<>();
            reloadApps();
        }
    }

    private void launchPackage(String packageName) {
        if(moving()&&videoApp(packageName)){
            Toast.makeText(this,"Video blocked while driving",Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            Toast.makeText(this, "Unable to open media app", Toast.LENGTH_SHORT).show();
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            SplitScreenService.showAppControls(MainActivity.this);
            startActivity(intent);
            playLaunchTransition();
        } catch (ActivityNotFoundException error) {
            SplitScreenService.hideAppControls(MainActivity.this);
            Toast.makeText(this, "Unable to open media app", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean videoApp(String packageName){
        if(packageName==null||packageName.equals("com.google.android.apps.youtube.music"))return false;
        String lower=packageName.toLowerCase(Locale.ROOT);
        if(lower.equals("com.google.android.youtube")||lower.contains("youtube.tv")||lower.contains("netflix")
                ||lower.equals("org.videolan.vlc")||lower.contains("primevideo")||lower.contains("tiktok")
                ||lower.equals("com.google.android.videos"))return true;
        Intent video=new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_VIDEO").setPackage(packageName);
        return !getPackageManager().queryIntentActivities(video,PackageManager.MATCH_DEFAULT_ONLY).isEmpty();
    }

    private void openAppDetails(String packageName) {
        try {
            SplitScreenService.showAppControls(MainActivity.this);
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + packageName)));
        } catch (ActivityNotFoundException ignored) {
            SplitScreenService.hideAppControls(MainActivity.this);
            Toast.makeText(this, "App details are unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    private void openSystemSettings(String action) {
        try {
            SplitScreenService.showAppControls(MainActivity.this);
            startActivity(new Intent(action));
            playLaunchTransition();
        } catch (ActivityNotFoundException error) {
            SplitScreenService.hideAppControls(MainActivity.this);
            Toast.makeText(this, "This setting is unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void playLaunchTransition() {
        if (preferences.visualEffects()) {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } else {
            overridePendingTransition(0, 0);
        }
    }

    private AlertDialog.Builder dialogBuilder() {
        return new ThemedDialog(this, palette != null ? palette : LauncherTheme.resolve(this, preferences));
    }

    private void applyAppearance(boolean animate) {
        final LauncherTheme.Palette next = LauncherTheme.resolve(this, preferences);
        if (palette != null && palette.light == next.light && palette.accent == next.accent) {
            return;
        }
        final View root = findViewById(R.id.root);
        if (animate && preferences.visualEffects() && root != null && root.isLaidOut()) {
            root.animate().cancel();
            root.animate().alpha(0.72f).setDuration(100L)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            applyAppearanceNow(next);
                            root.setAlpha(0.72f);
                            root.animate().alpha(1f).setDuration(260L)
                                    .setInterpolator(new DecelerateInterpolator()).start();
                        }
                    }).start();
        } else {
            applyAppearanceNow(next);
        }
    }

    private void applyAppearanceNow(LauncherTheme.Palette next) {
        LauncherTheme.Palette previous = palette;
        palette = next;
        View root = findViewById(R.id.root);
        root.setBackgroundColor(next.root);
        if (Build.VERSION.SDK_INT >= 29) {
            root.setForceDarkAllowed(false);
        }
        wallpaper.setBackgroundColor(next.root);
        boolean defaultWallpaper = preferences.wallpaperUri() == null || preferences.sampleWallpaper();
        if (preferences.auroraBackground() || defaultWallpaper) {
            loadWallpaper();
        }
        updateScrim();
        View clockCard = findViewById(R.id.clock_card);
        clockCard.setBackgroundColor(Color.TRANSPARENT);
        networkStatus.setBackground(LauncherTheme.dockItem(this, next));
        View speedCluster = findViewById(R.id.speed_cluster);
        speedCluster.setBackgroundColor(Color.TRANSPARENT);
        View calendarCard = findViewById(R.id.calendar_card);
        calendarCard.setBackground(LauncherTheme.glass(this, next));
        View mediaCard = findViewById(R.id.media_card);
        mediaCard.setBackground(LauncherTheme.glass(this, next));
        ((HeadingStripView) findViewById(R.id.heading_strip)).theme(next);
        findViewById(R.id.telemetry_card).setBackground(LauncherTheme.glass(this, next));
        findViewById(R.id.trip_card).setBackground(LauncherTheme.glass(this, next));
        findViewById(R.id.energy_card).setBackground(LauncherTheme.glass(this, next));
        findViewById(R.id.dashboard_panel).setBackgroundColor(Color.TRANSPARENT);
        findViewById(R.id.dashboard_divider).setBackgroundColor(
                next.light ? 0x14102a2d : 0x18ffffff);
        findViewById(R.id.navigation_divider).setBackgroundColor(
                next.light ? 0x14102a2d : 0x18ffffff);
        navigationHintCard.setBackground(LauncherTheme.glass(this, next));
        navigationHintIcon.setBackground(LauncherTheme.primary(this, next));
        navigationHintIcon.setPadding(dp(10), dp(10), dp(10), dp(10));
        navigationHintIcon.setClipToOutline(true);
        findViewById(R.id.navigation_end).setBackground(ripple(
                LauncherTheme.rounded(this, next.light ? 0x14102a2d : 0x1cffffff, 15, 0, 0),
                next.light ? 0x22102a2d : 0x33ffffff));
        ((TurnProgressView) findViewById(R.id.navigation_turn_progress)).colors(next.accent,
                next.light ? 0x1a102a2d : 0x24ffffff);
        renderedChipsKey = null;
        // The command bar sits on the wallpaper with a hairline above it, not in a capsule.
        dock.setBackground(commandBarBackground(next));
        mediaArtwork.setBackground(LauncherTheme.tile(this, next));
        mediaArtwork.setClipToOutline(true);
        int[] circleButtons = {R.id.media_previous, R.id.media_next, R.id.media_playlists, R.id.media_expand, R.id.media_favorite, R.id.media_eq};
        for (int id : circleButtons) {
            findViewById(id).setBackground(ripple(null, next.light ? 0x26102a2d : 0x33ffffff));
        }
        findViewById(R.id.media_open).setBackground(LauncherTheme.dockItem(this, next));
        findViewById(R.id.charge_review_pill).setBackground(ripple(LauncherTheme.rounded(this,next.surface,28,1,next.accent),
                next.light?0x22102a2d:0x33ffffff));
        ((TextView)findViewById(R.id.charge_review_text)).setTextColor(next.ink);
        ((TextView)findViewById(R.id.charge_review_action)).setTextColor(next.accent);
        mediaCard.setClipToOutline(true);
        recolorTextTree(root, previous, next);
        // The startup reminder's card is drawn for one palette; rebuild it so a theme change (Auto at
        // sunrise or sunset) never leaves light-theme text on the dark card or the other way round.
        if (startupNotice != null) {
            ((ViewGroup) findViewById(R.id.root)).removeView(startupNotice);
            startupNotice = null;
            maybeShowStartupNotice();
        }
        renderedCalendarKey = null;
        renderWeatherCard();
        applyMediaColors();
        clockCard.setElevation(0f);
        networkStatus.setElevation(0f);
        speedCluster.setElevation(0f);
        calendarCard.setElevation(0f);
        mediaCard.setElevation(0f);
        findViewById(R.id.dashboard_panel).setElevation(next.light ? dp(2) : 0f);
        TextView clock = findViewById(R.id.clock);
        clock.setTypeface(android.graphics.Typeface.create(
                "sans-serif-light",
                android.graphics.Typeface.NORMAL));
        TextView date = findViewById(R.id.date);
        if (next.light) {
            clock.setShadowLayer(dp(10), 0f, dp(1), 0xb8ffffff);
            date.setShadowLayer(dp(5), 0f, dp(1), 0xb8ffffff);
        } else {
            clock.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
            date.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        }
        getWindow().setNavigationBarColor(next.root);
        getWindow().setStatusBarColor(next.root);
        if (dock != null && dock.getChildCount() > 0) {
            renderDock();
        }
        renderTravelStatus();
        renderedNavigationKey = null;
        renderNavigationHints();
        renderedEnergyKey = null;
        renderDashboardData();
    }

    private void recolorTextTree(View view, LauncherTheme.Palette previous,
                                 LauncherTheme.Palette next) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            int current = text.getCurrentTextColor();
            int darkInk = getResources().getColor(R.color.ink, getTheme());
            int darkMuted = getResources().getColor(R.color.muted, getTheme());
            int darkAccent = getResources().getColor(R.color.accent, getTheme());
            if (current == darkInk || (previous != null && current == previous.ink)) {
                text.setTextColor(next.ink);
            } else if (current == darkMuted || (previous != null && current == previous.muted)) {
                text.setTextColor(next.muted);
            } else if (current == darkAccent || (previous != null && current == previous.accent)) {
                text.setTextColor(next.accent);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                recolorTextTree(group.getChildAt(i), previous, next);
            }
        }
    }

    private void runEntranceAnimation() {
        if (entranceAnimated) {
            return;
        }
        View root = findViewById(R.id.root);
        if (!preferences.visualEffects()) {
            cancelAnimations(root);
            entranceAnimated = true;
            return;
        }
        animateIn((View) findViewById(R.id.clock).getParent(), 0f, -dp(12), 1f,
                0L, 360L);
        animateIn(networkStatus, dp(16), 0f, 0.98f, 70L, 360L);
        animateIn(findViewById(R.id.speed_cluster), 0f, dp(16), 0.86f,
                110L, 520L);
        animateIn(findViewById(R.id.calendar_card), -dp(22), dp(8), 0.97f,
                190L, 440L);
        animateIn(findViewById(R.id.media_card), dp(26), dp(8), 0.98f,
                230L, 440L);
        animateIn(dock, 0f, dp(24), 0.98f, 300L, 460L);
        entranceAnimated = true;
    }

    private void animateIn(View view, float fromX, float fromY, float fromScale,
                           long delay, long duration) {
        if (view == null) {
            return;
        }
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationX(fromX);
        view.setTranslationY(fromY);
        view.setScaleX(fromScale);
        view.setScaleY(fromScale);
        view.animate().alpha(1f).translationX(0f).translationY(0f)
                .scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(duration)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void animateDockTiles() {
        for (int i = 0; i < dock.getChildCount(); i++) {
            View child = dock.getChildAt(i);
            child.animate().cancel();
            child.setAlpha(0f);
            child.setTranslationY(dp(12));
            child.setScaleX(0.96f);
            child.setScaleY(0.96f);
            child.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(i * 35L).setDuration(260L)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private void installMotionFeedback(final View view) {
        if (view == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && view.getForeground() == null && view.getBackground() != null
                && !(view.getBackground() instanceof android.graphics.drawable.RippleDrawable)) {
            Drawable.ConstantState shape = view.getBackground().getConstantState();
            view.setForeground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(palette != null && palette.light ? 0x1f102a2d : 0x26ffffff),
                    null, shape == null ? null : shape.newDrawable()));
        }
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View target, MotionEvent event) {
                if (!preferences.visualEffects()) {
                    target.animate().cancel();
                    target.setScaleX(1f);
                    target.setScaleY(1f);
                    return false;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    target.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90L)
                            .setInterpolator(new DecelerateInterpolator()).start();
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    // A slight overshoot on release reads as a physical button settling.
                    target.animate().scaleX(1f).scaleY(1f).setDuration(240L)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
                }
                return false;
            }
        });
    }

    private void cancelAnimations(View view) {
        if (view == null) {
            return;
        }
        view.animate().cancel();
        view.setAlpha(1f);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                cancelAnimations(group.getChildAt(i));
            }
        }
    }

    private void animateMediaChange(String signature) {
        boolean changed = lastMediaSignature != null && !lastMediaSignature.equals(signature);
        lastMediaSignature = signature;
        if (!changed || !foreground || !preferences.visualEffects()) {
            return;
        }
        View media = findViewById(R.id.media_card);
        media.animate().cancel();
        media.setAlpha(0.62f);
        media.setScaleX(0.985f);
        media.setScaleY(0.985f);
        // Cancelling may interrupt the entrance slide; finish its translation here too.
        media.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setDuration(260L)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private int getColorCompat(int id) {
        if (palette != null) {
            if (id == R.color.ink) return palette.ink;
            if (id == R.color.muted) return palette.muted;
            if (id == R.color.accent) return palette.accent;
        }
        return getResources().getColor(id, getTheme());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface AppChoice {
        void selected(AppEntry app);
    }

    private final class AppGridAdapter extends BaseAdapter {
        private final List<AppEntry> entries;

        AppGridAdapter(List<AppEntry> entries) {
            this.entries = entries;
        }

        @Override public int getCount() { return entries.size(); }
        @Override public AppEntry getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout tile = new LinearLayout(MainActivity.this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(10), dp(12), dp(10), dp(10));
            tile.setBackground(LauncherTheme.tile(MainActivity.this, palette));
            tile.setElevation(palette.light ? dp(3) : 0f);
            installMotionFeedback(tile);
            AppEntry entry = entries.get(position);

            ImageView icon = new ImageView(MainActivity.this);
            icon.setImageDrawable(entry.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));

            TextView label = new TextView(MainActivity.this);
            label.setText(entry.isShortcut() && entry.appLabel != null
                    ? entry.label + "\n" + entry.appLabel : entry.label);
            label.setTextColor(getColorCompat(R.color.ink));
            label.setTextSize(13);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
            labelParams.topMargin = dp(6);
            tile.addView(label, labelParams);
            tile.setLayoutParams(new GridView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(122)));
            return tile;
        }
    }
}
