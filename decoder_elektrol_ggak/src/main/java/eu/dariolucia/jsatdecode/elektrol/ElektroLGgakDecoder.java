package eu.dariolucia.jsatdecode.elektrol;

import static eu.dariolucia.jsatdecode.decoder.base.DecoderLockStates.LOCKED;
import static eu.dariolucia.jsatdecode.decoder.base.DecoderLockStates.SEARCHING;
import static eu.dariolucia.jsatdecode.decoder.base.DecoderLockStates.UNKNOWN;
import static eu.dariolucia.jsatdecode.decoder.base.DecoderLockStates.UNLOCKED;
import static eu.dariolucia.jsatdecode.decoder.base.FrameAnnotationKeys.FRAME_LENGTH;
import static eu.dariolucia.jsatdecode.decoder.base.FrameAnnotationKeys.FRAME_TYPE;
import static eu.dariolucia.jsatdecode.decoder.base.FrameAnnotationKeys.QUALITY;
import static eu.dariolucia.jsatdecode.decoder.base.FrameAnnotationKeys.RX_TIME_NS;
import static eu.dariolucia.jsatdecode.decoder.base.FrameAnnotationKeys.RX_TIME_SOURCE;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.decoder.DecoderDescriptor;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringDescriptor;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.api.taps.ComplexTap;
import eu.dariolucia.jsatdecode.api.taps.TapProvider;
import eu.dariolucia.jsatdecode.core.buffers.ComplexFloatRingBuffer;
import eu.dariolucia.jsatdecode.decoder.base.AbstractDecoder;
import eu.dariolucia.jsatdecode.decoder.base.monitoring.DecoderMetricSchema;
import eu.dariolucia.jsatdecode.decoder.base.monitoring.DecoderSnapshots;
import eu.dariolucia.jsatdecode.util.UtcTime;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One-shot Elektro-L GGAK PM/BPSK decoder based on decoder_elektrol_ggak.grc. The owner serializes accept
 * and lifecycle changes; concurrent monitoring only reads cached status and bounded taps. DSP and
 * sink delivery execute synchronously, without decoder threads, files, network I/O or UI work.
 * Every output is 220 polarity-normalized, additive-checksum-valid bytes following a 32-bit ASM.
 * Checksum octets are retained; no payload error correction is attempted.
 */
public final class ElektroLGgakDecoder extends AbstractDecoder {
    static final DecoderDescriptor DESCRIPTOR =
            new DecoderDescriptor(
                    "elektrol_ggak",
                    "Elektro-L GGAK",
                    "0.1.0",
                    "5 ksym/s PM/BPSK, CCSDS ASM, checksum-valid 220-byte GGAK payloads");

    private final ElektroLGgakSettings settings;
    private final ElektroLGgakDsp dsp;
    private final GgakDeframer deframer;
    private final TapBuffer rf, baseband, symbols;
    private final TapProvider taps;
    private long samples, expectedInput = -1, inputSample, blockTimestamp;
    private int blockOffset;
    private long lastFrameSample = -1;
    private volatile long good, bad, publishedSamples;
    private volatile String frameLock = SEARCHING;
    private volatile Throwable failure;

    /**
     * Validates the complete unprefixed decoder section and allocates bounded DSP state.
     * Optional type must equal elektrol_ggak. No processing or external resource starts here.
     *
     * @param configuration decoder-only configuration, including optional routing selector
     */
    public ElektroLGgakDecoder(Configuration configuration) {
        super(
                DESCRIPTOR,
                METRICS,
                "Frame sink wiring requires CONFIGURED",
                "Start requires CONFIGURED and a frame sink");
        settings = ElektroLGgakSettings.read(Objects.requireNonNull(configuration));
        rf = new TapBuffer(settings.tapCapacity(), settings.rate(), 8192);
        baseband = new TapBuffer(settings.tapCapacity(), settings.baseRate(), 8192);
        symbols = new TapBuffer(settings.tapCapacity(), settings.bitRate(), 2048);
        taps = new TapProvider() {
            @Override
            public Optional<ComplexTap> rf() {
                return rf.tap();
            }

            @Override
            public Optional<ComplexTap> baseband() {
                return baseband.tap();
            }

            @Override
            public Optional<ComplexTap> symbols() {
                return symbols.tap();
            }
        };
        deframer = new GgakDeframer(settings.syncSearchErrors(), settings.syncLockedErrors(), this::frame);
        dsp = new ElektroLGgakDsp(settings, new ElektroLGgakDsp.Output() {
            @Override
            public void baseband(float i, float q) {
                baseband.append(i, q);
            }

            @Override
            public void symbol(float i, float q) {
                symbols.append(i, q);
                deframer.bit(i >= 0 ? 1 : 0);
            }
        });
    }

