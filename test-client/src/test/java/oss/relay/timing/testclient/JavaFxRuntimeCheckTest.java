package oss.relay.timing.testclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class JavaFxRuntimeCheckTest {
    @Test
    void mavenResolvedJavaFxModulesHaveCompatibleVersions() {
        assertDoesNotThrow(JavaFxRuntimeCheck::verify);
    }
}
