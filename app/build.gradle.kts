plugins {
    id("com.android.application")
}

android {
    namespace = "com.github.dhangofa.batteryremapper"
    compileSdk = 34

   defaultConfig {
        applicationId = "com.github.dhangofa.batteryremapper"
        minSdk = 29
        targetSdk = 34
        
        // Dynamic versioning using run_number from CI
        val runNumber = project.findProperty("versionCode")?.toString()?.toInt() ?: 1
        
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
