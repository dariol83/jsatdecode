package eu.dariolucia.jsatdecode.distribution.zmq;

import eu.dariolucia.jsatdecode.api.distribution.RuntimeEvent;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/* All methods are worker-confined. Explicit wire fields are removed from generic
   annotations, whose remaining contents become the extensible metadata map. */
final class SatDecodeWire {
    static final byte[] FRAME = "satdecode.v1.frame".getBytes(StandardCharsets.US_ASCII);
    static final byte[] STATUS = "satdecode.v1.monitor.status".getBytes(StandardCharsets.US_ASCII);
    static final byte[] EVENT = "satdecode.v1.monitor.event".getBytes(StandardCharsets.US_ASCII);
    private final ZmqSettings settings;
    final UtcClock clock;

    SatDecodeWire(ZmqSettings settings, UtcClock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    private Map<String, Object> common(String type, long sequence) {
        if (sequence < 0) throw new IllegalArgumentException("Sequence exhausted");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema_version", 1);
        envelope.put("message_type", type);
        envelope.put("receiver_id", settings.receiver());
        envelope.put("decoder", settings.decoder());
        envelope.put("sequence", sequence);
        return envelope;
    }

    List<byte[]> frame(AnnotatedFrame frame, long sequence) {
        byte[] payload = frame.data();
        if (payload.length == 0 || payload.length > settings.maxBytes())
            throw new IllegalArgumentException("Invalid frame payload size");
        Map<String, Object> extra = new LinkedHashMap<>(frame.annotations());
        Object rx = extra.remove("rx_time_ns"), reception = extra.remove("reception.timestamp");
        long time = rx != null ? utc(rx) : reception != null ? utc(reception) : clock.reception(frame.timestampNanos());
        Object source = extra.remove("rx_time_source");
        String rxSource = source == null ? (rx == null && reception == null ? "host" : "unknown") : text(source, "rx_time_source");
        if (!Set.of("hardware", "sample_clock", "host", "recording", "unknown").contains(rxSource))
            throw new IllegalArgumentException("Invalid rx_time_source");
        Object type = extra.remove("frame_type"), genericType = extra.remove("frame.type");
        Object quality = extra.remove("quality"), valid = extra.remove("frame.valid");
        if (valid != null && !(valid instanceof Boolean))
            throw new IllegalArgumentException("frame.valid must be boolean");
        String qualityName = quality == null ? (valid == null ? "UNKNOWN" : Boolean.TRUE.equals(valid) ? "GOOD" : "BAD") : text(quality, "quality");
        if (!Set.of("GOOD", "BAD", "UNKNOWN").contains(qualityName))
            throw new IllegalArgumentException("Invalid quality");
        Object metadata = extra.remove("metadata");
        if (metadata != null) {
            if (!(metadata instanceof Map<?, ?> map)) throw new IllegalArgumentException("metadata must be a map");
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key))
                    throw new IllegalArgumentException("metadata keys must be strings");
                extra.put(key, entry.getValue());
            }
        }
        Map<String, Object> envelope = common("tm.frame", sequence);
        envelope.put("rx_time_ns", time);
        envelope.put("rx_time_source", rxSource);
        envelope.put("publish_time_ns", clock.now());
        envelope.put("frame_type", type != null ? text(type, "frame_type") : genericType != null ? text(genericType, "frame.type") : "RAW");
        envelope.put("frame_length", payload.length);
        envelope.put("quality", qualityName);
        envelope.put("metadata", extra);
        byte[] encoded = encode(envelope);
        if ((long) encoded.length + payload.length + FRAME.length > settings.maxBytes())
            throw new IllegalArgumentException("Frame multipart message exceeds max_message_bytes");
        return List.of(FRAME, encoded, payload);
    }

    List<byte[]> status(Map<String, Object> metrics, Map<String, Long> timestamps, long sequence) {
        Map<String, Object> envelope = common("monitor.status", sequence);
        envelope.put("timestamp_ns", clock.now());
        envelope.put("metrics", metrics);
        envelope.put("metric_timestamps_ns", timestamps);
        return List.of(STATUS, encode(envelope));
    }

    List<byte[]> event(RuntimeEvent event, long sequence) {
        Map<String, Object> details = new LinkedHashMap<>(event.attributes());
        Object explicit = details.remove("reception.timestamp");
        Map<String, Object> envelope = common("monitor.event", sequence);
        envelope.put("timestamp_ns", explicit != null ? utc(explicit) : clock.reception(event.timestampNanos()));
        envelope.put("event", event.type());
        envelope.put("severity", event.severity().name());
        envelope.put("details", details);
        return List.of(EVENT, encode(envelope));
    }

    private byte[] encode(Map<String, Object> envelope) {
        return CanonicalCbor.encode(envelope, settings.maxBytes());
    }

    private static long utc(Object value) {
        if (value instanceof String s) return UtcClock.instant(Instant.parse(s));
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            return UtcClock.nonnegative(((Number) value).longValue());
        throw new IllegalArgumentException("UTC time must be integral epoch nanoseconds or an ISO-8601 instant");
    }

    private static String text(Object value, String key) {
        String s = value instanceof Enum<?> e ? e.name() : value instanceof String string ? string : null;
        if (s == null || s.isBlank()) throw new IllegalArgumentException(key + " must be a nonblank string");
        return s;
    }
}
