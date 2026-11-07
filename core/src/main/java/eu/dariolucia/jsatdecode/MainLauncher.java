package eu.dariolucia.jsatdecode;

/**
 * Unified launcher for JSatDecode and JSatInspector.
 * 
 * When launched with --inspector as the first argument, JSatInspector is started.
 * Otherwise, the normal JSatDecode CLI is launched.
 * 
 * This allows both applications to be packaged in a single JAR.
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
            // Launch JSatInspector with remaining arguments
            String[] inspectorArgs = new String[args.length - 1];
            System.arraycopy(args, 1, inspectorArgs, 0, inspectorArgs.length);
            eu.dariolucia.jsatdecode.inspector.JSatInspector.main(inspectorArgs);
        } else {
            // Launch JSatDecode with all arguments
            eu.dariolucia.jsatdecode.cli.Main.main(args);
        }
    }
}
