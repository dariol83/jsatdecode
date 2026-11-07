package eu.dariolucia.jsatdecode.hinode;

import static eu.dariolucia.jsatdecode.decoder.base.DecoderLockStates.LOCKED;
import static eu.dariolucia.jsatdecode.decoder.base.DecoderLockStates.SEARCHING;
import static eu.dariolucia.jsatdecode.decoder.base.DecoderLockStates.UNLOCKED;
import static eu.dariolucia.jsatdecode.decoder.base.FrameAnnotationKeys.FRAME_LENGTH;
import static eu.dariolucia.jsatdecode.decoder.base.FrameAnnotationKeys.FRAME_TYPE;
import static eu.dariolucia.jsatdecode.decoder.base.FrameAnnotationKeys.QUALITY;
import static eu.dariolucia.jsatdecode.decoder.base.config.DecoderConfiguration.bool;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.decoder.DecoderDescriptor;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringDescriptor;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringType;
import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.api.taps.ComplexTap;
import eu.dariolucia.jsatdecode.api.taps.TapProvider;
import eu.dariolucia.jsatdecode.core.buffers.ComplexFloatRingBuffer;
import eu.dariolucia.jsatdecode.decoder.base.AbstractDecoder;
import eu.dariolucia.jsatdecode.decoder.base.monitoring.CarrierStatus;
import eu.dariolucia.jsatdecode.decoder.base.monitoring.DecoderMetricSchema;
import eu.dariolucia.jsatdecode.decoder.base.monitoring.DecoderSnapshots;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Hinode 500 kS/s PM/BPSK receiver, isolated from framework APIs and transports. Accept calls and
 * lifecycle mutations must be serialized by the owner. All DSP executes synchronously in accept;
 * observation reads are concurrent and bounded. No worker, file output or UI is created. See
 * docs/receiver.md for qualification limits.
 */
public final class HinodeDecoder extends AbstractDecoder {
    static final DecoderDescriptor DESCRIPTOR =
            new DecoderDescriptor(
                    "hinode_s_tlm",
                    "Hinode S-band telemetry",
                    "0.1.0",
                    "500 kS/s PM/BPSK, CCSDS concatenated coding");
    private final ComplexFloatRingBuffer rf, baseband, symbols;
    private final float[] rfBuffer = new float[8192],
            bbBuffer = new float[8192],
            symBuffer = new float[8192];
    private int rfCount, bbCount, symCount;
    private final TapProvider taps;
    private final HinodeDsp dsp;
    private final StreamingViterbi[] viterbi = new StreamingViterbi[4];
    private final CcsdsDeframer[] deframers = new CcsdsDeframer[4];
    private final byte[][] recent = new byte[3][];
    private int recentPosition;
    private float previous;
    private long symbolCount, timestamp, lastFrameSample = -1;
    private volatile long frames, duplicates;
    private volatile long publishedSamples;
    private final Moments carrierMoments = new Moments(), symbolMoments = new Moments();
    private final double[] frequencies = new double[8];
    private int frequencyCount, frequencyPos, carrierGood, carrierBad, symbolGood, symbolBad;
    private volatile double snr = Double.NaN, symbolSnr = Double.NaN, frequencyStd = Double.NaN;
    private volatile String carrierLock = SEARCHING, symbolLock = SEARCHING, frameLock = SEARCHING;
    // Publish lock and its estimate together; polling never reads live PLL state.
    private volatile CarrierStatus carrierStatus = new CarrierStatus(SEARCHING, Double.NaN);

