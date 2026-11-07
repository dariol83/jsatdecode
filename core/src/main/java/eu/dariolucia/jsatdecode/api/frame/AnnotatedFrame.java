package eu.dariolucia.jsatdecode.api.frame;

import eu.dariolucia.jsatdecode.api.config.ImmutableValues;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable functional result, shareable across distributor workers.
 * Equality and hash code compare payload contents and all metadata.
 *
 * @param data           frame bytes (copied on construction and access)
 * @param timestampNanos UTC epoch-nanosecond reception timestamp
 * @param annotations    extensible immutable metadata
 */
public record AnnotatedFrame(byte[] data, long timestampNanos, Map<String, Object> annotations) {
    /**
     * Snapshots payload and annotations.
     *
     * @param data           non-null frame bytes; empty allowed
     * @param timestampNanos UTC epoch-nanosecond timestamp
     * @param annotations    non-null supported values
     */
    public AnnotatedFrame {
        data = Objects.requireNonNull(data, "data").clone();
        annotations = ImmutableValues.copy(annotations);
    }

    /**
     * Returns detached bytes.
     *
     * @return payload copy
     */
    @Override
    public byte[] data() {
        return data.clone();
    }

    /**
     * Compares payload contents and metadata.
     *
     * @param other candidate
     * @return true for equal frames
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof AnnotatedFrame f && timestampNanos == f.timestampNanos &&
                Arrays.equals(data, f.data) && annotations.equals(f.annotations);
    }

    /**
     * Hashes payload contents and metadata.
     *
     * @return content hash
     */
    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(data), timestampNanos, annotations);
    }
}