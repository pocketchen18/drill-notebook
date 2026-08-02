package com.drillnotebook.app.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbeddingVectorCodecTest {

    private static List<Float> vector512() {
        List<Float> v = new ArrayList<>(512);
        for (int i = 0; i < 512; i++) {
            v.add((i + 1) / 512.0f);
        }
        return v;
    }

    @Test
    void encode512DimVectorProducesExactly2048Bytes() {
        byte[] blob = EmbeddingVectorCodec.encode(vector512(), 512);
        assertEquals(2048, blob.length);
    }

    @Test
    void roundTripPreservesNormalizedValuesWithinFloat32Tolerance() {
        List<Float> raw = vector512();
        byte[] blob = EmbeddingVectorCodec.encode(raw, 512);
        float[] decoded = EmbeddingVectorCodec.decode(blob, 512);

        // Expected: L2-normalized raw values.
        double norm = 0;
        for (float v : raw) norm += (double) v * v;
        norm = Math.sqrt(norm);
        double decodedNorm = 0;
        for (int i = 0; i < 512; i++) {
            assertEquals(raw.get(i) / norm, decoded[i], 1e-6,
                    "component " + i);
            decodedNorm += (double) decoded[i] * decoded[i];
        }
        assertEquals(1.0, Math.sqrt(decodedNorm), 1e-5, "unit L2 norm");
    }

    @Test
    void encodeIsLittleEndian() {
        // Single-dim vector [1.0] normalizes to 1.0f = 0x3F800000.
        byte[] blob = EmbeddingVectorCodec.encode(List.of(1.0f), 1);
        assertArrayEquals(new byte[] {0x00, 0x00, (byte) 0x80, 0x3F}, blob);
    }

    @Test
    void encodeRejectsCountMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingVectorCodec.encode(vector512(), 511));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingVectorCodec.encode(null, 512));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingVectorCodec.encode(List.of(), 512));
    }

    @Test
    void encodeRejectsNonFiniteAndZeroVectors() {
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingVectorCodec.encode(List.of(1.0f, Float.NaN), 2));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingVectorCodec.encode(
                        List.of(Float.POSITIVE_INFINITY, 1.0f), 2));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingVectorCodec.encode(
                        java.util.Arrays.asList(1.0f, null), 2));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingVectorCodec.encode(
                        Collections.nCopies(512, 0.0f), 512));
    }

    @Test
    void decodeRejectsWrongBlobLength() {
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingVectorCodec.decode(new byte[2047], 512));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingVectorCodec.decode(new byte[2049], 512));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingVectorCodec.decode(null, 512));
    }
}
