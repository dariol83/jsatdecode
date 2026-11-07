package eu.dariolucia.jsatdecode.elektrol;

import static eu.dariolucia.jsatdecode.decoder.base.config.DecoderConfiguration.bool;
import static eu.dariolucia.jsatdecode.decoder.base.config.DecoderConfiguration.integer;
import static eu.dariolucia.jsatdecode.decoder.base.config.DecoderConfiguration.knownKeys;
import static eu.dariolucia.jsatdecode.decoder.base.config.DecoderConfiguration.number;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;

import java.util.Set;

/** Immutable setup configuration; the validated GRC fixes all modulation and loop parameters. */
record ElektroLGgakSettings(
        double sampleRateHz,
        boolean dcblocker,
        int dcBlockerLength,
        int syncSearchErrors,
        int syncLockedErrors,
        int tapCapacity) {
    static final String TYPE = "elektrol_ggak";
    static final double CHANNEL_RATE = 250_000;
    static final double BASE_RATE = 50_000;
    static final double SYMBOL_RATE = 5_000;

    ElektroLGgakSettings {
        if (!Double.isFinite(sampleRateHz)
                || sampleRateHz < CHANNEL_RATE
                || sampleRateHz > 16_000_000
                || sampleRateHz % CHANNEL_RATE != 0)
            throw new ConfigurationException(
                    "sample_rate_hz must be an integer multiple of 250000 in [250000,16000000]");
        if (dcBlockerLength < 2 || dcBlockerLength > 65536)
            throw new ConfigurationException("dcblocker_length must be in [2,65536]");
        if (syncSearchErrors < 0 || syncSearchErrors > 15
                || syncLockedErrors < syncSearchErrors || syncLockedErrors > 15)
            throw new ConfigurationException(
                    "ASM thresholds require 0 <= sync_search_errors <= sync_locked_errors <= 15");
        if (tapCapacity < 1 || tapCapacity > 1048576)
            throw new ConfigurationException("tap_capacity must be in [1,1048576]");
    }

    static ElektroLGgakSettings read(Configuration configuration) {
        knownKeys(configuration,
                Set.of("type", "sample_rate_hz", "dcblocker", "dcblocker_length",
                        "sync_search_errors", "sync_locked_errors", "tap_capacity"),
                "Unknown Elektro-L GGAK setting: ");
        if (configuration.asMap().containsKey("type")
                && !TYPE.equals(configuration.getString("type")))
            throw new ConfigurationException("decoder.type must be " + TYPE);
        return new ElektroLGgakSettings(
                number(configuration, "sample_rate_hz", 1_000_000, CHANNEL_RATE, 16_000_000),
                bool(configuration, "dcblocker", false),
                integer(configuration, "dcblocker_length", 16384, 2, 65536),
                integer(configuration, "sync_search_errors", 2, 0, 15),
                integer(configuration, "sync_locked_errors", 4, 0, 15),
                integer(configuration, "tap_capacity", 16384, 1, 1048576));
    }

    double rate() { return sampleRateHz; }
    boolean dcBlockEnabled() { return dcblocker; }
    int channelDecimation() { return (int) (sampleRateHz / CHANNEL_RATE); }
    double channelRate() { return CHANNEL_RATE; }
    double baseRate() { return BASE_RATE; }
    double bitRate() { return SYMBOL_RATE; }
}
