package com.softiva.oabl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** Parses the JSON OABL Link sends, including an unknown signal key, which is skipped not guessed. */
public class CarLinkProtocolTest {
    @Test public void productionAcceptsOnlyTheRealProbeAndSimulationRequiresTestMode() {
        CarLinkProtocol.Hello hello = new CarLinkProtocol.Hello();
        assertFalse(hello.supportedSource(false));
        hello.probe = "simulated";
        assertFalse(hello.supportedSource(false)); assertTrue(hello.supportedSource(true));
        hello.probe = "dongfeng-dfcar";
        assertTrue(hello.supportedSource(false)); assertTrue(hello.supportedSource(true));
        hello.probe = "unknown"; assertFalse(hello.supportedSource(true));
    }
    @Test public void helloReportsSchemaProbeAndKnownSignals() throws Exception {
        JSONObject json = new JSONObject("{\"schema\":1,\"probe\":\"dongfeng-dfcar\",\"link\":\"abc123\",\"model\":\"BOX\",\"android\":\"10\","
                + "\"signals\":[{\"key\":\"soc\",\"unit\":\"%\"},{\"key\":\"odometer\",\"unit\":\"km\"},{\"key\":\"made_up\",\"unit\":\"\"}]}");
        CarLinkProtocol.Hello hello = CarLinkProtocol.parseHello(json);
        assertTrue(hello.compatible());
        assertEquals("dongfeng-dfcar", hello.probe);
        assertEquals("the head unit install id", "abc123", hello.link);
        assertTrue(hello.signals.contains(CarSignal.SOC));
        assertTrue(hello.signals.contains(CarSignal.ODOMETER));
        assertFalse("an unknown signal is not listed", hello.signals.contains(null));
        assertEquals("two known signals, the unknown one skipped", 2, hello.signals.size());
    }

    @Test public void nowGivesTheLatestValueAndTimePerSignal() throws Exception {
        JSONObject json = new JSONObject("{\"t\":900,\"values\":{\"soc\":{\"v\":61,\"t\":900},\"speed\":{\"v\":0,\"t\":500},"
                + "\"made_up\":{\"v\":1,\"t\":900}}}");
        CarLinkProtocol.Now now = CarLinkProtocol.parseNow(json);
        assertEquals(900, now.time);
        assertEquals(61.0, now.values.get(CarSignal.SOC), 0);
        assertEquals(900, (long) now.times.get(CarSignal.SOC));
        assertEquals(500, (long) now.times.get(CarSignal.SPEED));
        assertFalse(now.values.containsKey(null));
    }

    @Test public void samplesPageParsesInOrderWithTheLatestSeq() throws Exception {
        JSONObject json = new JSONObject("{\"after\":0,\"latest_seq\":2,\"samples\":["
                + "{\"seq\":1,\"t\":100,\"values\":{\"soc\":62,\"odometer\":3851}},"
                + "{\"seq\":2,\"t\":200,\"values\":{\"soc\":61,\"speed\":20}}]}");
        CarLinkProtocol.SamplesPage page = CarLinkProtocol.parseSamples(json);
        assertEquals(2, page.latestSeq);
        assertEquals(2, page.samples.size());
        assertEquals(1, page.samples.get(0).seq);
        assertEquals(3851.0, page.samples.get(0).values.get(CarSignal.ODOMETER), 0);
        assertEquals(20.0, page.samples.get(1).values.get(CarSignal.SPEED), 0);
    }

    @Test public void aStreamSampleParsesTheSameShape() throws Exception {
        CarLinkProtocol.CarSample sample = CarLinkProtocol.parseSample(
                new JSONObject("{\"seq\":7,\"t\":1234,\"values\":{\"soc\":60,\"charging\":1}}"));
        assertEquals(7, sample.seq);
        assertEquals(60.0, sample.values.get(CarSignal.SOC), 0);
        assertEquals(1.0, sample.values.get(CarSignal.CHARGING), 0);
    }

    @Test public void valuesEncodeAndParseBackForStorage() {
        CarLinkProtocol.CarSample sample = new CarLinkProtocol.CarSample();
        sample.values.put(CarSignal.SOC, 62.0);
        sample.values.put(CarSignal.TRIP_KWH100, 14.1);
        sample.values.put(CarSignal.CHARGING, 1.0);
        String json = CarLinkProtocol.encodeValues(sample);
        assertTrue("whole numbers stay integers", json.contains("\"soc\":62") && !json.contains("62.0"));
        java.util.Map<CarSignal, Double> back = CarLinkProtocol.parseValues(json);
        assertEquals(62.0, back.get(CarSignal.SOC), 0);
        assertEquals(14.1, back.get(CarSignal.TRIP_KWH100), 1e-9);
        assertEquals(1.0, back.get(CarSignal.CHARGING), 0);
        assertTrue("a malformed row yields nothing, never a crash", CarLinkProtocol.parseValues("not json").isEmpty());
    }

