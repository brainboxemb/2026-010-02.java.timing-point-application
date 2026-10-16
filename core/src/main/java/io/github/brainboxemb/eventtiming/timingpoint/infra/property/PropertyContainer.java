package io.github.brainboxemb.eventtiming.timingpoint.infra.property;

import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Tracks related current values and publishes one grouped change event.
 *
 * <p>The owner changes its normal fields first and then calls {@link #update()}.
 * The container compares the tracked values with its previous snapshot and emits
 * one event containing every property that changed.</p>
 */
public final class PropertyContainer {
    private final List<Property<?>> properties = new ArrayList<Property<?>>();
    private final Event<List<Property<?>>> changedEvent = new Event<List<Property<?>>>();

    /** One change source containing all properties changed by the last update. */
    public final EventSource<List<Property<?>>> CHANGED = changedEvent;

    /**
     * Adds one named value to this container and returns the container for chaining.
     */
    public synchronized <T> PropertyContainer track(
            String name,
            Supplier<T> source) {

        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("property name must not be blank");
        }
        if (source == null) {
            throw new IllegalArgumentException("property source must not be null");
        }

        String normalized = name.trim();
        for (Property<?> property : properties) {
            if (property.name.equals(normalized)) {
                throw new IllegalArgumentException("duplicate property " + normalized);
            }
        }

        properties.add(new Property<T>(normalized, source));
        return this;
    }

    /**
     * Publishes one event when one or more tracked values changed.
     *
     * <p>The event is emitted after the snapshot has been updated and outside the
     * container lock.</p>
     */
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
