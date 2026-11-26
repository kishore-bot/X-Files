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
        exclusiveContent {
            forRepository {
                maven {
                    name = "LazyMatrix"
                    val local = providers.gradleProperty("lazyMatrixLocalRepo").orNull
                        ?.let { file(it) } ?: file("../../CustomCompose/LazyMatrix/lazymatrix/build/maven")
                    val remote = providers.gradleProperty("lazyMatrixMavenUrl").orNull
                    if (remote == null && local.resolve("dev/lazymatrix/lazymatrix/0.1.1/lazymatrix-0.1.1.aar").isFile) {
                        url = local.toURI()
                    } else {
                        url = uri(remote ?: "https://maven.pkg.github.com/kishore-bot/lazymatrix")
                        credentials {
                            username = providers.environmentVariable("LAZYMATRIX_MAVEN_USER").orNull
                            password = providers.environmentVariable("LAZYMATRIX_MAVEN_TOKEN").orNull
                        }
                    }
                }
            }
            filter { includeModule("dev.lazymatrix", "lazymatrix") }
        }
    }
}

rootProject.name = "Xfiles"
include(":androidApp", ":sharedLogic", ":documentEngine", ":core:designsystem", ":core:documents", ":feature:library", ":feature:reader", ":feature:tools", ":feature:editor", ":feature:assistant", ":feature:tidy")
include(":baselineprofile")
// FFmpeg audio decoders for the video player (LGPL libraries built by scripts/build-ffmpeg.sh).
include(":ffmpeg")
// Optional local-only modules; the app builds the same without this file.
file("private.settings.gradle.kts").takeIf { it.exists() }?.let { apply(from = it) }
