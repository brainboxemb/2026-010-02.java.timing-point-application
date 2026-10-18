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
        assertEquals("A0001A", TestParticipantIds.normalTag(1, 1));
        assertEquals("A0001B", TestParticipantIds.normalTag(1, 2));
        assertEquals("A0001", TestParticipantIds.normalRegistration(1));
        assertEquals("0001", TestParticipantIds.normalTeam(1));
    }

    @Test
    public void reservePairHasNoImplicitTeamAssignment() {
        assertEquals("R0001A", TestParticipantIds.reserveTag(1, 1));
        assertEquals("R0001B", TestParticipantIds.reserveTag(1, 2));
        assertEquals("R0001", TestParticipantIds.reserveRegistration(1));
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
