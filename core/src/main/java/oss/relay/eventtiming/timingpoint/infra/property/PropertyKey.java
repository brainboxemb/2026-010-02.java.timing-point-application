package oss.relay.eventtiming.timingpoint.infra.property;

/**
 * Typed immutable identity of one property.
 *
 * <p>The key may be shared with consumers while the mutable {@link Property}
 * handle remains private to its owner.</p>
 *
 * @param <T> property value type
 */
public final class PropertyKey<T> {
    public final String name;

    private PropertyKey(String name) {
        this.name = name;
    }

    /** Creates one typed property key. */
    public static <T> PropertyKey<T> of(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("property name must not be blank");
        }
        return new PropertyKey<T>(name.trim());
    }
}
