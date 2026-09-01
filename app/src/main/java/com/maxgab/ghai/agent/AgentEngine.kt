package com.maxgab.ghai.agent

import com.maxgab.ghai.data.AppSettings
import com.maxgab.ghai.data.LlmProvider
import com.maxgab.ghai.data.model.ToolCall
import com.maxgab.ghai.network.GeminiClient
import com.maxgab.ghai.network.LlmClient
import com.maxgab.ghai.network.OpenRouterClient
import com.maxgab.ghai.network.OrChatRequest
import com.maxgab.ghai.network.OrFunctionCall
import com.maxgab.ghai.network.OrMessage
import com.maxgab.ghai.network.OrReasoning
import com.maxgab.ghai.network.OrToolCall
import com.maxgab.ghai.network.StreamEvent
import com.maxgab.ghai.network.ToolDefinitions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

sealed interface AgentEvent {
    data class Reasoning(val delta: String) : AgentEvent
    data class Content(val delta: String) : AgentEvent
    data class ToolCallBegin(val id: String, val name: String, val arguments: String) : AgentEvent
    data class ToolCallEnd(val id: String, val name: String, val resultJson: String, val success: Boolean) : AgentEvent
    data class TurnFinished(val finalContent: String, val thinkingMillis: Long) : AgentEvent
    data class Failed(val message: String) : AgentEvent
    data class Retrying(val attempt: Int, val delayMs: Long, val message: String) : AgentEvent
}

class AgentEngine(
    private val openRouterClient: OpenRouterClient,
    private val geminiClient: GeminiClient,
    private val toolRouter: ToolRouter
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun clientFor(settings: AppSettings): LlmClient = when (settings.provider) {
        LlmProvider.OPENROUTER -> openRouterClient
        LlmProvider.GEMINI -> geminiClient
    }

    /**
     * Runs the model/tool loop with no step cap: it keeps calling tools and feeding
     * results back for as long as the model asks it to, until it produces a final
     * answer with no more tool calls. There is no artificial ceiling — the only way
     * to stop an in-progress turn is the user's Stop button, which cancels this
     * flow's collection.
     */
    fun run(seedMessages: List<OrMessage>, settings: AppSettings): Flow<AgentEvent> = channelFlow {
        val messages = seedMessages.toMutableList()
        val llmClient = clientFor(settings)

        while (true) {
            val request = OrChatRequest(
                model = settings.model,
                messages = messages,
                tools = ToolDefinitions.all,
                temperature = settings.temperature,
                reasoning = settings.effort.apiValue?.let { OrReasoning(effort = it) }
            )

            val contentBuilder = StringBuilder()
            val reasoningBuilder = StringBuilder()
            val toolCallBuffers = linkedMapOf<Int, ToolCallBuffer>()
            var streamFailed = false
            val thinkStart = System.currentTimeMillis()
            var thinkEnd = thinkStart

            llmClient.streamChat(request).collect { event ->
                when (event) {
                    is StreamEvent.ReasoningDelta -> {
                        reasoningBuilder.append(event.text)
                        send(AgentEvent.Reasoning(event.text))
                    }
                    is StreamEvent.ContentDelta -> {
                        if (thinkEnd == thinkStart) thinkEnd = System.currentTimeMillis()
                        contentBuilder.append(event.text)
                        send(AgentEvent.Content(event.text))
                    }
                    is StreamEvent.ToolCallDelta -> {
                        val buf = toolCallBuffers.getOrPut(event.index) { ToolCallBuffer() }
                        event.id?.let { buf.id = it }
                        event.name?.let { buf.name = it }
                        event.argumentsDelta?.let { buf.arguments.append(it) }
                    }
                    is StreamEvent.Finished -> Unit
                    is StreamEvent.Failed -> {
                        streamFailed = true
                        send(AgentEvent.Failed(event.message))
                    }
                    is StreamEvent.Retrying -> {
                        send(AgentEvent.Retrying(event.attempt, event.delayMs, event.message))
                    }
                }
            }

            if (streamFailed) return@channelFlow
            if (thinkEnd == thinkStart) thinkEnd = System.currentTimeMillis()
            val thinkingMillis = thinkEnd - thinkStart

            val toolCalls = toolCallBuffers.toSortedMap().values
                .filter { it.id != null && it.name != null }
                .map { ToolCall(it.id!!, it.name!!, it.arguments.toString()) }

            if (toolCalls.isEmpty()) {
                send(AgentEvent.TurnFinished(contentBuilder.toString(), thinkingMillis))
                return@channelFlow
            }

            messages += OrMessage(
                role = "assistant",
                content = contentBuilder.toString().ifBlank { null },
                toolCalls = toolCalls.map { tc -> OrToolCall(id = tc.id, function = OrFunctionCall(tc.name, tc.arguments)) }
            )

            for (call in toolCalls) {
                send(AgentEvent.ToolCallBegin(call.id, call.name, call.arguments))
                val result = toolRouter.execute(call.name, call.arguments)
                val success = isSuccessResult(result)
                send(AgentEvent.ToolCallEnd(call.id, call.name, result, success))
                messages += OrMessage(role = "tool", content = result, toolCallId = call.id, name = call.name)
            }
            // The loop continues, feeding the tool results back to the model.
        }
    }

    private fun isSuccessResult(resultJson: String): Boolean = runCatching {
        val el = json.parseToJsonElement(resultJson)
        (el as? JsonObject)?.containsKey("error") != true
    }.getOrDefault(true)

    private class ToolCallBuffer {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }
}
