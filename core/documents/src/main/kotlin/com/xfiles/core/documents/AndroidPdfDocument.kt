package com.xfiles.core.documents

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.models.PageMatchBounds
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresExtension
import com.xfiles.documents.structure.DocumentError
import com.xfiles.documents.structure.DocumentException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

data class PdfMatchBox(val left: Float, val top: Float, val right: Float, val bottom: Float)
data class PdfPageMatches(val page: Int, val matches: List<List<PdfMatchBox>>)
data class PdfSearchResult(val pages: List<PdfPageMatches>, val truncated: Boolean)

class AndroidPdfDocument(context: Context) {
    private val resolver = context.contentResolver
    private val cache = context.cacheDir
    private val io = Dispatchers.IO.limitedParallelism(1)

    val canSearch: Boolean
        get() = Build.VERSION.SDK_INT >= 35 || supportsPdfExtensions()

    suspend fun render(uri: Uri, index: Int, password: String?): FilePreview.Pdf = withContext(io) {
        openRenderer(uri, password).use { pdf ->
            if (pdf.pageCount <= 0) throw IOException("PDF has no pages")
            val pageIndex = index.coerceIn(0, pdf.pageCount - 1)
            pdf.openPage(pageIndex).use { page ->
                val scale = minOf(2f, MAX_BITMAP_EDGE.toFloat() / maxOf(page.width, page.height))
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                FilePreview.Pdf(bitmap, pageIndex, pdf.pageCount, canSearch)
            }
        }
    }

    suspend fun search(uri: Uri, query: String, password: String?): PdfSearchResult = withContext(io) {
        if (query.isBlank()) return@withContext PdfSearchResult(emptyList(), truncated = false)
        when {
            Build.VERSION.SDK_INT >= 35 -> openRenderer(uri, password).use { renderer ->
                scan(renderer.pageCount) { index ->
                    renderer.openPage(index).use { page ->
                        page.searchText(query).toNormalizedMatches(page.width, page.height)
                    }
                }
            }
            supportsPdfExtensions() -> {
                val descriptor = openDescriptor(uri)
                val renderer = try {
                    openOlderRenderer(descriptor)
                } catch (error: Exception) {
                    descriptor.close()
                    throw error
                }
                renderer.use { pdf -> scan(pdf.pageCount) { pdf.search(it, query) } }
            }
            else -> PdfSearchResult(emptyList(), truncated = false)
        }
    }

    private suspend fun scan(count: Int, page: (Int) -> List<List<PdfMatchBox>>): PdfSearchResult {
        val results = ArrayList<PdfPageMatches>()
        var remaining = MAX_MATCHES
        val limit = minOf(count, MAX_SEARCH_PAGES)
        for (index in 0 until limit) {
            currentCoroutineContext().ensureActive()
            if (index % 8 == 0) yield()
            val hits = page(index)
            if (hits.isEmpty()) continue
            results.add(PdfPageMatches(index, hits.take(remaining)))
            val omitted = hits.size > remaining
            remaining -= hits.size
            if (remaining <= 0) return PdfSearchResult(results, truncated = omitted || index + 1 < count)
        }
        return PdfSearchResult(results, truncated = limit < count)
    }

    private suspend fun openRenderer(uri: Uri, password: String?): PdfRenderer {
        val descriptor = openDescriptor(uri)
        return try {
            if (Build.VERSION.SDK_INT >= 35 && password != null) {
                PdfRenderer(descriptor, LoadParams.Builder().setPassword(password).build())
            } else {
                PdfRenderer(descriptor)
            }
        } catch (error: SecurityException) {
            descriptor.close()
            val reason = when {
                Build.VERSION.SDK_INT < 35 -> DocumentError.UnsupportedEncryption("Opening this encrypted PDF requires Android 15.")
                password == null -> DocumentError.PasswordRequired("Enter the PDF password.")
                else -> DocumentError.WrongPassword("That password did not unlock this PDF.")
            }
            throw DocumentException(reason)
        } catch (error: Exception) {
            descriptor.close()
            throw error
        }
    }

    private suspend fun openDescriptor(uri: Uri): ParcelFileDescriptor {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw IOException("PDF could not be opened")
        try {
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
            return descriptor
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ESPIPE) {
                descriptor.close()
                throw error
            }
        }
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val temporary = File.createTempFile("pdf-preview-", ".pdf", cache)
            try {
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val bytes = input.read(buffer)
                        if (bytes < 0) break
                        downloaded += bytes
                        if (downloaded > MAX_PROVIDER_BYTES) throw DocumentException(
                            DocumentError.LimitExceeded("The PDF exceeds the reader's size limit."),
                        )
                        output.write(buffer, 0, bytes)
                    }
                }
                ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
            } finally {
                temporary.delete()
            }
        }
    }

    @SuppressLint("NewApi")
    private fun openOlderRenderer(descriptor: ParcelFileDescriptor): ExtensionPdfText =
        ExtensionPdfText(descriptor)

    private companion object {
        const val MAX_BITMAP_EDGE = 2048
        const val MAX_SEARCH_PAGES = 1_500
        const val MAX_MATCHES = 1_000
        const val MAX_PROVIDER_BYTES = 128L * 1024 * 1024
    }
}

private const val PDF_EXTENSION_VERSION = 13

private fun supportsPdfExtensions(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= PDF_EXTENSION_VERSION

@RequiresExtension(extension = Build.VERSION_CODES.S, version = PDF_EXTENSION_VERSION)
private class ExtensionPdfText(descriptor: ParcelFileDescriptor) : AutoCloseable {
    private val renderer = PdfRendererPreV(descriptor)
    val pageCount: Int get() = renderer.pageCount

    fun search(index: Int, query: String): List<List<PdfMatchBox>> =
        renderer.openPage(index).use { page -> page.searchText(query).toNormalizedMatches(page.width, page.height) }

    override fun close() = renderer.close()
}

@SuppressLint("NewApi")
private fun List<PageMatchBounds>.toNormalizedMatches(width: Int, height: Int): List<List<PdfMatchBox>> {
    if (width <= 0 || height <= 0) return emptyList()
    fun normalize(rect: RectF) = PdfMatchBox(
        (rect.left / width).coerceIn(0f, 1f),
        (rect.top / height).coerceIn(0f, 1f),
        (rect.right / width).coerceIn(0f, 1f),
        (rect.bottom / height).coerceIn(0f, 1f),
    )
    return map { match -> match.bounds.map(::normalize) }.filter { it.isNotEmpty() }
}
