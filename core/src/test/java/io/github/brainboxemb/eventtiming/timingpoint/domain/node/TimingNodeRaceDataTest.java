package io.github.brainboxemb.eventtiming.timingpoint.domain.node;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamHandler;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TimingNodeRaceDataTest {

    @Test
    public void firstRaceDataSequenceAppliesAndSendsAck() {
        Fixture fixture = fixture();
        fixture.node.activate();
        try {
            fixture.receive(
                    raceDataSet(
                            1L,
                            "RT-R-0001",
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
                    fixture.port.teamIdFor(
                            new RegistrationId(
                                    "RT-R-0001")));
        } finally {
            fixture.node.deactivate();
        }
    }

    @Test
    public void gapDoesNotApplyAndSendsReqForFirstMissingSequence() {
        Fixture fixture = fixture();
        fixture.node.activate();
        try {
            fixture.receive(
                    raceDataSet(
                            2L,
                            "RT-R-0001",
                            "0042"));

            UpstreamData.Req req =
                    (UpstreamData.Req)
                            fixture.stream.takeSent();
            assertSame(
                    UpstreamData.Stream.RACE_DATA,
                    req.stream());
            assertEquals(1L, req.fromSequence());
            assertNull(
                    fixture.port.teamIdFor(
                            new RegistrationId(
                                    "RT-R-0001")));
        } finally {
            fixture.node.deactivate();
        }
    }

    @Test
    public void duplicateSequenceSendsCumulativeAckWithoutApplyingAgain() {
        Fixture fixture = fixture();
        fixture.node.activate();
        try {
            fixture.receive(
                    raceDataSet(
                            1L,
                            "RT-R-0001",
                            "0042"));
            fixture.stream.takeSent();

            fixture.receive(
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            1L,
                            Collections.singletonList(
                                    DefaultUpstreamData.teamAssignmentDelete(
                                            "RT-R-0001")),
                            Collections.<UpstreamData.StageStartTimeChange>emptyList()));

            UpstreamData.Ack ack =
                    (UpstreamData.Ack)
                            fixture.stream.takeSent();
            assertEquals(1L, ack.sequence());
            assertEquals(
                    new TeamId("0042"),
                    fixture.port.teamIdFor(
                            new RegistrationId(
                                    "RT-R-0001")));
        } finally {
            fixture.node.deactivate();
        }
    }

    @Test
    public void invalidFixedTeamOverrideDoesNotAdvanceOrRespond() {
        Fixture fixture = fixture();
        fixture.node.activate();
        try {
            fixture.receive(
                    raceDataSet(
                            1L,
                            "RT-A-0001",
                            "9999"));

            assertEquals(0, fixture.stream.sentCount());
            assertEquals(
                    new TeamId("0001"),
                    fixture.port.teamIdFor(
                            new RegistrationId(
                                    "RT-A-0001")));
        } finally {
            fixture.node.deactivate();
        }
    }

    @Test
    public void invalidRaceDataIsAtomicAcrossSections() {
        Fixture fixture = fixture();
        fixture.node.activate();
        try {
            fixture.receive(
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            1L,
                            Collections.singletonList(
                                    DefaultUpstreamData.teamAssignmentSet(
                                            "RT-A-0001",
                                            "9999")),
                            Collections.singletonList(
                                    DefaultUpstreamData.stageStartSet(
                                            "0042",
                                            LocalTime.of(12, 0)))));

            assertEquals(0, fixture.stream.sentCount());
            assertNull(
                    fixture.port.stageStartTimeFor(
                            new TeamId("0042")));
        } finally {
            fixture.node.deactivate();
        }
    }

    @Test
    public void stageStartTimesFollowRaceDataSequence() {
        Fixture fixture = fixture();
        fixture.node.activate();
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
                    fixture.port.stageStartTimeFor(
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
                    fixture.port.stageStartTimeFor(
                            new TeamId("0042")));
        } finally {
            fixture.node.deactivate();
        }
    }

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

    private static Fixture fixture() {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNode node =
                TimingNodeFixture.createDirect(
                        new NodeId("A"),
                        new EmptyPersistence(),
                        new DefaultTimingDataFactory(),
                        () -> Instant.parse(
                                "2026-10-13T12:00:00Z"),
                        new DefaultEventData(),
                        stream);
        return new Fixture(
                node,
                stream);
    }

    private static final class Fixture {
        private final TimingNode node;
        private final InMemoryUpstreamStream stream;
        private final UpstreamHandler port;

        private Fixture(
                TimingNode node,
                InMemoryUpstreamStream stream) {
            this.node = node;
            this.stream = stream;
            this.port =
                    node.upstreamHandler();
        }

        private void receive(
                UpstreamData data) {
            stream.receive(data);
            assertTrue(
                    port.inputAvailable());
        }
    }

    private static final class EmptyPersistence
            implements TimingDataPersistence {
        @Override
        public LoadResult load() {
            return new LoadResult(
                    Collections.<TimingData>emptyList(),
                    false);
        }

        @Override
        public void append(TimingData data) {
            // RACE_DATA-only tests do not commit TimingData.
        }
    }
}
