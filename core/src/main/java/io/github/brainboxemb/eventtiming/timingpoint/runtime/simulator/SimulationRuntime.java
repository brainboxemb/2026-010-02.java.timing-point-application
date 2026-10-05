package io.github.brainboxemb.eventtiming.timingpoint.runtime.simulator;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagRegistrationMapper;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaInstallation;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.Application;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.Composition;
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

    public static Application create(
            BuildIdentity buildIdentity,
            Config config,
            List<AntennaInstallation> antennaInstallations,
            TagRegistrationMapper tagRegistrationMapper) {
        return Composition.create(
                buildIdentity,
                config,
                antennaInstallations,
                tagRegistrationMapper);
    }
}
