package com.xfiles.feature.tools.domain

import android.net.Uri
import com.xfiles.core.documents.ZipJobProgress
import com.xfiles.core.documents.ZipArchives
import com.xfiles.core.documents.ZipEntryInfo
import javax.inject.Inject

/** Compress picked files and folders into a zip in the chosen folder. */
class CompressToZipUseCase @Inject constructor(
    private val archives: ZipArchives,
) {
    suspend operator fun invoke(
        sources: List<Uri>,
        destinationTree: Uri,
        outputName: String,
        onProgress: (ZipJobProgress) -> Unit = {},
    ): Uri = archives.compress(sources, destinationTree, outputName.ensureZipName(), onProgress)
}

/** Lists a zip's entries for the picker. */
class ListZipEntriesUseCase @Inject constructor(
    private val archives: ZipArchives,
) {
    suspend operator fun invoke(archive: Uri): List<ZipEntryInfo> = archives.list(archive)
}

/** Extracts all entries (when [selected] is null) or the chosen entry names into the destination folder. */
class ExtractArchiveUseCase @Inject constructor(
    private val archives: ZipArchives,
) {
    suspend operator fun invoke(
        archive: Uri,
        destinationTree: Uri,
        selected: Set<String>? = null,
        onProgress: (ZipJobProgress) -> Unit = {},
    ): Int = archives.extract(archive, destinationTree, selected, onProgress)
}

private fun String.ensureZipName(): String {
    val trimmed = trim().ifEmpty { "Archive" }
    return if (trimmed.endsWith(".zip", ignoreCase = true)) trimmed else "$trimmed.zip"
}
