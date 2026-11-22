# Repository inventory and coverage ledger

**22 November 2026 · clean snapshot `b79503e80aa85df65674f2365eb6d69f3bccee00`**

630 public-tree files were inventoried by path, byte size and SHA-256 prefix. The screening pass read every line of 613 text files, totaling 119,664 lines. It detected concurrency, ownership, trust-boundary and cleanup signals, including lines in comments and tests. These signals direct manual review. Each path has a coverage status.

- **T (86)** — Targeted manual line review and mechanical screening. Full semantic review of adjacent paths can still be pending.
- **S (527)** — All lines screened mechanically. Manual line-by-line review is outstanding.
- **I (17)** — Asset, fixture or binary inventoried by path and checksum. Binary behavior needs an appropriate reader or test.

Excluded paths include `.git/`, Gradle and Kotlin caches, IDE state, build and Cargo target outputs, FFmpeg cache, downloaded model artifacts, and generated evaluation datasets. The worktree’s ignored `private.settings.gradle.kts` and `private.app.gradle.kts` are outside the public build graph. Local SDK properties, signing keys, Firebase configuration and service credentials were excluded. Admission is based on source-tree visibility and ignore rules. Verification of existing package artifacts is pending.

| Group | Files | Text lines |
|---|---:|---:|
| (root) | 15 | 1110 |
| .github | 5 | 216 |
| androidApp | 62 | 3745 |
| baselineprofile | 7 | 372 |
| core/designsystem | 15 | 2447 |
| core/documents | 28 | 4643 |
| docs | 2 | 269 |
| documentEngine | 14 | 1399 |
| engine | 3 | 1970 |
| engine/crates/document-core | 9 | 1168 |
| engine/crates/document-ffi | 17 | 3777 |
| engine/crates/document-glance | 8 | 1882 |
| engine/crates/document-imaging | 6 | 531 |
| engine/crates/document-parsers | 66 | 42503 |
| engine/crates/document-search | 11 | 3050 |
| engine/crates/document-tidy | 9 | 2199 |
| engine/crates/fily-inference | 4 | 437 |
| engine/crates/xfiles-mcp | 19 | 2830 |
| feature/assistant | 11 | 1479 |
| feature/editor | 4 | 574 |
| feature/library | 18 | 3160 |
| feature/reader | 128 | 18140 |
| feature/tidy | 4 | 461 |
| feature/tools | 38 | 6840 |
| ffmpeg | 17 | 2240 |
| firebase | 3 | 96 |
| gradle | 4 | 119 |
| scripts | 1 | 89 |
| sharedLogic | 76 | 7494 |
| training | 26 | 4424 |

## Package and dependency index

- **androidApp** — `com.xfiles.android`, `com.xfiles.android.appfunctions`, `com.xfiles.android.feedback`, `com.xfiles.android.shortcuts`, `com.xfiles.android.widget`
- **baselineprofile** — `com.xfiles.baselineprofile`
- **core/designsystem** — `com.xfiles.design`
- **core/documents** — `com.xfiles.core.documents`
- **documentEngine** — `com.xfiles.documentengine`
- **feature/assistant** — `com.xfiles.feature.assistant`
- **feature/editor** — `com.xfiles.feature.editor`
- **feature/library** — `com.xfiles.feature.library`
- **feature/reader** — `com.xfiles.feature.reader`, `com.xfiles.feature.reader.domain`
- **feature/tidy** — `com.xfiles.feature.tidy`
- **feature/tools** — `com.xfiles.feature.tools`, `com.xfiles.feature.tools.domain`
- **ffmpeg** — `androidx.media3.decoder.ffmpeg`
- **sharedLogic** — `com.xfiles.crash`, `com.xfiles.documents`, `com.xfiles.documents.archive`, `com.xfiles.documents.book`, `com.xfiles.documents.code`, `com.xfiles.documents.library`, `com.xfiles.documents.search`, `com.xfiles.documents.structure`, `com.xfiles.feedback`, `com.xfiles.fily`, `com.xfiles.scan`, `com.xfiles.settings`, `com.xfiles.tidy`

Gradle pins, aliases, and actual project edges are in the [version catalog](../../gradle/libs.versions.toml), [settings](../../settings.gradle.kts) and linked build files below. Cargo direct declarations live in each crate manifest and resolved package versions are in the [engine lock](../../engine/Cargo.lock) and [FFmpeg bridge lock](../../ffmpeg/rust/Cargo.lock). Python pins are in the [training requirements lock](../../training/requirements-lock.txt). Private overlays can modify the local dependency graph and are excluded from compatibility conclusions.

## Revision check

The branch advanced during the audit. The six changed paths were re-inspected for the final snapshot. The presentation input, elapsed timer, splash theme and release version changed. Release-critical findings outside these paths retain the same source anchors.

## Manual review continuation

Prioritize remaining S entries for native parsers and JNI callbacks, Android provider lifecycle and exported intents, and Compose accessibility. Finish with Python training and packaging policy, remaining resources and documentation. Review each checked-in test for covered behavior separately from tests whose names are identifiable by inventory. New source changes after this snapshot need a new fingerprint and finding recheck.

## Per-file ledger

Columns are status, size in bytes, line count, SHA-256 prefix, and screening tags. Tags are ABI, CONC, TRUST, and LIFE. A dash denotes an absent value.

### (root)

- **S** [.editorconfig](../../.editorconfig) · 511 B · 30 lines · `B6D6EFDBADFB`
- **S** [.gitattributes](../../.gitattributes) · 118 B · 8 lines · `797DB2CE4070`
- **S** [.gitignore](../../.gitignore) · 1534 B · 87 lines · `3DD6AD47F36C`
- **S** [build.gradle.kts](../../build.gradle.kts) · 683 B · 15 lines · `A0E7B1DB1803`
- **S** [CODE_OF_CONDUCT.md](../../CODE_OF_CONDUCT.md) · 806 B · 17 lines · `C65FCB307925`
- **S** [CONTRIBUTING.md](../../CONTRIBUTING.md) · 5204 B · 97 lines · `FA9640DB952C`
- **T** [gradle.properties](../../gradle.properties) · 205 B · 5 lines · `A8FA8F1702F3`
- **S** [gradlew](../../gradlew) · 8656 B · 248 lines · `A5A5C199BA02`
- **S** [gradlew.bat](../../gradlew.bat) · 4888 B · 112 lines · `F17917F8DBE6`
- **S** [LICENSE](../../LICENSE) · 11358 B · 202 lines · `CFC7749B96F6`
- **S** [NOTICE](../../NOTICE) · 960 B · 15 lines · `14EA03BC8FE9`
- **S** [README.md](../../README.md) · 10391 B · 222 lines · `9B11983B885C`
- **T** [rust-toolchain.toml](../../rust-toolchain.toml) · 112 B · 4 lines · `8BAFFBE9539D`
- **S** [SECURITY.md](../../SECURITY.md) · 1043 B · 25 lines · `26A324B2E29C`
- **T** [settings.gradle.kts](../../settings.gradle.kts) · 828 B · 23 lines · `064FB0E39C10`

### .github

- **S** [.github/ISSUE_TEMPLATE/bug_report.yml](../../.github/ISSUE_TEMPLATE/bug_report.yml) · 1344 B · 48 lines · `5F2BAAAED6DA`
- **S** [.github/ISSUE_TEMPLATE/config.yml](../../.github/ISSUE_TEMPLATE/config.yml) · 212 B · 5 lines · `7CC43EE0D9CA`
- **S** [.github/ISSUE_TEMPLATE/feature_request.yml](../../.github/ISSUE_TEMPLATE/feature_request.yml) · 707 B · 27 lines · `34639BBB0871`
- **S** [.github/PULL_REQUEST_TEMPLATE.md](../../.github/PULL_REQUEST_TEMPLATE.md) · 897 B · 22 lines · `C4D604BD3F74`
- **T** [.github/workflows/release.yml](../../.github/workflows/release.yml) · 4550 B · 114 lines · `8FF99341ECC6`

### androidApp

