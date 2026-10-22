package io.github.brainboxemb.eventtiming.timingpoint.presentation.common.terminal;

import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventData;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.PresentationGatewayFixture;

import java.io.StringReader;
import java.io.StringWriter;
import java.time.ZoneId;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** Verifies terminal operator-time and help semantics independently of the host time zone. */
public class TerminalSessionTest {

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
                "seq=1 node=A kind=NODE_OPEN loc=5"));
        assertTrue(text.contains(
                "seq=2 node=A kind=AUTOMATIC_REGISTRATION loc=5 id=A0001 action=ADD"));
        assertTrue(text.contains("effectiveTime=2026-10-01T12:00:01.000Z"));
        assertTrue(text.contains("recordedAt=2026-10-01T12:00:01.000Z"));
        assertTrue(text.contains("RaceData: mappings=0 starts=1 timeBasis=UTC"));
        assertTrue(text.contains(
                "id=A0001 start=2026-10-01T11:00:00.000Z"));
        assertTrue(text.contains(
                "RaceData: id=A0001 liveTeam=- start=2026-10-01T11:00:00.000Z"));
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
        assertTrue(text.contains("effectiveTime and recordedAt are absolute UTC"));
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
