package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One process-local application-exit request shared by Presentation and Runtime.
 *
 * <p>Presentation can request STOP or RESTART without owning the
 * TimingApplication lifecycle directly. The executable waits for the first request
 * and then follows the normal deactivation path.</p>
 */
final class ApplicationExitSignal {

    private final CountDownLatch requested =
            new CountDownLatch(1);
    private final AtomicReference<TimingApplicationRuntime.ExitRequest> request =
            new AtomicReference<>();

    void requestStop() {
        request(TimingApplicationRuntime.ExitRequest.STOP);
    }

    void requestRestart() {
        request(TimingApplicationRuntime.ExitRequest.RESTART);
    }

    void requestRestartKeepLogging() {
        request(TimingApplicationRuntime.ExitRequest.RESTART_KEEP_LOGGING);
    }

    private void request(TimingApplicationRuntime.ExitRequest value) {
        if (request.compareAndSet(null, value)) {
            requested.countDown();
        }
    }

    TimingApplicationRuntime.ExitRequest awaitRequest()
            throws InterruptedException {
        requested.await();
        return request.get();
    }
}
