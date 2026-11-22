# Xfiles — App features

Updated: 14 November 2026. This is the single feature and setup document for the current working tree, including uncommitted changes. “Implemented” describes source behavior; it does not mean every device, format or external service has passed acceptance testing. Historical decisions and validation records remain in [NOTES.md](../NOTES.md).

## Contents

- [Platform and status](#platform-and-status)
- [Library and search](#library-and-search)
- [Supported files](#supported-files)
- [Reading and navigation](#reading-and-navigation)
- [PDF reading and tools](#pdf-reading-and-tools)
- [Books and Office documents](#books-and-office-documents)
- [Photos and video](#photos-and-video)
- [Creating and editing files](#creating-and-editing-files)
- [Tidy up](#tidy-up)
- [Sharing and printing](#sharing-and-printing)
- [Gemini and Siri access](#gemini-and-siri-access)
- [Appearance, motion and haptics](#appearance-motion-and-haptics)
- [Privacy and crash reports](#privacy-and-crash-reports)
- [Android build and Firebase setup](#android-build-and-firebase-setup)
- [Performance and resource limits](#performance-and-resource-limits)
- [About and team](#about-and-team)
- [Known gaps and validation](#known-gaps-and-validation)

## Platform and status

| Area | Current scope |
|---|---|
| Android | Primary implementation. Minimum Android 11/API 30; compile/target Android 17/API 37. Current APK ABIs are arm64-v8a and x86_64. Features and codecs may require newer platform support. |
| iOS | Shared UI and platform/App Intent adapters exist, but support is incomplete. Full macOS/Xcode linking, signing and physical-device acceptance remain pending. Video playback is unimplemented. |
| Offline build | No Internet permission or Crashlytics SDK. Reading, parsing, local OCR and organization work locally. |
| Optional Firebase build | Adds diagnostic networking, with reporting off by default and separately consented. Reading and document WebViews retain their offline restrictions. |
| Validation | Both Android build profiles and static lint passed. Recent changes still need user-led device checks; older tests/benchmarks do not validate newer edits. |

## Library and search

- Open individual files with the Android picker, Open with or Share to Xfiles. Add chosen folders and their subfolders to Library without broad storage/media permissions.
- Navigate Library, Recent, Bookmarks and Tools. Filter by All, PDF, Docs, Slides, Sheets, Books, Images, Media, Text, Code, Data and Archives.
- Search filenames, including typo-tolerant ranking, and supported document contents. Content results show a matching passage and open a PDF at its matching page when available.
- Restore the cached folder list/index promptly while background refresh runs. Recent prioritizes frequently and recently opened files. Removing a library folder releases its persisted grants.
- Select up to 32 originals for sharing. File menus preserve Open, Share original, supported Print, selection and history actions.
- Android photo/video rows show 56 dp thumbnails. Videos add a play badge and duration when available. Previews do not autoplay; failed or unsupported previews retain file-type icons. There is no new library grid layout.
- Thumbnail requests use bounded decoding, two background jobs, an 8 MiB memory cache and no disk copies. Android 8 video previews depend on the provider; newer versions can extract a scaled frame. iOS currently retains icons.

## Supported files

Detection is content-based; filename hints choose presentation for text formats. This table describes Android capability. Device codecs, damaged/encrypted files and the limits below still apply.

| Format | Extensions | Level | Works | Does not work yet |
|---|---|---|---|---|
| PDF | `.pdf` | Rendered (by the platform) | Pages, zoom, thumbnails, view modes, go to page, user-supplied password. Native find, text selection and copy on Android 15+ or Android 12–14 with SDK extension 13. Other Android devices use bundled offline Latin-script recognition for find and text extraction, with approximate line highlights. Highlight, pen, notes and drawn signature, saved as a standard incremental update | Forms, signature checking and attachments. Recognition cannot provide selectable text, support every script, or process encrypted/uninspectable PDFs; it is slower than native extraction. Background summaries/Tidy sample up to 8 pages on the recognition fallback. Android password PDFs need Android 15+. Encrypted PDFs can't save annotations. Damaged PDFs stay viewable but can't save. The new fallback and extension path still need device acceptance |
| Word | `.docx` `.docm` `.dotx` `.dotm` | Rendered as pages | Print Layout pages (Pages / Reading switch): page size and margins per section, portrait and landscape, columns, headers and footers (first-page and even-page ones too) with live page numbers, footnotes at the foot of their page, endnotes; styles, theme fonts and colours, fonts drawn with metric-compatible ones (Carlito, Caladea, Arimo, Tinos, Cousine) so lines break where Word breaks them; sizes, colours, highlight, underline styles, caps, super- and subscript; alignment, indents, spacing, line spacing, contextual spacing, borders and shading, tab stops with leaders; numbered and bulleted lists in their own formats; tables with grid widths, merged cells, borders, shading and table-style heading and banded rows, split across pages with heading rows repeated; paragraphs split between lines; keep with next; pictures inline and floating, text boxes, shapes, VML pictures; links and bookmarks; equations as linear text; tracked changes as accepted; find; password-protected files. Reading view: the reflowed text as before | Charts and SmartArt show as a labelled box. Page breaks can differ slightly from Word's where text wraps around floating pictures. No page borders, line numbers, drop caps or WordArt effects. Comments aren't shown in page view. Macros are never run |
| Excel | `.xlsx` `.xlsm` `.xltx` `.xltm` | Values | One sheet at a time with tabs, lettered columns and numbered rows that stay in view, columns sized to content, numbers aligned right, text spilling into empty cells, tap a cell for its full value, cached values with number formats, the formula itself when no value is cached, cell fills, font colour, bold, italic, underline, strikethrough, colours and bold or italic on single words inside a cell (rich text), and size, horizontal alignment, merged cells, find, password-protected files. Hidden sheets are skipped. Number formats checked on generated files | Formula evaluation, borders, pattern fills other than solid (white fills show as none), row heights and the file's column widths, charts, frozen panes, non-Gregorian calendars. Unsupported format codes show the raw value |
| PowerPoint | `.pptx` `.pptm` `.ppsx` `.ppsm` `.potx` `.potm` | Rendered (approximate) | Positioned shapes, theme, master and layout inheritance, colours on single words (also in Google Slides exports), pictures, tables, notes, transitions, entrance builds, presenter, password-protected files | Charts and SmartArt show as labelled boxes. First gradient stop only. Custom geometry draws as a rectangle. No exit, emphasis or motion-path effects. No deck fonts. Embedded audio and video play full screen from a play button (files inside the deck only; linked web videos are not followed) |
| OpenDocument | `.odt` `.ods` `.odp` | Structured / Values | Text, styles, links, tables, pictures in ODT and ODP, sheets showing displayed text, ODP slides laid out with master pages, backgrounds, text frames, styled text including colours on single words, lists, shapes, lines, tables and pictures, ODP audio and video played from the slide, ODP notes, find (ODT and ODS) | ODP gradients and picture fills show their main colour, custom geometry draws as a rectangle, no ODP transitions or animations, base64-embedded pictures, ODS cell styles and merges |
| EPUB | `.epub` | Rendered as a book | **Book** view, like an e-reader: one page at a time in the book's own layout, styles, fonts and pictures; tap the right or left edge or swipe to turn pages, tap the middle for the menu. Contents (EPUB 3 navigation or EPUB 2 NCX) with the current chapter marked; **Aa** settings: White, Sepia, Green and Black themes, text size 70–240%, the book's own font or Serif, Caladea or Sans, line spacing, margins, justified text; the chapter title above the page and pages left in the chapter and % of the book below it; a slider to move through the book; footnotes in a pop-up; audio and video play in the page; each book reopens where it was left. Press and hold text to highlight it (yellow, green, blue, pink) or add a note; bookmark pages from the menu; the Contents sheet's Notebook lists them in reading order, and they keep their place across text size, font and screen changes. Right-to-left books (from page-progression-direction, or the text's language when a chapter doesn't say) and vertical writing turn pages in their own direction. Fixed-layout books show each page scaled to fit. Find within the current chapter. **Scroll** view: the reflowed text as before, with find across the whole book | Book scripts never run (the chapter frame is sandboxed). Obfuscated (protected) fonts fall back to the reader's fonts. SVG-wrapped covers in Scroll view. DRM-protected books |
| Markdown | `.md` `.markdown` `.mdown` `.mkd` `.mdx` | Rendered | Preview like Android Studio's and GitHub's: GitHub Flavored Markdown (tables, task lists, strikethrough, footnotes, heading ids), raw HTML, fenced code coloured; pictures beside the file show once its folder can be read. Source: the exact text with line numbers and Android Studio's Markdown colours. Find in both | No scripts in raw HTML. Whole-file view up to 8 MiB, larger files show as plain lines |
| CSV / TSV | `.csv` `.tsv` `.tab` | Values | Grid, sniffed delimiter, header row, Preview / Source switch, find; bounded plain-text editing | In-place spreadsheet cell editing |
| JSON | `.json` `.geojson` `.jsonc` `.webmanifest` | Structured | Pretty printing, Preview / Source switch, find; bounded plain-text editing | Structured tree editing. Whole-file view up to 8 MiB |
| Code and config | `.kt` `.java` `.rs` `.py` `.js` `.ts` `.swift` `.go` `.c` `.cpp` `.sql` `.sh` `.xml` `.yaml` `.yml` `.toml` `.ini` `.css` `.diff` `.patch` `.jsonl` and others | Structured | Line numbers, streaming for large files, find, bounded syntax colouring and plain-text editing | No full IDE/language-server tools; oversized colouring blocks stay plain. SVG stays source; HTML has its separate offline Preview policy |
| Plain text | `.txt` `.log` and any other text | Structured | UTF-8, UTF-16 with BOM, streaming, find, editing; .txt/.text offer reading Preview and numbered Source | Other legacy encodings |
| Images | `.jpg` `.jpeg` `.png` `.webp` `.gif` `.bmp` `.heic` `.heif` `.avif` | Rendered (by the platform) | Pinch, pan, double-tap zoom, rotate, EXIF orientation, GIF and animated WebP on Android 9+. Up to 64 MiB; pictures over 2^28 pixels are refused | TIFF. Opened on a device: PNG, JPEG with EXIF, GIF, WebP, BMP. HEIC, HEIF and AVIF have never been opened on a device; AVIF is tried from Android 12, but the SDK only names it from Android 14 |
| Audio and video | `.mp4` `.m4v` `.mov` `.3gp` `.webm` `.mkv` `.mp3` `.m4a` `.aac` `.wav` `.ogg` `.opus` `.flac` `.amr` `.mid` | Played (by the platform) | Android player with play/pause, tap/double-tap/horizontal seeking, supported audio/subtitle choices, local SRT/VTT, volume/brightness gestures and lock/background pause without automatic resume. Embedded media up to 128 MiB; see [media controls](#photos-and-video) | Unsupported codecs/subtitle encodings, playlists and background playback. iOS playback is unimplemented. Earlier emulator fixtures are recorded in the media guide; physical acceptance remains pending |
| ZIP | `.zip` | Listing | Entry list | Extracting or opening entries, ZIP64, encrypted archives |

Additional exclusions: RTF, XLSB, RAR, 7z, TAR, GZ, TIFF, VCF, ICS, email, flat ODF, FB2, MOBI and AZW3 are not claimed. ZIP64/encrypted ZIP extraction is unsupported. Local Google document pointer files do not become downloadable cloud documents.

## Reading and navigation

- Search supported text/block documents with case-insensitive matches, bounded to the loaded output/search limits. Slides, images and ZIP listings do not have text find. Matches do not span separate blocks.
- Use Preview/Source for supported HTML, Markdown, JSON, CSV and TXT views. TXT/.text Preview uses a centered reading column with left-aligned paragraphs; Source provides numbered text with original whitespace.
- Preview/Source is a compact floating glass pill with centered labels and 48 dp touch targets. It hides during scrolling and returns after inactivity; fullscreen hides it, and reduced motion disables its transition.
- Reading actions include details, supported search, bookmarks, sharing, printing, editing and fullscreen where appropriate to the format.
- Dark reading mode for text-based files (plain text, code, Markdown source and similar viewers): the reader menu offers a Dark reading / Light reading toggle that switches the page background and text colours between the standard white page and a dark surface. The toggle is per-session and does not affect the app theme.
- At a glance provides bounded on-device summaries/keywords through local algorithms. It does not require a cloud language model. Older-Android PDF extraction may use bounded recognition and cannot provide every script or selectable text.
- Fullscreen and ordinary reader frames respect safe areas. Short landscape PDF windows use a centered one-row toolbar and full-width reading pane; taller windows can use supporting panels. Library navigation and open book menus have dedicated landscape arrangements.

## PDF reading and tools

- Render pages, zoom/pan, browse thumbnails, jump to a page, and select single-page, two-page or grid views.
- Native text find/selection/copy use supported Android APIs: Android 15+ or compatible Android 12–14 SDK-extension support. Other Android devices use bundled offline Latin-script recognition for supported find/text workflows, with approximate line highlights rather than native selection.
- Android password-protected PDF opening requires Android 15+. Passwords remain in memory for the open session. Recognition fallback refuses encrypted or uninspectable PDFs.
- Add highlights, pen strokes, notes and drawn signatures. Save supported annotations using an incremental update. Encrypted PDFs cannot save annotations; damaged PDFs may remain viewable but cannot be saved. Signed/PDF-A files have additional warnings/constraints.
- Draw and keep up to five local signatures, then place and resize them. These are visual signatures, not cryptographic signing or signature verification.
- Merge PDFs; split/extract pages; reorder, rotate or delete pages; change page size; add gallery images. Structural metadata can be limited when pages/documents are combined or removed.
- Add AES-256 PDF password protection or remove protection with the required password. Owner restrictions are not silently bypassed.
- Export pages as JPEG/PNG, extract supported text and use PDF to ZIP. PDF-to-image output is bounded to 300 DPI and 16 megapixels/page; text export is bounded to 200,000 characters.
- Tools save a new verified copy rather than silently replacing the original. Forms, attachment handling and cryptographic signature verification are not implemented.

## Books and Office documents

### EPUB

- Reflowable reading with font/size settings, contents, chapter progress and saved reading position. Fixed-layout pages are scaled to fit.
- Highlight text, add notes, bookmark positions and browse the Notebook. Footnotes open in a pop-up; supported embedded media can play in the page.
- Right-to-left and vertical-writing behavior follows book direction/language metadata where supported.
- Paper-curl page movement uses bounded previews and falls back when content or boundaries cannot use that effect. Reduced motion is respected. The active reading frame and position remain owned by the reader.
- Book chapter scripts do not run. DRM-protected books are unsupported; protected fonts and unsupported content can fall back to reader fonts/representations.

### Office and OpenDocument

- Modern Word/OpenDocument text and supported slide formats retain their implemented designed layouts, images, tables and typography as described in the format table.
- Spreadsheets show stored/cached values and supported formatting; formulas are not recalculated by a spreadsheet engine.
- Supported legacy DOC/XLS/PPT files provide extracted content, with lower fidelity and format-specific exclusions.
- Supported password-protected modern Office files use Standard/Agile AES decryption in memory after password entry. Passwords are not stored. Legacy encrypted/rights-managed variants remain unsupported.
- Macros and active Office objects do not execute.

## Photos and video

### Photos

- Swipe or use next/previous within a frozen, authorized gallery sequence. Opening one external/picker file does not discover neighboring files without authorization.
- Pinch/double-tap zoom, pan and quarter-turn rotation. Zoomed/multitouch gestures do not accidentally page to another photo.
- The selected photo drives the title, details, Share and Print actions. Android EXIF orientation and supported animated GIF/WebP behavior are retained.
- One visible decode and a bounded adjacent static preview avoid decoding the entire gallery. Cancellation, memory pressure and disposal release obsolete work/caches.

### Android video/audio

- Native local playback with play/pause, seek, 10-second skips and fading controls.
- Single tap shows/hides controls. Double tap left/right seeks; horizontal swipe seeks within the file.
- Audio-language and subtitle choices are separate. Embedded tracks depend on the device/format; unsupported binary subtitle encodings are visibly unavailable.
- Select local SRT/VTT sidecars through the system picker. Captions are bounded to 1 MiB, 10,000 cues and bounded displayed text; overlapping cues use an interval index.
- Optional vertical gestures adjust media volume and app-window brightness. Brightness is restored on exit; system volume follows the accepted device/media route.
- Pause on background/lock and do not automatically resume after unlocking. No background-playback service or network streaming is added.
- iOS playback remains unimplemented. Codec, track, lock, route, caption and gesture behavior still need the physical-device matrix.

## Creating and editing files

| Tool | Features and limits |
|---|---|
| Images to PDF | Gallery input, reorder, rotate, page size, margins and compression; pages streamed as JPEG. |
| Text to PDF | Eight templates, page options and up to 200,000 characters. PNG export covers the first page only. Complex-script search/copy still needs acceptance. |
| Scan to PDF | Camera/gallery, automatic corner detection, manual corners, perspective correction, four filters, up to 50 pages and optional searchable text. Real camera/scan acceptance remains separate from synthetic fixtures. |
| Image to text | Bundled on-device Latin-script OCR. No handwriting or universal language recognition claim. |
| Plain-text editor | Text, Markdown, CSV/TSV, JSON, XML, YAML, HTML/source and code within 256 KiB. Undo/redo; preserves supported encoding, line endings and final newline. |
| Document Find & Replace | Case-sensitive or case-insensitive text replacement in DOCX, PPTX, ODT and ODP. Preserves formatting, styles and media. Produces a new file via Save As. |
| PPTX slide tools | View slide count, reorder slides (move up/down) and delete slides. Produces a new file via Save As; the original is not modified. |
| XLSX cell editing | Inline cell value editing in XLSX spreadsheets. Supports numbers and text (inline strings). Produces a new file via Save As. Legacy XLS is view-only. |

Editing supports UTF-8 and UTF-16 with BOM where required. Mixed line endings/unsupported encodings are refused rather than silently converted. Overwrite needs a live write grant; otherwise use Save As. Sharing selected text/draft text does not substitute the saved original for unsaved edits.

## Tidy up

- Scan authorized folders for identical files, similar documents and similar photos. Exact duplicates use content hashing; similarity is distinct from equality.
- Group collections by purpose: Resumes & CVs, Banking & statements, Receipts & bills, Tickets & travel, Certificates & IDs, Payslips and medical documents. Rules show matching reasons and are local heuristics, not semantic AI.
- Cache scan results and retry failed/unreadable analysis on explicit rescan. Filename rules can operate beyond the bounded content-analysis subset.
- Content analysis is bounded to 60,000 characters/document, 64 MiB inputs and 2,000 documents/scan; photo similarity samples up to 3,000 small previews.
- On Android, delete an extra exact copy only after explicit confirmation. The first is labelled Kept copy; similarity/collection rows do not offer deletion. Scans never delete, rename or move files automatically.
- Deletion requires a persisted write grant for the selected tree and provider delete support. Both files are freshly byte-compared before deletion; a stale cached hash alone is insufficient. Old read-only folders need to be selected again to grant write access.
- Provider refusal, changed content, unavailable retained copies or a running scan can prevent deletion. SAF cannot provide an atomic compare-and-delete transaction against another application's concurrent edits. Test using disposable copies.

## Sharing and printing

- Share original files, selected library items and supported exports through the system chooser. No recipient is selected automatically.
- Android uses temporary read grants, ClipData, accurate MIME types and a narrow FileProvider for private staging. Staged exports remain available to receivers rather than being deleted when the chooser closes.
- Staging is bounded to 64 MiB/file and 256 MiB cache with timed cleanup; text sharing is bounded to 100,000 characters.
- Print supported PDFs, platform-decodable images and plain-text/source representations. Rich Office files, EPUB and media require a supported export instead of silent flattening.
- Android PDF printing respects passwords/permissions, page ranges, margins, cancellation and resource ownership. It creates a bounded raster copy rather than vector-preserving output: up to 4 million pixels/page, 500 selected pages and 128 MiB output. Text printing is bounded to 256 KiB.
- iOS activity/print-controller adapters exist in source with security scopes and iPad anchors, but full Xcode/device validation remains pending. Protected-PDF printing has platform limitations.
- Opening a chooser or preparing a print job is not proof of delivery or physical printing.

## Gemini and Siri access

- About has a default-off Assistant access switch. Turning it off blocks document queries, execution and foreground handoffs. A separate default-off text-sharing switch gates excerpts/text matches and is cleared when master access changes.
- Android AppFunctions use local tools and authorized library references; no Gemini API key, Genkit server or remote MCP endpoint is required. Android 16/API 36+ enables the service; older devices retain ordinary app functionality.
- Android function identifiers use the `com.xfiles.android.appfunctions` service (`XfilesAppFunctionService`) plus the function name.

| Group | Functions | Observable result |
|---|---|---|
| Metadata | `searchDocuments`, `listRecentDocuments`, `getDocumentInfo` | At most 20 authorized results; no paths/file bytes. |
| Reading | `openDocument`, `listBookmarks`, `openBookmark` | Metadata or a validated foreground location. |
| Bookmarking | `addBookmark` | Confirmation for a bounded, existing PDF page; no silent mutation. |
| Content | `getDocumentExcerpt`, `findTextInDocument` | Separate consent; bounded text, availability/partial flags and offsets rather than invented page numbers. |
| Delivery | `prepareShareDocument`, `prepareShareDocuments`, `preparePrintDocument` | In-app review, then the system chooser/print preview. No delivery/printing completion claim. |
| Creation | `openTextEditor`, `createTextDocument`, `openTextToPdf`, `createPdfFromText`, `openImagesToPdf`, `createPdfFromImages` | Blank or prefilled drafts; user selects inputs/options and output. |
| Capture | `openScanner`, `openImageToText` | Existing permission, capture and review screens. |
| PDF | `openPdfTools`, `mergePdfDocuments`, `extractPdfPages`, `prepareOrganizePdf`, `prepareResizePdf`, `preparePdfToImages`, `preparePdfToText`, `preparePdfToZip`, `openPasswordTool` | Specific existing review screens, authorized preselection and ordered merge input. User reviews ranges/order/options, enters passwords in-app and chooses a new output. |
| Tidy | `openTidyUp`, `openSimilarDocuments`, `startTidyScan`, `getTidyReport` | Visible cancellable scan/review, or a separately consented lower-bound aggregate of a cached report. No deletion or sensitive classification disclosure. |

- Requests open real app workflows with review, cancellation and output selection. A returned handoff does not mean a file was saved, shared or printed.
- Content excerpts default to 2,000 characters and cap at 4,000; matches/results and foreground requests have bounded budgets. Foreground authorizations expire, are single-use and are revalidated after access changes.
- Assistants cannot submit arbitrary filesystem paths, access locked-document passwords, delete Tidy files or gain generic UI automation/global tap-and-scroll access through this catalog.
- Siri App Intent sources cover Find, Open recent, Share and Print against authorized recents. Static shortcut names may remain visible while access is off; that does not authorize data access.
- Actual Gemini distribution/registration, voice availability and Siri/Xcode/device acceptance remain separate gates. Turning off access cannot recall information already disclosed to an external assistant.

## Appearance, motion and haptics

- Xfiles glass surfaces, existing colors, typography and icons; compact floating reading controls that preserve document legibility.
- System/light/dark appearance, touch-feedback controls, reduced-motion handling and action-specific haptic feedback for supported interactions.
- Dedicated landscape arrangements use window width and height: compact library navigation, combined title/search, full-width short-height PDF reading, and larger-window supporting panes.
- Safe-area and keyboard handling in normal/fullscreen views. Larger fonts retain usable targets; compact controls retain 48 dp touch allocations where implemented.
- Physical haptic feel, visual quality, accessibility and high-refresh smoothness still require device acceptance. No universal “no lag” guarantee is made.

## Privacy and crash reports

- Files are reached through explicit grants, not broad storage/media permission. Camera permission is requested for camera tools; gallery paths do not need it.
- Document parsing, OCR, grouping, summaries and reading are local. Sharing, printing and optional assistants intentionally hand approved information to other software with its own privacy/network behavior.
- The default build has no Internet permission. The optional diagnostics build adds Internet permission but keeps document WebViews offline through interception/CSP and does not add Analytics, accounts or ads.
- Offline HTML may run its own scripts within the viewer sandbox. Book chapter scripts, Markdown scripts, macros and active Office content do not execute. External links require review before leaving the app.
- Parsing, decompression, XML depth, images and output are bounded; parser panics become typed errors. This is not process isolation or an external security certification.
- Passwords are not persisted. Cloud backup and phone-to-phone app-data transfer are disabled. Local caches/preferences can contain authorized names, references, bookmarks and reading snippets.
- Sensitive-document hints can offer Hide from screenshots, including recent-app preview protection for the active document. Classification/OEM behavior needs acceptance.
- A local crash report is stored privately, at most five reports/64 KiB each, with message scrubbing. The next launch offers manual viewing/sharing to the configured contact.
- Optional Firebase diagnostics have their own explicit, default-off consent. Firebase receives SDK diagnostic/device/app/installation data; documents are not attached and existing local reports are not retroactively uploaded.
- The local crash recorder stays outermost and forwards a bounded scrubbed exception copy to Crashlytics only while allowed. Its transport permits only consented Crashlytics report events, dropping OCR/other telemetry.
- Turning off stops forwarding new crashes and blocks future report transport sends. A process restart fully unloads diagnostics; already-started/sent uploads cannot be recalled. Native signal/NDK reporting is not added.

## Android build and Firebase setup

### Build profiles

Android package/application ID: **`com.ezfiles`**. `com.ezfiles.android` is the source namespace, not the Firebase registration package.

Prerequisites: JDK 17, Android SDK platform 37, NDK 29.0.14206865 and Rust stable 1.85+. Current pins include AGP 9.5.1, Gradle 9.7.1, Kotlin 2.4.20 and Compose Multiplatform 1.11.1. The [version catalog](../gradle/libs.versions.toml) and [Gradle properties](../gradle.properties) are authoritative; retain the current Gobley compatibility flags until a verified migration.

```sh
# Default offline APK
./gradlew :androidApp:assembleShare

# Optional Firebase diagnostics APK
./gradlew :androidApp:assembleShare -Pezfiles.firebaseCrashReports=true

# Static checks; add the Firebase property to check that profile
./gradlew :shared:lintDebug :androidApp:lintDebug
```

Debug is for development. Share is optimized and can use the existing debug key for local testing when owner signing is absent. Release requires configured signing; do not treat an unsigned APK as installable. Updating an installed app requires a matching signing certificate to preserve its private data.

The standard Share APK path is `androidApp/build/outputs/apk/share/androidApp-share.apk`. Switching profiles overwrites that path. Named local Firebase/offline artifacts and matching mappings, when present, are recorded in [NOTES.md](../NOTES.md); old artifacts are not guaranteed current after rebuilding.

### Firebase Android setup

1. In [Firebase Console](https://console.firebase.google.com/), create/select a project and register an Android app using **com.xfiles**. An optional nickname is Xfiles Android; no iOS registration is required for this scope.
2. Put the downloaded client file at `androidApp/google-services.json`. The supplied file was validated and copied there; it is Git-ignored. Do not put a service-account private key in the mobile app.
3. Build/install the optional diagnostics APK. In About, enable Share crash diagnostics and accept the disclosure. Activation requires a new process: use Android Settings → Apps → Xfiles → Force stop, then reopen for a deterministic manual check.
4. Verify opt-out/restart, queued-report refusal and a synthetic report's actual receipt in the intended project. No report was sent by the coding agent during setup.
5. Preserve/upload the exact corresponding R8 mapping through an approved release step for readable obfuscated stacks. Local build mapping/symbol upload tasks are disabled.

The integration pins Firebase BoM 35.1.0, Google services plugin 4.5.0 and Crashlytics plugin 3.0.8. The filtered backend uses the bundled transport 5.0.0 APIs; upgrades must revalidate that filter and its report-event identifier. See [Firebase Android setup](https://firebase.google.com/docs/android/setup) and [opt-in Crashlytics guidance](https://firebase.google.com/docs/crashlytics/android/customize-crash-reports).

## Performance and resource limits

- Lazy lists and bounded native batches avoid rendering/loading entire libraries and documents at once. Parsing and media work use background workers with explicit resource ownership and cancellation.
- Cached metadata, background search/index work, bounded viewer images, Baseline/Startup Profiles and memory-pressure hooks reduce avoidable work. Later changes also reduce temporary search storage, UI-side serialization and repeated EPUB animation mutations.
- Thumbnail cache limits are separate from full-reader/PDF/image caches. APK size is not a measurement of runtime memory, battery use, startup time or smoothness.
- Recent offline and Firebase APKs compiled and passed lint. Static manifest/DEX checks verified profile boundaries; earlier benchmarks belong only to their recorded revisions/devices in NOTES.md.
- Physical low-memory/high-refresh devices, large folders/files, provider delays, repeated viewer entry/exit and animation/haptic tuning remain acceptance work.

## About and team

About includes supported-file information, Why Xfiles, appearance/haptic settings, assistant permissions, optional crash-diagnostics consent, privacy, feedback and component licenses.

| Team member | Contact |
|---|---|
| Kishore K | [kishoreabhi333@gmail.com](mailto:kishoreabhi333@gmail.com) |
| Robin Isac | [robinisacsharon@gmail.com](mailto:robinisacsharon@gmail.com) |

Manual crash sharing/feedback targets Kishore's address. No roles or biographies were invented. Branding source: [Xfiles logo](../docs/assets/xfiles-logo.svg). Bundled font license texts remain in the app assets.

## Known gaps and validation

- Full iOS app build/link/signing/device validation, iOS playback, and remaining iOS tool adapters.
- Actual Gemini distribution/voice and Siri acceptance, external share/print completion and provider/device behavior.
- Firebase consent lifecycle, actual project receipt and exact mapping upload; native signal/NDK crash coverage is not implemented.
- Physical safe-area/landscape/keyboard checks, paper-curl feel, haptics, accessibility, device codecs/subtitles, large media and memory pressure.
- PDF forms/signature verification, unsupported formats/encodings, archive extraction and other exclusions listed above.
- External security/advisory/license review and hostile-file/fuzz/device corpus coverage. Source safeguards do not constitute certification.

Recent changes have build/static verification with user-led runtime testing. Prepared regression scenarios are not evidence that tests were executed. No commits, deployment, installation or report upload were performed by the documentation consolidation.
