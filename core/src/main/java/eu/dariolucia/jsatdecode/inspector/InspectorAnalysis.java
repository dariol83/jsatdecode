package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.api.taps.ComplexSnapshot;
import eu.dariolucia.jsatdecode.core.buffers.ComplexFloatRingBuffer;
import eu.dariolucia.jsatdecode.dsp.fft.ComplexFft;
import eu.dariolucia.jsatdecode.dsp.fft.ExponentialAverager;
import eu.dariolucia.jsatdecode.dsp.fft.Spectrum;
import eu.dariolucia.jsatdecode.dsp.fft.Window;
import eu.dariolucia.jsatdecode.ui.swing.SpectrumFrame;
import eu.dariolucia.jsatdecode.ui.swing.SpectrumSettings;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/* One source producer publishes bounded latest samples. Only this application's
   FFT worker owns plans, scratch and linear-power EMA; no Swing dependency. */
final class InspectorAnalysis implements AutoCloseable {
    private static final int MAX_SAMPLES = 131072;
    private static final int MAX_COLUMNS = 4096;
    private static final long SECOND = 1_000_000_000L;
    private final AtomicInteger columns = new AtomicInteger(1024);
    private final ArrayBlockingQueue<SpectrumFrame> waterfall = new ArrayBlockingQueue<>(64);
    private volatile InspectorSettings requested;
    private volatile Input input;
    private volatile SpectrumFrame latest;
    private volatile boolean running;
    private boolean closed;
    private Thread worker;

    // The following state belongs exclusively to the FFT worker.
    private InspectorSettings configured;
    private Input observed;
    private SpectrumSettings spectrumSettings;
    private ComplexFft fft;
    private Window window;
    private ExponentialAverager average;
    private double[] real, imaginary, power, shifted, reduced;
    private SpectrumFrame analyzedFrame;
    private long analyzedSamples = -1, analyzedSnapshotTime, waterfallSnapshotTime, lastWaterfall, missedSnapshots;

    InspectorAnalysis(InspectorSettings settings) {
        requested = Objects.requireNonNull(settings);
        input = new Input(settings.effectiveSampleRate());
        latest = empty(settings, SpectrumFrame.State.WAITING);
    }

    synchronized void start() {
        if (closed) throw new IllegalStateException("Analysis is closed");
        if (running) return;
        running = true;
        worker = new Thread(this::run, "jsatinspector-fft");
        worker.setDaemon(true);
        worker.start();
    }

    /* Source callback: bounded primitive copies only. The ring intentionally
       overwrites observation history; it is independent of lossless recording.
       Exactly one externally synchronized source thread may call accept. */
    void accept(ComplexSampleBlock block) {
        Input target = input;
        int count = Math.min(block.complexSamples(), MAX_SAMPLES);
        if (count == 0) return;
        int begin = block.complexSamples() - count;
        for (int i = 0; i < count; i++) {
            target.copy[2 * i] = block.i(begin + i);
            target.copy[2 * i + 1] = block.q(begin + i);
        }
        // One outer revision binds the source timestamp/count to the ring's
        // coherent samples without making the producer wait for its observer.
        target.revision++;
        target.ring.write(target.copy, count);
        target.timestampNanos = block.timestampNanos();
        target.samples += block.complexSamples();
        target.revision++;
    }

    void updateSettings(InspectorSettings settings) {
        Objects.requireNonNull(settings);
        InspectorSettings previous = requested;
        if (previous.effectiveSampleRate() != settings.effectiveSampleRate() || previous.frequencyHz() != settings.frequencyHz())
            input = new Input(settings.effectiveSampleRate());
        requested = settings;
        Thread current = worker;
        if (current != null) LockSupport.unpark(current);
    }

    void setColumns(int value) {
        columns.set(Math.max(0, Math.min(value, MAX_COLUMNS)));
    }

    SpectrumFrame latest() {
        return latest;
    }

