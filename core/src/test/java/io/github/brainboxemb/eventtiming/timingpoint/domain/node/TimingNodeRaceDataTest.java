package io.github.brainboxemb.eventtiming.timingpoint.domain.node;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node.NodeUpstream;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.ClockTimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;

import org.junit.Test;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class TimingNodeRaceDataTest {

    /** Verifies sequence 1 applies its assignment and produces an ACK. */
    @Test
    public void firstRaceDataSequenceAppliesAndSendsAck() {
        Fixture fixture = fixture();
        activate(fixture.node);
        activate(fixture.port);
        try {
            fixture.receive(
                    raceDataSet(
                            1L,
                            "R0001",
                            "0042"));

            UpstreamData.Ack ack =
                    (UpstreamData.Ack)
                            fixture.stream.takeSent();
            assertSame(
                    UpstreamData.Stream.RACE_DATA,
                    ack.stream());
            assertEquals(1L, ack.sequence());
            assertEquals(
                    new TeamId("0042"),
                    fixture.raceData.teamIdFor(
                            new RegistrationId(
                                    "R0001")));
        } finally {
            deactivate(fixture.port);
            deactivate(fixture.node);
        }
    }

    /** Verifies a sequence gap leaves state unchanged and requests the first missing item. */
    @Test
    public void gapDoesNotApplyAndSendsReqForFirstMissingSequence() {
        Fixture fixture = fixture();
        activate(fixture.node);
        activate(fixture.port);
        try {
            fixture.receive(
                    raceDataSet(
                            2L,
                            "R0001",
                            "0042"));

            UpstreamData.Req req =
                    (UpstreamData.Req)
                            fixture.stream.takeSent();
            assertSame(
                    UpstreamData.Stream.RACE_DATA,
                    req.stream());
            assertEquals(1L, req.fromSequence());
            assertNull(
                    fixture.raceData.teamIdFor(
                            new RegistrationId(
                                    "R0001")));
        } finally {
            deactivate(fixture.port);
            deactivate(fixture.node);
        }
    }

    /** Verifies duplicate RACE_DATA is acknowledged without reapplying its changes. */
    @Test
    public void duplicateSequenceSendsCumulativeAckWithoutApplyingAgain() {
        Fixture fixture = fixture();
        activate(fixture.node);
        activate(fixture.port);
        try {
            fixture.receive(
                    raceDataSet(
                            1L,
                            "R0001",
                            "0042"));
            fixture.stream.takeSent();

            fixture.receive(
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            1L,
                            Collections.singletonList(
                                    DefaultUpstreamData.teamAssignmentDelete(
                                            "R0001")),
                            Collections.<UpstreamData.StageStartTimeChange>emptyList()));

            UpstreamData.Ack ack =
                    (UpstreamData.Ack)
                            fixture.stream.takeSent();
            assertEquals(1L, ack.sequence());
            assertEquals(
                    new TeamId("0042"),
                    fixture.raceData.teamIdFor(
                            new RegistrationId(
                                    "R0001")));
        } finally {
            deactivate(fixture.port);
            deactivate(fixture.node);
        }
    }

    /** Verifies an invalid fixed-team override neither advances sequence nor responds. */
    @Test
    public void invalidFixedTeamOverrideDoesNotAdvanceOrRespond() {
        Fixture fixture = fixture();
        activate(fixture.node);
        activate(fixture.port);
        try {
            fixture.receive(
                    raceDataSet(
                            1L,
                            "A0001",
                            "9999"));

            assertEquals(0, fixture.stream.sentCount());
            assertEquals(
                    new TeamId("0001"),
                    fixture.raceData.teamIdFor(
                            new RegistrationId(
                                    "A0001")));
        } finally {
            deactivate(fixture.port);
            deactivate(fixture.node);
        }
    }

    /** Verifies invalid RACE_DATA applies none of its section changes. */
    @Test
    public void invalidRaceDataIsAtomicAcrossSections() {
        Fixture fixture = fixture();
        activate(fixture.node);
        activate(fixture.port);
        try {
            fixture.receive(
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            1L,
                            Collections.singletonList(
                                    DefaultUpstreamData.teamAssignmentSet(
                                            "A0001",
                                            "9999")),
                            Collections.singletonList(
                                    DefaultUpstreamData.stageStartSet(
                                            "R0001",
                                            LocalTime.of(12, 0)))));

            assertEquals(0, fixture.stream.sentCount());
            assertEquals(
                    new TeamId("0001"),
                    fixture.raceData.teamIdFor(
                            new RegistrationId("A0001")));
        } finally {
            deactivate(fixture.port);
            deactivate(fixture.node);
        }
    }

    @Test
    public void timeOnlyStageStartUsesConfiguredZoneWithoutAddingDate() {
        TimeSource timeSource =
                new ClockTimeSource(
                        Clock.fixed(
                                Instant.parse("2026-10-23T12:00:00Z"),
                                ZoneId.of("Europe/Amsterdam")));
        Fixture fixture = fixture(timeSource);

        activate(fixture.node);
        activate(fixture.port);
        try {
            fixture.receive(
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            1L,
                            Collections.<UpstreamData.TeamAssignmentChange>emptyList(),
                            Collections.singletonList(
                                    DefaultUpstreamData.stageStartSet(
                                            "R0001",
                                            LocalTime.of(1, 0)))));
            fixture.stream.takeSent();

            assertEquals(
                    Long.valueOf(23_00_00_00L),
                    fixture.raceData.stageStartFor(
                            new RegistrationId("R0001")));
        } finally {
            deactivate(fixture.port);
            deactivate(fixture.node);
        }
    }

    @Test
    public void datedLocalStageStartCarriesUtcDateAcrossPreviousDay() {
        TimeSource timeSource =
                new ClockTimeSource(
                        Clock.fixed(
                                Instant.parse("2026-10-23T12:00:00Z"),
                                ZoneId.of("Europe/Amsterdam")));
        Fixture fixture = fixture(timeSource);

        activate(fixture.node);
        activate(fixture.port);
        try {
            fixture.receive(
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            1L,
                            Collections.<UpstreamData.TeamAssignmentChange>emptyList(),
                            Collections.singletonList(
                                    DefaultUpstreamData.stageStartSet(
                                            "R0001",
                                            TimingTimestamp.localDateTime(
                                                    LocalDate.of(2026, 10, 23),
                                                    LocalTime.of(1, 0))))));
            fixture.stream.takeSent();

            assertEquals(
                    Long.valueOf(2026_10_22_23_00_00_00L),
                    fixture.raceData.stageStartFor(
                            new RegistrationId("R0001")));
        } finally {
            deactivate(fixture.port);
            deactivate(fixture.node);
        }
    }

    /** Builds one RACE_DATA team-assignment update for node A. */
    private static UpstreamData.RaceData raceDataSet(
            long sequence,
            String registrationId,
            String teamId) {
        return DefaultUpstreamData.raceData(
                new NodeId("A"),
                sequence,
                Collections.singletonList(
                        DefaultUpstreamData.teamAssignmentSet(
                                registrationId,
                                teamId)),
                Collections.<UpstreamData.StageStartTimeChange>emptyList());
    }

    /** Creates one same-thread node fixture with an in-memory upstream stream. */
    private static Fixture fixture() {

        return fixture(
                () -> Instant.parse(
                        "2026-10-13T12:00:00Z"));
    }

    private static Fixture fixture(TimeSource timeSource) {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components components =
                TimingNodeFixture.createDirectComponents(
                        new NodeId("A"),
                        new EmptyPersistence(),
                        timeSource,
                        new DefaultEventData(),
                        stream.bind(
                                new NodeId("A")));
        return new Fixture(
                components.node(),
                stream,
                components.nodeUpstream(),
                components.raceData());
    }

    private static final class Fixture {
        private final TimingNode node;
        private final InMemoryUpstreamStream stream;
        private final NodeUpstream port;
        private final RaceDataState raceData;

        /** Creates one direct-lane integration fixture for RACE_DATA handling. */
        private Fixture(
                TimingNode node,
                InMemoryUpstreamStream stream,
                NodeUpstream port,
                RaceDataState raceData) {
            this.node = node;
            this.stream = stream;
            this.port = port;
            this.raceData = raceData;
        }

        /** Delivers one upstream message through the stream event boundary. */
        private void receive(
                UpstreamData data) {
            stream.receive(data);
        }
    }

    private static final class EmptyPersistence
            implements TimingDataPersistence {
        /** Returns an empty persisted TimingData history for RACE_DATA-only tests. */
        @Override
        public LoadResult load() {
            return new LoadResult(
                    Collections.<TimingRecord>emptyList(),
                    false);
        }

        /** Rejects unexpected TimingData commits in RACE_DATA-only tests. */
        @Override
        public void append(TimingRecord record) {
            // RACE_DATA-only tests do not commit TimingData.
        }
    }
}
