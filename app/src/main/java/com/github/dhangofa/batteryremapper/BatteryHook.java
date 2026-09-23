package com.github.dhangofa.batteryremapper;

import android.app.AlertDialog;
import android.app.AndroidAppHelper;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.WindowManager;
import android.net.Uri;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
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
    /*
     * Suppresses repeated countdowns after the user dismisses the warning.
     *
     * This remains true only for the current low-battery event. It resets
     * after charging, rising above the trigger, changing the trigger, or
     * disabling the related feature.
     */
    private static volatile boolean shutdownDismissedForCurrentEvent = false;
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
    private static boolean packageReceiverRegistered = false;

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

    private static final String ACTION_TURN_OFF_SAVER = MODULE_PACKAGE + ".action.TURN_OFF_SAVER";
    private static final String SAVER_NOTIFICATION_TAG = "BatteryRemapper_Saver";
    private static final int SAVER_NOTIFICATION_ID = 10029;
    private static final String SAVER_NOTIFICATION_CHANNEL_ID = "battery_saver_channel";
    private static volatile boolean saverManuallyDismissed = false;
    private static volatile boolean saverNotificationPosted = false;
    private static boolean saverActionReceiverRegistered = false;      
    

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        
        // ------------------------------------------------------------------
        // HOOK: SYSTEM UI - Visuals, Hysteresis Saver, & Shutdown Timer
        // ------------------------------------------------------------------
        if (!lpparam.packageName.equals("com.android.systemui")) return;

        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "attach",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param
                        ) {
                            Application application =
                                    (Application) param.thisObject;
        
                            XposedBridge.log(
                                    "BatteryRemapper: System UI application attached."
                            );
        
                            // Run after attachment finishes so provider and receiver
                            // operations use a fully attached System UI context.
                            new Handler(
                                    Looper.getMainLooper()
                            ).post(
                                    () -> initializeRuntimeComponents(application)
                            );
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(
                    "BatteryRemapper Startup Hook Error: "
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

    private void initializeRuntimeComponents(Application application) {
        if (application == null) {
            return;
        }
    
        loadSettings(application);
        registerSettingsReceiver(application);
        registerStatusReceiver(application);
        registerBatteryReceiver(application);
        registerPackageRemovalReceiver(application);
        registerSaverActionReceiver(application);
    }

    private static boolean isPowerSaveMode(Context context) {
        if (context == null) {
            return false;
        }
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isPowerSaveMode();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void handleBatterySaverLogic(Context context, int level, int plugged) {
        if (context == null) {
            return;
        }

        /*
         * The cache is updated only when the request actually succeeded, so a failed toggle is
         * retried on the next battery event instead of being remembered as already applied.
         */

        // CHARGING: Force OFF immediately
        if (plugged != 0) {
            saverManuallyDismissed = false;
            saverNotificationPosted = false;
            if (appliedSaverState != 0 || isPowerSaveMode(context)) {
                if (setBatterySaver(context, false)) {
                    appliedSaverState = 0;
                    cancelBatterySaverNotification(context);
                }
            }
            return;
        }

        /*
         * If the user manually turned off Battery Saver externally (e.g. from Quick Settings)
         * after it was turned on, respect that choice for the current discharge cycle.
         */
        if (appliedSaverState == 1 && !isPowerSaveMode(context)) {
            saverManuallyDismissed = true;
            saverNotificationPosted = false;
            appliedSaverState = 0;
            cancelBatterySaverNotification(context);
            return;
        }

        // UNPLUGGED: Hysteresis Logic
        if (level <= SAVER_ON_LEVEL) {
            if (!saverManuallyDismissed) {
                if (appliedSaverState != 1 || !isPowerSaveMode(context)) {
                    if (setBatterySaver(context, true)) {
                        appliedSaverState = 1;
                    }
                }
                if (appliedSaverState == 1 || isPowerSaveMode(context)) {
                    if (!saverNotificationPosted) {
                        showBatterySaverNotification(context, level);
                        saverNotificationPosted = true;
                    }
                }
            }
        } else if (level > SAVER_OFF_LEVEL) {
            saverManuallyDismissed = false;
            saverNotificationPosted = false;
            if (appliedSaverState != 0 || isPowerSaveMode(context)) {
                if (setBatterySaver(context, false)) {
                    appliedSaverState = 0;
                    cancelBatterySaverNotification(context);
                }
            }
        }
        // Between the two thresholds: keep the current state (hysteresis)
    }

    /**
     * Judges the countdown against the displayed level and configured trigger.
     *
     * The user may dismiss one low-battery event without permanently disabling
     * Automatic Shutdown. The dismissal resets after charging or after the
     * displayed percentage rises above the configured trigger.
     */
    private void handleShutdownLogic(
            int displayedLevel,
            int plugged
    ) {
        /*
         * Disabling Automatic Shutdown must cancel any existing countdown
         * and clear the temporary dismissal state.
         */
        if (!autoShutdownEnabled) {
            shutdownDismissedForCurrentEvent = false;
    
            if (isShuttingDown
                    || shutdownDialog != null
                    || shutdownTimer != null) {
                cancelCountdown();
            }
    
            return;
        }
    
        /*
         * Charging or rising above the trigger ends the current low-battery
         * event. A future drop to or below the trigger may start a new
         * countdown.
         */
        if (plugged != 0 || displayedLevel > shutdownTrigger) {
            shutdownDismissedForCurrentEvent = false;
    
            if (isShuttingDown
                    || shutdownDialog != null
                    || shutdownTimer != null) {
                cancelCountdown();
            }
    
            return;
        }
    
        /*
         * The battery remains at or below the trigger, but the user already
         * chose to continue using the device during this event.
         */
        if (shutdownDismissedForCurrentEvent) {
            return;
        }
    
        /*
         * Start only one countdown for the current event.
         */
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
    }
    /**
     * Applies Battery Saver through a cascading multi-tier strategy supporting
     * AOSP, Pixel, Xiaomi HyperOS/MIUI, Samsung One UI, ColorOS/OxygenOS, and OriginOS.
     *
     * @return whether the Battery Saver state was successfully applied
     */
    private boolean setBatterySaver(Context context, boolean enable) {
        if (context == null) {
            return false;
        }

        boolean success = false;

        // 1. Native PowerManager API (AOSP, Pixel, Motorola, Sony, etc.)
        if (tryPowerManagerBatterySaver(context, enable)) {
            XposedBridge.log(
                    "BatteryRemapper: Battery Saver -> "
                            + (enable ? "ON" : "OFF")
                            + " (via PowerManager)"
            );
            success = true;
        }

        // 2. Direct IPowerManager Binder IPC (bypasses OEM wrapper restrictions)
        if (!success && tryIPowerManagerBatterySaver(enable)) {
            XposedBridge.log(
                    "BatteryRemapper: Battery Saver -> "
                            + (enable ? "ON" : "OFF")
                            + " (via IPowerManager IPC)"
            );
            success = true;
        }

        // 3. SettingsLib Fuelgauge (AOSP / legacy ROMs)
        if (!success && trySettingsLibBatterySaver(context, enable)) {
            XposedBridge.log(
                    "BatteryRemapper: Battery Saver -> "
                            + (enable ? "ON" : "OFF")
                            + " (via SettingsLib)"
            );
            success = true;
        }

        // 4. Global System Setting (universal AOSP setting: Settings.Global.LOW_POWER = "low_power")
        // System UI runs with UID 1000 (system), so this writes to Settings and notifies system server.
        if (tryGlobalSettingsBatterySaver(context, enable)) {
            XposedBridge.log(
                    "BatteryRemapper: Battery Saver -> "
                            + (enable ? "ON" : "OFF")
                            + " (via Settings.Global low_power)"
            );
            success = true;
        }

        // 5. OEM-specific providers and broadcasts (Xiaomi HyperOS / MIUI, Samsung One UI, ColorOS, OriginOS)
        if (tryOemSpecificBatterySaver(context, enable)) {
            XposedBridge.log(
                    "BatteryRemapper: Battery Saver -> "
                            + (enable ? "ON" : "OFF")
                            + " (via OEM-specific hooks)"
            );
            success = true;
        }

        if (isPowerSaveMode(context) == enable) {
            return true;
        }

        return success;
    }

    /**
     * Releases any Battery Saver state previously controlled through
     * BatteryRemapper.
     *
     * A successful OFF request is processed by Android's Power Manager as a
     * manual disable operation, which also releases the corresponding sticky
     * Battery Saver state on supported Android implementations.
     */
    private void releaseBatterySaver(Context context, String reason) {
        if (context == null) {
            XposedBridge.log(
                    "BatteryRemapper: Cannot release Battery Saver because "
                            + "the System UI context is unavailable."
            );
    
            appliedSaverState = -1;
            return;
        }
    
        saverManuallyDismissed = false;
        saverNotificationPosted = false;
        boolean released = setBatterySaver(context, false);
        cancelBatterySaverNotification(context);
    
        /*
         * Reset the cache even when the ROM rejects the request. If automation
         * is enabled again later, the next battery event must evaluate the
         * actual condition instead of trusting an old requested state.
         */
        appliedSaverState = -1;
    
        if (released) {
            XposedBridge.log(
                    "BatteryRemapper: Released Battery Saver state: "
                            + reason
            );
        } else {
            XposedBridge.log(
                    "BatteryRemapper: Could not release Battery Saver state: "
                            + reason
            );
        }
    }

    /** Native path: {@code PowerManager.setPowerSaveModeEnabled}. */
    private boolean tryPowerManagerBatterySaver(Context context, boolean enable) {
        try {
            Object powerManager = context.getSystemService(Context.POWER_SERVICE);

            if (powerManager == null) {
                return false;
            }

            Object result = XposedHelpers.callMethod(powerManager, "setPowerSaveModeEnabled", enable);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** IPC path: Direct call into system_server IPowerManager binder. */
    private boolean tryIPowerManagerBatterySaver(boolean enable) {
        try {
            Class<?> serviceManagerClass = XposedHelpers.findClass("android.os.ServiceManager", null);
            IBinder binder = (IBinder) XposedHelpers.callStaticMethod(
                    serviceManagerClass,
                    "getService",
                    Context.POWER_SERVICE
            );
            if (binder == null) {
                return false;
            }

            Class<?> stubClass = XposedHelpers.findClass("android.os.IPowerManager$Stub", null);
            Object iPowerManager = XposedHelpers.callStaticMethod(stubClass, "asInterface", binder);
            if (iPowerManager == null) {
                return false;
            }

            Object result = XposedHelpers.callMethod(iPowerManager, "setPowerSaveModeEnabled", enable);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** ROM path: {@code SettingsLib.fuelgauge.BatterySaverUtils}, dynamically probing all method signatures. */
    private boolean trySettingsLibBatterySaver(Context context, boolean enable) {
        try {
            Class<?> saverUtils = XposedHelpers.findClass(
                    "com.android.settingslib.fuelgauge.BatterySaverUtils",
                    context.getClassLoader()
            );

            for (Method method : saverUtils.getDeclaredMethods()) {
                if ("setPowerSaveMode".equals(method.getName())
                        && Modifier.isStatic(method.getModifiers())) {
                    Class<?>[] paramTypes = method.getParameterTypes();
                    try {
                        method.setAccessible(true);
                        if (paramTypes.length == 3
                                && paramTypes[0] == Context.class
                                && paramTypes[1] == boolean.class
                                && paramTypes[2] == boolean.class) {
                            Object res = method.invoke(null, context, enable, true);
                            return !(res instanceof Boolean) || (Boolean) res;
                        } else if (paramTypes.length == 4
                                && paramTypes[0] == Context.class
                                && paramTypes[1] == boolean.class
                                && paramTypes[2] == boolean.class
                                && paramTypes[3] == int.class) {
                            // Android 13/14/15 caller reason (0 = SAVE_MODE_SCHEDULE_NONE)
                            Object res = method.invoke(null, context, enable, true, 0);
                            return !(res instanceof Boolean) || (Boolean) res;
                        } else if (paramTypes.length == 2
                                && paramTypes[0] == Context.class
                                && paramTypes[1] == boolean.class) {
                            Object res = method.invoke(null, context, enable);
                            return !(res instanceof Boolean) || (Boolean) res;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Global Settings path: {@code Settings.Global.LOW_POWER} observed by Android PowerManagerService. */
    private boolean tryGlobalSettingsBatterySaver(Context context, boolean enable) {
        try {
            return Settings.Global.putInt(
                    context.getContentResolver(),
                    "low_power",
                    enable ? 1 : 0
            );
        } catch (Throwable t) {
            return false;
        }
    }

    /** OEM-specific Settings and Broadcasts (Xiaomi HyperOS/MIUI, Samsung One UI, ColorOS, OriginOS). */
    private boolean tryOemSpecificBatterySaver(Context context, boolean enable) {
        boolean anyApplied = false;
        android.content.ContentResolver resolver = context.getContentResolver();

        // 1. Xiaomi / Redmi / POCO (HyperOS / MIUI)
        try {
            Settings.System.putInt(
                    resolver,
                    "POWER_SAVE_MODE_OPEN",
                    enable ? 1 : 0
            );
            anyApplied = true;
        } catch (Throwable ignored) {
        }

        try {
            Intent miuiIntent = new Intent("miui.intent.action.POWER_SAVE_MODE_CHANGED");
            miuiIntent.putExtra("state", enable ? 1 : 0);
            context.sendBroadcast(miuiIntent);
            anyApplied = true;
        } catch (Throwable ignored) {
        }

        // 2. Samsung (One UI)
        try {
            Settings.Global.putInt(
                    resolver,
                    "sem_low_power_mode",
                    enable ? 1 : 0
            );
            anyApplied = true;
        } catch (Throwable ignored) {
        }
        try {
            Settings.Global.putInt(
                    resolver,
                    "sem_power_mode",
                    enable ? 1 : 0
            );
            anyApplied = true;
        } catch (Throwable ignored) {
        }
        try {
            Settings.Global.putInt(
                    resolver,
                    "low_power_mode",
                    enable ? 1 : 0
            );
            anyApplied = true;
        } catch (Throwable ignored) {
        }

        // 3. OPPO / OnePlus / Realme (ColorOS / OxygenOS / Realme UI)
        try {
            Settings.System.putInt(
                    resolver,
                    "super_powersave_mode_state",
                    enable ? 1 : 0
            );
            anyApplied = true;
        } catch (Throwable ignored) {
        }

        // 4. Vivo / iQOO (OriginOS / FuntouchOS)
        try {
            Settings.System.putInt(
                    resolver,
                    "super_power_save",
                    enable ? 1 : 0
            );
            anyApplied = true;
        } catch (Throwable ignored) {
        }
        try {
            Settings.System.putInt(
                    resolver,
                    "power_save_mode",
                    enable ? 1 : 0
            );
            anyApplied = true;
        } catch (Throwable ignored) {
        }

        return anyApplied;
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
                    
                    /*
                     * Cancel only the current low-battery countdown.
                     *
                     * Automatic Shutdown remains enabled, but the warning will not
                     * reopen until charging begins or the displayed battery rises
                     * above the configured trigger.
                     */
                    builder.setNegativeButton(
                            moduleString(
                                    R.string.shutdown_dialog_use_anyway,
                                    "Use anyway"
                            ),
                            (dialog, which) -> dismissCurrentShutdownEvent()
                    );
                    
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

                    shutdownDismissedForCurrentEvent = true;
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

    /**
     * Dismisses the current countdown without permanently turning off the
     * Automatic Shutdown setting.
     *
     * The dialog remains suppressed until the battery rises above the
     * configured trigger or a charger is connected.
     */
    private void dismissCurrentShutdownEvent() {
        shutdownDismissedForCurrentEvent = true;
    
        XposedBridge.log(
                "BatteryRemapper: User dismissed the current "
                        + "low-battery shutdown countdown."
        );
    
        cancelCountdown();
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
            boolean remapperWasEnabled = remapperEnabled;

            /*
             * The displayed percentage depends on the master switch as well as on the window:
             * with remapping switched off, System UI still has to be told to recompute the level,
             * or the status bar keeps showing the remapped value until the next real battery
             * event.
             */
            boolean displayChanged =
                    remapperWasEnabled != newRemapperEnabled
                            || mapMin != newRange[0]
                            || mapMax != newRange[1];
            boolean triggerChanged =
                    shutdownTrigger != newShutdownTrigger;

            remapperEnabled = newRemapperEnabled;
            batterySaverEnabled = newBatterySaverEnabled;
            autoShutdownEnabled = newAutoShutdownEnabled;
            mapMin = newRange[0];
            mapMax = newRange[1];
            shutdownTrigger = newShutdownTrigger;

            /*
             * A changed trigger represents a new shutdown condition.
             * Disabling either the master feature or Automatic Shutdown also
             * clears the temporary dismissal state.
             */
            if (triggerChanged
                    || !remapperEnabled
                    || !autoShutdownEnabled) {
                shutdownDismissedForCurrentEvent = false;
            }
    
            /*
             * BatteryRemapper may have enabled Android's global Battery Saver state.
             * Disabling the automation, including through the master switch, must
             * therefore request Battery Saver OFF instead of only forgetting the
             * internal hysteresis cache.
             */
            if ((saverWasEnabled && !batterySaverEnabled)
                    || (remapperWasEnabled && !remapperEnabled && (saverWasEnabled || isPowerSaveMode(context)))) {
                releaseBatterySaver(
                        context,
                        remapperEnabled
                                ? "Battery Saver automation disabled"
                                : "master remapping disabled"
                );
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
                cancelCountdown();
            }
    
            /*
             * A changed window - or a master switch that now changes what is displayed - stays
             * invisible until System UI recomputes the level, which it only does when an
             * ACTION_BATTERY_CHANGED broadcast arrives.
             */
            if (displayChanged) {
                requestBatteryRefresh();
            }

            /*
             * A changed trigger has to be judged against the battery right now, and a feature
             * that was just switched off has to take its countdown down.
             */
            if (triggerChanged || !autoShutdownEnabled) {
                evaluateCountdownNow();
            }

            /*
             * If Battery Saver automation is enabled or remapping range changed, immediately
             * evaluate Battery Saver against the current battery state.
             */
            if (batterySaverEnabled) {
                evaluateBatterySaverNow();
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

                    int plugged = extras.getInt(BatteryManager.EXTRA_PLUGGED, 0);
                    int displayedLevel = Mapping.remap(level, mapMin, mapMax);

                    if (batterySaverEnabled) {
                        handleBatterySaverLogic(
                                receiverContext,
                                displayedLevel,
                                plugged
                        );
                    }

                    handleShutdownLogic(
                            displayedLevel,
                            plugged
                    );
                }
            };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                        receiver,
                        filter,
                        Context.RECEIVER_EXPORTED
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
     * Releases Battery Saver if the BatteryRemapper package is uninstalled
     * while its hook is still loaded inside System UI.
     *
     * Uninstalling an LSPosed module does not necessarily terminate System UI
     * before Android delivers the package-removal broadcast. This receiver gives
     * the loaded hook one final opportunity to release the global Saver state.
     */
    private void registerPackageRemovalReceiver(Context context) {
        if (context == null || packageReceiverRegistered) {
            return;
        }
    
        try {
            IntentFilter filter =
                    new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
    
            filter.addDataScheme("package");
    
            BroadcastReceiver receiver =
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(
                                Context receiverContext,
                                Intent intent
                        ) {
                            if (intent == null
                                    || !Intent.ACTION_PACKAGE_REMOVED.equals(
                                            intent.getAction()
                                    )) {
                                return;
                            }
    
                            Uri removedPackage = intent.getData();
    
                            if (removedPackage == null
                                    || !MODULE_PACKAGE.equals(
                                            removedPackage
                                                    .getSchemeSpecificPart()
                                    )) {
                                return;
                            }
    
                            /*
                             * Ignore package replacement during an APK update.
                             * Android sends PACKAGE_REMOVED with this extra before
                             * installing the replacement package.
                             */
                            if (intent.getBooleanExtra(
                                    Intent.EXTRA_REPLACING,
                                    false
                            )) {
                                return;
                            }
    
                            XposedBridge.log(
                                    "BatteryRemapper: Module package removed; "
                                            + "releasing automation state."
                            );
    
                            remapperEnabled = false;
                            batterySaverEnabled = false;
                            autoShutdownEnabled = false;
                            shutdownDismissedForCurrentEvent = false;
    
                            cancelCountdown();
    
                            /*
                             * Perform the cleanup regardless of the cached
                             * appliedSaverState. The Android power service may
                             * retain a manual or sticky state even after process
                             * and framework restarts.
                             */
                            releaseBatterySaver(
                                    receiverContext,
                                    "module package uninstalled"
                            );
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
    
            packageReceiverRegistered = true;
    
            XposedBridge.log(
                    "BatteryRemapper: Package-removal receiver registered."
            );
        } catch (Throwable t) {
            XposedBridge.log(
                    "BatteryRemapper Package Receiver Failure: "
                            + t.getMessage()
            );
        }
    }

    private void registerSaverActionReceiver(Context context) {
        if (context == null || saverActionReceiverRegistered) {
            return;
        }

        try {
            IntentFilter filter = new IntentFilter(ACTION_TURN_OFF_SAVER);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null || !ACTION_TURN_OFF_SAVER.equals(intent.getAction())) {
                        return;
                    }

                    XposedBridge.log(
                            "BatteryRemapper: User requested Battery Saver OFF via notification action."
                    );
                    saverManuallyDismissed = true;
                    saverNotificationPosted = false;
                    setBatterySaver(receiverContext, false);
                    appliedSaverState = 0;
                    cancelBatterySaverNotification(receiverContext);
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

            saverActionReceiverRegistered = true;
            XposedBridge.log("BatteryRemapper: Saver action receiver registered.");
        } catch (Throwable t) {
            XposedBridge.log(
                    "BatteryRemapper Saver Action Receiver Failure: " + t.getMessage()
            );
        }
    }

    private void showBatterySaverNotification(Context context, int level) {
        if (context == null) {
            return;
        }

        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }

            String channelName = moduleString(R.string.battery_saver_channel_name, "Battery Saver");
            String channelId = SAVER_NOTIFICATION_CHANNEL_ID;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    List<NotificationChannel> channels = nm.getNotificationChannels();
                    if (channels != null) {
                        for (NotificationChannel ch : channels) {
                            String id = ch.getId();
                            if (id != null) {
                                String lower = id.toLowerCase();
                                if (lower.equals("battery")
                                        || lower.equals("bat")
                                        || lower.equals("power")
                                        || lower.contains("battery")
                                        || lower.contains("saver")) {
                                    channelId = id;
                                    break;
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log(
                            "BatteryRemapper: Channel discovery failed: " + t.getMessage()
                    );
                }

                if (nm.getNotificationChannel(channelId) == null) {
                    NotificationChannel channel = new NotificationChannel(
                            channelId,
                            channelName,
                            NotificationManager.IMPORTANCE_HIGH
                    );
                    channel.setDescription("Notifications about Battery Saver state");
                    channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                    channel.setShowBadge(true);
                    try {
                        nm.createNotificationChannel(channel);
                    } catch (Throwable t) {
                        XposedBridge.log(
                                "BatteryRemapper: Channel creation failed: " + t.getMessage()
                        );
                    }
                }
            }

            Icon smallIcon = null;
            String sysUiPkg = context.getPackageName();
            String[] candidateDrawables = new String[] {
                    "ic_battery_saver",
                    "ic_power_saver",
                    "stat_sys_battery_saver",
                    "stat_sys_battery",
                    "ic_battery_alert",
                    "stat_sys_battery_charge",
                    "ic_sysbar_battery"
            };

            for (String candidate : candidateDrawables) {
                try {
                    int resId = context.getResources().getIdentifier(
                            candidate,
                            "drawable",
                            sysUiPkg
                    );
                    if (resId != 0) {
                        smallIcon = Icon.createWithResource(sysUiPkg, resId);
                        break;
                    }
                } catch (Throwable ignored) {}
            }

            if (smallIcon == null && context.getApplicationInfo() != null && context.getApplicationInfo().icon != 0) {
                smallIcon = Icon.createWithResource(sysUiPkg, context.getApplicationInfo().icon);
            }

            if (smallIcon == null) {
                int frameworkIcon = 0;
                try {
                    frameworkIcon = context.getResources().getIdentifier(
                            "stat_sys_battery_saver",
                            "drawable",
                            "android"
                    );
                } catch (Throwable ignored) {}
                if (frameworkIcon == 0) {
                    frameworkIcon = android.R.drawable.stat_sys_warning;
                }
                smallIcon = Icon.createWithResource("android", frameworkIcon);
            }

            Intent settingsIntent = new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS);
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent contentIntent = PendingIntent.getActivity(
                    context,
                    0,
                    settingsIntent,
                    pendingFlags
            );

            Intent turnOffIntent = new Intent(ACTION_TURN_OFF_SAVER);
            PendingIntent turnOffPendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    turnOffIntent,
                    pendingFlags
            );

            String title = moduleString(R.string.battery_saver_notif_title, "Battery Saver is on");
            String text = moduleString(
                    R.string.battery_saver_notif_text,
                    "Turned on automatically at " + level + "% battery.",
                    level
            );
            String turnOffLabel = moduleString(R.string.battery_saver_notif_turn_off, "Turn off");

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(context, channelId);
            } else {
                builder = new Notification.Builder(context);
            }

            builder.setSmallIcon(smallIcon)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setPriority(Notification.PRIORITY_HIGH);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                Notification.Action turnOffAction = new Notification.Action.Builder(
                        0,
                        turnOffLabel,
                        turnOffPendingIntent
                ).build();
                builder.addAction(turnOffAction);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setCategory(Notification.CATEGORY_STATUS);
                builder.setVisibility(Notification.VISIBILITY_PUBLIC);
            }

            Notification notification = builder.build();

            boolean posted = false;
            try {
                Method notifyAsUser = NotificationManager.class.getMethod(
                        "notifyAsUser",
                        String.class,
                        int.class,
                        Notification.class,
                        UserHandle.class
                );
                UserHandle targetUser = null;
                try {
                    targetUser = (UserHandle) XposedHelpers.getStaticObjectField(UserHandle.class, "ALL");
                } catch (Throwable ignored) {}
                if (targetUser == null) {
                    try {
                        targetUser = (UserHandle) XposedHelpers.getStaticObjectField(UserHandle.class, "CURRENT");
                    } catch (Throwable ignored) {}
                }
                if (targetUser == null) {
                    targetUser = Process.myUserHandle();
                }
                notifyAsUser.invoke(nm, SAVER_NOTIFICATION_TAG, SAVER_NOTIFICATION_ID, notification, targetUser);
                posted = true;
            } catch (Throwable t) {
                XposedBridge.log(
                        "BatteryRemapper: notifyAsUser failed, falling back to standard notify: "
                                + t.getMessage()
                );
            }

            if (!posted) {
                nm.notify(SAVER_NOTIFICATION_TAG, SAVER_NOTIFICATION_ID, notification);
            }

            XposedBridge.log(
                    "BatteryRemapper: Posted 'Battery Saver is on' system notification at "
                            + level
                            + "%."
            );
        } catch (Throwable t) {
            XposedBridge.log(
                    "BatteryRemapper: Failed to post Battery Saver notification: "
                            + t.getMessage()
            );
        }
    }

    private void cancelBatterySaverNotification(Context context) {
        if (context == null) {
            return;
        }
        saverNotificationPosted = false;
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }

            boolean cancelled = false;
            try {
                Method cancelAsUser = NotificationManager.class.getMethod(
                        "cancelAsUser",
                        String.class,
                        int.class,
                        UserHandle.class
                );
                UserHandle targetUser = null;
                try {
                    targetUser = (UserHandle) XposedHelpers.getStaticObjectField(UserHandle.class, "ALL");
                } catch (Throwable ignored) {}
                if (targetUser == null) {
                    try {
                        targetUser = (UserHandle) XposedHelpers.getStaticObjectField(UserHandle.class, "CURRENT");
                    } catch (Throwable ignored) {}
                }
                if (targetUser == null) {
                    targetUser = Process.myUserHandle();
                }
                cancelAsUser.invoke(nm, SAVER_NOTIFICATION_TAG, SAVER_NOTIFICATION_ID, targetUser);
                cancelled = true;
            } catch (Throwable ignored) {}

            if (!cancelled) {
                nm.cancel(SAVER_NOTIFICATION_TAG, SAVER_NOTIFICATION_ID);
            }
        } catch (Throwable ignored) {}
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

    /** Re-evaluates Battery Saver state against current battery state immediately. */
    private void evaluateBatterySaverNow() {
        Context context = AndroidAppHelper.currentApplication();

        if (context == null || !batterySaverEnabled) {
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

            int plugged = extras.getInt(BatteryManager.EXTRA_PLUGGED, 0);
            int displayedLevel = Mapping.remap(level, mapMin, mapMax);

            handleBatterySaverLogic(context, displayedLevel, plugged);
        } catch (Throwable t) {
            XposedBridge.log(
                    "BatteryRemapper: Could not re-evaluate Battery Saver: "
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
