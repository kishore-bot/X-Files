# Ranked bug report

**Snapshot `b79503e` · 22 November 2026**

Source-confirmed findings describe a reachable code path or missing guarantee. The scenarios describe planned regression checks. Runtime outcomes, attackability and performance impact require separate validation. High severity flags potential document mix-ups, confidential voice handling, native resource ownership, failed model install, or a missing release capability.

## SEC-01 · High · Offline voice policy permits providers with network capability

- **Evidence** — [SpeechRecognition.kt:63](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/SpeechRecognition.kt#L63) · [SpeechRecognition.kt:144](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/SpeechRecognition.kt#L144) · [TextToSpeech.kt:38](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/TextToSpeech.kt#L38)
- **Validation scenario** — Open voice Fily with a device profile that has no on-device speech recognizer. The app selects Android’s default service and requests offline results as a preference. TTS selects a locale without checking the selected voice’s network requirement.
- **Impact** — A provider can process microphone audio or spoken document excerpts off-device. Actual transmission was outside this audit.
- **Remedy and expected result** — Require an available local recognizer and a locally installed TTS voice. Offer setup guidance while keeping Fily voice-only. Verify microphone and output behavior on API 30 and 31+.

## REL-01 · High · A late PDF spread can put another document’s page in the cache

- **Evidence** — [DocumentAnalysisViewModel.kt:178](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/DocumentAnalysisViewModel.kt#L178) · [DocumentAnalysisViewModel.kt:220](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/DocumentAnalysisViewModel.kt#L220) · [PdfPagesUseCase.kt:26](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/PdfPagesUseCase.kt#L26) · [PdfPagesUseCase.kt:38](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/domain/PdfPagesUseCase.kt#L38)
- **Validation scenario** — With a delayed renderer, enable two-page view in document A and switch to B while the partner page is pending. The spread job can finish after cache clear and cache a page under its integer index before the ViewModel checks the URI.
- **Impact** — B can retrieve a bitmap from A. That is a document isolation bug, with device reproduction pending.
- **Remedy and expected result** — Scope render jobs and cache to a reader session. Verify the generation before every write and UI publish. Add deterministic switch, reopen and password-change tests.

## REL-02 · High · A pending OCR task can outlive the PDF bitmap it uses

- **Evidence** — [TextRecognitionRepository.kt:39](../../core/documents/src/main/kotlin/com/xfiles/core/documents/TextRecognitionRepository.kt#L39) · [TextRecognitionRepository.kt:44](../../core/documents/src/main/kotlin/com/xfiles/core/documents/TextRecognitionRepository.kt#L44) · [AndroidPdfDocument.kt:164](../../core/documents/src/main/kotlin/com/xfiles/core/documents/AndroidPdfDocument.kt#L164) · [AndroidPdfDocument.kt:190](../../core/documents/src/main/kotlin/com/xfiles/core/documents/AndroidPdfDocument.kt#L190)
- **Validation scenario** — Cancel a PDF OCR request while asynchronous ML Kit recognition is pending. Cancellation ends the waiting coroutine. Its caller recycles the bitmap in finally while task completion remains pending.
- **Impact** — Continued task access to a recycled bitmap is possible. The effect depends on ML Kit’s ownership and copy behavior.
- **Remedy and expected result** — Transfer the bitmap lifecycle to Task completion, reject stale UI delivery and bound in-flight OCR. Test cancellation before and after result delivery with a delayed task.

## REL-03 · High · Model cancellation leaves a shared staging path open to a retry

- **Evidence** — [AndroidModelDownloads.kt:103](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/AndroidModelDownloads.kt#L103) · [AndroidModelDownloads.kt:119](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/AndroidModelDownloads.kt#L119) · [AndroidModelDownloads.kt:130](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/AndroidModelDownloads.kt#L130) · [AndroidModelDownloads.kt:177](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/AndroidModelDownloads.kt#L177)
- **Validation scenario** — Cancel during an import copy and promptly retry with the same model. Cancellation removes the active entry before the worker exits. Both jobs name the same partial file, and the import copy has no per-chunk cancellation check.
- **Impact** — Overlapping writes or cleanup can break installation and misreport progress.
- **Remedy and expected result** — Assign each attempt a generation and private staging file. Cap bytes read, check cancellation between chunks and allow only the active owner to validate, publish and clean.

## BUILD-01 · High release gap · A clean signed CI build can omit FFmpeg audio

- **Evidence** — [release.yml:89](../../.github/workflows/release.yml#L89) · [build.gradle.kts:38](../../feature/reader/build.gradle.kts#L38) · [build.gradle.kts:36](../../ffmpeg/build.gradle.kts#L36) · [build.gradle.kts:54](../../ffmpeg/build.gradle.kts#L54)
- **Validation scenario** — Inspect a clean runner artifact. The workflow installs SDK and builds but has no FFmpeg preparation step. The FFmpeg module skips the Rust JNI build when the ignored cache does not contain per-ABI libavcodec.
- **Impact** — The signed APK can build with Java renderer classes and no usable FFmpeg fallback. Formats needing the extension may have silent or unsupported audio.
- **Remedy and expected result** — Choose a release contract for mandatory FFmpeg or explicit platform-only playback. For mandatory operation, verify native assets per ABI and run an extension-only playback smoke test before publish.

## REL-04 · Medium · A corrupt-length final model cannot be repaired by the ordinary install action

- **Evidence** — [AndroidModelDownloads.kt:60](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/AndroidModelDownloads.kt#L60) · [AndroidModelDownloads.kt:103](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/AndroidModelDownloads.kt#L103) · [AndroidModelDownloads.kt:173](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/AndroidModelDownloads.kt#L173)
- **Validation scenario** — Use a truncated final model fixture. Install state rejects its length while download and import skip any existing regular file.
- **Impact** — Retry can leave the model missing indefinitely without explicit removal.
- **Remedy and expected result** — Use one validated readiness rule for state and repair planning. Verify length and digest prior to final promotion. Preserve existing model names and URLs.

## REL-05 · Medium · Office edit cancellation relies on a completion event that follows the blocking call

- **Evidence** — [OfficeEditRepository.kt:99](../../core/documents/src/main/kotlin/com/xfiles/core/documents/OfficeEditRepository.kt#L99) · [OfficeEditRepository.kt:139](../../core/documents/src/main/kotlin/com/xfiles/core/documents/OfficeEditRepository.kt#L139) · [office_edit.rs:185](../../engine/crates/document-ffi/src/office_edit.rs#L185)
- **Validation scenario** — Start a long Office replacement and cancel while Rust is executing. The default job-completion handler is installed around the synchronous call and disposed on return.
- **Impact** — The native cancellation latch may never signal during the active job. UI cancellation can leave native CPU and disk work running.
- **Remedy and expected result** — Link cancellation at request time to the worker’s native latch. Keep its resource owner alive until native exit.

## REL-06 · Medium · Office export can leave a partial destination

- **Evidence** — [OfficeEditRepository.kt:112](../../core/documents/src/main/kotlin/com/xfiles/core/documents/OfficeEditRepository.kt#L112) · [OfficeDocumentEditor.kt:114](../../core/documents/src/main/kotlin/com/xfiles/core/documents/OfficeDocumentEditor.kt#L114)
- **Validation scenario** — Use a test DocumentsProvider that returns a stream and fails partway through a copy. Both Office paths write directly to the chosen destination.
- **Impact** — The remaining file can be mistaken for a complete export. A provider may lack delete support.
- **Remedy and expected result** — Validate private staged output, report publication separately and attempt cleanup only for a newly created destination under the current operation.

## REL-07 · Medium · Transient indexing failures are persisted as fresh empty documents

- **Evidence** — [LibrarySearchRepository.kt:70](../../core/documents/src/main/kotlin/com/xfiles/core/documents/LibrarySearchRepository.kt#L70) · [LibrarySearchRepository.kt:79](../../core/documents/src/main/kotlin/com/xfiles/core/documents/LibrarySearchRepository.kt#L79) · [index.rs:239](../../engine/crates/document-search/src/index.rs#L239)
- **Validation scenario** — Restore a temporarily unreadable file without changing its metadata and rescan. The catch path stores empty text against the current size and mtime, so stale checking can skip it on the next run.
- **Impact** — The file can remain absent from full-text results.
- **Remedy and expected result** — Track retryable errors separately from successful empty text. Invalidate prior index entries safely and resume failed document indexing.

## REL-08 · Medium · PDF derivative caches ignore document revision

- **Evidence** — [AndroidPdfDocument.kt:154](../../core/documents/src/main/kotlin/com/xfiles/core/documents/AndroidPdfDocument.kt#L154) · [AndroidPdfDocument.kt:248](../../core/documents/src/main/kotlin/com/xfiles/core/documents/AndroidPdfDocument.kt#L248) · [AndroidPdfDocument.kt:273](../../core/documents/src/main/kotlin/com/xfiles/core/documents/AndroidPdfDocument.kt#L273)
- **Validation scenario** — Replace a PDF in a test provider that keeps its URI and page count. Reopen it and retrieve a previously cached page thumbnail, OCR or character map.
- **Impact** — A new file revision can display stale text or images. Access after permission revocation requires separate validation.
- **Remedy and expected result** — Key reuse by source revision and permission lifetime. Use session-only caching for providers without reliable metadata.

## PERF-01 · Medium · A zero-hit text-layer search triggers OCR

- **Evidence** — [AndroidPdfDocument.kt:317](../../core/documents/src/main/kotlin/com/xfiles/core/documents/AndroidPdfDocument.kt#L317) · [AndroidPdfDocument.kt:338](../../core/documents/src/main/kotlin/com/xfiles/core/documents/AndroidPdfDocument.kt#L338)
- **Validation scenario** — Search a born-digital PDF for an absent term. The fallback sees an empty hit list and starts OCR even though the PDF has text.
- **Impact** — Search does extra raster and OCR work, with latency and PSS impact awaiting measurement.
- **Remedy and expected result** — Decide OCR from usable page text and limit recognition work. Preserve image-only and mixed-document matching.

## SEC-02 · Medium · Recognized speech options appear verbatim in local logs

- **Evidence** — [SpeechRecognition.kt:128](../../feature/assistant/src/main/kotlin/com/xfiles/feature/assistant/SpeechRecognition.kt#L128)
- **Validation scenario** — Speak a synthetic phrase on a test release build and inspect app logs with approved developer access. The result callback logs the full alternatives.
- **Impact** — Logs can include phrases and sensitive filenames or search terms.
- **Remedy and expected result** — Remove speech text logging from all variants. Use non-content outcomes and duration telemetry if needed.

## RUST-01 · Medium · Snippet writes and index metadata update at different commit points

- **Evidence** — [index.rs:293](../../engine/crates/document-search/src/index.rs#L293) · [index.rs:319](../../engine/crates/document-search/src/index.rs#L319) · [index.rs:458](../../engine/crates/document-search/src/index.rs#L458)
- **Validation scenario** — Inject failure between a new snippet write or removal and metadata rename, then reopen the previously committed index.
- **Impact** — Old metadata can point to missing snippet text. Storage clear also needs observable error reporting.
- **Remedy and expected result** — Prepare versioned snippets, atomically commit metadata and reclaim prior-generation files after commit. Add interrupted-write recovery tests.

## MCP-01 · Medium · Dropping a desktop request can leave a blocking job active

- **Evidence** — [jobs.rs:63](../../engine/crates/xfiles-mcp/src/jobs.rs#L63) · [jobs.rs:78](../../engine/crates/xfiles-mcp/src/jobs.rs#L78)
- **Validation scenario** — Cancel a test client’s long-running tool request and track the job permit. Timeout sets a cancellation token. Dropping the awaiting future has no matching cancellation cleanup.
- **Impact** — A job can continue after its caller is gone. Document-writing tools need a prepublication cancellation check.
- **Remedy and expected result** — Use a drop guard with an explicit cancellation state, keep the permit with work and check cancellation before commit. Test disconnect and protocol cancellation separately.

## MCP-02 · Medium · Desktop PDF split can leave some files published on failure

- **Evidence** — [pdf.rs:245](../../engine/crates/xfiles-mcp/src/tools/pdf.rs#L245) · [pdf.rs:258](../../engine/crates/xfiles-mcp/src/tools/pdf.rs#L258) · [output.rs:51](../../engine/crates/xfiles-mcp/src/output.rs#L51)
- **Validation scenario** — Use a controlled filesystem failure for a later final output in a multi-file split. The operation stages all parts and publishes one at a time. Returning the first commit failure leaves prior committed files.
- **Impact** — A failed request can leave valid visible output without a complete outcome.
- **Remedy and expected result** — Return explicit partial outcomes or stage a directory and publish as an operation when feasible. Preserve existing files under the overwrite contract.

## BUILD-02 · Medium · Manual release version input goes through shell parsing before validation

- **Evidence** — [release.yml:9](../../.github/workflows/release.yml#L9) · [release.yml:15](../../.github/workflows/release.yml#L15) · [release.yml:35](../../.github/workflows/release.yml#L35)
- **Validation scenario** — Review the version step with a benign malformed input containing whitespace or quote characters. The workflow embeds workflow_dispatch input directly in the inline run script and only then applies the version pattern.
- **Impact** — Manual input affects a privileged release job’s script parsing. Reach depends on repository dispatch permissions and workflow configuration.
- **Remedy and expected result** — Read the dispatch value through a step environment variable, validate it as data, and limit repository write permissions to a protected publish job.

## UI-01 · Low · Presenter elapsed time can jump when the system clock changes

- **Evidence** — [PresentationPreview.kt:561](../../feature/reader/src/main/kotlin/com/xfiles/feature/reader/PresentationPreview.kt#L561)
- **Validation scenario** — In a test with a controlled clock, change wall time while the presentation is running. The counter and delay are computed from wall time.
- **Impact** — The pacing timer can jump forward or backward while a talk is active.
- **Remedy and expected result** — Use monotonic elapsed time, inject a clock for a deterministic regression test, and keep the displayed duration advancing after a wall-clock adjustment. [Android SystemClock](https://developer.android.com/reference/android/os/SystemClock)

## Follow-up risks

- **NAT-01 · High if GPU is shipped** — Verify optional OpenCL linkage, backend detection and CPU fallback on devices without an accessible driver. The default flag is off. [build.gradle.kts:35](../../documentEngine/build.gradle.kts#L35)
- **FFI-01 · Medium** — Closing resource ownership after returning through prompt-cancellable `withContext` needs verification in archive and Office paths. Use a delayed open in a controlled cancellation test and assert the descriptor closes exactly once. [ArchiveRepository.kt:117](../../core/documents/src/main/kotlin/com/xfiles/core/documents/ArchiveRepository.kt#L117) · [OfficeEditRepository.kt:66](../../core/documents/src/main/kotlin/com/xfiles/core/documents/OfficeEditRepository.kt#L66) · [Coroutine resource cancellation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html)
- **CLOUD-01 · Medium** — The checked-in feedback rules permit bounded anonymous creates and deny client reads and mutations. Production App Check enforcement and quota controls are unknown. [firestore.rules:35](../../firebase/firestore.rules#L35)
- **SEC-03 · Medium** — AppFunctions handoff tokens need a consent-revocation check when consumed. Confirm the token can survive revocation before claiming any disclosure. [FilyToolHost.kt:64](../../androidApp/src/main/kotlin/com/xfiles/android/appfunctions/FilyToolHost.kt#L64) · [MainActivity.kt:564](../../androidApp/src/main/kotlin/com/xfiles/android/MainActivity.kt#L564)

[Security assessment](SECURITY_AUDIT.md) · [Roadmap](MODERNIZATION_ROADMAP.md)
