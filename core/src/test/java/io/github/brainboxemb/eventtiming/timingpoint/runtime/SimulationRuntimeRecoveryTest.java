package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.AutomaticRegistration;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.NodeOpen;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.RegistrationAction;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeProxy;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeStatus;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSet;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Presentation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
    private static final TagId FIRST_TAG = new TagId("TAG-1001");
    private static final TagId SECOND_TAG = new TagId("TAG-1002");
    private static final RegistrationId FIRST_REGISTRATION =
            new RegistrationId("R-1001");
    private static final RegistrationId SECOND_REGISTRATION =
            new RegistrationId("R-1002");
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

            AutomaticRegistration firstRegistration =
                    emitAndAwaitRegistration(
                            node,
                            firstAntenna,
                            FIRST_TAG,
                            FIRST_TIME);

            assertEquals(
                    2L,
                    firstRegistration.sequenceNumber());
            assertEquals(
                    RegistrationAction.ADD,
                    firstRegistration.action());
            assertEquals(
                    FIRST_REGISTRATION,
                    firstRegistration.registrationId());
            assertEquals(
                    FIRST_TIME,
                    firstRegistration.effectiveTime());
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
                .timingDataCommittedEvent()
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
                    recoveredNode.status();
            assertEquals(
                    TimingNodeTypes.State.CLOSED,
                    recoveredStatus.state());
            assertFalse(
                    "historical OPEN must not restore operational location",
                    recoveredStatus.hasLocation());
            assertEquals(
                    2,
                    recoveredNode.logBookCount());

            List<TimingData> recoveredHistory =
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

            AutomaticRegistration secondRegistration =
                    emitAndAwaitRegistration(
                            recoveredNode,
                            secondAntenna,
                            SECOND_TAG,
                            SECOND_TIME);

            assertEquals(
                    "the first post-recovery registration must continue the recovered sequence",
                    4L,
                    secondRegistration.sequenceNumber());
            assertEquals(
                    SECOND_REGISTRATION,
                    secondRegistration.registrationId());
            assertEquals(
                    SECOND_TIME,
                    secondRegistration.effectiveTime());

            List<TimingData> finalHistory =
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

    private static AutomaticRegistration emitAndAwaitRegistration(
            TimingNodeProxy node,
            SimulatedAntenna antenna,
            TagId tagId,
            TimingTimestamp observedAt)
            throws Exception {
        CountDownLatch committed =
                new CountDownLatch(1);
        AtomicReference<AutomaticRegistration> registration =
                new AtomicReference<AutomaticRegistration>();

        node.timingDataCommittedEvent()
                .subscribe(
                        data -> {
                            if (data instanceof AutomaticRegistration) {
                                registration.set(
                                        (AutomaticRegistration) data);
                                committed.countDown();
                            }
                        });

        antenna.emit(
                tagId,
                -42,
                observedAt);

        assertTrue(
                "simulated antenna observation did not commit a registration",
                committed.await(
                        2L,
                        TimeUnit.SECONDS));

        return registration.get();
    }

    private static List<TimingData> history(
            TimingNodeProxy node) {
        List<TimingData> records =
                new ArrayList<TimingData>();

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
            TimingData data,
            long sequence,
            int locationId) {
        assertTrue(
                "expected lifecycle OPEN at sequence " + sequence,
                data instanceof NodeOpen);
        assertEquals(
                sequence,
                data.sequenceNumber());
        assertEquals(
                new LocationId(
                        locationId),
                data.locationId());
    }

    private static void assertAutomaticRegistration(
            TimingData data,
            long sequence,
            RegistrationId registrationId,
            TimingTimestamp effectiveTime) {
        assertTrue(
                "expected automatic registration at sequence " + sequence,
                data instanceof AutomaticRegistration);

        AutomaticRegistration registration =
                (AutomaticRegistration) data;
        assertEquals(
                sequence,
                registration.sequenceNumber());
        assertEquals(
                RegistrationAction.ADD,
                registration.action());
        assertEquals(
                registrationId,
                registration.registrationId());
        assertEquals(
                effectiveTime,
                registration.effectiveTime());
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
        return new Config(
                new NodeId("A"),
                new Presentation(
                        null,
                        null),
                null,
                null,
                timingDataFile,
                policy);
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
