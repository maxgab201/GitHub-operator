package com.maxgab.ghai.network

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
import com.maxgab.ghai.util.jsonArrayOrNull
import com.maxgab.ghai.util.jsonObjectOrNull
import com.maxgab.ghai.util.jsonPrimitiveOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.pow

private sealed interface AttemptOutcome {
    data object Success : AttemptOutcome
    data class Retryable(val message: String) : AttemptOutcome
    data class Fatal(val message: String) : AttemptOutcome
}

class OpenRouterClient(
    private val settings: SettingsRepository,
    private val usage: UsageTracker
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val eventSourceFactory = EventSources.createFactory(client)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /**
     * Streams a chat completion, transparently retrying transient failures
     * (timeouts, 429, 5xx) with exponential backoff. Non-retryable failures
     * (bad key, invalid request) are surfaced immediately as [StreamEvent.Failed].
     */
    fun streamChat(request: OrChatRequest, maxAttempts: Int): Flow<StreamEvent> = channelFlow {
        val attempts = maxAttempts.coerceAtLeast(1)
        var attempt = 0
        while (attempt < attempts) {
            attempt++
            usage.recordRequest()
            val outcome = runSingleAttempt(request, this)
            when (outcome) {
                is AttemptOutcome.Success -> return@channelFlow
                is AttemptOutcome.Fatal -> {
                    send(StreamEvent.Failed(outcome.message, retryable = false))
                    return@channelFlow
                }
                is AttemptOutcome.Retryable -> {
                    if (attempt >= attempts) {
                        send(StreamEvent.Failed(outcome.message, retryable = false))
                        return@channelFlow
                    }
                    val delayMs = min(20_000L, (1000L * 2.0.pow(attempt - 1)).toLong())
                    delay(delayMs)
                }
            }
        }
    }

    private suspend fun runSingleAttempt(
        request: OrChatRequest,
        scope: ProducerScope<StreamEvent>
    ): AttemptOutcome {
        val apiKey = settings.getOpenRouterKey()
        val body = json.encodeToString(OrChatRequest.serializer(), request)
            .toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .addHeader("HTTP-Referer", "https://github.com/maxgab201/GitHub-operator")
            .addHeader("X-Title", "GH AI")
            .post(body)
            .build()

        var eventSourceRef: EventSource? = null
        try {
            return suspendCancellableCoroutine { cont ->
                val listener = object : EventSourceListener() {
                    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                        if (data == "[DONE]") {
                            if (cont.isActive) cont.resume(AttemptOutcome.Success)
                            return
                        }
                        runCatching { json.decodeFromString(OrStreamChunk.serializer(), data) }
                            .onSuccess { chunk -> emitChunk(chunk, scope) }
                    }

                    override fun onClosed(eventSource: EventSource) {
                        if (cont.isActive) cont.resume(AttemptOutcome.Success)
                    }

                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                        if (!cont.isActive) return
                        val code = response?.code
                        val bodyText = runCatching { response?.body?.string() }.getOrNull()
                        val message = bodyText?.takeIf { it.isNotBlank() }
                            ?: t?.message
                            ?: "Fallo de conexión con OpenRouter"
                        val outcome = when {
                            code != null && (code == 429 || code in 500..599) -> AttemptOutcome.Retryable(message)
                            code != null -> AttemptOutcome.Fatal("HTTP $code: $message")
                            t is java.io.IOException -> AttemptOutcome.Retryable(message)
                            else -> AttemptOutcome.Fatal(message)
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

    private fun emitChunk(chunk: OrStreamChunk, scope: ProducerScope<StreamEvent>) {
        chunk.error?.let {
            scope.trySend(StreamEvent.Failed(it.message ?: "Error de OpenRouter", retryable = false))
            return
        }
        val choice = chunk.choices.firstOrNull() ?: return
        choice.delta?.let { delta ->
            if (!delta.reasoning.isNullOrEmpty()) scope.trySend(StreamEvent.ReasoningDelta(delta.reasoning))
            if (!delta.content.isNullOrEmpty()) scope.trySend(StreamEvent.ContentDelta(delta.content))
            delta.toolCalls?.forEach { tc ->
                scope.trySend(
                    StreamEvent.ToolCallDelta(
                        index = tc.index,
                        id = tc.id,
                        name = tc.function?.name,
                        argumentsDelta = tc.function?.arguments
                    )
                )
            }
        }
        choice.finishReason?.let { scope.trySend(StreamEvent.Finished(it)) }
    }

    /** Non-streaming helper used for cheap tasks like auto-titling a session. */
    suspend fun completeOnce(request: OrChatRequest, maxAttempts: Int): Result<String> {
        return runCatching {
            retryWithBackoff(
                maxAttempts = maxAttempts,
                isRetryable = ::isTransientHttpError
            ) {
                usage.recordRequest()
                val apiKey = settings.getOpenRouterKey()
                val body = json.encodeToString(OrChatRequest.serializer(), request.copy(stream = false))
                    .toRequestBody("application/json".toMediaType())
                val httpRequest = Request.Builder()
                    .url("https://openrouter.ai/api/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("HTTP-Referer", "https://github.com/maxgab201/GitHub-operator")
                    .addHeader("X-Title", "GH AI")
                    .post(body)
                    .build()
                withContext(Dispatchers.IO) {
                    client.newCall(httpRequest).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw HttpStatusException(response.code, text)
                        val obj = json.parseToJsonElement(text)
                        obj.jsonObjectOrNull()
                            ?.get("choices")?.jsonArrayOrNull()
                            ?.firstOrNull()?.jsonObjectOrNull()
                            ?.get("message")?.jsonObjectOrNull()
                            ?.get("content")?.jsonPrimitiveOrNull()
                            ?: ""
                    }
                }
            }
        }
    }
}
