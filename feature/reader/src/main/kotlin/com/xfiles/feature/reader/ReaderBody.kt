package com.xfiles.feature.reader

import androidx.compose.runtime.getValue
import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xfiles.core.documents.FileEntry
import com.xfiles.core.documents.FilePreview
import com.xfiles.core.documents.PdfPageText
import com.xfiles.documents.PdfMarkup
import com.xfiles.documents.structure.DocumentFormat
import kotlinx.coroutines.CancellationException

/**
 * The document area under the bars: the page grid, a loading or error message, or the viewer for the
 * open file's preview. Stateless: panels come from [ui] and changes go out through [onEvent]. The page
 * style is provided to every viewer here, with the text colour that contrasts with its page.
 */
@Composable
internal fun ReaderBody(
    modifier: Modifier,
    state: ReaderState,
    ui: ReaderUiState,
    onEvent: (ReaderUiEvent) -> Unit,
    pageStyle: PageStyle,
    gallery: GalleryUiState,
    paged: GallerySession?,
    rotation: Int,
    rotationOf: (String) -> Int,
    markup: PdfMarkupState,
    pdfFind: PdfFindState,
    structuredEdits: StructuredEditSession?,
    onBack: () -> Unit,
    /** Closes the reader; an EPUB book has no top bar of its own, so its own close control calls this. */
    onClose: () -> Unit,
    /** Opens Fily; an EPUB book's own bar carries the mic, since the reader's bar is hidden for it. */
    onFily: () -> Unit,
    onPassword: (String) -> Unit,
    onPage: (Int) -> Unit,
    onPdfPageSettled: (Int) -> Unit,
    onPdfQuery: (String) -> Unit,
    onAddMarkup: (PdfMarkup) -> Unit,
    pdfThumbnail: suspend (Int) -> Bitmap?,
    pdfText: suspend (Int) -> PdfPageText?,
    /** One page of the open PDF, for the continuous view; see DocumentAnalysisViewModel.pagePreview. */
    pagePreview: suspend (Int) -> FilePreview.Pdf?,
    onOpenArchiveEntry: (Uri) -> Unit,
    onGalleryPage: (Int) -> Unit,
    galleryPage: suspend (Int, Int) -> Bitmap?,
    galleryAnimation: suspend (Int, Int) -> AnimatedImageDrawable?,
    pictureAnimation: suspend (FileEntry, Int) -> AnimatedImageDrawable?,
) {
    val ready = state as? ReaderState.Ready
    val gridPdf = ready?.preview as? FilePreview.Pdf
    Box(modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        if (ui.showGrid && gridPdf != null) {
            PdfPageGrid(
                pageCount = gridPdf.pageCount,
                current = ui.visiblePdfPage ?: gridPdf.page,
                load = pdfThumbnail,
                onOpen = { index ->
                    onEvent(ReaderUiEvent.SetGrid(false))
                    onPage(index)
                },
                onClose = { onEvent(ReaderUiEvent.SetGrid(false)) },
            )
        } else when (state) {
            ReaderState.Loading -> CircularProgressIndicator()
            ReaderState.ReadError -> Text("This file could not be opened. Try selecting it again.")
            ReaderState.EngineUnavailable -> Text("The document engine is unavailable on this device.")
            is ReaderState.Password -> {
                Text(state.file.name)
                DocumentPasswordDialog(state, onPassword, onBack)
            }
            is ReaderState.Failure -> Text(state.message)
            // A gallery keeps one key across page turns, so the pager is not recreated when the file changes.
            is ReaderState.Ready -> CompositionLocalProvider(
                LocalPageStyle provides pageStyle,
                // Fixed page styles set the text colour, so default text contrasts with the page they sit on.
                LocalContentColor provides (pageStyle.colors()?.text ?: LocalContentColor.current),
            ) {
                key(paged?.id ?: state.file.uri) {
                    ReaderPreview(
                        state = state,
                        ui = ui,
                        onEvent = onEvent,
                        gallery = gallery,
                        paged = paged,
                        rotation = rotation,
                        rotationOf = rotationOf,
                        markup = markup,
                        pdfFind = pdfFind,
                        structuredEdits = structuredEdits,
                        onPdfPageSettled = onPdfPageSettled,
                        onPdfQuery = onPdfQuery,
                        onAddMarkup = onAddMarkup,
                        pdfText = pdfText,
                        pagePreview = pagePreview,
                        onBack = onBack,
                        onClose = onClose,
                        onFily = onFily,
                        onOpenArchiveEntry = onOpenArchiveEntry,
                        onGalleryPage = onGalleryPage,
                        galleryPage = galleryPage,
                        galleryAnimation = galleryAnimation,
                        pictureAnimation = pictureAnimation,
                    )
                }
            }
        }
    }
}

