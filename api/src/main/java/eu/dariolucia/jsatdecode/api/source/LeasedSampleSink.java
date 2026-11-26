package eu.dariolucia.jsatdecode.api.source;

/** A serial synchronous consumer of borrowed, read-only sample blocks. */
@FunctionalInterface
public interface LeasedSampleSink {
    /**
     * Borrows a block until this call returns. A consumer that uses it later
     * must retain a separate block before returning and close that block once.
     * The borrowed argument itself remains owned by the caller.
     *
     * @param block open read-only block
     */
    void accept(LeasedSampleBlock block);
}
