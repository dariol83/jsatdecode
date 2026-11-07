package eu.dariolucia.jsatdecode.source.file;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable file-source settings. Offsets/lengths use complex samples, not bytes
 * or seconds. Factory configuration is the unprefixed source section.
 *
 * @param file            readable regular recording file
 * @param blockSamples    bounded block capacity, 1 through 1048576
 * @param startOffset     inclusive nonnegative sample index
 * @param endOffset       exclusive sample index, or -1 when absent
 * @param length          selected sample count, or -1 when absent; mutually exclusive with endOffset
 * @param repeat          rewind the selected nonempty segment until stopped
 * @param realtime        pace delivery to the sample clock instead of processing unpaced
 * @param centerFrequency finite nonnegative metadata in Hz, zero if unspecified
 */
public record FileSourceSettings(Path file, int blockSamples, long startOffset, long endOffset,
                                 long length, boolean repeat, boolean realtime, double centerFrequency) {
    private static final Set<String> KEYS = Set.of("type", "file", "sample_rate", "block_samples",
            "start_offset", "end_offset", "length", "repeat", "realtime", "center_frequency");

    /**
     * Validates settings before a worker or retained channel is created.
     *
     * @param file            recording path
     * @param blockSamples    maximum block count
     * @param startOffset     inclusive index
     * @param endOffset       exclusive index or -1
     * @param length          count or -1
     * @param repeat          repeat segment
     * @param realtime        enable pacing
     * @param centerFrequency metadata in Hz
     * @throws ConfigurationException for unreadable paths, invalid bounds or conflicting limits
     */
    public FileSourceSettings {
        file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new ConfigurationException("source.file must be a readable regular file: " + file);
        }
        if (blockSamples < 1 || blockSamples > 1048576)
            throw new ConfigurationException("source.block_samples must be in [1,1048576]");
        if (startOffset < 0) throw new ConfigurationException("source.start_offset must be nonnegative");
        if (endOffset < -1 || length < -1 || (endOffset != -1 && length != -1)) {
            throw new ConfigurationException("source.end_offset and source.length are mutually exclusive nonnegative limits");
        }
        if (!Double.isFinite(centerFrequency) || centerFrequency < 0) {
            throw new ConfigurationException("source.center_frequency must be finite and nonnegative");
        }
    }

    /**
     * Parses an unprefixed source section; unknown keys fail to expose typos.
     *
     * @param configuration non-null component settings
     * @return validated immutable settings
     * @throws ConfigurationException for malformed settings
     */
    public static FileSourceSettings read(Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        for (String key : configuration.asMap().keySet()) {
            if (!KEYS.contains(key)) throw new ConfigurationException("Unknown source setting: " + key);
        }
        try {
            return new FileSourceSettings(Path.of(configuration.getString("file")),
                    has(configuration, "block_samples") ? configuration.getInt("block_samples") : 4096,
                    has(configuration, "start_offset") ? nonnegative(configuration, "start_offset") : 0,
                    has(configuration, "end_offset") ? nonnegative(configuration, "end_offset") : -1,
                    has(configuration, "length") ? nonnegative(configuration, "length") : -1,
                    has(configuration, "repeat") && configuration.getBoolean("repeat"),
                    has(configuration, "realtime") && configuration.getBoolean("realtime"),
                    has(configuration, "center_frequency") ? configuration.getDouble("center_frequency") : 0);
        } catch (java.nio.file.InvalidPathException e) {
            throw new ConfigurationException("Invalid source.file path", e);
        }
    }

    /**
     * Resolves the selected segment against a known recording length.
     *
     * @param totalSamples nonnegative recording sample count
     * @return exclusive selected end index
     * @throws ConfigurationException for out-of-file ranges or an empty repeated segment
     */
    public long endSample(long totalSamples) {
        long end;
        try {
            end = length >= 0 ? Math.addExact(startOffset, length) : endOffset >= 0 ? endOffset : totalSamples;
        } catch (ArithmeticException e) {
            throw new ConfigurationException("source.length overflows the selected range", e);
        }
        if (totalSamples < 0 || startOffset > totalSamples || end < startOffset || end > totalSamples) {
            throw new ConfigurationException("Source sample range is outside the recording");
        }
        if (repeat && end == startOffset) throw new ConfigurationException("Cannot repeat an empty source segment");
        return end;
    }

    private static boolean has(Configuration configuration, String key) {
        return configuration.asMap().containsKey(key);
    }

    private static long nonnegative(Configuration configuration, String key) {
        long value = configuration.getLong(key);
        if (value < 0) throw new ConfigurationException("source." + key + " must be nonnegative");
        return value;
    }
}
