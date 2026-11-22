package com.xfiles.fily


data class QuickCommand(val operation: String, val value: String? = null)

/**
 * Exact screen commands ("next page", "zoom in", "select B3") recognised by plain matching, so they
 * act at once instead of waiting seconds for the model. Only operations the viewer on screen
 * supports are matched; anything else goes to the model.
 */
object QuickCommands {
    private val filler = Regex("""^(i mean |no |hey |ok |okay )*(fily,? )?(please |can you |could you |would you |now |just )*|( please| now| for me)+$""")

    fun parse(request: String, operations: Collection<String>, pageCount: Int? = null): QuickCommand? {
        if (operations.isEmpty()) return null
        val text = request.lowercase().replace(Regex("""[^a-z0-9 ]"""), " ").replace(Regex(" +"), " ").trim().replace(filler, "").trim()
        fun can(op: String) = op in operations
        fun pick(vararg ops: String) = ops.firstOrNull(::can)?.let { QuickCommand(it) }

        Regex("""^(go to |jump to |turn to |show |open )?(the )?(page|slide) (\w+)$""").find(text)?.let { m ->
            if (can("go_to_page")) return QuickCommand("go_to_page", m.groupValues[4])
        }
        Regex("""^(select|tap|click|click on|tap on|go to|show|open) (cell )?([a-z]{1,3}) ?(\w+)$""").find(text)?.let { m ->
            val row = m.groupValues[4]
            if (can("select_cell") && (m.groupValues[2].isNotEmpty() || row.any(Char::isDigit))) {
                // Spaced, so a spoken row ("C three") stays separate from the column letters.
                return QuickCommand("select_cell", m.groupValues[3] + " " + row)
            }
        }
        Regex("""^(skip|jump|seek|go) (ahead )?to (\w+) seconds?$""").find(text)?.let { m ->
            if (can("seek")) return QuickCommand("seek", m.groupValues[3])
        }
        return when {
            text.matches(Regex("""^(go to )?(the )?(last|final) (page|slide)$""")) && can("go_to_page") && pageCount != null ->
                QuickCommand("go_to_page", pageCount.toString())
            text.matches(Regex("""^(go to )?(the )?first (page|slide)$""")) && can("go_to_page") -> QuickCommand("go_to_page", "1")
            text.matches(Regex("""^(go to |show |show me )?(the )?next (page|slide)$|^(turn the )?page$|^page down$""")) -> pick("next_page")
            text.matches(Regex("""^(go to |show |go back to )?(the )?previous (page|slide)$|^(go )?back (a|one) (page|slide)$|^page up$""")) -> pick("previous_page")
            text.matches(Regex("""^(show |show me )?(the )?next (photo|picture|image|pic)$""")) -> pick("next_photo")
            text.matches(Regex("""^(show |show me )?(the )?previous (photo|picture|image|pic)$""")) -> pick("previous_photo")
            text.matches(Regex("""^(go )?(next|forward)( one)?$""")) -> pick("next_page", "next_photo")
            // A bare "go back" means leaving the file (see isClose); "back one" still turns back.
            text.matches(Regex("""^previous( one)?$|^(go )?back one$""")) -> pick("previous_page", "previous_photo")
            text.matches(Regex("""^(next|previous) (sheet|tab)$""")) -> pick(if (text.startsWith("next")) "next_sheet" else "previous_sheet")
            text.matches(Regex("""^zoom in( more| a (little|bit))?$|^(make it )?bigger$|^enlarge( it)?$""")) -> pick("zoom_in")
            text.matches(Regex("""^zoom out( more| a (little|bit))?$|^(make it )?smaller$""")) -> pick("zoom_out")
            text.matches(Regex("""^scroll( down)?( more| a (little|bit))?$|^(go )?down$""")) -> pick("scroll_down")
            text.matches(Regex("""^scroll up( more)?$|^(go )?up$""")) -> pick("scroll_up")
            text.matches(Regex("""^(scroll|go|jump) to (the )?(top|start|beginning)$""")) -> pick("scroll_top")
            text.matches(Regex("""^(scroll|go|jump) to (the )?(bottom|end)$""")) -> pick("scroll_bottom")
            text.matches(Regex("""^rotate( it| this| the (photo|picture|image))?( clockwise)?$""")) -> pick("rotate_photo")
            text.matches(Regex("""^(play|resume|continue)( it| this| the (video|song|audio|track))?$""")) -> pick("play")
            text.matches(Regex("""^(pause|stop)( it| this| the (video|song|audio|track)| playback)?$""")) -> pick("pause")
            else -> scrollIntent(text)?.let { pick(it) }
        }
    }

