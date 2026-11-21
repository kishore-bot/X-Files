package com.xfiles.documents.search

import com.xfiles.documents.library.LibraryCategory
import com.xfiles.documents.library.libraryCategories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileNameIndexTest {
    private fun found(names: List<String>, query: String): List<String> =
        FileNameIndex(names).search(query).map { names[it] }

    @Test
    fun findsNamesDespiteTypos() {
        val names = listOf("Invoice_2025.pdf", "Notes.txt", "Holiday photos.zip")
        assertEquals(listOf("Invoice_2025.pdf"), found(names, "invoce"))
        assertEquals(listOf("Invoice_2025.pdf"), found(names, "INVOICE"))
        assertEquals(listOf("Holiday photos.zip"), found(names, "holdiay"))
        assertTrue(found(names, "zzz").isEmpty())
    }

    @Test
    fun exactAndPrefixRankAboveFuzzy() {
        val names = listOf("Reprot draft.docx", "Report.docx", "Quarterly report.pdf", "Reporting guide.pdf")
        assertEquals(
            listOf("Report.docx", "Quarterly report.pdf", "Reporting guide.pdf", "Reprot draft.docx"),
            found(names, "report"),
        )
    }

    @Test
    fun everyQueryWordMustMatch() {
        val names = listOf("Annual_Report_2024.pdf", "Annual budget.xlsx", "Report card.pdf")
        assertEquals(listOf("Annual_Report_2024.pdf"), found(names, "annual report"))
        assertEquals(listOf("Annual_Report_2024.pdf"), found(names, "report annual"))
    }

    @Test
    fun camelCaseSeparatorsAndAccents() {
        val resume = "Résumé.odt"
        val names = listOf("AnnualReport.pdf", "my-cv.docx", resume, "meeting_notes.md")
        assertEquals(listOf("AnnualReport.pdf"), found(names, "annual report"))
        assertEquals(listOf("AnnualReport.pdf"), found(names, "annualreport"))
        assertEquals(listOf(resume), found(names, "resume"))
        assertEquals(listOf("meeting_notes.md"), found(names, "meeting notes"))
        assertEquals(listOf("meeting_notes.md"), found(names, "meeting_notes.md"))
    }

    @Test
    fun numbersAreNeverFuzzy() {
        val names = listOf("Invoice 2024.pdf", "Invoice 2025.pdf")
        assertEquals(listOf("Invoice 2025.pdf"), found(names, "2025"))
        assertEquals(listOf("Invoice 2025.pdf"), found(names, "invoice 2025"))
    }

    @Test
    fun shortQueriesMatchPartsButNotFuzzily() {
        val names = listOf("cat.txt", "car.txt", "scatter.csv")
        assertEquals(listOf("cat.txt", "scatter.csv"), found(names, "cat"))
        assertEquals(listOf("cat.txt", "car.txt", "scatter.csv"), found(names, "c"))
    }

    @Test
    fun blankAndPunctuationQueries() {
        val names = listOf("a_b.txt", "c.txt")
        assertEquals(listOf("a_b.txt", "c.txt"), found(names, "   "))
        assertEquals(listOf("a_b.txt"), found(names, "_"))
    }

    @Test
    fun equalScoresKeepGivenOrderAndLimitApplies() {
        val names = listOf("b report.pdf", "a report.pdf", "c report.pdf")
        assertEquals(listOf(0, 1), FileNameIndex(names).search("report", 2))
    }

    @Test
    fun trigramsCatchHeavierMisspellingsOfLongWords() {
        val names = listOf("Acknowledgement letter.docx", "Agenda.docx")
        assertEquals(listOf("Acknowledgement letter.docx"), found(names, "acknowlegdment"))
    }

    @Test
    fun categoriesFollowExtensions() {
        assertEquals(setOf(LibraryCategory.Docs), libraryCategories("notes.DOCX"))
        assertEquals(setOf(LibraryCategory.Sheets, LibraryCategory.Data), libraryCategories("data.csv"))
        assertEquals(setOf(LibraryCategory.Code), libraryCategories("Main.kt"))
        assertEquals(setOf(LibraryCategory.Code), libraryCategories("Dockerfile"))
        assertTrue(libraryCategories("mystery.xyz").isEmpty())
    }
}
