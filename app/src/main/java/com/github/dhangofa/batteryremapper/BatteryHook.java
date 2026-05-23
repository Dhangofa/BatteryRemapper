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
    
    private static boolean isSaverTargetOn = false; 
    private static int appliedSaverState = -1;      

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        
        // ------------------------------------------------------------------
        // HOOK: SYSTEM UI - Visuals, Saver Automation, & Shutdown Timer
        // ------------------------------------------------------------------
        if (lpparam.packageName.equals("com.android.systemui")) {
            try {
                XposedHelpers.findAndHookMethod(Intent.class, "getIntExtra", String.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String key = (String) param.args[0];
                        
                        if (BatteryManager.EXTRA_LEVEL.equals(key)) {
                            // 1. Get the true physical hardware state
                            int originalLevel = (Integer) param.getResult(); 
                            
                            Intent intent = (Intent) param.thisObject;
                            Bundle extras = intent.getExtras();
                            int plugged = extras != null ? extras.getInt(BatteryManager.EXTRA_PLUGGED, 0) : 0;
                            
                            // 2. Calculate the fake UI percentage
                            int displayedLevel = remapBattery(originalLevel);
                            
                            // 3. BATTERY SAVER AUTOMATION
                            // Triggered directly from SystemUI so the ROM thinks a human pressed the QS Tile
                            Context context = AndroidAppHelper.currentApplication();
                            if (context != null) {
                                if (displayedLevel <= 20) {
                                    isSaverTargetOn = true;
                                } else if (displayedLevel >= 51) {
                                    isSaverTargetOn = false;
                                }

                                if (plugged == 0) {
                                    if (isSaverTargetOn && appliedSaverState != 1) {
                                        enableBatterySaver(context);
                                        appliedSaverState = 1;
                                    } else if (!isSaverTargetOn && appliedSaverState != 0) {
                                        disableBatterySaver(context);
                                        appliedSaverState = 0;
                                    }
                                } else {
                                    appliedSaverState = -1;
                                }
                            }

                            // 4. SHUTDOWN TIMER (Based on Physical Level)
                            if (originalLevel <= 20) { 
                                if (plugged == 0) {
                                    if (!isShuttingDown) {
                                        isShuttingDown = true;
                                        startCountdown();
                                    }
                                } else {
                                    if (isShuttingDown) {
                                        cancelCountdown();
                                    }
                                }
                            } else {
                                if (isShuttingDown) {
                                    cancelCountdown();
                                }
                            }
                            
                            // 5. Apply the Visual Spoof to the Status Bar
                            param.setResult(displayedLevel);
                        }
                    }
                });
                
                XposedBridge.log("BatteryRemapper: System UI hooked for Visuals, Saver, & Shutdown!");
                
            } catch (Throwable t) {
                XposedBridge.log("BatteryRemapper UI Hook Critical Failure: " + t.getMessage());
            }
        }
    }

    // ======================================================================
    // HELPER METHODS (Executing as SystemUI Native)
    // ======================================================================

    private void enableBatterySaver(Context context) {
        try {
            // Primary Method: Standard Native API
            Object powerManager = context.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                XposedHelpers.callMethod(powerManager, "setPowerSaveModeEnabled", true);
            }
            
            // Secondary Fallback: Mimic the exact class the Quick Settings tile uses
            try {
                Class<?> saverUtils = XposedHelpers.findClass("com.android.settingslib.fuelgauge.BatterySaverUtils", context.getClassLoader());
                XposedHelpers.callStaticMethod(saverUtils, "setPowerSaveMode", context, true, true);
            } catch (Throwable ignored) { }
            
            XposedBridge.log("BatteryRemapper: Battery Saver ON (SystemUI Authorized).");
        } catch (Throwable t) {
            XposedBridge.log("BatteryRemapper: SystemUI Toggle Ignored: " + t.getMessage());
        }
    }

    private void disableBatterySaver(Context context) {
        try {
            Object powerManager = context.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                XposedHelpers.callMethod(powerManager, "setPowerSaveModeEnabled", false);
            }
            
            try {
                Class<?> saverUtils = XposedHelpers.findClass("com.android.settingslib.fuelgauge.BatterySaverUtils", context.getClassLoader());
                XposedHelpers.callStaticMethod(saverUtils, "setPowerSaveMode", context, false, false);
            } catch (Throwable ignored) { }
            
            XposedBridge.log("BatteryRemapper: Battery Saver OFF (SystemUI Authorized).");
        } catch (Throwable t) {
            XposedBridge.log("BatteryRemapper: SystemUI Toggle Ignored: " + t.getMessage());
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
                try {
                    Intent intent = new Intent("com.android.internal.intent.action.REQUEST_SHUTDOWN");
                    intent.putExtra("android.intent.extra.KEY_CONFIRM", false);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    XposedBridge.log("BatteryRemapper: Executed native Intent shutdown.");
                    return;
                } catch (Throwable t1) {
                    XposedBridge.log("BatteryRemapper: Intent shutdown failed. Trying shell... " + t1.getMessage());
                }

                try {
                    Runtime.getRuntime().exec("cmd power shutdown");
                    XposedBridge.log("BatteryRemapper: Executed shell forced shutdown.");
                } catch (Throwable t2) {
                    XposedBridge.log("BatteryRemapper: Shell shutdown failed - " + t2.getMessage());
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("BatteryRemapper Master Shutdown Failure: " + t.getMessage());
            isShuttingDown = false;
        }
    }

    private int remapBattery(int physicalLevel) {
        if (physicalLevel <= 20) return 0;
        if (physicalLevel >= 80) return 100;
        return Math.round((float)(physicalLevel - 20) * 100f / 60f);
    }
}
