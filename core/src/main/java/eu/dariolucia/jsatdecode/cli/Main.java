package eu.dariolucia.jsatdecode.cli;

import eu.dariolucia.jsatdecode.api.decoder.DecoderFactory;
import eu.dariolucia.jsatdecode.api.source.SampleSourceFactory;
import eu.dariolucia.jsatdecode.core.config.ConfigurationLoader;
import eu.dariolucia.jsatdecode.core.registry.FactoryRegistry;
import eu.dariolucia.jsatdecode.core.runtime.JSatDecodeRuntime;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Management entry point with lazily loaded optional desktop observation.
 * No sample processing or transport I/O runs here.
 */
public final class Main {
    private Main() {
    }

    /**
     * Parses configuration, installs an owned shutdown hook and runs until EOF/failure.
     *
     * @param args CLI options; see --help
     * @throws Exception for invalid settings, startup failure or interrupted execution
     */
    public static void main(String[] args) throws Exception {
        CommandLine command = CommandLine.parse(args);
        switch (command.action()) {
            case "help" -> System.out.println("""
                    JSatDecode 0.1.0-SNAPSHOT
                    Usage: java -jar jsatdecoder.jar --config <file> [--set key=value] [--ui|--headless] [--store <folder>] [-v]
                    Options: --help, -h, --version, --list-sources, --list-decoders, --ui-demo
                    --set may repeat; dedicated --headless/--ui overrides properties and --set.
                    --ui-demo opens synthetic monitoring without a source file. Output types: synthetic, zmq.
                    --store creates a timestamped .frame.bin recording with ZMQ-compatible metadata and payloads.
                    -v logs observed carrier/symbol/frame lock transitions, plus all monitoring metrics every two seconds.
                    """);
            case "version" -> System.out.println("JSatDecode 0.1.0-SNAPSHOT");
            case "list-sources" -> FactoryRegistry.discover(SampleSourceFactory.class, f -> f.descriptor().id())
                    .entries().forEach((id, f) -> System.out.println(id + " - " + f.descriptor().description()));
            case "list-decoders" -> FactoryRegistry.discover(DecoderFactory.class, f -> f.descriptor().id())
                    .entries().forEach((id, f) -> System.out.println(id + " - " + f.descriptor().description()));
            case "ui-demo" -> OptionalMonitoringUi.demo(ConfigurationLoader.load(Map.of("ui.enabled", true),
                    command.config(), command.overrides(), command.switches()));
            case "run" -> {
                var configuration = ConfigurationLoader.load(Map.of("ui.enabled", false,
                                "distribution.frames.type", "synthetic", "distribution.monitoring.type", "synthetic"),
                        command.config(), command.overrides(), command.switches());
                try (var runtime = JSatDecodeRuntime.create(configuration)) {
                    AtomicReference<AutoCloseable> ui = new AtomicReference<>();
                    Thread hook = new Thread(() -> {
                        OptionalMonitoringUi.close(ui.get());
                        runtime.close();
                    }, "jsatdecode-shutdown");
                    Runtime.getRuntime().addShutdownHook(hook);
                    try {
                        ui.set(OptionalMonitoringUi.open(configuration, runtime));
                        runtime.start();
                        runtime.awaitCompletion();
                    } catch (Exception e) {
                        // Print full error details to stderr
                        System.err.println("\n=== JSatDecode ERROR ===");
                        System.err.println("Error: " + e.getMessage());
                        if (e.getCause() != null) {
                            System.err.println("Caused by: " + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
                        }
                        System.err.println("\nFull stack trace:");
                        e.printStackTrace(System.err);
                        if (e.getCause() != null) {
                            System.err.println("\nCaused by stack trace:");
                            e.getCause().printStackTrace(System.err);
                        }
                        throw e;
                    } finally {
                        OptionalMonitoringUi.close(ui.get());
                        runtime.close();
                        try {
                            Runtime.getRuntime().removeShutdownHook(hook);
                        } catch (IllegalStateException ignored) { /* JVM shutdown already owns hook execution. */ }
                    }
                    if (runtime.failure().isPresent())
                        throw new IllegalStateException("Runtime failed", runtime.failure().orElseThrow());
                }
            }
            default -> throw new IllegalArgumentException("Unknown action");
        }
    }
}
