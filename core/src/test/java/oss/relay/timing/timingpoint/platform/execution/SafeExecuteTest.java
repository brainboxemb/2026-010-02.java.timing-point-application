package oss.relay.timing.timingpoint.platform.execution;

import java.util.Optional;
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

    /** Verifies that a selected handled failure produces an empty result. */
    @Test
    public void handledCallFailureReturnsEmpty() {
        IllegalStateException failure = new IllegalStateException("not available");

        Optional<String> result =
                SafeExecute.callOrEmpty(
                        () -> {
                            throw failure;
                        },
                        IllegalStateException.class,
                        ex -> true);

        assertFalse(result.isPresent());
    }

    /** Verifies that successful guarded calls return their value. */
    @Test
    public void successfulGuardedCallReturnsValue() {
        Optional<String> result =
                SafeExecute.callOrEmpty(
                        () -> "ready",
                        IllegalStateException.class,
                        ex -> true);

        assertEquals("ready", result.get());
    }

    /** Verifies that a selected but unhandled failure propagates unchanged. */
    @Test
    public void unhandledSelectedCallFailurePropagates() {
        IllegalStateException failure = new IllegalStateException("invalid");

        try {
            SafeExecute.callOrEmpty(
                    () -> {
                        throw failure;
                    },
                    IllegalStateException.class,
                    ex -> false);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertSame(failure, expected);
        }
    }

    /** Verifies that unrelated call failures propagate unchanged. */
    @Test
    public void unrelatedCallFailurePropagates() {
        IllegalArgumentException failure = new IllegalArgumentException("invalid");

        try {
            SafeExecute.callOrEmpty(
                    () -> {
                        throw failure;
                    },
                    IllegalStateException.class,
                    ex -> true);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertSame(failure, expected);
        }
    }

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

    /** Verifies that runtime failure handling runs before the same failure is rethrown. */
    @Test
    public void runtimeFailureHandlerRunsBeforeRethrow() {
        IllegalStateException failure = new IllegalStateException("expected");
        RuntimeException[] handled = new RuntimeException[1];

        try {
            SafeExecute.runOrRethrow(
                    () -> {
                        throw failure;
                    },
                    ex -> handled[0] = ex);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertSame(failure, expected);
            assertSame(failure, handled[0]);
        }
    }

    /** Verifies that successful runtime-guarded work does not invoke the failure handler. */
    @Test
    public void successfulRuntimeGuardDoesNotInvokeFailureHandler() {
        AtomicBoolean executed = new AtomicBoolean();
        AtomicBoolean handled = new AtomicBoolean();

        SafeExecute.runOrRethrow(
                () -> executed.set(true),
                ex -> handled.set(true));

        assertTrue(executed.get());
        assertFalse(handled.get());
    }

    /** Verifies that a successful guarded operation does not call the rejection handler. */
    @Test
    public void handledRejectionSuccessDoesNotCallHandler() {
        AtomicBoolean executed = new AtomicBoolean();
        AtomicBoolean rejected = new AtomicBoolean();

        boolean completed =
                SafeExecute.runOrHandleRejection(
                        () -> executed.set(true),
                        ex -> rejected.set(true));

        assertTrue(completed);
        assertTrue(executed.get());
        assertFalse(rejected.get());
    }

    /** Verifies that the rejection policy receives the original exception. */
    @Test
    public void handledRejectionReceivesOriginalException() {
        RejectedExecutionException rejection = new RejectedExecutionException("full");
        RejectedExecutionException[] reported = new RejectedExecutionException[1];

        boolean completed =
                SafeExecute.runOrHandleRejection(
                        () -> {
                            throw rejection;
                        },
                        ex -> reported[0] = ex);

        assertFalse(completed);
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
