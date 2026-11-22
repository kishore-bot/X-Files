package com.xfiles.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
    BookSheetFrame(onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
        }
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.hits) { hit ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onOpen(hit) }
                        .padding(vertical = 10.dp),
                ) {
                    Text(
                        hit.snippet,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
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
