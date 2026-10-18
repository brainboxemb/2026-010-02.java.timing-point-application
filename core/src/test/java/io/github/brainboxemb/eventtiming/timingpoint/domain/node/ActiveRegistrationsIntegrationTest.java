package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;

import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTime;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.TeamRank;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamHandler;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static org.junit.Assert.assertEquals;

public class ActiveRegistrationsIntegrationTest {

    /** Verifies recovered registrations are recounted against current team assignments. */
    @Test
    public void recoveredAndLaterAssignedRegistrationsUseCurrentTeamResolution() {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components components =
                TimingNodeFixture.createDirectComponents(
                        new NodeId("A"),
                        new StaticPersistence(
                                registration(1L, "RT-A-0001"),
                                registration(2L, "RT-R-0001")),
                        new DefaultTimingDataFactory(),
                        () -> Instant.parse("2026-10-13T12:05:00Z"),
                        new DefaultEventData(),
                        stream);
        TimingNode node =
                components.node();

        activate(node);
        try {
            awaitOperational(node);
            UpstreamHandler port =
                    components.upstreamHandler();

            // RT-A-0001 is fixed by EventData. RT-R-0001 is still unresolved.
            awaitUniqueTeamCount(
                    components.nodeService(),
                    new LocationId(1),
                    1);

            stream.receive(
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            1L,
                            Collections.singletonList(
                                    DefaultUpstreamData.teamAssignmentSet(
                                            "RT-R-0001",
                                            "0042")),
                            Collections.<UpstreamData.StageStartTimeChange>emptyList()));
            org.junit.Assert.assertTrue(
                    port.inputAvailable());
            stream.takeSent();

            // The LogBook is unchanged; the current assignment now resolves the
            // second active RegistrationId to a second unique TeamId.
            awaitUniqueTeamCount(
                    components.nodeService(),
                    new LocationId(1),
                    2);
            assertEquals(
                    Integer.valueOf(2),
                    node.logBook().process(
                            LogBookQueries.timingDataCount()));
        } finally {
            deactivate(node);
        }
    }


    /** Duplicate passages stay visible while team ranking selects one maintained candidate. */
    @Test
    public void recoveredPassagesAndTeamRankingRemainSeparate() {
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        TimingNodeFixture.Components components =
                TimingNodeFixture.createDirectComponents(
                        new NodeId("A"),
                        new StaticPersistence(
                                registrationAt(
                                        1L,
                                        "RT-A-0001",
                                        Instant.parse("2026-10-13T12:00:20Z")),
                                registrationAt(
                                        2L,
                                        "RT-A-0001",
                                        Instant.parse("2026-10-13T12:00:10Z"))),
                        new DefaultTimingDataFactory(),
                        () -> Instant.parse("2026-10-13T12:05:00Z"),
                        new DefaultEventData(),
                        stream);

        activate(components.node());
        try {
            awaitOperational(components.node());

            stream.receive(
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            1L,
                            Collections.<UpstreamData.TeamAssignmentChange>emptyList(),
                            Collections.singletonList(
                                    DefaultUpstreamData.stageStartSet("0001", LocalTime.NOON))));
            org.junit.Assert.assertTrue(components.upstreamHandler().inputAvailable());
            stream.takeSent();

            List<StageTime> passages = new ArrayList<StageTime>();
            components.nodeService().visitStageTimes(ZoneOffset.UTC, 16, passages::add);

            List<TeamRank> ranking = new ArrayList<TeamRank>();
            components.nodeService()
                    .visitTeamRanking(
                            new LocationId(1),
                            ZoneOffset.UTC,
                            16,
                            ranking::add);

            assertEquals(2, passages.size());
            assertEquals(1, ranking.size());
            assertEquals(2L, ranking.get(0).stageTime().sequenceNumber());
        } finally {
            deactivate(components.node());
        }
    }

    /** Verifies recovered TimingData larger than one batch is read completely. */
    @Test
    public void recoveredTimingDataIsReadInBoundedBatches() {
        TimingData[] records = new TimingData[1000];
        for (int index = 0; index < records.length - 1; index++) {
            records[index] =
                    registration(
                            index + 1L,
                            "RT-A-0001");
        }
        records[records.length - 1] =
                registration(
                        records.length,
                        "RT-A-0002");

        TimingNodeFixture.Components components =
                TimingNodeFixture.createDirectComponents(
                        new NodeId("A"),
                        new StaticPersistence(records),
                        new DefaultTimingDataFactory(),
                        () -> Instant.parse("2026-10-13T12:05:00Z"),
                        new DefaultEventData(),
                        new InMemoryUpstreamStream());

        activate(components.node());
        try {
            awaitOperational(components.node());
            awaitUniqueTeamCount(
                    components.nodeService(),
                    new LocationId(1),
                    2);
        } finally {
            deactivate(components.node());
        }
    }

    /** Waits until recovered Task Lane state is available. */
    private static void awaitOperational(TimingNode node) {
        long deadline =
                System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(1L);
        while (node.operationalState()
                        == TimingNodeTypes.OperationalState.INIT
                && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(
                TimingNodeTypes.OperationalState.OK,
                node.operationalState());
    }


    /** Waits until NodeService has caught up to the published LogBook head. */
    private static void awaitUniqueTeamCount(
            NodeService service,
            LocationId locationId,
            int expected) {

        assertEquals(
                expected,
                service.uniqueTeamRegistrationCount(locationId));
    }

    /** Builds one persisted automatic registration for the recovery fixture. */
    private static TimingData registration(long sequence, String registrationId) {

        Instant effectiveTime =
                Instant.parse("2026-10-13T12:00:00Z").plusMillis(sequence);
        return registrationAt(sequence, registrationId, effectiveTime);
    }

    /** Builds one persisted automatic registration at an explicit effective time. */
    private static TimingData registrationAt(
            long sequence,
            String registrationId,
            Instant effectiveTime) {

        TimingTimestamp time = new TimingTimestamp(effectiveTime);
        return new DefaultTimingDataFactory()
                .createAutomaticRegistration(
                        new TimingDataFactory.Context(
                                new NodeId("A"),
                                sequence,
                                new LocationId(1),
                                time,
                                time),
                        new RegistrationId(registrationId));
    }

    private static final class StaticPersistence
            implements TimingDataPersistence {
        private final List<TimingData> records;

        /** Creates immutable persistence containing the supplied recovered records. */
        private StaticPersistence(
                TimingData... records) {
            this.records =
                    Arrays.asList(records);
        }

        /** Returns the configured persisted records without repair. */
        @Override
        public LoadResult load() {
            return new LoadResult(
                    records,
                    false);
        }

        /** Rejects commits because this test only verifies recovery and live assignment changes. */
        @Override
        public void append(TimingData data) {
            throw new AssertionError(
                    "recovery/count test must not append TimingData");
        }
    }
}
