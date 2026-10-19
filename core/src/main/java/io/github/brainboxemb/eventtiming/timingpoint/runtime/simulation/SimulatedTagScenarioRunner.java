package io.github.brainboxemb.eventtiming.timingpoint.runtime.simulation;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.application.SimulationControl;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime-owned deterministic simulated-tag scenario generator.
 *
 * <p>The runner never submits registrations directly. Every generated
 * observation is emitted through {@link SimulatedAntenna}, after which the
 * ordinary AntennaManager -> TagProcessor -> TimingNode path owns the result.</p>
 */
public final class SimulatedTagScenarioRunner
        implements SimulationControl {
    static final String PROFILE_SIMPLE = "simple";
    static final String PROFILE_NORMAL = "normal";
    static final String PROFILE_EDGE = "edge";

    private static final Logger LOG =
            LoggerFactory.getLogger(
                    SimulatedTagScenarioRunner.class);

    private static final int MAX_ACTIVE_SCENARIOS = 32;

    private final SimulatedAntenna antenna;
    private final EventData eventData;
    private final TimeSource timeSource;
    private final MonotonicClock monotonicClock;
    private final ScheduledExecutorService scheduledExecutor;

    private boolean active;
    private int activeScenarios;
    private long activationGeneration;

    /** Creates the deterministic simulation runner on the supplied JDK scheduler. */
    public SimulatedTagScenarioRunner(
            SimulatedAntenna antenna,
            EventData eventData,
            TimeSource timeSource,
            MonotonicClock monotonicClock,
            ScheduledExecutorService scheduledExecutor) {
        if (antenna == null) {
            throw new IllegalArgumentException(
                    "antenna must not be null");
        }
        if (eventData == null) {
            throw new IllegalArgumentException(
                    "eventData must not be null");
        }
        if (timeSource == null) {
            throw new IllegalArgumentException(
                    "timeSource must not be null");
        }
        if (monotonicClock == null) {
            throw new IllegalArgumentException(
                    "monotonicClock must not be null");
        }
        if (scheduledExecutor == null) {
            throw new IllegalArgumentException(
                    "scheduledExecutor must not be null");
        }
        this.antenna = antenna;
        this.eventData = eventData;
        this.timeSource = timeSource;
        this.monotonicClock = monotonicClock;
        this.scheduledExecutor = scheduledExecutor;
    }

    /** Enables acceptance and scheduling of new deterministic simulation scenarios. */
    public synchronized void activate() {
        if (active) {
            throw new IllegalStateException(
                    "SimulatedTagScenarioRunner is already active");
        }
        activationGeneration++;
        active = true;
    }

    /** Stops new simulation scenarios and clears runtime-owned active-scenario state. */
    public synchronized void deactivate() {
        if (!active) {
            return;
        }
        active = false;
        activeScenarios = 0;
        activationGeneration++;
    }

    /** Starts one named simulated registration scenario when runtime simulation is active. */
    @Override
    public StartResult startRegistration(
            RegistrationId registrationId,
            String profileId) {
        if (registrationId == null) {
            throw new IllegalArgumentException(
                    "registrationId must not be null");
        }
        if (profileId == null
                || profileId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "profileId must not be blank");
        }

        final List<TagId> tags =
                eventData.tagIdsFor(
                        registrationId);
        if (tags.isEmpty()) {
            return StartResult.UNKNOWN_REGISTRATION;
        }

        final Scenario scenario =
                scenario(
                        registrationId,
                        profileId.trim(),
                        tags);
        if (scenario == null) {
            return StartResult.UNKNOWN_PROFILE;
        }

        final long generation;
        synchronized (this) {
            if (!active
                    || !antenna.inventoryRunning()) {
                return StartResult.UNAVAILABLE;
            }
            if (activeScenarios
                    >= MAX_ACTIVE_SCENARIOS) {
                return StartResult.BUSY;
            }
            activeScenarios++;
            generation = activationGeneration;
        }

        long baseEpochMillis =
                timeSource.now().toEpochMilli();
        long baseMonotonicNanos =
                monotonicClock.nowNanos();
        if (!scheduleStep(
                scenario,
                0,
                baseEpochMillis,
                baseMonotonicNanos,
                0L,
                generation)) {
            finishScenario(generation);
            return StartResult.BUSY;
        }

        return StartResult.ACCEPTED;
    }

    /** Returns the number of simulated scenarios that have not finished yet. */
    synchronized int activeScenarioCount() {
        return activeScenarios;
    }

    /** Schedules one scenario step immediately or after its relative delay. */
    private boolean scheduleStep(
            Scenario scenario,
            int index,
            long baseEpochMillis,
            long baseMonotonicNanos,
            long previousOffsetNanos,
            long generation) {
        ObservationStep step =
                scenario.steps.get(
                        index);
        long delayNanos =
                step.offsetNanos
                        - previousOffsetNanos;

        Runnable work =
                () -> runStep(
                        scenario,
                        index,
                        baseEpochMillis,
                        baseMonotonicNanos,
                        generation);

        try {
            if (delayNanos == 0L) {
                scheduledExecutor.execute(work);
            } else {
                scheduledExecutor.schedule(
                        work,
                        delayNanos,
                        TimeUnit.NANOSECONDS);
            }
            return true;
        } catch (RejectedExecutionException ex) {
            return false;
        }
    }

    /** Emits one simulated observation and schedules the next scenario step when present. */
    private void runStep(
            Scenario scenario,
            int index,
            long baseEpochMillis,
            long baseMonotonicNanos,
            long generation) {
        if (!isActive(generation)) {
            return;
        }

        ObservationStep step =
                scenario.steps.get(
                        index);
        try {
            antenna.emit(
                    step.tagId,
                    step.rssi,
                    baseEpochMillis
                            + TimeUnit.NANOSECONDS.toMillis(step.offsetNanos),
                    baseMonotonicNanos
                            + step.offsetNanos);
        } catch (RuntimeException ex) {
            LOG.debug(
                    "Simulated tag scenario stopped while emitting {}",
                    step.tagId,
                    ex);
            finishScenario(generation);
            return;
        }

        int nextIndex =
                index + 1;
        if (nextIndex
                >= scenario.steps.size()) {
            finishScenario(generation);
        } else if (!scheduleStep(
                scenario,
                nextIndex,
                baseEpochMillis,
                baseMonotonicNanos,
                step.offsetNanos,
                generation)) {
            finishScenario(generation);
        }
    }

    /** Returns whether work still belongs to the current active simulation generation. */
    private synchronized boolean isActive(long generation) {
        return active
                && activationGeneration == generation;
    }

    /** Decrements the count only for a scenario from the current activation generation. */
    private synchronized void finishScenario(long generation) {
        if (activationGeneration == generation
                && activeScenarios > 0) {
            activeScenarios--;
        }
    }

    /** Resolves one configured simulation profile into its deterministic observation sequence. */
    private static Scenario scenario(
            RegistrationId registrationId,
            String profileId,
            List<TagId> tags) {
        if (PROFILE_SIMPLE.equals(
                profileId)) {
            return simple(
                    tags);
        }
        if (PROFILE_NORMAL.equals(
                profileId)) {
            return normal(
                    tags);
        }
        if (PROFILE_EDGE.equals(
                profileId)) {
            return edge(
                    registrationId,
                    tags);
        }
        return null;
    }

    /** Builds the short single-tag simulation profile. */
    private static Scenario simple(
            List<TagId> tags) {
        List<ObservationStep> steps =
                new ArrayList<ObservationStep>();
        steps.add(
                step(
                        tags.get(0),
                        -45,
                        0L));
        return new Scenario(
                steps);
    }

    /** Builds the normal multi-observation simulation profile. */
    private static Scenario normal(
            List<TagId> tags) {
        TagId first =
                tags.get(0);
        TagId second =
                tags.size() >= 2
                        ? tags.get(1)
                        : first;

        List<ObservationStep> steps =
                new ArrayList<ObservationStep>();
        steps.add(
                step(first, -60, 0L));
        steps.add(
                step(second, -52, 40L));
        steps.add(
                step(first, -42, 80L));
        steps.add(
                step(second, -50, 120L));
        return new Scenario(
                steps);
    }

    /** Builds the edge-case simulation profile used for timing/filter stress. */
    private static Scenario edge(
            RegistrationId registrationId,
            List<TagId> tags) {
        int variant =
                Math.floorMod(
                        registrationId.type()
                                + registrationId.number(),
                        3);
        TagId first =
                tags.get(0);
        TagId second =
                tags.size() >= 2
                        ? tags.get(1)
                        : first;

        List<ObservationStep> steps =
                new ArrayList<ObservationStep>();
        switch (variant) {
            case 0:
                steps.add(
                        step(first, -48, 0L));
                steps.add(
                        step(second, -43, 5L));
                break;
            case 1:
                /*
                 * Keep the observation gap below the reference quiet timeout
                 * so this represents one deliberately long passage rather
                 * than several unrelated short passages.
                 */
                steps.add(
                        step(first, -60, 0L));
                steps.add(
                        step(second, -56, 200L));
                steps.add(
                        step(first, -52, 400L));
                steps.add(
                        step(second, -48, 600L));
                steps.add(
                        step(first, -44, 800L));
                break;
            default:
                steps.add(
                        step(first, -55, 0L));
                steps.add(
                        step(first, -43, 60L));
                steps.add(
                        step(first, -50, 120L));
                break;
        }
        return new Scenario(
                steps);
    }

    /** Creates one observation step at the supplied relative offset. */
    private static ObservationStep step(
            TagId tagId,
            int rssi,
            long offsetMillis) {
        return new ObservationStep(
                tagId,
                rssi,
                TimeUnit.MILLISECONDS.toNanos(
                        offsetMillis));
    }

    private static final class Scenario {
        private final List<ObservationStep> steps;

        /** Stores one immutable ordered sequence of simulated observation steps. */
        private Scenario(
                List<ObservationStep> steps) {
            this.steps = steps;
        }
    }

    private static final class ObservationStep {
        private final TagId tagId;
        private final int rssi;
        private final long offsetNanos;

        /** Stores one immutable simulated observation and its relative offset. */
        private ObservationStep(
                TagId tagId,
                int rssi,
                long offsetNanos) {
            this.tagId = tagId;
            this.rssi = rssi;
            this.offsetNanos = offsetNanos;
        }
    }
}
