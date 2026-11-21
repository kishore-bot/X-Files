# Xfiles Android-first KMP plan

## Product principle

**Performance is part of what makes Xfiles feel premium.** The original About → Why Xfiles page opens with “Free doesn't mean cheap.” Its commitments to high-quality free software, privacy, and accessibility guide this rebuild. Preserve the author's `VisionText`, sign-off, styling, and back behavior when porting About. The reference copy is in EzFiles `shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/about/AboutContent.kt` and the layout is in `AboutScreens.kt`.

- Keep startup, search, file opening, and page turns responsive while preserving the original typography, glass surfaces, motion, haptics, and dark mode. Defer parsing, indexing, and assistant warm-up until the library or first page is ready unless the current action needs that work. Use bounded cache and worker budgets to keep frame time and memory predictable.
- Evaluate premium effects against the same screen with its original reduced-motion or lower-cost rendering path. Preserve the design and use the less expensive implementation when the visual and interaction checks agree.
- Compare release-like builds on the same Android device and fixtures. Record cold startup time to initial and full display, p50/p95 frame timing for library scrolling and reader page turns, first-page time, and peak process memory. Establish a baseline across repeated runs and investigate regressions outside the baseline's observed variation before accepting the slice. Use EzFiles' `baselineprofile/src/main/kotlin/com/ezfiles/baselineprofile/StartupBenchmark.kt` and `ReaderBenchmark.kt` to recover the original journeys and fixtures.
- Keep local file handling and assistant permission controls as product requirements. Fily remains voice-only, with model throughput, first-token latency, memory and quality measured separately from reader performance.

## Current Android architecture

```text
:androidApp [Android application, route state, dependency wiring]
  → :feature:library [LibraryViewModel, folders, recents, About]
  → :feature:reader [DocumentAnalysisViewModel, Android Jetpack Compose, Media3]
  → :feature:tools [PdfToolsViewModel, system document pickers]
  → :feature:editor [TextEditorViewModel, new notes, save a copy]
  → :core:designsystem [EzFiles palettes, typography, glass and icon vectors]

:feature:* → :core:documents [Android URI grants, folder traversal, previews and PDF export]
:core:documents → :sharedLogic [KMP common document types and PDF page selection]
:core:documents → :documentEngine [KMP with Android Kotlin adapters]
:documentEngine → OxiKT / UniFFI 0.32 → engine/crates/document-ffi → Rust crates
```

