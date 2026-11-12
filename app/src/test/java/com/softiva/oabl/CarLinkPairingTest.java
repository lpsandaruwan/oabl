package com.softiva.oabl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Arrays;
import org.junit.Test;

/**
 * Pins the box's signing to OABL Link's with a fixed vector. If either side's algorithm ever changes,
 * these values diverge and this test fails, so pairing can never silently break. The vector was
 * computed from the shared contract (sharedKey = HMAC-SHA256(code, "oabl-link/v1/pair");
 * sign = HMAC-SHA256(sharedKey, "METHOD\ntarget\ntime\nnonce\nsha256(body)")).
 */
public class CarLinkPairingTest {
    @Test public void sharedKeyMatchesTheLinkVector() {
        assertEquals("543b65ce258c07bcf7becd7456b38cd88ff079c8f99277163ede499818321806",
                CarLinkPairing.hex(CarLinkPairing.sharedKey("428913")));
    }

    @Test public void signatureMatchesTheLinkVector() {
        byte[] key = CarLinkPairing.sharedKey("428913");
        assertEquals("d07c45f06c3537a291a88f8b259c3601a310879e7a48fbb53fda14da75ec74b5",
                CarLinkPairing.sign(key, "GET", "/v1/now", "", 10_000_000L, "n-1"));
    }

    @Test public void differentCodesDeriveDifferentKeys() {
        assertFalse(Arrays.equals(CarLinkPairing.sharedKey("428913"), CarLinkPairing.sharedKey("000000")));
    }

    @Test public void theMethodAndTargetArePartOfTheSignature() {
        byte[] key = CarLinkPairing.sharedKey("428913");
        String now = CarLinkPairing.sign(key, "GET", "/v1/now", "", 1, "n");
        String samples = CarLinkPairing.sign(key, "GET", "/v1/samples?after=3", "", 1, "n");
        assertFalse("a different target signs differently", now.equals(samples));
    }
}
