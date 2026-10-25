package io.github.brainboxemb.eventtiming.testclient;

/** State of persistent Engineering Client links; HTTP API is stateless. */
enum ConnectionToolbarState {
    NONE, PARTIAL, ALL;

    static int countConnected(boolean events, boolean terminal, boolean deviceLog) {
        return (events ? 1 : 0) + (terminal ? 1 : 0) + (deviceLog ? 1 : 0);
    }

    static ConnectionToolbarState of(boolean events, boolean terminal, boolean deviceLog) {
        int count = countConnected(events, terminal, deviceLog);
        return count == 0 ? NONE : count == 3 ? ALL : PARTIAL;
    }

    boolean reconnectAvailable() {
        return this == PARTIAL;
    }
}
