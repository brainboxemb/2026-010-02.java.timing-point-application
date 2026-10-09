package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.DynamicConfiguration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConfigurationControlTest {
    private static final NodeId NODE_ID = new NodeId("A");

    @Test
    public void snapshotDistinguishesStartupCurrentAndMutability() {
        Fixture fixture = new Fixture();

        ConfigurationControl.Snapshot snapshot =
                fixture.control.snapshot();

        assertEquals(1, snapshot.timingNodes().size());
        ConfigurationControl.TimingNodeConfiguration node =
                snapshot.timingNodes().get(0);
        assertEquals(NODE_ID, node.nodeId());

        ConfigurationControl.TagProcessingConfiguration tagProcessing =
                node.tagProcessing();
        assertEquals(
                250L,
                tagProcessing.startup().quietTimeoutMillis());
        assertEquals(
                250L,
                tagProcessing.current().quietTimeoutMillis());
        assertEquals(
                256,
                tagProcessing.current().observationQueueCapacity());
        assertFalse(tagProcessing.overridden());
        assertTrue(tagProcessing.quietTimeoutRuntimeMutable());
        assertTrue(tagProcessing.maxBurstDurationRuntimeMutable());
        assertTrue(tagProcessing.duplicateWindowRuntimeMutable());
        assertTrue(tagProcessing.sweepCadenceRuntimeMutable());
        assertFalse(
                tagProcessing.observationQueueCapacityRuntimeMutable());
    }

    @Test
    public void partialSetUsesAuthoritativeCurrentValueAndEmitsOnce() {
        Fixture fixture = new Fixture();
        AtomicInteger changes = new AtomicInteger();
        AtomicReference<ConfigurationControl.Change> last =
                new AtomicReference<ConfigurationControl.Change>();
        fixture.control.changes().subscribe(change -> {
            changes.incrementAndGet();
            last.set(change);
        });

        ConfigurationControl.Update first =
                fixture.control.setTagProcessing(
                        NODE_ID,
                        patch(300L, null, null, null, null));

        assertEquals(
                ConfigurationControl.UpdateResult.APPLIED,
                first.result());
        assertEquals(
                300L,
                first.tagProcessing().current().quietTimeoutMillis());
        assertEquals(
                50L,
                first.tagProcessing().current().sweepCadenceMillis());
        assertTrue(first.tagProcessing().overridden());
        assertEquals(1, changes.get());

        ConfigurationControl.Update second =
                fixture.control.setTagProcessing(
                        NODE_ID,
                        patch(null, null, null, 75L, null));

        assertEquals(
                ConfigurationControl.UpdateResult.APPLIED,
                second.result());
        assertEquals(
                300L,
                second.tagProcessing().current().quietTimeoutMillis());
        assertEquals(
                75L,
                second.tagProcessing().current().sweepCadenceMillis());
        assertEquals(2, changes.get());
        assertEquals(NODE_ID, last.get().nodeId());
        assertEquals(
                75L,
                last.get().tagProcessing().current().sweepCadenceMillis());

        assertEquals(
                fixture.dynamic.currentValue(),
                policy(300L, 1000L, 15000L, 75L, 256));
    }

    @Test
    public void clearRestoresStartupAndEmitsOnlyWhenApplied() {
        Fixture fixture = new Fixture();
        AtomicInteger changes = new AtomicInteger();
        fixture.control.changes().subscribe(
                ignored -> changes.incrementAndGet());

        assertEquals(
                ConfigurationControl.UpdateResult.APPLIED,
                fixture.control.setTagProcessing(
                        NODE_ID,
                        patch(300L, null, null, null, null))
                        .result());
        assertEquals(
                ConfigurationControl.UpdateResult.APPLIED,
                fixture.control.clearTagProcessing(NODE_ID).result());
        assertEquals(
                ConfigurationControl.UpdateResult.NO_CHANGE,
                fixture.control.clearTagProcessing(NODE_ID).result());

        ConfigurationControl.TagProcessingConfiguration view =
                fixture.control.snapshot()
                        .timingNodes().get(0)
                        .tagProcessing();
        assertFalse(view.overridden());
        assertEquals(
                view.startup().quietTimeoutMillis(),
                view.current().quietTimeoutMillis());
        assertEquals(2, changes.get());
    }

    @Test
    public void invalidAndRestartRequiredLeaveCurrentValueAndEmitNothing() {
        Fixture fixture = new Fixture();
        AtomicInteger changes = new AtomicInteger();
        fixture.control.changes().subscribe(
                ignored -> changes.incrementAndGet());

        ConfigurationControl.Update invalid =
                fixture.control.setTagProcessing(
                        NODE_ID,
                        patch(0L, null, null, null, null));
        assertEquals(
                ConfigurationControl.UpdateResult.INVALID,
                invalid.result());
        assertEquals(
                250L,
                invalid.tagProcessing().current().quietTimeoutMillis());

        ConfigurationControl.Update restart =
                fixture.control.setTagProcessing(
                        NODE_ID,
                        patch(null, null, null, null, 128L));
        assertEquals(
                ConfigurationControl.UpdateResult.RESTART_REQUIRED,
                restart.result());
        assertEquals(
                256,
                restart.tagProcessing().current().observationQueueCapacity());

        assertEquals(0, changes.get());
        assertFalse(fixture.dynamic.overridden());
    }

    @Test
    public void sameEffectiveValueReturnsNoChangeWithoutEvent() {
        Fixture fixture = new Fixture();
        AtomicInteger changes = new AtomicInteger();
        fixture.control.changes().subscribe(
                ignored -> changes.incrementAndGet());

        ConfigurationControl.Update result =
                fixture.control.setTagProcessing(
                        NODE_ID,
                        patch(250L, null, null, null, null));

        assertEquals(
                ConfigurationControl.UpdateResult.NO_CHANGE,
                result.result());
        assertEquals(0, changes.get());
    }

    private static ConfigurationControl.TagProcessingPatch patch(
            Long quiet,
            Long maxBurst,
            Long duplicate,
            Long sweep,
            Long queueCapacity) {
        return new ConfigurationControl.TagProcessingPatch(
                quiet,
                maxBurst,
                duplicate,
                sweep,
                queueCapacity);
    }

    private static TagProcessingPolicy policy(
            long quiet,
            long maxBurst,
            long duplicate,
            long sweep,
            int queueCapacity) {
        return new TagProcessingPolicy(
                Duration.ofMillis(quiet),
                Duration.ofMillis(maxBurst),
                Duration.ofMillis(duplicate),
                Duration.ofMillis(sweep),
                queueCapacity);
    }

    private static final class Fixture {
        private final DynamicConfiguration<TagProcessingPolicy> dynamic =
                DynamicConfiguration.create(
                        TagProcessingPolicy.defaults(),
                        value -> value != null,
                        (startup, candidate) ->
                                startup.observationQueueCapacity()
                                        == candidate.observationQueueCapacity());
        private final ConfigurationControl control;

        private Fixture() {
            Map<NodeId, DynamicConfiguration<TagProcessingPolicy>> values =
                    new LinkedHashMap<
                            NodeId,
                            DynamicConfiguration<TagProcessingPolicy>>();
            values.put(NODE_ID, dynamic);
            control = new ConfigurationControl(values);
        }
    }
}
