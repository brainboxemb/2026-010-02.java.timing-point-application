package oss.relay.timing.timingpoint.runtime;

import oss.relay.timing.eventdata.EventData;
import oss.relay.timing.eventdata.TagId;
import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.timingdata.TimingDataTypes.LocationId;
import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.timing.timingdata.TimingTimestamp;
import oss.relay.timing.timingpoint.application.TimingNodeProxy;
import oss.relay.timing.timingpoint.application.TimingNodeStatus;
import oss.relay.timing.timingpoint.domain.node.TimingNodeTypes;
import oss.relay.timing.timingpoint.domain.node.processing.TagProcessingPolicy;
import oss.relay.timing.timingpoint.infra.BuildIdentity;
import oss.relay.timing.timingpoint.io.devices.antenna.AntennaId;
import oss.relay.timing.timingpoint.io.devices.antenna.manager.AntennaSet;
import oss.relay.timing.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import oss.relay.timing.timingpoint.runtime.config.Config;
import oss.relay.timing.timingpoint.runtime.simulation.SimulationRuntime;
import oss.relay.timing.timingpoint.runtime.config.Presentation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Step-5 V03 integration verification.
 *
 * <p>The test deliberately enters registration work through the normal
 * SimulatedAntenna -> TagProcessor -> TimingNode path. It never inserts a
 * registration directly into LogBook or persistence.</p>
 */
public class SimulationRuntimeRecoveryTest {
    private static final AntennaId ANTENNA_ID = new AntennaId("1");
    private static final TagId FIRST_TAG = new TagId("R1001A");
    private static final TagId SECOND_TAG = new TagId("R1002A");
    private static final RegistrationId FIRST_REGISTRATION =
            new RegistrationId("R1001");
    private static final RegistrationId SECOND_REGISTRATION =
            new RegistrationId("R1002");
    private static final TimingTimestamp FIRST_TIME =
            TimingTimestamp.parse("2026-10-07T08:00:00Z");
    private static final TimingTimestamp SECOND_TIME =
            TimingTimestamp.parse("2026-10-07T08:00:01Z");

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void recoversSimulatedRegistrationHistoryAndContinuesSequence()
            throws Exception {
        Path timingDataFile =
                temporaryFolder
                        .getRoot()
                        .toPath()
                        .resolve("v03-timing-data.jsonl");
        TagProcessingPolicy policy =
                new TagProcessingPolicy(
                        Duration.ofMillis(20),
                        Duration.ofMillis(250),
                        Duration.ofMillis(100),
                        Duration.ofMillis(5),
                        8);

        SimulatedAntenna firstAntenna = new SimulatedAntenna();
        TimingApplicationRuntime firstRun =
                runtime(
                        timingDataFile,
                        policy,
                        firstAntenna);

        firstRun.activate();
        try {
            TimingNodeProxy node =
                    firstRun.presentationGateway().timingNode(new NodeId("A"));

            openAndWaitForInventory(
                    firstRun,
                    node,
                    firstAntenna,
                    24);

            TimingRecord firstRegistration =
                    emitAndAwaitRegistration(
                            node,
                            firstAntenna,
                            FIRST_TAG,
                            FIRST_TIME);

            assertEquals(
                    2L,
                    firstRegistration.sequenceNumber());
            assertEquals(
                    TimingRecord.Action.ADD,
                    firstRegistration.action());
            assertEquals(FIRST_REGISTRATION.packedValue(), firstRegistration.registrationId());
            assertEquals(FIRST_TIME.instant().toEpochMilli(), firstRegistration.effectiveTimeMillis());
            assertEquals(
                    2,
                    node.logBookCount());
        } finally {
            firstRun.deactivate();
        }

        SimulatedAntenna secondAntenna = new SimulatedAntenna();
        TimingApplicationRuntime secondRun =
                runtime(
                        timingDataFile,
                        policy,
                        secondAntenna);
        TimingNodeProxy recoveredNode =
                secondRun.presentationGateway().timingNode(new NodeId("A"));
        AtomicInteger recoveryCommitEvents =
                new AtomicInteger();
        recoveredNode
                .NEW_DATA_ENTRY
                .subscribe(
                        ignored ->
                                recoveryCommitEvents.incrementAndGet());

        secondRun.activate();
        try {
            assertEquals(
                    "recovery must not replay committed history as new commit events",
                    0,
                    recoveryCommitEvents.get());

            TimingNodeStatus recoveredStatus =
                    awaitStatus(recoveredNode);
            assertEquals(
                    TimingNodeTypes.FunctionalState.CLOSED,
                    recoveredStatus.state());
            assertFalse(
                    "historical OPEN must not restore operational location",
                    recoveredStatus.hasLocation());
            assertEquals(
                    2,
                    recoveredNode.logBookCount());

            List<TimingRecord> recoveredHistory =
                    history(recoveredNode);
            assertEquals(
                    2,
                    recoveredHistory.size());
            assertOpen(
                    recoveredHistory.get(0),
                    1L,
                    24);
            assertAutomaticRegistration(
                    recoveredHistory.get(1),
                    2L,
                    FIRST_REGISTRATION,
                    FIRST_TIME);

            openAndWaitForInventory(
                    secondRun,
                    recoveredNode,
                    secondAntenna,
                    25);

            TimingRecord secondRegistration =
                    emitAndAwaitRegistration(
                            recoveredNode,
                            secondAntenna,
                            SECOND_TAG,
                            SECOND_TIME);

            assertEquals(
                    "the first post-recovery registration must continue the recovered sequence",
                    4L,
                    secondRegistration.sequenceNumber());
            assertEquals(SECOND_REGISTRATION.packedValue(), secondRegistration.registrationId());
            assertEquals(SECOND_TIME.instant().toEpochMilli(), secondRegistration.effectiveTimeMillis());

            List<TimingRecord> finalHistory =
                    history(recoveredNode);
            assertEquals(
                    4,
                    finalHistory.size());
            assertOpen(
                    finalHistory.get(0),
                    1L,
                    24);
            assertAutomaticRegistration(
                    finalHistory.get(1),
                    2L,
                    FIRST_REGISTRATION,
                    FIRST_TIME);
            assertOpen(
                    finalHistory.get(2),
                    3L,
                    25);
            assertAutomaticRegistration(
                    finalHistory.get(3),
                    4L,
                    SECOND_REGISTRATION,
                    SECOND_TIME);
        } finally {
            secondRun.deactivate();
        }
    }

