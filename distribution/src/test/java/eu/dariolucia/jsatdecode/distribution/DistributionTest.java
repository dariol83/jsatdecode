package eu.dariolucia.jsatdecode.distribution;

import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

/** Exercises the actual assembled package in separate modular Java processes. */
public final class DistributionTest {
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private static final Path JAVA_HOME = Path.of(System.getProperty("java.home"));
    private static final String CORE_MODULE = "eu.dariolucia.jsatdecode.core";
    private static final Set<String> APPLICATION_MODULES = Set.of(CORE_MODULE,
            "eu.dariolucia.jsatdecode.decoder.base", "eu.dariolucia.jsatdecode.decoder.elektrol.ggak",
            "eu.dariolucia.jsatdecode.decoder.hinode");
    private static final Set<String> DECODERS = Set.of("dummy", "elektrol_ggak", "hinode_s_tlm");

    private DistributionTest() { }

    /** @param args the ZIP created during the package phase */
    public static void main(String[] args) throws Exception {
        check(args.length == 1, "Expected the assembled distribution ZIP path");
        Path archive = Path.of(args[0]).toAbsolutePath();
        Path smokeDirectory = Files.createDirectories(archive.getParent().resolve("distribution-smoke-test"));
        Path extracted = Files.createTempDirectory(smokeDirectory, "package with spaces ");
        Path home = extract(archive, extracted);
        Path lib = home.resolve("lib");
        check(Files.isDirectory(lib), "Distribution has no lib directory");
        Set<String> jars;
        try (var entries = Files.list(lib)) {
            jars = entries.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        }
        for (String artifact : List.of("jsatdecode-core", "decoder-base", "decoder-elektrol-ggak", "decoder-hinode",
                "jeromq", "jradio", "JTransforms",
                "JLargeArrays", "commons-math3", "ascii85", "jnacl", "metrics-core", "slf4j-api", "ssdv4j")) {
            check(jars.stream().anyMatch(name -> name.startsWith(artifact + "-") && name.endsWith(".jar")),
                    "Distribution is missing " + artifact);
        }
        for (String script : List.of("jsatdecoder.sh", "jsatdecoder.bat", "jsatinspector.sh", "jsatinspector.bat")) {
            check(Files.isRegularFile(home.resolve("bin").resolve(script)), "Missing launcher " + script);
        }
        checkModuleDescriptors(lib);
        Path working = Files.createDirectories(extracted.resolve("caller working directory"));
        Result modules = run(List.of(javaTool("java"),
                "--module-path", lib.toString(), "--add-modules", CORE_MODULE, "--validate-modules"), working, false);
        check(modules.exitCode() == 0, "Distribution module graph is invalid:\n" + modules.output());
        smokeNamedConsumer(lib, working);

        // Both a quoted config path and its relative input path must survive the scripts.
        Files.write(working.resolve("input samples !.cf32"), new byte[33 * 8]);
        Files.writeString(working.resolve("input config !.properties"), """
                source.type=cf32
                source.file=input samples !.cf32
                source.sample_rate=1000
                source.block_samples=8
                decoder.type=dummy
                distribution.frames.type=synthetic
                distribution.monitoring.type=synthetic
                """, StandardCharsets.UTF_8);

        if (WINDOWS) {
            smoke(home, working, null);
            Path shell = findWindowsShell();
            if (shell != null) smoke(home, working, shell);
            else System.out.println("Distribution smoke tests: Git sh unavailable; POSIX launchers checked on Unix builds.");
        } else {
            smoke(home, working, Path.of("/bin/sh"));
        }
        System.out.println("Distribution smoke tests passed: complete package, explicit modules, external consumer, mission DSP, launcher arguments and exit codes.");
    }

    private static void checkModuleDescriptors(Path lib) {
        ModuleFinder finder = ModuleFinder.of(lib);
        for (String name : APPLICATION_MODULES) {
            var reference = finder.find(name).orElseThrow(() -> new AssertionError("Missing application module " + name));
            check(!reference.descriptor().isAutomatic(), "Application module must have module-info.class: " + name);
        }
        // Starting only core must bring mission providers into the graph through uses/provides.
        var configuration = ModuleLayer.boot().configuration().resolveAndBind(finder, ModuleFinder.of(), Set.of(CORE_MODULE));
        for (String name : APPLICATION_MODULES) {
            check(configuration.findModule(name).isPresent(), "Core service binding did not resolve " + name);
        }
    }