    // Scrolling isn't in the trained tool catalog, so the model can't do it: natural phrasings are caught here.
    private val scrollVerb = Regex("""\b(scroll|scrolling|move|go|swipe|take me|jump|skip)\b""")
    private val scrollObject = Regex("""^((the|this|a|my) )?(page|file|document|doc|screen|text|content|sheet|list|table|book|chapter|it|bit|little|more|further|lower|higher|again|some|lot|way|all|down|up|of|to|the|a|now|please)( |$)""")

    /**
     * "scroll down the page", "scroll a bit more", "move up", "take me to the end", "scroll all the way up":
     * the scroll operation meant, or null. Only short commands count, so a question that mentions scrolling
     * ("why can't you scroll the table") still goes to the model.
     */
    internal fun scrollIntent(text: String): String? {
        val words = text.split(' ').filter(String::isNotEmpty)
        if (words.isEmpty() || words.size > 9 || !scrollVerb.containsMatchIn(text)) return null
        if (Regex("""\b(why|how|what|can't|cannot|not|don't|dont)\b""").containsMatchIn(text)) return null
        val toEnd = Regex("""\b(bottom|end|last line|all the way down)\b""").containsMatchIn(text)
        val toStart = Regex("""\b(top|start|beginning|all the way up)\b""").containsMatchIn(text)
        val up = Regex("""\b(up|upward|upwards|back up|higher|previous)\b""").containsMatchIn(text)
        val down = Regex("""\b(down|downward|downwards|lower|further|more|next)\b""").containsMatchIn(text)
        // Every word must be part of the command: a verb, a direction, or a filler such as "the page".
        val rest = text.replace(scrollVerb, " ")
            .replace(Regex("""\b(down|downward|downwards|up|upward|upwards|back|bottom|end|top|start|beginning|last line|lower|higher|further|more|next|previous|of)\b"""), " ")
            .replace(Regex(" +"), " ").trim()
        var leftover = rest
        while (leftover.isNotEmpty()) {
            val match = scrollObject.find(leftover) ?: return null
            leftover = leftover.substring(match.range.last + 1).trim()
        }
        return when {
            toEnd -> "scroll_bottom"
            toStart -> "scroll_top"
            up -> "scroll_up"
            down || Regex("""\bscroll\b""").containsMatchIn(text) -> "scroll_down"
            else -> null
        }
    }

    private val ordinals = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth")
    private val ordinalRequest = Regex(
        """^(open|show|show me|play|read) (up )?(the )?(?:(\w+) )?(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|last|top|\d+(?:st|nd|rd|th)|number (\w+))\b(.*)$""",
    )
    private val listWords = Regex("""\b(file|files|one|document|doc|item|screen|list|result|pdf)\b""")

    /**
     * "open the first file on screen", "open the second one", "open number 3", "open the last
     * one": which of the listed files (0-based; -1 for the last), or null. One stray word from the
     * recognizer is allowed ("open the first file UC on screen"). Pages and slides are not files.
     */
    fun listPosition(request: String): Int? {
        val text = request.lowercase().replace(Regex("""[^a-z0-9 ]"""), " ").replace(Regex(" +"), " ").trim().replace(filler, "").trim()
        val match = ordinalRequest.find(text) ?: return null
        val (_, _, _, stray, which, number, rest) = match.destructured
        if (stray.isNotEmpty() && stray != "very" && stray != "my") return null
        if (Regex("""\b(page|slide|photo|picture|sheet|cell)\b""").containsMatchIn(rest)) return null
        val restWords = rest.trim().split(' ').filter(String::isNotEmpty)
        if (restWords.size > 6 || (restWords.isNotEmpty() && !listWords.containsMatchIn(rest))) return null
        return when {
            which == "last" -> -1
            which == "top" -> 0
            number.isNotEmpty() -> spokenNumber(number)?.minus(1)
            which[0].isDigit() -> which.takeWhile(Char::isDigit).toIntOrNull()?.minus(1)
            else -> ordinals.indexOf(which)
        }?.takeIf { it >= -1 }
    }

    private val closeRequest = Regex(
        """^(close|exit|quit|leave|shut)( it| this| that)?( (the |this )?(file|document|doc|pdf|book|slides|sheet|video|photo|reader))?$|^(go )?back( to (the |my )?(library|home|files))?$|^go (to the )?home$""",
    )

    /** "close", "exit this document", "go back to the library": leave the open file. */
    fun isClose(request: String): Boolean =
        closeRequest.matches(request.lowercase().replace(Regex("""[^a-z0-9 ]"""), " ").replace(Regex(" +"), " ").trim().replace(filler, "").trim())

