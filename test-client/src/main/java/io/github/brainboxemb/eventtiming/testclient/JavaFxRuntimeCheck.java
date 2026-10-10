package io.github.brainboxemb.eventtiming.testclient;

import javafx.beans.value.ObservableValue;
import javafx.scene.control.TableView;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.security.CodeSource;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/**
 * Detect mixed JavaFX modules before any JavaFX controls or BentoFX docks are
 * created. JavaFX internals are not a compatible cross-version API.
 */
final class JavaFxRuntimeCheck {
    private static final String BUILD_IDENTITY =
            "/event-timing-test-client-build.properties";

    private JavaFxRuntimeCheck() {
    }

    static void verify() {
        String expected = expectedVersion();
        List<RuntimePart> actual = List.of(
                runtimePart("javafx.base", ObservableValue.class),
                runtimePart("javafx.graphics", Stage.class),
                runtimePart("javafx.controls", TableView.class));

        boolean incompatible = false;
        for (RuntimePart part : actual) {
            if (part.version() != null && !part.version().equals(expected)) {
                incompatible = true;
            }
        }
        try {
            // BentoFX 0.16 and JavaFX Controls 21 require this method. Older
            // javafx.base builds raise NoSuchMethodError only much later in
            // unrelated TableView selection and docking code.
            ObservableValue.class.getMethod("map", Function.class);
        } catch (NoSuchMethodException e) {
            incompatible = true;
        }
        if (!incompatible) return;

        StringBuilder message = new StringBuilder(
                "Incompatible JavaFX runtime: Engineering Client requires JavaFX ")
                .append(expected).append(" in every JavaFX module.");
        for (RuntimePart part : actual) {
            message.append(System.lineSeparator()).append("  ")
                    .append(part.name()).append(": ")
                    .append(part.version() == null ? "unknown version" : part.version())
                    .append(" from ").append(part.origin());
        }
        message.append(System.lineSeparator())
                .append("Remove old JavaFX SDK/JARs from the IDE run classpath/module path. ")
                .append("Use JDK 21 and run from the repository root with:")
                .append(System.lineSeparator())
                .append("  .\\mvnw.cmd -f test-client\\pom.xml clean javafx:run")
                .append(System.lineSeparator())
                .append("or start the self-contained packaged Windows app-image.");
        throw new IllegalStateException(message.toString());
    }

    private static String expectedVersion() {
        Properties values = new Properties();
        try (InputStream resource = JavaFxRuntimeCheck.class
                .getResourceAsStream(BUILD_IDENTITY)) {
            if (resource == null) {
                throw new IllegalStateException("Missing build identity: " + BUILD_IDENTITY);
            }
            values.load(resource);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read JavaFX build version", ex);
        }
        String value = values.getProperty("javafx.version");
        if (value == null || value.isBlank() || value.contains("${")) {
            throw new IllegalStateException("Missing filtered javafx.version in build identity");
        }
        return value;
    }

    private static RuntimePart runtimePart(String name, Class<?> type) {
        String version = null;
        Module module = type.getModule();
        if (module != null && module.isNamed()
                && module.getDescriptor() != null) {
            version = module.getDescriptor().version()
                    .map(Object::toString).orElse(null);
        }
        if (version == null) version = type.getPackage().getImplementationVersion();
        String origin = "unknown origin";
        try {
            CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                origin = codeSource.getLocation().toExternalForm();
                if (version == null) {
                    // JARs on the classpath can be unnamed modules without
                    // version metadata, but their filenames retain the release.
                    String release = origin.replaceAll(
                            ".*javafx-(?:base|graphics|controls)-([0-9]+(?:\\.[0-9]+)*).*",
                            "$1");
                    if (!release.equals(origin)) version = release;
                }
            }
        } catch (SecurityException ignored) {
            // Version from the module descriptor remains available.
        }
        return new RuntimePart(name, version, origin);
    }

    private record RuntimePart(String name, String version, String origin) {
    }
}
