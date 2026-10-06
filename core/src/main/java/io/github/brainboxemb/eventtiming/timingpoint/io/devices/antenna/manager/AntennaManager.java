package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task.AntennaTasks;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.ScheduledTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Public lifecycle and intent boundary for one configured antenna set.
 *
 * <p>The manager starts operation tasks and exposes status. Startup self-test
 * and inventory reconciliation are separate concerns; physical device steps
 * live in cooperative tasks and ManagedAntenna.</p>
 */
public final class AntennaManager {
    private static final Logger LOG =
            LoggerFactory.getLogger(AntennaManager.class);

    private final ManagedAntennaSet antennaSet;
    private final ScheduledTaskRunner tasks;
    private final InventoryController inventory;

    private volatile State state = State.NEW;
    private volatile Throwable failure;
    private volatile boolean selfTestBusy;
    private volatile boolean selfTestPassed;

    private CompletableFuture<Void> selfTestOperation;

    public AntennaManager(
            List<AntennaInstallation> installations,
            SerialScheduledExecutor controlLane,
            Duration controlTimeout) {
        if (controlLane == null) {
            throw new IllegalArgumentException(
                    "controlLane must not be null");
        }

        antennaSet =
                new ManagedAntennaSet(
                        installations);
        tasks =
                new ScheduledTaskRunner(
                        controlLane,
                        controlTimeout);
        inventory =
                new InventoryController(
                        antennaSet,
                        tasks,
                        this::recordFailure);
    }

    /** Activates control and starts the asynchronous startup self-test. */
    public void activate() {
        synchronized (this) {
            if (state != State.NEW) {
                throw new IllegalStateException(
                        "AntennaManager can only activate from NEW; current state="
                                + state);
            }
        }

        tasks.start();
        state = State.ACTIVE;

        LOG.info(
                "AntennaManager activated with {} configured antenna(s)",
                antennaSet.size());

        startSelfTest();
    }

    public boolean isBusy() {
        return selfTestBusy
                || inventory.isBusy();
    }

    public boolean isReady() {
        return state == State.ACTIVE
                && selfTestPassed;
    }

    public boolean requestEnableInventory() {
        return state == State.ACTIVE
                && inventory.requestEnabled(
                        true);
    }

    public boolean requestDisableInventory() {
        return state == State.ACTIVE
                && inventory.requestEnabled(
                        false);
    }

    public void enableInventory() {
        if (!requestEnableInventory()) {
            throw new IllegalStateException(
                    "AntennaManager rejected enable-inventory request");
        }
    }

    public void disableInventory() {
        if (!requestDisableInventory()) {
            throw new IllegalStateException(
                    "AntennaManager rejected disable-inventory request");
        }
    }

    public EventSource<TagObservation> tagObservedEvent(
            AntennaId antennaId) {
        return antennaSet.tagObservedEvent(
                antennaId);
    }

    public State state() {
        return state;
    }

    public Throwable failure() {
        return failure;
    }

    public List<AntennaStatus> statuses() {
        return antennaSet.statuses();
    }

    public AntennaStatus status(
            AntennaId antennaId) {
        return antennaSet.status(
                antennaId);
    }

    /**
     * Stops task admission, shuts down every configured antenna and closes the
     * logical control lane. Runtime still owns the physical worker.
     */
    public void deactivate() {
        synchronized (this) {
            if (state == State.INACTIVE) {
                return;
            }
            if (state == State.DEACTIVATING) {
                throw new IllegalStateException(
                        "AntennaManager is already deactivating");
            }
            state = State.DEACTIVATING;
        }

        inventory.stop();
        cancel(
                selfTestOperation);

        RuntimeException shutdownFailure = null;
        try {
            if (tasks.isNew()) {
                tasks.start();
            }

            CompletableFuture<Void> shutdown =
                    tasks.runTask(
                            AntennaTasks.shutdown(
                                    antennaSet.antennas()));
            tasks.await(
                    shutdown);
        } catch (RuntimeException ex) {
            shutdownFailure = ex;
        }

        try {
            tasks.close();
        } catch (RuntimeException ex) {
            if (shutdownFailure == null) {
                shutdownFailure = ex;
            } else if (shutdownFailure != ex) {
                shutdownFailure.addSuppressed(
                        ex);
            }
        }

        selfTestBusy = false;
        selfTestOperation = null;

        if (shutdownFailure == null) {
            state = State.INACTIVE;
            LOG.info(
                    "AntennaManager deactivated");
            return;
        }

        failure = shutdownFailure;
        state = State.FAILED;
        throw shutdownFailure;
    }

    private void startSelfTest() {
        selfTestBusy = true;
        selfTestPassed = false;

        final CompletableFuture<Void> running =
                tasks.runTask(
                        AntennaTasks.selfTest(
                                antennaSet.antennas()));
        selfTestOperation =
                running;

        running.whenComplete(
                (ignored, taskFailure) -> {
                    boolean accepted =
                            tasks.execute(
                                    () -> finishSelfTest(
                                            running,
                                            taskFailure));

                    if (!accepted) {
                        selfTestBusy = false;
                        recordFailure(
                                new IllegalStateException(
                                        "AntennaManager control lane rejected self-test completion",
                                        taskFailure));
                    }
                });
    }

    private void finishSelfTest(
            CompletableFuture<Void> running,
            Throwable taskFailure) {
        if (selfTestOperation == running) {
            selfTestOperation = null;
        }
        selfTestBusy = false;

        if (state != State.ACTIVE) {
            return;
        }

        if (taskFailure != null) {
            selfTestPassed = false;
            recordFailure(
                    taskFailure);
            LOG.warn(
                    "AntennaManager self-test FAIL",
                    taskFailure);
            return;
        }

        selfTestPassed =
                antennaSet.allSelfTestsPassed();

        LOG.info(
                "AntennaManager self-test {}",
                selfTestPassed
                        ? "PASS"
                        : "FAIL");

        if (selfTestPassed) {
            inventory.ready();
        }
    }

    private void recordFailure(
            Throwable cause) {
        if (cause == null
                || cause instanceof CancellationException) {
            return;
        }
        if (failure == null) {
            failure = cause;
        }
        if (tasks.failure() != null) {
            state = State.FAILED;
        }
    }

    private static void cancel(
            CompletableFuture<Void> operation) {
        if (operation != null
                && !operation.isDone()) {
            operation.cancel(
                    true);
        }
    }
}
