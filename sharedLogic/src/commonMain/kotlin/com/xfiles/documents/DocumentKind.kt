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

fun documentKindFromName(name: String): DocumentKind = when (name.substringAfterLast('.', "").lowercase()) {
    "txt", "md", "markdown", "csv", "tsv", "log", "json", "xml", "kt", "kts", "rs", "py", "js", "ts", "yaml", "yml", "toml", "ini", "java", "c", "h", "cpp", "sh" -> DocumentKind.Text
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heif", "heic", "avif", "ico" -> DocumentKind.Image
    "pdf" -> DocumentKind.Pdf
    "doc", "docx", "docm", "dotx", "dotm", "xls", "xlsx", "xlsm", "xltx", "ppt", "pptx", "pptm",
    "ppsx", "potx", "epub", "zip", "rtf", "odt", "ott", "ods", "odp", "html", "htm" -> DocumentKind.Document
    "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus" -> DocumentKind.Audio
    "mp4", "mkv", "webm", "mov", "avi", "m4v" -> DocumentKind.Video
    else -> DocumentKind.Unknown
}

fun documentKind(name: String, mimeType: String?): DocumentKind {
    val mime = mimeType?.substringBefore(';')?.lowercase().orEmpty()
    val namedKind = documentKindFromName(name)
    return when {
        mime == "application/pdf" -> DocumentKind.Pdf
        mime.startsWith("image/") -> DocumentKind.Image
        mime.startsWith("audio/") -> DocumentKind.Audio
        mime.startsWith("video/") -> DocumentKind.Video
        namedKind != DocumentKind.Unknown -> namedKind
        mime.startsWith("text/") -> DocumentKind.Text
        else -> DocumentKind.Unknown
    }
}