- **T** [androidApp/build.gradle.kts](../../androidApp/build.gradle.kts) · 8713 B · 186 lines · `0E340952734E`
- **S** [androidApp/src/dev/res/values/strings.xml](../../androidApp/src/dev/res/values/strings.xml) · 112 B · 4 lines · `806E74CD9A53`
- **S** [androidApp/src/dev/res/xml/shortcuts.xml](../../androidApp/src/dev/res/xml/shortcuts.xml) · 1945 B · 46 lines · `FCEE6DD0B62D`
- **T** [androidApp/src/firebase/AndroidManifest.xml](../../androidApp/src/firebase/AndroidManifest.xml) · 1399 B · 15 lines · `8EA85DDA2E4A` · TRUST
- **S** [androidApp/src/firebase/kotlin/com/xfiles/android/ConsentedCrashBackendFactory.kt](../../androidApp/src/firebase/kotlin/com/xfiles/android/ConsentedCrashBackendFactory.kt) · 1664 B · 29 lines · `4C5E12D0ED95`
- **S** [androidApp/src/firebase/kotlin/com/xfiles/android/CrashReportingProvider.kt](../../androidApp/src/firebase/kotlin/com/xfiles/android/CrashReportingProvider.kt) · 2062 B · 48 lines · `53381D50BFFC` · LIFE,TRUST
- **T** [androidApp/src/firebase/kotlin/com/xfiles/android/feedback/FirestoreFeedbackSender.kt](../../androidApp/src/firebase/kotlin/com/xfiles/android/feedback/FirestoreFeedbackSender.kt) · 4555 B · 105 lines · `3F26F785561F` · CONC,LIFE
- **T** [androidApp/src/main/AndroidManifest.xml](../../androidApp/src/main/AndroidManifest.xml) · 19595 B · 270 lines · `1AF650408D62` · TRUST
- **T** [androidApp/src/main/keepRules/app.keep](../../androidApp/src/main/keepRules/app.keep) · 1209 B · 19 lines · `29A29286A9DD`
- **T** [androidApp/src/main/kotlin/com/xfiles/android/AppearanceStore.kt](../../androidApp/src/main/kotlin/com/xfiles/android/AppearanceStore.kt) · 3382 B · 76 lines · `36AFB5072704` · CONC
- **T** [androidApp/src/main/kotlin/com/xfiles/android/appfunctions/FilyDocumentsSource.kt](../../androidApp/src/main/kotlin/com/xfiles/android/appfunctions/FilyDocumentsSource.kt) · 5258 B · 110 lines · `9A1E86EEB3B0` · LIFE
- **T** [androidApp/src/main/kotlin/com/xfiles/android/appfunctions/FilyToolHost.kt](../../androidApp/src/main/kotlin/com/xfiles/android/appfunctions/FilyToolHost.kt) · 4541 B · 103 lines · `E7A8F899BAFE` · CONC,TRUST
- **S** [androidApp/src/main/kotlin/com/xfiles/android/appfunctions/PassagesModule.kt](../../androidApp/src/main/kotlin/com/xfiles/android/appfunctions/PassagesModule.kt) · 525 B · 17 lines · `BB28FA6F4130`
- **T** [androidApp/src/main/kotlin/com/xfiles/android/appfunctions/XfilesAppFunctionService.kt](../../androidApp/src/main/kotlin/com/xfiles/android/appfunctions/XfilesAppFunctionService.kt) · 6476 B · 143 lines · `D801483CE22E` · TRUST
- **S** [androidApp/src/main/kotlin/com/xfiles/android/AppModule.kt](../../androidApp/src/main/kotlin/com/xfiles/android/AppModule.kt) · 340 B · 14 lines · `DBA855658D52`
- **S** [androidApp/src/main/kotlin/com/xfiles/android/AppNavigator.kt](../../androidApp/src/main/kotlin/com/xfiles/android/AppNavigator.kt) · 2400 B · 66 lines · `A69DC7938E7F` · LIFE
- **S** [androidApp/src/main/kotlin/com/xfiles/android/CrashReporter.kt](../../androidApp/src/main/kotlin/com/xfiles/android/CrashReporter.kt) · 8565 B · 175 lines · `49C965F4330E` · LIFE
- **S** [androidApp/src/main/kotlin/com/xfiles/android/CrashReportingModule.kt](../../androidApp/src/main/kotlin/com/xfiles/android/CrashReportingModule.kt) · 611 B · 18 lines · `FB3C71C2B9D5`
- **S** [androidApp/src/main/kotlin/com/xfiles/android/CrashReportingSetup.kt](../../androidApp/src/main/kotlin/com/xfiles/android/CrashReportingSetup.kt) · 498 B · 12 lines · `488380428F5D`
- **S** [androidApp/src/main/kotlin/com/xfiles/android/CrashReportPrompt.kt](../../androidApp/src/main/kotlin/com/xfiles/android/CrashReportPrompt.kt) · 4227 B · 94 lines · `9A3F4A5CA8E4` · CONC
- **S** [androidApp/src/main/kotlin/com/xfiles/android/feedback/FeedbackEnvironmentModule.kt](../../androidApp/src/main/kotlin/com/xfiles/android/feedback/FeedbackEnvironmentModule.kt) · 1159 B · 32 lines · `B355FF138EAF`
- **S** [androidApp/src/main/kotlin/com/xfiles/android/FilyActions.kt](../../androidApp/src/main/kotlin/com/xfiles/android/FilyActions.kt) · 4558 B · 98 lines · `4EC4A56C2EE9`
- **S** [androidApp/src/main/kotlin/com/xfiles/android/FilyLibrary.kt](../../androidApp/src/main/kotlin/com/xfiles/android/FilyLibrary.kt) · 4033 B · 78 lines · `B2CB29123443`
- **S** [androidApp/src/main/kotlin/com/xfiles/android/LauncherIcons.kt](../../androidApp/src/main/kotlin/com/xfiles/android/LauncherIcons.kt) · 1551 B · 32 lines · `284DDA5B1B1C`
- **S** [androidApp/src/main/kotlin/com/xfiles/android/MainActivity.kt](../../androidApp/src/main/kotlin/com/xfiles/android/MainActivity.kt) · 31052 B · 598 lines · `D066186812ED`
- **S** [androidApp/src/main/kotlin/com/xfiles/android/Routes.kt](../../androidApp/src/main/kotlin/com/xfiles/android/Routes.kt) · 748 B · 20 lines · `2137973AF367`
- **S** [androidApp/src/main/kotlin/com/xfiles/android/SharedLinkSheet.kt](../../androidApp/src/main/kotlin/com/xfiles/android/SharedLinkSheet.kt) · 3596 B · 92 lines · `3C40AE804773`
- **S** [androidApp/src/main/kotlin/com/xfiles/android/shortcuts/RecentShortcuts.kt](../../androidApp/src/main/kotlin/com/xfiles/android/shortcuts/RecentShortcuts.kt) · 2373 B · 54 lines · `E20832FB2682` · CONC
- **S** [androidApp/src/main/kotlin/com/xfiles/android/SplashIntro.kt](../../androidApp/src/main/kotlin/com/xfiles/android/SplashIntro.kt) · 5455 B · 135 lines · `F4D863CB75BE` · CONC
- **S** [androidApp/src/main/kotlin/com/xfiles/android/StartupGate.kt](../../androidApp/src/main/kotlin/com/xfiles/android/StartupGate.kt) · 2804 B · 67 lines · `D9DF97396C46`
- **S** [androidApp/src/main/kotlin/com/xfiles/android/widget/WidgetActions.kt](../../androidApp/src/main/kotlin/com/xfiles/android/widget/WidgetActions.kt) · 1481 B · 38 lines · `A193C5D325BE`
- **S** [androidApp/src/main/kotlin/com/xfiles/android/widget/WidgetRefresh.kt](../../androidApp/src/main/kotlin/com/xfiles/android/widget/WidgetRefresh.kt) · 1644 B · 40 lines · `4CDA30ED905F` · CONC
- **S** [androidApp/src/main/kotlin/com/xfiles/android/widget/XfilesWidget.kt](../../androidApp/src/main/kotlin/com/xfiles/android/widget/XfilesWidget.kt) · 7545 B · 193 lines · `BC7FC42E6412`
- **S** [androidApp/src/main/kotlin/com/xfiles/android/XfilesApplication.kt](../../androidApp/src/main/kotlin/com/xfiles/android/XfilesApplication.kt) · 1435 B · 31 lines · `8B3F60EE2578`
- **S** [androidApp/src/main/res/drawable/ic_launcher_background.xml](../../androidApp/src/main/res/drawable/ic_launcher_background.xml) · 810 B · 16 lines · `108CBC796AA9`
- **S** [androidApp/src/main/res/drawable/ic_launcher_background_dark.xml](../../androidApp/src/main/res/drawable/ic_launcher_background_dark.xml) · 816 B · 16 lines · `AAF775F4116F`
- **S** [androidApp/src/main/res/drawable/ic_launcher_background_light.xml](../../androidApp/src/main/res/drawable/ic_launcher_background_light.xml) · 845 B · 16 lines · `61C6F8BAD5C7`
- **S** [androidApp/src/main/res/drawable/ic_launcher_foreground.xml](../../androidApp/src/main/res/drawable/ic_launcher_foreground.xml) · 9825 B · 127 lines · `A624BFAB8E5A`
- **S** [androidApp/src/main/res/drawable/ic_launcher_monochrome.xml](../../androidApp/src/main/res/drawable/ic_launcher_monochrome.xml) · 1213 B · 13 lines · `CE57345FE49C`
- **S** [androidApp/src/main/res/drawable/ic_shortcut_file.xml](../../androidApp/src/main/res/drawable/ic_shortcut_file.xml) · 492 B · 11 lines · `691F57FE3086`
- **S** [androidApp/src/main/res/drawable/ic_splash_animated.xml](../../androidApp/src/main/res/drawable/ic_splash_animated.xml) · 3621 B · 67 lines · `369402564D49`
- **S** [androidApp/src/main/res/drawable/ic_splash_mark.xml](../../androidApp/src/main/res/drawable/ic_splash_mark.xml) · 9799 B · 127 lines · `F34857469F43`
- **S** [androidApp/src/main/res/drawable/ic_widget_fily.xml](../../androidApp/src/main/res/drawable/ic_widget_fily.xml) · 369 B · 10 lines · `6DB084C40DFC`
- **S** [androidApp/src/main/res/drawable/ic_widget_open.xml](../../androidApp/src/main/res/drawable/ic_widget_open.xml) · 410 B · 10 lines · `CDC0A4D4AA22`
- **S** [androidApp/src/main/res/drawable/ic_widget_scan.xml](../../androidApp/src/main/res/drawable/ic_widget_scan.xml) · 401 B · 10 lines · `73E0FCF41892`
- **S** [androidApp/src/main/res/drawable/widget_preview.xml](../../androidApp/src/main/res/drawable/widget_preview.xml) · 1168 B · 28 lines · `878CF9539B80`
- **S** [androidApp/src/main/res/drawable-night/ic_launcher_background.xml](../../androidApp/src/main/res/drawable-night/ic_launcher_background.xml) · 810 B · 16 lines · `108CBC796AA9`
- **S** [androidApp/src/main/res/mipmap-anydpi/ic_launcher.xml](../../androidApp/src/main/res/mipmap-anydpi/ic_launcher.xml) · 344 B · 6 lines · `ADA31DA9E23F`
- **S** [androidApp/src/main/res/mipmap-anydpi/ic_launcher_dark.xml](../../androidApp/src/main/res/mipmap-anydpi/ic_launcher_dark.xml) · 353 B · 6 lines · `C3DD2416EB9A`
- **S** [androidApp/src/main/res/mipmap-anydpi/ic_launcher_light.xml](../../androidApp/src/main/res/mipmap-anydpi/ic_launcher_light.xml) · 354 B · 6 lines · `665E533D99A8`
- **S** [androidApp/src/main/res/values/colors.xml](../../androidApp/src/main/res/values/colors.xml) · 297 B · 6 lines · `2321D2FA85D5`
- **S** [androidApp/src/main/res/values/strings.xml](../../androidApp/src/main/res/values/strings.xml) · 1287 B · 16 lines · `5C738EEAA7EF`
- **S** [androidApp/src/main/res/values/themes.xml](../../androidApp/src/main/res/values/themes.xml) · 910 B · 15 lines · `2F7AAC4FC1BF`
- **S** [androidApp/src/main/res/values-night/colors.xml](../../androidApp/src/main/res/values-night/colors.xml) · 125 B · 4 lines · `9EE1981FED83`
- **S** [androidApp/src/main/res/values-v31/themes.xml](../../androidApp/src/main/res/values-v31/themes.xml) · 355 B · 7 lines · `35B1D557B4C4`
- **S** [androidApp/src/main/res/xml/app_metadata.xml](../../androidApp/src/main/res/xml/app_metadata.xml) · 514 B · 9 lines · `A17CDF1E64B8`
- **S** [androidApp/src/main/res/xml/archive_cache_paths.xml](../../androidApp/src/main/res/xml/archive_cache_paths.xml) · 227 B · 5 lines · `FE61A3209357`
- **S** [androidApp/src/main/res/xml/data_extraction_rules.xml](../../androidApp/src/main/res/xml/data_extraction_rules.xml) · 821 B · 19 lines · `B2E56391859E`
- **S** [androidApp/src/main/res/xml/xfiles_widget_info.xml](../../androidApp/src/main/res/xml/xfiles_widget_info.xml) · 606 B · 14 lines · `1574BE2EFDB4`
- **S** [androidApp/src/offline/kotlin/com/xfiles/android/CrashReportingProvider.kt](../../androidApp/src/offline/kotlin/com/xfiles/android/CrashReportingProvider.kt) · 423 B · 11 lines · `B297C0A5D8CB`
- **S** [androidApp/src/offline/kotlin/com/xfiles/android/feedback/ShareFeedbackSender.kt](../../androidApp/src/offline/kotlin/com/xfiles/android/feedback/ShareFeedbackSender.kt) · 1754 B · 48 lines · `D9B20DC19B88` · LIFE
- **S** [androidApp/src/prod/res/xml/shortcuts.xml](../../androidApp/src/prod/res/xml/shortcuts.xml) · 2173 B · 48 lines · `8A2A15C51049`

### baselineprofile

- **T** [baselineprofile/build.gradle.kts](../../baselineprofile/build.gradle.kts) · 2122 B · 55 lines · `5D4581EF5410`
- **T** [baselineprofile/src/main/AndroidManifest.xml](../../baselineprofile/src/main/AndroidManifest.xml) · 473 B · 11 lines · `1C8DC6AC1434` · TRUST
- **S** [baselineprofile/src/main/kotlin/com/xfiles/baselineprofile/BaselineProfileGenerator.kt](../../baselineprofile/src/main/kotlin/com/xfiles/baselineprofile/BaselineProfileGenerator.kt) · 1265 B · 36 lines · `F9A47730120A`
- **S** [baselineprofile/src/main/kotlin/com/xfiles/baselineprofile/BenchmarkFixtures.kt](../../baselineprofile/src/main/kotlin/com/xfiles/baselineprofile/BenchmarkFixtures.kt) · 5930 B · 114 lines · `DE5BA717EC37` · LIFE
- **S** [baselineprofile/src/main/kotlin/com/xfiles/baselineprofile/Journeys.kt](../../baselineprofile/src/main/kotlin/com/xfiles/baselineprofile/Journeys.kt) · 2788 B · 63 lines · `7C8135ED2AB6`
- **S** [baselineprofile/src/main/kotlin/com/xfiles/baselineprofile/ReaderBenchmark.kt](../../baselineprofile/src/main/kotlin/com/xfiles/baselineprofile/ReaderBenchmark.kt) · 1878 B · 53 lines · `C8AAB4FAA66E`
- **S** [baselineprofile/src/main/kotlin/com/xfiles/baselineprofile/StartupBenchmark.kt](../../baselineprofile/src/main/kotlin/com/xfiles/baselineprofile/StartupBenchmark.kt) · 1375 B · 40 lines · `2ACADAA646E6`

### core/designsystem

- **T** [core/designsystem/build.gradle.kts](../../core/designsystem/build.gradle.kts) · 659 B · 28 lines · `8DCFD6202B50`
- **S** [core/designsystem/src/main/kotlin/com/xfiles/design/EnterMotion.kt](../../core/designsystem/src/main/kotlin/com/xfiles/design/EnterMotion.kt) · 1375 B · 35 lines · `D24B445A694E`
- **S** [core/designsystem/src/main/kotlin/com/xfiles/design/EzComponents.kt](../../core/designsystem/src/main/kotlin/com/xfiles/design/EzComponents.kt) · 13811 B · 375 lines · `E1029A7F151F`
- **S** [core/designsystem/src/main/kotlin/com/xfiles/design/EzHaptics.kt](../../core/designsystem/src/main/kotlin/com/xfiles/design/EzHaptics.kt) · 2264 B · 54 lines · `6EA72AC3354A`
- **S** [core/designsystem/src/main/kotlin/com/xfiles/design/EzIcons.kt](../../core/designsystem/src/main/kotlin/com/xfiles/design/EzIcons.kt) · 14246 B · 170 lines · `FE6CA2F4B21C`
- **S** [core/designsystem/src/main/kotlin/com/xfiles/design/EzListItems.kt](../../core/designsystem/src/main/kotlin/com/xfiles/design/EzListItems.kt) · 3505 B · 92 lines · `EBAF7A321AE3`
- **S** [core/designsystem/src/main/kotlin/com/xfiles/design/EzMotion.kt](../../core/designsystem/src/main/kotlin/com/xfiles/design/EzMotion.kt) · 1722 B · 35 lines · `51E2BC641F52`
- **S** [core/designsystem/src/main/kotlin/com/xfiles/design/EzNavigationBar.kt](../../core/designsystem/src/main/kotlin/com/xfiles/design/EzNavigationBar.kt) · 5686 B · 120 lines · `DF74D03CE359`
- **S** [core/designsystem/src/main/kotlin/com/xfiles/design/EzOptionMenu.kt](../../core/designsystem/src/main/kotlin/com/xfiles/design/EzOptionMenu.kt) · 1540 B · 45 lines · `0296C86F06E7`
- **S** [core/designsystem/src/main/kotlin/com/xfiles/design/EzSwitch.kt](../../core/designsystem/src/main/kotlin/com/xfiles/design/EzSwitch.kt) · 961 B · 32 lines · `7A5856A861F8`
- **S** [core/designsystem/src/main/kotlin/com/xfiles/design/EzTheme.kt](../../core/designsystem/src/main/kotlin/com/xfiles/design/EzTheme.kt) · 17437 B · 435 lines · `2B5E0869C5AC`
- **S** [core/designsystem/src/main/kotlin/com/xfiles/design/FilyMascot.kt](../../core/designsystem/src/main/kotlin/com/xfiles/design/FilyMascot.kt) · 21535 B · 470 lines · `29D34A95C4E9`
- **S** [core/designsystem/src/main/kotlin/com/xfiles/design/Haptics.kt](../../core/designsystem/src/main/kotlin/com/xfiles/design/Haptics.kt) · 5539 B · 149 lines · `F3974A2495B7`
- **S** [core/designsystem/src/main/kotlin/com/xfiles/design/ReducedMotion.kt](../../core/designsystem/src/main/kotlin/com/xfiles/design/ReducedMotion.kt) · 2421 B · 64 lines · `DE1ECAA6DEDE` · CONC
- **S** [core/designsystem/src/main/kotlin/com/xfiles/design/Surfaces.kt](../../core/designsystem/src/main/kotlin/com/xfiles/design/Surfaces.kt) · 13919 B · 343 lines · `F352A39BB6FE`

### core/documents

