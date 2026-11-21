package com.xfiles.android.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.xfiles.android.R
import com.xfiles.core.documents.FileEntry
import com.xfiles.documents.DocumentKind
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_WIDGET_FILES = 4
private val CompactSize = DpSize(250.dp, 56.dp)
private val FullSize = DpSize(250.dp, 200.dp)

/**
 * "Xfiles quick": three buttons (Scan, Fily, Open) and up to four recent files. Below the compact
 * height only the button row shows. Colors are Material 3 dynamic colors on Android 12 and later.
 */
class XfilesWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(CompactSize, FullSize))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Recents come from the stored list, read off the main thread; a failed read shows the empty hint.
        val recents = withContext(Dispatchers.IO) {
            runCatching {
                EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
                    .documents()
                    .recentsSnapshot()
            }.getOrDefault(emptyList()).take(MAX_WIDGET_FILES)
        }
        val colors = colorsFor(context)
        provideContent {
            GlanceTheme(colors = colors) {
                WidgetBody(recents)
            }
        }
    }
}

class XfilesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = XfilesWidget()
}

private fun colorsFor(context: Context) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ColorProviders(light = dynamicLightColorScheme(context), dark = dynamicDarkColorScheme(context))
    } else {
        ColorProviders(light = lightColorScheme(), dark = darkColorScheme())
    }

@Composable
private fun WidgetBody(recents: List<FileEntry>) {
    val context = LocalContext.current
    val showFiles = LocalSize.current.height >= FullSize.height
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(24.dp)
            .padding(12.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(
                text = context.getString(R.string.app_name),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.defaultWeight())
            ActionButton(R.drawable.ic_widget_scan, context.getString(R.string.widget_scan), WidgetActions.button(context, WidgetActions.SCAN))
            Spacer(GlanceModifier.width(6.dp))
            ActionButton(R.drawable.ic_widget_fily, context.getString(R.string.widget_fily), WidgetActions.button(context, WidgetActions.FILY))
            Spacer(GlanceModifier.width(6.dp))
            ActionButton(R.drawable.ic_widget_open, context.getString(R.string.widget_open), WidgetActions.button(context, WidgetActions.OPEN))
        }
        if (showFiles) {
            Spacer(GlanceModifier.height(10.dp))
            if (recents.isEmpty()) {
                Text(
                    text = context.getString(R.string.widget_empty),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                    modifier = GlanceModifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            } else {
                recents.forEachIndexed { index, file ->
                    if (index > 0) Spacer(GlanceModifier.height(6.dp))
                    FileRow(context, file)
                }
            }
        }
    }
}

@Composable
private fun ActionButton(icon: Int, label: String, intent: Intent) {
    Box(
        modifier = GlanceModifier
            .size(40.dp)
            .cornerRadius(14.dp)
            .background(GlanceTheme.colors.primaryContainer)
            .clickable(actionStartActivity(intent)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = label,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
            modifier = GlanceModifier.size(20.dp),
        )
    }
}

@Composable
private fun FileRow(context: Context, file: FileEntry) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(44.dp)
            .cornerRadius(14.dp)
            .background(GlanceTheme.colors.surfaceVariant)
            .clickable(actionStartActivity(WidgetActions.file(context, file.uri)))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .cornerRadius(8.dp)
                .background(GlanceTheme.colors.secondaryContainer)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(
                text = kindLabel(file.kind),
                style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 10.sp, fontWeight = FontWeight.Bold),
            )
        }
        Spacer(GlanceModifier.width(10.dp))
        Text(
            text = file.name,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
            maxLines = 1,
        )
    }
}

private fun kindLabel(kind: DocumentKind): String = when (kind) {
    DocumentKind.Text -> "TXT"
    DocumentKind.Image -> "IMG"
    DocumentKind.Pdf -> "PDF"
    DocumentKind.Document -> "DOC"
    DocumentKind.Audio -> "AUDIO"
    DocumentKind.Video -> "VIDEO"
    DocumentKind.Unknown -> "FILE"
}
