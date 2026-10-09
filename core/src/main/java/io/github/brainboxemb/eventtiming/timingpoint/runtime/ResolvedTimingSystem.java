package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;

/** Resolved providers and their typed resources for one configured TimingSystem. */
final class ResolvedTimingSystem {
    final Config.TimingSystemConfig configuration;
    final boolean tagScenarioSimulationEnabled;
    final EventData eventData;
    final TimingDataFactory timingDataFactory;
    final TimingDataCodec timingDataCodec;

    ResolvedTimingSystem(
            Config.TimingSystemConfig configuration,
            boolean tagScenarioSimulationEnabled,
            EventData eventData,
            TimingDataFactory timingDataFactory,
            TimingDataCodec timingDataCodec) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        if (eventData == null) {
            throw new IllegalArgumentException("eventData must not be null");
        }
        if (timingDataFactory == null) {
            throw new IllegalArgumentException("timingDataFactory must not be null");
        }
        if (timingDataCodec == null) {
            throw new IllegalArgumentException("timingDataCodec must not be null");
        }

        this.configuration = configuration;
        this.tagScenarioSimulationEnabled = tagScenarioSimulationEnabled;
        this.eventData = eventData;
        this.timingDataFactory = timingDataFactory;
        this.timingDataCodec = timingDataCodec;
    }
}
