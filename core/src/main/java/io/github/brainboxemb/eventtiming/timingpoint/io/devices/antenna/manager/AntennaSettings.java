package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.Setting;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.util.function.Consumer;

/**
 * Shared desired inventory state for one AntennaManager.
 *
 * <p>{@link #update(Consumer)} runs the caller's edit as one atomic transaction.
 * Readers never observe an unfinished edit. A successful changed value emits one
 * post-commit notification; an unchanged value or failed edit emits nothing.
 * Publication does not wait for hardware. The manager owns reconciliation,
 * physical execution and applied state.</p>
 */
public final class AntennaSettings {
    private final Setting<Boolean> inventoryEnabledSetting =
            new Setting<Boolean>(Boolean.FALSE);
    private final Event<Long> changedEvent = new Event<Long>();

    private long revision;
    private boolean updating;

    /**
     * Mutable proposed values scoped to a single update callback.
     *
     * <p>Do not retain or use this editor after the callback returns.</p>
     */
    public static final class Edit {
        private boolean inventoryEnabled;

        /** Creates one transaction-scoped edit initialized from current requested state. */
        private Edit(boolean inventoryEnabled) {
            this.inventoryEnabled = inventoryEnabled;
        }

        /** Sets the proposed desired inventory state for this update transaction. */
        public void inventoryEnabled(boolean enabled) {
            inventoryEnabled = enabled;
        }
    }

    /**
     * Atomically publishes the desired values edited by {@code transaction}.
     *
     * <p>The callback must be short and nonblocking. Recursively calling update
     * on the same object is not permitted. A callback exception rolls back the
     * proposed changes. Listeners are invoked after publication, outside the
     * settings lock, and should only wake their component's control loop.</p>
     */
    public void update(Consumer<Edit> transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("transaction must not be null");
        }

        Long changedRevision = null;
        synchronized (this) {
            if (updating) {
                throw new IllegalStateException("nested Settings update is not allowed");
            }

            updating = true;
            try {
                Edit edit = new Edit(inventoryEnabledSetting.requestedValue());
                transaction.accept(edit);

                if (inventoryEnabledSetting.requestIfChanged(edit.inventoryEnabled)) {
                    changedRevision = Long.valueOf(++revision);
                }
            } finally {
                updating = false;
            }
        }

        if (changedRevision != null) {
            changedEvent.emit(changedRevision);
        }
    }

    /** The last committed desired inventory state. */
    public synchronized boolean inventoryEnabled() {
        return inventoryEnabledSetting.requestedValue();
    }

    /** Monotonic revision of committed, effective settings changes. */
    public synchronized long revision() {
        return revision;
    }

    /** Post-commit notification for owner-side reconciliation. */
    public EventSource<Long> changedEvent() {
        return changedEvent;
    }

    /** Device-side state tracking, not part of the settings writer interface. */
    Setting<Boolean> inventorySetting() {
        return inventoryEnabledSetting;
    }
}
