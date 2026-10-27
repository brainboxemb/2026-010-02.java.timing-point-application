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
            String registrationId,
            String start);

    MutationResult deleteStageStart(
            String registrationId);

    MutationResult setStageStartRange(
            String registrationPrefix,
            int firstRegistrationNumber,
            int lastRegistrationNumber,
            String start,
            long intervalMillis);

    MutationResult deleteStageStartRange(
            String registrationPrefix,
            int firstRegistrationNumber,
            int lastRegistrationNumber);

    long requestTimingData(
            String nodeId,
            long fromSequence);
}
