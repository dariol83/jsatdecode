package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.dsp.fft.WindowFunction;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Self-contained acquisition mapping, continuity and anti-aliasing checks. */
public final class InspectorReceiverTest {
    private static int checks;

    /** Runs the receiver suite without contacting a PlutoPlus. */
    public static void main(String[] arguments) throws Exception { run(); }

    /** Exercises exact metadata and low-rate acquisition mapping. */
    public static void run() throws Exception {
        mapping();
        decimation();
        continuity();
        for (RecordingFormat format : RecordingFormat.values()) networkRecording(format);
        System.out.println("JSatInspector receiver: " + checks + " checks passed");
    }

    private static InspectorSettings settings(int sampleRate, int decimation) {
        return settings(sampleRate, decimation, 16384);
    }

    private static InspectorSettings settings(int sampleRate, int decimation, int bufferSamples) {
        return new InspectorSettings("127.0.0.1:30431", 137100000, 2000000, sampleRate, decimation, 42, bufferSamples,
                -140, 0, WindowFunction.HANN, 15, 4096, 2, Path.of("."), false, 1280, 850);
    }

    private static void mapping() {
        for (int sampleRate : new int[] {500000, 1000000, 2000000, 3000000, 4000000, 5000000}) {
            for (int decimation : new int[] {1, 2, 4, 8}) {
                InspectorSettings settings = settings(sampleRate, decimation);
                var configuration = InspectorReceiver.sourceConfiguration(settings);
                int expected = sampleRate <= 2000000 ? 4000000 : sampleRate;
                check(configuration.getInt("sample_rate") == expected && configuration.getInt("fpga_decimation") == 1,
                        "Inspector software decimation leaves shared hardware/source behavior unchanged");
                check(configuration.getInt("buffer_size") == 16384 && configuration.getInt("block_samples") == 4096,
                        "RX sample count passes directly to the source");
                List<ComplexSampleBlock> output = new ArrayList<>();
                try (InspectorReceiver receiver = new InspectorReceiver(settings, output::add)) {
                    receiver.accept(tone(expected, 0, 4096, 1000));
                    check(receiver.failure() == null && output.get(0).sampleRate() == settings.effectiveSampleRate(),
                            "All 24 sample-rate/decimation combinations produce selected effective rate");
                }
            }
        }
        for (int samples : new int[] {1, 4095, 4096, 4097, 65535, 1048576}) {
            var configuration = InspectorReceiver.sourceConfiguration(settings(3000000, 1, samples));
            check(configuration.getInt("buffer_size") == samples &&
                            configuration.getInt("block_samples") == Math.min(samples, 4096),
                    "RX buffer remains in complex samples at the source and delivery-block boundary");
        }
    }

    private static void decimation() {
        for (int factor : new int[] {1, 2, 4, 8, 16, 32, 64}) {
            int count = 65536, rate = 4000000;
            InspectorDecimator together = new InspectorDecimator(factor);
            ComplexSampleBlock expected = together.process(tone(rate, 0, count, 1000));
            InspectorDecimator split = new InspectorDecimator(factor);
            List<ComplexSampleBlock> chunks = new ArrayList<>();
            int offset = 0;
            while (offset < count) {
                int size = Math.min(count - offset, offset % 113 + 1);
                chunks.add(split.process(tone(rate, offset, size, 1000)));
                offset += size;
            }
            int sample = 0;
            for (ComplexSampleBlock chunk : chunks) {
                check(chunk.sampleIndex() == sample && chunk.sampleRate() == rate / (double) factor, "Streaming output index/rate remains continuous");
                for (int n = 0; n < chunk.complexSamples(); n++, sample++) {
                    if (Float.floatToRawIntBits(chunk.i(n)) != Float.floatToRawIntBits(expected.i(sample)) ||
                            Float.floatToRawIntBits(chunk.q(n)) != Float.floatToRawIntBits(expected.q(sample)))
                        throw new AssertionError("Decimation depends on source block boundaries for factor " + factor);
                }
            }
            check(sample == expected.complexSamples(), "Arbitrary odd source chunks preserve every decimated output sample");
            check(Math.abs(rms(expected, 256) - 1) < 0.001, "In-band tone retains unity gain after startup for factor " + factor);
            if (factor > 1) {
                ComplexSampleBlock rejected = new InspectorDecimator(factor).process(tone(rate, 0, count, rate / (double) factor * 0.7));
                check(rms(rejected, 256) < 0.001, "Anti-alias filter rejects out-of-band tone for factor " + factor);
            }
        }
    }

