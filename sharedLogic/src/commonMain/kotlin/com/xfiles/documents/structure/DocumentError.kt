package com.xfiles.documents.structure

/**
 * Why a document could not be read. Cancellation is not an error: it
 * surfaces as a coroutine `CancellationException`.
 */
sealed interface DocumentError {
    val detail: String

    /** The platform could not open the file (permission, missing, provider failure). */
    data class FileAccess(override val detail: String) : DocumentError

    /** The content is not a format the engine can read. */
    data class Unsupported(override val detail: String) : DocumentError

    /** The content claims a format but is damaged. */
    data class Malformed(override val detail: String) : DocumentError

    /** The file or its content exceeds a [ReadLimits] budget. */
    data class LimitExceeded(override val detail: String) : DocumentError

    /** The document is encrypted and no password was given. */
    data class PasswordRequired(override val detail: String) : DocumentError

    /** The document is encrypted and the password given did not unlock it. */
    data class WrongPassword(override val detail: String) : DocumentError

    /** The document is encrypted with a scheme the engine does not implement. */
    data class UnsupportedEncryption(override val detail: String) : DocumentError

    /** A defect in the app or engine. */
    data class Internal(override val detail: String) : DocumentError
}

class DocumentException(val error: DocumentError) : Exception(error.detail)
