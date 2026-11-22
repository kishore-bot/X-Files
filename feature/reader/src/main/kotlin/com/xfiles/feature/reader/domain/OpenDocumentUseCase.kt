package com.xfiles.feature.reader.domain

import android.net.Uri
import com.xfiles.core.documents.DocumentRepository
import com.xfiles.core.documents.FilePreview
import com.xfiles.documents.structure.DocumentError
import com.xfiles.documents.structure.DocumentException
import com.xfiles.feature.reader.ReaderState
import javax.inject.Inject

/**
 * Describes and previews a file for the reader. A file that needs a password, cannot be opened, or is not
 * supported comes back as the matching [ReaderState]; any other read error is thrown to the caller.
 */
class OpenDocumentUseCase @Inject constructor(
    private val documents: DocumentRepository,
) {
    suspend operator fun invoke(uri: Uri, page: Int, password: String?): ReaderState {
        val file = documents.describe(uri)
        val preview = try {
            documents.preview(file, page, password)
        } catch (error: DocumentException) {
            return when (error.error) {
                is DocumentError.PasswordRequired -> ReaderState.Password(file, page, incorrect = false)
                is DocumentError.WrongPassword -> ReaderState.Password(file, page, incorrect = true)
                is DocumentError.UnsupportedEncryption -> ReaderState.Failure("This file uses an encryption format Xfiles cannot open.")
                is DocumentError.LimitExceeded -> ReaderState.Failure("This file exceeds the reader's size limit.")
                is DocumentError.Unsupported -> ReaderState.Ready(file, FilePreview.Unsupported)
                else -> ReaderState.ReadError
            }
        }
        return ReaderState.Ready(file, preview)
    }
}
