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
        // HOOK 1: SYSTEM SERVER - Read-Only Monitor + Action Trigger
        // ------------------------------------------------------------------
        if (lpparam.packageName.equals("android")) {
            try {
                Class<?> batteryServiceClass = XposedHelpers.findClass("com.android.server.BatteryService", lpparam.classLoader);
                
                XposedBridge.hookAllMethods(batteryServiceClass, "processValuesLocked", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        
                        Object mHealthInfo = XposedHelpers.getObjectField(param.thisObject, "mHealthInfo");
                        if (mHealthInfo == null) return;

                        // READ ONLY - We do not overwrite this!
                        int physicalLevel = XposedHelpers.getIntField(mHealthInfo, "batteryLevel");
                        
                        boolean acOnline = XposedHelpers.getBooleanField(mHealthInfo, "chargerAcOnline");
                        boolean usbOnline = XposedHelpers.getBooleanField(mHealthInfo, "chargerUsbOnline");
                        int plugged = (acOnline || usbOnline) ? 1 : 0;
                        
                        // Calculate what the UI is showing right now
                        int virtualLevel = remapBattery(physicalLevel);

                        if (virtualLevel <= 20) {
                            isSaverTargetOn = true;
                        } else if (virtualLevel >= 51) {
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
                
                XposedBridge.log("BatteryRemapper: System Server Monitor hooked (Read-Only).");
            } catch (Throwable t) {
                XposedBridge.log("BatteryRemapper Core Hook Failure: " + t.getMessage());
            }
        }

        // ------------------------------------------------------------------
        // HOOK 2: SYSTEM UI - Visual Spoofing + Shutdown Timer
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
                            
                            // Here is where we actually spoof the visuals
                            int displayedLevel = remapBattery(originalLevel);
                            
                            if (originalLevel <= 20) { // Physical 20% limit
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
                
                XposedBridge.log("BatteryRemapper: System UI hooked for Visuals & Shutdown Timer.");
                
            } catch (Throwable t) {
                XposedBridge.log("BatteryRemapper UI Hook Critical Failure: " + t.getMessage());
            }
        }
    }

    // ======================================================================
    // HELPER METHODS (CLEAN API NO ROOT SHELL)
    // ======================================================================

    private void enableBatterySaver() {
        try {
            // Opens a raw root stream that works on Magisk, KernelSU, and any custom ROM
            Process p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            
            // Send the commands directly to the kernel
            os.writeBytes("settings put global low_power 1\n");
            os.writeBytes("cmd power set-mode 1\n");
            os.writeBytes("exit\n");
            os.flush();
            p.waitFor();
            
            XposedBridge.log("BatteryRemapper: Battery Saver toggled ON via Root Shell.");
        } catch (Throwable t) {
            XposedBridge.log("BatteryRemapper: Root Shell Toggle Failed: " + t.getMessage());
        }
    }

    private void disableBatterySaver() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            
            os.writeBytes("settings put global low_power 0\n");
            os.writeBytes("cmd power set-mode 0\n");
            os.writeBytes("exit\n");
            os.flush();
            p.waitFor();
            
            XposedBridge.log("BatteryRemapper: Battery Saver toggled OFF via Root Shell.");
        } catch (Throwable t) {
            XposedBridge.log("BatteryRemapper: Root Shell Toggle Failed: " + t.getMessage());
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
