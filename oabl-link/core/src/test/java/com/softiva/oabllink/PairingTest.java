package com.softiva.oabllink;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** The 6-digit pairing code, the shared key both sides derive, and request signing with replay and freshness. */
public class PairingTest {
    @Test public void codeIsSixDigits() {
        Pairing pairing = new Pairing();
        for (int i = 0; i < 50; i++) {
            String code = pairing.newCode();
            assertTrue(code, code.matches("\\d{6}"));
        }
    }

    @Test public void bothSidesDeriveTheSameKeyFromTheCode() {
        assertArrayEquals(Pairing.sharedKey("123456"), Pairing.sharedKey("123456"));
        assertFalse(java.util.Arrays.equals(Pairing.sharedKey("123456"), Pairing.sharedKey("654321")));
        assertTrue(Pairing.sharedKey("000001").length == 32); // HMAC-SHA256
    }

    @Test public void aSignedRequestVerifies() {
        byte[] key = Pairing.sharedKey("428913");
        long t = 10_000_000L;
        String signature = Pairing.sign(key, "GET", "/v1/samples?after=12", "", t, "n-1");
        assertTrue(Pairing.verify(key, "GET", "/v1/samples?after=12", "", t, "n-1", signature, t + 1000, new HashSet<>()));
    }

    @Test public void tamperingWithThePathOrBodyFails() {
        byte[] key = Pairing.sharedKey("428913");
        long t = 10_000_000L;
        String signature = Pairing.sign(key, "GET", "/v1/now", "", t, "n-2");
        assertFalse("changed path", Pairing.verify(key, "GET", "/v1/samples", "", t, "n-2", signature, t, new HashSet<>()));
        assertFalse("changed body", Pairing.verify(key, "GET", "/v1/now", "x", t, "n-2", signature, t, new HashSet<>()));
        assertFalse("wrong key", Pairing.verify(Pairing.sharedKey("000000"), "GET", "/v1/now", "", t, "n-2", signature, t, new HashSet<>()));
    }

    @Test public void staleRequestsAndReplaysAreRejected() {
        byte[] key = Pairing.sharedKey("111222");
        long t = 10_000_000L;
        String signature = Pairing.sign(key, "GET", "/v1/now", "", t, "n-3");
        assertFalse("too old", Pairing.verify(key, "GET", "/v1/now", "", t, "n-3", signature, t + Pairing.FRESH_WINDOW_MS + 1, new HashSet<>()));
        Set<String> seen = new HashSet<>();
        assertTrue(Pairing.verify(key, "GET", "/v1/now", "", t, "n-3", signature, t, seen));
        assertFalse("same nonce again", Pairing.verify(key, "GET", "/v1/now", "", t, "n-3", signature, t, seen));
    }
}