    /**
     * Validates decoder-only settings and allocates bounded state. No threads start.
     *
     * @param configuration unprefixed decoder section; optional type must equal hinode_s_tlm and
     *     sample_rate_hz must be 500000
     */
    public HinodeDecoder(Configuration configuration) {
        super(DESCRIPTOR, METRICS, "Already started", "Not configured for start");
        Objects.requireNonNull(configuration);
        for (String key : configuration.asMap().keySet()) {
            if (!Set.of("type", "sample_rate_hz", "asm_errors", "tap_capacity", "dcblocker")
                    .contains(key))
                throw new ConfigurationException("Unknown Hinode setting: " + key);
        }
        if (configuration.asMap().containsKey("type")
                && !DESCRIPTOR.id().equals(configuration.getString("type")))
            throw new ConfigurationException("Hinode decoder.type must be " + DESCRIPTOR.id());
        if (configuration.asMap().containsKey("sample_rate_hz")
                && configuration.getDouble("sample_rate_hz") != 500000)
            throw new ConfigurationException("Hinode sample_rate_hz must be 500000");
        int threshold =
                configuration.asMap().containsKey("asm_errors")
                        ? configuration.getInt("asm_errors")
                        : 4;
        int capacity =
                configuration.asMap().containsKey("tap_capacity")
                        ? configuration.getInt("tap_capacity")
                        : 16384;
        if (threshold < 0 || threshold > 4)
            throw new ConfigurationException("asm_errors must be in [0,4]");
        if (capacity < 1 || capacity > 1048576)
            throw new ConfigurationException("tap_capacity must be in [1,1048576]");
        rf = new ComplexFloatRingBuffer(capacity, 500000);
        baseband = new ComplexFloatRingBuffer(capacity, 500000);
        symbols = new ComplexFloatRingBuffer(capacity, 65600);
        taps =
                new TapProvider() {
                    public Optional<ComplexTap> rf() {
                        return Optional.of(rf);
                    }

                    public Optional<ComplexTap> baseband() {
                        return Optional.of(baseband);
                    }

                    public Optional<ComplexTap> symbols() {
                        return Optional.of(symbols);
                    }
                };
        for (int b = 0; b < 4; b++) {
            final int branch = b;
            deframers[b] = new CcsdsDeframer(threshold, data -> frame(data, branch));
            viterbi[b] = new StreamingViterbi(deframers[b]::bit);
        }
        boolean dcblocker = bool(configuration, "dcblocker", true);
        dsp =
                new HinodeDsp(
                        dcblocker,
                        new HinodeDsp.Output() {
                            public void baseband(float i, float q) {
                                carrierMoments.add(i, q);
                                bbBuffer[bbCount++] = i;
                                bbBuffer[bbCount++] = q;
                                if (bbCount == bbBuffer.length) {
                                    baseband.write(bbBuffer, bbCount / 2);
                                    bbCount = 0;
                                }
                            }

                            public void symbol(float i, float q) {
                                recoveredSymbol(i, q);
                            }
                        });
    }

    @Override
    public void accept(ComplexSampleBlock block) {
        if (state != ComponentState.RUNNING) throw new IllegalStateException("Decoder not running");
        try {
            if (block.sampleRate() != 500000)
                throw new IllegalArgumentException("Hinode requires 500000 complex samples/s");
            for (int n = 0; n < block.complexSamples(); n++) {
                float i = block.i(n), q = block.q(n);
                if (!Float.isFinite(i) || !Float.isFinite(q))
                    throw new IllegalArgumentException("Non-finite Hinode IQ");
                timestamp = Math.addExact(block.timestampNanos(), n * 2000L);
                rfBuffer[rfCount++] = i;
                rfBuffer[rfCount++] = q;
                if (rfCount == rfBuffer.length) {
                    rf.write(rfBuffer, rfCount / 2);
                    rfCount = 0;
                }
                dsp.sample(i, q);
                if (dsp.samples % 250000 == 0) updateLocks();
                if (lastFrameSample >= 0 && dsp.samples - lastFrameSample > 1500000)
                    frameLock = UNLOCKED;
            }
            flushTaps();
            publishedSamples = dsp.samples;
        } catch (RuntimeException | Error e) {
            state = ComponentState.FAILED;
            carrierLock = symbolLock = frameLock = UNLOCKED;
            carrierStatus = new CarrierStatus(UNLOCKED, Double.NaN);
            throw e;
        }
    }

    private void recoveredSymbol(float i, float q) {
        symbolMoments.add(i, q);
        symBuffer[symCount++] = i;
        symBuffer[symCount++] = q;
        if (symCount == symBuffer.length) {
            symbols.write(symBuffer, symCount / 2);
            symCount = 0;
        }
        // Both coded-pair phases and both BPSK polarities use the same continuous
        // symbol stream. No queue, branching stream copies or per-symbol objects.
        if ((symbolCount & 1) == 1) {
            viterbi[0].pair(previous, i);
            viterbi[1].pair(-previous, -i);
        } else if (symbolCount > 0) {
            viterbi[2].pair(previous, i);
            viterbi[3].pair(-previous, -i);
        }
        previous = i;
        symbolCount++;
    }

