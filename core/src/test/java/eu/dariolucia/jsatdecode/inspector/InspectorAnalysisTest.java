package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.dsp.fft.Window;
import eu.dariolucia.jsatdecode.dsp.fft.WindowFunction;
import eu.dariolucia.jsatdecode.ui.swing.SpectrumFrame;
import eu.dariolucia.jsatdecode.ui.swing.SpectrumPanel;
import eu.dariolucia.jsatdecode.ui.swing.SpectrumSettings;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.TimeUnit;

/** Synthetic checks for the standalone observer, without PlutoPlus hardware. */
public final class InspectorAnalysisTest {
    private static int checks;

    private InspectorAnalysisTest() { }

    /**
     * Checks tone placement through 131072 bins, smoothing, cadence and rendering.
     *
     * @param args ignored
     * @throws Exception if an assertion or worker operation fails
     */
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        tones();
        averagingAndResize();
        cadence();
        slowSpectrumCadence();
        liveWaterfallCadence();
        render();
        System.out.println("JSatInspector analysis: " + checks + " checks passed");
    }

    private static void tones() throws Exception {
        long index = 1;
        for (int size = 1024; size <= 131072; size *= 2) {
            for (WindowFunction function : WindowFunction.values()) {
                InspectorSettings settings = settings(size, function, 0);
                try (InspectorAnalysis analysis = new InspectorAnalysis(settings)) {
                    analysis.setColumns(4096);
                    analysis.start();
                    for (int bin : new int[]{-size / 2, -size / 8, 0, size / 8, size / 2 - 1}) {
                        analysis.accept(tone(size, bin, 0.5, settings.effectiveSampleRate(), index));
                        SpectrumFrame frame = await(analysis, index++);
                        int peak = peak(frame);
                        near(frame.frequencyAtColumn(peak), settings.frequencyHz() + bin * (double) settings.effectiveSampleRate() / size,
                                settings.effectiveSampleRate() / (double) frame.columns(), "Signed tone frequency through maximum FFT size");
                        near(frame.dbAt(peak), 20 * Math.log10(size * 0.5 * new Window(function, size).coherentGain()),
                                0.001, "Unnormalized windowed tone power");
                        check(frame.floorDb() == settings.minDb() && frame.ceilingDb() == settings.maxDb(), "Manual range remains fixed");
                        check(frame.columns() <= 4096, "Display columns bounded");
                    }
                }
            }
        }
    }

    private static void averagingAndResize() throws Exception {
        InspectorSettings settings = settings(1024, WindowFunction.RECTANGULAR, 2);
        try (InspectorAnalysis analysis = new InspectorAnalysis(settings)) {
            analysis.start();
            analysis.accept(tone(1024, 64, 1, settings.effectiveSampleRate(), 1));
            SpectrumFrame original = await(analysis, 1);
            analysis.accept(tone(1024, 64, 0.5, settings.effectiveSampleRate(), 2));
            SpectrumFrame averaged = await(analysis, 2);
            near(averaged.dbAt(peak(averaged)), 10 * Math.log10(1024.0 * 1024 * 0.8125), 0.001, "EMA averages linear power");
            near(original.dbAt(peak(original)), 20 * Math.log10(1024), 0.001, "Published frame remains immutable");
            analysis.setColumns(37);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (analysis.latest().columns() != 37 && System.nanoTime() < deadline) Thread.sleep(5);
            check(analysis.latest().columns() == 37, "Idle trace resizes without new input");
            near(analysis.latest().dbAt(peak(analysis.latest())), averaged.dbAt(peak(averaged)), 1e-8, "Resizing does not advance EMA");
            check(analysis.latest().floorDb() == -140 && analysis.latest().ceilingDb() == 100, "Resizing retains manual limits");
            InspectorSettings changed = settings(4096, WindowFunction.HANN, 0);
            analysis.updateSettings(changed);
            analysis.accept(tone(4096, 32, 0.25, changed.effectiveSampleRate(), 3));
            check(await(analysis, 3).fftSize() == 4096, "Live FFT plan replacement");
        }
    }

    private static void cadence() throws Exception {
        InspectorSettings settings = settings(1024, WindowFunction.RECTANGULAR, 0);
        try (InspectorAnalysis analysis = new InspectorAnalysis(settings)) {
            analysis.start();
            analysis.accept(tone(1024, 16, 0.5, settings.effectiveSampleRate(), 1));
            await(analysis, 1);
            check(analysis.pollWaterfall() == null, "Waterfall waits for its default four-lines-per-second cadence");
            SpectrumFrame first = awaitWaterfall(analysis);
            check(first.timestampNanos() == 1, "First waterfall row uses completed input");
            Thread.sleep(650);
            check(analysis.pollWaterfall() == null, "Idle input never generates repeated waterfall rows");
            analysis.accept(tone(1024, -16, 0.25, settings.effectiveSampleRate(), 2));
            await(analysis, 2);
            check(awaitWaterfall(analysis).timestampNanos() == 2, "New processed input resumes waterfall");
            check(analysis.pollWaterfall() == null, "No queued catch-up rows");
        }
    }

    private static void render() throws Exception {
        InspectorSettings settings = settings(1024, WindowFunction.HANN, 0);
        SpectrumFrame frame;
        try (InspectorAnalysis analysis = new InspectorAnalysis(settings)) {
            analysis.start();
            analysis.accept(tone(1024, 40, 0.5, settings.effectiveSampleRate(), 7));
            frame = await(analysis, 7);
        }
        SwingUtilities.invokeAndWait(() -> {
            SpectrumSettings display = new SpectrumSettings(1024, WindowFunction.HANN, 15, 15, 1, -140, 100, 4096, settings.frequencyHz());
            SpectrumPanel spectrum = new SpectrumPanel("Inspector test", display, value -> { });
            spectrum.setSize(720, 240);
            spectrum.range(false, -139.5, 99.5);
            spectrum.apply(frame);
            WaterfallPanel waterfall = new WaterfallPanel();
            waterfall.setSize(720, 240);
            waterfall.range(-139.5, 99.5);
            // More than the retained image depth exercises bounded scrolling.
            for (int i = 0; i < 1100; i++) waterfall.append(frame);
            BufferedImage image = new BufferedImage(720, 240, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                spectrum.paint(graphics);
                waterfall.paint(graphics);
            } finally {
                graphics.dispose();
            }
            int plot = image.getRGB(SpectrumPanel.LEFT, 100);
            check(image.getRGB(SpectrumPanel.LEFT - 1, 100) != plot, "Waterfall starts at shared spectrum left inset");
            check(image.getRGB(720 - SpectrumPanel.RIGHT, 100) != image.getRGB(719 - SpectrumPanel.RIGHT, 100),
                    "Waterfall ends at shared spectrum right inset");
            waterfall.clear();
            spectrum.apply(null);
            waterfallTiming(display);
        });
    }

    private static void waterfallTiming(SpectrumSettings display) {
        WaterfallPanel waterfall = new WaterfallPanel();
        long second = TimeUnit.SECONDS.toNanos(1);
        long started = 1_800_000_000L * second;
        for (int rate : new int[]{1, 4, 15}) {
            waterfall.clear();
            waterfall.setSize(720, 232); // 200 visible raster rows.
            for (int i = 0; i < 200; i++)
                waterfall.append(timedFrame(display, started + Math.round(i * (double) second / rate), 3));
            near(waterfall.visibleAgeSeconds(), 199.0 / rate, 1e-9,
                    "Waterfall elapsed time follows source observations at " + rate + " lines per second");
            waterfall.setSize(480, 132); // Only the newest 100 rows are visible.
            near(waterfall.visibleAgeSeconds(), 99.0 / rate, 1e-9, "Shorter viewport labels only visible history");
            waterfall.setSize(960, 432);
            near(waterfall.visibleAgeSeconds(), 199.0 / rate, 1e-9, "Growing the viewport retains observation ages");
            waterfall.append(timedFrame(display, started + Math.round(200 * (double) second / rate), 7));
            near(waterfall.visibleAgeSeconds(), 200.0 / rate, 1e-9, "Changing spectrum columns retains waterfall times");
        }

        // Retain history across a live 1 -> 4 -> 15 lines/s change. The time axis
        // must reflect each historical interval rather than the current setting.
        waterfall.clear();
        waterfall.append(timedFrame(display, started, 3));
        for (int i = 1; i <= 100; i++) waterfall.append(timedFrame(display, started + i * second, 3));
        for (int i = 1; i <= 100; i++) waterfall.append(timedFrame(display, started + 100 * second + i * second / 4, 3));
        for (int i = 1; i <= 100; i++)
            waterfall.append(timedFrame(display, started + 125 * second + Math.round(i * (double) second / 15), 3));
        near(waterfall.visibleAgeSeconds(), 125 + 100.0 / 15, 1e-9, "Mixed cadence history retains its actual elapsed span");
        waterfall.setSize(720, 132);
        near(waterfall.visibleAgeSeconds(), 99.0 / 15, 1e-9, "Mixed cadence viewport can show only the newest fast interval");
        waterfall.setSize(720, 232);
        near(waterfall.visibleAgeSeconds(), 99.0 / 4 + 100.0 / 15, 1e-9, "Visible history can span multiple waterfall cadences");

        waterfall.clear();
        check(waterfall.visibleAgeSeconds() == 0, "Clearing removes waterfall timestamp history");
        waterfall.setSize(720, 1132);
        for (int i = 0; i < 1300; i++) waterfall.append(timedFrame(display, started + i * second / 4, 3));
        near(waterfall.visibleAgeSeconds(), 1023.0 / 4, 1e-9, "Timestamp history wraps with the bounded 1024-row raster");
        waterfall.setSize(720, 232);
        near(waterfall.visibleAgeSeconds(), 199.0 / 4, 1e-9, "Wrapped history preserves the visible row ages");
        waterfall.append(timedFrame(display, started + 1299 * second / 4 + 2 * second, 7));
        near(waterfall.visibleAgeSeconds(), 2 + 198.0 / 4, 1e-9, "Source gaps remain visible after history wrap and column resizing");
        BufferedImage image = new BufferedImage(720, 232, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            waterfall.paint(graphics);
            waterfall.append(timedFrame(display, started + 1299 * second / 4 + 2 * second + Math.round(second / 15.0), 7));
            waterfall.setSize(720, 34); // Two rows display a millisecond age label.
            near(waterfall.visibleAgeSeconds(), 1.0 / 15, 1e-9, "Subsecond waterfall ages remain measurable");
            waterfall.paint(graphics);
        } finally {
            graphics.dispose();
        }
        waterfall.clear();
        waterfall.append(timedFrame(display, started, 3));
        check(waterfall.visibleAgeSeconds() == 0, "First observation after clearing starts a fresh time axis");
    }

    private static SpectrumFrame timedFrame(SpectrumSettings display, long timestamp, int columns) {
        return new SpectrumFrame("Waterfall timing", SpectrumFrame.State.READY, display, 62500,
                display.rfCenterHz(), true, timestamp, new double[columns]);
    }

    private static void slowSpectrumCadence() throws Exception {
        for (int rate : new int[]{InspectorSettings.defaults().waterfallRate(), 1, 15}) {
            InspectorSettings settings = settings(1024, WindowFunction.RECTANGULAR, 0, 0.5, rate);
            try (InspectorAnalysis analysis = new InspectorAnalysis(settings)) {
                analysis.accept(tone(1024, 8, 0.5, settings.effectiveSampleRate(), 1));
                analysis.start();
                await(analysis, 1);
                analysis.accept(tone(1024, 16, 0.5, settings.effectiveSampleRate(), 2));
                long before = System.nanoTime();
                check(awaitWaterfall(analysis).timestampNanos() == 2, "Waterfall analyzes new input independently of slow spectrum cadence");
                waterfallInterval(System.nanoTime() - before, rate);
                check(analysis.latest().timestampNanos() == 1, "Waterfall analysis preserves the requested spectrum display cadence");
                analysis.accept(tone(1024, -16, 0.5, settings.effectiveSampleRate(), 3));
                before = System.nanoTime();
                check(awaitWaterfall(analysis).timestampNanos() == 3, "Second independent waterfall interval");
                waterfallInterval(System.nanoTime() - before, rate);
                check(analysis.pollWaterfall() == null, "Independent waterfall cadence does not queue catch-up rows");
            }
        }
    }

    private static void liveWaterfallCadence() throws Exception {
        InspectorSettings slow = settings(1024, WindowFunction.RECTANGULAR, 2, 0.5, 1);
        InspectorSettings fast = settings(1024, WindowFunction.RECTANGULAR, 2, 0.5, 15);
        try (InspectorAnalysis analysis = new InspectorAnalysis(slow)) {
            analysis.accept(tone(1024, 16, 1, slow.effectiveSampleRate(), 1));
            analysis.start();
            await(analysis, 1);
            analysis.updateSettings(fast);
            analysis.accept(tone(1024, 16, 0.5, slow.effectiveSampleRate(), 2));
            long before = System.nanoTime();
            SpectrumFrame row = awaitWaterfall(analysis);
            check(row.timestampNanos() == 2, "Live waterfall rate increase uses the next completed input");
            waterfallInterval(System.nanoTime() - before, 15);
            near(row.dbAt(peak(row)), 10 * Math.log10(1024.0 * 1024 * 0.8125), 0.001,
                    "Changing waterfall cadence retains linear-power averaging history");
            check(analysis.latest().timestampNanos() == 1, "Waterfall rate edits do not force a spectrum publication");

            analysis.updateSettings(slow);
            analysis.accept(tone(1024, 16, 0.25, slow.effectiveSampleRate(), 3));
            before = System.nanoTime();
            row = awaitWaterfall(analysis);
            check(row.timestampNanos() == 3, "Live waterfall rate decrease uses the next completed input");
            waterfallInterval(System.nanoTime() - before, 1);
            near(row.dbAt(peak(row)), 10 * Math.log10(1024.0 * 1024 * 0.625), 0.001,
                    "Rate decreases also retain averaging history");
            check(analysis.pollWaterfall() == null, "Live cadence changes do not enqueue catch-up rows");
        }
    }

    private static void waterfallInterval(long elapsedNanos, int rate) {
        long period = TimeUnit.SECONDS.toNanos(1) / rate;
        check(elapsedNanos >= period / 2, "Waterfall does not exceed " + rate + " lines per second");
        check(elapsedNanos < period + TimeUnit.MILLISECONDS.toNanos(500), "Waterfall follows " + rate + " lines per second independently of spectrum");
    }

    private static InspectorSettings settings(int size, WindowFunction window, int averaging) {
        return settings(size, window, averaging, 30);
    }

    private static InspectorSettings settings(int size, WindowFunction window, int averaging, double fftRate) {
        return settings(size, window, averaging, fftRate, InspectorSettings.defaults().waterfallRate());
    }

    private static InspectorSettings settings(int size, WindowFunction window, int averaging, double fftRate, int waterfallRate) {
        InspectorSettings defaults = InspectorSettings.defaults();
        return new InspectorSettings(defaults.ipAddress(), defaults.frequencyHz(), 500000, 500000, 8, defaults.gain(),
                defaults.rxBufferSamples(), -140, 100, window, fftRate, size, averaging, waterfallRate,
                defaults.storageFolder(), defaults.storageFormat(), false, 1280, 850);
    }

    private static ComplexSampleBlock tone(int size, int bin, double amplitude, int sampleRate, long stamp) {
        float[] iq = new float[size * 2];
        for (int i = 0; i < size; i++) {
            double angle = 2 * Math.PI * bin * i / size;
            iq[2 * i] = (float) (amplitude * Math.cos(angle));
            iq[2 * i + 1] = (float) (amplitude * Math.sin(angle));
        }
        return new ComplexSampleBlock(iq, size, 0, stamp, sampleRate);
    }

    private static SpectrumFrame await(InspectorAnalysis analysis, long stamp) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            SpectrumFrame frame = analysis.latest();
            if (frame.state() == SpectrumFrame.State.READY && frame.timestampNanos() == stamp) return frame;
            if (frame.state() == SpectrumFrame.State.ERROR) throw new AssertionError("FFT worker failed");
            Thread.sleep(3);
        }
        throw new AssertionError("Timed out waiting for completed FFT snapshot " + stamp);
    }

    private static SpectrumFrame awaitWaterfall(InspectorAnalysis analysis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            SpectrumFrame row = analysis.pollWaterfall();
            if (row != null) return row;
            Thread.sleep(5);
        }
        throw new AssertionError("Timed out waiting for waterfall row");
    }

    private static int peak(SpectrumFrame frame) {
        int peak = 0;
        for (int i = 1; i < frame.columns(); i++) if (frame.dbAt(i) > frame.dbAt(peak)) peak = i;
        return peak;
    }

    private static void near(double actual, double expected, double tolerance, String description) {
        check(Math.abs(actual - expected) <= tolerance, description + ": " + actual + " expected " + expected);
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
}
