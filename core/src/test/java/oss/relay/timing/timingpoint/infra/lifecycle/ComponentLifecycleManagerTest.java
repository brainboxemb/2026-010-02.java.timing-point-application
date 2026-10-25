package oss.relay.timing.timingpoint.infra.lifecycle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class ComponentLifecycleManagerTest {

    @Test
    public void activatesInRegistrationOrderAndDeactivatesInReverseOrder() {
        List<String> calls =
                new ArrayList<String>();
        ComponentLifecycleManager lifecycle =
                new ComponentLifecycleManager();

        lifecycle.register(
                "first",
                new TestComponent(calls, "first", null));
        lifecycle.register(
                "second",
                new TestComponent(calls, "second", null));

        lifecycle.activateAll();
        lifecycle.deactivateAll();

        assertEquals(
                Arrays.asList(
                        "first.activate",
                        "second.activate",
                        "second.deactivate",
                        "first.deactivate"),
                calls);
    }

    @Test
    public void registersLifecycleComponentWithoutPublicLifecycleMethods() {
        List<String> calls = new ArrayList<String>();
        TestComponent component = new TestComponent(calls, "", null);
        ComponentLifecycleManager lifecycle =
                new ComponentLifecycleManager();

        lifecycle.register(component);

        lifecycle.activateAll();
        lifecycle.deactivateAll();

        assertEquals(
                Arrays.asList(
                        "activate",
                        "deactivate"),
                calls);
    }

    @Test
    public void lifecycleComponentOwnsActiveState() {
        List<String> calls = new ArrayList<String>();
        TestComponent component = new TestComponent(calls, "", null);
        ComponentLifecycleManager lifecycle = new ComponentLifecycleManager();

        lifecycle.register(component);
        assertFalse(component.active());

        lifecycle.activateAll();
        assertTrue(component.active());

        lifecycle.deactivateAll();
        assertFalse(component.active());
    }

    @Test
    public void lifecycleComponentOwnsInactiveGuard() {
        RuntimeException failure = new RuntimeException("inactive");
        GuardedComponent component = new GuardedComponent(failure);
        ComponentLifecycleManager lifecycle = new ComponentLifecycleManager();

        try {
            component.command();
            fail("expected inactive failure");
        } catch (RuntimeException expected) {
            assertSame(failure, expected);
        }

        lifecycle.register(component);
        lifecycle.activateAll();
        component.command();
        lifecycle.deactivateAll();
    }

    @Test
    public void activatesAgainAfterDeactivation() {
        List<String> calls = new ArrayList<String>();
        ComponentLifecycleManager lifecycle = new ComponentLifecycleManager();

        lifecycle.register(
                "component",
                new TestComponent(calls, "", null));

        lifecycle.activateAll();
        lifecycle.deactivateAll();
        lifecycle.activateAll();
        lifecycle.deactivateAll();

        assertEquals(Arrays.asList("activate", "deactivate", "activate", "deactivate"), calls);
    }

    @Test
    public void activationFailureDoesNotStopIndependentComponents() {
        List<String> calls =
                new ArrayList<String>();
        RuntimeException failure =
                new RuntimeException(
                        "second activation failed");
        TestComponent first =
                new TestComponent(calls, "first", null);
        TestComponent second =
                new TestComponent(calls, "second", failure);
        TestComponent third =
                new TestComponent(calls, "third", null);
        ComponentLifecycleManager lifecycle =
                new ComponentLifecycleManager();

        lifecycle.register("first", first);
        lifecycle.register("second", second);
        lifecycle.register("third", third);

        lifecycle.activateAll();

        assertEquals(
                Arrays.asList(
                        "first.activate",
                        "second.activate",
                        "third.activate"),
                calls);
        assertTrue(first.active());
        assertFalse(second.active());
        assertTrue(third.active());

        lifecycle.deactivateAll();

        assertEquals(
                Arrays.asList(
                        "first.activate",
                        "second.activate",
                        "third.activate",
                        "third.deactivate",
                        "first.deactivate"),
                calls);
    }
    private static final class GuardedComponent extends LifecycleComponent {
        private GuardedComponent(RuntimeException inactiveFailure) {
            super(() -> inactiveFailure);
        }

        private void command() {
            failIfInactive();
        }

        @Override
        protected void onActivate() {
        }

        @Override
        protected void onDeactivate() {
        }
    }

    private static final class TestComponent extends LifecycleComponent {
        private final List<String> calls;
        private final String prefix;
        private final RuntimeException activationFailure;

        private TestComponent(
                List<String> calls,
                String prefix,
                RuntimeException activationFailure) {
            this.calls = calls;
            this.prefix = prefix;
            this.activationFailure = activationFailure;
        }

        private boolean active() {
            return isActive();
        }

        @Override
        protected void onActivate() {
            calls.add(label("activate"));
            if (activationFailure != null) {
                throw activationFailure;
            }
        }

        @Override
        protected void onDeactivate() {
            calls.add(label("deactivate"));
        }

        private String label(String action) {
            return prefix.isEmpty()
                    ? action
                    : prefix + "." + action;
        }
    }
}
