package oss.relay.timing.testclient;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class EngineeringScriptsPaneTest {
    @TempDir
    Path temporaryDirectory;

    @Test void localLuaCanRunWhileSimulationCapabilityIsUnavailable() {
        // Run readiness does not depend on SI-01 TAG_SCENARIO_SIMULATION.
        assertTrue(EngineeringScriptsPane.canRun(true, "A", false));
    }

    @Test
    void sourceCheckoutScriptsDirectoryIsDefault() throws Exception {
        Path repository = temporaryDirectory.resolve("repo");
        Path scripts =
                repository.resolve("test-client").resolve("scripts");
        Files.createDirectories(scripts);

        assertEquals(
                scripts.toAbsolutePath().normalize(),
                EngineeringScriptsPane.defaultScriptsDirectory(
                        repository,
                        null));
    }

    @Test
    void moduleWorkingDirectoryUsesItsScriptsDirectory() throws Exception {
        Path module = temporaryDirectory.resolve("repo").resolve("test-client");
        Path scripts = module.resolve("scripts");
        Files.createDirectories(scripts);

        assertEquals(
                scripts.toAbsolutePath().normalize(),
                EngineeringScriptsPane.defaultScriptsDirectory(
                        module,
                        null));
    }

    @Test
    void packagedScriptsDirectoryTakesPriority() throws Exception {
        Path repository = temporaryDirectory.resolve("repo");
        Files.createDirectories(
                repository.resolve("test-client").resolve("scripts"));

        Path appImage =
                temporaryDirectory.resolve("package")
                        .resolve("EventTimingEngineeringClient");
        Path scripts = appImage.resolve("scripts");
        Files.createDirectories(scripts);
        Path launcher =
                appImage.resolve("EventTimingEngineeringClient.exe");

        assertEquals(
                scripts.toAbsolutePath().normalize(),
                EngineeringScriptsPane.defaultScriptsDirectory(
                        repository,
                        launcher));
    }

    @Test void runRequiresSyncedSelectedNodeAndNoConcurrentGuiBatch() {
        assertFalse(EngineeringScriptsPane.canRun(false, "A", false));
        assertFalse(EngineeringScriptsPane.canRun(true, null, false));
        assertFalse(EngineeringScriptsPane.canRun(true, "", false));
        assertFalse(EngineeringScriptsPane.canRun(true, "A", true));
    }
}
