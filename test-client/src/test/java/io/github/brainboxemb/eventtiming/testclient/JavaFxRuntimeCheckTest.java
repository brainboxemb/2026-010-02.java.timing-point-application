package io.github.brainboxemb.eventtiming.testclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class JavaFxRuntimeCheckTest {
    @Test
    void mavenResolvedJavaFxModulesHaveCompatibleVersions() {
        assertDoesNotThrow(JavaFxRuntimeCheck::verify);
    }
}
