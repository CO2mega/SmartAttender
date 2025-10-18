plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.example.greetingcard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.greetingcard"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        // Enable ML Model Binding to generate typed model bindings for tflite files
        // Place your model files under src/main/ml/ for the binding to pick them up.
        mlModelBinding = true
    }
    // If you're using view binding or data binding, ensure they are enabled here
    // viewBinding {
    //     enable = true
    // }
    // dataBinding {
    //     enable = true
    // }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.material3) // Note: You have both androidx.compose.material3 and androidx.material3. You might only need the Compose one.
    // Android Material Components library (required for Theme.MaterialComponents)
    implementation("com.google.android.material:material:1.9.0")

    // CameraX dependencies (from version catalog)
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.0")

    // ML Kit Face Detection (from version catalog)
    implementation("com.google.mlkit:face-detection:16.1.5")

    // TensorFlow Lite for embeddings
    implementation("org.tensorflow:tensorflow-lite:2.11.0")

    // Optional: GPU delegate (uncomment if you add GPU delegate usage)
    // implementation("org.tensorflow:tensorflow-lite-gpu:2.11.0")

    // Room dependencies (use kapt for annotation processing)
    implementation(libs.androidx.room.runtime)
    kapt(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    // Accompanist Permissions (from version catalog)
    implementation(libs.accompanist.permissions)

    // Biometric (Fingerprint) authentication (from version catalog)
    implementation(libs.androidx.biometric)

    // Lifecycle runtime Compose (for LocalLifecycleOwner moved to lifecycle-runtime-compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}
