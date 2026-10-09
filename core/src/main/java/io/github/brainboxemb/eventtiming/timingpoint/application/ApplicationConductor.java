package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.domain.system.SystemConductor;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates application component lifecycle.
 *
 * <p>Runtime registers each TimingSystem before activation. The application
 * The application conductor activates the system's AntennaManager first and then
 * the Domain SystemConductor. Deactivation is handled in reverse order.</p>
 */
public final class ApplicationConductor {
    private static final Logger LOG =
            LoggerFactory.getLogger(ApplicationConductor.class);

    private final ComponentLifecycleManager componentLifecycle =
            new ComponentLifecycleManager();

    /**
     * Registers one TimingSystem in application activation order.
     */
    public void registerTimingSystem(
            AntennaManager antennaManager,
            SystemConductor systemConductor) {
        if (systemConductor == null) {
            throw new IllegalArgumentException(
                    "systemConductor must not be null");
        }

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
