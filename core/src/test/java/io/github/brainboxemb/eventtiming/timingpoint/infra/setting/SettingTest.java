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

        assertTrue(
                setting.beginChange());
        assertEquals(
                Boolean.TRUE,
                setting.processingValue());

        setting.completeChange(
                true);

        assertEquals(
                Boolean.TRUE,
                setting.appliedValue());
        assertFalse(
                setting.changePending());
    }

    @Test
    public void newerRequestStaysPendingWhileOlderChangeCompletes() {
        Setting<Boolean> setting =
                new Setting<Boolean>(
                        Boolean.FALSE);

        setting.request(
                Boolean.TRUE);
        assertTrue(
                setting.beginChange());

        setting.request(
                Boolean.FALSE);

        assertEquals(
                Boolean.FALSE,
                setting.requestedValue());
        assertEquals(
                Boolean.FALSE,
                setting.appliedValue());
        assertTrue(
                "an older ENABLE is still being processed",
                setting.changePending());

        setting.completeChange(
                true);

        assertEquals(
                Boolean.TRUE,
                setting.appliedValue());
        assertTrue(
                "the newer DISABLE must still be applied",
                setting.changePending());

        assertTrue(
                setting.beginChange());
        assertEquals(
                Boolean.FALSE,
                setting.processingValue());
        setting.completeChange(
                true);

        assertEquals(
                Boolean.FALSE,
                setting.appliedValue());
        assertFalse(
                setting.changePending());
    }

    @Test
    public void failedProcessingDoesNotAdvanceAppliedValue() {
        Setting<Boolean> setting =
                new Setting<Boolean>(
                        Boolean.FALSE);

        setting.request(
                Boolean.TRUE);
        assertTrue(
                setting.beginChange());

        setting.completeChange(
                false);

        assertEquals(
                Boolean.TRUE,
                setting.requestedValue());
        assertEquals(
                Boolean.FALSE,
                setting.appliedValue());
        assertTrue(
                setting.changePending());
    }
}
