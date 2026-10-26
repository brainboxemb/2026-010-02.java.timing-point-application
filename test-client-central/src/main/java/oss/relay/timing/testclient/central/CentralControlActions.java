package oss.relay.timing.testclient.central;

/** Central operator controls that are intentionally not exported to local scripts. */
interface CentralControlActions extends CentralActions {
    record MutationResult(
            int affected,
            int immediatePackets) {
    }

    MutationResult setTeamAssignment(
            String registrationPrefix,
            String registrationNumber,
            String teamId);

    MutationResult deleteTeamAssignment(
            String registrationPrefix,
            String registrationNumber);

    MutationResult setStageStartRange(
            int stageId,
            String registrationPrefix,
            String firstRegistrationNumber,
            String lastRegistrationNumber,
            String start,
            long intervalMillis);

    MutationResult deleteStageStartRange(
            int stageId,
            String registrationPrefix,
            String firstRegistrationNumber,
            String lastRegistrationNumber);

    long requestSourceHead(String nodeId);
}
