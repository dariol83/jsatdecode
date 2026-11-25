package eu.dariolucia.jsatdecode.deployment;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
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

import javax.imageio.ImageIO;

/** Exercises the actual assembled package in separate modular Java processes. */
public final class DeploymentTest {
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private static final Path JAVA_HOME = Path.of(System.getProperty("java.home"));
    private static final String CORE_MODULE = "eu.dariolucia.jsatdecode.core";
    private static final String API_MODULE = "eu.dariolucia.jsatdecode.api";
    private static final String INSPECTOR_MODULE = "eu.dariolucia.jsatdecode.inspector";
    private static final String DECODER_BASE_MODULE = "eu.dariolucia.jsatdecode.decoder.base";
    private static final String FILE_SOURCE_MODULE = "eu.dariolucia.jsatdecode.source.file";
    private static final String RADIO_SOURCE_MODULE = "eu.dariolucia.jsatdecode.source.fmcomms2";
    private static final String ZMQ_MODULE = "eu.dariolucia.jsatdecode.distribution.zmq";
    private static final String JTDP_MODULE = "eu.dariolucia.jsatdecode.distribution.jtdp";
    private static final Set<String> DECODER_MODULES = Set.of(API_MODULE, CORE_MODULE,
            DECODER_BASE_MODULE, "eu.dariolucia.jsatdecode.decoder.elektrol.ggak",
            "eu.dariolucia.jsatdecode.decoder.hinode", FILE_SOURCE_MODULE, RADIO_SOURCE_MODULE,
            ZMQ_MODULE, JTDP_MODULE);
    private static final Set<String> DECODERS = Set.of("dummy", "elektrol_ggak", "hinode_s_tlm");
    private static final Set<String> INSPECTOR_MODULES = Set.of(API_MODULE, CORE_MODULE, INSPECTOR_MODULE,
            FILE_SOURCE_MODULE, RADIO_SOURCE_MODULE);

    private DeploymentTest() { }

    /** @param args decoder and Inspector ZIP paths; '-' denotes an unselected deployment */
    public static void main(String[] args) throws Exception {
        check(args.length == 2 && !(args[0].equals("-") && args[1].equals("-")), "Expected at least one selected deployment");
        if (!args[0].equals("-")) checkDecoderDeployment(Path.of(args[0]).toAbsolutePath());
        if (!args[1].equals("-")) checkInspectorDeployment(Path.of(args[1]).toAbsolutePath());
        System.out.println("Deployment checks passed: independent archives, explicit modules, providers, launcher arguments and exit codes.");
    }

    private static PackageContents checkArchive(Path archive, String application, Set<String> expectedModules) throws Exception {
        Path smokeDirectory = Files.createDirectories(archive.getParent().resolve("deployment-smoke-test"));
        Path extracted = Files.createTempDirectory(smokeDirectory, application + " package with spaces ");
        Path home = extract(archive, extracted);
        check(home.getFileName().toString().equals(archive.getFileName().toString().replaceFirst("\\.zip$", "")),
                "Unexpected deployment root directory");
        Path lib = home.resolve("lib");
        check(Files.isDirectory(lib), "Deployment has no lib directory");
        Set<String> jars;
        try (var entries = Files.list(lib)) {
            jars = entries.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        }
        check(jars.stream().allMatch(name -> name.endsWith(".jar")), "Unexpected non-JAR runtime dependency: " + jars);
        for (String extension : List.of(".sh", ".bat")) {
            check(Files.isRegularFile(home.resolve("bin").resolve(application + extension)), "Missing launcher " + application + extension);
            String other = application.equals("jsatdecoder") ? "jsatinspector" : "jsatdecoder";
            check(!Files.exists(home.resolve("bin").resolve(other + extension)), "Unrelated launcher was packaged: " + other);
        }
        String icon = application.equals("jsatdecoder") ? "icon-jsatdecode.png" : "icon-jsatinspector.png";
        Path iconFile = home.resolve("icons").resolve(icon);
        check(Files.isRegularFile(iconFile) && ImageIO.read(iconFile.toFile()) != null, "Missing or unreadable icon " + icon);
        String otherIcon = application.equals("jsatdecoder") ? "icon-jsatinspector.png" : "icon-jsatdecode.png";
        check(!Files.exists(home.resolve("icons").resolve(otherIcon)), "Unrelated application icon was packaged");
        check(Files.isRegularFile(home.resolve("LICENSE")), "Missing license");
        check(Files.isRegularFile(home.resolve("docs/README.md")), "Missing documentation index");
        checkModuleDescriptors(lib, expectedModules);
        Path working = Files.createDirectories(extracted.resolve("caller working directory"));
        String rootModule = application.equals("jsatdecoder") ? CORE_MODULE : INSPECTOR_MODULE;
        Result modules = run(List.of(javaTool("java"),
                "--module-path", lib.toString(), "--add-modules", rootModule, "--validate-modules"), working, false);
        check(modules.exitCode() == 0, "Deployment module graph is invalid:\n" + modules.output());
        return new PackageContents(home, lib, working, jars);
    }

