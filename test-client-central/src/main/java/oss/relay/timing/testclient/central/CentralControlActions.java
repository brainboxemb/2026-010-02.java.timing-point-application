package oss.relay.timing.testclient.central;

/** Central operator controls that are intentionally not exported to local scripts. */
interface CentralControlActions extends CentralActions {
    record MutationResult(
            int affected,
            int immediatePackets) {
    }

    MutationResult setTeamAssignment(
            String registrationId,
            String teamId);

    MutationResult deleteTeamAssignment(
            String registrationId);

    MutationResult setStageStartRange(
            int stageId,
            String firstRegistrationId,
            String lastRegistrationId,
            String start,
            long intervalMillis);

    MutationResult deleteStageStartRange(
            int stageId,
            String firstRegistrationId,
            String lastRegistrationId);

    long requestSourceHead(String nodeId);
}
