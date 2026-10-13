package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.StageStartTimes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.TeamAssignments;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Derived/read-side stage timing for one TimingNode.
 *
 * <p>The capability never mutates TimingData or live RaceData state. Every
 * calculation uses one already committed TimingData record plus the current
 * EventData/TeamAssignments/StageStartTimes state.</p>
 */
public final class StageTiming {

    public enum Outcome {
        RESOLVED,
        TIMING_DATA_NOT_FOUND,
        NOT_REGISTRATION,
        REVOKED,
        TEAM_UNRESOLVED,
        START_UNRESOLVED
    }

    public static final class Result {
        private final Outcome outcome;
        private final long sequenceNumber;
        private final RegistrationId registrationId;
        private final TeamId teamId;
        private final Instant startInstant;
        private final Duration elapsed;

        private Result(
                Outcome outcome,
                long sequenceNumber,
                RegistrationId registrationId,
                TeamId teamId,
                Instant startInstant,
                Duration elapsed) {
            this.outcome = outcome;
            this.sequenceNumber = sequenceNumber;
            this.registrationId = registrationId;
            this.teamId = teamId;
            this.startInstant = startInstant;
            this.elapsed = elapsed;
        }

        static Result timingDataNotFound(long sequenceNumber) {
            return new Result(
                    Outcome.TIMING_DATA_NOT_FOUND,
                    sequenceNumber,
                    null,
                    null,
                    null,
                    null);
        }

        static Result notRegistration(long sequenceNumber) {
            return new Result(
                    Outcome.NOT_REGISTRATION,
                    sequenceNumber,
                    null,
                    null,
                    null,
                    null);
        }

        static Result revoked(
                long sequenceNumber,
                RegistrationId registrationId) {
            return new Result(
                    Outcome.REVOKED,
                    sequenceNumber,
                    registrationId,
                    null,
                    null,
                    null);
        }

        static Result teamUnresolved(
                long sequenceNumber,
                RegistrationId registrationId) {
            return new Result(
                    Outcome.TEAM_UNRESOLVED,
                    sequenceNumber,
                    registrationId,
                    null,
                    null,
                    null);
        }

        static Result startUnresolved(
                long sequenceNumber,
                RegistrationId registrationId,
                TeamId teamId) {
            return new Result(
                    Outcome.START_UNRESOLVED,
                    sequenceNumber,
                    registrationId,
                    teamId,
                    null,
                    null);
        }

        static Result resolved(
                long sequenceNumber,
                RegistrationId registrationId,
                TeamId teamId,
                Instant startInstant,
                Duration elapsed) {
            return new Result(
                    Outcome.RESOLVED,
                    sequenceNumber,
                    registrationId,
                    teamId,
                    startInstant,
                    elapsed);
        }

        public Outcome outcome() {
            return outcome;
        }

        public boolean resolved() {
            return outcome == Outcome.RESOLVED;
        }

        public long sequenceNumber() {
            return sequenceNumber;
        }

        public RegistrationId registrationId() {
            return registrationId;
        }

        public TeamId teamId() {
            return teamId;
        }

        public Instant startInstant() {
            return startInstant;
        }

        public Duration elapsed() {
            return elapsed;
        }
    }

    private final EventData eventData;
    private final TeamAssignments teamAssignments;
    private final StageStartTimes stageStartTimes;

    StageTiming(
            EventData eventData,
            TeamAssignments teamAssignments,
            StageStartTimes stageStartTimes) {
        if (eventData == null) {
            throw new IllegalArgumentException(
                    "eventData must not be null");
        }
        if (teamAssignments == null) {
            throw new IllegalArgumentException(
                    "teamAssignments must not be null");
        }
        if (stageStartTimes == null) {
            throw new IllegalArgumentException(
                    "stageStartTimes must not be null");
        }
        this.eventData = eventData;
        this.teamAssignments = teamAssignments;
        this.stageStartTimes = stageStartTimes;
    }

    Result calculate(
            TimingData data,
            ZoneId raceTimeZone) {
        if (data == null) {
            throw new IllegalArgumentException(
                    "data must not be null");
        }
        if (raceTimeZone == null) {
            throw new IllegalArgumentException(
                    "raceTimeZone must not be null");
        }

        Registration registration =
                registration(data);
        if (registration == null) {
            return Result.notRegistration(
                    data.sequenceNumber());
        }
        if (registration.action
                == TimingData.RegistrationAction.REV) {
            return Result.revoked(
                    data.sequenceNumber(),
                    registration.registrationId);
        }

        TeamId teamId =
                eventData.teamIdFor(
                        registration.registrationId);
        if (teamId == null) {
            teamId =
                    teamAssignments.teamIdFor(
                            registration.registrationId);
        }
        if (teamId == null) {
            return Result.teamUnresolved(
                    data.sequenceNumber(),
                    registration.registrationId);
        }

        StageStartTimes.StartDefinition start =
                stageStartTimes.startFor(
                        teamId);
        if (start == null) {
            return Result.startUnresolved(
                    data.sequenceNumber(),
                    registration.registrationId,
                    teamId);
        }

        Instant registrationInstant =
                data.effectiveTime().instant();
        Instant startInstant =
                resolveStartInstant(
                        start,
                        registrationInstant,
                        raceTimeZone);

        return Result.resolved(
                data.sequenceNumber(),
                registration.registrationId,
                teamId,
                startInstant,
                Duration.between(
                        startInstant,
                        registrationInstant));
    }

    private static Registration registration(
            TimingData data) {
        if (data instanceof TimingData.AutomaticRegistration) {
            TimingData.AutomaticRegistration automatic =
                    (TimingData.AutomaticRegistration) data;
            return new Registration(
                    automatic.registrationId(),
                    automatic.action());
        }
        if (data instanceof TimingData.ManualRegistration) {
            TimingData.ManualRegistration manual =
                    (TimingData.ManualRegistration) data;
            return new Registration(
                    manual.registrationId(),
                    manual.action());
        }
        return null;
    }

    private static Instant resolveStartInstant(
            StageStartTimes.StartDefinition start,
            Instant registrationInstant,
            ZoneId raceTimeZone) {
        if (start.startDate() != null) {
            return ZonedDateTime.of(
                    start.startDate(),
                    start.startTime(),
                    raceTimeZone)
                    .toInstant();
        }

        LocalDate registrationDate =
                registrationInstant
                        .atZone(raceTimeZone)
                        .toLocalDate();
        ZonedDateTime candidate =
                ZonedDateTime.of(
                        registrationDate,
                        start.startTime(),
                        raceTimeZone);

        if (candidate.toInstant()
                .isAfter(registrationInstant)) {
            candidate =
                    ZonedDateTime.of(
                            registrationDate.minusDays(1L),
                            start.startTime(),
                            raceTimeZone);
        }
        return candidate.toInstant();
    }

    private static final class Registration {
        private final RegistrationId registrationId;
        private final TimingData.RegistrationAction action;

        private Registration(
                RegistrationId registrationId,
                TimingData.RegistrationAction action) {
            this.registrationId = registrationId;
            this.action = action;
        }
    }
}
