package eu.dariolucia.jsatdecode.decoder.base;

import eu.dariolucia.jsatdecode.api.decoder.Decoder;
import eu.dariolucia.jsatdecode.api.decoder.DecoderDescriptor;
import eu.dariolucia.jsatdecode.api.frame.FrameSink;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringDescriptor;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Common one-shot decoder bookkeeping. The owner serializes lifecycle mutations with accept calls;
 * concurrent observers may read the volatile state and immutable metadata. No threads or resources
 * are created. Subclasses retain DSP, failure handling, observation and stop/close semantics.
 */
public abstract class AbstractDecoder implements Decoder {
    /** Safely published lifecycle state; mutated only by the serialized decoder owner. */
    protected volatile ComponentState state = ComponentState.CONFIGURED;

    /** Functional sink, installed before start and confined to the decoder owner thereafter. */
    protected FrameSink sink;

    private final DecoderDescriptor descriptor;
    private final List<MonitoringDescriptor> metrics;
    private final String sinkStateMessage;
    private final String startStateMessage;

    /**
     * Captures immutable metadata and mission-specific lifecycle diagnostics during construction.
     *
     * @param descriptor decoder identity
     * @param metrics fixed ordered schema, copied once; no registration occurs during DSP
     * @param sinkStateMessage diagnostic when wiring a sink outside CONFIGURED
     * @param startStateMessage diagnostic when starting outside CONFIGURED or without a sink
     */
    protected AbstractDecoder(
            DecoderDescriptor descriptor,
            Collection<MonitoringDescriptor> metrics,
            String sinkStateMessage,
            String startStateMessage) {
        this.descriptor = Objects.requireNonNull(descriptor);
        this.metrics = List.copyOf(metrics);
        this.sinkStateMessage = Objects.requireNonNull(sinkStateMessage);
        this.startStateMessage = Objects.requireNonNull(startStateMessage);
    }

    @Override
    public final DecoderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public final Collection<MonitoringDescriptor> monitoringDescriptors() {
        return metrics;
    }

    @Override
    public final ComponentState state() {
        return state;
    }

    @Override
    public final void setFrameSink(FrameSink sink) {
        if (state != ComponentState.CONFIGURED) throw new IllegalStateException(sinkStateMessage);
        this.sink = Objects.requireNonNull(sink);
    }

    @Override
    public final void start() {
        if (state != ComponentState.CONFIGURED || sink == null) {
            throw new IllegalStateException(startStateMessage);
        }
        state = ComponentState.RUNNING;
    }
}
