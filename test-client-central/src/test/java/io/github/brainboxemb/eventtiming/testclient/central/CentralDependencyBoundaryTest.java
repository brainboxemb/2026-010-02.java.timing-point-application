package io.github.brainboxemb.eventtiming.testclient.central;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

final class CentralDependencyBoundaryTest {
    private static final List<String> FORBIDDEN =
            List.of(
                    "java.net.http.",
                    "HttpURLConnection",
                    "URLConnection",
                    "io.github.brainboxemb.eventtiming.testclient.ApiClient",
                    "io.github.brainboxemb.eventtiming.testclient.ApiPane",
                    "io.github.brainboxemb.eventtiming.testclient.TimingPane",
                    "io.github.brainboxemb.eventtiming.testclient.EngineeringSystemContext",
                    "io.github.brainboxemb.eventtiming.testclient.HttpTraffic");

    @Test
    void productionSourcesCannotUseIf03HttpClientOrJdkHttp()
            throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            for (Path source : sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                String text = Files.readString(source);
                for (String forbidden : FORBIDDEN) {
                    assertFalse(
                            text.contains(forbidden),
                            () -> source
                                    + " contains forbidden Central dependency "
                                    + forbidden);
                }
                for (String line : text.split("\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith(
                            "import io.github.brainboxemb.eventtiming.testclient.")) {
                        assertFalse(
                                !trimmed.startsWith(
                                        "import io.github.brainboxemb.eventtiming.testclient.central."),
                                () -> source
                                        + " imports ordinary Engineering Client code: "
                                        + trimmed);
                    }
                }
            }
        }
    }
}
