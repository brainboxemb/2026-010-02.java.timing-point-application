package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lifecycle/control owner for the configured antennas of one TimingSystem.
 *
 * <p>Provider failures are isolated per antenna. Blocking provider and power
 * operations run on the shared bounded I/O executor; TimingNode/event callbacks
 * only submit control work and never wait for device I/O.</p>
 */
public final class AntennaManager implements AutoCloseable {

    public enum State {
        NEW,
        STARTING,
        RUNNING,
        DEGRADED,
        STOPPING,
        STOPPED,
        FAILED
    }

    public enum AntennaState {
        UNCHECKED,
        CHECKING,
        READY,
        INVENTORY,
        ERROR,
        CLOSED
    }

    public enum FailureReason {
        OVERLOADED,
        TIMEOUT,
        INTERRUPTED,
        PROVIDER_FAILURE
    }

    /** Visible failure of one result-bearing antenna lifecycle operation. */
    public static final class ControlException extends RuntimeException {
        private final FailureReason reason;

        private ControlException(
                FailureReason reason,
                String message,
                Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public FailureReason reason() {
            return reason;
        }
    }

    /** Immutable point-in-time view of one configured antenna. */
    public static final class AntennaStatus {
        private final Antenna antenna;
        private final AntennaState state;
        private final Throwable failure;

        private AntennaStatus(
                Antenna antenna,
                AntennaState state,
                Throwable failure) {
            this.antenna = antenna;
            this.state = state;
            this.failure = failure;
        }

        public Antenna antenna() {
            return antenna;
        }

        public AntennaState state() {
            return state;
        }

        public Throwable failure() {
            return failure;
        }

        public boolean healthy() {
            return state == AntennaState.READY
                    || state == AntennaState.INVENTORY;
        }
    }

    private interface ControlAction {
        void run();
    }

    private static final class ManagedAntenna {
        private final AntennaInstallation installation;
        private volatile AntennaState state = AntennaState.UNCHECKED;
        private volatile Throwable failure;
        private boolean externalPowerApplied;

        private ManagedAntenna(AntennaInstallation installation) {
            this.installation = installation;
        }
    }

    private final List<ManagedAntenna> managedAntennas;
    private final List<Antenna> antennas;
    private final Map<String, List<ManagedAntenna>> inventoryGroups;
    private final ExecutorService sharedIoExecutor;
    private final ScheduledExecutorService scheduler;
    private final ArrayBlockingQueue<FutureTask<Void>> controlQueue;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final long controlTimeoutNanos;
    private final Map<String, ScheduledFuture<?>> groupSchedules =
            new LinkedHashMap<String, ScheduledFuture<?>>();

    private volatile State state = State.NEW;
    private volatile Throwable failure;
    private volatile boolean desiredOperational;

    /** Compatibility constructor for directly powered, non-multiplexed antennas. */
    public AntennaManager(
            List<Antenna> antennas,
            ExecutorService sharedIoExecutor,
            int controlQueueCapacity,
            Duration controlTimeout) {
        this(
                directInstallations(antennas),
                sharedIoExecutor,
                null,
                controlQueueCapacity,
                controlTimeout);
    }

    public AntennaManager(
            List<AntennaInstallation> installations,
            ExecutorService sharedIoExecutor,
            ScheduledExecutorService scheduler,
            int controlQueueCapacity,
            Duration controlTimeout) {
        if (installations == null || installations.isEmpty()) {
            throw new IllegalArgumentException(
                    "installations must contain at least one antenna");
        }
        if (sharedIoExecutor == null) {
            throw new IllegalArgumentException(
                    "sharedIoExecutor must not be null");
        }
        if (controlQueueCapacity < 1) {
            throw new IllegalArgumentException(
                    "controlQueueCapacity must be positive");
        }
        if (controlTimeout == null
                || controlTimeout.isZero()
                || controlTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "controlTimeout must be positive");
        }

        List<ManagedAntenna> managed =
                new ArrayList<ManagedAntenna>(installations.size());
        List<Antenna> antennaCopy =
                new ArrayList<Antenna>(installations.size());
        Map<String, List<ManagedAntenna>> groups =
                new LinkedHashMap<String, List<ManagedAntenna>>();
        Map<String, Duration> groupIntervals =
                new LinkedHashMap<String, Duration>();

