package com.xfiles.documentengine

import com.xfiles.documents.PdfCompressQuality
import com.xfiles.documents.PdfPageSpan
import com.xfiles.documents.PdfProtectionChoice
import com.xfiles.documents.PdfProtectionOutcome
import com.xfiles.documents.PdfSignSpec
import com.xfiles.documents.PdfWatermarkPosition
import com.xfiles.documents.PdfWatermarkSpec
import com.xfiles.engine.ffi.NativeImageQuality
import com.xfiles.engine.ffi.NativePdfOverlay
import com.xfiles.engine.ffi.NativeProtectionChoice
import com.xfiles.engine.ffi.NativeProtectionOutcome
import com.xfiles.engine.ffi.NativePdfPageEdit
import com.xfiles.engine.ffi.NativePdfPageRange
import com.xfiles.engine.ffi.NativePdfSource
import com.xfiles.engine.ffi.NativePdfStroke
import com.xfiles.engine.ffi.NativePdfToolListener
import com.xfiles.engine.ffi.NativeSignature
import com.xfiles.engine.ffi.NativeWatermark
import com.xfiles.engine.ffi.NativeWatermarkPosition
import com.xfiles.engine.ffi.ReadException
import com.xfiles.engine.ffi.pdfToolCompress
import com.xfiles.engine.ffi.pdfToolInspect
import com.xfiles.engine.ffi.pdfToolOverlay
import com.xfiles.engine.ffi.pdfToolSplit
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** What a PDF needs to be known before a dedicated tool can offer it. */
data class PdfToolInspection(val pages: Int, val signed: Boolean, val archival: Boolean)

data class PdfCompressSummary(
    val pages: Int,
    val beforeBytes: Long,
    val afterBytes: Long,
    val duplicatesMerged: Int,
    val streamsRecompressed: Int,
    val imagesRecompressed: Int,
    /** What happened to the source's protection on the result. */
    val protection: PdfProtectionOutcome,
    /** The source's limits (printing, changing, ...) are written into the result. */
    val restrictionsKept: Boolean,
)

data class PdfOverlaySummary(
    val pages: Int,
    val pagesChanged: Int,
    val totalBytes: Long,
    val protection: PdfProtectionOutcome,
    val restrictionsKept: Boolean,
)

internal fun PdfPageEdit.toNative(): NativePdfPageEdit = NativePdfPageEdit(page.toUInt(), rotation)

private fun PdfCompressQuality.toNative(): NativeImageQuality = when (this) {
    PdfCompressQuality.Lossless -> NativeImageQuality.LOSSLESS
    PdfCompressQuality.Balanced -> NativeImageQuality.BALANCED
    PdfCompressQuality.Strong -> NativeImageQuality.STRONG
}

private fun PdfProtectionChoice.toNative(): NativeProtectionChoice = when (this) {
    PdfProtectionChoice.SameAsOriginal -> NativeProtectionChoice.SAME_AS_ORIGINAL
    PdfProtectionChoice.Aes256 -> NativeProtectionChoice.AES256
}

private fun NativeProtectionOutcome.toShared(): PdfProtectionOutcome = when (this) {
    NativeProtectionOutcome.NOT_PROTECTED -> PdfProtectionOutcome.NotProtected
    NativeProtectionOutcome.KEPT -> PdfProtectionOutcome.Kept
    NativeProtectionOutcome.UPGRADED -> PdfProtectionOutcome.Upgraded
    NativeProtectionOutcome.KEPT_NEW_OWNER -> PdfProtectionOutcome.KeptNewOwner
    NativeProtectionOutcome.OWNER_PASSWORD_OPENS -> PdfProtectionOutcome.OwnerPasswordOpens
    NativeProtectionOutcome.SAVED_WITHOUT_PROTECTION -> PdfProtectionOutcome.SavedWithoutProtection
}

/** Page count and flags of [file]. Throws [PdfPasswordRequired] when it needs a password. */
suspend fun inspectPdfTool(file: File, password: String?): PdfToolInspection {
    val coroutine = currentCoroutineContext()
    coroutine.ensureActive()
    return try {
        val info = pdfToolInspect(NativePdfSource(fd = -1, path = file.absolutePath, password = password))
        PdfToolInspection(info.pageCount.toInt(), info.isSigned, info.archival)
    } catch (_: ReadException.PasswordRequired) {
        throw PdfPasswordRequired(wrong = false)
    } catch (_: ReadException.WrongPassword) {
        throw PdfPasswordRequired(wrong = true)
    }
}

/**
 * Compacts [source] into [destination] at [quality]. Reports sizes the engine measured.
 * A protected source is protected again as [protection] says; the summary states the outcome.
 */
suspend fun compressPdf(
    source: File,
    destination: File,
    sourcePassword: String?,
    quality: PdfCompressQuality,
    protection: PdfProtectionChoice,
    onProgress: (Int, Int) -> Unit,
): PdfCompressSummary {
    val coroutine = currentCoroutineContext()
    coroutine.ensureActive()
    val native = NativePdfSource(fd = -1, path = source.absolutePath, password = sourcePassword)
    val report = guarded {
        pdfToolCompress(
            native,
            destination.absolutePath,
            quality.toNative(),
            protection.toNative(),
            progressListener(coroutine[Job], onProgress),
        )
    }
    coroutine.ensureActive()
    return PdfCompressSummary(
        pages = report.pageCount.toInt(),
        beforeBytes = report.beforeLen.toLong(),
        afterBytes = report.afterLen.toLong(),
        duplicatesMerged = report.duplicatesMerged.toInt(),
        streamsRecompressed = report.streamsRecompressed.toInt(),
        imagesRecompressed = report.imagesRecompressed.toInt(),
        protection = report.protection.toShared(),
        restrictionsKept = report.restrictionsKept,
    )
}

