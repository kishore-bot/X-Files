package com.xfiles.design

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.staticCompositionLocalOf

/** Spatial feedback follows the platform accessibility preference without changing the visual identity. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Material 3 Expressive motion: springs instead of fixed durations, so movement
 * settles naturally and can be interrupted mid-way. The values are Material's own
 * expressive tokens (ExpressiveMotionTokens in material3 1.4, where
 * MotionScheme.expressive() isn't public yet).
 *
 * Spatial springs move things (position, size, scale) and may overshoot a little;
 * effects springs change colour and opacity and never overshoot.
 * The system's animation scale, including "Remove animations", applies to all of them.
 */
object EzMotion {
    /** Small things: buttons, chips, icons, toggles. A quick, slightly bouncy settle. */
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)

    /** Medium things: indicators, cards, screens moving into place. */
    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)

    /** Large things: full-screen changes and sheets. */
    fun <T> slowSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 200f)

    fun <T> fastEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)

    fun <T> defaultEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)

    fun <T> slowEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 800f)
}
