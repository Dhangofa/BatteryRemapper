package com.github.dhangofa.batteryremapper;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores the configuration selected in the BatteryRemapper app UI. */
public final class AppPreferences {

    public static final String PREFERENCES_NAME =
            "battery_remapper_preferences";

    public static final String KEY_REMAPPER_ENABLED =
            "battery_remapper_enabled";

    public static final String KEY_BATTERY_SAVER_ENABLED =
            "battery_saver_enabled";

    public static final String KEY_AUTO_SHUTDOWN_ENABLED =
            "auto_shutdown_enabled";

    /** Physical percentage that the display shows as 0%. */
    public static final String KEY_MAP_MIN = "map_min";

    /** Physical percentage that the display shows as 100%. */
    public static final String KEY_MAP_MAX = "map_max";

    /** Displayed percentage at or below which the shutdown countdown starts. */
    public static final String KEY_SHUTDOWN_TRIGGER = "shutdown_trigger";

    public static final boolean DEFAULT_REMAPPER_ENABLED = true;
    public static final boolean DEFAULT_BATTERY_SAVER_ENABLED = true;
    public static final boolean DEFAULT_AUTO_SHUTDOWN_ENABLED = false;

    /** The module's original hard-coded window: physical 20%..80% shows as 0%..100%. */
    public static final int DEFAULT_MAP_MIN = Mapping.DEFAULT_PHYSICAL_MIN;
    public static final int DEFAULT_MAP_MAX = Mapping.DEFAULT_PHYSICAL_MAX;

    /**
     * Displayed level that reproduces the original shutdown behaviour: with the default
     * window a displayed 0% is a physical 20%.
     */
    public static final int DEFAULT_SHUTDOWN_TRIGGER = 0;

    /** Bounds for the mapping window. The whole scale is allowed; it may not collapse. */
    public static final int MAP_LIMIT_MIN = 0;
    public static final int MAP_LIMIT_MAX = 100;

    /** Bounds for the countdown trigger, expressed on the displayed scale. */
    public static final int SHUTDOWN_TRIGGER_LIMIT_MIN = 0;
    public static final int SHUTDOWN_TRIGGER_LIMIT_MAX = 50;

    private final SharedPreferences preferences;

    public AppPreferences(Context context) {
        preferences = context
                .getApplicationContext()
                .getSharedPreferences(
                        PREFERENCES_NAME,
                        Context.MODE_PRIVATE
                );
    }

    public boolean isRemapperEnabled() {
        return preferences.getBoolean(
                KEY_REMAPPER_ENABLED,
                DEFAULT_REMAPPER_ENABLED
        );
    }

    public void setRemapperEnabled(boolean enabled) {
        preferences.edit()
                .putBoolean(KEY_REMAPPER_ENABLED, enabled)
                .apply();
    }

    public boolean isBatterySaverEnabled() {
        return preferences.getBoolean(
                KEY_BATTERY_SAVER_ENABLED,
                DEFAULT_BATTERY_SAVER_ENABLED
        );
    }

    public void setBatterySaverEnabled(boolean enabled) {
        preferences.edit()
                .putBoolean(KEY_BATTERY_SAVER_ENABLED, enabled)
                .apply();
    }

    public boolean isAutoShutdownEnabled() {
        return preferences.getBoolean(
                KEY_AUTO_SHUTDOWN_ENABLED,
                DEFAULT_AUTO_SHUTDOWN_ENABLED
        );
    }

    public void setAutoShutdownEnabled(boolean enabled) {
        preferences.edit()
                .putBoolean(KEY_AUTO_SHUTDOWN_ENABLED, enabled)
                .apply();
    }

    public int getMapMin() {
        return clamp(
                preferences.getInt(KEY_MAP_MIN, DEFAULT_MAP_MIN),
                MAP_LIMIT_MIN,
                MAP_LIMIT_MAX - 1
        );
    }

    public int getMapMax() {
        return clamp(
                preferences.getInt(KEY_MAP_MAX, DEFAULT_MAP_MAX),
                MAP_LIMIT_MIN + 1,
                MAP_LIMIT_MAX
        );
    }

    /**
     * The mapping window as one normalized pair.
     *
     * <p>The two bounds are stored independently, so a corrupted or migrated preference file
     * could hold a pair that does not describe a window at all. Anything that displays or applies
     * the window reads it through here, so the UI can never show a pair the hook would reject.
     */
    public int[] getMapRange() {
        return normalizeRange(
                preferences.getInt(KEY_MAP_MIN, DEFAULT_MAP_MIN),
                preferences.getInt(KEY_MAP_MAX, DEFAULT_MAP_MAX)
        );
    }

    public void setMapRange(int min, int max) {
        int[] range = normalizeRange(min, max);

        preferences.edit()
                .putInt(KEY_MAP_MIN, range[0])
                .putInt(KEY_MAP_MAX, range[1])
                .apply();
    }

    public int getShutdownTrigger() {
        return clamp(
                preferences.getInt(
                        KEY_SHUTDOWN_TRIGGER,
                        DEFAULT_SHUTDOWN_TRIGGER
                ),
                SHUTDOWN_TRIGGER_LIMIT_MIN,
                SHUTDOWN_TRIGGER_LIMIT_MAX
        );
    }

    public void setShutdownTrigger(int displayedLevel) {
        preferences.edit()
                .putInt(
                        KEY_SHUTDOWN_TRIGGER,
                        normalizeShutdownTrigger(displayedLevel)
                )
                .apply();
    }

    /**
     * Atomically disables the master feature and its child automation features.
     */
    public void disableAllFeatures() {
        preferences.edit()
                .putBoolean(KEY_REMAPPER_ENABLED, false)
                .putBoolean(KEY_BATTERY_SAVER_ENABLED, false)
                .putBoolean(KEY_AUTO_SHUTDOWN_ENABLED, false)
                .apply();
    }

    /**
     * Clamps a mapping window into bounds that always work, guaranteeing {@code min < max} so
     * the scale can never be divided by zero.
     */
    public static int[] normalizeRange(int min, int max) {
        int lower = clamp(min, MAP_LIMIT_MIN, MAP_LIMIT_MAX - 1);
        int upper = clamp(max, MAP_LIMIT_MIN + 1, MAP_LIMIT_MAX);

        if (upper <= lower) {
            // lower is at most MAP_LIMIT_MAX - 1, so lower + 1 is always legal.
            upper = lower + 1;
        }

        return new int[]{lower, upper};
    }

    public static int normalizeShutdownTrigger(int displayedLevel) {
        return clamp(
                displayedLevel,
                SHUTDOWN_TRIGGER_LIMIT_MIN,
                SHUTDOWN_TRIGGER_LIMIT_MAX
        );
    }

    private static int clamp(int value, int lower, int upper) {
        if (value < lower) {
            return lower;
        }

        return Math.min(value, upper);
    }
}
