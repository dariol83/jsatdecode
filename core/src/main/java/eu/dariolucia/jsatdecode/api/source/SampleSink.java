package eu.dariolucia.jsatdecode.api.source;

/**
 * Synchronous functional sample consumer, called serially by the source worker.
 */
@FunctionalInterface
public interface SampleSink {
    /**
     * Processes a block; may perform DSP but no UI, network or monitoring waits.
     *
     * @param block non-null immutable samples, retainable after return
     */
    void accept(ComplexSampleBlock block);
}