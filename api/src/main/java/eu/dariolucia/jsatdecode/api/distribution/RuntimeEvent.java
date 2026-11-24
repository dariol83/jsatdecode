package eu.dariolucia.jsatdecode.api.distribution;

import eu.dariolucia.jsatdecode.api.config.ImmutableValues;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable event; lock events reflect actual independent decoder states.
 *
 * @param timestampNanos UTC epoch-nanosecond event time
 * @param severity       severity category
 * @param source         nonblank originating component ID
 * @param type           nonblank event key, for example source.eof
 * @param message        nonblank human-readable explanation
 * @param attributes     immutable extensible context
 */
public record RuntimeEvent(long timestampNanos, EventSeverity severity, String source,
                           String type, String message, Map<String, Object> attributes) {
    /**
     * Validates and snapshots event data.
     *
     * @param timestampNanos UTC epoch-nanosecond timestamp
     * @param severity       severity category
     * @param source         originating ID
     * @param type           event key
     * @param message        explanation
     * @param attributes     supported immutable values
     */
    public RuntimeEvent {
        Objects.requireNonNull(severity, "severity");
        ImmutableValues.text(source, "source");
        ImmutableValues.text(type, "type");
        ImmutableValues.text(message, "message");
        attributes = ImmutableValues.copy(attributes);
    }
}