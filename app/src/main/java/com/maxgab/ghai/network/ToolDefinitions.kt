package com.maxgab.ghai.network

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Instead of hand-coding one function per GitHub REST endpoint, the model gets two
 * generic tools that proxy directly to GitHub's REST and GraphQL APIs using the
 * user's personal access token. This gives it access to literally everything the
 * token is scoped for (repos, actions, issues, PRs, files, releases, webhooks,
 * collaborators, ...) without limiting it to a curated subset.
 */
object ToolDefinitions {

    val githubApi = OrTool(
        function = OrFunctionDef(
            name = "github_api",
            description = "Llama directamente a la API REST de GitHub (api.github.com) usando el token " +
                "personal del usuario. Sirve para CUALQUIER operación posible en GitHub: crear, editar o " +
                "borrar repositorios, gestionar GitHub Actions (workflows, runs, secrets), issues, pull " +
                "requests, archivos/contenido, releases, colaboradores, webhooks, organizaciones, etc. " +
                "'path' es la ruta del endpoint (ej: '/repos/owner/repo' o '/repos/owner/repo/actions/workflows'). " +
                "El body debe ser un objeto JSON válido cuando el método lo requiera (POST/PATCH/PUT).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("method") {
                        put("type", "string")
                        putJsonArray("enum") {
                            listOf("GET", "POST", "PUT", "PATCH", "DELETE").forEach { add(JsonPrimitive(it)) }
                        }
                        put("description", "Método HTTP.")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Ruta del endpoint tras https://api.github.com, ej: /repos/owner/repo")
                    }
                    putJsonObject("query") {
                        put("type", "object")
                        put("description", "Parámetros de query string opcionales, clave-valor.")
                    }
                    putJsonObject("body") {
                        put("type", "object")
                        put("description", "Cuerpo JSON opcional para POST/PUT/PATCH.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("method"))
                    add(JsonPrimitive("path"))
                }
            }
        )
    )

    val githubGraphql = OrTool(
        function = OrFunctionDef(
            name = "github_graphql",
            description = "Ejecuta una consulta o mutación GraphQL contra la API de GitHub " +
                "(https://api.github.com/graphql) usando el token del usuario. Útil para operaciones que " +
                "no están cubiertas de forma cómoda por la API REST (github_api).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Documento GraphQL (query o mutation).")
                    }
                    putJsonObject("variables") {
                        put("type", "object")
                        put("description", "Variables opcionales para la consulta.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("query"))
                }
            }
        )
    )

    val all = listOf(githubApi, githubGraphql)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonObject(
    key: String,
    builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
) {
    put(key, buildJsonObject(builder))
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArray(
    key: String,
    builder: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit
) {
    put(key, buildJsonArray(builder))
}
