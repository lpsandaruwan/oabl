package com.softiva.oabl;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits provider guidance text into glanceable parts: the distance to the next maneuver,
 * the instruction, the ETA and the remaining trip. Anything it cannot recognise is passed
 * through unchanged, so unfamiliar formats still read correctly.
 */
final class NavigationGuidance {
    enum Maneuver {
        UNKNOWN, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, UTURN_LEFT,
        SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN_RIGHT, ROUNDABOUT,
        FORK_LEFT, FORK_RIGHT, MERGE, DESTINATION
    }
    private static final Pattern DISTANCE = Pattern.compile(
            "(?i)(?<![\\w.,])(\\d+(?:[.,]\\d+)?)\\s?(km|m|mi|ft|yd)\\b");
    private static final Pattern ETA = Pattern.compile(
            "(?i)(?:(\\d{1,2}[:.]\\d{2}\\s?(?:[ap]\\.?m\\.?)?)\\s*ETA|ETA:?\\s*(\\d{1,2}[:.]\\d{2}\\s?(?:[ap]\\.?m\\.?)?))");
    private static final Pattern ARRIVAL = Pattern.compile(
            "(?i)\\b(arriv\\w*|destination|you have arrived|you've arrived)\\b");
    private static final Pattern ROUNDABOUT_EXIT = Pattern.compile(
            "(?i)\\b(?:take\\s+)?(?:the\\s+)?(first|second|third|fourth|fifth|sixth|\\d+(?:st|nd|rd|th))\\s+exit\\b");
    private static final Pattern LANE = Pattern.compile(
            "(?i)\\b(?:use|take|stay in|keep in)\\s+(?:the\\s+)?(?:far\\s+)?(?:left|right|middle|center)(?:[- ]hand)?\\s+lanes?\\b");
    private static final Pattern TRAFFIC_DELAY = Pattern.compile(
            "(?i)\\b(\\d+)\\s*(?:min|minute)s?\\s+(?:delay|slower)\\b");

    final String distance;
    final double meters;
    final String instruction;
    final String eta;
    final String remaining;
    final boolean arriving;
    final Maneuver maneuver;
    final int roundaboutExit;
    final String laneGuidance;
    final String trafficDelay;

    private NavigationGuidance(String distance, double meters, String instruction,
                               String eta, String remaining, boolean arriving, Maneuver maneuver,
                               int roundaboutExit, String laneGuidance, String trafficDelay) {
        this.distance = distance;
        this.meters = meters;
        this.instruction = instruction;
        this.eta = eta;
        this.remaining = remaining;
        this.arriving = arriving;
        this.maneuver = maneuver;
        this.roundaboutExit = roundaboutExit;
        this.laneGuidance = laneGuidance;
        this.trafficDelay = trafficDelay;
    }

    static NavigationGuidance read(NavigationHintParser.Text text) {
        String distance = null;
        double meters = -1;
        String instruction;
        String other = text.detail;
        Matcher match = DISTANCE.matcher(text.title);
        String source = text.title;
        if (!match.find()) {
            match = null;
            if (text.detail != null) {
                Matcher inDetail = DISTANCE.matcher(text.detail);
                if (inDetail.find()) {
                    match = inDetail;
                    source = text.detail;
                    other = text.title;
                }
            }
        }
        if (match != null) {
            distance = match.group(1) + " " + match.group(2).toLowerCase(Locale.ROOT);
            meters = toMeters(match.group(1), match.group(2));
            String rest = tidy(source.substring(0, match.start()) + " " + source.substring(match.end()));
            // "300 m" + "Turn left onto Main St" → the detail; Waze-style "Turn right" +
            // "in 300 m to Main St" → "Turn right to Main St".
            boolean distanceInTitle = source.equals(text.title);
            instruction = distanceInTitle ? join(rest, other) : join(other, rest);
            if (instruction != null) instruction = capitalise(instruction);
        } else {
            instruction = text.detail == null ? text.title : text.title + " · " + text.detail;
        }
        String eta = null;
        String remaining = text.trip;
        if (text.trip != null) {
            Matcher etaMatch = ETA.matcher(text.trip);
            if (etaMatch.find()) {
                eta = (etaMatch.group(1) != null ? etaMatch.group(1) : etaMatch.group(2)).trim();
                remaining = tidy(text.trip.substring(0, etaMatch.start()) + " " + text.trip.substring(etaMatch.end()));
                if (remaining.isEmpty()) remaining = null;
            }
        }
        boolean arriving = ARRIVAL.matcher(text.title).find()
                || (text.detail != null && ARRIVAL.matcher(text.detail).find());
        Maneuver maneuver = classify(instruction, arriving);
        return new NavigationGuidance(distance, meters, instruction, eta, remaining, arriving,
                maneuver, roundaboutExit(instruction), match(LANE, instruction), match(TRAFFIC_DELAY, text.trip));
    }

