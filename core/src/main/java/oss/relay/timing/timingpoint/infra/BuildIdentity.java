package oss.relay.timing.timingpoint.infra;

/**
 * Immutable identity and deterministic provenance of one built application artifact.
 *
 * <p>The embedded fields identify the software/source context without wall-clock build time,
 * CI run identifiers or actor/user metadata. Repeating a build with the same provenance inputs
 * therefore does not change this identity merely because the build ran again.</p>
 */
public final class BuildIdentity {
    public static final String FIRST_API_VERSION = "1";

    private final String application;
    private final String version;
    private final String revision;
    private final String sourceRef;
    private final String buildOrigin;
    private final boolean dirty;
    private final String apiVersion;

    public BuildIdentity(
            String application,
            String version,
            String revision,
            String sourceRef,
            String buildOrigin,
            boolean dirty,
            String apiVersion) {
        this.application = requireText(application, "application");
        this.version = requireText(version, "version");
        this.revision = requireText(revision, "revision");
        this.sourceRef = requireText(sourceRef, "sourceRef");
        this.buildOrigin = requireText(buildOrigin, "buildOrigin");
        this.dirty = dirty;
        this.apiVersion = requireText(apiVersion, "apiVersion");
    }

    public static BuildIdentity firstApiVersion(
            String application,
            String version,
            String revision,
            String sourceRef,
            String buildOrigin,
            boolean dirty) {
        return new BuildIdentity(
                application,
                version,
                revision,
                sourceRef,
                buildOrigin,
                dirty,
                FIRST_API_VERSION);
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

    public String apiVersion() {
        return apiVersion;
    }

    public String displayName() {
        return application + " " + version;
    }

    public String provenance() {
        return "revision=" + revision
                + " sourceRef=" + sourceRef
                + " buildOrigin=" + buildOrigin
                + " dirty=" + dirty;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
