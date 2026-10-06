package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Reusable cooperative task set owned by one AntennaManager.
 *
 * <p>The task objects are constructed once and reset before each new run.
 * Concrete task classes remain package-private.</p>
 */
public final class AntennaTasks {

    /** Direct one-antenna operations required by the task state machines. */
    public interface AntennaTarget {
        Duration powerOnForSelfTest();

        void selfTest();

        void powerOffAfterSelfTest();

        Duration powerOnForInventory();

        void initialize();

        void startInventory();

        void stopInventory();

        void powerOffAfterInventory();

        void shutdownProvider();

        boolean inInventoryGroup();

        boolean availableForInventory();

        boolean inventoryRunning();

        Throwable failure();
    }

    private final SelfTestTask selfTestTask;
    private final InventoryEnableTask inventoryEnableTask;
    private final InventoryDisableTask inventoryDisableTask;
    private final AntennaSwitchTask antennaSwitchTask;
    private final AntennaShutdownTask antennaShutdownTask;

    public AntennaTasks(
            List<? extends AntennaTarget> antennas,
            List<? extends AntennaTarget> inventoryGroup,
            Duration inventoryInterval,
            BooleanSupplier inventoryRequested) {
        if (antennas == null
                || antennas.isEmpty()) {
            throw new IllegalArgumentException(
                    "antennas must contain at least one antenna");
        }
        if (inventoryGroup == null) {
            throw new IllegalArgumentException(
                    "inventoryGroup must not be null");
        }
        if (inventoryRequested == null) {
            throw new IllegalArgumentException(
                    "inventoryRequested must not be null");
        }
        if (!inventoryGroup.isEmpty()
                && (inventoryInterval == null
                    || inventoryInterval.isZero()
                    || inventoryInterval.isNegative())) {
            throw new IllegalArgumentException(
                    "inventoryInterval must be positive for an inventory group");
        }

        selfTestTask =
                new SelfTestTask(
                        antennas);
        inventoryEnableTask =
                new InventoryEnableTask(
                        antennas,
                        inventoryGroup,
                        inventoryRequested);
        inventoryDisableTask =
                new InventoryDisableTask(
                        antennas);
        antennaSwitchTask =
                new AntennaSwitchTask(
                        inventoryGroup,
                        inventoryInterval,
                        inventoryRequested);
        antennaShutdownTask =
                new AntennaShutdownTask(
                        antennas);
    }

    public CooperativeTask selfTest() {
        selfTestTask.reset();
        return selfTestTask;
    }

    public CooperativeTask enableInventory() {
        inventoryEnableTask.reset();
        return inventoryEnableTask;
    }

    public CooperativeTask disableInventory() {
        inventoryDisableTask.reset();
        return inventoryDisableTask;
    }

    public CooperativeTask switchInventory() {
        antennaSwitchTask.reset();
        return antennaSwitchTask;
    }

    public CooperativeTask shutdown() {
        antennaShutdownTask.reset();
        return antennaShutdownTask;
    }
}
