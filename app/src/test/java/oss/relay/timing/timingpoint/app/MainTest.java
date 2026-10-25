package oss.relay.timing.timingpoint.app;

import oss.relay.timing.timingpoint.infra.BuildIdentity;
import oss.relay.timing.timingpoint.infra.logging.Logging;
import oss.relay.timing.timingpoint.infra.logging.LoggingConfig;
import oss.relay.timing.timingpoint.infra.logging.LoggingFileConfig;
import oss.relay.timing.timingpoint.infra.logging.LoggingLevel;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MainTest {
    @Rule
    public final TemporaryFolder temporaryFolder =
            new TemporaryFolder();

    @Test
    public void helpReturnsSuccessAndDoesNotUseErrorOutput()
            throws Exception {
        Output output = run("--help");

        assertEquals(0, output.exitCode);
        assertTrue(output.stdout.contains("Startup options:"));
        assertTrue(output.stdout.contains("--generate-config"));
        assertTrue(output.stderr.isEmpty());
    }

    @Test
    public void versionReturnsBuildIdentity()
            throws Exception {
        Output output = run("--version");

        assertEquals(0, output.exitCode);
        assertTrue(output.stdout.contains(
                "Application identity"));
        assertTrue(output.stdout.contains(
                "  Application: timing-application"));
        assertTrue(output.stdout.contains(
                "  Version: test-version"));
        assertTrue(output.stdout.contains(
                "  Revision: abc123def456"));
        assertTrue(output.stdout.contains(
                "  SourceRef: feature/test"));
        assertTrue(output.stderr.isEmpty());
    }

    @Test
    public void normalStartWritesBuildIdentityBeforeConfigurationFailure()
            throws Exception {
        Path missingConfig =
                temporaryFolder
                        .getRoot()
                        .toPath()
                        .resolve(
                                "missing-application.yml");

        Output output =
                run(
                        missingConfig.toString());

        assertEquals(
                1,
                output.exitCode);
        assertTrue(
                output.stdout.contains(
                        "Application identity"));
        assertTrue(
                output.stdout.contains(
                        "  Version: test-version"));
        assertTrue(
                output.stdout.contains(
                        "  Revision: abc123def456"));
        assertTrue(
                output.stdout.contains(
                        "  SourceRef: feature/test"));
        assertTrue(
                output.stderr.contains(
                        "Unable to start application from configuration"));
    }

    @Test
    public void configuredStartupIdentityIsWrittenToRetainedLogOnce()
            throws Exception {
        File logs = temporaryFolder.newFolder("startup-identity-logs");
        LoggingConfig config = new LoggingConfig(
                LoggingLevel.INFO,
                new LoggingFileConfig(logs.getAbsolutePath(), 4096, 3));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        try (Logging logging = Logging.start(config);
                PrintStream out = new PrintStream(stdout, true, "UTF-8")) {
            ApplicationLauncher.announceStartupIdentity(identity(), logging, out);
        }

        File[] files = logs.listFiles((directory, name) -> name.endsWith(".txt"));
        assertTrue(files != null && files.length == 1);
        String retained = new String(
                Files.readAllBytes(files[0].toPath()),
                StandardCharsets.UTF_8);

        assertTrue(retained.contains("[INFO] - Application identity"));
        assertTrue(retained.contains("[INFO] -   Application: timing-application"));
        assertTrue(retained.contains("[INFO] -   Version: test-version"));
        assertTrue(retained.contains("[INFO] -   API version: 1"));
        assertTrue(retained.contains("[INFO] -   Revision: abc123def456"));
        assertTrue(retained.contains("[INFO] -   SourceRef: feature/test"));
        assertTrue(retained.contains("[INFO] -   BuildOrigin: local"));
        assertTrue(retained.contains("[INFO] -   Dirty: false"));
        assertEquals(1, retained.split("abc123def456", -1).length - 1);
        assertEquals("", stdout.toString("UTF-8"));
    }

    @Test
    public void startupIdentityStillUsesStdoutWithoutConfiguredLogging()
            throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, "UTF-8")) {
            ApplicationLauncher.announceStartupIdentity(identity(), null, out);
        }
        String text = stdout.toString("UTF-8");
        assertTrue(text.contains("Application identity"));
        assertTrue(text.contains("  Application: timing-application"));
        assertTrue(text.contains("  Version: test-version"));
        assertTrue(text.contains("  Revision: abc123def456"));
    }

    @Test
    public void invalidOptionReturnsUsageError()
            throws Exception {
        Output output = run("--invalid");

        assertEquals(2, output.exitCode);
        assertTrue(output.stderr.contains(
                "Unknown startup option: --invalid"));
        assertTrue(output.stderr.contains("Usage:"));
        assertFalse(output.stdout.contains("Usage:"));
    }

    @Test
    public void generateConfigWritesRequestedFile()
            throws Exception {
        Path target = temporaryFolder
                .getRoot()
                .toPath()
                .resolve("generated.yml");

        Output output = run(
                "--generate-config",
                target.toString());

        assertEquals(0, output.exitCode);
        assertTrue(java.nio.file.Files.isRegularFile(target));
        assertTrue(output.stdout.contains(
                "Generated example configuration:"));
        assertTrue(output.stderr.isEmpty());
    }

    @Test
    public void generateConfigRefusesOverwrite()
            throws Exception {
        Path target = temporaryFolder
                .newFile("existing.yml")
                .toPath();

        Output output = run(
                "--generate-config",
                target.toString());

        assertEquals(1, output.exitCode);
        assertTrue(output.stderr.contains(
                "Unable to generate example configuration"));
    }

    private static Output run(String... args)
            throws Exception {
        ByteArrayOutputStream stdoutBytes =
                new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBytes =
                new ByteArrayOutputStream();

        int exitCode;
        try (PrintStream stdout =
                        new PrintStream(stdoutBytes, true, "UTF-8");
                PrintStream stderr =
                        new PrintStream(stderrBytes, true, "UTF-8")) {
            exitCode = Main.run(
                    args,
                    identity(),
                    stdout,
                    stderr);
        }

        return new Output(
                exitCode,
                stdoutBytes.toString("UTF-8"),
                stderrBytes.toString("UTF-8"));
    }

    private static BuildIdentity identity() {
        return BuildIdentity.firstApiVersion(
                "timing-application",
                "test-version",
                "abc123def456",
                "feature/test",
                "local",
                false);
    }

    private static final class Output {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private Output(
                int exitCode,
                String stdout,
                String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
