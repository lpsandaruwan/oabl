package com.softiva.oabl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The box's half of OABL Link pairing and request signing (energy-companion.md §3.6). The head unit
 * shows a 6-digit code; the driver types it on the box; both sides derive the same key and every
 * request after that is signed with it. This must match OABL Link's {@code Pairing} byte for byte —
 * {@code CarLinkPairingTest} pins a known vector so the two can never drift apart. OABL Link is a
 * separate project, so the small, versioned contract is intentionally duplicated here rather than
 * shared as a dependency.
 */
final class CarLinkPairing {
    private static final String HMAC = "HmacSHA256";
    private static final byte[] KEY_INFO = "oabl-link/v1/pair".getBytes(StandardCharsets.UTF_8);

    private final SecureRandom random = new SecureRandom();

    private CarLinkPairing() { }

    /** The shared key both sides derive from the typed code. */
    static byte[] sharedKey(String code) {
        return hmac(code.trim().getBytes(StandardCharsets.UTF_8), KEY_INFO);
    }

    /** The signature over one request: HMAC-SHA256 of the canonical string, as lowercase hex. */
    static String sign(byte[] key, String method, String target, String body, long timeMs, String nonce) {
        return hex(hmac(key, canonical(method, target, body, timeMs, nonce).getBytes(StandardCharsets.UTF_8)));
    }

    private static String canonical(String method, String target, String body, long timeMs, String nonce) {
        return method.toUpperCase() + "\n" + target + "\n" + timeMs + "\n" + nonce + "\n"
                + hex(sha256(body == null ? "" : body));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return sb.toString();
    }
}
