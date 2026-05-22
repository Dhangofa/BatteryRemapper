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
import android.os.PowerManager;
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
    
    // Track battery saver to prevent spamming the toggle
    private static boolean batterySaverTriggered = false;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        
        // ------------------------------------------------------------------
        // HOOK 1: SYSTEM SERVER (android)
        // This tricks the core OS to force real hardware shutdowns and enable battery saver
        // ------------------------------------------------------------------
		if (lpparam.packageName.equals("android")) {
			try {
				Class<?> amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", lpparam.classLoader);
				
				// hookAllMethods survives Android 16 signature changes
				XposedBridge.hookAllMethods(amsClass, "broadcastIntent", new XC_MethodHook() {
					@Override
					protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
						// FAST EXIT: Prevents CPU drain. If no args, skip immediately.
						if (param.args == null || param.args.length < 2) return;
		
						Intent intent = null;
						
						// On Android 16, the Intent is almost always exactly at index 1.
						// We check this first for maximum performance.
						if (param.args[1] instanceof Intent) {
							intent = (Intent) param.args[1];
						} else {
							// Fallback: search the arguments just in case a specific custom ROM shifted it
							for (Object arg : param.args) {
								if (arg instanceof Intent) {
									intent = (Intent) arg;
									break;
								}
							}
						}
		
						// If we found an intent, and it's the Battery broadcast, intercept it
						if (intent != null && Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
							int originalLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
							
							if (originalLevel != -1) {
								int remappedLevel = remapBattery(originalLevel);
								
								// Overwrite the intent so the native Android 16 OS sees the new UI level
								intent.putExtra(BatteryManager.EXTRA_LEVEL, remappedLevel);
		
								// FEATURE: AUTO BATTERY SAVER AT 20% UI
								if (remappedLevel <= 20) {
									int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
									if (plugged == 0 && !batterySaverTriggered) {
										enableBatterySaver(param.thisObject);
										batterySaverTriggered = true;
									} else if (plugged != 0) {
										batterySaverTriggered = false; 
									}
								} else {
									batterySaverTriggered = false;
								}
							}
						}
					}
				});
				XposedBridge.log("BatteryRemapper: Android 16 System Server hooked successfully.");
			} catch (Throwable t) {
				XposedBridge.log("BatteryRemapper Core Hook Failure: " + t.getMessage());
			}
		}
        // ------------------------------------------------------------------
        // HOOK 2: SYSTEM UI (com.android.systemui)
        // This handles your visual 30-second countdown popup
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
                            
                            // SHUTDOWN UI LOGIC (Triggers at physical 20% / UI 0%)
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
                            
                            // Return the remapped value to the status bar UI
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

    private void enableBatterySaver(Object activityManagerService) {
        try {
            // Get the system context from the ActivityManagerService
            Context context = (Context) XposedHelpers.getObjectField(activityManagerService, "mContext");
            if (context != null) {
                PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (powerManager != null && !powerManager.isPowerSaveMode()) {
                    // Force the Battery Saver ON using standard PowerManager API reflection
                    XposedHelpers.callMethod(powerManager, "setPowerSaveModeEnabled", true);
                    XposedBridge.log("BatteryRemapper: Battery Saver automatically enabled at 20% UI limit.");
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("BatteryRemapper: Failed to enable Battery Saver - " + t.getMessage());
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
                    
                    XposedBridge.log("BatteryRemapper: 30-second shutdown countdown started.");
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
