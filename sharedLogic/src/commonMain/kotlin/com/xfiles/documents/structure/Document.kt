package com.xfiles.documents.structure

/** Resource budget for one reading session. All sizes are bytes. */
data class ReadLimits(
    val maxInputBytes: Long,
    val maxBatchBytes: Long,
    /** Text kept for display. Text is held in memory as UTF-16, so this
     *  costs about twice its value in heap. */
    val maxTotalOutputBytes: Long,
) {
    companion object {
        val Default = ReadLimits(
            maxInputBytes = 256L * 1024 * 1024,
            maxBatchBytes = 64L * 1024,
            maxTotalOutputBytes = 16L * 1024 * 1024,
        )
    }
}

/** Formats the engine can open, with names for display. */
enum class DocumentFormat(val label: String) {
    PlainText("Text"),
    Markdown("Markdown"),
    Csv("CSV"),
    Json("JSON"),
    SourceCode("Code"),
    Html("HTML"),
    Pdf("PDF"),
    Docx("Word"),
    Xlsx("Excel"),
    Pptx("PowerPoint"),
    Odt("OpenDocument Text"),
    Ods("OpenDocument Spreadsheet"),
    Odp("OpenDocument Presentation"),
    Epub("EPUB"),
    Doc("Word 97-2003"),
    Xls("Excel 97-2003"),
    Ppt("PowerPoint 97-2003"),
    Archive("ZIP archive"),
    Image("Image"),
}

/** Selects the viewer. */
enum class DocumentKind { Text, Spreadsheet, Presentation, Pdf, Archive, Image }

data class DocumentInfo(
    val name: String,
    val format: DocumentFormat,
    val kind: DocumentKind,
    val byteSize: Long,
    val encoding: String?,
)

/** Inline styling over [start] until [end] (UTF-16 indices, like Kotlin strings). */
data class TextSpan(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val code: Boolean = false,
    /** A web or mail link; the engine drops any other scheme. */
    val link: String? = null,
    /** ARGB text colour, where it differs from the text around it. */
    val color: Int? = null,
)

/** How a spreadsheet cell looks. Colours are ARGB; [size] is in points, `null` for the usual size. */
data class CellLook(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val color: Int? = null,
    val fill: Int? = null,
    val size: Float? = null,
    val align: CellAlign = CellAlign.Auto,
    val wrap: Boolean = false,
)

/** [Auto] puts numbers right and text left, as spreadsheets do. */
enum class CellAlign { Auto, Left, Center, Right }

sealed interface ContentBlock {
    /** [level] 1 is the most prominent. */
    data class Heading(val level: Int, val text: String, val spans: List<TextSpan> = emptyList()) : ContentBlock

    data class Paragraph(val text: String, val spans: List<TextSpan> = emptyList()) : ContentBlock

    /**
     * [depth] 0 is the outermost list. [marker] is the visible label ("3.",
     * "☑"), `null` for a bullet, or empty for a continuation paragraph.
     */
    data class ListItem(
        val depth: Int,
        val text: String,
        val marker: String? = null,
        val spans: List<TextSpan> = emptyList(),
    ) : ContentBlock

    /** A quotation, call-out or speaker note. */
    data class Quote(val text: String, val spans: List<TextSpan> = emptyList()) : ContentBlock

    /** Preformatted text; [firstLine] numbers the lines of source files. */
    data class Code(val text: String, val language: String? = null, val firstLine: Int? = null) : ContentBlock

    data object Divider : ContentBlock

    /**
     * [row] is the spreadsheet row number; `null` for tables in documents.
     * [styles] index the workbook's [CellStyle]s per cell, 0 being plain; it
     * may be shorter than [cells], or longer for coloured empty cells.
     */
    data class TableRow(
        val row: Int?,
        val cells: List<String>,
        val header: Boolean = false,
        val styles: List<Int> = emptyList(),
    ) : ContentBlock

    /** Entry [index] (from 1) of the workbook's palette of cell looks, sent before the rows that use it. */
    data class CellStyle(val index: Int, val look: CellLook) : ContentBlock

    /**
     * Cells of the current sheet shown as one, with the top-left cell's value.
     * Rows count from 1 like [TableRow.row], columns from 0; both ends included.
     */
    data class Merge(val firstRow: Int, val firstColumn: Int, val lastRow: Int, val lastColumn: Int) : ContentBlock

    /**
     * Differently styled runs inside one spreadsheet cell, such as one word in red.
     * [row] counts from 1 like [TableRow.row], [column] from 0. Sent right after its row.
     */
    data class CellRuns(val row: Int, val column: Int, val spans: List<TextSpan>) : ContentBlock

    /** How many slides the whole document has, sent before them. */
    data class Count(val total: Int) : ContentBlock

    /** Start of a sheet, slide or chapter. */
    data class Section(val title: String) : ContentBlock

    data class Entry(val path: String, val size: Long, val isDirectory: Boolean) : ContentBlock

    /**
     * A picture in the reading flow. [path] names it for the document session;
     * `null` means nothing can be loaded, so only the label shows. [width] and
     * [height] are the intended size in points, if known.
     */
    data class Image(
        val path: String?,
        val alt: String? = null,
        val width: Float? = null,
        val height: Float? = null,
    ) : ContentBlock

    /** The canvas of the current slide, in points; [background] is ARGB. */
    data class Slide(
        val width: Float,
        val height: Float,
        val background: Int?,
        val backgroundImage: String?,
        val transition: String?,
    ) : ContentBlock

    /** One positioned element of the current slide. */
    data class Shape(val shape: SlideShape) : ContentBlock
}

data class ContentBatch(
    val blocks: List<ContentBlock>,
    val isLast: Boolean,
    /** Reading stopped at [ReadLimits.maxTotalOutputBytes]. */
    val truncated: Boolean,
)
