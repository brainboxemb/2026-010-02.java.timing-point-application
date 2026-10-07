package io.github.brainboxemb.eventtiming.eventdata;

/**
 * Semantic team identity used by event/reference data.
 *
 * <p>The value is intentionally profile-neutral. Concrete EventData profiles
 * define any formatting, range or translation rules.</p>
 */
public final class TeamId {
    private final String value;

    public TeamId(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "TeamId must not be blank");
        }
        this.value = value.trim();
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TeamId)) {
            return false;
        }
        TeamId that = (TeamId) other;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
