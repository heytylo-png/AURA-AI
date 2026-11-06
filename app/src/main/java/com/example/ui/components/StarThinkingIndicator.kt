package com.example.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Aura's "thinking" mark: three small four-point stars on a ring. The ring turns once every
 * 2.4 s and each star brightens in turn. Holds still when the phone's animations are turned off.
 */
@Composable
fun StarThinkingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val context = LocalContext.current
    val animate = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val transition = rememberInfiniteTransition(label = "aura_stars")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "aura_stars_angle"
    )
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "aura_stars_twinkle"
    )

    Canvas(modifier = modifier.size(size)) {
        val ring = this.size.minDimension * 0.24f
        val base = this.size.minDimension * 0.22f
        val rotation = if (animate) angle else 0f
        rotate(rotation) {
            STARS.forEachIndexed { i, (deg, scale) ->
                val a = (deg - 90f) * (PI / 180f).toFloat()
                val c = Offset(center.x + ring * cos(a), center.y + ring * sin(a))
                val alpha = if (animate) twinkle(phase, i) else 1f
                drawStar(c, base * scale, color.copy(alpha = color.alpha * alpha))
            }
        }
    }
}

/** "Starai is thinking" row used in the chat list. */
@Composable
fun ThinkingRow(aiName: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .testTag("thinking_indicator")
            .semantics { contentDescription = "$aiName is thinking" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StarThinkingIndicator()
        Text(
            text = "$aiName is thinking",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Angle on the ring (degrees from the top) and relative size of each star. */
private val STARS = listOf(0f to 0.62f, 120f to 0.46f, 240f to 0.36f)

/** Each star peaks a third of a cycle after the previous one; range 0.35–1. */
internal fun twinkle(phase: Float, index: Int): Float {
    val p = ((phase - index / 3f) % 1f + 1f) % 1f
    val wave = (1f - cos(2f * PI.toFloat() * p)) / 2f
    return 0.35f + 0.65f * wave
}

private fun DrawScope.drawStar(center: Offset, radius: Float, color: Color) {
    val inner = radius * 0.22f
    val path = Path()
    for (k in 0 until 8) {
        val r = if (k % 2 == 0) radius else inner
        val a = (k * 45f - 90f) * (PI / 180f).toFloat()
        val x = center.x + r * cos(a)
        val y = center.y + r * sin(a)
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}
