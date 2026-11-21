package com.xfiles.documentengine

import android.content.ContentResolver
import android.net.Uri
import com.xfiles.documents.structure.CellAlign
import com.xfiles.documents.structure.CellLook
import com.xfiles.documents.structure.ContentBatch
import com.xfiles.documents.structure.ContentBlock
import com.xfiles.documents.structure.DocumentError
import com.xfiles.documents.structure.DocumentException
import com.xfiles.documents.structure.DocumentFormat
import com.xfiles.documents.structure.DocumentInfo
import com.xfiles.documents.structure.DocumentKind
import com.xfiles.documents.structure.ShapeBuild
import com.xfiles.documents.structure.ShapeGeometry
import com.xfiles.documents.structure.ShapeParagraph
import com.xfiles.documents.structure.SlideShape
import com.xfiles.documents.structure.StructuredDocument
import com.xfiles.documents.structure.TextSpan
import com.xfiles.engine.ffi.NativeBatch
import com.xfiles.engine.ffi.NativeBlock
import com.xfiles.engine.ffi.NativeCellStyle
import com.xfiles.engine.ffi.NativeDocumentInfo
import com.xfiles.engine.ffi.NativeDocumentKind
import com.xfiles.engine.ffi.NativeFormat
import com.xfiles.engine.ffi.NativeGeometry
import com.xfiles.engine.ffi.NativeLimits
import com.xfiles.engine.ffi.NativeShape
import com.xfiles.engine.ffi.NativeSpan
import com.xfiles.engine.ffi.ReadException
import com.xfiles.engine.ffi.openBorrowedFd
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class StructuredDocumentReader(private val resolver: ContentResolver) {
    private val io = Dispatchers.IO.limitedParallelism(1)

    suspend fun read(uri: Uri, name: String, password: String? = null): StructuredDocument = withContext(io) {
        try {
            val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Document could not be opened")
            val limits = NativeLimits(128uL * 1024uL * 1024uL, 64uL * 1024uL, 4uL * 1024uL * 1024uL)
            val open = descriptor.use { source -> openBorrowedFd(source.fd, name, limits, password) }
            open.use { session ->
                val info = session.info().toDomain(name)
                val blocks = ArrayList<ContentBlock>()
                val wanted = LinkedHashSet<String>()
                var truncated = false
                while (blocks.size < MAX_BLOCKS) {
                    ensureActive()
                    val batch = session.nextBatch()?.toDomain() ?: break
                    val remaining = MAX_BLOCKS - blocks.size
                    val kept = batch.blocks.take(remaining)
                    blocks.addAll(kept)
                    kept.forEach { block ->
                        when (block) {
                            is ContentBlock.Image -> block.path?.let(wanted::add)
                            is ContentBlock.Slide -> block.backgroundImage?.let(wanted::add)
                            is ContentBlock.Shape -> block.shape.image?.let(wanted::add)
                            else -> Unit
                        }
                    }
                    truncated = batch.truncated || batch.blocks.size > kept.size || (!batch.isLast && blocks.size == MAX_BLOCKS)
                    if (batch.isLast || truncated) break
                }
                val images = linkedMapOf<String, ByteArray>()
                var imageBudget = MAX_IMAGE_BYTES
                for (path in wanted.take(MAX_IMAGES)) {
                    ensureActive()
                    val bytes = try {
                        session.resource(path)
                    } catch (error: ReadException.Cancelled) {
                        throw CancellationException(error.detail)
                    } catch (_: ReadException) {
                        continue
                    }
                    if (bytes.size <= imageBudget) {
                        images[path] = bytes
                        imageBudget -= bytes.size
                        if (imageBudget == 0) break
                    }
                }
                StructuredDocument(info, blocks, images, truncated)
            }
        } catch (error: ReadException) {
            throw error.toDomain()
        }
    }

    private companion object {
        const val MAX_BLOCKS = 6_000
        const val MAX_IMAGES = 48
        const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
    }
}


private fun ReadException.toDomain(): Exception = when (this) {
    is ReadException.Io -> DocumentException(DocumentError.FileAccess(detail))
    is ReadException.UnsupportedFormat -> DocumentException(DocumentError.Unsupported(detail))
    is ReadException.Malformed -> DocumentException(DocumentError.Malformed(detail))
    is ReadException.LimitExceeded -> DocumentException(DocumentError.LimitExceeded(detail))
    is ReadException.PasswordRequired -> DocumentException(DocumentError.PasswordRequired(detail))
    is ReadException.WrongPassword -> DocumentException(DocumentError.WrongPassword(detail))
    is ReadException.UnsupportedEncryption -> DocumentException(DocumentError.UnsupportedEncryption(detail))
    is ReadException.Internal -> DocumentException(DocumentError.Internal(detail))
    // Only produced after DocumentSession.cancel(), which callers invoke when
    // the coroutine is being cancelled anyway.
    is ReadException.Cancelled -> CancellationException(detail)
}

