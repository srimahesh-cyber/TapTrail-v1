plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.taptrail.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.taptrail.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-hackathon"
        
        // Backend URL configurable via local.properties or build config
        buildConfigField("String", "BACKEND_URL", "\"http://10.0.2.2:8000\"")
        buildConfigField("String", "AUTH_TOKEN", "\"dev_token_change_me\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    
    // OkHttp for backend communication
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSON parsing
    implementation("org.json:json:20231013")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