    private static void smokeNamedConsumer(Path lib, Path working) throws Exception {
        Path source = Files.createDirectories(working.resolve("consumer source"));
        Path classes = Files.createDirectories(working.resolve("consumer classes"));
        Path descriptor = source.resolve("module-info.java");
        Path application = source.resolve("ModuleConsumer.java");
        // Core API access must work through decoder.base's transitive requirement.
        Files.writeString(descriptor, """
                module jsatdecode.consumer {
                    requires eu.dariolucia.jsatdecode.decoder.base;
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(application, """
                package consumer;

                import eu.dariolucia.jsatdecode.api.config.Configuration;
                import eu.dariolucia.jsatdecode.api.decoder.DecoderFactory;
                import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
                import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
                import eu.dariolucia.jsatdecode.core.registry.FactoryRegistry;
                import eu.dariolucia.jsatdecode.decoder.base.AbstractDecoder;
                import java.util.Map;

                public final class ModuleConsumer {
                    public static void main(String[] args) throws Exception {
                        var factories = FactoryRegistry.discover(DecoderFactory.class, factory -> factory.descriptor().id());
                        float[] iq = new float[8192 * 2];
                        for (int n = 0; n < iq.length / 2; n++) {
                            iq[2 * n] = (float) Math.cos(n * 0.1);
                            iq[2 * n + 1] = (float) Math.sin(n * 0.1);
                        }
                        for (var mission : Map.of("elektrol_ggak", 1000000, "hinode_s_tlm", 500000).entrySet()) {
                            var factory = factories.require(mission.getKey());
                            check(factory.getClass().getModule().isNamed(), "Factory is not in a named module");
                            var decoder = factory.create(new Configuration(Map.of("sample_rate_hz", mission.getValue())));
                            try (decoder) {
                                check(decoder instanceof AbstractDecoder, "Shared decoder base is not accessible");
                                decoder.setFrameSink(frame -> {});
                                decoder.start();
                                decoder.accept(new ComplexSampleBlock(iq, iq.length / 2, 0, 0, mission.getValue()));
                                check(decoder.state() == ComponentState.RUNNING, "Mission DSP did not run");
                                check(!decoder.monitoringSnapshot().values().isEmpty(), "Mission monitoring is unavailable");
                                decoder.stop();
                                check(decoder.state() == ComponentState.STOPPED, "Mission decoder did not stop");
                            }
                            check(decoder.state() == ComponentState.CLOSED, "Mission decoder did not close");
                        }
                        System.out.println("Named consumer and mission DSP passed");
                    }

                    private static void check(boolean condition, String message) {
                        if (!condition) throw new AssertionError(message);
                    }
                }
                """, StandardCharsets.UTF_8);
        Result compilation = run(List.of(javaTool("javac"), "--module-path", lib.toString(),
                "-d", classes.toString(), descriptor.toString(), application.toString()), working, false);
        check(compilation.exitCode() == 0, "External named consumer did not compile:\n" + compilation.output());
        Result execution = run(List.of(javaTool("java"), "--module-path", lib + java.io.File.pathSeparator + classes,
                "--module", "jsatdecode.consumer/consumer.ModuleConsumer"), working, false);
        check(execution.exitCode() == 0 && execution.output().contains("Named consumer and mission DSP passed"),
                "External named consumer or mission DSP failed:\n" + execution.output());
    }

    private static String javaTool(String name) {
        return JAVA_HOME.resolve("bin").resolve(name + (WINDOWS ? ".exe" : "")).toString();
    }

    private static Path extract(Path archive, Path directory) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                Path destination = directory.resolve(entry.getName()).normalize();
                check(destination.startsWith(directory), "Archive entry escapes extraction directory: " + entry.getName());
                if (entry.isDirectory()) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    try (var input = zip.getInputStream(entry)) {
                        Files.copy(input, destination);
                    }
                }
            }
        }
        try (var children = Files.list(directory)) {
            List<Path> roots = children.toList();
            check(roots.size() == 1 && Files.isDirectory(roots.getFirst()), "Expected one distribution root directory");
            return roots.getFirst();
        }
    }

    private static void smoke(Path home, Path working, Path shell) throws Exception {
        String label = shell == null ? "Windows batch" : "POSIX shell";
        Result help = launch(home, working, shell, "jsatdecoder", "--help");
        check(help.exitCode() == 0 && help.output().contains("JSatDecode"), label + " decoder help failed:\n" + help.output());
        Result decoders = launch(home, working, shell, "jsatdecoder", "--list-decoders");
        check(decoders.exitCode() == 0, label + " decoder discovery failed:\n" + decoders.output());
        for (String decoder : DECODERS) check(decoders.output().lines().anyMatch(line -> line.startsWith(decoder + " - ")),
                label + " did not discover decoder " + decoder + ":\n" + decoders.output());
        Result sources = launch(home, working, shell, "jsatdecoder", "--list-sources");
        check(sources.exitCode() == 0, label + " source discovery failed:\n" + sources.output());
        for (String source : List.of("cf32", "cs16", "wav", "fmcomms2")) {
            check(sources.output().lines().anyMatch(line -> line.startsWith(source + " - ")),
                    label + " did not discover source " + source);
        }
        Result inspector = launch(home, working, shell, "jsatinspector", "--help");
        check(inspector.exitCode() == 0 && inspector.output().contains("JSatInspector"),
                label + " inspector help failed:\n" + inspector.output());
        Result runtime = launch(home, working, shell, "jsatdecoder", "--config", "input config !.properties", "--headless",
                "--set", "source.sample_rate=2000", "--set", "source.block_samples=16");
        check(runtime.exitCode() == 0, label + " quoted arguments or caller working directory were lost:\n" + runtime.output());
        for (String application : List.of("jsatdecoder", "jsatinspector")) {
            Result invalid = launch(home, working, shell, application, "--distribution-invalid-option");
            check(invalid.exitCode() != 0 && (invalid.output().contains("argument") || invalid.output().contains("Unknown option")),
                    label + " " + application + " must propagate application errors:\n" + invalid.output());
        }
        System.out.println("Distribution smoke tests passed for " + label + ".");
    }

    private static Result launch(Path home, Path working, Path shell, String application, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        Path script = home.resolve("bin").resolve(application + (shell == null ? ".bat" : ".sh"));
        if (shell == null) {
            command.add(System.getenv().getOrDefault("ComSpec", "cmd.exe"));
            command.addAll(List.of("/d", "/s", "/c"));
            StringBuilder line = new StringBuilder("\"").append(quote(script.toString()));
            for (String arg : args) line.append(' ').append(quote(arg));
            command.add(line.append('"').toString());
        } else {
            command.add(shell.toString());
            command.add(script.toString().replace('\\', '/'));
            command.addAll(List.of(args));
        }
        return run(command, working, shell != null && WINDOWS);
    }

    private static String quote(String value) {
        check(!value.contains("\""), "Unexpected double quote in smoke test argument");
        return '"' + value + '"';
    }

    private static Result run(List<String> command, Path working, boolean msys) throws Exception {
        Path output = Files.createTempFile(working, "launcher-output-", ".txt");
        ProcessBuilder builder = new ProcessBuilder(command).directory(working.toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile());
        String javaHome = JAVA_HOME.toString();
        if (msys && javaHome.length() > 2 && javaHome.charAt(1) == ':') {
            javaHome = "/" + Character.toLowerCase(javaHome.charAt(0)) + javaHome.substring(2).replace('\\', '/');
        }
        builder.environment().put("JAVA_HOME", javaHome);
        Process process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("Launcher timed out: " + command + "\n" + Files.readString(output));
        }
        return new Result(process.exitValue(), Files.readString(output));
    }

    private static Path findWindowsShell() {
        for (String variable : List.of("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)", "LOCALAPPDATA")) {
            String value = System.getenv(variable);
            if (value == null) continue;
            for (String location : List.of("Git/bin/sh.exe", "Programs/Git/bin/sh.exe")) {
                Path candidate = Path.of(value).resolve(location);
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        for (String directory : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) continue;
            Path candidate = Path.of(directory).resolve("sh.exe");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Result(int exitCode, String output) { }
}
