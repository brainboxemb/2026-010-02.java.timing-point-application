package io.github.brainboxemb.eventtiming.timingpoint.domain.node;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamHandler;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Collections;

import org.junit.Test;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TimingNodeRaceDataTest {

    /** Verifies sequence 1 applies its assignment and produces an ACK. */
    @Test
    public void firstRaceDataSequenceAppliesAndSendsAck() {
        Fixture fixture = fixture();
        activate(fixture.node);
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
            deactivate(fixture.node);
        }
    }

    /** Verifies a sequence gap leaves state unchanged and requests the first missing item. */
    @Test
    public void gapDoesNotApplyAndSendsReqForFirstMissingSequence() {
        Fixture fixture = fixture();
        activate(fixture.node);
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
            deactivate(fixture.node);
        }
    }

    /** Verifies duplicate RACE_DATA is acknowledged without reapplying its changes. */
    @Test
    public void duplicateSequenceSendsCumulativeAckWithoutApplyingAgain() {
        Fixture fixture = fixture();
        activate(fixture.node);
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
            deactivate(fixture.node);
        }
    }

    /** Verifies an invalid fixed-team override neither advances sequence nor responds. */
    @Test
    public void invalidFixedTeamOverrideDoesNotAdvanceOrRespond() {
        Fixture fixture = fixture();
        activate(fixture.node);
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
            deactivate(fixture.node);
        }
    }

    /** Verifies invalid RACE_DATA applies none of its section changes. */
    @Test
    public void invalidRaceDataIsAtomicAcrossSections() {
        Fixture fixture = fixture();
        activate(fixture.node);
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
                                            "0042",
                                            LocalTime.of(12, 0)))));

            assertEquals(0, fixture.stream.sentCount());
            assertNull(
                    fixture.raceData.stageStartTimeFor(
                            new TeamId("0042")));
        } finally {
            deactivate(fixture.node);
        }
    }

    /** Verifies stage-start changes are applied and deleted in RACE_DATA order. */
    @Test
    public void stageStartTimesFollowRaceDataSequence() {
        Fixture fixture = fixture();
        activate(fixture.node);
        try {
            fixture.receive(
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            1L,
                            Collections.<UpstreamData.TeamAssignmentChange>emptyList(),
                            Collections.singletonList(
                                    DefaultUpstreamData.stageStartSet(
                                            "0042",
                                            LocalTime.of(12, 30)))));
            assertEquals(
                    1L,
                    ((UpstreamData.Ack)
                            fixture.stream.takeSent())
                            .sequence());
            assertEquals(
                    LocalTime.of(12, 30),
                    fixture.raceData.stageStartTimeFor(
                            new TeamId("0042"))
                            .time());

            fixture.receive(
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            2L,
                            Collections.<UpstreamData.TeamAssignmentChange>emptyList(),
                            Collections.singletonList(
                                    DefaultUpstreamData.stageStartDelete(
                                            "0042"))));
            assertEquals(
                    2L,
                    ((UpstreamData.Ack)
                            fixture.stream.takeSent())
                            .sequence());
            assertNull(
                    fixture.raceData.stageStartTimeFor(
                            new TeamId("0042")));
        } finally {
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
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components components =
                TimingNodeFixture.createDirectComponents(
                        new NodeId("A"),
                        new EmptyPersistence(),
                        new DefaultTimingDataFactory(),
                        () -> Instant.parse(
                                "2026-10-13T12:00:00Z"),
                        new DefaultEventData(),
                        stream);
        return new Fixture(
                components.node(),
                stream,
                components.upstreamHandler(),
                components.raceData());
    }

    private static final class Fixture {
        private final TimingNode node;
        private final InMemoryUpstreamStream stream;
        private final UpstreamHandler port;
        private final RaceDataState raceData;

        /** Creates one direct-lane integration fixture for RACE_DATA handling. */
        private Fixture(
                TimingNode node,
                InMemoryUpstreamStream stream,
                UpstreamHandler port,
                RaceDataState raceData) {
            this.node = node;
            this.stream = stream;
            this.port = port;
            this.raceData = raceData;
        }

        /** Delivers one upstream message and verifies that the handler schedules it. */
        private void receive(
                UpstreamData data) {
            stream.receive(data);
            assertTrue(
                    port.inputAvailable());
        }
    }

    private static final class EmptyPersistence
            implements TimingDataPersistence {
        /** Returns an empty persisted TimingData history for RACE_DATA-only tests. */
        @Override
        public LoadResult load() {
            return new LoadResult(
                    Collections.<TimingData>emptyList(),
                    false);
        }

        /** Rejects unexpected TimingData commits in RACE_DATA-only tests. */
        @Override
        public void append(TimingData data) {
            // RACE_DATA-only tests do not commit TimingData.
        }
    }
}
