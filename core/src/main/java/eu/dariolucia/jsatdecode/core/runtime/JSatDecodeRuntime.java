package eu.dariolucia.jsatdecode.core.runtime;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.decoder.Decoder;
import eu.dariolucia.jsatdecode.api.decoder.DecoderFactory;
import eu.dariolucia.jsatdecode.api.distribution.*;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.lifecycle.LifecycleComponent;
import eu.dariolucia.jsatdecode.api.monitoring.Monitorable;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringDescriptor;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.api.source.SampleSource;
import eu.dariolucia.jsatdecode.api.source.SampleSourceFactory;
import eu.dariolucia.jsatdecode.api.taps.TapProvider;
import eu.dariolucia.jsatdecode.core.config.DistributionConfiguration;
import eu.dariolucia.jsatdecode.core.registry.FactoryRegistry;

import java.util.*;

/**
 * One-shot runtime owning all supplied components. Lifecycle calls are serialized.
 * Source callbacks perform optional frequency translation, decoder work and nonwaiting frame offers.
 * The management caller drives monitoring/failure detection with awaitCompletion.
 */
public final class JSatDecodeRuntime implements LifecycleComponent, Monitorable {
    private final SampleSource source;
    private final Decoder decoder;
    private final List<FrameDistributor> frames;
    private final List<MonitoringDistributor> monitoring;
    private final List<LifecycleComponent> startup;
    private final VerboseMonitoringLog verboseLog;
    private volatile ComponentState state = ComponentState.CONFIGURED;
    private volatile Throwable failure;
    private boolean stopped, closed;

    /**
     * Takes exclusive lifecycle ownership and wires components before start.
     *
     * @param source     configured source
     * @param decoder    configured decoder
     * @param frames     configured frame outputs, all receive each frame
     * @param monitoring configured observational outputs
     */
    public JSatDecodeRuntime(SampleSource source, Decoder decoder, List<FrameDistributor> frames,
                             List<MonitoringDistributor> monitoring) {
        this(source, decoder, frames, monitoring, false, 0);
    }

    private JSatDecodeRuntime(SampleSource source, Decoder decoder, List<FrameDistributor> frames,
                              List<MonitoringDistributor> monitoring, boolean verbose, double outputFrequencyOffset) {
        this.source = Objects.requireNonNull(source);
        this.decoder = Objects.requireNonNull(decoder);
        this.frames = List.copyOf(frames);
        this.monitoring = List.copyOf(monitoring);
        List<LifecycleComponent> components = new ArrayList<>();
        components.addAll(frames);
        components.addAll(monitoring);
        components.add(decoder);
        components.add(source);
        Set<LifecycleComponent> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        for (LifecycleComponent c : components) {
            if (!unique.add(c) || c.state() != ComponentState.CONFIGURED)
                throw new IllegalArgumentException("Components must be distinct and CONFIGURED");
        }
        startup = List.copyOf(components);
        decoder.setFrameSink(frame -> {
            for (FrameDistributor output : this.frames) output.distribute(frame);
        });
        source.setSampleSink(SourceFrequencyRotator.connect(decoder::accept, outputFrequencyOffset));
        verboseLog = verbose ? new VerboseMonitoringLog(monitoringDescriptors(), System.err::println) : null;
    }

