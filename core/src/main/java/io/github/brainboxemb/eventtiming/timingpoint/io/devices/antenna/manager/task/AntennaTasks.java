package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task;

import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.Setting;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaInfo;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;

import java.time.Duration;
import java.util.List;

/**
 * Reusable cooperative task set owned by one AntennaManager.
 */
public final class AntennaTasks {

    /** Reusable task contract for component-owned state machines. */
    public interface ReusableTask extends CooperativeTask {
        void reset();
    }

    /** Direct one-antenna operations required by the task state machines. */
    public interface AntennaTarget {
        void powerOn();

        void powerOff();

        Duration powerStabilization();

        AntennaInfo selfTest();

        void beginInventoryPreparation();

        void initialize();

        void startInventory();

        void stopInventory();

        void shutdownProvider();

        boolean inInventoryGroup();

        boolean availableForInventory();

        boolean inventoryRunning();
    }

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

    /** Creates the one reusable self-test task owned by one managed antenna. */
    public static ReusableTask selfTestTask(
            AntennaTarget antenna) {
        if (antenna == null) {
            throw new IllegalArgumentException(
                    "antenna must not be null");
        }
        return new SelfTestTask(
                antenna);
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
