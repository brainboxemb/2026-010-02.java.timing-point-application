package oss.relay.timing.testclient.central;

/** Central System Simulator actions shared by GUI controls and local engineering scripts. */
public interface CentralActions {
    long setTeamAssignment(
            String nodeId,
            String registrationId,
            String teamId);

    long deleteTeamAssignment(
            String nodeId,
            String registrationId);

    long setStageStart(
            String nodeId,
            String registrationId,
            String start);

    long deleteStageStart(
            String nodeId,
            String registrationId);

    long requestTimingData(
            String nodeId,
            long fromSequence);
}
