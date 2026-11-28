// Media3's FFmpeg audio extension (Apache-2.0 sources from androidx/media), linked to FFmpeg's audio
// decoders built as separate LGPL shared libraries by scripts/build-ffmpeg.sh. The player uses these only
// for formats the phone can't decode itself (E-AC3, AC3, DTS, TrueHD...). The JNI bridge is Rust (rust/).
// Without a built FFmpeg for an ABI the module still builds: no bridge is built or shipped for that ABI and
// the extension reports itself unavailable there, so the phone's decoders are used.
plugins {
    alias(libs.plugins.android.library)
}

val ffmpegDir: File = rootProject.layout.projectDirectory.dir(".cache/ffmpeg-7.1.2").asFile
val abis = listOf("arm64-v8a", "x86_64")
val rustTargets = mapOf("arm64-v8a" to "aarch64-linux-android", "x86_64" to "x86_64-linux-android")

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk {
        version = release(libs.versions.compile.sdk.get().toInt()) {
            minorApiLevel = libs.versions.sdk.minor.api.get().toInt()
        }
    }
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
        ndk.abiFilters += abis
        consumerProguardFiles("consumer-rules.pro")
    }
    // FFmpeg's own .so files and the Rust bridge, packaged next to each other.
    sourceSets.getByName("main").jniLibs.directories.add(layout.buildDirectory.dir("ffmpeg-libs").get().asFile.path)
    sourceSets.getByName("main").jniLibs.directories.add(layout.buildDirectory.dir("rust-libs").get().asFile.path)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val copyFfmpegLibs = tasks.register<Copy>("copyFfmpegLibs") {
    for (abi in abis) {
        from(File(ffmpegDir, "$abi/lib")) {
            include("libavutil.so", "libswresample.so", "libavcodec.so")
            into(abi)
        }
    }
    into(layout.buildDirectory.dir("ffmpeg-libs"))
}

// One cargo build per ABI whose FFmpeg is present, producing libffmpegJNI.so. The crate is standalone (not in the
// engine workspace) so that host builds never need FFmpeg.
val rustCrateDir: File = layout.projectDirectory.dir("rust").asFile
val ndkDir: File = androidComponents.sdkComponents.ndkDirectory.get().asFile
val buildFfmpegJni = tasks.register("buildFfmpegJni") {
    description = "Builds the Rust FFmpeg JNI bridge for every ABI that has FFmpeg."
    group = "build"
}
for (abi in abis) {
    if (!File(ffmpegDir, "$abi/lib/libavcodec.so").exists()) continue
    val triple = rustTargets.getValue(abi)
    val linker = File(
        File(ndkDir, "toolchains/llvm/prebuilt").listFiles()!!.first(),
        "bin/$triple${libs.versions.min.sdk.get()}-clang",
    )
    val built = tasks.register<Exec>("buildFfmpegJni_${abi.replace('-', '_')}") {
        workingDir(rustCrateDir)
        commandLine("cargo", "build", "--release", "--locked", "--lib", "--target", triple)
        environment("FFMPEG_DIR", ffmpegDir.path)
        environment("FFMPEG_ANDROID_API", libs.versions.min.sdk.get())
        environment("ANDROID_NDK_HOME", ndkDir.path)
        environment("CARGO_TARGET_${triple.uppercase().replace('-', '_')}_LINKER", linker.path)
        doLast {
            copy {
                from(File(rustCrateDir, "target/$triple/release/libffmpegJNI.so"))
                into(layout.buildDirectory.dir("rust-libs/$abi"))
            }
        }
    }
    buildFfmpegJni.configure { dependsOn(built) }
}

tasks.matching { it.name == "preBuild" || (it.name.startsWith("merge") && it.name.endsWith("JniLibFolders")) }
    .configureEach {
        dependsOn(copyFfmpegLibs)
        dependsOn(buildFfmpegJni)
    }

dependencies {
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.decoder)
    compileOnly("androidx.annotation:annotation:1.11.0")
    compileOnly("org.checkerframework:checker-qual:4.4.0")
}