- **T** [core/documents/build.gradle.kts](../../core/documents/build.gradle.kts) · 887 B · 32 lines · `899F9D76EB32`
- **T** [core/documents/src/main/kotlin/com/xfiles/core/documents/AndroidPdfDocument.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/AndroidPdfDocument.kt) · 21967 B · 489 lines · `C48F4D71CAC3` · CONC,LIFE
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/AndroidTidy.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/AndroidTidy.kt) · 7709 B · 182 lines · `7966816588C6` · ABI,CONC,LIFE,TRUST
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/ArchiveHandoff.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/ArchiveHandoff.kt) · 821 B · 26 lines · `857837E5DB58`
- **T** [core/documents/src/main/kotlin/com/xfiles/core/documents/ArchiveRepository.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/ArchiveRepository.kt) · 12400 B · 286 lines · `0B66F87B7B17` · ABI,CONC,LIFE
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/ArchiveTime.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/ArchiveTime.kt) · 817 B · 19 lines · `C3C815803127`
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/ConversionRepository.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/ConversionRepository.kt) · 21843 B · 455 lines · `D823CCC15AD4` · CONC,LIFE,TRUST
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/DocumentPrinting.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/DocumentPrinting.kt) · 1689 B · 44 lines · `A9CACD8B509C`
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/DocumentRepository.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/DocumentRepository.kt) · 19216 B · 413 lines · `244D65445E39` · CONC,LIFE,TRUST
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/DocumentSharing.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/DocumentSharing.kt) · 2127 B · 48 lines · `702FF03F161A` · LIFE
- **T** [core/documents/src/main/kotlin/com/xfiles/core/documents/FolderRepository.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/FolderRepository.kt) · 9498 B · 208 lines · `DD9476979714` · CONC,LIFE
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/Frecency.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/Frecency.kt) · 1228 B · 32 lines · `913F88F55130`
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/JpegPdfWriter.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/JpegPdfWriter.kt) · 3961 B · 98 lines · `161DCA3BB78B`
- **T** [core/documents/src/main/kotlin/com/xfiles/core/documents/LibrarySearchRepository.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/LibrarySearchRepository.kt) · 5633 B · 133 lines · `BDA7131F1C60` · CONC,LIFE,TRUST
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/MemoryPressure.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/MemoryPressure.kt) · 766 B · 22 lines · `23B3D8605293`
- **T** [core/documents/src/main/kotlin/com/xfiles/core/documents/OfficeDocumentEditor.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/OfficeDocumentEditor.kt) · 7144 B · 165 lines · `065DE4566942` · CONC,LIFE,TRUST
- **T** [core/documents/src/main/kotlin/com/xfiles/core/documents/OfficeEditRepository.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/OfficeEditRepository.kt) · 7544 B · 172 lines · `7A6AD5F22E68` · CONC,LIFE,TRUST
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/PagedText.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/PagedText.kt) · 443 B · 9 lines · `C0A5BA94535C`
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/PdfComposer.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/PdfComposer.kt) · 4730 B · 120 lines · `001E0FE1BFD5` · LIFE
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/PdfPageText.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/PdfPageText.kt) · 10223 B · 225 lines · `03C8AEE39D20`
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/PdfPrintAdapter.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/PdfPrintAdapter.kt) · 3103 B · 74 lines · `80ECD34811EF` · LIFE
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/PdfToolsRepository.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/PdfToolsRepository.kt) · 15818 B · 379 lines · `7DBB274908E7` · CONC,LIFE,TRUST
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/PictureOptions.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/PictureOptions.kt) · 2355 B · 54 lines · `51D98C0F487B`
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/ScanProcessing.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/ScanProcessing.kt) · 9105 B · 199 lines · `F9F44AEEDA90` · LIFE
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/TextPdf.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/TextPdf.kt) · 11111 B · 256 lines · `B0A5C20AAECD`
- **S** [core/documents/src/main/kotlin/com/xfiles/core/documents/TextPdfLayout.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/TextPdfLayout.kt) · 9958 B · 213 lines · `736E4A7F36B7`
- **T** [core/documents/src/main/kotlin/com/xfiles/core/documents/TextRecognitionRepository.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/TextRecognitionRepository.kt) · 2238 B · 51 lines · `E65345092F39` · CONC
- **T** [core/documents/src/main/kotlin/com/xfiles/core/documents/ZipArchives.kt](../../core/documents/src/main/kotlin/com/xfiles/core/documents/ZipArchives.kt) · 11053 B · 239 lines · `D36EFDAC90CC` · TRUST

### docs

- **S** [docs/assets/xfiles-logo.svg](../../docs/assets/xfiles-logo.svg) · 7390 B · 106 lines · `A411CF4F1CE3`
- **S** [docs/PLAN.md](../../docs/PLAN.md) · 25818 B · 163 lines · `31A0B051EE04`

### documentEngine

- **T** [documentEngine/build.gradle.kts](../../documentEngine/build.gradle.kts) · 1595 B · 47 lines · `D866BDAFCFBA`
- **S** [documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/DocumentAnalyzer.kt](../../documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/DocumentAnalyzer.kt) · 1908 B · 48 lines · `1BA7B6C97FC2` · CONC
- **S** [documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/DocumentPassages.kt](../../documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/DocumentPassages.kt) · 2618 B · 59 lines · `6FC27F89A918`
- **S** [documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/DocumentTextIndex.kt](../../documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/DocumentTextIndex.kt) · 1226 B · 31 lines · `02C8C674B0F7` · LIFE
- **T** [documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/FilyInference.kt](../../documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/FilyInference.kt) · 6804 B · 167 lines · `DB7C8B088EAF` · CONC,LIFE,TRUST
- **S** [documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/OfficeEditEngine.kt](../../documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/OfficeEditEngine.kt) · 4087 B · 102 lines · `8E8B149B4CAC` · LIFE
- **S** [documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/PdfMarkupWriter.kt](../../documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/PdfMarkupWriter.kt) · 1702 B · 37 lines · `46AA0071E4B5`
- **S** [documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/PdfRewrite.kt](../../documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/PdfRewrite.kt) · 5396 B · 112 lines · `36609297B8AB` · LIFE
- **S** [documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/PdfSignatures.kt](../../documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/PdfSignatures.kt) · 965 B · 32 lines · `21991C97C74E`
- **S** [documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/PdfTextLayerWriter.kt](../../documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/PdfTextLayerWriter.kt) · 1072 B · 32 lines · `44EFB745F6E3`
- **S** [documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/PdfToolRuns.kt](../../documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/PdfToolRuns.kt) · 10387 B · 259 lines · `CC88E960223B` · LIFE
- **S** [documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/RustTidyEngine.kt](../../documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/RustTidyEngine.kt) · 3999 B · 85 lines · `91501EB41B2B` · LIFE
- **S** [documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/SheetSlideEdits.kt](../../documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/SheetSlideEdits.kt) · 3465 B · 78 lines · `0FF1A1EC684D` · LIFE
- **T** [documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/StructuredDocumentReader.kt](../../documentEngine/src/androidMain/kotlin/com/xfiles/documentengine/StructuredDocumentReader.kt) · 13519 B · 310 lines · `19D669380605` · CONC,LIFE

### engine

- **S** [engine/Cargo.lock](../../engine/Cargo.lock) · 47130 B · 1912 lines · `5D17DA35C0BF`
- **T** [engine/Cargo.toml](../../engine/Cargo.toml) · 2753 B · 56 lines · `FDED21A45F78` · LIFE
- **S** [engine/rustfmt.toml](../../engine/rustfmt.toml) · 45 B · 2 lines · `93ED1668BB2C`

### engine/crates/document-core

- **S** [engine/crates/document-core/Cargo.toml](../../engine/crates/document-core/Cargo.toml) · 313 B · 10 lines · `591E0843B69E`
- **S** [engine/crates/document-core/src/cancel.rs](../../engine/crates/document-core/src/cancel.rs) · 811 B · 26 lines · `0EAD8F2C90BD`
- **S** [engine/crates/document-core/src/detect.rs](../../engine/crates/document-core/src/detect.rs) · 5863 B · 143 lines · `D7BF6D6AF7D1`
- **S** [engine/crates/document-core/src/engine.rs](../../engine/crates/document-core/src/engine.rs) · 7764 B · 212 lines · `520458D26233` · LIFE
- **S** [engine/crates/document-core/src/error.rs](../../engine/crates/document-core/src/error.rs) · 2071 B · 69 lines · `FC6CDF9A4E81`
- **S** [engine/crates/document-core/src/lib.rs](../../engine/crates/document-core/src/lib.rs) · 791 B · 24 lines · `00A9B875EB37`
- **S** [engine/crates/document-core/src/limits.rs](../../engine/crates/document-core/src/limits.rs) · 1821 B · 44 lines · `FDA28AFD3D86`
- **S** [engine/crates/document-core/src/model.rs](../../engine/crates/document-core/src/model.rs) · 24353 B · 579 lines · `DDA1CE031C9C`
- **S** [engine/crates/document-core/src/parser.rs](../../engine/crates/document-core/src/parser.rs) · 2260 B · 61 lines · `7B941FC5A19C`

### engine/crates/document-ffi

- **S** [engine/crates/document-ffi/Cargo.toml](../../engine/crates/document-ffi/Cargo.toml) · 891 B · 30 lines · `6C7B4A3BE481`
- **S** [engine/crates/document-ffi/examples/profile.rs](../../engine/crates/document-ffi/examples/profile.rs) · 2519 B · 69 lines · `F052050FEBAD` · LIFE
- **T** [engine/crates/document-ffi/src/archive.rs](../../engine/crates/document-ffi/src/archive.rs) · 4910 B · 141 lines · `B1ACDAA82C2D` · ABI,CONC
- **T** [engine/crates/document-ffi/src/fily.rs](../../engine/crates/document-ffi/src/fily.rs) · 1737 B · 49 lines · `A55B10CEF8BC`
- **S** [engine/crates/document-ffi/src/glance.rs](../../engine/crates/document-ffi/src/glance.rs) · 1955 B · 66 lines · `485A2BF2870D`
- **S** [engine/crates/document-ffi/src/imaging.rs](../../engine/crates/document-ffi/src/imaging.rs) · 1911 B · 48 lines · `C5E12F95408A`
- **S** [engine/crates/document-ffi/src/lib.rs](../../engine/crates/document-ffi/src/lib.rs) · 39839 B · 1152 lines · `4FCBD1F26077` · ABI,CONC,LIFE
- **T** [engine/crates/document-ffi/src/office_edit.rs](../../engine/crates/document-ffi/src/office_edit.rs) · 10750 B · 278 lines · `0D90433D12A9` · CONC,LIFE
- **S** [engine/crates/document-ffi/src/pdf_text_layer.rs](../../engine/crates/document-ffi/src/pdf_text_layer.rs) · 3214 B · 82 lines · `A1418D6EE625` · LIFE
- **S** [engine/crates/document-ffi/src/pdf_tools_ext.rs](../../engine/crates/document-ffi/src/pdf_tools_ext.rs) · 19796 B · 478 lines · `BFB6419FB0EE` · LIFE
- **S** [engine/crates/document-ffi/src/search.rs](../../engine/crates/document-ffi/src/search.rs) · 15373 B · 420 lines · `BAED706A4085` · CONC,LIFE
- **S** [engine/crates/document-ffi/src/sheet_edit.rs](../../engine/crates/document-ffi/src/sheet_edit.rs) · 3973 B · 114 lines · `6D82E3F2294D` · LIFE
- **S** [engine/crates/document-ffi/src/slide_edit.rs](../../engine/crates/document-ffi/src/slide_edit.rs) · 1804 B · 43 lines · `9AA10A4586B6` · LIFE
- **S** [engine/crates/document-ffi/src/tidy.rs](../../engine/crates/document-ffi/src/tidy.rs) · 10016 B · 273 lines · `926BF789CF67` · CONC,LIFE
- **S** [engine/crates/document-ffi/tests/pdf_annotations.rs](../../engine/crates/document-ffi/tests/pdf_annotations.rs) · 6839 B · 171 lines · `EB40821C63E4` · LIFE
- **S** [engine/crates/document-ffi/tests/pdf_tools.rs](../../engine/crates/document-ffi/tests/pdf_tools.rs) · 9066 B · 220 lines · `7D0FE3BFE7C7` · LIFE
- **S** [engine/crates/document-ffi/tests/session_lifecycle.rs](../../engine/crates/document-ffi/tests/session_lifecycle.rs) · 5539 B · 143 lines · `1C85B8C6E7E6` · LIFE

### engine/crates/document-glance

- **S** [engine/crates/document-glance/Cargo.toml](../../engine/crates/document-glance/Cargo.toml) · 458 B · 12 lines · `263CAE407D02`
- **S** [engine/crates/document-glance/src/items.rs](../../engine/crates/document-glance/src/items.rs) · 30191 B · 797 lines · `7282C0DC74FC`
- **S** [engine/crates/document-glance/src/keywords.rs](../../engine/crates/document-glance/src/keywords.rs) · 11281 B · 262 lines · `1EA682600201`
- **S** [engine/crates/document-glance/src/language.rs](../../engine/crates/document-glance/src/language.rs) · 8889 B · 235 lines · `4D1A84D239FB`
- **S** [engine/crates/document-glance/src/lib.rs](../../engine/crates/document-glance/src/lib.rs) · 4278 B · 126 lines · `68D336114729`
- **S** [engine/crates/document-glance/src/sentences.rs](../../engine/crates/document-glance/src/sentences.rs) · 5191 B · 142 lines · `A24B09F24317`
- **S** [engine/crates/document-glance/src/stopwords.rs](../../engine/crates/document-glance/src/stopwords.rs) · 7632 B · 119 lines · `C47076906374` · LIFE
- **S** [engine/crates/document-glance/src/textrank.rs](../../engine/crates/document-glance/src/textrank.rs) · 6613 B · 189 lines · `93A451CEED8F` · LIFE

### engine/crates/document-imaging

- **S** [engine/crates/document-imaging/Cargo.toml](../../engine/crates/document-imaging/Cargo.toml) · 362 B · 10 lines · `D9557A5631CE`
- **S** [engine/crates/document-imaging/src/lib.rs](../../engine/crates/document-imaging/src/lib.rs) · 3196 B · 85 lines · `3FE7CBEB3F04`
- **T** [engine/crates/document-imaging/src/sauvola.rs](../../engine/crates/document-imaging/src/sauvola.rs) · 4405 B · 116 lines · `897217424025` · LIFE
- **S** [engine/crates/document-imaging/src/sharpness.rs](../../engine/crates/document-imaging/src/sharpness.rs) · 4377 B · 123 lines · `60185B051FFE` · LIFE
- **S** [engine/crates/document-imaging/src/similarity.rs](../../engine/crates/document-imaging/src/similarity.rs) · 2362 B · 62 lines · `4E497947D57E` · LIFE
- **S** [engine/crates/document-imaging/src/skew.rs](../../engine/crates/document-imaging/src/skew.rs) · 4721 B · 135 lines · `B42C6C4CC3EC` · LIFE

