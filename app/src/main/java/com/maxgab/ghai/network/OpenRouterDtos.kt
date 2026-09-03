package com.maxgab.ghai.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class OrChatRequest(
    val model: String,
    val messages: List<OrMessage>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    val tools: List<OrTool>? = null,
    val reasoning: OrReasoning? = null
)

@Serializable
data class OrReasoning(
    val effort: String? = null
)

@Serializable
data class OrMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OrToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null
)

@Serializable
data class OrToolCall(
    val id: String,
    val type: String = "function",
    val function: OrFunctionCall
)

@Serializable
data class OrFunctionCall(
    val name: String,
    val arguments: String
)

@Serializable
data class OrTool(
    val type: String = "function",
    val function: OrFunctionDef
)

@Serializable
data class OrFunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonElement
)

// ---- Streaming chunk shapes ----

@Serializable
data class OrStreamChunk(
    val id: String? = null,
    val choices: List<OrStreamChoice> = emptyList(),
    val error: OrError? = null
)

@Serializable
data class OrError(
    val message: String? = null,
    val code: JsonElement? = null
)

@Serializable
data class OrStreamChoice(
    val delta: OrDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class OrDelta(
    val role: String? = null,
    val content: String? = null,
    val reasoning: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OrDeltaToolCall>? = null
)

@Serializable
data class OrDeltaToolCall(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: OrDeltaFunction? = null
)

@Serializable
data class OrDeltaFunction(
    val name: String? = null,
    val arguments: String? = null
)
