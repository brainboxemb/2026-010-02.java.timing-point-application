package oss.relay.eventtiming.timingpoint.runtime;

import oss.relay.eventtiming.eventdata.EventData;
import oss.relay.eventtiming.eventdata.EventDataProvider;
import oss.relay.eventtiming.timingdata.TimingDataProvider;
import oss.relay.eventtiming.timingpoint.infra.extension.ExtensionRegistry;
import oss.relay.eventtiming.timingpoint.runtime.config.Config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Resolves configured provider selections into immutable system runtime data. */
final class TimingSystemDataResolver {
    private static final Logger LOG =
            LoggerFactory.getLogger(
                    TimingSystemDataResolver.class);

    private TimingSystemDataResolver() {
    }

    /** Resolves all configured systems through the discovered extension registry. */
    static TimingSystemResolvedDataList resolveConfigured(
            Config config,
            ExtensionRegistry extensions) {
        if (config == null) {
            throw new IllegalArgumentException(
                    "config must not be null");
        }
        if (extensions == null) {
            throw new IllegalArgumentException(
                    "extensions must not be null");
        }

        TimingSystemResolvedDataList result =
                new TimingSystemResolvedDataList();

        for (Config.TimingSystemConfig timingSystem
                : config.timingSystems()) {
            EventDataProvider eventDataProvider =
                    extensions.eventDataProvider(
                            timingSystem
                                    .eventDataProviderId());
            TimingDataProvider timingDataProvider =
                    extensions.timingDataProvider(
                            timingSystem
                                    .timingDataProviderId());

            LOG.info(
                    "TimingSystem {} selected EventDataProvider id={} implementation={}",
                    timingSystem.timingSystemId(),
                    eventDataProvider.id(),
                    eventDataProvider
                            .getClass()
                            .getName());
            LOG.info(
                    "TimingSystem {} selected TimingDataProvider id={} implementation={}",
                    timingSystem.timingSystemId(),
                    timingDataProvider.id(),
                    timingDataProvider
                            .getClass()
                            .getName());

            result.add(
                    new TimingSystemResolvedData(
                            timingSystem,
                            true,
                            eventDataProvider.createEventData(),
                            timingDataProvider.createCodec()));
        }

        return result;
    }

    /** Resolves the single-system simulation seam from explicitly supplied EventData. */
    static TimingSystemResolvedDataList resolveSimulation(
            Config config,
            boolean tagScenarioSimulationEnabled,
            EventData eventData,
            TimingDataProvider timingDataProvider) {
        if (config == null) {
            throw new IllegalArgumentException(
                    "config must not be null");
        }
        if (config.timingSystems().size() != 1) {
            throw new IllegalArgumentException(
                    "Simulation composition requires exactly one TimingSystem");
        }
        if (eventData == null) {
            throw new IllegalArgumentException(
                    "eventData must not be null");
        }
        if (timingDataProvider == null) {
            throw new IllegalArgumentException(
                    "timingDataProvider must not be null");
        }

        TimingSystemResolvedDataList result =
                new TimingSystemResolvedDataList();
        result.add(
                new TimingSystemResolvedData(
                        config.timingSystems().get(0),
                        tagScenarioSimulationEnabled,
                        eventData,
                            timingDataProvider.createCodec()));
        return result;
    }
}