    private static void checkDecoderDeployment(Path archive) throws Exception {
        PackageContents contents = checkArchive(archive, "jsatdecoder", DECODER_MODULES);
        Path lib = contents.lib(), working = contents.working();
        Set<String> jars = contents.jars();
        for (String artifact : List.of("jsatdecode-api", "jsatdecode-core", "jsatdecode-source-file",
                "jsatdecode-source-fmcomms2", "jsatdecode-distribution-zmq", "jsatdecode-distribution-jtdp",
                "decoder-base", "decoder-elektrol-ggak", "decoder-hinode",
                "jeromq", "jradio", "JTransforms",
                "JLargeArrays", "commons-math3", "ascii85", "jnacl", "metrics-core", "slf4j-api", "ssdv4j")) {
            check(jars.stream().anyMatch(name -> name.startsWith(artifact + "-") && name.endsWith(".jar")),
                    "Decoder deployment is missing " + artifact);
        }
        smokeIndependentConsumers(lib, working);
        smokeIndependentSources(lib, working);
        smokeIndependentDistributors(lib, working);
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

        smokeLaunchers(contents, true);
    }

    private static void checkInspectorDeployment(Path archive) throws Exception {
        PackageContents contents = checkArchive(archive, "jsatinspector", INSPECTOR_MODULES);
        check(contents.jars().size() == 5, "Inspector must contain only API, core, Inspector and source providers: " + contents.jars());
        smokeIndependentSources(contents.lib(), contents.working());
        smokeIndependentInspector(contents.lib(), contents.working());
        smokeLaunchers(contents, false);
    }

    private static void checkModuleDescriptors(Path lib, Set<String> expectedModules) {
        ModuleFinder finder = ModuleFinder.of(lib);
        Set<String> actualModules = finder.findAll().stream().map(reference -> reference.descriptor().name())
                .filter(name -> name.startsWith("eu.dariolucia.jsatdecode.")).collect(Collectors.toSet());
        check(actualModules.equals(expectedModules), "Unexpected project modules: " + actualModules);
        for (String name : expectedModules) {
            var reference = finder.find(name).orElseThrow(() -> new AssertionError("Missing application module " + name));
            check(!reference.descriptor().isAutomatic(), "Application module must have module-info.class: " + name);
        }
        var api = finder.find(API_MODULE).orElseThrow().descriptor();
        check(api.requires().size() == 1 && api.requires().iterator().next().name().equals("java.base"),
                "API must depend only on java.base");
        checkProjectRequirements(finder, CORE_MODULE, Set.of(API_MODULE));
        checkProjectRequirements(finder, FILE_SOURCE_MODULE, Set.of(API_MODULE));
        checkProjectRequirements(finder, RADIO_SOURCE_MODULE, Set.of(API_MODULE));
        if (expectedModules.contains(DECODER_BASE_MODULE)) {
            checkProjectRequirements(finder, DECODER_BASE_MODULE, Set.of(API_MODULE));
            checkProjectRequirements(finder, ZMQ_MODULE, Set.of(API_MODULE));
            checkProjectRequirements(finder, JTDP_MODULE, Set.of(API_MODULE));
            for (String decoder : List.of("eu.dariolucia.jsatdecode.decoder.elektrol.ggak",
                    "eu.dariolucia.jsatdecode.decoder.hinode")) {
                checkProjectRequirements(finder, decoder, Set.of(API_MODULE, DECODER_BASE_MODULE));
            }
        }
        String root = expectedModules.contains(INSPECTOR_MODULE) ? INSPECTOR_MODULE : CORE_MODULE;
        var configuration = ModuleLayer.boot().configuration().resolveAndBind(finder, ModuleFinder.of(), Set.of(root));
        for (String name : expectedModules) {
            check(configuration.findModule(name).isPresent(), "Service binding did not resolve " + name);
        }
    }

