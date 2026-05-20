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
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    compileOnly("org.api.rovo89:xposed:82")
}
