package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Immutable ordered collection of distinct TimingNode instances belonging to a system.
 *
 * <p>Membership is based on instance identity. Composition uses {@link Builder};
 * once built, the collection can be shared safely without defensive copying.</p>
 */
public final class TimingNodeList implements Iterable<TimingNode> {
    private final List<TimingNode> nodes;

    /** Creates one immutable list from builder-owned composition state. */
    private TimingNodeList(List<TimingNode> nodes) {

        this.nodes = Collections.unmodifiableList(new ArrayList<TimingNode>(nodes));
    }

    /** Starts composition of one TimingNode list. */
    public static Builder builder() {

        return new Builder();
    }

    /** Creates one immutable single-node list. */
    public static TimingNodeList of(TimingNode node) {

        return builder().add(node).build();
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

    /** Mutable composition helper; the built TimingNodeList is independent and immutable. */
    public static final class Builder {
        private final List<TimingNode> nodes = new ArrayList<TimingNode>();
        private final Map<TimingNode, Boolean> members = new IdentityHashMap<TimingNode, Boolean>();

        /** Adds one distinct TimingNode instance in composition order. */
        public Builder add(TimingNode node) {

            failIfInvalidArgument(node != null, "TimingNode must not be null");
            failIfInvalidArgument(!members.containsKey(node), "TimingNode instance is already present");
            nodes.add(node);
            members.put(node, Boolean.TRUE);
            return this;
        }

        /** Builds one immutable TimingNodeList snapshot of the composed nodes. */
        public TimingNodeList build() {

            return new TimingNodeList(nodes);
        }
    }
}
