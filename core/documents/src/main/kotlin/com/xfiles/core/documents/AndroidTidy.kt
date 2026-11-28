package com.xfiles.core.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import com.xfiles.documentengine.DocumentAnalyzer
import com.xfiles.documentengine.RustTidyEngine
import com.xfiles.tidy.GrayImage
import com.xfiles.tidy.SensitiveCheck
import com.xfiles.tidy.TidyFile
import com.xfiles.tidy.TidyFiles
import com.xfiles.tidy.TidyLibrary
import com.xfiles.tidy.TidyService
import com.xfiles.tidy.TidyStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

fun FileEntry.toTidyFile() = TidyFile(uri.toString(), name, kind, sizeBytes, modifiedAt)

/**
 * Files for Tidy up through the Storage Access Framework, with only the folder grants the user gave.
 * Deletion also needs a persisted write grant and the provider's own delete capability.
 */
internal class AndroidTidyFiles(context: Context) : TidyFiles {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    /** A plain descriptor, no private copy: a file a provider can only stream is left out of hashing. */
    override fun openDescriptor(file: TidyFile): Int? = try {
        resolver.openFileDescriptor(Uri.parse(file.uri), "r")?.detachFd()
    } catch (_: FileNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    override fun grayThumbnail(file: TidyFile, side: Int): GrayImage? {
        val uri = Uri.parse(file.uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(uri, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Decoders downscale by powers of two while reading, so a 50 MP photo is never whole in memory.
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side * 2) sample *= 2
        val decoded = decode(uri, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        val scaled = Bitmap.createScaledBitmap(decoded, side, side, true)
        try {
            val argb = IntArray(side * side)
            scaled.getPixels(argb, 0, side, 0, 0, side, side)
            // ITU-R BT.601 luma, in integers.
            val gray = ByteArray(argb.size) { i ->
                val p = argb[i]
                ((((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000).toByte()
            }
            return GrayImage(side, side, gray)
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    private fun decode(uri: Uri, options: BitmapFactory.Options): Bitmap? = try {
        resolver.openFileDescriptor(uri, "r")?.use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, options) }
    } catch (_: FileNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: OutOfMemoryError) {
        null // A broken header claiming a huge size.
    }

    override val canDelete: Boolean = true

    override fun deleteDuplicate(file: TidyFile, retained: TidyFile): Boolean {
        val target = Uri.parse(file.uri)
        val keeper = Uri.parse(retained.uri)
        if (target == keeper || !mayDelete(target)) return false
        return try {
            val identical = resolver.openInputStream(target)?.use { first ->
                resolver.openInputStream(keeper)?.use { second -> sameBytes(first, second) }
            } == true
            identical && DocumentsContract.deleteDocument(resolver, target)
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun mayDelete(uri: Uri): Boolean = try {
        if (!DocumentsContract.isDocumentUri(app, uri) || !DocumentsContract.isTreeUri(uri)) {
            false
        } else {
            val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
            resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission } &&
                resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                    cursor.moveToFirst() && !cursor.isNull(0) &&
                        (cursor.getLong(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE.toLong()) != 0L &&
                        cursor.getString(1) != DocumentsContract.Document.MIME_TYPE_DIR
                } == true
        }
    } catch (_: RuntimeException) {
        false
    }

    private fun sameBytes(first: InputStream, second: InputStream): Boolean {
        val left = ByteArray(32 * 1024)
        val right = ByteArray(left.size)
        while (true) {
            val count = first.readChunk(left)
            if (count != second.readChunk(right)) return false
            if (count == 0) return true
            if (!left.copyOf(count).contentEquals(right.copyOf(count))) return false
        }
    }
}

/** Fills one comparison chunk even when providers return short reads. Zero means end of file. */
private fun InputStream.readChunk(buffer: ByteArray): Int {
    var filled = 0
    while (filled < buffer.size) {
        val count = read(buffer, filled, buffer.size - filled)
        if (count < 0) break
        filled += count
    }
    return filled
}

@Module
@InstallIn(SingletonComponent::class)
object TidyModule {
    /** Tidy work shares one low-parallelism IO lane, so a scan never starves the reader. */
    private val lane = Dispatchers.IO.limitedParallelism(1)

    @Provides
    @Singleton
    fun tidyLibrary(@ApplicationContext context: Context, folders: FolderRepository, pdfs: AndroidPdfDocument): TidyLibrary {
        val analyzer = DocumentAnalyzer(context.contentResolver)
        return object : TidyLibrary {
            override suspend fun files() = folders.scan().files.map(FileEntry::toTidyFile)
            override suspend fun plainText(file: TidyFile, maxChars: Int) =
                if (file.name.endsWith(".pdf", ignoreCase = true)) pdfs.text(Uri.parse(file.uri), null, maxChars, ocr = false).takeIf { it.isNotBlank() }
                else analyzer.extractText(Uri.parse(file.uri), file.name, maxChars.toUInt()).takeIf { it.isNotBlank() }
        }
    }

    @Provides
    @Singleton
    fun tidyStore(@ApplicationContext context: Context): TidyStore {
        val preferences = context.getSharedPreferences("tidy", Context.MODE_PRIVATE)
        return object : TidyStore {
            override fun get(key: String) = preferences.getString(key, null)
            override fun put(key: String, value: String) {
                preferences.edit().putString(key, value).apply()
            }
        }
    }

    @Provides
    @Singleton
    fun tidyService(@ApplicationContext context: Context, library: TidyLibrary, store: TidyStore): TidyService =
        TidyService(library, AndroidTidyFiles(context), RustTidyEngine(), store, lane, System::currentTimeMillis)

    @Provides
    @Singleton
    fun sensitiveCheck(library: TidyLibrary, store: TidyStore): SensitiveCheck =
        SensitiveCheck(library, RustTidyEngine(), store, lane)
}
