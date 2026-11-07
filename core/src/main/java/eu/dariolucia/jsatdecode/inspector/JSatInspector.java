package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.ui.swing.SpectrumFrame;

import javax.swing.*;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Standalone PlutoPlus spectrum analyser and CF32/CS16 recorder. No decoder, decoder
 * runtime, distributor or monitoring UI is constructed. Lifecycle and storage
 * operations run on a management worker; Swing is confined to the EDT.
 */
public final class JSatInspector {
    private static final System.Logger LOG = System.getLogger(JSatInspector.class.getName());

    private JSatInspector() { }

    /**
     * Opens JSatInspector, restoring user settings without connecting to hardware
     * or starting a recording. Requires a graphical Java 21 or newer runtime.
     *
     * @param args optional {@code --help}; no other arguments are supported
     */
    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h")) {
            System.out.println("JSatInspector - PlutoPlus spectrum analyser and CF32/CS16 signal saver\n"
                    + "Usage: jsatinspector.sh [--help] (Windows: jsatinspector.bat [--help])\n"
                    + "       java -jar jsatdecoder.jar --inspector [--help]\n"
                    + "Settings: %APPDATA%/JSatInspector/settings.properties or ~/.jsatinspector/settings.properties\n"
                    + "Connect and recording are started explicitly in the window.");
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("Unsupported argument; use --help");
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("JSatInspector requires a graphical desktop.");
            return;
        }
        InspectorPreferences preferences = InspectorPreferences.forUser();
        InspectorSettings settings = preferences.load();
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (ReflectiveOperationException | UnsupportedLookAndFeelException e) {
                LOG.log(System.Logger.Level.DEBUG, "Using the default Swing look and feel", e);
            }
            new Session(preferences, settings).show();
        });
    }

    /* One management owner serializes radio lifecycle, file open/drain and settings
       writes. The EDT only reads immutable spectra and nonwaiting row mailboxes. */
    private static final class Session implements InspectorWindow.Actions {
        private final InspectorPreferences preferences;
        private final ScheduledExecutorService management = Executors.newSingleThreadScheduledExecutor(r ->
                new Thread(r, "jsatinspector-management"));
        private final AtomicBoolean closing = new AtomicBoolean();
        private final Object analysisSettings = new Object();
        private final InspectorWindow window;
        private final Timer displayTimer;
        private final Thread shutdownHook;
        private volatile InspectorSettings settings;
        private volatile InspectorAnalysis analysis;
        private volatile InspectorReceiver receiver;
        private volatile DropSampleTest activeTest;
        private Throwable reportedRecordingFailure;
        private SpectrumFrame displayed;

        Session(InspectorPreferences preferences, InspectorSettings settings) {
            this.preferences = preferences;
            this.settings = settings;
            window = new InspectorWindow(settings, this);
            displayTimer = new Timer(40, e -> display());
            shutdownHook = new Thread(this::shutdown, "jsatinspector-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            management.scheduleWithFixedDelay(this::monitor, 250, 250, TimeUnit.MILLISECONDS);
        }

        void show() {
            window.setVisible(true);
            displayTimer.start();
        }

        @Override
        public void connect(InspectorSettings requested) {
            settings = requested;
            window.setConnection(false, true);
            window.setStatus("Connecting to " + requested.ipAddress() + "...");
            window.clearPlots();
            displayed = null;
            submit(() -> {
                try {
                    InspectorAnalysis next = new InspectorAnalysis(requested);
                    synchronized (analysisSettings) {
                        analysis = next;
                        next.updateSettings(settings);
                    }
                    next.start();
                    receiver = new InspectorReceiver(requested, next::accept);
                    receiver.start();
                    reportedRecordingFailure = null;
                    onEdt(() -> {
                        window.setConnection(true, false);
                        window.setStatus("Connected | " + requested.effectiveSampleRate() + " samples/s | "
                                + requested.effectiveSampleRate() + " Hz span");
                    });
                } catch (Exception e) {
                    stopSession();
                    error("Cannot connect", e);
                    onEdt(() -> window.setConnection(false, false));
                }
            });
        }

        @Override
        public void disconnect() {
            window.setConnection(true, true);
            window.setRecording(false, true);
            window.setStatus("Disconnecting and finishing recording...");
            submit(() -> {
                stopSession();
                onEdt(() -> {
                    window.setConnection(false, false);
                    window.setRecording(false, false);
                    window.setStatus("Disconnected");
                });
            });
        }

        @Override
        public void testMode(InspectorSettings requested) {
            if (closing.get() || activeTest != null || receiver != null) return;
            settings = requested;
            DropSampleTest test = new DropSampleTest(requested.ipAddress());
            activeTest = test;
            AtomicBoolean cancelled = new AtomicBoolean();
            AtomicReference<DropSampleTest.Progress> progress = new AtomicReference<>();
            DropSampleTestDialogs.ProgressDialog dialog = DropSampleTestDialogs.progress(window,
                    DropSampleTest.plan().size(), () -> {
                        cancelled.set(true);
                        test.cancel();
                    });
            Timer progressTimer = new Timer(100, event -> {
                DropSampleTest.Progress current = progress.get();
                if (current != null) dialog.update(current);
            });
            window.setTesting(true);
            window.clearPlots();
            displayed = null;
            window.setStatus("Running PlutoPlus sample-drop tests...");
            submit(() -> {
                List<DropSampleResult> results = new ArrayList<>();
                String failureMessage = null;
                try {
                    // Test mode owns a dedicated count-only source. No analysis
                    // worker or recording receives its samples.
                    stopSession();
                    test.run(progress::set, results::add);
                } catch (Exception e) {
                    failureMessage = e.toString();
                    LOG.log(System.Logger.Level.ERROR, "PlutoPlus test mode failed", e);
                } finally {
                    activeTest = null;
                    List<DropSampleResult> completed = List.copyOf(results);
                    String testFailure = failureMessage;
                    SwingUtilities.invokeLater(() -> {
                        progressTimer.stop();
                        dialog.finish();
                        if (!closing.get()) {
                            window.setTesting(false);
                            window.setConnection(false, false);
                            window.setRecording(false, false);
                            window.setStatus((testFailure != null ? "Test failed" : cancelled.get() ? "Test cancelled" : "Test complete")
                                    + " | " + completed.size() + " run results");
                            if (testFailure != null) JOptionPane.showMessageDialog(window, testFailure,
                                    "PlutoPlus test mode failed", JOptionPane.ERROR_MESSAGE);
                            DropSampleTestDialogs.showResults(window, completed, cancelled.get());
                        }
                    });
                }
            });
            progressTimer.start();
            dialog.setVisible(true);
        }

        @Override
        public void startRecording(InspectorSettings requested) {
            settings = requested;
            window.setRecording(false, true);
            submit(() -> {
                try {
                    if (receiver == null || !receiver.running()) throw new IllegalStateException("Connect before recording");
                    Path file = receiver.startRecording(requested.storageFolder(), requested.storageFormat());
                    reportedRecordingFailure = null;
                    onEdt(() -> {
                        window.setRecording(true, false);
                        window.setStatus("Recording " + file);
                    });
                } catch (Exception e) {
                    error("Cannot start recording", e);
                    onEdt(() -> window.setRecording(false, false));
                }
            });
        }

        @Override
        public void stopRecording() {
            window.setRecording(true, true);
            window.setStatus("Finishing recording...");
            submit(() -> {
                try {
                    if (receiver != null) receiver.stopRecording();
                    Throwable failure = receiver == null ? null : receiver.recordingFailure();
                    if (failure != null) error("Recording incomplete", failure);
                    else onEdt(() -> window.setStatus("Recording saved | Reception continues"));
                } finally { onEdt(() -> window.setRecording(false, false)); }
            });
        }

        @Override
        public void changeFft(InspectorSettings requested) {
            synchronized (analysisSettings) {
                settings = requested;
                InspectorAnalysis active = analysis;
                if (active != null) active.updateSettings(requested);
            }
        }

        @Override
        public void close(InspectorSettings requested) {
            if (!closing.compareAndSet(false, true)) return;
            DropSampleTest test = activeTest;
            if (test != null) test.cancel();
            settings = requested;
            displayTimer.stop();
            window.setConnection(receiver != null, true);
            window.setRecording(false, true);
            window.setStatus("Closing and finishing recording...");
            management.execute(() -> {
                try {
                    stopSession();
                    save();
                } finally {
                    management.shutdown();
                    try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                    catch (IllegalStateException ignored) { /* VM shutdown has already started. */ }
                    SwingUtilities.invokeLater(window::dispose);
                }
            });
        }

        private void display() {
            InspectorAnalysis active = analysis;
            if (active == null || closing.get()) return;
            active.setColumns(window.spectrumColumns());
            SpectrumFrame frame = active.latest();
            ArrayList<SpectrumFrame> rows = new ArrayList<>();
            SpectrumFrame row;
            while (rows.size() < 60 && (row = active.pollWaterfall()) != null) rows.add(row);
            if (frame != displayed || !rows.isEmpty()) {
                window.display(frame, rows);
                displayed = frame;
            }
        }

        private void monitor() {
            if (closing.get() || receiver == null) return;
            try {
                receiver.pollDiagnostics();
                Throwable failure = receiver.failure();
                if (failure != null || !receiver.running()) {
                    stopSession();
                    error("Reception stopped", failure == null ? new IllegalStateException("Source stopped unexpectedly") : failure);
                    onEdt(() -> {
                        window.setConnection(false, false);
                        window.setRecording(false, false);
                    });
                    return;
                }
                Throwable recordingFailure = receiver.recordingFailure();
                if (recordingFailure != null && recordingFailure != reportedRecordingFailure) {
                    reportedRecordingFailure = recordingFailure;
                    receiver.stopRecording();
                    error("Recording stopped; file is incomplete", recordingFailure);
                    onEdt(() -> window.setRecording(false, false));
                }
            } catch (Exception e) { error("Receiver monitoring failed", e); }
        }

        private void stopSession() {
            InspectorReceiver oldReceiver = receiver;
            receiver = null;
            InspectorAnalysis oldAnalysis = analysis;
            analysis = null;
            try { if (oldReceiver != null) oldReceiver.close(); }
            finally { if (oldAnalysis != null) oldAnalysis.close(); }
        }

        private void submit(Runnable operation) {
            if (!closing.get()) management.execute(operation);
        }

        private void error(String message, Throwable failure) {
            LOG.log(System.Logger.Level.ERROR, message, failure);
            onEdt(() -> window.setStatus(message + ": " + failure.getMessage()));
        }

        private void onEdt(Runnable operation) {
            SwingUtilities.invokeLater(() -> { if (!closing.get()) operation.run(); });
        }

        private void save() {
            try { preferences.save(settings); }
            catch (Exception e) { LOG.log(System.Logger.Level.WARNING, "Cannot save JSatInspector settings", e); }
        }

        private void shutdown() {
            if (management.isShutdown()) return;
            closing.set(true);
            DropSampleTest test = activeTest;
            if (test != null) test.cancel();
            try { management.submit(() -> { stopSession(); save(); }).get(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (ExecutionException | RejectedExecutionException e) { LOG.log(System.Logger.Level.WARNING, "Shutdown cleanup failed", e); }
            finally { management.shutdown(); }
        }
    }
}
