package eu.dariolucia.jsatdecode.ui.swing;

import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.ui.swing.UiViewModel.LockState;
import eu.dariolucia.jsatdecode.ui.swing.UiViewModel.Quality;

import java.util.Locale;

/* Single observer worker owns the smoothing history. Locks annotate confidence
   rather than inventing a mission-independent error-rate/margin estimator. */
final class SignalQualityEstimator {
    private final UiSettings settings;
    private double previous = Double.NaN;

    SignalQualityEstimator(UiSettings settings) {
        this.settings = settings;
    }

    Quality update(MonitoringSnapshot snapshot) {
        Object snr = snapshot.values().get(DecoderStatusKeys.SIGNAL_SNR_DB);
        double raw = snr instanceof Number number ? number.doubleValue() : Double.NaN;
        double score = settings.qualityProfile().percent(raw);
        if (Double.isFinite(score))
            previous = Double.isNaN(previous) ? score : settings.smoothingAlpha() * score + (1 - settings.smoothingAlpha()) * previous;
        else {
            previous = Double.NaN;
            raw = Double.NaN;
        }
        return new Quality(previous, raw, Double.isFinite(raw), lock(snapshot.values().get(DecoderStatusKeys.CARRIER_LOCK)),
                lock(snapshot.values().get(DecoderStatusKeys.SYMBOL_LOCK)), lock(snapshot.values().get(DecoderStatusKeys.FRAME_LOCK)), snapshot.timestampNanos());
    }

    private static LockState lock(Object value) {
        if (value instanceof Boolean b) return b ? LockState.LOCKED : LockState.UNLOCKED;
        String name = value instanceof Enum<?> e ? e.name() : value instanceof String string ? string : "UNKNOWN";
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "LOCKED" -> LockState.LOCKED;
            case "UNLOCKED" -> LockState.UNLOCKED;
            case "SEARCHING" -> LockState.SEARCHING;
            default -> LockState.UNKNOWN;
        };
    }
}
