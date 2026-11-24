package eu.dariolucia.jsatdecode.source.fmcomms2;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;

import java.math.BigDecimal;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/* Setup-only recording settings. No directory/file/thread/buffer is created here. */
record RecordingSettings(boolean enabled, Path folder, String name, long queueBytes, int slots,
                         int writeBytes, Policy policy, int blockSamples) {
    enum Policy { STOP_RECORDING, FAIL_SESSION }

    static final Set<String> KEYS = Set.of("recording.enabled", "recording.folder", "recording.name",
            "recording.queue_mib", "recording.write_buffer_mib", "recording.failure_policy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss").withZone(ZoneOffset.UTC);

    static RecordingSettings read(Configuration c, int blockSamples) {
        boolean enabled = c.asMap().containsKey("recording.enabled") && c.getBoolean("recording.enabled");
        long queueBytes = mib(c, "recording.queue_mib", 64, 1024);
        int writeBytes = (int) mib(c, "recording.write_buffer_mib", 4, 64);
        int slots = (int) Math.min(65536, queueBytes / (blockSamples * 8L));
        if (slots == 0) throw new ConfigurationException("recording.queue_mib must hold at least one block_samples block");
        Policy policy;
        try {
            policy = Policy.valueOf(c.asMap().containsKey("recording.failure_policy")
                    ? c.getString("recording.failure_policy") : "STOP_RECORDING");
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("recording.failure_policy must be STOP_RECORDING or FAIL_SESSION", e);
        }
        String name = safeName(c.asMap().containsKey("recording.name") ? c.getString("recording.name") : "session");
        Path folder = null;
        if (enabled || c.asMap().containsKey("recording.folder")) {
            String value = c.asMap().containsKey("recording.folder") ? c.getString("recording.folder") : System.getProperty("user.home");
            if (value == null || value.isBlank())
                throw new ConfigurationException("recording.folder is blank or user.home is unavailable");
            try {
                folder = Path.of(value).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                throw new ConfigurationException("Invalid recording.folder", e);
            }
        }
        return new RecordingSettings(enabled, folder, name, queueBytes, slots, writeBytes, policy, blockSamples);
    }

    private static long mib(Configuration c, String key, int fallback, int maximum) {
        int value = c.asMap().containsKey(key) ? c.getInt(key) : fallback;
        if (value < 1 || value > maximum) throw new ConfigurationException(key + " must be in [1," + maximum + "] MiB");
        return value * 1048576L;
    }

    private static String safeName(String value) {
        if (value.isBlank() || value.length() > 120)
            throw new ConfigurationException("recording.name must contain 1..120 characters");
        // Portable single component; replace separators/control characters before resolving.
        String safe = value.replaceAll("[\\x00-\\x1f\\x7f<>:\"/\\\\|?*]", "_").strip().replaceAll("[. ]+$", "");
        if (safe.isBlank()) throw new ConfigurationException("recording.name has no usable filename characters");
        return safe;
    }

    Path file(long center, int rate, double gain, Instant started) {
        return folder.resolve(name + "_" + center + "_" + rate + "sps_g" +
                BigDecimal.valueOf(gain).stripTrailingZeros().toPlainString() + "_" + TIME.format(started) + ".cf32");
    }
}
