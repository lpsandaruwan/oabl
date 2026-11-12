package com.softiva.oabl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The client state: pairing, signing with the key, folding in samples, and catching up over a gap. */
public class CarLinkTest {
    @Test public void changingHeadUnitClearsLiveValuesAndSequenceButKeepsPairing() {
        CarLink link = paired(); link.ingestSamples(pageTo2());
        link.clearReadings();
        assertTrue(link.paired()); assertEquals(0, link.lastSeq());
        assertFalse(link.has(CarSignal.SOC)); assertEquals(0, link.latestTime(CarSignal.SOC));
    }
    private static CarLinkProtocol.CarSample sample(long seq, long time, CarSignal signal, double value) {
        CarLinkProtocol.CarSample s = new CarLinkProtocol.CarSample();
        s.seq = seq;
        s.time = time;
        s.values.put(signal, value);
        return s;
    }

    private CarLink paired() {
        CarLink link = new CarLink();
        link.pair("428913");
        return link;
    }

    @Test public void signsWithThePairedKey() {
        CarLink link = paired();
        assertTrue(link.paired());
        CarLink.Headers headers = link.sign("GET", "/v1/now", 10_000_000L);
        assertEquals(10_000_000L, headers.time);
        // The signature is exactly what the shared algorithm produces for that nonce and time.
        String expected = CarLinkPairing.sign(CarLinkPairing.sharedKey("428913"), "GET", "/v1/now", "", headers.time, headers.nonce);
        assertEquals(expected, headers.signature);
    }

    @Test public void catchUpPageAdvancesTheSequence() {
        CarLink link = paired();
        CarLinkProtocol.SamplesPage page = new CarLinkProtocol.SamplesPage();
        page.samples.add(sample(1, 100, CarSignal.SOC, 62));
        page.samples.add(sample(2, 200, CarSignal.SOC, 61));
        assertEquals(2, link.ingestSamples(page));
        assertEquals(2, link.lastSeq());
        assertEquals(61.0, link.latest(CarSignal.SOC), 0);
        assertEquals(200, link.latestTime(CarSignal.SOC));
        // Re-applying an old page changes nothing.
        assertEquals(0, link.ingestSamples(page));
        assertEquals(2, link.lastSeq());
    }

    @Test public void streamInOrderAppliesAndAdvances() {
        CarLink link = paired();
        link.ingestSamples(pageTo2());
        assertEquals(CarLink.Ingest.APPLIED, link.ingestStreamSample(sample(3, 300, CarSignal.SPEED, 20)));
        assertEquals(3, link.lastSeq());
        assertEquals(CarLink.Ingest.DUPLICATE, link.ingestStreamSample(sample(3, 300, CarSignal.SPEED, 20)));
        assertEquals(3, link.lastSeq());
    }

    @Test public void aGapKeepsTheDisplayFreshButTriggersCatchUp() {
        CarLink link = paired();
        link.ingestSamples(pageTo2());
        CarLink.Ingest result = link.ingestStreamSample(sample(6, 600, CarSignal.SOC, 55));
        assertEquals(CarLink.Ingest.GAP, result);
        assertEquals("the sequence stays, so the gap is filled not skipped", 2, link.lastSeq());
        assertEquals("the newest value still shows", 55.0, link.latest(CarSignal.SOC), 0);
        assertEquals("/v1/samples?after=2&limit=500", link.catchUpTarget(500));
    }

    @Test public void nowUpdatesValuesWithoutMovingTheSequence() {
        CarLink link = paired();
        link.ingestSamples(pageTo2());
        CarLinkProtocol.Now now = new CarLinkProtocol.Now();
        now.time = 999;
        now.values.put(CarSignal.RANGE_EST, 214.0);
        now.times.put(CarSignal.RANGE_EST, 999L);
        link.ingestNow(now);
        assertEquals(214.0, link.latest(CarSignal.RANGE_EST), 0);
        assertEquals("now never advances the catch-up sequence", 2, link.lastSeq());
        assertFalse(link.has(CarSignal.POWER));
    }

    @Test public void seedingResumesCatchUpFromWhatIsStored() {
        CarLink link = paired();
        link.seedLastSeq(42); // the box already has samples up to 42 for this head-unit link
        assertEquals(42, link.lastSeq());
        assertEquals("/v1/samples?after=42&limit=500", link.catchUpTarget(500));
        // A fresh install reports seq from 1 under a new link; seeding to 0 catches up from the start.
        link.seedLastSeq(0);
        assertEquals(0, link.lastSeq());
        link.seedLastSeq(-5); // never negative
        assertEquals(0, link.lastSeq());
    }

    private static CarLinkProtocol.SamplesPage pageTo2() {
        CarLinkProtocol.SamplesPage page = new CarLinkProtocol.SamplesPage();
        page.samples.add(sample(1, 100, CarSignal.SOC, 62));
        page.samples.add(sample(2, 200, CarSignal.SOC, 61));
        return page;
    }

    @Test public void catchUpPreservesNewerSnapshotValueAndFreshness() {
        CarLink link = paired();
        CarLinkProtocol.Now now = new CarLinkProtocol.Now();
        now.time = 1000;
        now.values.put(CarSignal.SOC, 65.0);
        link.ingestNow(now, 1000); // received as stamped: the clocks agree
        assertEquals(2, link.ingestSamples(pageTo2()));
        assertEquals(2, link.lastSeq());
        assertEquals(65, link.latest(CarSignal.SOC), 0);
        assertEquals(1000, link.latestTime(CarSignal.SOC));
        now.time = 2000;
        link.ingestNow(now, 2000);
        assertEquals(2000, link.latestTime(CarSignal.SOC));
    }
}
