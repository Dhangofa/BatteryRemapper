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
                                    remapBattery(originalLevel);
        
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
        
                            // 2. SHUTDOWN TIMER LOGIC (Based on physical level)
                            if (autoShutdownEnabled) {
                                handleShutdownLogic(
                                        originalLevel,
                                        plugged
                                );
                            }
        
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
        // CHARGING: Force OFF immediately
        if (plugged != 0) {
            if (appliedSaverState != 0) {
                setBatterySaver(context, false);
                appliedSaverState = 0;
            }
            return;
        }

        // UNPLUGGED: Hysteresis Logic
        if (level <= 20) {
            // Below 20%: Force ON
            if (appliedSaverState != 1) {
                setBatterySaver(context, true);
                appliedSaverState = 1;
            }
        } else if (level > 50) {
            // Above 50%: Force OFF
            if (appliedSaverState != 0) {
                setBatterySaver(context, false);
                appliedSaverState = 0;
            }
        }
        // Levels 21-50: Do nothing (Maintain state)
    }

    private void handleShutdownLogic(int originalLevel, int plugged) {
        if (originalLevel <= 20 && plugged == 0) {
            if (!isShuttingDown) {
                isShuttingDown = true;
                startCountdown();
            }
        } else {
            if (isShuttingDown) cancelCountdown();
        }
    }

    private void setBatterySaver(Context context, boolean enable) {
        try {
            // 1. Native API
            Object powerManager = context.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                XposedHelpers.callMethod(powerManager, "setPowerSaveModeEnabled", enable);
            }
            // 2. ROM-Specific UI Authorized Fallback
            try {
                Class<?> saverUtils = XposedHelpers.findClass("com.android.settingslib.fuelgauge.BatterySaverUtils", context.getClassLoader());
                XposedHelpers.callStaticMethod(saverUtils, "setPowerSaveMode", context, enable, true);
            } catch (Throwable ignored) { }
            
            XposedBridge.log("BatteryRemapper: Battery Saver -> " + (enable ? "ON" : "OFF"));
        } catch (Throwable t) {
            XposedBridge.log("BatteryRemapper: Toggle Error: " + t.getMessage());
        }
    }

    private void startCountdown() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context context = AndroidAppHelper.currentApplication();
                    if (context == null) return;

                    AlertDialog.Builder builder = new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert);
                    builder.setTitle("Battery Depleted");
                    builder.setMessage("Device will shut down in 30 seconds.\nPlug in charger to cancel.");
                    builder.setCancelable(false);
                    
                    shutdownDialog = builder.create();
                    shutdownDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
                    shutdownDialog.show();

                    shutdownTimer = new CountDownTimer(30000, 1000) {
                        @Override
                        public void onTick(long millisUntilFinished) {
                            if (shutdownDialog != null && shutdownDialog.isShowing()) {
                                shutdownDialog.setMessage("Device will shut down in " + (millisUntilFinished / 1000) + " seconds.\nPlug in charger to cancel.");
                            }
                        }

                        @Override
                        public void onFinish() {
                            cancelCountdown();
                            triggerShutdown();
                        }
                    }.start();
                } catch (Throwable t) {
                    XposedBridge.log("BatteryRemapper UI Failure: " + t.getMessage());
                    triggerShutdown(); 
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

    private int remapBattery(int physicalLevel) {
        if (physicalLevel <= 20) return 0;
        if (physicalLevel >= 80) return 100;
        return Math.round((float)(physicalLevel - 20) * 100f / 60f);
    }

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
    
            boolean saverWasEnabled = batterySaverEnabled;
            boolean shutdownWasEnabled = autoShutdownEnabled;
            remapperEnabled = newRemapperEnabled;
            batterySaverEnabled = newBatterySaverEnabled;
            autoShutdownEnabled = newAutoShutdownEnabled;
    
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
    
            XposedBridge.log(
                    "BatteryRemapper: Settings loaded"
                            + " [remapper="
                            + remapperEnabled
                            + ", saver="
                            + batterySaverEnabled
                            + ", shutdown="
                            + autoShutdownEnabled
                            + "]"
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
}