### engine/crates/document-parsers

- **S** [engine/crates/document-parsers/Cargo.toml](../../engine/crates/document-parsers/Cargo.toml) · 649 B · 24 lines · `040529255268`
- **S** [engine/crates/document-parsers/examples/render_page.rs](../../engine/crates/document-parsers/examples/render_page.rs) · 770 B · 18 lines · `D6AA0C0100F1`
- **S** [engine/crates/document-parsers/src/archive_crypto.rs](../../engine/crates/document-parsers/src/archive_crypto.rs) · 21224 B · 624 lines · `1D6D6E537515` · LIFE
- **S** [engine/crates/document-parsers/src/archive_index.rs](../../engine/crates/document-parsers/src/archive_index.rs) · 17358 B · 438 lines · `0E2CB86EACC0` · ABI,LIFE
- **S** [engine/crates/document-parsers/src/cfb.rs](../../engine/crates/document-parsers/src/cfb.rs) · 34532 B · 830 lines · `90765F23A982` · LIFE
- **S** [engine/crates/document-parsers/src/code.rs](../../engine/crates/document-parsers/src/code.rs) · 5316 B · 157 lines · `9D077BF95BA4` · LIFE
- **S** [engine/crates/document-parsers/src/csv.rs](../../engine/crates/document-parsers/src/csv.rs) · 5528 B · 163 lines · `38A7A3BD37FE` · LIFE
- **S** [engine/crates/document-parsers/src/docx.rs](../../engine/crates/document-parsers/src/docx.rs) · 75833 B · 1712 lines · `BC9796B2BA41` · LIFE
- **S** [engine/crates/document-parsers/src/docx_html.rs](../../engine/crates/document-parsers/src/docx_html.rs) · 120588 B · 2766 lines · `4D20F88F90D7` · LIFE
- **S** [engine/crates/document-parsers/src/dom.rs](../../engine/crates/document-parsers/src/dom.rs) · 4994 B · 130 lines · `BC7ED87498A0`
- **S** [engine/crates/document-parsers/src/encrypted.rs](../../engine/crates/document-parsers/src/encrypted.rs) · 38869 B · 933 lines · `C3C8837FA850` · LIFE
- **S** [engine/crates/document-parsers/src/epub.rs](../../engine/crates/document-parsers/src/epub.rs) · 41392 B · 936 lines · `4B22A5E771B3` · LIFE
- **S** [engine/crates/document-parsers/src/epub_book.rs](../../engine/crates/document-parsers/src/epub_book.rs) · 48892 B · 1163 lines · `2DFE6302AB3E` · LIFE
- **S** [engine/crates/document-parsers/src/image.rs](../../engine/crates/document-parsers/src/image.rs) · 2807 B · 85 lines · `ACEF039EF024` · LIFE
- **S** [engine/crates/document-parsers/src/legacy_doc.rs](../../engine/crates/document-parsers/src/legacy_doc.rs) · 32241 B · 826 lines · `DC40D0081879`
- **S** [engine/crates/document-parsers/src/legacy_ppt.rs](../../engine/crates/document-parsers/src/legacy_ppt.rs) · 50521 B · 1195 lines · `ADD7F539DE48`
- **S** [engine/crates/document-parsers/src/legacy_ppt_draw.rs](../../engine/crates/document-parsers/src/legacy_ppt_draw.rs) · 41961 B · 1143 lines · `333C3BC21DC1`
- **S** [engine/crates/document-parsers/src/legacy_xls.rs](../../engine/crates/document-parsers/src/legacy_xls.rs) · 51367 B · 1272 lines · `5833C3489C46`
- **S** [engine/crates/document-parsers/src/lib.rs](../../engine/crates/document-parsers/src/lib.rs) · 7106 B · 186 lines · `FBF416C1D1EB`
- **S** [engine/crates/document-parsers/src/listing.rs](../../engine/crates/document-parsers/src/listing.rs) · 2375 B · 73 lines · `4688F3D8BEB3` · LIFE
- **S** [engine/crates/document-parsers/src/markdown.rs](../../engine/crates/document-parsers/src/markdown.rs) · 14319 B · 405 lines · `0E245D9FD64A` · LIFE
- **S** [engine/crates/document-parsers/src/markdown_html.rs](../../engine/crates/document-parsers/src/markdown_html.rs) · 14697 B · 408 lines · `7BCC393FFD52` · ABI,LIFE
- **S** [engine/crates/document-parsers/src/numfmt.rs](../../engine/crates/document-parsers/src/numfmt.rs) · 56367 B · 1478 lines · `79E8DA70E227` · LIFE
- **S** [engine/crates/document-parsers/src/odf.rs](../../engine/crates/document-parsers/src/odf.rs) · 37191 B · 857 lines · `D87624FC7A01` · LIFE
- **S** [engine/crates/document-parsers/src/odp.rs](../../engine/crates/document-parsers/src/odp.rs) · 43680 B · 964 lines · `61BA537BAD68` · LIFE
- **S** [engine/crates/document-parsers/src/odt_html.rs](../../engine/crates/document-parsers/src/odt_html.rs) · 90834 B · 2006 lines · `288D8FC3358F` · LIFE
- **S** [engine/crates/document-parsers/src/office_edit.rs](../../engine/crates/document-parsers/src/office_edit.rs) · 54406 B · 1436 lines · `385F30113A38` · LIFE
- **S** [engine/crates/document-parsers/src/ooxml.rs](../../engine/crates/document-parsers/src/ooxml.rs) · 10739 B · 263 lines · `BB8DB96A1D56`
- **S** [engine/crates/document-parsers/src/package.rs](../../engine/crates/document-parsers/src/package.rs) · 11425 B · 264 lines · `D4B0A30743C8` · LIFE
- **S** [engine/crates/document-parsers/src/pdf.rs](../../engine/crates/document-parsers/src/pdf.rs) · 2041 B · 62 lines · `0D42ACA04185` · LIFE
- **S** [engine/crates/document-parsers/src/pdf_annot.rs](../../engine/crates/document-parsers/src/pdf_annot.rs) · 63359 B · 1492 lines · `C7B425C6B422` · LIFE
- **S** [engine/crates/document-parsers/src/pdf_crypt.rs](../../engine/crates/document-parsers/src/pdf_crypt.rs) · 42714 B · 1111 lines · `B42C77E72F48` · LIFE
- **S** [engine/crates/document-parsers/src/pdf_file.rs](../../engine/crates/document-parsers/src/pdf_file.rs) · 46710 B · 1103 lines · `D589A8EE941B` · LIFE
- **S** [engine/crates/document-parsers/src/pdf_signatures.rs](../../engine/crates/document-parsers/src/pdf_signatures.rs) · 10960 B · 272 lines · `88B7A54F8AF0` · LIFE
- **S** [engine/crates/document-parsers/src/pdf_syntax.rs](../../engine/crates/document-parsers/src/pdf_syntax.rs) · 17838 B · 529 lines · `0A3E2600485C` · LIFE
- **S** [engine/crates/document-parsers/src/pdf_text.rs](../../engine/crates/document-parsers/src/pdf_text.rs) · 52613 B · 1418 lines · `F2C914FB58DD` · LIFE
- **S** [engine/crates/document-parsers/src/pdf_text_layer.rs](../../engine/crates/document-parsers/src/pdf_text_layer.rs) · 21001 B · 526 lines · `B09601913887` · LIFE
- **S** [engine/crates/document-parsers/src/pdf_tools.rs](../../engine/crates/document-parsers/src/pdf_tools.rs) · 63959 B · 1583 lines · `C3C70CE8A270` · LIFE
- **S** [engine/crates/document-parsers/src/pdf_tools_compress.rs](../../engine/crates/document-parsers/src/pdf_tools_compress.rs) · 22788 B · 564 lines · `A8DA7BF46E76` · LIFE
- **S** [engine/crates/document-parsers/src/pdf_tools_images.rs](../../engine/crates/document-parsers/src/pdf_tools_images.rs) · 71526 B · 1791 lines · `3A985181B062` · LIFE
- **S** [engine/crates/document-parsers/src/pdf_tools_ops_tests.rs](../../engine/crates/document-parsers/src/pdf_tools_ops_tests.rs) · 12968 B · 330 lines · `D5488AFF7195` · LIFE
- **S** [engine/crates/document-parsers/src/pdf_tools_overlay.rs](../../engine/crates/document-parsers/src/pdf_tools_overlay.rs) · 24725 B · 641 lines · `4AA02C4A2208` · LIFE
- **S** [engine/crates/document-parsers/src/pdf_tools_split.rs](../../engine/crates/document-parsers/src/pdf_tools_split.rs) · 3969 B · 107 lines · `2C4C80DB891C` · LIFE
- **S** [engine/crates/document-parsers/src/pdf_tools_tests.rs](../../engine/crates/document-parsers/src/pdf_tools_tests.rs) · 50267 B · 1114 lines · `340935341797` · LIFE
- **S** [engine/crates/document-parsers/src/pptx.rs](../../engine/crates/document-parsers/src/pptx.rs) · 54734 B · 1299 lines · `B396C2B6BE6F`
- **S** [engine/crates/document-parsers/src/readahead.rs](../../engine/crates/document-parsers/src/readahead.rs) · 8858 B · 248 lines · `5B0BF259A56A` · LIFE
- **S** [engine/crates/document-parsers/src/rich.rs](../../engine/crates/document-parsers/src/rich.rs) · 5064 B · 156 lines · `4A941ED0F20C`
- **S** [engine/crates/document-parsers/src/sheet_edit.rs](../../engine/crates/document-parsers/src/sheet_edit.rs) · 52359 B · 1273 lines · `AD7C3FE4A48B` · LIFE
- **S** [engine/crates/document-parsers/src/slide_edit.rs](../../engine/crates/document-parsers/src/slide_edit.rs) · 25064 B · 580 lines · `135514EA0EC8` · LIFE
- **S** [engine/crates/document-parsers/src/stream.rs](../../engine/crates/document-parsers/src/stream.rs) · 8752 B · 225 lines · `4758CC7550FB` · LIFE
- **S** [engine/crates/document-parsers/src/text.rs](../../engine/crates/document-parsers/src/text.rs) · 24031 B · 623 lines · `685A19295CD0` · LIFE
- **S** [engine/crates/document-parsers/src/text_replace.rs](../../engine/crates/document-parsers/src/text_replace.rs) · 19181 B · 548 lines · `11D9B60E0DB7` · LIFE
- **S** [engine/crates/document-parsers/src/xlsx.rs](../../engine/crates/document-parsers/src/xlsx.rs) · 51085 B · 1133 lines · `0615D083D16C` · LIFE
- **S** [engine/crates/document-parsers/src/xml.rs](../../engine/crates/document-parsers/src/xml.rs) · 14568 B · 438 lines · `08AF83E00793` · ABI,LIFE
- **S** [engine/crates/document-parsers/src/zip.rs](../../engine/crates/document-parsers/src/zip.rs) · 24259 B · 582 lines · `56A28FBE8A5F` · LIFE
- **I** [engine/crates/document-parsers/testdata/pdf_crypt/r2_rc4_40.pdf](../../engine/crates/document-parsers/testdata/pdf_crypt/r2_rc4_40.pdf) · 1509 B · — lines · `0103BD16E873`
- **I** [engine/crates/document-parsers/testdata/pdf_crypt/r3_rc4_128.pdf](../../engine/crates/document-parsers/testdata/pdf_crypt/r3_rc4_128.pdf) · 1510 B · — lines · `AFB1F0F3B333`
- **I** [engine/crates/document-parsers/testdata/pdf_crypt/r4_aes_128.pdf](../../engine/crates/document-parsers/testdata/pdf_crypt/r4_aes_128.pdf) · 1520 B · — lines · `987B42861974`
- **I** [engine/crates/document-parsers/testdata/pdf_crypt/r4_aes_clear_metadata.pdf](../../engine/crates/document-parsers/testdata/pdf_crypt/r4_aes_clear_metadata.pdf) · 1497 B · — lines · `228CB1B04F0B`
- **I** [engine/crates/document-parsers/testdata/pdf_crypt/r4_empty_user_no_assemble.pdf](../../engine/crates/document-parsers/testdata/pdf_crypt/r4_empty_user_no_assemble.pdf) · 1739 B · — lines · `2CFFD1B0267B`
- **I** [engine/crates/document-parsers/testdata/pdf_crypt/r4_rc4_128.pdf](../../engine/crates/document-parsers/testdata/pdf_crypt/r4_rc4_128.pdf) · 1618 B · — lines · `FDA69C2D07E9`
- **I** [engine/crates/document-parsers/testdata/pdf_crypt/r6_aes_256.pdf](../../engine/crates/document-parsers/testdata/pdf_crypt/r6_aes_256.pdf) · 1822 B · — lines · `BBE78AFC5D8D`
- **I** [engine/crates/document-parsers/testdata/pdf_crypt/r6_empty_user_all_allowed.pdf](../../engine/crates/document-parsers/testdata/pdf_crypt/r6_empty_user_all_allowed.pdf) · 2036 B · — lines · `3DB474CDBEC1`
- **I** [engine/crates/document-parsers/testdata/pdf_crypt/r6_empty_user_no_modify.pdf](../../engine/crates/document-parsers/testdata/pdf_crypt/r6_empty_user_no_modify.pdf) · 1823 B · — lines · `DF0528D90DEA`
- **I** [engine/crates/document-parsers/testdata/pdf_crypt/r6_empty_user_no_print.pdf](../../engine/crates/document-parsers/testdata/pdf_crypt/r6_empty_user_no_print.pdf) · 2039 B · — lines · `E8474D672B86`
- **I** [engine/crates/document-parsers/testdata/pdf_crypt/r6_user_no_copy.pdf](../../engine/crates/document-parsers/testdata/pdf_crypt/r6_user_no_copy.pdf) · 2037 B · — lines · `7CF5EB707E36`

### engine/crates/document-search

