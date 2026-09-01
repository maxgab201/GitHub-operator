package com.maxgab.ghai.agent

import com.maxgab.ghai.data.AppSettings
import com.maxgab.ghai.network.OpenRouterClient
import com.maxgab.ghai.network.OrChatRequest
import com.maxgab.ghai.network.OrMessage

private const val TITLE_PROMPT = "Resume la siguiente conversación en un título corto de 3 a 6 " +
    "palabras, sin comillas ni punto final, en el mismo idioma del usuario. Responde solo con el título."

class SessionTitler(private val openRouterClient: OpenRouterClient) {

    suspend fun generateTitle(userMessage: String, assistantMessage: String, settings: AppSettings): String? {
        val request = OrChatRequest(
            model = settings.model,
            messages = listOf(
                OrMessage(role = "system", content = TITLE_PROMPT),
                OrMessage(role = "user", content = "Usuario: $userMessage\nAsistente: ${assistantMessage.take(500)}")
            ),
            stream = false,
            temperature = 0.3
        )
        return openRouterClient.completeOnce(request, maxAttempts = 2)
            .getOrNull()
            ?.trim()
            ?.trim('"', '“', '”', '.')
            ?.take(60)
            ?.takeIf { it.isNotBlank() }
    }
}