    /**
     * Discovers all four factory contracts and builds configured components.
     * Construction failures close every successfully constructed component.
     * Global logging.verbose enables single-line standard-error observations
     * on the management caller driving awaitCompletion; it defaults to false.
     * A nonzero source.output_frequency_offset shifts source samples by that
     * many Hz before decoder acceptance, using each block's output sample rate.
     * This runtime-owned setting is consumed before passing source settings to
     * its factory. Absent or zero offsets retain the direct source/decoder path.
     *
     * @param configuration merged global settings; component sections are passed unprefixed
     * @return owned runtime
     */
    public static JSatDecodeRuntime create(Configuration configuration) {
        Configuration config = DistributionConfiguration.normalize(configuration);
        if (config.asMap().containsKey("ui.enabled")) config.getBoolean("ui.enabled");
        boolean verbose = config.asMap().containsKey("logging.verbose") && config.getBoolean("logging.verbose");
        double outputFrequencyOffset = config.asMap().containsKey("source.output_frequency_offset")
                ? config.getDouble("source.output_frequency_offset") : 0;
        var sources = FactoryRegistry.discover(SampleSourceFactory.class, f -> f.descriptor().id());
        var decoders = FactoryRegistry.discover(DecoderFactory.class, f -> f.descriptor().id());
        var frames = FactoryRegistry.discover(FrameDistributorFactory.class, f -> f.descriptor().id());
        var monitoring = FactoryRegistry.discover(MonitoringDistributorFactory.class, f -> f.descriptor().id());
        // Resolve every configured type before creating resources.
        var sf = sources.require(config.getString("source.type"));
        var df = decoders.require(config.getString("decoder.type"));
        var ff = frames.require(config.getString("distribution.frames.type"));
        var mf = monitoring.require(config.getString("distribution.monitoring.type"));
        List<LifecycleComponent> owned = new ArrayList<>();
        try {
            var f = ff.create(config.getSection("distribution.frames"));
            owned.add(f);
            var m = mf.create(config.getSection("distribution.monitoring"));
            owned.add(m);
            List<FrameDistributor> frameOutputs = new ArrayList<>();
            frameOutputs.add(f);
            if (config.asMap().containsKey("storage.folder")) {
                var recordingSettings = new LinkedHashMap<String, Object>();
                var frameSettings = config.getSection("distribution.frames").asMap();
                for (String key : List.of("decoder", "receiver_id", "queue_capacity", "max_message_bytes"))
                    if (frameSettings.containsKey(key)) recordingSettings.put(key, frameSettings.get(key));
                recordingSettings.put("folder", config.getString("storage.folder"));
                recordingSettings.put("filename_decoder", config.getString("decoder.type"));
                var recording = frames.require("frame-file").create(new Configuration(recordingSettings));
                owned.add(recording);
                frameOutputs.add(recording);
            }
            var d = df.create(config.getSection("decoder"));
            owned.add(d);
            Configuration sourceSettings = config.getSection("source");
            if (sourceSettings.asMap().containsKey("output_frequency_offset")) {
                var values = new LinkedHashMap<String, Object>(sourceSettings.asMap());
                values.remove("output_frequency_offset");
                sourceSettings = new Configuration(values);
            }
            if (sf.descriptor().id().equals("fmcomms2") && sourceSettings.asMap().containsKey("recording.enabled")
                    && sourceSettings.getBoolean("recording.enabled") && !sourceSettings.asMap().containsKey("recording.name")) {
                var values = new LinkedHashMap<String, Object>(sourceSettings.asMap());
                values.put("recording.name", config.asMap().containsKey("application.configuration_name")
                        ? config.getString("application.configuration_name") : "session");
                sourceSettings = new Configuration(values);
            }
            var s = sf.create(sourceSettings);
            owned.add(s);
            return new JSatDecodeRuntime(s, d, frameOutputs, List.of(m), verbose, outputFrequencyOffset);
        } catch (RuntimeException | Error e) {
            Collections.reverse(owned);
            for (var c : owned)
                try {
                    c.close();
                } catch (Throwable cleanup) {
                    e.addSuppressed(cleanup);
                }
            throw e;
        }
    }

    @Override
    public synchronized void start() throws Exception {
        if (state != ComponentState.CONFIGURED) throw new IllegalStateException("Runtime is one-shot");
        state = ComponentState.STARTING;
        try {
            for (var c : startup) c.start();
            state = ComponentState.RUNNING;
            event("runtime.started", "Runtime started");
        } catch (Exception | Error e) {
            failure = e;
            state = ComponentState.FAILED;
            close();
            throw e;
        }
    }

    private void event(String type, String message) {
        var event = new RuntimeEvent(eu.dariolucia.jsatdecode.util.UtcTime.nowNanos(), EventSeverity.INFO, "runtime", type, message, Map.of());
        for (var output : monitoring) output.publishEvent(event);
    }