- **S** [engine/crates/document-search/Cargo.toml](../../engine/crates/document-search/Cargo.toml) · 273 B · 9 lines · `6550BA291033`
- **S** [engine/crates/document-search/src/fold.rs](../../engine/crates/document-search/src/fold.rs) · 3356 B · 98 lines · `13A3C9AECBF7`
- **T** [engine/crates/document-search/src/index.rs](../../engine/crates/document-search/src/index.rs) · 29800 B · 797 lines · `574AF7C0CBE1` · CONC,LIFE
- **S** [engine/crates/document-search/src/lib.rs](../../engine/crates/document-search/src/lib.rs) · 1050 B · 29 lines · `719FD172C000`
- **S** [engine/crates/document-search/src/lru.rs](../../engine/crates/document-search/src/lru.rs) · 2855 B · 94 lines · `73ADAD40F5DA`
- **S** [engine/crates/document-search/src/names.rs](../../engine/crates/document-search/src/names.rs) · 12808 B · 349 lines · `BE55ED4D031C`
- **S** [engine/crates/document-search/src/passage.rs](../../engine/crates/document-search/src/passage.rs) · 30009 B · 724 lines · `13725396AC6C` · CONC,LIFE
- **S** [engine/crates/document-search/src/snippet.rs](../../engine/crates/document-search/src/snippet.rs) · 8014 B · 217 lines · `00F9738A4924` · LIFE
- **S** [engine/crates/document-search/src/store.rs](../../engine/crates/document-search/src/store.rs) · 8373 B · 254 lines · `ADE30EDFC01A` · LIFE
- **S** [engine/crates/document-search/src/text.rs](../../engine/crates/document-search/src/text.rs) · 7853 B · 257 lines · `4A8FC8776B67`
- **S** [engine/crates/document-search/src/tokens.rs](../../engine/crates/document-search/src/tokens.rs) · 7356 B · 222 lines · `457AB525B55A`

### engine/crates/document-tidy

- **S** [engine/crates/document-tidy/Cargo.toml](../../engine/crates/document-tidy/Cargo.toml) · 446 B · 11 lines · `66CFB90B16C7`
- **S** [engine/crates/document-tidy/src/collections.rs](../../engine/crates/document-tidy/src/collections.rs) · 28495 B · 754 lines · `82FB71513AD0` · LIFE
- **S** [engine/crates/document-tidy/src/dhash.rs](../../engine/crates/document-tidy/src/dhash.rs) · 4995 B · 128 lines · `07D77705739F` · LIFE
- **S** [engine/crates/document-tidy/src/identical.rs](../../engine/crates/document-tidy/src/identical.rs) · 12011 B · 317 lines · `6F73030ABC14` · LIFE
- **S** [engine/crates/document-tidy/src/lib.rs](../../engine/crates/document-tidy/src/lib.rs) · 2092 B · 57 lines · `461629F8863D`
- **S** [engine/crates/document-tidy/src/sensitive.rs](../../engine/crates/document-tidy/src/sensitive.rs) · 12376 B · 329 lines · `60586BC657F8`
- **S** [engine/crates/document-tidy/src/simhash.rs](../../engine/crates/document-tidy/src/simhash.rs) · 7199 B · 173 lines · `4FFAB9F36A74` · LIFE
- **T** [engine/crates/document-tidy/src/similar.rs](../../engine/crates/document-tidy/src/similar.rs) · 9167 B · 257 lines · `608568EC9ED5` · LIFE
- **S** [engine/crates/document-tidy/src/xxh64.rs](../../engine/crates/document-tidy/src/xxh64.rs) · 5441 B · 173 lines · `D00864953CD7`

### engine/crates/fily-inference

- **T** [engine/crates/fily-inference/Cargo.toml](../../engine/crates/fily-inference/Cargo.toml) · 397 B · 13 lines · `9D43FC4BF027`
- **T** [engine/crates/fily-inference/src/android.rs](../../engine/crates/fily-inference/src/android.rs) · 11436 B · 312 lines · `F4237C095B3D` · CONC
- **S** [engine/crates/fily-inference/src/lib.rs](../../engine/crates/fily-inference/src/lib.rs) · 1810 B · 61 lines · `2F81780106E1`
- **S** [engine/crates/fily-inference/src/reuse.rs](../../engine/crates/fily-inference/src/reuse.rs) · 1816 B · 51 lines · `D5B16D76FAF6`

### engine/crates/xfiles-mcp

- **S** [engine/crates/xfiles-mcp/Cargo.toml](../../engine/crates/xfiles-mcp/Cargo.toml) · 744 B · 26 lines · `EECA1AED5541`
- **S** [engine/crates/xfiles-mcp/README.md](../../engine/crates/xfiles-mcp/README.md) · 4116 B · 97 lines · `C5312F998A68`
- **S** [engine/crates/xfiles-mcp/src/config.rs](../../engine/crates/xfiles-mcp/src/config.rs) · 4855 B · 139 lines · `BF96DFFB2793` · LIFE
- **T** [engine/crates/xfiles-mcp/src/document.rs](../../engine/crates/xfiles-mcp/src/document.rs) · 8081 B · 223 lines · `F1CCDF28B789` · LIFE
- **S** [engine/crates/xfiles-mcp/src/error.rs](../../engine/crates/xfiles-mcp/src/error.rs) · 1496 B · 52 lines · `8CDD77F351B7`
- **T** [engine/crates/xfiles-mcp/src/jobs.rs](../../engine/crates/xfiles-mcp/src/jobs.rs) · 2819 B · 91 lines · `967826E5C535` · CONC
- **T** [engine/crates/xfiles-mcp/src/main.rs](../../engine/crates/xfiles-mcp/src/main.rs) · 2022 B · 70 lines · `184B4B248980`
- **T** [engine/crates/xfiles-mcp/src/output.rs](../../engine/crates/xfiles-mcp/src/output.rs) · 4648 B · 143 lines · `04784A39A1F4` · LIFE,TRUST
- **T** [engine/crates/xfiles-mcp/src/sandbox.rs](../../engine/crates/xfiles-mcp/src/sandbox.rs) · 11069 B · 283 lines · `9FF0B672FE41` · LIFE
- **S** [engine/crates/xfiles-mcp/src/server.rs](../../engine/crates/xfiles-mcp/src/server.rs) · 8197 B · 164 lines · `81D5D0BB7ED1`
- **T** [engine/crates/xfiles-mcp/src/services.rs](../../engine/crates/xfiles-mcp/src/services.rs) · 1390 B · 42 lines · `F5FCE7606884`
- **S** [engine/crates/xfiles-mcp/src/text.rs](../../engine/crates/xfiles-mcp/src/text.rs) · 9601 B · 267 lines · `59B6AA2FA70E` · LIFE
- **T** [engine/crates/xfiles-mcp/src/tools/archive.rs](../../engine/crates/xfiles-mcp/src/tools/archive.rs) · 4424 B · 116 lines · `816916E5E285`
- **S** [engine/crates/xfiles-mcp/src/tools/mod.rs](../../engine/crates/xfiles-mcp/src/tools/mod.rs) · 152 B · 6 lines · `3FFEA22F5E93`
- **S** [engine/crates/xfiles-mcp/src/tools/office.rs](../../engine/crates/xfiles-mcp/src/tools/office.rs) · 5016 B · 135 lines · `BF0124A699F2`
- **T** [engine/crates/xfiles-mcp/src/tools/pdf.rs](../../engine/crates/xfiles-mcp/src/tools/pdf.rs) · 19970 B · 515 lines · `6AFAD3BDA561`
- **S** [engine/crates/xfiles-mcp/src/tools/read.rs](../../engine/crates/xfiles-mcp/src/tools/read.rs) · 6284 B · 160 lines · `CFF83FD6B499`
- **S** [engine/crates/xfiles-mcp/src/validate.rs](../../engine/crates/xfiles-mcp/src/validate.rs) · 6110 B · 174 lines · `DD0C35BE0422` · LIFE
- **S** [engine/crates/xfiles-mcp/tests/stdio_protocol.rs](../../engine/crates/xfiles-mcp/tests/stdio_protocol.rs) · 5266 B · 127 lines · `6A707B83BEE2` · LIFE

### feature/assistant

- **T** [feature/assistant/build.gradle.kts](../../feature/assistant/build.gradle.kts) · 1052 B · 37 lines · `E96D5716F724`
- **T** [feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/AndroidModelDownloads.kt](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/AndroidModelDownloads.kt) · 8778 B · 183 lines · `A45EEEC7673E` · CONC,LIFE,TRUST
- **S** [feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/AssistantAccessModule.kt](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/AssistantAccessModule.kt) · 1661 B · 44 lines · `8248C52FE07D`
- **S** [feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/AssistantModule.kt](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/AssistantModule.kt) · 627 B · 18 lines · `8B79B138895C`
- **S** [feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/FilyStage.kt](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/FilyStage.kt) · 3478 B · 76 lines · `029F4E18DECC`
- **T** [feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/FilyViewModel.kt](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/FilyViewModel.kt) · 8779 B · 211 lines · `50E9CDD0CC85` · CONC,LIFE,TRUST
- **S** [feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/SpeechLanguages.kt](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/SpeechLanguages.kt) · 4204 B · 93 lines · `F834F60D40C7` · CONC,LIFE,TRUST
- **S** [feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/SpeechModels.kt](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/SpeechModels.kt) · 767 B · 16 lines · `D8C8DB12E1C0`
- **T** [feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/SpeechRecognition.kt](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/SpeechRecognition.kt) · 13770 B · 296 lines · `B97D9CD2BD97` · LIFE,TRUST
- **T** [feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/TextToSpeech.kt](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/TextToSpeech.kt) · 2340 B · 66 lines · `3AA6104BC375`
- **S** [feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/VoiceAssistantSheet.kt](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/VoiceAssistantSheet.kt) · 20906 B · 439 lines · `CD4F8565633C` · CONC

### feature/editor

- **T** [feature/editor/build.gradle.kts](../../feature/editor/build.gradle.kts) · 1005 B · 36 lines · `51ABC3075044`
- **S** [feature/editor/src/main/kotlin/com/xfiles/feature/editor/TextCodec.kt](../../feature/editor/src/main/kotlin/com/xfiles/feature/editor/TextCodec.kt) · 6963 B · 169 lines · `881BA1745E76` · LIFE
- **S** [feature/editor/src/main/kotlin/com/xfiles/feature/editor/TextEditorScreen.kt](../../feature/editor/src/main/kotlin/com/xfiles/feature/editor/TextEditorScreen.kt) · 8838 B · 165 lines · `D7132CC00823` · LIFE
- **S** [feature/editor/src/main/kotlin/com/xfiles/feature/editor/TextEditorViewModel.kt](../../feature/editor/src/main/kotlin/com/xfiles/feature/editor/TextEditorViewModel.kt) · 8918 B · 204 lines · `5B3C59D41145` · CONC,LIFE,TRUST

### feature/library

- **T** [feature/library/build.gradle.kts](../../feature/library/build.gradle.kts) · 1050 B · 37 lines · `DA0F24B102B6`
- **S** [feature/library/src/main/kotlin/com/xfiles/feature/library/AboutContent.kt](../../feature/library/src/main/kotlin/com/xfiles/feature/library/AboutContent.kt) · 5184 B · 74 lines · `7BA76C634894`
- **S** [feature/library/src/main/kotlin/com/xfiles/feature/library/AboutIcons.kt](../../feature/library/src/main/kotlin/com/xfiles/feature/library/AboutIcons.kt) · 682 B · 19 lines · `78315A276804`
- **S** [feature/library/src/main/kotlin/com/xfiles/feature/library/AboutScreen.kt](../../feature/library/src/main/kotlin/com/xfiles/feature/library/AboutScreen.kt) · 20482 B · 455 lines · `DD590AB86E20` · CONC,LIFE
- **S** [feature/library/src/main/kotlin/com/xfiles/feature/library/AboutSheets.kt](../../feature/library/src/main/kotlin/com/xfiles/feature/library/AboutSheets.kt) · 4776 B · 101 lines · `69809E4A78AB`
- **S** [feature/library/src/main/kotlin/com/xfiles/feature/library/AboutUpcoming.kt](../../feature/library/src/main/kotlin/com/xfiles/feature/library/AboutUpcoming.kt) · 3963 B · 99 lines · `976950BADC09`
- **S** [feature/library/src/main/kotlin/com/xfiles/feature/library/CloudCrashReportingCard.kt](../../feature/library/src/main/kotlin/com/xfiles/feature/library/CloudCrashReportingCard.kt) · 4537 B · 96 lines · `CF662DDAC28F` · CONC,LIFE
- **S** [feature/library/src/main/kotlin/com/xfiles/feature/library/FeedbackSheet.kt](../../feature/library/src/main/kotlin/com/xfiles/feature/library/FeedbackSheet.kt) · 10282 B · 223 lines · `D22D7F8BD47B`
- **T** [feature/library/src/main/kotlin/com/xfiles/feature/library/FeedbackViewModel.kt](../../feature/library/src/main/kotlin/com/xfiles/feature/library/FeedbackViewModel.kt) · 5755 B · 144 lines · `72FF6E810331` · CONC
- **S** [feature/library/src/main/kotlin/com/xfiles/feature/library/FilyAbout.kt](../../feature/library/src/main/kotlin/com/xfiles/feature/library/FilyAbout.kt) · 7394 B · 166 lines · `B1E86C9751FC`
- **S** [feature/library/src/main/kotlin/com/xfiles/feature/library/LandscapeLibraryRail.kt](../../feature/library/src/main/kotlin/com/xfiles/feature/library/LandscapeLibraryRail.kt) · 4094 B · 86 lines · `BF75DC89CD95`
- **S** [feature/library/src/main/kotlin/com/xfiles/feature/library/LibraryChrome.kt](../../feature/library/src/main/kotlin/com/xfiles/feature/library/LibraryChrome.kt) · 15296 B · 358 lines · `5404E67367BB`
- **S** [feature/library/src/main/kotlin/com/xfiles/feature/library/LibraryRows.kt](../../feature/library/src/main/kotlin/com/xfiles/feature/library/LibraryRows.kt) · 16174 B · 366 lines · `27B6F843ED6B`
- **S** [feature/library/src/main/kotlin/com/xfiles/feature/library/LibraryScreen.kt](../../feature/library/src/main/kotlin/com/xfiles/feature/library/LibraryScreen.kt) · 20736 B · 446 lines · `D6BF8FE886F7`
- **S** [feature/library/src/main/kotlin/com/xfiles/feature/library/LibraryThumbnails.kt](../../feature/library/src/main/kotlin/com/xfiles/feature/library/LibraryThumbnails.kt) · 5524 B · 127 lines · `6CF05B5D61A6` · CONC,LIFE
- **S** [feature/library/src/main/kotlin/com/xfiles/feature/library/LibraryViewModel.kt](../../feature/library/src/main/kotlin/com/xfiles/feature/library/LibraryViewModel.kt) · 9166 B · 230 lines · `ACE9238CD7F2` · CONC,LIFE,TRUST
- **S** [feature/library/src/main/kotlin/com/xfiles/feature/library/ToolsSheet.kt](../../feature/library/src/main/kotlin/com/xfiles/feature/library/ToolsSheet.kt) · 3906 B · 80 lines · `424723B1B64C`

