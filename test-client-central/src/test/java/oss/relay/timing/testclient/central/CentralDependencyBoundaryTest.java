package oss.relay.timing.testclient.central;

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
                    "import java.net.",
                    "java.net.http.",
                    "HttpURLConnection",
                    "URLConnection",
                    "oss.relay.timing.testclient.ApiClient",
                    "oss.relay.timing.testclient.ApiPane",
                    "oss.relay.timing.testclient.TimingPane",
                    "oss.relay.timing.testclient.EngineeringSystemContext",
                    "oss.relay.timing.testclient.HttpTraffic");

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
                            "import oss.relay.timing.testclient.")) {
                        assertFalse(
                                !trimmed.startsWith(
                                        "import oss.relay.timing.testclient.central."),
                                () -> source
                                        + " imports ordinary Engineering Client code: "
                                        + trimmed);
                    }
                }
            }
        }
    }
}
