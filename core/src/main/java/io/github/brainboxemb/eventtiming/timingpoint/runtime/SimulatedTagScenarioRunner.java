package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.application.SimulationControl;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
final class SimulatedTagScenarioRunner
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
    private final SerialScheduledExecutor executor;

    private boolean active;
    private int activeScenarios;

    SimulatedTagScenarioRunner(
            SimulatedAntenna antenna,
            EventData eventData,
            TimeSource timeSource,
            SerialScheduledExecutor executor) {
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
        if (executor == null) {
            throw new IllegalArgumentException(
                    "executor must not be null");
        }
        this.antenna = antenna;
        this.eventData = eventData;
        this.timeSource = timeSource;
        this.executor = executor;
    }

    synchronized void activate() {
        if (active) {
            throw new IllegalStateException(
                    "SimulatedTagScenarioRunner is already active");
        }
        executor.start();
        active = true;
    }

    synchronized void deactivate() {
        if (!active) {
            return;
        }
        active = false;
        activeScenarios = 0;
        executor.close();
    }

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
        }

        Instant baseTime =
                timeSource.now();
        if (!scheduleStep(
                scenario,
                0,
                baseTime,
                0L)) {
            finishScenario();
            return StartResult.BUSY;
        }

        return StartResult.ACCEPTED;
    }

    synchronized int activeScenarioCount() {
        return activeScenarios;
    }

    private boolean scheduleStep(
            Scenario scenario,
            int index,
            Instant baseTime,
            long previousOffsetNanos) {
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
                        baseTime);

        try {
            if (delayNanos == 0L) {
                return executor.execute(
                        work);
            }
            executor.schedule(
                    work,
                    delayNanos);
            return true;
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    private void runStep(
            Scenario scenario,
            int index,
            Instant baseTime) {
        if (!isActive()) {
            finishScenario();
            return;
        }

        ObservationStep step =
                scenario.steps.get(
                        index);
        try {
            antenna.emit(
                    step.tagId,
                    step.rssi,
                    new TimingTimestamp(
                            baseTime.plusNanos(
                                    step.offsetNanos)));
        } catch (RuntimeException ex) {
            LOG.debug(
                    "Simulated tag scenario stopped while emitting {}",
                    step.tagId,
                    ex);
            finishScenario();
            return;
        }

        int nextIndex =
                index + 1;
        if (nextIndex
                >= scenario.steps.size()) {
            finishScenario();
            return;
        }

        if (!scheduleStep(
                scenario,
                nextIndex,
                baseTime,
                step.offsetNanos)) {
            finishScenario();
        }
    }

    private synchronized boolean isActive() {
        return active;
    }

    private synchronized void finishScenario() {
        if (activeScenarios > 0) {
            activeScenarios--;
        }
    }

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

    private static Scenario edge(
            RegistrationId registrationId,
            List<TagId> tags) {
        int variant =
                Math.floorMod(
                        registrationId.value()
                                .hashCode(),
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
                steps.add(
                        step(first, -60, 0L));
                steps.add(
                        step(second, -54, 400L));
                steps.add(
                        step(first, -46, 800L));
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

        private Scenario(
                List<ObservationStep> steps) {
            this.steps = steps;
        }
    }

    private static final class ObservationStep {
        private final TagId tagId;
        private final int rssi;
        private final long offsetNanos;

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
