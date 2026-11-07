package eu.dariolucia.jsatdecode.api.decoder;

import eu.dariolucia.jsatdecode.api.frame.FrameSink;
import eu.dariolucia.jsatdecode.api.lifecycle.LifecycleComponent;
import eu.dariolucia.jsatdecode.api.monitoring.Monitorable;
import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.api.taps.TapProvider;

/**
 * Mission-neutral block decoder. Accept calls are serial on the functional worker,
 * while monitoring and tap reads may run concurrently. No UI or transport calls.
 * Every implementation exposes all {@link eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys}
 * in descriptors and snapshots. Unavailable measurements use Double.NaN; carrier,
 * symbol and frame lock remain independent. The authoritative implementation
 * contract is docs/decoder-implementation-specification.md.
 */
public interface Decoder extends LifecycleComponent, Monitorable {
    /**
     * Returns implementation metadata.
     *
     * @return immutable descriptor
     */
    DecoderDescriptor descriptor();

    /**
     * Processes samples while RUNNING; failures propagate to the runtime.
     *
     * @param samples non-null immutable block, retainable after return
     */
    void accept(ComplexSampleBlock samples);

    /**
     * Installs a bounded nonblocking frame dispatcher before start.
     *
     * @param sink non-null consumer
     * @throws IllegalStateException if processing has started
     */
    void setFrameSink(FrameSink sink);

    /**
     * Returns available observation taps.
     *
     * @return non-null provider; each tap may be absent
     */
    TapProvider taps();
}