    /** Route distance remaining, not the distance to the next turn. */
    double remainingKilometers() {
        if (remaining == null) return Double.NaN;
        Matcher match = DISTANCE.matcher(remaining);
        return match.find() ? toMeters(match.group(1), match.group(2)) / 1000.0 : Double.NaN;
    }

    private static double toMeters(String number, String unit) {
        double value;
        try {
            value = Double.parseDouble(number.replace(',', '.'));
        } catch (NumberFormatException error) {
            return -1;
        }
        switch (unit.toLowerCase(Locale.ROOT)) {
            case "km": return value * 1000;
            case "mi": return value * 1609.34;
            case "ft": return value * 0.3048;
            case "yd": return value * 0.9144;
            default: return value;
        }
    }

    private static String tidy(String value) {
        String text = value.replaceAll("(?i)^\\s*in\\b", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("^[\\s·,:\\-]+|[\\s·,:\\-]+$", "")
                .replaceAll("\\s*·\\s*·\\s*", " · ")
                .trim();
        return text;
    }

    private static String join(String first, String second) {
        if (first == null || first.isEmpty()) return second == null || second.isEmpty() ? null : second;
        if (second == null || second.isEmpty()) return first;
        // A lowercase continuation ("to Main St") reads as one phrase; otherwise separate.
        return first + (Character.isLowerCase(second.charAt(0)) ? " " : " · ") + second;
    }

    private static String capitalise(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static Maneuver classify(String instruction, boolean arriving) {
        if (arriving) return Maneuver.DESTINATION;
        if (instruction == null) return Maneuver.UNKNOWN;
        String value = instruction.toLowerCase(Locale.ROOT).replace('-', ' ');
        if (value.contains("roundabout") || value.contains("traffic circle")) return Maneuver.ROUNDABOUT;
        if (value.contains("u turn") || value.contains("make a u"))
            return value.contains("right") ? Maneuver.UTURN_RIGHT : Maneuver.UTURN_LEFT;
        if (value.contains("fork")) return value.contains("left") ? Maneuver.FORK_LEFT
                : value.contains("right") ? Maneuver.FORK_RIGHT : Maneuver.MERGE;
        if (value.contains("merge")) return Maneuver.MERGE;
        if (value.contains("sharp left")) return Maneuver.SHARP_LEFT;
        if (value.contains("sharp right")) return Maneuver.SHARP_RIGHT;
        if (value.contains("slight left") || value.contains("bear left") || value.contains("keep left")) return Maneuver.SLIGHT_LEFT;
        if (value.contains("slight right") || value.contains("bear right") || value.contains("keep right")) return Maneuver.SLIGHT_RIGHT;
        if (value.contains("left")) return Maneuver.LEFT;
        if (value.contains("right")) return Maneuver.RIGHT;
        if (value.contains("continue") || value.contains("straight") || value.contains("head ")) return Maneuver.STRAIGHT;
        return Maneuver.UNKNOWN;
    }

    private static int roundaboutExit(String instruction) {
        if (instruction == null) return 0;
        Matcher match = ROUNDABOUT_EXIT.matcher(instruction);
        if (!match.find()) return 0;
        String value = match.group(1).toLowerCase(Locale.ROOT);
        switch (value) {
            case "first": return 1;
            case "second": return 2;
            case "third": return 3;
            case "fourth": return 4;
            case "fifth": return 5;
            case "sixth": return 6;
            default:
                try { return Integer.parseInt(value.replaceAll("\\D", "")); }
                catch (NumberFormatException invalid) { return 0; }
        }
    }

    private static String match(Pattern pattern, String text) {
        if (text == null) return null;
        Matcher match = pattern.matcher(text);
        return match.find() ? capitalise(match.group()) : null;
    }
}
