package com.maxgab.ghai.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxgab.ghai.data.model.ChatMessage
import com.maxgab.ghai.data.model.MessageRole
import com.maxgab.ghai.data.model.MessageStatus
import com.maxgab.ghai.util.MarkdownText

@Composable
fun MessageBubble(
    message: ChatMessage,
    onEdit: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier
) {
    when (message.role) {
        MessageRole.USER -> UserBubble(message, onEdit, modifier)
        MessageRole.ASSISTANT -> AssistantBubble(message, modifier)
        MessageRole.TOOL -> ToolResultCard(message, modifier)
        MessageRole.SYSTEM -> Unit
    }
}

@Composable
private fun UserBubble(message: ChatMessage, onEdit: (ChatMessage) -> Unit, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text = message.content, style = MaterialTheme.typography.bodyLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(onClick = { onEdit(message) }, modifier = Modifier.padding(top = 2.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = "Editar", modifier = Modifier.padding(4.dp))
            }
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(message.content)) },
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copiar", modifier = Modifier.padding(4.dp))
            }
        }
    }
}

@Composable
private fun AssistantBubble(message: ChatMessage, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = modifier.fillMaxWidth()) {
        ThinkingIndicator(message)

        if (message.toolCalls.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                message.toolCalls.forEach { call -> ToolCallChip(call.name) }
            }
        }

        if (message.content.isNotBlank()) {
            MarkdownText(text = message.content)
        }

        if (message.status == MessageStatus.ERROR) {
            Text(
                text = message.errorMessage ?: "Ocurrió un error.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (message.status == MessageStatus.STOPPED) {
            Text(
                text = "Generación detenida.",
                color = LocalContentColor.current.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelMedium
            )
        }

        if (message.content.isNotBlank() && message.status != MessageStatus.STREAMING) {
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(message.content)) },
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copiar", modifier = Modifier.padding(4.dp))
            }
        }
    }
}

@Composable
private fun ToolCallChip(name: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(Icons.Outlined.Build, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
        Text(text = name, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ToolResultCard(message: ChatMessage, modifier: Modifier = Modifier) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (message.status == MessageStatus.PENDING) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(14.dp), strokeWidth = 2.dp)
            }
            Text(
                text = "Resultado · ${message.toolName ?: "herramienta"}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            if (message.status != MessageStatus.PENDING) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null
                )
            }
        }
        AnimatedVisibility(
            visible = expanded && message.content.isNotBlank(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(
                        text = message.content,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                IconButton(onClick = { clipboard.setText(AnnotatedString(message.content)) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copiar resultado", modifier = Modifier.padding(4.dp))
                }
            }
        }
    }
}
