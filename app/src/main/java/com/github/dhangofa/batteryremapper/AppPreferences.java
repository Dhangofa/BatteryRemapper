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

    public static final boolean DEFAULT_REMAPPER_ENABLED = true;
    public static final boolean DEFAULT_BATTERY_SAVER_ENABLED = true;
    public static final boolean DEFAULT_AUTO_SHUTDOWN_ENABLED = false;

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
}