    @Override
    public void accept(ComplexSampleBlock block) {
        if (state != ComponentState.RUNNING) throw new IllegalStateException("Decoder is not running");
        try {
            Objects.requireNonNull(block, "block");
            if (block.sampleRate() != settings.rate())
                throw new IllegalArgumentException("Input rate differs from configured sample_rate_hz=" + settings.rate());
            if (expectedInput >= 0 && block.sampleIndex() != expectedInput)
                throw new IllegalArgumentException("Discontinuous Elektro-L GGAK input sample index");
            expectedInput = Math.addExact(block.sampleIndex(), block.complexSamples());
            blockTimestamp = block.timestampNanos();
            for (int n = 0; n < block.complexSamples(); n++) {
                float i = block.i(n), q = block.q(n);
                if (!Float.isFinite(i) || !Float.isFinite(q))
                    throw new IllegalArgumentException("Non-finite Elektro-L GGAK IQ");
                blockOffset = n;
                inputSample = Math.addExact(block.sampleIndex(), n);
                samples = Math.incrementExact(samples);
                // Raw RF is exactly the original decoder input, before the configurable DC block.
                rf.append(i, q);
                dsp.sample(i, q);
                if (lastFrameSample >= 0 && samples - lastFrameSample > 3.0 * settings.rate())
                    frameLock = UNLOCKED;
            }
            flushTaps();
            publishedSamples = samples;
        } catch (RuntimeException | Error e) {
            failure = e;
            publishedSamples = samples;
            frameLock = UNLOCKED;
            state = ComponentState.FAILED;
            throw e;
        }
    }

    private void frame(byte[] payload, int asmErrors, boolean inverted) {
        // The GRC checks canonical octets after ASM removal and full polarity normalization.
        int expected = 0x0202;
        for (int n = 0; n < 218; n++) expected += Byte.toUnsignedInt(payload[n]);
        expected &= 0xffff;
        int received = (Byte.toUnsignedInt(payload[218]) << 8) | Byte.toUnsignedInt(payload[219]);
        if (expected != received) {
            long rejected = Math.incrementExact(bad);
            Math.addExact(good, rejected);
            bad = rejected;
            return;
        }
        // Timestamp the causal input sample producing the last payload symbol. Receiver/filter
        // latency is intentionally not guessed or subtracted. Preserve this block's source UTC.
        double offset = blockOffset * (1e9 / settings.rate());
        if (!Double.isFinite(offset) || offset >= Long.MAX_VALUE)
            throw new ArithmeticException("Elektro-L GGAK sample time overflow");
        long timestamp = Math.addExact(blockTimestamp, Math.round(offset));
        long accepted = Math.incrementExact(good);
        Math.addExact(accepted, bad);
        sink.accept(new AnnotatedFrame(payload, timestamp, Map.ofEntries(
                Map.entry(FRAME_TYPE, "GGAK"),
                Map.entry(QUALITY, "UNKNOWN"),
                Map.entry(FRAME_LENGTH, GgakDeframer.PAYLOAD_BYTES),
                Map.entry(RX_TIME_NS, timestamp),
                Map.entry(RX_TIME_SOURCE, "sample_clock"),
                Map.entry("asm_errors", asmErrors),
                Map.entry("cadu_length", GgakDeframer.CADU_BYTES),
                Map.entry("polarity_inverted", inverted),
                Map.entry("input_sample_index", inputSample),
                Map.entry("timestamp_reference", "last_payload_symbol_causal_input"),
                Map.entry("checksum_valid", true),
                Map.entry("checksum", received))));
        // Each complete accepted candidate counts, including repeated transmitted payloads.
        // A throwing sink never increments the lifetime accepted counter.
        good = accepted;
        lastFrameSample = samples;
        frameLock = LOCKED;
    }

