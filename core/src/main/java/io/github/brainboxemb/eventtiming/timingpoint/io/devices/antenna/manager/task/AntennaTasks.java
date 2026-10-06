package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Factory and narrow execution ports for AntennaManager cooperative tasks.
 *
 * <p>The concrete task classes remain package-private. The manager exposes its
 * package-private runtime objects only through these small operation ports, so
 * moving tasks into this subpackage does not make ManagedAntenna or the switch
 * controller public API.</p>
 */
public final class AntennaTasks {

    /**
     * Device operations required by manager tasks.
     */
    public interface AntennaTarget {
        Duration beginSelfTest();

        void completeSelfTest();

        Duration beginPrepareForInventory();

        boolean completePrepareForInventory();

        boolean startInventory();

        boolean disableInventory();

        void shutdown();

        boolean inInventoryGroup();

        Throwable failure();
    }

    /**
     * Inventory-group operations required by enable/switch tasks.
     */
    public interface SwitchTarget {
        boolean hasInventoryGroup();

        boolean rotationNeeded();

        Duration inventoryInterval();

        boolean startFirstAvailable();

        void rotateInventoryGroup();
    }

    private AntennaTasks() {
    }

    public static CooperativeTask selfTest(
            List<? extends AntennaTarget> antennas) {
        return new SelfTestTask(
                antennas);
    }

    public static CooperativeTask enableInventory(
            List<? extends AntennaTarget> antennas,
            SwitchTarget switching,
            BooleanSupplier stillRequested) {
        return new InventoryEnableTask(
                antennas,
                switching,
                stillRequested);
    }

    public static CooperativeTask disableInventory(
            List<? extends AntennaTarget> antennas) {
        return new InventoryDisableTask(
                antennas);
    }

    public static CooperativeTask switchInventory(
            SwitchTarget switching,
            BooleanSupplier inventoryRequested) {
        return new AntennaSwitchTask(
                switching,
                inventoryRequested);
    }

    public static CooperativeTask shutdown(
            List<? extends AntennaTarget> antennas) {
        return new AntennaShutdownTask(
                antennas);
    }
}
