package com.xfiles.feature.reader

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xfiles.design.BarIconButton
import com.xfiles.design.EzIcons
import com.xfiles.design.glassBar

/**
 * The reader's top bar: a slim floating glass pill with Back and the full file name. Every other action
 * lives in the floating action button, so the name keeps the room it needs.
 */
@Composable
internal fun ReaderTopBar(
    title: String,
    backEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .glassBar()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIconButton(EzIcons.Back, "Back to library", onBack, enabled = backEnabled)
        Text(
            title,
            modifier = Modifier.padding(start = 4.dp, end = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
