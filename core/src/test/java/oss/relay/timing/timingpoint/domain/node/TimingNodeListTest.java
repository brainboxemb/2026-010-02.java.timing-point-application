package oss.relay.timing.timingpoint.domain.node;

import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import oss.relay.timing.timingpoint.testsupport.TimingNodeFixture;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class TimingNodeListTest {

    @Test
    public void constructorCopiesSourceList() {

        TimingNode first = node("A");
        TimingNode second = node("B");
        List<TimingNode> source = new ArrayList<TimingNode>();
        source.add(first);

        TimingNodeList nodes = new TimingNodeList(source);
        source.add(second);

        assertEquals(1, nodes.size());
        assertSame(first, nodes.get(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorRejectsSameNodeInstanceTwice() {

        TimingNode node = node("A");
        new TimingNodeList(Arrays.asList(node, node));
    }

    /** Creates one direct TimingNode without starting component lifecycle. */
    private static TimingNode node(String id) {

        return TimingNodeFixture.createDirect(
                new NodeId(id),
                new NoOpPersistence(),
                () -> Instant.parse("2026-10-15T12:00:00Z"));
    }

    /** Persistence stub because TimingNodeList tests only collection ownership. */
    private static final class NoOpPersistence implements TimingDataPersistence {

        @Override
        public LoadResult load() {

            return new LoadResult(Collections.<TimingRecord>emptyList(), false);
        }

        @Override
        public void append(TimingRecord record) {
            // TimingNodeList tests do not activate or persist node data.
        }
    }
}
