package com.xfiles.feature.library

import androidx.compose.ui.graphics.vector.ImageVector
import com.xfiles.design.EzIcons

/*
 * The words and lists of the About screen, kept apart from the layout so they are easy to edit.
 * Keep the supported formats in step with sharedLogic DocumentKind.kt and the engine's DocumentFormat.
 */

/** The privacy promises, in the words that are true for Xfiles. */
val PrivacyPromises: List<String> = listOf(
    "Documents are opened, searched and processed on this phone, without the internet. Documents never leave the phone.",
    "No ads, no accounts and no usage analytics.",
    "The internet is used only for the Fily model download you start, feedback you choose to send, and crash diagnostics you allow. " +
        "Sharing and printing send a file only where you choose.",
    "Feedback and crash reports go to Xfiles via Firestore. You can send a crash report yourself or opt in to automatic uploads.",
)

/** One group of supported files: its icon, extensions and, in plain words, what Xfiles does with them. */
data class SupportedGroup(val title: String, val icon: ImageVector, val extensions: String, val summary: String)

/** Every kind of file Xfiles opens (RTF is listed as a document kind but the engine does not open it, so it is left out). */
val SupportedFiles: List<SupportedGroup> = listOf(
    SupportedGroup("PDF", EzIcons.PdfFile, "pdf", "Pages, zoom, search, highlights and PDF tools"),
    SupportedGroup("Word", EzIcons.Document, "docx, docm, dotx, dotm", "Pages as designed, with their fonts, tables and pictures"),
    SupportedGroup("OpenDocument text", EzIcons.Document, "odt, ott", "Pages as designed"),
    SupportedGroup("Older Word", EzIcons.Document, "doc", "The text"),
    SupportedGroup("PowerPoint", EzIcons.Lines, "pptx, pptm, ppsx, potx", "Slides as designed"),
    SupportedGroup("OpenDocument slides", EzIcons.Lines, "odp", "Slides as designed"),
    SupportedGroup("Older PowerPoint", EzIcons.Lines, "ppt", "Slides, read from the file"),
    SupportedGroup("Excel", EzIcons.Table, "xlsx, xlsm, xltx", "Sheets with their formatting, as last saved"),
    SupportedGroup("Other spreadsheets", EzIcons.Table, "xls, ods, csv, tsv", "Sheets and tables"),
    SupportedGroup("E-books", EzIcons.Book, "epub", "Chapters, read like a book"),
    SupportedGroup("Web pages and Markdown", EzIcons.Code, "html, htm, md, markdown", "Rendered like a browser, with the source too"),
    SupportedGroup("Text and logs", EzIcons.TextT, "txt, log and other text", "Read, search and edit"),
    SupportedGroup("Code and data", EzIcons.Code, "json, xml, yaml, kt, py, js and more", "Line numbers and search"),
    SupportedGroup("Images", EzIcons.Image, "jpg, png, webp, gif, bmp, heic, avif", "Zoom, pan and rotate"),
    SupportedGroup(
        "Audio and video", EzIcons.Movie, "mp4, mkv, webm, mov, m4v, avi, mp3, m4a, aac, wav, flac, ogg, opus",
        "Played in Xfiles, using the codecs on your phone",
    ),
    SupportedGroup("ZIP archives", EzIcons.Archive, "zip", "A list of what's inside"),
)

data class Licence(val name: String, val licence: String)

/** The main components Xfiles ships, from its Gradle and Cargo files; each one's own notice carries the details. */
val Licences: List<Licence> = listOf(
    Licence("Kotlin, kotlinx.coroutines and kotlinx.serialization", "Apache License 2.0"),
    Licence("Jetpack Compose and Material 3", "Apache License 2.0"),
    Licence(
        "AndroidX Activity, Lifecycle, Navigation 3, CameraX, ExifInterface, WorkManager, Glance, Media3 (ExoPlayer) and ProfileInstaller",
        "Apache License 2.0",
    ),
    Licence("Media3 FFmpeg extension (Java sources; JNI bridge ported to Rust)", "Apache License 2.0"),
    Licence(
        "FFmpeg 7.1.2 audio decoders (libavcodec, libavutil, libswresample), built unmodified as separate shared libraries " +
            "that can be replaced; source: ffmpeg.org/releases/ffmpeg-7.1.2.tar.xz, build script: scripts/build-ffmpeg.sh",
        "GNU Lesser General Public License 2.1 or later",
    ),
    Licence("Dagger Hilt", "Apache License 2.0"),
    Licence("Material Symbols icons (path data)", "Apache License 2.0"),
    Licence("Google ML Kit Text Recognition (on-device model)", "ML Kit Terms of Service (not open source)"),
    Licence("UniFFI (Rust to Kotlin bindings)", "Mozilla Public License 2.0"),
    Licence("OxiKT (Kotlin binding generator for the Rust engine)", "See the project's repository"),
    Licence("JNA (Java Native Access)", "Apache License 2.0 or LGPL 2.1"),
    Licence("quick-xml and pulldown-cmark", "MIT License"),
    Licence("flate2, miniz_oxide and crc32fast", "MIT or Apache License 2.0"),
    Licence("RustCrypto aes, cbc, sha1, sha2, md-5 and rc4; getrandom, zeroize, serde_json and libc", "MIT or Apache License 2.0"),
    Licence("llama.cpp and ggml, through the llama-cpp-2 crate (on-device Fily engine)", "MIT License; llama-cpp-2 is MIT or Apache License 2.0"),
    Licence("Fily model, fine-tuned from Liquid AI LFM2.5-350M", "LFM Open License 1.0"),
    Licence("Cloud Firestore (configured Android builds)", "Apache License 2.0; Firebase service terms apply"),
)