    /**
     * Waits on the management caller, polling metrics at most four times/second.
     * EOF drains decoder/output; component failure shuts down and throws.
     * Interruption shuts down, restores interruption and propagates.
     *
     * @throws InterruptedException if interrupted
     * @throws OutputException      for source, decoder or output failure
     */
    public void awaitCompletion() throws InterruptedException {
        try {
            while (state == ComponentState.RUNNING) {
                synchronized (this) {
                    if (state != ComponentState.RUNNING) break;
                    for (var c : startup) {
                        if (c.state() == ComponentState.FAILED) {
                            Throwable componentFailure = getComponentFailure(c);
                            String message = "Component failed: " + c.getClass().getSimpleName();
                            if (componentFailure != null) {
                                message += " - " + componentFailure.getClass().getSimpleName() + ": " + componentFailure.getMessage();
                                if (componentFailure.getCause() != null) {
                                    message += " [Caused by: " + componentFailure.getCause().getClass().getSimpleName() + ": " + componentFailure.getCause().getMessage() + "]";
                                }
                            }
                            // Include stack trace in the exception for debugging
                            OutputException outputEx = new OutputException(message, componentFailure != null ? componentFailure : new RuntimeException("No nested exception available"));
                            throw outputEx;
                        }
                    }
                    if (source.state() == ComponentState.STOPPED) {
                        event("source.eof", "Source completed");
                        stop();
                        break;
                    }
                    publishMonitoring();
                }
                Thread.sleep(250);
            }
            if (failure != null) throw new OutputException("Runtime failed", failure);
        } catch (InterruptedException e) {
            stop();
            Thread.currentThread().interrupt();
            throw e;
        } catch (RuntimeException e) {
            failure = e;
            state = ComponentState.FAILED;
            stop();
            throw e;
        }
    }

    /**
     * Polls merged source/decoder/output metrics on the observer caller. No
     * lifecycle lock, callback or transport I/O is involved. Values and component
     * states may come from different instants; the immutable result is retainable.
     *
     * @return current best-effort monitoring snapshot
     */
    @Override
    public MonitoringSnapshot monitoringSnapshot() {
        Map<String, Object> values = new LinkedHashMap<>(source.monitoringSnapshot().values());
        values.putAll(decoder.monitoringSnapshot().values());
        long sent = 0, dropped = 0;
        for (var output : frames) {
            var status = output.monitoringSnapshot();
            sent += status.messagesSent();
            dropped += status.messagesDropped();
            String prefix = "distribution.frames." + output.descriptor().id() + ".";
            values.put(prefix + "received", status.messagesReceived());
            values.put(prefix + "sent", status.messagesSent());
            values.put(prefix + "dropped", status.messagesDropped());
            values.put(prefix + "errors", status.errors());
            values.put(prefix + "queue_depth", status.queueDepth());
            values.put(prefix + "queue_capacity", status.queueCapacity());
            values.put(prefix + "connected", status.connected());
        }
        values.put("output.frames_sent", sent);
        values.put("output.frames_dropped", dropped);
        values.put("runtime.state", state);
        values.put("runtime.source_state", source.state());
        values.put("runtime.decoder_state", decoder.state());
        return new MonitoringSnapshot(eu.dariolucia.jsatdecode.util.UtcTime.nowNanos(), values);
    }

    /**
     * Polls source/decoder metadata outside DSP; decoder entries take precedence
     * on duplicate keys. Runtime/output metrics without descriptors are unitless.
     *
     * @return immutable descriptor collection
     */
    @Override
    public Collection<MonitoringDescriptor> monitoringDescriptors() {
        Map<String, MonitoringDescriptor> descriptors = new LinkedHashMap<>();
        for (var descriptor : source.monitoringDescriptors()) descriptors.put(descriptor.key(), descriptor);
        for (var descriptor : decoder.monitoringDescriptors()) descriptors.put(descriptor.key(), descriptor);
        return List.copyOf(descriptors.values());
    }

    /**
     * Obtains decoder observation taps on the polling caller. This does not
     * change decoder lifecycle or enable any analysis work on a producer.
     *
     * @return non-null optional tap provider
     */
    public TapProvider taps() {
        return decoder.taps();
    }

    private void publishMonitoring() {
        var snapshot = monitoringSnapshot();
        for (var output : monitoring) if (output.isRunning()) output.publishMonitoring(snapshot);
        if (verboseLog != null) verboseLog.accept(snapshot, System.nanoTime());
    }

