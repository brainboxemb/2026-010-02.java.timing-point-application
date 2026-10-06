package io.github.brainboxemb.eventtiming.timingpoint.application.logic;

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
                () -> calls.add("first.activate"),
                () -> calls.add("first.deactivate"));
        lifecycle.register(
                "second",
                () -> calls.add("second.activate"),
                () -> calls.add("second.deactivate"));

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
                () -> calls.add("first.activate"),
                () -> calls.add("first.deactivate"));
        lifecycle.register(
                "second",
                () -> {
                    calls.add("second.activate");
                    throw failure;
                },
                () -> calls.add("second.deactivate"));

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
}
