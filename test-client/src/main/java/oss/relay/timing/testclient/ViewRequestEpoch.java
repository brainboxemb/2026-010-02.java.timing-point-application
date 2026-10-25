package oss.relay.timing.testclient;

/**
 * FX-thread-confined invalidation generation for asynchronous presentation reads.
 *
 * <p>The network request may finish on a worker thread, but begin(), invalidate()
 * and isCurrent() are only used from the JavaFX Application Thread, so no
 * synchronization or executor is owned by this passive state object.</p>
 */
final class ViewRequestEpoch {
    private long generation;

    long begin() {
        return ++generation;
    }

    long current() {
        return generation;
    }

    void invalidate() {
        generation++;
    }

    boolean isCurrent(long token) {
        return token == generation;
    }
}
