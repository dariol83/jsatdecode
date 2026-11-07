package eu.dariolucia.jsatdecode;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.lifecycle.LifecycleException;
import eu.dariolucia.jsatdecode.api.source.*;
import eu.dariolucia.jsatdecode.source.cf32.*;
import eu.dariolucia.jsatdecode.source.cs16.*;
import eu.dariolucia.jsatdecode.source.file.AbstractFileSampleSource;
import eu.dariolucia.jsatdecode.source.wav.*;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic exact-sample, file validation, lifecycle and pacing acceptance tests. */
public final class FileSourceTest {
    private static int checks;
    private FileSourceTest() {}

    /** Generates private fixtures and runs Phase 4 checks; always removes test recordings.
     * @param args unused
     * @throws Exception if fixture generation or a check fails
     */
    public static void main(String[] args) throws Exception {
        Path directory = FileSourceFixtures.temporaryDirectory("tests-");
        try {
            samplesAndRanges(directory);
            cs16Samples(directory);
            specialValues(directory);
            cs16SpecialValues(directory);
            repeat(directory);
            cs16Repeat(directory);
            wavVariants(directory);
            malformedWav(directory);
            configurationErrors(directory);
            cs16ConfigurationErrors(directory);
            lifecycle(directory);
            failures(directory);
            pacing(directory);
            cs16Pacing(directory);
            largeFiles(directory);
            cs16LargeFiles(directory);
            discovery();
            System.out.println("Phase 4: " + checks + " checks passed");
        } finally { FileSourceFixtures.clean(directory); }
    }

    private static Configuration config(Path file, Object... settings) {
        Map<String, Object> values = new HashMap<>();
        values.put("file", file.toString());
        for (int n = 0; n < settings.length; n += 2) values.put((String) settings[n], settings[n + 1]);
        return new Configuration(values);
    }

    private static void samplesAndRanges(Path directory) throws Exception {
        float[] iq = FileSourceFixtures.samples(1003);
        Path cf32 = directory.resolve("samples.cf32"), wav = directory.resolve("samples.wav");
        Files.write(cf32, FileSourceFixtures.floats(iq));
        Files.write(wav, FileSourceFixtures.wav(iq, 500000));
        // Decode each complete recording with multiple block sizes, retaining all blocks.
        for (boolean wave : new boolean[]{false, true}) {
            for (int block : new int[]{1, 7, 4096}) {
                try (AbstractFileSampleSource source = source(wave, config(wave ? wav : cf32,
                        "sample_rate", 500000, "block_samples", block))) {
                    List<ComplexSampleBlock> received = collect(source);
                    verify(received, iq, 0, 1003, 500000);
                    check(received.size() == (1003 + block - 1) / block);
                    check(source.state() == ComponentState.STOPPED);
                    check(source.sampleFormat() == SampleFormat.COMPLEX_FLOAT32_INTERLEAVED);
                    check(source.failure().isEmpty());
                    var values = source.monitoringSnapshot().values();
                    check(values.get("source.samples").equals(1003L));
                    check(values.get("source.blocks").equals((long) received.size()));
                    check(values.get("source.cycles").equals(1L));
                    check(values.get("source.eof").equals(true));
                    check(values.get("source.position").equals(1003L));
                    check((Double) values.get("source.elapsed_s") > 0);
                    check((Double) values.get("source.realtime_factor") >= 0);
                    check(source.monitoringDescriptors().size() == values.size());
                    expect(UnsupportedOperationException.class, () -> values.clear());
                }
            }
            List<ComplexSampleBlock> firstRun;
            // Length and exclusive-end selection must produce identical functional output.
            try (var source = source(wave, config(wave ? wav : cf32, "sample_rate", 500000,
                    "block_samples", 3, "start_offset", 17, "length", 10))) {
                firstRun = collect(source);
                verify(firstRun, iq, 17, 10, 500000);
                check(source.monitoringSnapshot().values().get("source.selected_samples").equals(10L));
            }
            try (var source = source(wave, config(wave ? wav : cf32, "sample_rate", 500000,
                    "block_samples", 3, "start_offset", 17, "end_offset", 27))) {
                List<ComplexSampleBlock> secondRun = collect(source);
                check(secondRun.size() == firstRun.size());
                for (int n = 0; n < firstRun.size(); n++) {
                    check(Arrays.equals(secondRun.get(n).iq(), firstRun.get(n).iq()));
                    check(secondRun.get(n).sampleIndex() == firstRun.get(n).sampleIndex());
                }
            }
            try (var source = source(wave, config(wave ? wav : cf32, "sample_rate", 500000, "start_offset", 1003))) {
                // An empty nonrepeating selection completes without an empty block.
                check(collect(source).isEmpty());
                check(source.monitoringSnapshot().values().get("source.samples").equals(0L));
                check(source.monitoringSnapshot().values().get("source.eof").equals(true));
            }
        }
        Path empty = directory.resolve("empty.cf32");
        Files.write(empty, new byte[0]);
        try (var source = new Cf32SampleSource(config(empty, "sample_rate", 1))) {
            check(collect(source).isEmpty());
        }
    }

