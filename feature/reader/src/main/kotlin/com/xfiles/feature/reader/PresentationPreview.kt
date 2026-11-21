package com.xfiles.feature.reader

import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.glassBar
import com.xfiles.documents.structure.ContentBlock
import com.xfiles.documents.structure.SlideShape
import com.xfiles.documents.structure.StructuredDocument

@Composable
internal fun PresentationPreview(document: StructuredDocument) {
    val slides = remember(document.blocks) { groupSlides(document.blocks) }
    var current by rememberSaveable { mutableIntStateOf(0) }
    var notesVisible by rememberSaveable { mutableStateOf(false) }
    var presenting by rememberSaveable { mutableStateOf(false) }
    val position = current.coerceIn(0, slides.lastIndex.coerceAtLeast(0))
    val slide = slides.getOrNull(position)
    if (slide == null) {
        Text("This presentation has no readable slides.")
        return
    }
    if (presenting) {
        Slideshow(slides, position, onSlide = { current = it }, onExit = { presenting = false })
    }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            SlideCanvas(slide.canvas, slide.shapes, Modifier.widthIn(max = 820.dp).fillMaxWidth())
        }
        if (notesVisible && slide.notes.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                slide.notes.forEach { DocumentBlock(it) }
            }
        }
        if (document.truncated) Text("Showing the available slides within the preview limit.", color = EzColors.TextSecondary)
        Row(Modifier.fillMaxWidth().glassBar(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { current = position - 1 }, enabled = position > 0) {
                Icon(EzIcons.ChevronLeft, "Previous slide")
            }
            Column(Modifier.weight(1f)) {
                Text(slide.title.ifBlank { "Slide ${position + 1}" }, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                Text("${position + 1} / ${slides.size}", color = EzColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (slide.notes.isNotEmpty()) TextButton(onClick = { notesVisible = !notesVisible }) {
                Text(if (notesVisible) "Hide notes" else "Notes")
            }
            IconButton(onClick = { presenting = true }) { Icon(EzIcons.Fullscreen, "Present") }
            IconButton(onClick = { current = position + 1 }, enabled = position < slides.lastIndex) {
                Icon(EzIcons.ChevronRight, "Next slide")
            }
        }
    }
}

/**
 * Full-screen presenting: black background, system bars hidden, screen kept on. Swipe or tap the left
 * or right third to change slides; tap the middle to show the counter; back leaves.
 */
@Composable
private fun Slideshow(slides: List<PreviewSlide>, start: Int, onSlide: (Int) -> Unit, onExit: () -> Unit) {
    val pager = rememberPagerState(initialPage = start) { slides.size }
    var chrome by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    SideEffect(pager.currentPage) { onSlide(pager.currentPage) }
    LaunchedEffect(chrome) {
        if (chrome) {
            delay(2_000)
            chrome = false
        }
    }
    Dialog(onDismissRequest = onExit, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = true
            onDispose {
                view.keepScreenOn = false
                controller?.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        Box(
            Modifier.fillMaxSize().background(Color.Black).pointerInput(slides.size) {
                detectTapGestures { offset ->
                    val third = size.width / 3
                    when {
                        offset.x < third -> scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) }
                        offset.x > 2 * third -> scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(slides.lastIndex)) }
                        else -> chrome = true
                    }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }) { index ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    SlideCanvas(slides[index].canvas, slides[index].shapes, Modifier.fillMaxWidth())
                }
            }
            AnimatedVisibility(chrome, Modifier.align(Alignment.BottomCenter).padding(24.dp), enter = fadeIn(), exit = fadeOut()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${pager.currentPage + 1} / ${slides.size}", color = Color.White)
                    IconButton(onClick = onExit) { Icon(EzIcons.FullscreenExit, "Stop presenting", tint = Color.White) }
                }
            }
        }
    }
}

private data class PreviewSlide(
    val title: String,
    val canvas: ContentBlock.Slide,
    val shapes: List<SlideShape>,
    val notes: List<ContentBlock>,
)

private fun groupSlides(blocks: List<ContentBlock>): List<PreviewSlide> = buildList {
    var title = ""
    var canvas: ContentBlock.Slide? = null
    val shapes = mutableListOf<SlideShape>()
    val notes = mutableListOf<ContentBlock>()
    fun finish() {
        canvas?.let { add(PreviewSlide(title, it, shapes.toList(), notes.toList())) }
        canvas = null
        shapes.clear()
        notes.clear()
    }
    for (block in blocks) {
        when (block) {
            is ContentBlock.Section -> {
                finish()
                title = block.title
            }
            is ContentBlock.Slide -> {
                if (canvas != null) finish()
                canvas = block
            }
            is ContentBlock.Shape -> shapes.add(block.shape)
            is ContentBlock.Quote -> notes.add(block)
            else -> Unit
        }
    }
    finish()
}
