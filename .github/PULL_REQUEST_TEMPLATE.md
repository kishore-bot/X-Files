## What and why

<!-- What does this change, and why? Link the issue: "Fixes #123". -->

## How it was tested

- [ ] `./gradlew :sharedLogic:testAndroidHostTest :androidApp:assembleDevDebug` passes
- [ ] `cargo test --manifest-path engine/Cargo.toml --workspace` passes (if the engine changed)
- [ ] New or changed logic has tests
- [ ] Checked on a real device: light and dark, 1.0× and 1.3× font scale (UI changes)

## Architecture and performance

- [ ] UI follows MVVM/UDF: immutable `UiState`, sealed `UiEvent`, stateless composables, logic in use cases
- [ ] No blocking work on the main thread. Heavy work is in the Rust engine or on the right dispatcher
- [ ] Hot paths measured (numbers below), or not applicable

<!-- Screenshots (before / after) and measurements, if relevant. -->

## Fily

- [ ] Doesn't change `FilyPrompt` POLICY, tool JSON or APP_STATE (or retraining is included)
