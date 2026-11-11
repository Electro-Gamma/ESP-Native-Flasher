plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.espflasher.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.espflasher.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables.useSupportLibrary = true

        // Launcher label used by AndroidManifest (${appLabel}); the debug build overrides it below.
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    // Release signing. The release build is only signed when ESPFLASHER_KEYSTORE points at a real keystore file
    // (set the four ESPFLASHER_* environment variables - see README, "Build from source"). Without it nothing changes: assembleRelease
    // produces an UNSIGNED apk, which Android refuses to install, and assembleDebug is untouched (debug key).
    // Store and key password are expected to be the same value (a PKCS12 keystore, keytool's default, ignores a
    // different key password).
    val releaseKeystore: String? = System.getenv("ESPFLASHER_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).isFile }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ESPFLASHER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ESPFLASHER_KEY_ALIAS")
                keyPassword = System.getenv("ESPFLASHER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            // Distinguish the side-by-side debug install from the release app in the launcher.
            manifestPlaceholders["appLabel"] = "ESP Flasher (debug)"
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Vendored Arduino Hex Upload support (io.espflasher.app.arduinohex) -
    // brings in kr.co.makeitall.arduino's own protocol code (STK500v1/v2,
    // AVR109, USBasp, ArduinoISP) and its felHR85-based serial stack
    // unchanged, rather than re-deriving any of it. arduino-hex-upload's
    // own build.gradle depends on :IntelHexFormatReader too, but as
    // `implementation`, not `api` - that hides it from anything that only
    // depends on :arduino-hex-upload, so ArduinoHexUploadActivity (which
    // calls IntelHexFormatReader.HexFileReader directly, the same way the
    // reference app's own MainActivity does) needs this module declared
    // here too, exactly like the reference app's own app/build.gradle does.
    implementation(project(":arduino-hex-upload"))
    implementation(project(":IntelHexFormatReader"))

    // Core AndroidX / Kotlin
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // ArduinoHexUploadActivity references CoordinatorLayout.LayoutParams
    // directly (for the upload FAB's inset handling) - declared explicitly
    // rather than assumed transitive through material, the same kind of
    // assumption that broke the :IntelHexFormatReader dependency above.
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")

    // Lifecycle / ViewModel / StateFlow support
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // DataStore for settings persistence
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // RecyclerView for the console log
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
