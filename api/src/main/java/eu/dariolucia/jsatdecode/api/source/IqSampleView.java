package eu.dariolucia.jsatdecode.api.source;

/**
 * Read-only complex samples and their stream metadata. A view supplied by a
 * leased block is valid only during its synchronous callback; a
 * {@link ComplexSampleBlock} is independently owned and can be retained.
 */
public interface IqSampleView extends IqReadAccess {
    /** Returns the stream position.
     * @return absolute index of the first complex sample */
    long sampleIndex();
    /** Returns the sample timestamp.
     * @return UTC epoch timestamp in nanoseconds */
    long timestampNanos();
    /** Returns the sampling rate.
     * @return complex samples per second */
    double sampleRate();
}
