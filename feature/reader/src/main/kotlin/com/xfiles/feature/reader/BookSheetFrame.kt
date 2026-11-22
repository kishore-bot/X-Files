@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.xfiles.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.EzShapes
import com.xfiles.design.glass

/**
 * A reader sheet in the EzFiles reader's look: a near-solid frosted panel with a drag handle and a
 * light scrim, so the book stays readable behind it. Every reader sheet is built on this.
 */
@Composable
internal fun BookSheetFrame(
    onDismiss: () -> Unit,
    skipPartiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        shape = EzShapes.Sheet,
        containerColor = Color.Transparent,
        contentColor = EzColors.Text,
        tonalElevation = 0.dp,
        scrimColor = EzColors.Scrim.copy(alpha = 0.22f),
        dragHandle = null,
    ) {
        val frost = Brush.verticalGradient(
            listOf(EzColors.SheetSurface.copy(alpha = 0.97f), EzColors.DialogSurface.copy(alpha = 0.985f)),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(frost, EzShapes.Sheet)
                .glass(EzShapes.Sheet, 0.35f)
                .navigationBarsPadding(),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 12.dp, bottom = 14.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .background(EzColors.TextSecondary.copy(alpha = 0.35f), EzShapes.Pill),
            )
            content()
        }
    }
}

/** A quiet heading inside a sheet, as the EzFiles reader labels its settings. */
@Composable
internal fun BookSectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = EzColors.TextSecondary)
}
