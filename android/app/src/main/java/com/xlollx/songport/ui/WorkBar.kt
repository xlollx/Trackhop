package com.xlollx.songport.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The bar of something at work (a sync, a preview, a removal), drawn the way Android 16's player
 * does: the part done is a wave that keeps moving, the rest a flat track; with no share known, a
 * stretch of wave slides along. A flat Material bar without the 1.18 switch or with animations off
 * in the system settings. A bar that stands for something stopped (a paused sync) stays flat: the
 * wave says "working".
 */
@Composable
fun WorkBar(
    progress: (() -> Float)?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    if (!richMotion()) {
        if (progress != null) LinearProgressIndicator(progress = progress, modifier = modifier, color = color, trackColor = trackColor)
        else LinearProgressIndicator(modifier = modifier, color = color, trackColor = trackColor)
        return
    }
    val moving = rememberInfiniteTransition(label = "work bar")
    val phase by moving.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "wave")
    val slide by moving.animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = LinearEasing)), label = "slide")
    Canvas(
        modifier.height(10.dp).semantics {
            progressBarRangeInfo = if (progress != null) ProgressBarRangeInfo(progress().coerceIn(0f, 1f), 0f..1f) else ProgressBarRangeInfo.Indeterminate
        },
    ) {
        val stroke = 4.dp.toPx()
        val amplitude = 2.5.dp.toPx()
        val wavelength = 26.dp.toPx()
        val gap = 5.dp.toPx()
        val mid = size.height / 2
        val w = size.width
        val (from, to) = if (progress != null) 0f to progress().coerceIn(0f, 1f) * w else {
            val len = w * 0.4f
            val x = slide * (w + len) - len
            x.coerceAtLeast(0f) to (x + len).coerceAtMost(w)
        }
        fun flat(a: Float, b: Float) {
            if (b - a > stroke) drawLine(trackColor, Offset(a + stroke / 2, mid), Offset(b - stroke / 2, mid), stroke, StrokeCap.Round)
        }
        flat(if (to > 0f) to + gap else 0f, w)
        if (from > 0f) flat(0f, from - gap)
        if (to - from < 1f) return@Canvas
        val wave = Path()
        var x = from
        // The wave grows from flat over its first and last half wavelength: no hook at the ends.
        fun y(x: Float): Float {
            val edge = min(x - from, to - x).coerceAtLeast(0f) / (wavelength / 2)
            return mid + amplitude * edge.coerceAtMost(1f) * sin(((x / wavelength) - phase) * 2 * PI).toFloat()
        }
        wave.moveTo(x, y(x))
        while (x < to) { x = min(x + 2f, to); wave.lineTo(x, y(x)) }
        drawPath(wave, color, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}
