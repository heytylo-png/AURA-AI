package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AiModelUsed

/**
 * Animated 3-dot typing indicator displayed when the AI is processing or generating a response.
 */
@Composable
fun AiTypingIndicator(
    modifier: Modifier = Modifier,
    aiName: String = "Aura",
    statusText: String = "is thinking...",
    modelUsed: AiModelUsed? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("ai_typing_indicator"),
        horizontalAlignment = Alignment.Start
    ) {
        if (modelUsed != null) {
            ModelBadge(modelUsed = modelUsed, modifier = Modifier.padding(bottom = 4.dp))
        }

        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomEnd = 16.dp,
                        bottomStart = 4.dp
                    )
                )
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TypingBouncingDots(
                    dotSize = 7.dp,
                    dotColor = MaterialTheme.colorScheme.primary,
                    bounceDistance = 5.dp
                )

                Text(
                    text = "$aiName $statusText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * Three bouncing animated dots with staggered keyframe timings.
 */
@Composable
fun TypingBouncingDots(
    modifier: Modifier = Modifier,
    dotSize: Dp = 7.dp,
    dotColor: Color = MaterialTheme.colorScheme.primary,
    bounceDistance: Dp = 5.dp
) {
    val transition = rememberInfiniteTransition(label = "typing_dots_transition")

    val dot1Offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0f at 0
                -1f at 200 using FastOutSlowInEasing
                0f at 400 using FastOutSlowInEasing
                0f at 1200
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot1_offset"
    )

    val dot2Offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0f at 200
                -1f at 400 using FastOutSlowInEasing
                0f at 600 using FastOutSlowInEasing
                0f at 1200
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot2_offset"
    )

    val dot3Offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0f at 400
                -1f at 600 using FastOutSlowInEasing
                0f at 800 using FastOutSlowInEasing
                0f at 1200
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot3_offset"
    )

    val dot1Alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0.4f at 0
                1f at 200
                0.4f at 400
                0.4f at 1200
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot1_alpha"
    )

    val dot2Alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0.4f at 200
                1f at 400
                0.4f at 600
                0.4f at 1200
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot2_alpha"
    )

    val dot3Alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0.4f at 400
                1f at 600
                0.4f at 800
                0.4f at 1200
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot3_alpha"
    )

    Row(
        modifier = modifier
            .semantics { contentDescription = "AI is typing" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .offset(y = bounceDistance * dot1Offset)
                .size(dotSize)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = dot1Alpha))
        )
        Box(
            modifier = Modifier
                .offset(y = bounceDistance * dot2Offset)
                .size(dotSize)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = dot2Alpha))
        )
        Box(
            modifier = Modifier
                .offset(y = bounceDistance * dot3Offset)
                .size(dotSize)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = dot3Alpha))
        )
    }
}
