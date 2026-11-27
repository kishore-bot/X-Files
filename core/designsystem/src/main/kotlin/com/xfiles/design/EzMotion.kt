package com.xfiles.design

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment

/** Follows Android's Remove animations preference. */
val LocalReducedMotion = staticCompositionLocalOf { false }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object EzMotion {
    internal val standard = MotionScheme.standard()
    private val expressive = MotionScheme.expressive()

    fun <T> fastSpatial(): FiniteAnimationSpec<T> = standard.fastSpatialSpec()
    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = standard.defaultSpatialSpec()
    fun <T> emphasis(): FiniteAnimationSpec<T> = expressive.fastSpatialSpec()

    fun <T> fastEffects(): FiniteAnimationSpec<T> = standard.fastEffectsSpec()
    fun <T> defaultEffects(): FiniteAnimationSpec<T> = standard.defaultEffectsSpec()

    fun fadeEnter(reduced: Boolean): EnterTransition =
        if (reduced) EnterTransition.None else fadeIn(fastEffects())

    fun fadeExit(reduced: Boolean): ExitTransition =
        if (reduced) ExitTransition.None else fadeOut(fastEffects())

    fun edgeEnter(reduced: Boolean, fromTop: Boolean = false): EnterTransition =
        if (reduced) EnterTransition.None else fadeEnter(false) + slideInVertically(fastSpatial()) {
            if (fromTop) -it / 6 else it / 6
        }

    fun edgeExit(reduced: Boolean, toTop: Boolean = false): ExitTransition =
        if (reduced) ExitTransition.None else fadeExit(false) + slideOutVertically(fastSpatial()) {
            if (toTop) -it / 6 else it / 6
        }

    fun expandFromTop(reduced: Boolean): EnterTransition =
        if (reduced) EnterTransition.None
        else expandVertically(defaultSpatial(), expandFrom = Alignment.Top) + fadeEnter(false)

    fun collapseToTop(reduced: Boolean): ExitTransition =
        if (reduced) ExitTransition.None
        else shrinkVertically(fastSpatial(), shrinkTowards = Alignment.Top) + fadeExit(false)

    /** The caller supplies logical direction so push and pop mirror in right-to-left layouts. */
    fun horizontal(
        direction: Int,
        distancePx: Int,
        reduced: Boolean,
    ): ContentTransform {
        if (reduced) return EnterTransition.None togetherWith ExitTransition.None
        val enter = fadeIn(defaultEffects()) + slideInHorizontally(defaultSpatial()) {
            direction * (it / 10).coerceAtMost(distancePx)
        }
        val exit = fadeOut(fastEffects()) + slideOutHorizontally(defaultSpatial()) {
            -direction * (it / 14).coerceAtMost(distancePx / 2)
        }
        return enter togetherWith exit
    }
}
