package io.github.brainboxemb.eventtiming.timingpoint.infra.property;

/**
 * One typed current value owned by a PropertyContainer.
 *
 * <p>The value may be read directly. Changes are staged and committed through the
 * owning container so related properties can be published as one change.</p>
 *
 * @param <T> property value type
 */
public final class Property<T> {
    final PropertyContainer owner;
    public final String name;
    private volatile T value;

    Property(PropertyContainer owner, String name, T initialValue) {
        this.owner = owner;
        this.name = name;
        this.value = initialValue;
    }

    /** Returns the current committed value. */
    public T value() {
        return value;
    }

    /** Replaces the committed value; only PropertyContainer may call this. */
    void assign(T value) {
        this.value = value;
    }
}
