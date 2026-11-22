pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Xfiles"
include(":androidApp", ":sharedLogic", ":documentEngine", ":core:designsystem", ":core:documents", ":feature:library", ":feature:reader", ":feature:tools", ":feature:editor", ":feature:assistant", ":feature:tidy")
include(":baselineprofile")
// FFmpeg audio decoders for the video player (LGPL libraries built by scripts/build-ffmpeg.sh).
include(":ffmpeg")
