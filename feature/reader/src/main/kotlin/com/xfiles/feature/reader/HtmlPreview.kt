package com.xfiles.feature.reader

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xfiles.design.glassPanel
import com.xfiles.design.rememberHaptics
import com.xfiles.feature.reader.domain.HtmlLinkAction
import com.xfiles.feature.reader.domain.classifyHtmlLink
import java.io.ByteArrayInputStream

/**
 * A local web page, shown read-only. Scripts, network loads, and file or content access stay off, so
 * opening a page can't track the reader or leak the file. Links: `#anchors` scroll in place, files next to
 * the page open in the reader, and web or mail links open in another app after a confirmation.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun HtmlPreview(
    html: String,
    reading: ReadingTheme = ReadingTheme.Standard,
    baseUri: Uri? = null,
    onOpenSibling: (Uri) -> Unit = {},
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val original = LocalOriginalView.current
    // Original view keeps the page's own colours on white; the themed view adds glass or a reading page.
    val palette = if (original) null else reading.palette()
    // The page keeps an about:blank base, so relative hrefs reach the link handler as written.
    val pageUrl = "about:blank"
    var pendingLink by rememberSaveable(html) { mutableStateOf<String?>(null) }
    val siblingHandler by rememberUpdatedState(onOpenSibling)
    val page = when {
        original -> Modifier.fillMaxSize().background(Color.White, RoundedCornerShape(16.dp))
        palette == null -> Modifier.fillMaxSize().glassPanel(RoundedCornerShape(16.dp), elevation = 3.dp)
        else -> Modifier.fillMaxSize().background(palette.page, RoundedCornerShape(16.dp))
    }
    // The reading colours go in as a stylesheet ahead of the page; the page itself is unchanged.
    val pageHtml = if (palette == null) html else readingHtml(html, palette)
    Box(page.clip(RoundedCornerShape(16.dp))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    setBackgroundColor(Color.White.toArgb())
                    settings.apply {
                        javaScriptEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        blockNetworkLoads = true
                        blockNetworkImage = true
                        domStorageEnabled = false
                        setGeolocationEnabled(false)
                        builtInZoomControls = true
                        displayZoomControls = false
                        loadWithOverviewMode = true
                        useWideViewPort = true
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            if (request.url.scheme == "data") null else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url
                            return when (val action = classifyHtmlLink(viewContext, baseUri, url, pageUrl)) {
                                // Same-document fragments are left to the web view, which scrolls to the anchor.
                                HtmlLinkAction.ScrollInPlace -> false
                                is HtmlLinkAction.External -> {
                                    if (request.hasGesture()) pendingLink = action.url
                                    true
                                }
                                is HtmlLinkAction.Sibling -> {
                                    if (request.hasGesture()) siblingHandler(action.uri)
                                    true
                                }
                                HtmlLinkAction.Ignore -> true // The viewer itself never navigates.
                            }
                        }
                    }
                }
            },
            update = { view ->
                if (view.tag != pageHtml.hashCode()) {
                    view.tag = pageHtml.hashCode()
                    view.loadDataWithBaseURL(pageUrl, pageHtml, "text/html", "utf-8", null)
                }
            },
            onRelease = { it.destroy() },
        )
        pendingLink?.let { link ->
            AlertDialog(
                onDismissRequest = { pendingLink = null },
                title = { Text("Open this link?") },
                text = { Text(link, maxLines = 4) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingLink = null
                        if (openExternalLink(context, link)) haptics.confirm() else haptics.reject()
                    }) { Text("Open") }
                },
                dismissButton = { TextButton(onClick = { pendingLink = null }) { Text("Cancel") } },
            )
        }
    }
}

/** Opens [link] in an app that handles it. False when no app can. */
private fun openExternalLink(context: Context, link: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE))
    true
} catch (_: ActivityNotFoundException) {
    Toast.makeText(context, "No app can open this link.", Toast.LENGTH_SHORT).show()
    false
}

/**
 * [html] with a reading-theme stylesheet at the start of its head (or before everything when it has none).
 * Backgrounds and text colours are forced with !important so the page's own colours yield; links keep the
 * palette's secondary tone. The page's markup and scripts (none run) are untouched.
 */
internal fun readingHtml(html: String, palette: ReadingPalette): String {
    val page = palette.page.hex()
    val text = palette.text.hex()
    val muted = palette.secondary.hex()
    val css = "<style>html,body{background:$page !important;color:$text !important}" +
        "body *{color:inherit}" +
        "a{color:$muted !important}" +
        "table,th,td,pre,code,blockquote{background:transparent !important}" +
        "th,td,pre,blockquote{border-color:$muted !important}</style>"
    val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
    return if (head != null) html.replaceRange(head.range, head.value + css) else css + html
}

private fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)
