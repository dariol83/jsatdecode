package eu.dariolucia.jsatdecode.core.runtime;

import eu.dariolucia.jsatdecode.api.config.BinaryValue;
import eu.dariolucia.jsatdecode.api.config.NullValue;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringDescriptor;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringType;
import eu.dariolucia.jsatdecode.cli.Main;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Self-contained verbose CLI, transition and wall-clock cadence checks. */
public final class VerboseMonitoringLogTest {
    private static final long TIMESTAMP = Instant.parse("2026-10-28T12:34:56Z").getEpochSecond() * 1_000_000_000L;

    private VerboseMonitoringLogTest() {
    }

    /**
     * Runs deterministic formatting/cadence checks and a paced CLI integration.
     *
     * @param args ignored
     * @throws Exception if verification fails
     */
    public static void main(String[] args) throws Exception {
        transitions();
        status();
        cli();
        System.out.println("VerboseMonitoringLogTest passed");
    }

    private static void transitions() {
        List<String> lines = new ArrayList<>();
        var log = new VerboseMonitoringLog(List.of(), lines::add);
        log.accept(locks("SEARCHING", "UNKNOWN", "UNKNOWN"), 0);
        check(lines.size() == 2 && lines.get(0).endsWith("Locks: " + DecoderStatusKeys.CARRIER_LOCK + "=UNKNOWN -> SEARCHING"), "initial carrier state");
        log.accept(locks("SEARCHING", "UNKNOWN", "UNKNOWN"), 1);
        check(lines.size() == 2, "unchanged state does not repeat events");
        log.accept(locks("LOCKED", "SEARCHING", "UNLOCKED"), 2);
        check(lines.size() == 3 && lines.get(2).contains(DecoderStatusKeys.CARRIER_LOCK + "=SEARCHING -> LOCKED") &&
                lines.get(2).contains(DecoderStatusKeys.SYMBOL_LOCK + "=UNKNOWN -> SEARCHING") &&
                lines.get(2).contains(DecoderStatusKeys.FRAME_LOCK + "=UNKNOWN -> UNLOCKED"), "all independent transitions share one line");
        log.accept(locks(true, State.LOCKED, "locked"), 3);
        check(lines.size() == 4 && !lines.get(3).contains(DecoderStatusKeys.CARRIER_LOCK + "=") &&
                lines.get(3).contains(DecoderStatusKeys.SYMBOL_LOCK + "=SEARCHING -> LOCKED") &&
                lines.get(3).contains(DecoderStatusKeys.FRAME_LOCK + "=UNLOCKED -> LOCKED"), "Boolean/enum/string lock values");
        log.accept(locks(false, "SEARCHING", "UNLOCKED"), 4);
        check(lines.size() == 5 && lines.get(4).contains(DecoderStatusKeys.CARRIER_LOCK + "=LOCKED -> UNLOCKED") &&
                lines.get(4).contains(DecoderStatusKeys.SYMBOL_LOCK + "=LOCKED -> SEARCHING") &&
                lines.get(4).contains(DecoderStatusKeys.FRAME_LOCK + "=LOCKED -> UNLOCKED"), "loss and reacquisition states independent of status cadence");
        log.accept(new MonitoringSnapshot(TIMESTAMP, Map.of()), 5);
        check(lines.size() == 6 && lines.get(5).contains(DecoderStatusKeys.CARRIER_LOCK + "=UNLOCKED -> UNKNOWN") &&
                lines.get(5).contains(DecoderStatusKeys.SYMBOL_LOCK + "=SEARCHING -> UNKNOWN") &&
                lines.get(5).contains(DecoderStatusKeys.FRAME_LOCK + "=UNLOCKED -> UNKNOWN"), "missing measurements become unknown");
        log.accept(locks("unrecognized", "UNKNOWN", 12), 6);
        check(lines.size() == 6, "unsupported lock values stay unknown");
        check(lines.stream().allMatch(line -> line.startsWith("2026-10-28T12:34:56Z INFO ") && line.lines().count() == 1), "one physical UTC log line per record");
    }