    private void frame(byte[] data, int branch) {
        for (byte[] prior : recent)
            if (Arrays.equals(prior, data)) {
                duplicates++;
                return;
            }
        recent[recentPosition] = data;
        recentPosition = (recentPosition + 1) % 3;
        int vcid = data[1] & 63;
        int counter = ((data[2] & 255) << 16) | ((data[3] & 255) << 8) | (data[4] & 255);
        sink.accept(
                new AnnotatedFrame(
                        data,
                        timestamp,
                        Map.of(
                                FRAME_TYPE,
                                "CCSDS_AOS_TRANSFER_FRAME",
                                QUALITY,
                                "GOOD",
                                "fec_backend",
                                "jradio",
                                "fec_valid",
                                true,
                                "branch",
                                branch,
                                "virtual_channel",
                                vcid,
                                "frame_counter",
                                counter,
                                FRAME_LENGTH,
                                892)));
        frames = Math.incrementExact(frames);
        lastFrameSample = dsp.samples;
        frameLock = LOCKED;
    }

    private void updateLocks() {
        snr = carrierMoments.snr();
        symbolSnr = symbolMoments.snr();
        frequencies[frequencyPos] = dsp.costas.frequency;
        frequencyPos = (frequencyPos + 1) % 8;
        frequencyCount = Math.min(8, frequencyCount + 1);
        if (frequencyCount == 8) {
            double mean = 0, variance = 0;
            for (double f : frequencies) mean += f / 8;
            for (double f : frequencies) variance += (f - mean) * (f - mean) / 8;
            frequencyStd = Math.sqrt(variance);
        }
        boolean good = frequencyStd <= .0025 && snr >= -1;
        boolean bad = frequencyStd >= .01 || snr <= -4;
        carrierGood = good ? carrierGood + 1 : 0;
        carrierBad = bad ? carrierBad + 1 : 0;
        if (!carrierLock.equals(LOCKED) && carrierGood >= 3) carrierLock = LOCKED;
        if (carrierLock.equals(LOCKED) && carrierBad >= 3) carrierLock = UNLOCKED;
        good = carrierLock.equals(LOCKED) && symbolSnr >= 0;
        bad = !carrierLock.equals(LOCKED) || symbolSnr <= -2;
        symbolGood = good ? symbolGood + 1 : 0;
        symbolBad = bad ? symbolBad + 1 : 0;
        if (!symbolLock.equals(LOCKED) && symbolGood >= 3) symbolLock = LOCKED;
        if (symbolLock.equals(LOCKED) && symbolBad >= 3) symbolLock = UNLOCKED;
        // Residual RF carrier PLL (not the translated BPSK Costas loop). This
        // read-only conversion runs at the existing half-second report cadence.
        carrierStatus =
                new CarrierStatus(
                        carrierLock,
                        carrierLock.equals(LOCKED)
                                ? dsp.carrier.frequency * (500000.0 / (2 * Math.PI))
                                : Double.NaN);
    }

    private void flushTaps() {
        if (rfCount > 0) {
            rf.write(rfBuffer, rfCount / 2);
            rfCount = 0;
        }
        if (bbCount > 0) {
            baseband.write(bbBuffer, bbCount / 2);
            bbCount = 0;
        }
        if (symCount > 0) {
            symbols.write(symBuffer, symCount / 2);
            symCount = 0;
        }
    }

    @Override
    public void stop() {
        if (state == ComponentState.CLOSED) return;
        flushTaps();
        carrierLock = symbolLock = frameLock = UNLOCKED;
        carrierStatus = new CarrierStatus(UNLOCKED, Double.NaN);
        if (state != ComponentState.FAILED) state = ComponentState.STOPPED;
    }

    @Override
    public void close() {
        stop();
        state = ComponentState.CLOSED;
    }

    @Override
    public TapProvider taps() {
        return taps;
    }

    @Override
    public MonitoringSnapshot monitoringSnapshot() {
        CarrierStatus carrier = carrierStatus;
        long valid = 0, rejected = 0, dropped = 0;
        for (CcsdsDeframer deframer : deframers) {
            valid += deframer.valid;
            rejected += deframer.rejected;
            dropped += deframer.candidateDrops;
        }
        long accepted = frames;
        return DecoderSnapshots.withDiagnostics(
                eu.dariolucia.jsatdecode.util.UtcTime.nowNanos(),
                isRunning() ? snr : Double.NaN,
                carrier.lock(),
                isRunning() ? carrier.offsetHz() : Double.NaN,
                symbolLock,
                65600.0,
                frameLock,
                accepted,
                rejected,
                publishedSamples > 0 ? accepted * 500000.0 / publishedSamples : 0.0,
                Map.entry("decoder.hinode_s_tlm.frames", accepted),
                Map.entry("decoder.hinode_s_tlm.duplicates", duplicates),
                Map.entry("decoder.hinode_s_tlm.costas_freq_std", frequencyStd),
                Map.entry("decoder.hinode_s_tlm.carrier_snr_db", snr),
                Map.entry("decoder.hinode_s_tlm.symbol_snr_db", symbolSnr),
                Map.entry("decoder.hinode_s_tlm.rs_valid", valid),
                Map.entry("decoder.hinode_s_tlm.rs_rejected", rejected),
                Map.entry("decoder.hinode_s_tlm.asm_candidate_drops", dropped));
    }

