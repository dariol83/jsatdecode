package eu.dariolucia.jsatdecode.core.runtime;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.api.source.SampleSink;

import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Analytical frequency, block ownership and configured file-to-decoder checks. */
public final class SourceFrequencyRotatorTest {
    private SourceFrequencyRotatorTest() {
    }

    /**
     * Runs without external recordings, hardware or desktop modules.
     *
     * @param args ignored
     * @throws Exception if verification fails
     */
    public static void main(String[] args) throws Exception {
        bypass();
        cardinalShifts();
        tones();
        rateChanges();
        failure();
        configuration();
        fileIntegration();
        System.out.println("SourceFrequencyRotatorTest passed");
    }

    private static void bypass() {
        List<ComplexSampleBlock> received = new ArrayList<>();
        SampleSink sink = received::add;
        var block = new ComplexSampleBlock(new float[]{-0f, 0f, Float.intBitsToFloat(0x7fc12345),
                Float.POSITIVE_INFINITY}, 2, 17, -23, 500_000);
        for (double offset : new double[]{0, -0.0}) {
            SampleSink connected = SourceFrequencyRotator.connect(sink, offset);
            check(connected == sink, "zero installs no rotator");
            connected.accept(block);
            check(received.getLast() == block, "zero preserves immutable block identity and all raw IQ bits");
        }
    }

    private static void tones() {
        int count = 200_003;
        double inputHz = 1_234.5, rate = 125_000;
        float[] iq = new float[count * 2];
        for (int n = 0; n < count; n++) {
            iq[2 * n] = (float) Math.cos(2 * Math.PI * inputHz * n / rate);
            iq[2 * n + 1] = (float) Math.sin(2 * Math.PI * inputHz * n / rate);
        }
        var original = new ComplexSampleBlock(iq, count, 91, 123_456_789, rate);
        for (double offset : new double[]{12_345.25, -12_345.25, -inputHz, rate + 12_345.25}) {
            List<ComplexSampleBlock> whole = new ArrayList<>();
            SourceFrequencyRotator.connect(whole::add, offset).accept(original);
            var output = whole.getFirst();
            check(output.sampleIndex() == 91 && output.timestampNanos() == 123_456_789
                    && output.sampleRate() == rate && output.complexSamples() == count, "metadata and count preserved");
            for (int n = 0; n < count; n++) {
                double angle = 2 * Math.PI * (inputHz + offset) * n / rate;
                near(output.i(n), Math.cos(angle), 2e-7, "translated tone I");
                near(output.q(n), Math.sin(angle), 2e-7, "translated tone Q");
                near(Math.hypot(output.i(n), output.q(n)), 1, 2e-7, "long-run amplitude");
            }

            List<ComplexSampleBlock> parts = new ArrayList<>();
            var split = SourceFrequencyRotator.connect(parts::add, offset);
            var empty = new ComplexSampleBlock(new float[0], 0, 91, 0, rate);
            split.accept(empty);
            check(parts.removeFirst() == empty, "empty block preserves phase and identity");
            int first = 0;
            for (int length : new int[]{1, 510, 2, 997, 4096, count}) {
                int size = Math.min(length, count - first);
                split.accept(new ComplexSampleBlock(Arrays.copyOfRange(iq, 2 * first, 2 * (first + size)),
                        size, first + 91, first + 1_000, rate));
                first += size;
            }
            int position = 0;
            for (var part : parts) {
                check(part.sampleIndex() == position + 91 && part.timestampNanos() == position + 1_000,
                        "each partition retains position and time");
                for (int n = 0; n < part.complexSamples(); n++, position++) {
                    check(Float.floatToRawIntBits(part.i(n)) == Float.floatToRawIntBits(output.i(position))
                            && Float.floatToRawIntBits(part.q(n)) == Float.floatToRawIntBits(output.q(position)),
                            "arbitrary partitions and retained blocks are bit-identical");
                }
            }
            check(position == count && Arrays.equals(iq, original.iq()), "all samples delivered without mutating input");
        }
    }

    private static void cardinalShifts() {
        float[] iq = new float[200_006];
        var random = new Random(317);
        for (int n = 0; n < iq.length; n++) iq[n] = random.nextFloat() - 0.5f;
        var input = new ComplexSampleBlock(iq, iq.length / 2, 0, 0, 500_000);
        for (int quarterTurns : new int[]{-4, -2, -1, 1, 2, 4}) {
            List<ComplexSampleBlock> received = new ArrayList<>();
            SourceFrequencyRotator.connect(received::add, quarterTurns * 125_000.0).accept(input);
            var output = received.getFirst();
            for (int n = 0; n < input.complexSamples(); n++) {
                float i = input.i(n), q = input.q(n);
                float expectedI = switch ((n * quarterTurns) & 3) { case 1 -> -q; case 2 -> -i; case 3 -> q; default -> i; };
                float expectedQ = switch ((n * quarterTurns) & 3) { case 1 -> i; case 2 -> -q; case 3 -> -i; default -> q; };
                check(output.i(n) == expectedI && output.q(n) == expectedQ, "cardinal shifts retain exact float values without phase drift");
            }
        }
    }

