package com.softiva.oabllink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

/** The JSON of hello, now and samples, and reading samples back the way the box will. */
public class ProtocolTest {
    @Test public void helloListsTheSignalsAndTheProbe() {
        JSONObject hello = Protocol.hello("0.1.0", "BOX", "10", "install-abc", new SimulatedCar(0));
        assertEquals(Protocol.SCHEMA, hello.getInt("schema"));
        assertEquals("simulated", hello.getString("probe"));
        boolean sawSoc = false;
        for (int i = 0; i < hello.getJSONArray("signals").length(); i++) {
            JSONObject entry = hello.getJSONArray("signals").getJSONObject(i);
            if (entry.getString("key").equals("soc")) {
                sawSoc = true;
                assertEquals("%", entry.getString("unit"));
                assertEquals(1, entry.getInt("tier"));
            }
        }
        assertTrue("soc is advertised", sawSoc);
    }

    @Test public void nowGivesEachSignalAValueAndTime() {
        SampleBuffer buffer = new SampleBuffer();
        buffer.record(new Reading(500).set(Signal.SOC, 62).set(Signal.SPEED, 0));
        buffer.record(new Reading(900).set(Signal.SOC, 61.5));
        JSONObject now = Protocol.now(buffer);
        assertEquals(900, now.getLong("t")); // the newest time across signals
        JSONObject soc = now.getJSONObject("values").getJSONObject("soc");
        assertEquals(61.5, soc.getDouble("v"), 0);
        assertEquals(900, soc.getLong("t"));
        assertEquals(500, now.getJSONObject("values").getJSONObject("speed").getLong("t"));
    }

    @Test public void wholeNumbersAreIntegersNotFloats() {
        SampleBuffer buffer = new SampleBuffer();
        buffer.record(new Reading(10).set(Signal.SOC, 62));
        Object value = Protocol.now(buffer).getJSONObject("values").getJSONObject("soc").get("v");
        assertTrue("62, not 62.0", value instanceof Long || value instanceof Integer);
        assertEquals(62L, ((Number) value).longValue());
    }

    @Test public void samplesRoundTripToTheBox() {
        SampleBuffer buffer = new SampleBuffer();
        buffer.record(new Reading(100).set(Signal.SOC, 62).set(Signal.ODOMETER, 3851));
        buffer.record(new Reading(200).set(Signal.SOC, 61).set(Signal.SPEED, 20));
        JSONObject page = Protocol.samples(buffer, 0, 100);
        assertEquals(2, buffer.latestSeq());
        assertEquals(buffer.latestSeq(), page.getLong("latest_seq"));
        List<Protocol.Incoming> read = Protocol.readSamples(page);
        assertEquals(2, read.size());
        assertEquals(1, read.get(0).seq);
        assertEquals(100, read.get(0).timeMs);
        assertEquals(62.0, read.get(0).values.get(Signal.SOC), 0);
        assertEquals(20.0, read.get(1).values.get(Signal.SPEED), 0);
    }

    @Test public void anUnknownSignalIsKeptAsideNotGuessed() {
        JSONObject sample = new JSONObject();
        sample.put("seq", 7);
        sample.put("t", 1234);
        sample.put("values", new JSONObject().put("soc", 60).put("made_up_signal", 1));
        Protocol.Incoming incoming = Protocol.readSample(sample);
        assertEquals(60.0, incoming.values.get(Signal.SOC), 0);
        assertFalse(incoming.values.containsKey(null));
        assertEquals(1, incoming.unknownKeys.size());
        assertEquals("made_up_signal", incoming.unknownKeys.get(0));
    }
}
