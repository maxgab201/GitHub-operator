package com.maxgab.ghai.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.maxgab.ghai.data.model.ChatMessage
import com.maxgab.ghai.data.model.MessageStatus
import kotlinx.coroutines.delay

@Composable
fun ThinkingIndicator(message: ChatMessage, modifier: Modifier = Modifier) {
    val stillThinking = message.status == MessageStatus.STREAMING && message.content.isBlank()
    var expanded by remember(message.id) { mutableStateOf(false) }

    if (!stillThinking && message.thinkingMillis <= 0L) return

    Column(modifier = modifier) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier
                .clickable(enabled = message.reasoning.isNotBlank()) { expanded = !expanded }
                .padding(vertical = 2.dp)
        ) {
            if (stillThinking) {
                ThinkingDots()
                Text(
                    text = "  Pensando" + elapsedSuffix(message.thinkStartedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalContentColor.current.copy(alpha = 0.6f)
                )
            } else {
                Text(
                    text = "Ha pensado por ${formatThinkingDuration(message.thinkingMillis)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalContentColor.current.copy(alpha = 0.6f)
                )
                if (message.reasoning.isNotBlank()) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).padding(start = 2.dp),
                        tint = LocalContentColor.current.copy(alpha = 0.6f)
                    )
                }
            }
        }
        AnimatedVisibility(visible = expanded && message.reasoning.isNotBlank()) {
            Text(
                text = message.reasoning,
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.65f),
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun elapsedSuffix(startedAt: Long): String {
    if (startedAt <= 0L) return "…"
    var seconds by remember(startedAt) { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(startedAt) {
        while (true) {
            seconds = (System.currentTimeMillis() - startedAt) / 1000
            delay(200)
        }
    }
    return "… ${seconds}s"
}

private fun formatThinkingDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "$minutes min $seconds s" else "$seconds s"
}

@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "thinking-dots")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot-$index"
            )
            Row(
                modifier = Modifier
                    .size(5.dp)
                    .alpha(alpha)
                    .background(LocalContentColor.current, CircleShape)
            ) {}
        }
    }
}
