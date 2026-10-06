package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import java.util.concurrent.CountDownLatch;

/**
 * One process-local shutdown request shared by Presentation and TimingApplication.
 *
 * <p>Presentation can request application shutdown without owning or calling the
 * TimingApplication lifecycle directly. The executable waits for this signal and
 * then follows the normal deactivation path.</p>
 */
final class ShutdownSignal {

    private final CountDownLatch requested =
            new CountDownLatch(1);

    void request() {
        requested.countDown();
    }

    void awaitRequest()
            throws InterruptedException {
        requested.await();
    }

    boolean requested() {
        return requested.getCount() == 0L;
    }
}
