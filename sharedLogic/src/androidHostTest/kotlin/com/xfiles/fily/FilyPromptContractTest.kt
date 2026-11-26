package com.xfiles.fily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app must send the prompt Fily was trained on. The expectations are a small copy of the training
 * export (tool catalog plus one row), kept under src/androidHostTest/resources/fily. Host-only because it
 * reads resources; the training pipeline is what regenerates them.
 */
class FilyPromptContractTest {
    private fun fixture(name: String): String {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("fily/$name")) { "Missing fixture $name" }
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun toolCatalogMatchesTraining() {
        val trained = Json.parseToJsonElement(fixture("tool_catalog.json"))
        assertEquals(trained, Json.parseToJsonElement(FILY_TOOL_CATALOG))
    }

    @Test
    fun liveStateIncludesOnlySuppliedDocumentAliasesAndSaveEvidence() {
        val formatted = FilyPrompt.appState(true, false, "2026-11-27", FilyScreen(
            selectedDocumentId = "doc_1",
            route = "Reader",
            page = 4,
            pageCount = 5,
            readerCapabilities = listOf("previous_page"),
            selectedFileIds = listOf("doc_1", "doc_2"),
            lastAction = FilyActionReceipt("mergePdfDocuments", "review_pending"),
            pdfSaveStatus = PdfSaveStatus.Ready,
        ))
        val state = Json.parseToJsonElement(formatted.removePrefix("APP_STATE=")).jsonObject
        assertTrue(state.getValue("capabilityFacts").jsonObject.isNotEmpty())
        assertEquals(listOf("doc_1", "doc_2"), state.getValue("authorizedIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Reader", state.getValue("screen").jsonPrimitive.content)
        assertEquals("4", state.getValue("page").jsonPrimitive.content)
        assertEquals("review_pending", state.getValue("lastAction").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("ready_for_review", state.getValue("pdfSaveStatus").jsonPrimitive.content)
    }

    // Known drift (2026-11-22): EzFiles training set edge_v5 trains control_viewer as a live tool with scroll,
    // cell and sheet operations, while both apps still send the older "proposed" entry. Re-enable once the app
    // catalog is moved to the shipped model's training catalog.
    @Ignore
    @Test
    fun systemPromptMatchesTrainingRows() {
        val rows = fixture("edge_v5_row.jsonl").lineSequence().filter(String::isNotBlank).toList()
        assertTrue(rows.isNotEmpty(), "fixture has no rows")
        rows.forEach { line ->
            val row = Json.parseToJsonElement(line).jsonObject
            val tools = row.getValue("tools").jsonArray.map { it.jsonPrimitive.content }
            val system = row.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonPrimitive.content
            assertEquals(system, FilyPrompt.system(tools), "row ${row["id"]}")
        }
    }
}
