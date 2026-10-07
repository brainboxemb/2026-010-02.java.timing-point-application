package io.github.brainboxemb.eventtiming.timingpoint.characterization;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Embedded source/build identity of the engineering harness artifact. */
final class CharacterizationBuildInfo {
    private static final String RESOURCE =
            "/runtime-characterization-build.properties";

    private final String version;
    private final String revision;
    private final String sourceRef;
    private final String buildOrigin;
    private final boolean dirty;

    private CharacterizationBuildInfo(
            String version,
            String revision,
            String sourceRef,
            String buildOrigin,
            boolean dirty) {
        this.version = requireText(version, "version");
        this.revision = requireText(revision, "revision");
        this.sourceRef = requireText(sourceRef, "sourceRef");
        this.buildOrigin = requireText(buildOrigin, "buildOrigin");
        this.dirty = dirty;
    }

    static CharacterizationBuildInfo load() {
        Properties properties = new Properties();
        try (InputStream input =
                CharacterizationBuildInfo.class.getResourceAsStream(
                        RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing harness build identity " + RESOURCE);
            }
            properties.load(input);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to load harness build identity " + RESOURCE,
                    ex);
        }

        return new CharacterizationBuildInfo(
                properties.getProperty("harness.version"),
                properties.getProperty("build.revision"),
                properties.getProperty("build.sourceRef"),
                properties.getProperty("build.origin"),
                Boolean.parseBoolean(
                        properties.getProperty("build.dirty")));
    }

    private static String requireText(
            String value,
            String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Harness build " + field + " must not be blank");
        }
        return value.trim();
    }

    String version() { return version; }
    String revision() { return revision; }
    String sourceRef() { return sourceRef; }
    String buildOrigin() { return buildOrigin; }
    boolean dirty() { return dirty; }
}
