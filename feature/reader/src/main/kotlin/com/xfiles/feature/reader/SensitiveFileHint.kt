package com.xfiles.feature.reader

import androidx.compose.animation.CapturedAnimatedVisibility
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzShapes
import com.xfiles.design.glassPanel
import com.xfiles.tidy.SensitiveFindings
import kotlinx.coroutines.delay

/**
 * A small hint over an open document that shows a card, ID or bank account number, offering to keep it
 * out of screenshots and the recent-apps view while it is open. The numbers found are never shown.
 */
@Composable
internal fun SensitiveFileHint(documentKey: String?, findings: SensitiveFindings?, modifier: Modifier = Modifier) {
    if (documentKey == null) return
    var dismissed by remember(documentKey) { mutableStateOf(false) }
    var secure by remember(documentKey) { mutableStateOf(false) }
    LaunchedEffect(secure) {
        if (secure) {
            delay(2_500)
            dismissed = true
        }
    }
    SecureWindow(secure) // Stays on while this file is open, after the hint has gone.
    CapturedAnimatedVisibility(
        visible = !dismissed && findings?.any == true,
        enter = slideInVertically { -it / 2 } + fadeIn(),
        exit = slideOutVertically { -it / 2 } + fadeOut(),
        modifier = modifier,
    ) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().glassPanel(EzShapes.Medium)
                .padding(start = 14.dp, top = 6.dp, bottom = 4.dp, end = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(EzIcons.Lock, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text("This file shows ${findings?.describe().orEmpty()}", style = MaterialTheme.typography.bodyMedium, color = EzColors.Text)
                    Text(
                        if (secure) "Screenshots are off while it's open." else "Keep it out of screenshots and the recent apps view?",
                        style = MaterialTheme.typography.bodySmall,
                        color = EzColors.TextSecondary,
                    )
                }
                IconButton(onClick = { dismissed = true }) { Icon(EzIcons.Close, "Dismiss", tint = EzColors.TextSecondary) }
            }
            if (!secure) {
                TextButton(onClick = { secure = true }, modifier = Modifier.align(Alignment.End).padding(end = 8.dp)) {
                    Text("Hide from screenshots")
                }
            }
        }
    }
}

/** FLAG_SECURE on the activity window while [enabled]; cleared when the document closes. */
@Composable
internal fun SecureWindow(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled, context) {
        val window = if (enabled) context.findActivity()?.window else null
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
