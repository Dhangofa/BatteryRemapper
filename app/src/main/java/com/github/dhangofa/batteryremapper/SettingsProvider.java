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
 * configuration when SystemUI starts.
 *
 * This provider performs no polling and starts no background service.
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
