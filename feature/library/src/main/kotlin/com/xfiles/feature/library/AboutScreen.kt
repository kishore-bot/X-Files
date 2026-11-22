package com.xfiles.feature.library

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.crash.CloudCrashReporting
import com.xfiles.crash.NoCloudCrashReporting
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzOptionMenu
import com.xfiles.design.EzTopBar
import com.xfiles.design.EzShapes
import com.xfiles.design.EzSwitch
import com.xfiles.design.EzSectionHeader
import com.xfiles.design.EzListItem
import androidx.compose.runtime.LaunchedEffect
import com.xfiles.design.glassCard
import com.xfiles.design.glossyPrimary
import com.xfiles.fily.AssistantModel
import com.xfiles.settings.ThemePreference
import kotlinx.coroutines.launch

/**
 * The About screen: the app, its theme and touch feedback, Fily, the privacy promises, the supported
 * files, and the feedback and licence pages. [onClearSearchIndex] deletes the search index.
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    theme: ThemePreference,
    haptics: Boolean,
    onTheme: (ThemePreference) -> Unit,
    onHaptics: (Boolean) -> Unit,
    cloudCrash: CloudCrashReporting = NoCloudCrashReporting,
    fily: FilyAbout? = null,
    onClearSearchIndex: (suspend () -> Unit)? = null,
) {
    var showingFormats by rememberSaveable { mutableStateOf(false) }
    var showingLicences by rememberSaveable { mutableStateOf(false) }
    var showingFeedback by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val (versionName, versionCode) = remember(context) { packageVersion(context) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        EzTopBar(
            title = "About Xfiles",
            onBack = onBack,
            backLabel = "Back to library",
            gutter = 0.dp,
        )
        PrimaryTabRow(
            selectedTabIndex = tab,
            containerColor = Color.Transparent,
            modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
        ) {
            listOf("About", "Upcoming").forEachIndexed { index, label ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(label, style = MaterialTheme.typography.titleSmall) },
                )
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            if (tab == 1) {
                UpcomingScreen()
            } else {
                LazyColumn(
                    Modifier.widthIn(max = 620.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 16.dp, horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item("hero") {
                        Column(
                            Modifier.fillMaxWidth().glassCard(EzShapes.Large, 0.55f).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.size(76.dp).glossyPrimary(EzShapes.Medium), contentAlignment = Alignment.Center) {
                                Icon(EzIcons.Document, null, Modifier.size(40.dp), tint = EzColors.OnPrimary)
                            }
                            Text("Xfiles", style = MaterialTheme.typography.headlineMedium, color = EzColors.Text, modifier = Modifier.semantics { heading() })
                            if (versionName.isNotEmpty()) {
                                Text("Version $versionName ($versionCode)", style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary)
                            }
                        }
                    }
                    item("appearance") { AppearanceCard(theme, onTheme, haptics, onHaptics) }
                    fily?.let { item("fily") { FilyCard(it) } }
                    item("formats") {
                        NavigationCard(EzIcons.Document, "Supported files", "${SupportedFiles.size} kinds of file, from PDF to e-books") {
                            showingFormats = true
                        }
                    }
                    item("privacy") { PrivacyCard(onClearSearchIndex) }
                    item("crash") { CloudCrashReportingCard(cloudCrash) }
                    item("more-heading") { SectionHeading("More") }
                    item("feedback") {
                        NavigationCard(EzIcons.Feedback, "Send feedback", "Report a bug or suggest an idea") { showingFeedback = true }
                    }
                    item("licences") {
                        NavigationCard(EzIcons.Code, "Open-source licences", "The libraries and models Xfiles is built with") {
                            showingLicences = true
                        }
                    }
                }
            }
        }
    }
    if (showingFormats) SupportedFilesSheet(onDismiss = { showingFormats = false })
    if (showingLicences) LicencesSheet(onDismiss = { showingLicences = false })
    if (showingFeedback) FeedbackSheet(onDismiss = { showingFeedback = false })
}

/** The app's version name (with the dev suffix, where there is one) and version code, from the package. */
private fun packageVersion(context: Context): Pair<String, Long> = try {
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
private fun AppearanceCard(
    theme: ThemePreference,
    onTheme: (ThemePreference) -> Unit,
    haptics: Boolean,
    onHaptics: (Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().glassCard().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium, color = EzColors.Text, modifier = Modifier.semantics { heading() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemePreference.entries.forEach { option ->
                FilterChip(selected = option == theme, onClick = { onTheme(option) }, label = { Text(option.label) })
            }
        }
        SwitchRow(
            title = "Touch feedback",
            body = "A light tap when you choose, switch or finish something",
            checked = haptics,
            onCheckedChange = onHaptics,
        )
    }
}

@Composable
private fun FilyCard(fily: FilyAbout) {
    Column(
        Modifier.fillMaxWidth().glassCard().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FilyAboutMascot(Modifier.align(Alignment.CenterHorizontally))
        Text("Fily", style = MaterialTheme.typography.titleMedium, color = EzColors.Text, modifier = Modifier.semantics { heading() })
        SwitchRow(
            title = "Fily and assistants",
            body = "Lets Fily, the voice assistant on this phone, and assistants built into Android find your files and open, share or print them when you ask. " +
                "Off turns Fily off and no assistant can see your files.",
            checked = fily.enabled,
            onCheckedChange = fily.onEnabled,
        )
        if (fily.enabled) {
            SwitchRow(
                title = "Let them read inside files",
                body = "Needed to answer questions about a document, like the total on a bill. Off: only file names, sizes and dates. " +
                    "Nothing leaves your phone.",
                checked = fily.contentAccess,
                onCheckedChange = fily.onContentAccess,
            )
            FilyModelRow(
                install = fily.model,
                bytes = AssistantModel.Fily.bytes,
                onDownload = fily.onDownload,
                onImport = fily.onImport,
                onCancel = fily.onCancelDownload,
                onRemove = fily.onRemoveModel,
            )
            fily.languages?.let { languages ->
                LaunchedEffect(Unit) { languages.onRefresh() }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Speech language", Modifier.weight(1f), color = EzColors.Text)
                    EzOptionMenu(
                        options = languages.support.all,
                        selected = languages.support.all.firstOrNull { it.tag == languages.selected },
                        optionLabel = { language ->
                            languages.label(language.tag) + if (language.installed) "" else " (download)"
                        },
                        onSelect = { languages.onSelect(it.tag) },
                        placeholder = "Phone language",
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivacyCard(onClearSearchIndex: (suspend () -> Unit)?) {
    val scope = rememberCoroutineScope()
    var cleared by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().glassCard().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(EzIcons.Lock)
            Text("Privacy", style = MaterialTheme.typography.titleMedium, color = EzColors.Text, modifier = Modifier.semantics { heading() })
        }
        PrivacyPromises.forEach { CheckLine(it) }
        if (onClearSearchIndex != null) {
            CheckLine("Search reads your files only on this device. Its index stays in Xfiles' private storage.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (cleared) "Search index cleared" else "Search index",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EzColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (!cleared) {
                    TextButton(onClick = { scope.launch { onClearSearchIndex(); cleared = true } }) { Text("Clear") }
                }
            }
        }
    }
}

/** A line with a red check marker, for the privacy promises. */
@Composable
private fun CheckLine(text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.padding(top = 2.dp).size(22.dp).clip(CircleShape).glossyPrimary(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CheckIcon, null, tint = EzColors.OnPrimary, modifier = Modifier.size(15.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
            color = EzColors.Text,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = EzColors.Text)
            Text(body, style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary)
        }
        EzSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionHeading(title: String) {
    EzSectionHeader(title)
}

/** A glass card that opens something: icon badge, title and line, chevron. */
@Composable
private fun NavigationCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, onClick: () -> Unit) {
    // The shared list row inside a glass card: haptic tick, press scale and a 64 dp+ target.
    Box(Modifier.fillMaxWidth().glassCard()) {
        EzListItem(
            title = title,
            supporting = body,
            icon = icon,
            trailing = { Icon(EzIcons.ChevronRight, null, tint = EzColors.TextSecondary, modifier = Modifier.size(22.dp)) },
            onClick = onClick,
        )
    }
}
