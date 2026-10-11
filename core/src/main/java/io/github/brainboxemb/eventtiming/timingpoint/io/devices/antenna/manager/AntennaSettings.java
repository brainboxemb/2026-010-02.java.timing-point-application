package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.Setting;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import java.util.function.Consumer;

/**
 * Shared desired inventory state. update() atomically publishes a complete
 * transaction and returns without waiting for physical antenna execution.
 * An unchanged transaction emits no notification; failed edits commit nothing.
 */
public final class AntennaSettings {
    private final Setting<Boolean> setting = new Setting<Boolean>(Boolean.FALSE);
    private final Event<Long> changed = new Event<Long>();
    private long revision;

    /** Temporary transaction editor; never retain it beyond update(). */
    public static final class Edit {
        private boolean enabled;
        private Edit(boolean initial) { enabled = initial; }
        public void inventoryEnabled(boolean value) { enabled = value; }
    }

    public void update(Consumer<Edit> transaction) {
        if (transaction == null) { throw new IllegalArgumentException("transaction must not be null"); }
        Long changedRevision = null;
        synchronized (this) {
            Edit edit = new Edit(setting.requestedValue().booleanValue());
            transaction.accept(edit);
            if (setting.requestIfChanged(Boolean.valueOf(edit.enabled))) {
                changedRevision = Long.valueOf(++revision);
            }
        }
        // Synchronous event delivery happens outside the settings lock.
        if (changedRevision != null) { changed.emit(changedRevision); }
    }

    public synchronized boolean inventoryEnabled() { return setting.requestedValue().booleanValue(); }
    public synchronized long revision() { return revision; }
    public EventSource<Long> changedEvent() { return changed; }

    // Only hardware-side implementation in this package may mark applied.
    Setting<Boolean> inventorySetting() { return setting; }
}
