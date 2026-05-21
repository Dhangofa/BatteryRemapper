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

    // Keep track of state so we don't spawn 100 dialogs at once
    private static boolean isShuttingDown = false;
    private static AlertDialog shutdownDialog = null;
    private static CountDownTimer shutdownTimer = null;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.android.systemui")) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(Intent.class, "getIntExtra", String.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    
                    if (BatteryManager.EXTRA_LEVEL.equals(key)) {
                        int originalLevel = (Integer) param.getResult();
                        
                        Intent intent = (Intent) param.thisObject;
                        Bundle extras = intent.getExtras();
                        
                        // Check if phone is plugged in (0 means on battery)
                        int plugged = extras != null ? extras.getInt(BatteryManager.EXTRA_PLUGGED, 0) : 0;
                        
                        // 1. SHUTDOWN LOGIC
                        if (originalLevel <= 20) {
                            if (plugged == 0) {
                                // Hit 20% and not charging: Start the countdown once
                                if (!isShuttingDown) {
                                    isShuttingDown = true;
                                    startCountdown();
                                }
                            } else {
                                // Hit 20% but charger is connected: Abort shutdown
                                if (isShuttingDown) {
                                    cancelCountdown();
                                }
                            }
                        } else {
                            // Battery is safely above 20%: Abort shutdown if running
                            if (isShuttingDown) {
                                cancelCountdown();
                            }
                        }
                        
                        // 2. UI LOGIC: Overwrite the result for the status bar
                        int displayedLevel = remapBattery(originalLevel);
                        param.setResult(displayedLevel);
                    }
                }
            });
            
            XposedBridge.log("BatteryRemapper: Hook initialized with 30s Countdown Feature!");

        } catch (Throwable t) {
            XposedBridge.log("BatteryRemapper Hook Critical Failure: " + t.getMessage());
        }
    }

    private void startCountdown() {
        // UI elements MUST run on the main Android UI thread
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context context = AndroidAppHelper.currentApplication();
                    if (context == null) return;

                    // Build the Warning Box
                    AlertDialog.Builder builder = new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert);
                    builder.setTitle("Battery Depleted");
                    builder.setMessage("Device will shut down in 30 seconds.\nPlug in charger to cancel.");
                    builder.setCancelable(false); // Prevents dismissing by tapping outside
                    
                    shutdownDialog = builder.create();
                    // TYPE_SYSTEM_ERROR (2010) bypasses all screens to draw directly over everything
                    shutdownDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
                    shutdownDialog.show();

                    // Start the 30 second timer (30000ms total, updating every 1000ms)
                    shutdownTimer = new CountDownTimer(30000, 1000) {
                        @Override
                        public void onTick(long millisUntilFinished) {
                            if (shutdownDialog != null && shutdownDialog.isShowing()) {
                                shutdownDialog.setMessage("Device will shut down in " + (millisUntilFinished / 1000) + " seconds.\nPlug in charger to cancel.");
                            }
                        }

                        @Override
                        public void onFinish() {
                            cancelCountdown(); // Cleanup the UI
                            triggerShutdown(); // Kill the power
                        }
                    }.start();
                    
                    XposedBridge.log("BatteryRemapper: 30-second shutdown countdown started.");
                } catch (Throwable t) {
                    XposedBridge.log("BatteryRemapper UI Failure: " + t.getMessage());
                    // Failsafe: If the custom ROM blocks the dialog from drawing, shut down gracefully anyway
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
                XposedBridge.log("BatteryRemapper: Shutdown cancelled! Charger detected.");
            }
        });
    }

    private void triggerShutdown() {
        try {
            Context context = AndroidAppHelper.currentApplication();
            if (context != null) {
                Object powerManager = context.getSystemService(Context.POWER_SERVICE);
                if (powerManager != null) {
                    // Call the hidden OS-level power off command
                    XposedHelpers.callMethod(powerManager, "shutdown", false, "battery_remapper_empty", false);
                }
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
