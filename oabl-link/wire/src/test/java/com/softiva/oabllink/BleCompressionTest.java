package com.softiva.oabllink;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.Test;

public class BleCompressionTest {
    private static byte[] json(int n) {
        StringBuilder text = new StringBuilder("{\"samples\":[");
        for (int i = 0; i < n; i++) text.append("{\"t\":").append(1793000000L + i * 5).append(",\"soc\":78.").append(i % 10)
                .append(",\"kw\":").append(i % 40).append(",\"v\":").append(50 + i % 7).append("},");
        return text.append("{}]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test public void aLongJsonAnswerShrinksAndComesBackIdentical() throws Exception {
        byte[] plain = json(60);
        byte[] packed = BleCompression.pack(plain);
        assertTrue("packed " + packed.length + " of " + plain.length, packed.length < plain.length / 2);
        assertArrayEquals(plain, BleCompression.unpack(packed));
    }

    @Test public void shortAnswersAreLeftAlone() throws Exception {
        byte[] plain = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        assertSame(plain, BleCompression.pack(plain));
        assertArrayEquals(plain, BleCompression.unpack(plain));
    }

    @Test public void incompressibleDataIsLeftAlone() throws Exception {
        byte[] noise = new byte[2000];
        new Random(1).nextBytes(noise);
        noise[0] = '{';
        assertSame(noise, BleCompression.pack(noise));
    }

    @Test public void jsonNeverLooksCompressed() throws Exception {
        byte[] plain = json(3);
        assertArrayEquals(plain, BleCompression.unpack(plain));
    }

    @Test public void aBombIsRefused() throws Exception {
        byte[] zeros = new byte[BleCompression.MAX_OUTPUT * 2];
        byte[] packed;
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
             java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(out)) {
            gzip.write(zeros); gzip.finish(); packed = out.toByteArray();
        }
        assertThrows(IOException.class, () -> BleCompression.unpack(packed));
    }

    @Test public void aTruncatedStreamIsAnErrorNotGarbage() throws Exception {
        byte[] packed = BleCompression.pack(json(60));
        byte[] cut = java.util.Arrays.copyOf(packed, packed.length / 2);
        assertThrows(IOException.class, () -> BleCompression.unpack(cut));
    }
}
