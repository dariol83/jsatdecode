package eu.dariolucia.jsatdecode;

import eu.dariolucia.jsatdecode.cli.Main;

/** Child-JVM CLI probe with no desktop imports, also runnable with java.base only. */
public final class UiHeadlessProbe {
    private UiHeadlessProbe() { }
    /** Executes the real CLI then checks that fallback created no UI threads.
     * @param args actual CLI options
     * @throws Exception on CLI or observer isolation failure
     */
    public static void main(String[] args) throws Exception {
        Main.main(args);
        for(Thread thread:Thread.getAllStackTraces().keySet()) {
            String name=thread.getName();
            if(name.startsWith("AWT-") || name.startsWith("jsatdecode-ui-")) throw new AssertionError("Headless path started UI thread: "+name);
        }
        System.out.println("HEADLESS_NO_UI_THREADS");
    }
}
