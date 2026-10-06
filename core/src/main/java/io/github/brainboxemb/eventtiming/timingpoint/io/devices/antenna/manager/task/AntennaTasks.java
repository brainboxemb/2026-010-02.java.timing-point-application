package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task;

import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.Setting;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;

import java.time.Duration;
import java.util.List;

/**
 * Reusable cooperative task set owned by one AntennaManager.
 *
 * <p>The three task objects are constructed once and reset before a new run.
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
    }

    private final SelfTestTask selfTestTask;
    private final InventoryTask inventoryTask;
    private final AntennaShutdownTask antennaShutdownTask;

    public AntennaTasks(
            List<? extends AntennaTarget> antennas,
            List<? extends AntennaTarget> inventoryGroup,
            Duration inventoryInterval,
            Setting<Boolean> inventoryEnabledSetting) {
        if (antennas == null
                || antennas.isEmpty()) {
            throw new IllegalArgumentException(
                    "antennas must contain at least one antenna");
        }
        if (inventoryGroup == null) {
            throw new IllegalArgumentException(
                    "inventoryGroup must not be null");
        }
        if (inventoryEnabledSetting == null) {
            throw new IllegalArgumentException(
                    "inventoryEnabledSetting must not be null");
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
        inventoryTask =
                new InventoryTask(
                        antennas,
                        inventoryGroup,
                        inventoryInterval,
                        inventoryEnabledSetting);
        antennaShutdownTask =
                new AntennaShutdownTask(
                        antennas);
    }

    public CooperativeTask selfTest() {
        selfTestTask.reset();
        return selfTestTask;
    }

    public CooperativeTask inventory() {
        inventoryTask.reset();
        return inventoryTask;
    }

    public CooperativeTask shutdown() {
        antennaShutdownTask.reset();
        return antennaShutdownTask;
    }
}
