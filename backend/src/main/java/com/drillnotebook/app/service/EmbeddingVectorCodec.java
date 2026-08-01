package com.drillnotebook.app.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Encodes/decodes embedding vectors as little-endian float32 BLOBs.
 *
 * <p>Per the canonical contract, every vector (local or remote) is L2
 * normalized in Java before storage, so similarity is always a plain dot
 * product. Vectors with wrong count, NaN, infinity, or zero norm are
 * rejected with {@link IllegalArgumentException} — they must never be
 * written to {@code retrieval_embedding.vector_blob}.
 */
public final class EmbeddingVectorCodec {

    private EmbeddingVectorCodec() {}

    /**
     * Validate, L2-normalize and encode a raw provider vector.
     *
     * @param vector     raw vector as received from the provider (JSON numbers)
     * @param dimensions expected dimension count of the embedding space
     * @return {@code dimensions * 4} bytes, little-endian float32
     * @throws IllegalArgumentException on null/count mismatch/non-finite/zero norm
     */
    public static byte[] encode(List<Float> vector, int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("VECTOR_INVALID: dimensions must be positive");
        }
        if (vector == null || vector.size() != dimensions) {
            throw new IllegalArgumentException(
                    "VECTOR_COUNT_MISMATCH: expected " + dimensions
                            + " values, got " + (vector == null ? 0 : vector.size()));
        }
        double sumSquares = 0.0;
        for (Float value : vector) {
            if (value == null || !Float.isFinite(value)) {
                throw new IllegalArgumentException(
                        "VECTOR_NOT_FINITE: vector contains null/NaN/infinite value");
            }
            sumSquares += (double) value * (double) value;
        }
        double norm = Math.sqrt(sumSquares);
        if (norm == 0.0 || !Double.isFinite(norm)) {
            throw new IllegalArgumentException(
                    "VECTOR_ZERO_NORM: vector has zero or non-finite L2 norm");
        }
        ByteBuffer buf = ByteBuffer.allocate(dimensions * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (Float value : vector) {
            buf.putFloat((float) (value / norm));
        }
        return buf.array();
    }

    /**
     * Decode a stored BLOB back to a float32 array.
     *
     * @throws IllegalArgumentException when {@code blob.length != dimensions * 4};
     *                                  callers treat such rows as corrupt (skip + mark for rebuild)
     */
    public static float[] decode(byte[] blob, int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("VECTOR_INVALID: dimensions must be positive");
        }
        if (blob == null || blob.length != dimensions * 4) {
            throw new IllegalArgumentException(
                    "VECTOR_BLOB_LENGTH_MISMATCH: expected " + (dimensions * 4)
                            + " bytes, got " + (blob == null ? 0 : blob.length));
        }
        ByteBuffer buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            out[i] = buf.getFloat();
        }
        return out;
    }
}
