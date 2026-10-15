package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;

import java.time.Instant;
import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class TimingNodeListTest {

    @Test
    public void builtListIsIndependentFromBuilder() {

        TimingNode first = node("A");
        TimingNode second = node("B");
        TimingNodeList.Builder builder = TimingNodeList.builder().add(first);

        TimingNodeList nodes = builder.build();
        builder.add(second);

        assertEquals(1, nodes.size());
        assertSame(first, nodes.get(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void builderRejectsSameNodeInstanceTwice() {

        TimingNode node = node("A");
        TimingNodeList.builder().add(node).add(node);
    }

    /** Creates one direct TimingNode without starting component lifecycle. */
    private static TimingNode node(String id) {

        return TimingNodeFixture.createDirect(
                new NodeId(id),
                new NoOpPersistence(),
                new DefaultTimingDataFactory(),
                () -> Instant.parse("2026-10-15T12:00:00Z"));
    }

    /** Persistence stub because TimingNodeList tests only collection ownership. */
    private static final class NoOpPersistence implements TimingDataPersistence {

        @Override
        public LoadResult load() {

            return new LoadResult(Collections.<TimingData>emptyList(), false);
        }

        @Override
        public void append(TimingData data) {
            // TimingNodeList tests do not activate or persist node data.
        }
    }
}
