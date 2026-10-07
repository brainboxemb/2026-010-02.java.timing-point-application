package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSet;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;


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
            AntennaSet antennaSet,
            EventData eventData) {
        return TimingApplicationRuntime.createSimulation(
                buildIdentity,
                config,
                antennaSet,
                eventData);
    }

    /**
     * Creates one simulator composition whose antenna is also available to the
     * capability-gated simulated-tag scenario control.
     */
    public static TimingApplicationRuntime create(
            BuildIdentity buildIdentity,
            Config config,
            SimulatedAntenna antenna,
            EventData eventData) {
        return TimingApplicationRuntime.createSimulation(
                buildIdentity,
                config,
                antenna,
                eventData);
    }
}
