package com.xfiles.android

import android.content.Context
import android.os.Build
import com.xfiles.crash.CrashBreadcrumbs
import com.xfiles.crash.CrashReport
import com.xfiles.crash.CrashReportFiles
import com.xfiles.crash.CrashReportFormatter
import com.xfiles.crash.CrashReports
import com.xfiles.crash.DeviceDescription
import com.xfiles.crash.MAX_CRASH_REPORT_BYTES
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

/** Reports live in app-private storage, which no other app can read. */
private fun crashDir(context: Context) = File(context.filesDir, "crash")

internal fun deviceDescription(context: Context): DeviceDescription {
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.titlecase(Locale.ROOT) }
    val model = Build.MODEL.orEmpty()
    return DeviceDescription(
        versionName = info?.versionName ?: "unknown",
        versionCode = info?.longVersionCode ?: 0L,
        system = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        model = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim(),
    )
}

/**
 * Call first in `Application.onCreate`. An uncaught exception on any thread (Kotlin, coroutine, or an
 * engine error that surfaced as an exception) is written to `filesDir/crash/` on the crashing thread,
 * then handed to the previous handler, which ends the process as before. Native signals in Rust kill
 * the process without reaching here.
 */
fun installCrashReporter(context: Context) {
    val app = context.applicationContext
    val device = deviceDescription(app)
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        try { writeCrashReport(app, device, thread.name, error) } catch (_: Throwable) { }
        previous?.uncaughtException(thread, error) ?: exitProcess(10)
    }
}

private fun writeCrashReport(context: Context, device: DeviceDescription, threadName: String, throwable: Throwable) {
    val now = System.currentTimeMillis()
    val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))
    val trace = generateSequence(throwable) { it.cause }.take(8).joinToString("\n") { error ->
        buildString {
            appendLine(error.javaClass.name)
            error.stackTrace.take(128).forEach { frame -> appendLine("\tat $frame") }
        }.trimEnd()
    }
    val text = CrashReportFormatter.format(device, time, threadName, CrashBreadcrumbs.screen, trace)
    val dir = crashDir(context)
    if (!dir.isDirectory && !dir.mkdirs()) return
    val existing = dir.list()?.toList().orEmpty()
    CrashReportFiles.toDeleteBeforeWrite(existing).forEach { File(dir, it).delete() }
    var sequence = 0
    var file = File(dir, CrashReportFiles.nameFor(now))
    while (file.exists() && sequence < 50) file = File(dir, CrashReportFiles.nameFor(now, ++sequence))
    // Written whole, then renamed, so a half-written report is never shown.
    val partial = File(dir, file.name + ".part")
    partial.writeText(text)
    if (!partial.renameTo(file)) partial.delete()
}

@Singleton
internal class AndroidCrashReports @Inject constructor(@ApplicationContext context: Context) : CrashReports {
    private val dir = crashDir(context)

    override fun pending(): CrashReport? {
        val names = runCatching { dir.list()?.toList() }.getOrNull().orEmpty()
        names.asSequence().filter { it.endsWith(".part") }.forEach { File(dir, it).delete() } // Writes cut short.
        return CrashReportFiles.newestFirst(names).asSequence()
            .map { name -> name to File(dir, name) }
            .mapNotNull { (name, file) ->
                // Reports are capped when written; anything bigger is not ours.
                val text = runCatching { if (file.length() > MAX_CRASH_REPORT_BYTES * 2L) null else file.readText() }.getOrNull()
                if (text.isNullOrBlank()) {
                    file.delete()
                    null
                } else {
                    val version = text.lineSequence().firstOrNull { it.startsWith("App: ") }
                        ?.removePrefix("App: ")?.substringBefore(" (").orEmpty()
                    CrashReport(name, crashUploadText(text), version, file.lastModified())
                }
            }
            .firstOrNull()
    }

    override fun dismiss(id: String) {
        val names = runCatching { dir.list()?.toList() }.getOrNull().orEmpty()
        CrashReportFiles.toDeleteOnDismiss(names, id).forEach { runCatching { File(dir, it).delete() } }
    }
}

private val exceptionType = Regex("""^(?:(?:Caused by|Suppressed): )?(?:[\w$]+\.)+[\w$]+(?=:|$)""")
private val stackFrame = Regex("""\s+at [\w.$/<>-]+\((?:[\w.$:-]+|Unknown Source|Native Method)\)""")
private val omittedFrames = Regex("""\s+\.\.\. \d+ more""")

/** Allow only stack frames and exception types through, including for reports from older versions. */
private fun crashUploadText(text: String): String {
    val lines = text.lineSequence().iterator()
    val report = buildString {
        repeat(8) {
            if (lines.hasNext()) {
                val line = lines.next()
                appendLine(if (line.startsWith("Thread: ") && line != "Thread: main") "Thread: background" else line)
            }
        }
        for (line in lines) {
            when {
                stackFrame.matches(line) || omittedFrames.matches(line) -> appendLine(line)
                else -> exceptionType.find(line.trimStart())
                    ?.let { appendLine(it.value) }
            }
        }
    }.trimEnd()
    return CrashReportFormatter.capBytes(report, MAX_CRASH_REPORT_BYTES)
}
