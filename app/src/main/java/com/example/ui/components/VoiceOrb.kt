package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.voice.VoiceState
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun VoiceOrb(
    voiceState: VoiceState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp
) {
    val transition = rememberInfiniteTransition(label = "orb_transition")

    // Breathing pulse
    val breathingPulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing_pulse"
    )

    // Fast rotation for processing
    val rotationAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing)
        ),
        label = "rotation_angle"
    )

    // Wave ring expansion for listening
    val listeningWave by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "listening_wave"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .testTag("voice_orb"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = (this.size.minDimension / 2f) * 0.7f

            when (voiceState) {
                VoiceState.IDLE -> {
                    // Soft radiant aura
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = 0.45f),
                                secondaryColor.copy(alpha = 0.2f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = baseRadius * breathingPulse
                        ),
                        radius = baseRadius * breathingPulse,
                        center = center
                    )
                    // Inner luminous core
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White, primaryColor, secondaryColor),
                            center = center,
                            radius = baseRadius * 0.65f
                        ),
                        radius = baseRadius * 0.65f,
                        center = center
                    )
                }

                VoiceState.LISTENING -> {
                    // Expanding sonar wave
                    drawCircle(
                        color = primaryColor.copy(alpha = (1.4f - listeningWave).coerceIn(0f, 0.6f)),
                        radius = baseRadius * listeningWave,
                        center = center,
                        style = Stroke(width = 3.dp.toPx())
                    )
                    drawCircle(
                        color = primaryColor.copy(alpha = 0.7f),
                        radius = baseRadius * 0.75f,
                        center = center
                    )
                }

                VoiceState.PROCESSING -> {
                    // Rotating chromatic rings
                    val radians = Math.toRadians(rotationAngle.toDouble())
                    val offset1 = Offset((cos(radians) * 6).toFloat(), (sin(radians) * 6).toFloat())
                    val offset2 = Offset((-cos(radians) * 6).toFloat(), (-sin(radians) * 6).toFloat())

                    drawCircle(
                        color = primaryColor.copy(alpha = 0.8f),
                        radius = baseRadius * 0.85f,
                        center = center + offset1,
                        style = Stroke(width = 4.dp.toPx())
                    )
                    drawCircle(
                        color = secondaryColor.copy(alpha = 0.8f),
                        radius = baseRadius * 0.75f,
                        center = center + offset2,
                        style = Stroke(width = 3.dp.toPx())
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White, primaryColor),
                            center = center,
                            radius = baseRadius * 0.5f
                        ),
                        radius = baseRadius * 0.5f,
                        center = center
                    )
                }

                VoiceState.SPEAKING -> {
                    // Audio wave rings
                    drawCircle(
                        color = tertiaryColor.copy(alpha = 0.35f),
                        radius = baseRadius * breathingPulse * 1.15f,
                        center = center,
                        style = Stroke(width = 4.dp.toPx())
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White, tertiaryColor, secondaryColor),
                            center = center,
                            radius = baseRadius * 0.75f
                        ),
                        radius = baseRadius * 0.75f,
                        center = center
                    )
                }
            }
        }

        // Overlay status icon
        when (voiceState) {
            VoiceState.LISTENING -> {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Listening",
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.35f)
                )
            }
            VoiceState.SPEAKING -> {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = "Speaking",
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.35f)
                )
            }
            else -> {}
        }
    }
}
