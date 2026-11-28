package com.xfiles.fily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ToolExecutorTest {
    private val files = listOf(
        FilyDocument("u1", "Aster invoice March.pdf", 10, modifiedAt = 1_000),
        FilyDocument("u2", "Nimbus invoice April.pdf", 10, modifiedAt = 3_000),
        FilyDocument("u3", "Holiday notes.txt", 10, modifiedAt = 2_000),
        FilyDocument("u4", "Rental agreement.pdf", 10, modifiedAt = 500),
    )
    private val opened = mutableListOf<FilyDestination>()

    private fun executor(): ToolExecutor {
        val store = HashMap<String, String>()
        val access = AssistantAccess(object : FilyPreferences {
            override fun get(key: String) = store[key]
            override fun put(key: String, value: String) { store[key] = value }
        }).apply { setEnabled(true) }
        val documents = object : FilyDocuments {
            override fun all() = files
            override fun recent() = files.take(2)
            override fun resolve(id: String) = files.firstOrNull { it.id == id }
            override suspend fun text(id: String, limit: Int): FilyExcerpt? = null
        }
        return ToolExecutor(access, documents, ReaderControl(), { opened += it; FilyHandoff.Accepted })
    }

    private fun names(result: String) =
        Json.parseToJsonElement(result).jsonObject["documents"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    @Test
    fun categorySearchMatchesNamesAndSortsNewestFirst() = runTest {
        val result = executor().execute("searchLibrary", Json.parseToJsonElement("""{"query":"","category":"invoice","sort":"modified_desc"}""") as JsonObject)
        assertEquals(listOf("Nimbus invoice April.pdf", "Aster invoice March.pdf"), names(result))
    }

    @Test
    fun agreementFindsContracts() = runTest {
        val result = executor().execute("searchLibrary", Json.parseToJsonElement("""{"query":"","category":"contract"}""") as JsonObject)
        assertEquals(listOf("Rental agreement.pdf"), names(result))
    }

    @Test
    fun aMadeUpIdOpensTheOneFileItNames() = runTest {
        val tools = executor()
        val result = tools.execute("openDocument", Json.parseToJsonElement("""{"documentId":"holiday_notes"}""") as JsonObject)
        assertTrue("opened" in result, result)
        assertEquals(FilyDestination.Open("u3"), opened.single())
    }

    @Test
    fun sharingOpensTheChooserForThatFileOnly() = runTest {
        val result = executor().execute("prepareShareDocument", Json.parseToJsonElement("""{"documentId":"u1"}""") as JsonObject)
        assertTrue("chooser_opened" in result, result)
        assertEquals(FilyDestination.Share(listOf("u1")), opened.single())
    }
}
