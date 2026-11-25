package eu.dariolucia.jsatdecode;

import eu.dariolucia.jsatdecode.api.application.ApplicationLauncher;
import eu.dariolucia.jsatdecode.core.registry.FactoryRegistry;

import java.util.Arrays;

/**
 * Unified launcher for JSatDecode and JSatInspector.
 * 
 * When launched with --inspector as the first argument, JSatInspector is started.
 * Otherwise, the normal JSatDecode CLI is launched.
 * 
 * Optional applications are discovered from separate modules or classpath JARs.
 */
public final class MainLauncher {
    
    private MainLauncher() {
        // Utility class
    }
    
    /**
     * Main entry point that dispatches to either JSatInspector or JSatDecode.
     * 
     * @param args command-line arguments
     * @throws Exception if the selected application fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--inspector".equals(args[0])) {
            var applications = FactoryRegistry.discover(ApplicationLauncher.class, ApplicationLauncher::id);
            ApplicationLauncher inspector = applications.entries().get("inspector");
            if (inspector == null)
                throw new IllegalStateException("JSatInspector is not installed; add jsatdecode-inspector to the module path or classpath");
            inspector.launch(Arrays.copyOfRange(args, 1, args.length));
        } else {
            // Launch JSatDecode with all arguments
            eu.dariolucia.jsatdecode.cli.Main.main(args);
        }
    }
}
