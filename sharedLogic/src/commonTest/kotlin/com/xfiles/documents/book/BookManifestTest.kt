package com.xfiles.documents.book

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookManifestTest {
    private val json = """
        {"title":"The Book","author":"A. Writer","language":"en","rtl":false,"fixed":false,
         "chapters":[{"href":"OEBPS/c1.xhtml","size":10,"block":0},
                     {"href":"OEBPS/c2.xhtml","size":20,"block":5},
                     {"href":"OEBPS/c3.xhtml","size":30,"block":-1}],
         "toc":[{"title":"One","href":"OEBPS/c1.xhtml","depth":0},
                {"title":"Two","href":"OEBPS/c2.xhtml#sec","depth":9},
                {"title":"","href":"OEBPS/c3.xhtml","depth":0}],
         "notes":{"OEBPS/c2.xhtml#n1":"A note.","bad":5}}
    """.trimIndent()

    @Test
    fun parsesChaptersContentsAndNotes() {
        val manifest = assertNotNull(BookManifest.parseOrNull(json))
        assertEquals("The Book", manifest.title)
        assertEquals(listOf(0, 5, null), manifest.chapters.map { it.firstBlock })
        // The empty title is dropped; the depth is capped at MAX_TOC_DEPTH.
        assertEquals(listOf("One", "Two"), manifest.toc.map { it.title })
        assertEquals(BookManifest.MAX_TOC_DEPTH, manifest.toc.last().depth)
        assertEquals(mapOf("OEBPS/c2.xhtml#n1" to "A note."), manifest.notes)
    }

    @Test
    fun blockOfIgnoresFragmentsAndUnknownFiles() {
        val manifest = assertNotNull(BookManifest.parseOrNull(json))
        assertEquals(5, manifest.blockOf("OEBPS/c2.xhtml#sec"))
        assertNull(manifest.blockOf("OEBPS/c3.xhtml"))
        assertNull(manifest.blockOf("OEBPS/missing.xhtml"))
    }

    @Test
    fun blockOfResolvesAnchorsAndFallsBackToTheChapterStart() {
        val withAnchors = """
            {"title":"T","author":"","language":"en","rtl":false,"fixed":false,
             "chapters":[{"href":"OEBPS/c1.xhtml","size":10,"block":0},{"href":"OEBPS/c2 3.xhtml","size":20,"block":5}],
             "toc":[],
             "anchors":{"OEBPS/c1.xhtml#sec":2,"OEBPS/c2 3.xhtml#x y":9,"bad":"x"},
             "notes":{}}
        """.trimIndent()
        val manifest = assertNotNull(BookManifest.parseOrNull(withAnchors))
        assertEquals(mapOf("OEBPS/c1.xhtml#sec" to 2, "OEBPS/c2 3.xhtml#x y" to 9), manifest.anchors)
        assertEquals(2, manifest.blockOf("OEBPS/c1.xhtml#sec"))
        // An id the book does not have lands on its file's first block.
        assertEquals(0, manifest.blockOf("OEBPS/c1.xhtml#missing"))
        assertEquals(9, manifest.blockOf("OEBPS/c2 3.xhtml#x y"))
        assertEquals(5, manifest.blockOf("OEBPS/c2 3.xhtml"))
    }

    @Test
    fun chapterAtFindsTheChapterHoldingABlockByBinarySearch() {
        val manifest = assertNotNull(BookManifest.parseOrNull(json))
        assertEquals(-1, manifest.chapterIndexAt(-1))
        assertEquals(0, manifest.chapterIndexAt(0))
        assertEquals(0, manifest.chapterIndexAt(4))
        assertEquals(1, manifest.chapterIndexAt(5))
        assertEquals(1, manifest.chapterIndexAt(999))
    }

    @Test
    fun chapterTitleFallsBackToTheFileName() {
        val manifest = assertNotNull(BookManifest.parseOrNull(json))
        assertEquals("One", manifest.chapterTitle(0))
        assertEquals("c3", manifest.chapterTitle(2))
    }

    @Test
    fun malformedOrOversizedInputIsRejectedWithoutThrowing() {
        assertNull(BookManifest.parseOrNull("{not json"))
        assertNull(BookManifest.parseOrNull("[1,2]"))
        assertNull(BookManifest.parseOrNull("x".repeat(BookManifest.MAX_JSON_CHARS + 1)))
    }

    @Test
    fun bookKeyIsStableAndChangesWithTheContent() {
        val manifest = assertNotNull(BookManifest.parseOrNull(json))
        val key = bookKey("book.epub", 1234L, manifest)
        assertEquals(16, key.length)
        assertEquals(key, bookKey("book.epub", 1234L, manifest))
        assertTrue(key != bookKey("book.epub", 1235L, manifest))
        assertTrue(key != bookKey("other.epub", 1234L, manifest))
    }
}