    private static final class Moments {
        double m2, m4;
        long count;

        void add(float i, float q) {
            double power = (double) i * i + (double) q * q;
            m2 += .001 * (power - m2);
            m4 += .001 * (power * power - m4);
            count++;
        }

        double snr() {
            if (count < 10000) return Double.NaN;
            double discriminant = 2 * m2 * m2 - m4;
            if (discriminant <= 0) return Double.NaN;
            double signal = Math.sqrt(discriminant), noise = m2 - signal;
            return noise > 0 && signal > 0
                    ? Math.max(-30, Math.min(60, 10 * Math.log10(signal / noise)))
                    : Double.NaN;
        }
    }

    private static final List<MonitoringDescriptor> METRICS =
            List.of(
                    DecoderMetricSchema.descriptor(
                            DecoderStatusKeys.SIGNAL_SNR_DB,
                            "Pre-clock complex M2M4, alpha 0.001; NaN if unavailable"),
                    DecoderMetricSchema.descriptor(
                            DecoderStatusKeys.CARRIER_LOCK,
                            "Frequency stability and pre-clock SNR; hysteretic"),
                    DecoderMetricSchema.descriptor(
                            DecoderStatusKeys.CARRIER_OFFSET_HZ,
                            "Residual carrier PLL offset from input IQ center; positive above"
                                    + " center; NaN unless carrier locked and running"),
                    DecoderMetricSchema.descriptor(
                            DecoderStatusKeys.SYMBOL_LOCK,
                            "Recovered-symbol M2M4 SNR, conditional on carrier lock"),
                    DecoderMetricSchema.descriptor(
                            DecoderStatusKeys.FRAME_LOCK,
                            "RS-valid activity within three signal seconds"),
                    DecoderMetricSchema.descriptor(
                            DecoderStatusKeys.FRAME_GOOD,
                            "Accepted valid frames after recent duplicate suppression"),
                    DecoderMetricSchema.descriptor(
                            DecoderStatusKeys.FRAME_BAD,
                            "Complete ASM candidates rejected by RS, across all ambiguity"
                                    + " branches"),
                    DecoderMetricSchema.descriptor(
                            DecoderStatusKeys.FRAME_TOTAL,
                            "Accepted valid frames plus rejected complete candidates; excludes"
                                    + " duplicates/incomplete candidates"),
                    DecoderMetricSchema.descriptor(
                            DecoderStatusKeys.SYMBOL_RATE_BAUD, "Nominal recovered symbol rate"),
                    DecoderMetricSchema.descriptor(
                            DecoderStatusKeys.FRAME_RATE_HZ,
                            "Lifetime accepted frames per signal second"),
                    new MonitoringDescriptor(
                            "decoder.hinode_s_tlm.frames",
                            MonitoringType.LONG,
                            "frames",
                            "Accepted unique RS-valid frames"),
                    new MonitoringDescriptor(
                            "decoder.hinode_s_tlm.duplicates",
                            MonitoringType.LONG,
                            "frames",
                            "Exact duplicates among last three accepted frames"),
                    new MonitoringDescriptor(
                            "decoder.hinode_s_tlm.costas_freq_std",
                            MonitoringType.DOUBLE,
                            "rad/sample",
                            "Eight half-second frequency observations"),
                    new MonitoringDescriptor(
                            "decoder.hinode_s_tlm.carrier_snr_db",
                            MonitoringType.DOUBLE,
                            "dB",
                            "Pre-clock complex M2M4 SNR"),
                    new MonitoringDescriptor(
                            "decoder.hinode_s_tlm.symbol_snr_db",
                            MonitoringType.DOUBLE,
                            "dB",
                            "Post-clock complex M2M4 SNR"),
                    new MonitoringDescriptor(
                            "decoder.hinode_s_tlm.rs_valid",
                            MonitoringType.LONG,
                            "frames",
                            "RS-valid branch results before deduplication"),
                    new MonitoringDescriptor(
                            "decoder.hinode_s_tlm.rs_rejected",
                            MonitoringType.LONG,
                            "candidates",
                            "Uncorrectable complete ASM candidates"),
                    new MonitoringDescriptor(
                            "decoder.hinode_s_tlm.asm_candidate_drops",
                            MonitoringType.LONG,
                            "candidates",
                            "Noise candidates exceeding 64 pending per branch"));
}
