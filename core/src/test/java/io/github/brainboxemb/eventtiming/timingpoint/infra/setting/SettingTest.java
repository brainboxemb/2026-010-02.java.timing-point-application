package io.github.brainboxemb.eventtiming.timingpoint.infra.setting;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SettingTest {

    @Test
    public void tracksRequestedAppliedAndPendingValues() {
        Setting<Boolean> setting =
                new Setting<Boolean>(
                        Boolean.FALSE);

        assertEquals(
                Boolean.FALSE,
                setting.requestedValue());
        assertEquals(
                Boolean.FALSE,
                setting.appliedValue());
        assertFalse(
                setting.changePending());

        setting.request(
                Boolean.TRUE);

        assertEquals(
                Boolean.TRUE,
                setting.requestedValue());
        assertEquals(
                Boolean.FALSE,
                setting.appliedValue());
        assertTrue(
                setting.changePending());

        setting.markApplied(
                Boolean.TRUE);

        assertEquals(
                Boolean.TRUE,
                setting.appliedValue());
        assertFalse(
                setting.changePending());
    }

    @Test
    public void newerRequestRemainsPendingAfterOlderValueWasApplied() {
        Setting<Boolean> setting =
                new Setting<Boolean>(
                        Boolean.FALSE);

        setting.request(
                Boolean.TRUE);

        /*
         * The owner starts applying TRUE. Before it completes, a newer FALSE
         * request arrives.
         */
        setting.request(
                Boolean.FALSE);
        setting.markApplied(
                Boolean.TRUE);

        assertEquals(
                Boolean.FALSE,
                setting.requestedValue());
        assertEquals(
                Boolean.TRUE,
                setting.appliedValue());
        assertTrue(
                setting.changePending());

        setting.markApplied(
                Boolean.FALSE);

        assertFalse(
                setting.changePending());
    }

    @Test
    public void cancelledRequestNeedsNoChangeWhenAppliedValueAlreadyMatches() {
        Setting<Boolean> setting =
                new Setting<Boolean>(
                        Boolean.FALSE);

        setting.request(
                Boolean.TRUE);
        assertTrue(
                setting.changePending());

        setting.request(
                Boolean.FALSE);

        assertEquals(
                Boolean.FALSE,
                setting.requestedValue());
        assertEquals(
                Boolean.FALSE,
                setting.appliedValue());
        assertFalse(
                setting.changePending());
    }
}
