package io.github.brainboxemb.eventtiming.timingpoint.presentation.common.terminal;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord.AutomaticTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl.TagProcessingConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl.TagProcessingPatch;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl.TagProcessingValue;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl.TimingNodeConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeProxy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookTestControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeStatus;
import io.github.brainboxemb.eventtiming.timingpoint.application.CommandResult;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.ConsolePromptControl;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingLevel;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingLevelControl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared line-oriented command session used by local and remote terminal transports. */
public final class TerminalSession {
    private static final int REGISTRATION_PAGE_SIZE = 100;
    private static final int MAX_REGISTRATION_PAGE_SIZE = 1000;
    private static final DateTimeFormatter REGISTRATION_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HHmmssSS")
                    .withZone(ZoneOffset.UTC);

    private final PresentationGateway presentationGateway;
    private final LoggingLevelControl loggingLevelControl;
    private final ConsolePromptControl consolePromptControl;
    private final Runnable shutdown;
    private NodeId currentNodeId;
    private boolean strictNodeAddressing;

    public TerminalSession(
            PresentationGateway presentationGateway,
            LoggingLevelControl loggingLevelControl,
            Runnable shutdown) {
        this(
                presentationGateway,
                loggingLevelControl,
                null,
                shutdown);
    }

    public TerminalSession(
            PresentationGateway presentationGateway,
            LoggingLevelControl loggingLevelControl,
            ConsolePromptControl consolePromptControl,
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
        this.consolePromptControl = consolePromptControl;
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
        currentNodeId =
                presentationGateway
                        .timingNodes()
                        .get(0)
                        .timingNodeId();
        strictNodeAddressing = isMultiNode();
        writer.println(readyMessage);

        String line;
        while (true) {
            String prompt = prompt();
            writer.print(prompt);
            writer.flush();
            if (consolePromptControl != null) {
                consolePromptControl.promptDisplayed(
                        writer,
                        prompt);
            }

            try {
                line = reader.readLine();
            } finally {
                if (consolePromptControl != null) {
                    consolePromptControl.promptConsumed();
                }
            }

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
        String commandToken = arguments[0];
        int separator = commandToken.indexOf(':');
        String command = (separator >= 0
                ? commandToken.substring(0, separator)
                : commandToken).toLowerCase(Locale.ROOT);
        String target = separator >= 0
                ? commandToken.substring(separator + 1)
                : null;

        try {
            if (target != null && !isNodeCommand(command)) {
                output.println("Command does not accept a NodeId: " + command);
                return false;
            }
            NodeId nodeId = null;
            if (target != null) {
                if (target.isEmpty() || target.indexOf(':') >= 0) {
                    output.println("Invalid NodeId: " + target);
                    return false;
                }
                nodeId = presentationGateway.timingNode(
                        new NodeId(target)).timingNodeId();
            }

            if (nodeId == null
                    && strictNodeAddressing
                    && requiresNodeTarget(command, arguments)) {
                output.println(
                        "Explicit NodeId required in multi-node strict mode. "
                                + "Use " + command + ":<NodeId>.");
                return false;
            }

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
                    showStatus(nodeId, output);
                    return false;
                case "node":
                    node(arguments, output);
                    return false;
                case "node-mode":
                    nodeMode(arguments, output);
                    return false;
                case "open":
                    open(arguments, nodeId, output);
                    return false;
                case "close":
                    close(arguments, nodeId, output);
                    return false;
                case "start":
                    stageStart(arguments, nodeId, output);
                    return false;
                case "reg":
                    registration(arguments, nodeId, output);
                    return false;
                case "regs":
                    registrations(arguments, nodeId, output);
                    return false;
                case "test":
                    test(arguments, nodeId, output);
                    return false;
                case "config":
                    configuration(arguments, nodeId, output);
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
            NodeId nodeId,
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
                        + timingNode(nodeId)
                                .open(new LocationId(locationId))
                                .name());
    }

    private void close(
            String[] arguments,
            NodeId nodeId,
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
                        + timingNode(nodeId)
                                .close()
                                .name());
    }

