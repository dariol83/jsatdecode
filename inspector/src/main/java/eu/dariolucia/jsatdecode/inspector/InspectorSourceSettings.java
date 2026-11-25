package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ImmutableValues;

import java.util.Map;

/** Application-local source selection; property names and values belong to the provider. */
record InspectorSourceSettings(String id, Map<String, String> properties, int softwareDecimation) {
    InspectorSourceSettings {
        ImmutableValues.text(id, "source ID");
        properties = Map.copyOf(properties);
        if (softwareDecimation < 1 || softwareDecimation > 64 || Integer.bitCount(softwareDecimation) != 1)
            throw new IllegalArgumentException("Software decimation must be a power of two in [1,64]");
    }

    Configuration configuration() {
        return new Configuration(properties);
    }
}
