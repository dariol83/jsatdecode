package eu.dariolucia.jsatdecode.ui.swing;

import eu.dariolucia.jsatdecode.api.config.BinaryValue;
import eu.dariolucia.jsatdecode.api.config.NullValue;
import eu.dariolucia.jsatdecode.api.monitoring.Monitorable;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.api.taps.ComplexSnapshot;
import eu.dariolucia.jsatdecode.api.taps.ComplexTap;
import eu.dariolucia.jsatdecode.api.taps.TapProvider;
import eu.dariolucia.jsatdecode.ui.swing.UiViewModel.LockState;
import eu.dariolucia.jsatdecode.ui.swing.UiViewModel.Quality;
import eu.dariolucia.jsatdecode.ui.swing.UiViewModel.StatusRow;
import eu.dariolucia.jsatdecode.ui.swing.UiViewModel.TapStatus;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * One-shot observer scheduler. Only its owned daemon worker calls providers or
 * computes view models; only the EDT invokes the display callback. A single
 * latest-model slot and at most one pending EDT update bound slow-display work.
 * Status/display failures stop this observer and notify its diagnostic callback;
 * an individual plot-analysis failure disables only that plot and warns once.
 * No runtime/source lifecycle method is called. Start/close are management work.
 */
public final class MonitoringUiController implements AutoCloseable {
    private SpectrumSettings spectrumSettings;
    private IntSupplier rfColumns, baseColumns;
    private SpectrumAnalysis rfAnalysis, baseAnalysis;
    private volatile int rfSmoothingLevel = SpectrumSettings.DEFAULT_RF_SMOOTHING_LEVEL;

    void rfSmoothingLevel(int level) {
        rfSmoothingLevel = level;
    }

    private SpectrumFrame rfFrame, baseFrame;
    private long nextRf, nextBase;
    private boolean rfFailed, baseFailed;
    private ConstellationSettings constellationSettings;
    private ConstellationAnalysis symbolAnalysis;
    private ConstellationFrame symbolFrame;
    private long nextSymbols;
    private boolean symbolsFailed;
    private final Monitorable monitoring;
    private final Supplier<TapProvider> taps;
    private final UiSettings settings;
    private final Consumer<UiViewModel> display;
    private final Consumer<Throwable> diagnostics;
    private final SignalQualityEstimator estimator;
    private final AtomicReference<UiViewModel> latest = new AtomicReference<>();
    private final AtomicBoolean active = new AtomicBoolean(), queued = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicLong polls = new AtomicLong(), coalesced = new AtomicLong();
    private ScheduledExecutorService executor;
    private volatile Thread worker;
    private boolean started, stopped;
    private long nextStatus, nextQuality;
    private Map<String, String> units;
    private List<StatusRow> rows = List.of();
    private List<TapStatus> tapStates = List.of();
    private int omitted;
    private boolean statusInitialized, qualityInitialized;
    private Quality quality = new Quality(Double.NaN, Double.NaN, false, LockState.UNKNOWN, LockState.UNKNOWN, LockState.UNKNOWN, 0);

    /**
     * Creates an inert observer; no Swing component or thread is allocated.
     *
     * @param monitoring  concurrent pull provider; reads must be bounded
     * @param taps        lazy optional-tap provider, called only by the observer worker
     * @param settings    immutable limits/cadence
     * @param display     consumes immutable models on the EDT, never live providers
     * @param diagnostics notified once on failure; must not throw or block
     */
    public MonitoringUiController(Monitorable monitoring, Supplier<TapProvider> taps, UiSettings settings,
                                  Consumer<UiViewModel> display, Consumer<Throwable> diagnostics) {
        this.monitoring = Objects.requireNonNull(monitoring);
        this.taps = Objects.requireNonNull(taps);
        this.settings = Objects.requireNonNull(settings);
        this.display = Objects.requireNonNull(display);
        this.diagnostics = Objects.requireNonNull(diagnostics);
        estimator = new SignalQualityEstimator(settings);
    }