    // Nonblocking EDT polling; at most 64 immutable rows can await display.
    SpectrumFrame pollWaterfall() {
        return waterfall.poll();
    }

    @Override
    public void close() {
        Thread current;
        synchronized (this) {
            closed = true;
            running = false;
            current = worker;
        }
        if (current == null || current == Thread.currentThread()) return;
        current.interrupt();
        boolean interrupted = false;
        while (current.isAlive()) {
            try {
                current.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void run() {
        lastWaterfall = System.nanoTime();
        long nextSpectrum = 0;
        try {
            while (running) {
                InspectorSettings settings = requested;
                Input current = input;
                long now = System.nanoTime();
                if (!sameAnalysis(configured, settings) || observed != current) {
                    configure(settings, current, now);
                    nextSpectrum = now;
                } else {
                    // Cadence-only edits retain plans, traces and averaging,
                    // and restart only the corresponding display deadline.
                    if (configured.fftRate() != settings.fftRate()) nextSpectrum = now;
                    if (configured.waterfallRate() != settings.waterfallRate()) lastWaterfall = now;
                    configured = settings;
                }
                long waterfallPeriod = SECOND / settings.waterfallRate();
                boolean spectrumDue = now - nextSpectrum >= 0;
                boolean waterfallDue = now - lastWaterfall >= waterfallPeriod;
                if (spectrumDue || waterfallDue) analyze(current, settings);
                if (spectrumDue) {
                    latest = analyzedFrame;
                    nextSpectrum = System.nanoTime() + (long) (SECOND / settings.fftRate());
                }
                // Wall-clock cadence, with no catch-up rows and no repeated idle
                // observations. A completed new trace can contribute only once.
                if (waterfallDue) {
                    if (analyzedFrame.state() == SpectrumFrame.State.READY && analyzedSnapshotTime != waterfallSnapshotTime) {
                        if (!waterfall.offer(analyzedFrame)) {
                            waterfall.poll();
                            waterfall.offer(analyzedFrame);
                            System.err.println("JSatInspector waterfall overrun: oldest display row discarded");
                        }
                        waterfallSnapshotTime = analyzedSnapshotTime;
                    }
                    lastWaterfall = System.nanoTime();
                }
                // Fixed delay bounds CPU and prevents a slow large FFT from
                // building a task backlog. An independent waterfall deadline
                // permits faster rows even with a 0.5-Hz spectrum rate.
                long deadline = Math.min(nextSpectrum, lastWaterfall + waterfallPeriod);
                LockSupport.parkNanos(Math.max(1_000_000, deadline - System.nanoTime()));
                if (Thread.interrupted() && !running) break;
            }
        } catch (RuntimeException e) {
            System.err.println("JSatInspector FFT worker failed: " + e);
            e.printStackTrace(System.err);
            latest = empty(requested, SpectrumFrame.State.ERROR);
        } finally {
            running = false;
        }
    }

    private void configure(InspectorSettings settings, Input current, long now) {
        configured = settings;
        observed = current;
        spectrumSettings = displaySettings(settings);
        fft = new ComplexFft(settings.fftSize());
        window = new Window(settings.window(), settings.fftSize());
        average = settings.averaging() == 0 ? null : new ExponentialAverager(settings.fftSize(), 1.0 / (1 << settings.averaging()));
        real = new double[settings.fftSize()];
        imaginary = new double[settings.fftSize()];
        power = new double[settings.fftSize()];
        shifted = new double[settings.fftSize()];
        reduced = null;
        analyzedSamples = -1;
        analyzedSnapshotTime = 0;
        waterfallSnapshotTime = 0;
        lastWaterfall = now;
        missedSnapshots = current.ring.missedSnapshots();
        waterfall.clear();
        latest = analyzedFrame = empty(settings, SpectrumFrame.State.WAITING);
    }

    private void analyze(Input current, InspectorSettings settings) {
        long revision = current.revision;
        if ((revision & 1) != 0) return;
        long available = current.samples;
        long timestamp = current.timestampNanos;
        int width = Math.min(settings.fftSize(), columns.get());
        if (width == 0) return;
        if (available == analyzedSamples) {
            // Resizing uses already analyzed dB bins and must not advance EMA.
            if (analyzedFrame.state() == SpectrumFrame.State.READY && reduced.length != width) {
                reduced = new double[width];
                Spectrum.reduceMaximum(power, reduced);
                analyzedFrame = new SpectrumFrame("RF spectrum", SpectrumFrame.State.READY, spectrumSettings, current.ring.sampleRate(),
                        settings.frequencyHz(), true, analyzedFrame.timestampNanos(), reduced);
            }
            return;
        }
        ComplexSnapshot snapshot = current.ring.snapshotLatest(settings.fftSize());
        if (current.revision != revision) return;
        long misses = current.ring.missedSnapshots();
        if (misses != missedSnapshots) {
            System.err.println("JSatInspector FFT snapshot underrun: " + (misses - missedSnapshots) + " observations skipped under contention");
            missedSnapshots = misses;
        }
        if (snapshot.complexSamples() != settings.fftSize()) return;
        // Retain the ring's own observation identity as an additional guard
        // against advancing averaging twice for the same completed snapshot.
        if (snapshot.timestampNanos() == analyzedSnapshotTime) {
            analyzedSamples = available;
            return;
        }
        analyzedSnapshotTime = snapshot.timestampNanos();
        for (int i = 0; i < settings.fftSize(); i++) {
            real[i] = snapshot.i(i);
            imaginary[i] = snapshot.q(i);
            if (!Double.isFinite(real[i]) || !Double.isFinite(imaginary[i])) {
                if (average != null) average.reset();
                analyzedFrame = empty(settings, SpectrumFrame.State.INVALID);
                analyzedSamples = available;
                return;
            }
        }
        window.apply(real, imaginary);
        fft.forward(real, imaginary);
        Spectrum.power(real, imaginary, power);
        Spectrum.fftShift(power, shifted);
        if (average != null) average.update(shifted, shifted);
        Spectrum.powerToDb(shifted, power, settings.minDb());
        if (reduced == null || reduced.length != width) reduced = new double[width];
        Spectrum.reduceMaximum(power, reduced);
        analyzedFrame = new SpectrumFrame("RF spectrum", SpectrumFrame.State.READY, spectrumSettings, current.ring.sampleRate(),
                settings.frequencyHz(), true, timestamp, reduced);
        analyzedSamples = available;
    }

    private static boolean sameAnalysis(InspectorSettings a, InspectorSettings b) {
        return a != null && a.fftSize() == b.fftSize() && a.window() == b.window() && a.averaging() == b.averaging() &&
                a.minDb() == b.minDb() && a.maxDb() == b.maxDb() && a.frequencyHz() == b.frequencyHz() &&
                a.effectiveSampleRate() == b.effectiveSampleRate();
    }

    private static SpectrumSettings displaySettings(InspectorSettings settings) {
        return new SpectrumSettings(settings.fftSize(), settings.window(), settings.fftRate(), settings.fftRate(),
                1, settings.minDb(), settings.maxDb(), MAX_COLUMNS, settings.frequencyHz());
    }

    private static SpectrumFrame empty(InspectorSettings settings, SpectrumFrame.State state) {
        return new SpectrumFrame("RF spectrum", state, displaySettings(settings), settings.effectiveSampleRate(),
                settings.frequencyHz(), true, 0, new double[0]);
    }

    private static final class Input {
        final ComplexFloatRingBuffer ring;
        final float[] copy = new float[MAX_SAMPLES * 2];
        volatile long revision;
        volatile long samples;
        volatile long timestampNanos;

        Input(double sampleRate) {
            ring = new ComplexFloatRingBuffer(MAX_SAMPLES, sampleRate);
        }
    }
}
