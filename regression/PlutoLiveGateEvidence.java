import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.decoder.Decoder;
import eu.dariolucia.jsatdecode.api.decoder.DecoderDescriptor;
import eu.dariolucia.jsatdecode.api.decoder.LeasedDecoder;
import eu.dariolucia.jsatdecode.api.frame.FrameSink;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringDescriptor;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.api.source.IqBufferPool;
import eu.dariolucia.jsatdecode.api.source.IqSampleView;
import eu.dariolucia.jsatdecode.api.source.LeasedSampleBlock;
import eu.dariolucia.jsatdecode.api.source.LeasedSampleSink;
import eu.dariolucia.jsatdecode.api.source.LeasedSampleSource;
import eu.dariolucia.jsatdecode.api.source.ManagedSampleSource;
import eu.dariolucia.jsatdecode.api.source.SampleFormat;
import eu.dariolucia.jsatdecode.api.source.SampleSink;
import eu.dariolucia.jsatdecode.api.source.SampleSource;
import eu.dariolucia.jsatdecode.api.source.SourceDescriptor;
import eu.dariolucia.jsatdecode.api.taps.TapProvider;
import eu.dariolucia.jsatdecode.core.runtime.JSatDecodeRuntime;
import eu.dariolucia.jsatdecode.elektrol.ElektroLGgakDecoder;
import eu.dariolucia.jsatdecode.hinode.HinodeDecoder;
import eu.dariolucia.jsatdecode.source.fmcomms2.Fmcomms2SampleSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Opt-in live test: requires exclusive use of the specified FMComms2 receiver. */
public final class PlutoLiveGateEvidence {
    private PlutoLiveGateEvidence() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !(args[1].equals("hinode") || args[1].equals("elektro"))) {
            throw new IllegalArgumentException("Expected: ip:host hinode|elektro new-output-directory seconds-per-run");
        }
        Path parent = Path.of(args[2]).toAbsolutePath().normalize();
        int seconds = Integer.parseInt(args[3]);
        if (seconds < 1 || seconds > 120) throw new IllegalArgumentException("seconds must be in [1,120]");
        Files.createDirectory(parent); // Never reuse or overwrite a prior capture.
        for (int run = 1; run <= 2; run++) acquire(args[0], args[1], parent.resolve("run-" + run), seconds, run);
    }

    private static void acquire(String uri, String mission, Path folder, int seconds, int run) throws Exception {
        boolean hinode = mission.equals("hinode");
        int rate = hinode ? 500_000 : 1_000_000;
        Map<String, Object> settings = new HashMap<>();
        settings.put("uri", uri);
        settings.put("frequency", hinode ? 2256220000L : 1693250000L);
        // This receiver advertises only PHY and PHY/8 ADC rates. Use the same
        // 1 MS/s decoder input as the Elektro example with an 8 MS/s PHY.
        settings.put("sample_rate", hinode ? 4000000 : 8000000);
        settings.put("fpga_decimation", 8);
        settings.put("bandwidth", hinode ? 4000000 : 1500000);
        settings.put("buffer_size", hinode ? 65536 : 32768);
        settings.put("block_samples", 4096);
        settings.put("gain1", "manual");
        settings.put("manual_gain1", hinode ? 32 : 40);
        settings.put("filter_source", "Off");
        settings.put("recording.enabled", true);
        settings.put("recording.folder", folder.toString());
        settings.put("recording.name", mission + "-live-" + run);
        settings.put("recording.failure_policy", "FAIL_SESSION");
        Fmcomms2SampleSource source = new Fmcomms2SampleSource(new Configuration(settings));
        LeasedDecoder decoder = hinode ? new HinodeDecoder(new Configuration(Map.of()))
                : new ElektroLGgakDecoder(new Configuration(Map.of("sample_rate_hz", rate, "dcblocker", false)));
        CaptureDecoder capture = new CaptureDecoder(decoder, source, rate, (long) rate * seconds, !hinode);
        SampleSource observed = new ObservedSource(source, capture);
        // Exercise the exact runtime rotator used by create(Configuration), while
        // inserting a test observer before translation to verify raw recordings.
        var constructor = JSatDecodeRuntime.class.getDeclaredConstructor(SampleSource.class, Decoder.class,
                List.class, List.class, boolean.class, double.class);
        constructor.setAccessible(true);
        try (JSatDecodeRuntime runtime = constructor.newInstance(observed, capture, List.of(), List.of(), false,
                hinode ? 0.0 : 250000.0)) {
            System.out.println("BEGIN mission=" + mission + " run=" + run + " settings=" + new TreeMap<>(settings));
            runtime.start();
            if (!source.awaitCompletion(Duration.ofSeconds(seconds + 45L))) {
                source.requestStop();
                throw new IllegalStateException("Timed out acquiring live samples");
            }
            runtime.awaitCompletion();
            if (source.failure().isPresent()) throw new IllegalStateException("Source failed", source.failure().orElseThrow());
            if (runtime.state() != ComponentState.STOPPED) throw new IllegalStateException("Runtime did not stop cleanly: " + runtime.state());
            Map<String, Object> sm = new TreeMap<>(source.monitoringSnapshot().values());
            Map<String, Object> dm = new TreeMap<>(decoder.monitoringSnapshot().values());
            var lease = source.leasedStatistics().orElseThrow();
            var pool = lease.pool();
            check(capture.samples >= (long) rate * seconds, "Too few samples");
            check(capture.translatedSamples == capture.samples, "Translated sample accounting mismatch");
            check(capture.nonzero > 0, "No non-zero IQ was acquired");
            check(number(sm, "source.samples") == capture.samples, "Source sample accounting mismatch");
            check(number(sm, "source.blocks") == capture.blocks && lease.deliveredBlocks() == capture.blocks,
                    "Source block accounting mismatch");
            check(number(sm, "source.errors") == 0 && number(sm, "source.reported_overruns") == 0,
                    "Reported source error or overrun");
            check(number(sm, "source.recording.errors") == 0 && Boolean.TRUE.equals(sm.get("source.recording.complete")),
                    "Recording did not finish successfully");
            check(number(sm, "source.recording.samples_accepted") == capture.samples
                    && number(sm, "source.recording.samples_written") == capture.samples
                    && number(sm, "source.recording.bytes_written") == capture.samples * 8,
                    "Recording sample accounting mismatch");
            check(pool.outstandingLeases() == 0 && pool.activeBytes() == 0 && pool.exhaustions() == 0
                    && pool.invalidReleases() == 0 && pool.peakReservedBytes() <= pool.budgetBytes(),
                    "Pool did not drain or exceeded its budget");
            check(number(dm, DecoderStatusKeys.FRAME_GOOD) == capture.frames, "Frame sink accounting mismatch");
            Path file = Path.of((String) sm.get("source.recording.file"));
            check(Files.size(file) == capture.samples * 8, "CF32 file length mismatch");
            String deliveredHash = HexFormat.of().formatHex(capture.digest.digest());
            MessageDigest recorded = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] bytes = new byte[131072];
                int n;
                while ((n = input.read(bytes)) != -1) recorded.update(bytes, 0, n);
            }
            String recordedHash = HexFormat.of().formatHex(recorded.digest());
            check(deliveredHash.equals(recordedHash), "Delivered IQ differs from recorded CF32");
            var rotatorField = JSatDecodeRuntime.class.getDeclaredField("rotatorPool");
            rotatorField.setAccessible(true);
            IqBufferPool rotator = (IqBufferPool) rotatorField.get(runtime);
            if (hinode) check(rotator == null, "Zero-offset path allocated a rotator pool");
            else {
                check(rotator != null, "Translated path did not allocate its rotator pool");
                var stats = rotator.statistics();
                check(stats.outstandingLeases() == 0 && stats.activeBytes() == 0 && stats.exhaustions() == 0
                        && stats.invalidReleases() == 0 && stats.peakReservedBytes() <= stats.budgetBytes(),
                        "Rotator pool did not drain or exceeded its budget");
            }
            runtime.close();
            if (rotator != null) {
                var stats = rotator.statistics();
                check(stats.closed() && stats.outstandingLeases() == 0 && stats.activeBytes() == 0
                        && stats.retainedBytes() == 0, "Rotator pool did not close and drain");
                System.out.println("rotator_diagnostics=" + stats);
            }
            double observedSeconds = (capture.lastBlockNanos - capture.firstBlockNanos) * 1e-9;
            double observedRate = (capture.samples - capture.firstBlockSamples) / observedSeconds;
            System.out.println("PASS mission=" + mission + " run=" + run + " samples=" + capture.samples
                    + " blocks=" + capture.blocks + " nonzero=" + capture.nonzero + " peak=" + capture.peak
                    + " frames=" + capture.frames + " observed_seconds=" + observedSeconds
                    + " observed_rate_sps=" + observedRate + " iq_sha256=" + deliveredHash);
            System.out.println("source_metrics=" + sm);
            System.out.println("decoder_metrics=" + dm);
            System.out.println("leased_diagnostics=" + lease);
        }
    }

    private static long number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number)) throw new IllegalStateException("Missing numeric metric: " + key + "=" + value);
        return ((Number) value).longValue();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class CaptureDecoder implements LeasedDecoder {
        private final LeasedDecoder decoder;
        private final Fmcomms2SampleSource source;
        private final int rate;
        private final long target;
        private final boolean translate;
        private final MessageDigest digest;
        private final byte[] bytes = new byte[4096 * 8];
        private final float[] raw = new float[4096 * 2];
        private long samples, blocks, nonzero, frames, anchor, translatedSamples, firstBlockNanos, lastBlockNanos;
        private int firstBlockSamples;
        private float peak;

        CaptureDecoder(LeasedDecoder decoder, Fmcomms2SampleSource source, int rate, long target, boolean translate) throws Exception {
            this.decoder = decoder;
            this.source = source;
            this.rate = rate;
            this.target = target;
            this.translate = translate;
            this.digest = MessageDigest.getInstance("SHA-256");
        }

        @Override public void acceptLeased(LeasedSampleBlock block) {
            block.withView(this::checkTranslation);
            decoder.acceptLeased(block);
            if (samples >= target) source.requestStop();
        }

        private void capture(IqSampleView block) {
            int count = block.complexSamples();
            check(count > 0 && count <= 4096, "Invalid block length");
            check(block.sampleIndex() == samples && block.sampleRate() == rate, "Unexpected block index/rate");
            if (blocks == 0) {
                anchor = block.timestampNanos();
                firstBlockNanos = System.nanoTime();
                firstBlockSamples = count;
            }
            lastBlockNanos = System.nanoTime();
            check(block.timestampNanos() == anchor + (long) (samples / (double) rate * 1e9), "Unexpected block timestamp");
            for (int i = 0; i < count; i++) {
                float re = block.i(i), im = block.q(i);
                check(Float.isFinite(re) && Float.isFinite(im), "Non-finite sample");
                raw[i * 2] = re;
                raw[i * 2 + 1] = im;
                if (re != 0 || im != 0) nonzero++;
                peak = Math.max(peak, Math.max(Math.abs(re), Math.abs(im)));
                putInt(bytes, i * 8, Float.floatToRawIntBits(re));
                putInt(bytes, i * 8 + 4, Float.floatToRawIntBits(im));
            }
            digest.update(bytes, 0, count * 8);
            samples += count;
            blocks++;
        }

        private void checkTranslation(IqSampleView block) {
            int count = block.complexSamples();
            check(block.sampleIndex() == translatedSamples && block.sampleRate() == rate,
                    "Translated block index/rate differs");
            check(block.timestampNanos() == anchor + (long) (translatedSamples / (double) rate * 1e9),
                    "Translated block timestamp differs");
            for (int i = 0; i < count; i++) {
                float re = raw[i * 2], im = raw[i * 2 + 1];
                int phase = translate ? (int) ((translatedSamples + i) & 3) : 0;
                float expectedI = switch (phase) { case 0 -> re; case 1 -> -im; case 2 -> -re; default -> im; };
                float expectedQ = switch (phase) { case 0 -> im; case 1 -> re; case 2 -> -im; default -> -re; };
                check(block.i(i) == expectedI && block.q(i) == expectedQ, "Unexpected frequency-translated sample");
            }
            translatedSamples += count;
        }

        private static void putInt(byte[] target, int offset, int value) {
            target[offset] = (byte) value;
            target[offset + 1] = (byte) (value >>> 8);
            target[offset + 2] = (byte) (value >>> 16);
            target[offset + 3] = (byte) (value >>> 24);
        }

        @Override public DecoderDescriptor descriptor() { return decoder.descriptor(); }
        @Override public void accept(ComplexSampleBlock block) { throw new IllegalStateException("Runtime used legacy handoff"); }
        @Override public void setFrameSink(FrameSink sink) { decoder.setFrameSink(frame -> { frames++; sink.accept(frame); }); }
        @Override public TapProvider taps() { return decoder.taps(); }
        @Override public void start() throws Exception { decoder.start(); }
        @Override public void stop() { decoder.stop(); }
        @Override public ComponentState state() { return decoder.state(); }
        @Override public void close() { decoder.close(); }
        @Override public MonitoringSnapshot monitoringSnapshot() { return decoder.monitoringSnapshot(); }
        @Override public Collection<MonitoringDescriptor> monitoringDescriptors() { return decoder.monitoringDescriptors(); }
    }

    private record ObservedSource(Fmcomms2SampleSource source, CaptureDecoder capture)
            implements ManagedSampleSource, LeasedSampleSource {
        @Override public SourceDescriptor descriptor() { return source.descriptor(); }
        @Override public SampleFormat sampleFormat() { return source.sampleFormat(); }
        @Override public void setSampleSink(SampleSink sink) { throw new IllegalStateException("Expected leased runtime path"); }
        @Override public void setLeasedSampleSink(LeasedSampleSink sink) {
            source.setLeasedSampleSink(block -> { block.withView(capture::capture); sink.accept(block); });
        }
        @Override public Optional<Statistics> leasedStatistics() { return source.leasedStatistics(); }
        @Override public void start() { source.start(); }
        @Override public void stop() { source.stop(); }
        @Override public void requestStop() { source.requestStop(); }
        @Override public Optional<Throwable> failure() { return source.failure(); }
        @Override public ComponentState state() { return source.state(); }
        @Override public void close() { source.close(); }
        @Override public MonitoringSnapshot monitoringSnapshot() { return source.monitoringSnapshot(); }
        @Override public Collection<MonitoringDescriptor> monitoringDescriptors() { return source.monitoringDescriptors(); }
    }
}