### feature/reader

- **T** [feature/reader/build.gradle.kts](../../feature/reader/build.gradle.kts) · 1301 B · 42 lines · `20397B8F71E6`
- **S** [feature/reader/src/main/assets/doc-fonts/Caladea-OFL.txt](../../feature/reader/src/main/assets/doc-fonts/Caladea-OFL.txt) · 4393 B · 93 lines · `CCDAB61D371D`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/AnimatedPicture.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/AnimatedPicture.kt) · 1867 B · 43 lines · `5B95AE58BDF5`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/ArchiveBrowser.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/ArchiveBrowser.kt) · 14244 B · 324 lines · `52772AEF525A`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/ArchiveViewModel.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/ArchiveViewModel.kt) · 13849 B · 351 lines · `CB14F3FF7831` · ABI,CONC,LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookBlocks.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookBlocks.kt) · 14678 B · 337 lines · `C7756077FE6C`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookChrome.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookChrome.kt) · 9821 B · 240 lines · `A167DA24017A`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookLayout.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookLayout.kt) · 9926 B · 211 lines · `250F64D7C54C`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookLinkSheet.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookLinkSheet.kt) · 609 B · 21 lines · `C8737280E993`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookNoteSheets.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookNoteSheets.kt) · 7129 B · 168 lines · `BA0D25BD5ADD`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookPagedMode.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookPagedMode.kt) · 8752 B · 229 lines · `6E91D65B4C3A` · CONC
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookPictureViewer.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookPictureViewer.kt) · 2655 B · 67 lines · `754C89A88C7B`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookPreview.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookPreview.kt) · 7015 B · 172 lines · `62DEA69834E3`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookReaderBody.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookReaderBody.kt) · 9002 B · 214 lines · `595641B1B51C`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookScrollMode.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookScrollMode.kt) · 4084 B · 100 lines · `F07F363B1CA3`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookSearchSheet.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookSearchSheet.kt) · 3467 B · 87 lines · `42BCCE1B1AD0`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookSelection.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookSelection.kt) · 22472 B · 556 lines · `D77AAA7AD5E8`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookSelectionLayer.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookSelectionLayer.kt) · 3597 B · 84 lines · `ECFEF6DFD043`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookSettingsSheet.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookSettingsSheet.kt) · 8476 B · 211 lines · `756A61FA9957`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookSheetFrame.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookSheetFrame.kt) · 2791 B · 73 lines · `DD6B69AF9C4C`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookSheetHost.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookSheetHost.kt) · 4357 B · 117 lines · `29DDA14A388E`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookSheets.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookSheets.kt) · 9329 B · 220 lines · `6AF6109E3134`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookStore.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookStore.kt) · 2438 B · 67 lines · `39474702395A` · CONC
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookTapZones.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookTapZones.kt) · 1907 B · 43 lines · `95810D5E1346`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookViewModel.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/BookViewModel.kt) · 13390 B · 335 lines · `69AE527915CF` · CONC
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/CodeHighlight.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/CodeHighlight.kt) · 15366 B · 320 lines · `6B8BBB48F3A2` · ABI,CONC,LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/CodePreview.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/CodePreview.kt) · 7061 B · 140 lines · `2D307B5371C0`
- **T** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/DocumentAnalysisViewModel.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/DocumentAnalysisViewModel.kt) · 18629 B · 419 lines · `9EE12F13D52D` · CONC,LIFE,TRUST
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/DocumentPrintController.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/DocumentPrintController.kt) · 2634 B · 66 lines · `DF76FFC3D273` · CONC,LIFE,TRUST
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/BookmarkPlaceUseCase.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/BookmarkPlaceUseCase.kt) · 1457 B · 33 lines · `FF4232BFDE90`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/BookProgressUseCase.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/BookProgressUseCase.kt) · 2548 B · 50 lines · `9C367D6BC11C`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/BookSearchUseCase.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/BookSearchUseCase.kt) · 2027 B · 49 lines · `F09AAF63D170`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/DocumentSummaryUseCase.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/DocumentSummaryUseCase.kt) · 792 B · 20 lines · `0F66CF281341`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/HtmlLinks.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/HtmlLinks.kt) · 3869 B · 85 lines · `AB1274197A93`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/LoadSlidePictureUseCase.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/LoadSlidePictureUseCase.kt) · 774 B · 18 lines · `5E5636BE6A1D`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/MediaMetadataUseCase.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/MediaMetadataUseCase.kt) · 2289 B · 53 lines · `68BF6C1833BB` · LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/MediaSubtitleFilesUseCase.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/MediaSubtitleFilesUseCase.kt) · 3367 B · 74 lines · `9B9C12C959A4` · LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/MediaTrackFormatting.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/MediaTrackFormatting.kt) · 3598 B · 77 lines · `2E665616AF32`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/MediaTrackModels.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/MediaTrackModels.kt) · 1424 B · 45 lines · `DFB410676EE3`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/MediaTracksUseCase.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/MediaTracksUseCase.kt) · 3509 B · 73 lines · `7397C26D7235`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/OpenDocumentUseCase.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/OpenDocumentUseCase.kt) · 1634 B · 34 lines · `20A0DA2EEAE4` · LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/PdfAnnotateUseCase.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/PdfAnnotateUseCase.kt) · 634 B · 18 lines · `A482EAC34A35`
- **T** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/PdfPagesUseCase.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/PdfPagesUseCase.kt) · 3987 B · 99 lines · `7B42D85703ED` · CONC,LIFE,TRUST
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/PdfSearchUseCase.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/PdfSearchUseCase.kt) · 846 B · 20 lines · `55A067A97B75`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/ReadPdfSignaturesUseCase.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/ReadPdfSignaturesUseCase.kt) · 1037 B · 25 lines · `335533724ECE` · ABI,LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/ResolveSlideLinkUseCase.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/ResolveSlideLinkUseCase.kt) · 1950 B · 46 lines · `869322F74121`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/ResolveSlideMediaUseCase.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/ResolveSlideMediaUseCase.kt) · 2304 B · 54 lines · `36702974AED7`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/SaveToDownloadsUseCase.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/SaveToDownloadsUseCase.kt) · 5570 B · 126 lines · `88A35EF3E9E5` · LIFE,TRUST
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/SensitiveScanUseCase.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/SensitiveScanUseCase.kt) · 1880 B · 47 lines · `FFBCD58CF610` · LIFE,TRUST
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/GalleryCatalog.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/GalleryCatalog.kt) · 1347 B · 31 lines · `873594BFD823`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/GalleryController.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/GalleryController.kt) · 7743 B · 162 lines · `D9D0B566513D` · CONC,LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/GalleryImages.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/GalleryImages.kt) · 5914 B · 129 lines · `9F063040AD5C` · CONC
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/GalleryPager.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/GalleryPager.kt) · 7324 B · 175 lines · `8721B9707423` · LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/GallerySession.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/GallerySession.kt) · 5293 B · 121 lines · `1C8AE2849AD6`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/GallerySortStore.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/GallerySortStore.kt) · 2055 B · 50 lines · `A1732692F0D4` · CONC
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/GlanceItems.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/GlanceItems.kt) · 4963 B · 116 lines · `DF5937A27DCD` · LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/HtmlPreview.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/HtmlPreview.kt) · 8461 B · 176 lines · `27F4E1AA446B` · LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/JsonTreePreview.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/JsonTreePreview.kt) · 9903 B · 215 lines · `4DA9E1D5533F`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaAudioSheet.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaAudioSheet.kt) · 2642 B · 61 lines · `B81F7B7D8D99`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaAudioStage.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaAudioStage.kt) · 6558 B · 168 lines · `171EA2FFE8FD`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaControls.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaControls.kt) · 2861 B · 77 lines · `E2CC861D6AB9`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaDetails.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaDetails.kt) · 3700 B · 78 lines · `340B3EE6D4AC`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaDevice.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaDevice.kt) · 5012 B · 126 lines · `37518848CCFE` · LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaGesture.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaGesture.kt) · 8335 B · 198 lines · `0DF3BFD5884A`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaGestureOverlay.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaGestureOverlay.kt) · 3850 B · 89 lines · `811B59DAF18B`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaHaptics.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaHaptics.kt) · 377 B · 11 lines · `2121EC2AF350`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaIcons.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaIcons.kt) · 2036 B · 33 lines · `35124B0CFC93`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaPip.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaPip.kt) · 6744 B · 167 lines · `AA77EB144CC0` · TRUST
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaPlaybackSheet.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaPlaybackSheet.kt) · 3621 B · 82 lines · `4FAED63E3963`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaPlayerContent.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaPlayerContent.kt) · 11773 B · 249 lines · `27B1B5212821`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaPlayerEvent.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaPlayerEvent.kt) · 2109 B · 47 lines · `6EDFD3CE9593`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaPlayerState.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaPlayerState.kt) · 2959 B · 67 lines · `155FD8675B7F`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaPlayerViewModel.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaPlayerViewModel.kt) · 14792 B · 356 lines · `E11D28D55C1C` · CONC
- **T** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaScreen.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaScreen.kt) · 4157 B · 95 lines · `8EAC3620D65A`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaSheetRows.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaSheetRows.kt) · 3482 B · 91 lines · `6DCD1F8159C0`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaSheets.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaSheets.kt) · 1704 B · 45 lines · `567E0D4D9005`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaSubtitleSheet.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaSubtitleSheet.kt) · 8104 B · 210 lines · `929AE3BBA81D`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaTopBar.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaTopBar.kt) · 6355 B · 155 lines · `6470F459A615`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaTransport.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/MediaTransport.kt) · 4617 B · 111 lines · `C2A7C15BDD61`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/OfficeFindReplacePanel.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/OfficeFindReplacePanel.kt) · 9358 B · 219 lines · `AB11759A0D59`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/OfficeFindReplaceViewModel.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/OfficeFindReplaceViewModel.kt) · 12877 B · 331 lines · `A30F93524D33` · CONC,LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/PageStyle.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/PageStyle.kt) · 5507 B · 130 lines · `ACF8B33477CB`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/PdfContinuousView.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/PdfContinuousView.kt) · 12932 B · 287 lines · `CB4C59472021` · CONC
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/PdfFindBar.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/PdfFindBar.kt) · 2324 B · 52 lines · `F7EEDE7763C7`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/PdfFindController.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/PdfFindController.kt) · 2251 B · 61 lines · `0B1452A6AA89` · CONC,LIFE,TRUST
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/PdfMarkupCanvas.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/PdfMarkupCanvas.kt) · 9909 B · 231 lines · `DDA3DE6C5B70`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/PdfMarkupController.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/PdfMarkupController.kt) · 4134 B · 103 lines · `8D5E94C5DC53` · CONC,LIFE,TRUST
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/PdfNavigation.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/PdfNavigation.kt) · 13101 B · 296 lines · `4DF7DC3FC7F0`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/PdfSignatures.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/PdfSignatures.kt) · 6744 B · 148 lines · `907C6E297B1B`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/PdfTextSelection.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/PdfTextSelection.kt) · 16480 B · 375 lines · `94535EA9B399`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/PdfToolbar.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/PdfToolbar.kt) · 7425 B · 185 lines · `20FF4C5D2F1F`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/PictureTiles.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/PictureTiles.kt) · 13920 B · 317 lines · `15E8AFC2E644` · CONC,LIFE,TRUST
- **T** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/PresentationPreview.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/PresentationPreview.kt) · 37401 B · 838 lines · `3F203ED91B1C` · CONC,LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/Printing.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/Printing.kt) · 2081 B · 55 lines · `BD3D18CC5A7B`
- **T** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderActionFab.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderActionFab.kt) · 9484 B · 218 lines · `815926D43103`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderBody.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderBody.kt) · 10893 B · 239 lines · `AF77F4875822` · LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderDialogs.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderDialogs.kt) · 4973 B · 107 lines · `8A1F451F365A` · LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderImage.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderImage.kt) · 13687 B · 273 lines · `7CCCDFEABE23` · CONC
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderModels.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderModels.kt) · 1860 B · 54 lines · `553D840C89C0`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderPdfContent.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderPdfContent.kt) · 3303 B · 82 lines · `06A02A9EEFD7`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderPrefs.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderPrefs.kt) · 2284 B · 61 lines · `2C4D7BA61D74` · LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderScreen.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderScreen.kt) · 22987 B · 443 lines · `739FA55E8DD9` · CONC
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderScroll.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderScroll.kt) · 4605 B · 116 lines · `80252FD4E005`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderTopBar.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderTopBar.kt) · 1540 B · 46 lines · `2861CA31FFBD`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderUiState.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderUiState.kt) · 4862 B · 83 lines · `D3C8AA771EF2`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderUiViewModel.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReaderUiViewModel.kt) · 733 B · 20 lines · `2756C32C5183`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReadingMode.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/ReadingMode.kt) · 533 B · 15 lines · `5226FEF74FD9`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/SaveToDownloadsViewModel.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/SaveToDownloadsViewModel.kt) · 3053 B · 83 lines · `14E67E0E4165` · CONC
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/SensitiveFileHint.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/SensitiveFileHint.kt) · 4790 B · 108 lines · `64C07FDE0A54`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/SheetGridRenderer.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/SheetGridRenderer.kt) · 2968 B · 85 lines · `2AA80D3DCC12` · LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/SheetGridSupplied.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/SheetGridSupplied.kt) · 3868 B · 95 lines · `21EDFFEC43D0`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/SlideCanvas.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/SlideCanvas.kt) · 14497 B · 329 lines · `01D9EC7AD0AB`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/SlideMediaBadge.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/SlideMediaBadge.kt) · 1471 B · 39 lines · `7B3AA2C193CC`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/SlideMediaPlayer.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/SlideMediaPlayer.kt) · 4983 B · 119 lines · `64CAD66176AC`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/SlidePictureViewer.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/SlidePictureViewer.kt) · 3786 B · 95 lines · `D5F0AD2BD12F`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/SlidePresentationViewModel.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/SlidePresentationViewModel.kt) · 6046 B · 151 lines · `05B2FEA04547` · CONC,LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/SpreadsheetPreview.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/SpreadsheetPreview.kt) · 20625 B · 477 lines · `3DB66455FCE2` · CONC,LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/StructuredEditSession.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/StructuredEditSession.kt) · 6819 B · 169 lines · `05BBFE5A94D3` · CONC,LIFE
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/StructuredPreview.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/StructuredPreview.kt) · 11615 B · 251 lines · `CDC94567F5A6` · CONC
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/StructuredTable.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/StructuredTable.kt) · 4508 B · 100 lines · `BA26B56536C3`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/Subtitles.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/Subtitles.kt) · 8092 B · 191 lines · `2C043CD43E29` · LIFE,TRUST
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/SubtitleStyleEditor.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/SubtitleStyleEditor.kt) · 8280 B · 204 lines · `B1831FB78812`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/SvgPreview.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/SvgPreview.kt) · 1061 B · 19 lines · `6BD451474F04`
- **S** [feature/reader/src/main/kotlin/com/xfiles/feature/reader/TextPreview.kt](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/TextPreview.kt) · 7287 B · 153 lines · `DBF2C6BBCE81`
- **I** [feature/reader/src/main/res/font/caladea_bold.ttf](../../feature/reader/src/main/res/font/caladea_bold.ttf) · 84492 B · — lines · `AE3CB2DCBC92`
- **I** [feature/reader/src/main/res/font/caladea_bolditalic.ttf](../../feature/reader/src/main/res/font/caladea_bolditalic.ttf) · 83356 B · — lines · `CCABAA7B7E2F`
- **I** [feature/reader/src/main/res/font/caladea_italic.ttf](../../feature/reader/src/main/res/font/caladea_italic.ttf) · 83780 B · — lines · `4359A8E24F74`
- **I** [feature/reader/src/main/res/font/caladea_regular.ttf](../../feature/reader/src/main/res/font/caladea_regular.ttf) · 81600 B · — lines · `F1E899278B7B`

