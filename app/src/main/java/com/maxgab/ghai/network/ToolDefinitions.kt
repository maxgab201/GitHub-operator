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

    val localFiles = OrTool(
        function = OrFunctionDef(
            name = "local_files",
            description = "Crea, lee, edita, mueve y borra archivos y carpetas en el workspace local de la " +
                "app (almacenamiento propio del dispositivo, sin necesitar permisos del sistema). Útil para " +
                "preparar archivos antes de subirlos con local_git, o para trabajar con archivos sueltos. " +
                "'action' puede ser: list, read, write, append, mkdir, delete, move, exists. 'path' es " +
                "relativo al workspace (ej: 'mi-repo/src/Main.kt').",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("action") {
                        put("type", "string")
                        putJsonArray("enum") {
                            listOf("list", "read", "write", "append", "mkdir", "delete", "move", "exists")
                                .forEach { add(JsonPrimitive(it)) }
                        }
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Ruta relativa al workspace.")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "Contenido a escribir/agregar (para write/append).")
                    }
                    putJsonObject("newPath") {
                        put("type", "string")
                        put("description", "Ruta destino (solo para move).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("action"))
                }
            }
        )
    )

    val localGit = OrTool(
        function = OrFunctionDef(
            name = "local_git",
            description = "Opera un repositorio git real clonado dentro del workspace local de la app " +
                "(clonar, ver estado, agregar cambios, commitear, pushear, pullear, cambiar de rama, ver " +
                "log/diff). Usa el token de GitHub del usuario para autenticar push/pull/clone por HTTPS. " +
                "'repoName' es el nombre de la carpeta local del repo dentro del workspace (ej: 'mi-repo'). " +
                "'action' puede ser: clone, init, status, add, commit, push, pull, checkout, branches, log, " +
                "diff, removeRepo.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("action") {
                        put("type", "string")
                        putJsonArray("enum") {
                            listOf(
                                "clone", "init", "status", "add", "commit", "push", "pull",
                                "checkout", "branches", "log", "diff", "removeRepo"
                            ).forEach { add(JsonPrimitive(it)) }
                        }
                    }
                    putJsonObject("repoName") {
                        put("type", "string")
                        put("description", "Carpeta local del repo dentro del workspace.")
                    }
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "URL HTTPS del repo remoto (solo para clone), ej: https://github.com/owner/repo.git")
                    }
                    putJsonObject("branch") {
                        put("type", "string")
                        put("description", "Nombre de rama (para clone o checkout).")
                    }
                    putJsonObject("createNew") {
                        put("type", "boolean")
                        put("description", "En checkout: si crea la rama si no existe.")
                    }
                    putJsonObject("pattern") {
                        put("type", "string")
                        put("description", "Patrón de archivos para 'add' (por defecto '.', todo).")
                    }
                    putJsonObject("message") {
                        put("type", "string")
                        put("description", "Mensaje de commit (para commit).")
                    }
                    putJsonObject("authorName") {
                        put("type", "string")
                    }
                    putJsonObject("authorEmail") {
                        put("type", "string")
                    }
                    putJsonObject("remote") {
                        put("type", "string")
                        put("description", "Nombre del remoto a pushear (por defecto 'origin').")
                    }
                    putJsonObject("maxCount") {
                        put("type", "integer")
                        put("description", "Cantidad máxima de commits a listar (para log).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("action"))
                    add(JsonPrimitive("repoName"))
                }
            }
        )
    )

    val localListApps = OrTool(
        function = OrFunctionDef(
            name = "local_list_apps",
            description = "Lista las apps instaladas en el dispositivo que se pueden abrir (nombre visible " +
                "y nombre de paquete), para luego abrir alguna con local_open_app.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            }
        )
    )

    val localOpenApp = OrTool(
        function = OrFunctionDef(
            name = "local_open_app",
            description = "Abre una app instalada en el dispositivo por su nombre de paquete " +
                "(obtenido con local_list_apps).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("packageName") {
                        put("type", "string")
                        put("description", "Nombre de paquete de la app a abrir, ej: com.android.chrome")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("packageName"))
                }
            }
        )
    )

    val all = listOf(githubApi, githubGraphql, localGit, localFiles, localListApps, localOpenApp)
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
