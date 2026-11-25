import eu.dariolucia.jsatdecode.inspector.JSatInspector;

import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Opens and closes the real Inspector window using an isolated preferences directory. */
public final class InspectorDesktopSmoke {
    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("A desktop is required");
        if (args.length != 0) throw new IllegalArgumentException("No arguments expected");
        String appData = System.getenv("APPDATA");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path settingsRoot = windows && appData != null && !appData.isBlank()
                ? Path.of(appData) : Path.of(System.getProperty("user.home"));
        settingsRoot = settingsRoot.toRealPath();
        if (!settingsRoot.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())) {
            throw new IllegalArgumentException("Set APPDATA on Windows, or user.home elsewhere, to a fresh temporary directory");
        }
        Path preferences = settingsRoot.resolve(windows && appData != null && !appData.isBlank()
                ? "JSatInspector/settings.properties" : ".jsatinspector/settings.properties");
        if (Files.exists(preferences)) throw new IllegalArgumentException("Preferences must be isolated and initially absent");

        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> uncaught.compareAndSet(null, failure));
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(30_000);
                System.err.println("Inspector desktop smoke test timed out");
                Runtime.getRuntime().halt(1);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "inspector-smoke-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        JSatInspector.main(new String[0]);
        AtomicReference<Frame> inspector = new AtomicReference<>();
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (inspector.get() == null && System.nanoTime() < deadline && uncaught.get() == null) {
            SwingUtilities.invokeAndWait(() -> {
                for (Frame frame : Frame.getFrames()) {
                    if (frame.isShowing() && "JSatInspector".equals(frame.getTitle())) inspector.set(frame);
                }
            });
            if (inspector.get() == null) Thread.sleep(50);
        }
        Frame window = inspector.get();
        if (window == null) throw new IllegalStateException("Inspector window did not open", uncaught.get());
        if (window.getIconImages().isEmpty()) throw new IllegalStateException("Inspector icon did not load from its application module");
        System.out.println("window=" + window.getTitle() + " width=" + window.getWidth() + " height=" + window.getHeight());
        SwingUtilities.invokeAndWait(() -> window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING)));
        while (window.isDisplayable() && System.nanoTime() < deadline && uncaught.get() == null) Thread.sleep(50);
        if (uncaught.get() != null) throw new IllegalStateException("Inspector failed", uncaught.get());
        if (window.isDisplayable() || !Files.isRegularFile(preferences)) {
            throw new IllegalStateException("Inspector did not close and save isolated preferences");
        }
        watchdog.interrupt();
        System.out.println("PASS Inspector window opened and closed with isolated preferences");
    }
}
