@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.xfiles.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xfiles.crash.CloudCrashReporting
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzOptionMenu
import com.xfiles.design.EzSectionHeader
import com.xfiles.design.EzSwitch
import com.xfiles.design.EzTopBar
import com.xfiles.design.glassCard
import com.xfiles.fily.AssistantModel
import com.xfiles.settings.ThemePreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class SettingsSection(val label: String) {
    Appearance("Appearance"), Fily("Fily"), Search("Library search"), Diagnostics("Diagnostics"),
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAbout: () -> Unit,
    theme: ThemePreference,
    haptics: Boolean,
    onTheme: (ThemePreference) -> Unit,
    onHaptics: (Boolean) -> Unit,
    cloudCrash: CloudCrashReporting,
    fily: FilyAbout,
    onClearSearchIndex: suspend () -> Unit,
) {
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    val back = { if (section == null) onBack() else section = null }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize()) {
        EzTopBar(section?.label ?: "Settings", back, backLabel = if (section == null) "Back to library" else "Back to settings")
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (section) {
                    null -> item("settings") {
                        Column(Modifier.fillMaxWidth().glassCard()) {
                            SettingsLink(EzIcons.Theme, "Appearance", "Theme and touch feedback") { section = SettingsSection.Appearance }
                            HorizontalDivider(color = EzColors.Hairline)
                            SettingsLink(EzIcons.Mic, "Fily", "Assistant access, voice, and on-device model") { section = SettingsSection.Fily }
                            HorizontalDivider(color = EzColors.Hairline)
                            SettingsLink(EzIcons.Search, "Library search", "Manage the on-device search index") { section = SettingsSection.Search }
                            HorizontalDivider(color = EzColors.Hairline)
                            SettingsLink(EzIcons.Lock, "Diagnostics", "Crash reporting preferences") { section = SettingsSection.Diagnostics }
                            HorizontalDivider(color = EzColors.Hairline)
                            SettingsLink(EzIcons.Info, "About Xfiles", "Version, release notes, and links", onAbout)
                        }
                    }
                    SettingsSection.Appearance -> item("appearance") {
                        SettingsCard {
                            EzSectionHeader("Theme")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ThemePreference.entries.forEach { option ->
                                    FilterChip(selected = option == theme, onClick = { onTheme(option) }, label = { Text(option.label) })
                                }
                            }
                            SettingsSwitch("Touch feedback", "Vibrate when you choose or finish an action.", haptics, onHaptics)
                        }
                    }
                    SettingsSection.Fily -> {
                        item("access") { FilyAccessSettings(fily) }
                        item("model") {
                            SettingsCard {
                                EzSectionHeader("On-device model")
                                FilyModelRow(
                                    install = fily.model,
                                    bytes = AssistantModel.Fily.bytes,
                                    onDownload = fily.onDownload,
                                    onImport = fily.onImport,
                                    onCancel = fily.onCancelDownload,
                                    onRemove = fily.onRemoveModel,
                                )
                            }
                        }
                    }
                    SettingsSection.Search -> item("search") { SearchSettings(onClearSearchIndex) }
                    SettingsSection.Diagnostics -> item("diagnostics") {
                        if (cloudCrash.available) CloudCrashReportingCard(cloudCrash)
                        else SettingsCard {
                            Text("Cloud crash diagnostics are unavailable in this build.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().glassCard().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun FilyAccessSettings(fily: FilyAbout) {
    SettingsCard {
        SettingsSwitch(
            "Fily and Android assistants",
            "Allow assistants to find, open, share, or print files when you ask.",
            fily.enabled,
            fily.onEnabled,
        )
        if (fily.enabled) {
            SettingsSwitch(
                "Access to file contents",
                "Allow assistants to read documents when answering questions.",
                fily.contentAccess,
                fily.onContentAccess,
            )
            fily.languages?.let { languages ->
                LaunchedEffect(languages.support.all.isEmpty()) {
                    if (languages.support.all.isEmpty()) languages.onRefresh()
                }
                Text("Speech language", style = MaterialTheme.typography.labelLarge)
                EzOptionMenu(
                    options = languages.support.all,
                    selected = languages.support.all.firstOrNull { it.tag == languages.selected },
                    optionLabel = { languages.label(it.tag) + if (it.installed) "" else " (download)" },
                    onSelect = { languages.onSelect(it.tag) },
                    placeholder = "Phone language",
                )
            }
        }
    }
}

@Composable
private fun SearchSettings(onClear: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    var clearing by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    SettingsCard {
        EzSectionHeader("Search index")
        Text("File contents are indexed on this device. Clearing the index keeps your files and removes the stored search data.")
        TextButton(enabled = !clearing, onClick = {
            clearing = true
            status = null
            scope.launch {
                try {
                    onClear()
                    status = "Search index cleared."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status = "Could not clear the index. Try again."
                } catch (_: LinkageError) {
                    status = "The search engine is unavailable in this build."
                } finally {
                    clearing = false
                }
            }
        }) { Text(if (clearing) "Clearing…" else "Clear search index") }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary) }
    }
}

@Composable
private fun SettingsSwitch(title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = EzColors.Text, style = MaterialTheme.typography.bodyLarge)
            Text(detail, color = EzColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        EzSwitch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.semantics { contentDescription = title })
    }
}
