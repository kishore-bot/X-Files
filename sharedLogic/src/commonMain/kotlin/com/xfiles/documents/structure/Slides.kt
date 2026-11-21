package com.xfiles.documents.structure

/** Outline of a slide element. */
enum class ShapeGeometry { Rect, RoundRect, Ellipse, Line }

/** A paragraph inside a slide shape; [size] in points, [color] ARGB. */
data class ShapeParagraph(
    val text: String,
    val spans: List<TextSpan>,
    val size: Float,
    val color: Int?,
    val bold: Boolean,
    val italic: Boolean,
    /** 0 left, 1 centre, 2 right, 3 justified. */
    val align: Int,
    val bullet: String?,
    val level: Int,
)

/**
 * When a shape appears while presenting: on click [step] (0 = as the slide
 * opens), with PowerPoint's preset entrance [effect] id.
 */
data class ShapeBuild(val step: Int, val effect: Int)

/** A slide element positioned in points on its slide. Colours are ARGB. */
data class SlideShape(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
    val geometry: ShapeGeometry,
    val fill: Int?,
    val line: Int?,
    val lineWidth: Float,
    /** Container path of a picture, loaded through the document session. */
    val image: String?,
    val paragraphs: List<ShapeParagraph>,
    /** 0 top, 1 middle, 2 bottom. */
    val anchor: Int,
    /** Left, top, right, bottom, in points. */
    val insets: List<Float>,
    val table: List<List<String>>,
    val tableHeader: Boolean,
    val build: ShapeBuild?,
    /** Embedded audio or video this shape plays; [image] is its poster. */
    val media: String? = null,
)
