package io.github.brainboxemb.eventtiming.testclient;

/** Presentation-only state of the three persistent Engineering Client links.
 * The HTTP API is intentionally excluded because it is stateless. */
enum ConnectionToolbarState {
    NONE(0), PARTIAL(-1), ALL(3);

    private final int count;

    ConnectionToolbarState(int count) {
        this.count = count;
    }

    static ConnectionToolbarState of(boolean events, boolean terminal, boolean deviceLog) {
        int count = (events ? 1 : 0) + (terminal ? 1 : 0) + (deviceLog ? 1 : 0);
        return count == 0 ? NONE : count == 3 ? ALL : PARTIAL;
    }

    int connectedCount() {
        return count;
    }
}
