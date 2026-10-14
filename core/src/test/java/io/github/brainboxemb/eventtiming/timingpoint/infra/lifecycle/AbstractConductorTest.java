package io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class AbstractConductorTest {

    @Test
    public void activatesComponentsStartsLaneThenRunsConcreteHook() {
        ExecutorService worker =
                Executors.newSingleThreadExecutor();
        SerialLane lane =
                new SerialLane(
                        4,
                        "abstract-conductor-test",
                        worker);
        List<String> calls =
                new ArrayList<String>();

        TestConductor conductor =
                new TestConductor(
                        lane,
                        calls,
                        null);

        try {
            conductor.activate();
            conductor.deactivate();

            assertEquals(
                    Arrays.asList(
                            "component.activate",
                            "hook",
                            "component.deactivate"),
                    calls);
            assertEquals(
                    SerialLane.State.STOPPED,
                    lane.state());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void hookFailureClosesLaneAndRollsBackComponents() {
        ExecutorService worker =
                Executors.newSingleThreadExecutor();
        SerialLane lane =
                new SerialLane(
                        4,
                        "abstract-conductor-test",
                        worker);
        List<String> calls =
                new ArrayList<String>();
        RuntimeException failure =
                new RuntimeException(
                        "startup hook failed");

        TestConductor conductor =
                new TestConductor(
                        lane,
                        calls,
                        failure);

        try {
            try {
                conductor.activate();
                fail("expected activation failure");
            } catch (RuntimeException expected) {
                assertSame(
                        failure,
                        expected);
            }

            assertEquals(
                    Arrays.asList(
                            "component.activate",
                            "hook",
                            "component.deactivate"),
                    calls);
            assertEquals(
                    SerialLane.State.STOPPED,
                    lane.state());
        } finally {
            worker.shutdownNow();
        }
    }

    private static final class TestConductor
            extends AbstractConductor {
        private final List<String> calls;
        private final RuntimeException hookFailure;

        private TestConductor(
                SerialLane lane,
                List<String> calls,
                RuntimeException hookFailure) {
            super(lane);
            this.calls = calls;
            this.hookFailure = hookFailure;

            registerComponent(
                    "test-component",
                    () -> calls.add(
                            "component.activate"),
                    () -> calls.add(
                            "component.deactivate"));
        }

        @Override
        protected void onActivated() {
            assertEquals(
                    SerialLane.State.RUNNING,
                    coordinationLane().state());

            calls.add(
                    "hook");

            if (hookFailure != null) {
                throw hookFailure;
            }
        }
    }
}