    @Test public void directRangeHelloIsAdditiveAndAnOldSchemaOneHelloStillWorks() throws Exception {
        CarLinkProtocol.Hello old = CarLinkProtocol.parseHello(new JSONObject()
                .put("schema", 1).put("probe", "dongfeng-dfcar")
                .put("signals", new org.json.JSONArray().put(new JSONObject().put("key", "range_est"))));
        assertTrue(old.compatible());
        assertEquals(1, old.signals.size());
        assertTrue(old.signals.contains(CarSignal.RANGE_EST));
        assertFalse(old.signals.contains(CarSignal.RANGE_STANDARD));
        assertFalse(old.signals.contains(CarSignal.RANGE_NEDC));
        CarLinkProtocol.Hello current = CarLinkProtocol.parseHello(new JSONObject()
                .put("schema", 1).put("revision", 3).put("probe", "dongfeng-dfcar")
                .put("signals", new org.json.JSONArray()
                        .put(new JSONObject().put("key", "range_standard").put("unit", "km"))
                        .put(new JSONObject().put("key", "range_nedc").put("unit", "km"))));
        assertTrue(current.compatible());
        assertEquals(2, current.signals.size());
        assertTrue(current.signals.contains(CarSignal.RANGE_STANDARD));
        assertTrue(current.signals.contains(CarSignal.RANGE_NEDC));
    }

    @Test public void nowPreservesEachDirectRangeAndItsOwnTimestampWithoutCycleAliases() throws Exception {
        CarLinkProtocol.Now now = CarLinkProtocol.parseNow(new JSONObject()
                .put("t", 1000).put("values", new JSONObject()
                        .put("range_est", new JSONObject().put("v", 213).put("t", 980))
                        .put("range_standard", new JSONObject().put("v", 273).put("t", 990))
                        .put("range_nedc", new JSONObject().put("v", 244).put("t", 1000))));
        assertEquals(3, now.values.size());
        assertEquals(213, now.values.get(CarSignal.RANGE_EST), 0);
        assertEquals(273, now.values.get(CarSignal.RANGE_STANDARD), 0);
        assertEquals(244, now.values.get(CarSignal.RANGE_NEDC), 0);
        assertEquals(980, (long) now.times.get(CarSignal.RANGE_EST));
        assertEquals(990, (long) now.times.get(CarSignal.RANGE_STANDARD));
        assertEquals(1000, (long) now.times.get(CarSignal.RANGE_NEDC));
    }

    @Test public void nowKeepsZeroRangeWhileNullAndMissingRangesStayAbsent() throws Exception {
        CarLinkProtocol.Now now = CarLinkProtocol.parseNow(new JSONObject()
                .put("t", 1000).put("values", new JSONObject()
                        .put("range_standard", new JSONObject().put("v", 0).put("t", 900))
                        .put("range_nedc", new JSONObject().put("v", JSONObject.NULL).put("t", 1000))
                        .put("range_cltc", new JSONObject().put("t", 1000))));
        assertEquals(1, now.values.size());
        assertEquals(0, now.values.get(CarSignal.RANGE_STANDARD), 0);
        assertEquals(900, (long) now.times.get(CarSignal.RANGE_STANDARD));
        for (CarSignal missing : new CarSignal[]{CarSignal.RANGE_NEDC}) {
            assertFalse(missing.key, now.values.containsKey(missing));
            assertFalse(missing.key, now.times.containsKey(missing));
        }
    }

    @Test public void directRangesSurviveSamplesAndRawStorageWithoutInventingAbsentCycles() throws Exception {
        CarLinkProtocol.CarSample sample = CarLinkProtocol.parseSample(new JSONObject()
                .put("seq", 7).put("t", 1234).put("values", new JSONObject()
                        .put("range_est", 213).put("range_standard", 273.5).put("range_nedc", 244)));
        assertEquals(7, sample.seq);
        assertEquals(1234, sample.time);
        assertEquals(3, sample.values.size());
        assertEquals(273.5, sample.values.get(CarSignal.RANGE_STANDARD), 0);
        assertEquals(244, sample.values.get(CarSignal.RANGE_NEDC), 0);
        java.util.Map<CarSignal, Double> stored = CarLinkProtocol.parseValues(CarLinkProtocol.encodeValues(sample));
        assertEquals(sample.values, stored);
    }

    @Test public void retiredCycleKeysFromOlderLinksAreSkippedAndZeroStaysDistinctFromNull() throws Exception {
        CarLinkProtocol.CarSample sample = CarLinkProtocol.parseSample(new JSONObject()
                .put("seq", 8).put("t", 2000).put("values", new JSONObject()
                        .put("range_standard", 0).put("range_nedc", JSONObject.NULL)
                        .put("range_cltc", 270).put("range_wltp", 230).put("range_display_raw", 3)));
        assertEquals(2, sample.values.size());
        assertEquals(0, sample.values.get(CarSignal.RANGE_STANDARD), 0);
        assertEquals(3, sample.values.get(CarSignal.RANGE_DISPLAY_RAW), 0);
        assertFalse(sample.values.containsKey(CarSignal.RANGE_NEDC));
        assertEquals(sample.values, CarLinkProtocol.parseValues(CarLinkProtocol.encodeValues(sample)));
    }
}
