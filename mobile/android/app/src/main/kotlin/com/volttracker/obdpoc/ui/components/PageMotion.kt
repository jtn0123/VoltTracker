package com.volttracker.obdpoc.ui.components

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/** How one page hands over to the next. */
enum class PageMotion { PUSH, POP, TAB }

/**
 * The app's screen change, for an `AnimatedContent`: a pushed screen slides in from the right over
 * the one below (which drifts left and dims), a pop plays that backwards, and a tab switch fades
 * through. [depth] is how deep the arriving screen sits, so the deeper one is always drawn on top.
 */
fun pageTransition(
    motion: PageMotion,
    depth: Int,
    reduceMotion: Boolean,
): ContentTransform {
    if (reduceMotion) return EnterTransition.None togetherWith ExitTransition.None
    val slide = tween<IntOffset>(VoltMotion.SCREEN_MS, easing = VoltMotion.Ease)
    val fade = tween<Float>(VoltMotion.SCREEN_MS, easing = VoltMotion.Ease)
    val transform =
        when (motion) {
            PageMotion.PUSH ->
                slideInHorizontally(slide) { it } togetherWith
                    slideOutHorizontally(slide) { -it / PARALLAX } + fadeOut(fade, targetAlpha = DIMMED)
            PageMotion.POP ->
                slideInHorizontally(slide) { -it / PARALLAX } + fadeIn(fade, initialAlpha = DIMMED) togetherWith
                    slideOutHorizontally(slide) { it }
            PageMotion.TAB ->
                fadeIn(tween(VoltMotion.STANDARD_MS, delayMillis = TAB_FADE_OUT_MS, easing = VoltMotion.Ease)) +
                    scaleIn(
                        tween(VoltMotion.STANDARD_MS, delayMillis = TAB_FADE_OUT_MS, easing = VoltMotion.Ease),
                        initialScale = TAB_SCALE,
                    ) togetherWith fadeOut(tween(TAB_FADE_OUT_MS))
        }
    transform.targetContentZIndex = depth.toFloat()
    return transform
}

/** The screen underneath moves a fraction as far as the one on top. */
private const val PARALLAX = 4

/** How far the screen underneath fades while it is covered. */
private const val DIMMED = 0.35f

private const val TAB_FADE_OUT_MS = 80
private const val TAB_SCALE = 0.985f
