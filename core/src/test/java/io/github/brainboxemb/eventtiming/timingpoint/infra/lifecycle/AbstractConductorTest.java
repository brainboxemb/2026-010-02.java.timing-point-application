package io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.RunnableSerialLane;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Test;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class AbstractConductorTest {

    @Test
    public void activatesComponentsStartsLaneThenRunsConcreteHook() {
        ExecutorService worker =
                Executors.newSingleThreadExecutor();
        RunnableSerialLane lane =
                new RunnableSerialLane(
                        4,
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
            activate(conductor);
            deactivate(conductor);

            assertEquals(
                    Arrays.asList(
                            "component.activate",
                            "hook",
                            "component.deactivate"),
                    calls);
            assertEquals(
                    RunnableSerialLane.State.STOPPED,
                    lane.state());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void hookFailureClosesLaneAndRollsBackComponents() {
        ExecutorService worker =
                Executors.newSingleThreadExecutor();
        RunnableSerialLane lane =
                new RunnableSerialLane(
                        4,
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
                activate(conductor);
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
                    RunnableSerialLane.State.STOPPED,
                    lane.state());
        } finally {
            worker.shutdownNow();
        }
    }

    private static final class RecordingComponent extends LifecycleComponent {
        private final List<String> calls;

        private RecordingComponent(List<String> calls) {
            this.calls = calls;
        }

        @Override
        protected void onActivate() {
            calls.add("component.activate");
        }

        @Override
        protected void onDeactivate() {
            calls.add("component.deactivate");
        }
    }

    private static final class TestConductor
            extends AbstractConductor {
        private final List<String> calls;
        private final RuntimeException hookFailure;

        private TestConductor(
                RunnableSerialLane lane,
                List<String> calls,
                RuntimeException hookFailure) {
            super("TestConductor", lane);
            this.calls = calls;
            this.hookFailure = hookFailure;

            registerComponent(
                    "test-component",
                    new RecordingComponent(calls));
        }

        @Override
        protected void onActivated() {
            assertEquals(
                    RunnableSerialLane.State.RUNNING,
                    coordinationLane().state());

            calls.add(
                    "hook");

            if (hookFailure != null) {
                throw hookFailure;
            }
        }
    }
}
