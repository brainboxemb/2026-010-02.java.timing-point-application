package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;

/**
 * Provider-resolved data required to compose one configured TimingSystem.
 *
 * <p>This is Runtime composition data, not a Domain TimingSystem. Provider
 * selection has already happened before an instance reaches
 * {@link TimingSystemComposer}.</p>
 */
final class TimingSystemResolvedData {
    private final Config.TimingSystemConfig configuration;
    private final boolean tagScenarioSimulationEnabled;
    private final EventData eventData;
    private final TimingDataFactory timingDataFactory;
    private final TimingDataCodec timingDataCodec;

    TimingSystemResolvedData(
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

    String id() {
        return configuration.timingSystemId();
    }

    Config.TimingSystemConfig configuration() {
        return configuration;
    }

    boolean tagScenarioSimulationEnabled() {
        return tagScenarioSimulationEnabled;
    }

    EventData eventData() {
        return eventData;
    }

    TimingDataFactory timingDataFactory() {
        return timingDataFactory;
    }

    TimingDataCodec timingDataCodec() {
        return timingDataCodec;
    }
}
