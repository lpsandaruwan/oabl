package com.softiva.oabllink;

/**
 * A read-only view of the car's vehicle properties, by numeric id. The real one on the head unit is
 * backed by Dongfeng's {@code dfcar_service} (the {@code df.car} CarPropertyManager); a fake one
 * backs the JVM tests. Keeping it an interface lets {@link DongfengProbe}'s decoding — the part that
 * can get a scale or a code wrong — be tested with the exact values the survey read off the car.
 *
 * <p>A property the app has no permission for, or that the car isn't reporting, comes back as
 * {@code null} (never a guessed value), so the probe simply leaves that signal out.
 */
public interface PropertySource {
    /** Whether the car currently exposes this property to this app (permission granted and a value present). */
    boolean supported(int propertyId);

    /** The property as an int, or null when it is unsupported or unavailable right now. */
    Integer getInt(int propertyId);

    /** The property as a float, or null when it is unsupported or unavailable right now. */
    Float getFloat(int propertyId);
}
