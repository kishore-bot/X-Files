package com.xfiles.feature.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xfiles.core.documents.PdfImageQuality
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzSectionHeader
import com.xfiles.design.EzShapes
import com.xfiles.design.EzSwitch
import com.xfiles.design.EzTextButton
import com.xfiles.design.glassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

private const val PICTURE_COLUMNS = 3

/**
 * The picture steps of Pictures to PDF, Scan to PDF, Add pictures and Picture to text: the pick card (or the single
 * picture), the reorderable picture grid, and for PDFs the page size, the quality and the searchable text.
 */
internal fun LazyListScope.pictureSections(state: ConvertState, viewModel: ConvertViewModel, launch: ConvertLaunch) {
    val tool = state.tool
    val locked = state.busy || state.output != null
    val scan = tool == ConvertTool.ScanToPdf
    if (state.pictures.isEmpty()) {
        item("pick") {
            FilePickCard(
                title = when (tool) {
                    ConvertTool.ScanToPdf -> "Scan pages"
                    ConvertTool.PictureToText -> "Choose a picture"
                    else -> "Choose pictures"
                },
                hint = when (tool) {
                    ConvertTool.ScanToPdf -> "Use the camera. Each page is straightened and filtered."
                    ConvertTool.PictureToText -> "Pick a picture with text in it"
                    else -> "Pick several pictures; they become pages in the order you set"
                },
                icon = if (scan) EzIcons.Camera else EzIcons.Image,
                tint = EzColors.Primary,
                onClick = {
                    when (tool) {
                        ConvertTool.ScanToPdf -> launch.scan()
                        ConvertTool.PictureToText -> launch.pickPicture()
                        else -> launch.pickPictures()
                    }
                },
            )
        }
    } else if (tool == ConvertTool.PictureToText) {
        item("picture") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PictureTile(
                    uri = state.pictures.first(),
                    modifier = Modifier.fillMaxWidth(0.6f).aspectRatio(0.72f),
                )
                EzTextButton(text = "Choose another picture", onClick = launch.pickPicture, enabled = !locked)
            }
        }
    } else {
        item("pictures-head") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EzSectionHeader("${state.pictures.size} ${if (state.pictures.size == 1) "page" else "pages"}")
                Spacer(Modifier.weight(1f))
                EzTextButton(
                    text = if (scan) "Scan more" else "Add pictures",
                    onClick = if (scan) launch.scan else launch.pickPictures,
                    enabled = !locked,
                )
            }
        }
        pictureGrid(state.pictures, enabled = !locked, viewModel = viewModel)
    }
    if (tool != ConvertTool.PictureToText) {
        item("paper") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Page size", style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
                PaperPicker(
                    selected = state.paper,
                    enabled = !locked,
                    onSelect = viewModel::setPaper,
                    allowFit = true,
                )
            }
        }
        item("quality") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Picture quality", style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PdfImageQuality.entries.forEach { level ->
                        ChoiceCard(
                            title = level.label,
                            detail = null,
                            selected = state.quality == level,
                            enabled = !locked,
                            onClick = { viewModel.setQuality(level) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
    if (tool == ConvertTool.PicturesToPdf || tool == ConvertTool.ScanToPdf) {
        item("searchable") {
            Row(
                Modifier.fillMaxWidth().glassCard(EzShapes.Card, 0.45f).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Searchable text", style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
                    Text(
                        "Reads each page on this phone and adds invisible text, so it can be searched and copied.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EzColors.TextSecondary,
                    )
                }
                EzSwitch(checked = state.searchable, onCheckedChange = viewModel::setSearchable)
            }
        }
    }
}

/**
 * The pictures as rows of three pages. Each page moves one place earlier or later, or leaves the list. The rows are
 * plain items, so the whole screen scrolls as one list.
 */
private fun LazyListScope.pictureGrid(pictures: List<Uri>, enabled: Boolean, viewModel: ConvertViewModel) {
    pictures.chunked(PICTURE_COLUMNS).forEachIndexed { row, chunk ->
        item("pictures-row-$row") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chunk.forEachIndexed { column, uri ->
                    val index = row * PICTURE_COLUMNS + column
                    PictureCell(
                        index = index,
                        count = pictures.size,
                        uri = uri,
                        enabled = enabled,
                        onMove = { delta -> viewModel.movePicture(index, index + delta) },
                        onRemove = { viewModel.removePicture(uri) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(PICTURE_COLUMNS - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PictureCell(
    index: Int,
    count: Int,
    uri: Uri,
    enabled: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.glassCard(EzShapes.Card, 0.45f).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PictureTile(uri = uri, modifier = Modifier.fillMaxWidth().aspectRatio(0.72f))
        Text("Page ${index + 1}", style = MaterialTheme.typography.labelMedium, color = EzColors.Text)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMove(-1) }, enabled = enabled && index > 0, modifier = Modifier.size(36.dp)) {
                Icon(EzIcons.ChevronLeft, contentDescription = "Move page ${index + 1} earlier", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { onMove(1) }, enabled = enabled && index < count - 1, modifier = Modifier.size(36.dp)) {
                Icon(EzIcons.ChevronRight, contentDescription = "Move page ${index + 1} later", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(36.dp)) {
                Icon(EzIcons.Close, contentDescription = "Remove page ${index + 1}", tint = EzColors.Accent, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** A picture's thumbnail, decoded small on the IO lane. */
@Composable
internal fun PictureTile(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    PreviewTile(
        key = uri.toString(),
        load = { loadPictureThumb(context, uri, THUMB_SIDE) },
        modifier = modifier,
        description = "Picture",
    )
}

private const val THUMB_SIDE = 320

/** Decodes [uri] at a size no larger than [side] pixels, so a long list stays light in memory. */
internal suspend fun loadPictureThumb(context: Context, uri: Uri, side: Int): Bitmap = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: error("The picture could not be opened.")
}
