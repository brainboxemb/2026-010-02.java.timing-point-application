package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.Setting;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AntennaSettingsTest {

    @Test
    public void declaresInventorySettingOnGenericContainer() {
        AntennaSettings settings = new AntennaSettings();

        settings.update(
                edit -> edit.set(
                        AntennaSettings.INVENTORY_ENABLED,
                        Boolean.TRUE));

        assertEquals(Boolean.TRUE, settings.requestedValue(AntennaSettings.INVENTORY_ENABLED));
        assertEquals(1L, settings.revision());
    }

    @Test
    public void managerHandleSharesRequestedAndAppliedState() {
        AntennaSettings settings = new AntennaSettings();
        Setting<Boolean> inventory = settings.inventorySetting();

        settings.update(
                edit -> edit.set(
                        AntennaSettings.INVENTORY_ENABLED,
                        Boolean.TRUE));

        assertTrue(inventory.changePending());
        inventory.markApplied(Boolean.TRUE);
        assertFalse(inventory.changePending());
        assertEquals(Boolean.TRUE, settings.appliedValue(AntennaSettings.INVENTORY_ENABLED));
    }
}
