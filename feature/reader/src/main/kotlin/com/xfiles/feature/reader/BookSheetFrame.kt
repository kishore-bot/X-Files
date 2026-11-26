@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.xfiles.feature.reader

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.xfiles.design.EzColors
import com.xfiles.design.EzControlSheet

@Composable
internal fun BookSheetFrame(
    onDismiss: () -> Unit,
    skipPartiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    EzControlSheet(onDismiss, skipPartiallyExpanded, content)
}

/** A quiet heading inside a sheet, as the EzFiles reader labels its settings. */
@Composable
internal fun BookSectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = EzColors.TextSecondary)
}
