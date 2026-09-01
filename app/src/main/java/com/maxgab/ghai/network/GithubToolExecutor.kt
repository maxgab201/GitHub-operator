package com.maxgab.ghai.network

import com.maxgab.ghai.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Executes the generic `github_api` / `github_graphql` tool calls against the real
 * GitHub API using the user's personal access token. Transient failures (timeouts,
 * 429, 5xx) are retried with backoff; anything else is returned as a tool result so
 * the model can see the real error and adjust its next call.
 */
class GithubToolExecutor(private val settings: SettingsRepository) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    suspend fun execute(toolName: String, argumentsJson: String): String = withContext(Dispatchers.IO) {
        try {
            when (toolName) {
                "github_api" -> retryWithBackoff(isRetryable = ::isTransientHttpError) {
                    callRest(argumentsJson)
                }
                "github_graphql" -> retryWithBackoff(isRetryable = ::isTransientHttpError) {
                    callGraphql(argumentsJson)
                }
                else -> errorJson("Herramienta desconocida: $toolName")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) {
            errorJson("GitHub devolvió HTTP ${e.code}: ${e.message}")
        } catch (e: Exception) {
            errorJson("Fallo ejecutando $toolName: ${e.message}")
        }
    }

    private fun callRest(argumentsJson: String): String {
        val args = json.parseToJsonElement(argumentsJson).let { it as? JsonObject } ?: JsonObject(emptyMap())
        val method = args["method"]?.jsonPrimitive?.content?.uppercase() ?: "GET"
        val path = args["path"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Falta 'path'")
        val query = (args["query"] as? JsonObject)
        val bodyObj = (args["body"] as? JsonObject)

        var urlBuilder = "https://api.github.com${if (path.startsWith("/")) path else "/$path"}".toHttpUrl()
            .newBuilder()
        query?.entries?.forEach { (k, v) ->
            urlBuilder = urlBuilder.addQueryParameter(k, v.jsonPrimitive.content)
        }

        val token = settings.getGithubToken()
        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")

        val requestBody = bodyObj?.let {
            json.encodeToString(JsonObject.serializer(), it).toRequestBody("application/json".toMediaType())
        }

        when (method) {
            "GET" -> requestBuilder.get()
            "DELETE" -> if (requestBody != null) requestBuilder.delete(requestBody) else requestBuilder.delete()
            "POST" -> requestBuilder.post(requestBody ?: EMPTY_JSON_BODY)
            "PUT" -> requestBuilder.put(requestBody ?: EMPTY_JSON_BODY)
            "PATCH" -> requestBuilder.patch(requestBody ?: EMPTY_JSON_BODY)
            else -> throw IllegalArgumentException("Método no soportado: $method")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw HttpStatusException(response.code, text.take(2000))
            return buildJsonObject {
                put("status", JsonPrimitive(response.code))
                put("body", runCatching { json.parseToJsonElement(text.ifBlank { "null" }) }.getOrDefault(JsonPrimitive(text)))
            }.toString()
        }
    }

    private fun callGraphql(argumentsJson: String): String {
        val args = json.parseToJsonElement(argumentsJson).let { it as? JsonObject } ?: JsonObject(emptyMap())
        val query = args["query"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Falta 'query'")
        val variables = (args["variables"] as? JsonObject) ?: JsonObject(emptyMap())

        val payload = buildJsonObject {
            put("query", JsonPrimitive(query))
            put("variables", variables)
        }
        val token = settings.getGithubToken()
        val request = Request.Builder()
            .url("https://api.github.com/graphql")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw HttpStatusException(response.code, text.take(2000))
            return text
        }
    }

    private fun errorJson(message: String): String = buildJsonObject {
        put("error", JsonPrimitive(message))
    }.toString()

    companion object {
        private val EMPTY_JSON_BODY = "{}".toRequestBody("application/json".toMediaType())
    }
}
