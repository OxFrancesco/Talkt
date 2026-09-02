package org.buddytools.thinkingorbs

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

/** Theme colors for the animation, ported from thinking-orbs-kotlin. */
data class ThinkingOrbColors(
    val accent: Color,
    val dots: Color,
    val pill: Color,
    val label: Color,
)

/**
 * Animated dotted band from OxFrancesco/thinking-orbs-kotlin.
 *
 * The system animator setting is honored so the indicator becomes a still, meaningful frame when
 * the user disables motion in Android accessibility settings.
 */
@Composable
fun ThinkingOrb(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    parameters: ThinkingOrbParameters = ThinkingOrbParameters(),
    colors: ThinkingOrbColors,
    paused: Boolean = false,
    contentDescription: String = "Thinking",
) {
    val inspectionMode = LocalInspectionMode.current
    val motionEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    var frameNanos by remember { mutableLongStateOf(0L) }

    LaunchedEffect(paused, motionEnabled, inspectionMode) {
        if (paused || !motionEnabled || inspectionMode) return@LaunchedEffect
        while (true) {
            withFrameNanos { frameNanos = it }
        }
    }

    Canvas(
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val time = if (frameNanos == 0L) 0.0 else frameNanos / 1_000_000_000.0
        val frame = ThinkingOrbGeometry.band(this.size.minDimension, time, parameters)
        for (dot in frame) {
            drawCircle(
                color = lerp(colors.dots, colors.accent, dot.accent).copy(alpha = dot.alpha),
                radius = dot.radius,
                center = androidx.compose.ui.geometry.Offset(dot.x, dot.y),
            )
        }
    }
}
