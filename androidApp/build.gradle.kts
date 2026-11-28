plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.baselineprofile)
}

// A configured prod build sends reports to Firestore. The property can override local configuration.
val firebaseReports = providers.gradleProperty("xfiles.firebaseCrashReports")
    .map { it.toBooleanStrict() }
    .getOrElse(file("google-services.json").isFile)

/**
 * The app version, SemVer with an optional pre-release label: 1.0.0-alpha.03, 1.0.0-beta.01, 1.0.0-rc.02, 1.0.0.
 * gradle.properties holds the current one; the release workflow passes the tag's (-Pxfiles.version=…).
 */
val appVersion: String = providers.gradleProperty("xfiles.version").getOrElse("1.0.0-beta.01")

/**
 * A monotonically increasing versionCode from [appVersion]: MAJOR·1,000,000 + MINOR·10,000 + PATCH·100 + stage,
 * where the stage is alpha.N → N, beta.N → 30+N, rc.N → 60+N and the final release → 99 (N up to 29).
 * So 1.0.0-beta.01 = 1000031 < 1.0.0-rc.01 = 1000061 < 1.0.0 = 1000099 < 1.0.1-alpha.01 = 1000101.
 */
val appVersionCode: Int = run {
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?$""").matchEntire(appVersion)
        ?: error("xfiles.version must look like 1.2.3 or 1.2.3-beta.01, not '$appVersion'")
    val (major, minor, patch, stage, number) = match.destructured
    val n = number.toIntOrNull() ?: 0
    require(stage.isEmpty() || n in 1..29) { "pre-release number must be 1..29 in '$appVersion'" }
    require(minor.toInt() < 100 && patch.toInt() < 100) { "minor and patch must be below 100 in '$appVersion'" }
    val step = when (stage) {
        "alpha" -> n
        "beta" -> 30 + n
        "rc" -> 60 + n
        else -> 99
    }
    major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + step
}
if (firebaseReports) {
    check(file("google-services.json").isFile) { "Firebase diagnostics requires androidApp/google-services.json" }
    apply(plugin = "com.google.gms.google-services")
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
        versionCode = appVersionCode
        versionName = appVersion
    }
    // Release signing comes from the environment (CI secrets or a local shell); without it, release stays unsigned.
    val keystore = System.getenv("XFILES_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).exists() }
    if (keystore != null) {
        signingConfigs.create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("XFILES_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("XFILES_KEY_ALIAS")
            keyPassword = System.getenv("XFILES_KEY_PASSWORD")
        }
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
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
    // Dev is offline. Prod includes Firestore when configured.
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
        "prodImplementation"(libs.firebase.firestore)
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
    // Use the same static brand mark for the platform and compat splash.
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

// The Firebase configuration registers only the prod application ID.
tasks.configureEach {
    if (firebaseReports && name.contains("Dev") && name.endsWith("GoogleServices")) enabled = false
}

// Optional local-only dependencies; the app builds the same without this file.
rootProject.file("private.app.gradle.kts").takeIf { it.exists() }?.let { apply(from = it) }
