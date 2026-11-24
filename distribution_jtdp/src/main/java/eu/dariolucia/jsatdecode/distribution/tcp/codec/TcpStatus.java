package eu.dariolucia.jsatdecode.distribution.tcp.codec;

import eu.dariolucia.jsatdecode.api.distribution.RuntimeEvent;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;

import java.util.Locale;
import java.util.Map;

/** Converts framework monitoring and event vocabulary into JTDP wire fields. */
public final class TcpStatus {
    private TcpStatus() { }

    public static Object value(Map<String, Object> values, String key, String alias) {
        return values.containsKey(key) ? values.get(key) : values.get(alias);
    }

    public static long counter(MonitoringSnapshot snapshot, String key, String alias) {
        Object value = value(snapshot.values(), key, alias);
        return value instanceof Number number ? number.longValue() & 0xFFFFFFFFL : 0;
    }

    public static int state(Object value) {
        if (value instanceof Number number) {
            double state = number.doubleValue();
            return state >= 0 && state <= 3 && state == Math.rint(state) ? (int) state : TcpConstants.STATE_UNKNOWN;
        }
        if (value instanceof String text) {
            return switch (text.toUpperCase(Locale.ROOT)) {
                case "SEARCHING" -> TcpConstants.STATE_SEARCHING;
                case "LOCKED" -> TcpConstants.STATE_LOCKED;
                case "UNLOCKED" -> TcpConstants.STATE_UNLOCKED;
                default -> TcpConstants.STATE_UNKNOWN;
            };
        }
        return TcpConstants.STATE_UNKNOWN;
    }

    public static float snr(MonitoringSnapshot snapshot) {
        Object value = value(snapshot.values(), "signal.snr_db", "snr_db");
        float snr = value instanceof Number number ? number.floatValue() : Float.NaN;
        return Float.isFinite(snr) ? snr : Float.NaN;
    }

    public static int carrierOffset(MonitoringSnapshot snapshot) {
        Object value = value(snapshot.values(), "carrier.offset_hz", "carrier_offset_hz");
        if (value instanceof Number number) {
            double offset = number.doubleValue();
            if (Double.isFinite(offset) && offset > Integer.MIN_VALUE && offset <= Integer.MAX_VALUE) {
                return (int) offset;
            }
        }
        return TcpConstants.CARRIER_OFFSET_UNAVAILABLE;
    }

    public static byte snapshotStatus(MonitoringSnapshot snapshot) {
        Map<String, Object> values = snapshot.values();
        return TcpConstants.encodeStatus(state(value(values, "carrier.lock", "carrier_state")),
                state(value(values, "symbol.lock", "symbol_state")),
                state(value(values, "frame.lock", "frame_state")), Float.isFinite(snr(snapshot)) ? 1 : 0);
    }

    public static long centerFrequency(MonitoringSnapshot snapshot) {
        return frequency(frequencyValue(snapshot.values()), 0);
    }

    private static Object frequencyValue(Map<String, Object> values) {
        if (values.containsKey("source.center_frequency_hz")) return values.get("source.center_frequency_hz");
        return value(values, "source.center_frequency", "center_frequency_hz");
    }

    private static long frequency(Object value, long fallback) {
        if (value instanceof Number number) {
            long frequency = number.longValue();
            if (frequency >= 0 && frequency < (1L << 56)) return frequency;
        }
        return fallback;
    }

    public static long eventCenterFrequency(RuntimeEvent event, long fallback) {
        return frequency(frequencyValue(event.attributes()), fallback);
    }

    public static byte eventStatus(RuntimeEvent event, byte fallback) {
        Map<String, Object> values = event.attributes();
        int status = fallback & 0xFC;
        status |= TcpConstants.decodeSnrAvailable(fallback) == 1 ? 1 : 0;
        String type = event.type().toLowerCase(Locale.ROOT);
        String[] components = {"carrier", "symbol", "frame"};
        int[] shifts = {6, 4, 2};
        for (int i = 0; i < components.length; ++i) {
            String component = components[i];
            Object observed = value(values, component + ".lock", component + "_state");
            boolean changed = type.startsWith(component + ".") || type.equals(component + "_state_changed");
            if (changed) {
                if (observed == null) observed = value(values, "new_state", "state");
                if (type.endsWith(".locked")) observed = "LOCKED";
                else if (type.endsWith(".unlocked")) observed = "UNLOCKED";
                else if (type.endsWith(".searching")) observed = "SEARCHING";
                else if (type.endsWith(".unknown")) observed = "UNKNOWN";
            }
            if (observed != null || changed) {
                status = (status & ~(3 << shifts[i])) | (state(observed) << shifts[i]);
            }
        }
        Object snr = value(values, "signal.snr_db", "snr_db");
        if (snr != null) status = (status & ~3) | (snr instanceof Number number && Float.isFinite(number.floatValue()) ? 1 : 0);
        return (byte) status;
    }

    public static byte mapEventCode(String type) {
        if (type == null) return -1;
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "decoder.started", "decoder_started" -> TcpConstants.EVENT_DECODER_STARTED;
            case "decoder.stopped", "decoder_stopped" -> TcpConstants.EVENT_DECODER_STOPPED;
            case "carrier.locked", "carrier.unlocked", "carrier.searching", "carrier.unknown", "carrier.state.changed", "carrier_state_changed" -> TcpConstants.EVENT_CARRIER_STATE_CHANGED;
            case "symbol.locked", "symbol.unlocked", "symbol.searching", "symbol.unknown", "symbol.state.changed", "symbol_state_changed" -> TcpConstants.EVENT_SYMBOL_STATE_CHANGED;
            case "frame.locked", "frame.unlocked", "frame.searching", "frame.unknown", "frame.state.changed", "frame_state_changed" -> TcpConstants.EVENT_FRAME_STATE_CHANGED;
            case "decoder.error", "decoder_error" -> TcpConstants.EVENT_DECODER_ERROR;
            case "distribution.overrun", "distribution_overrun" -> TcpConstants.EVENT_DISTRIBUTION_OVERRUN;
            case "data.discarded", "data_discarded" -> TcpConstants.EVENT_DATA_DISCARDED;
            default -> -1;
        };
    }
}
