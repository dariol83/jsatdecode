import eu.dariolucia.jsatdecode.cli.Main;

import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicReference;

/** Opens and closes the real JSatDecode synthetic monitoring window. */
public final class DecoderDesktopSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("No arguments expected");
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("A desktop is required");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> failure.compareAndSet(null, error));
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(30_000);
                System.err.println("Decoder desktop smoke test timed out");
                Runtime.getRuntime().halt(1);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "decoder-smoke-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        Thread application = new Thread(() -> {
            try { Main.main(new String[]{"--ui-demo"}); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "decoder-smoke-main");
        application.start();
        AtomicReference<Frame> decoder = new AtomicReference<>();
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (decoder.get() == null && System.nanoTime() < deadline && failure.get() == null && application.isAlive()) {
            SwingUtilities.invokeAndWait(() -> {
                for (Frame frame : Frame.getFrames()) {
                    if (frame.isShowing() && "JSatDecode synthetic monitoring".equals(frame.getTitle())) decoder.set(frame);
                }
            });
            if (decoder.get() == null) Thread.sleep(50);
        }
        Frame window = decoder.get();
        if (window == null) throw new IllegalStateException("Decoder window did not open", failure.get());
        System.out.println("window=" + window.getTitle() + " width=" + window.getWidth() + " height=" + window.getHeight());
        SwingUtilities.invokeAndWait(() -> window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING)));
        application.join(15_000);
        if (failure.get() != null) throw new IllegalStateException("Decoder UI failed", failure.get());
        if (application.isAlive() || window.isDisplayable()) throw new IllegalStateException("Decoder UI did not close");
        watchdog.interrupt();
        System.out.println("PASS decoder synthetic monitoring opened and closed");
    }
}