        for (AntennaInstallation installation : installations) {
            if (installation == null) {
                throw new IllegalArgumentException(
                        "installations must not contain null");
            }
            ManagedAntenna managedAntenna =
                    new ManagedAntenna(installation);
            managed.add(managedAntenna);
            antennaCopy.add(installation.antenna());

            String group = installation.inventoryGroup();
            if (group != null) {
                if (scheduler == null) {
                    throw new IllegalArgumentException(
                            "inventory groups require a scheduler");
                }
                Duration prior = groupIntervals.get(group);
                if (prior != null
                        && !prior.equals(installation.inventoryInterval())) {
                    throw new IllegalArgumentException(
                            "all antennas in inventory group "
                                    + group
                                    + " must use the same interval");
                }
                groupIntervals.put(group, installation.inventoryInterval());
                List<ManagedAntenna> members = groups.get(group);
                if (members == null) {
                    members = new ArrayList<ManagedAntenna>();
                    groups.put(group, members);
                }
                members.add(managedAntenna);
            }
        }

        this.managedAntennas = Collections.unmodifiableList(managed);
        this.antennas = Collections.unmodifiableList(antennaCopy);
        this.inventoryGroups = groups;
        this.sharedIoExecutor = sharedIoExecutor;
        this.scheduler = scheduler;
        this.controlQueue =
                new ArrayBlockingQueue<FutureTask<Void>>(controlQueueCapacity);
        try {
            this.controlTimeoutNanos = controlTimeout.toNanos();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "controlTimeout is too large",
                    ex);
        }
    }

    /**
     * Performs a non-inventory startup health check for every configured antenna.
     *
     * <p>A provider/power failure is contained to the affected antenna and does
     * not abort checks for the remaining antennas.</p>
     */
    public void start() {
        synchronized (this) {
            if (state != State.NEW) {
                throw new IllegalStateException(
                        "AntennaManager can only start from NEW; current state="
                                + state);
            }
            state = State.STARTING;
        }

        try {
            runControl(this::probeAll);
            refreshAggregateState();
        } catch (RuntimeException ex) {
            failure = ex;
            state = State.FAILED;
            throw ex;
        }
    }

    /**
     * Requests operational inventory state without waiting for provider I/O.
     *
     * <p>This is the form used from TimingNode status/event callbacks.</p>
     */
    public boolean requestOperational(boolean operational) {
        State current = state;
        if (current != State.RUNNING && current != State.DEGRADED) {
            return false;
        }
        desiredOperational = operational;
        try {
            FutureTask<Void> task = enqueueControl(this::reconcileOperational);
            if (task == null) {
                recordControlFailure(
                        controlFailure(
                                FailureReason.OVERLOADED,
                                "AntennaManager control queue is full",
                                null));
                return false;
            }
            return true;
        } catch (RuntimeException ex) {
            recordControlFailure(ex);
            return false;
        }
    }

    /** Result-bearing operational transition for runtime/bootstrap tests and callers. */
    public void setOperational(boolean operational) {
        State current = state;
        if (current != State.RUNNING && current != State.DEGRADED) {
            throw new IllegalStateException(
                    "AntennaManager is not available for operation; current state="
                            + current);
        }
        desiredOperational = operational;
        runControl(this::reconcileOperational);
    }

    public State state() {
        return state;
    }

    public Throwable failure() {
        return failure;
    }

    public List<Antenna> antennas() {
        return antennas;
    }

    public List<AntennaStatus> statuses() {
        List<AntennaStatus> result =
                new ArrayList<AntennaStatus>(managedAntennas.size());
        for (ManagedAntenna managed : managedAntennas) {
            result.add(snapshot(managed));
        }
        return Collections.unmodifiableList(result);
    }

    public AntennaStatus status(Antenna antenna) {
        if (antenna == null) {
            throw new IllegalArgumentException("antenna must not be null");
        }
        for (ManagedAntenna managed : managedAntennas) {
            if (managed.installation.antenna() == antenna) {
                return snapshot(managed);
            }
        }
        throw new IllegalArgumentException(
                "antenna is not managed by this AntennaManager");
    }

    @Override
    public void close() {
        State current;
        synchronized (this) {
            current = state;
            if (current == State.STOPPED) {
                return;
            }
            if (current == State.STARTING || current == State.STOPPING) {
                throw new IllegalStateException(
                        "AntennaManager cannot close during transition; current state="
                                + current);
            }
            state = State.STOPPING;
        }

        desiredOperational = false;
        RuntimeException closeFailure = null;
        try {
            runControl(this::stopPowerAndCloseAll);
        } catch (RuntimeException ex) {
            closeFailure = ex;
        }

        if (closeFailure == null) {
            state = State.STOPPED;
        } else {
            failure = closeFailure;
            state = State.FAILED;
            throw closeFailure;
        }
    }

    private void probeAll() {
        for (ManagedAntenna managed : managedAntennas) {
            sanityCheck(managed);
        }
    }

    private void sanityCheck(ManagedAntenna managed) {
        managed.state = AntennaState.CHECKING;
        managed.failure = null;

        try {
            powerOnIfConfigured(managed);
            managed.installation.antenna().probe();
            managed.state = AntennaState.READY;
        } catch (RuntimeException ex) {
            markAntennaFailed(managed, ex);
        } catch (Error ex) {
            markAntennaFailed(managed, ex);
        } finally {
            if (managed.installation.powerControl() != null) {
                try {
                    powerOffIfConfigured(managed);
                } catch (RuntimeException ex) {
                    markAntennaFailed(managed, ex);
                } catch (Error ex) {
                    markAntennaFailed(managed, ex);
                }
            }
        }
    }

    private void reconcileOperational() {
        if (desiredOperational) {
            activateAvailableAntennas();
        } else {
            deactivateAntennas();
        }
        refreshAggregateState();
    }

    private void activateAvailableAntennas() {
        for (ManagedAntenna managed : managedAntennas) {
            if (managed.installation.inventoryGroup() == null) {
                if (prepareForOperation(managed)) {
                    startInventory(managed);
                }
            }
        }

        for (Map.Entry<String, List<ManagedAntenna>> entry
                : inventoryGroups.entrySet()) {
            List<ManagedAntenna> members = entry.getValue();
            for (ManagedAntenna managed : members) {
                prepareForOperation(managed);
            }
            startFirstAvailable(members);
            ensureGroupSchedule(entry.getKey(), members);
        }
    }

    private boolean prepareForOperation(ManagedAntenna managed) {
        if (managed.state == AntennaState.ERROR
                || managed.state == AntennaState.CLOSED) {
            return false;
        }
        if (managed.state == AntennaState.INVENTORY) {
            return true;
        }

        try {
            powerOnIfConfigured(managed);
            managed.installation.antenna().initialize();
            managed.state = AntennaState.READY;
            return true;
        } catch (RuntimeException ex) {
            markAntennaFailed(managed, ex);
            powerOffAfterFailure(managed);
            return false;
        } catch (Error ex) {
            markAntennaFailed(managed, ex);
            powerOffAfterFailure(managed);
            return false;
        }
    }

    private boolean startInventory(ManagedAntenna managed) {
        if (managed.state == AntennaState.INVENTORY) {
            return true;
        }
        if (managed.state != AntennaState.READY) {
            return false;
        }

        try {
            managed.installation.antenna().startInventory();
            managed.state = AntennaState.INVENTORY;
            return true;
        } catch (RuntimeException ex) {
            markAntennaFailed(managed, ex);
            powerOffAfterFailure(managed);
            return false;
        } catch (Error ex) {
            markAntennaFailed(managed, ex);
            powerOffAfterFailure(managed);
            return false;
        }
    }

    private void deactivateAntennas() {
        cancelAllGroupSchedules();

        for (int index = managedAntennas.size() - 1; index >= 0; index--) {
            ManagedAntenna managed = managedAntennas.get(index);
            if (managed.state == AntennaState.INVENTORY) {
                try {
                    managed.installation.antenna().stopInventory();
                    managed.state = AntennaState.READY;
                } catch (RuntimeException ex) {
                    markAntennaFailed(managed, ex);
                } catch (Error ex) {
                    markAntennaFailed(managed, ex);
                }
            }

            if (managed.installation.powerControl() != null) {
                try {
                    powerOffIfConfigured(managed);
                } catch (RuntimeException ex) {
                    markAntennaFailed(managed, ex);
                } catch (Error ex) {
                    markAntennaFailed(managed, ex);
                }
            }
        }
    }

    private void startFirstAvailable(List<ManagedAntenna> members) {
        for (ManagedAntenna managed : members) {
            if (managed.state == AntennaState.INVENTORY) {
                return;
            }
        }
        for (ManagedAntenna managed : members) {
            if (startInventory(managed)) {
                return;
            }
        }
    }

    private void ensureGroupSchedule(
            String group,
            List<ManagedAntenna> members) {
        if (healthyMemberCount(members) <= 1) {
            cancelGroupSchedule(group);
            return;
        }

        ScheduledFuture<?> existing = groupSchedules.get(group);
        if (existing != null && !existing.isCancelled()) {
            return;
        }

        long intervalNanos =
                members.get(0).installation.inventoryInterval().toNanos();
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> requestGroupRotation(group),
                intervalNanos,
                intervalNanos,
                TimeUnit.NANOSECONDS);
        groupSchedules.put(group, future);
    }

    private void requestGroupRotation(String group) {
        if (!desiredOperational) {
            return;
        }
        try {
            FutureTask<Void> task =
                    enqueueControl(() -> rotateGroup(group));
            if (task == null) {
                recordControlFailure(
                        controlFailure(
                                FailureReason.OVERLOADED,
                                "AntennaManager control queue is full during inventory rotation",
                                null));
            }
        } catch (RuntimeException ex) {
            recordControlFailure(ex);
        }
    }

    private void rotateGroup(String group) {
        if (!desiredOperational) {
            return;
        }

        List<ManagedAntenna> members = inventoryGroups.get(group);
        if (members == null || members.isEmpty()) {
            return;
        }

        int healthy = healthyMemberCount(members);
        if (healthy <= 1) {
            startFirstAvailable(members);
            cancelGroupSchedule(group);
            refreshAggregateState();
            return;
        }

        int currentIndex = -1;
        for (int index = 0; index < members.size(); index++) {
            if (members.get(index).state == AntennaState.INVENTORY) {
                currentIndex = index;
                break;
            }
        }

        if (currentIndex >= 0) {
            ManagedAntenna current = members.get(currentIndex);
            try {
                current.installation.antenna().stopInventory();
                current.state = AntennaState.READY;
            } catch (RuntimeException ex) {
                markAntennaFailed(current, ex);
                powerOffAfterFailure(current);
            } catch (Error ex) {
                markAntennaFailed(current, ex);
                powerOffAfterFailure(current);
            }
        }

        int startIndex = currentIndex < 0 ? 0 : currentIndex + 1;
        for (int offset = 0; offset < members.size(); offset++) {
            ManagedAntenna candidate =
                    members.get((startIndex + offset) % members.size());
            if (startInventory(candidate)) {
                break;
            }
        }

        if (healthyMemberCount(members) <= 1) {
            cancelGroupSchedule(group);
        }
        refreshAggregateState();
    }

    private int healthyMemberCount(List<ManagedAntenna> members) {
        int count = 0;
        for (ManagedAntenna managed : members) {
            if (managed.state == AntennaState.READY
                    || managed.state == AntennaState.INVENTORY) {
                count++;
            }
        }
        return count;
    }

    private void stopPowerAndCloseAll() {
        cancelAllGroupSchedules();

        RuntimeException firstFailure = null;
        for (int index = managedAntennas.size() - 1; index >= 0; index--) {
            ManagedAntenna managed = managedAntennas.get(index);
            Antenna antenna = managed.installation.antenna();

            try {
                if (antenna.inventoryRunning()) {
                    antenna.stopInventory();
                }
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                }
            }

            try {
                powerOffIfConfigured(managed);
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                }
            }

            try {
                antenna.close();
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                }
            }
            managed.state = AntennaState.CLOSED;
        }

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private void powerOnIfConfigured(ManagedAntenna managed) {
        AntennaPowerControl power =
                managed.installation.powerControl();
        if (power == null || managed.externalPowerApplied) {
            return;
        }

        power.powerOn();
        managed.externalPowerApplied = true;
        waitForStabilization(
                managed.installation.powerStabilization());
    }

    private void powerOffIfConfigured(ManagedAntenna managed) {
        AntennaPowerControl power =
                managed.installation.powerControl();
        if (power == null || !managed.externalPowerApplied) {
            return;
        }

        try {
            power.powerOff();
        } finally {
            managed.externalPowerApplied = false;
        }
    }

    private void powerOffAfterFailure(ManagedAntenna managed) {
        if (managed.installation.powerControl() == null) {
            return;
        }
        try {
            powerOffIfConfigured(managed);
        } catch (RuntimeException ignored) {
            // Keep the first antenna failure as the diagnostic cause.
        }
    }

    private static void waitForStabilization(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            TimeUnit.NANOSECONDS.sleep(delay.toNanos());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Antenna power stabilization was interrupted",
                    ex);
        }
    }

    private void markAntennaFailed(
            ManagedAntenna managed,
            Throwable cause) {
        if (managed.failure == null) {
            managed.failure = cause;
        }
        managed.state = AntennaState.ERROR;
        if (failure == null) {
            failure = cause;
        }
    }

    private void refreshAggregateState() {
        State current = state;
        if (current == State.STOPPING
                || current == State.STOPPED) {
            return;
        }

        int healthy = 0;
        int failed = 0;
        for (ManagedAntenna managed : managedAntennas) {
            if (managed.state == AntennaState.ERROR) {
                failed++;
            } else if (managed.state != AntennaState.CLOSED
                    && managed.state != AntennaState.UNCHECKED
                    && managed.state != AntennaState.CHECKING) {
                healthy++;
            }
        }

        if (healthy == 0) {
            state = State.FAILED;
        } else if (failed > 0) {
            state = State.DEGRADED;
        } else {
            state = State.RUNNING;
        }
    }

    private void cancelAllGroupSchedules() {
        List<String> groups =
                new ArrayList<String>(groupSchedules.keySet());
        for (String group : groups) {
            cancelGroupSchedule(group);
        }
    }

    private void cancelGroupSchedule(String group) {
        ScheduledFuture<?> future = groupSchedules.remove(group);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void runControl(ControlAction action) {
        FutureTask<Void> task = enqueueControl(action);
        if (task == null) {
            throw controlFailure(
                    FailureReason.OVERLOADED,
                    "AntennaManager control queue is full",
                    null);
        }

        try {
            task.get(controlTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            task.cancel(true);
            throw controlFailure(
                    FailureReason.TIMEOUT,
                    "AntennaManager control operation timed out",
                    ex);
        } catch (InterruptedException ex) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw controlFailure(
                    FailureReason.INTERRUPTED,
                    "AntennaManager control operation was interrupted",
                    ex);
        } catch (CancellationException ex) {
            throw controlFailure(
                    FailureReason.OVERLOADED,
                    "AntennaManager control operation was cancelled before execution",
                    ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            throw controlFailure(
                    FailureReason.PROVIDER_FAILURE,
                    "AntennaManager provider operation failed",
                    cause == null ? ex : cause);
        }
    }

    private FutureTask<Void> enqueueControl(ControlAction action) {
        FutureTask<Void> task = new FutureTask<Void>(() -> {
            action.run();
            return null;
        });

        if (!controlQueue.offer(task)) {
            return null;
        }

        try {
            scheduleDrain();
            return task;
        } catch (RuntimeException ex) {
            controlQueue.remove(task);
            task.cancel(false);
            throw ex;
        }
    }

    private void scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) {
            return;
        }

        try {
            sharedIoExecutor.execute(this::drainControlQueue);
        } catch (RejectedExecutionException ex) {
            drainScheduled.set(false);
            cancelPendingControl();
            throw controlFailure(
                    FailureReason.OVERLOADED,
                    "Shared antenna I/O executor rejected control work",
                    ex);
        }
    }

    private void drainControlQueue() {
        try {
            FutureTask<Void> task;
            while ((task = controlQueue.poll()) != null) {
                task.run();
            }
        } finally {
            drainScheduled.set(false);
            if (!controlQueue.isEmpty()) {
                try {
                    scheduleDrain();
                } catch (ControlException ignored) {
                    // scheduleDrain already cancels pending work on shared-I/O rejection.
                }
            }
        }
    }

    private void cancelPendingControl() {
        FutureTask<Void> task;
        while ((task = controlQueue.poll()) != null) {
            task.cancel(false);
        }
    }

    private void recordControlFailure(Throwable cause) {
        if (failure == null) {
            failure = cause;
        }
    }

    private static AntennaStatus snapshot(ManagedAntenna managed) {
        return new AntennaStatus(
                managed.installation.antenna(),
                managed.state,
                managed.failure);
    }

    private static List<AntennaInstallation> directInstallations(
            List<Antenna> antennas) {
        if (antennas == null || antennas.isEmpty()) {
            throw new IllegalArgumentException(
                    "antennas must contain at least one antenna");
        }
        List<AntennaInstallation> result =
                new ArrayList<AntennaInstallation>(antennas.size());
        for (Antenna antenna : antennas) {
            if (antenna == null) {
                throw new IllegalArgumentException(
                        "antennas must not contain null");
            }
            result.add(AntennaInstallation.direct(antenna));
        }
        return result;
    }

    private static ControlException controlFailure(
            FailureReason reason,
            String message,
            Throwable cause) {
        return new ControlException(reason, message, cause);
    }
}
