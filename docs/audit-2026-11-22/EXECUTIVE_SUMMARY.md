# Xfiles architecture and reliability audit

**22 November 2026 · `C:/Users/kisho/Documents/projects/Xfiles` · clean `dev-deploy` snapshot `b79503e80aa85df65674f2365eb6d69f3bccee00`**

Xfiles uses Android-native Jetpack Compose, feature ViewModels and Hilt, shared Kotlin Multiplatform logic, and a Rust document and inference engine. The current checkout also has a desktop MCP server, optional FFmpeg audio, Firebase feedback, an LFM2 training and evaluation suite, and a signed GitHub release pipeline. Keep the current boundaries and prioritize document isolation, offline voice, and reproducible release packaging. [Architecture review](ARCHITECTURE_REVIEW.md)

| Priority | Finding |
|---|---|
| Document isolation | A late two-page PDF render can populate the next document’s cache. PDF OCR cancellation can also outlive bitmap disposal. REL-01 and REL-02. |
| Voice privacy | Fily can invoke a speech provider without an offline guarantee and logs recognized phrases. SEC-01 and SEC-02. |
| Model installation | Cancel and retry can share the same staging file. An incomplete final model can be skipped indefinitely by normal retry. REL-03 and REL-04. |
| Release packaging | The signed workflow can omit native FFmpeg audio even when the renderer is packaged. The pinned Kotlin, AGP and Gradle combination exceeds JetBrains’ fully supported matrix. BUILD-01 and TOOL-01. |
| Desktop integrity | A cancelled MCP request can leave blocking work active, and a failed PDF split can leave part of its outputs committed. MCP-01 and MCP-02. |

The register lists **17 source-confirmed paths or gaps**, including five high-priority findings, plus four follow-up risks. No finding received Critical severity. The fastest release path is to fix PDF ownership and offline voice, add an explicit FFmpeg artifact gate, and use the checked-in Fily harness as a quality gate alongside on-device latency and memory. [Ranked findings](BUG_REPORT.md) · [Roadmap](MODERNIZATION_ROADMAP.md)

Inventory covers **630 files** and mechanical screening covers **613 text files and 119,664 lines**. The targeted manual review covers build, Android and native integration, and the highest-impact workflows. The ledger identifies files that still need a full manual pass, especially parser internals and UI details. No builds, tests, device checks, inference, deployments or source changes were performed. [Coverage ledger](REPOSITORY_INVENTORY.md)

## Reports

- [Repository inventory](REPOSITORY_INVENTORY.md) and [ranked findings](BUG_REPORT.md)
- [Architecture](ARCHITECTURE_REVIEW.md), [Kotlin and KMP](KOTLIN_ADVANCED_OPPORTUNITIES.md), and [Rust and FFI](RUST_REVIEW.md)
- [Execution flows](THREADING_AND_EXECUTION_FLOW.md), [DSA and performance](DSA_AND_PERFORMANCE.md), and [security](SECURITY_AUDIT.md)
- [Product opportunities](FEATURE_SUGGESTIONS.md), [implementation roadmap](MODERNIZATION_ROADMAP.md), and [November 2026 references](DOCUMENTATION_REFERENCES.md)
