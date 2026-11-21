package com.xfiles.tidy

import com.xfiles.documents.DocumentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TidyReportTest {
    private fun file(id: String) = TidyFile("content://$id", "$id.pdf", DocumentKind.Pdf, 100, 1)

    @Test
    fun removingACopyDropsGroupsLeftWithOneFile() {
        val a = file("a")
        val b = file("b")
        val report = TidyReport(0, 2, listOf(IdenticalGroup(listOf(a, b), 100)), emptyList(), emptyList(), emptyList())
        assertEquals(100, report.reclaimableBytes)
        val after = report.without(b)
        assertTrue(after.identical.isEmpty())
        assertTrue(after.isEmpty)
    }

    @Test
    fun sensitiveFindingsReadNaturally() {
        assertEquals("a card number and an ID number", SensitiveFindings(paymentCard = true, nationalId = true).describe())
        assertEquals("Mentions ‘invoice’ · 2 amounts", CollectionReason(terms = listOf("invoice"), amounts = 2).describe())
    }
}
