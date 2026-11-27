package com.xfiles.fily

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class FilyDocument(
    val id: String,
    val name: String,
    val sizeBytes: Long? = null,
    val modifiedAt: Long? = null,
    val openedAt: Long? = null,
)

/** [pageStarts] are character offsets of PDF pages, so passages can be labelled "[page N]". */
data class FilyExcerpt(val text: String, val truncated: Boolean, val pageStarts: List<Int> = emptyList())

data class FilyBookmark(val id: String, val document: FilyDocument, val pageNumber: Int?)

/** What Fily may read. Every call stays within the folders and files the user already gave Xfiles. */
interface FilyDocuments {
    /** The open file, recents, then the linked folders' files, without duplicates. */
    fun all(): List<FilyDocument>
    fun recent(): List<FilyDocument>
    fun resolve(id: String): FilyDocument?
    suspend fun text(id: String, limit: Int): FilyExcerpt?
    fun bookmarks(): List<FilyBookmark> = emptyList()

    /** Files whose text mentions [query]; only called when content access is on. */
    suspend fun searchContent(query: String, limit: Int): List<FilyDocument> = emptyList()

    /**
     * The passages of [id] that answer [question] within [budget] characters, from a cached per-document index.
     * Null when retrieval is unavailable for the file, so the caller uses the plain excerpt.
     */
    suspend fun retrieve(id: String, question: String, budget: Int): FilyRetrieval? = null
}

enum class FilyTool { ScanToPdf, ImageToText, NewText, TextToPdf, ImagesToPdf, TidyUp, PdfTools }

/** Screens Fily may open. Each is a review: printing, saving and every PDF change still need the user's tap. */
sealed interface FilyDestination {
    data class Open(val documentId: String, val page: Int? = null) : FilyDestination
    data class Print(val documentId: String) : FilyDestination
    data class Share(val documentIds: List<String>) : FilyDestination
    data class AddBookmark(val documentId: String, val page: Int) : FilyDestination
    data class Tool(
        val tool: FilyTool,
        val documentIds: List<String> = emptyList(),
        val pdfOperation: String? = null,
        val draftText: String? = null,
        val draftTitle: String? = null,
        val startScan: Boolean = false,
    ) : FilyDestination
}

enum class FilyHandoff(val failure: String? = null) {
    Accepted,
    Busy("busy"),
    AwaitingEditorDecision("editor_confirmation_needed"),
    UnsavedAnnotations("unsaved_annotations"),
    Unavailable("not_available"),
}

fun interface FilyNavigator {
    suspend fun go(destination: FilyDestination): FilyHandoff
}

data class FilyTidySummary(
    val available: Boolean,
    val duplicateGroups: Int = 0,
    val similarDocumentGroups: Int = 0,
    val similarPhotoGroups: Int = 0,
    val reclaimableBytes: Long = 0,
    val scannedAt: Long? = null,
)

fun interface FilyTidy {
    suspend fun summary(): FilyTidySummary
}

/**
 * Runs the model's tool calls and returns compact JSON shaped like the tool results in the training
 * data. Reads go through [documents]; screens go through [navigator] after the access policy is checked
 * again, so turning Fily off mid-request stops the hand-off.
 */
