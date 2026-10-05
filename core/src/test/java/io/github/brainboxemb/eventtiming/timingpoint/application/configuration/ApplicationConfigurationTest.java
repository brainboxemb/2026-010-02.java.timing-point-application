package io.github.brainboxemb.eventtiming.timingpoint.application.configuration;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ApplicationConfigurationTest {

    @Test
    public void startsWithResolvedValueAndNoOverride() {
        TagProcessingPolicy startup = TagProcessingPolicy.defaults();
        ApplicationConfiguration configuration =
                ApplicationConfiguration.singleTimingNode(
                        new NodeId("TN-01"),
                        startup);

        DynamicConfiguration<TagProcessingPolicy> tagProcessing =
                configuration.timingNode(new NodeId("TN-01")).tagProcessing();

        assertSame(startup, tagProcessing.startupValue());
        assertSame(startup, tagProcessing.currentValue());
        assertFalse(tagProcessing.overridden());
    }

    @Test
    public void appliesAndClearsRuntimeOverrideWithOrderedChanges() {
        TagProcessingPolicy startup = TagProcessingPolicy.defaults();
        TagProcessingPolicy override = policy(
                300L,
                1200L,
                16000L,
                75L,
                startup.observationQueueCapacity());

        ApplicationConfiguration configuration =
                ApplicationConfiguration.singleTimingNode(
                        new NodeId("TN-01"),
                        startup);
        DynamicConfiguration<TagProcessingPolicy> tagProcessing =
                configuration.timingNode(new NodeId("TN-01")).tagProcessing();

        AtomicInteger changes = new AtomicInteger();
        AtomicReference<ConfigurationChange<TagProcessingPolicy>> last =
                new AtomicReference<>();
        tagProcessing.changes().subscribe(change -> {
            changes.incrementAndGet();
            last.set(change);
        });

        assertEquals(
                ConfigurationUpdateResult.APPLIED,
                tagProcessing.override(override));
        assertSame(override, tagProcessing.currentValue());
        assertTrue(tagProcessing.overridden());
        assertEquals(1, changes.get());
        assertSame(startup, last.get().previousValue());
        assertSame(override, last.get().currentValue());
        assertEquals(
                ConfigurationChange.Source.RUNTIME_OVERRIDE,
                last.get().source());

        assertEquals(
                ConfigurationUpdateResult.APPLIED,
                tagProcessing.clearOverride());
        assertSame(startup, tagProcessing.currentValue());
        assertFalse(tagProcessing.overridden());
        assertEquals(2, changes.get());
        assertSame(override, last.get().previousValue());
        assertSame(startup, last.get().currentValue());
        assertEquals(
                ConfigurationChange.Source.STARTUP_VALUE_RESTORED,
                last.get().source());
    }

    @Test
    public void rejectsQueueCapacityChangeWithoutPartialUpdateOrEvent() {
        TagProcessingPolicy startup = TagProcessingPolicy.defaults();
        TagProcessingPolicy restartRequired = policy(
                300L,
                1200L,
                16000L,
                75L,
                startup.observationQueueCapacity() + 1);

        ApplicationConfiguration configuration =
                ApplicationConfiguration.singleTimingNode(
                        new NodeId("TN-01"),
                        startup);
        DynamicConfiguration<TagProcessingPolicy> tagProcessing =
                configuration.timingNode(new NodeId("TN-01")).tagProcessing();

        AtomicInteger changes = new AtomicInteger();
        tagProcessing.changes().subscribe(change -> changes.incrementAndGet());

        assertEquals(
                ConfigurationUpdateResult.RESTART_REQUIRED,
                tagProcessing.override(restartRequired));
        assertSame(startup, tagProcessing.currentValue());
        assertFalse(tagProcessing.overridden());
        assertEquals(0, changes.get());
    }

    @Test
    public void nullOverrideIsInvalidAndProducesNoEvent() {
        TagProcessingPolicy startup = TagProcessingPolicy.defaults();
        ApplicationConfiguration configuration =
                ApplicationConfiguration.singleTimingNode(
                        new NodeId("TN-01"),
                        startup);
        DynamicConfiguration<TagProcessingPolicy> tagProcessing =
                configuration.timingNode(new NodeId("TN-01")).tagProcessing();

        AtomicInteger changes = new AtomicInteger();
        tagProcessing.changes().subscribe(change -> changes.incrementAndGet());

        assertEquals(
                ConfigurationUpdateResult.INVALID,
                tagProcessing.override(null));
        assertSame(startup, tagProcessing.currentValue());
        assertFalse(tagProcessing.overridden());
        assertEquals(0, changes.get());
    }

    @Test
    public void sameValueIsNoChangeAndProducesNoEvent() {
        TagProcessingPolicy startup = TagProcessingPolicy.defaults();
        ApplicationConfiguration configuration =
                ApplicationConfiguration.singleTimingNode(
                        new NodeId("TN-01"),
                        startup);
        DynamicConfiguration<TagProcessingPolicy> tagProcessing =
                configuration.timingNode(new NodeId("TN-01")).tagProcessing();

        AtomicInteger changes = new AtomicInteger();
        tagProcessing.changes().subscribe(change -> changes.incrementAndGet());

        assertEquals(
                ConfigurationUpdateResult.NO_CHANGE,
                tagProcessing.override(startup));
        assertEquals(
                ConfigurationUpdateResult.NO_CHANGE,
                tagProcessing.clearOverride());
        assertEquals(0, changes.get());
    }

    private static TagProcessingPolicy policy(
            long quietMillis,
            long maxBurstMillis,
            long duplicateMillis,
            long sweepMillis,
            int queueCapacity) {
        return new TagProcessingPolicy(
                Duration.ofMillis(quietMillis),
                Duration.ofMillis(maxBurstMillis),
                Duration.ofMillis(duplicateMillis),
                Duration.ofMillis(sweepMillis),
                queueCapacity);
    }
}