    private static TimingApplicationRuntime runtime(
            Path timingDataFile,
            TagProcessingPolicy policy,
            SimulatedAntenna antenna) {
        AntennaSet antennaSet =
                new AntennaSet()
                        .add(
                                ANTENNA_ID,
                                antenna);

        return SimulationRuntime.create(
                identity(),
                config(
                        timingDataFile,
                        policy),
                antennaSet,
                eventData());
    }

    private static void openAndWaitForInventory(
            TimingApplicationRuntime application,
            TimingNodeProxy node,
            SimulatedAntenna antenna,
            int locationId)
            throws Exception {
        await(
                application.antennaManager()::isReady,
                1000L);

        node.open(
                new LocationId(
                        locationId));

        await(
                antenna::inventoryRunning,
                1000L);
    }

    private static TimingRecord emitAndAwaitRegistration(
            TimingNodeProxy node,
            SimulatedAntenna antenna,
            TagId tagId,
            TimingTimestamp observedAt)
            throws Exception {
        CountDownLatch committed =
                new CountDownLatch(1);
        AtomicReference<TimingRecord> registration =
                new AtomicReference<TimingRecord>();

        node.NEW_DATA_ENTRY
                .subscribe(
                        data -> {
                            if (data.kind()
                                    == TimingRecord.Kind.AUTOMATIC_REGISTRATION) {
                                registration.set(data);
                                committed.countDown();
                            }
                        });

        antenna.emit(
                tagId,
                -42,
                observedAt.instant().toEpochMilli(),
                System.nanoTime());

        assertTrue(
                "simulated antenna observation did not commit a registration",
                committed.await(
                        2L,
                        TimeUnit.SECONDS));

        return registration.get();
    }

    private static List<TimingRecord> history(
            TimingNodeProxy node) {
        List<TimingRecord> records =
                new ArrayList<TimingRecord>();

        int total =
                node.visitLogBookFrom(
                        1L,
                        16,
                        records::add);

        assertEquals(
                records.size(),
                total);
        return records;
    }

    private static void assertOpen(
            TimingRecord data,
            long sequence,
            int locationId) {
        assertEquals(
                "expected lifecycle OPEN at sequence " + sequence,
                TimingRecord.Kind.NODE_OPEN,
                data.kind());
        assertEquals(
                sequence,
                data.sequenceNumber());
        assertEquals(
                locationId,
                data.locationId());
    }

    private static void assertAutomaticRegistration(
            TimingRecord data,
            long sequence,
            RegistrationId registrationId,
            TimingTimestamp effectiveTime) {
        assertEquals(
                "expected automatic registration at sequence " + sequence,
                TimingRecord.Kind.AUTOMATIC_REGISTRATION,
                data.kind());

        TimingRecord registration = data;
        assertEquals(
                sequence,
                registration.sequenceNumber());
        assertEquals(
                TimingRecord.Action.ADD,
                registration.action());
        assertEquals(
                registrationId.packedValue(),
                registration.registrationId());
        assertEquals(
                effectiveTime.instant().toEpochMilli(),
                registration.effectiveTimeMillis());
    }

    private static EventData eventData() {
        Map<TagId, RegistrationId> registrations =
                new LinkedHashMap<TagId, RegistrationId>();
        registrations.put(
                FIRST_TAG,
                FIRST_REGISTRATION);
        registrations.put(
                SECOND_TAG,
                SECOND_REGISTRATION);
        return new EventData(
                registrations);
    }

    private static Config config(
            Path timingDataFile,
            TagProcessingPolicy policy) {
        Config.TimingNodeConfig timingNode =
                new Config.TimingNodeConfig(
                        new NodeId("A"),
                        timingDataFile,
                        policy);
        Config.TimingSystemConfig timingSystem =
                new Config.TimingSystemConfig(
                        "A",
                        Collections.singletonList(
                                timingNode),
                        Config.REFERENCE_PROVIDER_ID,
                        Config.REFERENCE_PROVIDER_ID);
        return new Config(
                Collections.singletonList(
                        timingSystem),
                new Presentation(
                        null,
                        null),
                null,
                null);
    }

    private static BuildIdentity identity() {
        return BuildIdentity.firstApiVersion(
                "event-timing-app",
                "test-version",
                "abc123def456",
                "verification/step5-v03",
                "local",
                false);
    }

    private static TimingNodeStatus awaitStatus(
            TimingNodeProxy node) {

        return node.status();
    }

    private static void await(
            java.util.function.BooleanSupplier condition,
            long timeoutMillis)
            throws Exception {
        long deadline =
                System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(
                                timeoutMillis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(
                        "condition did not become true before timeout");
            }
            Thread.sleep(
                    5L);
        }
    }
}
