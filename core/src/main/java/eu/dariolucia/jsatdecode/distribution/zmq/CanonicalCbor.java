package eu.dariolucia.jsatdecode.distribution.zmq;

import eu.dariolucia.jsatdecode.api.config.BinaryValue;
import eu.dariolucia.jsatdecode.api.config.NullValue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/* Canonical cbor2-compatible encoding of the API's immutable value vocabulary.
   No decoder is needed in production. The independent reference client decodes
   tests. Length-first encoded-key sorting and shortest exact floats are required. */
final class CanonicalCbor {
    private final int limit;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private CanonicalCbor(int limit) {
        this.limit = limit;
    }

    static byte[] encode(Object value, int limit) {
        var encoder = new CanonicalCbor(limit);
        encoder.value(value, 0);
        return encoder.out.toByteArray();
    }

    private void put(int value) {
        reserve(1);
        out.write(value);
    }

    private void bytes(byte[] value) {
        reserve(value.length);
        out.writeBytes(value);
    }

    private void reserve(int count) {
        if (count > limit - out.size()) throw new IllegalArgumentException("CBOR message exceeds max_message_bytes");
    }

    private void header(int major, long value) {
        if (value < 24) put(major << 5 | (int) value);
        else if (value <= 0xff) {
            put(major << 5 | 24);
            integer(value, 1);
        } else if (value <= 0xffff) {
            put(major << 5 | 25);
            integer(value, 2);
        } else if (value <= 0xffffffffL) {
            put(major << 5 | 26);
            integer(value, 4);
        } else {
            put(major << 5 | 27);
            integer(value, 8);
        }
    }

    private void integer(long bits, int count) {
        for (int shift = (count - 1) * 8; shift >= 0; shift -= 8) put((int) (bits >>> shift) & 255);
    }

    private void value(Object value, int depth) {
        if (depth > 32) throw new IllegalArgumentException("CBOR metadata nesting exceeds 32");
        if (value == null || value == NullValue.INSTANCE) {
            put(0xf6);
            return;
        }
        if (value instanceof BinaryValue binary) {
            if (binary.size() > limit) throw new IllegalArgumentException("CBOR byte string exceeds bound");
            header(2, binary.size());
            bytes(binary.bytes());
            return;
        }
        if (value instanceof Boolean b) {
            put(b ? 0xf5 : 0xf4);
            return;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long n = ((Number) value).longValue();
            header(n < 0 ? 1 : 0, n < 0 ? ~n : n);
            return;
        }
        if (value instanceof Float || value instanceof Double) {
            floating(((Number) value).doubleValue());
            return;
        }
        if (value instanceof Character || value instanceof Enum<?>) {
            value = value instanceof Enum<?> e ? e.name() : value.toString();
        }
        if (value instanceof String s) {
            if (s.length() > limit) throw new IllegalArgumentException("CBOR string exceeds bound");
            for (int i = 0; i < s.length(); i++) {
                char unit = s.charAt(i);
                if (Character.isHighSurrogate(unit)) {
                    if (++i == s.length() || !Character.isLowSurrogate(s.charAt(i)))
                        throw new IllegalArgumentException("Malformed Unicode string");
                } else if (Character.isLowSurrogate(unit))
                    throw new IllegalArgumentException("Malformed Unicode string");
            }
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            header(3, utf8.length);
            bytes(utf8);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > limit) throw new IllegalArgumentException("CBOR map exceeds bound");
            record Entry(byte[] key, Object value) {
            }
            List<Entry> entries = new ArrayList<>(map.size());
            int keyBytes = 0;
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key))
                    throw new IllegalArgumentException("CBOR map keys must be strings");
                byte[] encoded = encode(key, limit);
                if (encoded.length > limit - keyBytes) throw new IllegalArgumentException("CBOR map keys exceed bound");
                keyBytes += encoded.length;
                entries.add(new Entry(encoded, entry.getValue()));
            }
            entries.sort((a, b) -> a.key.length != b.key.length ? Integer.compare(a.key.length, b.key.length) : Arrays.compareUnsigned(a.key, b.key));
            header(5, entries.size());
            for (var entry : entries) {
                bytes(entry.key);
                value(entry.value, depth + 1);
            }
            return;
        }
        if (value instanceof List<?> list) {
            header(4, list.size());
            for (Object element : list) value(element, depth + 1);
            return;
        }
        throw new IllegalArgumentException("Unsupported CBOR value: " + value.getClass());
    }

    private void floating(double value) {
        if (Double.isNaN(value)) {
            put(0xf9);
            integer(0x7e00, 2);
            return;
        }
        float f = (float) value;
        if (Double.doubleToRawLongBits(f) == Double.doubleToRawLongBits(value)) {
            int bits = Float.floatToRawIntBits(f);
            int sign = (bits >>> 16) & 0x8000;
            int exponent = (bits >>> 23) & 255, fraction = bits & 0x7fffff;
            int half = -1;
            if (exponent == 255 && fraction == 0) half = sign | 0x7c00;
            else if (exponent == 0 && fraction == 0) half = sign;
            else if (exponent >= 113 && exponent <= 142 && (fraction & 8191) == 0)
                half = sign | ((exponent - 112) << 10) | (fraction >>> 13);
            else if (exponent >= 103 && exponent <= 112) {
                int significand = fraction | 0x800000, shift = 126 - exponent;
                if ((significand & ((1 << shift) - 1)) == 0) half = sign | (significand >>> shift);
            }
            if (half >= 0) {
                put(0xf9);
                integer(half, 2);
            } else {
                put(0xfa);
                integer(bits & 0xffffffffL, 4);
            }
        } else {
            put(0xfb);
            integer(Double.doubleToRawLongBits(value), 8);
        }
    }
}
