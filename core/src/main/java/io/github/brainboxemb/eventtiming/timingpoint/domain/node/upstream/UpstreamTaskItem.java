package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTaskItem;

/** Marker for one upstream-owned TimingNode Task Lane item. */
public interface UpstreamTaskItem extends TimingNodeTaskItem {
    String name();
}
