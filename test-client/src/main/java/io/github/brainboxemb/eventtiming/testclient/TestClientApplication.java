package io.github.brainboxemb.eventtiming.testclient;

import javafx.application.Application;

/**
 * Stable plain-Java entry point for the SI-02 Engineering Desktop Client.
 *
 * <p>This class deliberately does not extend {@link Application}. IDEs and Maven
 * may invoke this main class directly without triggering the JVM's special
 * JavaFX launcher path.</p>
 */
public final class TestClientApplication {
    private TestClientApplication() {
    }

    public static void main(String[] args) {
        // Fail before creating controls/dock areas if NetBeans or an IDE
        // supplied old JavaFX modules beside the declared Maven release.
        JavaFxRuntimeCheck.verify();
        Application.launch(TestClientFxApplication.class, args);
    }
}
