@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.xfiles.feature.library

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.EzControlSheet
import com.xfiles.design.EzIcons
import com.xfiles.design.EzListItem
import com.xfiles.design.EzSectionHeader
import com.xfiles.design.EzTopBar
import com.xfiles.design.glassCard

private const val SOURCE = "https://github.com/kishore-bot/X-Files"
private enum class AboutPanel { Feedback, Licenses, Privacy, Formats }

@Composable
fun AboutScreen(onBack: () -> Unit) {
    var panel by rememberSaveable { mutableStateOf<AboutPanel?>(null) }
    var upcoming by rememberSaveable { mutableStateOf(false) }
    val close = { if (upcoming) upcoming = false else onBack() }
    BackHandler(onBack = close)
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val (version, build) = remember(context) { packageVersion(context) }
    fun open(url: String) {
        try {
            uriHandler.openUri(url)
        } catch (_: IllegalArgumentException) {
            Toast.makeText(context, "No app can open this link.", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        EzTopBar(if (upcoming) "Upcoming" else "About Xfiles", close, backLabel = if (upcoming) "Back to About" else "Back to settings")
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            if (upcoming) {
                UpcomingScreen()
            } else {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item("identity") {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(painterResource(com.xfiles.design.R.drawable.ic_xfiles_mark), null, Modifier.size(108.dp))
                            Text("Xfiles", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, color = EzColors.Text)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                if (version.isNotBlank()) {
                                    Text("Version $version", Modifier.align(Alignment.CenterVertically), color = EzColors.TextSecondary)
                                    Text("Build $build", Modifier.align(Alignment.CenterVertically), color = EzColors.TextSecondary)
                                }
                                TextButton(onClick = { open("$SOURCE/releases") }) { Text("Release notes") }
                            }
                        }
                    }
                    item("links") {
                        Column(Modifier.fillMaxWidth().glassCard()) {
                            SettingsLink(EzIcons.Feedback, "Feedback", "Report a bug or suggest an idea") { panel = AboutPanel.Feedback }
                            HorizontalDivider(color = EzColors.Hairline)
                            SettingsLink(EzIcons.Code, "Source code", "Open the Xfiles repository") { open(SOURCE) }
                            HorizontalDivider(color = EzColors.Hairline)
                            SettingsLink(EzIcons.Book, "Licenses") { panel = AboutPanel.Licenses }
                            HorizontalDivider(color = EzColors.Hairline)
                            SettingsLink(EzIcons.Lock, "Privacy") { panel = AboutPanel.Privacy }
                        }
                    }
                    item("more") {
                        Column(Modifier.fillMaxWidth().glassCard()) {
                            SettingsLink(EzIcons.Document, "Supported files") { panel = AboutPanel.Formats }
                            HorizontalDivider(color = EzColors.Hairline)
                            SettingsLink(EzIcons.Info, "Upcoming") { upcoming = true }
                        }
                    }
                }
            }
        }
    }
    val dismiss = { panel = null }
    when (panel) {
        AboutPanel.Feedback -> FeedbackSheet(onDismiss = dismiss)
        AboutPanel.Licenses -> LicencesSheet(onDismiss = dismiss)
        AboutPanel.Formats -> SupportedFilesSheet(onDismiss = dismiss)
        AboutPanel.Privacy -> EzControlSheet(dismiss) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EzSectionHeader("Privacy")
                PrivacyPromises.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = dismiss) { Text("Close") }
            }
        }
        null -> Unit
    }
}

internal fun packageVersion(context: Context): Pair<String, Long> = try {
    val info = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    (info.versionName ?: "") to info.longVersionCode
} catch (_: PackageManager.NameNotFoundException) {
    "" to 0L
}

@Composable
internal fun SettingsLink(icon: ImageVector, title: String, supporting: String? = null, onClick: () -> Unit) {
    EzListItem(title = title, supporting = supporting, icon = icon, onClick = onClick)
}
