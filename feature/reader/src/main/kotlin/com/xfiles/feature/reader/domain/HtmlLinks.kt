package com.xfiles.feature.reader.domain

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** What a link in a local web page should do when tapped. */
sealed interface HtmlLinkAction {
    /** Jump within the page, e.g. `#section`: the web view scrolls in place. */
    data object ScrollInPlace : HtmlLinkAction

    /** A web or mail link: opened in another app after the user confirms. */
    data class External(val url: String) : HtmlLinkAction

    /** A file next to the page (a relative link): opened in the reader. */
    data class Sibling(val uri: Uri) : HtmlLinkAction

    data object Ignore : HtmlLinkAction
}

/**
 * Classifies a tapped link. The page loads with an `about:blank` base, so a relative link arrives as
 * `about:<href>` and keeps its own text; [base] is the page's file, used to place that relative link.
 */
fun classifyHtmlLink(context: Context, base: Uri?, url: Uri, pageUrl: String): HtmlLinkAction {
    val scheme = url.scheme?.lowercase()
    val sameDocument = url.fragment != null && url.toString().substringBefore('#') == pageUrl.substringBefore('#')
    return when {
        sameDocument -> HtmlLinkAction.ScrollInPlace
        scheme == "http" || scheme == "https" || scheme == "mailto" -> HtmlLinkAction.External(url.toString())
        scheme == "about" -> {
            // "about:blank" is the page itself; anything else is a relative href written after the scheme.
            val href = url.encodedSchemeSpecificPart.orEmpty()
            if (base == null || href.isEmpty() || href == "blank") HtmlLinkAction.Ignore
            else siblingUri(context, base, href)?.let { HtmlLinkAction.Sibling(it) } ?: HtmlLinkAction.Ignore
        }
        scheme == "data" || scheme == "javascript" -> HtmlLinkAction.Ignore
        else -> HtmlLinkAction.Ignore
    }
}

/**
 * The file a relative link points to, next to the page at [base]. Document-provider files resolve through
 * their document id; plain files resolve against their folder. Null when the link can't be placed.
 */
internal fun siblingUri(context: Context, base: Uri, encodedHref: String): Uri? {
    val href = Uri.decode(encodedHref.substringBefore('#').substringBefore('?'))
    if (href.isEmpty() || href.startsWith("/")) return null
    return when (base.scheme) {
        "file" -> {
            val parent = base.path?.let(::File)?.parentFile ?: return null
            File(parent, href).takeIf { it.exists() }?.let(Uri::fromFile)
        }
        "content" -> {
            if (!DocumentsContract.isDocumentUri(context, base)) return null
            val authority = base.authority ?: return null
            val docId = DocumentsContract.getDocumentId(base)
            val sibling = siblingDocumentId(docId, href) ?: return null
            DocumentsContract.buildDocumentUri(authority, sibling)
        }
        else -> null
    }
}

/**
 * Document id of [href] beside [docId]: `primary:docs/a.html` plus `img/../b.html` gives `primary:docs/b.html`.
 * Null for an absolute path, a link that leaves the volume, or a document id without a volume.
 */
internal fun siblingDocumentId(docId: String, href: String): String? {
    val target = href.substringBefore('#').substringBefore('?')
    if (target.isEmpty() || target.contains("://")) return null
    val colon = docId.indexOf(':')
    if (colon < 0) return null
    val volume = docId.substring(0, colon)
    val stack = docId.substring(colon + 1).split('/').dropLast(1).filter { it.isNotEmpty() }.toMutableList()
    for (part in target.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (stack.isEmpty()) return null else stack.removeAt(stack.lastIndex)
            else -> stack.add(part)
        }
    }
    return if (stack.isEmpty()) null else "$volume:${stack.joinToString("/")}"
}
