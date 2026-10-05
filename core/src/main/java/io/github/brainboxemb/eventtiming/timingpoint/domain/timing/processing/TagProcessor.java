package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Active tag-processing boundary between antenna observations and TimingNode.
 *
 * <p>The antenna callback only performs bounded queue ingress. Mapping, passage
 * state, duplicate filtering and TimingNode admission run on one serial
 * execution lane owned by this processor.</p>
 */
public final class TagProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(TagProcessor.class);

    private enum State {
        NEW,
        RUNNING,
        STOPPED
    }

    private final TimingNode timingNode;
    private final TagRegistrationMapper mapper;
    private final RegistrationDuplicateFilter duplicateFilter;
    private final TagProcessingCounters counters;
    private final TagObservationFilter observationFilter;
    private final SerialScheduledExecutor executor;
    private final long sweepCadenceNanos;
    private final ArrayBlockingQueue<TagObservation> inputQueue;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    private volatile State state = State.NEW;
    private volatile SerialScheduledExecutor.ScheduledTask housekeepingTask;

    public TagProcessor(
            TimingNode timingNode,
            TagRegistrationMapper mapper,
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock,
            TagProcessingCounters counters,
            SerialScheduledExecutor executor) {
        if (timingNode == null) {
            throw new IllegalArgumentException("timingNode must not be null");
        }
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (monotonicClock == null) {
            throw new IllegalArgumentException("monotonicClock must not be null");
        }
        if (counters == null) {
            throw new IllegalArgumentException("counters must not be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }

        this.timingNode = timingNode;
        this.mapper = mapper;
        this.counters = counters;
        this.executor = executor;
        this.sweepCadenceNanos = policy.sweepCadenceNanos();
        this.inputQueue =
                new ArrayBlockingQueue<>(policy.observationQueueCapacity());
        duplicateFilter =
                new RegistrationDuplicateFilter(policy, monotonicClock);
        observationFilter =
                new TagObservationFilter(
                        policy,
                        monotonicClock,
                        counters,
                        this::processValidObservation);
    }

    public void start() {
        synchronized (lifecycleLock) {
            if (state != State.NEW) {
                throw new IllegalStateException(
                        "TagProcessor can only start from NEW; current state=" + state);
            }
            executor.start();
            state = State.RUNNING;
        }
    }

    public void stop() {
        boolean drainRemaining;
        synchronized (lifecycleLock) {
            if (state == State.STOPPED) {
                return;
            }
            if (state == State.NEW) {
                state = State.STOPPED;
                executor.close();
                inputQueue.clear();
                return;
            }

            state = State.STOPPED;
            closeHousekeepingLocked();
            drainRemaining = !inputQueue.isEmpty();
            if (drainRemaining && !executor.execute(this::drainAll)) {
                inputQueue.clear();
            }
        }

        executor.close();
        inputQueue.clear();
    }

    /**
     * Antenna EventSource callback. It does no mapping/filtering on the provider
     * thread.
     */
    public void onObservation(TagObservation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("observation must not be null");
        }

        counters.recordObservation();

        synchronized (lifecycleLock) {
            if (state != State.RUNNING) {
                counters.recordProcessorNotRunning();
                return;
            }
            if (!inputQueue.offer(observation)) {
                counters.recordObservationQueueFull();
                return;
            }
            scheduleDrainLocked();
        }
    }

    private void scheduleDrainLocked() {
        if (!drainScheduled.compareAndSet(false, true)) {
            return;
        }
        if (!executor.execute(this::drainBatch)) {
            drainScheduled.set(false);
            counters.recordProcessorNotRunning();
        }
    }

    private void drainBatch() {
        try {
            int count = inputQueue.size();
            for (int index = 0; index < count; index++) {
                TagObservation observation = inputQueue.poll();
                if (observation == null) {
                    break;
                }
                processObservationSafely(observation);
            }
        } finally {
            drainScheduled.set(false);
            synchronized (lifecycleLock) {
                if (state == State.RUNNING && !inputQueue.isEmpty()) {
                    scheduleDrainLocked();
                }
            }
        }
    }

    private void drainAll() {
        TagObservation observation;
        while ((observation = inputQueue.poll()) != null) {
            processObservationSafely(observation);
        }
    }

    private void processObservationSafely(TagObservation observation) {
        try {
            processObservation(observation);
        } catch (RuntimeException ex) {
            LOG.warn(
                    "Could not process tag observation {}",
                    observation.tagId().value(),
                    ex);
        }
    }

    private void processObservation(TagObservation observation) {
        RegistrationId registrationId = mapper.map(observation.tagId());
        if (registrationId == null) {
            counters.recordUnmapped();
            return;
        }

        counters.recordMapped();
        observationFilter.add(registrationId, observation);
        ensureHousekeeping();
    }

    private void ensureHousekeeping() {
        if (!hasTimedState()) {
            return;
        }

        synchronized (lifecycleLock) {
            if (state != State.RUNNING || housekeepingTask != null) {
                return;
            }
            housekeepingTask = executor.scheduleWithFixedDelay(
                    this::housekeeping,
                    sweepCadenceNanos);
        }
    }

    private void housekeeping() {
        try {
            observationFilter.periodic();
            duplicateFilter.periodic();
        } catch (RuntimeException ex) {
            LOG.warn("Tag-processing housekeeping failed", ex);
        } finally {
            synchronized (lifecycleLock) {
                if (housekeepingTask != null
                        && (state != State.RUNNING || !hasTimedState())) {
                    closeHousekeepingLocked();
                }
            }
        }
    }

    private boolean hasTimedState() {
        return observationFilter.hasPendingState()
                || duplicateFilter.hasPendingState();
    }

    private void closeHousekeepingLocked() {
        SerialScheduledExecutor.ScheduledTask task = housekeepingTask;
        housekeepingTask = null;
        if (task != null) {
            task.close();
        }
    }

    private void processValidObservation(
            RegistrationId registrationId,
            TimingTimestamp observedAt) {
        RegistrationDuplicateFilter.Result result =
                duplicateFilter.submitIfNew(
                        registrationId,
                        () -> timingNode.submit(
                                TimingNodeCommands.addAutomaticRegistration(
                                        registrationId,
                                        observedAt)));

        switch (result) {
            case DUPLICATE:
                counters.recordDuplicate();
                return;
            case ACCEPTED:
                counters.recordAdmission(CommandAdmission.ACCEPTED);
                return;
            case FULL:
                counters.recordAdmission(CommandAdmission.FULL);
                return;
            case NOT_RUNNING:
                counters.recordAdmission(CommandAdmission.NOT_RUNNING);
                return;
            default:
                throw new IllegalStateException(
                        "Unsupported duplicate-filter result " + result);
        }
    }
}
