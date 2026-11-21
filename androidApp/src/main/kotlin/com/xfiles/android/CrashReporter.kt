package com.xfiles.android

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcelable
import com.xfiles.crash.CRASH_REPORT_EMAIL
import com.xfiles.crash.CrashBreadcrumbs
import com.xfiles.crash.CrashReport
import com.xfiles.crash.CrashReportFiles
import com.xfiles.crash.CrashReportFormatter
import com.xfiles.crash.CrashReports
import com.xfiles.crash.CrashScrubber
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

private fun deviceDescription(context: Context): DeviceDescription {
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
fun installCrashReporter(context: Context): AndroidCrashRecorder =
    AndroidCrashRecorder(context.applicationContext, Thread.getDefaultUncaughtExceptionHandler()).also {
        Thread.setDefaultUncaughtExceptionHandler(it)
    }

/** Keeps the local recorder outermost, including when the optional SDK installs its own handler. */
class AndroidCrashRecorder internal constructor(
    private val app: Context,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    private val device = deviceDescription(app)
    private val forwarding = ThreadLocal<Boolean>()
    private var cloud: Thread.UncaughtExceptionHandler? = null
    private var cloudAllowed: () -> Boolean = { false }

    /** Called by the optional SDK after it starts: its handler becomes the forwarding target when [allowed]. */
    fun captureCloudHandler(allowed: () -> Boolean) {
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        if (installed !== this && installed !== previous) cloud = installed
        cloudAllowed = allowed
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // The SDK delegates to this recorder after processing; do not write twice or loop.
        if (forwarding.get() == true) {
            previous?.uncaughtException(thread, throwable) ?: exitProcess(10)
            return
        }
        forwarding.set(true)
        try {
            try { writeCrashReport(app, device, thread.name, throwable) } catch (_: Throwable) { }
            val target = cloud.takeIf { cloudAllowed() } ?: previous
            val forwarded = if (target != null && target === cloud) {
                try { scrubCrashThrowable(throwable) } catch (_: Throwable) { RuntimeException("Crash details unavailable") }
            } else throwable
            if (target != null) target.uncaughtException(thread, forwarded) else exitProcess(10)
        } finally {
            forwarding.remove()
        }
    }
}

/** Copies messages and causes with strict bounds; the SDK never gets the original exception graph. */
internal fun scrubCrashThrowable(error: Throwable): Throwable {
    val seen = mutableListOf<Throwable>()
    fun copy(current: Throwable, depth: Int): Throwable {
        if (depth >= 8 || seen.size >= 16 || seen.any { it === current }) return RuntimeException("Further crash details omitted")
        seen += current
        val result = RuntimeException(CrashScrubber.scrub("${current.javaClass.name}: ${current.message.orEmpty()}").take(500))
        result.stackTrace = current.stackTrace.take(128).toTypedArray()
        current.cause?.let { result.initCause(copy(it, depth + 1)) }
        current.suppressed.take(3).forEach { result.addSuppressed(copy(it, depth + 1)) }
        return result
    }
    return copy(error, 0)
}

private fun writeCrashReport(context: Context, device: DeviceDescription, threadName: String, throwable: Throwable) {
    val now = System.currentTimeMillis()
    val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))
    val text = CrashReportFormatter.format(device, time, threadName, CrashBreadcrumbs.screen, throwable)
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
    private val app = context.applicationContext
    private val dir = crashDir(app)

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
                    CrashReport(id = name, text = text, versionName = version)
                }
            }
            .firstOrNull()
    }

    override fun dismiss(id: String) {
        val names = runCatching { dir.list()?.toList() }.getOrNull().orEmpty()
        CrashReportFiles.toDeleteOnDismiss(names, id).forEach { runCatching { File(dir, it).delete() } }
    }

    override fun share(report: CrashReport): Boolean {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(CRASH_REPORT_EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, CrashReportFormatter.subject(report.versionName))
            .putExtra(Intent.EXTRA_TEXT, report.text)
        // Xfiles accepts shared text itself, so it is left out of the chooser.
        val ownTargets = runCatching {
            app.packageManager.queryIntentActivities(Intent(send).setPackage(app.packageName), PackageManager.MATCH_DEFAULT_ONLY)
                .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        }.getOrDefault(emptyList())
        val chooser = Intent.createChooser(send, "Send crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (ownTargets.isNotEmpty()) chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, ownTargets.toTypedArray<Parcelable>())
        return try {
            app.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
