package com.nestgallery.viewer.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private enum class PullPhase { Pulling, Armed, Refreshing, Done }

/** Refreshes that finish faster than this still show the spinner this long, so the pill doesn't just blink. */
private const val MIN_REFRESH_MS = 600L
private const val DONE_MS = 700L

/**
 * Pull-to-refresh for the browsing screens: a small capsule in the top bar's style that drops out from under the
 * bar as the list is pulled ("Pull to refresh" -> "Release to refresh", with a haptic tick at the threshold), shows
 * [refreshingLabel] with a spinner while [isRefreshing], then "Up to date" for a moment. [topInset] is the top bar's
 * height, so the capsule appears just below it rather than behind it.
 *
 * The content must be scrollable (empty states included, e.g. with verticalScroll) for the pull to register.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NestPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    topInset: Dp,
    modifier: Modifier = Modifier,
    refreshingLabel: String = "Refreshing…",
    doneLabel: String = "Up to date",
    content: @Composable BoxScope.() -> Unit
) {
    val state = rememberPullToRefreshState()
    // Shown state: on as soon as the pull is released (the caller's isRefreshing may only flip a frame later), held
    // for at least MIN_REFRESH_MS, then "done" for DONE_MS.
    var shown by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    // Keyed on shown too: a pull the caller ignores (isRefreshing never turns on) still finishes.
    LaunchedEffect(isRefreshing, shown) {
        if (isRefreshing) {
            if (!shown) { shown = true; startedAt = SystemClock.uptimeMillis() }
        } else if (!shown) {
            if (done) { delay(400); done = false }   // keep the done label while the pill slides back up
        } else if (!done) {
            delay((MIN_REFRESH_MS - (SystemClock.uptimeMillis() - startedAt)).coerceAtLeast(0L))
            done = true
            delay(DONE_MS)
            shown = false
        }
    }

    PullToRefreshBox(
        isRefreshing = shown,
        onRefresh = {
            done = false
            shown = true
            startedAt = SystemClock.uptimeMillis()
            onRefresh()
        },
        modifier = modifier,
        state = state,
        indicator = {
            val fraction = state.distanceFraction
            val phase = when {
                done -> PullPhase.Done
                shown -> PullPhase.Refreshing
                fraction >= 1f -> PullPhase.Armed
                else -> PullPhase.Pulling
            }
            val haptics = LocalHapticFeedback.current
            LaunchedEffect(phase == PullPhase.Armed) {
                if (phase == PullPhase.Armed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            PullPill(
                phase = phase,
                fraction = fraction,
                refreshingLabel = refreshingLabel,
                doneLabel = doneLabel,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset)
            )
        },
        content = content
    )
}

/**
 * An empty, scrollable layer filling the parent Box, for screens with nothing to scroll (empty folder, loading):
 * the pull needs a scrollable to start from, and putting verticalScroll on the centring Box itself would stop it
 * centring (inside a scroll the height is unbounded). Put it first, under the centred content.
 */
@Composable
fun BoxScope.PullTarget() {
    Box(Modifier.matchParentSize().verticalScroll(rememberScrollState()))
}

@Composable
private fun PullPill(phase: PullPhase, fraction: Float, refreshingLabel: String, doneLabel: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    // The pull state animates the fraction itself (held at 1 while refreshing, back to 0 after), so the pill just
    // follows it, with a little rubber-band past the threshold.
    val visible = fraction.coerceIn(0f, 1f) + (fraction - 1f).coerceIn(0f, 1f) * 0.15f
    val arrowTurn by animateFloatAsState(if (phase == PullPhase.Armed) 180f else 0f, label = "pullArrow")

    Surface(
        shape = RoundedCornerShape(50),
        color = colors.surface.copy(alpha = 0.96f),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.30f)),
        modifier = modifier.graphicsLayer {
            // slides down from just under the bar: hidden (above, transparent, small) at 0, in place at 1
            translationY = with(density) { (-28).dp.toPx() + 40.dp.toPx() * visible }
            alpha = (visible * 1.6f).coerceIn(0f, 1f)
            val s = 0.85f + 0.15f * visible.coerceAtMost(1f)
            scaleX = s
            scaleY = s
        }
    ) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentKey = { if (it == PullPhase.Armed) PullPhase.Pulling else it },  // arrow just turns, no fade
            label = "pullPhase"
        ) { p ->
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    when (p) {
                        PullPhase.Refreshing -> CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = colors.primary,
                            modifier = Modifier.fillMaxSize()
                        )
                        PullPhase.Done -> Icon(Icons.Default.CheckCircle, null, tint = colors.primary, modifier = Modifier.fillMaxSize())
                        else -> Icon(
                            Icons.Default.ArrowDownward,
                            null,
                            tint = colors.primary,
                            modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = arrowTurn }
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    when (p) {
                        PullPhase.Pulling -> if (phase == PullPhase.Armed) "Release to refresh" else "Pull to refresh"
                        PullPhase.Armed -> "Release to refresh"
                        PullPhase.Refreshing -> refreshingLabel
                        PullPhase.Done -> doneLabel
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface
                )
            }
        }
    }
}