    /**
     * Creates a spectrum observer. Width suppliers execute on the worker and
     * must read safely published widths, never Swing components.
     *
     * @param monitoring  monitoring provider
     * @param taps        optional taps
     * @param settings    status limits
     * @param spectra     spectrum limits
     * @param rfColumns   published RF drawable width
     * @param baseColumns published baseband drawable width
     * @param display     EDT consumer
     * @param diagnostics observer diagnostic
     */
    public MonitoringUiController(Monitorable monitoring, Supplier<TapProvider> taps, UiSettings settings,
                                  SpectrumSettings spectra, IntSupplier rfColumns, IntSupplier baseColumns,
                                  Consumer<UiViewModel> display, Consumer<Throwable> diagnostics) {
        this(monitoring, taps, settings, display, diagnostics);
        this.spectrumSettings = Objects.requireNonNull(spectra);
        this.rfColumns = Objects.requireNonNull(rfColumns);
        this.baseColumns = Objects.requireNonNull(baseColumns);
    }

    /**
     * Creates a complete plot observer with independent symbol cadence/history.
     * All provider and analysis work stays on the owned observer worker.
     *
     * @param monitoring    bounded monitoring provider
     * @param taps          optional tap supplier
     * @param settings      status/quality limits
     * @param spectra       spectrum limits
     * @param constellation symbol limits
     * @param rfColumns     safely published RF width
     * @param baseColumns   safely published baseband width
     * @param display       immutable models consumed on EDT
     * @param diagnostics   general observer failure callback
     */
    public MonitoringUiController(Monitorable monitoring, Supplier<TapProvider> taps, UiSettings settings,
                                  SpectrumSettings spectra, ConstellationSettings constellation, IntSupplier rfColumns, IntSupplier baseColumns,
                                  Consumer<UiViewModel> display, Consumer<Throwable> diagnostics) {
        this(monitoring, taps, settings, spectra, rfColumns, baseColumns, display, diagnostics);
        this.constellationSettings = Objects.requireNonNull(constellation);
    }

