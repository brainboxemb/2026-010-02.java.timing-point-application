package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application lifecycle coordinator.
 *
 * <p>The Application Conductor owns activation order, rollback and reverse
 * deactivation of the major application components. TimingSystem behaviour
 * remains inside the Domain system Conductor.</p>
 */
public final class Conductor {
    private static final Logger LOG =
            LoggerFactory.getLogger(Conductor.class);

    private final ComponentLifecycleManager componentLifecycle =
            new ComponentLifecycleManager();

    public Conductor(
            AntennaManager antennaManager,
            io.github.brainboxemb.eventtiming.timingpoint.domain.system.Conductor
                    systemConductor) {
        if (systemConductor == null) {
            throw new IllegalArgumentException(
                    "systemConductor must not be null");
        }

        /*
         * AntennaManager must be active before the TimingSystem Conductor
         * performs its initial inventory reconciliation.
         */
        if (antennaManager != null) {
            componentLifecycle.register(
                    "AntennaManager",
                    antennaManager::activate,
                    antennaManager::deactivate);
        }

        componentLifecycle.register(
                "TimingSystem Conductor",
                systemConductor::activate,
                systemConductor::deactivate);
    }

    public void activate() {
        LOG.info("Starting application");
        componentLifecycle.activateAll();
        LOG.info("Application started");
    }

    public void deactivate() {
        LOG.info("Stopping application");
        componentLifecycle.deactivateAll();
        LOG.info("Application stopped");
    }
}
