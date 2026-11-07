package eu.dariolucia.jsatdecode.hinode;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.decoder.Decoder;
import eu.dariolucia.jsatdecode.ui.swing.SwingMonitoringUi;

import java.awt.GraphicsEnvironment;
import java.util.Map;

/** Test-only bridge to the public desktop observer API. */
final class HinodeDesktopSession {
    private HinodeDesktopSession() { }

    /** Opens the actual three-plot desktop observer, failing if no desktop exists. */
    static AutoCloseable open(Decoder decoder) throws Exception {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("UI qualification requires a desktop");
        Configuration empty = new Configuration(Map.of());
        return SwingMonitoringUi.open(empty, decoder, decoder::taps,
                "Hinode recorded qualification");
    }
}
