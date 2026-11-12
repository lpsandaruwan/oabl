package com.softiva.oabl;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Full-screen launcher settings in fixed, paginated sections; rows show their current value. */
final class SettingsPanel {
    /** Section title followed by row ids; titles come from {@link MainActivity#settingTitle}. */
    private static final String[][] SECTIONS = {
            {"Look & feel", "appearance", "accent", "wallpaper", "background", "experience"},
            {"Home", "home_role", "home_tab", "weather", "screensaver", "screensaver_image", "startup", "start_actions", "split"},
            {"Driving", "vehicle", "energy", "car_source", "driving_mode", "speed_limit", "max_speed", "nav_app", "trips", "service"},
            {"Sound", "equalizer", "crossfade", "album_art", "music"},
            {"System", "backup", "media_access", "overlay", "split_assistant", "car_data", "android_settings", "performance", "about"},
    };
    private final MainActivity host;
    private final LauncherTheme.Palette palette;
    private final float density;
    private final List<TextView[]> rows = new ArrayList<>();
    private Dialog dialog;

    SettingsPanel(MainActivity host, LauncherTheme.Palette palette) {
        this.host = host;
        this.palette = palette;
        density = host.getResources().getDisplayMetrics().density;
    }

    private int dp(float value) { return Math.round(value * density); }

    private int section, page, renderedHeight, renderedWidth, rowHeight;
    private LinearLayout rail, grid, content;
    private TextView pager;

