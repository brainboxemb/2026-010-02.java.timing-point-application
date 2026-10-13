package io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.Arrays;

import org.junit.Test;

import static org.junit.Assert.fail;

public class DefaultUpstreamDataTest {

    @Test
    public void raceDataRejectsDuplicateRegistrationAndTeam() {
        expectInvalid(
                Arrays.asList(
                        assignment("RT-R-0001", "0042"),
                        assignment("RT-R-0001", "0043")));

        expectInvalid(
                Arrays.asList(
                        assignment("RT-R-0001", "0042"),
                        assignment("RT-R-0002", "0042")));
    }

    private static UpstreamData.RaceAssignment assignment(
            String registrationId,
            String teamId) {
        return new UpstreamData.RaceAssignment(
                new RegistrationId(registrationId),
                new TeamId(teamId));
    }

    private static void expectInvalid(
            java.util.List<UpstreamData.RaceAssignment> assignments) {
        try {
            DefaultUpstreamData.raceData(
                    new NodeId("A"),
                    1L,
                    assignments);
            fail("expected invalid RaceData");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