    private void stageStart(
            String[] arguments,
            NodeId nodeId,
            PrintWriter output) {

        if (arguments.length != 3) {
            output.println("Usage: start <id|team> <HHMMSShh>");
            return;
        }
        if (!isCompactOperatorTime(arguments[2])) {
            throw new IllegalArgumentException(
                    "stage start time must be HHMMSShh");
        }

        TimingNodeProxy timingNode = timingNode(nodeId);
        RegistrationId registrationId =
                registrationId(
                        arguments[1],
                        timingNode);
        final TimingTimestamp start;
        try {
            start =
                    compactOperatorTime(
                            arguments[2],
                            timingNode.timeSource().now());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "stage start time must be HHMMSShh",
                    ex);
        }

        timingNode.setStageStart(
                registrationId,
                start.instant().toEpochMilli());
        output.println(
                "Stage start: SET id="
                        + registrationId.value()
                        + " time="
                        + arguments[2]);
    }

    private void registration(
            String[] arguments,
            NodeId nodeId,
            PrintWriter output) {

        if (arguments.length < 2 || arguments.length > 3) {
            output.println(
                    "Usage: reg <registrationId> [now|HHMMSShh|<UTC timestamp>]");
            return;
        }

        TimingNodeProxy timingNode = timingNode(nodeId);
        TimingTimestamp effectiveTime;
        AutomaticTimeSource timeSource;

        if (arguments.length == 2 || "now".equalsIgnoreCase(arguments[2])) {
            effectiveTime = new TimingTimestamp(timingNode.timeSource().now());
            timeSource = AutomaticTimeSource.NODE;
        } else if (isCompactOperatorTime(arguments[2])) {
            effectiveTime =
                    compactOperatorTime(
                            arguments[2],
                            timingNode.timeSource().now());
            timeSource = AutomaticTimeSource.API;
        } else {
            effectiveTime = TimingTimestamp.parseAbsolute(arguments[2]);
            timeSource = AutomaticTimeSource.API;
        }

        CommandResult result =
                timingNode.addTestAutomaticRegistration(
                        registrationId(arguments[1], timingNode),
                        effectiveTime,
                        timeSource);

        if (result.committed()) {
            output.println(
                    "Registration: COMMITTED seq="
                            + result.timingRecord().sequenceNumber());
        } else {
            output.println("Registration: " + result.outcome().name());
        }
    }

    private void registrations(
            String[] arguments,
            NodeId nodeId,
            PrintWriter output) {

        TimingNodeProxy timingNode = timingNode(nodeId);
        TimingNodeProxy.RegistrationProjectionInfo info =
                timingNode.registrationProjectionInfo();

        if (arguments.length == 2
                && "count".equalsIgnoreCase(arguments[1])) {
            printRegistrationInfo(info, output);
            return;
        }

        if (arguments.length == 1
                || arguments.length == 2
                        && "all".equalsIgnoreCase(arguments[1])) {
            printRegistrationInfo(info, output);
            long after = 0L;
            int printed = 0;
            while (printed < info.count()) {
                List<TimingNodeProxy.RegistrationEntry> page =
                        registrationPage(
                                timingNode,
                                after,
                                REGISTRATION_PAGE_SIZE);
                if (page.isEmpty()) {
                    break;
                }
                printRegistrationPage(page, output);
                printed += page.size();
                after = page.get(page.size() - 1).sequenceNumber();
            }
            return;
        }

        if (arguments.length < 2 || arguments.length > 3) {
            output.println("Usage: regs [count|all|<afterSeq> [limit]]");
            return;
        }

        long after;
        int limit = REGISTRATION_PAGE_SIZE;
        try {
            after = Long.parseLong(arguments[1]);
            if (arguments.length == 3) {
                limit = Integer.parseInt(arguments[2]);
            }
        } catch (NumberFormatException ex) {
            output.println("Usage: regs [count|all|<afterSeq> [limit]]");
            return;
        }

        if (after < 0L || limit < 1 || limit > MAX_REGISTRATION_PAGE_SIZE) {
            output.println(
                    "regs requires afterSeq >= 0 and limit 1.."
                            + MAX_REGISTRATION_PAGE_SIZE);
            return;
        }

        printRegistrationInfo(info, output);
        printRegistrationPage(
                registrationPage(
                        timingNode,
                        after,
                        limit),
                output);
    }

    private static List<TimingNodeProxy.RegistrationEntry> registrationPage(
            TimingNodeProxy timingNode,
            long after,
            int limit) {

        List<TimingNodeProxy.RegistrationEntry> rows =
                new ArrayList<TimingNodeProxy.RegistrationEntry>();
        timingNode.visitRegistrationsAfter(
                after,
                limit,
                rows::add);
        return rows;
    }

    private static void printRegistrationInfo(
            TimingNodeProxy.RegistrationProjectionInfo info,
            PrintWriter output) {

        output.println(
                "Registrations: location="
                        + info.locationId().value()
                        + " count="
                        + info.count()
                        + " unique="
                        + info.uniqueCount()
                        + " lastMutation="
                        + info.lastMutationSequence()
                        + " lastRevoke="
                        + info.lastRevokeSequence());
    }

    private static void printRegistrationPage(
            List<TimingNodeProxy.RegistrationEntry> rows,
            PrintWriter output) {

        for (TimingNodeProxy.RegistrationEntry row : rows) {
            output.println(
                    "  seq="
                            + row.sequenceNumber()
                            + " id="
                            + row.registrationId().value()
                            + " time="
                            + REGISTRATION_TIME_FORMAT.format(
                                    row.effectiveTime().instant())
                            + " runtime="
                            + (row.elapsedMillis() == null
                                    ? "-"
                                    : formatElapsedMillis(
                                            row.elapsedMillis().longValue()))
                            + " rank="
                            + (row.rank() == null
                                    ? "-"
                                    : row.rank().toString())
                            + " state="
                            + (row.valid() ? "ACTIVE" : "REVOKED"));
        }
    }

    /** Formats one signed millisecond runtime with hundredth-second display precision. */
    private static String formatElapsedMillis(long elapsedMillis) {

        boolean negative = elapsedMillis < 0L;
        long absoluteMillis =
                negative
                        ? -elapsedMillis
                        : elapsedMillis;
        long totalHundredths = absoluteMillis / 10L;
        long hours = totalHundredths / 360000L;
        long minutes = totalHundredths / 6000L % 60L;
        long seconds = totalHundredths / 100L % 60L;
        long hundredths = totalHundredths % 100L;

        return (negative ? "-" : "")
                + String.format(
                        Locale.ROOT,
                        "%02d:%02d:%02d.%02d",
                        hours,
                        minutes,
                        seconds,
                        hundredths);
    }

    /** Resolves a numeric team number or accepts an explicit RegistrationId. */
    private static RegistrationId registrationId(
            String text,
            TimingNodeProxy timingNode) {

        if (!isUnsignedNumber(text)) {
            return new RegistrationId(text);
        }

        final int teamNumber;
        try {
            teamNumber = Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("team number must be in range 1..9999", ex);
        }
        if (teamNumber < 1 || teamNumber > 9999) {
            throw new IllegalArgumentException("team number must be in range 1..9999");
        }

        TeamId teamId =
                new TeamId(
                        String.format(
                                Locale.ROOT,
                                "%04d",
                                teamNumber));
        RegistrationId registrationId =
                timingNode.registrationIdFor(teamId);
        if (registrationId == null) {
            throw new IllegalArgumentException(
                    "no RegistrationId is known for team " + teamNumber);
        }
        return registrationId;
    }

    private static boolean isUnsignedNumber(String text) {

        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            if (!Character.isDigit(text.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /** Reports whether text is the compact operator time form HHMMSShh. */
    private static boolean isCompactOperatorTime(String text) {

        if (text == null || text.length() != 8) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            if (!Character.isDigit(text.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /** Resolves compact HHMMSShh against the node clock's current UTC date. */
    private static TimingTimestamp compactOperatorTime(
            String text,
            Instant nodeNow) {

        try {
            int hour = Integer.parseInt(text.substring(0, 2));
            int minute = Integer.parseInt(text.substring(2, 4));
            int second = Integer.parseInt(text.substring(4, 6));
            int hundredths = Integer.parseInt(text.substring(6, 8));

            LocalTime time =
                    LocalTime.of(
                            hour,
                            minute,
                            second,
                            hundredths * 10_000_000);
            LocalDate date =
                    nodeNow.atOffset(ZoneOffset.UTC).toLocalDate();
            return new TimingTimestamp(
                    date.atTime(time).toInstant(ZoneOffset.UTC));
        } catch (DateTimeException | NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "compact time must be HHMMSShh",
                    ex);
        }
    }

    private void test(
            String[] arguments,
            NodeId nodeId,
            PrintWriter output) {
        if (arguments.length != 4
                || !"logbook".equalsIgnoreCase(arguments[1])
                || !"write".equalsIgnoreCase(arguments[2])) {
            output.println("Usage: test logbook write <fail|timeout>");
            return;
        }

        LogBookTestControl.WriteFailure failure;
        switch (arguments[3].toLowerCase(Locale.ROOT)) {
            case "fail":
                failure = LogBookTestControl.WriteFailure.FAIL;
                break;
            case "timeout":
                failure = LogBookTestControl.WriteFailure.TIMEOUT;
                break;
            default:
                output.println("Usage: test logbook write <fail|timeout>");
                return;
        }

        timingNode(nodeId).testNextLogBookWriteFailure(failure);
        output.println("Next LogBook write test failure: " + failure.name());
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
            NodeId nodeId,
            PrintWriter output) {
        if (arguments.length == 1) {
            showConfiguration(nodeId, output);
            return;
        }
        if (arguments.length == 2
                && "tag-processing".equalsIgnoreCase(arguments[1])) {
            showConfiguration(nodeId, output);
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
                                .clearTagProcessing(effectiveNodeId(nodeId)),
                        effectiveNodeId(nodeId),
                        output);
                return;
            }
            if ("set".equals(action)) {
                setConfiguration(arguments, nodeId, output);
                return;
            }
        }

        output.println(
                "Usage: config [tag-processing "
                        + "[set <field=value>...|clear]]");
    }

    private void setConfiguration(
            String[] arguments,
            NodeId nodeId,
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
                                effectiveNodeId(nodeId),
                                new TagProcessingPatch(
                                        quiet,
                                        maxBurst,
                                        duplicate,
                                        sweep,
                                        queue)),
                effectiveNodeId(nodeId),
                output);
    }

    private void updateConfiguration(
            ConfigurationControl.Update update,
            NodeId nodeId,
            PrintWriter output) {
        output.println(
                "Tag processing update: "
                        + update.result().name());
        showTagProcessing(
                output,
                nodeId.value(),
                update.tagProcessing());
    }

    private NodeId currentNodeId() {
        return currentNodeId;
    }

    private NodeId effectiveNodeId(NodeId nodeId) {
        return nodeId == null ? currentNodeId : nodeId;
    }

    private TimingNodeProxy timingNode(NodeId nodeId) {
        return presentationGateway.timingNode(effectiveNodeId(nodeId));
    }

    private boolean isMultiNode() {
        return presentationGateway.timingNodes().size() > 1;
    }

    private String prompt() {
        if (!isMultiNode()) {
            return "event-timing> ";
        }
        return "event-timing[" + currentNodeId.value() + "]> ";
    }

    private static boolean isNodeCommand(String command) {
        return "open".equals(command)
                || "close".equals(command)
                || "start".equals(command)
                || "status".equals(command)
                || "reg".equals(command)
                || "regs".equals(command)
                || "test".equals(command)
                || "config".equals(command);
    }

    private static boolean requiresNodeTarget(
            String command,
            String[] arguments) {
        if ("open".equals(command)
                || "close".equals(command)
                || "start".equals(command)
                || "status".equals(command)
                || "reg".equals(command)
                || "regs".equals(command)
                || "test".equals(command)) {
            return true;
        }
        if (!"config".equals(command)
                || arguments.length < 3
                || !"tag-processing".equalsIgnoreCase(arguments[1])) {
            return false;
        }
        return "set".equalsIgnoreCase(arguments[2])
                || "clear".equalsIgnoreCase(arguments[2]);
    }

    private void nodeMode(
            String[] arguments,
            PrintWriter output) {
        if (!isMultiNode()) {
            if (arguments.length != 1) {
                output.println("Usage: node-mode [strict|selected]");
                return;
            }
            output.println("Node addressing mode: SINGLE");
            return;
        }

        if (arguments.length == 1) {
            output.println(
                    "Node addressing mode: "
                            + (strictNodeAddressing ? "STRICT" : "SELECTED"));
            return;
        }
        if (arguments.length != 2) {
            output.println("Usage: node-mode [strict|selected]");
            return;
        }

        if ("strict".equalsIgnoreCase(arguments[1])) {
            strictNodeAddressing = true;
        } else if ("selected".equalsIgnoreCase(arguments[1])) {
            strictNodeAddressing = false;
        } else {
            output.println("Usage: node-mode [strict|selected]");
            return;
        }

        output.println(
                "Node addressing mode: "
                        + (strictNodeAddressing ? "STRICT" : "SELECTED"));
    }

    private void node(
            String[] arguments,
            PrintWriter output) {
        if (arguments.length == 1) {
            output.println(
                    "TimingNode: "
                            + currentNodeId.value());
            output.print("Available:");
            for (TimingNodeProxy timingNode
                    : presentationGateway.timingNodes()) {
                output.print(
                        " "
                                + timingNode
                                        .timingNodeId()
                                        .value());
            }
            output.println();
            return;
        }
        if (arguments.length != 2) {
            output.println("Usage: node [id]");
            return;
        }

        NodeId requested =
                new NodeId(arguments[1]);
        TimingNodeProxy timingNode =
                presentationGateway.timingNode(
                        requested);
        currentNodeId =
                timingNode.timingNodeId();
        output.println(
                "TimingNode: "
                        + currentNodeId.value());
    }

    private void showConfiguration(
            NodeId nodeId,
            PrintWriter output) {
        output.println("Configuration");
        for (TimingNodeConfiguration node
                : presentationGateway
                        .configuration()
                        .snapshot()
                        .timingNodes()) {
            if (nodeId != null && !node.nodeId().equals(nodeId)) {
                continue;
            }
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
        output.println("  status                       Show selected TimingNode status");
        output.println("  node [id]                    Show/select default TimingNode");
        output.println("  node-mode [strict|selected]  Show/set multi-node addressing mode");
        output.println("  <command>:<NodeId>            Target one command at a node");
        output.println("                               Multi-node defaults to STRICT");
        output.println("  open:A 1                     Example: open node A at location 1");
        output.println("  status:B                     Example: show node B status");
        output.println("  open <locationId>            Open selected TimingNode at location");
        output.println("  close                        Close selected TimingNode");
        output.println(
                "  start <id|team> <HHMMSShh>   Set registration stage-start time");
        output.println(
                "                               Example: start 42 12345678");
        output.println(
                "  reg <id|team> [time]         Register one ID/team; omitted/now uses node clock");
        output.println(
                "                               time: now, HHMMSShh, or absolute UTC");
        output.println("                               Example: reg 7");
        output.println("                               Example: reg A0001");
        output.println("                               Example: reg A0001 now");
        output.println("                               Example: reg A0001 12345678");
        output.println(
                "                               Example: reg A0001 2026-10-19T13:00:00Z");
        output.println(
                "  regs [count|all|<after> [n]]  Show active-WP registration projection");
        output.println(
                "                               Example: regs count");
        output.println(
                "                               Example: regs 100 50");
        output.println(
                "  test logbook write <fail|timeout>"
                        + "  Fail or delay next LogBook write");
        output.println(
                "  test:A logbook write fail   "
                        + "Example: fail next write for node A");
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

    private void showStatus(NodeId nodeId, PrintWriter output) {
        TimingNodeProxy timingNode = timingNode(nodeId);
        TimingNodeStatus status = timingNode.status();

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
    }
}
