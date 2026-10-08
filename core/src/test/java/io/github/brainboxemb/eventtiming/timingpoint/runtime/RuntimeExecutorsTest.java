package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

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
    public void timingNodesShareOnePhysicalNodeWorker()
            throws Exception {
        RuntimeExecutors runtime = new RuntimeExecutors();
        RuntimeExecutors.TimingNodeExecutors first =
                runtime.createTimingNodeExecutors();
        RuntimeExecutors.TimingNodeExecutors second =
                runtime.createTimingNodeExecutors();
        runtime.start();

        AtomicReference<String> firstThread =
                new AtomicReference<String>();
        AtomicReference<String> secondThread =
                new AtomicReference<String>();
        CountDownLatch done = new CountDownLatch(2);

        first.timingNode().start();
        second.timingNode().start();
        try {
            assertEquals(
                    SerialExecutor.AdmissionResult.ACCEPTED,
                    first.timingNode().offer(() -> {
                        firstThread.set(
                                Thread.currentThread().getName());
                        done.countDown();
                    }));
            assertEquals(
                    SerialExecutor.AdmissionResult.ACCEPTED,
                    second.timingNode().offer(() -> {
                        secondThread.set(
                                Thread.currentThread().getName());
                        done.countDown();
                    }));

            assertTrue(done.await(1, TimeUnit.SECONDS));
            assertEquals(
                    "tp-dml-node-worker",
                    firstThread.get());
            assertEquals(
                    firstThread.get(),
                    secondThread.get());
        } finally {
            first.timingNode().close();
            second.timingNode().close();
            runtime.close();
        }
    }

    @Test
    public void conductorUsesSharedSystemCoordinationWorker()
            throws Exception {
        RuntimeExecutors runtime = new RuntimeExecutors();
        SerialExecutor conductor =
                runtime.createConductorExecutor();
        runtime.start();

        AtomicReference<String> threadName =
                new AtomicReference<String>();
        CountDownLatch done =
                new CountDownLatch(1);

        conductor.start();
        try {
            assertEquals(
                    SerialExecutor.AdmissionResult.ACCEPTED,
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
                    "tp-system-coord-worker",
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
        SerialScheduledExecutor first =
                runtime.createAntennaControlExecutor();
        SerialScheduledExecutor second =
                runtime.createAntennaControlExecutor();
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
            assertTrue(
                    first.execute(() -> {
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

            assertTrue(
                    second.execute(() -> {
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
        RuntimeExecutors.TimingNodeExecutors first =
                runtime.createTimingNodeExecutors();
        RuntimeExecutors.TimingNodeExecutors second =
                runtime.createTimingNodeExecutors();
        runtime.start();

        AtomicReference<String> firstThread =
                new AtomicReference<String>();
        AtomicReference<String> secondThread =
                new AtomicReference<String>();
        CountDownLatch done = new CountDownLatch(2);

        first.tagProcessor().start();
        second.tagProcessor().start();
        try {
            assertTrue(first.tagProcessor().execute(() -> {
                firstThread.set(
                        Thread.currentThread().getName());
                done.countDown();
            }));
            assertTrue(second.tagProcessor().execute(() -> {
                secondThread.set(
                        Thread.currentThread().getName());
                done.countDown();
            }));

            assertTrue(done.await(1, TimeUnit.SECONDS));
            assertEquals(
                    "tp-dml-tagproc-worker",
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