    private static void smokeIndependentInspector(Path lib, Path working) throws Exception {
        Path minimal = Files.createDirectories(working.resolve("independent inspector lib"));
        for (String name : List.of("jsatdecode-api", "jsatdecode-core", "jsatdecode-inspector",
                "jsatdecode-source-file", "jsatdecode-source-fmcomms2")) {
            Path jar = artifact(lib, name);
            Files.copy(jar, minimal.resolve(jar.getFileName()));
        }
        var finder = ModuleFinder.of(minimal);
        var configuration = ModuleLayer.boot().configuration().resolveAndBind(finder, ModuleFinder.of(), Set.of(INSPECTOR_MODULE));
        Set<String> modules = configuration.modules().stream().map(module -> module.name())
                .filter(name -> name.startsWith("eu.dariolucia.jsatdecode.")).collect(Collectors.toSet());
        check(modules.equals(Set.of(API_MODULE, CORE_MODULE, INSPECTOR_MODULE, FILE_SOURCE_MODULE, RADIO_SOURCE_MODULE)),
                "Independent Inspector requires unexpected project modules: " + modules);
        var requirements = finder.find(INSPECTOR_MODULE).orElseThrow().descriptor().requires().stream()
                .map(ModuleDescriptor.Requires::name).filter(name -> name.startsWith("eu.dariolucia.jsatdecode."))
                .collect(Collectors.toSet());
        check(requirements.equals(Set.of(API_MODULE, CORE_MODULE)), "Inspector has concrete implementation dependencies: " + requirements);
        try (ZipFile core = new ZipFile(artifact(lib, "jsatdecode-core").toFile());
             ZipFile inspector = new ZipFile(artifact(lib, "jsatdecode-inspector").toFile())) {
            String entry = "eu/dariolucia/jsatdecode/inspector/JSatInspector.class";
            check(core.getEntry(entry) == null && inspector.getEntry(entry) != null, "Inspector entry point is not isolated");
            check(core.getEntry("icon-jsatinspector.png") == null && inspector.getEntry("icon-jsatinspector.png") != null,
                    "Inspector must own its application icon");
        }
        for (List<String> launch : List.of(
                List.of("--module-path", minimal.toString(), "--module", INSPECTOR_MODULE, "--help"),
                List.of("--module-path", minimal.toString(), "--module", CORE_MODULE, "--inspector", "--help"),
                List.of("-cp", minimal + java.io.File.separator + "*", "eu.dariolucia.jsatdecode.MainLauncher", "--inspector", "--help"))) {
            List<String> command = new ArrayList<>();
            command.add(javaTool("java"));
            command.addAll(launch);
            Result result = run(command, working, false);
            check(result.exitCode() == 0 && result.output().contains("JSatInspector"),
                    "Independent Inspector launch failed: " + command + "\n" + result.output());
        }
    }

