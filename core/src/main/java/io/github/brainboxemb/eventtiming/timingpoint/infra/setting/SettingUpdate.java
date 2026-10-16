package io.github.brainboxemb.eventtiming.timingpoint.infra.setting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable message describing one committed grouped settings update. */
public final class SettingUpdate {
    public final long revision;
    public final List<SettingKey<?>> settings;

    SettingUpdate(
            long revision,
            List<SettingKey<?>> settings) {
        this.revision = revision;
        this.settings =
                Collections.unmodifiableList(
                        new ArrayList<SettingKey<?>>(settings));
    }

    /** Returns whether this update contains the supplied setting key. */
    public boolean contains(SettingKey<?> key) {
        if (key == null) {
            throw new IllegalArgumentException("setting key must not be null");
        }

        for (SettingKey<?> changed : settings) {
            if (changed == key) {
                return true;
            }
        }
        return false;
    }
}