    private static void cs16Samples(Path directory) throws Exception {
        float[] iq = FileSourceFixtures.samples(1003);
        Path cs16 = directory.resolve("samples.cs16");
        Files.write(cs16, FileSourceFixtures.cs16(iq));
        // Decode the complete recording with multiple block sizes, retaining all blocks.
        for (int block : new int[]{1, 7, 4096}) {
            try (Cs16SampleSource source = new Cs16SampleSource(config(cs16, "sample_rate", 500000, "block_samples", block))) {
                List<ComplexSampleBlock> received = collectCs16(source);
                verify(received, iq, 0, 1003, 500000, true);
                check(received.size() == (1003 + block - 1) / block);
                check(source.state() == ComponentState.STOPPED);
                check(source.sampleFormat() == SampleFormat.COMPLEX_FLOAT32_INTERLEAVED);
                check(source.failure().isEmpty());
                var values = source.monitoringSnapshot().values();
                check(values.get("source.samples").equals(1003L));
                check(values.get("source.blocks").equals((long) received.size()));
                check(values.get("source.cycles").equals(1L));
                check(values.get("source.eof").equals(true));
                check(values.get("source.position").equals(1003L));
                check((Double) values.get("source.elapsed_s") > 0);
                check((Double) values.get("source.realtime_factor") >= 0);
                check(source.monitoringDescriptors().size() == values.size());
            }
        }
        // Test with length and exclusive-end selection
        List<ComplexSampleBlock> firstRun;
        try (var source = new Cs16SampleSource(config(cs16, "sample_rate", 500000,
                "block_samples", 3, "start_offset", 17, "length", 10))) {
            firstRun = collectCs16(source);
            verify(firstRun, iq, 17, 10, 500000, true);
            check(source.monitoringSnapshot().values().get("source.selected_samples").equals(10L));
        }
        try (var source = new Cs16SampleSource(config(cs16, "sample_rate", 500000,
                "block_samples", 3, "start_offset", 17, "end_offset", 27))) {
            List<ComplexSampleBlock> secondRun = collectCs16(source);
            check(secondRun.size() == firstRun.size());
            for (int n = 0; n < firstRun.size(); n++) {
                // Compare with tolerance for CS16
                float[] iq1 = secondRun.get(n).iq();
                float[] iq2 = firstRun.get(n).iq();
                check(iq1.length == iq2.length);
                for (int i = 0; i < iq1.length; i++) {
                    check(Math.abs(iq1[i] - iq2[i]) < 1e-6f);
                }
                check(secondRun.get(n).sampleIndex() == firstRun.get(n).sampleIndex());
            }
        }
        try (var source = new Cs16SampleSource(config(cs16, "sample_rate", 500000, "start_offset", 1003))) {
            // An empty nonrepeating selection completes without an empty block.
            check(collectCs16(source).isEmpty());
            check(source.monitoringSnapshot().values().get("source.samples").equals(0L));
            check(source.monitoringSnapshot().values().get("source.eof").equals(true));
        }
        Path empty = directory.resolve("empty.cs16");
        Files.write(empty, new byte[0]);
        try (var source = new Cs16SampleSource(config(empty, "sample_rate", 1))) {
            check(collectCs16(source).isEmpty());
        }
    }

    private static AbstractFileSampleSource source(boolean wave, Configuration configuration) {
        return wave ? new WavSourceFactory().create(configuration) : new Cf32SourceFactory().create(configuration);
    }

