@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.xfiles.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.feature.reader.domain.BookSearchHit

/** The find-in-book sheet: a field, the count, and each place the text was found. A tap opens the place. */
@Composable
internal fun BookSearchSheet(
    state: BookSearchState,
    onQuery: (String) -> Unit,
    onOpen: (BookSearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Find in book") },
                leadingIcon = { Icon(EzIcons.Search, null, tint = EzColors.TextSecondary) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Text(
                searchStatus(state),
                style = MaterialTheme.typography.labelLarge,
                color = EzColors.TextSecondary,
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.hits) { hit ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(hit) }
                            .padding(vertical = 8.dp),
                    ) {
                        Row { Text(hit.snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 3) }
                    }
                }
            }
        }
    }
}

/** The line above the results: searching, the count (capped at the most the search keeps), or nothing yet. */
internal fun searchStatus(state: BookSearchState): String = when {
    state.query.isBlank() -> "Type to search this book."
    state.searching -> "Searching..."
    state.hits.isEmpty() -> "No matches."
    state.hits.size >= SEARCH_CAP -> "More than ${SEARCH_CAP - 1} matches. Showing the first $SEARCH_CAP."
    state.hits.size == 1 -> "1 match"
    else -> "${state.hits.size} matches"
}

private const val SEARCH_CAP = 400
