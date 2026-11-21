package com.xfiles.crash

import kotlin.concurrent.Volatile

/*
 * Crash reports without the network: a crash is written to app-private
 * storage, and on the next launch the user may read it and share it through
 * an app of their choice. Everything here is pure, so it is unit-tested; the
 * platform writes the files and opens the share sheet.
 *
 * A report must never carry the user's documents: no file names, URIs, paths
 * or document text. [CrashScrubber] removes them from exception messages.
 */

/**
 * The developer address that crash reports and feedback are addressed to.
 */
const val CRASH_REPORT_EMAIL = "kishoreabhi333@gmail.com"

/** At most this many reports are kept; older ones are deleted first. */
const val MAX_CRASH_REPORTS = 5

/** Each report stays under this many UTF-8 bytes. */
const val MAX_CRASH_REPORT_BYTES = 64 * 1024

/** A report waiting to be sent or dismissed. [id] is its file name. */
data class CrashReport(val id: String, val text: String, val versionName: String)

/** What a report says about the app and device: no user data. */
data class DeviceDescription(
    val versionName: String,
    val versionCode: Long,
    /** E.g. "Android 15 (API 35)". */
    val system: String,
    /** E.g. "Google Pixel 8". */
    val model: String,
)

/**
 * Where the user was when the app closed: a screen and a document format,
 * never a file. Set from the UI; read when the app crashes.
 */
object CrashBreadcrumbs {
    @Volatile
    var screen: String? = null
}

/** Removes anything that could identify the user's files or show their content. */
object CrashScrubber {
    // File names may hold spaces, so a URI or path runs on to where Java's messages
    // usually go on (": open failed", " (No such file)") or to the end of the line.
    private const val REST = """[^\n]*?(?=: |\s\(|\n|$)"""
    private val uri = Regex("""\b[a-zA-Z][a-zA-Z0-9+.\-]*://""" + REST)
    private val unixPath = Regex("""(?<![\w.$<>])(?:~|\.{1,2})?/[^\s/]""" + REST)
    private val windowsPath = Regex("""\b[A-Za-z]:\\""" + REST)
    private val quoted = Regex(""""[^"\n]*"|'[^'\n]*'|“[^”\n]*”|‘[^’\n]*’""")
    private val email = Regex("""[\w.+\-]+@[\w\-]+\.[\w.\-]+""")
    private val fileName = Regex(
        """[^\s/\\:'"()\[\]{},;=]+\.(?i:pdf|docx?|docm|dotx?|dotm|xlsx?|xlsm|xltx?|xltm|pptx?|pptm|ppsx?|ppsm|potx?|potm|odt|ods|odp|""" +
            """epub|zip|txt|md|markdown|csv|tsv|json|xml|xhtml|html?|ya?ml|toml|sql|sh|log|rtf|ini|conf|cfg|properties|""" +
            """jpe?g|png|webp|gif|bmp|heic|heif|avif|tiff?|svg|mp3|mp4|m4a|aac|wav|ogg|opus|flac|mkv|webm|mov|3gp)\b""",
    )

    fun scrub(text: String): String = text
        .replace(uri, "<uri>")
        .replace(windowsPath, "<path>")
        .replace(email, "<email>")
        .replace(quoted, "<text>")
        .replace(unixPath, "<path>")
        .replace(fileName, "<file>")
}

/** Builds report text from a crash; the platform supplies the device and the clock. */
object CrashReportFormatter {
    /** Each line of the trace (a long exception message, mostly) is cut to this many characters. */
    private const val MAX_MESSAGE_CHARS = 500

    fun format(
        device: DeviceDescription,
        timeUtc: String,
        threadName: String,
        screen: String?,
        throwable: Throwable,
        maxBytes: Int = MAX_CRASH_REPORT_BYTES,
    ): String = format(device, timeUtc, threadName, screen, throwable.stackTraceToString(), maxBytes)

