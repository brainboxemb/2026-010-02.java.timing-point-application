package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Ordered collection of distinct TimingNode instances belonging to a system.
 *
 * <p>Membership is based on instance identity. A node is added only once,
 * even when different TimingNodes happen to report the same identifier.</p>
 */
public final class TimingNodeList implements Iterable<TimingNode> {
    private final List<TimingNode> nodes =
            new ArrayList<TimingNode>();
    private final Map<TimingNode, Boolean> members =
            new IdentityHashMap<TimingNode, Boolean>();

    public TimingNodeList add(TimingNode node) {
        if (node == null || members.containsKey(node)) {
            throw new IllegalArgumentException(
                    "TimingNodes must be distinct non-null instances");
        }

        nodes.add(node);
        members.put(node, Boolean.TRUE);
        return this;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public int size() {
        return nodes.size();
    }

    public TimingNode get(int index) {
        return nodes.get(index);
    }

    @Override
    public Iterator<TimingNode> iterator() {
        return Collections.unmodifiableList(nodes).iterator();
    }
}
