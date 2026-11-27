package com.xfiles.documents

enum class DocumentKind {
    Text,
    Image,
    Pdf,
    Document,
    Audio,
    Video,
    Unknown,
}

fun isArchiveName(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "zip", "tar", "tgz", "7z" -> true
        "gz" -> name.endsWith(".tar.gz", ignoreCase = true)
        else -> false
    }
}

fun documentKindFromName(name: String): DocumentKind = when (name.substringAfterLast('.', "").lowercase()) {
    "gz" -> if (name.endsWith(".tar.gz", ignoreCase = true)) DocumentKind.Document else DocumentKind.Unknown
    "txt", "md", "markdown", "csv", "tsv", "log", "json", "xml", "kt", "kts", "rs", "py", "js", "ts", "yaml", "yml", "toml", "ini", "java", "c", "h", "cpp", "sh" -> DocumentKind.Text
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heif", "heic", "avif", "ico" -> DocumentKind.Image
    "pdf" -> DocumentKind.Pdf
    "doc", "docx", "docm", "dotx", "dotm", "xls", "xlsx", "xlsm", "xltx", "ppt", "pptx", "pptm",
    "ppsx", "potx", "epub", "zip", "tar", "tgz", "7z", "rtf", "odt", "ott", "ods", "odp", "html", "htm" -> DocumentKind.Document
    "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus" -> DocumentKind.Audio
    "mp4", "mkv", "webm", "mov", "avi", "m4v" -> DocumentKind.Video
    else -> DocumentKind.Unknown
}

/** Structured text sent with an application/ MIME type; the name's extension, when present, still decides first. */
private val TEXT_MIMES = setOf(
    "application/json", "application/xml", "application/yaml", "application/x-yaml", "application/toml",
    "application/sql", "application/javascript", "application/x-javascript", "application/x-sh",
)

/** Legacy binary Office (OLE/CFB compound files), macro-enabled and template types, and other packaged documents. */
private val DOCUMENT_MIMES = setOf(
    "application/msword", "application/x-ole-storage", "application/x-cfb", "application/cdfv2",
    "application/x-zip-compressed", "application/xhtml+xml", "application/rtf", "application/epub+zip",
)
private const val OFFICE_MIME_PREFIX = "application/vnd.ms-"
private const val OPEN_DOCUMENT_MIME_PREFIX = "application/vnd.oasis.opendocument."
private const val OOXML_MIME_PREFIX = "application/vnd.openxmlformats-officedocument."

fun documentKind(name: String, mimeType: String?): DocumentKind {
    val mime = mimeType?.substringBefore(';')?.lowercase().orEmpty()
    val namedKind = documentKindFromName(name)
    return when {
        mime == "application/pdf" -> DocumentKind.Pdf
        mime.startsWith("image/") -> DocumentKind.Image
        mime.startsWith("audio/") -> DocumentKind.Audio
        mime.startsWith("video/") -> DocumentKind.Video
        namedKind != DocumentKind.Unknown -> namedKind
        mime.startsWith("text/") || mime in TEXT_MIMES -> DocumentKind.Text
        mime in DOCUMENT_MIMES || mime.startsWith(OFFICE_MIME_PREFIX) ||
            mime.startsWith(OPEN_DOCUMENT_MIME_PREFIX) || mime.startsWith(OOXML_MIME_PREFIX) -> DocumentKind.Document
        else -> DocumentKind.Unknown
    }
}
