package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Coordinates application component lifecycle.
 *
 * <p>Runtime registers each already composed TimingSystem before activation.
 * The application conductor owns only application-level ordering between systems;
 * each TimingSystem owns its own child lifecycle and internal event graph.</p>
 */
public final class ApplicationConductor {
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationConductor.class);

    private final ComponentLifecycleManager componentLifecycle = new ComponentLifecycleManager();

    /** Registers one complete TimingSystem in application activation order. */
    public void registerTimingSystem(TimingSystem timingSystem) {

        checkArgument(timingSystem != null, "timingSystem must not be null");
        componentLifecycle.register(timingSystem, TimingSystem::activate, TimingSystem::deactivate);
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
