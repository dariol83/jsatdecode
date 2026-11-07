package eu.dariolucia.jsatdecode.source.fmcomms2;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/* Setup-only strict parsing. The framework API carries one complex stream, so
   exactly one RF receiver is selected, never concatenated/interleaved RX1/RX2. */
record Fmcomms2Settings(String uri, String host, int port, long frequency, int sampleRate, int decimation,
                        int bandwidth,
                        int bufferSize, int blockSamples, int receiver, String gainMode, double gain, String rfPort,
                        boolean quadrature, boolean rfDc, boolean bbDc, String filterSource, byte[] filter,
                        int connectTimeout, int readTimeout, int serverTimeout, int kernelBuffers, String phy,
                        String adc, RecordingSettings recording) {
    static Fmcomms2Settings read(Configuration c) {
        Set<String> allowed = Set.of("type", "uri", "frequency", "sample_rate", "bandwidth", "buffer_size", "block_samples",
                "rx1_en", "rx2_en", "gain1", "gain2", "manual_gain1", "manual_gain2", "rf_port_select", "quadrature", "rfdc", "bbdc",
                "filter_source", "filter", "connect_timeout_ms", "read_timeout_ms", "server_timeout_ms", "kernel_buffers",
                "control_device", "stream_device", "fpga_decimation");
        for (String key : c.asMap().keySet())
            if (!allowed.contains(key) && !RecordingSettings.KEYS.contains(key))
                throw new ConfigurationException("Unknown FMComms2 setting: " + key);
        if (has(c, "type") && !c.getString("type").equals("fmcomms2"))
            throw new ConfigurationException("source.type must be fmcomms2");
        String uri = text(c, "uri", "ip:192.168.2.1");
        URI address;
        try {
            if (!uri.startsWith("ip:") || uri.substring(3).isBlank()) throw new IllegalArgumentException();
            address = URI.create("tcp://" + uri.substring(3));
            if (address.getHost() == null || address.getRawUserInfo() != null || address.getRawQuery() != null || address.getRawFragment() != null ||
                    (address.getRawPath() != null && !address.getRawPath().isEmpty()) || address.getPort() == 0 || address.getPort() > 65535)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("uri must be ip:host[:port], IPv6 in brackets", e);
        }
        boolean rx1 = bool(c, "rx1_en", true), rx2 = bool(c, "rx2_en", false);
        if (rx1 == rx2)
            throw new ConfigurationException("Enable exactly one of rx1_en and rx2_en; source API has one complex stream");
        for (String key : List.of("gain1", "gain2"))
            if (!Set.of("manual", "slow_attack", "fast_attack", "hybrid").contains(text(c, key, "slow_attack")))
                throw new ConfigurationException(key + " must be manual, slow_attack, fast_attack or hybrid");
        double gain1 = number(c, "manual_gain1", 64), gain2 = number(c, "manual_gain2", 64);
        if (gain1 < -10 || gain1 > 73 || gain2 < -10 || gain2 > 73)
            throw new ConfigurationException("manual_gain1/2 must be within [-10,73] dB; hardware further restricts gains");
        long frequency = has(c, "frequency") ? c.getLong("frequency") : 2400000000L;
        if (frequency < 70000000L || frequency > 6000000000L)
            throw new ConfigurationException("frequency must be in [70000000,6000000000] Hz");
        String filterSource = text(c, "filter_source", "Off");
        if (!Set.of("Off", "File", "Keep").contains(filterSource))
            throw new ConfigurationException("filter_source supports Off, File or Keep; Auto/Design require an AD9361 filter designer, not IIOD commands");
        int rate = integer(c, "sample_rate", 3000000, 520833, 61440000);
        int decimation = integer(c, "fpga_decimation", 1, 1, 16);
        if (!Set.of(1, 2, 4, 8, 16).contains(decimation))
            throw new ConfigurationException("fpga_decimation must be 1, 2, 4, 8 or 16");
        if (rate % decimation != 0)
            throw new ConfigurationException("sample_rate must be exactly divisible by fpga_decimation");
        if (filterSource.equals("Off") && rate < 2084000)
            throw new ConfigurationException("filter_source=Off requires sample_rate >= 2084000; use a suitable FIR file for lower rates");
        byte[] filter = null;
        try {
            if (filterSource.equals("File")) {
                Path path = Path.of(c.getString("filter"));
                if (Files.size(path) < 1 || Files.size(path) > 65536)
                    throw new ConfigurationException("FIR file must contain 1..65536 bytes");
                try (var input = Files.newInputStream(path)) {
                    filter = input.readNBytes(65537);
                }
                if (filter.length < 1 || filter.length > 65536)
                    throw new ConfigurationException("FIR file changed or exceeds size bound");
            } else if (has(c, "filter"))
                throw new ConfigurationException("filter is only valid with filter_source=File");
        } catch (IOException | InvalidPathException e) {
            throw new ConfigurationException("Cannot read FIR filter file", e);
        }
        String rfPort = text(c, "rf_port_select", "A_BALANCED");
        if (!Set.of("A_BALANCED", "B_BALANCED", "C_BALANCED", "A_N", "A_P", "B_N", "B_P", "C_N", "C_P", "TX_MONITOR1", "TX_MONITOR2", "TX_MONITOR1_2").contains(rfPort))
            throw new ConfigurationException("Invalid rf_port_select");
        int buffer = integer(c, "buffer_size", 65536, 1, 1048576), block = integer(c, "block_samples", 4096, 1, buffer);
        int serverTimeout = integer(c, "server_timeout_ms", 3000, 1, 60000), readTimeout = integer(c, "read_timeout_ms", 5000, 1, 120000);
        if (readTimeout <= serverTimeout)
            throw new ConfigurationException("read_timeout_ms must exceed server_timeout_ms");
        if (buffer * 1000.0 / (rate / decimation) >= serverTimeout)
            throw new ConfigurationException("server_timeout_ms must exceed buffer signal duration at the decimated output rate");
        return new Fmcomms2Settings(uri, address.getHost(), address.getPort() < 0 ? 30431 : address.getPort(), frequency, rate, decimation,
                integer(c, "bandwidth", 2000000, 200000, 52000000), buffer, block, rx1 ? 0 : 1,
                text(c, rx1 ? "gain1" : "gain2", "slow_attack"), rx1 ? gain1 : gain2, rfPort,
                bool(c, "quadrature", true), bool(c, "rfdc", true), bool(c, "bbdc", true), filterSource, filter,
                integer(c, "connect_timeout_ms", 5000, 1, 120000), readTimeout, serverTimeout,
                integer(c, "kernel_buffers", 4, 1, 64), text(c, "control_device", "ad9361-phy"), text(c, "stream_device", "cf-ad9361-lpc"),
                RecordingSettings.read(c, block));
    }

    private static boolean has(Configuration c, String key) {
        return c.asMap().containsKey(key);
    }

    private static String text(Configuration c, String key, String fallback) {
        return has(c, key) ? c.getString(key) : fallback;
    }

    private static boolean bool(Configuration c, String key, boolean fallback) {
        return has(c, key) ? c.getBoolean(key) : fallback;
    }

    private static double number(Configuration c, String key, double fallback) {
        return has(c, key) ? c.getDouble(key) : fallback;
    }

    private static int integer(Configuration c, String key, int fallback, int min, int max) {
        int value = has(c, key) ? c.getInt(key) : fallback;
        if (value < min || value > max) throw new ConfigurationException(key + " must be in [" + min + "," + max + "]");
        return value;
    }
}
