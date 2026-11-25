package eu.dariolucia.jsatdecode.source.file;

import eu.dariolucia.jsatdecode.api.source.SourcePropertyDescriptor;

import java.util.List;

import static eu.dariolucia.jsatdecode.api.source.SourcePropertyDescriptor.Type.*;

/** Configuration metadata shared by the file providers; playback is setup-only. */
public final class FileSourceProperties {
    private FileSourceProperties() { }

    /** @param wav whether the sample rate is read from a WAV header
     *  @return ordered descriptors with the existing defaults */
    public static List<SourcePropertyDescriptor> descriptors(boolean wav) {
        return List.of(
                property("file", "Recording file", PATH, null, true, null, null, "Readable IQ recording file."),
                property("sample_rate", "Sample rate (Hz)", DOUBLE, null, !wav, Double.MIN_VALUE, null,
                        wav ? "Optional; must match the WAV header. Leave empty to use the header." : "Positive complex sample rate in Hz."),
                property("block_samples", "Block samples", INTEGER, "4096", false, 1.0, 1048576.0, "Maximum complex samples per delivery block."),
                property("start_offset", "Start sample", LONG, "0", false, 0.0, null, "Inclusive complex sample index."),
                property("end_offset", "End sample", LONG, null, false, 0.0, null, "Exclusive end index; mutually exclusive with length."),
                property("length", "Sample count", LONG, null, false, 0.0, null, "Selected sample count; mutually exclusive with end_offset."),
                property("repeat", "Repeat", BOOLEAN, "false", false, null, null, "Repeat the selected nonempty segment until stopped."),
                property("realtime", "Real-time playback", BOOLEAN, "false", false, null, null, "Pace delivery to the recording sample clock."),
                property("center_frequency", "Center frequency (Hz)", DOUBLE, "0", false, 0.0, null, "Center frequency metadata; zero when unspecified."));
    }

    private static SourcePropertyDescriptor property(String name, String label, SourcePropertyDescriptor.Type type,
                                                      String fallback, boolean required, Double min, Double max, String help) {
        String unit = switch (name) {
            case "sample_rate", "center_frequency" -> "Hz";
            case "block_samples", "start_offset", "end_offset", "length" -> "samples";
            default -> "";
        };
        return new SourcePropertyDescriptor(name, label, type, unit, fallback, required, min, max, List.of(), false, help);
    }
}
