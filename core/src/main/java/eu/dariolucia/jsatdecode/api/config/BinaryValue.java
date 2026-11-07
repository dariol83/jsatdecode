package eu.dariolucia.jsatdecode.api.config;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable binary metadata, safely shareable between producers and observers.
 * Construction and array access copy bytes; equality/hash code compare contents.
 * Use this value instead of a mutable byte array in annotations or snapshots.
 *
 * @param bytes binary content, copied during construction and access
 */
public record BinaryValue(byte[] bytes) {
    /**
     * Snapshots binary content, including an empty value.
     *
     * @param bytes non-null caller-owned bytes
     */
    public BinaryValue(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    /**
     * Returns a detached copy; mutation cannot affect this value.
     *
     * @return copied bytes
     */
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * Returns length without copying or allocating.
     *
     * @return nonnegative byte count
     */
    public int size() {
        return bytes.length;
    }

    /**
     * Compares binary contents.
     *
     * @param other candidate
     * @return true for equal binary values
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof BinaryValue value && Arrays.equals(bytes, value.bytes);
    }

    /**
     * Hashes immutable binary contents.
     *
     * @return content hash
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
