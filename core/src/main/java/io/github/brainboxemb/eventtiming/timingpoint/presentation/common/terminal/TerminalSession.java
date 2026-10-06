package io.github.brainboxemb.eventtiming.timingpoint.presentation.common.terminal;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl.TagProcessingConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl.TagProcessingPatch;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl.TagProcessingValue;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl.TimingNodeConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeProxy;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeStatus;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Problem;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.RegistrationResult;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingLevel;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingLevelControl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Shared line-oriented command session used by local and remote terminal transports. */
public final class TerminalSession {
    private static final String PROMPT = "event-timing> ";

    private final PresentationGateway presentationGateway;
    private final LoggingLevelControl loggingLevelControl;
    private final Runnable shutdown;

    public TerminalSession(
            PresentationGateway presentationGateway,
            LoggingLevelControl loggingLevelControl,
            Runnable shutdown) {
        if (presentationGateway == null) {
            throw new IllegalArgumentException(
                    "presentationGateway must not be null");
        }
        if (shutdown == null) {
            throw new IllegalArgumentException(
                    "shutdown must not be null");
        }
        this.presentationGateway = presentationGateway;
        this.loggingLevelControl = loggingLevelControl;
        this.shutdown = shutdown;
    }

    public void run(
            Reader input,
            Writer output,
            String readyMessage)
            throws IOException {
        if (input == null) {
            throw new IllegalArgumentException(
                    "input must not be null");
        }
        if (output == null) {
            throw new IllegalArgumentException(
                    "output must not be null");
        }
        if (readyMessage == null || readyMessage.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "readyMessage must not be blank");
        }

        BufferedReader reader = new BufferedReader(input);
        PrintWriter writer = new PrintWriter(output, true);
        writer.println(readyMessage);

