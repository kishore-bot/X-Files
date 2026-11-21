package com.xfiles.fily

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What to do after a tool ran, instead of asking the model to phrase it. */
internal sealed interface AfterTool {
    data class Say(val text: String, val citations: List<String> = emptyList(), val offered: List<ListedFile>? = null) : AfterTool
    /** Open this search result ("open my latest CV"). */
    data class Open(val documentId: String) : AfterTool
}

/**
 * Fixed, accurate sentences for action results: the small model often misreads a tool result
 * ("Access was turned off" for a text file without pages), and a second model call costs a second.
 * Content answers (excerpts, matches) still go back to the model.
 */
internal object FilyReplies {
    private val openWords = Regex("""\b(open|play|view|launch|start)\b""")
    private val otherAction = Regex(
        """\b(share|print|send|summar\w*|what|who|whom|when|how|why|does|did|is|are|tell|read|explain|bookmark|merge|split|convert|compress|password|delete|rename|move|copy|compare)\b""",
    )
    private val latestSorts = setOf("modified_desc", "opened_desc")

    fun afterTool(tool: String, args: JsonObject?, result: JsonObject, request: String, nameOf: (String) -> String?): AfterTool? {
        val status = result["status"]?.jsonPrimitive?.contentOrNull
        if (status == "failed") return failure(tool, args, result["reason"]?.jsonPrimitive?.contentOrNull)?.let { AfterTool.Say(it) }
        return when (tool) {
            "openDocument" -> result["documentId"]?.jsonPrimitive?.contentOrNull?.takeIf { status == "opened" }?.let { id ->
                AfterTool.Say("Opening ${nameOf(id)?.let(::spoken) ?: "it"}.", listOf(id))
            }
            "controlReader" -> if (status == "done") {
                val command = QuickCommand(args.string("operation") ?: return null, args.string("value"))
                AfterTool.Say(QuickCommands.reply(command, result.int("page"), result.int("pageCount"), result.string("sheet"), result.string("cell"), null))
            } else null
            "prepareShareDocument", "prepareShareDocuments" -> if (status == "chooser_opened") AfterTool.Say("Choose where to share it.") else null
            "preparePrintDocument" -> if (status == "print_preview_opened") AfterTool.Say("Here's the print preview.") else null
            "openScanner" -> opened(status, "Opening the scanner.")
            "openImageToText" -> opened(status, "Opening image to text.")
            "openTextEditor", "createTextDocument" -> opened(status, "Opening the text editor.")
            "openTextToPdf", "createPdfFromText" -> opened(status, "Opening text to PDF.")
            "openImagesToPdf", "createPdfFromImages" -> opened(status, "Opening images to PDF.")
            "openTidyUp", "openSimilarDocuments", "startTidyScan" -> opened(status, "Opening Tidy up.")
            "searchLibrary", "searchDocuments", "listRecentDocuments" -> documents(tool, args, result, request)
            else -> null
        }
    }

    private fun opened(status: String?, text: String): AfterTool? = if (status != null && status != "failed") AfterTool.Say(text) else null

    private fun documents(tool: String, args: JsonObject?, result: JsonObject, request: String): AfterTool? {
        val docs = result["documents"]?.jsonArray?.mapNotNull { item ->
            val doc = item.jsonObject
            ListedFile(doc.string("id") ?: return@mapNotNull null, doc.string("name") ?: return@mapNotNull null)
        } ?: return null
        val text = request.lowercase()
        val wantsOpen = openWords.containsMatchIn(text)
        // Questions and other actions on the results ("what does my CV say") need the model.
        if (!wantsOpen && otherAction.containsMatchIn(text.replace(Regex("""^(what|which) (files?|documents?|pdfs?)\b"""), ""))) return null
        if (docs.isEmpty()) return AfterTool.Say("I couldn't find a matching file.")
        val latest = tool == "listRecentDocuments" || args.string("sort") in latestSorts
        if (wantsOpen && (docs.size == 1 || latest)) return AfterTool.Open(docs.first().documentId)
        // "open my styled resume": the one result whose name has a word only it has.
        if (wantsOpen) namedIn(text, docs)?.let { return AfterTool.Open(it.documentId) }
        val shown = docs.take(3)
        val names = shown.map { spoken(it.name) }
        val list = when (names.size) {
            1 -> names[0]
            2 -> "${names[0]} or ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + " or " + names.last()
        }
        val more = result["partial"]?.jsonPrimitive?.contentOrNull == "true" || docs.size > shown.size
        return when {
            wantsOpen -> AfterTool.Say("I found ${count(docs.size, more)}: $list. Which one?", shown.map { it.documentId }, docs)
            docs.size == 1 -> AfterTool.Say("I found ${names[0]}.", listOf(docs[0].documentId), docs)
            else -> AfterTool.Say("I found ${count(docs.size, more)}. ${if (latest) "The latest are" else "The top ones are"} ${list.replace(" or ", " and ")}.",
                shown.map { it.documentId }, docs)
        }
    }

    private val ignored = setOf("open", "the", "my", "file", "document", "please", "show", "view", "play", "launch", "start", "fily", "one",
        "pdf", "and", "for", "with", "that", "this",
        // What kind of file, not which one: "open my resume" must not prefer old-resume.pdf over Kishore_CV.pdf.
        "resume", "resumes", "cv", "cvs", "invoice", "invoices", "bill", "bills", "contract", "contracts", "agreement", "receipt",
        "receipts", "notes", "note", "report", "reports", "statement", "photo", "photos", "video", "videos", "latest", "newest")

    /** The single result whose name contains more of the request's words than any other. */
    private fun namedIn(request: String, docs: List<ListedFile>): ListedFile? {
        val words = request.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in ignored }.toSet()
        if (words.isEmpty()) return null
        val scored = docs.map { doc ->
            val name = doc.name.lowercase().split(Regex("[^a-z0-9]+")).toSet()
            doc to words.count { it in name }
        }.sortedByDescending { it.second }
        val (best, score) = scored.first()
        return best.takeIf { score > 0 && scored.getOrNull(1)?.second != score }
    }

    private fun count(n: Int, more: Boolean) = if (more) "at least $n files" else "$n files"

    private fun failure(tool: String, args: JsonObject?, reason: String?): String? = when (reason) {
        "access_off" -> "Fily's file access is off. You can turn it on in Xfiles Info."
        "content_restricted" -> "I'm not allowed to read inside files. You can allow it in Xfiles Info."
        "not_available" -> "I couldn't find that file."
        "no_document" -> "Open a file first."
        "unsupported_here" -> when (args.string("operation")) {
            "go_to_page", "next_page", "previous_page" -> "This file has no pages. Say scroll down instead."
            "next_photo", "previous_photo", "rotate_photo" -> "This isn't a photo."
            "play", "pause", "seek" -> "This isn't a video or song."
            "select_cell", "next_sheet", "previous_sheet" -> "This isn't a spreadsheet."
            else -> "I can't do that in this file."
        }
        "missing_arguments" -> "Which file do you mean?"
        "unsupported_tool", null -> null
        else -> if (tool == "controlReader") {
            QuickCommands.reply(QuickCommand(args.string("operation") ?: "", args.string("value")), null, null, null, null, reason)
        } else null
    }

    /** "Kishore_K_Styled_Resume.pdf" → "Kishore K Styled Resume", as it should be read aloud. */
    fun spoken(name: String): String = name.substringBeforeLast('.').replace(Regex("""[_\-]+"""), " ").replace(Regex(" +"), " ").trim()
        .ifEmpty { name }

    private fun JsonObject?.string(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
}
