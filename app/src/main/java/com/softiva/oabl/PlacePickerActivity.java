package com.softiva.oabl;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.TilesOverlay;

/**
 * Picks Home or Work exactly: a map with a fixed pin in the middle. Pan until the pin's tip is on
 * the place, or search, or jump to the car's location; the street under the pin is shown as you go.
 * OpenStreetMap tiles, no account or key; tiles are cached in the app's cache folder.
 */
public final class PlacePickerActivity extends Activity {
    static final String EXTRA_HOME = "home", EXTRA_LAT = "lat", EXTRA_LON = "lon", EXTRA_LABEL = "label";
    private static final ExecutorService LOOKUP = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LauncherTheme.Palette palette;
    private MapView map;
    private TextView place, coordinates;
    private String label;
    private int lookups;
    private final Runnable lookupCenter = this::lookupCenter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        palette = LauncherTheme.resolve(this, new LauncherPreferences(this));
        boolean home = getIntent().getBooleanExtra(EXTRA_HOME, true);

        Configuration.getInstance().setUserAgentValue(getPackageName());
        Configuration.getInstance().setOsmdroidBasePath(new java.io.File(getFilesDir(), "osmdroid"));
        Configuration.getInstance().setOsmdroidTileCache(new java.io.File(getCacheDir(), "osmdroid-tiles"));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(palette.root);
        map = new MapView(this);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true);
        map.getZoomController().setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER);
        if (!palette.light) map.getOverlayManager().getTilesOverlay().setColorFilter(TilesOverlay.INVERT_COLORS);
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));

        // The pin stays in the middle; its tip marks the point.
        ImageView pin = new ImageView(this);
        pin.setImageResource(R.drawable.ic_map_pin);
        pin.setImageTintList(android.content.res.ColorStateList.valueOf(palette.accent));
        FrameLayout.LayoutParams pinSize = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER);
        pinSize.bottomMargin = dp(44); // tip (bottom of the icon) at the map's centre
        root.addView(pin, pinSize);

        // Top: back, title, search.
        LinearLayout top = bar();
        MusicIconView back = new MusicIconView(this, MusicIconView.Icon.BACK, palette.ink, palette.accent, "Back", this::finish);
        top.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView title = text(home ? "Set Home" : "Set Work", 22, palette.ink, true);
        title.setPadding(dp(6), 0, dp(16), 0);
        top.addView(title);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search a place or address");
        search.setTextColor(palette.ink);
        search.setHintTextColor(palette.muted);
        search.setBackgroundTintList(android.content.res.ColorStateList.valueOf(palette.accent));
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v, action, event) -> { search(search.getText().toString()); return true; });
        top.addView(search, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView go = button("Search", false, () -> search(search.getText().toString()));
        LinearLayout.LayoutParams goSize = new LinearLayout.LayoutParams(-2, dp(48));
        goSize.setMarginStart(dp(8));
        top.addView(go, goSize);
        FrameLayout.LayoutParams topSize = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        topSize.setMargins(dp(16), dp(12), dp(16), 0);
        root.addView(top, topSize);

        // Bottom: what's under the pin, my location, save.
        LinearLayout bottom = bar();
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        place = text("Move the map to put the pin on the place", 18, palette.ink, true);
        place.setMaxLines(1);
        place.setEllipsize(android.text.TextUtils.TruncateAt.END);
        coordinates = text("", 13, palette.muted, false);
        texts.addView(place);
        texts.addView(coordinates);
        texts.setPadding(dp(12), 0, dp(12), 0);
        bottom.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        bottom.addView(button("My location", false, this::centerOnCar), new LinearLayout.LayoutParams(-2, dp(52)));
        LinearLayout.LayoutParams saveSize = new LinearLayout.LayoutParams(-2, dp(52));
        saveSize.setMarginStart(dp(10));
        bottom.addView(button(home ? "Save as Home" : "Save as Work", true, this::save), saveSize);
        FrameLayout.LayoutParams bottomSize = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bottomSize.setMargins(dp(16), 0, dp(16), dp(16));
        root.addView(bottom, bottomSize);
        TextView credit = text("© OpenStreetMap contributors", 11, palette.muted, false);
        FrameLayout.LayoutParams creditSize = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        creditSize.setMargins(0, 0, dp(22), dp(96));
        root.addView(credit, creditSize);
        setContentView(root);
        ImmersiveWindow.apply(getWindow(), root);

        // Start at the saved point, else the car, else Colombo.
        LauncherPreferences preferences = new LauncherPreferences(this);
        double[] saved = preferences.navigationPoint(home);
        Location car = carLocation();
        GeoPoint start = saved != null ? new GeoPoint(saved[0], saved[1])
                : car != null ? new GeoPoint(car.getLatitude(), car.getLongitude()) : new GeoPoint(6.9271, 79.8612);
        map.getController().setZoom(saved != null || car != null ? 17.5 : 12.0);
        map.getController().setCenter(start);
        map.addMapListener(new MapListener() {
            @Override public boolean onScroll(ScrollEvent event) { centerMoved(); return false; }
            @Override public boolean onZoom(ZoomEvent event) { centerMoved(); return false; }
        });
        centerMoved();
    }

    @Override protected void onResume() { super.onResume(); map.onResume(); }
    @Override protected void onPause() { map.onPause(); super.onPause(); }

    private void centerMoved() {
        GeoPoint center = (GeoPoint) map.getMapCenter();
        coordinates.setText(String.format(Locale.US, "%.5f, %.5f", center.getLatitude(), center.getLongitude()));
        label = null;
        main.removeCallbacks(lookupCenter);
        main.postDelayed(lookupCenter, 600);
    }

    /** Street (or area) under the pin, after the map settles. */
    private void lookupCenter() {
        GeoPoint center = (GeoPoint) map.getMapCenter();
        final double lat = center.getLatitude(), lon = center.getLongitude();
        final int id = ++lookups;
        place.setText("Finding the street…");
        LOOKUP.execute(() -> {
            String name = null;
            if (Geocoder.isPresent()) {
                try {
                    List<Address> found = new Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1);
                    if (found != null && !found.isEmpty()) name = describe(found.get(0));
                } catch (Exception offline) {
                    // Coordinates stay as the label.
                }
            }
            final String result = name;
            main.post(() -> {
                if (id != lookups || isFinishing()) return;
                label = result;
                place.setText(result == null ? "Unnamed spot" : result);
            });
        });
    }

    /** "House no., Street, Area" as available. */
    private static String describe(Address a) {
        List<String> parts = new ArrayList<>();
        String street = a.getThoroughfare() == null ? null : (a.getSubThoroughfare() == null ? "" : a.getSubThoroughfare() + " ") + a.getThoroughfare();
        for (String part : new String[]{street, a.getSubLocality(), a.getLocality()}) {
            if (part != null && !part.trim().isEmpty() && !parts.contains(part.trim())) parts.add(part.trim());
        }
        if (parts.isEmpty() && a.getFeatureName() != null) parts.add(a.getFeatureName());
        return parts.isEmpty() ? null : android.text.TextUtils.join(", ", parts);
    }

    private void search(String query) {
        if (query == null || query.trim().isEmpty()) return;
        // Close the keyboard so the map and the Save bar are visible again.
        android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null && getCurrentFocus() != null) keyboard.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        GeoPoint near = (GeoPoint) map.getMapCenter();
        LOOKUP.execute(() -> {
            List<Address> found = null;
            try {
                if (Geocoder.isPresent()) {
                    // Prefer results around where the map is (about 60 km), then anywhere.
                    found = new Geocoder(this, Locale.getDefault()).getFromLocationName(query, 6,
                            near.getLatitude() - 0.5, near.getLongitude() - 0.5, near.getLatitude() + 0.5, near.getLongitude() + 0.5);
                    if (found == null || found.isEmpty()) found = new Geocoder(this, Locale.getDefault()).getFromLocationName(query, 6);
                }
            } catch (Exception offline) {
                found = null;
            }
            final List<Address> results = found;
            main.post(() -> {
                if (isFinishing()) return;
                if (results == null || results.isEmpty()) {
                    Toast.makeText(this, "Nothing found. Move the map to the place instead.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (results.size() == 1) { jump(results.get(0)); return; }
                String[] names = new String[results.size()];
                for (int i = 0; i < names.length; i++) {
                    String described = describe(results.get(i));
                    names[i] = described == null ? results.get(i).getAddressLine(0) : described;
                }
                new ThemedDialog(this, palette).setTitle("Which one?")
                        .setItems(names, (d, which) -> jump(results.get(which)))
                        .setNegativeButton("Cancel", null).show();
            });
        });
    }

    private void jump(Address address) {
        map.getController().animateTo(new GeoPoint(address.getLatitude(), address.getLongitude()), 18.0, 600L);
    }

    private void centerOnCar() {
        Location car = carLocation();
        if (car == null) { Toast.makeText(this, "No GPS position yet", Toast.LENGTH_SHORT).show(); return; }
        map.getController().animateTo(new GeoPoint(car.getLatitude(), car.getLongitude()), 18.0, 600L);
    }

    private Location carLocation() {
        DashboardData data = DashboardData.get(this);
        if (data.freshFix()) return data.fix;
        try {
            LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location last = manager == null ? null : manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) return last;
        } catch (SecurityException noPermission) {
            // Fall back to the map's default.
        }
        return data.fix;
    }

    private void save() {
        GeoPoint center = (GeoPoint) map.getMapCenter();
        setResult(RESULT_OK, new Intent()
                .putExtra(EXTRA_HOME, getIntent().getBooleanExtra(EXTRA_HOME, true))
                .putExtra(EXTRA_LAT, center.getLatitude())
                .putExtra(EXTRA_LON, center.getLongitude())
                .putExtra(EXTRA_LABEL, label));
        finish();
    }

    private LinearLayout bar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(12), dp(8));
        bar.setBackground(LauncherTheme.rounded(this, palette.light ? 0xf5ffffff : 0xf2171d24, 20, 0, 0));
        bar.setElevation(dp(4));
        return bar;
    }

    private TextView button(String label, boolean primary, Runnable action) {
        TextView button = text(label, 16, primary ? (palette.light ? 0xffffffff : palette.root) : palette.ink, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackground(primary ? LauncherTheme.rounded(this, palette.accent, 14, 0, 0) : LauncherTheme.raised(this, palette, 14));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private TextView text(String value, int size, int color, boolean medium) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
