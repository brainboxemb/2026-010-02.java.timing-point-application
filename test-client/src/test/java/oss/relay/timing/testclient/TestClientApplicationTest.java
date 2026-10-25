package oss.relay.timing.testclient;

import javafx.application.Application;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestClientApplicationTest {
    @Test
    void publicEntryPointIsNotAJavaFxApplicationSubclass() {
        assertFalse(Application.class.isAssignableFrom(TestClientApplication.class));
    }

    @Test
    void javaFxApplicationIsPubliclyConstructible() throws Exception {
        assertTrue(Modifier.isPublic(TestClientFxApplication.class.getModifiers()));
        assertTrue(Modifier.isPublic(
                TestClientFxApplication.class.getConstructor().getModifiers()));
    }
}
