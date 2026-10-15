package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RuntimeExecutorsTest {

    @Test
    public void timingNodeDataLanesShareOneExecutorThread() throws Exception {
        RuntimeExecutors runtime = new RuntimeExecutors();
        RuntimeExecutors.TimingNodeExecutionResources first = runtime.createTimingNodeExecutionResources();
        RuntimeExecutors.TimingNodeExecutionResources second = runtime.createTimingNodeExecutionResources();
        runtime.start();

        AtomicReference<String> firstThread = new AtomicReference<String>();
        AtomicReference<String> secondThread = new AtomicReference<String>();
        CountDownLatch done = new CountDownLatch(2);

        first.dataLane().start();
        second.dataLane().start();
        try {
            first.dataLane().execute(() -> {
                        firstThread.set(Thread.currentThread().getName());
                        done.countDown();
                    });
            second.dataLane().execute(() -> {
                        secondThread.set(Thread.currentThread().getName());
                        done.countDown();
                    });

            assertTrue(done.await(1, TimeUnit.SECONDS));
            assertEquals("tp-dl-node", firstThread.get());
            assertEquals(firstThread.get(), secondThread.get());
        } finally {
            first.dataLane().close();
            second.dataLane().close();
            runtime.close();
        }
    }

    @Test
    public void timingNodeDataAndTaskLanesShareTheSameExecutorThread() throws Exception {
        RuntimeExecutors runtime = new RuntimeExecutors();
        RuntimeExecutors.TimingNodeExecutionResources execution =
                runtime.createTimingNodeExecutionResources();
        runtime.start();

        AtomicReference<String> dataThread = new AtomicReference<String>();
        AtomicReference<String> taskThread = new AtomicReference<String>();
        CountDownLatch done = new CountDownLatch(2);

        execution.dataLane().start();
        execution.taskLane().start();
        try {
            execution.dataLane().execute(() -> {
                        dataThread.set(Thread.currentThread().getName());
                        done.countDown();
                    });
            execution.taskLane().execute(() -> {
                        taskThread.set(Thread.currentThread().getName());
                        done.countDown();
                    });

            assertTrue(done.await(1, TimeUnit.SECONDS));
            assertEquals("tp-dl-node", dataThread.get());
            assertEquals(dataThread.get(), taskThread.get());
        } finally {
            execution.taskLane().close();
            execution.dataLane().close();
            runtime.close();
        }
    }

    @Test
    public void timingSystemConductorUsesCoordinationExecutorThread() throws Exception {
        RuntimeExecutors runtime = new RuntimeExecutors();
        SerialLane conductor = runtime.createTimingSystemConductorLane();
        runtime.start();

        AtomicReference<String> threadName = new AtomicReference<String>();
        CountDownLatch done = new CountDownLatch(1);

        conductor.start();
        try {
            conductor.execute(() -> {
                        threadName.set(Thread.currentThread().getName());
                        done.countDown();
                    });

            assertTrue(done.await(1, TimeUnit.SECONDS));
            assertEquals("tp-dl-system-conductor", threadName.get());
        } finally {
            conductor.close();
            runtime.close();
        }
    }

    @Test
    public void antennaControlLanesUseSharedIoExecutorThread() throws Exception {
        RuntimeExecutors runtime = new RuntimeExecutors();
        SerialLane first = runtime.createAntennaControlLane(Duration.ofSeconds(1));
        SerialLane second = runtime.createAntennaControlLane(Duration.ofSeconds(1));
        runtime.start();

        AtomicReference<String> firstThread = new AtomicReference<String>();
        AtomicReference<String> secondThread = new AtomicReference<String>();
        CountDownLatch firstDone =
                new CountDownLatch(1);
        CountDownLatch secondDone =
                new CountDownLatch(1);

        first.start();
        second.start();
        try {
            first.execute(() -> {
                        firstThread.set(Thread.currentThread().getName());
                        firstDone.countDown();
                    });
            assertTrue(firstDone.await(1, TimeUnit.SECONDS));
            assertEquals("tp-io-shared", firstThread.get());

            first.close();

            second.execute(() -> {
                        secondThread.set(Thread.currentThread().getName());
                        secondDone.countDown();
                    });
            assertTrue(secondDone.await(1, TimeUnit.SECONDS));
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
        RuntimeExecutors.TimingNodeExecutionResources first = runtime.createTimingNodeExecutionResources();
        RuntimeExecutors.TimingNodeExecutionResources second = runtime.createTimingNodeExecutionResources();
        runtime.start();

        AtomicReference<String> firstThread = new AtomicReference<String>();
        AtomicReference<String> secondThread = new AtomicReference<String>();
        CountDownLatch done = new CountDownLatch(2);

        first.tagProcessorLane().start();
        second.tagProcessorLane().start();
        try {
            first.tagProcessorLane().execute(() -> {
                        firstThread.set(Thread.currentThread().getName());
                        done.countDown();
                    });
            second.tagProcessorLane().execute(() -> {
                        secondThread.set(Thread.currentThread().getName());
                        done.countDown();
                    });

            assertTrue(done.await(1, TimeUnit.SECONDS));
            assertEquals("tp-dl-tagproc", firstThread.get());
            assertEquals(firstThread.get(), secondThread.get());
        } finally {
            first.tagProcessorLane().close();
            second.tagProcessorLane().close();
            runtime.close();
        }
    }
}
