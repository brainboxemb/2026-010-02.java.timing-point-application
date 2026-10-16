package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.Setting;
import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.SettingContainer;
import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.SettingKey;

/**
 * Antenna-manager settings declaration.
 *
 * <p>Generic grouping, atomic update, revision and change publication are owned by
 * {@link SettingContainer}. This type declares only the antenna-specific setting
 * identities and keeps the manager-owned mutable Setting handle private to this package.</p>
 */
public final class AntennaSettings extends SettingContainer {
    /** Desired inventory enable state. */
    public static final SettingKey<Boolean> INVENTORY_ENABLED =
            SettingKey.of("inventoryEnabled");

    private final Setting<Boolean> inventoryEnabledSetting =
            setting(INVENTORY_ENABLED, Boolean.FALSE);

    /** Returns the last committed desired inventory state. */
    public boolean inventoryEnabled() {
        return requestedValue(INVENTORY_ENABLED).booleanValue();
    }

    /** Device-side state tracking, not part of the settings writer interface. */
    Setting<Boolean> inventorySetting() {
        return inventoryEnabledSetting;
    }
}
