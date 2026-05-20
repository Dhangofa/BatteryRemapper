package com.github.dhangofa.batteryremapper;

import java.lang.reflect.Method;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class BatteryHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.android.systemui")) {
            return;
        }

        try {
            Class<?> batteryControllerClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.policy.BatteryControllerImpl", 
                lpparam.classLoader
            );

            // Dynamically search for the method by name to avoid signature mismatches
            Method targetMethod = null;
            for (Method method : batteryControllerClass.getDeclaredMethods()) {
                if (method.getName().equals("fireBatteryLevelChanged")) {
                    targetMethod = method;
                    break;
                }
            }

            if (targetMethod != null) {
                XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // The first parameter (index 0) is always the battery level integer
                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
                            int physicalLevel = (Integer) param.args[0];
                            
                            // Apply your mathematical remapping logic here
                            // Example: converting a physical 20%-80% range into a displayed 0%-100%
                            int displayedLevel = remapBattery(physicalLevel);
                            
                            param.args[0] = displayedLevel;
                        }
                    }
                });
                XposedBridge.log("BatteryRemapper: Successfully hooked fireBatteryLevelChanged dynamically!");
            } else {
                XposedBridge.log("BatteryRemapper Error: fireBatteryLevelChanged method not found.");
            }

        } catch (Throwable t) {
            XposedBridge.log("BatteryRemapper Hook Critical Failure: " + t.getMessage());
        }
    }

    private int remapBattery(int physicalLevel) {
        if (physicalLevel <= 20) return 0;
        if (physicalLevel >= 80) return 100;
        // Remap the intermediate 20-80 range linearly to 0-100
        return Math.round((float)(physicalLevel - 20) * 100f / 60f);
    }
}
