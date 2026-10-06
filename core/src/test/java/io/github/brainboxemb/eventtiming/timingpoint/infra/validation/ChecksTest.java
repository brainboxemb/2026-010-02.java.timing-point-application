package io.github.brainboxemb.eventtiming.timingpoint.infra.validation;

import org.junit.Test;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ChecksTest {

    @Test
    public void checkStateFormatsFailureMessage() {
        try {
            checkState(
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

    @Test
    public void checkArgumentFormatsFailureMessage() {
        try {
            checkArgument(
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
