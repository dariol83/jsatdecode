package eu.dariolucia.jsatdecode.ui.swing;

import eu.dariolucia.jsatdecode.api.taps.ComplexSnapshot;
import eu.dariolucia.jsatdecode.api.taps.ComplexTap;
import eu.dariolucia.jsatdecode.ui.swing.ConstellationFrame.State;
import eu.dariolucia.jsatdecode.ui.swing.UiViewModel.TapStatus;

import java.util.Optional;

/* Worker-owned bounded observation history. Snapshots may overlap; the tap API
   has no sequence offsets, so only identical consecutive timestamps are deduplicated. */
final class ConstellationAnalysis {
    private static final float[] EMPTY = new float[0];
    private final ConstellationSettings settings;
    private float[] ring, scratch;
    private int count, write;
    private double rate = Double.NaN;
    private long timestamp;
    private boolean observed;
    private ConstellationFrame last;
    private TapStatus tap = new TapStatus("Constellation", false, 0, Double.NaN);

    ConstellationAnalysis(ConstellationSettings settings) {
        this.settings = settings;
        last = empty(State.WAITING);
    }

    ConstellationFrame analyze(Optional<ComplexTap> provider) {
        if (provider.isEmpty()) {
            clear();
            tap = new TapStatus("Constellation", false, 0, Double.NaN);
            return last = empty(State.UNAVAILABLE);
        }
        ComplexSnapshot snapshot = provider.orElseThrow().snapshot(settings.points());
        int n = snapshot.complexSamples();
        if (n > settings.points()) throw new IllegalStateException("Symbols tap exceeded requested bound");
        tap = new TapStatus("Constellation", true, n, snapshot.sampleRate());
        if (rate != snapshot.sampleRate() || last.state() == State.UNAVAILABLE) {
            clear();
            rate = snapshot.sampleRate();
            last = empty(State.WAITING);
        }
        if (n == 0 || observed && timestamp == snapshot.timestampNanos()) return last;
        int valid = 0;
        for (int i = 0; i < n; i++) if (Float.isFinite(snapshot.i(i)) && Float.isFinite(snapshot.q(i))) valid++;
        if (valid == 0) {
            clear();
            return last = empty(State.INVALID);
        }
        int capacity = settings.points() * settings.historyFrames();
        if (ring == null) {
            ring = new float[2 * capacity];
            scratch = new float[2 * capacity];
        }
        for (int i = 0; i < n; i++) {
            float x = snapshot.i(i), y = snapshot.q(i);
            if (!Float.isFinite(x) || !Float.isFinite(y)) continue;
            ring[2 * write] = x;
            ring[2 * write + 1] = y;
            write = (write + 1) % capacity;
            count = Math.min(capacity, count + 1);
        }
        double maximum = 0;
        int start = (write - count + capacity) % capacity;
        for (int i = 0; i < count; i++) {
            int at = (start + i) % capacity;
            float x = ring[2 * at], y = ring[2 * at + 1];
            scratch[2 * i] = x;
            scratch[2 * i + 1] = y;
            maximum = Math.max(maximum, Math.max(Math.abs((double) x), Math.abs((double) y)));
        }
        timestamp = snapshot.timestampNanos();
        observed = true;
        double extent = settings.autoScale() ? (maximum == 0 ? 1 : Math.max(1e-12, maximum * 1.1)) : settings.scale();
        return last = new ConstellationFrame(State.READY, scratch, count, extent, timestamp);
    }

    TapStatus tapStatus() {
        return tap;
    }

    ConstellationFrame error() {
        clear();
        return last = empty(State.ERROR);
    }

    private void clear() {
        count = 0;
        write = 0;
        observed = false;
    }

    private ConstellationFrame empty(State state) {
        return new ConstellationFrame(state, EMPTY, 0, settings.scale(), 0);
    }
}
