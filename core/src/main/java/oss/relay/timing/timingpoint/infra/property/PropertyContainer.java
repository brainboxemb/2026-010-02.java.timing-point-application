package oss.relay.timing.timingpoint.infra.property;

import oss.relay.timing.timingpoint.platform.events.Event;
import oss.relay.timing.timingpoint.platform.events.EventSource;

import java.util.ArrayList;
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
    private final Event<PropertyUpdate> changedEvent = new Event<PropertyUpdate>();

    /** One change source containing all properties changed by the last update. */
    public final EventSource<PropertyUpdate> CHANGED = changedEvent;

    /** Creates one typed property owned by this container. */
    public synchronized <T> Property<T> property(
            String name,
            T initialValue) {

        return property(PropertyKey.<T>of(name), initialValue);
    }

    /** Creates one typed property with an identity that may be shared with consumers. */
    public synchronized <T> Property<T> property(
            PropertyKey<T> key,
            T initialValue) {

        if (key == null) {
            throw new IllegalArgumentException("property key must not be null");
        }

        for (Property<?> property : properties) {
            if (property.name.equals(key.name)) {
                throw new IllegalArgumentException("duplicate property " + key.name);
            }
        }

        Property<T> property =
                new Property<T>(
                        this,
                        key,
                        initialValue);
        properties.add(property);
        return property;
    }

    /** Accepts all current property values as the comparison baseline without an event. */
    public synchronized void sync() {
        for (Property<?> property : properties) {
            property.sync();
        }
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

        if (!changed.isEmpty()) {
            changedEvent.emit(new PropertyUpdate(changed));
        }
    }
}
