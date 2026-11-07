package eu.dariolucia.jsatdecode.ui.swing;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.monitoring.Monitorable;
import eu.dariolucia.jsatdecode.api.taps.TapProvider;
import eu.dariolucia.jsatdecode.core.runtime.JSatDecodeRuntime;

import javax.swing.*;
import java.awt.*;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Optional desktop entry point, loaded lazily by the CLI only when requested.
 * Headless fallback creates no Swing component, EDT, timer or observer worker.
 * All windows belong to their session, never to a source or decoder.
 */
public final class SwingMonitoringUi {
    private SwingMonitoringUi() {
    }

    /**
     * Opens an independent observer on a management caller. Closing its window
     * disables only observation; closing the returned session releases UI work.
     * UI startup errors are handled by the CLI's optional-feature boundary.
     *
     * @param configuration global settings
     * @param runtime       monitoring/tap provider; its lifecycle is not owned by UI
     * @return idempotent session, or inert close action when headless
     * @throws Exception if desktop startup fails
     */
    public static AutoCloseable open(Configuration configuration, JSatDecodeRuntime runtime) throws Exception {
        return open(configuration, runtime, runtime::taps, "JSatDecode monitoring");
    }

    /**
     * Opens an independent observer for a decoder or another monitoring provider.
     * Closing the session stops observation without changing the provider's lifecycle.
     *
     * @param configuration global UI settings
     * @param provider      monitoring provider observed by this session
     * @param taps          current tap provider, obtained on each observation
     * @param title         window title
     * @return idempotent session, or inert close action when headless
     * @throws Exception if desktop startup fails
     */
    public static AutoCloseable open(Configuration configuration, Monitorable provider,
                                     Supplier<TapProvider> taps, String title) throws Exception {
        if (unavailable()) return () -> {
        };
        Configuration ui = configuration.getSection("ui");
        return new Session(provider, taps, UiSettings.read(ui), SpectrumSettings.read(ui),
                ConstellationSettings.read(ui), title);
    }

    /**
     * Runs synthetic monitoring and optional taps until its window closes.
     * It opens no source, decoder or transport; FFT runs on the UI observer.
     *
     * @param configuration global UI settings
     * @throws Exception if desktop creation or management waiting fails
     */
    public static void demo(Configuration configuration) throws Exception {
        if (unavailable()) return;
        SyntheticUiSource provider = new SyntheticUiSource();
        try (Session session = new Session(provider, () -> provider, UiSettings.read(configuration.getSection("ui")), SpectrumSettings.read(configuration.getSection("ui")), ConstellationSettings.read(configuration.getSection("ui")), "JSatDecode synthetic monitoring")) {
            Thread hook = new Thread(session::close, "jsatdecode-demo-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                session.closed.await();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException ignored) { /* JVM shutdown owns hook. */ }
            }
        }
    }

    private static boolean unavailable() {
        if (!GraphicsEnvironment.isHeadless()) return false;
        System.err.println("Graphical environment unavailable; continuing without monitoring UI.");
        return true;
    }

    /* Session lifetime is independent of runtime lifetime. No synchronized UI
       operation holds a runtime lock or waits for the EDT during shutdown. */
    static final class Session implements AutoCloseable {
        final MonitoringUiController controller;
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicReference<MonitoringWindow> window = new AtomicReference<>();
        private final AtomicBoolean closing = new AtomicBoolean();

        Session(Monitorable provider, Supplier<TapProvider> taps, UiSettings settings, String title) throws Exception {
            this(provider, taps, settings, SpectrumSettings.read(new Configuration(java.util.Map.of())), title);
        }

        Session(Monitorable provider, Supplier<TapProvider> taps, UiSettings settings, SpectrumSettings spectra, String title) throws Exception {
            this(provider, taps, settings, spectra, ConstellationSettings.read(new Configuration(java.util.Map.of())), title);
        }

        Session(Monitorable provider, Supplier<TapProvider> taps, UiSettings settings, SpectrumSettings spectra, ConstellationSettings symbols, String title) throws Exception {
            AtomicInteger rfWidth = new AtomicInteger(400), baseWidth = new AtomicInteger(400);
            controller = new MonitoringUiController(provider, taps, settings, spectra, symbols, rfWidth::get, baseWidth::get, model -> {
                MonitoringWindow view = window.get();
                if (view != null && view.isDisplayable()) view.apply(model);
            }, error -> {
                System.err.println("Monitoring UI disabled: " + error);
                requestClose();
            });
            try {
                SwingUtilities.invokeAndWait(() -> {
                    if (closing.get()) return;
                    MonitoringWindow view = new MonitoringWindow(settings, spectra, symbols, title, this::requestClose, rfWidth::set, baseWidth::set);
                    view.onRfSmoothing(controller::rfSmoothingLevel);
                    window.set(view);
                    view.setVisible(true);
                });
                if (!closing.get()) controller.start();
            } catch (Exception | LinkageError e) {
                close();
                throw e;
            }
        }

        private void requestClose() {
            if (!closing.compareAndSet(false, true)) return;
            controller.stop();
            closed.countDown();
            Runnable dispose = () -> Optional.ofNullable(window.getAndSet(null)).ifPresent(MonitoringWindow::dispose);
            if (SwingUtilities.isEventDispatchThread()) dispose.run();
            else SwingUtilities.invokeLater(dispose);
        }

        @Override
        public void close() {
            requestClose();
            controller.close();
        }
    }
}
