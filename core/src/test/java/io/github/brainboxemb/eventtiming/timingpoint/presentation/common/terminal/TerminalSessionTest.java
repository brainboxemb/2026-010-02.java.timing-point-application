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
