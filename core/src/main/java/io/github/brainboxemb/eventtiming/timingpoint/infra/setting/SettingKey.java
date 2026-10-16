package io.github.brainboxemb.eventtiming.timingpoint.infra.setting;

/**
 * Typed immutable identity of one setting in a SettingContainer.
 *
 * @param <T> setting value type
 */
public final class SettingKey<T> {
    public final String name;

    private SettingKey(String name) {
        this.name = name;
    }

    /** Creates one typed setting key. */
    public static <T> SettingKey<T> of(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("setting name must not be blank");
        }
        return new SettingKey<T>(name.trim());
    }
}
