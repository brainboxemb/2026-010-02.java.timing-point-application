package io.github.brainboxemb.eventtiming.timingpoint.app;

import java.nio.file.Paths;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StartupCommandLineTest {
    @Test
    public void noArgumentsPreserveArtifactSmokeMode() {
        StartupCommandLine command =
                StartupCommandLine.parse(new String[0]);

        assertEquals(
                StartupCommandLine.Action.SMOKE,
                command.action());
        assertNull(command.path());
    }

    @Test
    public void positionalConfigurationRemainsSupported() {
        StartupCommandLine command =
                StartupCommandLine.parse(
                        new String[] {"config/application.yml"});

        assertEquals(
                StartupCommandLine.Action.START,
                command.action());
        assertEquals(
                Paths.get("config/application.yml"),
                command.path());
    }

    @Test
    public void parsesShortAndLongConfigForms() {
        StartupCommandLine shortForm =
                StartupCommandLine.parse(
                        new String[] {"-c", "one.yml"});
        StartupCommandLine longForm =
                StartupCommandLine.parse(
                        new String[] {"--config=two.yml"});

        assertEquals(
                StartupCommandLine.Action.START,
                shortForm.action());
        assertEquals(Paths.get("one.yml"), shortForm.path());
        assertEquals(
                StartupCommandLine.Action.START,
                longForm.action());
        assertEquals(Paths.get("two.yml"), longForm.path());
    }

    @Test
    public void generateConfigDefaultsToApplicationYaml() {
        StartupCommandLine command =
                StartupCommandLine.parse(
                        new String[] {"--generate-config"});

        assertEquals(
                StartupCommandLine.Action.GENERATE_CONFIG,
                command.action());
        assertEquals(
                Paths.get("application.yml"),
                command.path());
    }

    @Test
    public void generateConfigAcceptsSeparatedAndEqualsPath() {
        StartupCommandLine separated =
                StartupCommandLine.parse(
                        new String[] {
                            "--generate-config",
                            "generated/application.yml"
                        });
        StartupCommandLine equalsForm =
                StartupCommandLine.parse(
                        new String[] {
                            "--generate-config=other.yml"
                        });

        assertEquals(
                StartupCommandLine.Action.GENERATE_CONFIG,
                separated.action());
        assertEquals(
                Paths.get("generated/application.yml"),
                separated.path());
        assertEquals(
                StartupCommandLine.Action.GENERATE_CONFIG,
                equalsForm.action());
        assertEquals(
                Paths.get("other.yml"),
                equalsForm.path());
    }

    @Test
    public void parsesHelpAndVersion() {
        assertEquals(
                StartupCommandLine.Action.HELP,
                StartupCommandLine.parse(
                        new String[] {"--help"}).action());
        assertEquals(
                StartupCommandLine.Action.HELP,
                StartupCommandLine.parse(
                        new String[] {"-h"}).action());
        assertEquals(
                StartupCommandLine.Action.VERSION,
                StartupCommandLine.parse(
                        new String[] {"--version"}).action());
        assertEquals(
                StartupCommandLine.Action.VERSION,
                StartupCommandLine.parse(
                        new String[] {"-V"}).action());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownOption() {
        StartupCommandLine.parse(
                new String[] {"--unknown"});
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMixedStartupModes() {
        StartupCommandLine.parse(
                new String[] {
                    "--help",
                    "--config",
                    "application.yml"
                });
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOptionWhereGenerateTargetIsExpected() {
        StartupCommandLine.parse(
                new String[] {
                    "--generate-config",
                    "--help"
                });
    }

    @Test
    public void helpListsAllStartupModes() {
        String help = StartupCommandLine.helpText();

        assertTrue(help.contains(
                "--config <application.yml>"));
        assertTrue(help.contains(
                "--generate-config [<application.yml>]"));
        assertTrue(help.contains("--version"));
        assertTrue(help.contains("--help"));
        assertTrue(help.contains(
                "No arguments run the packaged-artifact smoke check"));
        assertTrue(help.contains(
                "single positional <application.yml>"));
    }
}
