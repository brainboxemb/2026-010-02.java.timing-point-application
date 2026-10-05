package io.github.brainboxemb.eventtiming.timingpoint.app;

import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.YamlLoader;

import java.io.ByteArrayOutputStream;
import java.io.FileAlreadyExistsException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ExampleConfigurationTest {
    @Rule
    public final TemporaryFolder temporaryFolder =
            new TemporaryFolder();

    @Test
    public void generatedExampleMatchesPackagedSourceAndLoads()
            throws Exception {
        Path output = temporaryFolder
                .getRoot()
                .toPath()
                .resolve("nested")
                .resolve("application.yml");

        ExampleConfiguration.write(output);

        assertArrayEquals(
                packagedExample(),
                Files.readAllBytes(output));

        Config config = YamlLoader.load(output);
        assertEquals(
                "TN-01",
                config.timingNodeId().value());
        assertNotNull(config.timingDataPath());
        assertNotNull(config.presentation().remoteShell());
        assertNotNull(config.presentation().api());
        assertNotNull(config.logging());
        assertNotNull(config.loggingServer());
    }

    @Test(expected = FileAlreadyExistsException.class)
    public void generationDoesNotOverwriteExistingFile()
            throws Exception {
        Path output = temporaryFolder
                .newFile("application.yml")
                .toPath();

        ExampleConfiguration.write(output);
    }

    private static byte[] packagedExample()
            throws Exception {
        try (InputStream input =
                ExampleConfigurationTest.class
                        .getResourceAsStream(
                                ExampleConfiguration.RESOURCE)) {
            assertNotNull(
                    "Maven must package the canonical example configuration",
                    input);
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
