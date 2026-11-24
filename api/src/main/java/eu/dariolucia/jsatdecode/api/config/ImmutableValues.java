package eu.dariolucia.jsatdecode.api.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable value vocabulary: strings, boxed primitive numbers, booleans,
 * characters, enums, BinaryValue and recursively copied string-keyed maps/lists.
 * NullValue.INSTANCE denotes explicit metadata null. Raw Java null, arrays and
 * arbitrary mutable objects are rejected.
 */
public final class ImmutableValues {
    private ImmutableValues() {
    }

    /**
     * Copies recursively for safe sharing across threads.
     *
     * @param values non-null map with nonblank keys
     * @return detached immutable map
     */
    public static Map<String, Object> copy(Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((k, v) -> result.put(text(k, "key"), freeze(v)));
        return Map.copyOf(result);
    }

    /**
     * Validates required text.
     *
     * @param value text to validate
     * @param label diagnostic field name
     * @return original nonblank text
     */
    public static String text(String value, String label) {
        if (Objects.requireNonNull(value, label).isBlank())
            throw new IllegalArgumentException(label + " must not be blank");
        return value;
    }

    private static Object freeze(Object v) {
        // Recursively snapshot containers, accept only known immutable leaves, and
        // reject arbitrary objects so caller mutations cannot cross the API boundary.
        Objects.requireNonNull(v, "value");
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> r = new LinkedHashMap<>();
            m.forEach((k, x) -> {
                if (!(k instanceof String s)) throw new IllegalArgumentException("Map keys must be strings");
                r.put(text(s, "key"), freeze(x));
            });
            return Map.copyOf(r);
        }
        if (v instanceof List<?> l) return l.stream().map(ImmutableValues::freeze).toList();
        if (v instanceof BinaryValue || v instanceof String || v instanceof Boolean || v instanceof Character ||
                v instanceof Byte || v instanceof Short || v instanceof Integer ||
                v instanceof Long || v instanceof Float || v instanceof Double || v instanceof Enum<?>) return v;
        throw new IllegalArgumentException("Unsupported immutable value: " + v.getClass().getName());
    }
}