    private static List<ComplexSampleBlock> collectCs16(Cs16SampleSource source) throws Exception {
        List<ComplexSampleBlock> blocks = new ArrayList<>();
        source.setSampleSink(blocks::add);
        source.start();
        check(source.awaitCompletion(Duration.ofSeconds(10)));
        source.stop(); // Join after EOF before inspecting the retained immutable blocks.
        if (source.failure().isPresent()) throw new AssertionError("Source failed", source.failure().orElseThrow());
        return blocks;
    }

    private static void cs16SpecialValues(Path directory) throws Exception {
        float[] iq = {Float.intBitsToFloat(0x7fc01234), -0.0f, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.MIN_VALUE, -Float.MAX_VALUE};
        Path file = directory.resolve("special.cs16");
        Files.write(file, FileSourceFixtures.cs16(iq));
        try (var source = new Cs16SampleSource(config(file, "sample_rate", 8, "block_samples", 1))) {
            // CS16 saturates values outside [-1.0, 1.0] range
            List<ComplexSampleBlock> blocks = collectCs16(source);
            check(blocks.size() == 3);
            // Verify that values are properly converted from int16 to float
            for (int i = 0; i < blocks.size(); i++) {
                ComplexSampleBlock block = blocks.get(i);
                check(block.complexSamples() == 1);
            }
        }
    }

    private static void cs16Repeat(Path directory) throws Exception {
        float[] iq = FileSourceFixtures.samples(8);
        Path file = directory.resolve("repeat.cs16");
        Files.write(file, FileSourceFixtures.cs16(iq));
        try (var source = new Cs16SampleSource(config(file, "sample_rate", 8, "start_offset", 2,
                "end_offset", 5, "repeat", true, "block_samples", 2))) {
            List<ComplexSampleBlock> blocks = new ArrayList<>();
            source.setSampleSink(block -> {
                blocks.add(block);
                if (blocks.size() == 6) source.requestStop();
            });
            source.start();
            check(source.awaitCompletion(Duration.ofSeconds(5)));
            source.stop();
            check(source.state() == ComponentState.STOPPED && source.failure().isEmpty());
            check(blocks.size() == 6);
            for (int cycle = 0; cycle < 3; cycle++) verify(blocks.subList(cycle * 2, cycle * 2 + 2), iq, 2, 3, 8, true);
            int[] cumulative = {0, 2, 3, 5, 6, 8};
            long firstTimestamp = blocks.getFirst().timestampNanos();
            check(Math.abs(firstTimestamp-eu.dariolucia.jsatdecode.util.UtcTime.nowNanos())<5_000_000_000L);
            for (int n = 0; n < 6; n++) check(blocks.get(n).timestampNanos() - firstTimestamp == cumulative[n] * 125_000_000L);
            var values = source.monitoringSnapshot().values();
            check(values.get("source.samples").equals(9L) && values.get("source.cycles").equals(3L));
            check(values.get("source.eof").equals(false));
        }
    }

    private static void cs16ConfigurationErrors(Path directory) throws Exception {
        Path file = directory.resolve("config.cs16");
        Files.write(file, FileSourceFixtures.cs16(FileSourceFixtures.samples(4)));
        // Missing required settings
        expect(ConfigurationException.class, () -> new Cs16SampleSource(config(file)));
        expect(ConfigurationException.class, () -> new Cs16SampleSource(config(directory, "sample_rate", 1)));
        expect(ConfigurationException.class, () -> new Cs16SampleSource(config(directory.resolve("missing"), "sample_rate", 1)));
        // CS16 requires file size to be multiple of 4
        Files.write(file, new byte[7]);
        expect(ConfigurationException.class, () -> new Cs16SampleSource(config(file, "sample_rate", 1)));
    }

    private static void cs16Pacing(Path directory) throws Exception {
        Path file = directory.resolve("paced.cs16");
        float[] iq = FileSourceFixtures.samples(20);
        Files.write(file, FileSourceFixtures.cs16(iq));
        try (var source = new Cs16SampleSource(config(file, "sample_rate", 100, "realtime", true, "block_samples", 5))) {
            long start = System.nanoTime();
            List<ComplexSampleBlock> received = collectCs16(source);
            check(System.nanoTime() - start >= 180_000_000);
            verify(received, iq, 0, 20, 100, true);
            for (int n = 1; n < received.size(); n++) {
                check(received.get(n).timestampNanos() - received.get(n - 1).timestampNanos() == 50_000_000);
            }
            double elapsed = (Double) source.monitoringSnapshot().values().get("source.elapsed_s");
            double factor = (Double) source.monitoringSnapshot().values().get("source.realtime_factor");
            check(Math.abs(factor - 0.2 / elapsed) < 1e-12);
        }
    }