    private static void continuity() {
        List<ComplexSampleBlock> output = new ArrayList<>();
        try (InspectorReceiver receiver = new InspectorReceiver(settings(500000, 8), output::add)) {
            receiver.accept(tone(4000000, 0, 4096, 1000));
            receiver.accept(tone(4000000, 4096, 4096, 1000));
            check(output.size() == 2 && output.get(1).sampleIndex() == 64, "Effective recording and analysis share continuous sample indices");
            check(output.get(1).timestampNanos() == 1_000_000_000L + 1_024_000L, "Effective timestamps retain source UTC signal clock");
            receiver.accept(tone(4000000, 8193, 4096, 1000));
            check(receiver.failure() != null && receiver.failure().getMessage().contains("overrun/underrun"), "Source gaps become explicit reception failures");
            check(output.size() == 2, "Discontinuous samples cannot silently join a recording");
        }
        try (InspectorReceiver receiver = new InspectorReceiver(settings(500000, 1), ignored -> {})) {
            receiver.accept(tone(3000000, 0, 4096, 1000));
            check(receiver.failure() != null && receiver.failure().getMessage().contains("sample rate"), "Unexpected hardware sample rate fails explicitly");
        }
    }

    private static void networkRecording(RecordingFormat format) throws Exception {
        Path folder = Files.createTempDirectory("jsatinspector-iiod-test-");
        try (IiodServer server = new IiodServer()) {
            InspectorSettings d = settings(500000, 1);
            InspectorSettings configuration = new InspectorSettings("127.0.0.1:" + server.port(), d.frequencyHz(), d.bandwidth(),
                    d.sampleRate(), d.decimation(), d.gain(), d.rxBufferSamples(), d.minDb(), d.maxDb(), d.window(),
                    d.fftRate(), d.fftSize(), d.averaging(), folder, false, d.windowWidth(), d.windowHeight());
            AtomicInteger samples = new AtomicInteger();
            CountDownLatch received = new CountDownLatch(1);
            try (InspectorReceiver receiver = new InspectorReceiver(configuration, block -> {
                if (samples.addAndGet(block.complexSamples()) == 2048) received.countDown();
            })) {
                receiver.start();
                check(receiver.running(), "Shared PlutoPlus adapter connects through inspector management");
                Path recording = receiver.startRecording(folder, format);
                server.release.countDown();
                check(received.await(5, TimeUnit.SECONDS), "Network acquisition delivers every decimated sample");
                receiver.stopRecording();
                check(receiver.running() && !receiver.recording() && receiver.recordingFailure() == null,
                        "Stopping disk storage leaves network reception running");
                check(recording.getFileName().toString().matches("137100001_500000_42_\\d{8}T\\d{6}" +
                                java.util.regex.Pattern.quote(format.extension())),
                        format + " recording uses actual hardware LO, seconds-only timestamp and selected extension");
                float[] input = new float[32768];
                for (int n = 0; n < input.length; n += 2) {
                    input[n] = 0.125f;
                    input[n + 1] = -0.0625f;
                }
                ComplexSampleBlock expected = new InspectorDecimator(8).process(new ComplexSampleBlock(input, 16384, 0, 0, 4000000));
                ByteBuffer bytes = ByteBuffer.allocate(expected.complexSamples() * format.bytesPerComplexSample()).order(ByteOrder.LITTLE_ENDIAN);
                for (int n = 0; n < expected.complexSamples(); n++) {
                    if (format == RecordingFormat.CF32) {
                        bytes.putFloat(expected.i(n));
                        bytes.putFloat(expected.q(n));
                    } else {
                        bytes.putShort((short) Math.round(expected.i(n) * 32768));
                        bytes.putShort((short) Math.round(expected.q(n) * 32768));
                    }
                }
                check(Arrays.equals(bytes.array(), Files.readAllBytes(recording)),
                        "Native int16 network scans record as exact decimated " + format + " bytes");
                receiver.close();
                check(receiver.failure() == null && !receiver.running(), "Closing receiver cancels blocked network reads and joins cleanly");
            }
        } finally {
            try (var paths = Files.walk(folder)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static ComplexSampleBlock tone(int rate, int offset, int count, double frequency) {
        float[] iq = new float[count * 2];
        for (int n = 0; n < count; n++) {
            double phase = 2 * Math.PI * frequency * (offset + n) / rate;
            iq[n * 2] = (float) Math.cos(phase);
            iq[n * 2 + 1] = (float) Math.sin(phase);
        }
        return new ComplexSampleBlock(iq, count, offset, 1_000_000_000L + (long) (offset / (double) rate * 1e9), rate);
    }

    private static double rms(ComplexSampleBlock block, int skip) {
        double sum = 0;
        for (int n = skip; n < block.complexSamples(); n++) sum += block.i(n) * block.i(n) + block.q(n) * block.q(n);
        return Math.sqrt(sum / (block.complexSamples() - skip));
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }

    /* Local IIOD peer exercises the unchanged network adapter rather than a fake
       receiver. First READBUF waits until storage is attached, the next waits
       until intentional socket cancellation. No timing-sensitive sample loss. */
    private static final class IiodServer implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        final CountDownLatch release = new CountDownLatch(1);
        private final Thread worker = new Thread(this::run, "inspector-test-iiod");
        private volatile Socket peer;
        private volatile Throwable failure;

        IiodServer() throws IOException { worker.start(); }
        int port() { return listener.getLocalPort(); }

        private void run() {
            try (Socket socket = listener.accept()) {
                peer = socket;
                socket.setTcpNoDelay(true);
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                Map<String, byte[]> values = new HashMap<>();
                String mask = "00000003";
                boolean sent = false;
                for (String command; (command = line(input)) != null;) {
                    if (command.startsWith("TIMEOUT ") || command.startsWith("SET ")) send(output, "0\n");
                    else if (command.equals("PRINT")) {
                        byte[] context = xml().getBytes(StandardCharsets.UTF_8);
                        send(output, context.length + "\n");
                        output.write(context);
                        send(output, "\n");
                    } else if (command.startsWith("WRITE ")) {
                        int split = command.lastIndexOf(' ');
                        int length = Integer.parseInt(command.substring(split + 1));
                        values.put(command.substring(6, split), input.readNBytes(length));
                        send(output, length + "\n");
                    } else if (command.startsWith("READ ")) {
                        byte[] value = command.endsWith(" frequency") ? "137100001".getBytes(StandardCharsets.US_ASCII) : values.get(command.substring(5));
                        send(output, value.length + "\n");
                        output.write(value);
                        send(output, "\n");
                    } else if (command.startsWith("OPEN ")) {
                        mask = command.split(" ")[3];
                        send(output, "0\n");
                    } else if (command.startsWith("READBUF ")) {
                        if (sent) { input.read(); return; }
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Recorder did not start");
                        int length = Integer.parseInt(command.substring(command.lastIndexOf(' ') + 1));
                        ByteBuffer samples = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
                        while (samples.hasRemaining()) samples.putShort((short) 256).putShort((short) -128);
                        send(output, length + "\n" + mask + "\n");
                        output.write(samples.array());
                        output.flush();
                        sent = true;
                    } else throw new IOException("Unexpected IIOD request: " + command);
                }
            } catch (SocketException ignored) { /* Intentional management cancellation. */ }
            catch (Throwable e) { failure = e; }
        }

        private static void send(OutputStream output, String value) throws IOException {
            output.write(value.getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }

        private static String line(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int value; (value = input.read()) >= 0;) {
                if (value == '\n') return bytes.toString(StandardCharsets.US_ASCII).strip();
                bytes.write(value);
            }
            return null;
        }

        private static String xml() {
            StringBuilder phy = new StringBuilder("<channel id='voltage0' type='input'>");
            for (String name : List.of("sampling_frequency", "rf_bandwidth", "rf_port_select", "hardwaregain", "gain_control_mode",
                    "quadrature_tracking_en", "rf_dc_offset_tracking_en", "bb_dc_offset_tracking_en"))
                phy.append("<attribute name='").append(name).append("'/>");
            phy.append("</channel><channel id='altvoltage0' type='output'><attribute name='frequency'/></channel><attribute name='in_out_voltage_filter_fir_en'/>");
            return "<?xml version='1.0'?><context name='local'><device id='iio:device0' name='ad9361-phy'>" + phy + "</device>"
                    + "<device id='iio:device1' name='cf-ad9361-lpc'>"
                    + "<channel id='voltage0' type='input'><scan-element index='0' format='le:S12/16&gt;&gt;0'/></channel>"
                    + "<channel id='voltage1' type='input'><scan-element index='1' format='le:S12/16&gt;&gt;0'/></channel>"
                    + "</device></context>";
        }

        @Override public void close() throws Exception {
            release.countDown();
            listener.close();
            if (peer != null) peer.close();
            worker.join(5000);
            if (worker.isAlive()) throw new AssertionError("IIOD test server leaked");
            if (failure != null) throw new AssertionError("IIOD test server failed", failure);
        }
    }
}
