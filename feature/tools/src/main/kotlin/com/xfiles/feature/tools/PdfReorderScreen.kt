package com.xfiles.feature.tools

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import com.xfiles.design.rememberHaptics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.design.HapticOnChange
import com.xfiles.design.glassCard

/**
 * Pages in a grid. Long-press a page and drag to move it: the grid keys cells by
 * source page, so the others slide aside while the dragged page follows the finger.
 * A swap fires one haptic tick. Each page can also be turned or removed.
 */
@Composable
fun PdfReorderScreen(
    onBack: () -> Unit,
    viewModel: PdfReorderViewModel = hiltViewModel(),
) {
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    HapticOnChange(frame.outputs) { if (it.isNotEmpty()) confirm() }
    HapticOnChange(frame.error) { if (it != null) reject() }
    val slots by viewModel.slots.collectAsStateWithLifecycle()
    var pickedUri by rememberSaveable { mutableStateOf<String?>(null) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri.toString()
            viewModel.open(uri)
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) viewModel.save(uri)
    }
    val back = {
        if (!frame.saving) {
            viewModel.release()
            onBack()
        }
    }
    BackHandler(onBack = back)

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DedicatedToolHeader(title = "Reorder pages", onBack = back)
        PdfSourceCard(
            frame = frame,
            onChoose = { choose.launch(arrayOf("application/pdf")) },
            onUnlock = { password -> pickedUri?.let { viewModel.open(Uri.parse(it), password) } },
        )
        if (frame.isOpen && frame.outputs.isEmpty()) {
            Text(
                "Long-press a page and drag it. Turn or remove pages below each one.",
                style = MaterialTheme.typography.bodySmall,
            )
            PdfReorderGrid(
                slots = slots,
                enabled = !frame.busy,
                preview = viewModel::pagePreview,
                onMove = viewModel::move,
                onTurn = viewModel::turn,
                onRemove = viewModel::remove,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        PdfBusyBar(frame = frame, onCancel = viewModel::cancel)
        PdfMessages(frame)
        frame.outputs.singleOrNull()?.let { output -> Text("Ready to save · ${output.pages} pages") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (frame.outputs.isNotEmpty()) {
                Button(onClick = { save.launch(reorderedName(frame.name)) }, enabled = !frame.busy) { Text("Save PDF") }
            } else if (frame.isOpen) {
                Button(
                    onClick = viewModel::apply,
                    enabled = !frame.busy && slots.isNotEmpty(),
                ) { Text("Apply order") }
            }
        }
    }
}

@Composable
private fun PdfReorderGrid(
    slots: List<PdfReorderSlot>,
    enabled: Boolean,
    preview: suspend (Int) -> Bitmap,
    onMove: (Int, Int) -> Unit,
    onTurn: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val haptics = rememberHaptics()
    val currentSlots by rememberUpdatedState(slots)
    val currentMove by rememberUpdatedState(onMove)
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        state = gridState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(slots, key = { _, slot -> slot.id }) { index, slot ->
            val lifted = dragging == slot.id
            PdfReorderCell(
                index = index,
                slot = slot,
                lifted = lifted,
                offset = if (lifted) dragOffset else Offset.Zero,
                enabled = enabled,
                preview = preview,
                onTurn = { onTurn(slot.id) },
                onRemove = { onRemove(slot.id) },
                modifier = Modifier
                    .animateItem()
                    .pointerInput(slot.id, enabled) {
                        if (!enabled) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragging = slot.id
                                dragOffset = Offset.Zero
                                 haptics.longPress() 
                            },
                            onDragEnd = {
                                dragging = null
                                dragOffset = Offset.Zero
                            },
                            onDragCancel = {
                                dragging = null
                                dragOffset = Offset.Zero
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount
                                val id = dragging
                                if (id != null) {
                                    val hover = hoverCells(gridState, id, dragOffset)
                                    val from = currentSlots.indexOfFirst { it.id == id }
                                    if (hover != null && from >= 0 && hover.second.index != from) {
                                        val (dragged, target) = hover
                                        // The dragged cell takes the target's place, so shift the offset
                                        // by the same amount: the page stays under the finger.
                                        dragOffset = Offset(
                                            dragOffset.x + dragged.offset.x - target.offset.x,
                                            dragOffset.y + dragged.offset.y - target.offset.y,
                                        )
                                        currentMove(from, target.index)
                                        haptics.gestureStart()
                                    }
                                }
                            },
                        )
                    },
            )
        }
    }
}

/**
 * The dragged cell and the cell under its centre, when that is a different cell.
 * Only visible cells are checked, so this is linear in the number of visible rows.
 */
private fun hoverCells(state: LazyGridState, id: Int, delta: Offset): Pair<LazyGridItemInfo, LazyGridItemInfo>? {
    val visible = state.layoutInfo.visibleItemsInfo
    val dragged = visible.firstOrNull { it.key == id } ?: return null
    val cx = dragged.offset.x + dragged.size.width / 2f + delta.x
    val cy = dragged.offset.y + dragged.size.height / 2f + delta.y
    val target = visible.firstOrNull { item ->
        item.key != id &&
            cx >= item.offset.x && cx <= item.offset.x + item.size.width &&
            cy >= item.offset.y && cy <= item.offset.y + item.size.height
    } ?: return null
    return dragged to target
}

@Composable
private fun PdfReorderCell(
    index: Int,
    slot: PdfReorderSlot,
    lifted: Boolean,
    offset: Offset,
    enabled: Boolean,
    preview: suspend (Int) -> Bitmap,
    onTurn: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier,
) {
    val image by produceState<ImageBitmap?>(null, slot.id) { value = preview(slot.id).asImageBitmap() }
    Column(
        modifier
            .zIndex(if (lifted) 1f else 0f)
            .graphicsLayer {
                translationX = offset.x
                translationY = offset.y
                val scale = if (lifted) 1.05f else 1f
                scaleX = scale
                scaleY = scale
                shadowElevation = if (lifted) 12f else 0f
            }
            .glassCard()
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f), contentAlignment = Alignment.Center) {
            image?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = "Page ${slot.id + 1}",
                    modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = slot.rotation.toFloat() },
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Text("${index + 1} · from ${slot.id + 1}", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onTurn, enabled = enabled) { Text("Turn") }
            TextButton(onClick = onRemove, enabled = enabled) { Text("Remove") }
        }
    }
}

private fun reorderedName(name: String): String =
    name.removeSuffix(".pdf").ifBlank { "Document" } + " reordered.pdf"