suspend fun watermarkPdf(
    source: File,
    destination: File,
    spec: PdfWatermarkSpec,
    sourcePassword: String?,
    protection: PdfProtectionChoice,
    onProgress: (Int, Int) -> Unit,
): PdfOverlaySummary {
    require(spec.validationError() == null) { spec.validationError().orEmpty() }
    val watermark = NativeWatermark(
        text = spec.text.trim(),
        fontSize = spec.fontSize,
        opacity = spec.opacity,
        angleDegrees = spec.angleDegrees,
        color = spec.color.toUInt(),
        position = spec.position.toNative(),
        pages = spec.pages?.map { it.toUInt() },
    )
    return runOverlay(source, destination, NativePdfOverlay.Watermark(watermark), sourcePassword, protection, onProgress)
}

suspend fun signPdf(
    source: File,
    destination: File,
    spec: PdfSignSpec,
    sourcePassword: String?,
    protection: PdfProtectionChoice,
    onProgress: (Int, Int) -> Unit,
): PdfOverlaySummary {
    require(spec.validationError() == null) { spec.validationError().orEmpty() }
    val placement = spec.placement
    val signature = NativeSignature(
        page = placement.page.toUInt(),
        x = placement.x,
        y = placement.y,
        width = placement.width,
        height = placement.height,
        strokes = spec.strokes.map { stroke -> NativePdfStroke(points = stroke.flatMap { (x, y) -> listOf(x, y) }) },
        color = spec.color.toUInt(),
        lineWidth = spec.lineWidth,
    )
    return runOverlay(source, destination, NativePdfOverlay.Sign(signature), sourcePassword, protection, onProgress)
}

/**
 * Writes one PDF per span into [destinations], in order. Spans must be in bounds
 * and must not share a page. The engine checks that before writing anything.
 */
suspend fun splitPdf(
    source: File,
    spans: List<PdfPageSpan>,
    destinations: List<File>,
    sourcePassword: String?,
    onProgress: (Int, Int) -> Unit,
): List<PdfRewriteReport> {
    require(spans.size == destinations.size) { "Each part needs its own file." }
    val coroutine = currentCoroutineContext()
    coroutine.ensureActive()
    val native = NativePdfSource(fd = -1, path = source.absolutePath, password = sourcePassword)
    val ranges = spans.map { NativePdfPageRange(it.start.toUInt(), it.end.toUInt()) }
    val reports = guarded {
        pdfToolSplit(native, ranges, destinations.map { it.absolutePath }, progressListener(coroutine[Job], onProgress))
    }
    coroutine.ensureActive()
    return reports.map { report ->
        val dropped = report.dropped
        PdfRewriteReport(
            pages = report.pageCount.toInt(),
            droppedMetadata = dropped.outlines || dropped.forms || dropped.namedDestinations || dropped.structureTags ||
                dropped.pageLabels || dropped.attachments || dropped.links > 0u,
            signedSource = false,
            archivalSource = false,
        )
    }
}

private suspend fun runOverlay(
    source: File,
    destination: File,
    overlay: NativePdfOverlay,
    sourcePassword: String?,
    protection: PdfProtectionChoice,
    onProgress: (Int, Int) -> Unit,
): PdfOverlaySummary {
    val coroutine = currentCoroutineContext()
    coroutine.ensureActive()
    val native = NativePdfSource(fd = -1, path = source.absolutePath, password = sourcePassword)
    val report = guarded {
        pdfToolOverlay(
            native,
            overlay,
            destination.absolutePath,
            protection.toNative(),
            progressListener(coroutine[Job], onProgress),
        )
    }
    coroutine.ensureActive()
    return PdfOverlaySummary(
        pages = report.pageCount.toInt(),
        pagesChanged = report.pagesChanged.toInt(),
        totalBytes = report.totalLen.toLong(),
        protection = report.protection.toShared(),
        restrictionsKept = report.restrictionsKept,
    )
}

/** Cancellation is polled from the engine's thread through the job's state. */
private fun progressListener(job: Job?, onProgress: (Int, Int) -> Unit): NativePdfToolListener =
    object : NativePdfToolListener {
        override fun onProgress(done: UInt, total: UInt) = onProgress(done.toInt(), total.toInt())
        override fun isCancelled(): Boolean = job?.isActive == false
    }

private inline fun <T> guarded(block: () -> T): T = try {
    block()
} catch (_: ReadException.PasswordRequired) {
    throw PdfPasswordRequired(wrong = false)
} catch (_: ReadException.WrongPassword) {
    throw PdfPasswordRequired(wrong = true)
}

private fun PdfWatermarkPosition.toNative(): NativeWatermarkPosition = when (this) {
    PdfWatermarkPosition.Center -> NativeWatermarkPosition.CENTER
    PdfWatermarkPosition.TopLeft -> NativeWatermarkPosition.TOP_LEFT
    PdfWatermarkPosition.TopCenter -> NativeWatermarkPosition.TOP_CENTER
    PdfWatermarkPosition.TopRight -> NativeWatermarkPosition.TOP_RIGHT
    PdfWatermarkPosition.BottomLeft -> NativeWatermarkPosition.BOTTOM_LEFT
    PdfWatermarkPosition.BottomCenter -> NativeWatermarkPosition.BOTTOM_CENTER
    PdfWatermarkPosition.BottomRight -> NativeWatermarkPosition.BOTTOM_RIGHT
    PdfWatermarkPosition.Tiled -> NativeWatermarkPosition.TILED
}
