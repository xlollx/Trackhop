package com.xlollx.songport.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * The 1.18 motion (the wave of [WorkBar], the equalizer, the counting figures, the pop at the end
 * of a sync, the sheet that grows from its tile): on with the 1.18 switch, and only while the
 * system's animations are on.
 */
@Composable
fun richMotion(): Boolean {
    val ctx = LocalContext.current
    val animate = remember {
        android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    return animate && com.xlollx.songport.Features.v2
}

/** Three bars that bounce out of step, as a player's equalizer: "a sync is at work", in place of a spinner. */
@Composable
fun Equalizer(color: Color, size: Dp, modifier: Modifier = Modifier) {
    val beat = rememberInfiniteTransition(label = "equalizer")
    // Slow enough to read as music, not as a busy spinner: each bar bounces once or twice a second.
    val t by beat.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "beat")
    Canvas(modifier.size(size)) {
        val gap = this.size.width * 0.14f
        val bar = (this.size.width - 2 * gap) / 3
        for (i in 0 until 3) {
            // Each bar its own speed and start: they never line up.
            val wave = sin(((t * (i + 2)) + i * 0.33f) * 2 * PI).toFloat()
            val h = this.size.height * (0.35f + 0.65f * (0.5f + 0.5f * wave))
            drawRoundRect(color, Offset(i * (bar + gap), this.size.height - h), Size(bar, h), CornerRadius(bar / 2, bar / 2))
        }
    }
}

/** [target] counted up from zero the first time it shows (under a second); as it is without [richMotion]. */
@Composable
fun countUp(target: Int): Int {
    if (!richMotion()) return target
    val shown = remember { Animatable(0f) }
    LaunchedEffect(target) { shown.animateTo(target.toFloat(), tween(900, easing = FastOutSlowInEasing)) }
    return shown.value.toInt()
}
