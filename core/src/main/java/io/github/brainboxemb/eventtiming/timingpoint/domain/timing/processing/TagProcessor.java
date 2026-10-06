package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ConfigurationChange;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Active tag-processing boundary between antenna observations and TimingNode.
 *
 * <p>The antenna callback only performs bounded queue ingress. EventData resolution,
 * accepted-registration duplicate suppression, passage state and TimingNode
 * admission run on one serial execution lane owned by this processor. Duplicate
 * suppression happens after tag-to-registration mapping and before passage
 * aggregation so recently accepted registrations do not create unnecessary
 * burst/RSSI/housekeeping state. Completed passages are handed off with
 * {@link TimingNode#offer(io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeCommand)};
 * TagProcessor never waits for lower-priority TimingNode processing.</p>
 */
public final class TagProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(TagProcessor.class);

    private enum State {
        NEW,
        ACTIVE,
        INACTIVE
    }

    private final TimingNode timingNode;
    private final EventData eventData;
    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final Consumer<ConfigurationChange<TagProcessingPolicy>>
            policyChangeListener;
    private final RegistrationDuplicateFilter duplicateFilter;
    private final TagProcessingMetrics metrics;
    private final TagObservationFilter observationFilter;
    private final SerialScheduledExecutor executor;
    private final ArrayBlockingQueue<TagObservation> inputQueue;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    private volatile State state = State.NEW;
    private volatile SerialScheduledExecutor.ScheduledTask housekeepingTask;

    public TagProcessor(
            TimingNode timingNode,
            EventData eventData,
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics,
            SerialScheduledExecutor executor) {
        this(
                timingNode,
                eventData,
                ReadOnlyConfiguration.fixed(policy),
                monotonicClock,
                metrics,
                executor);
    }

    public TagProcessor(
            TimingNode timingNode,
            EventData eventData,
            ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics,
            SerialScheduledExecutor executor) {
        if (timingNode == null) {
            throw new IllegalArgumentException("timingNode must not be null");
        }
        if (eventData == null) {
            throw new IllegalArgumentException("eventData must not be null");
        }
        if (policyConfiguration == null) {
            throw new IllegalArgumentException(
                    "policyConfiguration must not be null");
        }
        if (monotonicClock == null) {
            throw new IllegalArgumentException("monotonicClock must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }

        TagProcessingPolicy startupPolicy = policyConfiguration.startupValue();
        if (startupPolicy == null) {
            throw new IllegalArgumentException(
                    "policyConfiguration.startupValue must not be null");
        }

        this.timingNode = timingNode;
        this.eventData = eventData;
        this.policyConfiguration = policyConfiguration;
        this.policyChangeListener = this::onPolicyChange;
        this.metrics = metrics;
        this.executor = executor;
        this.inputQueue =
                new ArrayBlockingQueue<>(
                        startupPolicy.observationQueueCapacity());
        duplicateFilter =
                new RegistrationDuplicateFilter(
                        policyConfiguration,
                        monotonicClock);
        observationFilter =
                new TagObservationFilter(
                        policyConfiguration,
                        monotonicClock,
                        metrics,
                        this::processValidObservation);
    }

    public void activate() {
        synchronized (lifecycleLock) {
            if (state != State.NEW) {
                throw new IllegalStateException(
                        "TagProcessor can only activate from NEW; current state=" + state);
            }
            executor.start();
            state = State.ACTIVE;
            policyConfiguration.changes().subscribe(policyChangeListener);
        }
    }

    public void deactivate() {
        boolean drainRemaining;
        synchronized (lifecycleLock) {
            if (state == State.INACTIVE) {
                return;
            }
            if (state == State.NEW) {
                state = State.INACTIVE;
                executor.close();
                inputQueue.clear();
                return;
            }

            state = State.INACTIVE;
            policyConfiguration.changes().unsubscribe(policyChangeListener);
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
     * Antenna tag-observed EventSource callback. It does no mapping/filtering on the provider
     * thread.
     */
    public void onTagObserved(TagObservation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("observation must not be null");
        }

        metrics.recordObservation();

        synchronized (lifecycleLock) {
            if (state != State.ACTIVE) {
                metrics.recordProcessorNotRunning();
                return;
            }
            if (!inputQueue.offer(observation)) {
                metrics.recordObservationQueueFull();
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
            metrics.recordProcessorNotRunning();
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
                if (state == State.ACTIVE && !inputQueue.isEmpty()) {
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
        RegistrationId registrationId = eventData.registrationIdFor(observation.tagId());
        if (registrationId == null) {
            metrics.recordUnmapped();
            return;
        }

        metrics.recordMapped();
        if (duplicateFilter.isDuplicate(registrationId)) {
            metrics.recordDuplicate();
            return;
        }

        observationFilter.add(registrationId, observation);
        ensureHousekeeping();
    }

    private void ensureHousekeeping() {
        if (!hasTimedState()) {
            return;
        }

        synchronized (lifecycleLock) {
            if (state != State.ACTIVE || housekeepingTask != null) {
                return;
            }
            housekeepingTask = executor.scheduleWithFixedDelay(
                    this::housekeeping,
                    policyConfiguration.currentValue().sweepCadenceNanos());
        }
    }

    private void onPolicyChange(
            ConfigurationChange<TagProcessingPolicy> change) {
        synchronized (lifecycleLock) {
            if (state != State.ACTIVE) {
                return;
            }
            if (!executor.execute(() -> applyPolicyChange(change))) {
                LOG.warn("Could not queue TagProcessor policy change");
            }
        }
    }

    private void applyPolicyChange(
            ConfigurationChange<TagProcessingPolicy> change) {
        duplicateFilter.onPolicyChanged();

        if (change.previousValue().sweepCadenceNanos()
                != change.currentValue().sweepCadenceNanos()) {
            synchronized (lifecycleLock) {
                closeHousekeepingLocked();
            }
        }

        /*
         * Evaluate existing timed state immediately against the new policy.
         * This makes shorter quiet/max/duplicate windows effective without
         * waiting for the next old housekeeping interval.
         */
        housekeeping();
        ensureHousekeeping();
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
                        && (state != State.ACTIVE || !hasTimedState())) {
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
        CommandAdmission admission = timingNode.offer(
                TimingNodeCommands.addAutomaticRegistration(
                        registrationId,
                        observedAt));

        if (admission == CommandAdmission.ACCEPTED) {
            duplicateFilter.recordAccepted(registrationId);

            /*
             * add(...) can close an expired previous burst and start a new burst
             * from the observation that triggered that closure before this
             * callback runs. Once the previous passage is accepted, that pending
             * next burst is inside the duplicate window and must not remain as
             * unnecessary timed state.
             */
            observationFilter.discard(registrationId);
        }

        metrics.recordAdmission(admission);
    }
}