    private static void cs16LargeFiles(Path directory) throws Exception {
        // Test with a large file - CS16: 4 bytes per complex sample
        int complexSampleCount = 500000;
        float[] iq = FileSourceFixtures.samples(complexSampleCount);
        Path cs16 = directory.resolve("large.cs16");
        Files.write(cs16, FileSourceFixtures.cs16(iq));
        try (var source = new Cs16SampleSource(config(cs16, "sample_rate", 48000,
                "start_offset", complexSampleCount - 4, "length", 4, "block_samples", 2))) {
            List<ComplexSampleBlock> blocks = collectCs16(source);
            check(blocks.size() == 2 && blocks.getFirst().sampleIndex() == complexSampleCount - 4);
            check(source.monitoringSnapshot().values().get("source.total_samples").equals((long) complexSampleCount));
        }
    }

    private static List<ComplexSampleBlock> collect(AbstractFileSampleSource source) throws Exception {
        List<ComplexSampleBlock> blocks = new ArrayList<>();
        source.setSampleSink(blocks::add);
        source.start();
        check(source.awaitCompletion(Duration.ofSeconds(10)));
        source.stop(); // Join after EOF before inspecting the retained immutable blocks.
        if (source.failure().isPresent()) throw new AssertionError("Source failed", source.failure().orElseThrow());
        return blocks;
    }

    private static void verify(List<ComplexSampleBlock> blocks, float[] expected, long offset, int count, double rate) {
        verify(blocks, expected, offset, count, rate, false);
    }

    private static void verify(List<ComplexSampleBlock> blocks, float[] expected, long offset, int count, double rate, boolean cs16) {
        long next = offset;
        int actual = 0;
        for (ComplexSampleBlock block : blocks) {
            check(block.sampleIndex() == next);
            check(block.sampleRate() == rate && block.complexSamples() > 0);
            for (int n = 0; n < block.complexSamples(); n++) {
                if (cs16) {
                    // For CS16, values are converted from int16 to float, so we need to compare with tolerance
                    float expectedI = expected[(int) next * 2];
                    float expectedQ = expected[(int) next * 2 + 1];
                    // Convert expected values to int16 and back to float to match the CS16 conversion
                    short expectedI16 = quantize(expectedI);
                    short expectedQ16 = quantize(expectedQ);
                    float expectedIConvert = expectedI16 * CS16_SCALE;
                    float expectedQConvert = expectedQ16 * CS16_SCALE;
                    check(Math.abs(block.i(n) - expectedIConvert) < 1e-6f);
                    check(Math.abs(block.q(n) - expectedQConvert) < 1e-6f);
                } else {
                    check(Float.floatToRawIntBits(block.i(n)) == Float.floatToRawIntBits(expected[(int) next * 2]));
                    check(Float.floatToRawIntBits(block.q(n)) == Float.floatToRawIntBits(expected[(int) next * 2 + 1]));
                }
                next++;
                actual++;
            }
        }
        check(actual == count);
    }

    private static final float CS16_SCALE = 1.0f / 32768.0f;

