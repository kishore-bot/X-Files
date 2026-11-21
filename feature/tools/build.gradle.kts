plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.xfiles.feature.tools"
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
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.viewmodel.compose)
    implementation(project(":core:designsystem"))
    implementation(project(":core:documents"))
    implementation(project(":documentEngine"))
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.androidx.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.exifinterface)
}
