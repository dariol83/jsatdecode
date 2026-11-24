package eu.dariolucia.jsatdecode.source.wav;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.source.file.AbstractFileSampleSource;
import eu.dariolucia.jsatdecode.source.file.FileSourceSettings;

import java.io.IOException;

/**
 * Deterministic IEEE float32 stereo IQ WAV/RF64 source using the header sample rate.
 */
public final class WavSampleSource extends AbstractFileSampleSource {
    /**
     * Validates header, file and unprefixed source settings without retaining a channel.
     * An optional sample_rate must match the header; resampling is not performed.
     *
     * @param configuration non-null source section
     * @throws ConfigurationException for invalid configuration or unsupported/malformed WAV
     */
    public WavSampleSource(Configuration configuration) {
        this(configuration, FileSourceSettings.read(configuration));
    }

    private WavSampleSource(Configuration configuration, FileSourceSettings settings) {
        this(configuration, settings, header(settings));
    }

    private WavSampleSource(Configuration configuration, FileSourceSettings settings, WavHeader header) {
        super(WavSourceFactory.DESCRIPTOR, settings, header.dataOffset(), header.dataBytes(), rate(configuration, header));
    }

    private static WavHeader header(FileSourceSettings settings) {
        try {
            WavHeader hdr = WavHeader.read(settings.file());
            // Check if file is larger than header claims (handles >4GB RIFF/WAVE files)
            long fileSize = java.nio.file.Files.size(settings.file());
            long headerEnd = Math.addExact(hdr.dataOffset(), hdr.dataBytes());
            if (fileSize > headerEnd) {
                // File extends beyond header's data chunk size
                long availableBytes = Math.subtractExact(fileSize, hdr.dataOffset());
                // Only adjust if the available bytes are a multiple of 8 (sample alignment)
                if (availableBytes % 8 == 0) {
                    System.err.println("WARNING: WAV file size (" + fileSize + " bytes) exceeds header data chunk size (" + 
                                     hdr.dataBytes() + " bytes) at offset " + hdr.dataOffset() + 
                                     ". Ignoring header length and using actual file size.");
                    return new WavHeader(hdr.dataOffset(), availableBytes, hdr.sampleRate());
                }
            }
            return hdr;
        } catch (IOException | ArithmeticException e) {
            throw new ConfigurationException("Invalid WAV source.file: " + e.getMessage(), e);
        }
    }

    private static double rate(Configuration configuration, WavHeader header) {
        if (configuration.asMap().containsKey("type") && !configuration.getString("type").equals("wav")) {
            throw new ConfigurationException("WAV source.type must be wav");
        }
        if (configuration.asMap().containsKey("sample_rate") && configuration.getDouble("sample_rate") != header.sampleRate()) {
            throw new ConfigurationException("source.sample_rate must match the WAV header");
        }
        return header.sampleRate();
    }
}
