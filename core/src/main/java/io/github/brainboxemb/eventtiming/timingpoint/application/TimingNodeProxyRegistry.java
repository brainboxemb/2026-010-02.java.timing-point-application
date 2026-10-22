package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered application registry of TimingNode proxies keyed by NodeId.
 *
 * <p>The registry owns proxy construction, application-wide NodeId uniqueness
 * and composition order. It owns no TimingNode lifecycle or execution.</p>
 */
final class TimingNodeProxyRegistry
        implements Iterable<TimingNodeProxy> {
    private final Map<NodeId, TimingNodeProxy> proxies =
            new LinkedHashMap<NodeId, TimingNodeProxy>();

    TimingNodeProxyRegistry(
            List<TimingNodeProxyComponents> timingNodes) {
        if (timingNodes == null || timingNodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "timingNodes must not be empty");
        }

        for (TimingNodeProxyComponents components : timingNodes) {
            if (components == null) {
                throw new IllegalArgumentException(
                        "timingNodes must not contain null");
            }
            TimingNode timingNode = components.timingNode();
            NodeId nodeId = timingNode.nodeId();
            if (proxies.containsKey(nodeId)) {
                throw new IllegalArgumentException(
                        "Duplicate TimingNode id "
                                + nodeId.value());
            }
            proxies.put(
                    nodeId,
                    new TimingNodeProxy(
                            timingNode,
                            components.nodeService(),
                            components.logBook()));
        }
    }

    TimingNodeProxy get(
            NodeId nodeId) {
        return proxies.get(nodeId);
    }

    int size() {
        return proxies.size();
    }

    List<TimingNodeProxy> values() {
        return Collections.unmodifiableList(
                new ArrayList<TimingNodeProxy>(
                        proxies.values()));
    }

    @Override
    public Iterator<TimingNodeProxy> iterator() {
        Collection<TimingNodeProxy> ordered =
                Collections.unmodifiableCollection(
                        proxies.values());
        return ordered.iterator();
    }
}
