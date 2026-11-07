package eu.dariolucia.jsatdecode.ui.swing;

import eu.dariolucia.jsatdecode.api.taps.ComplexSnapshot;
import eu.dariolucia.jsatdecode.api.taps.ComplexTap;
import eu.dariolucia.jsatdecode.dsp.fft.ComplexFft;
import eu.dariolucia.jsatdecode.dsp.fft.ExponentialAverager;
import eu.dariolucia.jsatdecode.dsp.fft.Spectrum;
import eu.dariolucia.jsatdecode.dsp.fft.Window;
import eu.dariolucia.jsatdecode.ui.swing.SpectrumFrame.State;
import eu.dariolucia.jsatdecode.ui.swing.UiViewModel.TapStatus;

import java.util.Optional;

/* One observer worker owns this stream, its plans, all scratch and EMA history.
   Processing requires a complete N-sample snapshot; partial/empty observations
   never become zero-padded spectra or update averaging. No Swing dependency. */
final class SpectrumAnalysis {
    private static final double[] EMPTY = new double[0];
    private final String name;
    private final SpectrumSettings settings;
    private ComplexFft fft;
    private Window window;
    private ExponentialAverager average;
    private int smoothingLevel;

    void smoothingLevel(int level) {
        if (level < 0 || level > 4) throw new IllegalArgumentException("Invalid smoothing level");
        if (level == smoothingLevel) return;
        smoothingLevel = level;
        if (fft != null) configureAverage();
    }

    private void configureAverage() {
        double alpha = settings.averageAlpha() / (1 << smoothingLevel);
        average = alpha < 1 ? new ExponentialAverager(settings.fftSize(), alpha) : null;
        rangeReady = false;
    }

    private double[] real, imaginary, power, shifted, reduced;
    private double rate = Double.NaN, center = Double.NaN;
    private boolean absolute;
    private SpectrumFrame last;
    private TapStatus tap;
    private boolean rangeReady;
    private long rangeUpdated;
    private double displayFloor, displayCeiling;

    SpectrumAnalysis(String name, SpectrumSettings settings) {
        this.name = name;
        this.settings = settings;
        tap = new TapStatus(name, false, 0, Double.NaN);
        last = empty(State.WAITING, 0);
    }

    SpectrumFrame analyze(Optional<ComplexTap> provider, int requestedColumns, double centerHz, boolean absoluteFrequency) {
        return analyze(provider, requestedColumns, centerHz, absoluteFrequency, System.nanoTime());
    }

    SpectrumFrame analyze(Optional<ComplexTap> provider, int requestedColumns, double centerHz, boolean absoluteFrequency, long now) {
        if (provider.isEmpty()) {
            reset();
            tap = new TapStatus(name, false, 0, Double.NaN);
            return last = empty(State.UNAVAILABLE, 0);
        }
        ComplexSnapshot snapshot = provider.orElseThrow().snapshot(settings.fftSize());
        int count = snapshot.complexSamples();
        if (count > settings.fftSize())
            throw new IllegalStateException("Spectrum tap exceeded requested snapshot bound");
        tap = new TapStatus(name, true, count, snapshot.sampleRate());
        if (last.state() == State.UNAVAILABLE) last = empty(State.WAITING, snapshot.timestampNanos());
        if (snapshot.sampleRate() != rate || centerHz != center || absoluteFrequency != absolute) {
            reset();
            rate = snapshot.sampleRate();
            center = centerHz;
            absolute = absoluteFrequency;
            last = empty(State.WAITING, snapshot.timestampNanos());
        }
        if (!(rate / settings.fftSize() > 0) || !Double.isFinite(center) || !Double.isFinite(center - rate / 2) ||
                !Double.isFinite(center + rate / 2) || center - rate / 2 == center + rate / 2) {
            reset();
            return last = empty(State.INVALID, snapshot.timestampNanos());
        }
        if (count < settings.fftSize() || requestedColumns <= 0) return last;
        if (fft == null) allocate();
        // Validate before mutating averaging. Snapshot indexed reads avoid an
        // additional temporary float-IQ array on every analysis observation.
        for (int i = 0; i < count; i++) {
            double r = snapshot.i(i), q = snapshot.q(i);
            if (!Double.isFinite(r) || !Double.isFinite(q)) {
                reset();
                return last = empty(State.INVALID, snapshot.timestampNanos());
            }
            real[i] = r;
            imaginary[i] = q;
        }
        window.apply(real, imaginary);
        fft.forward(real, imaginary);
        Spectrum.power(real, imaginary, power);
        Spectrum.fftShift(power, shifted);
        if (average != null) average.update(shifted, shifted);
        Spectrum.powerToDb(shifted, power, settings.floorDb());
        // Adapt each plot independently from all analyzed bins, before display
        // reduction can hide low levels. Only the observer worker scans the data.
        if (!rangeReady || now - rangeUpdated >= 1_000_000_000L) {
            double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
            for (double db : power)
                if (Double.isFinite(db)) {
                    minimum = Math.min(minimum, db);
                    maximum = Math.max(maximum, db);
                }
            if (minimum <= maximum) {
                displayFloor = minimum - 10;
                displayCeiling = maximum + 10;
                rangeUpdated = now;
                rangeReady = true;
            }
        }
        int columns = Math.min(Math.min(requestedColumns, settings.maximumColumns()), settings.fftSize());
        if (reduced == null || reduced.length != columns) reduced = new double[columns];
        Spectrum.reduceMaximum(power, reduced);
        return last = new SpectrumFrame(name, State.READY, settings, rate, center, absolute, snapshot.timestampNanos(), reduced, displayFloor, displayCeiling);
    }

    SpectrumFrame error() {
        reset();
        return last = empty(State.ERROR, 0);
    }

    TapStatus tapStatus() {
        return tap;
    }

    private void reset() {
        if (average != null) average.reset();
        rangeReady = false;
    }

    private SpectrumFrame empty(State state, long timestamp) {
        return new SpectrumFrame(name, state, settings, rate, Double.isNaN(center) ? 0 : center, absolute, timestamp, EMPTY);
    }

    private void allocate() {
        int size = settings.fftSize();
        fft = new ComplexFft(size);
        window = new Window(settings.window(), size);
        real = new double[size];
        imaginary = new double[size];
        power = new double[size];
        shifted = new double[size];
        configureAverage();
    }
}
