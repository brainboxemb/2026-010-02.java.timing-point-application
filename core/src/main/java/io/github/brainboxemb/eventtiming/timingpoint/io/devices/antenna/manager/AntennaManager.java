package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.ControlException;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.FailureReason;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.Setting;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.ScheduledTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.ScheduledTaskRunner.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Public lifecycle and control boundary for one configured antenna set.
 *
 * <p>AntennaManager owns the configured {@link ManagedAntenna} objects and the
 * device lifecycle needed to realise application intent: startup self-test,
 * optional power preparation, initialization, inventory start/stop and shutdown.
 * {@link AntennaSwitchController} owns only mutual-exclusion round-robin
 * switching for the optional inventory group.</p>
 *
 * <p>All device state changes run on one manager-owned logical
 * {@link SerialScheduledExecutor} lane. The physical scheduled I/O worker is
 * supplied and owned by Runtime.</p>
 */
public final class AntennaManager {
    private static final Logger LOG =
            LoggerFactory.getLogger(AntennaManager.class);

    private final List<ManagedAntenna> antennas;
    private final AntennaSwitchController switching;
    private final ScheduledTaskRunner control;

    private final Setting<Boolean> inventoryEnabled =
            new Setting<Boolean>(
                    Boolean.FALSE);

    private volatile State state = State.NEW;
    private volatile Throwable failure;
    private volatile boolean busy;
    private volatile boolean selfTestPassed;

    private SerialScheduledExecutor.ScheduledTask rotationTask;

    public AntennaManager(
            List<AntennaInstallation> installations,
            SerialScheduledExecutor controlLane,
            Duration controlTimeout) {
        if (installations == null
                || installations.isEmpty()) {
            throw new IllegalArgumentException(
                    "installations must contain at least one antenna");
        }
        if (controlLane == null) {
            throw new IllegalArgumentException(
                    "controlLane must not be null");
        }
        if (controlTimeout == null
                || controlTimeout.isZero()
                || controlTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "controlTimeout must be positive");
        }

        List<ManagedAntenna> configured =
                new ArrayList<ManagedAntenna>(
                        installations.size());
        List<ManagedAntenna> inventoryGroup =
                new ArrayList<ManagedAntenna>();
        Duration groupInterval = null;

        for (AntennaInstallation installation
                : installations) {
            validateInstallation(
                    installation,
                    configured);

            ManagedAntenna antenna =
                    new ManagedAntenna(
                            installation);
            configured.add(
                    antenna);

            if (antenna.inInventoryGroup()) {
                groupInterval =
                        sharedGroupInterval(
                                groupInterval,
                                antenna.inventoryInterval());
                inventoryGroup.add(
                        antenna);
            }
        }

