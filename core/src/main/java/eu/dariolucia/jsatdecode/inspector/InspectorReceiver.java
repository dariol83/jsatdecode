package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.api.source.ManagedSampleSource;
import eu.dariolucia.jsatdecode.api.source.RuntimeConfigurableSampleSource;
import eu.dariolucia.jsatdecode.api.source.SampleSource;
import eu.dariolucia.jsatdecode.api.source.SampleSourceFactory;
import eu.dariolucia.jsatdecode.api.source.SourceException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/* One-shot acquisition owner. Start/close/storage changes belong to a management
   thread. Analysis sink must only publish bounded observations; it runs on the
   source producer. Disk I/O and logging never run in sample delivery. */
final class InspectorReceiver implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(InspectorReceiver.class.getName());
    private static final int BLOCK_SAMPLES = 4096;
    private volatile InspectorSettings settings;
    private final SampleSource source;
    private final SampleSourceFactory factory;
    private final InspectorDecimator decimator;
    private final Consumer<ComplexSampleBlock> analysisSink;
    private final AtomicReference<IqRecorder> activeRecorder = new AtomicReference<>();
    private final double acquisitionRate;
    private volatile double observedRate;
    private final int decimationFactor;
    private final int outputBlockSamples;
    private final long repeatStart, repeatEnd;
    private volatile IqRecorder lastRecorder;
    private volatile Throwable failure;
    private volatile boolean started, closed;
    private long expectedIndex;
    private boolean received;
    private long reportedOverruns;
    private Throwable reportedFailure;

    InspectorReceiver(InspectorSettings settings, Consumer<ComplexSampleBlock> analysisSink) {
        this.settings = Objects.requireNonNull(settings);
        this.analysisSink = Objects.requireNonNull(analysisSink);
        Configuration configuration = sourceConfiguration(settings);
        factory = InspectorSources.factory(settings.sourceId());
        source = factory.create(InspectorSources.configuration(factory, configuration));
        Map<String, Object> metadata = source.monitoringSnapshot().values();
        Object rate = metadata.get("source.sample_rate");
        acquisitionRate = settings.source() == null ? acquisitionRate(settings.sampleRate())
                : rate instanceof Number number ? number.doubleValue() : Double.NaN;
        observedRate = acquisitionRate;
        decimationFactor = InspectorSources.selection(settings).softwareDecimation();
        decimator = new InspectorDecimator(decimationFactor);
        int blockSamples = configuration.asMap().containsKey("block_samples") ? configuration.getInt("block_samples") : BLOCK_SAMPLES;
        outputBlockSamples = (blockSamples + decimationFactor - 1) / decimationFactor;
        repeatStart = configuration.asMap().containsKey("start_offset") ? configuration.getLong("start_offset") : 0;
        Object selectedSamples = metadata.get("source.selected_samples");
        repeatEnd = configuration.asMap().containsKey("repeat") && configuration.getBoolean("repeat")
                && selectedSamples instanceof Number number ? Math.addExact(repeatStart, number.longValue()) : -1;
        source.setSampleSink(this::accept);
    }

    // AD9361 FIR-Off rates below 2.084 MHz are unsupported by the shared adapter.
    // Acquire at 4 MHz and use this inspector's anti-aliased software decimator.
    static int acquisitionRate(int requestedRate) {
        return requestedRate < 2_084_000 ? 4_000_000 : requestedRate;
    }

    static Configuration sourceConfiguration(InspectorSettings settings) {
        if (settings.source() != null) return settings.source().configuration();
        Map<String, Object> values = new LinkedHashMap<>();
        String address = settings.ipAddress().trim();
        values.put("uri", address.startsWith("ip:") ? address : "ip:" + address);
        values.put("frequency", Math.round(settings.frequencyHz()));
        values.put("sample_rate", acquisitionRate(settings.sampleRate()));
        values.put("bandwidth", settings.bandwidth());
        values.put("fpga_decimation", 1);
        values.put("buffer_size", settings.rxBufferSamples());
        values.put("block_samples", Math.min(settings.rxBufferSamples(), BLOCK_SAMPLES));
        values.put("gain1", "manual");
        values.put("manual_gain1", settings.gain());
        values.put("filter_source", "Off");
        return new Configuration(values);
    }

    void start() {
        if (started || closed) throw new IllegalStateException("Receiver is one-shot");
        started = true;
        try { source.start(); } catch (RuntimeException | Error e) { failure = e; throw e; }
        catch (Exception e) { failure = e; throw new SourceException("Cannot start source " + settings.sourceId(), e); }
        if (settings.source() == null && settings.frequencyHz() != Math.round(settings.frequencyHz()))
            LOG.log(System.Logger.Level.INFO, "PlutoPlus tuning frequency rounded to integer Hz: " + Math.round(settings.frequencyHz()));
    }

    // Visible to package tests for exact streaming/decimation contracts without radio hardware.
    void accept(ComplexSampleBlock block) {
        if (failure != null || closed) return;
        // File sources retain recording-relative indices when repeating a segment.
        boolean rewind = repeatEnd >= 0 && expectedIndex == repeatEnd && block.sampleIndex() == repeatStart;
        if (Double.isFinite(observedRate) && block.sampleRate() != observedRate
                || received && block.sampleIndex() != expectedIndex && !rewind) {
            String diagnostic = block.sampleRate() != observedRate ? "Unexpected acquisition sample rate " + block.sampleRate() :
                    "RX buffer overrun/underrun: expected sample index " + expectedIndex + ", received " + block.sampleIndex();
            failure = new IOException(diagnostic);
            if (source instanceof ManagedSampleSource managed) managed.requestStop();
            else throw new SourceException(diagnostic, failure);
            return;
        }
        received = true;
        observedRate = block.sampleRate();
        expectedIndex = block.sampleIndex() + block.complexSamples();
        ComplexSampleBlock effective = decimator.process(block);
        if (effective.complexSamples() == 0) return;
        IqRecorder recording = activeRecorder.get();
        if (recording != null) recording.offer(effective);
        analysisSink.accept(effective);
    }

    Path startRecording(Path folder) throws IOException {
        return startRecording(folder, RecordingFormat.CF32);
    }

    Path startRecording(Path folder, RecordingFormat format) throws IOException {
        if (!running()) throw new IllegalStateException("Connect the receiver before recording");
        if (activeRecorder.get() != null) throw new IllegalStateException("A recording is already active");
        Object centerReadback = source.monitoringSnapshot().values().get("source.center_frequency");
        double center = centerReadback instanceof Number number ? number.doubleValue() : settings.centerFrequency();
        Configuration configuration = sourceConfiguration(settings);
        String gainKey = configuration.asMap().containsKey("rx2_en") && configuration.getBoolean("rx2_en") ? "manual_gain2" : "manual_gain1";
        String defaultGain = factory.configurationProperties().stream().filter(property -> property.name().equals(gainKey))
                .map(property -> property.defaultValue() == null ? "0" : property.defaultValue()).findFirst().orElse("0");
        double gain = configuration.asMap().containsKey(gainKey) ? configuration.getDouble(gainKey) : Double.parseDouble(defaultGain);
        IqRecorder recording = IqRecorder.start(folder, center,
                sampleRate(), gain, outputBlockSamples, format);
        lastRecorder = recording;
        activeRecorder.set(recording);
        return recording.file();
    }

    double sampleRate() {
        return Double.isFinite(observedRate) ? observedRate / decimationFactor : settings.effectiveSampleRate();
    }

    double centerFrequency() {
        Object value = source.monitoringSnapshot().values().get("source.center_frequency");
        return value instanceof Number number ? number.doubleValue() : settings.centerFrequency();
    }

    InspectorSettings settings() {
        return settings;
    }

    void updateSource(InspectorSettings requested) {
        if (!running()) throw new IllegalStateException("Connect before updating source settings");
        InspectorSourceSettings previous = InspectorSources.selection(settings);
        InspectorSourceSettings next = InspectorSources.selection(requested);
        if (!previous.id().equals(next.id()) || previous.softwareDecimation() != next.softwareDecimation())
            throw new IllegalArgumentException("Source selection and software decimation require reconnecting");
        Configuration changes = InspectorSources.runtimeChanges(factory,
                previous.configuration(), next.configuration());
        if (!changes.asMap().isEmpty()) {
            if (!(source instanceof RuntimeConfigurableSampleSource configurable))
                throw new IllegalStateException("Source does not support runtime configuration");
            configurable.updateConfiguration(changes);
        }
        settings = requested;
    }

    void stopRecording() {
        IqRecorder recording = activeRecorder.getAndSet(null);
        if (recording != null) recording.close();
    }

    boolean recording() {
        IqRecorder recording = activeRecorder.get();
        return recording != null && recording.recording();
    }

    Throwable recordingFailure() {
        IqRecorder recording = lastRecorder;
        return recording == null ? null : recording.failure();
    }

    Throwable failure() {
        if (failure != null) return failure;
        if (source instanceof ManagedSampleSource managed) return managed.failure().orElse(null);
        if (source.state() == ComponentState.FAILED) failure = new SourceException("Source " + settings.sourceId() + " failed");
        return failure;
    }

    boolean running() {
        return started && !closed && failure() == null && source.state() == ComponentState.RUNNING;
    }

    // Management polling is the sole logging path for source diagnostics. IIOD
    // reports EOVERFLOW, but it has no hardware timestamps/hidden loss counters.
    void pollDiagnostics() {
        IqRecorder recording = lastRecorder;
        if (recording != null) recording.pollDiagnostics();
        Object value = source.monitoringSnapshot().values().get("source.reported_overruns");
        long overruns = value instanceof Number number ? number.longValue() : 0;
        if (overruns > reportedOverruns) {
            LOG.log(System.Logger.Level.ERROR, "Source RX buffer overrun: " + (overruns - reportedOverruns) + " new reports");
            reportedOverruns = overruns;
        }
        Throwable current = failure();
        if (current != null && current != reportedFailure) {
            reportedFailure = current;
            LOG.log(System.Logger.Level.ERROR, "Source reception failed", current);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { source.close(); } finally {
            stopRecording();
            pollDiagnostics();
        }
    }
}
