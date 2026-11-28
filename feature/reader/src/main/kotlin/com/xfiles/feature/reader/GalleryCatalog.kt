package com.xfiles.feature.reader

import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FolderRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Lists the pictures that can be swiped through with an opened one. The flow emits an empty listing at once (the
 * opened picture shows without waiting), then the candidates. Candidates carry modifiedAt so both sorts work.
 *
 * Sources, in order: the opened picture's own folder when it lies in a linked folder (FolderRepository fills
 * modifiedAt from the provider listing); otherwise the recent list, whose times are queried off the main thread.
 * Listing is cancelled with the collector; sorting happens in [GallerySession].
 */
@Singleton
class GalleryCatalog @Inject constructor(
    private val folders: FolderRepository,
    private val documents: DocumentRepository,
) {
    fun listings(opened: FileEntry): Flow<List<FileEntry>> = flow {
        emit(emptyList())
        val beside = folders.imagesBeside(opened.uri, GallerySession.MAX_ENTRIES)
        emit(beside ?: documents.recentsWithModifiedTimes())
    }.flowOn(Dispatchers.Default)
}
