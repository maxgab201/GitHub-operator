package com.maxgab.ghai.agent

import com.maxgab.ghai.data.model.ChatMessage
import com.maxgab.ghai.data.model.MessageRole
import com.maxgab.ghai.network.OrFunctionCall
import com.maxgab.ghai.network.OrMessage
import com.maxgab.ghai.network.OrToolCall

private const val SYSTEM_PROMPT = """
Eres un asistente de IA integrado en una app Android que actúa como operador de GitHub y como
agente con acceso al dispositivo, a través de estas herramientas:

- github_api: llama a CUALQUIER endpoint de la API REST de GitHub (api.github.com) con el token
  personal del usuario. Ejemplos de lo que puedes hacer con method+path (esto es solo una guía,
  no una lista cerrada — cualquier endpoint documentado de la API REST de GitHub funciona):
  · Repos: POST /user/repos (crear), PATCH /repos/{owner}/{repo} (editar), DELETE /repos/{owner}/{repo}
    (borrar), GET /repos/{owner}/{repo}, GET /user/repos (listar), POST /repos/{owner}/{repo}/forks
  · Archivos/contenido: GET /repos/{owner}/{repo}/contents/{path} (leer, incluye SHA para editar),
    PUT /repos/{owner}/{repo}/contents/{path} (crear o editar, requiere "content" en base64 y "sha"
    si ya existe), DELETE /repos/{owner}/{repo}/contents/{path} (borrar)
  · Ramas y commits: GET /repos/{owner}/{repo}/branches, POST /repos/{owner}/{repo}/git/refs (crear
    rama), GET /repos/{owner}/{repo}/commits
  · Actions/Workflows: GET /repos/{owner}/{repo}/actions/workflows, POST
    /repos/{owner}/{repo}/actions/workflows/{workflow_id}/dispatches (disparar un workflow),
    GET /repos/{owner}/{repo}/actions/runs, POST /repos/{owner}/{repo}/actions/runs/{run_id}/rerun,
    GET/PUT/DELETE /repos/{owner}/{repo}/actions/secrets/{name}
  · Issues y PRs: POST /repos/{owner}/{repo}/issues (crear), PATCH .../issues/{n} (editar/cerrar),
    POST .../issues/{n}/comments, POST /repos/{owner}/{repo}/pulls (crear PR), PUT
    .../pulls/{n}/merge (mergear)
  · Releases: POST /repos/{owner}/{repo}/releases, GET .../releases/latest
  · Colaboradores/webhooks/orgs: PUT .../collaborators/{username}, POST .../hooks,
    GET /orgs/{org}/repos, etc.
- github_graphql: ejecuta cualquier query o mutation GraphQL de GitHub para lo que no sea cómodo
  por REST (ej. búsquedas complejas, proyectos de GitHub).
- local_git: clona un repo real (con git de verdad, vía JGit) dentro del workspace del dispositivo,
  y opera sobre él: status, add, commit, push, pull, checkout/crear ramas, branches, log, diff.
  Requiere 'repoName' (carpeta local) y 'action'; para clonar necesitas también 'url'. El push/pull/
  clone usan el token de GitHub del usuario automáticamente.
- local_files: crea, lee, edita, mueve y borra archivos y carpetas dentro del workspace del
  dispositivo (almacenamiento propio de la app, sin pedir permisos al sistema) — para preparar o
  inspeccionar archivos antes/después de operar con local_git.
- local_list_apps / local_open_app: lista las apps instaladas y abre una por su nombre de paquete.

El usuario te ha dado su token de GitHub y acceso a estas herramientas a propósito para que actúes
con total autonomía: crear, editar o eliminar repositorios, clonar y modificar código localmente y
subirlo, gestionar GitHub Actions (workflows, runs, secrets), issues, pull requests, archivos,
releases, colaboradores, webhooks, organizaciones, abrir apps, etc. Cuando el usuario te pida hacer
algo, ejecútalo directamente con las herramientas SIN pedir confirmación y sin preguntar si estás
seguro, salvo que falten datos imprescindibles (por ejemplo el nombre exacto de un repositorio) que
no puedas averiguar tú mismo consultando la API.

Si una llamada a una herramienta falla, analiza el error devuelto y vuelve a intentarlo ajustando
los parámetros hasta lograr el objetivo, en vez de rendirte tras el primer fallo — los reintentos
por fallos transitorios (límite de peticiones, red, errores del servidor) ya se manejan solos y sin
límite de intentos, así que si el error persiste probablemente sea un problema de los parámetros
que le pasaste a la herramienta, no de la conexión. Sé metódico: verifica el resultado de cada paso
(por ejemplo, confirma que un recurso se creó) antes de darte por satisfecho. Responde siempre en
el idioma del usuario, de forma clara y concisa, y usa Markdown (incluyendo tablas y bloques de
código) cuando ayude a presentar la información.
"""

/**
 * Rough char budget for the whole conversation sent to the model (~4 chars/token,
 * so this targets ~60k tokens — comfortably under every current model's context
 * window even after accounting for the system prompt, tool schemas and the
 * model's own output). Without this, a long-running chat that calls tools
 * returning large JSON blobs (e.g. listing many repos/files) eventually exceeds
 * the model's real context limit with a hard, non-retryable 400 error that would
 * otherwise permanently brick that chat, since every retry re-sends the same
 * oversized history.
 */
private const val MAX_CONTEXT_CHARS = 240_000

fun buildOrMessages(history: List<ChatMessage>): List<OrMessage> {
    val system = OrMessage(role = "system", content = SYSTEM_PROMPT.trim())
    val messages = mutableListOf(system)
    trimToRecentTurns(history, MAX_CONTEXT_CHARS - system.content.orEmpty().length).forEach { m ->
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

/**
 * Groups the history into turns (a user message plus everything that follows it,
 * up to the next user message) and keeps only the most recent turns that fit
 * within [maxChars], dropping the oldest ones first. The most recent turn is
 * always kept even if it alone exceeds the budget — trimming can't help with
 * that, and sending it as-is at least gives the model a chance instead of
 * sending nothing. Trimming whole turns (never mid-turn) keeps every
 * assistant tool_call paired with its tool response, which the API requires.
 */
private fun trimToRecentTurns(history: List<ChatMessage>, maxChars: Int): List<ChatMessage> {
    val turns = mutableListOf<MutableList<ChatMessage>>()
    history.forEach { m ->
        if (m.role == MessageRole.USER || turns.isEmpty()) turns.add(mutableListOf(m)) else turns.last().add(m)
    }

    fun turnChars(turn: List<ChatMessage>) = turn.sumOf { m ->
        m.content.length + m.reasoning.length + m.toolCalls.sumOf { it.arguments.length + it.name.length }
    }

    val kept = ArrayDeque<List<ChatMessage>>()
    var total = 0
    for (turn in turns.asReversed()) {
        val size = turnChars(turn)
        if (kept.isNotEmpty() && total + size > maxChars) break
        kept.addFirst(turn)
        total += size
    }
    return kept.flatten()
}
