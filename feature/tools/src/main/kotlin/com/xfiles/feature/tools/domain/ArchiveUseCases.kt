package com.xfiles.feature.tools.domain

import android.net.Uri
import com.xfiles.core.documents.ArchiveOutputFormat
import com.xfiles.core.documents.ZipArchives
import com.xfiles.core.documents.ZipJobProgress
import javax.inject.Inject

class CreateArchiveUseCase @Inject constructor(private val archives: ZipArchives) {
    suspend operator fun invoke(
        sources: List<Uri>, destination: Uri, name: String, format: ArchiveOutputFormat, password: String?,
        onProgress: (ZipJobProgress) -> Unit,
    ): Uri = archives.compress(sources, destination, name, format, password, onProgress)
}

class ListArchiveEntriesUseCase @Inject constructor(private val archives: ZipArchives) {
    suspend operator fun invoke(archive: Uri) = archives.list(archive)
}

class ExtractArchiveUseCase @Inject constructor(private val archives: ZipArchives) {
    suspend operator fun invoke(
        archive: Uri, destination: Uri, selected: Set<String>?, password: String?,
        onProgress: (ZipJobProgress) -> Unit,
    ): Int = archives.extract(archive, destination, selected, password, onProgress)
}
