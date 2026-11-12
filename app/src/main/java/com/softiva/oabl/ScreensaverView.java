package com.softiva.oabl;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * Parked screensaver. Everything sits in one column on the left (clock at the top, then
 * weather, navigation or current street, and the playing song), leaving the photo clear.
 * The column only nudges a few pixels each minute against burn-in; nothing animates.
 */
@SuppressLint("ViewConstructor")
final class ScreensaverView extends FrameLayout {
    interface Listener { void dismissed(); }

    /** What the column shows; null fields hide their row. */
    static final class Info {
        int weatherCode = -1;
        boolean weatherDay = true;
        String weather, weatherDetail;
        String navTitle, navDetail, navEta;
        String location;
        String songTitle, songSubtitle;
        Bitmap songArt;
    }

    final TopCropImageView image;
    private final View shade;
    private final LinearLayout column;
    private final WeatherIconView weatherIcon;
    private final TextView weather, weatherDetail, navTitle, navDetail, location, songTitle, songSubtitle;
    private final LinearLayout weatherRow, navRow, songRow;
    private final ImageView songArt;
    private final Listener listener;
    private final float density;
    private String imageKey;
    private int lastMinute = -1;

    ScreensaverView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        density = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xff05070a);
        setClickable(true);
        setContentDescription("Screensaver. Tap to return to Home.");
        image = new TopCropImageView(context);
        addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // Dark on the left where the text is, fading out so the photo stays visible on the right.
        shade = new View(context);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xd9000000, 0x99000000, 0x14000000}));
        addView(shade, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(56);
        column.setPadding(pad, dp(36), pad, dp(36));

        TextClock clock = new TextClock(context);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(120);
        clock.setTextColor(0xfff2f5f8);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setFontFeatureSettings("tnum");
        clock.setIncludeFontPadding(false);
        column.addView(clock);
        TextClock date = new TextClock(context);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextSize(22);
        date.setTextColor(0xffc3ccd5);
        date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        column.addView(date);

        weatherRow = row(context, 26);
        weatherIcon = new WeatherIconView(context);
        weatherRow.addView(weatherIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout weatherText = stack(context);
        weather = line(context, 22, 0xfff2f5f8, true);
        weatherDetail = line(context, 15, 0xffaeb9c4, false);
        weatherText.addView(weather);
        weatherText.addView(weatherDetail);
        weatherRow.addView(weatherText);
        column.addView(weatherRow);

        navRow = row(context, 18);
        LinearLayout navText = stack(context);
        navText.setPadding(0, 0, 0, 0);
        navTitle = line(context, 22, 0xfff2f5f8, true);
        navDetail = line(context, 15, 0xffaeb9c4, false);
        navText.addView(navTitle);
        navText.addView(navDetail);
        navRow.addView(navText);
        column.addView(navRow);

        // Where the car is parked right now.
        location = line(context, 17, 0xffdfe6ee, false);
        location.setPadding(0, dp(16), 0, 0);
        android.graphics.drawable.Drawable pin = context.getDrawable(R.drawable.ic_pin).mutate();
        pin.setTint(0xff9fd4c4);
        pin.setBounds(0, 0, dp(20), dp(20));
        location.setCompoundDrawablesRelative(pin, null, null, null);
        location.setCompoundDrawablePadding(dp(10));
        column.addView(location);

        songRow = row(context, 22);
        songArt = new ImageView(context);
        songArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        songArt.setClipToOutline(true);
        GradientDrawable artShape = new GradientDrawable();
        artShape.setCornerRadius(dp(10));
        artShape.setColor(0x33ffffff);
        songArt.setBackground(artShape);
        songRow.addView(songArt, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout songText = stack(context);
        songTitle = line(context, 18, 0xfff2f5f8, true);
        songSubtitle = line(context, 14, 0xff9fd4c4, false);
        songText.addView(songTitle);
        songText.addView(songSubtitle);
        songRow.addView(songText);
        column.addView(songRow);

        addView(column, new LayoutParams(dp(640), LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.START));
    }

    private int dp(float value) { return Math.round(value * density); }

    private LinearLayout row(Context context, int topMargin) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(topMargin), 0, 0);
        return row;
    }

    private LinearLayout stack(Context context) {
        LinearLayout stack = new LinearLayout(context);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setPadding(dp(14), 0, 0, 0);
        return stack;
    }

    private TextView line(Context context, int size, int color, boolean medium) {
        TextView text = new TextView(context);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setMaxLines(1);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (medium) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
        return text;
    }

    /** Frees the photo while the screensaver is hidden; the next show decodes it again. */
    void release() {
        imageKey = null;
        image.setImageDrawable(null);
    }

    /** Shows a photo behind the column, or plain dark when {@code uri} is null. */
    void background(android.net.Uri uri) {
        String key = String.valueOf(uri);
        if (key.equals(imageKey)) return;
        imageKey = key;
        if (uri == null) {
            image.setImageDrawable(null);
            shade.setVisibility(GONE);
            return;
        }
        shade.setVisibility(VISIBLE);
        WallpaperRenderer.load(getContext(), image, uri, false, () -> { image.setImageDrawable(null); shade.setVisibility(GONE); });
    }

    void update(Info info) {
        weatherRow.setVisibility(info.weather == null ? GONE : VISIBLE);
        if (info.weather != null) {
            weatherIcon.set(info.weatherCode, info.weatherDay, 0xffdfe6ee);
            set(weather, info.weather);
            set(weatherDetail, info.weatherDetail);
        }
        boolean navigating = info.navTitle != null;
        navRow.setVisibility(navigating ? VISIBLE : GONE);
        if (navigating) {
            set(navTitle, info.navTitle);
            set(navDetail, info.navDetail == null ? (info.navEta == null ? null : "ETA " + info.navEta)
                    : info.navEta == null ? info.navDetail : info.navDetail + "  ·  ETA " + info.navEta);
        }
        set(location, info.location);
        songRow.setVisibility(info.songTitle == null ? GONE : VISIBLE);
        if (info.songTitle != null) {
            set(songTitle, info.songTitle);
            set(songSubtitle, info.songSubtitle);
            if (info.songArt != null) {
                songArt.setImageBitmap(info.songArt);
                songArt.setPadding(0, 0, 0, 0);
            } else {
                songArt.setImageResource(R.drawable.ic_music);
                songArt.setPadding(dp(12), dp(12), dp(12), dp(12));
            }
        }
        // A tiny once-a-minute nudge against burn-in; the clock stays in the corner.
        int minute = (int) (System.currentTimeMillis() / 60_000);
        if (minute != lastMinute) {
            lastMinute = minute;
            column.setTranslationX((minute % 3 - 1) * dp(3));
            column.setTranslationY(((minute / 3) % 3 - 1) * dp(3));
        }
    }

    private static void set(TextView view, String value) {
        view.setVisibility(value == null || value.isEmpty() ? GONE : VISIBLE);
        if (value != null && !value.contentEquals(view.getText())) view.setText(value);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) listener.dismissed();
        return true;
    }
}
