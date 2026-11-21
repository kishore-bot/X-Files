package com.xfiles.android

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Typed Navigation 3 destinations. Keys stay minimal: the open document and the draft live in their
 * ViewModels, so a restored [Reader] or [Editor] key without a session is dropped (see [AppNavigator]).
 */
@Serializable
sealed interface Route : NavKey {
    @Serializable data object Library : Route
    @Serializable data object Reader : Route
    @Serializable data object Editor : Route
    @Serializable data object Tools : Route
    @Serializable data object About : Route
    @Serializable data object Tidy : Route
}
