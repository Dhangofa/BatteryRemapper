package com.github.dhangofa.batteryremapper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Lightweight read-only bridge that lets BatteryHook load its persisted
 * configuration when SystemUI starts, or when the app reports that a setting
 * changed.
 *
 * This provider performs no polling and starts no background service.
 *
 * It also owns the app/hook protocol strings. MainActivity (in the app) and
 * BatteryHook (inside System UI) must agree on these exactly, so they are
 * defined once, here, rather than duplicated on both sides.
 */
public final class SettingsProvider extends ContentProvider {

    public static final String AUTHORITY =
            "com.github.dhangofa.batteryremapper.settings";

    public static final Uri CONTENT_URI =
            Uri.parse("content://" + AUTHORITY);

    public static final String METHOD_GET_SETTINGS =
            "get_settings";

    public static final String RESULT_REMAPPER_ENABLED =
            "remapper_enabled";

    public static final String RESULT_BATTERY_SAVER_ENABLED =
            "battery_saver_enabled";

    public static final String RESULT_AUTO_SHUTDOWN_ENABLED =
            "auto_shutdown_enabled";

    /** Physical percentage the display shows as 0%. */
    public static final String RESULT_MAP_MIN =
            "map_min";

    /** Physical percentage the display shows as 100%. */
    public static final String RESULT_MAP_MAX =
            "map_max";

    /** Displayed percentage at or below which the countdown starts. */
    public static final String RESULT_SHUTDOWN_TRIGGER =
            "shutdown_trigger";

    /** The package the module is scoped to. */
    public static final String SYSTEM_UI_PACKAGE =
            "com.android.systemui";

    public static final String MODULE_PACKAGE =
            "com.github.dhangofa.batteryremapper";

    /*
     * App <-> hook protocol.
     */

    public static final String ACTION_PROBE_HOOK =
            MODULE_PACKAGE + ".action.PROBE_SYSTEMUI_HOOK";

    public static final String ACTION_HOOK_STATUS =
            MODULE_PACKAGE + ".action.SYSTEMUI_HOOK_STATUS";

    public static final String ACTION_SETTINGS_CHANGED =
            MODULE_PACKAGE + ".action.SETTINGS_CHANGED";

    public static final String EXTRA_REQUEST_ID =
            MODULE_PACKAGE + ".extra.REQUEST_ID";

    public static final String EXTRA_HOOK_ACTIVE =
            MODULE_PACKAGE + ".extra.HOOK_ACTIVE";

    public static final String EXTRA_HOOKED_PACKAGE =
            MODULE_PACKAGE + ".extra.HOOKED_PACKAGE";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(
            String method,
            String argument,
            Bundle extras
    ) {
        if (!METHOD_GET_SETTINGS.equals(method)) {
            return super.call(method, argument, extras);
        }

        Context context = getContext();

        if (context == null) {
            return null;
        }

        SharedPreferences preferences =
                context.getSharedPreferences(
                        AppPreferences.PREFERENCES_NAME,
                        Context.MODE_PRIVATE
                );

        boolean remapperEnabled =
                preferences.getBoolean(
                        AppPreferences.KEY_REMAPPER_ENABLED,
                        AppPreferences.DEFAULT_REMAPPER_ENABLED
                );

        /*
         * The automation features only make sense while remapping itself is
         * enabled, so the master switch gates them here rather than in the hook.
         */
        boolean batterySaverEnabled =
                remapperEnabled
                        && preferences.getBoolean(
                                AppPreferences.KEY_BATTERY_SAVER_ENABLED,
                                AppPreferences.DEFAULT_BATTERY_SAVER_ENABLED
                        );

        boolean autoShutdownEnabled =
                remapperEnabled
                        && preferences.getBoolean(
                                AppPreferences.KEY_AUTO_SHUTDOWN_ENABLED,
                                AppPreferences.DEFAULT_AUTO_SHUTDOWN_ENABLED
                        );

        int[] range = AppPreferences.normalizeRange(
                preferences.getInt(
                        AppPreferences.KEY_MAP_MIN,
                        AppPreferences.DEFAULT_MAP_MIN
                ),
                preferences.getInt(
                        AppPreferences.KEY_MAP_MAX,
                        AppPreferences.DEFAULT_MAP_MAX
                )
        );

        int shutdownTrigger =
                AppPreferences.normalizeShutdownTrigger(
                        preferences.getInt(
                                AppPreferences.KEY_SHUTDOWN_TRIGGER,
                                AppPreferences.DEFAULT_SHUTDOWN_TRIGGER
                        )
                );

        Bundle result = new Bundle();

        result.putBoolean(
                RESULT_REMAPPER_ENABLED,
                remapperEnabled
        );

        result.putBoolean(
                RESULT_BATTERY_SAVER_ENABLED,
                batterySaverEnabled
        );

        result.putBoolean(
                RESULT_AUTO_SHUTDOWN_ENABLED,
                autoShutdownEnabled
        );

        result.putInt(RESULT_MAP_MIN, range[0]);
        result.putInt(RESULT_MAP_MAX, range[1]);
        result.putInt(RESULT_SHUTDOWN_TRIGGER, shutdownTrigger);

        return result;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException(
                "BatteryRemapper settings are read-only."
        );
    }

    @Override
    public int delete(
            Uri uri,
            String selection,
            String[] selectionArgs
    ) {
        throw new UnsupportedOperationException(
                "BatteryRemapper settings are read-only."
        );
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs
    ) {
        throw new UnsupportedOperationException(
                "BatteryRemapper settings are read-only."
        );
    }
}
