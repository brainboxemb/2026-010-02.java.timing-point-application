package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

/**
 * Provider-decoded/decrypted RFID tag identity.
 *
 * <p>Provider bytes, framing, encryption and decryption stay behind the
 * antenna/provider boundary. This value is the identity exposed to generic tag
 * processing.</p>
 */
public final class DecryptedTagId {
    private final String value;

    public DecryptedTagId(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "DecryptedTagId must not be blank");
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
        if (!(other instanceof DecryptedTagId)) {
            return false;
        }
        DecryptedTagId that = (DecryptedTagId) other;
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
