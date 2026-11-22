package com.xfiles.fily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FilyPassagesTest {
    private val lease = FilyDocument("u1", "Lease.pdf", 5_000, modifiedAt = 1_000)

    private fun executor(retrieval: FilyRetrieval?, text: String, question: String): ToolExecutor {
        val store = HashMap<String, String>()
        val access = AssistantAccess(object : FilyPreferences {
            override fun get(key: String) = store[key]
            override fun put(key: String, value: String) { store[key] = value }
        }).apply {
            setEnabled(true)
            setContentAccess(true) // Excerpts need "Allow access to file contents".
        }
        val documents = object : FilyDocuments {
            override fun all() = listOf(lease)
            override fun recent() = listOf(lease)
            override fun resolve(id: String) = if (id == lease.id) lease else null
            override suspend fun text(id: String, limit: Int) = FilyExcerpt(text.take(limit), text.length > limit, listOf(0))
            override suspend fun retrieve(id: String, question: String, budget: Int) = retrieval
        }
        return ToolExecutor(access, documents, ReaderControl(), { true }).apply { this.question = question }
    }

    private suspend fun excerpt(executor: ToolExecutor): JsonObject =
        Json.parseToJsonElement(
            executor.execute("getDocumentExcerpt", JsonObject(mapOf("documentId" to JsonPrimitive(lease.id)))),
        ).jsonObject

    @Test
    fun passagesRenderWithPageLabelsAndTheSharedSeparator() {
        val text = FilyPassages.render(listOf(FilyPassage("Fee is 48,000.", 3), FilyPassage("Due in June.", null)), 500)
        assertEquals("[page 3] Fee is 48,000.${Passages.SEPARATOR}Due in June.", text)
    }

    @Test
    fun renderedPassagesNeverExceedTheBudget() {
        assertEquals("[page 1] abcdef".take(10), FilyPassages.render(listOf(FilyPassage("abcdef", 1)), 10))
    }

    @Test
    fun aLongDocumentAnsweredFromItsPassagesCitesThePage() = runTest {
        val retrieval = FilyRetrieval.Passages(listOf(FilyPassage("Rent is 900 a month.", 12)), omitted = true)
        val json = excerpt(executor(retrieval, text = "x".repeat(10_000), question = "How much is the rent?"))
        assertEquals("[page 12] Rent is 900 a month.", json["text"]!!.jsonPrimitive.content)
        assertTrue(json["truncated"]!!.jsonPrimitive.boolean)
        assertTrue(json["available"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun aDocumentThatFitsWholeKeepsTheExcerptPath() = runTest {
        val json = excerpt(executor(FilyRetrieval.Whole, text = "Short lease.", question = "What is the rent?"))
        assertEquals("Short lease.", json["text"]!!.jsonPrimitive.content)
        assertFalse(json["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun aQuestionThatNamesAPageIsNotRetrieved() = runTest {
        val retrieval = FilyRetrieval.Passages(listOf(FilyPassage("RETRIEVED", 1)), omitted = true)
        val json = excerpt(executor(retrieval, text = "Page one text. Page two text.", question = "what does page two say?"))
        assertFalse(json["text"]!!.jsonPrimitive.content.contains("RETRIEVED"))
    }

    @Test
    fun anEmptyRetrievalFallsBackToTheKeywordPassages() = runTest {
        val retrieval = FilyRetrieval.Passages(emptyList(), omitted = true)
        val json = excerpt(executor(retrieval, text = "The engineer role began in 2023.", question = "What is my experience?"))
        assertTrue(json["text"]!!.jsonPrimitive.content.contains("engineer"))
    }
}
