package eu.dariolucia.jsatdecode.source.cf32;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.source.file.AbstractFileSampleSource;
import eu.dariolucia.jsatdecode.source.file.FileSourceSettings;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Deterministic interleaved little-endian float32 IQ recording source.
 */
public final class Cf32SampleSource extends AbstractFileSampleSource {
    /**
     * Validates an unprefixed source section, requiring file and positive sample_rate.
     * No worker or channel is retained until start. See file-sources.md for all keys.
     *
     * @param configuration non-null source-specific settings
     * @throws ConfigurationException for malformed configuration, file size or sample rate
     */
    public Cf32SampleSource(Configuration configuration) {
        this(configuration, FileSourceSettings.read(configuration));
    }

    private Cf32SampleSource(Configuration configuration, FileSourceSettings settings) {
        super(Cf32SourceFactory.DESCRIPTOR, settings, 0, size(settings), rate(configuration));
    }

    private static long size(FileSourceSettings settings) {
        try {
            return Files.size(settings.file());
        } catch (IOException e) {
            throw new ConfigurationException("Cannot inspect CF32 source.file", e);
        }
    }

    private static double rate(Configuration configuration) {
        if (configuration.asMap().containsKey("type") && !configuration.getString("type").equals("cf32")) {
            throw new ConfigurationException("CF32 source.type must be cf32");
        }
        return configuration.getDouble("sample_rate");
    }
}
