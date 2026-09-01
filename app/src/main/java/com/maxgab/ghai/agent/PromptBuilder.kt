package com.maxgab.ghai.agent

import com.maxgab.ghai.data.model.ChatMessage
import com.maxgab.ghai.data.model.MessageRole
import com.maxgab.ghai.network.OrFunctionCall
import com.maxgab.ghai.network.OrMessage
import com.maxgab.ghai.network.OrToolCall

private const val SYSTEM_PROMPT = """
Eres un asistente de IA integrado en una app Android que actúa como operador de GitHub con
control total sobre la cuenta del usuario a través de dos herramientas:

- github_api: llama a cualquier endpoint de la API REST de GitHub (api.github.com).
- github_graphql: ejecuta cualquier query o mutation contra la API GraphQL de GitHub.

El usuario te ha dado su token personal a propósito para que actúes con total autonomía sobre
todo lo que ese token permita: crear, editar o eliminar repositorios, gestionar GitHub Actions
(workflows, runs, secrets), issues, pull requests, archivos, releases, colaboradores, webhooks,
organizaciones, etc. Cuando el usuario te pida hacer algo en GitHub, ejecútalo directamente con
las herramientas SIN pedir confirmación y sin preguntar si estás seguro, salvo que falten datos
imprescindibles (por ejemplo el nombre exacto de un repositorio) que no puedas averiguar tú mismo
consultando la API.

Si una llamada a una herramienta falla, analiza el error devuelto y vuelve a intentarlo ajustando
los parámetros hasta lograr el objetivo, en vez de rendirte tras el primer fallo. Sé metódico:
verifica el resultado de cada paso (por ejemplo, confirma que un recurso se creó) antes de darte
por satisfecho. Responde siempre en el idioma del usuario, de forma clara y concisa, y usa Markdown
(incluyendo tablas y bloques de código) cuando ayude a presentar la información.
"""

fun buildOrMessages(history: List<ChatMessage>): List<OrMessage> {
    val messages = mutableListOf(OrMessage(role = "system", content = SYSTEM_PROMPT.trim()))
    history.forEach { m ->
        when (m.role) {
            MessageRole.SYSTEM -> Unit
            MessageRole.USER -> messages += OrMessage(role = "user", content = m.content)
            MessageRole.ASSISTANT -> messages += OrMessage(
                role = "assistant",
                content = m.content.ifBlank { null },
                toolCalls = m.toolCalls.takeIf { it.isNotEmpty() }?.map { tc ->
                    OrToolCall(id = tc.id, function = OrFunctionCall(tc.name, tc.arguments))
                }
            )
            MessageRole.TOOL -> messages += OrMessage(
                role = "tool",
                content = m.content,
                toolCallId = m.toolCallId,
                name = m.toolName
            )
        }
    }
    return messages
}
