package oss.relay.timing.testclient.central;

/** Central operator controls that are intentionally not exported to local scripts. */
interface CentralControlActions extends CentralActions {
    MutationResult setTeamAssignmentByNumber(
            String registrationPrefix,
            String registrationNumber,
            String teamId);

    MutationResult deleteTeamAssignmentByNumber(
            String registrationPrefix,
            String registrationNumber);

    MutationResult setStageStartRangeByNumber(
            String registrationPrefix,
            String firstRegistrationNumber,
            String lastRegistrationNumber,
            String start,
            long intervalMillis);

    MutationResult deleteStageStartRangeByNumber(
            String registrationPrefix,
            String firstRegistrationNumber,
            String lastRegistrationNumber);

    long requestSourceHead(String nodeId);
}
