package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

/**
 * Inventory and lifecycle control for a complete antenna set.
 *
 * <p>One TimingSystem requests one desired inventory state; the implementing
 * manager decides power, initialization, individual antenna work and internal
 * multiplex rotation. This port does not offer per-antenna commands.</p>
 */
public interface InventoryControl {
    void activate();
    void deactivate();
    boolean setInventoryEnabled(boolean enabled);
}
