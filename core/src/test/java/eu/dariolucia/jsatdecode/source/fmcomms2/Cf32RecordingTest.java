package eu.dariolucia.jsatdecode.source.fmcomms2;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.distribution.OutputException;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.api.source.SourceException;
import eu.dariolucia.jsatdecode.core.config.ConfigurationLoader;
import eu.dariolucia.jsatdecode.core.runtime.JSatDecodeRuntime;
import eu.dariolucia.jsatdecode.decoder.dummy.DummyDecoder;
import eu.dariolucia.jsatdecode.source.cf32.Cf32SampleSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/* Deterministic storage faults and real mock-IIOD integration. Invoked by Fmcomms2SourceTest. */
final class Cf32RecordingTest {
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final Instant START = Instant.parse("2026-10-24T16:30:45Z");
    private static final Clock CLOCK = Clock.fixed(START, ZoneOffset.ofHours(2));
    private static int checks;

    static void run() throws Exception {
        Path dir = Files.createTempDirectory("jsat-cf32-recording-");
        try {
            settings(dir);
            partialWrites(dir);
            wraparound(dir);
            overflow(dir);
            storageFaults(dir);
            sparseTail(dir);
            capture(dir);
            decimatedRx2(dir);
            runtimeIdentity(dir);
            sourceFailures(dir);
            delayedDrain(dir);
            cancellation(dir);
            check(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() && t.getName().equals("jsatdecode-cf32-writer")),
                    "No recording workers leaked");
            System.out.println("CF32 recording: " + checks + " checks passed");
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Configuration config(int port, Path folder, Object... extra) {
        Map<String, Object> c = new LinkedHashMap<>(Map.of("type", "fmcomms2", "uri", "ip:127.0.0.1:" + port,
                "buffer_size", 4, "block_samples", 2, "gain1", "manual", "manual_gain1", 42.5,
                "recording.enabled", true, "recording.folder", folder.toString(), "recording.name", "pass"));
        for (int i = 0; i < extra.length; i += 2) c.put((String) extra[i], extra[i + 1]);
        return new Configuration(c);
    }

    private static void settings(Path dir) throws Exception {
        for (var c : List.of(config(1, dir, "recording.enabled", "yes"), config(1, dir, "recording.queue_mib", 0),
                config(1, dir, "recording.queue_mib", 1025), config(1, dir, "recording.write_buffer_mib", 65),
                config(1, dir, "recording.failure_policy", "DROP"), config(1, dir, "recording.folder", " "),
                config(1, dir, "recording.name", ".."), config(1, dir, "recording.typo", 1),
                config(1, dir, "buffer_size", 1048576, "block_samples", 1048576, "recording.queue_mib", 1))) {
            expect(ConfigurationException.class, () -> new Fmcomms2SampleSource(c));
        }
        var settings = Fmcomms2Settings.read(config(1, dir, "block_samples", 1)).recording();
        check(settings.slots() == 65536, "Tiny blocks have a hard reference-slot cap");
        var defaults = RecordingSettings.read(new Configuration(Map.of("recording.enabled", true)), 4096);
        check(defaults.slots() == 2048 && defaults.writeBytes() == 4194304, "64 MiB queue and 4 MiB batch defaults");
        var safe = RecordingSettings.read(config(1, dir, "recording.name", "../bad:name. "), 2);
        check(safe.file(2400000000L, 500000, 42.5, START).getParent().equals(dir), "Name remains a single component");
        check(safe.file(2400000000L, 500000, 42.5, START).getFileName().toString().startsWith(".._bad_name_"), "Portable name sanitization");
        String previous = System.getProperty("user.home");
        try {
            System.setProperty("user.home", dir.toString());
            check(RecordingSettings.read(new Configuration(Map.of("recording.enabled", true)), 4096).folder().equals(dir), "Home fallback");
            System.clearProperty("user.home");
            expect(ConfigurationException.class, () -> RecordingSettings.read(new Configuration(Map.of("recording.enabled", true)), 4096));
            check(RecordingSettings.read(new Configuration(Map.of()), 4096).folder() == null, "Disabled recording never resolves home");
        } finally {
            if (previous == null) System.clearProperty("user.home"); else System.setProperty("user.home", previous);
        }
        Path properties = dir.resolve("multi.part.properties");
        Files.writeString(properties, "x=y\n");
        check(ConfigurationLoader.load(Map.of(), properties, Map.of(), Map.of()).getString("application.configuration_name").equals("multi.part"), "File stem identity");
        check(ConfigurationLoader.load(Map.of(), null, Map.of(), Map.of()).getString("application.configuration_name").equals("session"), "Embedded fallback identity");
        check(ConfigurationLoader.load(Map.of("application.configuration_name", "default"), properties,
                Map.of("application.configuration_name", "override"), Map.of("application.configuration_name", "switch"))
                .getString("application.configuration_name").equals("switch"), "Identity honors merge precedence");
    }

    private static RecordingSettings small(Path dir, String name, RecordingSettings.Policy policy, int slots, int bytes) {
        return new RecordingSettings(true, dir, name, 64 * 1048576L, slots, bytes, policy, 4);
    }

    private static Cf32Recorder recorder(RecordingSettings settings, Cf32Recorder.FileFactory files, AtomicReference<Throwable> fatal) throws Exception {
        var r = new Cf32Recorder(settings, 2203682000L, 500000, 45, START, fatal::set, () -> {});
        r.start(files);
        return r;
    }

    private static ComplexSampleBlock block(float... values) {
        return new ComplexSampleBlock(values, values.length / 2, 0, 0, 500000);
    }

    private static byte[] bytes(float... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) b.putInt(Float.floatToRawIntBits(value));
        return b.array();
    }

