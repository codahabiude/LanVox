plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.wifip2pchat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.lanvox"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "5.0"
    }

    // Use a persistent debug keystore when building locally (so successive
    // local builds sign identically and can update each other without
    // uninstalling). On CI (GitHub Actions) the env var is absent, so the
    // default ephemeral debug keystore is used — unchanged behaviour there.
    signingConfigs {
        getByName("debug") {
            val ks = System.getenv("LANVOX_DEBUG_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = "lanvoxdebug"
                keyAlias = "lanvox"
                keyPassword = "lanvoxdebug"
            }
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // Material Design 3
    implementation("com.google.android.material:material:1.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
