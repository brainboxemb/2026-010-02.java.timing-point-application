package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RuntimeExecutorsTest {

    @Test
    public void logBookExecutorsShareOneWorkerThread() throws Exception {
        RuntimeExecutors runtime = new RuntimeExecutors();
        RuntimeExecutors.TimingNodeExecutionResources first =
                runtime.createTimingNodeExecutionResources();
        RuntimeExecutors.TimingNodeExecutionResources second =
                runtime.createTimingNodeExecutionResources();
        runtime.start();

        AtomicReference<String> firstThread = new AtomicReference<String>();
        AtomicReference<String> secondThread = new AtomicReference<String>();
        CountDownLatch done = new CountDownLatch(2);

        try {
            first.logBookDataLaneExecutor().execute(
                    () -> {
                        firstThread.set(Thread.currentThread().getName());
                        done.countDown();
                    });
            second.logBookDataLaneExecutor().execute(
                    () -> {
                        secondThread.set(Thread.currentThread().getName());
                        done.countDown();
                    });

            assertTrue(done.await(1L, TimeUnit.SECONDS));
            assertEquals("tp-dl-node", firstThread.get());
            assertEquals(firstThread.get(), secondThread.get());
        } finally {
            runtime.close();
        }
    }

    @Test
    public void logBookAndComponentLanesShareTheSameExecutorThread() throws Exception {
        RuntimeExecutors runtime = new RuntimeExecutors();
        RuntimeExecutors.TimingNodeExecutionResources execution =
                runtime.createTimingNodeExecutionResources();
        runtime.start();

        AtomicReference<String> dataThread = new AtomicReference<String>();
        AtomicReference<String> taskThread = new AtomicReference<String>();
        CountDownLatch done = new CountDownLatch(2);
        SerialTaskRunner componentLane =
                new SerialTaskRunner(
                        8,
                        "component-owned-test",
                        execution.nodeScheduler());

        componentLane.start();
        try {
            execution.logBookDataLaneExecutor().execute(
                    () -> {
                        dataThread.set(Thread.currentThread().getName());
                        done.countDown();
                    });
            componentLane.runTask(
                    () -> {
                        taskThread.set(Thread.currentThread().getName());
                        done.countDown();
                        return TaskStep.done();
                    });

            assertTrue(done.await(1L, TimeUnit.SECONDS));
            assertEquals("tp-dl-node", dataThread.get());
            assertEquals(dataThread.get(), taskThread.get());
        } finally {
            componentLane.close();
            runtime.close();
        }
    }

    @Test
    public void timingSystemConductorUsesCoordinationExecutorThread() throws Exception {
        RuntimeExecutors runtime = new RuntimeExecutors();
        SerialTaskRunner conductor = runtime.createTimingSystemConductorLane();
        runtime.start();

        AtomicReference<String> threadName = new AtomicReference<String>();
        CountDownLatch done = new CountDownLatch(1);

        conductor.start();
        try {
            conductor.runTask(
                    () -> {
                        threadName.set(Thread.currentThread().getName());
                        done.countDown();
                        return TaskStep.done();
                    });

            assertTrue(done.await(1L, TimeUnit.SECONDS));
            assertEquals("tp-dl-system-conductor", threadName.get());
        } finally {
            conductor.close();
            runtime.close();
        }
    }

    @Test
    public void antennaControlLanesUseSharedIoExecutorThread() throws Exception {
        RuntimeExecutors runtime = new RuntimeExecutors();
        SerialTaskRunner first =
                runtime.createAntennaControlLane(Duration.ofSeconds(1));
        SerialTaskRunner second =
                runtime.createAntennaControlLane(Duration.ofSeconds(1));
        runtime.start();

        AtomicReference<String> firstThread = new AtomicReference<String>();
        AtomicReference<String> secondThread = new AtomicReference<String>();
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);

        first.start();
        second.start();
        try {
            first.runTask(
                    () -> {
                        firstThread.set(Thread.currentThread().getName());
                        firstDone.countDown();
                        return TaskStep.done();
                    });
            assertTrue(firstDone.await(1L, TimeUnit.SECONDS));
            assertEquals("tp-io-shared", firstThread.get());

            first.close();

            second.runTask(
                    () -> {
                        secondThread.set(Thread.currentThread().getName());
                        secondDone.countDown();
                        return TaskStep.done();
                    });
            assertTrue(secondDone.await(1L, TimeUnit.SECONDS));
            assertEquals("tp-io-shared", secondThread.get());
        } finally {
            first.close();
            second.close();
            runtime.close();
        }
    }

    @Test
    public void tagProcessorsShareOneScheduledExecutorThread() throws Exception {
        RuntimeExecutors runtime = new RuntimeExecutors();
        RuntimeExecutors.TimingNodeExecutionResources first =
                runtime.createTimingNodeExecutionResources();
        RuntimeExecutors.TimingNodeExecutionResources second =
                runtime.createTimingNodeExecutionResources();
        runtime.start();

        AtomicReference<String> firstThread = new AtomicReference<String>();
        AtomicReference<String> secondThread = new AtomicReference<String>();
        CountDownLatch done = new CountDownLatch(2);

        try {
            first.tagProcessorScheduler().execute(
                    () -> {
                        firstThread.set(Thread.currentThread().getName());
                        done.countDown();
                    });
            second.tagProcessorScheduler().execute(
                    () -> {
                        secondThread.set(Thread.currentThread().getName());
                        done.countDown();
                    });

            assertTrue(done.await(1L, TimeUnit.SECONDS));
            assertEquals("tp-dl-tagproc", firstThread.get());
            assertEquals(firstThread.get(), secondThread.get());
        } finally {
            runtime.close();
        }
    }
}