The Android library uses persisted Storage Access Framework grants. It scans the selected trees off the main thread, deduplicates provider document IDs, and visits up to 6,000 entries per refresh. Recent files, bookmarks, and linked folders persist locally. The reader can resume a bookmarked PDF page. The reader searches bounded text, supports zoom and rotation for images and PDF pages, and pulls a plain-text preview for other supported documents through Rust. The editor opens bounded plain text and saves a UTF-8 copy through the system picker. Cached vector paths and gradients draw the full-window glass treatment without a separately allocated screen bitmap. At a glance runs on demand through `document-glance`. Local audio and video use Media3 1.11.1. PDF tools stage inputs privately, validate output through `document-ffi`, and save a new copy to the destination chosen in the system picker. [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files), [PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer), [Media3 1.11.1](https://developer.android.com/jetpack/androidx/releases/media3#1.11.1)

## Build and release checks

Sync the Xfiles sources into the dedicated WSL mirror at `~/xfiles-build`. Omit generated build directories and preserve the mirror's `local.properties`. Use JDK `~/jdk17`, SDK `~/Android/Sdk`, Rust 1.100.0 and Android NDK r29. OxiKT 1.4.0 requires `releaseBuild=true` for optimized Rust on Android and builds the armv7 target as well. The app packages ARM64 and x86-64.

```bash
export JAVA_HOME=~/jdk17 ANDROID_HOME=~/Android/Sdk CARGO_BUILD_JOBS=1
cargo test --manifest-path engine/Cargo.toml --locked --workspace --jobs 1
bash ./gradlew --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process \
  :sharedLogic:testAndroidHostTest :androidApp:assembleDebug
bash ./gradlew --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process \
  -PreleaseBuild=true :androidApp:assembleRelease :androidApp:bundleRelease
```

Check the release APK using `zipalign -c -P 16 4` and inspect ELF LOAD segments and RELRO with the NDK's `llvm-readelf`. Verify the reader and Rust tools again on a release-like APK. Store upload signing separately from the source checkout. [Android 16 KB verification](https://developer.android.com/guide/practices/page-sizes)
## Reference code study

The original EzFiles checkout supplies behavior and test references for the Android-first implementation in Xfiles.

| Reference in EzFiles | Design lesson |
|---|---|
| `settings.gradle.kts`, `shared/build.gradle.kts` | The old shared module combined presentation, platform adapters, and build integration. Use a small Kotlin logic module and put Android presentation in the app. |
| `shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/ReaderViewModel.kt`, `presentation/library/LibraryViewModel.kt` under the same reader root | Preserve explicit reader and library state, including loading, errors, retry, and incremental content. |
| `shared/src/androidMain/kotlin/com/ezfiles/reader/data/AndroidDocumentAccess.kt`, `shared/src/commonMain/kotlin/com/ezfiles/reader/data/RustDocumentReader.kt` | Preserve content URI permissions, bounded reads, typed errors and deterministic descriptor/session cleanup. Choose the reader per format. |
| `engine/crates/document-ffi`, `shared/src/androidMain/cpp`, `shared/src/commonMain/kotlin/com/ezfiles/reader/application/assistant/FilySession.kt` | Document parsing and Fily inference are independent native boundaries. Preserve the existing format fixtures and voice behavior when implementing those features. |

## 2026 stack and EzFiles deprecation audit

| Component | Xfiles decision |
|---|---|
| Build and UI | Kotlin 2.4.21, AGP 9.5.2, Android compile SDK 37.2, target SDK 37, AndroidX Activity 1.13.0, and Android Jetpack Compose BOM 2026.11.01. Gradle 9.9.2 is newer than Kotlin's fully tested Gradle range. The Xfiles Android build and shared host tests run in the WSL mirror with this combination. [Kotlin releases](https://kotlinlang.org/docs/releases.html), [Kotlin compatibility](https://kotlinlang.org/docs/gradle-configure-project.html), [AGP releases](https://developer.android.com/reference/tools/gradle-api), [Gradle 9.9.2](https://docs.gradle.org/9.9.2/release-notes.html), [Activity releases](https://developer.android.com/jetpack/androidx/releases/activity), [Compose BOM](https://developer.android.com/develop/ui/compose/bom/bom-mapping?from=2026.09.00&to=2026.11.01) |
| Rust and Android native builds | Start the native slice with Rust 1.100.0, Edition 2024, and NDK r29 (`29.0.14206865`, pinned in `libs.versions.toml`; move to r30 together with that pin). Generate bindings through OxiKT 1.4.0 and its required UniFFI 0.32.0 pairing. Version and test the generated bindings, Rust artifact, and runtime as one unit. Keep Android native dependencies out of `sharedLogic`. [Rust 1.100.0](https://blog.rust-lang.org/2026/11/12/Rust-1.100.0/), [NDK releases](https://developer.android.com/ndk/downloads/), [OxiKT requirements](https://github.com/UbiqueInnovation/oxikt) |
| Android native artifacts | Verify arm64-v8a and x86_64, 16 KB ELF and APK alignment, and actual loading on a 16 KB Android device or emulator. An updated NDK alone cannot establish third-party artifact compatibility. [Android page-size guide](https://developer.android.com/guide/practices/page-sizes) |

| EzFiles reference | Legacy code to replace |
|---|---|
| `shared/build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml` | The combination of `androidTarget()`, `com.android.library`, Gobley 0.3.7, and the three AGP opt-outs depends on legacy Kotlin/Android and source-set APIs. Xfiles starts with Android-KMP, built-in Kotlin, and the new DSL. [Android-KMP plugin](https://developer.android.com/kotlin/multiplatform/plugin), [AGP migration roadmap](https://developer.android.com/build/releases/gradle-plugin-roadmap) |
| `androidApp/build.gradle.kts`, `baselineprofile/build.gradle.kts`, `ffmpeg/build.gradle.kts` | Replace the standalone Kotlin Android plugin, task graph listener, and Provider-backed generated JNI folders in legacy source sets. Use variant sources and declared task dependencies when implementing native packaging and benchmarks. [Built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin), [Configuration-cache requirements](https://docs.gradle.org/current/userguide/configuration_cache_requirements.html) |
| `shared/src/androidMain/cpp/llama-bridge.cpp` | The pinned backend marks `llama_batch_init`, `llama_batch_free`, and `llama_decode` deprecated. Prototype the Rust Fily adapter before adding a C++ bridge. If a bridge is needed with a pin offering the extended batch API, use the replacement API and recheck token positions, logits, and cache behavior. [llama.cpp API](https://github.com/ggml-org/llama.cpp/blob/master/include/llama.h) |
| `shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/PdfReader.kt` and other clipboard consumers | Use Android Jetpack Compose's `LocalClipboard` when implementing copy actions. [Compose clipboard API](https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/package-summary) |
| `shared/src/androidMain/kotlin/com/ezfiles/reader/presentation/viewers/HtmlViewerPage.android.kt` | Preserve restricted rendering. Replace the legacy file-URL settings with an explicit content-loading policy that avoids `file://` access for previews. [Android WebSettings](https://developer.android.com/reference/android/webkit/WebSettings) |
| `shared/src/commonMain/kotlin/com/ezfiles/reader/presentation/viewers/ImageViewer.kt`, `shared/src/androidMain/kotlin/com/ezfiles/reader/platform/CrashReports.android.kt` | When implementing these features, use the centroid-aware transform API and Android's `longVersionCode`. [Compose gesture API](https://developer.android.com/reference/kotlin/androidx/compose/foundation/gestures/rememberTransformableState.composable), [PackageInfo API](https://developer.android.com/reference/android/content/pm/PackageInfo.html) |
| `ffmpeg/build.gradle.kts`, `gradle/libs.versions.toml` | Media3 1.9.4 predates the 1.11.1 stable line. Update Media3 and any vendored FFmpeg extension together at the media phase. Verify fallback and decoder behavior separately. [Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3), [Media3 FFmpeg extension](https://github.com/androidx/media/tree/1.11.1/libraries/decoder_ffmpeg) |

## High-level design

```text
Android app
  Jetpack Compose / Android ViewModels
      -> sharedLogic (Kotlin Multiplatform)
         use cases, document models, policies, algorithms, repository ports
      -> Android adapters
         SAF, PDF rendering, persistence, external app handoff, Media3
         Rust document engine and optional Fily inference behind ports

Future iOS app
  SwiftUI -> SharedLogic.framework -> the same Kotlin use cases
        -> Apple file, rendering, and storage adapters
```

Use Android Jetpack Compose only in `:androidApp`. Use the Kotlin Multiplatform and Android-KMP library plugins in `:sharedLogic` with an Android target, keeping cross-platform Kotlin in `commonMain`. Add Apple targets, framework export and the SwiftUI app when iOS work starts. The first delivery validates the Android target and shared Kotlin tests. [KMP project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html), [Android-KMP library plugin](https://developer.android.com/kotlin/multiplatform/plugin)

The Android application assembles the feature modules and controls routes. The Android data module owns URI grants, persistence and document operations. The KMP modules hold reusable rules and the Kotlin boundary to the Rust engine. Android composables receive immutable ViewModel state and dispatch actions back to the ViewModel. [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)

## Low-level design

| Area | Kotlin implementation and data structures |
|---|---|
| Documents | Define `DocumentId`, `DocumentRef`, `DocumentKind` and typed `OpenError` in shared logic. A `DocumentSource` port opens a document using its opaque identifier. Android uses the Storage Access Framework, persists grants where offered and handles revocation. Prefer MIME and filename hints, with bounded signature detection when they disagree. [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files) |
| Reader | `OpenDocument` chooses a reader from a map keyed by format. Define a bounded `ReaderSession` open/read/close lifecycle. The Android ViewModel cancels work on replacement and closes the old session, then publishes results only for the active request. Read text and document blocks in batches and handle page errors without discarding prior content. |
| Library and recents | Use an ID-keyed map for deduplication and ordering by access time plus ID as a deterministic tie-break. Use a database query and index for persisted ordering and pagination if storage size warrants it. Keep the initial search a normalized linear scan, O(n) per query. Add an inverted index only when measured search latency calls for it. |
| Cache and concurrency | Use a byte-budgeted LRU for decoded pages when needed, with a hash map and doubly linked list for O(1) lookup and eviction. Coalesce concurrent loads for the same key. Cancel obsolete loads and bound reads, queued renders and archive expansion before allocating. Test eviction, oversized entries and cancellation. |
| Rust engine | Put format signatures, structured parsers, archive limits, indexing and optional bulk transformations in Rust. Define a Cargo workspace with a pure `document-core` and a thin `document-ffi` crate. Export typed errors and owned sessions that fetch bounded block batches. Keep the Kotlin reader port and SAF ownership in the existing layers. Keep Android-native PDF rendering and media behind Android adapters. |
| Fily | Prefer a Rust worker for model lifetime, grammar, prefix state and CPU fallback. Evaluate a Rust wrapper around llama.cpp for the unchanged GGUF and Adreno OpenCL path. Allow bundled upstream C++ if it provides the required backend. Add handwritten C++ only where profiling or missing APIs demonstrate a need. Benchmark both ABIs and compare output and memory before shipping. [llama.cpp Android OpenCL](https://github.com/ggml-org/llama.cpp/blob/master/docs/build.md#opencl) |

## Android UI parity and modular MVVM

The original EzFiles Android app is the visual and interaction reference. Use its current branch and the same fixtures for comparison. Retain the Xfiles app name and application ID. Port the existing visual assets and drawing code into Android Jetpack Compose. Record the original device settings for each capture.

| Screen or surface | EzFiles source of truth | Initial parity checks |
|---|---|---|
| App window and startup | `androidApp/src/main/kotlin/com/ezfiles/android/MainActivity.kt`, its splash drawables and theme resources | Splash art, light/dark system bars, edge-to-edge insets, gesture and three-button navigation, cold-start file handoff. |
| Design system | `reader/presentation/theme/EzTheme.kt`, `Surfaces.kt`, `EzIcons.kt`, `EzMotion.kt`, `EzHaptics.kt`, and `reader/presentation/components/EzChrome.kt` under the old common source root | Preserve palettes, dimensions, typography, vectors, glass gradients and shadows, reader backgrounds, press and selection animation, reduced motion, and haptics. |
| Library | `reader/presentation/library/LibraryScreen.kt`, `LibraryViewModel.kt`, and `FileType.kt` | Match the header, search field, folder and file-filter rows, empty states, recents, bookmark entry points, open-file action, and the Tools sheet trigger. |
| Reader | `reader/presentation/ReaderScreen.kt`, `ReaderViewModel.kt`, and `reader/presentation/viewers/` | Match reader chrome, loading/error/password states, document search dismissal order, and PDF, text and image behavior. Reference the dedicated Office, ebook, archive, HTML, and media viewers when those formats ship. |
| Tools and settings | `reader/presentation/create/CreateTools.kt`, `pdftools/`, `tidy/`, `about/AboutScreens.kt`, and app routes in `app/App.kt` | Match sheet layout and screen transitions, settings controls, and the original tool selection, review, and export flows. |
| Voice | `reader/presentation/assistant/VoiceAssistantSheet.kt` and voice routes in `app/App.kt` | Match mic visibility, permission and model-install states, sheet visuals, cancellation, and action review. Keep voice input and use the existing consent rules. |

### HLD

```text
:androidApp -> :feature:library, :feature:reader, :core:designsystem
:feature:* -> :core:designsystem, :sharedLogic
:androidApp -> :core:documents -> :sharedLogic
:core:documents -> :documentEngine (Rust bridge when format support needs it)
:feature:assistant -> :documentEngine (Fily inference)
```

The app owns the Activity, root navigation, intent handoff, and dependency construction. Each feature owns its Android screen, ViewModel, and UI-state mapping. The design system owns Android Compose primitives and visual resources shared by features. The data module owns the Android document store, SAF, and platform adapters. It implements repository interfaces from `:sharedLogic`, which retains Kotlin models, reusable use cases, and platform-neutral logic for future iOS. Introduce Gradle modules with their first real code. Add `:feature:tools` and `:feature:assistant` when those flows are implemented. [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations), [Modularization patterns](https://developer.android.com/topic/modularization/patterns)

### LLD

- Give each screen a route composable that collects its ViewModel's immutable `StateFlow<UiState>` with `collectAsStateWithLifecycle()`. Render through a state-and-callback composable that can run with fixture data. Send user intents to the ViewModel. Use repositories for persisted recents, permission state, and content. Keep ephemeral UI state such as focus and motion local to Compose.
- Use stable Navigation 3 **1.2.2** for typed destinations. The app owns the back stack and registers feature routes. Keep stable document IDs and minimal restoration inputs in route keys and saved state. Own reader sessions and cancel pending work within the reader destination lifecycle. Keep transient sheets and in-reader search state scoped to their screen. Reproduce the original back behavior and transitions, including the reduced-motion fallback. [Navigation 3 releases](https://developer.android.com/jetpack/androidx/releases/navigation3), [Modular navigation](https://developer.android.com/guide/navigation/navigation-3/modularize), [Navigation 3 state and ViewModels](https://developer.android.com/guide/navigation/navigation-3/save-state)
- Keep the original file-list ordering and type filters. Use stable document keys for rows, repository-owned recents, and the bounded reader and cache rules in the core LLD. Keep the Android document picker and runtime-permission launchers in the UI layer. Pass only validated results into the feature ViewModels.
- Verify the original and Xfiles on the same Android image, viewport, density, locale, system bars, font scale, theme, and fixture data. Mask nondeterministic system UI such as the clock and compare app-owned pixels, layout bounds, and semantics. Review rasterization differences individually. Cover light/dark library states, search, Tools sheet, reader text/PDF/image, errors, and back navigation first. Use device screenshots as the parity baseline and Compose UI tests for taps and semantics. Host preview screenshot testing is optional while its tooling remains experimental. [Compose screenshot testing](https://developer.android.com/studio/preview/compose-screenshot-testing)

### November 2026 Compose API choices

The scaffold already uses Android Jetpack Compose BOM **2026.11.01**, core Compose **1.13.0**, and Material 3 **1.5.0**. Preserve EzFiles styling through its own theme and components. Adopt new implementation APIs where the appearance and behavior can be checked against the reference. [November 2026 Compose release](https://developer.android.com/blog/posts/jetpack-compose-november-2026-release), [Material 3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3)

| November API | Xfiles use |
|---|---|
| State-based `BasicTextField`, `TextFieldState`, `InputTransformation`, and `OutputTransformation` | Use the state-based API for the library and reader search, editor, and find/replace. Keep the existing decoration and outside dimensions. Verify cursor position, selection, IME action, formatting, and restore behavior. Use the secure field API for password entry and check the original reveal behavior. |
| Stable Material 3 component APIs including `BasicAlertDialog` and stateful `Slider` | Use when the original dialog chrome, sheet, or scrubber can be expressed with the same tokens and behavior. Compare screenshots, touch targets, and accessibility actions. |
| Progressive blur, polygon and morph shapes, and dialog blur controls | Evaluate only when they fit an existing effect. EzFiles glass surfaces are custom gradients, borders and shadows, so carry these into the first port. |
| `computedStateOf` and vector drawing cache improvements | Reuse the original static vector instances. Evaluate `computedStateOf` for cheap scroll or chrome visibility thresholds. Keep sorting and search in the state and data layers. |

## Android delivery phases

| Phase | Implementation and done when | Rollback |
|---|---|---|
| 1. Build and identity | `settings.gradle.kts`, `gradle/libs.versions.toml`, `androidApp/build.gradle.kts`, and `sharedLogic/`. Verify WSL debug and optimized release builds, the `com.xfiles` application ID, `Xfiles` label, and both native ABI alignments. | Rebuild the last verified Xfiles source snapshot and retain its Android application ID. |
| 2. Library and visual system | `core/designsystem/`, `core/documents/DocumentRepository.kt`, `FolderRepository.kt`, and `feature/library/`. Check folder grants across restart, bookmark reopen, permission revocation, phone and tablet layouts, and light and dark screenshots against EzFiles. Match existing behavior before accepting visual changes. | Restore the previous cached background or library screen. Keep stored URI grants and the preference keys readable. |
| 3. Readers, tools, and editing | `feature/reader/`, `feature/tools/`, `feature/editor/`, and `documentEngine/src/androidMain/`. Validate plain text, PDF pages, images, local media, PDF merge and extraction, and saving a text copy. Port rich Office, slides, spreadsheets, e-books, printing, PDF annotations, remaining tools, and full-text library search using the EzFiles fixtures. | Restore the prior reader or tool route for the affected format. Existing files remain accessible through the SAF picker. |
| 4. Voice-only Fily | Port the behavior from EzFiles `FilySession.kt`, `ToolExecutor.kt`, `Grounding.kt`, `FilyGrammar.kt`, and `ModelDownloads.kt`. Preserve the model asset name, URL, expected hash, 4,096-token context and prefix state. Add the Rust runtime only after compiling and checking LFM2 on Android OpenCL. | Keep the assistant feature out of the candidate if its native build, performance, or tool behavior fails acceptance. Preserve downloaded model files. |
| 5. iOS | Add Apple targets after the Android flows pass. Create a SwiftUI host and implement Apple file access and media adapters. Build device and simulator artifacts on macOS. | Restore the Android-only settings and continue shipping the independent Android build. |

## Fily acceptance

Use the same GGUF, prompt, sampling settings, grammar, app fixture set, and test device for both builds. Record at least 20 warm runs and 10 cold runs. Report median and p95 time to first generated token in milliseconds, tokens per second after the first token, and peak process PSS in MiB. Record model load time separately. Test the same requests on the Adreno GPU and CPU fallback. Record whether the model state was reused.

Run the same held-out suite from EzFiles `training/eval_harness.py` against both implementations with an NVIDIA backend. Compare the completed counts, intent/tool accuracy, unsupported-action handling, and grounding scores. Keep failed runs in the report. Accept the Rust runtime when the held-out score is no lower and the measured Android performance is within the baseline's observed variation. Preserve the current voice workflow until those checks pass. [llama-cpp-2 OpenCL features](https://docs.rs/crate/llama-cpp-2/0.1.161/features), [llama-cpp-2 sequence state](https://docs.rs/llama-cpp-2/0.1.161/llama_cpp_2/context/struct.LlamaContext.html)

Keep builds in the dedicated Xfiles WSL mirror. No commits or pushes are required.
