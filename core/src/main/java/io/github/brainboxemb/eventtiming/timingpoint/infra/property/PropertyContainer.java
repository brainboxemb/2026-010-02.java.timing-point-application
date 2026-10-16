package io.github.brainboxemb.eventtiming.timingpoint.infra.property;

import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owns related properties and publishes one grouped change event.
 *
 * <p>The owner changes one or more Property values first and then calls
 * {@link #update()}. The container emits one event containing all properties
 * changed since the previous update.</p>
 */
public final class PropertyContainer {
    private final List<Property<?>> properties = new ArrayList<Property<?>>();
    private final Event<List<Property<?>>> changedEvent = new Event<List<Property<?>>>();

    /** One change source containing all properties changed by the last update. */
    public final EventSource<List<Property<?>>> CHANGED = changedEvent;

    /** Creates one typed property owned by this container. */
    public synchronized <T> Property<T> property(
            String name,
            T initialValue) {

        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("property name must not be blank");
        }

        String normalized = name.trim();
        for (Property<?> property : properties) {
            if (property.name.equals(normalized)) {
                throw new IllegalArgumentException("duplicate property " + normalized);
            }
        }

        Property<T> property =
                new Property<T>(
                        this,
                        normalized,
                        initialValue);
        properties.add(property);
        return property;
    }

    /** Publishes one event when one or more owned properties changed. */
    public void update() {

        List<Property<?>> changed = new ArrayList<Property<?>>();
        synchronized (this) {
            for (Property<?> property : properties) {
                if (property.update()) {
                    changed.add(property);
                }
            }
        }

        if (changed.isEmpty()) {
            return;
        }

        changedEvent.emit(
                Collections.unmodifiableList(
                        new ArrayList<Property<?>>(changed)));
    }
}
