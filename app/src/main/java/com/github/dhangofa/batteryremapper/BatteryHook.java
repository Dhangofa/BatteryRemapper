package com.github.dhangofa.batteryremapper;

import android.app.AlertDialog;
import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class BatteryHook implements IXposedHookLoadPackage {

    private static boolean isShuttingDown = false;
    private static AlertDialog shutdownDialog = null;
    private static CountDownTimer shutdownTimer = null;
    
    // -1 = Neutral/Unknown, 0 = Force OFF, 1 = Force ON
    private static int appliedSaverState = -1;      

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        
        // ------------------------------------------------------------------
        // HOOK: SYSTEM UI - Visuals, Hysteresis Saver, & Shutdown Timer
        // ------------------------------------------------------------------
        if (!lpparam.packageName.equals("com.android.systemui")) return;

        try {
            XposedHelpers.findAndHookMethod(Intent.class, "getIntExtra", String.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    
                    if (BatteryManager.EXTRA_LEVEL.equals(key)) {
                        int originalLevel = (Integer) param.getResult();
                        Intent intent = (Intent) param.thisObject;
                        Bundle extras = intent.getExtras();
                        
                        int plugged = (extras != null) ? extras.getInt(BatteryManager.EXTRA_PLUGGED, 0) : 0;
                        int displayedLevel = remapBattery(originalLevel);
                        Context context = AndroidAppHelper.currentApplication();

                        // 1. BATTERY SAVER HYSTERESIS LOGIC
                        if (context != null) {
                            handleBatterySaverLogic(context, displayedLevel, plugged);
                        }

                        // 2. SHUTDOWN TIMER LOGIC (Based on physical level)
                        handleShutdownLogic(originalLevel, plugged);
                        
                        // 3. APPLY VISUAL SPOOF
                        param.setResult(displayedLevel);
                    }
                }
            });
            XposedBridge.log("BatteryRemapper: System UI Hooked Successfully.");
        } catch (Throwable t) {
            XposedBridge.log("BatteryRemapper Error: " + t.getMessage());
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
}
