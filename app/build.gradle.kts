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
        
        // Dynamic versioning using run_number from CI
        val runNumber = project.findProperty("versionCode")?.toString()?.toInt() ?: 1
        
        versionCode = runNumber
        versionName = "1.0.$runNumber"
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
            isMinifyEnabled = false
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
