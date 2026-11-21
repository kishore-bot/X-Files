package com.xfiles.feature.reader

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.xfiles.design.EzIcons

@Composable
internal fun PdfFindBar(state: PdfFindState, onQuery: (String) -> Unit, onMove: (Int) -> Unit, onClose: () -> Unit) {
    val text = rememberTextFieldState()
    val query = text.text.toString().trim()
    LaunchedEffect(query) { onQuery(query.take(256)) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            state = text,
            modifier = Modifier.weight(1f),
            lineLimits = TextFieldLineLimits.SingleLine,
            label = { Text("Find in PDF") },
        )
        IconButton(onClick = { onMove(-1) }, enabled = state.selected > 0) {
            Icon(EzIcons.ChevronUp, "Previous match")
        }
        IconButton(onClick = { onMove(1) }, enabled = state.selected + 1 in state.matches.indices) {
            Icon(EzIcons.ChevronDown, "Next match")
        }
        IconButton(onClick = onClose) { Icon(EzIcons.Close, "Close PDF search") }
    }
    when {
        state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error -> Text("Could not search this PDF.", color = MaterialTheme.colorScheme.error)
        query.isNotBlank() -> Text(
            if (state.matches.isEmpty()) "No matches" else "${state.selected + 1} / ${state.matches.size}${if (state.truncated) "+" else ""}",
            modifier = Modifier.padding(vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
