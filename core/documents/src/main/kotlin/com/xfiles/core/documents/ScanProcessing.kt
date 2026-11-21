package com.xfiles.core.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.xfiles.engine.ffi.imagingBinarize
import com.xfiles.engine.ffi.imagingSharpness
import com.xfiles.scan.PageQuad
import com.xfiles.scan.detectPageOutline
import com.xfiles.scan.pageOutputSize
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ScanFilter(val label: String) {
    Original("Original"),
    Enhance("Enhance"),
    Grayscale("Grayscale"),
    BlackAndWhite("Black & white"),
}

/** One analysed camera frame: the page outline (fractions of the frame), and whether the frame is sharp. */
data class ScanFrame(val outline: PageQuad?, val sharp: Boolean)

/**
 * The scanner's image work. [analyze] runs on the camera's analysis thread for every few frames: page
 * edges in Kotlin (PageOutline), blur in Rust. [finishPage] straightens, filters and encodes one capture
 * on a background thread; black & white uses Rust's adaptive (Sauvola) threshold.
 */
@Singleton
class ScanProcessing @Inject constructor(@ApplicationContext context: Context) {
    private val pages = File(context.cacheDir, "scans")
    private val preferences = context.applicationContext.getSharedPreferences("scan", Context.MODE_PRIVATE)

    /** The filter chosen last time, or Enhance. Reads the preferences file once, so call it off the main thread. */
    suspend fun lastFilter(): ScanFilter = withContext(Dispatchers.IO) {
        runCatching { ScanFilter.valueOf(preferences.getString(KEY_FILTER, null).orEmpty()) }.getOrDefault(ScanFilter.Enhance)
    }

    /** Remembers [filter] for the next scan. Writes on IO; the caller does not wait for the disk. */
    suspend fun rememberFilter(filter: ScanFilter) = withContext(Dispatchers.IO) {
        preferences.edit().putString(KEY_FILTER, filter.name).apply()
    }

    /** [luma] is a downsampled frame, one brightness byte per pixel. Blocking; call off the main thread. */
    fun analyze(luma: ByteArray, width: Int, height: Int): ScanFrame {
        val outline = detectPageOutline(luma, width, height)
        val sharp = outline != null && runCatching {
            imagingSharpness(luma, width.toUInt(), height.toUInt()).let { !it.judged || !it.blurry }
        }.getOrDefault(true)
        return ScanFrame(outline, sharp)
    }

    /**
     * A filtered copy of [source] for the review screen's preview. The caller owns the copy and recycles it.
     * Blocking, so call it off the main thread.
     */
    fun previewCopy(source: Bitmap, filter: ScanFilter): Bitmap {
        val copy = source.copy(Bitmap.Config.ARGB_8888, true) ?: error("The preview could not be copied.")
        try {
            applyFilter(copy, filter)
        } catch (error: Throwable) {
            copy.recycle()
            throw error
        }
        return copy
    }

    /** The captured [photo] cut to [outline] (whole frame when null), filtered and saved as a JPEG page. */
    suspend fun finishPage(photo: File, outline: PageQuad?, filter: ScanFilter): Uri = withContext(Dispatchers.Default) {
        val source = decodeUpright(photo)
        try {
            val page = straighten(source, outline ?: PageQuad.FullFrame)
            try {
                applyFilter(page, filter)
                if (!pages.isDirectory) pages.mkdirs()
                val target = File.createTempFile("page-", ".jpg", pages)
                target.outputStream().buffered().use { page.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                Uri.fromFile(target)
            } finally {
                page.recycle()
            }
        } finally {
            source.recycle()
            photo.delete()
        }
    }

    /** Decoded at most 3,000 px on the long side and turned upright from the camera's EXIF rotation. */
    private fun decodeUpright(photo: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(photo.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("The photo could not be read.")
        val degrees = when (ExifInterface(photo.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return decoded
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
            .also { if (it !== decoded) decoded.recycle() }
    }

    /** Perspective correction: maps the outline's four corners onto a flat rectangle. */
    private fun straighten(source: Bitmap, quad: PageQuad): Bitmap {
        val (width, height) = pageOutputSize(quad, source.width, source.height, MAX_SIDE)
        val from = quad.corners.flatMap { listOf(it.x * source.width, it.y * source.height) }.toFloatArray()
        val to = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
        val matrix = Matrix().apply { setPolyToPoly(from, 0, to, 0, 4) }
        val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(page).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return page
    }

    private fun applyFilter(page: Bitmap, filter: ScanFilter) {
        if (filter == ScanFilter.Original) return
        val pixels = IntArray(page.width * page.height)
        page.getPixels(pixels, 0, page.width, 0, 0, page.width, page.height)
        when (filter) {
            ScanFilter.Enhance -> enhance(pixels)
            ScanFilter.Grayscale -> grayscale(pixels)
            ScanFilter.BlackAndWhite -> {
                val luma = ByteArray(pixels.size) { lumaOf(pixels[it]).toByte() }
                val ink = imagingBinarize(luma, page.width.toUInt(), page.height.toUInt())
                for (i in pixels.indices) pixels[i] = if ((ink[i].toInt() and 0xFF) < 128) Color.BLACK else Color.WHITE
            }
            ScanFilter.Original -> Unit
        }
        page.setPixels(pixels, 0, page.width, 0, 0, page.width, page.height)
    }

    /**
     * Luminance only, with a gentle S-curve (a 35% blend toward smoothstep) so paper lifts and ink deepens
     * without the hard clip of a threshold. One 256-entry table, one pass over the pixels.
     */
    private fun grayscale(pixels: IntArray) {
        val curve = IntArray(256) { level ->
            val t = level / 255f
            val smooth = t * t * (3f - 2f * t)
            (level + (smooth * 255f - level) * GRAYSCALE_CONTRAST).roundToInt().coerceIn(0, 255)
        }
        for (i in pixels.indices) {
            val g = curve[lumaOf(pixels[i])]
            pixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
    }

    /** Stretches brightness between the 2nd and 98th percentile, so grey paper turns white and ink dark. */
    private fun enhance(pixels: IntArray) {
        val histogram = IntArray(256)
        pixels.forEach { histogram[lumaOf(it)]++ }
        val low = percentile(histogram, pixels.size, 0.02f)
        val high = percentile(histogram, pixels.size, 0.98f).coerceAtLeast(low + 1)
        val table = IntArray(256) { ((it - low) * 255f / (high - low)).roundToInt().coerceIn(0, 255) }
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (p and 0xFF000000.toInt()) or (table[(p shr 16) and 0xFF] shl 16) or (table[(p shr 8) and 0xFF] shl 8) or table[p and 0xFF]
        }
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
        val target = (total * fraction).toInt()
        var seen = 0
        histogram.forEachIndexed { level, count ->
            seen += count
            if (seen > target) return level
        }
        return 255
    }

    private fun lumaOf(argb: Int): Int = (((argb shr 16) and 0xFF) * 299 + ((argb shr 8) and 0xFF) * 587 + (argb and 0xFF) * 114) / 1000

    companion object {
        /** Pages one scan can hold. The camera stops at this and the pictures list refuses more. */
        const val MAX_SCAN_PAGES = 50

        private const val MAX_SIDE = 3000
        private const val KEY_FILTER = "filter"
        private const val GRAYSCALE_CONTRAST = 0.35f
    }
}
