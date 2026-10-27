package oss.relay.timing.testclient.central;

/** Central System Simulator actions shared by GUI controls and local engineering scripts. */
public interface CentralActions {
    record MutationResult(
            int affected,
            int immediatePackets) {
    }

    MutationResult setTeamAssignment(
            String registrationId,
            String teamId);

    MutationResult deleteTeamAssignment(
            String registrationId);

    MutationResult setStageStart(
            int stageId,
            String registrationId,
            String start);

    MutationResult deleteStageStart(
            int stageId,
            String registrationId);

    MutationResult setStageStartRange(
            int stageId,
            String registrationPrefix,
            int firstRegistrationNumber,
            int lastRegistrationNumber,
            String start,
            long intervalMillis);

    MutationResult deleteStageStartRange(
            int stageId,
            String registrationPrefix,
            int firstRegistrationNumber,
            int lastRegistrationNumber);

    long requestTimingData(
            String nodeId,
            long fromSequence);
}
