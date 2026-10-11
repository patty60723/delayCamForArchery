plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.archery.delaycam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.archery.delaycam"
        // 29+ only: lets ClipExporter use the scoped-storage MediaStore API
        // unconditionally (no legacy WRITE_EXTERNAL_STORAGE + FileProvider
        // path to also write and test). Android 10 (API 29) shipped in 2019,
        // so this costs nothing on any reasonably current test device.
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-skeleton"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".delayfix"
            versionNameSuffix = "-delayfix"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // --- Core / Kotlin ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- Compose ---
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.1")

    // --- CameraX (spec 5.1: capture pipeline) ---
    // No camera-video: segment recording is a hand-rolled MediaCodec/
    // MediaMuxer pipeline (ContinuousSegmentEncoder) fed by a plain Preview
    // use case, not CameraX's Recorder - see that class's doc comment.
    val cameraxVersion = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // --- MediaPipe Tasks Vision (spec 5.1/5.3: gesture recognition) ---
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // --- Media3 / ExoPlayer (delayed playback + slow-mo playback speed) ---
    // Only media3-ui is used (PlayerView via AndroidView in Compose screens,
    // not the separate Compose-native media3-ui-compose components).
    val media3Version = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    // --- Settings persistence (spec 4) ---
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
