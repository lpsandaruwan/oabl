package com.softiva.oabllink;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pairing and request signing (energy-companion.md §3.6). OABL Link shows a 6-digit code, which is
 * typed once on the box. Both sides derive the same shared key from it, and every request after that
 * is signed with the key, so the server answers only the paired box and only on the car's private
 * Wi-Fi. The protocol is read-only: a signature authenticates a read, never a command.
 *
 * <p>Pure and side-effect free, so the whole handshake is unit-tested on the JVM. The replay guard's
 * store of seen nonces is passed in by the caller, which owns how long to remember them.
 */
public final class Pairing {
    private static final String HMAC = "HmacSHA256";
    private static final byte[] KEY_INFO = "oabl-link/v1/pair".getBytes(StandardCharsets.UTF_8);
    /** A signed request is accepted only this far from the server's clock. */
    public static final long FRESH_WINDOW_MS = 30_000;

    private final SecureRandom random = new SecureRandom();

    /** A fresh 6-digit code to show on the head unit, 000000–999999. */
    public String newCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    /** The shared key both sides derive from the code. The code alone never travels after pairing. */
    public static byte[] sharedKey(String code) {
        return hmac(code.trim().getBytes(StandardCharsets.UTF_8), KEY_INFO);
    }

    /** The signature over one request: HMAC-SHA256 of the canonical string, as lowercase hex. */
    public static String sign(byte[] key, String method, String path, String body, long timeMs, String nonce) {
        return hex(hmac(key, canonical(method, path, body, timeMs, nonce).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Accepts a signed request when the signature matches, the timestamp is within the fresh window,
     * and the nonce has not been seen. A fresh, accepted nonce is added to {@code seenNonces}.
     */
    public static boolean verify(byte[] key, String method, String path, String body, long timeMs, String nonce,
                                 String signature, long nowMs, Set<String> seenNonces) {
        if (nonce == null || signature == null) return false;
        if (Math.abs(nowMs - timeMs) > FRESH_WINDOW_MS) return false;
        if (seenNonces.contains(nonce)) return false;
        String expected = sign(key, method, path, body, timeMs, nonce);
        if (!constantTimeEquals(expected, signature)) return false;
        seenNonces.add(nonce);
        return true;
    }

    private static String canonical(String method, String path, String body, long timeMs, String nonce) {
        // One value per line, in a fixed order, so both sides build the exact same string.
        return method.toUpperCase() + "\n" + path + "\n" + timeMs + "\n" + nonce + "\n"
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

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return sb.toString();
    }

    /** Length-independent compare that doesn't short-circuit on the first differing byte. */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < x.length && i < y.length; i++) diff |= x[i] ^ y[i];
        return diff == 0;
    }
}
