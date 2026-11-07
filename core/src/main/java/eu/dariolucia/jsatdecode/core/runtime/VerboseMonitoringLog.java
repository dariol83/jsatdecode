package eu.dariolucia.jsatdecode.core.runtime;

import eu.dariolucia.jsatdecode.api.config.BinaryValue;
import eu.dariolucia.jsatdecode.api.config.NullValue;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringDescriptor;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/* Confined to the runtime management caller under its lifecycle lock. Reuses
   runtime snapshots; no extra polling thread, history or DSP callback is needed.
   UTC timestamps identify observations; monotonic time controls log cadence. */
final class VerboseMonitoringLog {
    private static final long STATUS_PERIOD_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final List<String> LOCK_KEYS = List.of(DecoderStatusKeys.CARRIER_LOCK,
            DecoderStatusKeys.SYMBOL_LOCK, DecoderStatusKeys.FRAME_LOCK);
    private final Map<String, String> units = new HashMap<>();
    private final Map<String, String> locks = new HashMap<>();
    private final Consumer<String> output;
    private boolean statusLogged;
    private long lastStatusNanos;

    VerboseMonitoringLog(Collection<MonitoringDescriptor> descriptors, Consumer<String> output) {
        this.output = Objects.requireNonNull(output);
        for (var descriptor : descriptors) units.put(descriptor.key(), descriptor.unit());
    }

    void accept(MonitoringSnapshot snapshot, long nowNanos) {
        StringJoiner transitions = new StringJoiner(", ");
        for (String key : LOCK_KEYS) {
            String current = lock(snapshot.values().get(key));
            String previous = locks.getOrDefault(key, "UNKNOWN");
            if (!current.equals(previous)) transitions.add(key + "=" + previous + " -> " + current);
            locks.put(key, current);
        }
        if (transitions.length() != 0) write(snapshot, "Locks: " + transitions);

        if (!statusLogged || nowNanos - lastStatusNanos >= STATUS_PERIOD_NANOS) {
            StringJoiner values = new StringJoiner(", ");
            snapshot.values().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                String unit = units.getOrDefault(entry.getKey(), "");
                values.add(text(entry.getKey()) + "=" + format(entry.getValue()) +
                        (unit.isEmpty() ? "" : " [" + text(unit) + "]"));
            });
            write(snapshot, "Monitoring: " + values);
            statusLogged = true;
            lastStatusNanos = nowNanos;
        }
    }

    private void write(MonitoringSnapshot snapshot, String message) {
        output.accept(Instant.ofEpochSecond(0, snapshot.timestampNanos()) + " INFO " + message);
    }

    private static String lock(Object value) {
        if (value instanceof Boolean b) return b ? "LOCKED" : "UNLOCKED";
        String name = value instanceof Enum<?> e ? e.name() : value instanceof String s ? s : "UNKNOWN";
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "LOCKED" -> "LOCKED";
            case "UNLOCKED" -> "UNLOCKED";
            case "SEARCHING" -> "SEARCHING";
            default -> "UNKNOWN";
        };
    }

    // Match the status table's value vocabulary without depending on Swing or
    // truncating the set of metrics. Escape text so each record stays one line.
    private static String format(Object value) {
        if (value instanceof BinaryValue binary) return binary.size() + " bytes";
        if (value == NullValue.INSTANCE) return "null";
        if (value instanceof Map<?, ?> map) return map.size() + " entries";
        if (value instanceof List<?> list) return list.size() + " values";
        if (value instanceof Double d && !Double.isFinite(d) || value instanceof Float f && !Float.isFinite(f))
            return "Unavailable";
        return text(value instanceof Enum<?> e ? e.name() : String.valueOf(value));
    }

    private static String text(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
                .replace("\u0085", "\\u0085").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }
}
