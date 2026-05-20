package com.github.dhangofa.batteryremapper;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class BatteryHook implements IXposedHookLoadPackage {
    private static final String TAG = "BatteryRemapper_Dhangofa";
    private static final String TARGET_PACKAGE = "com.android.systemui";
    private static final String TARGET_CLASS = "com.android.systemui.statusbar.policy.BatteryControllerImpl";

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(
                TARGET_CLASS,
                lpparam.classLoader,
                "fireBatteryLevelChanged",
                int.class,     
                boolean.class, 
                boolean.class, 
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args == null || param.args.length == 0) {
                            return;
                        }

                        int physicalLevel = (int) param.args[0];
                        int uiLevel;

                        if (physicalLevel <= 20) {
                            uiLevel = 0;
                        } else if (physicalLevel >= 80) {
                            uiLevel = 100;
                        } else {
                            uiLevel = ((physicalLevel - 20) * 100) / 60;
                        }

                        param.args[0] = uiLevel;
                        
                        Log.i(TAG, "Intercepted SystemUI update -> Physical: " + physicalLevel + "% | Scaled UI: " + uiLevel + "%");
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("BatteryRemapper Hook Failure: " + t.getMessage());
        }
    }
}
