package oss.relay.eventtiming.timingpoint.domain.node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static oss.relay.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Immutable ordered collection of distinct TimingNode instances belonging to a system.
 *
 * <p>Membership is based on instance identity. The supplied source list is validated
 * and copied so later caller changes cannot mutate this collection.</p>
 */
public final class TimingNodeList implements Iterable<TimingNode> {
    private final List<TimingNode> nodes;

    /** Creates one immutable list from the supplied TimingNodes in their current order. */
    public TimingNodeList(List<TimingNode> nodes) {

        failIfInvalidArgument(nodes != null, "nodes must not be null");

        List<TimingNode> copy = new ArrayList<TimingNode>(nodes.size());
        Map<TimingNode, Boolean> members =
                new IdentityHashMap<TimingNode, Boolean>();

        for (TimingNode node : nodes) {
            failIfInvalidArgument(node != null, "TimingNode must not be null");
            failIfInvalidArgument(
                    !members.containsKey(node),
                    "TimingNode instance is already present");
            copy.add(node);
            members.put(node, Boolean.TRUE);
        }

        this.nodes = Collections.unmodifiableList(copy);
    }

    /** Reports whether the system contains no TimingNode instances. */
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

    /** Reports whether this immutable list contains the supplied TimingNode instance. */
    public boolean contains(TimingNode node) {

        for (TimingNode candidate : nodes) {
            if (candidate == node) {
                return true;
            }
        }
        return false;
    }

    /** Iterates over the TimingNodes without exposing mutation. */
    @Override
    public Iterator<TimingNode> iterator() {

        return nodes.iterator();
    }
}
