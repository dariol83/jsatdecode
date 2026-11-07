package eu.dariolucia.jsatdecode.cli;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.core.runtime.JSatDecodeRuntime;

import java.lang.reflect.InvocationTargetException;

/* Deliberately contains no desktop type reference. Even java.base-only deployments
   can decode with --headless, or warn/fall back when --ui is requested. */
final class OptionalMonitoringUi {
    private OptionalMonitoringUi() {
    }

    static AutoCloseable open(Configuration configuration, JSatDecodeRuntime runtime) {
        if (!enabled(configuration)) return null;
        try {
            return (AutoCloseable) Class.forName("eu.dariolucia.jsatdecode.ui.swing.SwingMonitoringUi")
                    .getMethod("open", Configuration.class, JSatDecodeRuntime.class).invoke(null, configuration, runtime);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            report(e);
            return null;
        }
    }

    static void demo(Configuration configuration) {
        if (!enabled(configuration)) return;
        try {
            Class.forName("eu.dariolucia.jsatdecode.ui.swing.SwingMonitoringUi")
                    .getMethod("demo", Configuration.class).invoke(null, configuration);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            report(e);
        }
    }

    private static boolean enabled(Configuration configuration) {
        return configuration.asMap().containsKey("ui.enabled") && configuration.getBoolean("ui.enabled");
    }

    static void close(AutoCloseable session) {
        if (session != null) try {
            session.close();
        } catch (Exception | LinkageError e) {
            report(e);
        }
    }

    private static void report(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException invocation ? invocation.getCause() : error;
        if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
        System.err.println("Monitoring UI unavailable; continuing without UI: " + cause);
    }
}
