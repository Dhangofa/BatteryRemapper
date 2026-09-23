plugins {
    id("com.android.application")
}

android {
    namespace = "com.github.dhangofa.batteryremapper"
    compileSdk = 36

   defaultConfig {
        applicationId = "com.github.dhangofa.batteryremapper"
        minSdk = 29
        targetSdk = 37
        versionCode = 59
        versionName = "1.1.0"
    }
    // Suggested by IzzyOnDroid
    dependenciesInfo {
        // Disables dependency metadata when building APKs (for IzzyOnDroid/F-Droid)
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles (for Google Play)
        includeInBundle = false
    }

    buildTypes {
        release {
            // Shrinking and obfuscation are on, so app/proguard-rules.pro is actually used. The
            // entry class named in assets/xposed_init is kept by the rule in that file.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
