package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;

/**
 * Runtime-only result of composing one TimingNode and the child reference needed
 * for external antenna-event wiring.
 */
final class TimingNodeComponents {
    private final TimingNode node;
    private final TagProcessor tagProcessor;

    TimingNodeComponents(
            TimingNode node,
            TagProcessor tagProcessor) {
        this.node = node;
        this.tagProcessor = tagProcessor;
    }

    TimingNode node() {
        return node;
    }

    TagProcessor tagProcessor() {
        return tagProcessor;
    }
}
