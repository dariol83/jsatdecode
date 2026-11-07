package eu.dariolucia.jsatdecode.ui.swing;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.core.config.ConfigurationLoader;
import eu.dariolucia.jsatdecode.core.runtime.JSatDecodeRuntime;
import eu.dariolucia.jsatdecode.source.wav.WavHeader;

import javax.swing.*;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Explicit external-recording regression through the configured runtime. */
public final class SourceFrequencyOffsetQualification {
    private SourceFrequencyOffsetQualification() {
    }

    /**
     * Replays a full WAV, optionally generating an independently shifted CF32
     * recording and compensating through source.output_frequency_offset. The
     * quarter-rate generator uses exact sign changes/swaps, not the rotator.
     * Outputs retain per-frame lengths for byte/count/order comparisons. Input
     * recordings stay unchanged; only this run's generated CF32 is deleted.
     *
     * @param args properties, WAV, new output directory, mode
     *             (absent/zero/positive/negative/observe), optional expected frames.bin
     * @throws Exception on runtime, observer, equality or speed failure
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 5)
            throw new IllegalArgumentException("properties recording.wav output-directory absent|zero|positive|negative|observe [expected.frames.bin]");
        String mode = args[3];
        if (!Set.of("absent", "zero", "positive", "negative", "observe").contains(mode))
            throw new IllegalArgumentException("Unknown mode: " + mode);
        Path input = Path.of(args[1]), directory = Path.of(args[2]).toAbsolutePath();
        Files.createDirectory(directory);
        WavHeader header = WavHeader.read(input);
        var values = new LinkedHashMap<String, Object>(ConfigurationLoader.load(Map.of(), Path.of(args[0]), Map.of(), Map.of()).asMap());
        values.put("source.file", input.toString());
        values.put("source.realtime", false);
        values.put("source.repeat", false);
        values.put("source.block_samples", 4096);
        values.remove("source.output_frequency_offset");
        values.put("distribution.frames.type", "synthetic");
        values.put("distribution.frames.queue_capacity", 16384);
        values.put("distribution.monitoring.type", "synthetic");
        values.put("storage.folder", directory.resolve("recording").toString());
        values.put("ui.enabled", false);
        Path shifted = directory.resolve("shifted.cf32");
        boolean translate = mode.equals("positive") || mode.equals("negative");
        if (translate) {
            shift(input, header, shifted, mode.equals("positive") ? -1 : 1);
            values.put("source.type", "cf32");
            values.put("source.file", shifted.toString());
            values.put("source.sample_rate", header.sampleRate());
            values.put("source.output_frequency_offset", (mode.equals("positive") ? 1 : -1) * header.sampleRate() / 4);
        } else if (mode.equals("zero")) values.put("source.output_frequency_offset", 0);
        Configuration config = new Configuration(values);
        boolean observe = mode.equals("observe");
        AtomicInteger models = new AtomicInteger(), rf = new AtomicInteger(), baseband = new AtomicInteger(), symbols = new AtomicInteger();
        AtomicReference<Throwable> diagnostic = new AtomicReference<>();
        AtomicReference<Display> display = new AtomicReference<>();
        Configuration ui = config.getSection("ui");
        var spectra = SpectrumSettings.read(ui);
        var constellation = ConstellationSettings.read(ui);
        if (observe) SwingUtilities.invokeAndWait(() -> display.set(new Display(spectra, constellation)));
        double elapsed;
        long good;
        try (var runtime = JSatDecodeRuntime.create(config);
             var observer = new MonitoringUiController(runtime, runtime::taps, UiSettings.read(ui), spectra,
                     constellation, () -> 400, () -> 400, model -> {
                         if (!SwingUtilities.isEventDispatchThread()) throw new AssertionError("Display outside EDT");
                         display.get().apply(model);
                         models.incrementAndGet();
                         for (var frame : model.spectra()) if (frame.state() == SpectrumFrame.State.READY) {
                             if (frame.name().equals("RF spectrum")) rf.incrementAndGet(); else baseband.incrementAndGet();
                         }
                         if (model.constellation() != null && model.constellation().points() > 0) symbols.incrementAndGet();
                     }, error -> diagnostic.compareAndSet(null, error))) {
            if (observe) observer.start();
            long start = System.nanoTime();
            runtime.start();
            runtime.awaitCompletion();
            elapsed = (System.nanoTime() - start) * 1e-9;
            observer.close();
            if (observe) SwingUtilities.invokeAndWait(() -> {});
            if (runtime.failure().isPresent()) throw new AssertionError("Runtime failed", runtime.failure().orElseThrow());
            if (observer.failure().isPresent() || diagnostic.get() != null) throw new AssertionError("Observer failed", diagnostic.get());
            var status = runtime.monitoringSnapshot().values();
            good = (Long) status.get(DecoderStatusKeys.FRAME_GOOD);
            for (String key : List.of("output.frames_dropped", "distribution.frames.frame-file.errors", "distribution.frames.frame-file.queue_depth"))
                if (((Number) status.get(key)).longValue() != 0) throw new AssertionError("Nonzero " + key);
            Files.writeString(directory.resolve("status.txt"), new TreeMap<>(status).toString());
        } finally {
            if (translate) Files.delete(shifted);
        }
        if (observe && (models.get() == 0 || rf.get() == 0 || baseband.get() == 0 || symbols.get() == 0))
            throw new AssertionError("Observer did not render all plots");
        Path recording;
        try (var paths = Files.list(directory.resolve("recording"))) { recording = paths.findFirst().orElseThrow(); }
        int frames = 0;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var in = new DataInputStream(Files.newInputStream(recording)); var out = new DataOutputStream(bytes)) {
            while (in.available() != 0) {
                in.skipNBytes(in.readInt());
                in.skipNBytes(in.readInt());
                int length = in.readInt();
                if (length < 1 || length > 1_048_576 || bytes.size() > 64 * 1024 * 1024) throw new IOException("Invalid/excessive frame capture");
                byte[] payload = in.readNBytes(length);
                if (payload.length != length) throw new EOFException();
                out.writeInt(length);
                out.write(payload);
                frames++;
            }
        }
        byte[] actual = bytes.toByteArray();
        Files.write(directory.resolve("frames.bin"), actual);
        boolean equal = args.length == 4 || Arrays.equals(actual, Files.readAllBytes(Path.of(args[4])));
        double speed = header.dataBytes() / 8 / header.sampleRate() / elapsed;
        String result = String.format(Locale.ROOT, "mode=%s frames=%d good=%d wall_s=%.6f speed=%.3fx equal=%s sha256=%s observer_models=%d%n",
                mode, frames, good, elapsed, speed, equal, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual)), models.get());
        Files.writeString(directory.resolve("result.txt"), result);
        System.out.print(result);
        if (!equal || frames == 0 || good != frames || speed <= 2) throw new AssertionError("Recording equality/count/speed gate failed");
    }

    private static void shift(Path input, WavHeader header, Path output, int direction) throws IOException {
        try (var in = FileChannel.open(input, StandardOpenOption.READ);
             var out = FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            in.position(header.dataOffset());
            ByteBuffer buffer = ByteBuffer.allocateDirect(1_048_576).order(ByteOrder.LITTLE_ENDIAN);
            long remaining = header.dataBytes(), sample = 0;
            while (remaining > 0) {
                buffer.clear().limit((int) Math.min(buffer.capacity(), remaining));
                while (buffer.hasRemaining()) if (in.read(buffer) < 0) throw new EOFException();
                buffer.flip();
                for (int position = 0; position < buffer.limit(); position += 8, sample++) {
                    int i = buffer.getInt(position), q = buffer.getInt(position + 4);
                    switch ((int) (sample * direction) & 3) {
                        case 1 -> { buffer.putInt(position, q ^ Integer.MIN_VALUE); buffer.putInt(position + 4, i); }
                        case 2 -> { buffer.putInt(position, i ^ Integer.MIN_VALUE); buffer.putInt(position + 4, q ^ Integer.MIN_VALUE); }
                        case 3 -> { buffer.putInt(position, q); buffer.putInt(position + 4, i ^ Integer.MIN_VALUE); }
                        default -> { }
                    }
                }
                remaining -= buffer.remaining();
                while (buffer.hasRemaining()) out.write(buffer);
            }
        }
    }

    private static final class Display {
        private final SpectrumPanel rf, baseband;
        private final ConstellationPanel symbols;
        private final BufferedImage image = new BufferedImage(400, 250, BufferedImage.TYPE_INT_RGB);

        private Display(SpectrumSettings spectra, ConstellationSettings constellation) {
            rf = new SpectrumPanel("RF spectrum", spectra, width -> {});
            baseband = new SpectrumPanel("Baseband spectrum", spectra, width -> {});
            symbols = new ConstellationPanel(constellation);
            rf.setSize(400, 250);
            baseband.setSize(400, 250);
            symbols.setSize(400, 250);
        }

        private void apply(UiViewModel model) {
            for (var frame : model.spectra()) {
                if (frame.name().equals("RF spectrum")) rf.apply(frame); else baseband.apply(frame);
            }
            if (model.constellation() != null) symbols.apply(model.constellation());
            Graphics2D graphics = image.createGraphics();
            try { rf.paint(graphics); baseband.paint(graphics); symbols.paint(graphics); }
            finally { graphics.dispose(); }
        }
    }
}
