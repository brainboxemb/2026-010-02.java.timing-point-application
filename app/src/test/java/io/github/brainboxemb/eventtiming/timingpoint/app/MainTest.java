package io.github.brainboxemb.eventtiming.timingpoint.app;

import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

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
                "timing-application test-version"));
        assertTrue(output.stdout.contains(
                "revision=abc123def456"));
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
                        "timing-application test-version revision=abc123def456"));
        assertTrue(
                output.stdout.contains(
                        "sourceRef=feature/test"));
        assertTrue(
                output.stderr.contains(
                        "Unable to start application from configuration"));
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