    private static void checkProjectRequirements(ModuleFinder finder, String module, Set<String> expected) {
        var descriptor = finder.find(module).orElseThrow().descriptor();
        Set<String> actual = descriptor.requires().stream().map(ModuleDescriptor.Requires::name)
                .filter(name -> name.startsWith("eu.dariolucia.jsatdecode."))
                .collect(Collectors.toSet());
        check(actual.equals(expected), module + " has unexpected JSatDecode dependencies: " + actual);
        for (String dependency : expected) {
            check(descriptor.requires().stream().anyMatch(required -> required.name().equals(dependency)
                            && required.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE)),
                    module + " must expose " + dependency + " transitively");
        }
    }

    private static void smokeIndependentConsumers(Path lib, Path working) throws Exception {
        Path api = artifact(lib, "jsatdecode-api");
        Path base = artifact(lib, "decoder-base");
        Path jradio = artifact(lib, "jradio");
        for (boolean withBase : List.of(false, true)) {
            String label = withBase ? "base" : "api";
            String moduleName = "jsatdecode.consumer." + label;
            Path source = Files.createDirectories(working.resolve(label + " consumer source"));
            Path classes = Files.createDirectories(working.resolve(label + " consumer classes"));
            Path descriptor = source.resolve("module-info.java");
            Path application = source.resolve("IndependentConsumer.java");
            Files.writeString(descriptor, "module " + moduleName + " { requires "
                    + (withBase ? "eu.dariolucia.jsatdecode.decoder.base" : API_MODULE) + "; }", StandardCharsets.UTF_8);
            Files.writeString(application, """
                    package consumer;
                    import eu.dariolucia.jsatdecode.api.config.Configuration;
                    import eu.dariolucia.jsatdecode.core.buffers.ComplexFloatRingBuffer;
                    import eu.dariolucia.jsatdecode.dsp.fft.ComplexFft;
                    import java.util.Map;
                    public final class IndependentConsumer {
                        public static void main(String[] args) {
                            if (new Configuration(Map.of("sample_rate", 1000)).getInt("sample_rate") != 1000)
                                throw new AssertionError("API configuration unavailable");
                            if (new ComplexFft(8).size() != 8 || new ComplexFloatRingBuffer(2, 1000).capacitySamples() != 2)
                                throw new AssertionError("Reusable utilities unavailable");
                            if (ModuleLayer.boot().findModule("eu.dariolucia.jsatdecode.core").isPresent())
                                throw new AssertionError("Independent consumer resolved core");
                            System.out.println("Independent consumer passed");
                        }
                    }
                    """, StandardCharsets.UTF_8);
            String modulePath = api.toString();
            if (withBase) modulePath += java.io.File.pathSeparator + base + java.io.File.pathSeparator + jradio;
            Result compilation = run(List.of(javaTool("javac"), "--module-path", modulePath,
                    "-d", classes.toString(), descriptor.toString(), application.toString()), working, false);
            check(compilation.exitCode() == 0, label + " independent consumer did not compile:\n" + compilation.output());
            Result execution = run(List.of(javaTool("java"), "--module-path", modulePath + java.io.File.pathSeparator + classes,
                    "--module", moduleName + "/consumer.IndependentConsumer"), working, false);
            check(execution.exitCode() == 0 && execution.output().contains("Independent consumer passed"),
                    label + " independent consumer failed:\n" + execution.output());
        }
    }

    private static Path artifact(Path lib, String artifact) throws IOException {
        try (var entries = Files.list(lib)) {
            List<Path> matches = entries.filter(path -> path.getFileName().toString().startsWith(artifact + "-")
                    && path.getFileName().toString().endsWith(".jar")).toList();
            check(matches.size() == 1, "Expected one packaged " + artifact + " JAR");
            return matches.getFirst();
        }
    }

    private static void smokeIndependentSources(Path lib, Path working) throws Exception {
        Path api = artifact(lib, "jsatdecode-api");
        Path source = Files.createDirectories(working.resolve("source consumer source"));
        Path classes = Files.createDirectories(working.resolve("source consumer classes"));
        Path descriptor = source.resolve("module-info.java");
        Path application = source.resolve("SourceConsumer.java");
        Files.writeString(descriptor, """
                module jsatdecode.sourceconsumer {
                    requires eu.dariolucia.jsatdecode.api;
                    uses eu.dariolucia.jsatdecode.api.source.SampleSourceFactory;
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(application, """
                package consumer;
                import eu.dariolucia.jsatdecode.api.config.Configuration;
                import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
                import eu.dariolucia.jsatdecode.api.source.*;
                import java.nio.ByteBuffer;
                import java.nio.ByteOrder;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.util.Map;
                import java.util.ServiceLoader;
                import java.util.Set;
                import java.util.concurrent.atomic.AtomicInteger;
                import java.util.stream.Collectors;

                public final class SourceConsumer {
                    public static void main(String[] args) throws Exception {
                        boolean file = args[0].equals("file"), named = args[1].equals("named");
                        String module = "eu.dariolucia.jsatdecode.source." + (file ? "file" : "fmcomms2");
                        var factories = ServiceLoader.load(SampleSourceFactory.class).stream().map(ServiceLoader.Provider::get)
                                .collect(Collectors.toMap(f -> f.descriptor().id(), f -> f));
                        check(factories.keySet().equals(file ? Set.of("wav", "cf32", "cs16") : Set.of("fmcomms2")),
                                "Unexpected independently discovered sources: " + factories.keySet());
                        if (named) {
                            Set<String> modules = ModuleLayer.boot().modules().stream().map(Module::getName)
                                    .filter(n -> n.startsWith("eu.dariolucia.jsatdecode.")).collect(Collectors.toSet());
                            check(modules.equals(Set.of("eu.dariolucia.jsatdecode.api", module)), "Unexpected modules: " + modules);
                            for (var factory : factories.values())
                                check(factory.getClass().getModule().getName().equals(module), "Wrong provider module");
                        } else {
                            for (var factory : factories.values())
                                check(!factory.getClass().getModule().isNamed(), "Expected classpath provider");
                        }
                        if (file) for (String id : Set.of("wav", "cf32", "cs16")) replay(factories.get(id), id);
                        else {
                            var factory = factories.get("fmcomms2");
                            try (var receiver = factory.create(new Configuration(Map.of()))) {
                                check(receiver instanceof ManagedSampleSource, "Missing managed source capability");
                            }
                            check(factory instanceof DiagnosticSampleSourceFactory, "Missing diagnostic factory capability");
                            var capture = ((DiagnosticSampleSourceFactory) factory).createDiagnostic(
                                    new Configuration(Map.of()), 2, (utc, elapsed, count) -> {});
                            try (var receiver = capture.source()) {
                                check(capture.diagnostics().capacity() == 2 && capture.diagnostics().capturedReadCount() == 0,
                                        "Invalid diagnostic log");
                            }
                        }
                        System.out.println("Independent " + args[0] + " sources passed on " + args[1]);
                    }

                    private static void replay(SampleSourceFactory factory, String id) throws Exception {
                        ByteBuffer data = ByteBuffer.allocate(id.equals("wav") ? 52 : id.equals("cs16") ? 4 : 8)
                                .order(ByteOrder.LITTLE_ENDIAN);
                        if (id.equals("wav")) {
                            data.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(44)
                                    .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                                    .putShort((short) 3).putShort((short) 2).putInt(1000).putInt(8000)
                                    .putShort((short) 8).putShort((short) 32)
                                    .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(8);
                        }
                        if (id.equals("cs16")) data.putShort((short) 8192).putShort((short) -16384);
                        else data.putFloat(0.25f).putFloat(-0.5f);
                        Path input = Path.of("isolated-source." + id);
                        Files.write(input, data.array());
                        var configuration = new Configuration(Map.of("file", input.toString(), "sample_rate", 1000, "block_samples", 1));
                        var source = (ManagedSampleSource) factory.create(configuration);
                        AtomicInteger samples = new AtomicInteger();
                        try (source) {
                            source.setSampleSink(block -> {
                                check(block.complexSamples() == 1 && block.sampleIndex() == 0 && block.sampleRate() == 1000
                                                && block.i(0) == 0.25f && block.q(0) == -0.5f,
                                        "Incorrect " + id + " sample");
                                samples.incrementAndGet();
                            });
                            source.start();
                            long deadline = System.nanoTime() + 5_000_000_000L;
                            while (source.state() != ComponentState.STOPPED && source.state() != ComponentState.FAILED
                                    && System.nanoTime() < deadline) Thread.sleep(1);
                            check(source.state() == ComponentState.STOPPED && source.failure().isEmpty() && samples.get() == 1
                                            && Boolean.TRUE.equals(source.monitoringSnapshot().values().get("source.eof")),
                                    id + " did not reach EOF with one sample: " + source.failure());
                        }
                    }

                    private static void check(boolean condition, String message) {
                        if (!condition) throw new AssertionError(message);
                    }
                }
                """, StandardCharsets.UTF_8);
        Result compilation = run(List.of(javaTool("javac"), "--module-path", api.toString(),
                "-d", classes.toString(), descriptor.toString(), application.toString()), working, false);
        check(compilation.exitCode() == 0, "Independent source consumer did not compile:\n" + compilation.output());
        for (boolean file : List.of(true, false)) {
            String label = file ? "file" : "fmcomms2";
            Path provider = artifact(lib, file ? "jsatdecode-source-file" : "jsatdecode-source-fmcomms2");
            String path = api + java.io.File.pathSeparator + provider + java.io.File.pathSeparator + classes;
            Result named = run(List.of(javaTool("java"), "--module-path", path, "--module",
                    "jsatdecode.sourceconsumer/consumer.SourceConsumer", label, "named"), working, false);
            check(named.exitCode() == 0 && named.output().contains("sources passed"),
                    "Independent named " + label + " source failed:\n" + named.output());
            Result classpath = run(List.of(javaTool("java"), "--class-path", path,
                    "consumer.SourceConsumer", label, "classpath"), working, false);
            check(classpath.exitCode() == 0 && classpath.output().contains("sources passed"),
                    "Independent classpath " + label + " source failed:\n" + classpath.output());
        }
    }

    private static void smokeIndependentDistributors(Path lib, Path working) throws Exception {
        Path api = artifact(lib, "jsatdecode-api");
        Path source = Files.createDirectories(working.resolve("distribution consumer source"));
        Path classes = Files.createDirectories(working.resolve("distribution consumer classes"));
        Path descriptor = source.resolve("module-info.java");
        Path application = source.resolve("DistributionConsumer.java");
        Files.writeString(descriptor, """
                module jsatdecode.distributionconsumer {
                    requires eu.dariolucia.jsatdecode.api;
                    uses eu.dariolucia.jsatdecode.api.distribution.FrameDistributorFactory;
                    uses eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorFactory;
                    uses eu.dariolucia.jsatdecode.api.distribution.DistributionConfigurationNormalizer;
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(application, """
                package consumer;
                import eu.dariolucia.jsatdecode.api.config.Configuration;
                import eu.dariolucia.jsatdecode.api.distribution.*;
                import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
                import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
                import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
                import java.io.DataInputStream;
                import java.net.ServerSocket;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.Files;
                import java.util.*;
                import java.util.stream.Collectors;

                public final class DistributionConsumer {
                    public static void main(String[] args) throws Exception {
                        boolean zmq = args[0].equals("zmq"), named = args[1].equals("named");
                        String module = "eu.dariolucia.jsatdecode.distribution." + (zmq ? "zmq" : "jtdp");
                        var frames = ServiceLoader.load(FrameDistributorFactory.class).stream().map(ServiceLoader.Provider::get)
                                .collect(Collectors.toMap(f -> f.descriptor().id(), f -> f));
                        var monitoring = ServiceLoader.load(MonitoringDistributorFactory.class).stream().map(ServiceLoader.Provider::get)
                                .collect(Collectors.toMap(f -> f.descriptor().id(), f -> f));
                        var normalizers = ServiceLoader.load(DistributionConfigurationNormalizer.class).stream().map(ServiceLoader.Provider::get).toList();
                        check(frames.keySet().equals(zmq ? Set.of("zmq", "frame-file") : Set.of("tcp")), "frame providers: " + frames.keySet());
                        check(monitoring.keySet().equals(Set.of(zmq ? "zmq" : "tcp")), "monitoring providers: " + monitoring.keySet());
                        check(normalizers.size() == (zmq ? 0 : 1), "normalizers: " + normalizers.size());
                        if (named) {
                            Set<String> modules = ModuleLayer.boot().modules().stream().map(Module::getName)
                                    .filter(n -> n.startsWith("eu.dariolucia.jsatdecode.")).collect(Collectors.toSet());
                            check(modules.equals(Set.of("eu.dariolucia.jsatdecode.api", module)), "unexpected modules: " + modules);
                            for (var factory : frames.values()) check(module.equals(factory.getClass().getModule().getName()), "frame owner");
                            for (var factory : monitoring.values()) check(module.equals(factory.getClass().getModule().getName()), "monitoring owner");
                        } else {
                            for (var factory : frames.values()) check(!factory.getClass().getModule().isNamed(), "classpath frame provider");
                        }
                        int port;
                        try (var reserved = new ServerSocket(0)) { port = reserved.getLocalPort(); }
                        Configuration frameSettings = zmq ? new Configuration(Map.of("decoder", "test", "endpoint", "tcp://127.0.0.1:*"))
                                : new Configuration(Map.of("port", port, "max_frame_buffer", 1));
                        Configuration monitorSettings = zmq ? new Configuration(Map.of("decoder", "test", "endpoint", "tcp://127.0.0.1:*"))
                                : new Configuration(Map.of("port", port));
                        if (!zmq) {
                            var normalized = normalizers.getFirst().normalize(new Configuration(Map.of(
                                    "distribution.tcp.port", port, "distribution.frames.type", "tcp", "distribution.monitoring.type", "tcp")));
                            check(normalized.getInt("distribution.frames.port") == port
                                    && normalized.getInt("distribution.monitoring.port") == port, "shared listener aliases");
                        }
                        try (var frame = frames.get(zmq ? "zmq" : "tcp").create(frameSettings);
                             var monitor = monitoring.get(zmq ? "zmq" : "tcp").create(monitorSettings)) {
                            frame.start(); monitor.start();
                            check(frame.state() == ComponentState.RUNNING && monitor.state() == ComponentState.RUNNING, "providers did not start");
                            frame.distribute(new AnnotatedFrame(new byte[]{1, 2, 3}, 123, Map.of()));
                            monitor.publishMonitoring(new MonitoringSnapshot(123, Map.of()));
                            frame.stop(); monitor.stop();
                        }
                        if (zmq) {
                            var folder = Files.createTempDirectory("isolated-frame-file-");
                            try (var recording = frames.get("frame-file").create(new Configuration(Map.of("folder", folder.toString(), "decoder", "test")))) {
                                recording.start();
                                recording.distribute(new AnnotatedFrame(new byte[]{1, 2, 3}, 123, Map.of()));
                                recording.stop();
                                check(recording.monitoringSnapshot().messagesSent() == 1, "recording did not drain");
                            }
                            try (var paths = Files.list(folder); var input = new DataInputStream(Files.newInputStream(paths.findFirst().orElseThrow()))) {
                                check(new String(input.readNBytes(input.readInt()), StandardCharsets.US_ASCII).equals("satdecode.v1.frame"), "recorded topic");
                                check(input.readNBytes(input.readInt()).length > 0, "recorded metadata");
                                check(Arrays.equals(input.readNBytes(input.readInt()), new byte[]{1, 2, 3}) && input.read() == -1, "recorded payload");
                            }
                        }
                        System.out.println("Independent " + args[0] + " distribution passed on " + args[1]);
                    }
                    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
                }
                """, StandardCharsets.UTF_8);
        Result compilation = run(List.of(javaTool("javac"), "--module-path", api.toString(),
                "-d", classes.toString(), descriptor.toString(), application.toString()), working, false);
        check(compilation.exitCode() == 0, "Independent distribution consumer did not compile:\n" + compilation.output());
        for (boolean zmq : List.of(true, false)) {
            String label = zmq ? "zmq" : "jtdp";
            Path provider = artifact(lib, "jsatdecode-distribution-" + label);
            String path = api + java.io.File.pathSeparator + provider + java.io.File.pathSeparator + classes;
            if (zmq) path += java.io.File.pathSeparator + artifact(lib, "jeromq") + java.io.File.pathSeparator + artifact(lib, "jnacl");
            Result named = run(List.of(javaTool("java"), "--module-path", path, "--module",
                    "jsatdecode.distributionconsumer/consumer.DistributionConsumer", label, "named"), working, false);
            check(named.exitCode() == 0 && named.output().contains("distribution passed"),
                    "Independent named " + label + " distribution failed:\n" + named.output());
            Result classpath = run(List.of(javaTool("java"), "--class-path", path,
                    "consumer.DistributionConsumer", label, "classpath"), working, false);
            check(classpath.exitCode() == 0 && classpath.output().contains("distribution passed"),
                    "Independent classpath " + label + " distribution failed:\n" + classpath.output());
        }
    }

    private static void smokeNamedConsumer(Path lib, Path working) throws Exception {
        Path source = Files.createDirectories(working.resolve("consumer source"));
        Path classes = Files.createDirectories(working.resolve("consumer classes"));
        Path descriptor = source.resolve("module-info.java");
        Path application = source.resolve("ModuleConsumer.java");
        // The registry is a runtime service in core; decoder contracts are in api.
        Files.writeString(descriptor, """
                module jsatdecode.consumer {
                    requires eu.dariolucia.jsatdecode.decoder.base;
                    requires eu.dariolucia.jsatdecode.core;
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

    private static void smokeLaunchers(PackageContents contents, boolean decoder) throws Exception {
        List<Path> shells = new ArrayList<>();
        if (WINDOWS) {
            shells.add(null);
            Path shell = findWindowsShell();
            if (shell != null) shells.add(shell);
            else System.out.println("Deployment checks: Git sh unavailable; POSIX launchers checked on Unix builds.");
        } else shells.add(Path.of("/bin/sh"));
        for (Path shell : shells) {
            if (decoder) smokeDecoder(contents.home(), contents.working(), shell);
            else smokeInspector(contents.home(), contents.working(), shell);
        }
    }

    private static void smokeInspector(Path home, Path working, Path shell) throws Exception {
        String label = shell == null ? "Windows batch" : "POSIX shell";
        for (String option : List.of("--help", "-h")) {
            Result help = launch(home, working, shell, "jsatinspector", option);
            check(help.exitCode() == 0 && help.output().contains("JSatInspector"), label + " Inspector help failed:\n" + help.output());
        }
        Result invalid = launch(home, working, shell, "jsatinspector", "--deployment-invalid-option");
        check(invalid.exitCode() != 0 && invalid.output().contains("argument"), label + " Inspector must propagate application errors:\n" + invalid.output());
        System.out.println("Inspector deployment smoke tests passed for " + label + ".");
    }

    private static void smokeDecoder(Path home, Path working, Path shell) throws Exception {
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
        Result runtime = launch(home, working, shell, "jsatdecoder", "--config", "input config !.properties", "--headless",
                "--set", "source.sample_rate=2000", "--set", "source.block_samples=16");
        check(runtime.exitCode() == 0, label + " quoted arguments or caller working directory were lost:\n" + runtime.output());
        Result invalid = launch(home, working, shell, "jsatdecoder", "--deployment-invalid-option");
        check(invalid.exitCode() != 0 && invalid.output().contains("Unknown option"), label + " decoder must propagate application errors:\n" + invalid.output());
        System.out.println("Decoder deployment smoke tests passed for " + label + ".");
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
    private record PackageContents(Path home, Path lib, Path working, Set<String> jars) { }
}
