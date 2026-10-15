package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

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

    /** Adds one distinct TimingNode instance and returns this list for composition chaining. */
    public TimingNodeList add(TimingNode node) {
        checkArgument(node != null, "TimingNode must not be null");
        checkArgument(
                !members.containsKey(node),
                "TimingNode instance is already present");

        nodes.add(node);
        members.put(node, Boolean.TRUE);
        return this;
    }

    /** Returns an independent ordered list containing the same TimingNode instances. */
    public TimingNodeList copy() {
        TimingNodeList copy = new TimingNodeList();
        for (TimingNode node : nodes) {
            copy.add(node);
        }
        return copy;
    }

    /** Reports whether the system currently contains no TimingNode instances. */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /** Returns the number of TimingNode instances in composition order. */
    public int size() {
        return nodes.size();
    }

    /** Returns the TimingNode at the requested composition-order index. */
    public TimingNode get(int index) {
        return nodes.get(index);
    }

    /** Iterates over the TimingNodes without exposing list mutation. */
    @Override
    public Iterator<TimingNode> iterator() {
        return Collections.unmodifiableList(nodes).iterator();
    }
}
