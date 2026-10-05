package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;

import java.util.Collections;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class ApplicationTest {
    @Test
    public void createsSharedBoundaryForConfiguredTimingNode() {
        BuildIdentity identity = identity();
        TimingNode timingNode = timingNode();
        Application application = new Application(identity, timingNode);

        assertSame(identity, application.buildIdentity());
        assertSame(timingNode, application.timingNode());
        assertSame(identity, application.presentationGateway().version());
        assertEquals(Lifecycle.State.NEW, application.state());

        application.start();
        try {
            assertEquals(
                    "TN-01",
                    application.presentationGateway().timingNode().status().timingNodeId().value());
            assertEquals(
                    TimingNodeTypes.Lifecycle.CLOSED,
                    application.presentationGateway().timingNode().status().lifecycle());
        } finally {
            application.close();
        }

        try {
            timingNode.query(TimingNodeQueries.status());
            fail("expected TimingNode to be unavailable after application close");
        } catch (TimingNodeTypes.OperationException expected) {
            assertEquals(
                    TimingNodeTypes.OperationException.Reason.UNAVAILABLE,
                    expected.reason());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingBuildIdentity() {
        new Application(null, timingNode());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingTimingNode() {
        new Application(identity(), null);
    }

    @Test
    public void formatsStableSmokeOutput() {
        assertEquals(
                "timing-application lifecycle OK version=test-version state=STOPPED",
                Application.smokeOutput(identity(), Lifecycle.State.STOPPED));
    }

    private static TimingNode timingNode() {
        return TimingNodeFixture.create(
                new NodeId("TN-01"),
                new NoOpPersistence(),
                () -> TimingTimestamp.parse(
                        "2026-10-02T08:00:00.000000000Z"));
    }

    private static final class NoOpPersistence implements TimingDataPersistence {
        @Override
        public LoadResult load() {
            return new LoadResult(
                    Collections.<TimingData>emptyList(),
                    false);
        }

        @Override
        public void append(TimingData data) {
            // ApplicationTest exercises runtime composition, not persistence.
        }
    }

    private static BuildIdentity identity() {
        return BuildIdentity.firstApiVersion(
                "timing-application",
                "test-version",
                "abc123def456",
                "feature/test",
                "local",
                false);
    }
}
