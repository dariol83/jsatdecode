package eu.dariolucia.jsatdecode.distribution.zmq.client;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/* This reader accepts the definite-length CBOR vocabulary used by the core
   publisher. It deliberately rejects tags, indefinite lengths, undefined,
   non-string keys and integers outside the Java signed-long range. It does not
   require canonical key ordering or shortest encodings from peer publishers. */
final class WireDecoder {
    private static final byte[] FRAME = "satdecode.v1.frame".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STATUS = "satdecode.v1.monitor.status".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EVENT = "satdecode.v1.monitor.event".getBytes(StandardCharsets.US_ASCII);
    private static final Set<String> SOURCES = Set.of("hardware", "sample_clock", "host", "recording", "unknown");
    private static final Set<String> QUALITIES = Set.of("GOOD", "BAD", "UNKNOWN");
    private static final Set<String> SEVERITIES = Set.of("TRACE", "DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL");

    private WireDecoder() {
    }

    record Decoded(boolean frame, byte[] payload, Map<String, Object> information) {
    }

    static Decoded decode(List<byte[]> parts, int maxMessageBytes) {
        if (maxMessageBytes <= 0) throw invalid("maxMessageBytes must be positive");
        if (parts == null || parts.isEmpty()) throw invalid("Missing multipart message");
        int remaining = maxMessageBytes;
        for (byte[] part : parts) {
            if (part == null) throw invalid("Null multipart part");
            if (part.length > remaining) throw invalid("Message exceeds maxMessageBytes");
            remaining -= part.length;
        }
        byte[] topic = parts.getFirst();
        boolean frame = Arrays.equals(topic, FRAME);
        boolean status = Arrays.equals(topic, STATUS);
        boolean event = Arrays.equals(topic, EVENT);
        if (!frame && !status && !event) throw invalid("Unknown topic");
        if (parts.size() != (frame ? 3 : 2)) throw invalid("Invalid multipart part count");

        Reader reader = new Reader(parts.get(1));
        Map<String, Object> envelope = map(reader.value(0), "envelope");
        if (reader.position != reader.input.length) throw invalid("Trailing CBOR data");
        validateCommon(envelope, frame ? "tm.frame" : status ? "monitor.status" : "monitor.event");
        if (frame) {
            nonnegative(envelope, FrameKey.RX_TIME_NS.key());
            nonnegative(envelope, FrameKey.PUBLISH_TIME_NS.key());
            member(envelope, FrameKey.RX_TIME_SOURCE.key(), SOURCES);
            text(envelope, FrameKey.FRAME_TYPE.key());
            member(envelope, FrameKey.QUALITY.key(), QUALITIES);
            long length = nonnegative(envelope, FrameKey.FRAME_LENGTH.key());
            if (length == 0 || length != parts.get(2).length) throw invalid("Frame length does not match payload");
            map(envelope.get(FrameKey.METADATA.key()), FrameKey.METADATA.key());
            return new Decoded(true, parts.get(2).clone(), envelope);
        }
        nonnegative(envelope, MonitoringKey.TIMESTAMP_NS.key());
        if (status) {
            Map<String, Object> metrics = map(envelope.get(MonitoringKey.METRICS.key()), MonitoringKey.METRICS.key());
            Map<String, Object> times = map(envelope.get(MonitoringKey.METRIC_TIMESTAMPS_NS.key()),
                    MonitoringKey.METRIC_TIMESTAMPS_NS.key());
            if (!metrics.keySet().equals(times.keySet()))
                throw invalid("Metrics and observation timestamp keys must match");
            for (String key : times.keySet()) nonnegative(times, key);
        } else {
            text(envelope, MonitoringKey.EVENT.key());
            member(envelope, MonitoringKey.SEVERITY.key(), SEVERITIES);
            map(envelope.get(MonitoringKey.DETAILS.key()), MonitoringKey.DETAILS.key());
        }
        return new Decoded(false, null, envelope);
    }

    private static void validateCommon(Map<String, Object> envelope, String type) {
        if (nonnegative(envelope, "schema_version") != 1) throw invalid("Unsupported schema_version");
        if (!type.equals(text(envelope, "message_type"))) throw invalid("Topic and message_type disagree");
        text(envelope, "receiver_id");
        text(envelope, "decoder");
        nonnegative(envelope, "sequence");
    }