### feature/tidy

- **T** [feature/tidy/build.gradle.kts](../../feature/tidy/build.gradle.kts) · 1003 B · 36 lines · `9DA52AC1E3F4`
- **S** [feature/tidy/src/main/kotlin/com/xfiles/feature/tidy/TidyIcons.kt](../../feature/tidy/src/main/kotlin/com/xfiles/feature/tidy/TidyIcons.kt) · 1187 B · 24 lines · `3E7BC2F3DE3D`
- **S** [feature/tidy/src/main/kotlin/com/xfiles/feature/tidy/TidyUpScreen.kt](../../feature/tidy/src/main/kotlin/com/xfiles/feature/tidy/TidyUpScreen.kt) · 15960 B · 323 lines · `F81A1FC09766`
- **S** [feature/tidy/src/main/kotlin/com/xfiles/feature/tidy/TidyViewModel.kt](../../feature/tidy/src/main/kotlin/com/xfiles/feature/tidy/TidyViewModel.kt) · 2773 B · 78 lines · `8DE7709D4AE7` · CONC

### feature/tools

- **T** [feature/tools/build.gradle.kts](../../feature/tools/build.gradle.kts) · 1248 B · 42 lines · `CB4DEDAF80D1`
- **T** [feature/tools/src/main/AndroidManifest.xml](../../feature/tools/src/main/AndroidManifest.xml) · 363 B · 6 lines · `02E83E771900`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/ArchiveToolScreen.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/ArchiveToolScreen.kt) · 7662 B · 183 lines · `26A4F95AE341`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/ArchiveToolSections.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/ArchiveToolSections.kt) · 7322 B · 168 lines · `3DD1FEDE30F1`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/ArchiveToolViewModel.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/ArchiveToolViewModel.kt) · 7035 B · 146 lines · `FC95B47D5965` · CONC,LIFE
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/ConvertPdfOutputs.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/ConvertPdfOutputs.kt) · 6838 B · 153 lines · `F75ED9BF3A6E`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/ConvertPictures.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/ConvertPictures.kt) · 10335 B · 226 lines · `1D40F4A78AFF` · TRUST
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/ConvertScreen.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/ConvertScreen.kt) · 13156 B · 295 lines · `A63F3569AD9A` · LIFE
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/ConvertText.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/ConvertText.kt) · 8408 B · 195 lines · `2AA58429536D`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/ConvertViewModel.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/ConvertViewModel.kt) · 20524 B · 449 lines · `DA71F8CE5017` · CONC,LIFE,TRUST
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/domain/ArchiveUseCases.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/domain/ArchiveUseCases.kt) · 1536 B · 43 lines · `7136F5257FDF`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfActionScreen.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfActionScreen.kt) · 11613 B · 277 lines · `31BBF625877C` · LIFE
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfActionSections.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfActionSections.kt) · 9183 B · 217 lines · `0A251B271B53`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfCompressScreen.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfCompressScreen.kt) · 8575 B · 191 lines · `0B7B0AE00F6C`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfCompressViewModel.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfCompressViewModel.kt) · 1872 B · 53 lines · `11744EF0112D`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfPaperSection.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfPaperSection.kt) · 5764 B · 151 lines · `F0F69DB79CDA`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfReorderGrid.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfReorderGrid.kt) · 8162 B · 188 lines · `50912F062D2D`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfReorderScreen.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfReorderScreen.kt) · 5944 B · 147 lines · `9CE47A349AC7`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfReorderViewModel.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfReorderViewModel.kt) · 2503 B · 71 lines · `1B2DAD009633`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfSignPad.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfSignPad.kt) · 7435 B · 178 lines · `75E280F53473`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfSignScreen.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfSignScreen.kt) · 10238 B · 221 lines · `08DF0E108B28`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfSignStore.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfSignStore.kt) · 1371 B · 38 lines · `EF6714456841` · LIFE
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfSignViewModel.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfSignViewModel.kt) · 5705 B · 151 lines · `297EF38789B9`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfSplitScreen.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfSplitScreen.kt) · 9333 B · 214 lines · `F4ACE2A1CC3E`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfSplitSections.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfSplitSections.kt) · 7811 B · 215 lines · `69C4E6E7FF1B`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfSplitViewModel.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfSplitViewModel.kt) · 3794 B · 100 lines · `3B27239EBE01`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfToolKit.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfToolKit.kt) · 11530 B · 291 lines · `3880E0ECFD56` · LIFE
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfToolsScreen.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfToolsScreen.kt) · 8687 B · 175 lines · `A9248AD266B6` · LIFE
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfToolsViewModel.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfToolsViewModel.kt) · 18051 B · 397 lines · `C8DC64DE6FAE` · CONC,LIFE,TRUST
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfWatermarkPreview.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfWatermarkPreview.kt) · 9197 B · 227 lines · `1F33315038D4`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfWatermarkScreen.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfWatermarkScreen.kt) · 11079 B · 234 lines · `B3F8382CF148`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfWatermarkViewModel.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/PdfWatermarkViewModel.kt) · 1458 B · 40 lines · `4F87B8F8467C`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/ScanCamera.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/ScanCamera.kt) · 13951 B · 288 lines · `78D4B9904438` · CONC
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/ScanReview.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/ScanReview.kt) · 15775 B · 322 lines · `9732FAA6CF57` · LIFE
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/ToolKitBars.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/ToolKitBars.kt) · 5284 B · 132 lines · `5B5068060947`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/ToolKitFiles.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/ToolKitFiles.kt) · 7351 B · 171 lines · `2ACC1103A394`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/ToolKitHeader.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/ToolKitHeader.kt) · 4542 B · 115 lines · `32B2C7D8548C`
- **S** [feature/tools/src/main/kotlin/com/xfiles/feature/tools/ToolKitResult.kt](../../feature/tools/src/main/kotlin/com/xfiles/feature/tools/ToolKitResult.kt) · 5660 B · 130 lines · `F966E0E42C1D`

### ffmpeg

- **T** [ffmpeg/build.gradle.kts](../../ffmpeg/build.gradle.kts) · 3846 B · 88 lines · `BAAE0A15A2D5`
- **T** [ffmpeg/consumer-rules.pro](../../ffmpeg/consumer-rules.pro) · 302 B · 4 lines · `49E3052D6460`
- **T** [ffmpeg/rust/build.rs](../../ffmpeg/rust/build.rs) · 5750 B · 135 lines · `ED778562A387`
- **S** [ffmpeg/rust/Cargo.lock](../../ffmpeg/rust/Cargo.lock) · 11030 B · 419 lines · `E7DD7925A1E6`
- **S** [ffmpeg/rust/Cargo.toml](../../ffmpeg/rust/Cargo.toml) · 708 B · 25 lines · `F65602AB1C73`
- **S** [ffmpeg/rust/src/android_log.rs](../../ffmpeg/rust/src/android_log.rs) · 914 B · 29 lines · `DEDEC21158E7` · ABI
- **T** [ffmpeg/rust/src/bridge.rs](../../ffmpeg/rust/src/bridge.rs) · 11119 B · 297 lines · `115816671E18` · ABI,LIFE
- **T** [ffmpeg/rust/src/decoder.rs](../../ffmpeg/rust/src/decoder.rs) · 14390 B · 374 lines · `BA8EC509070F` · ABI
- **S** [ffmpeg/rust/src/error.rs](../../ffmpeg/rust/src/error.rs) · 1577 B · 45 lines · `7883D5069362`
- **S** [ffmpeg/rust/src/format.rs](../../ffmpeg/rust/src/format.rs) · 2201 B · 68 lines · `5410435B5B4B`
- **S** [ffmpeg/rust/src/lib.rs](../../ffmpeg/rust/src/lib.rs) · 1018 B · 26 lines · `4B233AB457A5`
- **S** [ffmpeg/rust/src/version.rs](../../ffmpeg/rust/src/version.rs) · 683 B · 23 lines · `18C6B08D4C8C`
- **T** [ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder.java](../../ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder.java) · 11711 B · 320 lines · `1F88283BE022`
- **T** [ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.java](../../ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.java) · 6569 B · 170 lines · `BB860123A494`
- **S** [ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegDecoderException.java](../../ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegDecoderException.java) · 1082 B · 32 lines · `9E73968D0802`
- **T** [ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegLibrary.java](../../ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegLibrary.java) · 5054 B · 166 lines · `D7D00601C186` · TRUST
- **S** [ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/package-info.java](../../ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/package-info.java) · 720 B · 19 lines · `1D75D02151FC`

### firebase

- **T** [firebase/firebase.json](../../firebase/firebase.json) · 56 B · 5 lines · `E87125F3EC64`
- **T** [firebase/firestore.rules](../../firebase/firestore.rules) · 1923 B · 42 lines · `01DAA614BDBD` · TRUST
- **S** [firebase/README.md](../../firebase/README.md) · 2260 B · 49 lines · `EDA6C3760A1A`

### gradle

- **S** [gradle/gradle-daemon-jvm.properties](../../gradle/gradle-daemon-jvm.properties) · 1138 B · 12 lines · `6C13AA5BA366`
- **T** [gradle/libs.versions.toml](../../gradle/libs.versions.toml) · 6026 B · 97 lines · `12247556C297`
- **I** [gradle/wrapper/gradle-wrapper.jar](../../gradle/wrapper/gradle-wrapper.jar) · 49154 B · — lines · `621008AFC4F5`
- **T** [gradle/wrapper/gradle-wrapper.properties](../../gradle/wrapper/gradle-wrapper.properties) · 368 B · 10 lines · `F6003845D66C`

### scripts

- **T** [scripts/build-ffmpeg.sh](../../scripts/build-ffmpeg.sh) · 4763 B · 89 lines · `314FEC4E6CF4`

### sharedLogic