    private static void completed(Cf32Recorder r) throws Exception {
        check(r.await(WAIT), "Recorder finalizes");
        r.join();
    }

    private static void partialWrites(Path dir) throws Exception {
        MemoryFile file = new MemoryFile();
        file.zeroWrites = 3;
        float[] values = {1f, -1f, -0f, Float.intBitsToFloat(0x7fc01234), 0.25f, -0.125f};
        var r = recorder(small(dir, "partial", RecordingSettings.Policy.STOP_RECORDING, 8, 16), p -> file, new AtomicReference<>());
        r.offer(block(values));
        r.finish(true);
        completed(r);
        check(Arrays.equals(file.bytes(), bytes(values)), "Partial/zero writes preserve exact float bits and final partial batch");
        check(r.writtenBytes == 24 && r.acceptedSamples == 3 && r.state == Cf32Recorder.State.COMPLETE, "Exact successful accounting");
        check(file.closed && r.queueBytes() == 0, "Closed and drained");
    }

    private static void overflow(Path dir) throws Exception {
        for (var policy : RecordingSettings.Policy.values()) {
            MemoryFile file = new MemoryFile();
            file.entered = new CountDownLatch(1);
            file.resume = new CountDownLatch(1);
            AtomicReference<Throwable> fatal = new AtomicReference<>();
            var r = recorder(small(dir, "overflow", policy, 2, 8), p -> file, fatal);
            try {
                r.offer(block(1, 2, 3, 4));
                latch(file.entered);
                r.offer(block(5, 6, 7, 8));
                r.offer(block(9, 10, 11, 12));
                r.offer(block(13, 14));
                check(r.failure != null && r.acceptedSamples == 6, "Overflow stops at accepted contiguous prefix");
                check((fatal.get() != null) == (policy == RecordingSettings.Policy.FAIL_SESSION), "Overflow policy notification");
                check(r.queueBytes() <= 32 && r.highWaterBytes <= 32, "Slow writer cannot exceed reference/payload bound");
                r.finish(true);
            } finally { file.resume.countDown(); }
            completed(r);
            check(Arrays.equals(file.bytes(), bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)), "Healthy overflow drains the contiguous prefix");
            r.offer(block(99, 100));
            check(r.state == Cf32Recorder.State.FAILED && r.queueBytes() == 0, "Failure remains visible and further offers are inert");
        }
    }

    private static void wraparound(Path dir) throws Exception {
        MemoryFile file = new MemoryFile();
        var r = recorder(small(dir, "wrap", RecordingSettings.Policy.STOP_RECORDING, 2, 16), p -> file, new AtomicReference<>());
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + WAIT.toNanos();
        for (int n = 0; n < 1000; n++) {
            float[] values = new float[(n % 4 + 1) * 2];
            for (int i = 0; i < values.length; i++) values[i] = n + i / 8f;
            r.offer(block(values));
            expected.writeBytes(bytes(values));
            // Test-only rendezvous: the production producer never waits for capacity.
            while (r.queueBytes() != 0 && r.failure == null && System.nanoTime() < deadline) Thread.yield();
            check(r.failure == null && System.nanoTime() < deadline, "SPSC wraparound progresses");
        }
        r.finish(true);
        completed(r);
        check(Arrays.equals(file.bytes(), expected.toByteArray()), "Repeated slot reuse preserves order and variable-size blocks");
    }

    private static void storageFaults(Path dir) throws Exception {
        MemoryFile file = new MemoryFile();
        file.failAfter = 11;
        file.closeFailure = true;
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        var r = recorder(small(dir, "fault", RecordingSettings.Policy.FAIL_SESSION, 4, 16), p -> file, fatal);
        r.offer(block(1, 2, 3, 4));
        r.finish(true);
        completed(r);
        check(r.failure.getMessage().equals("injected write failure") && fatal.get() == r.failure, "Original storage cause retained");
        check(r.failure.getSuppressed().length == 1, "Close error is suppressed, not substituted");
        check(r.writtenBytes == 8 && Arrays.equals(file.bytes(), bytes(1, 2)), "Partial sample tail is truncated");
        MemoryFile close = new MemoryFile();
        close.closeFailure = true;
        r = recorder(small(dir, "close", RecordingSettings.Policy.STOP_RECORDING, 2, 16), p -> close, new AtomicReference<>());
        r.finish(true);
        completed(r);
        check(r.state == Cf32Recorder.State.FAILED, "Close failure prevents complete status");
        MemoryFile zeros = new MemoryFile();
        zeros.zeroWrites = Integer.MAX_VALUE;
        r = recorder(small(dir, "stalled", RecordingSettings.Policy.STOP_RECORDING, 2, 8), p -> zeros, new AtomicReference<>());
        r.offer(block(1, 2));
        r.finish(true);
        completed(r);
        check(r.failure instanceof IOException && r.failure.getMessage().contains("no progress"), "Persistent zero writes fail without busy spinning");
    }

    private static void sparseTail(Path dir) throws Exception {
        long prefix = 1L << 32;
        var r = recorder(small(dir, "sparse", RecordingSettings.Policy.STOP_RECORDING, 2, 16), path -> {
            var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE);
            channel.position(prefix);
            return channel;
        }, new AtomicReference<>());
        // Economically model an already written 4-GiB prefix; no multi-GiB allocation/write.
        r.writtenBytes = prefix;
        r.offer(block(0.25f, -0.5f, 0.75f, -1));
        r.finish(true);
        completed(r);
        check(r.writtenBytes == prefix + 16 && Files.size(r.file) == prefix + 16, "Long file/counter positions cross 4 GiB");
        List<Float> replay = new ArrayList<>();
        try (var source = new Cf32SampleSource(new Configuration(Map.of("file", r.file.toString(), "sample_rate", 500000,
                "start_offset", prefix / 8, "length", 2)))) {
            source.setSampleSink(b -> { for (int i = 0; i < b.complexSamples(); i++) { replay.add(b.i(i)); replay.add(b.q(i)); } });
            source.start();
            check(source.awaitCompletion(WAIT), "Sparse CF32 tail replays");
            check(source.failure().isEmpty() && replay.equals(List.of(0.25f, -0.5f, 0.75f, -1f)), "Large-file playback values");
        }
    }

    private static void capture(Path dir) throws Exception {
        Path folder = dir.resolve("capture/nested");
        try (var server = new Fmcomms2SourceTest.Server(Fmcomms2SourceTest.xml("le:S12/16>>0", 0), (s, n, turn) -> {
            byte[] raw = turn == 0 ? new byte[]{1,0,2,0,3,0,4,0,5,0,6,0,7,0,8,0} : new byte[]{-1,-1,0,-8};
            s.send(raw.length + "\n" + s.mask + "\n"); s.fragment(raw);
            if (raw.length < n) s.send("0\n");
        }); var source = new Fmcomms2SampleSource(config(server.port(), folder), CLOCK, Cf32Recorder.FILES)) {
            List<ComplexSampleBlock> delivered = new ArrayList<>();
            source.setSampleSink(b -> { delivered.add(b); if (b.sampleIndex() + b.complexSamples() == 5) source.requestStop(); });
            source.start();
            check(source.awaitCompletion(WAIT), "Acquisition completion includes writer finalization");
            source.stop();
            var status = source.monitoringSnapshot().values();
            Path file = Path.of((String) status.get("source.recording.file"));
            check(file.getFileName().toString().equals("pass_2400000000_3000000sps_g42.5_20261024_163045.cf32"), "Exact UTC filename independent of clock zone");
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            for (var b : delivered) expected.writeBytes(bytes(b.iq()));
            check(Files.size(file) == 40 && Arrays.equals(Files.readAllBytes(file), expected.toByteArray()), "Recorded bytes equal decoder inputs across short blocks");
            check(delivered.size() == 3 && status.get("source.recording.complete").equals(true), "Original delivery boundaries and complete recording");
            check(status.get("source.recording.start_time_ns").equals(START.getEpochSecond() * 1000000000L), "Recording UTC epoch retained");
            check(source.monitoringDescriptors().size() == status.size(), "Recording monitoring schema stays fixed");
            List<Float> replay = new ArrayList<>();
            try (var input = new Cf32SampleSource(new Configuration(Map.of("file", file.toString(), "sample_rate", 3000000)))) {
                input.setSampleSink(b -> { for (float value : b.iq()) replay.add(value); });
                input.start();
                check(input.awaitCompletion(WAIT) && input.failure().isEmpty(), "Captured session replays with unchanged CF32 reader");
                ByteBuffer decoded = ByteBuffer.wrap(expected.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
                for (float value : replay) check(Float.floatToRawIntBits(value) == decoded.getInt(), "Exact replay sample bits");
                check(replay.size() == 10, "Replay complex-sample count");
            }
        }
    }

    private static void decimatedRx2(Path dir) throws Exception {
        String xml = Fmcomms2SourceTest.xml("le:S12/16>>0", 0).replace("<channel id='voltage2' type='input'>",
                "<channel id='voltage2' type='input'><attribute name='sampling_frequency'/>");
        try (var server = new Fmcomms2SourceTest.Server(xml, (s,n,t) -> { s.send(n + "\n" + s.mask + "\n"); s.fragment(new byte[n]); });
             var source = new Fmcomms2SampleSource(config(server.port(), dir.resolve("rx2"), "sample_rate", 4000000, "fpga_decimation", 8,
                     "rx1_en", false, "rx2_en", true, "gain2", "slow_attack", "manual_gain2", -3.5), CLOCK, Cf32Recorder.FILES)) {
            server.writes.put("iio:device1 INPUT voltage2 sampling_frequency", "4000000\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            server.loOverride = "2203682000";
            source.setSampleSink(b -> source.requestStop());
            source.start();
            check(source.awaitCompletion(WAIT), "Decimated RX2 recording completes");
            var status = source.monitoringSnapshot().values();
            Path file = Path.of((String) status.get("source.recording.file"));
            check(file.getFileName().toString().equals("pass_2203682000_500000sps_g-3.5_20261024_163045.cf32"), "Filename uses read-back LO, delivered rate and selected RX2 gain");
            check(status.get("source.recording.rx_channel").equals(2) && status.get("source.recording.gain_mode").equals("slow_attack"), "AGC mode is explicit alongside configured gain");
            check(!server.writes.containsKey("iio:device0 INPUT voltage1 hardwaregain"), "Recording does not change AGC hardware setup");
        }
    }

    private static void runtimeIdentity(Path dir) throws Exception {
        for (boolean local : List.of(false, true)) {
            Path folder = dir.resolve(local ? "identity-local" : "identity-global");
            try (var server = new Fmcomms2SourceTest.Server(Fmcomms2SourceTest.xml("le:S12/16>>0", 0), (s,n,t) -> {
                if (t == 0) { s.send(n + "\n" + s.mask + "\n"); s.fragment(new byte[n]); }
                else { s.pending.countDown(); s.input.read(); }
            })) {
                Path properties = dir.resolve("runtime-name.properties");
                Files.writeString(properties, "source.type=fmcomms2\nsource.uri=ip:127.0.0.1:" + server.port() +
                        "\nsource.buffer_size=4\nsource.block_samples=2\nsource.recording.enabled=true\nsource.recording.folder=" +
                        folder.toString().replace('\\', '/') + "\ndecoder.type=dummy\n" + (local ? "source.recording.name=local\napplication.configuration_name=global\n" : ""));
                var c = ConfigurationLoader.load(Map.of("distribution.frames.type", "synthetic", "distribution.monitoring.type", "synthetic"),
                        properties, Map.of(), Map.of());
                try (var runtime = JSatDecodeRuntime.create(c)) {
                    runtime.start();
                    latch(server.pending);
                    runtime.stop();
                    var status = runtime.monitoringSnapshot().values();
                    String name = Path.of((String) status.get("source.recording.file")).getFileName().toString();
                    check(name.startsWith((local ? "local" : "runtime-name") + "_"), "Runtime propagates identity with source override precedence");
                    check(status.get("source.recording.complete").equals(true), "Runtime stop drains recording");
                }
            }
        }
    }

    private static void sourceFailures(Path dir) throws Exception {
        for (var policy : RecordingSettings.Policy.values()) {
            MemoryFile file = new MemoryFile();
            file.failAfter = 0;
            try (var server = new Fmcomms2SourceTest.Server(Fmcomms2SourceTest.xml("le:S12/16>>0", 0), (s,n,t) -> {
                s.send(n + "\n" + s.mask + "\n"); s.output.write(new byte[n]); s.output.flush();
            }); var source = new Fmcomms2SampleSource(config(server.port(), dir, "buffer_size", 65536, "block_samples", 4096,
                    "recording.write_buffer_mib", 1, "recording.failure_policy", policy.name()), CLOCK, p -> file)) {
                AtomicBoolean continued = new AtomicBoolean();
                source.setSampleSink(b -> {
                    if (file.failed) {
                        continued.set(true);
                        if (policy == RecordingSettings.Policy.STOP_RECORDING) source.requestStop();
                    }
                });
                source.start();
                check(source.awaitCompletion(WAIT), "Recording fault terminates or allows callback stop");
                source.stop();
                var status = source.monitoringSnapshot().values();
                check(status.get("source.recording.errors").equals(1L) && status.get("source.recording.complete").equals(false), "Storage failure remains observable");
                if (policy == RecordingSettings.Policy.STOP_RECORDING)
                    check(source.failure().isEmpty() && continued.get(), "Recording failure leaves decoding active");
                else check(source.state() == ComponentState.FAILED && source.failure().orElseThrow().getCause().getMessage().equals("injected write failure"),
                        "Fatal recording failure is not hidden by socket cancellation");
            }
        }
        // Runtime must preserve asynchronous source causes rather than calling initCause twice.
        try (var server = new Fmcomms2SourceTest.Server(Fmcomms2SourceTest.xml("le:S12/16>>0", 0), (s,n,t) -> s.send("-75\n"));
             var source = new Fmcomms2SampleSource(config(server.port(), dir.resolve("runtime-failure")));
             var runtime = new JSatDecodeRuntime(source, new DummyDecoder(), List.of(), List.of())) {
            runtime.start();
            try { runtime.awaitCompletion(); throw new AssertionError("Runtime failure swallowed"); }
            catch (OutputException e) { check(e.getCause() != null && e.getCause().getMessage().contains("errno 75"), "Runtime retains original async cause"); }
            check(source.monitoringSnapshot().values().get("source.recording.state").equals("INCOMPLETE"), "Source failure marks recording incomplete");
        }
    }

    private static void cancellation(Path dir) throws Exception {
        Path disabled = dir.resolve("disabled-must-not-exist");
        try (var server = new Fmcomms2SourceTest.Server(Fmcomms2SourceTest.xml("le:S12/16>>0", 0), (s,n,t) -> {
            s.send(n + "\n" + s.mask + "\n"); s.fragment(new byte[n]);
        }); var source = new Fmcomms2SampleSource(config(server.port(), disabled, "recording.enabled", false), CLOCK,
                p -> { throw new AssertionError("Disabled recording opened a channel"); })) {
            source.setSampleSink(b -> source.requestStop());
            source.start();
            check(source.awaitCompletion(WAIT), "Disabled source stops");
            check(!Files.exists(disabled), "Disabled source creates no recording directory");
        }
        Path startup = dir.resolve("startup");
        try (var server = new Fmcomms2SourceTest.Server(Fmcomms2SourceTest.xml("le:S12/16>>0", 0), (s,n,t) -> {});
             var source = new Fmcomms2SampleSource(config(server.port(), startup))) {
            server.pausePrint = true;
            source.setSampleSink(b -> {});
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread starter = new Thread(() -> { try { source.start(); } catch (Throwable e) { error.set(e); } });
            starter.start();
            latch(server.pending);
            source.stop();
            starter.join(3000);
            check(!starter.isAlive() && error.get() == null && !Files.exists(startup), "Startup cancellation creates no recorder");
        }
        try (var server = new Fmcomms2SourceTest.Server(Fmcomms2SourceTest.xml("le:S12/16>>0", 0), (s,n,t) -> {
            s.pending.countDown(); s.input.read();
        }); var source = new Fmcomms2SampleSource(config(server.port(), dir.resolve("empty")), CLOCK, Cf32Recorder.FILES)) {
            source.setSampleSink(b -> { throw new AssertionError("Blocked source delivered samples"); });
            source.start();
            latch(server.pending);
            Thread.currentThread().interrupt();
            source.stop();
            check(Thread.interrupted(), "Management interruption is preserved after joining both workers");
            var status = source.monitoringSnapshot().values();
            check(status.get("source.recording.complete").equals(true) && Files.size(Path.of((String) status.get("source.recording.file"))) == 0,
                    "Blocked read cancellation finalizes an empty recording");
        }
        CountDownLatch opening = new CountDownLatch(1), proceed = new CountDownLatch(1);
        try (var server = new Fmcomms2SourceTest.Server(Fmcomms2SourceTest.xml("le:S12/16>>0", 0), (s,n,t) -> {});
             var source = new Fmcomms2SampleSource(config(server.port(), dir.resolve("opening")), CLOCK, path -> {
                 opening.countDown();
                 try { latch(proceed); } catch (InterruptedException e) { throw new IOException(e); }
                 return Cf32Recorder.FILES.open(path);
             })) {
            source.setSampleSink(b -> { throw new AssertionError("Cancelled startup delivered samples"); });
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread starter = new Thread(() -> { try { source.start(); } catch (Throwable e) { error.set(e); } });
            Thread stopper = new Thread(source::stop);
            starter.start();
            try {
                latch(opening);
                source.requestStop();
                stopper.start();
            } finally { proceed.countDown(); }
            starter.join(3000);
            stopper.join(3000);
            check(!starter.isAlive() && !stopper.isAlive() && error.get() == null, "Concurrent stop cancels recording setup and drains the created file");
            check(source.monitoringSnapshot().values().get("source.recording.complete").equals(true), "Cancelled file setup releases recording ownership");
        }
        Path collisionFolder = dir.resolve("collision");
        Files.createDirectories(collisionFolder);
        Path collision = Fmcomms2Settings.read(config(1, collisionFolder)).recording().file(2400000000L, 3000000, 42.5, START);
        Files.writeString(collision, "preserve");
        try (var server = new Fmcomms2SourceTest.Server(Fmcomms2SourceTest.xml("le:S12/16>>0", 0), (s,n,t) -> {});
             var source = new Fmcomms2SampleSource(config(server.port(), collisionFolder), CLOCK, Cf32Recorder.FILES)) {
            source.setSampleSink(b -> { throw new AssertionError("Samples delivered after recording startup failure"); });
            expect(SourceException.class, source::start);
            source.stop();
            check(source.failure().isPresent() && Files.readString(collision).equals("preserve"), "CREATE_NEW collision preserves existing data");
        }
        Path regularFile = dir.resolve("not-a-directory");
        Files.writeString(regularFile, "preserve");
        try (var server = new Fmcomms2SourceTest.Server(Fmcomms2SourceTest.xml("le:S12/16>>0", 0), (s,n,t) -> {});
             var source = new Fmcomms2SampleSource(config(server.port(), regularFile))) {
            source.setSampleSink(b -> { throw new AssertionError("Samples after folder failure"); });
            expect(SourceException.class, source::start);
            check(source.awaitCompletion(WAIT) && source.failure().isPresent(), "Folder creation error cleans startup resources");
            check(server.requests.stream().noneMatch(command -> command.startsWith("OPEN ")), "Storage setup completes before hardware OPEN");
        }
    }

    private static void delayedDrain(Path dir) throws Exception {
        MemoryFile file = new MemoryFile();
        file.entered = new CountDownLatch(1);
        file.resume = new CountDownLatch(1);
        CountDownLatch stoppedInCallback = new CountDownLatch(1);
        try (var server = new Fmcomms2SourceTest.Server(Fmcomms2SourceTest.xml("le:S12/16>>0", 0), (s,n,t) -> {
            s.send(n + "\n" + s.mask + "\n"); s.output.write(new byte[n]); s.output.flush();
        }); var source = new Fmcomms2SampleSource(config(server.port(), dir.resolve("drain"), "buffer_size", 65536, "block_samples", 4096,
                "recording.write_buffer_mib", 1), CLOCK, p -> file)) {
            source.setSampleSink(b -> {
                if (b.sampleIndex() >= 131072) {
                    source.requestStop();
                    stoppedInCallback.countDown();
                }
            });
            source.start();
            try {
                latch(stoppedInCallback);
                latch(file.entered);
                check(!source.awaitCompletion(Duration.ofMillis(20)) && source.state() != ComponentState.STOPPED,
                        "Source cannot report completion while an accepted batch is blocked in storage");
            } finally { file.resume.countDown(); }
            check(source.awaitCompletion(WAIT), "Source completes after final writer drain");
            source.stop();
            var status = source.monitoringSnapshot().values();
            check(status.get("source.recording.complete").equals(true) && file.size() == (Long) status.get("source.samples") * 8,
                    "Management shutdown writes every accepted sample");
        }
        IllegalStateException marker = new IllegalStateException("injected decoder callback failure");
        try (var server = new Fmcomms2SourceTest.Server(Fmcomms2SourceTest.xml("le:S12/16>>0", 0), (s,n,t) -> {
            s.send(n + "\n" + s.mask + "\n"); s.fragment(new byte[n]);
        }); var source = new Fmcomms2SampleSource(config(server.port(), dir.resolve("sink-failure")))) {
            source.setSampleSink(b -> { throw marker; });
            source.start();
            check(source.awaitCompletion(WAIT) && source.failure().orElseThrow() == marker, "Decoder failure retains original cause through recording drain");
            var status = source.monitoringSnapshot().values();
            check(status.get("source.recording.state").equals("INCOMPLETE") && status.get("source.recording.samples_accepted").equals(2L)
                    && status.get("source.samples").equals(0L), "Recording includes the failing input block without claiming successful delivery");
        }
    }

    private static void latch(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Fixture latch timeout");
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable e) { check(type.isInstance(e), "Expected " + type + ", got " + e); return; }
        throw new AssertionError("Expected " + type);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class MemoryFile implements SeekableByteChannel {
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        volatile boolean closed, failed;
        boolean closeFailure;
        int zeroWrites, failAfter = Integer.MAX_VALUE;
        CountDownLatch entered, resume;
        byte[] bytes() { return data.toByteArray(); }
        @Override public int write(ByteBuffer src) throws IOException {
            if (entered != null) {
                entered.countDown();
                try { latch(resume); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
            if (zeroWrites > 0) { zeroWrites--; return 0; }
            if (data.size() >= failAfter) { failed = true; throw new IOException("injected write failure"); }
            int count = Math.min(Math.min(src.remaining(), 3), failAfter - data.size());
            for (int i = 0; i < count; i++) data.write(src.get());
            return count;
        }
        @Override public long position() { return data.size(); }
        @Override public long size() { return data.size(); }
        @Override public SeekableByteChannel truncate(long size) { byte[] before = bytes(); data.reset(); data.write(before, 0, (int) Math.min(size, before.length)); return this; }
        @Override public boolean isOpen() { return !closed; }
        @Override public void close() throws IOException { closed = true; if (closeFailure) throw new IOException("injected close failure"); }
        @Override public int read(ByteBuffer dst) { throw new UnsupportedOperationException(); }
        @Override public SeekableByteChannel position(long position) { throw new UnsupportedOperationException(); }
    }
}
