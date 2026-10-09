package io.github.brainboxemb.eventtiming.testclient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Build identity embedded in the standalone SI-02 Engineering Desktop Client. */
public final class TestClientBuildIdentity {
    private static final String RESOURCE = "/event-timing-test-client-build.properties";

    private final String application;
    private final String version;
    private final String revision;
    private final String sourceRef;
    private final String buildOrigin;
    private final boolean dirty;

    private TestClientBuildIdentity(
            String application,
            String version,
            String revision,
            String sourceRef,
            String buildOrigin,
            boolean dirty) {
        this.application = required(application, "application");
        this.version = required(version, "version");
        this.revision = required(revision, "revision");
        this.sourceRef = required(sourceRef, "sourceRef");
        this.buildOrigin = required(buildOrigin, "buildOrigin");
        this.dirty = dirty;
    }

    public static TestClientBuildIdentity embedded() {
        Properties properties = new Properties();
        try (InputStream input = TestClientBuildIdentity.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing Engineering Client build identity: " + RESOURCE);
            }
            properties.load(input);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load Engineering Client build identity", ex);
        }

        return new TestClientBuildIdentity(
                properties.getProperty("application.name"),
                properties.getProperty("application.version"),
                properties.getProperty("build.revision"),
                properties.getProperty("build.sourceRef"),
                properties.getProperty("build.origin"),
                parseDirty(properties.getProperty("build.dirty")));
    }

    public String application() {
        return application;
    }

    public String version() {
        return version;
    }

    public String revision() {
        return revision;
    }

    public String sourceRef() {
        return sourceRef;
    }

    public String buildOrigin() {
        return buildOrigin;
    }

    public boolean dirty() {
        return dirty;
    }

    private static boolean parseDirty(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalStateException("Invalid Engineering Client build.dirty value: " + value);
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Missing Engineering Client build field: " + name);
        }
        return value;
    }
}
