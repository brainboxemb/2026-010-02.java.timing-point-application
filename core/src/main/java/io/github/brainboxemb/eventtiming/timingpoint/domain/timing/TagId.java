package io.github.brainboxemb.eventtiming.timingpoint.domain.timing;

/**
 * RFID/tag source identity resolved before definitive TimingData construction.
 *
 * <p>The public baseline treats the value as opaque. Concrete event/provider
 * configuration owns real formats, categories and mappings.</p>
 */
public final class TagId {
    private final String value;

    public TagId(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("TagId must not be blank");
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
        if (!(other instanceof TagId)) {
            return false;
        }
        TagId that = (TagId) other;
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
