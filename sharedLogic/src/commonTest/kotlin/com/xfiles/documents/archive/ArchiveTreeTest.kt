package com.xfiles.documents.archive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchiveTreeTest {

    private fun entry(path: String, size: Long = 1, dir: Boolean = path.endsWith('/')) =
        ArchiveEntry(path, size, size, dir, encrypted = false, modifiedDos = 0)

    @Test
    fun naturalOrderComparesDigitRunsByValue() {
        val sorted = listOf("img10", "img2", "img1", "Img3").sortedWith(NaturalOrder)
        assertEquals(listOf("img1", "img2", "Img3", "img10"), sorted)
    }

    @Test
    fun naturalOrderIsCaseInsensitiveAndTotalOnTies() {
        assertTrue(NaturalOrder.compare("a", "B") < 0)
        assertTrue(NaturalOrder.compare("file01", "file1") != 0)
        assertEquals(0, NaturalOrder.compare("same", "SAME"))
        assertTrue(NaturalOrder.compare("file", "file.txt") < 0)
    }

    @Test
    fun foldersComeBeforeFilesThenNaturalOrder() {
        val tree = ArchiveTree.build(
            listOf(entry("b.txt"), entry("Z/"), entry("a/x10.txt"), entry("a/x2.txt"), entry("dir/sub/deep.txt")),
        )
        val root = tree.find("")!!
        assertEquals(listOf("a", "dir", "Z", "b.txt"), root.sortedChildren.map { it.name })
        assertEquals(listOf("x2.txt", "x10.txt"), tree.find("a")!!.sortedChildren.map { it.name })
    }

    @Test
    fun implicitFoldersAreCreatedAndNavigable() {
        val tree = ArchiveTree.build(listOf(entry("dir/sub/deep.txt", size = 42)))
        assertTrue(tree.find("dir")!!.isDirectory)
        assertNull(tree.find("dir")!!.entry, "a prefix folder has no archive record")
        assertEquals(42L, tree.find("dir/sub/deep.txt")!!.size)
        assertNull(tree.find("missing"))
    }

    @Test
    fun directoryRecordArrivingLaterKeepsItsMetadata() {
        val record = ArchiveEntry("dir/", 0, 0, isDirectory = true, encrypted = false, modifiedDos = 0x5A21_0000)
        val tree = ArchiveTree.build(listOf(entry("dir/a.txt"), record))
        assertNotNull(tree.find("dir")!!.entry)
        assertEquals(0x5A21_0000, tree.find("dir")!!.entry!!.modifiedDos)
    }

    @Test
    fun unsafePathsAreSkipped() {
        val tree = ArchiveTree.build(listOf(entry("../escape.txt"), entry("ok.txt"), entry("")))
        assertEquals(1, tree.entryCount)
        assertNull(tree.find("escape.txt"))
    }

    @Test
    fun fileThenFolderNameCollisionKeepsTheFirstAndDropsContentsBehindIt() {
        val tree = ArchiveTree.build(listOf(entry("name"), entry("name/inside.txt")))
        assertTrue(!tree.find("name")!!.isDirectory)
        assertNull(tree.find("name/inside.txt"))
    }

    @Test
    fun searchIsCaseInsensitiveAndBounded() {
        val tree = ArchiveTree.build((1..50).map { entry("docs/Page$it.txt") })
        assertEquals(1, tree.search("PAGE1.TXT").size)
        assertEquals(3, tree.search("page1", limit = 3).size)
        assertTrue(tree.search("   ").isEmpty())
    }
}
