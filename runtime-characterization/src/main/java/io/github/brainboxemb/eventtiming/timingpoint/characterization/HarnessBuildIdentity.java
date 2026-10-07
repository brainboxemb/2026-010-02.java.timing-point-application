package io.github.brainboxemb.eventtiming.timingpoint.characterization;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Filtered build/source identity retained in every characterization result. */
final class HarnessBuildIdentity {
    private static final String RESOURCE =
            "/runtime-characterization.properties";

    private final String version;
    private final String revision;
    private final String sourceRef;
    private final String origin;
    private final boolean dirty;

    private HarnessBuildIdentity(
            String version,
            String revision,
            String sourceRef,
            String origin,
            boolean dirty) {
        this.version = require("harness.version", version);
        this.revision = require("build.revision", revision);
        this.sourceRef = require("build.sourceRef", sourceRef);
        this.origin = require("build.origin", origin);
        this.dirty = dirty;
    }

    static HarnessBuildIdentity load() {
        Properties properties =
                new Properties();
        try (InputStream input =
                HarnessBuildIdentity.class.getResourceAsStream(
                        RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing harness build identity " + RESOURCE);
            }
            properties.load(input);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to load harness build identity",
                    ex);
        }

        String dirtyValue =
                properties.getProperty(
                        "build.dirty");
        if (!"true".equalsIgnoreCase(dirtyValue)
                && !"false".equalsIgnoreCase(dirtyValue)) {
            throw new IllegalStateException(
                    "Invalid build.dirty value " + dirtyValue);
        }

        return new HarnessBuildIdentity(
                properties.getProperty("harness.version"),
                properties.getProperty("build.revision"),
                properties.getProperty("build.sourceRef"),
                properties.getProperty("build.origin"),
                Boolean.parseBoolean(dirtyValue));
    }

    private static String require(
            String name,
            String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Missing " + name);
        }
        return value.trim();
    }

    String version() { return version; }
    String revision() { return revision; }
    String sourceRef() { return sourceRef; }
    String origin() { return origin; }
    boolean dirty() { return dirty; }
}
