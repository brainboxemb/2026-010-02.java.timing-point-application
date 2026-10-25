package oss.relay.eventtiming.testclient.central;

/** Central operator controls that are intentionally not exported to local scripts. */
interface CentralControlActions extends CentralActions {
    long requestSourceHead(String nodeId);
}
