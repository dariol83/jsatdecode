package eu.dariolucia.jsatdecode.cli;

import eu.dariolucia.jsatdecode.api.config.ConfigurationException;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable parsed CLI; parsing performs no I/O. Last repeated override/switch wins.
 *
 * @param config    optional properties path
 * @param overrides explicit settings
 * @param switches  dedicated settings
 * @param action    run, help, version, list-sources, list-decoders or ui-demo
 */
public record CommandLine(Path config, Map<String, String> overrides, Map<String, Object> switches, String action) {
    /**
     * Copies parsed settings.
     *
     * @param config    optional path
     * @param overrides explicit settings
     * @param switches  dedicated settings
     * @param action    requested action
     */
    public CommandLine {
        overrides = Map.copyOf(overrides);
        switches = Map.copyOf(switches);
    }

    /**
     * Parses supported options and rejects missing values or unknown arguments.
     *
     * @param args arguments
     * @return parsed command
     */
    public static CommandLine parse(String[] args) {
        Path file = null;
        Map<String, String> overrides = new LinkedHashMap<>();
        Map<String, Object> switches = new LinkedHashMap<>();
        String action = args.length == 0 ? "help" : "run";
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            switch (option) {
                case "--config", "--set", "--store" -> {
                    if (++i == args.length || args[i].startsWith("--") || args[i].equals("-v"))
                        throw new ConfigurationException("Missing value for " + option);
                    if (option.equals("--config")) file = Path.of(args[i]);
                    else if (option.equals("--store")) {
                        if (args[i].isBlank()) throw new ConfigurationException("Missing folder for --store");
                        switches.put("storage.folder", args[i]);
                    } else {
                        int separator = args[i].indexOf('=');
                        if (separator <= 0 || args[i].substring(0, separator).isBlank())
                            throw new ConfigurationException("Expected --set key=value");
                        overrides.put(args[i].substring(0, separator), args[i].substring(separator + 1));
                    }
                }
                case "--ui" -> switches.put("ui.enabled", true);
                case "--headless" -> switches.put("ui.enabled", false);
                case "-v" -> switches.put("logging.verbose", true);
                case "--help", "-h", "--version", "--list-sources", "--list-decoders", "--ui-demo" -> {
                    String next = option.equals("-h") ? "help" : option.substring(2);
                    if (!action.equals("run") && !action.equals(next))
                        throw new ConfigurationException("Conflicting CLI actions");
                    action = next;
                }
                default -> throw new ConfigurationException("Unknown option: " + option);
            }
        }
        return new CommandLine(file, overrides, switches, action);
    }
}
