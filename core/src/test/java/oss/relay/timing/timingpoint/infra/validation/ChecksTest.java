package oss.relay.timing.timingpoint.infra.validation;

import org.junit.Test;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfIllegalState;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ChecksTest {

    /** Verifies state-contract failure formatting. */
    @Test
    public void failIfIllegalStateFormatsFailureMessage() {
        try {
            failIfIllegalState(
                    false,
                    "state must be %s, was %s",
                    "NEW",
                    "ACTIVE");
            fail(
                    "expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals(
                    "state must be NEW, was ACTIVE",
                    expected.getMessage());
        }
    }

    /** Verifies argument-contract failure formatting. */
    @Test
    public void failIfInvalidArgumentFormatsFailureMessage() {
        try {
            failIfInvalidArgument(
                    false,
                    "capacity must be positive, was %s",
                    0);
            fail(
                    "expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals(
                    "capacity must be positive, was 0",
                    expected.getMessage());
        }
    }
}
