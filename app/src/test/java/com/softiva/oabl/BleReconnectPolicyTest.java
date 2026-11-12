package com.softiva.oabl;

import static org.junit.Assert.*;
import org.junit.Test;

public class BleReconnectPolicyTest {
    @Test public void unverifiedCodeKeepsRetryingButSlowsAfterRepeatedFailures() {
        assertEquals(5000, BleReconnectPolicy.retryDelayMs(false, 1));
        assertEquals(5000, BleReconnectPolicy.retryDelayMs(false, 2));
        assertEquals(30000, BleReconnectPolicy.retryDelayMs(false, 3));
        assertTrue(BleReconnectPolicy.fallBackToGatt(true, false, true));
    }

    @Test public void laterRejectedProofRetriesPromptlyAfterTheSameCodeAlreadyWorked() {
        assertEquals(2000, BleReconnectPolicy.retryDelayMs(true, 10));
        assertTrue(BleReconnectPolicy.fallBackToGatt(true, true, true));
    }

    @Test public void nativeStreamFailureFallsBackButGattFailureDoesNotLoop() {
        assertTrue(BleReconnectPolicy.fallBackToGatt(true, false, false));
        assertFalse(BleReconnectPolicy.fallBackToGatt(false, true, false));
    }

    @Test public void theStreamIsTriedAgainAfterAGattSpell() {
        assertTrue("nothing failed yet", BleReconnectPolicy.tryNativeStream(0, 5_000));
        long until = 10_000 + BleReconnectPolicy.GATT_SPELL_MS;
        assertFalse(BleReconnectPolicy.tryNativeStream(until, 10_000));
        assertFalse(BleReconnectPolicy.tryNativeStream(until, until - 1));
        assertTrue(BleReconnectPolicy.tryNativeStream(until, until));
    }
}
