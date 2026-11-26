plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.xfiles.feature.reader"
    compileSdk {
        version = release(libs.versions.compile.sdk.get().toInt()) {
            minorApiLevel = libs.versions.sdk.minor.api.get().toInt()
        }
    }
    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("dev.lazymatrix:lazymatrix:0.1.1")
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.viewmodel.compose)
    implementation(project(":core:designsystem"))
    implementation(project(":core:documents"))
    implementation(project(":documentEngine"))
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.media3.exoplayer)
    // Audio formats the phone can't decode (AC3, E-AC3, DTS, TrueHD) go through FFmpeg.
    runtimeOnly(project(":ffmpeg"))
    implementation(libs.media3.ui.compose)
    implementation(libs.androidx.core)
}