    private void flushTaps() {
        rf.flush();
        baseband.flush();
        symbols.flush();
    }

    @Override
    public void stop() {
        if (state == ComponentState.CLOSED) return;
        flushTaps();
        publishedSamples = samples;
        frameLock = UNLOCKED;
        if (state != ComponentState.FAILED) state = ComponentState.STOPPED;
    }

    @Override
    public void close() {
        stop();
        state = ComponentState.CLOSED;
    }

    /**
     * Returns the first functional input, DSP or sink failure, retained after close.
     *
     * @return empty before failure, otherwise the original thrown failure
     */
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    @Override
    public TapProvider taps() {
        return taps;
    }

    @Override
    public MonitoringSnapshot monitoringSnapshot() {
        long accepted = good, rejected = bad, signalSamples = publishedSamples;
        return DecoderSnapshots.create(
                UtcTime.nowNanos(),
                Double.NaN,
                UNKNOWN,
                Double.NaN,
                UNKNOWN,
                settings.bitRate(),
                state == ComponentState.RUNNING ? frameLock : UNLOCKED,
                accepted,
                rejected,
                DecoderSnapshots.frameRate(accepted, settings.rate(), signalSamples));
    }

    /** Buffered primitive tap publication; an observation failure disables only that tap. */
    private static final class TapBuffer {
        private final ComplexFloatRingBuffer ring;
        private final float[] buffer;
        private int count;
        private volatile boolean enabled = true;

        TapBuffer(int capacity, double rate, int bufferFloats) {
            ring = new ComplexFloatRingBuffer(capacity, rate);
            buffer = new float[bufferFloats];
        }

        void append(float i, float q) {
            if (!enabled) return;
            buffer[count++] = i;
            buffer[count++] = q;
            if (count == buffer.length) flush();
        }

        void flush() {
            if (count == 0) return;
            try {
                ring.write(buffer, count / 2);
            } catch (RuntimeException observationFailure) {
                enabled = false;
            } finally {
                count = 0;
            }
        }

        Optional<ComplexTap> tap() {
            return enabled ? Optional.of(ring) : Optional.empty();
        }
    }

    private static final List<MonitoringDescriptor> METRICS = List.of(
            DecoderMetricSchema.descriptor(DecoderStatusKeys.SIGNAL_SNR_DB,
                    "Unavailable: the reference defines no calibrated supported SNR estimator; NaN"),
            DecoderMetricSchema.descriptor(DecoderStatusKeys.CARRIER_LOCK,
                    "UNKNOWN: carrier tracking has no qualified independent lock detector"),
            DecoderMetricSchema.descriptor(DecoderStatusKeys.CARRIER_OFFSET_HZ,
                    "NaN without a qualified independent carrier lock detector"),
            DecoderMetricSchema.descriptor(DecoderStatusKeys.SYMBOL_LOCK,
                    "UNKNOWN: M&M recovery has no qualified independent timing lock detector"),
            DecoderMetricSchema.descriptor(DecoderStatusKeys.SYMBOL_RATE_BAUD,
                    "Nominal recovered symbol rate, 5000 baud"),
            DecoderMetricSchema.descriptor(DecoderStatusKeys.FRAME_LOCK,
                    "Checksum-valid payload delivery within three input signal seconds"),
            DecoderMetricSchema.descriptor(DecoderStatusKeys.FRAME_GOOD,
                    "Checksum-valid 220-byte GGAK payloads successfully handed to sink"),
            DecoderMetricSchema.descriptor(DecoderStatusKeys.FRAME_BAD,
                    "Complete 220-byte GGAK candidates rejected by additive checksum; not lost frames"),
            DecoderMetricSchema.descriptor(DecoderStatusKeys.FRAME_TOTAL,
                    "Accepted plus rejected complete candidates; excludes search and incomplete data"),
            DecoderMetricSchema.descriptor(DecoderStatusKeys.FRAME_RATE_HZ,
                    "Lifetime accepted frames divided by processed input signal seconds"));
}
