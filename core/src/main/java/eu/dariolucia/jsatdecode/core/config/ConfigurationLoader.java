package eu.dariolucia.jsatdecode.core.config;

import eu.dariolucia.jsatdecode.api.config.Configuration;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * UTF-8 Java properties loading on management threads. Paths remain relative to the working directory.
 */
public final class ConfigurationLoader {
    private ConfigurationLoader() {
    }

    /**
     * Merges defaults, file, overrides and dedicated switches, in that order.
     * An absent application.configuration_name defaults to the file basename
     * without its final extension, or session when no file was supplied.
     *
     * @param defaults  lowest priority settings
     * @param file      optional properties file
     * @param overrides explicit key/value overrides
     * @param switches  highest priority dedicated options
     * @return immutable merged configuration
     * @throws IOException if the file cannot be read
     */
    public static Configuration load(Map<String, ?> defaults, Path file, Map<String, ?> overrides,
                                     Map<String, ?> switches) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>(defaults);
        if (file != null) {
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            properties.forEach((k, v) -> result.put((String) k, v));
        }
        result.putAll(overrides);
        result.putAll(switches);
        String name = file == null ? "session" : file.getFileName().toString();
        int extension = name.lastIndexOf('.');
        if (file != null && extension > 0) name = name.substring(0, extension);
        result.putIfAbsent("application.configuration_name", name);
        return new Configuration(result);
    }
}
