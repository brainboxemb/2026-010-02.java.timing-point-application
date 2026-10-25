package oss.relay.eventtiming.timingpoint.presentation.interfaces.console;

import oss.relay.eventtiming.eventdata.defaultprofile.DefaultEventData;
import oss.relay.eventtiming.timingdata.TimingRecord;
import oss.relay.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.eventtiming.timingdata.TimingDataTypes.NodeId;
import oss.relay.eventtiming.timingdata.TimingTimestamp;
import oss.relay.eventtiming.timingpoint.infra.BuildIdentity;
import oss.relay.eventtiming.timingpoint.infra.logging.LoggingLevel;
import oss.relay.eventtiming.timingpoint.infra.logging.LoggingLevelControl;
import oss.relay.eventtiming.timingpoint.testsupport.PresentationGatewayFixture;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalConsoleTest {
    @Test
    public void helpVersionStatusAndQuitUseSharedApplicationBoundary() {
        AtomicBoolean stopped = new AtomicBoolean(false);
        StringWriter output = new StringWriter();
        try (PresentationGatewayFixture fixture = new PresentationGatewayFixture(identity())) {
            LocalConsole console = new LocalConsole(
                    fixture.handler(),
                    () -> stopped.set(true),
                    new StringReader(
                            "help\n"
                                    + "help open\n"
                                    + "help start\n"
                                    + "help reg\n"
                                    + "help regs\n"
                                    + "help lb\n"
                                    + "help lbl\n"
                                    + "help data\n"
                                    + "help config\n"
                                    + "version\n"
                                    + "status\n"
                                    + "node\n"
                                    + "node A\n"
                                    + "open 24\n"
                                    + "status\n"
                                    + "reg N0001 2026-10-01T12:00:00Z\n"
                                    + "config\n"
                                    + "config tag-processing set "
                                    + "quietTimeoutMillis=300 "
                                    + "sweepCadenceMillis=75\n"
                                    + "config tag-processing clear\n"
                                    + "close\n"
                                    + "status\n"
                                    + "quit\n"),
                    output);

            console.run();
        }

        String text = output.toString();
        assertTrue(text.contains(
                "help [command]               Show commands or details for one command"));
        assertTrue(text.contains("version                      Show application version"));
        assertTrue(text.contains("log [T|D|I|W|E]             Show/set log level"));
        assertTrue(text.contains("status                       Show selected TimingNode status"));
        assertTrue(text.contains("node [id]                    Show/select default TimingNode"));
        assertTrue(text.contains("open <locationId>            Open selected TimingNode at location"));
        assertTrue(text.contains("close                        Close selected TimingNode"));
        assertTrue(text.contains("start <id|team> <time>"));
        assertTrue(text.contains("reg <id|team> [time]"));
        assertTrue(text.contains("regs [options]"));
        assertTrue(text.contains("lb [options]"));
        assertTrue(text.contains("lbl <locationId> [options]"));
        assertTrue(text.contains("data [options]"));
        assertTrue(text.contains("config [options]"));
        assertTrue(text.contains("Use 'help <command>' for syntax"));
        assertTrue(text.contains("Usage: open[:<NodeId>] <locationId>"));
        assertTrue(text.contains("Example: open:A 1"));
        assertTrue(text.contains("Usage: start[:<NodeId>] <id|team> <HHMMSShh[U]>"));
        assertTrue(text.contains("Suffix U makes the compact time UTC."));
        assertTrue(text.contains(
                "Usage: reg[:<NodeId>] <id|team> [now|HHMMSShh[U]|<UTC timestamp>]"));
        assertTrue(text.contains("Usage: regs[:<NodeId>]"));
        assertTrue(text.contains("runtime is finish minus stage start"));
        assertTrue(text.contains("Usage: lb[:<NodeId>]"));
        assertTrue(text.contains("Usage: lbl[:<NodeId>] <locationId>"));
        assertTrue(text.contains("Usage: data[:<NodeId>]"));
        assertTrue(text.contains("Usage: config[:<NodeId>]"));
        assertTrue(text.contains(
                "config:<NodeId> tag-processing set <field=value>..."));
        assertFalse(text.contains("auto-reg"));
        assertTrue(text.contains(
                "reboot [+log]                Restart; +log keeps live logging"));
        assertTrue(text.contains("quit                         Stop the application"));
        assertTrue(text.contains("exit                         Alias for quit"));
        assertTrue(text.contains("event-timing-app"));
        assertTrue(text.contains("Version      : test-version"));
        assertTrue(text.contains("Revision     : abc123def456"));
        assertTrue(text.contains("Source ref   : feature/test"));
        assertTrue(text.contains("Build origin : local"));
        assertTrue(text.contains("Source state : clean"));
        assertTrue(text.contains("Timing node"));
        assertTrue(text.contains("Id        : A"));
        assertTrue(text.contains("State     : CLOSED"));
        assertTrue(text.contains("TimingNode: A"));
        assertTrue(text.contains("Available: A"));
        assertTrue(text.contains("Open: OPENED"));
        assertTrue(text.contains("Location  : 24"));
        assertTrue(text.contains("Registration: COMMITTED seq=2"));
        assertTrue(text.contains("Configuration"));
        assertTrue(text.contains("quietTimeoutMillis : current=250 startup=250 runtimeMutable=true"));
        assertTrue(text.contains("Tag processing update: APPLIED"));
        assertTrue(text.contains("quietTimeoutMillis : current=300 startup=250 runtimeMutable=true"));
        assertTrue(text.contains("sweepCadenceMillis : current=75 startup=50 runtimeMutable=true"));
        assertTrue(text.contains("Tag processing overridden : false"));
        assertTrue(text.contains("Close: CLOSED"));
        assertTrue(text.contains("Location  : -"));
        assertTrue(stopped.get());
    }

    @Test
    public void regAcceptsNodeClockCompactHundredthsAndExplicitUtcTime() {
        StringWriter output = new StringWriter();

        try (PresentationGatewayFixture fixture =
                new PresentationGatewayFixture(
                        identity(),
                        new DefaultEventData(),
                        "A")) {
            LocalConsole console = new LocalConsole(
                    fixture.handler(),
                    () -> { },
                    new StringReader(
                            "open 24\n"
                                    + "reg 1\n"
                                    + "reg 0002 now\n"
                                    + "reg 3 12345678U\n"
                                    + "reg A0004 2026-10-01T12:35:57Z\n"
                                    + "auto-reg A0005 now\n"
                                    + "quit\n"),
                    output);

            console.run();

            List<TimingRecord> records = new ArrayList<>();
            fixture.handler()
                    .timingNode(new NodeId("A"))
                    .visitLatestLogBook(10, records::add);

            TimingRecord first = records.get(1);
            TimingRecord second = records.get(2);
            TimingRecord compact = records.get(3);
            TimingRecord explicit = records.get(4);

            assertEquals(
                    new RegistrationId("A0001").packedValue(),
                    first.registrationId());
            assertEquals(
                    new RegistrationId("A0002").packedValue(),
                    second.registrationId());
            assertEquals(
                    new RegistrationId("A0003").packedValue(),
                    compact.registrationId());
            assertEquals(
                    new RegistrationId("A0004").packedValue(),
                    explicit.registrationId());

            TimingTimestamp nodeTime =
                    TimingTimestamp.parse("2026-10-01T12:00:01Z");
            assertEquals(nodeTime.instant().toEpochMilli(), first.effectiveTimeMillis());
            assertEquals(nodeTime.instant().toEpochMilli(), second.effectiveTimeMillis());
            assertEquals(TimingRecord.AutomaticTimeSource.NODE, first.automaticTimeSource());
            assertEquals(TimingRecord.AutomaticTimeSource.NODE, second.automaticTimeSource());
            assertEquals(
                    TimingTimestamp.parse("2026-10-01T12:34:56.78Z")
                            .instant()
                            .toEpochMilli(),
                    compact.effectiveTimeMillis());
            assertEquals(TimingRecord.AutomaticTimeSource.API, compact.automaticTimeSource());
            assertEquals(
                    TimingTimestamp.parse("2026-10-01T12:35:57Z").instant().toEpochMilli(),
                    explicit.effectiveTimeMillis());
            assertEquals(TimingRecord.AutomaticTimeSource.API, explicit.automaticTimeSource());
        }

        String text = output.toString();
        assertTrue(text.contains("Registration: COMMITTED seq=2"));
        assertTrue(text.contains("Registration: COMMITTED seq=3"));
        assertTrue(text.contains("Registration: COMMITTED seq=4"));
        assertTrue(text.contains("Registration: COMMITTED seq=5"));
        assertTrue(text.contains("Unknown command: auto-reg A0005 now"));
    }

    @Test
    public void startAcceptsRegistrationIdOrTeamNumberAndCompactHundredths() {
        StringWriter output = new StringWriter();

        try (PresentationGatewayFixture fixture =
                new PresentationGatewayFixture(
                        identity(),
                        new DefaultEventData(),
                        "A")) {
            LocalConsole console = new LocalConsole(
                    fixture.handler(),
                    () -> { },
                    new StringReader(
                            "open 1\n"
                                    + "start 42 12345678\n"
                                    + "start A0043 10345679U\n"
                                    + "start 42 24600000\n"
                                    + "quit\n"),
                    output);

            console.run();
        }

        String text = output.toString();
        assertTrue(text.contains(
                "Stage start: SET id=A0042 time=12345678"));
        assertTrue(text.contains(
                "Stage start: SET id=A0043 time=10345679U"));
        assertTrue(text.contains(
                "Invalid command value: stage start time must be HHMMSShh or HHMMSShhU"));
    }

    @Test
    public void regsShowsUniqueCountAndPagedActiveWpProjection() {
        StringWriter output = new StringWriter();

        try (PresentationGatewayFixture fixture =
                new PresentationGatewayFixture(
                        identity(),
                        new DefaultEventData(),
                        "A")) {
            LocalConsole console = new LocalConsole(
                    fixture.handler(),
                    () -> { },
                    new StringReader(
                            "open 24\n"
                                    + "reg 1 12000100\n"
                                    + "reg 2 12000200\n"
                                    + "reg 3 12000300\n"
                                    + "start 1 12000050\n"
                                    + "start 2 12000000\n"
                                    + "regs count\n"
                                    + "regs 2 1\n"
                                    + "regs all\n"
                                    + "quit\n"),
                    output);

            console.run();
        }

        String text = output.toString();
        assertTrue(text.contains(
                "Registrations: location=24 count=3 unique=3"));
        assertTrue(text.contains(
                "seq=2 id=A0001 time=12000100 runtime=00:00:00.50 rank=1 state=ACTIVE"));
        assertTrue(text.contains(
                "seq=3 id=A0002 time=12000200 runtime=00:00:02.00 rank=2 state=ACTIVE"));
        assertTrue(text.contains(
                "seq=4 id=A0003 time=12000300 runtime=- rank=- state=ACTIVE"));
    }

    @Test
    public void logCommandShowsAndChangesRuntimeLevel() {
        AtomicBoolean stopped = new AtomicBoolean(false);
        StringWriter output = new StringWriter();
        TestLoggingLevelControl logging = new TestLoggingLevelControl();

        try (PresentationGatewayFixture fixture = new PresentationGatewayFixture(identity())) {
            LocalConsole console = new LocalConsole(
                    fixture.handler(),
                    logging,
                    () -> stopped.set(true),
                    new StringReader(
                            "help\n"
                                    + "log\n"
                                    + "log D\n"
                                    + "log\n"
                                    + "log I\n"
                                    + "quit\n"),
                    output);

            console.run();
        }

        String text = output.toString();
        assertTrue(text.contains("log [T|D|I|W|E]"));
        assertTrue(text.contains("Log level: INFO"));
        assertTrue(text.contains("Log level: DEBUG"));
        assertEquals(LoggingLevel.INFO, logging.level());
        assertTrue(stopped.get());
    }

    @Test
    public void exitAlsoStopsApplication() {
        AtomicBoolean stopped = new AtomicBoolean(false);
        try (PresentationGatewayFixture fixture = new PresentationGatewayFixture(identity())) {
            LocalConsole console = new LocalConsole(
                    fixture.handler(),
                    () -> stopped.set(true),
                    new StringReader("exit\n"),
                    new StringWriter());

            console.run();
        }

        assertTrue(stopped.get());
    }

    @Test
    public void unknownCommandDoesNotStopApplication() {
        AtomicBoolean stopped = new AtomicBoolean(false);
        StringWriter output = new StringWriter();
        try (PresentationGatewayFixture fixture = new PresentationGatewayFixture(identity())) {
            LocalConsole console = new LocalConsole(
                    fixture.handler(),
                    () -> stopped.set(true),
                    new StringReader("wat\n"),
                    output);

            console.run();
        }

        assertFalse(stopped.get());
        assertTrue(output.toString().contains("Unknown command: wat"));
    }

    private static final class TestLoggingLevelControl implements LoggingLevelControl {
        private LoggingLevel level = LoggingLevel.INFO;

        @Override
        public LoggingLevel level() {
            return level;
        }

        @Override
        public void setLevel(LoggingLevel level) {
            this.level = level;
        }
    }

    private static BuildIdentity identity() {
        return BuildIdentity.firstApiVersion(
                "event-timing-app",
                "test-version",
                "abc123def456",
                "feature/test",
                "local",
                false);
    }
}