private fun NativeDocumentInfo.toDomain(name: String) = DocumentInfo(
    name = name,
    format = when (format) {
        NativeFormat.PLAIN_TEXT -> DocumentFormat.PlainText
        NativeFormat.MARKDOWN -> DocumentFormat.Markdown
        NativeFormat.CSV -> DocumentFormat.Csv
        NativeFormat.JSON -> DocumentFormat.Json
        NativeFormat.SOURCE_CODE -> DocumentFormat.SourceCode
        NativeFormat.HTML -> DocumentFormat.Html
        NativeFormat.PDF -> DocumentFormat.Pdf
        NativeFormat.DOCX -> DocumentFormat.Docx
        NativeFormat.XLSX -> DocumentFormat.Xlsx
        NativeFormat.PPTX -> DocumentFormat.Pptx
        NativeFormat.ODT -> DocumentFormat.Odt
        NativeFormat.ODS -> DocumentFormat.Ods
        NativeFormat.ODP -> DocumentFormat.Odp
        NativeFormat.EPUB -> DocumentFormat.Epub
        NativeFormat.DOC -> DocumentFormat.Doc
        NativeFormat.XLS -> DocumentFormat.Xls
        NativeFormat.PPT -> DocumentFormat.Ppt
        NativeFormat.ZIP -> DocumentFormat.Archive
        NativeFormat.IMAGE -> DocumentFormat.Image
        // Detection-only formats: the engine refines or refuses them before opening.
        NativeFormat.OLE_COMPOUND, NativeFormat.UNKNOWN ->
            throw DocumentException(DocumentError.Internal("engine opened unexpected format $format"))
    },
    kind = when (kind) {
        NativeDocumentKind.TEXT -> DocumentKind.Text
        NativeDocumentKind.SPREADSHEET -> DocumentKind.Spreadsheet
        NativeDocumentKind.PRESENTATION -> DocumentKind.Presentation
        NativeDocumentKind.PDF -> DocumentKind.Pdf
        NativeDocumentKind.ARCHIVE -> DocumentKind.Archive
        NativeDocumentKind.IMAGE -> DocumentKind.Image
    },
    byteSize = byteSize.toLong(),
    encoding = encoding,
)

private fun NativeBatch.toDomain() = ContentBatch(
    blocks = blocks.map { block ->
        when (block) {
            is NativeBlock.Heading -> ContentBlock.Heading(block.level.toInt(), block.text, block.spans.toDomain())
            is NativeBlock.Paragraph -> ContentBlock.Paragraph(block.text, block.spans.toDomain())
            is NativeBlock.ListItem ->
                ContentBlock.ListItem(block.depth.toInt(), block.text, block.marker, block.spans.toDomain())
            is NativeBlock.Quote -> ContentBlock.Quote(block.text, block.spans.toDomain())
            is NativeBlock.Code -> ContentBlock.Code(block.text, block.language, block.firstLine?.toInt())
            NativeBlock.Divider -> ContentBlock.Divider
            is NativeBlock.TableRow ->
                ContentBlock.TableRow(block.row?.toInt(), block.cells, block.header, block.styles.map { it.toInt() })
            is NativeBlock.CellStyle -> ContentBlock.CellStyle(block.index.toInt(), block.style.toDomain())
            is NativeBlock.Merge -> ContentBlock.Merge(
                block.firstRow.toInt(),
                block.firstColumn.toInt(),
                block.lastRow.toInt(),
                block.lastColumn.toInt(),
            )
            is NativeBlock.CellRuns ->
                ContentBlock.CellRuns(block.row.toInt(), block.column.toInt(), block.spans.toDomain())
            is NativeBlock.Count -> ContentBlock.Count(block.total.toInt())
            is NativeBlock.Section -> ContentBlock.Section(block.title)
            is NativeBlock.Entry -> ContentBlock.Entry(block.path, block.size.toLong(), block.isDir)
            is NativeBlock.Image -> ContentBlock.Image(block.path, block.alt, block.width, block.height)
            is NativeBlock.Slide -> ContentBlock.Slide(
                block.width,
                block.height,
                block.background?.toInt(),
                block.backgroundImage,
                block.transition,
            )
            is NativeBlock.Shape -> ContentBlock.Shape(block.shape.toDomain())
        }
    },
    isLast = isLast,
    truncated = truncated,
)

private fun NativeCellStyle.toDomain(): CellLook {
    val style = flags.toInt()
    return CellLook(
        bold = style and 1 != 0,
        italic = style and 2 != 0,
        underline = style and 4 != 0,
        strikethrough = style and 8 != 0,
        color = color?.toInt(),
        fill = fill?.toInt(),
        size = size,
        align = when (align.toInt()) {
            1 -> CellAlign.Left
            2 -> CellAlign.Center
            3 -> CellAlign.Right
            else -> CellAlign.Auto
        },
        wrap = wrap,
    )
}

private fun List<NativeSpan>.toDomain(): List<TextSpan> = map { span ->
    val style = span.style.toInt()
    TextSpan(
        start = span.start.toInt(),
        end = span.end.toInt(),
        bold = style and 1 != 0,
        italic = style and 2 != 0,
        underline = style and 4 != 0,
        strikethrough = style and 8 != 0,
        code = style and 16 != 0,
        link = span.link,
        color = span.color?.toInt(),
    )
}

private fun NativeShape.toDomain() = SlideShape(
    x = x,
    y = y,
    width = width,
    height = height,
    rotation = rotation,
    geometry = when (geometry) {
        NativeGeometry.RECT -> ShapeGeometry.Rect
        NativeGeometry.ROUND_RECT -> ShapeGeometry.RoundRect
        NativeGeometry.ELLIPSE -> ShapeGeometry.Ellipse
        NativeGeometry.LINE -> ShapeGeometry.Line
    },
    fill = fill?.toInt(),
    line = line?.toInt(),
    lineWidth = lineWidth,
    image = image,
    paragraphs = paragraphs.map { p ->
        ShapeParagraph(
            text = p.text,
            spans = p.spans.toDomain(),
            size = p.size,
            color = p.color?.toInt(),
            bold = p.bold,
            italic = p.italic,
            align = p.align.toInt(),
            bullet = p.bullet,
            level = p.level.toInt(),
        )
    },
    anchor = anchor.toInt(),
    insets = insets,
    table = table,
    tableHeader = tableHeader,
    build = build?.let { ShapeBuild(it.step.toInt(), it.effect.toInt()) },
    media = media,
)
