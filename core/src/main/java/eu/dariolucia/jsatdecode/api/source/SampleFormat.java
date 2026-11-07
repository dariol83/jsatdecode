package eu.dariolucia.jsatdecode.api.source;

/**
 * In-memory sample layout, independent of file encoding or transport endianness.
 */
public enum SampleFormat {
    /**
     * Java float I0,Q0,I1,Q1,... values.
     */
    COMPLEX_FLOAT32_INTERLEAVED
}