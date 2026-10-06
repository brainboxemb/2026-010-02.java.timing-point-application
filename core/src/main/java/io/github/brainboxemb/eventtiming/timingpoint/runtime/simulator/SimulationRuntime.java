package io.github.brainboxemb.eventtiming.timingpoint.runtime.simulator;

import io.github.brainboxemb.eventtiming.timingpoint.domain.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaInstallation;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.TimingApplicationRuntime;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;

import java.util.List;

/**
 * Explicit simulator entry point that assembles simulated devices through the
 * same production component graph.
 *
 * <p>Simulation is a composition choice, not a second application runtime.
 * Simulated antennas still feed the real AntennaManager, TagProcessor and
 * TimingNode path.</p>
 */
public final class SimulationRuntime {
    private SimulationRuntime() {
    }

    public static TimingApplicationRuntime create(
            BuildIdentity buildIdentity,
            Config config,
            List<AntennaInstallation> antennaInstallations,
            EventData eventData) {
        return TimingApplicationRuntime.create(
                buildIdentity,
                config,
                antennaInstallations,
                eventData);
    }
}
