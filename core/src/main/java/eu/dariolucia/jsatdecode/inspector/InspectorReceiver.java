package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.source.fmcomms2.Fmcomms2SampleSource;

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
    private final InspectorSettings settings;
    private final Fmcomms2SampleSource source;
    private final InspectorDecimator decimator;
    private final Consumer<ComplexSampleBlock> analysisSink;
    private final AtomicReference<IqRecorder> activeRecorder = new AtomicReference<>();
    private final int acquisitionRate;
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
        acquisitionRate = acquisitionRate(settings.sampleRate());
        decimator = new InspectorDecimator(acquisitionRate / settings.sampleRate() * settings.decimation());
        source = new Fmcomms2SampleSource(sourceConfiguration(settings));
        source.setSampleSink(this::accept);
    }

    // AD9361 FIR-Off rates below 2.084 MHz are unsupported by the shared adapter.
    // Acquire at 4 MHz and use this inspector's anti-aliased software decimator.
    static int acquisitionRate(int requestedRate) {
        return requestedRate < 2_084_000 ? 4_000_000 : requestedRate;
    }

    static Configuration sourceConfiguration(InspectorSettings settings) {
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
        if (settings.frequencyHz() != Math.round(settings.frequencyHz()))
            LOG.log(System.Logger.Level.INFO, "PlutoPlus tuning frequency rounded to integer Hz: " + Math.round(settings.frequencyHz()));
    }

    // Visible to package tests for exact streaming/decimation contracts without radio hardware.
    void accept(ComplexSampleBlock block) {
        if (failure != null || closed) return;
        if (block.sampleRate() != acquisitionRate || received && block.sampleIndex() != expectedIndex) {
            String diagnostic = block.sampleRate() != acquisitionRate ? "Unexpected acquisition sample rate " + block.sampleRate() :
                    "RX buffer overrun/underrun: expected sample index " + expectedIndex + ", received " + block.sampleIndex();
            failure = new IOException(diagnostic);
            source.requestStop();
            return;
        }
        received = true;
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
        double center = centerReadback instanceof Number number ? number.doubleValue() : Math.round(settings.frequencyHz());
        int factor = acquisitionRate / settings.sampleRate() * settings.decimation();
        int sourceBlockSamples = Math.min(settings.rxBufferSamples(), BLOCK_SAMPLES);
        int outputBlockSamples = (sourceBlockSamples + factor - 1) / factor;
        IqRecorder recording = IqRecorder.start(folder, center,
                settings.effectiveSampleRate(), settings.gain(), outputBlockSamples, format);
        lastRecorder = recording;
        activeRecorder.set(recording);
        return recording.file();
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
        return failure != null ? failure : source.failure().orElse(null);
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
            LOG.log(System.Logger.Level.ERROR, "PlutoPlus RX buffer overrun: " + (overruns - reportedOverruns) + " new IIOD overflow reports");
            reportedOverruns = overruns;
        }
        Throwable current = failure();
        if (current != null && current != reportedFailure) {
            reportedFailure = current;
            LOG.log(System.Logger.Level.ERROR, "PlutoPlus reception failed (RX buffer underrun, disconnect or device/protocol error)", current);
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
