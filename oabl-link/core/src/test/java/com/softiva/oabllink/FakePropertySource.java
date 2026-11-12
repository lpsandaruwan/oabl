package com.softiva.oabllink;

import java.util.HashMap;
import java.util.Map;

/** A car for the probe tests: only the properties put here are supported; everything else reads null. */
final class FakePropertySource implements PropertySource {
    private final Map<Integer, Integer> ints = new HashMap<>();
    private final Map<Integer, Float> floats = new HashMap<>();

    FakePropertySource putInt(int propertyId, int value) { ints.put(propertyId, value); return this; }
    FakePropertySource putFloat(int propertyId, float value) { floats.put(propertyId, value); return this; }

    @Override public boolean supported(int propertyId) {
        return ints.containsKey(propertyId) || floats.containsKey(propertyId);
    }

    @Override public Integer getInt(int propertyId) { return ints.get(propertyId); }

    @Override public Float getFloat(int propertyId) { return floats.get(propertyId); }
}
