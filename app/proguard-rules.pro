# The module entry point is named in assets/xposed_init and instantiated by the framework through
# reflection, so it must keep both its name and its members.
-keep class com.github.dhangofa.batteryremapper.BatteryHook { *; }

# The legacy Xposed API is supplied by the framework at runtime and is therefore not on R8's
# classpath; AndroidAppHelper is injected the same way.
-dontwarn de.robv.android.xposed.**
-dontwarn android.app.AndroidAppHelper

# The launcher activity, the settings provider and the broadcast receivers are declared in the
# manifest, and AGP keeps manifest-declared components automatically, so no rules are needed for
# them here.
