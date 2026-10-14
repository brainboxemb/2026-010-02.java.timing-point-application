package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamHandler;

/**
 * Runtime-only result of composing one TimingNode and the child reference needed
 * for external antenna-event wiring.
 */
final class TimingNodeComponents {
    private final TimingNode node;
    private final TagProcessor tagProcessor;
    private final UpstreamHandler upstreamHandler;

    /** Creates the runtime-only references produced while composing one TimingNode. */
    TimingNodeComponents(
            TimingNode node,
            TagProcessor tagProcessor,
            UpstreamHandler upstreamHandler) {
        this.node = node;
        this.tagProcessor = tagProcessor;
        this.upstreamHandler = upstreamHandler;
    }

    /** Returns the composed TimingNode domain owner. */
    TimingNode node() {
        return node;
    }

    /** Returns the TagProcessor reference needed for antenna-event routing. */
    TagProcessor tagProcessor() {
        return tagProcessor;
    }

    /** Returns the node-scoped upstream boundary for runtime I/O wiring. */
    UpstreamHandler upstreamHandler() {
        return upstreamHandler;
    }
}
