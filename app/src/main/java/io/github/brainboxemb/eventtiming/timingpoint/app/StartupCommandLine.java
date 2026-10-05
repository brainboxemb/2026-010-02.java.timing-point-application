package io.github.brainboxemb.eventtiming.timingpoint.app;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Parses the small, stable startup command line of the packaged application.
 *
 * <p>The parser intentionally owns only process-startup choices. Runtime
 * configuration remains in IF-11 YAML and is loaded by the runtime configuration
 * mapper after parsing.</p>
 */
final class StartupCommandLine {
    enum Action {
        SMOKE,
        START,
        GENERATE_CONFIG,
        HELP,
        VERSION
    }

    private static final String DEFAULT_CONFIG_FILE = "application.yml";

    private final Action action;
    private final Path path;

    private StartupCommandLine(Action action, Path path) {
        this.action = action;
        this.path = path;
    }

    static StartupCommandLine parse(String[] args) {
        if (args == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
        if (args.length == 0) {
            return new StartupCommandLine(Action.SMOKE, null);
        }

        if (args.length == 1) {
            String arg = requireNonBlank(args[0]);

            if ("-h".equals(arg) || "--help".equals(arg)) {
                return new StartupCommandLine(Action.HELP, null);
            }
            if ("-V".equals(arg) || "--version".equals(arg)) {
                return new StartupCommandLine(Action.VERSION, null);
            }
            if ("--generate-config".equals(arg)) {
                return new StartupCommandLine(
                        Action.GENERATE_CONFIG,
                        Paths.get(DEFAULT_CONFIG_FILE));
            }
            if (arg.startsWith("--config=")) {
                return new StartupCommandLine(
                        Action.START,
                        pathValue(
                                arg.substring("--config=".length()),
                                "--config"));
            }
            if (arg.startsWith("--generate-config=")) {
                return new StartupCommandLine(
                        Action.GENERATE_CONFIG,
                        pathValue(
                                arg.substring("--generate-config=".length()),
                                "--generate-config"));
            }
            if (arg.startsWith("-")) {
                throw new IllegalArgumentException(
                        "Unknown startup option: " + arg);
            }

            /*
             * Compatibility form retained from the first executable:
             * a single positional path means --config <path>.
             */
            return new StartupCommandLine(
                    Action.START,
                    Paths.get(arg));
        }

        if (args.length == 2) {
            String option = requireNonBlank(args[0]);
            String value = requireNonBlank(args[1]);

            if ("-c".equals(option) || "--config".equals(option)) {
                return new StartupCommandLine(
                        Action.START,
                        pathValue(value, option));
            }
            if ("--generate-config".equals(option)) {
                return new StartupCommandLine(
                        Action.GENERATE_CONFIG,
                        pathValue(value, option));
            }
        }

        throw new IllegalArgumentException(
                "Invalid startup arguments. Use --help to see supported options.");
    }

    Action action() {
        return action;
    }

    Path path() {
        return path;
    }

    static String helpText() {
        return "Timing Point Application\n"
                + "\n"
                + "Usage:\n"
                + "  java -jar timing-point-app-<version>.jar [<application.yml>]\n"
                + "  java -jar timing-point-app-<version>.jar --config <application.yml>\n"
                + "  java -jar timing-point-app-<version>.jar --generate-config [<application.yml>]\n"
                + "\n"
                + "Startup options:\n"
                + "  -c, --config <file>           Start with the selected IF-11 YAML file.\n"
                + "      --generate-config [file] Write the complete shipped example and exit.\n"
                + "                               Default: application.yml\n"
                + "  -V, --version                 Show build identity and exit.\n"
                + "  -h, --help                    Show this help and exit.\n"
                + "\n"
                + "Compatibility:\n"
                + "  No arguments run the packaged-artifact smoke check and exit.\n"
                + "  A single positional <application.yml> remains accepted as --config <file>.\n";
    }

    private static String requireNonBlank(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Startup arguments must not be blank.");
        }
        return value;
    }

    private static Path pathValue(String value, String option) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    option + " requires a non-blank file path.");
        }
        if (value.startsWith("-")) {
            throw new IllegalArgumentException(
                    option + " requires a file path, not another startup option: "
                            + value);
        }
        return Paths.get(value);
    }
}