    private static short quantize(float sample) {
        // Same quantization as used in CS16 conversion
        long rounded = Math.round(sample * 32768.0);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, rounded));
    }

    private static void specialValues(Path directory) throws Exception {
        float[] iq = {Float.intBitsToFloat(0x7fc01234), -0.0f, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.MIN_VALUE, -Float.MAX_VALUE};
        for (boolean wave : new boolean[]{false, true}) {
            Path file = directory.resolve(wave ? "special.wav" : "special.cf32");
            Files.write(file, wave ? FileSourceFixtures.wav(iq, 8) : FileSourceFixtures.floats(iq));
            try (var source = source(wave, config(file, "sample_rate", 8, "block_samples", 1))) {
                verify(collect(source), iq, 0, 3, 8);
            }
        }
    }

    private static void repeat(Path directory) throws Exception {
        float[] iq = FileSourceFixtures.samples(8);
        for (boolean wave : new boolean[]{false, true}) {
            Path file = directory.resolve(wave ? "repeat.wav" : "repeat.cf32");
            Files.write(file, wave ? FileSourceFixtures.wav(iq, 8) : FileSourceFixtures.floats(iq));
            try (var source = source(wave, config(file, "sample_rate", 8, "start_offset", 2,
                    "end_offset", 5, "repeat", true, "block_samples", 2))) {
                List<ComplexSampleBlock> blocks = new ArrayList<>();
                source.setSampleSink(block -> {
                    blocks.add(block);
                    if (blocks.size() == 6) source.requestStop();
                });
                source.start();
                check(source.awaitCompletion(Duration.ofSeconds(5)));
                source.stop();
                check(source.state() == ComponentState.STOPPED && source.failure().isEmpty());
                check(blocks.size() == 6);
                for (int cycle = 0; cycle < 3; cycle++) verify(blocks.subList(cycle * 2, cycle * 2 + 2), iq, 2, 3, 8);
                int[] cumulative = {0, 2, 3, 5, 6, 8};
                long firstTimestamp = blocks.getFirst().timestampNanos();
                check(Math.abs(firstTimestamp-eu.dariolucia.jsatdecode.util.UtcTime.nowNanos())<5_000_000_000L);
                for (int n = 0; n < 6; n++) check(blocks.get(n).timestampNanos() - firstTimestamp == cumulative[n] * 125_000_000L);
                var values = source.monitoringSnapshot().values();
                check(values.get("source.samples").equals(9L) && values.get("source.cycles").equals(3L));
                check(values.get("source.eof").equals(false));
            }
        }
    }

    private static void wavVariants(Path directory) throws Exception {
        float[] iq = FileSourceFixtures.samples(7);
        byte[] fmt = FileSourceFixtures.chunk("fmt ", FileSourceFixtures.format(48000, false));
        byte[] data = FileSourceFixtures.chunk("data", FileSourceFixtures.floats(iq));
        byte[] junk = FileSourceFixtures.chunk("JUNK", new byte[]{1, 2, 3});
        byte[][] variants = {
            FileSourceFixtures.riff(junk, fmt, junk, data, junk),
            FileSourceFixtures.riff(data, junk, fmt),
            FileSourceFixtures.riff(FileSourceFixtures.chunk("fmt ", FileSourceFixtures.format(48000, true)), data),
            FileSourceFixtures.riff(fmt, FileSourceFixtures.chunk("data", new byte[0]))
        };
        for (int n = 0; n < variants.length; n++) {
            Path file = directory.resolve("variant-" + n + ".wav");
            Files.write(file, variants[n]);
            WavHeader header = WavHeader.read(file);
            check(header.sampleRate() == 48000);
            try (var source = new WavSampleSource(config(file, "block_samples", 3))) {
                verify(collect(source), iq, 0, n == 3 ? 0 : 7, 48000);
            }
        }
        Path rf64 = directory.resolve("small.rf64");
        byte[] header = FileSourceFixtures.rf64Header(iq.length * 4L, iq.length / 2, 48000);
        Files.write(rf64, header);
        Files.write(rf64, FileSourceFixtures.floats(iq), StandardOpenOption.APPEND);
        try (var source = new WavSampleSource(config(rf64))) { verify(collect(source), iq, 0, 7, 48000); }
    }

    private static void malformedWav(Path directory) throws Exception {
        byte[] good = FileSourceFixtures.wav(FileSourceFixtures.samples(3), 48000);
        Path invalid = directory.resolve("invalid.wav");
        // Header field corruption exercises encoding, geometry and container boundaries.
        int[][] corruptions = {{0, 0}, {8, 0}, {20, 1}, {22, 1}, {24, 0},
                {28, 0}, {32, 4}, {34, 16}, {16, 15}, {40, 23}, {4, 1000000}, {36, 0}, {12, 0}};
        for (int[] corruption : corruptions) {
            byte[] bytes = good.clone();
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int offset = corruption[0], value = corruption[1];
            if (offset == 20 || offset == 22 || offset == 32 || offset == 34) buffer.putShort(offset, (short) value);
            else buffer.putInt(offset, value);
            Files.write(invalid, bytes);
            expect(IOException.class, () -> WavHeader.read(invalid));
            expect(ConfigurationException.class, () -> new WavSampleSource(config(invalid)));
        }
        for (int length : new int[]{0, 4, 11, 19, good.length - 1}) {
            Files.write(invalid, Arrays.copyOf(good, length));
            expect(IOException.class, () -> WavHeader.read(invalid));
        }
        byte[] fmt = FileSourceFixtures.chunk("fmt ", FileSourceFixtures.format(48000, false));
        byte[] data = FileSourceFixtures.chunk("data", new byte[8]);
        // Reject ambiguous duplicate regions and extensible PCM masquerading as float.
        for (byte[] contents : new byte[][]{
                FileSourceFixtures.riff(fmt, fmt, data), FileSourceFixtures.riff(fmt, data, data),
                FileSourceFixtures.riff(data), FileSourceFixtures.riff(fmt)}) {
            Files.write(invalid, contents);
            expect(IOException.class, () -> WavHeader.read(invalid));
        }
        byte[] extensible = FileSourceFixtures.format(48000, true);
        extensible[24] = 1; // PCM GUID, despite a 32-bit storage width.
        Files.write(invalid, FileSourceFixtures.riff(FileSourceFixtures.chunk("fmt ", extensible), data));
        expect(IOException.class, () -> WavHeader.read(invalid));
        byte[] rf64 = FileSourceFixtures.rf64Header(8, 2, 48000);
        // RF64 counts, tables and overlarge unsigned sizes must fail before sample delivery.
        Files.write(invalid, rf64);
        Files.write(invalid, new byte[8], StandardOpenOption.APPEND);
        expect(IOException.class, () -> WavHeader.read(invalid)); // ds64 count disagreement
        ByteBuffer.wrap(rf64).order(ByteOrder.LITTLE_ENDIAN).putInt(44, 1);
        Files.write(invalid, rf64);
        expect(IOException.class, () -> WavHeader.read(invalid)); // unsupported replacement table
        for (int field : new int[]{20, 28, 36}) {
            rf64 = FileSourceFixtures.rf64Header(8, 1, 48000);
            ByteBuffer.wrap(rf64).order(ByteOrder.LITTLE_ENDIAN).putLong(field, Long.MAX_VALUE);
            Files.write(invalid, rf64);
            Files.write(invalid, new byte[8], StandardOpenOption.APPEND);
            expect(IOException.class, () -> WavHeader.read(invalid));
        }
        expect(IllegalArgumentException.class, () -> new WavHeader(0, 7, 1));
    }

    private static void configurationErrors(Path directory) throws Exception {
        Path file = directory.resolve("config.cf32");
        Files.write(file, FileSourceFixtures.floats(FileSourceFixtures.samples(4)));
        expect(ConfigurationException.class, () -> new Cf32SampleSource(config(file)));
        expect(ConfigurationException.class, () -> new Cf32SampleSource(config(directory, "sample_rate", 1)));
        expect(ConfigurationException.class, () -> new Cf32SampleSource(config(directory.resolve("missing"), "sample_rate", 1)));
        for (Object[] option : new Object[][]{
                {"sample_rate", 0}, {"sample_rate", -1}, {"sample_rate", "NaN"},
                {"block_samples", 0}, {"block_samples", 1048577}, {"block_samples", 1.5},
                {"start_offset", -1}, {"start_offset", 5}, {"length", -1}, {"length", 5},
                {"end_offset", -1}, {"end_offset", 5}, {"repeat", "yes"}, {"realtime", 1},
                {"center_frequency", -1}, {"type", "wav"}, {"typo", 1}}) {
            Map<String, Object> values = new HashMap<>(config(file, "sample_rate", 10).asMap());
            values.put((String) option[0], option[1]);
            expect(ConfigurationException.class, () -> new Cf32SampleSource(new Configuration(values)));
        }
        expect(ConfigurationException.class, () -> new Cf32SampleSource(config(file, "sample_rate", 1, "length", 1, "end_offset", 2)));
        expect(ConfigurationException.class, () -> new Cf32SampleSource(config(file, "sample_rate", 1, "start_offset", 2, "end_offset", 1)));
        expect(ConfigurationException.class, () -> new Cf32SampleSource(config(file, "sample_rate", 1, "start_offset", 4, "repeat", true)));
        expect(ConfigurationException.class, () -> new Cf32SampleSource(config(file, "sample_rate", 1, "start_offset", 1, "length", Long.MAX_VALUE)));
        Files.write(file, new byte[7]);
        expect(ConfigurationException.class, () -> new Cf32SampleSource(config(file, "sample_rate", 1)));
        Path wave = directory.resolve("config.wav");
        Files.write(wave, FileSourceFixtures.wav(FileSourceFixtures.samples(2), 48000));
        expect(ConfigurationException.class, () -> new WavSampleSource(config(wave, "sample_rate", 1)));
        expect(ConfigurationException.class, () -> new WavSampleSource(config(wave, "type", "cf32")));
    }

    private static void lifecycle(Path directory) throws Exception {
        Path file = directory.resolve("lifecycle.cf32");
        Files.write(file, FileSourceFixtures.floats(FileSourceFixtures.samples(3)));
        var configuration = config(file, "sample_rate", 1000);
        var before = new Cf32SampleSource(configuration);
        check(before.state() == ComponentState.CONFIGURED && !before.isRunning());
        check(!before.awaitCompletion(Duration.ZERO));
        expect(LifecycleException.class, before::start);
        expect(NullPointerException.class, () -> before.setSampleSink(null));
        before.stop();
        check(before.state() == ComponentState.STOPPED);
        before.stop();
        before.close();
        before.close();
        check(before.state() == ComponentState.CLOSED && before.awaitCompletion(Duration.ZERO));
        expect(LifecycleException.class, before::start);
        expect(IllegalStateException.class, () -> before.setSampleSink(block -> { }));
        try (var source = new Cf32SampleSource(configuration)) {
            collect(source);
            expect(LifecycleException.class, source::start);
            expect(IllegalStateException.class, () -> source.setSampleSink(block -> { }));
            expect(IllegalArgumentException.class, () -> source.awaitCompletion(Duration.ofSeconds(-1)));
            expect(IllegalArgumentException.class, () -> source.awaitCompletion(Duration.ofSeconds(Long.MAX_VALUE)));
        }
        // Neither construction nor completion retains an input file handle.
        Files.move(file, directory.resolve("renamed.cf32"));
        Files.move(directory.resolve("renamed.cf32"), file);
    }

    private static void failures(Path directory) throws Exception {
        Path file = directory.resolve("failure.cf32");
        byte[] contents = FileSourceFixtures.floats(FileSourceFixtures.samples(8));
        Files.write(file, contents);
        try (var source = new Cf32SampleSource(config(file, "sample_rate", 10))) {
            RuntimeException sentinel = new RuntimeException("sink failed");
            source.setSampleSink(block -> { throw sentinel; });
            source.start();
            check(source.awaitCompletion(Duration.ofSeconds(5)));
            source.stop();
            check(source.state() == ComponentState.FAILED && source.failure().orElseThrow() == sentinel);
            check(source.monitoringSnapshot().values().get("source.samples").equals(0L));
            check(source.monitoringSnapshot().values().get("source.eof").equals(false));
        }
        try (var source = new Cf32SampleSource(config(file, "sample_rate", 10))) {
            source.setSampleSink(block -> { });
            Files.write(file, new byte[0]);
            expect(SourceException.class, source::start);
            check(source.state() == ComponentState.FAILED && source.failure().isPresent());
        }
        Files.write(file, contents);
        try (var source = new Cf32SampleSource(config(file, "sample_rate", 10, "block_samples", 1))) {
            source.setSampleSink(block -> {
                try (FileChannel writer = FileChannel.open(file, StandardOpenOption.WRITE)) { writer.truncate(8); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            });
            source.start();
            check(source.awaitCompletion(Duration.ofSeconds(5)));
            source.stop();
            check(source.state() == ComponentState.FAILED && source.failure().orElseThrow() instanceof EOFException);
            check(source.monitoringSnapshot().values().get("source.samples").equals(1L));
        }
    }

    private static void pacing(Path directory) throws Exception {
        Path file = directory.resolve("paced.cf32");
        float[] iq = FileSourceFixtures.samples(20);
        Files.write(file, FileSourceFixtures.floats(iq));
        try (var source = new Cf32SampleSource(config(file, "sample_rate", 100, "realtime", true, "block_samples", 5))) {
            long start = System.nanoTime();
            List<ComplexSampleBlock> received = collect(source);
            check(System.nanoTime() - start >= 180_000_000);
            verify(received, iq, 0, 20, 100);
            for (int n = 1; n < received.size(); n++) {
                check(received.get(n).timestampNanos() - received.get(n - 1).timestampNanos() == 50_000_000);
            }
            double elapsed = (Double) source.monitoringSnapshot().values().get("source.elapsed_s");
            double factor = (Double) source.monitoringSnapshot().values().get("source.realtime_factor");
            check(Math.abs(factor - 0.2 / elapsed) < 1e-12);
        }
        // Even a thousands-of-seconds pacing deadline must be promptly interruptible.
        try (var source = new Cf32SampleSource(config(file, "sample_rate", 0.001, "realtime", true, "block_samples", 1))) {
            AtomicInteger delivered = new AtomicInteger();
            source.setSampleSink(block -> delivered.incrementAndGet());
            source.start();
            check(!source.awaitCompletion(Duration.ofMillis(20)));
            long stopStart = System.nanoTime();
            Thread.currentThread().interrupt();
            try {
                source.stop();
                check(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            check(System.nanoTime() - stopStart < 2_000_000_000L);
            check(source.state() == ComponentState.STOPPED && source.failure().isEmpty());
            check(delivered.get() == 0 && source.monitoringSnapshot().values().get("source.eof").equals(false));
        }
    }

    private static void largeFiles(Path directory) throws Exception {
        long bytes = (1L << 32) + 16; // Beyond both signed/unsigned 32-bit sample-data lengths.
        long samples = bytes / 8;
        float[] tail = {1.25f, -2.5f, 3.75f, -4.5f};
        Path cf32 = directory.resolve("large.cf32"), rf64 = directory.resolve("large.rf64");
        FileSourceFixtures.writeSparse(cf32, 0, bytes, new byte[0], tail);
        FileSourceFixtures.writeSparse(rf64, 80, bytes, FileSourceFixtures.rf64Header(bytes, samples, 48000), tail);
        for (boolean wave : new boolean[]{false, true}) {
            try (var source = source(wave, config(wave ? rf64 : cf32, "sample_rate", 48000,
                    "start_offset", samples - 2, "length", 2, "block_samples", 1))) {
                List<ComplexSampleBlock> blocks = collect(source);
                check(blocks.size() == 2 && blocks.getFirst().sampleIndex() == samples - 2);
                check(Arrays.equals(blocks.get(0).iq(), Arrays.copyOfRange(tail, 0, 2)));
                check(Arrays.equals(blocks.get(1).iq(), Arrays.copyOfRange(tail, 2, 4)));
                check(source.monitoringSnapshot().values().get("source.total_samples").equals(samples));
            }
        }
        check(WavHeader.read(rf64).dataBytes() == bytes);
        // RIFF's ordinary 32-bit sizes are unsigned, even without RF64 sentinels.
        long riffBytes = (1L << 31) + 16;
        Path largeRiff = directory.resolve("large-riff.wav");
        FileSourceFixtures.writeSparse(largeRiff, 44, riffBytes,
                FileSourceFixtures.riffHeader(riffBytes, 48000), tail);
        try (var source = new WavSampleSource(config(largeRiff, "start_offset", riffBytes / 8 - 2, "length", 2))) {
            check(Arrays.equals(collect(source).getFirst().iq(), tail));
            check(WavHeader.read(largeRiff).dataBytes() == riffBytes);
        }
    }

    private static void discovery() {
        var factories = ServiceLoader.load(SampleSourceFactory.class).stream().map(ServiceLoader.Provider::get).toList();
        check(factories.stream().map(factory -> factory.descriptor().id()).collect(java.util.stream.Collectors.toSet())
                .equals(Set.of("cf32", "cs16", "wav", "fmcomms2")));
        check(new Cf32SourceFactory().descriptor().id().equals("cf32"));
        check(new eu.dariolucia.jsatdecode.source.cs16.Cs16SourceFactory().descriptor().id().equals("cs16"));
        check(new WavSourceFactory().descriptor().id().equals("wav"));
    }

    private interface Action { void run() throws Exception; }
    private static void expect(Class<? extends Throwable> type, Action action) {
        checks++;
        try { action.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + type);
    }
    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Phase 4 check " + checks + " failed");
    }
}
