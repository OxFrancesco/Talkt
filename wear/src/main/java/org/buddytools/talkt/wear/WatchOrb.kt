package org.buddytools.talkt.wear

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import org.buddytools.thinkingorbs.ThinkingOrbGeometry
import org.buddytools.thinkingorbs.ThinkingOrbParameters

@Composable
fun WatchOrb(recording: Boolean, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val motionEnabled = ValueAnimator.areAnimatorsEnabled()
    var time by remember { mutableDoubleStateOf(0.0) }
    val scale by animateFloatAsState(
        if (recording && motionEnabled) 1.06f else 1f,
        tween(180, easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)), label = "recording",
    )
    LaunchedEffect(recording, motionEnabled, lifecycle) {
        if (!recording || !motionEnabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous = 0L
            while (true) withFrameNanos { now ->
                if (previous == 0L) previous = now
                if (now - previous >= 33_000_000L) {
                    time += (now - previous) / 1_000_000_000.0
                    previous = now
                }
            }
        }
    }
    Canvas(modifier.graphicsLayer { scaleX = scale; scaleY = scale }.semantics {
        contentDescription = if (recording) "Stop recording" else "Record audio"
        stateDescription = if (recording) "Recording" else "Ready"
    }) {
        ThinkingOrbGeometry.band(size.minDimension, time, ThinkingOrbParameters(speed = 0.72f, spin = 1f, dots = 0.78f, spread = 0.92f, perspective = 0.9f))
            .forEach { dot ->
                drawCircle(Color.White.copy(alpha = dot.alpha * if (recording) 1f else 0.6f), dot.radius, Offset(dot.x, dot.y))
            }
    }
}
