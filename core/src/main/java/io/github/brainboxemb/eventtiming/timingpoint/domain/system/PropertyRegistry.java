package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * System-local node state properties, indexed by TimingNode instance.
 *
 * <p>Preserves registration order without exposing mutable collections.
 * Executor selection and coordination remain outside this registry.</p>
 */
final class PropertyRegistry
        implements Iterable<TimingNodeStateProperty> {
    private final List<TimingNodeStateProperty> properties =
            new ArrayList<TimingNodeStateProperty>();
    private final Map<TimingNode, TimingNodeStateProperty> byNode =
            new IdentityHashMap<TimingNode, TimingNodeStateProperty>();

    void register(TimingNodeStateProperty property) {
        if (property == null
                || byNode.containsKey(property.timingNode())) {
            throw new IllegalArgumentException(
                    "Property requires a distinct non-null TimingNode");
        }

        properties.add(property);
        byNode.put(property.timingNode(), property);
    }

    TimingNodeStateProperty get(TimingNode node) {
        return byNode.get(node);
    }

    @Override
    public Iterator<TimingNodeStateProperty> iterator() {
        return Collections.unmodifiableList(properties).iterator();
    }
}
