# Xfiles

**Premium feel, faster app.** Xfiles is an offline-first Android file reader and toolkit: PDFs, Office and OpenDocument files, EPUB books, archives, pictures and video, all handled on the phone. It includes **Fily**, an on-device AI assistant you talk to. No document ever leaves the device.

Xfiles is a Kotlin + Rust rewrite of EzFiles. Parsing, PDF tools, search and the assistant's inference all run in a native Rust engine. The UI is Jetpack Compose.

---

## Features

**Reading**
- **PDF**
  - Smooth zoom, plus single-page, two-page and grid views.
  - Go to page, find, password-protected files, printing, and annotations (pen, highlight, notes, signatures).
  - **Text selection** with drag handles, for copy, highlight, search or share. It works on scanned pages through on-device OCR.
- **Office and OpenDocument:** DOCX, XLSX, PPTX, ODT, ODS and ODP, plus legacy DOC, XLS and PPT.
  - Find & replace across text runs.
  - Spreadsheet cell editing with frozen rows and columns.
  - Reorder, duplicate or delete slides, with a presenter view.
- **EPUB:** page-by-page reading, contents, highlights and notes, footnotes, and per-book reading settings with light and dark themes.
- **Text and code:** Markdown, JSON, YAML, CSV and more, in an editor with undo/redo, find & replace and encoding control.
- **Pictures:** a swipeable gallery of the folder, sharp tiled zoom, animated images, and EXIF data.
- **Video and audio**
  - Picture-in-picture, gestures, speed control, orientation lock and resume.
  - Subtitles (SRT, VTT, ASS) with styling and automatic contrast.
  - Frame-accurate scrubbing with a live preview.
  - AC3, E-AC3, DTS and TrueHD audio through FFmpeg.
- **ZIP archives:** browse, extract, and open ZipCrypto or AES-encrypted entries.
- **At a glance:** a summary of any document, with tappable dates (add to calendar), amounts, phone numbers, emails and links.

**Tools**
- **PDF**
  - Merge, split (ranges, every N pages, one file per page), reorder, rotate and compress.
  - Watermark, sign, and protect or unlock with AES-256.
- **Conversions**
  - Images to PDF, with a compression choice.
  - PDF to pictures as JPEG or PNG at 72, 150 or 300 DPI.
  - Text to PDF, with templates, font and alignment.
- **Scanner:** manual corner adjustment, grayscale, and searchable (OCR) PDFs.
- **Tidy up:** find duplicate and unneeded files and clean up storage.

**Library**
- Folders, recents ranked by **frecency** (how often and how recently), bookmarks with page snippets, and 12 file-type filters.
- **Typo-tolerant** file-name search, plus full-text search inside documents.
- A per-file menu (open, share, print, remove from recents), multi-select, a home-screen widget, app shortcuts, and "Open with" / "Share to" for 40+ file types.

---

## Fily: the on-device assistant

Fily is a voice assistant that runs a **350M-parameter language model locally** through llama.cpp, on the GPU (OpenCL) when that is safe and on the CPU otherwise. It answers questions about your files and drives the app through 45 tools: opening documents, running PDF tools, navigating the reader, tidying storage and more. It speaks its answers back with on-device text-to-speech.

Each technique below is used only where it solves a real constraint of a small model on a phone:

| Technique | Why it's there | How it works |
|---|---|---|
| **RAG (retrieval-augmented generation)** | The model's 4K-token context can't hold a long PDF. | Text is split into ~250-token passages (never across PDF pages) and indexed with **BM25** in Rust. The best passages for the question are deduplicated with MMR, merged with neighbours, and packed into a character budget with `[page N]` labels, so Fily can cite pages. Indexes live in an LRU cache (8 documents / 64 MB) and are invalidated when the file's modified time or size changes. Benchmark on a desktop x86-64 machine (not the phone): 1 MB of text indexes in 28 ms, and a query takes about 0.3 ms. |
| **CAG / KV-cache reuse** | The system prompt and tool catalog are long, and re-reading them on every step wastes seconds on a phone. | The llama.cpp context stays alive between calls. Each request computes the common token prefix with what is already in the KV cache, trims the rest, and decodes only the new suffix. It falls back safely on hybrid or recurrent layers. Every generation logs prefill and reused token counts. |
| **GPU crash guard** | Some mobile GPU drivers crash in native code while loading the model. | A persisted state machine (untested → trying → proven/bad) is written before each GPU load. A crash mid-load means the next launch uses the CPU. GPU load and first reply each time out after 30 s. The state resets when the app or the model file changes. |
| **Prompt contract test** | A retrained model must get exactly the prompt format it was trained on. | A test compares the app's tool catalog and system prompt with fixtures exported from the training data. |

A knowledge graph (KAG) was considered and left out: it adds nothing for a file app with a small model.

Xfiles also exposes its actions to the system assistant through **Android AppFunctions**.

---

