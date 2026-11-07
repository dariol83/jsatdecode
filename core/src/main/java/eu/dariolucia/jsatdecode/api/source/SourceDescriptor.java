package eu.dariolucia.jsatdecode.api.source;

import eu.dariolucia.jsatdecode.api.config.ImmutableValues;

/**
 * Immutable registration metadata; equality includes every field.
 *
 * @param id          nonblank registered ID
 * @param name        nonblank display name
 * @param version     nonblank implementation version
 * @param description nonblank description
 */
public record SourceDescriptor(String id, String name, String version, String description) {
    /**
     * Validates metadata.
     *
     * @param id          registered ID
     * @param name        display name
     * @param version     implementation version
     * @param description description
     */
    public SourceDescriptor {
        ImmutableValues.text(id, "id");
        ImmutableValues.text(name, "name");
        ImmutableValues.text(version, "version");
        ImmutableValues.text(description, "description");
    }
}