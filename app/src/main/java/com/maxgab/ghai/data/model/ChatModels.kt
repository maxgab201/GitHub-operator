package com.maxgab.ghai.data.model

import kotlinx.serialization.Serializable

enum class MessageRole {
    SYSTEM, USER, ASSISTANT, TOOL
}

enum class EffortLevel(val apiValue: String?, val label: String) {
    NONE(null, "Ninguno"),
    LOW("low", "Bajo"),
    MEDIUM("medium", "Medio"),
    HIGH("high", "Alto");

    companion object {
        fun fromName(name: String?): EffortLevel = entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}

enum class MessageStatus {
    PENDING, STREAMING, DONE, ERROR, STOPPED
}

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String = "",
    val reasoning: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val status: MessageStatus = MessageStatus.DONE,
    val thinkStartedAt: Long = 0L,
    val thinkingMillis: Long = 0L,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val orderIndex: Long = 0L
)

data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val titleGenerated: Boolean
)
