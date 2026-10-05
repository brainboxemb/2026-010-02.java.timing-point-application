package io.github.brainboxemb.eventtiming.timingpoint.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the complete example IF-11 configuration packaged with the application.
 *
 * <p>The resource is copied from the repository's canonical
 * {@code config/application.yml} during the Maven build. The generated example
 * therefore belongs to the same application version as the JAR that writes it.</p>
 */
final class ExampleConfiguration {
    static final String RESOURCE = "/example-config/application.yml";

    private ExampleConfiguration() {
    }

    static void write(Path target) throws IOException {
        if (target == null) {
            throw new IllegalArgumentException(
                    "target must not be null");
        }

        Path output = target.toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (InputStream input =
                ExampleConfiguration.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Packaged example configuration is missing: "
                                + RESOURCE);
            }

            /*
             * Files.copy without REPLACE_EXISTING is deliberate. A command that
             * exists to create a safe starting point must never silently destroy
             * an operator's current configuration.
             */
            Files.copy(input, output);
        }
    }
}
