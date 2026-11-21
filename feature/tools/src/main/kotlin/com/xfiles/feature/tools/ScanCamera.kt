package com.xfiles.feature.tools

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xfiles.core.documents.ScanFilter
import com.xfiles.core.documents.ScanProcessing
import com.xfiles.design.EzIcons
import com.xfiles.scan.AutoCaptureState
import com.xfiles.scan.PageQuad
import com.xfiles.scan.SteadyPage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

/**
 * The document scanner: live page outline, auto-capture when the page holds still and sharp, a manual
 * shutter, and Original / Enhance / Black & white. Analysis runs on one background thread, a frame at a
 * time (older frames are dropped), so the preview never stutters.
 */
@Composable
internal fun ScanCamera(processing: ScanProcessing, pages: Int, onPage: (File, PageQuad?, ScanFilter) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var permitted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    BackHandler(onBack = onDone)
    var review by remember { mutableStateOf<ReviewShot?>(null) }
    val reviewNow by rememberUpdatedState(review)
    val reviewOpen by rememberUpdatedState(review != null)
    BackHandler(enabled = review != null) {
        review?.photo?.delete()
        review = null
    }
    // A review left open when the scanner closes must not leave its photo behind.
    DisposableEffect(Unit) {
        onDispose { reviewNow?.photo?.delete() }
    }
    if (!permitted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
            Text("Xfiles needs the camera to scan pages. Pictures stay on this phone.")
            Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            TextButton(onClick = onDone) { Text("Back") }
        }
        return
    }

    var auto by rememberSaveable { mutableStateOf(true) }
    var filter by rememberSaveable { mutableStateOf(ScanFilter.Enhance) }
    val scope = rememberCoroutineScope()
    // The last filter chosen, read once off the main thread; later choices are written as they are made.
    LaunchedEffect(Unit) { filter = processing.lastFilter() }
    fun choose(option: ScanFilter) {
        filter = option
        scope.launch { processing.rememberFilter(option) }
    }
    var outline by remember { mutableStateOf<PageQuad?>(null) }
    var status by remember { mutableStateOf(AutoCaptureState.Searching) }
    val capturing = remember { AtomicBoolean(false) }
    val autoNow by rememberUpdatedState(auto)
    val filterNow by rememberUpdatedState(filter)
    // Read by the analysis thread too, so it always sees the current page count.
    val full by rememberUpdatedState(pages >= ScanProcessing.MAX_SCAN_PAGES)
    val onPageNow by rememberUpdatedState(onPage)
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val analyzer = remember { Executors.newSingleThreadExecutor { Thread(it, "xfiles-scan-analysis") } }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    /** [manual] shots open the review so the corners can be fixed; auto-capture goes straight to the page. */
    fun shoot(seen: PageQuad?, manual: Boolean) {
        if (full || !capturing.compareAndSet(false, true)) return
        val file = File.createTempFile("capture-", ".jpg", context.cacheDir)
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing.set(false)
                    if (manual) review = ReviewShot(file, seen) else onPageNow(file, seen, filterNow)
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing.set(false)
                    file.delete()
                }
            },
        )
    }

    DisposableEffect(lifecycle) {
        val future = ProcessCameraProvider.getInstance(context)
        val steady = SteadyPage() // Confined to the analysis thread.
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { use ->
                use.setAnalyzer(analyzer) { image ->
                    val frame = image.use { proxy -> downsampledLuma(proxy) }
                    val result = processing.analyze(frame.luma, frame.width, frame.height)
                    val quad = result.outline?.let { frame.toUpright(it) }
                    val state = steady.offer(quad, result.sharp, System.currentTimeMillis())
                    ContextCompat.getMainExecutor(context).execute {
                        outline = quad
                        status = state
                        if (state == AutoCaptureState.Take && autoNow && !reviewOpen && !full) shoot(quad, manual = false)
                    }
                }
            }
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            analysis.clearAnalyzer()
            if (future.isDone) future.get().unbindAll()
            analyzer.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // CameraX's default 4:3 frame, upright on a phone held in portrait; the overlay shares this box,
        // so outline fractions land exactly on the preview.
        Box(Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(3f / 4f)) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        outline?.let { quad ->
            val tint = if (status == AutoCaptureState.Holding || status == AutoCaptureState.Take) Color(0xFF4CAF50) else Color(0xFFFFC107)
            Canvas(Modifier.fillMaxSize()) {
                val path = Path().apply {
                    quad.corners.forEachIndexed { index, point ->
                        val at = Offset(point.x * size.width, point.y * size.height)
                        if (index == 0) moveTo(at.x, at.y) else lineTo(at.x, at.y)
                    }
                    close()
                }
                drawPath(path, tint.copy(alpha = 0.25f))
                drawPath(path, tint, style = Stroke(width = 3.dp.toPx()))
            }
        }
        }
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color.Black.copy(alpha = 0.45f)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDone) { Icon(EzIcons.Back, "Done scanning", tint = Color.White) }
                Text(
                    when {
                        full -> "Page limit reached. Tap Done to save."
                        else -> when (status) {
                            AutoCaptureState.Searching -> "Point at a page"
                            AutoCaptureState.Moving -> "Hold steady"
                            AutoCaptureState.Holding, AutoCaptureState.Take -> "Hold still…"
                            AutoCaptureState.Taken -> "Got it — next page"
                        }
                    },
                    Modifier.weight(1f),
                    color = Color.White,
                )
                Text("Auto", color = Color.White)
                Switch(checked = auto && !full, onCheckedChange = { auto = it }, enabled = !full, modifier = Modifier.padding(start = 8.dp))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanFilter.entries.forEach { option ->
                    FilterChip(selected = filter == option, onClick = { choose(option) }, label = { Text(option.label, color = Color.White) })
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.45f)).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("$pages / ${ScanProcessing.MAX_SCAN_PAGES}", color = Color.White)
            Box(
                Modifier.size(68.dp).clip(CircleShape).background(Color.White.copy(alpha = if (full) 0.3f else 1f)),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = { shoot(outline, manual = true) }, enabled = !full, modifier = Modifier.size(68.dp)) {
                    Icon(EzIcons.Camera, "Take picture", tint = Color.Black)
                }
            }
            TextButton(onClick = onDone) { Text("Done", color = Color.White) }
        }
        review?.let { shot ->
            ScanReview(
                photo = shot.photo,
                initial = shot.seen,
                filter = filter,
                onFilter = ::choose,
                processing = processing,
                onRetake = {
                    shot.photo.delete()
                    review = null
                },
                onKeep = { quad ->
                    review = null
                    onPageNow(shot.photo, quad, filterNow)
                },
            )
        }
    }
}

