package com.softiva.oabllink;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Version 2: fresh P-256 ECDH, code-bound mutual proofs, directional AES-256-GCM records.
 * A passive transcript does not expose an offline six-digit-code test without the ECDH secret.
 * Callers must rate-limit online failures and discard this object on every disconnect/failure.
 */
public final class BleSecureSession {
    public static final int VERSION = 2;
    /** Plaintext of the request that asks the peer to send its last sealed record again, unchanged. */
    public static final String RESEND_REQUEST = "oabl-resend";
    /** Damaged records tolerated in a row before the session is closed for good. */
    public static final int MAX_DAMAGED_IN_A_ROW = 3;
    /**
     * A record of the expected sequence whose authentication tag did not match: the bytes were
     * damaged in transit (some Bluetooth stacks do this to long transfers). The session is intact,
     * nothing was accepted, and the peer can be asked to send the same sealed record again.
     */
    public static final class RecordCorrupted extends SecurityException {
        RecordCorrupted(String message, Throwable cause) { super(message, cause); }
    }
    private final boolean server;
    private final byte[] psk, nonce = new byte[16];
    private final KeyPair ephemeral;
    private byte[] clientHello, serverHello, transcript, proofKey, txKey, rxKey, txPrefix, rxPrefix;
    private int stage;
    private long sent, received;
    private boolean failed, retryable;
    private int damagedInARow;
    private String trace = "none";
    public BleSecureSession(boolean server, byte[] psk) {
        if (psk == null || psk.length != 32) throw new IllegalArgumentException("32-byte pairing key required");
        this.server = server; this.psk = psk.clone();
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1")); ephemeral = generator.generateKeyPair();
            new SecureRandom().nextBytes(nonce);
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public boolean authenticated() { return !failed && stage == 3; }
    public byte[] begin() {
        require(!server && stage == 0, "handshake state");
        clientHello = hello(1); stage = 1; return clientHello.clone();
    }
    /** Reply to a handshake message, or null when the client's handshake completes. */
    public byte[] handshake(byte[] message) {
        try {
            require(message != null && message.length >= 2 && message[0] == VERSION, "BLE version");
            int type = message[1] & 255;
            if (server && stage == 0 && type == 1) {
                clientHello = message.clone(); serverHello = hello(2); derive(publicKey(message)); stage = 1;
                return serverHello.clone();
            }
            if (!server && stage == 1 && type == 2) {
                serverHello = message.clone(); derive(publicKey(message)); stage = 2;
                return proof(3, "client");
            }
            if (server && stage == 1 && type == 3) {
                require(MessageDigest.isEqual(message, proof(3, "client")), "Pairing code rejected"); stage = 3;
                return proof(4, "server");
            }
            if (!server && stage == 2 && type == 4) {
                require(MessageDigest.isEqual(message, proof(4, "server")), "Pairing code rejected"); stage = 3; return null;
            }
            throw new SecurityException("unexpected handshake message");
        } catch (RuntimeException failure) { failed = true; throw failure; }
    }
    private byte[] hello(int type) {
        byte[] pub = ephemeral.getPublic().getEncoded();
        return ByteBuffer.allocate(20 + pub.length).put((byte) VERSION).put((byte) type).putShort((short) pub.length).put(pub).put(nonce).array();
    }
    private PublicKey publicKey(byte[] hello) {
        require(hello.length >= 20 && hello.length <= 180, "public key size");
        int length = ByteBuffer.wrap(hello, 2, 2).getShort() & 65535;
        require(length + 20 == hello.length, "public key length");
        try {
            PublicKey pub = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Arrays.copyOfRange(hello, 4, 4 + length)));
            require(pub instanceof ECPublicKey, "EC public key required");
            ECParameterSpec actual = ((ECPublicKey) pub).getParams(), expected = ((ECPublicKey) ephemeral.getPublic()).getParams();
            require(actual.getCurve().equals(expected.getCurve()) && actual.getGenerator().equals(expected.getGenerator())
                    && actual.getOrder().equals(expected.getOrder()) && actual.getCofactor() == expected.getCofactor(), "P-256 required");
            return pub;
        } catch (GeneralSecurityException e) { throw new SecurityException("invalid public key", e); }
    }
    private void derive(PublicKey peer) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256"); hash.update(clientHello); transcript = hash.digest(serverHello);
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH"); agreement.init(ephemeral.getPrivate()); agreement.doPhase(peer, true);
            byte[] extracted = mac(psk, agreement.generateSecret());
            proofKey = expand(extracted, "proof", 32);
            txKey = expand(extracted, server ? "s2c-key" : "c2s-key", 32);
            rxKey = expand(extracted, server ? "c2s-key" : "s2c-key", 32);
            txPrefix = expand(extracted, server ? "s2c-nonce" : "c2s-nonce", 4);
            rxPrefix = expand(extracted, server ? "c2s-nonce" : "s2c-nonce", 4);
            Arrays.fill(extracted, (byte) 0);
        } catch (GeneralSecurityException e) { throw new SecurityException("ECDH failed", e); }
    }
    private byte[] expand(byte[] prk, String label, int length) {
        byte[] info = ("oabl-ble/v2/" + label).getBytes(StandardCharsets.UTF_8);
        ByteBuffer input = ByteBuffer.allocate(info.length + transcript.length + 1).put(info).put(transcript).put((byte) 1);
        return Arrays.copyOf(mac(prk, input.array()), length);
    }
    private byte[] proof(int type, String side) {
        byte[] name = side.getBytes(StandardCharsets.UTF_8);
        byte[] signed = mac(proofKey, ByteBuffer.allocate(name.length + transcript.length).put(name).put(transcript).array());
        return ByteBuffer.allocate(34).put((byte) VERSION).put((byte) type).put(signed).array();
    }
    public byte[] seal(byte[] cleartext) {
        require(authenticated() && cleartext.length <= BleFrames.MAX_BYTES - 26 && sent < Long.MAX_VALUE, "session/record size");
        long sequence = ++sent;
        byte[] header = ByteBuffer.allocate(10).put((byte) VERSION).put((byte) (server ? 6 : 5)).putLong(sequence).array();
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, txKey, txPrefix, sequence, header, cleartext);
        byte[] record = ByteBuffer.allocate(header.length + encrypted.length).put(header).put(encrypted).array();
        trace = "sent seq=" + sequence + " bytes=" + record.length + " sha=" + fingerprint(record).substring(0, 16);
        return record;
    }
    /** Sequence, size and a short hash of the last record sealed or opened, for finding where bytes change. */
    public String trace() { return trace; }
    public byte[] open(byte[] message) {
        long expected = received + 1;
        try {
            require(authenticated() && message != null && message.length >= 26 && message.length <= BleFrames.MAX_BYTES, "session/record size");
            require(message[0] == VERSION && message[1] == (server ? 5 : 6), "record direction/version");
            long sequence = ByteBuffer.wrap(message, 2, 8).getLong();
            require(sequence == received + 1 && sequence > 0, "record replay/order");
            byte[] clear;
            try { clear = crypt(Cipher.DECRYPT_MODE, rxKey, rxPrefix, sequence, Arrays.copyOf(message, 10), Arrays.copyOfRange(message, 10, message.length)); }
            catch (SecurityException damaged) {
                // Right sequence, wrong tag: tolerate a few in a row, then close for good.
                if (++damagedInARow <= MAX_DAMAGED_IN_A_ROW) retryable = true;
                throw damaged;
            }
            damagedInARow = 0;
            received = sequence;
            trace = "received seq=" + sequence + " bytes=" + message.length + " sha=" + fingerprint(message).substring(0, 16);
            return clear;
        } catch (RuntimeException failure) {
            boolean retry = retryable; retryable = false;
            if (!retry) failed = true;
            String sequence = message != null && message.length >= 10
                    ? Long.toString(ByteBuffer.wrap(message, 2, 8).getLong()) : "unavailable";
            String detail = failure.getMessage() + " · expected=" + expected
                    + " · received=" + sequence + " · bytes=" + (message == null ? 0 : message.length)
                    + " · sha256=" + fingerprint(message);
            throw retry ? new RecordCorrupted(detail, failure) : new SecurityException(detail, failure);
        }
    }
    /** Hash the encrypted record, including its header; never expose plaintext or key material. */
    private static String fingerprint(byte[] message) {
        if (message == null) return "unavailable";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(message);
            StringBuilder text = new StringBuilder(64);
            for (byte value : digest) text.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
            return text.toString();
        } catch (GeneralSecurityException unavailable) { return "unavailable"; }
    }
    private byte[] crypt(int mode, byte[] key, byte[] prefix, long seq, byte[] header, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, ByteBuffer.allocate(12).put(prefix).putLong(seq).array()));
            cipher.updateAAD(transcript); cipher.updateAAD(header); return cipher.doFinal(data);
        } catch (GeneralSecurityException e) { throw new SecurityException("record authentication failed", e); }
    }
    private static byte[] mac(byte[] key, byte[] data) {
        try { Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key, "HmacSHA256")); return m.doFinal(data); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private void require(boolean value, String message) { if (failed || !value) throw new SecurityException(message); }
}