    /**
     * Starts a daemon poller with no fixed-rate catch-up queue. Calls after start
     * or stop fail; provider and Swing failures remain isolated to this observer.
     */
    public synchronized void start() {
        if (started || stopped) throw new IllegalStateException("UI observer is one-shot");
        started = true;
        active.set(true);
        try {
            executor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "jsatdecode-ui-observer");
                thread.setDaemon(true);
                worker = thread;
                return thread;
            });
            double hz = Math.max(settings.statusHz(), settings.qualityHz());
            if (spectrumSettings != null)
                hz = Math.max(hz, Math.max(spectrumSettings.rfHz(), spectrumSettings.basebandHz()));
            if (constellationSettings != null) hz = Math.max(hz, constellationSettings.refreshHz());
            long period = (long) (1_000_000_000 / hz);
            executor.scheduleWithFixedDelay(this::poll, 0, period, TimeUnit.NANOSECONDS);
        } catch (RuntimeException | Error e) {
            if (fatal(e)) throw e;
            fail(e);
        }
    }

    private void poll() {
        if (!active.get()) return;
        try {
            long now = System.nanoTime();
            MonitoringSnapshot snapshot = monitoring.monitoringSnapshot();
            polls.incrementAndGet();
            if (units == null) {
                units = new HashMap<>();
                int read = 0;
                for (var descriptor : monitoring.monitoringDescriptors()) {
                    if (read++ >= settings.maximumMetrics()) break;
                    units.put(descriptor.key(), text(descriptor.unit()));
                }
            }
            TapProvider provider = Objects.requireNonNull(taps.get());
            if (spectrumSettings != null) updateSpectra(provider, snapshot, now);
            if (constellationSettings != null) updateSymbols(provider, now);
            if (!statusInitialized || now - nextStatus >= 0) {
                List<StatusRow> current = new ArrayList<>();
                for (var entry : snapshot.values().entrySet()) {
                    if (current.size() == settings.maximumMetrics()) break;
                    current.add(new StatusRow(text(entry.getKey()), format(entry.getValue()), units.getOrDefault(entry.getKey(), "")));
                }
                current.sort(Comparator.comparing(StatusRow::metric));
                rows = List.copyOf(current);
                omitted = Math.max(0, snapshot.values().size() - rows.size());
                tapStates = spectrumSettings == null ? readTaps(provider) : List.of(rfAnalysis.tapStatus(), baseAnalysis.tapStatus(),
                        constellationSettings == null ? tap("Constellation", provider.symbols()) : symbolAnalysis.tapStatus());
                nextStatus = now + (long) (1_000_000_000 / settings.statusHz());
                statusInitialized = true;
            }
            if (!qualityInitialized || now - nextQuality >= 0) {
                quality = estimator.update(snapshot);
                nextQuality = now + (long) (1_000_000_000 / settings.qualityHz());
                qualityInitialized = true;
            }
            if (active.get())
                publish(new UiViewModel(rows, omitted, quality, tapStates, snapshot.timestampNanos(), spectrumSettings == null ? List.of() : List.of(rfFrame, baseFrame), symbolFrame));
        } catch (RuntimeException | Error e) {
            if (fatal(e)) throw e;
            fail(e);
        }
    }

    private void updateSpectra(TapProvider provider, MonitoringSnapshot snapshot, long now) {
        if (rfAnalysis == null) {
            rfAnalysis = new SpectrumAnalysis("RF spectrum", spectrumSettings);
            baseAnalysis = new SpectrumAnalysis("Baseband spectrum", spectrumSettings);
        }
        if (rfFrame == null || now - nextRf >= 0) {
            if (!rfFailed) {
                try {
                    double center = spectrumSettings.rfCenterHz();
                    if (Double.isNaN(center)) {
                        Object value = snapshot.values().get("source.center_frequency_hz");
                        center = observedCenter(value);
                        if (Double.isNaN(center))
                            center = observedCenter(snapshot.values().get("source.center_frequency"));
                    }
                    rfAnalysis.smoothingLevel(rfSmoothingLevel);
                    rfFrame = rfAnalysis.analyze(provider.rf(), rfColumns.getAsInt(), Double.isNaN(center) ? 0 : center, !Double.isNaN(center));
                } catch (RuntimeException | Error e) {
                    if (fatal(e)) throw e;
                    rfFailed = true;
                    rfFrame = rfAnalysis.error();
                    System.err.println("RF spectrum disabled: " + e);
                }
            }
            nextRf = now + (long) (1_000_000_000 / spectrumSettings.rfHz());
        }
        if (baseFrame == null || now - nextBase >= 0) {
            if (!baseFailed) {
                try {
                    baseFrame = baseAnalysis.analyze(provider.baseband(), baseColumns.getAsInt(), 0, false);
                } catch (RuntimeException | Error e) {
                    if (fatal(e)) throw e;
                    baseFailed = true;
                    baseFrame = baseAnalysis.error();
                    System.err.println("Baseband spectrum disabled: " + e);
                }
            }
            nextBase = now + (long) (1_000_000_000 / spectrumSettings.basebandHz());
        }
    }

    private void updateSymbols(TapProvider provider, long now) {
        if (symbolAnalysis == null) symbolAnalysis = new ConstellationAnalysis(constellationSettings);
        if (symbolFrame == null || now - nextSymbols >= 0) {
            if (!symbolsFailed) {
                try {
                    symbolFrame = symbolAnalysis.analyze(provider.symbols());
                } catch (RuntimeException | Error e) {
                    if (fatal(e)) throw e;
                    symbolsFailed = true;
                    symbolFrame = symbolAnalysis.error();
                    System.err.println("Constellation disabled: " + e);
                }
            }
            nextSymbols = now + (long) (1_000_000_000 / constellationSettings.refreshHz());
        }
    }

    private static double observedCenter(Object value) {
        return value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() > 0 ? n.doubleValue() : Double.NaN;
    }

    private List<TapStatus> readTaps(TapProvider provider) {
        return List.of(tap("RF spectrum", provider.rf()), tap("Baseband spectrum", provider.baseband()), tap("Constellation", provider.symbols()));
    }

    private TapStatus tap(String name, Optional<ComplexTap> value) {
        if (value.isEmpty()) return new TapStatus(name, false, 0, Double.NaN);
        ComplexSnapshot snapshot = value.orElseThrow().snapshot(settings.tapSamples());
        if (snapshot.complexSamples() > settings.tapSamples())
            throw new IllegalStateException("Tap exceeded requested snapshot bound");
        return new TapStatus(name, true, snapshot.complexSamples(), snapshot.sampleRate());
    }

    private String text(String value) {
        int maximum = settings.maximumTextCharacters();
        return value.length() <= maximum ? value : value.substring(0, maximum - 1) + "\u2026";
    }

    private String format(Object value) {
        if (value instanceof BinaryValue binary) return binary.size() + " bytes";
        if (value == NullValue.INSTANCE) return "null";
        if (value instanceof Map<?, ?> map) return map.size() + " entries";
        if (value instanceof List<?> list) return list.size() + " values";
        if (value instanceof Double d && !Double.isFinite(d) || value instanceof Float f && !Float.isFinite(f))
            return "Unavailable";
        return text(value instanceof Enum<?> e ? e.name() : String.valueOf(value));
    }

    private void publish(UiViewModel model) {
        if (latest.getAndSet(model) != null) coalesced.incrementAndGet();
        queueUpdate();
    }

    private void queueUpdate() {
        if (active.get() && queued.compareAndSet(false, true)) SwingUtilities.invokeLater(this::applyLatest);
    }

    private void applyLatest() {
        try {
            UiViewModel model = latest.getAndSet(null);
            if (active.get() && model != null) display.accept(model);
        } catch (RuntimeException | Error e) {
            if (fatal(e)) throw e;
            fail(e);
        } finally {
            queued.set(false);
            if (active.get() && latest.get() != null) queueUpdate();
        }
    }

    private void fail(Throwable error) {
        if (failure.compareAndSet(null, error)) {
            stop();
            try {
                diagnostics.accept(error);
            } catch (RuntimeException ignored) { /* Diagnostics must not escape to runtime/DSP. */ }
        }
    }

    @SuppressWarnings("removal") // Do not consume legacy thread termination or VM resource failures.
    private static boolean fatal(Throwable error) {
        return error instanceof VirtualMachineError || error instanceof ThreadDeath;
    }

    /**
     * Disables observation without joining, safe for a window-close callback on
     * the EDT. It never stops the source/runtime. A currently executing provider
     * read may finish, but its model will not be displayed.
     */
    public synchronized void stop() {
        stopped = true;
        active.set(false);
        latest.set(null);
        if (executor != null) executor.shutdown();
    }

    /**
     * Stops and joins for at most two seconds on a management caller, preserving
     * interruption. EDT/observer calls only signal stop, avoiding self/deadlock.
     * Providers must honor their bounded-read contract; a stuck daemon cannot
     * delay decoder shutdown beyond this join budget.
     */
    @Override
    public void close() {
        stop();
        ScheduledExecutorService owned;
        synchronized (this) {
            owned = executor;
        }
        if (owned == null || SwingUtilities.isEventDispatchThread() || Thread.currentThread() == worker) return;
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!owned.isTerminated() && System.nanoTime() < deadline) {
            try {
                owned.awaitTermination(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /**
     * Returns whether future UI updates are enabled.
     *
     * @return current observer activity
     */
    public boolean isActive() {
        return active.get();
    }

    /**
     * Reports safely published local diagnostics without provider reads.
     *
     * @return first observer failure, if any
     */
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure.get());
    }

    /**
     * Returns completed provider polls for diagnostics.
     *
     * @return poll count
     */
    public long polls() {
        return polls.get();
    }

    /**
     * Returns updates replaced in the single pending slot.
     *
     * @return coalesced update count; functional data is unaffected
     */
    public long coalescedUpdates() {
        return coalesced.get();
    }
}
