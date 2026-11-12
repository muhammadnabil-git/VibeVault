plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")   // reads app/google-services.json (Firebase)
}

android {
    namespace = "com.example.ytsaver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ytsaver"
        minSdk = 29
        targetSdk = 35
        versionCode = 101
        versionName = "7.7.2"
        ndk { abiFilters += listOf("arm64-v8a") } // add "armeabi-v7a" for old 32-bit phones
    }

    // Signing: NO passwords live in the source. The same production key must keep being used so that updates install over
    // existing installs. Supply it either as environment variables (CI) or as Gradle properties (~/.gradle/gradle.properties):
    //   VV_KEYSTORE_FILE (path), VV_KEYSTORE_PASSWORD, VV_KEY_ALIAS, VV_KEY_PASSWORD
    // See RELEASE.md. Without them, release builds are produced UNSIGNED (never with a made-up key) and debug builds use
    // the standard Android debug key.
    fun cred(name: String): String? = System.getenv(name) ?: (project.findProperty(name) as String?)
    val releaseStore = cred("VV_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.isFile }
    val releaseReady = releaseStore != null && cred("VV_KEYSTORE_PASSWORD") != null &&
        cred("VV_KEY_ALIAS") != null && cred("VV_KEY_PASSWORD") != null
    signingConfigs {
        if (releaseReady) {
            create("release") {
                storeFile = releaseStore
                storePassword = cred("VV_KEYSTORE_PASSWORD")
                keyAlias = cred("VV_KEY_ALIAS")
                keyPassword = cred("VV_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (releaseReady) signingConfig = signingConfigs.getByName("release")
            else logger.warn("VibeVault: release signing credentials not provided -> the release APK will be UNSIGNED (see RELEASE.md).")
        }
    }

    buildFeatures { compose = true }

    // JVM unit tests run against stubbed Android classes: calls into them return defaults instead of throwing
    testOptions { unitTests.isReturnDefaultValues = true }

    // Required: yt-dlp/ffmpeg binaries ship as native libs and must be extracted
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")

    // in-app browser: lets us present a normal browser identity so sign-in pages accept it
    implementation("androidx.webkit:webkit:1.12.1")

    // Firebase: Google sign-in (Auth) + playlist / settings sync (Firestore)
    implementation(platform("com.google.firebase:firebase-bom:33.9.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // yt-dlp + ffmpeg engine (this is what makes real, non-empty files)
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    testImplementation("junit:junit:4.13.2")
}
