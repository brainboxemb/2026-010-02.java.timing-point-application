package oss.relay.timing.testclient;

/** Engineering Client lifecycle/connection actions exposed to SI-02 automation scripts. */
interface EngineeringClientActions {
    record Status(
            boolean apiAvailable,
            boolean eventsConnected,
            boolean centralConnected) {
    }

    void reset();

    void connectEvents();

    void disconnectEvents();

    void connectCentral();

    void disconnectCentral();

    boolean apiAvailable();

    boolean eventsConnected();

    boolean centralConnected();

    default Status status() {
        return new Status(
                apiAvailable(),
                eventsConnected(),
                centralConnected());
    }
}
