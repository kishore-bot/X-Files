import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.oxikt)
}

kotlin {
    android {
        namespace = "com.xfiles.documentengine"
        compileSdk {
            version = release(libs.versions.compile.sdk.get().toInt()) {
                minorApiLevel = libs.versions.sdk.minor.api.get().toInt()
            }
        }
        minSdk = libs.versions.min.sdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    sourceSets {
        androidMain.dependencies {
            implementation(libs.coroutines.core)
        }
        commonMain.dependencies {
            api(project(":sharedLogic"))
        }
    }
}

cargo {
    packageDirectory = layout.settingsDirectory.dir("engine/crates/document-ffi")
    ndkVersion = libs.versions.ndk.get()
    androidDebugAbis.addAll("arm64-v8a", "x86_64")
    // Fily on the Adreno GPU through llama.cpp's OpenCL backend: -Pfily.opencl=true. Off by default because a
    // static OpenCL build links libdocument_ffi.so to libOpenCL.so, and Pixels and emulators don't expose it to
    // apps, so the whole engine would fail to load. Shipping it needs ggml's dynamically loaded backend
    // (GGML_BACKEND_DL) and OpenCL headers plus the ICD loader in the NDK sysroot (see docs/PLAN.md, Fily).
    if (providers.gradleProperty("fily.opencl").orNull == "true") features.add("fily-opencl")
}

uniffi {
    generateFromLibrary {
        packageName = "com.xfiles.engine.ffi"
    }
}