- **T** [sharedLogic/build.gradle.kts](../../sharedLogic/build.gradle.kts) · 922 B · 34 lines · `B039128E93B8`
- **S** [sharedLogic/src/androidHostTest/kotlin/com/xfiles/fily/FilyPromptContractTest.kt](../../sharedLogic/src/androidHostTest/kotlin/com/xfiles/fily/FilyPromptContractTest.kt) · 1984 B · 44 lines · `E04363457D01`
- **I** [sharedLogic/src/androidHostTest/resources/fily/edge_v5_row.jsonl](../../sharedLogic/src/androidHostTest/resources/fily/edge_v5_row.jsonl) · 3844 B · — lines · `C0B4AF5D120D`
- **S** [sharedLogic/src/androidHostTest/resources/fily/tool_catalog.json](../../sharedLogic/src/androidHostTest/resources/fily/tool_catalog.json) · 15493 B · 671 lines · `9D18075089DB`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/crash/CloudCrashReporting.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/crash/CloudCrashReporting.kt) · 1128 B · 31 lines · `003955C2CFA4`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/crash/CrashReporting.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/crash/CrashReporting.kt) · 8256 B · 205 lines · `CE70CFA631A1`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/crash/CrashReports.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/crash/CrashReports.kt) · 612 B · 17 lines · `BA7ECA890B39`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/archive/ArchiveTree.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/archive/ArchiveTree.kt) · 7016 B · 167 lines · `4967E9B2E859` · ABI
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/book/BookAnnotations.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/book/BookAnnotations.kt) · 6085 B · 155 lines · `521C550F8C4C` · LIFE
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/book/BookManifest.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/book/BookManifest.kt) · 8045 B · 183 lines · `375EC02634F6` · LIFE
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/book/BookPagination.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/book/BookPagination.kt) · 3547 B · 93 lines · `595A978702D3`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/book/LruCache.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/book/LruCache.kt) · 1018 B · 39 lines · `1ACEE3DBDC40`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/book/ReadingSettings.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/book/ReadingSettings.kt) · 2759 B · 72 lines · `7BC4F4072CE1` · LIFE
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/book/SegmentIndex.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/book/SegmentIndex.kt) · 3757 B · 94 lines · `65D4F0CA2801`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/book/TextSelection.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/book/TextSelection.kt) · 3652 B · 81 lines · `76CEE6480991`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/code/CodeFolding.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/code/CodeFolding.kt) · 8675 B · 168 lines · `92C6B20D09D2`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/code/SourceCode.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/code/SourceCode.kt) · 1032 B · 18 lines · `3A8ADF325333`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/DocumentAnalysis.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/DocumentAnalysis.kt) · 768 B · 19 lines · `58906875B3B4`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/DocumentKind.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/DocumentKind.kt) · 2679 B · 53 lines · `97D908BA378A`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/library/LibraryCategory.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/library/LibraryCategory.kt) · 2167 B · 48 lines · `16D898B238FE`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/PdfCompressQuality.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/PdfCompressQuality.kt) · 616 B · 12 lines · `0CBBF8568C3B`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/PdfMarkup.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/PdfMarkup.kt) · 576 B · 14 lines · `999F5DB09E63`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/PdfPageSelection.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/PdfPageSelection.kt) · 855 B · 21 lines · `A96DF5792E30`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/PdfProtection.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/PdfProtection.kt) · 1922 B · 49 lines · `58711502C85D`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/PdfSignSpec.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/PdfSignSpec.kt) · 3500 B · 95 lines · `3132CF2CAE81`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/PdfSplitPlan.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/PdfSplitPlan.kt) · 2226 B · 49 lines · `7464A749C7B5`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/PdfSplitSpans.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/PdfSplitSpans.kt) · 3315 B · 73 lines · `21B4AEDFB020`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/PdfWatermarkSpec.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/PdfWatermarkSpec.kt) · 1391 B · 41 lines · `CEEBF1466777`
- **T** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/search/FileNameIndex.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/search/FileNameIndex.kt) · 12302 B · 316 lines · `C1A4143A3171`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/structure/Document.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/structure/Document.kt) · 6149 B · 177 lines · `475486449770`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/structure/DocumentError.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/structure/DocumentError.kt) · 1439 B · 35 lines · `01063984D949`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/structure/Slides.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/structure/Slides.kt) · 1931 B · 58 lines · `F906DE2415F5`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/documents/structure/StructuredDocument.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/documents/structure/StructuredDocument.kt) · 2029 B · 46 lines · `F39B5FC88419`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/feedback/Feedback.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/feedback/Feedback.kt) · 2782 B · 79 lines · `4D7431378C42`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/AssistantAccess.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/AssistantAccess.kt) · 3846 B · 95 lines · `B36EEB3B4CAE` · LIFE
- **T** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/FilyGpuGuard.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/FilyGpuGuard.kt) · 4089 B · 93 lines · `A4D17142475F`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/FilyGrammar.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/FilyGrammar.kt) · 1717 B · 33 lines · `9F53FDE4EB52`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/FilyPreferences.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/FilyPreferences.kt) · 354 B · 12 lines · `554B4120F744`
- **T** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/FilyPrompt.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/FilyPrompt.kt) · 4293 B · 79 lines · `FAB937F82718`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/FilyReplies.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/FilyReplies.kt) · 8398 B · 131 lines · `048A69FED182`
- **T** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/FilyRetrieval.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/FilyRetrieval.kt) · 1089 B · 24 lines · `28ED2EA3229D`
- **T** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/FilySession.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/FilySession.kt) · 15888 B · 290 lines · `08A79C15A309`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/FilyToolCatalog.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/FilyToolCatalog.kt) · 10285 B · 4 lines · `958202AFEFFD`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/Grounding.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/Grounding.kt) · 4563 B · 90 lines · `22818312AD24`
- **T** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/ModelDownloads.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/ModelDownloads.kt) · 2052 B · 56 lines · `564FD73916FB`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/Passages.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/Passages.kt) · 10259 B · 185 lines · `0EA441020EDA`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/QuickCommands.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/QuickCommands.kt) · 13388 B · 207 lines · `46E818D3592C`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/ReaderControl.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/ReaderControl.kt) · 3462 B · 82 lines · `14B1C5944C40`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/ResponseParser.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/ResponseParser.kt) · 1994 B · 55 lines · `10AE482A3346` · LIFE
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/SpeechLanguage.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/SpeechLanguage.kt) · 794 B · 20 lines · `F124E3CDC463`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/SpokenNumbers.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/SpokenNumbers.kt) · 582 B · 13 lines · `A26714950943`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/ToolExecutor.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/ToolExecutor.kt) · 25924 B · 491 lines · `2346C1AB72D2` · LIFE
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/fily/ToolSelector.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/fily/ToolSelector.kt) · 4307 B · 62 lines · `3C13EECF391E`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/scan/AutoCapture.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/scan/AutoCapture.kt) · 2163 B · 65 lines · `39A284760948`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/scan/PageOutline.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/scan/PageOutline.kt) · 19586 B · 475 lines · `D514C953AD9F`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/settings/ThemePreference.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/settings/ThemePreference.kt) · 139 B · 7 lines · `08BEE9A9740A`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/tidy/Tidy.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/tidy/Tidy.kt) · 4774 B · 117 lines · `EFD364A657F9`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/tidy/TidyPorts.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/tidy/TidyPorts.kt) · 2929 B · 73 lines · `E49DB1735358`
- **S** [sharedLogic/src/commonMain/kotlin/com/xfiles/tidy/TidyService.kt](../../sharedLogic/src/commonMain/kotlin/com/xfiles/tidy/TidyService.kt) · 17360 B · 409 lines · `F2C4FA3910AA` · CONC,LIFE
- **S** [sharedLogic/src/commonTest/kotlin/com/xfiles/documents/archive/ArchiveTreeTest.kt](../../sharedLogic/src/commonTest/kotlin/com/xfiles/documents/archive/ArchiveTreeTest.kt) · 2982 B · 76 lines · `FD0EFF7CF99E`
- **S** [sharedLogic/src/commonTest/kotlin/com/xfiles/documents/book/BookAnnotationsTest.kt](../../sharedLogic/src/commonTest/kotlin/com/xfiles/documents/book/BookAnnotationsTest.kt) · 4379 B · 95 lines · `007089D3CFF3`
- **S** [sharedLogic/src/commonTest/kotlin/com/xfiles/documents/book/BookManifestTest.kt](../../sharedLogic/src/commonTest/kotlin/com/xfiles/documents/book/BookManifestTest.kt) · 4043 B · 91 lines · `A3D3584542A2`
- **S** [sharedLogic/src/commonTest/kotlin/com/xfiles/documents/book/BookPaginationTest.kt](../../sharedLogic/src/commonTest/kotlin/com/xfiles/documents/book/BookPaginationTest.kt) · 2587 B · 70 lines · `47E27895D06E`
- **S** [sharedLogic/src/commonTest/kotlin/com/xfiles/documents/book/ReadingSettingsAndSelectionTest.kt](../../sharedLogic/src/commonTest/kotlin/com/xfiles/documents/book/ReadingSettingsAndSelectionTest.kt) · 2648 B · 64 lines · `1C2BB8476235`
- **S** [sharedLogic/src/commonTest/kotlin/com/xfiles/documents/book/SegmentIndexTest.kt](../../sharedLogic/src/commonTest/kotlin/com/xfiles/documents/book/SegmentIndexTest.kt) · 2032 B · 53 lines · `1C2C82C1EF2A`
- **S** [sharedLogic/src/commonTest/kotlin/com/xfiles/documents/book/TextSelectionSpansTest.kt](../../sharedLogic/src/commonTest/kotlin/com/xfiles/documents/book/TextSelectionSpansTest.kt) · 2100 B · 61 lines · `8F0BBBAAD2C3`
- **S** [sharedLogic/src/commonTest/kotlin/com/xfiles/documents/DocumentKindTest.kt](../../sharedLogic/src/commonTest/kotlin/com/xfiles/documents/DocumentKindTest.kt) · 1308 B · 30 lines · `1EF2EA8A077F`
- **S** [sharedLogic/src/commonTest/kotlin/com/xfiles/documents/PdfPageSelectionTest.kt](../../sharedLogic/src/commonTest/kotlin/com/xfiles/documents/PdfPageSelectionTest.kt) · 595 B · 20 lines · `0780C427F8E1`
- **S** [sharedLogic/src/commonTest/kotlin/com/xfiles/documents/PdfSplitPlanTest.kt](../../sharedLogic/src/commonTest/kotlin/com/xfiles/documents/PdfSplitPlanTest.kt) · 2302 B · 63 lines · `3516EB7B7056`
- **S** [sharedLogic/src/commonTest/kotlin/com/xfiles/documents/search/FileNameIndexTest.kt](../../sharedLogic/src/commonTest/kotlin/com/xfiles/documents/search/FileNameIndexTest.kt) · 3744 B · 90 lines · `2D28B2E1CFD7`
- **S** [sharedLogic/src/commonTest/kotlin/com/xfiles/fily/FilyGpuGuardTest.kt](../../sharedLogic/src/commonTest/kotlin/com/xfiles/fily/FilyGpuGuardTest.kt) · 4437 B · 124 lines · `0FB666D1C981`
- **S** [sharedLogic/src/commonTest/kotlin/com/xfiles/fily/FilyPassagesTest.kt](../../sharedLogic/src/commonTest/kotlin/com/xfiles/fily/FilyPassagesTest.kt) · 3933 B · 82 lines · `503D01AEC652`
- **S** [sharedLogic/src/commonTest/kotlin/com/xfiles/fily/QuickScrollTest.kt](../../sharedLogic/src/commonTest/kotlin/com/xfiles/fily/QuickScrollTest.kt) · 1505 B · 39 lines · `379C6B545D45`
- **S** [sharedLogic/src/commonTest/kotlin/com/xfiles/fily/ResponseParserTest.kt](../../sharedLogic/src/commonTest/kotlin/com/xfiles/fily/ResponseParserTest.kt) · 641 B · 18 lines · `C26705F7E50C`
- **S** [sharedLogic/src/commonTest/kotlin/com/xfiles/fily/ToolExecutorTest.kt](../../sharedLogic/src/commonTest/kotlin/com/xfiles/fily/ToolExecutorTest.kt) · 2998 B · 66 lines · `ECB5BE041363`
- **S** [sharedLogic/src/commonTest/kotlin/com/xfiles/tidy/TidyReportTest.kt](../../sharedLogic/src/commonTest/kotlin/com/xfiles/tidy/TidyReportTest.kt) · 999 B · 27 lines · `DDA60BC19091`

### training

- **T** [training/bench_quantized.py](../../training/bench_quantized.py) · 1774 B · 38 lines · `D9660697E91C`
- **S** [training/build_app_v8.py](../../training/build_app_v8.py) · 28353 B · 489 lines · `4CF98D3D49DB`
- **S** [training/build_dataset.py](../../training/build_dataset.py) · 26637 B · 315 lines · `324CF00DE4C8`
- **S** [training/build_domain_dataset.py](../../training/build_domain_dataset.py) · 12303 B · 128 lines · `E61A2358DF6D`
- **S** [training/build_edge_curriculum.py](../../training/build_edge_curriculum.py) · 23465 B · 269 lines · `BA3729C24F8A`
- **S** [training/build_hard_dataset.py](../../training/build_hard_dataset.py) · 11266 B · 134 lines · `0BEA1A16A50A`
- **S** [training/build_screen_control.py](../../training/build_screen_control.py) · 13218 B · 218 lines · `65D52273E9EA`
- **S** [training/calibrate_quantization.py](../../training/calibrate_quantization.py) · 2119 B · 40 lines · `05D04C318A31`
- **S** [training/catalog.py](../../training/catalog.py) · 7243 B · 94 lines · `5E6A83704F31`
- **S** [training/data/app_capabilities.json](../../training/data/app_capabilities.json) · 8274 B · 268 lines · `0872B1F50FD5`
- **T** [training/eval_harness.py](../../training/eval_harness.py) · 9219 B · 194 lines · `B7E60C70558F` · LIFE
- **T** [training/evaluate_gguf.py](../../training/evaluate_gguf.py) · 7213 B · 134 lines · `0803DFADCA4B` · LIFE
- **S** [training/export_quantized.py](../../training/export_quantized.py) · 3660 B · 75 lines · `D60196075EC2`
- **S** [training/FEATURES.md](../../training/FEATURES.md) · 34968 B · 269 lines · `2400FB99719F`
- **S** [training/get_quant_tools.py](../../training/get_quant_tools.py) · 2002 B · 53 lines · `ECA57FB6C5C7` · ABI
- **S** [training/README.md](../../training/README.md) · 2910 B · 45 lines · `7ADDBCBBB701`
- **S** [training/requirements-lock.txt](../../training/requirements-lock.txt) · 1255 B · 54 lines · `0527D0D04137`
- **S** [training/run_five_hour.py](../../training/run_five_hour.py) · 8429 B · 134 lines · `016F12769EF8` · LIFE
- **S** [training/status_five_hour.py](../../training/status_five_hour.py) · 770 B · 14 lines · `232898B8C53B`
- **T** [training/test_eval_harness.py](../../training/test_eval_harness.py) · 1730 B · 39 lines · `39984F450151`
- **S** [training/test_long_training.py](../../training/test_long_training.py) · 3353 B · 77 lines · `38E140092BBF` · LIFE
- **S** [training/test_training.py](../../training/test_training.py) · 6635 B · 135 lines · `004403C234EF`
- **S** [training/tool_catalog.json](../../training/tool_catalog.json) · 15493 B · 671 lines · `9D18075089DB`
- **S** [training/train.py](../../training/train.py) · 10063 B · 192 lines · `290DB9838DCF`
- **S** [training/train_long.py](../../training/train_long.py) · 16190 B · 268 lines · `EBDDEA16D05A` · LIFE
- **S** [training/validate_dataset.py](../../training/validate_dataset.py) · 3446 B · 77 lines · `B5E954A98E55`
