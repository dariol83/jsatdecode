package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.api.application.ApplicationLauncher;

/** Preserves the unified launcher's --inspector dispatch without a core dependency on this module. */
public final class InspectorLauncher implements ApplicationLauncher {
    /** Creates an inert service provider. */
    public InspectorLauncher() { }

    @Override
    public String id() {
        return "inspector";
    }

    @Override
    public void launch(String[] arguments) {
        JSatInspector.main(arguments);
    }
}
