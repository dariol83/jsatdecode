package eu.dariolucia.jsatdecode.decoder.dummy;

import eu.dariolucia.jsatdecode.api.decoder.Decoder;
import eu.dariolucia.jsatdecode.api.decoder.DecoderDescriptor;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.frame.FrameSink;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringDescriptor;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringType;
import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.api.taps.ComplexTap;
import eu.dariolucia.jsatdecode.api.taps.TapProvider;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Integration-only decoder: emits one frame per input block containing original
 * little-endian IEEE float IQ bits. No demodulation, SNR estimator or locks exist.
 * Frame timestamps retain source UTC epoch-nanosecond time; sample_index annotates recording position.
 */
public final class DummyDecoder implements Decoder {
    private volatile ComponentState state = ComponentState.CONFIGURED;
    private FrameSink sink;
    private volatile long frames;
    private volatile double signalSeconds;

    /**
     * Creates a configured integration decoder.
     */
    public DummyDecoder() {
    }

    @Override
    public DecoderDescriptor descriptor() {
        return new DecoderDescriptor("dummy", "Dummy decoder", "1", "Deterministic integration decoder, no DSP");
    }

    @Override
    public void setFrameSink(FrameSink sink) {
        if (state != ComponentState.CONFIGURED) throw new IllegalStateException("Already started");
        this.sink = Objects.requireNonNull(sink);
    }

    @Override
    public void accept(ComplexSampleBlock block) {
        if (!isRunning()) throw new IllegalStateException("Decoder not running");
        try {
            ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(block.complexSamples(), 8)).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < block.complexSamples(); i++) {
                bytes.putInt(Float.floatToRawIntBits(block.i(i)));
                bytes.putInt(Float.floatToRawIntBits(block.q(i)));
            }
            sink.accept(new AnnotatedFrame(bytes.array(), block.timestampNanos(), Map.of("sample_index", block.sampleIndex())));
            frames = Math.incrementExact(frames);
            signalSeconds += block.complexSamples() / block.sampleRate();
        } catch (RuntimeException | Error e) {
            state = ComponentState.FAILED;
            throw e;
        }
    }

    @Override
    public void start() {
        if (state != ComponentState.CONFIGURED || sink == null)
            throw new IllegalStateException("Not configured for start");
        state = ComponentState.RUNNING;
    }

    @Override
    public void stop() {
        if (state != ComponentState.CLOSED && state != ComponentState.FAILED) state = ComponentState.STOPPED;
    }

    @Override
    public void close() {
        stop();
        state = ComponentState.CLOSED;
    }

    @Override
    public ComponentState state() {
        return state;
    }

    @Override
    public MonitoringSnapshot monitoringSnapshot() {
        long accepted = frames;
        return new MonitoringSnapshot(eu.dariolucia.jsatdecode.util.UtcTime.nowNanos(), Map.of(
                DecoderStatusKeys.SIGNAL_SNR_DB, Double.NaN,
                DecoderStatusKeys.CARRIER_LOCK, "UNKNOWN", DecoderStatusKeys.CARRIER_OFFSET_HZ, Double.NaN,
                DecoderStatusKeys.SYMBOL_LOCK, "UNKNOWN", DecoderStatusKeys.SYMBOL_RATE_BAUD, Double.NaN,
                DecoderStatusKeys.FRAME_LOCK, "UNKNOWN", DecoderStatusKeys.FRAME_GOOD, accepted,
                DecoderStatusKeys.FRAME_BAD, 0L, DecoderStatusKeys.FRAME_TOTAL, accepted,
                DecoderStatusKeys.FRAME_RATE_HZ, signalSeconds > 0 ? accepted / signalSeconds : 0.0));
    }

    @Override
    public Collection<MonitoringDescriptor> monitoringDescriptors() {
        return List.of(
                new MonitoringDescriptor(DecoderStatusKeys.SIGNAL_SNR_DB, MonitoringType.DOUBLE, "dB", "Unavailable: integration decoder has no SNR estimator"),
                new MonitoringDescriptor(DecoderStatusKeys.CARRIER_LOCK, MonitoringType.STRING, "", "UNKNOWN: no carrier recovery"),
                new MonitoringDescriptor(DecoderStatusKeys.CARRIER_OFFSET_HZ, MonitoringType.DOUBLE, "Hz", "NaN: no carrier recovery"),
                new MonitoringDescriptor(DecoderStatusKeys.SYMBOL_LOCK, MonitoringType.STRING, "", "UNKNOWN: no timing recovery"),
                new MonitoringDescriptor(DecoderStatusKeys.SYMBOL_RATE_BAUD, MonitoringType.DOUBLE, "baud", "NaN: no modulation"),
                new MonitoringDescriptor(DecoderStatusKeys.FRAME_LOCK, MonitoringType.STRING, "", "UNKNOWN: generated integration records have no frame synchronizer"),
                new MonitoringDescriptor(DecoderStatusKeys.FRAME_GOOD, MonitoringType.LONG, "frames", "Accepted generated integration records; no RF/FEC validation implied"),
                new MonitoringDescriptor(DecoderStatusKeys.FRAME_BAD, MonitoringType.LONG, "frames", "Zero: no input frame validation"),
                new MonitoringDescriptor(DecoderStatusKeys.FRAME_TOTAL, MonitoringType.LONG, "frames", "Accepted generated integration records"),
                new MonitoringDescriptor(DecoderStatusKeys.FRAME_RATE_HZ, MonitoringType.DOUBLE, "Hz", "Lifetime generated records per input signal second"));
    }

    @Override
    public TapProvider taps() {
        return new TapProvider() {
            public Optional<ComplexTap> rf() {
                return Optional.empty();
            }

            public Optional<ComplexTap> baseband() {
                return Optional.empty();
            }

            public Optional<ComplexTap> symbols() {
                return Optional.empty();
            }
        };
    }
}

