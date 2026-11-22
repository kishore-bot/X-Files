package com.xfiles.feature.tools

import androidx.compose.foundation.layout.fillMaxWidth
import android.graphics.Bitmap
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzShapes
import com.xfiles.design.glassCard
import com.xfiles.design.rememberHaptics

/**
 * Pages in a grid. Long-press a page and drag: the grid keys cells by source page, so the others slide aside while
 * the dragged page follows the finger. A swap fires one haptic tick. Each page can also be turned or removed.
 */
@Composable
internal fun PdfReorderGrid(
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
        modifier = modifier,
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
                                        // The dragged cell takes the target's place, so shift the offset by the same
                                        // amount: the page stays under the finger.
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

/** The dragged cell and the cell under its centre, when that is a different cell. Only visible cells are checked. */
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
            .glassCard(EzShapes.Card, 0.5f)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PreviewTile(
            key = slot.id,
            load = { preview(slot.id) },
            modifier = Modifier.fillMaxWidth().aspectRatio(0.72f),
            description = "Page ${slot.id + 1}",
            rotationDegrees = slot.rotation.toFloat(),
        )
        Text(
            "${index + 1} · from ${slot.id + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = EzColors.Text,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onTurn, enabled = enabled) {
                Icon(EzIcons.Rotate, contentDescription = "Turn page ${slot.id + 1}", tint = EzColors.Primary, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(EzIcons.Delete, contentDescription = "Remove page ${slot.id + 1}", tint = EzColors.Accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}
