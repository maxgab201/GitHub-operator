package com.maxgab.ghai.network

import com.maxgab.ghai.data.LlmProvider
import com.maxgab.ghai.data.SettingsRepository
import com.maxgab.ghai.data.UsageTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private sealed interface GeminiAttemptOutcome {
    data object Success : GeminiAttemptOutcome
    data class Retryable(val message: String) : GeminiAttemptOutcome
    data class Fatal(val message: String) : GeminiAttemptOutcome
}

/**
 * Talks to Google AI Studio's Gemini API (generativelanguage.googleapis.com) using
 * the same internal [OrChatRequest] shape the rest of the app already builds for
 * OpenRouter — translating to/from Gemini's own `contents`/`parts` wire format here
 * so nothing above this layer needs to know which provider is active.
 */
class GeminiClient(
    private val settings: SettingsRepository,
    private val usage: UsageTracker
) : LlmClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val eventSourceFactory = EventSources.createFactory(client)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    override fun streamChat(request: OrChatRequest): Flow<StreamEvent> = channelFlow {
        var attempt = 0
        while (true) {
            attempt++
            usage.recordRequest(LlmProvider.GEMINI)
            val outcome = runSingleAttempt(request, this)
            when (outcome) {
                is GeminiAttemptOutcome.Success -> return@channelFlow
                is GeminiAttemptOutcome.Fatal -> {
                    send(StreamEvent.Failed(outcome.message, retryable = false))
                    return@channelFlow
                }
                is GeminiAttemptOutcome.Retryable -> {
                    val delayMs = backoffDelayMillis(attempt)
                    send(StreamEvent.Retrying(attempt, delayMs, outcome.message))
                    delay(delayMs)
                }
            }
        }
    }

    private suspend fun runSingleAttempt(
        request: OrChatRequest,
        scope: ProducerScope<StreamEvent>
    ): GeminiAttemptOutcome {
        val apiKey = settings.getGeminiKey()
        val geminiRequest = toGeminiRequest(request)
        val body = json.encodeToString(GeminiRequest.serializer(), geminiRequest)
            .toRequestBody("application/json".toMediaType())

        val url = "https://generativelanguage.googleapis.com/v1beta/models/${request.model}:streamGenerateContent"
            .toHttpUrl().newBuilder()
            .addQueryParameter("alt", "sse")
            .addQueryParameter("key", apiKey)
            .build()
        val httpRequest = Request.Builder().url(url).post(body).build()

        var toolCallCounter = 0
        var eventSourceRef: EventSource? = null
        try {
            return suspendCancellableCoroutine { cont ->
                val listener = object : EventSourceListener() {
                    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                        runCatching { json.decodeFromString(GeminiStreamChunk.serializer(), data) }
                            .onSuccess { chunk -> toolCallCounter = emitChunk(chunk, scope, toolCallCounter) }
                    }

                    override fun onClosed(eventSource: EventSource) {
                        if (cont.isActive) cont.resume(GeminiAttemptOutcome.Success)
                    }

                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                        if (!cont.isActive) return
                        val code = response?.code
                        val bodyText = runCatching { response?.body?.string() }.getOrNull()
                        val message = bodyText?.takeIf { it.isNotBlank() } ?: t?.message ?: "Fallo de conexión con Gemini"
                        val outcome = when {
                            code != null && (code == 429 || code in 500..599) -> GeminiAttemptOutcome.Retryable(message)
                            code != null -> GeminiAttemptOutcome.Fatal("HTTP $code: $message")
                            t is java.io.IOException -> GeminiAttemptOutcome.Retryable(message)
                            else -> GeminiAttemptOutcome.Fatal(message)
                        }
                        cont.resume(outcome)
                    }
                }
                val es = eventSourceFactory.newEventSource(httpRequest, listener)
                eventSourceRef = es
                cont.invokeOnCancellation { es.cancel() }
            }
        } catch (e: CancellationException) {
            eventSourceRef?.cancel()
            throw e
        }
    }

    private fun emitChunk(chunk: GeminiStreamChunk, scope: ProducerScope<StreamEvent>, toolCallCounterIn: Int): Int {
        var toolCallCounter = toolCallCounterIn
        chunk.error?.let {
            scope.trySend(StreamEvent.Failed(it.message ?: "Error de Gemini", retryable = false))
            return toolCallCounter
        }
        val candidate = chunk.candidates?.firstOrNull() ?: return toolCallCounter
        candidate.content?.parts?.forEach { part ->
            part.text?.let { scope.trySend(StreamEvent.ContentDelta(it)) }
            part.functionCall?.let { fc ->
                val index = toolCallCounter++
                scope.trySend(
                    StreamEvent.ToolCallDelta(
                        index = index,
                        id = "call_${UUID.randomUUID()}",
                        name = fc.name,
                        argumentsDelta = fc.args?.toString() ?: "{}"
                    )
                )
            }
        }
        candidate.finishReason?.let { scope.trySend(StreamEvent.Finished(it)) }
        return toolCallCounter
    }

    override suspend fun completeOnce(request: OrChatRequest): Result<String> {
        return try {
            Result.success(completeOnceOrThrow(request))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun completeOnceOrThrow(request: OrChatRequest): String = retryWithBackoff(isRetryable = ::isTransientHttpError) {
        usage.recordRequest(LlmProvider.GEMINI)
        val apiKey = settings.getGeminiKey()
        val geminiRequest = toGeminiRequest(request)
        val body = json.encodeToString(GeminiRequest.serializer(), geminiRequest)
            .toRequestBody("application/json".toMediaType())
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${request.model}:generateContent"
            .toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey)
            .build()
        val httpRequest = Request.Builder().url(url).post(body).build()

        withContext(Dispatchers.IO) {
            client.newCall(httpRequest).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw HttpStatusException(response.code, text)
                val chunk = json.decodeFromString(GeminiStreamChunk.serializer(), text)
                chunk.candidates?.firstOrNull()?.content?.parts?.firstNotNullOfOrNull { it.text } ?: ""
            }
        }
    }

    /** Translates our internal OpenAI-style chat shape into Gemini's contents/parts format. */
    private fun toGeminiRequest(request: OrChatRequest): GeminiRequest {
        var systemInstruction: GeminiContent? = null
        val contents = mutableListOf<GeminiContent>()

        request.messages.forEach { m ->
            when (m.role) {
                "system" -> systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = m.content.orEmpty())))
                "user" -> contents += GeminiContent(role = "user", parts = listOf(GeminiPart(text = m.content.orEmpty())))
                "assistant" -> {
                    val parts = mutableListOf<GeminiPart>()
                    if (!m.content.isNullOrBlank()) parts += GeminiPart(text = m.content)
                    m.toolCalls?.forEach { tc ->
                        val args = runCatching { json.parseToJsonElement(tc.function.arguments) }.getOrDefault(JsonObject(emptyMap()))
                        parts += GeminiPart(functionCall = GeminiFunctionCall(name = tc.function.name, args = args))
                    }
                    if (parts.isNotEmpty()) contents += GeminiContent(role = "model", parts = parts)
                }
                "tool" -> {
                    val responseJson = runCatching { json.parseToJsonElement(m.content ?: "{}") }
                        .getOrDefault(buildJsonObject { put("result", JsonPrimitive(m.content ?: "")) })
                    contents += GeminiContent(
                        role = "function",
                        parts = listOf(
                            GeminiPart(
                                functionResponse = GeminiFunctionResponse(
                                    name = m.name ?: "unknown",
                                    response = responseJson
                                )
                            )
                        )
                    )
                }
            }
        }

        val tools = request.tools?.takeIf { it.isNotEmpty() }?.let { list ->
            listOf(
                GeminiToolDecl(
                    functionDeclarations = list.map { t ->
                        GeminiFunctionDecl(
                            name = t.function.name,
                            description = t.function.description,
                            parameters = t.function.parameters
                        )
                    }
                )
            )
        }

        return GeminiRequest(
            contents = contents,
            tools = tools,
            systemInstruction = systemInstruction,
            generationConfig = request.temperature?.let { GeminiGenerationConfig(temperature = it) }
        )
    }
}
