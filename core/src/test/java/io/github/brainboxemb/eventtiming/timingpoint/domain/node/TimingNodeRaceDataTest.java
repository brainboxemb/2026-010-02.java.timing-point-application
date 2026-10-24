package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node.NodeUpstream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.ClockTimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;
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

    @Test
    public void firstRaceDataPacketAppliesAndSendsRecv() {
        Fixture fixture = fixture();
        activate(fixture.node);
        activate(fixture.port);
        try {
            fixture.receive(
                    raceDataSet(
                            1L,
                            "R0001",
                            "0042"));

            assertReceive(
                    fixture.stream.takeSent(),
                    1L);
            assertEquals(
                    new TeamId("0042"),
                    fixture.raceData.teamIdFor(
                            new RegistrationId("R0001")));
        } finally {
            deactivate(fixture.port);
            deactivate(fixture.node);
        }
    }

    @Test
    public void packetGapDoesNotApplyAndReportsLastValidPacket() {
        Fixture fixture = fixture();
        activate(fixture.node);
        activate(fixture.port);
        try {
            fixture.receive(
                    raceDataSet(
                            2L,
                            "R0001",
                            "0042"));

            assertReceive(
                    fixture.stream.takeSent(),
                    0L);
            assertNull(
                    fixture.raceData.teamIdFor(
                            new RegistrationId("R0001")));
        } finally {
            deactivate(fixture.port);
            deactivate(fixture.node);
        }
    }

    @Test
    public void duplicatePacketIsConfirmedWithoutApplyingAgain() {
        Fixture fixture = fixture();
        activate(fixture.node);
        activate(fixture.port);
        try {
            UpstreamData packet =
                    raceDataSet(
                            1L,
                            "R0001",
                            "0042");
            fixture.receive(packet);
            fixture.stream.takeSent();

            fixture.receive(packet);

            assertReceive(
                    fixture.stream.takeSent(),
                    1L);
            assertEquals(
                    new TeamId("0042"),
                    fixture.raceData.teamIdFor(
                            new RegistrationId("R0001")));
        } finally {
            deactivate(fixture.port);
            deactivate(fixture.node);
        }
    }

    @Test
    public void invalidFixedTeamOverrideDoesNotConfirmPacket() {
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
                            new RegistrationId("A0001")));
        } finally {
            deactivate(fixture.port);
            deactivate(fixture.node);
        }
    }

    @Test
    public void invalidRaceDataIsAtomicAcrossSections() {
        Fixture fixture = fixture();
        activate(fixture.node);
        activate(fixture.port);
        try {
            fixture.receive(
                    DefaultUpstreamData.createDnPacket(
                            new NodeId("A"),
                            1L,
                            DefaultUpstreamData.createRaceData(
                                    new LocationId(1),
                                    Collections.singletonList(
                                            DefaultUpstreamData.createTeamAssignmentSet(
                                                    "A0001",
                                                    "9999")),
                                    Collections.singletonList(
                                            DefaultUpstreamData.createStageStartSet(
                                                    "R0001",
                                                    LocalTime.of(12, 0))))));

            assertEquals(0, fixture.stream.sentCount());
            assertEquals(
                    new TeamId("0001"),
                    fixture.raceData.teamIdFor(
                            new RegistrationId("A0001")));
            assertNull(
                    fixture.raceData.stageStartFor(
                            new RegistrationId("R0001")));
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
                    DefaultUpstreamData.createDnPacket(
                            new NodeId("A"),
                            1L,
                            DefaultUpstreamData.createRaceData(
                                    new LocationId(1),
                                    Collections.<UpstreamData.TeamAssignmentChange>emptyList(),
                                    Collections.singletonList(
                                            DefaultUpstreamData.createStageStartSet(
                                                    "R0001",
                                                    LocalTime.of(1, 0))))));
            assertReceive(fixture.stream.takeSent(), 1L);

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
                    DefaultUpstreamData.createDnPacket(
                            new NodeId("A"),
                            1L,
                            DefaultUpstreamData.createRaceData(
                                    new LocationId(1),
                                    Collections.<UpstreamData.TeamAssignmentChange>emptyList(),
                                    Collections.singletonList(
                                            DefaultUpstreamData.createStageStartSet(
                                                    "R0001",
                                                    TimingTimestamp.localDateTime(
                                                            LocalDate.of(2026, 10, 23),
                                                            LocalTime.of(1, 0)))))));
            assertReceive(fixture.stream.takeSent(), 1L);

            assertEquals(
                    Long.valueOf(2026_10_22_23_00_00_00L),
                    fixture.raceData.stageStartFor(
                            new RegistrationId("R0001")));
        } finally {
            deactivate(fixture.port);
            deactivate(fixture.node);
        }
    }

    private static UpstreamData raceDataSet(
            long packetNr,
            String registrationId,
            String teamId) {
        return DefaultUpstreamData.createDnPacket(
                new NodeId("A"),
                packetNr,
                DefaultUpstreamData.createRaceData(
                        new LocationId(1),
                        Collections.singletonList(
                                DefaultUpstreamData.createTeamAssignmentSet(
                                        registrationId,
                                        teamId)),
                        Collections.<UpstreamData.StageStartTimeChange>emptyList()));
    }

    private static void assertReceive(
            UpstreamData packet,
            long packetNr) {
        assertSame(
                UpstreamData.Family.RECV,
                packet.family());
        assertEquals(
                packetNr,
                ((UpstreamData.Receive) packet.message())
                        .receivedPacketNr());
    }

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
                        stream.bind(new NodeId("A")));
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

        private void receive(UpstreamData data) {
            stream.receive(data);
        }
    }

    private static final class EmptyPersistence
            implements TimingDataPersistence {
        @Override
        public LoadResult load() {
            return new LoadResult(
                    Collections.<TimingRecord>emptyList(),
                    false);
        }

        @Override
        public void append(TimingRecord record) {
            // RACE_DATA-only tests do not commit TimingData.
        }
    }
}
