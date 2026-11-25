package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.source.DiagnosticSampleSourceFactory;
import eu.dariolucia.jsatdecode.api.source.ManagedSampleSource;
import eu.dariolucia.jsatdecode.api.source.SampleSource;
import eu.dariolucia.jsatdecode.api.source.SampleSourceFactory;
import eu.dariolucia.jsatdecode.api.source.SourceReadDiagnostics;
import eu.dariolucia.jsatdecode.api.source.SourcePropertyDescriptor;
import eu.dariolucia.jsatdecode.core.registry.FactoryRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Objects;

/** Source discovery and property configuration, without concrete provider dependencies. */
final class InspectorSources {
    private InspectorSources() { }

    static Map<String, SampleSourceFactory> available() {
        return FactoryRegistry.discover(SampleSourceFactory.class, f -> f.descriptor().id()).entries();
    }

    static SampleSourceFactory factory(String id) {
        return new FactoryRegistry<>(available().values(), f -> f.descriptor().id()).require(id);
    }

    static InspectorSourceSettings selection(InspectorSettings settings) {
        if (settings.source() != null) return settings.source();
        Map<String, String> values = new LinkedHashMap<>();
        InspectorReceiver.sourceConfiguration(settings).asMap().forEach((key, value) -> values.put(key, value.toString()));
        int factor = InspectorReceiver.acquisitionRate(settings.sampleRate()) / settings.sampleRate() * settings.decimation();
        return new InspectorSourceSettings("fmcomms2", values, factor);
    }

    static Configuration configuration(SampleSourceFactory factory, Configuration values) {
        List<SourcePropertyDescriptor> properties = factory.configurationProperties();
        // Legacy providers may not expose metadata. Their own validation remains sufficient.
        if (properties.isEmpty()) return values;
        Map<String, Object> parsed = new LinkedHashMap<>(values.asMap());
        for (SourcePropertyDescriptor property : properties) {
            Object value = parsed.get(property.name());
            if (value != null) parsed.put(property.name(), property.parse(value.toString()));
            else if (property.required() && property.defaultValue() == null)
                throw new ConfigurationException("Missing configuration: " + property.name());
        }
        return new Configuration(parsed);
    }

    static Configuration runtimeChanges(SampleSourceFactory factory, Configuration previous, Configuration next) {
        Map<String, SourcePropertyDescriptor> properties = new LinkedHashMap<>();
        factory.configurationProperties().forEach(property -> properties.put(property.name(), property));
        Map<String, Object> changes = new LinkedHashMap<>();
        var keys = new HashSet<>(previous.asMap().keySet());
        keys.addAll(next.asMap().keySet());
        for (String key : keys) {
            SourcePropertyDescriptor property = properties.get(key);
            Object before = previous.asMap().get(key), after = next.asMap().get(key);
            if (property != null) {
                if (before == null) before = property.defaultValue();
                if (after == null) after = property.defaultValue();
                if (before != null) before = property.parse(before.toString());
                if (after != null) after = property.parse(after.toString());
            }
            if (Objects.equals(before, after)) continue;
            if (property == null || !property.runtimeUpdatable() || after == null)
                throw new ConfigurationException("Setting requires reconnecting: " + key);
            changes.put(key, after);
        }
        return new Configuration(changes);
    }

    static ManagedSampleSource receiver(Configuration configuration) {
        SampleSource source = receiver("fmcomms2", configuration);
        if (source instanceof ManagedSampleSource managed) return managed;
        source.close();
        throw new IllegalStateException("FMComms2 source does not support managed acquisition");
    }

    static SampleSource receiver(String id, Configuration configuration) {
        SampleSourceFactory factory = factory(id);
        return factory.create(configuration(factory, configuration));
    }

    static DiagnosticSampleSourceFactory.Capture diagnostic(Configuration configuration, int capacity,
                                                             SourceReadDiagnostics.ReadListener listener) {
        SampleSourceFactory sourceFactory = factory("fmcomms2");
        if (sourceFactory instanceof DiagnosticSampleSourceFactory diagnostic) {
            return diagnostic.createDiagnostic(configuration, capacity, listener);
        }
        throw new IllegalStateException("FMComms2 source does not support read diagnostics");
    }
}
