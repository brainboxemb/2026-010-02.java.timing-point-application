package io.github.brainboxemb.eventtiming.timingpoint.presentation.common.terminal;

import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeProxy;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.PresentationGatewayFixture;

import java.io.StringReader;
import java.io.StringWriter;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** Verifies terminal operator-time and help semantics independently of the host time zone. */
public class TerminalSessionTest {

    @Test
    public void rebootRequestsRestartWithoutRequestingStop() throws Exception {
        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicBoolean restarted = new AtomicBoolean(false);
        StringWriter output = new StringWriter();

        try (PresentationGatewayFixture fixture =
                new PresentationGatewayFixture(identity())) {
            TerminalSession session =
                    new TerminalSession(
                            fixture.handler(),
                            null,
                            () -> stopped.set(true),
                            () -> restarted.set(true));

            session.run(
                    new StringReader("reboot\n"),
                    output,
                    "ready");
        }

        assertTrue(output.toString().contains("Rebooting application."));
        assertTrue(restarted.get());
        assertTrue(!stopped.get());
    }

    @Test
    public void rebootPlusLogRequestsRetainedLoggingRestartOnly()
            throws Exception {
        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicBoolean restarted = new AtomicBoolean(false);
        AtomicBoolean retainedLogging = new AtomicBoolean(false);
        StringWriter output = new StringWriter();

        try (PresentationGatewayFixture fixture =
                new PresentationGatewayFixture(identity())) {
            TerminalSession session =
                    new TerminalSession(
                            fixture.handler(),
                            null,
                            null,
                            () -> stopped.set(true),
                            () -> restarted.set(true),
                            () -> retainedLogging.set(true));

            session.run(
                    new StringReader("reboot +log\n"),
                    output,
                    "ready");
        }

        assertTrue(output.toString().contains(
                "Rebooting application; keeping logging connection."));
        assertTrue(retainedLogging.get());
        assertTrue(!restarted.get());
        assertTrue(!stopped.get());
    }

    @Test
    public void compactTimeIsLocalUnlessExplicitlySuffixedUtc() throws Exception {
        StringWriter output = new StringWriter();

        try (PresentationGatewayFixture fixture =
                new PresentationGatewayFixture(
                        identity(),
                        new DefaultEventData(),
                        "A")) {
            TerminalSession session =
                    new TerminalSession(
                            fixture.handler(),
                            null,
                            null,
                            () -> { },
                            ZoneId.of("Europe/Amsterdam"));

            session.run(
                    new StringReader(
                            "open 1\n"
                                    + "reg A0001 14000100\n"
                                    + "start A0001 13000000\n"
                                    + "reg A0002 13000100U\n"
                                    + "start A0002 12000000U\n"
                                    + "regs all\n"
                                    + "quit\n"),
                    output,
                    "ready");
        }

        String text = output.toString();
        assertTrue(text.contains(
                "seq=2 id=A0001 time=14000100 runtime=01:00:01.00 rank=1 state=ACTIVE"));
        assertTrue(text.contains(
                "seq=3 id=A0002 time=15000100 runtime=01:00:01.00 rank=1 state=ACTIVE"));
    }

    @Test
    public void rawLogBookAndRaceDataInspectionKeepUtcTechnicalTime() throws Exception {
        StringWriter output = new StringWriter();

        try (PresentationGatewayFixture fixture =
                new PresentationGatewayFixture(
                        identity(),
                        new DefaultEventData(),
                        "A")) {
            TerminalSession session =
                    new TerminalSession(
                            fixture.handler(),
                            null,
                            null,
                            () -> { },
                            ZoneId.of("Europe/Amsterdam"));

            session.run(
                    new StringReader(
                            "open 5\n"
                                    + "reg A0001 14000100\n"
                                    + "start A0001 13000000\n"
                                    + "lb count\n"
                                    + "lb all\n"
                                    + "data\n"
                                    + "data starts\n"
                                    + "data A0001\n"
                                    + "quit\n"),
                    output,
                    "ready");
        }

        String text = output.toString();
        assertTrue(text.contains("LogBook: count=2"));
        assertTrue(text.contains(
                "seq=1 node=A type=NODE_INFO loc=5 code=OPEN"));
        assertTrue(text.contains(
                "seq=2 node=A type=AUTO_REG loc=5 id=A0001 code=ADD"));
        assertTrue(text.contains("time=2026-10-01T12:00:01.000Z"));
        assertTrue(text.contains("recTime=2026-10-01T12:00:01.000Z"));
        assertTrue(text.contains("RaceData: mappings=0 starts=1 timeBasis=UTC compact"));
        assertTrue(text.contains(
                "id=A0001 start=2026-10-01T11:00:00Z"));
        assertTrue(text.contains(
                "RaceData: id=A0001 liveTeam=- start=2026-10-01T11:00:00Z"));
    }

