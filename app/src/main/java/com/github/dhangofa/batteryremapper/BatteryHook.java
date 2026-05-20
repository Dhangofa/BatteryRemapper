package com.github.dhangofa.batteryremapper;

import android.content.Intent;
import android.os.BatteryManager;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class BatteryHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        // We only want to trick the System UI into drawing the new number.
        // We leave the rest of the OS alone so charging logic doesn't break.
        if (!lpparam.packageName.equals("com.android.systemui")) {
            return;
        }

        try {
            // Hook the exact moment SystemUI reads an integer from ANY Intent
            XposedHelpers.findAndHookMethod(Intent.class, "getIntExtra", String.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    
                    // If SystemUI is asking for the Battery Level...
                    if (BatteryManager.EXTRA_LEVEL.equals(key)) {
                        // Get the real, physical battery level the system just read
                        int originalLevel = (Integer) param.getResult();
                        
                        // Run our math and overwrite the result being returned to crDroid
                        int displayedLevel = remapBattery(originalLevel);
                        param.setResult(displayedLevel);
                    }
                }
            });
            
            XposedBridge.log("BatteryRemapper: Successfully hooked Intent.getIntExtra for crDroid!");

        } catch (Throwable t) {
            XposedBridge.log("BatteryRemapper Hook Critical Failure: " + t.getMessage());
        }
    }

    private int remapBattery(int physicalLevel) {
        if (physicalLevel <= 20) return 0;
        if (physicalLevel >= 80) return 100;
        return Math.round((float)(physicalLevel - 20) * 100f / 60f);
    }
}
