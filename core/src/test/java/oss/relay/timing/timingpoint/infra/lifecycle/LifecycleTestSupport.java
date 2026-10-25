package oss.relay.timing.timingpoint.infra.lifecycle;

/**
 * Test-only access to infrastructure lifecycle without re-exposing lifecycle
 * methods on production component APIs.
 */
public final class LifecycleTestSupport {

    private LifecycleTestSupport() {
    }

    public static void activate(LifecycleComponent component) {

        component.activateLifecycle();
    }

    public static void deactivate(LifecycleComponent component) {

        component.deactivateLifecycle();
    }
}