/** The viewer for a ready file's preview; the kind of preview decides the view. */
@Composable
private fun ReaderPreview(
    state: ReaderState.Ready,
    ui: ReaderUiState,
    onEvent: (ReaderUiEvent) -> Unit,
    gallery: GalleryUiState,
    paged: GallerySession?,
    rotation: Int,
    rotationOf: (String) -> Int,
    markup: PdfMarkupState,
    pdfFind: PdfFindState,
    structuredEdits: StructuredEditSession?,
    onPdfPageSettled: (Int) -> Unit,
    onPdfQuery: (String) -> Unit,
    onAddMarkup: (PdfMarkup) -> Unit,
    pdfText: suspend (Int) -> PdfPageText?,
    pagePreview: suspend (Int) -> FilePreview.Pdf?,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onFily: () -> Unit,
    onOpenArchiveEntry: (Uri) -> Unit,
    onGalleryPage: (Int) -> Unit,
    galleryPage: suspend (Int, Int) -> Bitmap?,
    galleryAnimation: suspend (Int, Int) -> AnimatedImageDrawable?,
    pictureAnimation: suspend (FileEntry, Int) -> AnimatedImageDrawable?,
) {
    when (val preview = state.preview) {
        is FilePreview.Text -> {
            val plain = ui.showSearch || ui.showSource
            val json = remember(preview) {
                if (state.file.name.endsWith(".json", ignoreCase = true)) parseJsonOrNull(preview.content) else null
            }
            when {
                // SVG: the drawn picture by default, its highlighted markup under "Source".
                isSvgName(state.file.name) && !ui.showSearch && !ui.showSource -> HtmlPreview(svgAsHtml(preview.content))
                isSvgName(state.file.name) && !ui.showSearch -> CodePreview(preview.content, state.file.name)
                !plain && json != null -> JsonTreePreview(json, preview.content)
                !plain && isSourceCode(state.file.name) -> CodePreview(preview.content, state.file.name)
                else -> TextPreview(preview, ui.showSearch, onSearchClose = { onEvent(ReaderUiEvent.SetSearch(false)) })
            }
        }
        is FilePreview.Html ->
            if (ui.showSearch || ui.showSource) TextPreview(preview.text, ui.showSearch, onSearchClose = { onEvent(ReaderUiEvent.SetSearch(false)) })
            // Relative links open the sibling file through the same nested-open path as archive entries.
            else HtmlPreview(preview.html, baseUri = state.file.uri, onOpenSibling = onOpenArchiveEntry)
        is FilePreview.Structured -> if (isZipName(state.file.name)) {
            ArchiveBrowser(archive = state.file.uri, onOpenFile = onOpenArchiveEntry)
        } else {
            if (ui.showSearch || (ui.showSource && preview.source != null)) {
                val text = preview.source ?: remember(preview.document) {
                    val content = preview.document.plainText(256_000)
                    FilePreview.Text(content, preview.document.truncated || content.length >= 256_000)
                }
                TextPreview(text, ui.showSearch, onSearchClose = { onEvent(ReaderUiEvent.SetSearch(false)) })
            } else if (preview.document.info.format == DocumentFormat.Epub) {
                // An EPUB book brings its own chrome (with a close control and Fily), so the reader's bar is hidden for it.
                CompositionLocalProvider(LocalDocumentImages provides preview.document.images) {
                    BookPreview(preview.document, onClose = onClose, onFily = onFily)
                }
            } else {
                CompositionLocalProvider(LocalDocumentImages provides preview.document.images) {
                    StructuredPreview(preview.document, structuredEdits)
                }
            }
        }
        is FilePreview.Picture -> if (paged != null && gallery is GalleryUiState.Paged) {
            GalleryPager(
                session = paged,
                seed = gallery.seed,
                seedUri = gallery.seedUri,
                rotationOf = rotationOf,
                onSettle = onGalleryPage,
                load = galleryPage,
                animate = galleryAnimation,
            )
        } else {
            // GIF, WebP and AVIF play here too; stills get null and stay on the bitmap.
            val animation by produceState<AnimatedImageDrawable?>(null, state.file.uri, preview.bitmap) {
                value = try {
                    pictureAnimation(state.file, maxOf(preview.bitmap.width, preview.bitmap.height))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            ReaderImage(
                bitmap = preview.bitmap,
                description = state.file.name,
                rotation = rotation,
                regions = rememberPictureRegions(state.file.uri),
                animated = animation,
                animating = true,
                pictureKey = state.file.uri.toString(),
            )
        }
        is FilePreview.Pdf -> ReaderPdfPage(
            ready = state,
            preview = preview,
            ui = ui,
            onEvent = onEvent,
            markup = markup,
            pdfFind = pdfFind,
            pdfText = pdfText,
            loadPage = pagePreview,
            onAddMarkup = onAddMarkup,
            onPdfPageSettled = onPdfPageSettled,
            onPdfQuery = onPdfQuery,
        )
        is FilePreview.Media -> MediaScreen(state.file, preview.video, onBack)
        FilePreview.Unsupported -> ExternalOpen(state.file)
    }
}
