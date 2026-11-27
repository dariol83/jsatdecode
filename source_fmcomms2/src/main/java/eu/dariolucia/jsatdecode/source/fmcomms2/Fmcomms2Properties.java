package eu.dariolucia.jsatdecode.source.fmcomms2;

import eu.dariolucia.jsatdecode.api.source.SourcePropertyDescriptor;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static eu.dariolucia.jsatdecode.api.source.SourcePropertyDescriptor.Type.*;

/** Metadata only; Fmcomms2Settings remains the authoritative configuration parser. */
final class Fmcomms2Properties {
    private Fmcomms2Properties() { }

    static final List<SourcePropertyDescriptor> ALL = List.of(
            p("uri", "Device URI", STRING, "ip:192.168.2.1", null, null, false, "IIOD address, ip:host[:port]; bracket IPv6 addresses."),
            p("frequency", "Tuning frequency (Hz)", LONG, "2400000000", 70000000.0, 6000000000.0, true, "Receive LO frequency."),
            p("sample_rate", "PHY sample rate (Hz)", INTEGER, "3000000", 520833.0, 61440000.0, false, "With filter_source=Off, at least 2084000 Hz. Must be divisible by fpga_decimation."),
            p("bandwidth", "RF bandwidth (Hz)", INTEGER, "2000000", 200000.0, 52000000.0, true, "Receive RF bandwidth."),
            p("fpga_decimation", "FPGA decimation", INTEGER, "1", null, null, false, "Hardware must support the requested output rate.", "1", "2", "4", "8", "16"),
            p("buffer_size", "RX buffer (samples)", INTEGER, "65536", 1.0, 1048576.0, false, "Receive buffer capacity in complex samples."),
            p("block_samples", "Block samples", INTEGER, "4096", 1.0, 1048576.0, false, "Delivery block capacity; must not exceed buffer_size."),
            p("rx1_en", "Enable RX1", BOOLEAN, "true", null, null, false, "Enable exactly one of RX1 and RX2."),
            p("rx2_en", "Enable RX2", BOOLEAN, "false", null, null, false, "Enable exactly one of RX1 and RX2."),
            p("gain1", "RX1 gain mode", STRING, "slow_attack", null, null, true, "Gain control for RX1.", "manual", "slow_attack", "fast_attack", "hybrid"),
            p("gain2", "RX2 gain mode", STRING, "slow_attack", null, null, true, "Gain control for RX2.", "manual", "slow_attack", "fast_attack", "hybrid"),
            p("manual_gain1", "RX1 manual gain (dB)", DOUBLE, "64", -10.0, 73.0, true, "Used in manual mode; hardware can further restrict gain."),
            p("manual_gain2", "RX2 manual gain (dB)", DOUBLE, "64", -10.0, 73.0, true, "Used in manual mode; hardware can further restrict gain."),
            p("rf_port_select", "RF input port", STRING, "A_BALANCED", null, null, false, "Selected receive RF port.",
                    "A_BALANCED", "B_BALANCED", "C_BALANCED", "A_N", "A_P", "B_N", "B_P", "C_N", "C_P", "TX_MONITOR1", "TX_MONITOR2", "TX_MONITOR1_2"),
            p("quadrature", "Quadrature tracking", BOOLEAN, "true", null, null, false, "Enable quadrature tracking."),
            p("rfdc", "RF DC tracking", BOOLEAN, "true", null, null, false, "Enable RF DC offset tracking."),
            p("bbdc", "Baseband DC tracking", BOOLEAN, "true", null, null, false, "Enable baseband DC offset tracking."),
            p("filter_source", "FIR filter mode", STRING, "Off", null, null, false, "Off disables FIR; File loads filter; Keep preserves hardware FIR.", "Off", "File", "Keep"),
            p("filter", "FIR filter file", PATH, null, null, null, false, "Required only for filter_source=File; 1..65536 bytes."),
            p("connect_timeout_ms", "Connect timeout (ms)", INTEGER, "5000", 1.0, 120000.0, false, "Connection timeout."),
            p("read_timeout_ms", "Read timeout (ms)", INTEGER, "5000", 1.0, 120000.0, false, "Must exceed server_timeout_ms."),
            p("server_timeout_ms", "Server timeout (ms)", INTEGER, "3000", 1.0, 60000.0, false, "Must exceed the receive buffer signal duration."),
            p("kernel_buffers", "Kernel buffers", INTEGER, "4", 1.0, 64.0, false, "IIOD kernel buffer count."),
            p("control_device", "Control device", STRING, "ad9361-phy", null, null, false, "IIOD device name or ID."),
            p("stream_device", "Stream device", STRING, "cf-ad9361-lpc", null, null, false, "IIOD ADC device name or ID."),
            p("recording.enabled", "Source CF32 recording", BOOLEAN, "false", null, null, false, "Source-owned recording, independent of Inspector storage."),
            p("recording.folder", "Source recording folder", PATH, null, null, null, false, "Defaults to user.home when source recording is enabled."),
            p("recording.name", "Source recording name", STRING, "session", null, null, false, "Portable recording filename prefix, 1..120 characters."),
            p("recording.queue_mib", "Source recording queue (MiB)", INTEGER, "64", 1.0, 1024.0, false, "Must hold at least one delivery block."),
            p("recording.write_buffer_mib", "Source write buffer (MiB)", INTEGER, "4", 1.0, 64.0, false, "Source recording write buffer size."),
            p("recording.failure_policy", "Source recording failure policy", STRING, "STOP_RECORDING", null, null, false, "Action on source recorder failure.", "STOP_RECORDING", "FAIL_SESSION"));

    static final Set<String> RUNTIME_KEYS = ALL.stream().filter(SourcePropertyDescriptor::runtimeUpdatable)
            .map(SourcePropertyDescriptor::name).collect(Collectors.toUnmodifiableSet());

    private static SourcePropertyDescriptor p(String key, String label, SourcePropertyDescriptor.Type type,
                                               String fallback, Double min, Double max, boolean live, String help,
                                               String... choices) {
        String unit = switch (key) {
            case "frequency", "sample_rate", "bandwidth" -> "Hz";
            case "manual_gain1", "manual_gain2" -> "dB";
            case "connect_timeout_ms", "read_timeout_ms", "server_timeout_ms" -> "ms";
            case "buffer_size", "block_samples" -> "samples";
            case "recording.queue_mib", "recording.write_buffer_mib" -> "MiB";
            default -> "";
        };
        return new SourcePropertyDescriptor(key, label, type, unit, fallback, false, min, max, List.of(choices), live, help,
                !Set.of("uri", "frequency", "sample_rate", "bandwidth", "fpga_decimation", "buffer_size", "gain1", "manual_gain1").contains(key));
    }
}
