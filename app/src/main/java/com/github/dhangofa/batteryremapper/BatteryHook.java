package com.github.dhangofa.batteryremapper;

import android.app.AlertDialog;
import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.net.Uri;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class BatteryHook implements IXposedHookLoadPackage {

    private static boolean isShuttingDown = false;
    private static AlertDialog shutdownDialog = null;
    private static CountDownTimer shutdownTimer = null;
    private static final AtomicBoolean batteryHookInstalled = new AtomicBoolean(false);
    private static final String MODULE_PACKAGE = "com.github.dhangofa.batteryremapper";
    private static final String ACTION_PROBE_HOOK = MODULE_PACKAGE + ".action.PROBE_SYSTEMUI_HOOK";
    private static final String ACTION_HOOK_STATUS = MODULE_PACKAGE + ".action.SYSTEMUI_HOOK_STATUS";
    private static final String EXTRA_REQUEST_ID = MODULE_PACKAGE + ".extra.REQUEST_ID";
    private static final String EXTRA_HOOK_ACTIVE = MODULE_PACKAGE + ".extra.HOOK_ACTIVE";
    private static final String EXTRA_HOOKED_PACKAGE = MODULE_PACKAGE + ".extra.HOOKED_PACKAGE";
    private static boolean statusReceiverRegistered = false;

    private static final String ACTION_SETTINGS_CHANGED = MODULE_PACKAGE + ".action.SETTINGS_CHANGED";
    private static final Uri SETTINGS_URI =
            Uri.parse(
                    "content://"
                            + MODULE_PACKAGE
                            + ".settings"
            );
    private static final String METHOD_GET_SETTINGS = "get_settings";
    private static final String RESULT_REMAPPER_ENABLED = "remapper_enabled";
    private static final String RESULT_BATTERY_SAVER_ENABLED = "battery_saver_enabled";
    private static final String RESULT_AUTO_SHUTDOWN_ENABLED = "auto_shutdown_enabled";
    private static volatile boolean remapperEnabled = true;
    private static volatile boolean batterySaverEnabled = true;
    private static volatile boolean autoShutdownEnabled = false;
    private static boolean settingsReceiverRegistered = false;
    private static boolean batteryReceiverRegistered = false;

    /*
     * The mapping window and the countdown trigger. The provider owns these keys, so they are read
     * through its constants rather than duplicated here.
     */
    private static volatile int mapMin = AppPreferences.DEFAULT_MAP_MIN;
    private static volatile int mapMax = AppPreferences.DEFAULT_MAP_MAX;
    private static volatile int shutdownTrigger =
            AppPreferences.DEFAULT_SHUTDOWN_TRIGGER;

    /** Displayed level at or below which Battery Saver is forced ON while unplugged. */
    private static final int SAVER_ON_LEVEL = 20;

    /** Displayed level above which Battery Saver is forced OFF while unplugged. */
    private static final int SAVER_OFF_LEVEL = 50;

    private static final long COUNTDOWN_TOTAL_MS = 30000L;
    private static final long COUNTDOWN_TICK_MS = 1000L;

    /** Used only if the module's own strings cannot be read from System UI. */
    private static final String FALLBACK_DIALOG_TITLE = "Battery depleted";
    private static final String FALLBACK_DIALOG_MESSAGE =
            "Device will shut down in %1$d seconds.\nPlug in the charger to cancel.";

    // -1 = Neutral/Unknown, 0 = Force OFF, 1 = Force ON
    private static int appliedSaverState = -1;      

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        
        // ------------------------------------------------------------------
        // HOOK: SYSTEM UI - Visuals, Hysteresis Saver, & Shutdown Timer
        // ------------------------------------------------------------------
        if (!lpparam.packageName.equals("com.android.systemui")) return;

        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Application application = (Application) param.thisObject;
                            loadSettings(application);
                            registerSettingsReceiver(application);
                            registerStatusReceiver(application);
                            registerBatteryReceiver(application);
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(
                    "BatteryRemapper Status Receiver Error: "
                            + t.getMessage()
            );
        }

        if (!batteryHookInstalled.compareAndSet(false, true)) {
            XposedBridge.log(
                    "BatteryRemapper: Battery hook already installed; "
                            + "duplicate installation skipped."
            );
        
            return;
        }
        
        try {
            XposedHelpers.findAndHookMethod(
                    Intent.class,
                    "getIntExtra",
                    String.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param
                        ) throws Throwable {
                            /*
                             * Intent.getIntExtra() is used extensively throughout
                             * SystemUI. Ignore every key except the battery level.
                             */
                            String key = (String) param.args[0];
        
                            if (!BatteryManager.EXTRA_LEVEL.equals(key)) {
                                return;
                            }
        
                            /*
                             * When BatteryRemapper is disabled, leave Android's
                             * original battery value completely untouched.
                             */
                            if (!remapperEnabled) {
                                return;
                            }
        
                            /*
                             * EXTRA_LEVEL is a generic key named "level".
                             * Process it only for ACTION_BATTERY_CHANGED.
                             */
                            Object thisObject = param.thisObject;
        
                            if (!(thisObject instanceof Intent)) {
                                return;
                            }
        
                            Intent intent = (Intent) thisObject;
        
                            if (!Intent.ACTION_BATTERY_CHANGED.equals(
                                    intent.getAction()
                            )) {
                                return;
                            }
        
                            /*
                             * Validate Android's original result.
                             */
                            Object result = param.getResult();
        
                            if (!(result instanceof Integer)) {
                                return;
                            }
        
                            int originalLevel = (Integer) result;
        
                            if (originalLevel < 0 || originalLevel > 100) {
                                XposedBridge.log(
                                        "BatteryRemapper: Ignored invalid battery "
                                                + "level: "
                                                + originalLevel
                                );
        
                                return;
                            }
        
                            Bundle extras = intent.getExtras();
        
                            int plugged =
                                    extras != null
                                            ? extras.getInt(
                                                    BatteryManager.EXTRA_PLUGGED,
                                                    0
                                            )
                                            : 0;
        
                            int displayedLevel =
                                    Mapping.remap(
                                            originalLevel,
                                            mapMin,
                                            mapMax
                                    );
        
                            Context context =
                                    AndroidAppHelper.currentApplication();
        
                            // 1. BATTERY SAVER HYSTERESIS LOGIC
                            if (batterySaverEnabled && context != null) {
                                handleBatterySaverLogic(
                                        context,
                                        displayedLevel,
                                        plugged
                                );
                            }
        
                            /*
                             * 2. SHUTDOWN TIMER LOGIC
                             *
                             * Driven by the displayed level, which is the value the user is
                             * reacting to, against the configured trigger. Called even while the
                             * feature is off, so it can clean up a countdown that is still
                             * running.
                             */
                            handleShutdownLogic(
                                    displayedLevel,
                                    plugged
                            );
        
                            // 3. APPLY VISUAL SPOOF
                            param.setResult(displayedLevel);
                        }
                    }
            );
        
            XposedBridge.log(
                    "BatteryRemapper: System UI Hooked Successfully."
            );
        } catch (Throwable t) {
            /*
             * Allow a later load callback to retry if hook installation
             * failed before the interceptor was installed.
             */
            batteryHookInstalled.set(false);
        
            XposedBridge.log(
                    "BatteryRemapper Error: " + t.getMessage()
            );
        }
    }

    private void handleBatterySaverLogic(Context context, int level, int plugged) {
        /*
         * The cache is updated only when the request actually succeeded, so a failed toggle is
         * retried on the next battery event instead of being remembered as already applied.
         */

        // CHARGING: Force OFF immediately
        if (plugged != 0) {
            if (appliedSaverState != 0 && setBatterySaver(context, false)) {
                appliedSaverState = 0;
            }
            return;
        }

        // UNPLUGGED: Hysteresis Logic
        if (level <= SAVER_ON_LEVEL) {
            if (appliedSaverState != 1 && setBatterySaver(context, true)) {
                appliedSaverState = 1;
            }
        } else if (level > SAVER_OFF_LEVEL) {
            if (appliedSaverState != 0 && setBatterySaver(context, false)) {
                appliedSaverState = 0;
            }
        }
        // Between the two thresholds: keep the current state (hysteresis)
    }

    /**
     * Judges the countdown against the displayed level and the configured trigger.
     *
     * <p>Called even while the feature is switched off, so the disabled path can clean up a
     * countdown that is still running.
     */
    private void handleShutdownLogic(int displayedLevel, int plugged) {
        if (!autoShutdownEnabled) {
            /*
             * loadSettings() already cancels when the switch goes off; this is the second layer,
             * so a stale timer or dialog cannot survive a reordering of settings receivers.
             */
            if (isShuttingDown || shutdownDialog != null || shutdownTimer != null) {
                cancelCountdown();
            }

            return;
        }

        if (displayedLevel <= shutdownTrigger && plugged == 0) {
            if (!isShuttingDown) {
                isShuttingDown = true;
                XposedBridge.log(
                        "BatteryRemapper: Shutdown countdown armed at displayed "
                                + displayedLevel
                                + "% (trigger "
                                + shutdownTrigger
                                + "%), unplugged"
                );
                startCountdown();
            }
        } else {
            if (isShuttingDown) cancelCountdown();
        }
    }

    /**
     * Applies Battery Saver through the native API, falling back to SettingsLib when that path is
     * unavailable.
     *
     * <p>The two paths are tried in order and the first success wins: calling both would send two
     * requests for one state change, and routing the fallback through an outer {@code catch} meant
     * it was never attempted when the native call threw.
     *
     * @return whether one of the two paths actually applied the state
     */
    private boolean setBatterySaver(Context context, boolean enable) {
        if (tryPowerManagerBatterySaver(context, enable)) {
            XposedBridge.log("BatteryRemapper: Battery Saver -> " + (enable ? "ON" : "OFF"));
            return true;
        }

        if (trySettingsLibBatterySaver(context, enable)) {
            XposedBridge.log(
                    "BatteryRemapper: Battery Saver -> "
                            + (enable ? "ON" : "OFF")
                            + " (via SettingsLib)"
            );
            return true;
        }

        XposedBridge.log(
                "BatteryRemapper: Battery Saver toggle failed; neither path was available"
        );

        return false;
    }

    /** Native path: {@code PowerManager.setPowerSaveModeEnabled}. */
    private boolean tryPowerManagerBatterySaver(Context context, boolean enable) {
        try {
            Object powerManager = context.getSystemService(Context.POWER_SERVICE);

            if (powerManager == null) {
                return false;
            }

            XposedHelpers.callMethod(powerManager, "setPowerSaveModeEnabled", enable);

            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** ROM path: {@code SettingsLib.fuelgauge.BatterySaverUtils}, used by MIUI and others. */
    private boolean trySettingsLibBatterySaver(Context context, boolean enable) {
        try {
            Class<?> saverUtils = XposedHelpers.findClass(
                    "com.android.settingslib.fuelgauge.BatterySaverUtils",
                    context.getClassLoader()
            );

            XposedHelpers.callStaticMethod(
                    saverUtils,
                    "setPowerSaveMode",
                    context,
                    enable,
                    true
            );

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // Suppressed for the window type below, which is deprecated but deliberate.
    @SuppressWarnings("deprecation")
    private void startCountdown() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context context = AndroidAppHelper.currentApplication();

                    if (context == null) {
                        XposedBridge.log(
                                "BatteryRemapper: No context, so the countdown cannot be shown"
                        );

                        isShuttingDown = false;
                        return;
                    }

                    int startSeconds =
                            (int) (COUNTDOWN_TOTAL_MS / COUNTDOWN_TICK_MS);

                    AlertDialog.Builder builder = new AlertDialog.Builder(
                            context,
                            android.R.style.Theme_DeviceDefault_Dialog_Alert
                    );

                    builder.setTitle(
                            moduleString(
                                    R.string.shutdown_dialog_title,
                                    FALLBACK_DIALOG_TITLE
                            )
                    );

                    builder.setMessage(countdownMessage(startSeconds));
                    builder.setCancelable(false);
                    
                    shutdownDialog = builder.create();

                    /*
                     * The module runs inside System UI, which is the system uid, and among the
                     * system window types this is the one the dialog actually appears with. The
                     * modern replacement (TYPE_APPLICATION_OVERLAY) needs the overlay permission
                     * and shows nothing from here, so the deprecated type is kept on purpose.
                     */
                    shutdownDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
                    shutdownDialog.show();

                    shutdownTimer = new CountDownTimer(COUNTDOWN_TOTAL_MS, COUNTDOWN_TICK_MS) {
                        @Override
                        public void onTick(long millisUntilFinished) {
                            if (shutdownDialog != null && shutdownDialog.isShowing()) {
                                shutdownDialog.setMessage(
                                        countdownMessage(
                                                (int) (millisUntilFinished / COUNTDOWN_TICK_MS)
                                        )
                                );
                            }
                        }

                        @Override
                        public void onFinish() {
                            cancelCountdown();
                            triggerShutdown();
                        }
                    }.start();
                } catch (Throwable t) {
                    /*
                     * A ROM-specific dialog failure must not become an immediate shutdown. The
                     * promise is a 30 second warning, so give the shutdown up instead of
                     * short-circuiting the warning the user was supposed to get.
                     */
                    XposedBridge.log(
                            "BatteryRemapper: Countdown UI failure; shutdown cancelled: "
                                    + t.getMessage()
                    );

                    isShuttingDown = false;
                    cancelCountdown();
                }
            }
        });
    }

    private void cancelCountdown() {
        isShuttingDown = false;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (shutdownTimer != null) {
                    shutdownTimer.cancel();
                    shutdownTimer = null;
                }
                if (shutdownDialog != null) {
                    if (shutdownDialog.isShowing()) {
                        shutdownDialog.dismiss();
                    }
                    shutdownDialog = null;
                }
            }
        });
    }

    private void triggerShutdown() {
        try {
            Context context = AndroidAppHelper.currentApplication();
            if (context != null) {
                Intent intent = new Intent("com.android.internal.intent.action.REQUEST_SHUTDOWN");
                intent.putExtra("android.intent.extra.KEY_CONFIRM", false);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                XposedBridge.log("BatteryRemapper: Executed shutdown.");
            }
        } catch (Throwable t) {
            XposedBridge.log("BatteryRemapper Shutdown Failure: " + t.getMessage());
            isShuttingDown = false;
        }
    }

    /** Physical level to displayed level, shared with the app's preview through Mapping. */
    private void loadSettings(Context context) {
        if (context == null) {
            return;
        }
    
        try {
            Bundle result =
                    context.getContentResolver().call(
                            SETTINGS_URI,
                            METHOD_GET_SETTINGS,
                            null,
                            null
                    );
    
            if (result == null) {
                XposedBridge.log(
                        "BatteryRemapper: Settings provider returned no data."
                );
    
                return;
            }
    
            boolean newRemapperEnabled =
                    result.getBoolean(
                            RESULT_REMAPPER_ENABLED,
                            true
                    );
    
            boolean newBatterySaverEnabled =
                    newRemapperEnabled
                            && result.getBoolean(
                                    RESULT_BATTERY_SAVER_ENABLED,
                                    true
                            );
    
            boolean newAutoShutdownEnabled =
                    newRemapperEnabled
                            && result.getBoolean(
                                    RESULT_AUTO_SHUTDOWN_ENABLED,
                                    false
                            );
    
            int[] newRange = AppPreferences.normalizeRange(
                    result.getInt(
                            SettingsProvider.RESULT_MAP_MIN,
                            AppPreferences.DEFAULT_MAP_MIN
                    ),
                    result.getInt(
                            SettingsProvider.RESULT_MAP_MAX,
                            AppPreferences.DEFAULT_MAP_MAX
                    )
            );

            int newShutdownTrigger =
                    AppPreferences.normalizeShutdownTrigger(
                            result.getInt(
                                    SettingsProvider.RESULT_SHUTDOWN_TRIGGER,
                                    AppPreferences.DEFAULT_SHUTDOWN_TRIGGER
                            )
                    );

            boolean saverWasEnabled = batterySaverEnabled;
            boolean shutdownWasEnabled = autoShutdownEnabled;
            boolean windowChanged =
                    mapMin != newRange[0] || mapMax != newRange[1];
            boolean triggerChanged =
                    shutdownTrigger != newShutdownTrigger;

            remapperEnabled = newRemapperEnabled;
            batterySaverEnabled = newBatterySaverEnabled;
            autoShutdownEnabled = newAutoShutdownEnabled;
            mapMin = newRange[0];
            mapMax = newRange[1];
            shutdownTrigger = newShutdownTrigger;
    
            /*
             * Reset the internal Saver cache when automation is disabled.
             * BatteryRemapper does not force the actual system Saver state.
             */
            if (saverWasEnabled && !batterySaverEnabled) {
                appliedSaverState = -1;
            }
    
            /*
             * Turning Automatic Shutdown OFF must immediately cancel
             * any active shutdown countdown.
             */
            if (shutdownWasEnabled && !autoShutdownEnabled) {
                cancelCountdown();
            }
    
            /*
             * The master preference should normally change only across
             * a SystemUI restart, but keep cleanup defensive.
             */
            if (!remapperEnabled) {
                appliedSaverState = -1;
                cancelCountdown();
            }
    
            /*
             * A changed window stays invisible until System UI recomputes the level, which it
             * only does when an ACTION_BATTERY_CHANGED broadcast arrives.
             */
            if (windowChanged) {
                requestBatteryRefresh();
            }

            /*
             * A changed trigger has to be judged against the battery right now, and a feature
             * that was just switched off has to take its countdown down.
             */
            if (triggerChanged || !autoShutdownEnabled) {
                evaluateCountdownNow();
            }

            XposedBridge.log(
                    "BatteryRemapper: Settings loaded"
                            + " [remapper="
                            + remapperEnabled
                            + ", saver="
                            + batterySaverEnabled
                            + ", shutdown="
                            + autoShutdownEnabled
                            + ", physical "
                            + mapMin
                            + "%.."
                            + mapMax
                            + "% -> displayed 0%..100%"
                            + ", trigger at displayed "
                            + shutdownTrigger
                            + "%]"
            );
        } catch (Throwable t) {
            XposedBridge.log(
                    "BatteryRemapper Settings Load Failure: "
                            + t.getMessage()
            );
        }
    }

    private void registerSettingsReceiver(Context context) {
        if (context == null || settingsReceiverRegistered) {
            return;
        }
    
        try {
            IntentFilter filter =
                    new IntentFilter(ACTION_SETTINGS_CHANGED);
    
            BroadcastReceiver receiver =
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(
                                Context receiverContext,
                                Intent intent
                        ) {
                            if (intent == null
                                    || !ACTION_SETTINGS_CHANGED.equals(
                                            intent.getAction()
                                    )) {
                                return;
                            }
    
                            loadSettings(receiverContext);
                        }
                    };
    
            if (Build.VERSION.SDK_INT
                    >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                        receiver,
                        filter,
                        Context.RECEIVER_EXPORTED
                );
            } else {
                context.registerReceiver(
                        receiver,
                        filter
                );
            }
    
            settingsReceiverRegistered = true;
    
            XposedBridge.log(
                    "BatteryRemapper: Settings receiver registered."
            );
        } catch (Throwable t) {
            XposedBridge.log(
                    "BatteryRemapper Settings Receiver Failure: "
                            + t.getMessage()
            );
        }
    }
    
    private void registerStatusReceiver(Context context) {
        if (statusReceiverRegistered || context == null) {
            return;
        }
    
        try {
            IntentFilter filter = new IntentFilter(ACTION_PROBE_HOOK);
    
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(
                        Context receiverContext,
                        Intent intent
                ) {
                    if (intent == null
                            || !ACTION_PROBE_HOOK.equals(
                                    intent.getAction()
                            )) {
                        return;
                    }
    
                    String requestId = intent.getStringExtra(
                            EXTRA_REQUEST_ID
                    );
    
                    if (requestId == null || requestId.isEmpty()) {
                        return;
                    }
    
                    Intent response = new Intent(ACTION_HOOK_STATUS);
    
                    // Deliver only to the BatteryRemapper app.
                    response.setPackage(MODULE_PACKAGE);
    
                    response.putExtra(
                            EXTRA_REQUEST_ID,
                            requestId
                    );
    
                    response.putExtra(
                            EXTRA_HOOK_ACTIVE,
                            true
                    );
    
                    response.putExtra(
                            EXTRA_HOOKED_PACKAGE,
                            "com.android.systemui"
                    );
    
                    receiverContext.sendBroadcast(response);
                }
            };
    
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                        receiver,
                        filter,
                        Context.RECEIVER_EXPORTED
                );
            } else {
                context.registerReceiver(
                        receiver,
                        filter
                );
            }
    
            statusReceiverRegistered = true;
    
            XposedBridge.log(
                    "BatteryRemapper: Status receiver registered."
            );
        } catch (Throwable t) {
            XposedBridge.log(
                    "BatteryRemapper Status Receiver Failure: "
                            + t.getMessage()
            );
        }
    }

    /**
     * Watches the battery directly.
     *
     * <p>Without this, the charger being connected only cancelled a running countdown once System
     * UI happened to read the level again; here every battery event re-judges it.
     */
    private void registerBatteryReceiver(Context context) {
        if (context == null || batteryReceiverRegistered) {
            return;
        }

        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null
                            || !Intent.ACTION_BATTERY_CHANGED.equals(
                                    intent.getAction()
                            )) {
                        return;
                    }

                    Bundle extras = intent.getExtras();

                    if (extras == null) {
                        return;
                    }

                    int level = extras.getInt(BatteryManager.EXTRA_LEVEL, -1);

                    if (level < 0 || level > 100) {
                        return;
                    }

                    handleShutdownLogic(
                            Mapping.remap(level, mapMin, mapMax),
                            extras.getInt(BatteryManager.EXTRA_PLUGGED, 0)
                    );
                }
            };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                        receiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED
                );
            } else {
                context.registerReceiver(receiver, filter);
            }

            batteryReceiverRegistered = true;

            XposedBridge.log(
                    "BatteryRemapper: Battery receiver registered, so connecting the "
                            + "charger cancels the countdown on its own."
            );
        } catch (Throwable t) {
            XposedBridge.log(
                    "BatteryRemapper Battery Receiver Failure: " + t.getMessage()
            );
        }
    }

    /**
     * Asks System UI to re-read the battery level.
     *
     * <p>System UI recomputes the displayed percentage only when an
     * {@code ACTION_BATTERY_CHANGED} broadcast arrives, so a window that changes while the
     * battery is steady would otherwise stay invisible until the next real battery event.
     */
    private void requestBatteryRefresh() {
        Context context = AndroidAppHelper.currentApplication();

        if (context == null) {
            return;
        }

        try {
            Intent current = context.registerReceiver(
                    null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            );

            if (current == null) {
                return;
            }

            Intent refresh = new Intent(current);

            // Never leak a battery broadcast to other apps.
            refresh.setPackage(context.getPackageName());
            context.sendBroadcast(refresh);

            XposedBridge.log(
                    "BatteryRemapper: Asked System UI to re-read the battery level."
            );
        } catch (Throwable t) {
            XposedBridge.log(
                    "BatteryRemapper: Could not request a battery refresh: "
                            + t.getMessage()
            );
        }
    }

    /** Re-judges the countdown against the battery state right now. */
    private void evaluateCountdownNow() {
        Context context = AndroidAppHelper.currentApplication();

        if (context == null) {
            return;
        }

        try {
            Intent battery = context.registerReceiver(
                    null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            );

            Bundle extras = battery == null ? null : battery.getExtras();

            if (extras == null) {
                return;
            }

            int level = extras.getInt(BatteryManager.EXTRA_LEVEL, -1);

            if (level < 0 || level > 100) {
                return;
            }

            handleShutdownLogic(
                    Mapping.remap(level, mapMin, mapMax),
                    extras.getInt(BatteryManager.EXTRA_PLUGGED, 0)
            );
        } catch (Throwable t) {
            XposedBridge.log(
                    "BatteryRemapper: Could not re-judge the countdown: "
                            + t.getMessage()
            );
        }
    }

    /**
     * Reads a string from the module's own resources.
     *
     * <p>The dialog is raised inside System UI, so the app's Context cannot be used; the module's
     * resources are read through a package context instead. That keeps localization working the
     * normal way, with English in {@code values/} and Chinese in {@code values-zh-rCN/}.
     */
    private String moduleString(int resourceId, String fallback, Object... args) {
        String format = null;

        try {
            Context moduleContext = AndroidAppHelper.currentApplication()
                    .createPackageContext(
                            MODULE_PACKAGE,
                            Context.CONTEXT_IGNORE_SECURITY
                    );

            format = moduleContext.getString(resourceId);
        } catch (Throwable ignored) {
            // Falls back to the built-in English copy below.
        }

        if (format == null) {
            format = fallback;
        }

        if (args.length == 0) {
            return format;
        }

        return String.format(java.util.Locale.getDefault(), format, args);
    }

    private String countdownMessage(int seconds) {
        return moduleString(
                R.string.shutdown_dialog_message,
                FALLBACK_DIALOG_MESSAGE,
                seconds
        );
    }
}
