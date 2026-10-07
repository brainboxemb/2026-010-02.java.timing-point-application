package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CooperativeTaskControllerTest {

    @Test
    public void wakeWhileRunningCoalescesIntoOneFollowUpRun() {
        ControlledRunner runner =
                new ControlledRunner();
        CooperativeTaskController controller =
                new CooperativeTaskController(
                        runner,
                        () -> TaskStep.done(),
                        failure -> {
                            throw new AssertionError(
                                    failure);
                        });

        controller.wake();
        controller.wake();
        controller.wake();

        assertEquals(
                1,
                runner.runCount());
        assertTrue(
                controller.isRunning());
        assertTrue(
                controller.wakePending());

        runner.complete(
                0);

        assertEquals(
                2,
                runner.runCount());
        assertTrue(
                controller.isRunning());
        assertFalse(
                controller.wakePending());

        runner.complete(
                1);

        assertFalse(
                controller.isRunning());
        assertFalse(
                controller.wakePending());
    }

    @Test
    public void executionFailureIsReportedWithoutRestart() {
        ControlledRunner runner =
                new ControlledRunner();
        AtomicReference<Throwable> observed =
                new AtomicReference<Throwable>();
        CooperativeTaskController controller =
                new CooperativeTaskController(
                        runner,
                        () -> TaskStep.done(),
                        observed::set);

        controller.wake();
        controller.wake();

        RuntimeException failure =
                new RuntimeException(
                        "boom");
        runner.fail(
                0,
                failure);

        assertSame(
                failure,
                observed.get());
        assertEquals(
                1,
                runner.runCount());
        assertFalse(
                controller.isRunning());
    }

    private static final class ControlledRunner
            implements CooperativeTaskRunner {
        private final List<CompletableFuture<Void>> runs =
                new ArrayList<CompletableFuture<Void>>();

        @Override
        public CompletableFuture<Void> runTask(
                CooperativeTask task) {
            CompletableFuture<Void> run =
                    new CompletableFuture<Void>();
            runs.add(
                    run);
            return run;
        }

        int runCount() {
            return runs.size();
        }

        void complete(
                int index) {
            runs.get(index)
                    .complete(
                            null);
        }

        void fail(
                int index,
                Throwable failure) {
            runs.get(index)
                    .completeExceptionally(
                            failure);
        }
    }
}