    private static void rateChanges() {
        List<ComplexSampleBlock> received = new ArrayList<>();
        var sink = SourceFrequencyRotator.connect(received::add, 31_250);
        float[] dc = new float[]{1, 0, 1, 0, 1, 0};
        sink.accept(new ComplexSampleBlock(dc, 3, 0, 1, 500_000));
        // A native FPGA-decimated output reports 125 ksps even with a 500 ksps PHY.
        sink.accept(new ComplexSampleBlock(dc, 3, 3, 2, 125_000));
        double phase = 0;
        for (var block : received) {
            for (int n = 0; n < block.complexSamples(); n++) {
                near(block.i(n), Math.cos(phase), 1e-7, "output rate sets phase increment");
                near(block.q(n), Math.sin(phase), 1e-7, "rate change preserves phase");
                phase += 2 * Math.PI * 31_250 / block.sampleRate();
            }
        }
        for (double rate : new double[]{Double.MIN_NORMAL, 1, Double.MAX_VALUE}) {
            var huge = new ArrayList<ComplexSampleBlock>();
            SourceFrequencyRotator.connect(huge::add, Double.MAX_VALUE)
                    .accept(new ComplexSampleBlock(dc, 3, 0, 0, rate));
            for (int n = 0; n < 3; n++)
                near(Math.hypot(huge.getFirst().i(n), huge.getFirst().q(n)), 1, 1e-7, "finite extreme offsets do not overflow");
        }
    }

    private static void failure() {
        var expected = new IllegalStateException("decoder failure");
        var sink = SourceFrequencyRotator.connect(block -> { throw expected; }, 10);
        try {
            sink.accept(new ComplexSampleBlock(new float[]{1, 0}, 1, 0, 0, 100));
            throw new AssertionError("Sink failure was lost");
        } catch (IllegalStateException actual) {
            check(actual == expected, "decoder failure propagates unchanged to source");
        }
    }

    private static void configuration() {
        for (Object value : List.of("bad", "NaN", "Infinity", "-Infinity", true, Double.NaN, Double.POSITIVE_INFINITY)) {
            try {
                JSatDecodeRuntime.create(new Configuration(Map.of("source.output_frequency_offset", value)));
                throw new AssertionError("Invalid offset accepted: " + value);
            } catch (ConfigurationException expected) {
                check(expected.getMessage().contains("source.output_frequency_offset"), "precise offset validation before resource creation");
            }
        }
    }

    private static void fileIntegration() throws Exception {
        Path directory = Files.createTempDirectory("jsat-offset-");
        try {
            int rate = 128, total = 30, start = 7, count = 19, blockSize = 3;
            ByteBuffer iq = ByteBuffer.allocate(total * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (int n = 0; n < total; n++) {
                iq.putFloat((float) Math.cos(n * 0.27));
                iq.putFloat((float) Math.sin(n * 0.27));
            }
            Path cf32 = directory.resolve("input.cf32"), wav = directory.resolve("input.wav");
            Files.write(cf32, iq.array());
            ByteBuffer header = ByteBuffer.allocate(44 + iq.capacity()).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(0x46464952).putInt(36 + iq.capacity()).putInt(0x45564157).putInt(0x20746d66)
                    .putInt(16).putShort((short) 3).putShort((short) 2).putInt(rate).putInt(rate * 8)
                    .putShort((short) 8).putShort((short) 32).putInt(0x61746164).putInt(iq.capacity()).put(iq.array());
            Files.write(wav, header.array());
            for (String type : List.of("cf32", "wav")) {
                int variant = 0;
                for (String offset : List.of("absent", "0", "-0.0", "32", "-32", "0.75")) {
                    Path output = directory.resolve(type + variant++);
                    var settings = new LinkedHashMap<String, Object>();
                    settings.put("source.type", type);
                    settings.put("source.file", (type.equals("cf32") ? cf32 : wav).toString());
                    if (type.equals("cf32")) settings.put("source.sample_rate", rate);
                    settings.put("source.block_samples", blockSize);
                    settings.put("source.start_offset", start);
                    settings.put("source.length", count);
                    if (!offset.equals("absent")) settings.put("source.output_frequency_offset", offset);
                    settings.put("decoder.type", "dummy");
                    settings.put("distribution.frames.type", "synthetic");
                    settings.put("distribution.monitoring.type", "synthetic");
                    settings.put("storage.folder", output.toString());
                    try (var runtime = JSatDecodeRuntime.create(new Configuration(settings))) {
                        runtime.start();
                        runtime.awaitCompletion();
                        check(runtime.failure().isEmpty(), "configured file-to-decoder success");
                    }
                    List<Path> files;
                    try (var paths = Files.list(output)) { files = paths.toList(); }
                    check(files.size() == 1, "single frame recording");
                    int position = 0;
                    double hz = offset.equals("absent") ? 0 : Double.parseDouble(offset);
                    try (var input = new DataInputStream(Files.newInputStream(files.getFirst()))) {
                        while (input.available() != 0) {
                            input.skipNBytes(input.readInt()); // Topic.
                            input.skipNBytes(input.readInt()); // CBOR metadata.
                            int samples = Math.min(blockSize, count - position);
                            check(input.readInt() == samples * 8, "block boundaries retained through runtime");
                            ByteBuffer payload = ByteBuffer.wrap(input.readNBytes(samples * 8)).order(ByteOrder.LITTLE_ENDIAN);
                            for (int n = 0; n < samples; n++, position++) {
                                float i = iq.getFloat((start + position) * 8), q = iq.getFloat((start + position) * 8 + 4);
                                float actualI = payload.getFloat(), actualQ = payload.getFloat();
                                if (hz == 0) {
                                    check(Float.floatToRawIntBits(actualI) == Float.floatToRawIntBits(i)
                                            && Float.floatToRawIntBits(actualQ) == Float.floatToRawIntBits(q), "configured zero/absent is bit-exact");
                                } else {
                                    double phase = 2 * Math.PI * hz * position / rate;
                                    near(actualI, i * Math.cos(phase) - q * Math.sin(phase), 1e-7, "runtime translated I");
                                    near(actualQ, i * Math.sin(phase) + q * Math.cos(phase), 1e-7, "runtime translated Q");
                                }
                            }
                        }
                    }
                    check(position == count, "complete selected sample range");
                }
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void near(double actual, double expected, double tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " != " + expected);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