class ToolExecutor(
    private val access: AssistantAccess,
    private val documents: FilyDocuments,
    private val reader: ReaderControl,
    private val navigator: FilyNavigator,
    private val tidy: FilyTidy? = null,
) {
    val supported: Set<String> = setOf(
        "controlReader",
        "searchLibrary", "searchDocuments", "listRecentDocuments", "getDocumentInfo",
        "getDocumentExcerpt", "findTextInDocument", "openDocument", "listBookmarks", "openBookmark", "addBookmark",
        "prepareShareDocument", "prepareShareDocuments", "preparePrintDocument",
        "openScanner", "openImageToText", "openTextEditor", "createTextDocument", "openTextToPdf", "createPdfFromText",
        "openImagesToPdf", "createPdfFromImages", "openPdfTools", "mergePdfDocuments", "extractPdfPages",
        "prepareOrganizePdf", "prepareResizePdf", "preparePdfToImages", "preparePdfToText", "preparePdfToZip",
        "openPasswordTool", "openTidyUp", "openSimilarDocuments", "startTidyScan",
    ) + if (tidy != null) setOf("getTidyReport") else emptySet()

    /** The model sees "doc_1" instead of long URIs: shorter to read and cite, and what it was trained on. */
    private val aliases = LinkedHashMap<String, String>()
    private val originals = HashMap<String, String>()
    private val names = HashMap<String, String>()

    /** The user's words for the current request; excerpts pick the passages that answer them. */
    var question: String = ""

    fun alias(documentId: String): String = aliases.getOrPut(documentId) { "doc_${aliases.size + 1}".also { originals[it] = documentId } }

    fun nameOf(alias: String): String? = names[alias] ?: resolve(alias)?.name

    suspend fun execute(name: String, args: JsonObject?): String = execute(name, args) {}

    internal suspend fun execute(
        name: String, args: JsonObject?, evidence: (List<DocumentEvidence>) -> Unit,
    ): String {
        if (name !in supported) return failed("unsupported_tool")
        val arguments = args ?: JsonObject(emptyMap())
        val needsContent = name in CONTENT_TOOLS
        return try {
            access.authorized(needsContent) {
                when (name) {
                    "searchLibrary" -> libraryQuery(arguments).let { query -> listed(withContentFallback(query.query, search(query))) }
                    "searchDocuments" -> arguments.string("query").orEmpty().let { query -> listed(withContentFallback(query, search(LibraryQuery(query)))) }
                    "listRecentDocuments" -> listed(documents.recent())
                    "getDocumentInfo" -> info(document(arguments.required("documentId"))).toString()
                    "getDocumentExcerpt" -> excerpt(document(arguments.required("documentId")), arguments.int("maxChars"), evidence)
                    "findTextInDocument" -> findText(document(arguments.required("documentId")), arguments.required("query"), evidence)
                    "openDocument" -> openDocument(arguments.required("documentId"))
                    "listBookmarks" -> bookmarks(arguments.string("documentId"))
                    "openBookmark" -> openBookmark(arguments.required("bookmarkId"))
                    "addBookmark" -> addBookmark(document(arguments.required("documentId")), arguments.int("pageNumber") ?: throw MissingArgument())
                    // Opens the system share sheet; the user still picks the app, and it gets read access only.
                    "prepareShareDocument" -> document(arguments.required("documentId")).let { doc ->
                        handoff("chooser_opened", FilyDestination.Share(listOf(doc.id))) { put("documentId", alias(doc.id)) }
                    }
                    "prepareShareDocuments" -> arguments.ids("documentIds").map(::document).let { docs ->
                        require(docs.isNotEmpty())
                        handoff("chooser_opened", FilyDestination.Share(docs.map(FilyDocument::id)))
                    }
                    "preparePrintDocument" -> document(arguments.required("documentId")).let { doc ->
                        handoff("print_preview_opened", FilyDestination.Print(doc.id)) { put("documentId", alias(doc.id)) }
                    }
                    "openScanner" -> tool(FilyTool.ScanToPdf)
                    "openImageToText" -> tool(FilyTool.ImageToText)
                    "openTextEditor" -> tool(FilyTool.NewText)
                    "openTextToPdf" -> tool(FilyTool.TextToPdf)
                    "openImagesToPdf", "createPdfFromImages" -> tool(FilyTool.ImagesToPdf)
                    "openTidyUp", "openSimilarDocuments" -> tool(FilyTool.TidyUp)
                    "startTidyScan" -> handoff("scan_started", FilyDestination.Tool(FilyTool.TidyUp, startScan = true))
                    "createTextDocument" -> draft(FilyTool.NewText, arguments.required("text"), arguments.string("title").orEmpty())
                    "createPdfFromText" -> draft(FilyTool.TextToPdf, arguments.required("text"), arguments.string("title").orEmpty())
                    "openPdfTools" -> pdfTool(null, arguments.string("documentId"))
                    "mergePdfDocuments" -> mergePdfs(arguments.ids("documentIds"))
                    "extractPdfPages" -> pdfTool("Split", arguments.string("documentId"))
                    "prepareOrganizePdf" -> pdfTool("Organize", arguments.string("documentId"))
                    "prepareResizePdf" -> pdfTool("PageSize", arguments.string("documentId"))
                    "preparePdfToImages" -> pdfTool("ToImages", arguments.string("documentId"))
                    "preparePdfToText" -> pdfTool("ToText", arguments.string("documentId"))
                    "preparePdfToZip" -> pdfTool("ToZip", arguments.string("documentId"))
                    "openPasswordTool" -> pdfTool("AddPassword", arguments.string("documentId"))
                    "getTidyReport" -> tidyReport()
                    "controlReader" -> reader.perform(arguments.required("operation"), (arguments["value"] as? JsonPrimitive)?.content)
                    else -> failed("unsupported_tool")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AssistantAccessDenied) {
            failed(if (access.enabled.value) "content_restricted" else "access_off")
        } catch (_: MissingArgument) {
            failed("missing_arguments")
        } catch (_: Exception) {
            failed("not_available")
        }
    }

    // --- Reading --------------------------------------------------------------------------------

    /** Name search over the library as a lazy pipeline: filter, score, sort, take. */
    private fun search(query: LibraryQuery): List<FilyDocument> {
        val words = query.query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
        val exclude = query.exclude.map(String::lowercase)
        val scored = documents.all().asSequence()
            .filter { doc -> query.extensions.isEmpty() || doc.name.substringAfterLast('.', "").lowercase() in query.extensions }
            .filter { doc -> query.anyOf.isEmpty() || query.anyOf.any { doc.name.contains(it, ignoreCase = true) } }
            .filter { doc -> exclude.none { doc.name.contains(it, ignoreCase = true) } }
            .filter { doc -> query.modifiedAfter == null || (doc.modifiedAt ?: 0) >= query.modifiedAfter }
            .filter { doc -> query.modifiedBefore == null || (doc.modifiedAt ?: Long.MAX_VALUE) < query.modifiedBefore }
            .filter { doc -> query.minBytes == null || (doc.sizeBytes ?: 0) >= query.minBytes }
            .map { doc ->
                val name = doc.name.lowercase()
                doc to if (words.isEmpty()) 1 else words.count { it in name } + if (query.query.isNotBlank() && query.query.lowercase() in name) words.size else 0
            }
            .filter { (_, score) -> score > 0 }
        return when (query.sort) {
            Sort.ModifiedNewest -> scored.sortedByDescending { it.first.modifiedAt ?: 0 }
            Sort.OpenedNewest -> scored.sortedByDescending { it.first.openedAt ?: 0 }
            Sort.SizeLargest -> scored.sortedByDescending { it.first.sizeBytes ?: 0 }
            Sort.Relevance -> scored.sortedByDescending { it.second }
        }.map { it.first }.take(RESULT_LIMIT + 1).toList()
    }

    /** No name matched: look inside files ("the one that mentions INR 48,000"), if text may be read. */
    private suspend fun withContentFallback(query: String, byName: List<FilyDocument>): List<FilyDocument> =
        if (byName.isNotEmpty() || query.isBlank() || !access.contentAccess.value) byName
        else documents.searchContent(query, RESULT_LIMIT + 1).ifEmpty { byName }

    private suspend fun excerpt(
        doc: FilyDocument, maxChars: Int?, evidence: (List<DocumentEvidence>) -> Unit,
    ): String {
        val budget = (maxChars ?: MAX_EXCERPT_CHARS).coerceIn(1, MAX_EXCERPT_CHARS)
        val id = alias(doc.id)
        var fitsWhole = false
        if (question.isNotBlank() && !Passages.namesPage(question)) {
            val retrieved = documents.retrieve(doc.id, question, budget)
            fitsWhole = retrieved is FilyRetrieval.Whole
            if (retrieved is FilyRetrieval.Passages && retrieved.passages.isNotEmpty()) {
                val packed = FilyPassages.pack(retrieved.passages, budget)
                evidence(packed.passages.map { DocumentEvidence(id, it.page, it.text) })
                return excerptJson(id, packed.text, available = true, truncated = retrieved.omitted)
            }
        }
        val full = documents.text(doc.id, SOURCE_CHARS)
            ?: return excerptJson(id, "", available = false, truncated = false)
        val (text, passages, cut) = if (fitsWhole || question.isBlank()) {
            val visible = full.text.take(budget)
            Triple(visible, FilyPassages.pages(visible, full.pageStarts), visible.length < full.text.length)
        } else {
            val (selected, incomplete) = Passages.select(full.text, question, budget, full.pageStarts)
            val packed = FilyPassages.pack(selected, budget)
            Triple(packed.text, packed.passages, incomplete)
        }
        evidence(passages.map { DocumentEvidence(id, it.page, it.text) })
        return excerptJson(id, text, available = true, truncated = cut || full.truncated)
    }

    private fun excerptJson(id: String, text: String, available: Boolean, truncated: Boolean): String =
        buildJsonObject {
            put("documentId", id)
            put("text", text)
            put("available", available)
            put("truncated", truncated)
        }.toString()

    private suspend fun findText(
        doc: FilyDocument, query: String, evidence: (List<DocumentEvidence>) -> Unit,
    ): String {
        val needle = query.take(256)
        val id = alias(doc.id)
        val full = documents.text(doc.id, SOURCE_CHARS)
        val text = full?.text.orEmpty()
        val starts = full?.pageStarts.orEmpty()
        val excerpts = mutableListOf<DocumentEvidence>()
        val matches = generateSequence(text.indexOf(needle, ignoreCase = true).takeIf { it >= 0 }) { previous ->
            text.indexOf(needle, previous + 1, ignoreCase = true).takeIf { it >= 0 }
        }.take(5).map { found ->
            val page = starts.indexOfLast { it <= found }
            val pageStart = starts.getOrNull(page)?.coerceIn(0, found) ?: 0
            val pageEnd = starts.getOrNull(page + 1)?.coerceIn(found, text.length) ?: text.length
            val snippet = text.substring((found - 70).coerceAtLeast(pageStart), (found + needle.length + 70).coerceAtMost(pageEnd))
            excerpts += DocumentEvidence(id, (page + 1).takeIf { page >= 0 }, snippet)
            buildJsonObject {
                put("offset", found)
                put("snippet", snippet)
                if (page >= 0) put("page", page + 1)
            }
        }.toList()
        evidence(excerpts)
        return buildJsonObject {
            put("documentId", id)
            put("matches", JsonArray(matches))
            put("available", full != null)
            put("partial", full?.truncated ?: false)
        }.toString()
    }

    private fun bookmarks(documentId: String?): String {
        val wanted = documentId?.let { resolve(it)?.id }
        val list = documents.bookmarks().asSequence().filter { wanted == null || it.document.id == wanted }.take(RESULT_LIMIT).toList()
        return buildJsonObject {
            put("bookmarks", JsonArray(list.map { mark ->
                buildJsonObject {
                    put("bookmarkId", mark.id)
                    put("documentId", alias(mark.document.id).also { names[it] = mark.document.name })
                    put("name", mark.document.name)
                    mark.pageNumber?.let { put("pageNumber", it) }
                }
            }))
        }.toString()
    }

    private suspend fun tidyReport(): String {
        val report = requireNotNull(tidy).summary()
        return buildJsonObject {
            put("available", report.available)
            put("partial", false)
            put("duplicateGroups", report.duplicateGroups)
            put("similarDocumentGroups", report.similarDocumentGroups)
            put("similarPhotoGroups", report.similarPhotoGroups)
            put("reclaimableBytes", report.reclaimableBytes)
            report.scannedAt?.let { put("scannedAt", isoDate(it)) }
        }.toString()
    }

    // --- Hand-offs ------------------------------------------------------------------------------

    /** Shows a screen once the access policy is confirmed unchanged since this request started. */
    private suspend fun handoff(status: String, destination: FilyDestination, extra: JsonObjectBuilder.() -> Unit = {}): String {
        val authorization = access.captureAuthorization()
        access.recheckAuthorization(authorization)
        navigator.go(destination).failure?.let { return failed(it) }
        return buildJsonObject {
            put("status", status)
            extra()
        }.toString()
    }

    private suspend fun openDocument(said: String): String {
        val doc = resolve(said) ?: return openByName(said) ?: failed("not_available")
        return handoff("opened", FilyDestination.Open(doc.id)) { put("documentId", alias(doc.id).also { names[it] = doc.name }) }
    }

    /**
     * The model made up an ID from the user's words ("resume_1"): search the names for those words.
     * One match opens; several go back to the model as search results, so it can ask which one.
     */
    private suspend fun openByName(said: String): String? {
        if (said.isBlank() || said in originals) return null
        val words = said.substringBeforeLast('.').split(Regex("[^A-Za-z]+")).filter { it.length >= 2 }.joinToString(" ")
        if (words.isBlank()) return null
        val found = search(LibraryQuery(words))
        return when (found.size) {
            0 -> null
            1 -> openDocument(alias(found.single().id))
            else -> listed(found)
        }
    }

    private suspend fun openBookmark(bookmarkId: String): String {
        val mark = documents.bookmarks().firstOrNull { it.id == bookmarkId } ?: return failed("not_available")
        return handoff("opened", FilyDestination.Open(mark.document.id, mark.pageNumber)) { put("bookmarkId", bookmarkId) }
    }

    private suspend fun addBookmark(doc: FilyDocument, pageNumber: Int): String {
        require(doc.name.endsWith(".pdf", ignoreCase = true) && pageNumber in 1..100_000)
        return handoff("review_pending", FilyDestination.AddBookmark(doc.id, pageNumber)) { put("documentId", alias(doc.id)) }
    }

    private suspend fun tool(tool: FilyTool) = handoff("opened", FilyDestination.Tool(tool))

    private suspend fun draft(tool: FilyTool, text: String, title: String) =
        handoff("draft_ready", FilyDestination.Tool(tool, draftText = text.take(MAX_DRAFT_CHARS), draftTitle = title.take(120)))

    /** [operation] null opens the PDF tools list. */
    private suspend fun pdfTool(operation: String?, documentId: String?): String {
        val doc = documentId?.takeIf(String::isNotBlank)?.let(::document)
        require(doc == null || doc.name.endsWith(".pdf", ignoreCase = true))
        return handoff(if (operation == null) "opened" else "review_pending", FilyDestination.Tool(FilyTool.PdfTools, listOfNotNull(doc?.id), operation))
    }

    private suspend fun mergePdfs(ids: List<String>): String {
        val docs = ids.map(::document)
        require(docs.all { it.name.endsWith(".pdf", ignoreCase = true) })
        return handoff("review_pending", FilyDestination.Tool(FilyTool.PdfTools, docs.map(FilyDocument::id), "Merge"))
    }

    // --- Helpers --------------------------------------------------------------------------------

    private fun resolve(said: String?): FilyDocument? = said?.let { originals[it] ?: it }?.let(documents::resolve)

    private fun document(said: String): FilyDocument = resolve(said) ?: throw IllegalArgumentException("Unknown document")

    private fun info(doc: FilyDocument) = buildJsonObject {
        put("documentId", alias(doc.id).also { names[it] = doc.name })
        put("name", doc.name)
        doc.sizeBytes?.let { put("sizeBytes", it) }
        doc.modifiedAt?.let { put("modified", isoDate(it)) }
    }

    private fun listed(results: List<FilyDocument>): String = buildJsonObject {
        put("documents", JsonArray(results.take(RESULT_LIMIT).map { doc ->
            buildJsonObject {
                put("id", alias(doc.id).also { names[it] = doc.name })
                put("name", doc.name)
            }
        }))
        put("partial", results.size > RESULT_LIMIT)
    }.toString()

    private fun libraryQuery(args: JsonObject) = LibraryQuery(
        query = args.string("query").orEmpty(),
        anyOf = args.string("category")?.let(::categoryKeywords).orEmpty(),
        extensions = args.string("fileType")?.let(::extensionsFor).orEmpty(),
        exclude = (args["exclude"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
        modifiedAfter = args.string("after")?.let(::parseIsoDate),
        modifiedBefore = args.string("before")?.let(::parseIsoDate),
        minBytes = (args["minBytes"] as? JsonPrimitive)?.longOrNull,
        sort = when (args.string("sort")) {
            "modified_desc" -> Sort.ModifiedNewest
            "opened_desc" -> Sort.OpenedNewest
            "size_desc" -> Sort.SizeLargest
            else -> Sort.Relevance
        },
    )

    private class MissingArgument : Exception()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.required(key: String): String = string(key)?.takeIf(String::isNotBlank) ?: throw MissingArgument()

    private fun JsonObject.ids(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

    private fun failed(reason: String): String = buildJsonObject {
        put("status", "failed")
        put("reason", reason)
    }.toString()

    private companion object {
        const val RESULT_LIMIT = 8
        // Fits the 4,096-token context with the prompt, tools and history (about 4 characters per token).
        const val MAX_EXCERPT_CHARS = 3000
        const val SOURCE_CHARS = 60_000
        const val MAX_DRAFT_CHARS = 20_000
        val CONTENT_TOOLS = setOf("getDocumentExcerpt", "findTextInDocument")
    }
}

internal enum class Sort { Relevance, ModifiedNewest, OpenedNewest, SizeLargest }

internal data class LibraryQuery(
    val query: String,
    val anyOf: List<String> = emptyList(),
    val extensions: Set<String> = emptySet(),
    val exclude: List<String> = emptyList(),
    val modifiedAfter: Long? = null,
    val modifiedBefore: Long? = null,
    val minBytes: Long? = null,
    val sort: Sort = Sort.Relevance,
)

/** Name keywords for the categories the model asks for; names only, never file contents. */
internal fun categoryKeywords(category: String): List<String> = when (val key = category.lowercase().trim()) {
    "resume", "resumes", "cv", "cvs" -> listOf("resume", "cv", "curriculum", "biodata")
    "invoice", "invoices", "bill", "bills" -> listOf("invoice", "bill")
    "receipt", "receipts" -> listOf("receipt")
    "ticket", "tickets", "travel" -> listOf("ticket", "boarding", "itinerary", "pnr")
    "statement", "statements", "bank" -> listOf("statement", "bank")
    "id", "ids", "identity" -> listOf("aadhaar", "aadhar", "passport", "pan", "licence", "license", "voter")
    "certificate", "certificates" -> listOf("certificate", "cert")
    "contract", "contracts", "agreement" -> listOf("contract", "agreement")
    "report", "reports" -> listOf("report")
    else -> listOf(key)
}

internal fun extensionsFor(fileType: String): Set<String> = when (val type = fileType.lowercase().trim().removePrefix(".")) {
    "pdf" -> setOf("pdf")
    "word", "doc", "docx", "document" -> setOf("doc", "docx", "odt", "rtf")
    "excel", "sheet", "spreadsheet", "xls", "xlsx", "csv" -> setOf("xls", "xlsx", "csv", "ods")
    "powerpoint", "slides", "presentation", "ppt", "pptx" -> setOf("ppt", "pptx", "odp")
    "image", "photo", "picture", "jpg", "jpeg", "png" -> setOf("jpg", "jpeg", "png", "webp", "heic", "gif")
    "text", "txt", "note", "markdown", "md" -> setOf("txt", "md")
    "video" -> setOf("mp4", "mkv", "webm", "mov")
    "audio", "music" -> setOf("mp3", "m4a", "wav", "ogg", "flac")
    else -> setOf(type)
}

/** `YYYY-MM-DD` to UTC epoch milliseconds, or null when malformed. */
internal fun parseIsoDate(value: String): Long? {
    val parts = value.trim().take(10).split('-')
    if (parts.size != 3) return null
    val (y, m, d) = parts.map { it.toIntOrNull() ?: return null }
    if (m !in 1..12 || d !in 1..31) return null
    return daysFromCivil(y, m, d) * 86_400_000L
}

internal fun isoDate(epochMillis: Long): String {
    val (y, m, d) = civilFromDays(epochMillis.floorDiv(86_400_000L))
    return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}

// Howard Hinnant's civil-calendar algorithms; no date library is available in common code.
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = y.floorDiv(400)
    val yoe = y - era * 400
    val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
}

private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val z = days + 719_468L
    val era = z.floorDiv(146_097L)
    val doe = (z - era * 146_097L).toInt()
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = (yoe + era * 400).toInt() + if (month <= 2) 1 else 0
    return Triple(year, month, day)
}