        String line;
        while (true) {
            writer.print(PROMPT);
            writer.flush();
            line = reader.readLine();
            if (line == null) {
                return;
            }
            if (execute(line, writer)) {
                return;
            }
        }
    }

    private boolean execute(String line, PrintWriter output) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        String[] arguments = trimmed.split("\\s+");
        String command =
                arguments[0].toLowerCase(Locale.ROOT);

        try {
            switch (command) {
                case "help":
                    requireArgumentCount(arguments, 1, "help", output);
                    showHelp(output);
                    return false;
                case "version":
                    requireArgumentCount(arguments, 1, "version", output);
                    showVersion(output);
                    return false;
                case "status":
                    requireArgumentCount(arguments, 1, "status", output);
                    showStatus(output);
                    return false;
                case "open":
                    open(arguments, output);
                    return false;
                case "close":
                    close(arguments, output);
                    return false;
                case "auto-reg":
                    automaticRegistration(arguments, output);
                    return false;
                case "config":
                    configuration(arguments, output);
                    return false;
                case "log":
                    logging(arguments, output);
                    return false;
                case "quit":
                case "exit":
                    if (!requireArgumentCount(
                            arguments,
                            1,
                            command,
                            output)) {
                        return false;
                    }
                    output.println("Stopping application.");
                    shutdown.run();
                    return true;
                default:
                    output.println(
                            "Unknown command: "
                                    + trimmed
                                    + ". Type 'help' for commands.");
                    return false;
            }
        } catch (IllegalArgumentException ex) {
            output.println("Invalid command value: " + ex.getMessage());
            return false;
        } catch (RuntimeException ex) {
            output.println("Command failed: " + ex.getMessage());
            return false;
        }
    }

    private void open(
            String[] arguments,
            PrintWriter output) {
        if (arguments.length != 2) {
            output.println("Usage: open <locationId>");
            return;
        }

        int locationId;
        try {
            locationId = Integer.parseInt(arguments[1]);
        } catch (NumberFormatException ex) {
            output.println("Invalid locationId: " + arguments[1]);
            return;
        }

        output.println(
                "Open: "
                        + presentationGateway
                                .timingNode()
                                .open(new LocationId(locationId))
                                .name());
    }

    private void close(
            String[] arguments,
            PrintWriter output) {
        if (!requireArgumentCount(
                arguments,
                1,
                "close",
                output)) {
            return;
        }

        output.println(
                "Close: "
                        + presentationGateway
                                .timingNode()
                                .close()
                                .name());
    }

    private void automaticRegistration(
            String[] arguments,
            PrintWriter output) {
        if (arguments.length != 3) {
            output.println(
                    "Usage: auto-reg <registrationId> <time>");
            return;
        }

        RegistrationResult result =
                presentationGateway
                        .timingNode()
                        .applyAutomaticRegistration(
                                TimingNodeProxy
                                        .AutomaticRegistrationAction
                                        .ADD,
                                new RegistrationId(arguments[1]),
                                TimingTimestamp.parse(arguments[2]));

        if (result.committed()) {
            output.println(
                    "Automatic registration: COMMITTED seq="
                            + result.timingData()
                                    .sequenceNumber());
        } else {
            output.println(
                    "Automatic registration: "
                            + result.outcome().name());
        }
    }

    private void logging(
            String[] arguments,
            PrintWriter output) {
        if (loggingLevelControl == null) {
            output.println("Logging control unavailable.");
            return;
        }

        if (arguments.length == 1) {
            output.println("Log level: " + loggingLevelControl.level().name());
            return;
        }

        if (arguments.length != 2) {
            output.println("Usage: log [T|D|I|W|E]");
            return;
        }

        LoggingLevel level;
        switch (arguments[1].toUpperCase(Locale.ROOT)) {
            case "T":
                level = LoggingLevel.TRACE;
                break;
            case "D":
                level = LoggingLevel.DEBUG;
                break;
            case "I":
                level = LoggingLevel.INFO;
                break;
            case "W":
                level = LoggingLevel.WARN;
                break;
            case "E":
                level = LoggingLevel.ERROR;
                break;
            default:
                output.println("Usage: log [T|D|I|W|E]");
                return;
        }

        loggingLevelControl.setLevel(level);
        output.println("Log level: " + level.name());
    }

    private void configuration(
            String[] arguments,
            PrintWriter output) {
        if (arguments.length == 1) {
            showConfiguration(output);
            return;
        }
        if (arguments.length == 2
                && "tag-processing".equalsIgnoreCase(arguments[1])) {
            showConfiguration(output);
            return;
        }
        if (arguments.length >= 3
                && "tag-processing".equalsIgnoreCase(arguments[1])) {
            String action =
                    arguments[2].toLowerCase(Locale.ROOT);
            if ("clear".equals(action)) {
                if (arguments.length != 3) {
                    output.println(
                            "Usage: config tag-processing clear");
                    return;
                }
                updateConfiguration(
                        presentationGateway
                                .configuration()
                                .clearTagProcessing(currentNodeId()),
                        output);
                return;
            }
            if ("set".equals(action)) {
                setConfiguration(arguments, output);
                return;
            }
        }

        output.println(
                "Usage: config [tag-processing "
                        + "[set <field=value>...|clear]]");
    }

    private void setConfiguration(
            String[] arguments,
            PrintWriter output) {
        if (arguments.length < 4) {
            output.println(
                    "Usage: config tag-processing set "
                            + "<field=value>...");
            return;
        }

        Long quiet = null;
        Long maxBurst = null;
        Long duplicate = null;
        Long sweep = null;
        Long queue = null;
        Set<String> seen = new HashSet<String>();

        for (int index = 3;
                index < arguments.length;
                index++) {
            String assignment = arguments[index];
            int equals = assignment.indexOf('=');
            if (equals <= 0
                    || equals == assignment.length() - 1) {
                output.println(
                        "Invalid configuration assignment: "
                                + assignment);
                return;
            }

            String field = assignment.substring(0, equals);
            if (!seen.add(field)) {
                output.println(
                        "Duplicate configuration field: "
                                + field);
                return;
            }

            Long value;
            try {
                value = Long.valueOf(
                        assignment.substring(equals + 1));
            } catch (NumberFormatException ex) {
                output.println(
                        "Invalid integer for "
                                + field
                                + ": "
                                + assignment.substring(equals + 1));
                return;
            }

            switch (field) {
                case "quietTimeoutMillis":
                    quiet = value;
                    break;
                case "maxBurstDurationMillis":
                    maxBurst = value;
                    break;
                case "duplicateWindowMillis":
                    duplicate = value;
                    break;
                case "sweepCadenceMillis":
                    sweep = value;
                    break;
                case "observationQueueCapacity":
                    queue = value;
                    break;
                default:
                    output.println(
                            "Unknown tag-processing field: "
                                    + field);
                    return;
            }
        }

        updateConfiguration(
                presentationGateway
                        .configuration()
                        .setTagProcessing(
                                currentNodeId(),
                                new TagProcessingPatch(
                                        quiet,
                                        maxBurst,
                                        duplicate,
                                        sweep,
                                        queue)),
                output);
    }

    private void updateConfiguration(
            ConfigurationControl.Update update,
            PrintWriter output) {
        output.println(
                "Tag processing update: "
                        + update.result().name());
        showTagProcessing(
                output,
                currentNodeId().value(),
                update.tagProcessing());
    }

    private io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId
            currentNodeId() {
        return presentationGateway
                .timingNode()
                .status()
                .timingNodeId();
    }

    private void showConfiguration(PrintWriter output) {
        output.println("Configuration");
        for (TimingNodeConfiguration node
                : presentationGateway
                        .configuration()
                        .snapshot()
                        .timingNodes()) {
            showTagProcessing(
                    output,
                    node.nodeId().value(),
                    node.tagProcessing());
        }
    }

    private void showTagProcessing(
            PrintWriter output,
            String nodeId,
            TagProcessingConfiguration configuration) {
        output.println("  TimingNode " + nodeId);
        output.println(
                "    Tag processing overridden : "
                        + configuration.overridden());

        showTagProcessingValue(
                output,
                "quietTimeoutMillis",
                configuration.startup().quietTimeoutMillis(),
                configuration.current().quietTimeoutMillis(),
                configuration.quietTimeoutRuntimeMutable());
        showTagProcessingValue(
                output,
                "maxBurstDurationMillis",
                configuration.startup().maxBurstDurationMillis(),
                configuration.current().maxBurstDurationMillis(),
                configuration.maxBurstDurationRuntimeMutable());
        showTagProcessingValue(
                output,
                "duplicateWindowMillis",
                configuration.startup().duplicateWindowMillis(),
                configuration.current().duplicateWindowMillis(),
                configuration.duplicateWindowRuntimeMutable());
        showTagProcessingValue(
                output,
                "sweepCadenceMillis",
                configuration.startup().sweepCadenceMillis(),
                configuration.current().sweepCadenceMillis(),
                configuration.sweepCadenceRuntimeMutable());
        showTagProcessingValue(
                output,
                "observationQueueCapacity",
                configuration.startup().observationQueueCapacity(),
                configuration.current().observationQueueCapacity(),
                configuration.observationQueueCapacityRuntimeMutable());
    }

    private static void showTagProcessingValue(
            PrintWriter output,
            String name,
            long startup,
            long current,
            boolean runtimeMutable) {
        output.println(
                "    "
                        + name
                        + " : current="
                        + current
                        + " startup="
                        + startup
                        + " runtimeMutable="
                        + runtimeMutable);
    }

    private static boolean requireArgumentCount(
            String[] arguments,
            int expected,
            String command,
            PrintWriter output) {
        if (arguments.length == expected) {
            return true;
        }
        output.println("Usage: " + command);
        return false;
    }

    private void showHelp(PrintWriter output) {
        output.println("Commands:");
        output.println("  help                         Show available commands");
        output.println("  version                      Show application version");
        output.println("  status                       Show TimingNode status");
        output.println("  open <locationId>            Open TimingNode at location");
        output.println("  close                        Close TimingNode");
        output.println(
                "  auto-reg <id> <time>         "
                        + "Create automatic registration");
        output.println(
                "  config                       "
                        + "Show current configuration");
        output.println(
                "  log [T|D|I|W|E]             "
                        + "Show/set log level (TRACE/DEBUG/INFO/WARN/ERROR)");
        output.println(
                "  config tag-processing set "
                        + "<field=value>...");
        output.println(
                "                               "
                        + "Set runtime TagProcessor values");
        output.println(
                "  config tag-processing clear  "
                        + "Restore startup TagProcessor values");
        output.println("  quit                         Stop the application");
        output.println("  exit                         Alias for quit");
    }

    private void showVersion(PrintWriter output) {
        BuildIdentity identity =
                presentationGateway.version();
        output.println(identity.application());
        output.println(
                "  Version      : "
                        + identity.version());
        output.println(
                "  Revision     : "
                        + identity.revision());
        output.println(
                "  Source ref   : "
                        + identity.sourceRef());
        output.println(
                "  Build origin : "
                        + identity.buildOrigin());
        output.println(
                "  Source state : "
                        + (identity.dirty()
                                ? "modified"
                                : "clean"));
    }

    private void showStatus(PrintWriter output) {
        TimingNodeStatus status =
                presentationGateway.timingNode().status();
        output.println("Timing node");
        output.println(
                "  Id        : "
                        + status.timingNodeId().value());
        output.println(
                "  State     : "
                        + status.state().name());
        output.println(
                "  Location  : "
                        + (status.hasLocation()
                                ? status.locationId().value()
                                : "-"));
        for (Problem problem : status.problems()) {
            output.println(
                    "  Problem   : "
                            + problem.severity().name()
                            + " "
                            + problem.code().name());
            output.println(
                    "  Detail    : "
                            + problem.message());
        }
    }
}
