package io.github.brainboxemb.eventtiming.systemtest;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * Keep the shared test-data convention explicit: two physical tags identify
 * one registration; the normal TeamId uses the same four-digit sequence.
 *
 * <p>These are fixture-format tests, not tests of production RFID decoding
 * or the normal/reserve lookup pipeline.</p>
 */
public final class TestParticipantIdsTest {
    @Test
    public void normalPairHasOneRegistrationAndTeamIdentity() {
        assertEquals("TT-A-0001-1", TestParticipantIds.normalTag(1, 1));
        assertEquals("TT-A-0001-2", TestParticipantIds.normalTag(1, 2));
        assertEquals("RT-A-0001", TestParticipantIds.normalRegistration(1));
        assertEquals("0001", TestParticipantIds.normalTeam(1));
    }

    @Test
    public void reservePairHasNoImplicitTeamAssignment() {
        assertEquals("TT-R-0001-1", TestParticipantIds.reserveTag(1, 1));
        assertEquals("TT-R-0001-2", TestParticipantIds.reserveTag(1, 2));
        assertEquals("RT-R-0001", TestParticipantIds.reserveRegistration(1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void participantZeroIsInvalid() {
        TestParticipantIds.normalRegistration(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void thirdPhysicalTagIsInvalid() {
        TestParticipantIds.normalTag(1, 3);
    }
}
