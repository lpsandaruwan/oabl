package com.softiva.oabl;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Sunrise and sunset from the last known position (NOAA solar position algorithm), computed
 * offline. Used for the automatic light/dark theme and shown on the weather card.
 */
final class SunTimes {
    /** Sunrise and sunset as epoch milliseconds for the day containing {@code now}; null if polar day/night. */
    final long sunrise, sunset;

    private SunTimes(long sunrise, long sunset) {
        this.sunrise = sunrise;
        this.sunset = sunset;
    }

    /** Remembers the area (rounded to ~1 km) so the theme works before the next GPS fix. */
    static void remember(Context context, double latitude, double longitude) {
        prefs(context).edit().putFloat("lat", (float) (Math.round(latitude * 100) / 100.0))
                .putFloat("lon", (float) (Math.round(longitude * 100) / 100.0)).apply();
    }

    /** Sun times for today at the remembered area, or null when no position was ever seen. */
    static SunTimes today(Context context, long now) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.contains("lat")) return null;
        return compute(prefs.getFloat("lat", 0), prefs.getFloat("lon", 0), now, TimeZone.getDefault());
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("sun_area", Context.MODE_PRIVATE);
    }

    static SunTimes compute(double latitude, double longitude, long now, TimeZone zone) {
        Calendar day = Calendar.getInstance(zone);
        day.setTimeInMillis(now);
        int dayOfYear = day.get(Calendar.DAY_OF_YEAR);
        int daysInYear = day.getActualMaximum(Calendar.DAY_OF_YEAR);
        double gamma = 2 * Math.PI / daysInYear * (dayOfYear - 1);
        double equationOfTime = 229.18 * (0.000075 + 0.001868 * Math.cos(gamma) - 0.032077 * Math.sin(gamma)
                - 0.014615 * Math.cos(2 * gamma) - 0.040849 * Math.sin(2 * gamma));
        double declination = 0.006918 - 0.399912 * Math.cos(gamma) + 0.070257 * Math.sin(gamma)
                - 0.006758 * Math.cos(2 * gamma) + 0.000907 * Math.sin(2 * gamma)
                - 0.002697 * Math.cos(3 * gamma) + 0.00148 * Math.sin(3 * gamma);
        double lat = Math.toRadians(latitude);
        double cosHour = Math.cos(Math.toRadians(90.833)) / (Math.cos(lat) * Math.cos(declination))
                - Math.tan(lat) * Math.tan(declination);
        if (cosHour > 1 || cosHour < -1) return null;
        double hourAngle = Math.toDegrees(Math.acos(cosHour));
        // Minutes after UTC midnight.
        double riseUtc = 720 - 4 * (longitude + hourAngle) - equationOfTime;
        double setUtc = 720 - 4 * (longitude - hourAngle) - equationOfTime;
        Calendar midnight = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        midnight.clear();
        midnight.set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH));
        long base = midnight.getTimeInMillis();
        return new SunTimes(base + Math.round(riseUtc * 60_000), base + Math.round(setUtc * 60_000));
    }

    /** Light theme from 20 minutes after sunrise until 20 minutes before sunset. */
    boolean daylight(long now) {
        // West of Greenwich the sunset can fall after UTC midnight; check yesterday's window too.
        long set = sunset < sunrise ? sunset + 86_400_000L : sunset;
        for (long shift = -86_400_000L; shift <= 0; shift += 86_400_000L) {
            if (now >= sunrise + shift + 20 * 60_000L && now < set + shift - 20 * 60_000L) return true;
        }
        return false;
    }
}
