package io.github.brainboxemb.eventtiming.timingpoint.infra.property;

import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Owns related current properties and publishes one event for each committed update.
 *
 * <p>An owner stages values with {@link Update#set(Property, Object)}, commits them
 * together, finishes any owner-specific critical-section work, and then calls
 * {@link Update#publish()}. Subscribers therefore never need a separate event object
 * per property. The event identifies every property changed by that update.</p>
 */
public final class PropertyContainer implements EventSource<PropertyContainer.Change> {
    private final Event<Change> changedEvent = new Event<Change>();
    private final List<Property<?>> properties = new ArrayList<Property<?>>();

    /** Creates one property owned by this container. Property names are unique per container. */
    public synchronized <T> Property<T> property(String name, T initialValue) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("property name must not be blank");
        }
        String normalized = name.trim();
        for (Property<?> property : properties) {
            if (property.name.equals(normalized)) {
                throw new IllegalArgumentException("duplicate property " + normalized);
            }
        }

        Property<T> property = new Property<T>(this, normalized, initialValue);
        properties.add(property);
        return property;
    }

    /** Starts one staged update. The returned object is single-use. */
    public Update update() {
        return new Update(this);
    }

    /** Subscribes to committed changes from this container. */
    @Override
    public boolean subscribe(Consumer<Change> listener) {
        return changedEvent.subscribe(listener);
    }

    /** Immutable description of the properties changed by one committed update. */
    public static final class Change {
        public final List<Property<?>> properties;

        private Change(List<Property<?>> properties) {
            this.properties = Collections.unmodifiableList(
                    new ArrayList<Property<?>>(properties));
        }

        /** Returns true when the named property changed in this update. */
        public boolean contains(String propertyName) {
            for (Property<?> property : properties) {
                if (property.name.equals(propertyName)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Stages one or more values, commits them together, then publishes one container event.
     *
     * <p>Commit and publication are deliberately separate. A component may need to keep
     * its own short lock after the values become current but before downstream listeners
     * are called. Values are not visible until {@link #commit()}.</p>
     */
    public static final class Update {
        private final PropertyContainer owner;
        private final List<ProposedValue<?>> proposed = new ArrayList<ProposedValue<?>>();
        private List<Property<?>> changed;
        private boolean committed;
        private boolean published;

        private Update(PropertyContainer owner) {
            this.owner = owner;
        }

        /** Stages the final value for one property in this update. */
        public <T> Update set(Property<T> property, T value) {
            if (committed) {
                throw new IllegalStateException("property update is already committed");
            }
            owner.requireOwned(property);

            for (int index = 0; index < proposed.size(); index++) {
                ProposedValue<?> existing = proposed.get(index);
                if (existing.property == property) {
                    proposed.set(index, new ProposedValue<T>(property, value));
                    return this;
                }
            }
            proposed.add(new ProposedValue<T>(property, value));
            return this;
        }

        /** Commits all staged values together without invoking subscribers. */
        public void commit() {
            if (committed) {
                throw new IllegalStateException("property update is already committed");
            }

            List<Property<?>> changedProperties = new ArrayList<Property<?>>();
            synchronized (owner) {
                for (ProposedValue<?> value : proposed) {
                    if (value.apply()) {
                        changedProperties.add(value.property);
                    }
                }
            }
            changed = changedProperties;
            committed = true;
        }

        /** Publishes one change event after commit; unchanged updates emit nothing. */
        public void publish() {
            if (!committed) {
                throw new IllegalStateException("property update must be committed before publish");
            }
            if (published) {
                throw new IllegalStateException("property update is already published");
            }
            published = true;

            if (!changed.isEmpty()) {
                owner.changedEvent.emit(new Change(changed));
            }
        }
    }

    /** Rejects a property that belongs to another container. */
    private void requireOwned(Property<?> property) {
        if (property == null) {
            throw new IllegalArgumentException("property must not be null");
        }
        if (property.owner != this) {
            throw new IllegalArgumentException("property belongs to another container");
        }
    }

    /** One staged typed value. */
    private static final class ProposedValue<T> {
        private final Property<T> property;
        private final T value;

        private ProposedValue(Property<T> property, T value) {
            this.property = property;
            this.value = value;
        }

        private boolean apply() {
            if (Objects.equals(property.value(), value)) {
                return false;
            }
            property.assign(value);
            return true;
        }
    }
}
