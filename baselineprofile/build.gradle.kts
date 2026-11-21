// Generates the app's Baseline Profile and measures startup and reader frames with Macrobenchmark.
//
//   ANDROID_SERIAL=<serial> ./gradlew :androidApp:generateReleaseBaselineProfile
//
// writes androidApp/src/release/generated/baselineProfiles/baseline-prof.txt,
// which is committed and compiled into release builds (bundle, APKs). Always
// set ANDROID_SERIAL: without it the tests run on every connected device.
// On Windows use gradlew.bat. Requires a device or emulator on API 33+ (profile
// collection without root). Run again after large UI changes.
//
// Startup and reader benchmarks (emulator numbers only compare the two modes):
//
//   ANDROID_SERIAL=<serial> ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
//
// Startup journeys are in Journeys.kt; a missing screen fails collection on purpose.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.xfiles.baselineprofile"
    compileSdk {
        version = release(libs.versions.compile.sdk.get().toInt()) {
            minorApiLevel = libs.versions.sdk.minor.api.get().toInt()
        }
    }

    defaultConfig {
        // Profile collection without root needs Android 13 (API 33) or newer on the device.
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":androidApp"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

baselineProfile {
    // The connected emulator, never a managed device that would download a system image.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
