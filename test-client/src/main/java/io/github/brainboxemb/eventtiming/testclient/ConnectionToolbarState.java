package io.github.brainboxemb.eventtiming.testclient;

/** State of persistent Engineering Client links; HTTP API is stateless. */
enum ConnectionToolbarState {
    NONE, PARTIAL, ALL;

    static int countConnected(
            boolean events,
            boolean central,
            boolean terminal,
            boolean deviceLog) {
        return (events ? 1 : 0)
                + (central ? 1 : 0)
                + (terminal ? 1 : 0)
                + (deviceLog ? 1 : 0);
    }

    static ConnectionToolbarState of(
            boolean events,
            boolean central,
            boolean terminal,
            boolean deviceLog) {
        int count = countConnected(events, central, terminal, deviceLog);
        return count == 0 ? NONE : count == 4 ? ALL : PARTIAL;
    }
}
