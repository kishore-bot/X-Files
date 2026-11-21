plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.baselineprofile)
}

// Opt-in Firebase Crashlytics. Off unless -Pxfiles.firebaseCrashReports=true (needs androidApp/google-services.json).
// The default build has no Firebase code, no google-services plugin and no network permission beyond the app's own.
val firebaseReports = providers.gradleProperty("xfiles.firebaseCrashReports").map { it.toBoolean() }.getOrElse(false)
if (firebaseReports) {
    check(file("google-services.json").isFile) { "Firebase diagnostics requires androidApp/google-services.json" }
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    namespace = "com.xfiles.android"
    sourceSets.getByName("main").kotlin.srcDir(if (firebaseReports) "src/firebase/kotlin" else "src/offline/kotlin")
    // The overlay turns off Firebase's default collection in every build type, so none collects before consent.
    if (firebaseReports) sourceSets.configureEach {
        if (name in setOf("debug", "release", "benchmark")) manifest.srcFile("src/firebase/AndroidManifest.xml")
    }
    compileSdk {
        version = release(libs.versions.compile.sdk.get().toInt()) {
            minorApiLevel = libs.versions.sdk.minor.api.get().toInt()
        }
    }
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        applicationId = "com.xfiles"
        minSdk = libs.versions.min.sdk.get().toInt()
        ndk.abiFilters += setOf("arm64-v8a", "x86_64")
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release code with the debug key, for measuring startup and frame times locally.
        // Never distribute this variant.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    // Custom build types must explicitly consume the release profiles.
    sourceSets.getByName("benchmark").baselineProfiles.srcDir("src/release/generated/baselineProfiles")

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    if (firebaseReports) {
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.crashlytics)
        // Already shipped by this Crashlytics version; exposed for ConsentedCrashBackendFactory.
        implementation("com.google.android.datatransport:transport-runtime:5.0.0")
        implementation("com.google.android.datatransport:transport-backend-cct:5.0.0")
    }
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(project(":core:designsystem"))
    implementation(project(":core:documents"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:tools"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:assistant"))
    implementation(project(":feature:tidy"))
    implementation(libs.hilt.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.serialization.json)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    // Installs the Baseline Profile on sideloaded APKs too; Play does it for bundles.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
}

baselineProfile {
    // Generated on demand (see baselineprofile/build.gradle.kts) and committed,
    // so a normal release build never needs a device.
    automaticGenerationDuringBuild = false
    dexLayoutOptimization = true
}

// Local mapping and symbol files only; uploading them to Firebase is a separate release step.
tasks.configureEach {
    if (name.startsWith("uploadCrashlytics")) enabled = false
}
