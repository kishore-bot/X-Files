package com.xfiles.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzShapes
import com.xfiles.design.glassBar
import com.xfiles.design.glassCard
import com.xfiles.crash.CloudCrashReporting
import com.xfiles.crash.NoCloudCrashReporting
import com.xfiles.design.glossyPrimary
import com.xfiles.settings.ThemePreference

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    theme: ThemePreference,
    haptics: Boolean,
    onTheme: (ThemePreference) -> Unit,
    onHaptics: (Boolean) -> Unit,
    cloudCrash: CloudCrashReporting = NoCloudCrashReporting,
) {
    var vision by rememberSaveable { mutableStateOf(false) }
    val back: () -> Unit = { if (vision) vision = false else onBack() }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().glassBar(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(EzIcons.Back, if (vision) "Back to About" else "Back to library") }
            Text(if (vision) "Why Xfiles" else "About Xfiles", style = MaterialTheme.typography.titleLarge)
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            if (vision) {
                VisionScreen()
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
                            Text("Xfiles", style = MaterialTheme.typography.headlineMedium)
                            Text("Built for people. Shared with everyone.", fontStyle = FontStyle.Italic, color = EzColors.Accent)
                        }
                    }
                    item("appearance") {
                        Column(Modifier.fillMaxWidth().glassCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Appearance", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ThemePreference.entries.forEach { option ->
                                    FilterChip(
                                        selected = option == theme,
                                        onClick = { onTheme(option) },
                                        label = { Text(option.label) },
                                    )
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Haptic feedback", Modifier.weight(1f))
                                Switch(checked = haptics, onCheckedChange = onHaptics)
                            }
                        }
                    }
                    item("crash") { CloudCrashReportingCard(cloudCrash) }
                    item("maker") { MakerCard() }
                    item("vision") {
                        Row(
                            Modifier.fillMaxWidth().glassCard().clickable { vision = true }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Icon(EzIcons.Lightbulb, null, tint = EzColors.Primary)
                            Column(Modifier.weight(1f)) {
                                Text("Why Xfiles", style = MaterialTheme.typography.titleMedium)
                                Text("The idea behind the app", color = EzColors.TextSecondary)
                            }
                            Icon(EzIcons.ChevronRight, null, tint = EzColors.TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

/** The company behind Xfiles: its name, meaning, motto and philosophy. */
@Composable
private fun MakerCard() {
    Column(
        Modifier.fillMaxWidth().glassCard().padding(20.dp).semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Made by", style = MaterialTheme.typography.labelLarge, color = EzColors.TextSecondary)
        Text(
            "SAHAPRAXIS",
            style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 3.sp, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
        )
        Text("A group that came together on its own.", color = EzColors.TextSecondary, textAlign = TextAlign.Center)
        Text(
            "“Free doesn’t mean cheap.”",
            Modifier.padding(top = 6.dp),
            fontStyle = FontStyle.Italic,
            color = EzColors.Accent,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Create · Share · Grow",
            style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 1.5.sp),
            color = EzColors.Text,
        )
        Text("Xfiles is our first project.", Modifier.padding(top = 4.dp), color = EzColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun VisionScreen() {
    LazyColumn(
        Modifier.widthIn(max = 620.dp).fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 24.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(VisionText) { block ->
            when (block) {
                is VisionBlock.Title -> Text(block.text, style = MaterialTheme.typography.headlineMedium)
                is VisionBlock.Paragraph -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 27.sp),
                )
                is VisionBlock.PullQuote -> Text(
                    block.text,
                    modifier = Modifier.fillMaxWidth().glassCard().padding(16.dp),
                    color = EzColors.Accent,
                    style = MaterialTheme.typography.titleLarge,
                )
                is VisionBlock.Beliefs -> Column(
                    Modifier.fillMaxWidth().glassCard().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    block.items.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
                }
                is VisionBlock.SignOff -> Text(block.text, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
