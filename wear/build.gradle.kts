plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ro.pub.cs.system.eim.aplicatie_hack.wear"
    compileSdk = 36

    defaultConfig {
        // Același applicationId ca telefonul — necesar pentru Wearable Data Layer pairing
        applicationId = "ro.pub.cs.system.eim.aplicatie_hack"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    // Wear OS Compose — UI rotundă, optimizată pentru ceas
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)

    // Wearable Data Layer
    implementation(libs.play.services.wearable)

    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
}