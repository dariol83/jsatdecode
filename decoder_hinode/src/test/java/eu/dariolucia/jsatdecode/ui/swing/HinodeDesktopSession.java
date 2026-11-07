package eu.dariolucia.jsatdecode.ui.swing;

import java.awt.GraphicsEnvironment;
import java.util.Map;
import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.decoder.Decoder;

/** Test-only bridge to the existing desktop session; no framework API extension. */
public final class HinodeDesktopSession {
    private HinodeDesktopSession() { }
    /** Opens the actual three-plot desktop observer, failing if no desktop exists. */
    public static AutoCloseable open(Decoder decoder) throws Exception {
        if(GraphicsEnvironment.isHeadless()) throw new IllegalStateException("UI qualification requires a desktop");
        Configuration empty=new Configuration(Map.of());
        return new SwingMonitoringUi.Session(decoder,decoder::taps,UiSettings.read(empty),
            SpectrumSettings.read(empty),ConstellationSettings.read(empty),"Hinode recorded qualification");
    }
}