        antennas =
                Collections.unmodifiableList(
                        configured);
        switching =
                new AntennaSwitchController(
                        inventoryGroup,
                        groupInterval);
        control =
                new ScheduledTaskRunner(
                        controlLane,
                        controlTimeout);
    }

    /**
     * Activates the AntennaManager and starts its startup self-test.
     *
     * <p>The self-test is asynchronous. Activation returns after the self-test
     * has been admitted so Presentation startup does not have to wait for
     * antenna power stabilization or provider I/O.</p>
     */
    public void activate() {
        synchronized (this) {
            if (state != State.NEW) {
                throw new IllegalStateException(
                        "AntennaManager can only activate from NEW; current state="
                                + state);
            }
        }

        try {
            control.start();
            state = State.ACTIVE;
            LOG.info(
                    "AntennaManager activated with {} configured antenna(s)",
                    antennas.size());
            startSelfTest();
        } catch (RuntimeException ex) {
            failure = ex;
            state = State.FAILED;
            throw ex;
        }
    }

    /** Returns whether the manager is currently processing device work. */
    public boolean isBusy() {
        return busy;
    }

    /**
     * Returns whether startup self-test passed and the manager is available for
     * normal inventory requests.
     */
    public boolean isReady() {
        return state == State.ACTIVE
                && selfTestPassed
                && !busy;
    }

    private void startSelfTest() {
        busy = true;
        selfTestPassed = false;
        LOG.info(
                "AntennaManager self-test started");

        CompletableFuture<Void> transition =
                CompletableFuture.completedFuture(
                        null);

        for (ManagedAntenna antenna : antennas) {
            transition =
                    transition.thenCompose(
                            ignored -> selfTestAntenna(
                                    antenna));
        }

        transition.whenComplete(
                (ignored, transitionFailure) -> {
                    boolean accepted =
                            control.execute(
                                    () -> finishSelfTest(
                                            transitionFailure));
                    if (!accepted) {
                        ControlException rejection =
                                controlFailure(
                                        FailureReason.OVERLOADED,
                                        "AntennaManager control lane rejected self-test completion",
                                        control.failure());
                        busy = false;
                        selfTestPassed = false;
                        recordFailure(
                                rejection);
                        LOG.warn(
                                "AntennaManager self-test FAIL because completion was not admitted",
                                rejection);
                    }
                });
    }

    private CompletableFuture<Void> selfTestAntenna(
            ManagedAntenna antenna) {
        LOG.info(
                "Antenna {} self-test started",
                antenna.antennaId());

        CompletableFuture<Void> selfTest =
                control.runDelayed(
                        antenna::beginSelfTest,
                        antenna::completeSelfTest);

        return selfTest
                .handle(
                        (ignored, selfTestFailure) -> {
                            if (selfTestFailure == null) {
                                return CompletableFuture.<Void>completedFuture(
                                        null);
                            }

                            ControlException mapped =
                                    mapControlFailure(
                                            unwrapCompletionFailure(
                                                    selfTestFailure));
                            return control.runAsync(
                                    () -> antenna.selfTestControlFailed(
                                            mapped));
                        })
                .thenCompose(
                        followUp -> followUp);
    }

    private void finishSelfTest(
            Throwable transitionFailure) {
        if (transitionFailure != null) {
            ControlException mapped =
                    mapControlFailure(
                            unwrapCompletionFailure(
                                    transitionFailure));
            recordFailure(
                    mapped);
            selfTestPassed = false;
            busy = false;
            LOG.warn(
                    "AntennaManager self-test FAIL",
                    mapped);
            return;
        }

        boolean passed = true;
        for (ManagedAntenna antenna : antennas) {
            if (!antenna.selfTestPassed()) {
                passed = false;
                break;
            }
        }

        selfTestPassed = passed;
        busy = false;

        LOG.info(
                "AntennaManager self-test {}",
                passed
                        ? "PASS"
                        : "FAIL");

        if (passed) {
            processInventorySetting();
        }
    }

    /**
     * Requests inventory enable without waiting for provider I/O.
     *
     * <p>When the startup self-test is still running, the requested value is
     * retained and applied after a PASS result.</p>
     */
    public boolean requestEnableInventory() {
        return requestInventory(
                true);
    }

    /**
     * Requests inventory disable without waiting for provider I/O.
     *
     * <p>If inventory is already disabled and no enable is being processed,
     * this is a no-op.</p>
     */
    public boolean requestDisableInventory() {
        return requestInventory(
                false);
    }

    /**
     * Convenience method for direct callers that need the normal request path.
     *
     * <p>The operation is still asynchronous; use status/device state when a
     * caller needs to observe completion.</p>
     */
    public void enableInventory() {
        if (!requestEnableInventory()) {
            throw new IllegalStateException(
                    "AntennaManager rejected enable-inventory request");
        }
    }

    /**
     * Convenience method for direct callers that need the normal request path.
     */
    public void disableInventory() {
        if (!requestDisableInventory()) {
            throw new IllegalStateException(
                    "AntennaManager rejected disable-inventory request");
        }
    }

    private boolean requestInventory(
            boolean enabled) {
        if (state != State.ACTIVE) {
            return false;
        }

        inventoryEnabled.request(
                Boolean.valueOf(
                        enabled));

        if (!inventoryEnabled.changePending()) {
            return true;
        }

        LOG.info(
                "AntennaManager inventory requested={} applied={} changePending={}",
                inventoryEnabled.requestedValue(),
                inventoryEnabled.appliedValue(),
                inventoryEnabled.changePending());

        if (control.execute(
                this::processInventorySetting)) {
            return true;
        }

        ControlException rejection =
                controlFailure(
                        FailureReason.OVERLOADED,
                        "AntennaManager control lane rejected inventory setting",
                        control.failure());
        recordFailure(
                rejection);
        LOG.warn(
                "AntennaManager rejected inventory setting because control work was not admitted",
                rejection);
        return false;
    }

    /**
     * Returns the subscription-only tag-observed event for one configured
     * antenna without exposing the concrete provider object.
     */
    public EventSource<TagObservation> tagObservedEvent(
            AntennaId antennaId) {
        return find(
                antennaId)
                .tagObservedEvent();
    }

    /** Returns software-component lifecycle only. */
    public State state() {
        return state;
    }

    /**
     * Returns a manager/control failure. Per-antenna provider failures are
     * exposed through {@link #statuses()} instead.
     */
    public Throwable failure() {
        return failure;
    }

    public List<AntennaStatus> statuses() {
        List<AntennaStatus> result =
                new ArrayList<AntennaStatus>(
                        antennas.size());
        for (ManagedAntenna antenna : antennas) {
            result.add(
                    antenna.status());
        }
        return Collections.unmodifiableList(
                result);
    }

    public AntennaStatus status(
            AntennaId antennaId) {
        return find(
                antennaId)
                .status();
    }

    /**
     * Deactivates manager control and shuts down every configured antenna.
     *
     * <p>This remains safe before successful activation so application rollback
     * can use one reverse-order lifecycle path.</p>
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

        inventoryEnabled.request(
                Boolean.FALSE);
        cancelRotation();

        RuntimeException firstFailure = null;

        try {
            if (control.isNew()) {
                control.start();
            }
            runControl(
                    this::shutdownAll);
        } catch (RuntimeException ex) {
            firstFailure = ex;
        }

        try {
            control.close();
        } catch (RuntimeException ex) {
            firstFailure =
                    appendFailure(
                            firstFailure,
                            ex);
        }

        if (firstFailure == null) {
            state = State.INACTIVE;
            LOG.info(
                    "AntennaManager deactivated");
            return;
        }

        failure = firstFailure;
        state = State.FAILED;
        throw firstFailure;
    }

    /**
     * Processes the latest requested inventory setting on the manager control
     * lane.
     */
    private void processInventorySetting() {
        if (state != State.ACTIVE
                || busy
                || !selfTestPassed
                || !inventoryEnabled.beginChange()) {
            return;
        }

        boolean enable =
                inventoryEnabled.processingValue()
                        .booleanValue();
        busy = true;

        if (!enable) {
            boolean success =
                    applyInventoryDisable();
            inventoryEnabled.completeChange(
                    success);
            busy = false;

            if (success
                    && inventoryEnabled.changePending()) {
                processInventorySetting();
            }
            return;
        }

        CompletableFuture<Void> transition =
                prepareInventory();

        transition.whenComplete(
                (ignored, transitionFailure) -> {
                    boolean accepted =
                            control.execute(
                                    () -> finishInventoryEnable(
                                            transitionFailure));
                    if (!accepted) {
                        ControlException rejection =
                                controlFailure(
                                        FailureReason.OVERLOADED,
                                        "AntennaManager control lane rejected inventory-enable completion",
                                        control.failure());
                        inventoryEnabled.completeChange(
                                false);
                        busy = false;
                        recordFailure(
                                rejection);
                        LOG.warn(
                                "AntennaManager could not complete inventory enable",
                                rejection);
                    }
                });
    }

    private boolean applyInventoryDisable() {
        LOG.info(
                "Disabling antenna inventory");
        cancelRotation();

        boolean success =
                disableAllInventory();

        if (success) {
            LOG.info(
                    "Antenna inventory disabled; external power removed where configured");
        } else {
            LOG.warn(
                    "Antenna inventory disable did not complete for every configured antenna");
        }

        return success;
    }

    /**
     * Builds the antenna-specific preparation sequence directly.
     *
     * <p>This is lifecycle policy, not a generic CompletableFuture sequencing
     * abstraction. Each delayed step checks the current request version before
     * it may initialize or start a reader.</p>
     */
    private CompletableFuture<Void> prepareInventory() {
        CompletableFuture<Void> transition =
                CompletableFuture.completedFuture(
                        null);

        for (ManagedAntenna antenna : antennas) {
            if (antenna.inInventoryGroup()) {
                continue;
            }

            transition =
                    transition.thenCompose(
                            ignored -> prepareAntenna(
                                    antenna,
                                    true));
        }

        for (ManagedAntenna antenna : antennas) {
            if (!antenna.inInventoryGroup()) {
                continue;
            }

            transition =
                    transition.thenCompose(
                            ignored -> prepareAntenna(
                                    antenna,
                                    false));
        }

        if (switching.hasInventoryGroup()) {
            transition =
                    transition.thenCompose(
                            ignored -> {
                                if (!inventoryRequestedEnabled()) {
                                    return CompletableFuture.completedFuture(
                                            null);
                                }
                                return control.runAsync(
                                        () -> {
                                            if (inventoryRequestedEnabled()) {
                                                switching.startFirstAvailable();
                                            }
                                        });
                            });
        }

        return transition;
    }

    private CompletableFuture<Void> prepareAntenna(
            ManagedAntenna antenna,
            boolean startInventory) {
        if (!inventoryRequestedEnabled()) {
            return CompletableFuture.completedFuture(
                    null);
        }

        return control.runDelayed(
                () -> inventoryRequestedEnabled()
                        ? antenna.beginPrepareForInventory()
                        : null,
                () -> {
                    if (!inventoryRequestedEnabled()) {
                        return;
                    }

                    boolean prepared =
                            antenna.completePrepareForInventory();
                    if (prepared
                            && startInventory
                            && inventoryRequestedEnabled()) {
                        antenna.startInventory();
                    }
                });
    }

    private void finishInventoryEnable(
            Throwable transitionFailure) {
        if (transitionFailure != null) {
            ControlException mapped =
                    mapControlFailure(
                            unwrapCompletionFailure(
                                    transitionFailure));
            recordFailure(
                    mapped);
            inventoryEnabled.completeChange(
                    false);
            busy = false;
            LOG.warn(
                    "Antenna inventory preparation failed",
                    mapped);
            return;
        }

        if (!inventoryRequestedEnabled()) {
            cancelRotation();
            disableAllInventory();
            inventoryEnabled.completeChange(
                    false);
            busy = false;
            return;
        }

        ensureRotation();
        inventoryEnabled.completeChange(
                true);
        busy = false;
        LOG.info(
                "Antenna inventory enabled");

        if (inventoryEnabled.changePending()) {
            processInventorySetting();
        }
    }

    /**
     * Starts one fixed-delay multiplex callback on the existing manager lane.
     */
    private synchronized void ensureRotation() {
        if (!inventoryRequestedEnabled()
                || !switching.hasInventoryGroup()
                || !switching.rotationNeeded()) {
            cancelRotation();
            return;
        }
        if (rotationTask != null) {
            return;
        }

        rotationTask =
                control.scheduleWithFixedDelay(
                        this::rotateInventoryGroup,
                        switching.inventoryInterval());
    }

    private void rotateInventoryGroup() {
        if (!inventoryRequestedEnabled()) {
            return;
        }

        switching.rotateInventoryGroup();
        if (!switching.rotationNeeded()) {
            cancelRotation();
        }
    }

    private synchronized void cancelRotation() {
        SerialScheduledExecutor.ScheduledTask task =
                rotationTask;
        rotationTask = null;
        if (task != null) {
            task.close();
        }
    }

    private boolean disableAllInventory() {
        boolean success = true;

        for (int index = antennas.size() - 1;
                index >= 0;
                index--) {
            if (!antennas.get(index)
                    .disableInventory()) {
                success = false;
            }
        }

        return success;
    }

    private void shutdownAll() {
        RuntimeException firstFailure = null;

        for (int index = antennas.size() - 1;
                index >= 0;
                index--) {
            try {
                antennas.get(index)
                        .shutdown();
            } catch (RuntimeException ex) {
                firstFailure =
                        appendFailure(
                                firstFailure,
                                ex);
            }
        }

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private boolean inventoryRequestedEnabled() {
        return Boolean.TRUE.equals(
                inventoryEnabled.requestedValue());
    }

    private ManagedAntenna find(
            AntennaId antennaId) {
        if (antennaId == null) {
            throw new IllegalArgumentException(
                    "antennaId must not be null");
        }

        for (ManagedAntenna antenna : antennas) {
            if (antenna.antennaId()
                    .equals(
                            antennaId)) {
                return antenna;
            }
        }

        throw new IllegalArgumentException(
                "unknown AntennaId "
                        + antennaId);
    }

    private void requireActive(
            String operation) {
        if (state != State.ACTIVE) {
            throw new IllegalStateException(
                    operation
                            + " requires active AntennaManager; current state="
                            + state);
        }
    }

    private static Throwable unwrapCompletionFailure(
            Throwable failure) {
        if (!(failure instanceof CompletionException)) {
            return failure;
        }

        Throwable cause =
                failure.getCause();
        return cause == null
                ? failure
                : cause;
    }

    private void runControl(
            Runnable action) {
        try {
            control.run(
                    action);
        } catch (OperationException ex) {
            throw mapControlFailure(
                    ex);
        }
    }

    private void awaitControl(
            CompletableFuture<Void> future) {
        try {
            control.await(
                    future);
        } catch (OperationException ex) {
            throw mapControlFailure(
                    ex);
        }
    }

    private static ControlException mapControlFailure(
            Throwable failure) {
        if (failure instanceof ControlException) {
            return (ControlException) failure;
        }
        if (!(failure instanceof OperationException)) {
            return controlFailure(
                    FailureReason.PROVIDER_FAILURE,
                    "AntennaManager provider operation failed",
                    failure);
        }

        OperationException operation =
                (OperationException) failure;
        switch (operation.reason()) {
            case OVERLOADED:
                return controlFailure(
                        FailureReason.OVERLOADED,
                        "AntennaManager control operation was rejected",
                        operation);
            case TIMEOUT:
                return controlFailure(
                        FailureReason.TIMEOUT,
                        "AntennaManager control operation timed out",
                        operation);
            case INTERRUPTED:
                return controlFailure(
                        FailureReason.INTERRUPTED,
                        "AntennaManager control operation was interrupted",
                        operation);
            case EXECUTION_FAILURE:
                return controlFailure(
                        FailureReason.PROVIDER_FAILURE,
                        "AntennaManager provider operation failed",
                        operation.getCause() == null
                                ? operation
                                : operation.getCause());
            default:
                throw new IllegalStateException(
                        "Unsupported serial operation failure "
                                + operation.reason());
        }
    }

    private static ControlException controlFailure(
            FailureReason reason,
            String message,
            Throwable cause) {
        return new ControlException(
                reason,
                message,
                cause);
    }

    private void recordFailure(
            Throwable cause) {
        if (failure == null) {
            failure = cause;
        }

        /*
         * A failed backing/control lane means the manager itself can no longer
         * perform its role. Ordinary queue-full overload and contained provider
         * failures do not change component lifecycle.
         */
        if (control.failure() != null) {
            state = State.FAILED;
        }
    }

    private static void validateInstallation(
            AntennaInstallation installation,
            List<ManagedAntenna> existing) {
        if (installation == null) {
            throw new IllegalArgumentException(
                    "installations must not contain null");
        }

        for (ManagedAntenna antenna : existing) {
            if (antenna.antennaId()
                    .equals(
                            installation.antennaId())) {
                throw new IllegalArgumentException(
                        "duplicate AntennaId "
                                + installation.antennaId());
            }
        }
    }

    private static Duration sharedGroupInterval(
            Duration current,
            Duration candidate) {
        if (current == null) {
            return candidate;
        }
        if (!current.equals(
                candidate)) {
            throw new IllegalArgumentException(
                    "all antennas in the inventory group must use the same interval");
        }
        return current;
    }

    private static RuntimeException appendFailure(
            RuntimeException current,
            RuntimeException later) {
        if (current == null) {
            return later;
        }
        if (current != later) {
            current.addSuppressed(
                    later);
        }
        return current;
    }
}
