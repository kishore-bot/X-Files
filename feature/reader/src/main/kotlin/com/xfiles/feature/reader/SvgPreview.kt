package com.xfiles.feature.reader

/** Whether [name] is an SVG picture (drawn as a picture, with its markup one tap away). */
internal fun isSvgName(name: String): Boolean = name.endsWith(".svg", ignoreCase = true)

/**
 * SVG markup as a page that draws the picture centred and scaled to fit. It goes through [HtmlPreview], so it
 * keeps the same sandbox: no scripts, no network, and no file access. The XML declaration is dropped because
 * it is only valid at the top of an XML document, not inside an HTML page.
 */
internal fun svgAsHtml(svg: String): String {
    val markup = svg.replace(XML_DECLARATION, "")
    return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<style>html,body{margin:0;height:100%}" +
        "body{display:flex;align-items:center;justify-content:center;padding:12px;box-sizing:border-box}" +
        "svg{max-width:100%;max-height:100%;width:auto;height:auto}</style></head><body>$markup</body></html>"
}

private val XML_DECLARATION = Regex("<\\?xml[^>]*\\?>")
