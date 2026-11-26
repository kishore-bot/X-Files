# Measuring Xfiles performance

Use the same build variant, inputs, device refresh rate, theme, and settings on both
revisions. Collect on a lower-end and a mid-range physical Android device. Record
the Android version, CPU and GPU variant, app revision, baseline profile mode,
and the thermal state at the start and end of each run.

The benchmark sources in `baselineprofile` cover cold and warm library launch,
first PDF render, PDF and text flings, digital PDF search hits and misses, and
scrolling a wide CSV workbook with both frozen panes. The fixtures are generated
by the benchmark app. Use the same fixture and measurement source when comparing
LazyMatrix to the original spreadsheet renderer at `543dc2c`. Use identical
light and dark runs for comparisons involving the reader chrome.

## Perfetto measurements

| Journey | Capture |
| --- | --- |
| Startup and splash | Time to initial and fully drawn frames from StartupTimingMetric. Compare cold and warm runs with the same profile mode. |
| PDF open and fling | `XfilesPdfFirstPage`, `XfilesPdfRenderQueue`, and `XfilesPdfRender`. Capture p95 frame time and peak process memory while flinging the same document in continuous and two-page modes. |
| PDF search | `XfilesPdfSearch` with a hit and a miss in the same digital PDF. Check that `XfilesPdfOcrPage` is absent for the digital miss on Android with the PDF text API. Repeat with a scanned fixture to measure OCR. |
| Linked folders | Time from launch or refresh to the first usable list, and counts and wall times of `XfilesProviderRoot`, `XfilesProviderChildren`, and `XfilesProviderPath` inside `XfilesFolderScan`. Separate dispatcher wait using `XfilesFolderQueue`. |
| Content search | Run a query before refresh, while `XfilesIndexExtract` is active, and after a batch commits. Compare `XfilesIndexQuery`, `XfilesIndexWriterOpen`, `XfilesIndexSave`, and `XfilesIndexSnapshotLoad`. Record time to the first useful hit and p95 query latency. |
| Wide spreadsheet | p95 frame time and peak process memory for the same frozen sheet and axis swipes on both builds. Use the same frozen panes and row/column dimensions. |
| Video | Compare Media3 `XfilesVideoSeekToFrameMs` and cumulative `XfilesVideoDroppedFrames` on the same streams. Record frame timing while pinching and while seeking. A seek without a renderer first-frame callback, including a timeout or superseded seek, produces no latency point. |
| Fily | Compare `XfilesFilyLoadCpu` or `XfilesFilyLoadGpu` with the warm `XfilesFilyGenerate` path. Record first-token and queue counters, memory, and thermal behavior over repeated requests with the same model and prompt. |

The linked-folder scanner currently examines up to 6,000 entries. Use 1,000 and
near-capacity linked libraries for Android provider traces. The generated Rust
index fixture exercises larger supported metadata and posting sets on the host:

```sh
cargo run --release --locked --manifest-path engine/Cargo.toml -p document-search --example index_perf -- 1000 10000 20000
```

That fixture reports snapshot load, commit time, and query latency while a batch
commits. Use the Android trace for provider and native binding overhead. Compare
`XfilesNativeTextRead` with `XfilesIndexExtract` and `XfilesNativeTextChars` to
size text transferred across the binding. Capture
the same operation boundaries before changing ranking, storage, cache budgets,
or native worker counts.

## Storage decision

Keep the existing BM25 engine and app-wide preferences. Compare Room for
per-file positions and settings if traces show preference write amplification,
or if grouped updates need a transaction. A migration should preserve the SAF
identity and old positions and avoid blocking composition or startup. Compare
SQLite FTS only if corpus measurements show the Rust implementation is the
limiting factor.