## MCP server: the engine as AI tools

[`engine/crates/xfiles-mcp`](engine/crates/xfiles-mcp) is a standalone **Model Context Protocol** server, built on the official Rust SDK `rmcp`. It gives desktop AI clients such as Claude Desktop and Claude Code the same document engine the app uses. It has 13 tools:

- **Reading:** `read_document`, `search_document`, `glance` (including PDF text extraction with page numbers).
- **PDF:** `pdf_merge`, `pdf_split`, `pdf_compress`, `pdf_reorder`, `pdf_rotate`, `pdf_watermark`, `pdf_protect`.
- **Office:** `office_find_replace` (preview, then apply to a new file).
- **ZIP:** `list_archive`, `extract_archive_entry`.

Safety:
- Access is sandboxed to the `--root` directories you pass (paths are canonicalised, including symlinks).
- Inputs are never modified, and outputs are written atomically.
- Each operation has a timeout and a size cap, and there is no network access.

```bash
cargo build --release --manifest-path engine/Cargo.toml -p xfiles-mcp
claude mcp add xfiles -- /path/to/xfiles-mcp --root ~/Documents
```

See the crate README for the Claude Desktop configuration.

---

## Architecture

```
androidApp ─ Hilt wiring, navigation, flavors (dev/prod), widget, AppFunctions, Firebase (prod)
feature/*  ─ library · reader · tools · editor · assistant · tidy      (Compose, MVVM, StateFlow)
core/*     ─ designsystem (glass UI, motion, haptics) · documents (repositories, PDF/OCR, printing)
sharedLogic ─ Kotlin Multiplatform domain: Fily session, prompt and tools, book layout, feedback, models
documentEngine ─ Kotlin facade over the Rust engine (UniFFI bindings)
engine/    ─ Rust workspace
   document-parsers   PDF tools and text, DOCX/XLSX/PPTX/ODF/EPUB/legacy Office, ZIP, edits
   document-search    BM25 passage index, LRU, typo-tolerant name index
   document-glance    summaries and actionable items
   document-imaging / document-tidy / document-core
   fily-inference     llama.cpp runtime with KV-cache reuse
   document-ffi       UniFFI surface for Android
   xfiles-mcp         MCP server (host only)
ffmpeg/    ─ Media3 FFmpeg audio extension with a Rust JNI bridge (FFmpeg itself is LGPL, linked dynamically)
```

**Main technologies**
- Kotlin 2.4 and Compose BOM 2026.11, using frame-rate hints and the highest display refresh rate.
- Hilt, Coroutines/Flow, Navigation 3, Media3, CameraX, ML Kit OCR, WorkManager, Glance.
- Rust with UniFFI, llama.cpp and FFmpeg 7.1.
- Builds with AGP 9.5 and R8 full mode, with baseline profiles.

---

## Privacy

- **Offline-first:** documents, file names and Fily conversations never leave the phone.
- **Internet is used only for:**
  - the one-time Fily model download, when you ask for it;
  - opt-in crash reports (prod builds);
  - feedback you choose to send.
- **Feedback:** About → Send feedback goes to Cloud Firestore in prod builds that have Firebase. Nothing is sent automatically. You see exactly what is attached (message, optional app version and device model, optional reply email). Builds without Firebase use the share sheet instead.
- **Uninstall:** backups are disabled, so uninstalling removes all app data.

---

## Building

Builds are run on Linux or WSL with:
- JDK 17
- Android SDK with compile SDK 37
- NDK `29.0.14206865`
- Rust (version in `rust-toolchain.toml`)
- CMake

```bash
# 1. FFmpeg audio decoders (once; LGPL-only configuration, verified checksum)
ANDROID_HOME=~/Android/Sdk bash scripts/build-ffmpeg.sh

# 2. App
./gradlew :androidApp:assembleDevDebug          # dev flavor (com.xfiles.dev), no Firebase
./gradlew :androidApp:assembleProdRelease       # prod, R8-minified

# Prod with Crashlytics and Firestore feedback (needs androidApp/google-services.json, not committed)
./gradlew -Pxfiles.firebaseCrashReports=true :androidApp:assembleProdRelease
```

Without step 1 the app still builds; AC3, DTS and similar audio then falls back to the phone's own decoders.

To set up Firestore for feedback, see [`firebase/README.md`](firebase/README.md). The security rules only allow creating schema-checked feedback documents.

### Tests

```bash
./gradlew :sharedLogic:testAndroidHostTest           # Kotlin domain tests
cargo test --manifest-path engine/Cargo.toml --workspace   # Rust engine and MCP server
```

---

## Team

- **Kishore K** – Developer
- **Robin Isac** – Developer
- **Abhijith H M** – Market Analyst

Built by SAHAPRAXIS. *Free doesn't mean cheap.*

Third-party licences are listed in the app under About → Open-source licences. FFmpeg is used under the LGPL 2.1+, as separate replaceable shared libraries.
