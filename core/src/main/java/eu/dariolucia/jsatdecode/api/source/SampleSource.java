package eu.dariolucia.jsatdecode.api.source;

import eu.dariolucia.jsatdecode.api.lifecycle.LifecycleComponent;
import eu.dariolucia.jsatdecode.api.monitoring.Monitorable;

/**
 * Source owns acquisition timing; never depends on decoder implementations or UI.
 */
public interface SampleSource extends LifecycleComponent, Monitorable {
    /**
     * Returns immutable metadata.
     *
     * @return source descriptor
     */
    SourceDescriptor descriptor();

    /**
     * Returns in-memory sample layout.
     *
     * @return sample format
     */
    SampleFormat sampleFormat();

    /**
     * Installs the consumer on the management thread before start.
     * Delivery is serial on the source worker; sink failures fail acquisition.
     *
     * @param sink non-null sample consumer
     * @throws IllegalStateException if processing has started
     */
    void setSampleSink(SampleSink sink);
}