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
    
    // Smart State Trackers for the Battery Saver
    private static boolean isSaverTargetOn = false; 
    private static int appliedSaverState = -1;      

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        
        // ------------------------------------------------------------------
        // HOOK 1: SYSTEM SERVER (android)
        // ------------------------------------------------------------------
        if (lpparam.packageName.equals("android")) {
            try {
                // Hook the BatteryService class directly
                Class<?> batteryServiceClass = XposedHelpers.findClass("com.android.server.BatteryService", lpparam.classLoader);
                
                XposedBridge.hookAllMethods(batteryServiceClass, "processValuesLocked", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        
                        // THE FIX: Grab the internal hardware data object directly, bypassing param.args entirely
                        Object mHealthInfo = XposedHelpers.getObjectField(param.thisObject, "mHealthInfo");
                        if (mHealthInfo == null) return;

                        // Read the physical hardware data using boolean/int getters
                        int originalLevel = XposedHelpers.getIntField(mHealthInfo, "batteryLevel");
                        boolean acOnline = XposedHelpers.getBooleanField(mHealthInfo, "chargerAcOnline");
                        boolean usbOnline = XposedHelpers.getBooleanField(mHealthInfo, "chargerUsbOnline");
                        
                        int plugged = (acOnline || usbOnline) ? 1 : 0;
                        int remappedLevel = remapBattery(originalLevel);
                        
                        // DEBUG LOG
                        XposedBridge.log("BatteryRemapper-Debug: BatteryService processed. Physical:" + originalLevel + " Remapped:" + remappedLevel);

                        // Overwrite the level so the whole system sees the remapped UI percentage
                        XposedHelpers.setIntField(mHealthInfo, "batteryLevel", remappedLevel);

                        // FEATURE: TRIGGER SAVER
                        if (remappedLevel <= 20) {
                            isSaverTargetOn = true;
                        } else if (remappedLevel >= 51) {
                            isSaverTargetOn = false;
                        }

                        if (plugged == 0) {
                            if (isSaverTargetOn && appliedSaverState != 1) {
                                enableBatterySaver();
                                appliedSaverState = 1;
                            } else if (!isSaverTargetOn && appliedSaverState != 0) {
                                disableBatterySaver();
                                appliedSaverState = 0;
                            }
                        } else {
                            appliedSaverState = -1;
                        }
                    }
                });
                
                XposedBridge.log("BatteryRemapper: Android 16 BatteryService hooked successfully.");
            } catch (Throwable t) {
                XposedBridge.log("BatteryRemapper Core Hook Failure: " + t.getMessage());
            }
        }

        // ------------------------------------------------------------------
        // HOOK 2: SYSTEM UI (com.android.systemui)
        // ------------------------------------------------------------------
        if (lpparam.packageName.equals("com.android.systemui")) {
            try {
                XposedHelpers.findAndHookMethod(Intent.class, "getIntExtra", String.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String key = (String) param.args[0];
                        
                        if (BatteryManager.EXTRA_LEVEL.equals(key)) {
                            int originalLevel = (Integer) param.getResult();
                            
                            Intent intent = (Intent) param.thisObject;
                            Bundle extras = intent.getExtras();
                            
                            int plugged = extras != null ? extras.getInt(BatteryManager.EXTRA_PLUGGED, 0) : 0;
                            int displayedLevel = remapBattery(originalLevel);
                            
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
                            
                            param.setResult(displayedLevel);
                        }
                    }
                });
                
                XposedBridge.log("BatteryRemapper: System UI hooked with 30s Countdown Feature!");
                
            } catch (Throwable t) {
                XposedBridge.log("BatteryRemapper UI Hook Critical Failure: " + t.getMessage());
            }
        }
    }

    // ======================================================================
    // HELPER METHODS
    // ======================================================================

    private void enableBatterySaver() {
        try {
            // Kernel override via su
            Process p = Runtime.getRuntime().exec(new String[]{
                "/system/bin/su", "-c", 
                "settings put global low_power 1 && cmd power set-mode 1"
            });
            p.waitFor();
            XposedBridge.log("BatteryRemapper: Kernel-level Power Saver forced ON.");
        } catch (Throwable t) {
            XposedBridge.log("BatteryRemapper: Kernel-level override failed: " + t.getMessage());
        }
    }

    private void disableBatterySaver() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                "/system/bin/su", "-c", 
                "settings put global low_power 0 && cmd power set-mode 0"
            });
            p.waitFor();
            XposedBridge.log("BatteryRemapper: Kernel-level Power Saver forced OFF.");
        } catch (Throwable t) {
            XposedBridge.log("BatteryRemapper: Kernel-level override failed: " + t.getMessage());
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
