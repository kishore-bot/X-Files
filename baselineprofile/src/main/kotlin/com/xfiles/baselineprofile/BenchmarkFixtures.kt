package com.xfiles.baselineprofile

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileNotFoundException

const val TEXT_MARKER = "Xfiles benchmark native reader fixture"
const val PDF_PAGES = 8

/** A test-only read-only provider with literal fixture filenames; no private root or arbitrary path is exposed. */
class BenchmarkFixtureProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val app = requireNotNull(context)
        val name = uri.pathSegments.singleOrNull()
        if (uri.authority != "${app.packageName}.fixtures" || name !in setOf("benchmark-reader.txt", "benchmark-reader.pdf")) {
            throw FileNotFoundException("Unknown benchmark fixture")
        }
        return File(File(app.filesDir, "benchmark-fixtures"), requireNotNull(name))
    }
    override fun getType(uri: Uri): String = when (file(uri).extension) {
        "pdf" -> "application/pdf"
        else -> "text/plain"
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only benchmark fixture")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = file(uri)
        val supported = setOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val columns = projection?.filter { it in supported }?.toTypedArray() ?: supported.toTypedArray()
        return MatrixCursor(columns).apply {
            addRow(columns.map { column -> when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> file.lastModified()
            } })
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only fixtures")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only fixtures")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only fixtures")
}

class ReaderFixture internal constructor(private val context: Context, private val uri: Uri, val pdf: Boolean) {
    fun launchIntent(): Intent {
        // Macrobenchmark launches through shell. Grant from the owning test UID before handing over.
        context.grantUriPermission(PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(PACKAGE, "$PACKAGE.android.MainActivity")
            setDataAndType(uri, if (pdf) "application/pdf" else "text/plain")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }

    internal fun revoke() { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}

class BenchmarkFixtures private constructor(val text: ReaderFixture, val pdf: ReaderFixture) {
    fun revoke() { text.revoke(); pdf.revoke() }

    companion object {
        /** Fixture generation is outside measured/collected blocks and touches no user documents. */
        fun create(): BenchmarkFixtures {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == "com.xfiles.baselineprofile") { "Fixtures must belong to the standalone benchmark APK" }
            val directory = File(context.filesDir, "benchmark-fixtures").apply { check(isDirectory || mkdirs()) }
            val text = File(directory, "benchmark-reader.txt").apply {
                bufferedWriter().use { writer ->
                    writer.appendLine(TEXT_MARKER)
                    writer.appendLine()
                    repeat(500) { index ->
                        writer.appendLine("Benchmark row ${index.toString().padStart(4, '0')}: controlled local document content.")
                        writer.appendLine()
                    }
                }
            }
            val pdf = File(directory, "benchmark-reader.pdf")
            val document = PdfDocument()
            try {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 26f }
                repeat(PDF_PAGES) { index ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawText("Xfiles benchmark page ${index + 1}", 36f, 80f, paint)
                    document.finishPage(page)
                }
                pdf.outputStream().use(document::writeTo)
            } finally {
                document.close()
            }
            val authority = "${context.packageName}.fixtures"
            return BenchmarkFixtures(
                ReaderFixture(context, Uri.Builder().scheme("content").authority(authority).appendPath(text.name).build(), pdf = false),
                ReaderFixture(context, Uri.Builder().scheme("content").authority(authority).appendPath(pdf.name).build(), pdf = true),
            )
        }
    }
}
