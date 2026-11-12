package com.softiva.oabllink;

import static org.junit.Assert.*;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Direct car ranges remain distinct, sparse, and compatible with schema-1 clients. */
public class RangeExtensionTest {
    @Test public void surveyedRemainingRangesKeepTheirOwnNamesAndKilometres() {
        FakePropertySource source = new FakePropertySource()
                .putInt(DongfengProbe.RANGE_ACTUAL, 213)
                .putInt(DongfengProbe.RANGE_STANDARD, 273)
                .putInt(DongfengProbe.RANGE_NEDC, 244);
        Reading reading = new DongfengProbe(source).read(1234);
        assertEquals(213, reading.get(Signal.RANGE_EST), 0);
        assertEquals(273, reading.get(Signal.RANGE_STANDARD), 0);
        assertEquals(244, reading.get(Signal.RANGE_NEDC), 0);
        assertFalse(reading.has(Signal.SOC)); // no entered/full-charge rating is needed
    }

    @Test public void directRangesAcceptZeroAndPlausibleBoundsButOmitUnavailableValues() {
        for (int km : new int[]{0, 1, 255, 1999}) {
            Reading reading = ranges(km).read(1);
            for (Signal signal : directRanges()) assertEquals(signal.key, km, reading.get(signal), 0);
        }
        for (int invalid : new int[]{-1, 2000, DongfengProbe.INVALID_12BIT,
                DongfengProbe.INVALID_WORD, Integer.MAX_VALUE}) {
            Reading reading = ranges(invalid).read(1);
            for (Signal signal : directRanges()) assertFalse(signal.key + " " + invalid, reading.has(signal));
        }
        Reading absent = new DongfengProbe(new FakePropertySource()).read(1);
        for (Signal signal : directRanges()) assertFalse(signal.key, absent.has(signal));
    }

    @Test public void availableAndHelloAdvertiseOnlyThePropertiesThisProbeCanRead() {
        DongfengProbe probe = new DongfengProbe(new FakePropertySource()
                .putInt(DongfengProbe.RANGE_STANDARD, 273).putInt(DongfengProbe.RANGE_NEDC, 244));
        List<Signal> available = probe.available();
        assertEquals(2, available.size());
        assertTrue(available.contains(Signal.RANGE_STANDARD));
        assertTrue(available.contains(Signal.RANGE_NEDC));
        JSONObject hello = Protocol.hello("test", "test", "test", "test", probe);
        JSONArray signals = hello.getJSONArray("signals");
        assertTrue(advertised(signals, "range_standard"));
        assertTrue(advertised(signals, "range_nedc"));
        assertFalse(advertised(signals, "range_est"));
        assertFalse(advertised(signals, "range_cltc"));
        assertFalse(advertised(signals, "range_wltp"));
        assertFalse(advertised(signals, "range_display_raw"));
        assertFalse(advertised(Protocol.hello("test", "test", "test", "test",
                new DongfengProbe(new FakePropertySource())).getJSONArray("signals"), "range_nedc"));
    }

    @Test public void increasedRangePropertiesAreNeverExposedAsCycleRemainingRanges() {
        // Exact property names in the vendor catalogue say INCRESE/INC, rather than remaining range.
        FakePropertySource gains = new FakePropertySource().putInt(0x2140301d, 40)
                .putInt(0x2140301e, 50).putInt(0x214024fc, 30).putInt(0x21402a0d, 20);
        DongfengProbe probe = new DongfengProbe(gains);
        assertTrue(probe.available().isEmpty());
        assertTrue(probe.read(1).isEmpty());
    }

    @Test public void optionalDirectRangeKeysRoundTripWithoutRenamingOrSynthesizing() {
        SampleBuffer buffer = new SampleBuffer();
        buffer.record(new Reading(100).set(Signal.RANGE_STANDARD, 273).set(Signal.RANGE_NEDC, 244));
        JSONObject values = Protocol.now(buffer).getJSONObject("values");
        assertEquals(273, values.getJSONObject("range_standard").getInt("v"));
        assertEquals(100, values.getJSONObject("range_nedc").getLong("t"));
        assertFalse(values.has("range_est"));
        Protocol.Incoming sample = Protocol.readSamples(Protocol.samples(buffer, 0, 100)).get(0);
        assertEquals(273, sample.values.get(Signal.RANGE_STANDARD), 0);
        assertEquals(244, sample.values.get(Signal.RANGE_NEDC), 0);
        assertTrue(sample.unknownKeys.isEmpty());
        for (Signal signal : new Signal[]{Signal.RANGE_STANDARD, Signal.RANGE_NEDC}) assertEquals("km", signal.unit);
        assertNull(Signal.byKey("range_cltc"));
        assertNull(Signal.byKey("range_wltp"));
    }

    @Test public void oldSamplesAndNullOptionalValuesStillReadAsSparseSchemaOneData() {
        Protocol.Incoming old = Protocol.readSample(new JSONObject()
                .put("seq", 7).put("t", 100).put("values", new JSONObject().put("soc", 60).put("range_est", 210)));
        assertEquals(2, old.values.size());
        assertEquals(210, old.values.get(Signal.RANGE_EST), 0);
        assertFalse(old.values.containsKey(Signal.RANGE_STANDARD));
        Protocol.Incoming optional = Protocol.readSample(new JSONObject().put("seq", 8).put("t", 200)
                .put("values", new JSONObject().put("soc", 59).put("range_nedc", JSONObject.NULL)
                        .put("range_cltc", JSONObject.NULL).put("future_cycle", 200)));
        assertEquals(1, optional.values.size()); // retired range_cltc is skipped like any unknown key
        assertEquals(59, optional.values.get(Signal.SOC), 0);
        assertTrue(optional.unknownKeys.contains("future_cycle"));
        assertEquals(1, Protocol.SCHEMA);
    }

    @Test public void rangeDisplaySettingIsReadRawOnly() {
        Reading reading = new DongfengProbe(new FakePropertySource().putInt(DongfengProbe.RANGE_DISPLAY, 3)).read(1);
        assertEquals(3, reading.get(Signal.RANGE_DISPLAY_RAW), 0);
        assertFalse(new DongfengProbe(new FakePropertySource().putInt(DongfengProbe.RANGE_DISPLAY, DongfengProbe.INVALID_BYTE)).read(1)
                .has(Signal.RANGE_DISPLAY_RAW));
    }

    private static DongfengProbe ranges(int km) {
        return new DongfengProbe(new FakePropertySource().putInt(DongfengProbe.RANGE_ACTUAL, km)
                .putInt(DongfengProbe.RANGE_STANDARD, km).putInt(DongfengProbe.RANGE_NEDC, km));
    }

    private static Signal[] directRanges() {
        return new Signal[]{Signal.RANGE_EST, Signal.RANGE_STANDARD, Signal.RANGE_NEDC};
    }

    private static boolean advertised(JSONArray signals, String key) {
        for (int i = 0; i < signals.length(); i++) {
            JSONObject signal = signals.getJSONObject(i);
            if (key.equals(signal.getString("key"))) {
                assertEquals("km", signal.getString("unit"));
                return true;
            }
        }
        return false;
    }
}
