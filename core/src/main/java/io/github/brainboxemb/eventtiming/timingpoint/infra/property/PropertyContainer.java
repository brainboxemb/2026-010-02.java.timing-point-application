package io.github.brainboxemb.eventtiming.timingpoint.infra.property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns related current properties and notifies subscribers when a property changes.
 *
 * <p>An owner stages one or more values, commits them together and publishes only
 * after the complete update is current. Subscribers attach directly to this container
 * and receive the changed {@link Property}.</p>
 */
public final class PropertyContainer {
    private static final Logger LOG = LoggerFactory.getLogger(PropertyContainer.class);

    private final List<Property<?>> properties = new ArrayList<Property<?>>();
    private volatile List<Consumer<Property<?>>> subscribers =
            Collections.<Consumer<Property<?>>>emptyList();

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

    /**
     * Subscribes to property changes from this container.
     *
     * <p>Subscribers run synchronously after the complete update has been committed.
     * The same subscriber is added at most once according to equals().</p>
     */
    public synchronized boolean subscribe(Consumer<Property<?>> subscriber) {
        if (subscriber == null) {
            throw new IllegalArgumentException("subscriber must not be null");
        }
        if (subscribers.contains(subscriber)) {
            return false;
        }

        List<Consumer<Property<?>>> updated =
                new ArrayList<Consumer<Property<?>>>(subscribers);
        updated.add(subscriber);
        subscribers = Collections.unmodifiableList(updated);
        return true;
    }

    /** Publishes one already committed property change. */
    private void publish(Property<?> property) {
        for (Consumer<Property<?>> subscriber : subscribers) {
            try {
                subscriber.accept(property);
            } catch (RuntimeException ex) {
                LOG.warn(
                        "Property subscriber {} failed while handling {}",
                        subscriber.getClass().getName(),
                        property.name,
                        ex);
            }
        }
    }

    /**
     * Stages one or more values, commits them together, then publishes changed properties.
     *
     * <p>Commit and publication are deliberately separate. A component may need to keep
     * its own short lock after the values become current but before downstream subscribers
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

        /** Publishes each changed property after the complete update has been committed. */
        public void publish() {
            if (!committed) {
                throw new IllegalStateException("property update must be committed before publish");
            }
            if (published) {
                throw new IllegalStateException("property update is already published");
            }

            published = true;
            for (Property<?> property : changed) {
                owner.publish(property);
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
