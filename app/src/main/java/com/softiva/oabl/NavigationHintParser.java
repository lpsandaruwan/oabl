package com.softiva.oabl;

import java.util.Locale;

/** Text-only notification rules; directions are displayed as supplied, never inferred. */
final class NavigationHintParser {
    static final class Text {
        final String title;
        final String detail;
        final String trip;

        Text(String title, String detail, String trip) {
            this.title = title;
            this.detail = detail;
            this.trip = trip;
        }
    }

    private NavigationHintParser() { }

    static boolean accepts(String source, String selectedPackage, String category,
                           boolean ongoing, boolean summary, String channel, String tag) {
        if (source == null || summary || !ongoing) {
            return false;
        }
        boolean knownProvider = "com.google.android.apps.maps".equals(source)
                || "com.waze".equals(source);
        if (!knownProvider && !source.equals(selectedPackage)) {
            return false;
        }
        if ("navigation".equals(category)) {
            return true;
        }
        // Older providers may not set CATEGORY_NAVIGATION. Only accept an
        // explicitly named navigation channel/tag, never generic Maps status,
        // location sharing, downloads, promotions, or destination suggestions.
        boolean legacyCategory = category == null || "status".equals(category)
                || "service".equals(category);
        return legacyCategory && (navigationMarker(channel) || navigationMarker(tag));
    }

    static Text parse(CharSequence title, CharSequence text, CharSequence bigText,
                      CharSequence subText, CharSequence infoText) {
        String heading = clean(title);
        String detail = clean(text);
        if (detail == null) {
            detail = clean(bigText);
        }
        if (heading == null && detail == null) {
            return null;
        }
        if (heading == null) {
            heading = detail;
            detail = null;
        } else if (heading.equals(detail)) {
            detail = null;
        }
        String trip = clean(subText);
        if (trip == null || trip.equals(heading) || trip.equals(detail)) {
            trip = clean(infoText);
        }
        if (heading.equals(trip) || (detail != null && detail.equals(trip))) {
            trip = null;
        }
        return new Text(heading, detail, trip);
    }

    private static boolean navigationMarker(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("navigation") || "nav".equals(lower)
                || lower.startsWith("nav_") || lower.startsWith("nav-");
    }

    private static String clean(CharSequence value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > 120 ? text.substring(0, 119) + "…" : text;
    }
}
