package eu.dariolucia.jsatdecode.api.source;

import java.util.Optional;

/** Optional source capability for synchronous, borrowed IQ delivery. */
public interface LeasedSampleSource extends SampleSource {
    /**
     * Installs a borrowed-block sink on the management thread before start.
     * It replaces any previously installed legacy or leased sink.
     *
     * @param sink non-null serial synchronous consumer
     * @throws IllegalStateException if processing has started
     */
    void setLeasedSampleSink(LeasedSampleSink sink);

    /**
     * Returns management diagnostics for the pooled path, if it was started.
     * A successfully delivered leased block avoids the source's defensive
     * whole-block copy. Collect this snapshot on a management thread: the pool
     * takes its accounting lock briefly. This is not an acquisition callback.
     *
     * @return diagnostics, or empty before pooled acquisition starts
     */
    default Optional<Statistics> leasedStatistics() { return Optional.empty(); }

    /**
     * Source-side avoided-copy count and bounded pool accounting.
     * @param deliveredBlocks successfully delivered leased blocks
     * @param pool consistent pool accounting
     */
    record Statistics(long deliveredBlocks, IqBufferPool.Statistics pool) { }
}