    private static long nonnegative(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof Long value) || value < 0)
            throw invalid(key + " must be a nonnegative integer");
        return value;
    }

    private static String text(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof String value) || value.isBlank())
            throw invalid(key + " must be a nonblank string");
        return value;
    }

    private static void member(Map<String, Object> map, String key, Set<String> values) {
        if (!values.contains(text(map, key))) throw invalid("Invalid " + key);
    }

    @SuppressWarnings("unchecked") // Every decoded map is checked while its entries are read.
    private static Map<String, Object> map(Object value, String name) {
        if (!(value instanceof Map<?, ?>)) throw invalid(name + " must be a map");
        return (Map<String, Object>) value;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static final class Reader {
        private final byte[] input;
        private int position;

        private Reader(byte[] input) {
            this.input = input;
        }

        private Object value(int depth) {
            if (depth > 32) throw invalid("CBOR nesting exceeds 32");
            int initial = octet();
            int major = initial >>> 5;
            int additional = initial & 31;
            if (major == 7) {
                return switch (additional) {
                    case 20 -> Boolean.FALSE;
                    case 21 -> Boolean.TRUE;
                    case 22 -> null;
                    case 25 -> half((int) bits(2));
                    case 26 -> (double) Float.intBitsToFloat((int) bits(4));
                    case 27 -> Double.longBitsToDouble(bits(8));
                    default -> throw invalid("Unsupported CBOR simple value");
                };
            }
            if (major == 6) throw invalid("CBOR tags are unsupported");
            long argument = argument(additional);
            return switch (major) {
                case 0 -> argument;
                case 1 -> ~argument;
                case 2 -> binary(length(argument, 1));
                case 3 -> string(length(argument, 1));
                case 4 -> list(length(argument, 1), depth);
                case 5 -> map(length(argument, 2), depth);
                default -> throw invalid("Unsupported CBOR major type");
            };
        }

        private int octet() {
            if (position == input.length) throw invalid("Truncated CBOR value");
            return input[position++] & 255;
        }

        private long bits(int count) {
            if (count > input.length - position) throw invalid("Truncated CBOR argument");
            long result = 0;
            for (int i = 0; i < count; ++i) result = (result << 8) | octet();
            return result;
        }

        private long argument(int additional) {
            long result = switch (additional) {
                case 24 -> bits(1);
                case 25 -> bits(2);
                case 26 -> bits(4);
                case 27 -> bits(8);
                default -> {
                    if (additional >= 24) throw invalid("Unsupported CBOR length or argument");
                    yield additional;
                }
            };
            if (result < 0) throw invalid("CBOR integer exceeds signed-long range");
            return result;
        }

        // Every element needs at least one byte; every map entry needs two.
        // Validate against actual remaining input before allocating containers.
        private int length(long count, int minimumBytes) {
            if (count > (input.length - position) / minimumBytes)
                throw invalid("CBOR length exceeds available input");
            return (int) count;
        }

        private byte[] binary(int length) {
            byte[] result = Arrays.copyOfRange(input, position, position + length);
            position += length;
            return result;
        }

        private String string(int length) {
            try {
                String result = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(input, position, length)).toString();
                position += length;
                return result;
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException("Malformed CBOR UTF-8", e);
            }
        }

        private List<Object> list(int count, int depth) {
            List<Object> result = new ArrayList<>(count);
            for (int i = 0; i < count; ++i) result.add(value(depth + 1));
            return Collections.unmodifiableList(result);
        }

        private Map<String, Object> map(int count, int depth) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (int i = 0; i < count; ++i) {
                Object key = value(depth + 1);
                if (!(key instanceof String text)) throw invalid("CBOR map keys must be strings");
                if (text.isBlank()) throw invalid("CBOR map keys must be nonblank");
                if (result.containsKey(text)) throw invalid("Duplicate CBOR map key");
                result.put(text, value(depth + 1));
            }
            return Collections.unmodifiableMap(result);
        }

        private static double half(int bits) {
            int sign = bits >>> 15;
            int exponent = (bits >>> 10) & 31;
            int fraction = bits & 1023;
            double magnitude;
            if (exponent == 31) magnitude = fraction == 0 ? Double.POSITIVE_INFINITY : Double.NaN;
            else if (exponent == 0) magnitude = Math.scalb((double) fraction, -24);
            else magnitude = Math.scalb((double) (1024 + fraction), exponent - 25);
            return sign == 0 ? magnitude : -magnitude;
        }
    }
}
