package com.softiva.oabllink;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.agreement.jpake.*;

/** BLE v3: J-PAKE with explicit mutual key confirmation, then the encrypted record session.
 * Native phone/audio bonding is not required. No records or RPCs are accepted before BOTH
 * the password-authenticated exchange and the fresh encrypted-session handshake complete.
 * J-PAKE is Bouncy Castle's NIST 3072/SHA-256 implementation, not a custom PAKE primitive.
 * Each object is single-use; callers rate-limit failures across connections.
 */
public final class BlePakeSession {
    public static final int VERSION = 3;
    private static final int MAX_HANDSHAKE = 4096;
    private final boolean server;
    private final String self, peer;
    private JPAKEParticipant participant;
    private BigInteger material;
    private final MessageDigest transcript;
    private BleSecureSession records;
    private int stage;
    private boolean failed;

    public BlePakeSession(boolean server, byte[] passwordKey) {
        if (passwordKey == null || passwordKey.length != 32) throw new IllegalArgumentException("32-byte code key required");
        this.server = server;
        self = server ? "oabl-head-unit/v3" : "oabl-ai-box/v3";
        peer = server ? "oabl-ai-box/v3" : "oabl-head-unit/v3";
        char[] password = new char[64];
        char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < passwordKey.length; i++) {
            password[i * 2] = hex[(passwordKey[i] >>> 4) & 15];
            password[i * 2 + 1] = hex[passwordKey[i] & 15];
        }
        try {
            participant = new JPAKEParticipant(self, password);
            transcript = MessageDigest.getInstance("SHA-256");
        } catch (Exception error) { throw new IllegalStateException("PAKE unavailable", error); }
        finally { Arrays.fill(password, '\0'); }
    }

    public boolean authenticated() { return !failed && stage == 4 && records != null && records.authenticated(); }
    public byte[] begin() {
        require(!server && stage == 0, "handshake state");
        try {
            byte[] message = round1(participant.createRound1PayloadToSend());
            transcript.update(message); stage = 1; return message;
        } catch (Exception error) { throw reject(error); }
    }

    /** One serialized request/reply exchange. Returns the next outbound message or null. */
    public byte[] handshake(byte[] message) {
        try {
            require(!failed && !authenticated() && message != null && message.length >= 2
                    && message.length <= MAX_HANDSHAKE && message[0] == VERSION, "BLE v3 required");
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(message, 2, message.length - 2));
            int type = message[1] & 255;
            if (server && stage == 0 && type == 1) {
                byte[] answer = round1(participant.createRound1PayloadToSend());
                participant.validateRound1PayloadReceived(readRound1(input)); end(input);
                transcript.update(message); transcript.update(answer); stage = 1; return answer;
            }
            if (!server && stage == 1 && type == 1) {
                participant.validateRound1PayloadReceived(readRound1(input)); end(input);
                byte[] answer = round2(participant.createRound2PayloadToSend());
                transcript.update(message); transcript.update(answer); stage = 2; return answer;
            }
            if (server && stage == 1 && type == 2) {
                byte[] answer = round2(participant.createRound2PayloadToSend());
                participant.validateRound2PayloadReceived(readRound2(input)); end(input);
                material = participant.calculateKeyingMaterial();
                transcript.update(message); transcript.update(answer); stage = 2; return answer;
            }
            if (!server && stage == 2 && type == 2) {
                participant.validateRound2PayloadReceived(readRound2(input)); end(input);
                material = participant.calculateKeyingMaterial();
                transcript.update(message); stage = 3;
                return round3(participant.createRound3PayloadToSend(material));
            }
            if (server && stage == 2 && type == 3) {
                byte[] answer = round3(participant.createRound3PayloadToSend(material));
                participant.validateRound3PayloadReceived(readRound3(input), material); end(input);
                createRecords(); stage = 3; return answer;
            }
            if (!server && stage == 3 && type == 3) {
                participant.validateRound3PayloadReceived(readRound3(input), material); end(input);
                createRecords(); stage = 4; return envelope(records.begin());
            }
            if (records != null && (stage == 3 || stage == 4) && type == 4) {
                byte[] answer = records.handshake(Arrays.copyOfRange(message, 2, message.length));
                if (records.authenticated()) stage = 4;
                return answer == null ? null : envelope(answer);
            }
            throw new SecurityException("unexpected BLE handshake message");
        } catch (Exception error) { throw reject(error); }
    }

    private void createRecords() throws Exception {
        // HKDF-SHA256: fixed protocol salt, password-authenticated high-entropy material,
        // transcript-bound application label. This key is never the six-digit code itself.
        byte[] secret = material.toByteArray();
        byte[] extracted = mac(MessageDigest.getInstance("SHA-256").digest(
                "oabl-ble/v3/JPAKE-NIST3072".getBytes(StandardCharsets.UTF_8)), secret);
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        info.write("oabl-ble/v3/record-key".getBytes(StandardCharsets.UTF_8)); info.write(transcript.digest()); info.write(1);
        byte[] key = mac(extracted, info.toByteArray());
        try { records = new BleSecureSession(server, key); }
        finally { Arrays.fill(secret, (byte) 0); Arrays.fill(extracted, (byte) 0); Arrays.fill(key, (byte) 0); }
        material = null; participant = null;
    }
    private static byte[] mac(byte[] key, byte[] message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(message);
    }
    /** Sequence, size and short hash of the last record sealed or opened (no plaintext, no keys). */
    public String trace() { return records == null ? "none" : records.trace(); }
    public byte[] seal(byte[] plain) {
        try {
            require(authenticated(), "Bluetooth not authenticated");
            return records.seal(plain);
        } catch (RuntimeException error) { throw reject("Bluetooth encrypted data rejected", error); }
    }
    public byte[] open(byte[] encrypted) {
        try {
            require(authenticated(), "Bluetooth not authenticated");
            return records.open(encrypted);
        } catch (BleSecureSession.RecordCorrupted damaged) { throw damaged;
        } catch (RuntimeException error) { throw reject("Bluetooth encrypted data rejected", error); }
    }
    private SecurityException reject(Exception error) {
        return reject("Bluetooth code authentication failed", error);
    }
    private SecurityException reject(String message, Exception error) {
        failed = true; participant = null; material = null; records = null;
        return new SecurityException(message, error);
    }
    private void require(boolean value, String reason) { if (failed || !value) throw new SecurityException(reason); }
    private byte[] envelope(byte[] value) {
        byte[] result = new byte[value.length + 2]; result[0] = VERSION; result[1] = 4;
        System.arraycopy(value, 0, result, 2, value.length); return result;
    }
    private DataOutputStream output(ByteArrayOutputStream bytes, int type) throws IOException {
        DataOutputStream out = new DataOutputStream(bytes); out.writeByte(VERSION); out.writeByte(type); out.writeUTF(self); return out;
    }
    private byte[] round1(JPAKERound1Payload value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = output(bytes, 1);
        number(out, value.getGx1()); number(out, value.getGx2());
        for (BigInteger n : value.getKnowledgeProofForX1()) number(out, n);
        for (BigInteger n : value.getKnowledgeProofForX2()) number(out, n);
        return bytes.toByteArray();
    }
    private byte[] round2(JPAKERound2Payload value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = output(bytes, 2);
        number(out, value.getA()); for (BigInteger n : value.getKnowledgeProofForX2s()) number(out, n); return bytes.toByteArray();
    }
    private byte[] round3(JPAKERound3Payload value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = output(bytes, 3);
        number(out, value.getMacTag()); return bytes.toByteArray();
    }
    private String identity(DataInputStream input) throws IOException {
        String id = input.readUTF(); require(peer.equals(id), "Bluetooth role mismatch"); return id;
    }
    private JPAKERound1Payload readRound1(DataInputStream input) throws IOException {
        String id = identity(input); BigInteger gx1 = number(input), gx2 = number(input);
        return new JPAKERound1Payload(id, gx1, gx2, new BigInteger[]{number(input), number(input)}, new BigInteger[]{number(input), number(input)});
    }
    private JPAKERound2Payload readRound2(DataInputStream input) throws IOException {
        return new JPAKERound2Payload(identity(input), number(input), new BigInteger[]{number(input), number(input)});
    }
    private JPAKERound3Payload readRound3(DataInputStream input) throws IOException {
        return new JPAKERound3Payload(identity(input), number(input));
    }
    private static void number(DataOutputStream out, BigInteger value) throws IOException {
        byte[] bytes = value.toByteArray(); out.writeShort(bytes.length); out.write(bytes);
    }
    private BigInteger number(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort(); require(length > 0 && length <= 385 && length <= input.available(), "PAKE integer size");
        byte[] bytes = new byte[length]; input.readFully(bytes); return new BigInteger(bytes);
    }
    private static void end(DataInputStream input) throws IOException {
        if (input.available() != 0) throw new SecurityException("trailing handshake bytes");
    }
}