/** A manual shot waiting for its corners to be checked. */
private class ReviewShot(val photo: File, val seen: PageQuad?)

/** A frame's luma, downsampled to at most ~320 px on the long side, plus how to turn it upright. */
private class LumaFrame(val luma: ByteArray, val width: Int, val height: Int, private val rotation: Int) {
    /** Maps an outline found in sensor orientation into the upright preview's fractions. */
    fun toUpright(quad: PageQuad): PageQuad {
        fun turn(p: com.xfiles.scan.ScanPoint) = when (rotation) {
            90 -> com.xfiles.scan.ScanPoint(1f - p.y, p.x)
            180 -> com.xfiles.scan.ScanPoint(1f - p.x, 1f - p.y)
            270 -> com.xfiles.scan.ScanPoint(p.y, 1f - p.x)
            else -> p
        }
        return com.xfiles.scan.orderCorners(quad.corners.map(::turn))
    }
}

private fun downsampledLuma(image: ImageProxy): LumaFrame {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val step = maxOf(1, maxOf(image.width, image.height) / 320)
    val width = image.width / step
    val height = image.height / step
    val luma = ByteArray(width * height)
    for (y in 0 until height) {
        val row = y * step * plane.rowStride
        for (x in 0 until width) luma[y * width + x] = buffer.get(row + x * step * plane.pixelStride)
    }
    return LumaFrame(luma, width, height, image.imageInfo.rotationDegrees)
}
