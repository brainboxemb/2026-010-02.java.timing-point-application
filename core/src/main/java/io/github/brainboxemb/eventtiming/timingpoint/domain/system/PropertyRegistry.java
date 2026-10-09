package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.SourceProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * System-local TimingNode state properties indexed by TimingNode instance.
 *
 * <p>Preserves registration order without exposing mutable collections. The
 * registry owns no source reads, execution or lifecycle.</p>
 */
final class PropertyRegistry implements Iterable<SourceProperty<TimingNode, State>> {
    private final List<SourceProperty<TimingNode, State>> properties =
            new ArrayList<SourceProperty<TimingNode, State>>();
    private final Map<TimingNode, SourceProperty<TimingNode, State>> byNode =
            new IdentityHashMap<TimingNode, SourceProperty<TimingNode, State>>();

    void register(SourceProperty<TimingNode, State> property) {
        if (property == null || byNode.containsKey(property.source())) {
            throw new IllegalArgumentException("Property requires a distinct non-null TimingNode");
        }

        properties.add(property);
        byNode.put(property.source(), property);
    }

    SourceProperty<TimingNode, State> get(TimingNode node) {
        return byNode.get(node);
    }

    @Override
    public Iterator<SourceProperty<TimingNode, State>> iterator() {
        return Collections.unmodifiableList(properties).iterator();
    }
}