    fun format(
        device: DeviceDescription,
        timeUtc: String,
        threadName: String,
        screen: String?,
        stackTrace: String,
        maxBytes: Int = MAX_CRASH_REPORT_BYTES,
    ): String {
        val header = buildString {
            appendLine("Xfiles crash report")
            appendLine("App: ${device.versionName} (${device.versionCode})")
            appendLine("System: ${device.system}")
            appendLine("Device: ${device.model}")
            appendLine("Time: $timeUtc")
            appendLine("Thread: ${CrashScrubber.scrub(threadName).take(80)}")
            appendLine("Last screen: ${screen?.let { CrashScrubber.scrub(it).take(80) } ?: "unknown"}")
            appendLine()
        }
        val trace = stackTrace.lineSequence().joinToString("\n") { line ->
            CrashScrubber.scrub(line).let { if (it.length > MAX_MESSAGE_CHARS) it.take(MAX_MESSAGE_CHARS) + "…" else it }
        }.trimEnd()
        return capBytes(header + trace, maxBytes)
    }

    private const val TRUNCATED = "\n… (report shortened)"

    /** Cuts [text] at a line end so it fits in [maxBytes] of UTF-8, with a note that it was cut. */
    fun capBytes(text: String, maxBytes: Int): String {
        if (text.encodeToByteArray().size <= maxBytes) return text
        val budget = maxBytes - TRUNCATED.encodeToByteArray().size
        val out = StringBuilder()
        var used = 0
        for (line in text.lineSequence()) {
            val bytes = line.encodeToByteArray().size + 1
            if (used + bytes > budget) {
                // A single line too long on its own: keep what fits of it.
                if (out.isEmpty()) out.append(cutToBytes(line, budget))
                break
            }
            out.append(line).append('\n')
            used += bytes
        }
        return out.toString().trimEnd('\n') + TRUNCATED
    }

    private fun cutToBytes(line: String, budget: Int): String {
        var used = 0
        var end = 0
        while (end < line.length) {
            val c = line[end]
            val bytes = when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && end + 1 < line.length && line[end + 1].isLowSurrogate() -> 4
                else -> 3
            }
            if (used + bytes > budget) break
            used += bytes
            end += if (bytes == 4) 2 else 1
        }
        return line.substring(0, end)
    }

    /** Subject line of a crash report message. */
    fun subject(versionName: String): String = "Xfiles crash report $versionName"
}

/** The blank feedback message: the app version and device, nothing else. */
object FeedbackTemplate {
    fun subject(versionName: String): String = "Xfiles feedback $versionName"

    fun body(device: DeviceDescription): String = buildString {
        appendLine()
        appendLine()
        appendLine("--")
        appendLine("Xfiles ${device.versionName} (${device.versionCode})")
        appendLine(device.system)
        append(device.model)
    }
}

/** Report files are named by the time of the crash, so they sort by age. */
object CrashReportFiles {
    private val name = Regex("""crash-(\d{1,19})(?:-(\d{1,4}))?\.txt""")

    fun nameFor(epochMillis: Long, sequence: Int = 0): String =
        "crash-$epochMillis" + (if (sequence > 0) "-$sequence" else "") + ".txt"

    fun isReport(fileName: String): Boolean = name.matches(fileName)

    /** Time, then sequence: the order reports were written in. */
    private fun age(fileName: String): Pair<Long, Int> {
        val match = name.matchEntire(fileName) ?: return 0L to 0
        return (match.groupValues[1].toLongOrNull() ?: 0L) to (match.groupValues[2].toIntOrNull() ?: 0)
    }

    private val newest = compareByDescending<String> { age(it).first }.thenByDescending { age(it).second }

    /** Reports newest first; other files are ignored. */
    fun newestFirst(fileNames: Collection<String>): List<String> = fileNames.filter(::isReport).sortedWith(newest)

    /**
     * The reports to delete before writing a new one, so that at most [max]
     * remain afterwards: the oldest ones.
     */
    fun toDeleteBeforeWrite(existing: Collection<String>, max: Int = MAX_CRASH_REPORTS): List<String> =
        newestFirst(existing).drop((max - 1).coerceAtLeast(0))

    /**
     * Dismissing or sending a report also clears every older one: they are
     * stale, and the user should not be asked again for each.
     */
    fun toDeleteOnDismiss(existing: Collection<String>, id: String): List<String> {
        if (!isReport(id)) return emptyList()
        val list = newestFirst(existing)
        val from = list.indexOf(id)
        return if (from < 0) emptyList() else list.drop(from)
    }
}