    private static void status() {
        List<String> lines = new ArrayList<>();
        var log = new VerboseMonitoringLog(List.of(
                new MonitoringDescriptor(DecoderStatusKeys.SIGNAL_SNR_DB, MonitoringType.DOUBLE, "dB", "SNR"),
                new MonitoringDescriptor("custom\nmetric", MonitoringType.STRING, "unit\r\nname", "Custom")), lines::add);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(DecoderStatusKeys.SIGNAL_SNR_DB, Double.NaN);
        values.put(DecoderStatusKeys.CARRIER_LOCK, "UNKNOWN");
        values.put(DecoderStatusKeys.CARRIER_OFFSET_HZ, 1200.5);
        values.put(DecoderStatusKeys.SYMBOL_LOCK, "UNKNOWN");
        values.put(DecoderStatusKeys.SYMBOL_RATE_BAUD, 9600.0);
        values.put(DecoderStatusKeys.FRAME_LOCK, "UNKNOWN");
        values.put(DecoderStatusKeys.FRAME_GOOD, 2L);
        values.put(DecoderStatusKeys.FRAME_BAD, 1L);
        values.put(DecoderStatusKeys.FRAME_TOTAL, 3L);
        values.put(DecoderStatusKeys.FRAME_RATE_HZ, 0.5);
        values.put("source.samples", 42L);
        values.put("output.frames_sent", 2L);
        values.put("runtime.state", State.LOCKED);
        values.put("custom\nmetric", "first\r\nsecond\u0085third\u2028fourth\u2029last\\end");
        values.put("binary", new BinaryValue(new byte[3]));
        values.put("null", NullValue.INSTANCE);
        values.put("map", Map.of("key", "value"));
        values.put("list", List.of(1, 2));
        values.put("infinite", Float.POSITIVE_INFINITY);
        values.put("long_text", "x".repeat(1200));
        var snapshot = new MonitoringSnapshot(TIMESTAMP, values);
        long start = Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(1);
        log.accept(snapshot, start);
        check(lines.size() == 1, "initial full status without invented unknown lock transitions");
        String line = lines.getFirst();
        for (String key : values.keySet()) check(line.contains(key.replace("\n", "\\n") + "="), "complete status includes " + key);
        check(line.contains(DecoderStatusKeys.SIGNAL_SNR_DB + "=Unavailable [dB]") && line.contains("infinite=Unavailable"), "unavailable values match table");
        check(line.contains("binary=3 bytes") && line.contains("null=null") && line.contains("map=1 entries") && line.contains("list=2 values"), "metadata values match table");
        check(line.contains("custom\\nmetric=first\\r\\nsecond\\u0085third\\u2028fourth\\u2029last\\\\end [unit\\r\\nname]"), "keys, values and units cannot create extra lines");
        check(line.contains("long_text=" + "x".repeat(1200)) && line.lines().count() == 1, "all metrics retained on one line");
        check(line.indexOf("binary=") < line.indexOf(DecoderStatusKeys.CARRIER_LOCK + "=") &&
                line.indexOf("source.samples=") < line.indexOf(DecoderStatusKeys.SYMBOL_LOCK + "="), "stable alphabetical order");
        log.accept(snapshot, start + TimeUnit.SECONDS.toNanos(2) - 1);
        check(lines.size() == 1, "no early status even across nanoTime wraparound");
        values.put("source.samples", 99L);
        log.accept(new MonitoringSnapshot(TIMESTAMP, values), start + TimeUnit.SECONDS.toNanos(2));
        check(lines.size() == 2 && lines.getLast().contains("source.samples=99"), "exact two-second deadline uses latest complete snapshot");
        log.accept(snapshot, start + TimeUnit.SECONDS.toNanos(20));
        check(lines.size() == 3, "delayed management polling emits one status without catch-up bursts");
    }

    private static void cli() throws Exception {
        Path directory = Files.createTempDirectory("jsat-verbose-");
        PrintStream original = System.err;
        try (var bytes = new ByteArrayOutputStream(); var captured = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            Path data = directory.resolve("samples.cf32");
            Files.write(data, new byte[33 * 8]);
            Path configuration = directory.resolve("run.properties");
            Files.writeString(configuration, "source.type=cf32\nsource.file=" + data.toString().replace('\\', '/') +
                    "\nsource.sample_rate=1000\nsource.block_samples=8\ndecoder.type=dummy\n");
            System.setErr(captured);
            Main.main(new String[]{"--config", configuration.toString(), "--headless"});
            check(bytes.size() == 0, "ordinary CLI run remains quiet");
            Main.main(new String[]{"--config", configuration.toString(), "--headless", "-v",
                    "--set", "logging.verbose=false", "--set", "source.realtime=true", "--set", "source.sample_rate=12"});
            List<String> status = bytes.toString(StandardCharsets.UTF_8).lines().toList();
            check(status.size() >= 2 && status.stream().allMatch(line -> line.contains(" INFO Monitoring: ")), "verbose CLI emits periodic single-line status with UI disabled");
            check(status.stream().allMatch(line -> line.contains(DecoderStatusKeys.FRAME_TOTAL + "=") &&
                    line.contains("output.frames_sent=") && line.contains("runtime.source_state=") && line.contains("source.")), "CLI logs decoder/source/runtime/output metrics");
            for (int i = 1; i < status.size(); i++) {
                Instant previous = Instant.parse(status.get(i - 1).substring(0, status.get(i - 1).indexOf(' ')));
                Instant current = Instant.parse(status.get(i).substring(0, status.get(i).indexOf(' ')));
                check(!current.isBefore(previous.plusSeconds(2)), "CLI summaries respect wall-clock interval");
            }
        } finally {
            System.setErr(original);
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static MonitoringSnapshot locks(Object carrier, Object symbol, Object frame) {
        return new MonitoringSnapshot(TIMESTAMP, Map.of(DecoderStatusKeys.CARRIER_LOCK, carrier,
                DecoderStatusKeys.SYMBOL_LOCK, symbol, DecoderStatusKeys.FRAME_LOCK, frame));
    }

    private enum State { LOCKED }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
