package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTaskItem;

/** Marker for one NodeService-owned TimingNode Task Lane item. */
public interface NodeServiceTaskItem extends TimingNodeTaskItem {
    String name();
}
