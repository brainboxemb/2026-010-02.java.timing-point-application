package oss.relay.timing.timingpoint.infra.lifecycle;

import oss.relay.timing.timingpoint.platform.execution.tasks.SerialTaskRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;

import org.junit.Test;

import static oss.relay.timing.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static oss.relay.timing.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static org.junit.Assert.assertEquals;

public class AbstractConductorTest {

    @Test
    public void activatesComponentsStartsLaneThenRunsConcreteHook() {
        ScheduledExecutorService worker =
                Executors.newSingleThreadScheduledExecutor();
        SerialTaskRunner lane =
                new SerialTaskRunner(
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
                    false,
                    lane.isRunning());
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
                SerialTaskRunner lane,
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
                    true,
                    coordinationLane().isRunning());

            calls.add(
                    "hook");

            if (hookFailure != null) {
                throw hookFailure;
            }
        }
    }
}
