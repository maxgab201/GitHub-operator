package com.maxgab.ghai.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val tools: List<GeminiToolDecl>? = null,
    val systemInstruction: GeminiContent? = null,
    val generationConfig: GeminiGenerationConfig? = null
)

@Serializable
data class GeminiGenerationConfig(val temperature: Double? = null)

@Serializable
data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiPart(
    val text: String? = null,
    val functionCall: GeminiFunctionCall? = null,
    val functionResponse: GeminiFunctionResponse? = null
)

@Serializable
data class GeminiFunctionCall(
    val name: String,
    val args: JsonElement? = null
)

@Serializable
data class GeminiFunctionResponse(
    val name: String,
    val response: JsonElement
)

@Serializable
data class GeminiToolDecl(
    val functionDeclarations: List<GeminiFunctionDecl>
)

@Serializable
data class GeminiFunctionDecl(
    val name: String,
    val description: String,
    val parameters: JsonElement
)

// ---- Streaming / response shapes ----

@Serializable
data class GeminiStreamChunk(
    val candidates: List<GeminiCandidate>? = null,
    val error: GeminiError? = null
)

@Serializable
data class GeminiError(
    val message: String? = null,
    val code: Int? = null
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    @SerialName("finishReason") val finishReason: String? = null
)
