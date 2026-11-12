package com.softiva.oabllink;

import org.junit.Test;
import static org.junit.Assert.*;

public class BlePeerPolicyTest {
    @Test public void aPeerStillProvingTheCodeGivesWayAtOnce() {
        assertTrue(BlePeerPolicy.replaceable(false, 1000, 1000));
    }
    @Test public void aWorkingAuthenticatedReaderKeepsTheLink() {
        assertFalse(BlePeerPolicy.replaceable(true, 1000, 1500));
        assertFalse(BlePeerPolicy.replaceable(true, 1000, 1000 + BlePeerPolicy.SILENT_MS - 1));
    }
    @Test public void aSilentAuthenticatedReaderGivesWay() {
        assertTrue(BlePeerPolicy.replaceable(true, 1000, 1000 + BlePeerPolicy.SILENT_MS));
        assertTrue(BlePeerPolicy.replaceable(true, 0, 60_000));
    }
}
