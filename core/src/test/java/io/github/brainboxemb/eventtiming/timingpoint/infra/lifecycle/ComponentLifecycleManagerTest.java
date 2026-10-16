package io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
    public void rollsBackOnlyComponentsThatCompletedActivation() {
        List<String> calls =
                new ArrayList<String>();
        RuntimeException failure =
                new RuntimeException(
                        "second activation failed");
        ComponentLifecycleManager lifecycle =
                new ComponentLifecycleManager();

        lifecycle.register(
                "first",
                new TestComponent(calls, "first", null));
        lifecycle.register(
                "second",
                new TestComponent(calls, "second", failure));

        try {
            lifecycle.activateAll();
            fail("expected activation failure");
        } catch (RuntimeException expected) {
            assertSame(
                    failure,
                    expected);
        }

        assertEquals(
                Arrays.asList(
                        "first.activate",
                        "second.activate",
                        "first.deactivate"),
                calls);

        lifecycle.deactivateAll();
        assertEquals(
                3,
                calls.size());
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
