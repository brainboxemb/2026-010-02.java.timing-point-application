package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RuntimeExecutorsTest {

    @Test
    public void constructionDoesNotStartRuntimeWorkers() {
        RuntimeExecutors runtime = new RuntimeExecutors();
        try {
            assertFalse(runtime.started());
            runtime.start();
            assertTrue(runtime.started());
        } finally {
            runtime.close();
        }
    }

    @Test
    public void timingNodeDataLanesShareOnePhysicalNodeWorker()
            throws Exception {
        RuntimeExecutors runtime = new RuntimeExecutors();
        RuntimeExecutors.TimingNodeLanes first =
                runtime.createTimingNodeLanes();
        RuntimeExecutors.TimingNodeLanes second =
                runtime.createTimingNodeLanes();
        runtime.start();

        AtomicReference<String> firstThread =
                new AtomicReference<String>();
        AtomicReference<String> secondThread =
                new AtomicReference<String>();
        CountDownLatch done = new CountDownLatch(2);

        first.dataLane().start();
        second.dataLane().start();
        try {
            assertEquals(
                    SerialLane.AdmissionResult.ACCEPTED,
                    first.dataLane().offer(() -> {
                        firstThread.set(
                                Thread.currentThread().getName());
                        done.countDown();
                    }));
            assertEquals(
                    SerialLane.AdmissionResult.ACCEPTED,
                    second.dataLane().offer(() -> {
                        secondThread.set(
                                Thread.currentThread().getName());
                        done.countDown();
                    }));

            assertTrue(done.await(1, TimeUnit.SECONDS));
            assertEquals(
                    "tp-dl-node-worker",
                    firstThread.get());
            assertEquals(
                    firstThread.get(),
                    secondThread.get());
        } finally {
            first.dataLane().close();
            second.dataLane().close();
            runtime.close();
        }
    }

    @Test
    public void timingNodeDataAndTaskLanesShareTheSamePhysicalWorker()
            throws Exception {
        RuntimeExecutors runtime = new RuntimeExecutors();
        RuntimeExecutors.TimingNodeLanes lanes =
                runtime.createTimingNodeLanes();
        runtime.start();

        AtomicReference<String> dataThread =
                new AtomicReference<String>();
        AtomicReference<String> taskThread =
                new AtomicReference<String>();
        CountDownLatch done = new CountDownLatch(2);

        lanes.dataLane().start();
        lanes.taskLane().start();
        try {
            assertEquals(
                    SerialLane.AdmissionResult.ACCEPTED,
                    lanes.dataLane().offer(() -> {
                        dataThread.set(
                                Thread.currentThread().getName());
                        done.countDown();
                    }));
            assertEquals(
                    SerialLane.AdmissionResult.ACCEPTED,
                    lanes.taskLane().offer(() -> {
                        taskThread.set(
                                Thread.currentThread().getName());
                        done.countDown();
                    }));

            assertTrue(done.await(1, TimeUnit.SECONDS));
            assertEquals(
                    "tp-dl-node-worker",
                    dataThread.get());
            assertEquals(
                    dataThread.get(),
                    taskThread.get());
        } finally {
            lanes.taskLane().close();
            lanes.dataLane().close();
            runtime.close();
        }
    }

    @Test
    public void systemConductorUsesSharedSystemCoordinationWorker()
            throws Exception {
        RuntimeExecutors runtime = new RuntimeExecutors();
        SerialLane conductor =
                runtime.createSystemConductorLane();
        runtime.start();

        AtomicReference<String> threadName =
                new AtomicReference<String>();
        CountDownLatch done =
                new CountDownLatch(1);

        conductor.start();
        try {
            assertEquals(
                    SerialLane.AdmissionResult.ACCEPTED,
                    conductor.offer(() -> {
                        threadName.set(
                                Thread.currentThread().getName());
                        done.countDown();
                    }));

            assertTrue(
                    done.await(
                            1,
                            TimeUnit.SECONDS));
            assertEquals(
                    "tp-dl-system-worker",
                    threadName.get());
        } finally {
            conductor.close();
            runtime.close();
        }
    }

    @Test
    public void antennaControlLanesUseRuntimeOwnedSharedIoWorkers()
            throws Exception {
        RuntimeExecutors runtime = new RuntimeExecutors();
        SerialLane first =
                runtime.createAntennaControlLane();
        SerialLane second =
                runtime.createAntennaControlLane();
        runtime.start();

        AtomicReference<String> firstThread =
                new AtomicReference<String>();
        AtomicReference<String> secondThread =
                new AtomicReference<String>();
        CountDownLatch firstDone =
                new CountDownLatch(1);
        CountDownLatch secondDone =
                new CountDownLatch(1);

        first.start();
        second.start();
        try {
            assertEquals(
                    SerialLane.AdmissionResult.ACCEPTED,
                    first.offer(() -> {
                        firstThread.set(
                                Thread.currentThread().getName());
                        firstDone.countDown();
                    }));
            assertTrue(
                    firstDone.await(
                            1,
                            TimeUnit.SECONDS));
            assertTrue(
                    firstThread.get()
                            .equals("tp-io-shared-worker"));

            first.close();

            assertEquals(
                    SerialLane.AdmissionResult.ACCEPTED,
                    second.offer(() -> {
                        secondThread.set(
                                Thread.currentThread().getName());
                        secondDone.countDown();
                    }));
            assertTrue(
                    secondDone.await(
                            1,
                            TimeUnit.SECONDS));
            assertTrue(
                    secondThread.get()
                            .startsWith("tp-io-shared-"));
        } finally {
            first.close();
            second.close();
            runtime.close();
        }
    }

    @Test
    public void tagProcessorsShareOnePhysicalScheduledWorker()
            throws Exception {
        RuntimeExecutors runtime = new RuntimeExecutors();
        RuntimeExecutors.TimingNodeLanes first =
                runtime.createTimingNodeLanes();
        RuntimeExecutors.TimingNodeLanes second =
                runtime.createTimingNodeLanes();
        runtime.start();

        AtomicReference<String> firstThread =
                new AtomicReference<String>();
        AtomicReference<String> secondThread =
                new AtomicReference<String>();
        CountDownLatch done = new CountDownLatch(2);

        first.tagProcessor().start();
        second.tagProcessor().start();
        try {
            assertEquals(
                    SerialLane.AdmissionResult.ACCEPTED,
                    first.tagProcessor().offer(() -> {
                        firstThread.set(
                                Thread.currentThread().getName());
                        done.countDown();
                    }));
            assertEquals(
                    SerialLane.AdmissionResult.ACCEPTED,
                    second.tagProcessor().offer(() -> {
                        secondThread.set(
                                Thread.currentThread().getName());
                        done.countDown();
                    }));

            assertTrue(done.await(1, TimeUnit.SECONDS));
            assertEquals(
                    "tp-dl-tagproc-worker",
                    firstThread.get());
            assertEquals(
                    firstThread.get(),
                    secondThread.get());
        } finally {
            first.tagProcessor().close();
            second.tagProcessor().close();
            runtime.close();
        }
    }
}
