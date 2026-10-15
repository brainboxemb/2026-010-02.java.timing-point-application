package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Verifies the small rejection guard used at execution boundaries. */
public class SafeExecuteTest {

    /** Verifies that successful work runs without invoking cleanup. */
    @Test
    public void successfulOperationDoesNotRunCleanup() {
        AtomicBoolean executed = new AtomicBoolean();
        AtomicBoolean cleaned = new AtomicBoolean();

        SafeExecute.run(() -> executed.set(true), () -> cleaned.set(true), "should not fail");

        assertTrue(executed.get());
        assertFalse(cleaned.get());
    }

    /** Verifies that rejection runs cleanup and becomes the configured component failure. */
    @Test
    public void rejectionRunsCleanupAndIsWrapped() {
        AtomicBoolean cleaned = new AtomicBoolean();
        RejectedExecutionException rejection = new RejectedExecutionException("queue rejected work");

        try {
            SafeExecute.run(
                    () -> {
                        throw rejection;
                    },
                    () -> cleaned.set(true),
                    "startup work could not be scheduled");
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals("startup work could not be scheduled", expected.getMessage());
            assertSame(rejection, expected.getCause());
            assertTrue(cleaned.get());
        }
    }

    /** Verifies that unrelated runtime failures propagate without invoking rejection cleanup. */
    @Test
    public void unrelatedFailurePropagatesUnchanged() {
        AtomicBoolean cleaned = new AtomicBoolean();
        IllegalArgumentException failure = new IllegalArgumentException("ordinary failure");

        try {
            SafeExecute.run(
                    () -> {
                        throw failure;
                    },
                    () -> cleaned.set(true),
                    "unused");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertSame(failure, expected);
            assertFalse(cleaned.get());
        }
    }

    /** Verifies that a successful guarded operation does not call the rejection handler. */
    @Test
    public void handledRejectionSuccessDoesNotCallHandler() {
        AtomicBoolean executed = new AtomicBoolean();
        AtomicBoolean rejected = new AtomicBoolean();

        SafeExecute.runOrHandleRejection(
                () -> executed.set(true),
                ex -> rejected.set(true));

        assertTrue(executed.get());
        assertFalse(rejected.get());
    }

    /** Verifies that the rejection policy receives the original exception. */
    @Test
    public void handledRejectionReceivesOriginalException() {
        RejectedExecutionException rejection = new RejectedExecutionException("full");
        RejectedExecutionException[] reported = new RejectedExecutionException[1];

        SafeExecute.runOrHandleRejection(
                () -> {
                    throw rejection;
                },
                ex -> reported[0] = ex);

        assertSame(rejection, reported[0]);
    }

    /** Verifies that unrelated runtime failures are not swallowed. */
    @Test
    public void handledRejectionDoesNotSwallowOtherFailures() {
        IllegalArgumentException failure = new IllegalArgumentException("invalid");
        AtomicBoolean rejected = new AtomicBoolean();

        try {
            SafeExecute.runOrHandleRejection(
                    () -> {
                        throw failure;
                    },
                    ex -> rejected.set(true));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertSame(failure, expected);
            assertFalse(rejected.get());
        }
    }
}