    @Override
    public synchronized void stop() {
        if (stopped || closed) return;
        stopped = true;
        if (state != ComponentState.FAILED) state = ComponentState.STOPPING;
        // Acquisition joins before decoder flush; outputs stay running during flush.
        List<LifecycleComponent> shutdown = new ArrayList<>();
        shutdown.add(source);
        shutdown.add(decoder);
        shutdown.addAll(frames);
        for (var c : shutdown) {
            try {
                c.stop();
                if (c.state() == ComponentState.FAILED) {
                    Throwable componentFailure = getComponentFailure(c);
                    String message = "Component failed during stop: " + c.getClass().getSimpleName();
                    if (componentFailure != null) {
                        message += " - " + componentFailure.getClass().getSimpleName() + ": " + componentFailure.getMessage();
                    }
                    record(new OutputException(message, componentFailure != null ? componentFailure : new RuntimeException("No nested exception")));
                }
            } catch (Throwable e) {
                record(e);
            }
        }
        if (monitoring.stream().anyMatch(MonitoringDistributor::isRunning))
            try {
                publishMonitoring();
            } catch (Throwable e) {
                record(e);
            }
        // Outputs remain available for the final management event until frames drain.
        for (var output : monitoring) {
            if (output.isRunning()) try {
                String message = failure == null ? "Runtime stopped" : "Runtime failed: " + (failure.getMessage() != null ? failure.getMessage() : "Unknown error");
                EventSeverity severity = failure == null ? EventSeverity.INFO : EventSeverity.ERROR;
                output.publishEvent(new RuntimeEvent(eu.dariolucia.jsatdecode.util.UtcTime.nowNanos(), severity,
                        "runtime", "runtime", message, Map.of()));
            } catch (Throwable e) {
                record(e);
            }
            try {
                output.stop();
                if (output.state() == ComponentState.FAILED) {
                    record(new OutputException("Monitoring output failed"));
                }
            } catch (Throwable e) {
                record(e);
            }
        }
        state = failure == null ? ComponentState.STOPPED : ComponentState.FAILED;
    }

    /**
     * Extracts the root failure from a component if available.
     * Checks for components with failure() methods (like AbstractFileSampleSource and specific decoders).
     *
     * @param component the component that failed
     * @return the failure exception or null if not available
     */
    private Throwable getComponentFailure(LifecycleComponent component) {
        // Check if it's a SampleSource with failure method (like AbstractFileSampleSource)
        if (component instanceof eu.dariolucia.jsatdecode.source.file.AbstractFileSampleSource afs) {
            return afs.failure().orElse(null);
        }

        // Check for failure() method via reflection for any component
        try {
            java.lang.reflect.Method failureMethod = component.getClass().getMethod("failure");
            if (failureMethod != null && failureMethod.getReturnType().equals(Optional.class)) {
                Optional<?> opt = (Optional<?>) failureMethod.invoke(component);
                if (opt.isPresent() && opt.get() instanceof Throwable) {
                    return (Throwable) opt.get();
                }
            }
        } catch (Exception e) {
            // Method not found or invocation failed - continue
        }

        return null;
    }

    private void record(Throwable e) {
        if (failure == null) failure = e;
        else if (failure != e) failure.addSuppressed(e);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        stop();
        closed = true;
        List<LifecycleComponent> reverse = new ArrayList<>(startup);
        Collections.reverse(reverse);
        for (var c : reverse)
            try {
                c.close();
            } catch (Throwable e) {
                record(e);
            }
        state = ComponentState.CLOSED;
    }

    @Override
    public ComponentState state() {
        return state;
    }

    /**
     * Returns safely published first failure, including after close.
     *
     * @return failure or empty
     */
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    /**
     * Returns immutable lifecycle/output observations on the polling caller.
     *
     * @return runtime snapshot
     */
    public Snapshot snapshot() {
        return new Snapshot(state, source.state(), decoder.state(), frames.stream().map(FrameDistributor::monitoringSnapshot).toList(),
                monitoring.stream().map(MonitoringDistributor::monitoringSnapshot).toList());
    }

    /**
     * Immutable weakly consistent runtime observations.
     *
     * @param state      runtime state
     * @param source     source state
     * @param decoder    decoder state
     * @param frames     frame outputs
     * @param monitoring observational outputs
     */
    public record Snapshot(ComponentState state, ComponentState source, ComponentState decoder,
                           List<DistributionSnapshot> frames, List<DistributionSnapshot> monitoring) {
        /**
         * Copies output lists.
         *
         * @param state      runtime state
         * @param source     source state
         * @param decoder    decoder state
         * @param frames     frame observations
         * @param monitoring observational output status
         */
        public Snapshot {
            frames = List.copyOf(frames);
            monitoring = List.copyOf(monitoring);
        }
    }
}
