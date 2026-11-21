package com.xfiles.feature.reader

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xfiles.design.glassPanel
import java.io.ByteArrayInputStream

/**
 * A local web page, shown read-only. The page can't run scripts, read files or content URIs, or load
 * anything from the network (every sub-resource gets an empty response), so opening it can't track the
 * reader or leak the file. Tapping an https link opens it in the user's browser instead.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun HtmlPreview(html: String) {
    Box(Modifier.fillMaxSize().glassPanel(RoundedCornerShape(16.dp), elevation = 3.dp).clip(RoundedCornerShape(16.dp))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
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
                            if (request.hasGesture() && url.scheme == "https") {
                                try {
                                    view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addCategory(Intent.CATEGORY_BROWSABLE))
                                } catch (_: ActivityNotFoundException) {
                                    // No browser: the link simply does nothing.
                                }
                            }
                            return true // The viewer itself never navigates.
                        }
                    }
                }
            },
            update = { view ->
                if (view.tag != html.hashCode()) {
                    view.tag = html.hashCode()
                    view.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
