package eu.dariolucia.jsatdecode.core.registry;

import eu.dariolucia.jsatdecode.api.config.ConfigurationException;

import java.util.*;
import java.util.function.Function;

/**
 * Immutable setup-time registry. Discovery never occurs on sample workers.
 *
 * @param <T> factory contract
 */
public final class FactoryRegistry<T> {
    private final Map<String, T> factories;

    /**
     * Validates all IDs, rejecting duplicates even for the same instance.
     *
     * @param providers  discovered or explicitly supplied factories
     * @param identifier metadata ID accessor
     */
    public FactoryRegistry(Iterable<T> providers, Function<T, String> identifier) {
        Map<String, T> entries = new LinkedHashMap<>();
        for (T provider : providers) {
            String id = identifier.apply(Objects.requireNonNull(provider));
            if (id == null || id.isBlank()) throw new ConfigurationException("Blank factory ID");
            if (entries.putIfAbsent(id, provider) != null)
                throw new ConfigurationException("Duplicate factory ID: " + id);
        }
        factories = Collections.unmodifiableMap(entries);
    }

    /**
     * Discovers providers with the current context class loader.
     *
     * @param <T>        factory contract
     * @param type       factory interface
     * @param identifier ID accessor
     * @return fixed registry
     */
    public static <T> FactoryRegistry<T> discover(Class<T> type, Function<T, String> identifier) {
        return new FactoryRegistry<>(ServiceLoader.load(type), identifier);
    }

    /**
     * Resolves an ID or fails before execution.
     *
     * @param id configured ID
     * @return registered factory
     */
    public T require(String id) {
        T result = factories.get(id);
        if (result == null)
            throw new ConfigurationException("Unknown factory ID: " + id + "; available: " + factories.keySet());
        return result;
    }

    /**
     * Returns immutable providers in discovery order.
     *
     * @return provider map
     */
    public Map<String, T> entries() {
        return factories;
    }
}
