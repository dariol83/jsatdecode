package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.source.*;
import eu.dariolucia.jsatdecode.ui.swing.SpectrumFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class SourceConfigurationTest {
    @TempDir Path directory;

    @Test
    void metadataIsOptionalImmutableAndUsesExistingConfiguration() {
        SampleSourceFactory legacy = new SampleSourceFactory() {
            @Override public SourceDescriptor descriptor() { return new SourceDescriptor("legacy", "Legacy", "1", "Legacy source"); }
            @Override public SampleSource create(Configuration c) { throw new UnsupportedOperationException(); }
        };
        assertTrue(legacy.configurationProperties().isEmpty());
        var factories = InspectorSources.available();
        assertTrue(factories.keySet().containsAll(List.of("fmcomms2", "cf32", "cs16", "wav")));
        for (SampleSourceFactory factory : factories.values()) {
            var descriptors = factory.configurationProperties();
            assertEquals(descriptors.size(), descriptors.stream().map(SourcePropertyDescriptor::name).distinct().count());
            assertThrows(UnsupportedOperationException.class, () -> descriptors.clear());
            for (SourcePropertyDescriptor descriptor : descriptors) {
                if (descriptor.defaultValue() != null) assertDoesNotThrow(() -> descriptor.parse(descriptor.defaultValue()));
                assertThrows(UnsupportedOperationException.class, () -> descriptor.allowedValues().add("bad"));
            }
            if (!factory.descriptor().id().equals("fmcomms2"))
                assertTrue(descriptors.stream().noneMatch(SourcePropertyDescriptor::runtimeUpdatable));
        }
        assertTrue(property("cf32", "file").required());
        assertTrue(property("cf32", "sample_rate").required());
        assertFalse(property("wav", "sample_rate").required());
        assertNull(property("wav", "sample_rate").defaultValue());
        assertEquals("65536", property("fmcomms2", "buffer_size").defaultValue());
        assertEquals("Hz", property("fmcomms2", "frequency").unit());
        assertEquals("dB", property("fmcomms2", "manual_gain1").unit());
        assertEquals("samples", property("cf32", "block_samples").unit());
        assertEquals("slow_attack", property("fmcomms2", "gain1").defaultValue());
        assertTrue(property("fmcomms2", "frequency").runtimeUpdatable());
        assertFalse(property("fmcomms2", "sample_rate").runtimeUpdatable());
        assertThrows(ConfigurationException.class, () -> property("fmcomms2", "frequency").parse("69999999"));
        assertThrows(ConfigurationException.class, () -> property("fmcomms2", "frequency").parse("137100000.5"));
        assertThrows(ConfigurationException.class, () -> property("fmcomms2", "gain1").parse("unsupported"));
        assertThrows(ConfigurationException.class, () -> property("cf32", "sample_rate").parse("NaN"));
        assertThrows(ConfigurationException.class, () -> property("cf32", "sample_rate").parse("0"));
    }

    @Test
    void runtimeRequestsContainOnlyChangedDynamicKeysAndCanRestoreDefaults() {
        var factory = InspectorSources.factory("fmcomms2");
        Configuration previous = new Configuration(Map.of("frequency", 137100000L, "manual_gain1", 40, "uri", "ip:localhost"));
        Configuration equivalent = new Configuration(Map.of("frequency", "137100000", "manual_gain1", "40.0", "uri", "ip:localhost"));
        assertTrue(InspectorSources.runtimeChanges(factory, previous, equivalent).asMap().isEmpty());
        Configuration next = new Configuration(Map.of("frequency", "145000000", "uri", "ip:localhost"));
        assertEquals(Map.of("frequency", 145000000L, "manual_gain1", 64.0),
                InspectorSources.runtimeChanges(factory, previous, next).asMap());
        assertThrows(ConfigurationException.class, () -> InspectorSources.runtimeChanges(factory, previous,
                new Configuration(Map.of("frequency", 145000000L, "uri", "ip:other-host"))));
        assertThrows(ConfigurationException.class, () -> InspectorSources.runtimeChanges(factory, previous,
                new Configuration(Map.of("frequency", 1L, "uri", "ip:localhost"))));
    }

    @Test
    void selectionAndProviderPropertiesRoundTripAlongsideLegacyPreferences() throws Exception {
        Path file = directory.resolve("settings.properties");
        Files.writeString(file, "ip=localhost:30431\ngain=55\nsample_rate=500000\ndecimation=8\nrx_buffer_bytes=65536\n");
        InspectorPreferences preferences = new InspectorPreferences(file);
        InspectorSettings legacy = preferences.load();
        assertNull(legacy.source());
        assertEquals("fmcomms2", legacy.sourceId());
        assertEquals(62500, legacy.effectiveSampleRate());
        var selection = InspectorSources.selection(legacy);
        assertEquals(64, selection.softwareDecimation());
        assertEquals("4000000", selection.properties().get("sample_rate"));
        assertEquals("55", selection.properties().get("manual_gain1"));
        var selected = new InspectorSourceSettings("cf32", Map.of("file", directory.resolve("IQ space è.cf32").toString(),
                "sample_rate", "48000.5", "realtime", "true", "center_frequency", "0"), 2);
        InspectorSettings settings = legacy.withSource(selected);
        assertEquals(24000.25, settings.effectiveSampleRate());
        assertEquals(0, settings.centerFrequency());
        preferences.save(settings);
        assertEquals(settings, preferences.load());
        preferences.save(legacy);
        assertEquals(legacy, preferences.load());
        Files.writeString(file, "source.id=cf32\nsource.property.sample_rate=invalid\n");
        assertEquals(InspectorSettings.defaults(), preferences.load(), "Corrupt generic metadata cannot prevent startup");
    }

    @Test
    void analysisUsesSourceReadbackAndInfersWavRateFromDeliveredBlocks() throws Exception {
        InspectorSettings settings = InspectorSettings.defaults().withSource(new InspectorSourceSettings("wav", Map.of(), 1));
        try (InspectorAnalysis analysis = new InspectorAnalysis(settings)) {
            analysis.sourceMetadata(48000, 145000001);
            analysis.start();
            float[] iq = new float[4096 * 2];
            java.util.Arrays.fill(iq, 0.25f);
            analysis.accept(new ComplexSampleBlock(iq, 4096, 0, 123456789, 48000));
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (analysis.latest().state() != SpectrumFrame.State.READY && System.nanoTime() < deadline) Thread.sleep(2);
            assertEquals(SpectrumFrame.State.READY, analysis.latest().state());
            assertEquals(48000, analysis.latest().sampleRate());
            assertEquals(145000001, analysis.latest().centerHz());
        }
    }

    @Test
    void inspectorReplaysAllFileProvidersAndUsesTheirSampleRate() throws Exception {
        for (String id : List.of("cf32", "cs16", "wav")) {
            Path file = recording(id);
            Map<String, String> values = new HashMap<>(Map.of("file", file.toString(), "block_samples", "7"));
            if (!id.equals("wav")) values.put("sample_rate", "48000.5");
            InspectorSettings settings = InspectorSettings.defaults().withSource(new InspectorSourceSettings(id, values, 1));
            List<ComplexSampleBlock> blocks = new CopyOnWriteArrayList<>();
            try (InspectorReceiver receiver = new InspectorReceiver(settings, blocks::add)) {
                assertEquals(id.equals("wav") ? 48000 : 48000.5, receiver.sampleRate());
                receiver.start();
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (receiver.running() && System.nanoTime() < deadline) Thread.sleep(2);
                assertFalse(receiver.running());
                assertNull(receiver.failure());
                assertEquals(32, blocks.stream().mapToInt(ComplexSampleBlock::complexSamples).sum());
                assertEquals(0.25f, blocks.getFirst().i(0));
                assertEquals(-0.5f, blocks.getFirst().q(0));
                assertEquals(id.equals("wav") ? 48000 : 48000.5, blocks.getFirst().sampleRate());
            }
        }
    }

    @Test
    void fileAcquisitionCanRecordWithAnUnknownCenterAndAFractionalRate() throws Exception {
        Path input = recording("cf32");
        var selected = new InspectorSourceSettings("cf32", Map.of("file", input.toString(), "sample_rate", "48000.5",
                "repeat", "true", "realtime", "true", "block_samples", "7", "start_offset", "3", "length", "20"), 1);
        var delivered = new java.util.concurrent.atomic.AtomicInteger();
        try (InspectorReceiver receiver = new InspectorReceiver(InspectorSettings.defaults().withSource(selected), block -> delivered.incrementAndGet())) {
            receiver.start();
            Path recording = receiver.startRecording(directory.resolve("recordings"));
            int initial = delivered.get();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (delivered.get() < initial + 32 && System.nanoTime() < deadline) Thread.sleep(2);
            assertTrue(delivered.get() >= initial + 32, () -> "Repeating playback failed: " + receiver.failure());
            receiver.stopRecording();
            assertTrue(receiver.running());
            assertNull(receiver.recordingFailure());
            assertTrue(recording.getFileName().toString().startsWith("0_48000.5_0_"));
            byte[] bytes = Files.readAllBytes(recording);
            assertTrue(bytes.length > 0);
            assertEquals(0, bytes.length % 8);
            ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            while (data.hasRemaining()) { assertEquals(0.25f, data.getFloat()); assertEquals(-0.5f, data.getFloat()); }
        }
    }

    @Test
    void descriptorControlsRemainEditableOnlyForRuntimeProperties() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var factory = InspectorSources.factory("fmcomms2");
            var selection = InspectorSources.selection(InspectorSettings.defaults());
            SourceConfigurationPanel panel = new SourceConfigurationPanel(factory.configurationProperties(), selection.properties());
            assertEquals(selection.properties(), panel.values(), "Editing must not materialize absent defaults");
            panel.updateEnabled(true, false);
            assertTrue(named(panel, "source.frequency").isEnabled());
            assertTrue(named(panel, "source.manual_gain1").isEnabled());
            assertFalse(named(panel, "source.uri").isEnabled());
            assertFalse(named(panel, "source.sample_rate").isEnabled());
            ((JTextField) named(panel, "source.frequency")).setText("145000000");
            assertEquals("145000000", panel.values().get("frequency"));
            panel.updateEnabled(true, true);
            assertFalse(named(panel, "source.frequency").isEnabled());
            panel.updateEnabled(false, false);
            assertTrue(named(panel, "source.uri").isEnabled());
            SourceConfigurationPanel filePanel = new SourceConfigurationPanel(InspectorSources.factory("cf32").configurationProperties(), Map.of());
            assertNull(find(filePanel, "source.uri"));
            assertNotNull(named(filePanel, "source.file"));
            filePanel.updateEnabled(true, false);
            assertFalse(named(filePanel, "source.file").isEnabled());
        });
    }

    private Path recording(String id) throws Exception {
        ByteBuffer data = ByteBuffer.allocate(32 * (id.equals("cs16") ? 4 : 8)).order(ByteOrder.LITTLE_ENDIAN);
        while (data.hasRemaining()) {
            if (id.equals("cs16")) data.putShort((short) 8192).putShort((short) -16384);
            else data.putFloat(0.25f).putFloat(-0.5f);
        }
        Path file = directory.resolve("input." + id);
        if (id.equals("wav")) {
            ByteBuffer wav = ByteBuffer.allocate(44 + data.capacity()).order(ByteOrder.LITTLE_ENDIAN);
            wav.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + data.capacity());
            wav.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short) 3).putShort((short) 2);
            wav.putInt(48000).putInt(48000 * 8).putShort((short) 8).putShort((short) 32);
            wav.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(data.capacity()).put(data.array());
            Files.write(file, wav.array());
        } else Files.write(file, data.array());
        return file;
    }

    private static SourcePropertyDescriptor property(String source, String key) {
        return InspectorSources.factory(source).configurationProperties().stream().filter(p -> p.name().equals(key)).findFirst().orElseThrow();
    }

    private static JComponent named(Container root, String name) {
        return java.util.Objects.requireNonNull(find(root, name), name);
    }

    private static JComponent find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JComponent component && name.equals(component.getName())) return component;
            if (child instanceof Container container) {
                JComponent found = find(container, name);
                if (found != null) return found;
            }
        }
        return null;
    }
}
