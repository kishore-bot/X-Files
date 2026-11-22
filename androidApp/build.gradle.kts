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
    // dev: day-to-day builds, installable next to prod. prod: what users get.
    flavorDimensions += "env"
    productFlavors {
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        create("prod") {
            dimension = "env"
        }
    }
    buildTypes {
        release {
            // AGP 9.3+ app optimization: R8 code shrinking, obfuscation and optimization, optimized resource
            // shrinking and Android's default keep rules. Our rules: src/main/keepRules/*.keep.
            optimization {
                enable = true
            }
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
    sourceSets.getByName("benchmark").baselineProfiles.directories.add("src/prodRelease/generated/baselineProfiles")
    // dev never carries Firebase; prod does only in the opt-in firebase build.
    sourceSets.getByName("dev").kotlin.directories.add("src/offline/kotlin")
    sourceSets.getByName("prod").kotlin.directories.add(if (firebaseReports) "src/firebase/kotlin" else "src/offline/kotlin")
    // The overlay turns off Firebase's default collection in every prod variant, so none collects before consent.
    if (firebaseReports) sourceSets.configureEach {
        if (name in setOf("prodDebug", "prodRelease", "prodBenchmark")) manifest.srcFile("src/firebase/AndroidManifest.xml")
    }

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// dev is debug-only; release (minified) and benchmark builds exist for prod alone.
androidComponents {
    beforeVariants { variant ->
        if (variant.productFlavors.any { it.second == "dev" } && variant.buildType != "debug") variant.enable = false
    }
}

dependencies {
    if (firebaseReports) {
        "prodImplementation"(platform(libs.firebase.bom))
        "prodImplementation"(libs.firebase.crashlytics)
        // Feedback reports (src/firebase FirestoreFeedbackSender). Absent from builds without Firebase config.
        "prodImplementation"(libs.firebase.firestore)
        // Already shipped by this Crashlytics version; exposed for ConsentedCrashBackendFactory.
        "prodImplementation"("com.google.android.datatransport:transport-runtime:5.0.0")
        "prodImplementation"("com.google.android.datatransport:transport-backend-cct:5.0.0")
    }
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // AppFunctions service for Android 16+ assistants; the KSP compiler generates the service and its XML schema.
    implementation(libs.appfunctions.runtime)
    ksp(libs.appfunctions.compiler)
    implementation(project(":core:designsystem"))
    implementation(project(":core:documents"))
    implementation(project(":documentEngine"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:tools"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:assistant"))
    implementation(project(":feature:tidy"))
    implementation(libs.hilt.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    // System splash (Android 12+ native, compat below) with the animated mark in ic_splash_animated.xml.
    implementation(libs.core.splashscreen)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.serialization.json)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    // Newer WorkManager than Glance's, for R8 full-mode safe keep rules (Room database constructor).
    implementation(libs.work.runtime)
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
// google-services.json knows only com.xfiles, so dev variants skip the Firebase tasks.
tasks.configureEach {
    if (name.startsWith("uploadCrashlytics")) enabled = false
    if (firebaseReports && name.contains("Dev") && (name.endsWith("GoogleServices") || name.contains("Crashlytics"))) enabled = false
}
