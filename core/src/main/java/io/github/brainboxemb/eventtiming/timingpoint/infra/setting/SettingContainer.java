package io.github.brainboxemb.eventtiming.timingpoint.infra.setting;

import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Owns a group of typed settings and publishes atomic requested-value changes.
 *
 * <p>Concrete settings groups register their settings during construction. A caller
 * uses {@link #update(Consumer)} to stage one or more requested values. The staged
 * values are committed together only after the callback returns normally. One
 * {@link SettingUpdate} is emitted after a commit that changed at least one requested
 * value.</p>
 *
 * <p>The contained {@link Setting} instances retain requested/applied/pending/retry
 * semantics. This container owns only grouping, atomic publication and group revision.</p>
 */
public class SettingContainer {
    private final List<Entry<?>> entries = new ArrayList<Entry<?>>();
    private final Event<SettingUpdate> changedEvent = new Event<SettingUpdate>();

    /** Grouped committed requested-value changes. */
    public final EventSource<SettingUpdate> CHANGED = changedEvent;

    private long revision;
    private boolean updating;

    /** Mutable staged values scoped to one update callback. */
    public final class Edit {
        private final Map<SettingKey<?>, Object> values =
                new IdentityHashMap<SettingKey<?>, Object>();

        private Edit() {
        }

        /** Stages one requested value for this atomic update. */
        public <T> void set(
                SettingKey<T> key,
                T value) {

            entry(key);
            values.put(key, value);
        }
    }

    /** Registers one setting owned by this group. Intended for concrete group construction. */
    protected synchronized <T> Setting<T> setting(
            SettingKey<T> key,
            T initialValue) {

        if (key == null) {
            throw new IllegalArgumentException("setting key must not be null");
        }

        for (Entry<?> entry : entries) {
            if (entry.key.name.equals(key.name)) {
                throw new IllegalArgumentException("duplicate setting " + key.name);
            }
        }

        Setting<T> setting = new Setting<T>(initialValue);
        entries.add(new Entry<T>(key, setting));
        return setting;
    }

    /** Returns the current requested value for one registered setting. */
    public synchronized <T> T requestedValue(SettingKey<T> key) {
        return entry(key).setting.requestedValue();
    }

    /** Returns the current applied value for one registered setting. */
    public synchronized <T> T appliedValue(SettingKey<T> key) {
        return entry(key).setting.appliedValue();
    }

    /** Returns whether requested and applied values currently differ. */
    public synchronized boolean changePending(SettingKey<?> key) {
        return entryUntyped(key).setting.changePending();
    }

    /** Monotonic revision of committed effective group updates. */
    public synchronized long revision() {
        return revision;
    }

    /**
     * Atomically commits requested values staged by the supplied callback.
     *
     * <p>Nested updates are rejected. An exception from the callback commits
     * nothing and is propagated unchanged. Listeners run after the group lock is
     * released.</p>
     */
    public void update(Consumer<Edit> transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("transaction must not be null");
        }

        SettingUpdate committedUpdate = null;
        synchronized (this) {
            if (updating) {
                throw new IllegalStateException("nested Settings update is not allowed");
            }

            updating = true;
            try {
                Edit edit = new Edit();
                transaction.accept(edit);

                List<SettingKey<?>> changed = commit(edit);
                if (!changed.isEmpty()) {
                    committedUpdate =
                            new SettingUpdate(
                                    ++revision,
                                    changed);
                }
            } finally {
                updating = false;
            }
        }

        if (committedUpdate != null) {
            changedEvent.emit(committedUpdate);
        }
    }

    private List<SettingKey<?>> commit(Edit edit) {
        List<SettingKey<?>> changed = new ArrayList<SettingKey<?>>();

        for (Entry<?> entry : entries) {
            if (!edit.values.containsKey(entry.key)) {
                continue;
            }
            commitEntry(entry, edit.values.get(entry.key), changed);
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private <T> void commitEntry(
            Entry<?> rawEntry,
            Object value,
            List<SettingKey<?>> changed) {

        Entry<T> entry = (Entry<T>) rawEntry;
        if (entry.setting.requestIfChanged((T) value)) {
            changed.add(entry.key);
        }
    }

    private <T> Entry<T> entry(SettingKey<T> key) {
        if (key == null) {
            throw new IllegalArgumentException("setting key must not be null");
        }

        for (Entry<?> entry : entries) {
            if (entry.key == key) {
                return typedEntry(entry);
            }
        }
        throw new IllegalArgumentException("setting is not registered: " + key.name);
    }

    private Entry<?> entryUntyped(SettingKey<?> key) {
        if (key == null) {
            throw new IllegalArgumentException("setting key must not be null");
        }

        for (Entry<?> entry : entries) {
            if (entry.key == key) {
                return entry;
            }
        }
        throw new IllegalArgumentException("setting is not registered: " + key.name);
    }

    @SuppressWarnings("unchecked")
    private static <T> Entry<T> typedEntry(Entry<?> entry) {
        return (Entry<T>) entry;
    }

    private static final class Entry<T> {
        private final SettingKey<T> key;
        private final Setting<T> setting;

        private Entry(
                SettingKey<T> key,
                Setting<T> setting) {
            this.key = key;
            this.setting = setting;
        }
    }
}
