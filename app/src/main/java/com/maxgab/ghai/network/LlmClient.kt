package com.maxgab.ghai.network

import kotlinx.coroutines.flow.Flow

/**
 * A chat-completion backend. [OpenRouterClient] and `GeminiClient` both implement
 * this against the same internal [OrChatRequest]/[OrMessage] shapes, translating to
 * their own wire format internally, so the rest of the app (AgentEngine, prompt
 * building, tool definitions) stays provider-agnostic.
 */
interface LlmClient {
    fun streamChat(request: OrChatRequest): Flow<StreamEvent>
    suspend fun completeOnce(request: OrChatRequest): Result<String>
}