    /**
     * A fixed page, never scrolled: the sections on a rail, the chosen section's rows in two
     * columns (one in a split window). When a window is too short for a section, its rows page.
     */
    void show() {
        dialog = new Dialog(host, android.R.style.Theme_Material_NoActionBar);
        LinearLayout root = column();
        root.setBackgroundColor(palette.root);
        root.setPadding(dp(24), dp(12), dp(24), dp(16));

        LinearLayout header = new LinearLayout(host);
        header.setGravity(Gravity.CENTER_VERTICAL);
        MusicIconView back = new MusicIconView(host, MusicIconView.Icon.BACK, palette.ink, palette.accent, "Close settings", this::close);
        back.setBackground(LauncherTheme.dockItem(host, palette));
        header.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView title = text("Settings", 28, palette.ink, true);
        title.setPadding(dp(10), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        pager = text("", 15, palette.ink, true);
        pager.setGravity(Gravity.CENTER);
        pager.setPadding(dp(18), 0, dp(18), 0);
        pager.setBackground(LauncherTheme.raised(host, palette, 16));
        pager.setOnClickListener(v -> { page++; render(true); });
        header.addView(pager, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
        root.addView(header);

        LinearLayout body = new LinearLayout(host);
        body.setOrientation(LinearLayout.HORIZONTAL);
        rail = column();
        body.addView(rail, new LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.MATCH_PARENT));
        content = column();
        LinearLayout.LayoutParams contentSize = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        contentSize.setMarginStart(dp(16));
        body.addView(content, contentSize);
        grid = new LinearLayout(host);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams bodySize = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        bodySize.topMargin = dp(12);
        root.addView(body, bodySize);
        content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != renderedWidth || b - t != renderedHeight) v.post(() -> render(false));
        });

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(palette.root));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            ImmersiveWindow.apply(window, root);
        }
        // Values may change in sub-dialogs or system screens; refresh whenever we regain focus.
        root.getViewTreeObserver().addOnWindowFocusChangeListener(focused -> { if (focused) refresh(); });
        MapsTile.hideWhileOpen(dialog);
        dialog.show();
        DrivingDismiss.track(dialog);
    }

    void close() {
        if (dialog != null) dialog.dismiss();
    }

    /** Shows a section by its title, e.g. "Driving". */
    void showSection(String title) {
        for (int i = 0; i < SECTIONS.length; i++) if (SECTIONS[i][0].equals(title)) { section = i; page = 0; }
        if (content != null && content.getHeight() > 0) render(true);
    }

    /** The row for a setting, if it is on the page shown now (tests and deep links). */
    View row(String id) {
        for (int i = 0; i < SECTIONS.length; i++) for (int j = 1; j < SECTIONS[i].length; j++) {
            if (SECTIONS[i][j].equals(id) && i != section) { section = i; page = 0; render(true); }
        }
        return dialog == null ? null : dialog.getWindow().getDecorView().findViewWithTag("setting_" + id);
    }

    private void render(boolean force) {
        int width = content.getWidth(), height = content.getHeight();
        if (width == 0 || height == 0) return;
        if (!force && width == renderedWidth && height == renderedHeight) return;
        renderedWidth = width;
        renderedHeight = height;

        rail.removeAllViews();
        boolean narrowRail = host.getResources().getConfiguration().screenWidthDp < 900;
        rail.getLayoutParams().width = dp(narrowRail ? 150 : 200);
        for (int i = 0; i < SECTIONS.length; i++) {
            final int index = i;
            TextView tab = text(SECTIONS[i][0], narrowRail ? 15 : 17, i == section ? (palette.light ? Color.WHITE : palette.root) : palette.ink, true);
            tab.setGravity(Gravity.CENTER_VERTICAL);
            tab.setPadding(dp(18), 0, dp(12), 0);
            tab.setMaxLines(1);
            tab.setBackground(i == section ? LauncherTheme.rounded(host, palette.accent, 16, 0, 0) : LauncherTheme.dockItem(host, palette));
            tab.setOnClickListener(v -> { section = index; page = 0; render(true); });
            tab.setTag("settings_section_" + i);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
            size.bottomMargin = dp(6);
            rail.addView(tab, size);
        }

        rows.clear();
        grid.removeAllViews();
        String[] spec = SECTIONS[section];
        int count = spec.length - 1;
        int columns = width / density >= 700 ? 2 : 1;
        int fits = Math.max(2, height / dp(70));
        int perPage = fits * columns;
        int pages = (count + perPage - 1) / perPage;
        if (page >= pages) page = 0;
        int first = page * perPage;
        // Spread this page's rows evenly over the columns, so neither stands empty.
        int onPage = Math.min(perPage, count - first);
        int perColumn = (onPage + columns - 1) / columns;
        // Taller rows when a section is short, so the page isn't half empty (68–110 dp).
        rowHeight = Math.max(dp(68), Math.min(dp(110), height / Math.max(1, perColumn) - dp(2)));
        for (int c = 0; c < columns; c++) {
            LinearLayout column = column();
            int from = first + c * perColumn, to = Math.min(count, from + perColumn);
            if (from < to) {
                LinearLayout card = column();
                card.setBackground(LauncherTheme.rounded(host, palette.light ? 0xffffffff : 0xff161d25, 20, 0, 0));
                for (int i = from; i < to; i++) addRow(card, spec[i + 1], i < to - 1);
                column.addView(card);
            }
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (c < columns - 1) size.setMarginEnd(dp(16));
            grid.addView(column, size);
        }
        pager.setVisibility(pages > 1 ? View.VISIBLE : View.GONE);
        pager.setText("Page " + (page + 1) + " of " + pages + "  ›");
        pager.setContentDescription("Next page of " + spec[0] + " settings");
        refresh();
    }

    private void addRow(LinearLayout card, String id, boolean divider) {
        LinearLayout row = new LinearLayout(host);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(10), dp(16), dp(10));
        row.setMinimumHeight(rowHeight);
        row.setTag("setting_" + id);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.light ? 0x18142e38 : 0x22ffffff), null,
                new ColorDrawable(Color.WHITE)));
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout texts = column();
        TextView name = text(host.settingTitle(id), rowHeight > dp(90) ? 18 : 16, palette.ink, true);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView value = text("", rowHeight > dp(90) ? 14 : 13, palette.muted, false);
        value.setMaxLines(rowHeight > dp(90) ? 2 : 1);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        texts.addView(value);
        // The plain-language line only where the row is tall enough not to crowd the value.
        if (rowHeight >= dp(84)) {
            String text = host.settingHint(id);
            if (!text.isEmpty()) {
                TextView hint = text(text, 12, palette.muted, false);
                hint.setMaxLines(1);
                hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
                hint.setAlpha(.8f);
                texts.addView(hint);
            }
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(text("›", 24, palette.muted, false));
        row.setOnClickListener(v -> host.settingAction(id, this));
        card.addView(row);
        if (divider) {
            View line = new View(host);
            line.setBackgroundColor(palette.light ? 0x10142e38 : 0x12ffffff);
            LinearLayout.LayoutParams lineSize = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
            lineSize.leftMargin = dp(20);
            card.addView(line, lineSize);
        }
        rows.add(new TextView[]{value, name});
        value.setTag(id);
    }

    void refresh() {
        for (TextView[] row : rows) {
            String value = host.settingValue((String) row[0].getTag());
            row[0].setText(value);
            row[0].setVisibility(value == null || value.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(host);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private TextView text(String value, int size, int color, boolean medium) {
        TextView view = new TextView(host);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }
}
