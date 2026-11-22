# Contributing to Xfiles

Thanks for helping. Xfiles has one motto: **premium feel, faster app.** Every change is judged on three things: **performance**, **current technology** and **clean architecture**. Please read this page before opening a pull request.

## Ground rules

- **Offline first.** No document, text or file name ever leaves the device. A change that adds network access needs a clear reason and an opt-in.
- **Fast on a mid-range phone.** Measure before and after. A slower path needs a reason in the PR.
- **Follow the architecture below.** Reviews check it before they check style.
- **Small, focused pull requests**, with tests for the logic they change.

## Architecture

```
androidApp            App shell: activity, navigation, splash, DI wiring
feature/*             Screens: library, reader, tools, editor, tidy, assistant
core/designsystem     Theme, glass surfaces, components, haptics, motion
core/documents        Android-side repositories (files, PDF render, archives)
sharedLogic           Kotlin Multiplatform logic: models, Fily, search, settings
documentEngine        Kotlin bindings to the Rust engine (UniFFI)
engine/crates         Rust: parsers, PDF tools, search, inference
training              Fily model training (Python; large outputs are gitignored)
```

**UI layer (MVVM, unidirectional data flow)**

- Each screen has an immutable `UiState` data class, a sealed `UiEvent` and a `@HiltViewModel` that exposes `StateFlow<UiState>` and a single `onEvent(event)`. Use a pure reducer where you can.
- Composables are **stateless**: they take state and lambdas. Hoist state to the screen's root or to the ViewModel. `rememberSaveable` is only for pure UI state.
- Keep files focused, around 300 lines or fewer. Split by responsibility: screen, bars, sheets, rows.

**Domain layer**

- Put non-UI logic in use cases, in a `domain` package next to the feature:
  ```kotlin
  class SaveToDownloadsUseCase @Inject constructor(...) {
      suspend operator fun invoke(...)
  }
  ```
- Use cases are plain and testable. They don't depend on Compose.

**Data and engine**

- Heavy work (parsing, PDF, search, archives, inference) belongs in the **Rust engine**. Call it through `documentEngine`, never on the main thread.
- Rust code is bounded: cap input sizes and recursion, give typed errors, and never panic across FFI.

## Performance rules

- **Main thread:** only UI. Use coroutines with the right dispatcher: `IO` for files, `Default` for CPU, and the engine's own lanes.
- **Compose**
  - Use stable and immutable state.
  - Use `remember`/`derivedStateOf` for derived values, and give lazy lists keys.
  - Avoid recomposition storms: read fast-changing state in `graphicsLayer` or `drawBehind`.
  - Don't blur scrolling list items.
- **Memory:** decode bitmaps at the size shown, and bound caches by count and bytes. Release players, decoders and file descriptors in `DisposableEffect`/`onDispose`.
- **Startup:** do nothing in `Application.onCreate` that can wait. Keep the splash's data gate short.
- **Measure:** use the baseline profile (`baselineprofile`), Perfetto traces and the phone benchmark (`scripts/`). Include numbers in the PR for anything on a hot path.

## Technology

- **Kotlin:** Jetpack Compose with Material 3 (follow current Material Design guidance), Hilt, coroutines and Flow, Media3, Navigation, and Kotlin Multiplatform for `sharedLogic`.
- **Rust:** stable (`rust-toolchain.toml`), with UniFFI for bindings.
- Use the versions in `gradle/libs.versions.toml` and `engine/Cargo.toml`. Upgrade them in their own PR. Don't pin old APIs when a current one exists.
- **Haptics:** use `com.xfiles.design.rememberHaptics()`. Don't call platform haptics directly.

## Build and test

Requirements: JDK 17, Android SDK (compileSdk from `libs.versions.toml`), NDK, and Rust with the Android targets.

```bash
./gradlew :sharedLogic:testAndroidHostTest :androidApp:assembleDevDebug   # app and Kotlin tests
cargo test --manifest-path engine/Cargo.toml --workspace                  # Rust engine tests
```

Before you open a PR, make sure:

- everything builds;
- all tests pass;
- you've added tests for new logic: Kotlin in `commonTest`/`androidHostTest`, Rust next to the module;
- you've checked UI changes on a real device in light and dark, at 1.0× and 1.3× font scale.

## Fily (the assistant)

- Fily's prompt and tool catalog must match the trained model. `FilyPromptContractTest` enforces this.
- Don't change `FilyPrompt` POLICY, the tool JSON or APP_STATE fields unless you also retrain. See `training/README.md`.
- New screen commands go through `QuickCommands` and `ReaderControl`.

## Commits and pull requests

- Use one topic per commit. Write messages in the imperative, with a short subject and a body that explains why.
- Fill in the PR template.
- Link the issue it fixes.
- Add before/after screenshots for UI changes and numbers for performance work.
- Never commit secrets, signing keys, `google-services.json`, `local.properties` or model weights. `.gitignore` covers these, so keep it that way.

## Licence

By contributing, you agree that your contributions are licensed under the Apache License 2.0 (see `LICENSE`).
