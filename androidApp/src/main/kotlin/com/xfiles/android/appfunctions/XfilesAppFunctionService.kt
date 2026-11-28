package com.xfiles.android.appfunctions

import android.app.PendingIntent
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionSchemaCapability
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Xfiles' AppFunctions for Android 16+ assistants. The KSP compiler generates `XfilesAppFunctionService` from
 * this class and its XML schema, and the manifest registers it. Each function is a thin adapter over the Fily
 * tools ([FilyToolHost]), so it obeys the same switches: Fily off refuses everything, and reading inside files
 * needs the content switch.
 *
 * Data functions return the compact JSON Fily returns. Screen functions (open, scan, new note) return a [ScreenHandoff]
 * that carries a [PendingIntent] for the screen, through the AppFunctionOpenable schema capability: the agent launches it, the user reviews
 * the screen, and nothing is saved, shared or printed until the user acts there.
 *
 * AppFunctions exist only from Android 16 (API 36); on older devices the platform never binds this service.
 */
@RequiresApi(36)
@AndroidEntryPoint
@AppFunctionServiceEntryPoint(
    serviceName = "XfilesAppFunctionService",
    appFunctionXmlFileName = "xfiles_app_function_service",
)
abstract class BaseXfilesAppFunctionService : AppFunctionService() {
    @Inject
    internal lateinit var tools: FilyToolHost

    /**
     * Finds documents by name, and by kind when asked. Searches the open file, recent files and the folders the
     * user linked. Returns up to eight matches, each with an id to pass to the other functions.
     *
     * @param query Words from the file's name, such as "tax return 2025" or "electricity".
     * @param category Optional kind of document, such as "invoice", "resume", "receipt", "ticket" or "statement".
     * @param fileType Optional file type, such as "pdf", "word", "excel", "image" or "text".
     * @return JSON with a `documents` list of `id` and `name`, and `partial` when there are more matches.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun searchFiles(query: String, category: String?, fileType: String?): String =
        tools.run(
            "searchLibrary",
            buildJsonObject {
                put("query", query)
                category?.let { put("category", it) }
                fileType?.let { put("fileType", it) }
            },
        )

    /**
     * Lists the most recently opened documents, newest first.
     *
     * @return JSON with a `documents` list of `id` and `name`.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun listRecentFiles(): String = tools.run("listRecentDocuments", buildJsonObject { })

    /**
     * Gives the name, size and modified date of one document, without its contents.
     *
     * @param documentId The `id` from searchFiles or listRecentFiles.
     * @return JSON with `documentId`, `name`, and when known `sizeBytes` and `modified`.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getFileInfo(documentId: String): String =
        tools.run("getDocumentInfo", buildJsonObject { put("documentId", documentId) })

    /**
     * Opens a document in Xfiles' reader for the user to read. Returns the screen to launch; nothing is changed.
     *
     * @param documentId The `id` from searchFiles or listRecentFiles.
     * @return The screen that opens the document.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun openFile(documentId: String): ScreenHandoff =
        ScreenHandoff(tools.handoff("openDocument", buildJsonObject { put("documentId", documentId) }))

    /**
     * Gives the opening text of a document so the assistant can summarize it "at a glance". This reads the file's
     * contents, so it works only when the user allowed Fily to read inside files. Text is bounded to a few thousand
     * characters; `truncated` says when there is more.
     *
     * @param documentId The `id` from searchFiles or listRecentFiles.
     * @return JSON with `text`, `available`, and `truncated`; or `failed` with `content_restricted` when reading
     *   inside files is off.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun summarizeFile(documentId: String): String =
        tools.run("getDocumentExcerpt", buildJsonObject { put("documentId", documentId) })

    /**
     * Returns the screen that opens a new text note in Xfiles' editor, prefilled with the text. The user
     * reviews it and taps save; nothing is saved until then.
     *
     * @param text The note's text.
     * @param title Optional note title, such as "Meeting notes"; the file is named from it.
     * @return The screen that opens the editor with the note.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun createNote(text: String, title: String?): ScreenHandoff =
        ScreenHandoff(
            tools.handoff(
                "createTextDocument",
                buildJsonObject {
                    put("text", text)
                    title?.let { put("title", it) }
                },
            ),
        )

    /**
     * Returns the screen that opens Xfiles' scanner, so the user can scan pages into a PDF. Scanning starts
     * only when the user taps capture.
     *
     * @return The screen that opens the scanner.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun scanToPdf(): ScreenHandoff =
        ScreenHandoff(tools.handoff("openScanner", buildJsonObject { }))
}

/**
 * Schema capability for a function result that opens a screen. The compiler records intentToOpen as a
 * capability, separate from the data, so the agent can launch the screen without reading any data from it.
 */
@AppFunctionSchemaCapability
interface AppFunctionOpenable {
    val intentToOpen: PendingIntent
}

/** A screen for the user to review. The agent launches [intentToOpen]; nothing changes until the user acts there. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class ScreenHandoff(
    /** The screen to launch. */
    override val intentToOpen: PendingIntent,
) : AppFunctionOpenable
