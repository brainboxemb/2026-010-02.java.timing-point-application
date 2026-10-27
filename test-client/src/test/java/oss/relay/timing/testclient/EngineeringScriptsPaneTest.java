package oss.relay.timing.testclient;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class EngineeringScriptsPaneTest {
    @TempDir
    Path temporaryDirectory;

    @Test void localLuaCanRunWhileDisconnectedOrSimulationUnavailable() {
        assertTrue(EngineeringScriptsPane.canRun(false, false));
        assertTrue(EngineeringScriptsPane.canRun(true, false));
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

    @Test void concurrentGuiBatchIsTheOnlyGenericRunGate() {
        assertTrue(EngineeringScriptsPane.canRun(false, false));
        assertFalse(EngineeringScriptsPane.canRun(false, true));
        assertFalse(EngineeringScriptsPane.canRun(true, true));
    }
}