    @Test
    public void logBookUsesPersistedCodesAndLocationFilter() throws Exception {
        StringWriter output = new StringWriter();

        try (PresentationGatewayFixture fixture =
                new PresentationGatewayFixture(
                        identity(),
                        new DefaultEventData(),
                        "A")) {
            TimingNodeProxy node =
                    fixture.handler().timingNode(new NodeId("A"));
            node.open(new LocationId(5));
            node.addManualRegistration(
                    new RegistrationId("M0001"),
                    TimingTimestamp.parse("2026-10-01T12:00:05Z"),
                    TimingRecord.ManualTimeSource.OPERATOR_ENTERED);
            node.addManualRegistration(
                    new RegistrationId("M0002"),
                    TimingTimestamp.parse("2026-10-01T12:00:05.500Z"),
                    TimingRecord.ManualTimeSource.AUTOMATIC);
            node.close();
            node.open(new LocationId(6));
            node.addTestAutomaticRegistration(
                    new RegistrationId("A0002"),
                    TimingTimestamp.parse("2026-10-01T12:00:06Z"),
                    TimingRecord.AutomaticTimeSource.API);

            TerminalSession session =
                    new TerminalSession(
                            fixture.handler(),
                            null,
                            null,
                            () -> { },
                            ZoneId.of("Europe/Amsterdam"));
            session.run(
                    new StringReader(
                            "lb all\n"
                                    + "lbl 5 count\n"
                                    + "lbl 5 all\n"
                                    + "quit\n"),
                    output,
                    "ready");
        }

        String text = output.toString();
        assertTrue(text.contains(
                "type=MAN_REG loc=5 id=M0001 code=ADD,MAN"));
        assertTrue(text.contains(
                "type=MAN_REG loc=5 id=M0002 code=ADD,AUTO"));
        assertTrue(text.contains(
                "type=AUTO_REG loc=6 id=A0002 code=ADD"));
        assertTrue(text.contains("LogBook: location=5 count=4 timeBasis=UTC"));

        int filtered = text.lastIndexOf("LogBook: location=5 count=4 timeBasis=UTC");
        String filteredText = text.substring(filtered);
        assertTrue(filteredText.contains("type=NODE_INFO loc=5 code=OPEN"));
        assertTrue(filteredText.contains("type=MAN_REG loc=5 id=M0001 code=ADD,MAN"));
        assertTrue(filteredText.contains("type=MAN_REG loc=5 id=M0002 code=ADD,AUTO"));
        assertTrue(filteredText.contains("type=NODE_INFO loc=5 code=CLOSE"));
        assertTrue(!filteredText.contains(" loc=6 "));
    }

    @Test
    public void detailedHelpKeepsExamplesAndSubcommandsWithTheirCommand() throws Exception {
        StringWriter output = new StringWriter();

        try (PresentationGatewayFixture fixture =
                new PresentationGatewayFixture(identity())) {
            TerminalSession session =
                    new TerminalSession(
                            fixture.handler(),
                            null,
                            null,
                            () -> { },
                            ZoneId.of("Europe/Amsterdam"));

            session.run(
                    new StringReader(
                            "help\n"
                                    + "help open\n"
                                    + "help regs\n"
                                    + "help lb\n"
                                    + "help lbl\n"
                                    + "help data\n"
                                    + "help config\n"
                                    + "quit\n"),
                    output,
                    "ready");
        }

        String text = output.toString();
        int log = text.indexOf("log [T|D|I|W|E]");
        int status = text.indexOf("status                       Show selected TimingNode status");
        assertTrue(log >= 0 && status > log);
        assertTrue(text.contains("Usage: open[:<NodeId>] <locationId>"));
        assertTrue(text.contains("Example: open:A 1"));
        assertTrue(text.contains("Usage: regs[:<NodeId>]"));
        assertTrue(text.contains("runtime is finish minus stage start"));
        assertTrue(text.contains("Usage: lb[:<NodeId>]"));
        assertTrue(text.contains("type/code/time/recTime follow persisted LogBook vocabulary."));
        assertTrue(text.contains("Usage: lbl[:<NodeId>] <locationId>"));
        assertTrue(text.contains("Read raw LogBook history restricted to one LocationId."));
        assertTrue(text.contains("Usage: data[:<NodeId>]"));
        assertTrue(text.contains("Fixed EventData mappings are intentionally not included."));
        assertTrue(text.contains("Usage: config[:<NodeId>]"));
        assertTrue(text.contains("config:<NodeId> tag-processing clear"));
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
