package io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.console;

import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingLevel;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingLevelControl;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.PresentationGatewayFixture;

import java.io.StringReader;
import java.io.StringWriter;
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
                                    + "version\n"
                                    + "status\n"
                                    + "open 24\n"
                                    + "status\n"
                                    + "auto-reg N0001 2026-10-01T12:00:00Z\n"
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
        assertTrue(text.contains("help                         Show available commands"));
        assertTrue(text.contains("version                      Show application version"));
        assertTrue(text.contains("status                       Show TimingNode status"));
        assertTrue(text.contains("open <locationId>            Open TimingNode at location"));
        assertTrue(text.contains("close                        Close TimingNode"));
        assertTrue(text.contains("auto-reg <id> <time>"));
        assertTrue(text.contains("config                       Show current configuration"));
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
        assertTrue(text.contains("Open: OPENED"));
        assertTrue(text.contains("Location  : 24"));
        assertTrue(text.contains("Automatic registration: COMMITTED seq=1"));
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
