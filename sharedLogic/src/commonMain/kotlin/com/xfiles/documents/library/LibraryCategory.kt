package com.xfiles.documents.library

import com.xfiles.documents.code.SourceCodeExtensions
import com.xfiles.documents.code.SourceCodeFileNames

/** Library filter chips, in display order; the groups match EzFiles' library filters. */
enum class LibraryCategory(val label: String) {
    All("All"),
    Pdf("PDF"),
    Docs("Docs"),
    Slides("Slides"),
    Sheets("Sheets"),
    Books("Books"),
    Images("Images"),
    Media("Media"),
    Text("Text"),
    Code("Code"),
    Data("Data"),
    Archives("Archives"),
}

/**
 * The filter groups a file belongs to, by its extension (or whole name for
 * extensionless code files). Empty for files no group covers; they still show under All.
 */
fun libraryCategories(name: String): Set<LibraryCategory> {
    val extension = name.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "pdf" -> setOf(LibraryCategory.Pdf)
        "docx", "docm", "dotx", "dotm", "doc", "dot", "odt", "ott" -> setOf(LibraryCategory.Docs)
        "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "ppt", "pps", "pot", "odp", "otp" -> setOf(LibraryCategory.Slides)
        "epub" -> setOf(LibraryCategory.Books)
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif" -> setOf(LibraryCategory.Images)
        "xlsx", "xlsm", "xltx", "xltm", "xls", "xlt", "ods", "ots" -> setOf(LibraryCategory.Sheets)
        "csv", "tsv" -> setOf(LibraryCategory.Sheets, LibraryCategory.Data)
        "json", "geojson", "jsonl", "ndjson" -> setOf(LibraryCategory.Data)
        "xml", "svg", "plist" -> setOf(LibraryCategory.Data, LibraryCategory.Code)
        "zip" -> setOf(LibraryCategory.Archives)
        "html", "htm", "xhtml", "md", "markdown", "log", "txt", "text" -> setOf(LibraryCategory.Text)
        "mp4", "m4v", "mov", "3gp", "3g2", "webm", "mkv",
        "mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr", "mid", "midi" -> setOf(LibraryCategory.Media)
        else -> when {
            extension in SourceCodeExtensions -> setOf(LibraryCategory.Code)
            name.lowercase() in SourceCodeFileNames -> setOf(LibraryCategory.Code)
            else -> emptySet()
        }
    }
}
