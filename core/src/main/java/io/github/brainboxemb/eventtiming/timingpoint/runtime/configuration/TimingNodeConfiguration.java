package io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.DynamicConfiguration;

/** Concrete configuration branch owned by one composed TimingNode. */
public final class TimingNodeConfiguration {
    private final DynamicConfiguration<TagProcessingPolicy> tagProcessing;

    TimingNodeConfiguration(
            DynamicConfiguration<TagProcessingPolicy> tagProcessing) {
        if (tagProcessing == null) {
            throw new IllegalArgumentException(
                    "tagProcessing must not be null");
        }
        this.tagProcessing = tagProcessing;
    }

    /**
     * Writable Runtime tree value. Normal component dependencies should narrow
     * this to its ReadOnlyConfiguration super-interface; Application control
     * owns mutation use-cases.
     */
    public DynamicConfiguration<TagProcessingPolicy> tagProcessing() {
        return tagProcessing;
    }
}