    private val sections = linkedMapOf(
        "all" to "all|all files|everything|library|home",
        "recent" to "recent|recents|recent files|history",
        "pdf" to "pdf|pdfs",
        "docs" to "doc|docs|document|documents|word|word files",
        "slides" to "slide|slides|presentation|presentations|powerpoint|ppt|deck|decks",
        "sheets" to "sheet|sheets|spreadsheet|spreadsheets|excel",
        "books" to "book|books|ebook|ebooks|epub",
        "images" to "image|images|photo|photos|picture|pictures|gallery",
        "media" to "media|video|videos|audio|music|songs",
        "text" to "text|text files|notes",
        "code" to "code|source code|programs",
        "data" to "data|csv|json",
        "archives" to "archive|archives|zip|zips|zip files",
    )
    private val sectionRequest = Regex(
        """^(go to|open|show me|show|switch to|take me to)( the| my)? (.+?)( section| tab| category| filter| files| folder)( of the app| in the app)?$""",
    )

    /** "go to the slides section", "show me the PDF tab": the library filter to show ("slides"), or null. */
    fun librarySection(request: String): String? {
        val text = request.lowercase().replace(Regex("""[^a-z0-9 ]"""), " ").replace(Regex(" +"), " ").trim().replace(filler, "").trim()
        val words = sectionRequest.matchEntire(text)?.groupValues?.get(3) ?: return null
        return sections.entries.firstOrNull { (_, names) -> words in names.split('|') }?.key
    }

    private val choice = Regex("""^(the |that )?(first|second|third|fourth|fifth|last|top|\d+(?:st|nd|rd|th)|number (\w+))( one| file| document)?$""")

    /** An answer to "Which one?": "the second one", "the last", "number 2". */
    fun choicePosition(request: String): Int? {
        val text = request.lowercase().replace(Regex("""[^a-z0-9 ]"""), " ").replace(Regex(" +"), " ").trim().replace(filler, "").trim()
        val m = choice.matchEntire(text) ?: return null
        val which = m.groupValues[2]
        return when {
            which == "last" -> -1
            which == "top" -> 0
            m.groupValues[3].isNotEmpty() -> spokenNumber(m.groupValues[3])?.minus(1)
            which[0].isDigit() -> which.takeWhile(Char::isDigit).toIntOrNull()?.minus(1)
            else -> ordinals.indexOf(which)
        }?.takeIf { it >= -1 }
    }

    /** What Fily says after a quick command, from the controlReader result. */
    fun reply(command: QuickCommand, page: Int?, pageCount: Int?, sheet: String?, cell: String?, failure: String?): String {
        if (failure != null) return when (failure) {
            "at_last_page" -> "That's the last page."
            "at_first_page" -> "That's the first page."
            "at_last_photo" -> "That's the last photo."
            "at_first_photo" -> "That's the first photo."
            "at_last_sheet" -> "That's the last sheet."
            "at_first_sheet" -> "That's the first sheet."
            "out_of_range" -> if (pageCount != null && command.operation == "go_to_page") "There are only $pageCount pages." else "That isn't in this file."
            "at_limit" -> if (command.operation == "zoom_in") "That's as close as it zooms." else "That's as far as it zooms out."
            "still_loading" -> "That part is still loading. Try again in a moment."
            "missing_page", "missing_cell", "missing_time" -> "I didn't catch the number. Try again?"
            "not_ready" -> "It isn't ready to play yet."
            else -> "I can't do that here."
        }
        val where = when {
            page != null && pageCount != null -> "Page $page of $pageCount."
            else -> null
        }
        return when (command.operation) {
            "next_page", "previous_page", "go_to_page", "scroll_top", "scroll_bottom" -> where ?: "Done."
            "next_photo", "previous_photo" -> if (page != null && pageCount != null) "Photo $page of $pageCount." else "Done."
            "zoom_in" -> "Zoomed in."
            "zoom_out" -> "Zoomed out."
            "scroll_down" -> "Scrolled down."
            "scroll_up" -> "Scrolled up."
            "rotate_photo" -> "Rotated."
            "play" -> "Playing."
            "pause" -> "Paused."
            "seek" -> "Jumped to ${command.value} seconds."
            "select_cell" -> "Selected ${cell ?: command.value?.uppercase()}."
            "next_sheet", "previous_sheet" -> sheet?.let { "Now on $it." } ?: "Done."
            else -> "Done."
        }
    }
}
