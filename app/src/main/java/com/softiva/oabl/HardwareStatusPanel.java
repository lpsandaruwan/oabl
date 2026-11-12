package com.softiva.oabl;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * Everything the box and the car link are doing, one row each: a state dot, what it is, what it is
 * doing now, and a button to the screen that changes it. Opened from the header's status icons and
 * the car-link dot. Fixed page, no scrolling; refreshes while it is open.
 */
final class HardwareStatusPanel {
    static final int GOOD = 0, WARN = 1, BAD = 2, OFF = 3;

    static final class Row {
        final String title, value, action;
        final int state;
        final Runnable run;
        Row(String title, String value, int state, String action, Runnable run) {
            this.title = title; this.value = value; this.state = state; this.action = action; this.run = run;
        }
    }

    interface Source { List<Row> rows(); }

    private final Activity host;
    private final LauncherTheme.Palette palette;
    private final Source source;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Dialog dialog;
    private LinearLayout grid;
    private String renderedKey;
    private int rowHeight, renderedHeight;

    HardwareStatusPanel(Activity host, LauncherTheme.Palette palette, Source source) {
        this.host = host; this.palette = palette; this.source = source;
        density = host.getResources().getDisplayMetrics().density;
    }

    private int dp(float value) { return Math.round(value * density); }

    static int stateColor(LauncherTheme.Palette palette, int state) {
        switch (state) {
            case GOOD: return palette.light ? 0xff34724f : 0xff94d6aa;
            case WARN: return palette.light ? 0xff9a7400 : 0xfff2cf5c;
            case BAD: return palette.light ? 0xffba3d35 : 0xffff9d91;
            default: return palette.muted;
        }
    }

    void show() {
        dialog = new Dialog(host, android.R.style.Theme_Material_NoActionBar);
        LinearLayout root = column();
        root.setBackgroundColor(palette.root);
        root.setPadding(dp(24), dp(12), dp(24), dp(16));

        LinearLayout header = new LinearLayout(host);
        header.setGravity(Gravity.CENTER_VERTICAL);
        MusicIconView back = new MusicIconView(host, MusicIconView.Icon.BACK, palette.ink, palette.accent, "Close status", this::close);
        back.setBackground(LauncherTheme.dockItem(host, palette));
        header.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout titles = column();
        titles.setPadding(dp(10), 0, 0, 0);
        titles.addView(text("Status", 28, palette.ink, true));
        titles.addView(text("The box, its connections and the car link · tap a row to change it", 13, palette.muted, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(header);

        grid = new LinearLayout(host);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams gridSize = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        gridSize.topMargin = dp(12);
        root.addView(grid, gridSize);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(palette.root));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            ImmersiveWindow.apply(window, root);
        }
        MapsTile.hideWhileOpen(dialog, () -> handler.removeCallbacksAndMessages(null));
        dialog.show();
        DrivingDismiss.track(dialog);
        render();
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (dialog == null || !dialog.isShowing()) return;
                render();
                handler.postDelayed(this, 2000);
            }
        }, 2000);
    }

    void close() { if (dialog != null) dialog.dismiss(); }

    private void render() {
        List<Row> rows = source.rows();
        int height = grid.getHeight();
        if (height == 0) { grid.post(this::render); return; }
        StringBuilder key = new StringBuilder().append(height).append('\n');
        for (Row row : rows) key.append(row.title).append('|').append(row.value).append('|').append(row.state).append('\n');
        if (key.toString().equals(renderedKey)) return;
        renderedKey = key.toString();
        grid.removeAllViews();
        boolean twoColumns = host.getResources().getConfiguration().screenWidthDp >= 700;
        int columns = twoColumns ? 2 : 1;
        int perColumn = (rows.size() + columns - 1) / columns;
        // Rows grow to use the page (78 to 112 dp) so a short list is not a short page.
        rowHeight = Math.max(dp(78), Math.min(dp(112), height / Math.max(1, perColumn) - dp(2)));
        for (int c = 0; c < columns; c++) {
            LinearLayout card = column();
            card.setBackground(LauncherTheme.rounded(host, palette.light ? 0xffffffff : 0xff161d25, 20, 0, 0));
            int from = c * perColumn, to = Math.min(rows.size(), from + perColumn);
            for (int i = from; i < to; i++) addRow(card, rows.get(i), i < to - 1);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (c < columns - 1) size.setMarginEnd(dp(16));
            LinearLayout holder = column();
            holder.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            grid.addView(holder, size);
        }
    }

    private void addRow(LinearLayout card, Row row, boolean divider) {
        LinearLayout line = new LinearLayout(host);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(20), dp(10), dp(16), dp(10));
        line.setMinimumHeight(rowHeight);
        line.setClickable(true);
        line.setFocusable(true);
        line.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.light ? 0x18142e38 : 0x22ffffff), null, new ColorDrawable(Color.WHITE)));
        View dot = new View(host);
        dot.setBackground(LauncherTheme.rounded(host, stateColor(palette, row.state), 7, 0, 0));
        LinearLayout.LayoutParams dotSize = new LinearLayout.LayoutParams(dp(14), dp(14));
        dotSize.setMarginEnd(dp(16));
        line.addView(dot, dotSize);
        LinearLayout texts = column();
        TextView name = text(row.title, rowHeight > dp(96) ? 19 : 17, palette.ink, true);
        name.setMaxLines(1);
        TextView value = text(row.value, rowHeight > dp(96) ? 15 : 14, palette.muted, false);
        value.setMaxLines(2);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        texts.addView(value);
        line.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (row.action != null) {
            TextView action = text(row.action, 14, palette.accent, true);
            action.setGravity(Gravity.CENTER);
            action.setPadding(dp(14), 0, dp(14), 0);
            action.setBackground(LauncherTheme.rounded(host, palette.light ? 0x10142e38 : 0x14ffffff, 18, 0, 0));
            LinearLayout.LayoutParams actionSize = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            actionSize.setMarginStart(dp(12));
            line.addView(action, actionSize);
        }
        line.setContentDescription(row.title + ". " + row.value + (row.action == null ? "" : ". " + row.action));
        line.setOnClickListener(v -> { if (row.run != null) row.run.run(); });
        card.addView(line);
        if (divider) {
            View rule = new View(host);
            rule.setBackgroundColor(palette.light ? 0x10142e38 : 0x12ffffff);
            LinearLayout.LayoutParams ruleSize = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
            ruleSize.leftMargin = dp(50);
            card.addView(rule, ruleSize);
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
