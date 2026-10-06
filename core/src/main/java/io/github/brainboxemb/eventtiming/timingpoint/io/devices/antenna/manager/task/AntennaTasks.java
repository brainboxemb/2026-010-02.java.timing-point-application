package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task;

import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.Setting;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaInfo;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.ScheduledTaskRunner;

import java.time.Duration;
import java.util.List;

/** Reusable cooperative task set owned by one AntennaManager. */
public final class AntennaTasks {

    /** Immutable completion result published by a reusable antenna task. */
    public static final class TaskResult {
        private static final TaskResult SUCCESS = new TaskResult(null);

        private final Throwable failure;

        private TaskResult(Throwable failure) {
            this.failure = failure;
        }

        public static TaskResult success() {
            return SUCCESS;
        }

        public static TaskResult failed(Throwable failure) {
            if (failure == null) {
                throw new IllegalArgumentException("failure must not be null");
            }
            return new TaskResult(failure);
        }

        public boolean successful() {
            return failure == null;
        }

        public Throwable failure() {
            return failure;
        }
    }

    /** Direct one-antenna operations required by the task state machines. */
    public interface AntennaTarget {
        void beginSelfTest();

        void powerOn();

        void powerOff();

        Duration powerStabilization();

        AntennaInfo selfTest();

        void beginInventoryPreparation();

        void initialize();

        void startInventory();

        void stopInventory();

        void shutdownProvider();

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
        if (antennas == null || antennas.isEmpty()) {
            throw new IllegalArgumentException("antennas must contain at least one antenna");
        }
        if (inventoryGroup == null) {
            throw new IllegalArgumentException("inventoryGroup must not be null");
        }
        if (inventoryEnabledSetting == null) {
            throw new IllegalArgumentException("inventoryEnabledSetting must not be null");
        }
        if (!inventoryGroup.isEmpty()
                && (inventoryInterval == null || inventoryInterval.isZero() || inventoryInterval.isNegative())) {
            throw new IllegalArgumentException("inventoryInterval must be positive for an inventory group");
        }

        selfTestTask = new SelfTestTask(antennas);
        inventoryTask = new InventoryTask(antennas, inventoryGroup, inventoryInterval, inventoryEnabledSetting);
        antennaShutdownTask = new AntennaShutdownTask(antennas);
    }

    public void startSelfTest(ScheduledTaskRunner taskRunner) {
        selfTestTask.start(taskRunner);
    }

    public void cancelSelfTest() {
        selfTestTask.cancel();
    }

    public boolean selfTestRunning() {
        return selfTestTask.isRunning();
    }

    public EventSource<TaskResult> selfTestCompletedEvent() {
        return selfTestTask.completedEvent();
    }

    public void startInventory(ScheduledTaskRunner taskRunner) {
        inventoryTask.start(taskRunner);
    }

    public void cancelInventory() {
        inventoryTask.cancel();
    }

    public boolean inventoryRunning() {
        return inventoryTask.isRunning();
    }

    public EventSource<TaskResult> inventoryCompletedEvent() {
        return inventoryTask.completedEvent();
    }

    /**
     * Runs the ordered antenna shutdown sequence and waits for it to finish.
     *
     * <p>The manager calls this only during deactivation, after cancelling the
     * normal self-test and inventory tasks.</p>
     */
    public void shutdownAndWait(ScheduledTaskRunner taskRunner) {
        antennaShutdownTask.reset();
        taskRunner.await(taskRunner.runTask(antennaShutdownTask));
    }
}
