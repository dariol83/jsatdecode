package eu.dariolucia.jsatdecode.api.application;

/** Optional application entry point discovered without a dependency on its implementation. */
public interface ApplicationLauncher {
    /** @return stable application identifier */
    String id();

    /**
     * Launches the application on the caller's thread; applications own any
     * subsequent UI-thread dispatch. Discovery itself must not start workers.
     *
     * @param arguments application arguments, without the dispatch selector
     * @throws Exception if the application cannot start
     */
    void launch(String[] arguments) throws Exception;
}
