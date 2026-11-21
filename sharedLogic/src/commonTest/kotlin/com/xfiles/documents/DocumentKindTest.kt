package com.xfiles.documents

import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentKindTest {
    @Test
    fun recognizesReaderFormatsIgnoringCase() {
        assertEquals(DocumentKind.Pdf, documentKindFromName("notes.backup.PDF"))
        assertEquals(DocumentKind.Text, documentKindFromName("notes.md"))
        assertEquals(DocumentKind.Image, documentKindFromName("photo.JPEG"))
        assertEquals(DocumentKind.Document, documentKindFromName("archive.zip"))
        assertEquals(DocumentKind.Audio, documentKindFromName("album.FLAC"))
    }

    @Test
    fun handlesUnknownAndMissingExtensions() {
        assertEquals(DocumentKind.Unknown, documentKindFromName("archive.unsupported"))
        assertEquals(DocumentKind.Unknown, documentKindFromName("pdf"))
        assertEquals(DocumentKind.Unknown, documentKindFromName("untitled."))
    }

    @Test
    fun acceptsProviderMimeTypesAndFallsBackToFilename() {
        assertEquals(DocumentKind.Pdf, documentKind("download", "application/pdf"))
        assertEquals(DocumentKind.Document, documentKind("article.html", "text/html"))
        assertEquals(DocumentKind.Text, documentKind("download", "text/plain; charset=utf-8"))
        assertEquals(DocumentKind.Image, documentKind("photo.png", "application/octet-stream"))
    }
}
